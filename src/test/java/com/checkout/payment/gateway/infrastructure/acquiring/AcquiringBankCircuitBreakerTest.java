package com.checkout.payment.gateway.infrastructure.acquiring;

import static com.checkout.payment.gateway.testsupport.ProcessPaymentCommandFixture.aCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * Drives the real production circuit breaker configuration through the adapter.
 *
 * <p>Separated from AcquiringBankClientTest because a breaker is a state machine with
 * memory: a test that opens the circuit would change the meaning of every test that ran after it.
 * Here the state is the subject, so each test builds its own registry and starts from a closed
 * circuit.
 */
@DisplayName("Acquiring bank circuit breaker")
class AcquiringBankCircuitBreakerTest {

  private static final String BANK_URL = "http://acquiring-bank.test/payments";

  /**
   * Matches minimumNumberOfCalls in the production configuration. Below this the circuit cannot
   * open at all, which is deliberate: a handful of unlucky calls at start-up must not block every
   * payment that follows.
   */
  private static final int CALLS_NEEDED_TO_OPEN = 10;

  private MockRestServiceServer bank;
  private AcquiringBankClient client;
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    AcquiringBankResilienceConfig resilienceConfig = new AcquiringBankResilienceConfig();
    CircuitBreakerRegistry registry = resilienceConfig.acquiringBankCircuitBreakerRegistry();
    circuitBreaker = resilienceConfig.acquiringBankCircuitBreaker(registry);

    RestClient.Builder builder = RestClient.builder().baseUrl("http://acquiring-bank.test");
    bank = MockRestServiceServer.bindTo(builder).build();

    client = new AcquiringBankClient(builder.build(), circuitBreaker);
  }

  @Test
  @DisplayName("starts closed, so a healthy bank is never blocked")
  void startsClosed() {
    assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
  }

  @Test
  @DisplayName("opens after repeated 5xx responses")
  void opensWhenTheBankIsDown() {
    bank.expect(ExpectedCount.times(CALLS_NEEDED_TO_OPEN), requestTo(BANK_URL))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    authorizeRepeatedly(CALLS_NEEDED_TO_OPEN);

    assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);
  }

  /**
   * The most important assertion in the project's resilience story. A 4xx means the bank understood
   * the request and refused the payload - a defect on our side. Counting it as an outage would let
   * one malformed card open the circuit and block healthy payments for every other merchant.
   */
  @Test
  @DisplayName("stays closed no matter how many 4xx responses arrive")
  void ignoresOurOwnMistakes() {
    bank.expect(ExpectedCount.times(CALLS_NEEDED_TO_OPEN * 2), requestTo(BANK_URL))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    authorizeRepeatedly(CALLS_NEEDED_TO_OPEN * 2);

    assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
  }

  /**
   * The point of the breaker is not to fail differently, it is to stop spending threads on a bank
   * that will not answer. Once open, the request must never leave the process.
   */
  @Test
  @DisplayName("fails fast once open, without calling the bank again")
  void stopsCallingTheBankOnceOpen() {
    bank.expect(ExpectedCount.times(CALLS_NEEDED_TO_OPEN), requestTo(BANK_URL))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

    authorizeRepeatedly(CALLS_NEEDED_TO_OPEN);

    assertThatThrownBy(() -> client.authorize(aCommand().build()))
        .isInstanceOf(AcquiringBankUnavailableException.class)
        .hasMessageContaining("circuit is open");

    // Guards the opposite failure: if the circuit opened earlier than configured, some of
    // the ten stubs would go unused and this is what would catch it. An extra call would not
    // reach here at all - the mock server rejects unexpected requests as they happen.
    bank.verify();
  }

  /**
   * An outage that ends must not leave the gateway permanently refusing payments. The transition is
   * triggered by hand here rather than waiting out waitDurationInOpenState, which would make this
   * test sleep for half a minute.
   */
  @Test
  @DisplayName("closes again once the bank starts answering")
  void recoversWhenTheBankReturns() {
    bank.expect(ExpectedCount.times(CALLS_NEEDED_TO_OPEN), requestTo(BANK_URL))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    bank.expect(ExpectedCount.times(3), requestTo(BANK_URL))
        .andRespond(authorizedResponse());

    authorizeRepeatedly(CALLS_NEEDED_TO_OPEN);
    assertThat(circuitBreaker.getState()).isEqualTo(State.OPEN);

    circuitBreaker.transitionToHalfOpenState();
    authorizeRepeatedly(3);

    assertThat(circuitBreaker.getState()).isEqualTo(State.CLOSED);
  }

  /**
   * Failures are the point of most of these tests, so they are swallowed here. Asserting on each
   * one would repeat what AcquiringBankClientTest already covers and bury the circuit state, which
   * is what this class is about.
   */
  private void authorizeRepeatedly(int times) {
    for (int attempt = 0; attempt < times; attempt++) {
      try {
        client.authorize(aCommand().build());
      } catch (AcquiringBankUnavailableException expected) {
        // Intentionally ignored: the circuit state is what is being observed.
      }
    }
  }

  private static ResponseCreator authorizedResponse() {
    return withSuccess("""
        {"authorized": true, "authorization_code": "code-1"}
        """, MediaType.APPLICATION_JSON);
  }
}