package com.checkout.payment.gateway.infrastructure.acquiring;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClientResponseException;

/**
 * Circuit breaker guarding calls to the acquiring bank.
 *
 * <p>The point is to protect this application rather than the bank. Without a breaker, every
 * request to a dead acquirer holds a servlet thread until the read timeout expires; under load the
 * pool drains and the payment retrieval endpoint - which never touches the bank - stops answering
 * too. The breaker turns a slow cascade into an immediate failure.
 *
 * <p>There is no fallback on purpose: no plausible answer can be invented for a payment.
 * An open circuit means the merchant is told the payment could not be attempted, which is the truth
 * and is safe to retry from their side.
 */
@Configuration
public class AcquiringBankResilienceConfig {

  private static final Logger log = LoggerFactory.getLogger(AcquiringBankResilienceConfig.class);

  static final String CIRCUIT_BREAKER_NAME = "acquiring-bank";

  @Bean
  public CircuitBreakerRegistry acquiringBankCircuitBreakerRegistry() {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        // Count-based rather than time-based: payment volume is bursty, and a time
        // window can trip on two failures during a quiet minute.
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(20)
        // Never open on a handful of calls; a cold start with three unlucky requests
        // would otherwise block every payment that follows.
        .minimumNumberOfCalls(10)
        .failureRateThreshold(50.0f)
        // Long enough for a restart or failover to finish, short enough that a brief
        // blip does not cost minutes of revenue.
        .waitDurationInOpenState(Duration.ofSeconds(30))
        .permittedNumberOfCallsInHalfOpenState(3)
        // Without this the circuit only leaves the open state when a call arrives,
        // which is fragile for low-traffic periods.
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        .recordException(AcquiringBankResilienceConfig::isOutage)
        .build();

    return CircuitBreakerRegistry.of(config);
  }

  @Bean
  public CircuitBreaker acquiringBankCircuitBreaker(CircuitBreakerRegistry registry) {
    CircuitBreaker circuitBreaker = registry.circuitBreaker(CIRCUIT_BREAKER_NAME);

    // State changes are the first thing anyone asks about during an incident.
    circuitBreaker.getEventPublisher().onStateTransition(event ->
        log.warn("Acquiring bank circuit moved from {} to {}",
            event.getStateTransition().getFromState(),
            event.getStateTransition().getToState()));

    return circuitBreaker;
  }

  /**
   * Publishes circuit state and call counts as metrics.
   *
   * <p>Spring Boot applies every MeterBinder bean to the meter registry, so exposing the
   * binder is enough - no production class has to know that metrics exist. This is why the registry
   * above is a bean rather than a local variable.
   */
  @Bean
  public MeterBinder acquiringBankCircuitBreakerMetrics(CircuitBreakerRegistry registry) {
    return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
  }

  /**
   * Decides which failures count towards opening the circuit.
   *
   * <p>A 4xx means the bank understood us and refused the request - our payload was wrong.
   * That is a defect in this gateway, not an outage, and counting it would let a single malformed
   * card open the circuit and block healthy payments for every other merchant. Everything else -
   * 5xx, connection refused, timeouts - is genuine unavailability.
   */
  private static boolean isOutage(Throwable throwable) {
    Throwable cause = throwable.getCause();
    if (cause instanceof RestClientResponseException response) {
      return !response.getStatusCode().is4xxClientError();
    }
    return true;
  }
}