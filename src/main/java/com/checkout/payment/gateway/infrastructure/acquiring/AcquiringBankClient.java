package com.checkout.payment.gateway.infrastructure.acquiring;

import com.checkout.payment.gateway.application.payment.AcquiringBankGateway;
import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.AuthorizationResult;
import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankRequest;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP adapter implementing the acquiring bank port.
 *
 * <p>This is the only class allowed to know the acquirer's wire format. Everything crossing
 * its boundary is expressed in the application's own types, so replacing the simulator with a real
 * acquirer touches this file and its DTOs, nothing else.
 *
 * <p>Calls go through a circuit breaker. Deliberately no retry: authorization is not
 * idempotent without an idempotency key, and a retried read timeout can double-charge a cardholder
 * when the first attempt actually succeeded but the response was lost.
 */
@Component
public class AcquiringBankClient implements AcquiringBankGateway {

  private static final Logger log = LoggerFactory.getLogger(AcquiringBankClient.class);

  private final RestClient restClient;
  private final CircuitBreaker circuitBreaker;

  public AcquiringBankClient(
      @Qualifier("acquiringBankRestClient") RestClient restClient,
      CircuitBreaker acquiringBankCircuitBreaker
  ) {
    this.restClient = restClient;
    this.circuitBreaker = acquiringBankCircuitBreaker;
  }

  @Override
  public AuthorizationResult authorize(ProcessPaymentCommand command) {
    try {
      return circuitBreaker.executeSupplier(() -> sendToBank(command));
    } catch (CallNotPermittedException e) {
      // The circuit is open, so no call was made at all. Failing here costs a few
      // microseconds instead of a held thread and a full read timeout.
      log.warn("Acquiring bank circuit is open, skipping the call");
      throw new AcquiringBankUnavailableException("Acquiring bank circuit is open", e);
    }
  }

  private AuthorizationResult sendToBank(ProcessPaymentCommand command) {
    AcquiringBankRequest request = AcquiringBankMapper.toBankRequest(command);

    try {
      AcquiringBankResponse response = restClient.post()
          .uri("/payments")
          .contentType(MediaType.APPLICATION_JSON)
          .body(request)
          .retrieve()
          .body(AcquiringBankResponse.class);

      // A 200 with no body would otherwise be read as a declined payment, turning a
      // broken acquirer into a business decision the merchant would act on.
      if (response == null) {
        throw new AcquiringBankUnavailableException(
            "Acquiring bank returned a successful status with no body");
      }

      return AcquiringBankMapper.toAuthorizationResult(response);

    } catch (RestClientResponseException e) {
      // Kept as the cause so the breaker's recordException predicate can tell a 4xx
      // (our bug) from a 5xx (their outage) and only count the latter.
      log.error("Acquiring bank refused the request with status {}", e.getStatusCode());
      throw new AcquiringBankUnavailableException(
          "Acquiring bank responded with status " + e.getStatusCode(), e);

    } catch (RestClientException e) {
      // Connection refused, DNS failure or timeout. The exception is logged without
      // the request, which carries the PAN and CVV.
      log.error("Acquiring bank could not be reached", e);
      throw new AcquiringBankUnavailableException("Acquiring bank could not be reached", e);
    }
  }
}