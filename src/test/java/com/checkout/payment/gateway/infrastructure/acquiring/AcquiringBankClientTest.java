package com.checkout.payment.gateway.infrastructure.acquiring;

import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.DECLINED_CARD;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.UNAVAILABLE_CARD;
import static com.checkout.payment.gateway.testsupport.ProcessPaymentCommandFixture.aCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.AuthorizationResult;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Authorized;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Declined;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Exercises the adapter against a stubbed bank, covering the four outcomes the simulator documents
 * plus the ones it does not: an empty body, and a client error of our own making.
 *
 * <p>MockRestServiceServer replaces the request factory, so nothing here opens a socket.
 * That makes these tests fast and deterministic, and it is also why real timeouts and connection
 * failures are left to the end-to-end test.
 */
@DisplayName("AcquiringBankClient")
class AcquiringBankClientTest {

  private MockRestServiceServer bank;
  private AcquiringBankClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://acquiring-bank.test");
    bank = MockRestServiceServer.bindTo(builder).build();

    client = new AcquiringBankClient(builder.build(), neverOpeningCircuitBreaker());
  }

  /**
   * The breaker is real but configured never to trip, so a test that stubs several failures still
   * reaches the adapter every time. Circuit behaviour has its own test, where the state machine is
   * the subject rather than a source of interference.
   */
  private static CircuitBreaker neverOpeningCircuitBreaker() {
    CircuitBreakerConfig config = CircuitBreakerConfig.custom()
        .minimumNumberOfCalls(Integer.MAX_VALUE)
        .build();

    return CircuitBreaker.of("test-never-opens", config);
  }

  @Nested
  @DisplayName("what it sends")
  class Request {

    /**
     * The whole body is asserted at once because every field here is part of a contract we do not
     * control. A renamed key or a mis-formatted date fails silently at runtime, with the bank
     * answering 400 and no clue as to which field was wrong.
     */
    @Test
    @DisplayName("posts the payment in the shape the bank expects")
    void sendsTheBankItsOwnFormat() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andExpect(method(HttpMethod.POST))
          .andExpect(content().json("""
              {
                "card_number": "2222405343248877",
                "expiry_date": "04/2030",
                "currency": "USD",
                "amount": 1050,
                "cvv": "456"
              }
              """))
          .andRespond(authorizedResponse("code-1"));

      client.authorize(
          aCommand()
              .withCardNumber("2222405343248877")
              .withExpiry(4, 2030)
              .withCurrency("USD")
              .withAmount(1050)
              .withCvv("456")
              .build()
      );

      bank.verify();
    }
  }

  @Nested
  @DisplayName("what it makes of the answer")
  class Response {

    @Test
    @DisplayName("reads an approval, keeping the authorization code")
    void mapsAnApproval() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andRespond(authorizedResponse("0bb07405-6d44-4b50-a14f-7ae0beff13ad"));

      AuthorizationResult result = client.authorize(
          aCommand()
              .withCardNumber(AUTHORIZED_CARD)
              .build()
      );

      assertThat(result).isEqualTo(new Authorized("0bb07405-6d44-4b50-a14f-7ae0beff13ad"));
    }

    @Test
    @DisplayName("reads a refusal")
    void mapsARefusal() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andRespond(withSuccess("""
              {"authorized": false}
              """, MediaType.APPLICATION_JSON));

      AuthorizationResult result = client.authorize(
          aCommand()
              .withCardNumber(DECLINED_CARD)
              .build()
      );

      assertThat(result).isEqualTo(new Declined());
    }
  }

  @Nested
  @DisplayName("when things go wrong")
  class Failures {

    /**
     * The simulator answers 503 for a card ending in zero. There is no verdict to report, so this
     * must not be mistaken for a decline - the merchant would record a refusal that never
     * happened.
     */
    @Test
    @DisplayName("turns a 503 into unavailability rather than a decline")
    void reportsServiceUnavailable() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

      assertThatThrownBy(() -> client.authorize(
          aCommand()
              .withCardNumber(UNAVAILABLE_CARD)
              .build()
      ))
          .isInstanceOf(AcquiringBankUnavailableException.class)
          .hasMessageContaining("503");
    }

    /**
     * A 400 means the bank understood us and refused the payload - a defect on our side. It still
     * surfaces as unavailability because either way there is no verdict, but the cause is preserved
     * so the circuit breaker can tell the two apart.
     */
    @Test
    @DisplayName("turns a 400 into unavailability, keeping the cause for the breaker")
    void reportsClientError() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andRespond(withStatus(HttpStatus.BAD_REQUEST));

      assertThatThrownBy(() -> client.authorize(aCommand().build()))
          .isInstanceOf(AcquiringBankUnavailableException.class)
          .hasMessageContaining("400")
          .hasCauseInstanceOf(RestClientResponseException.class);
    }

    /**
     * A 200 with nothing in it would otherwise deserialise to null and, read carelessly, become a
     * decline. A broken bank must not turn into a business decision.
     */
    @Test
    @DisplayName("refuses an empty body even when the status says success")
    void reportsAnEmptyBody() {
      bank.expect(requestTo("http://acquiring-bank.test/payments"))
          .andRespond(withSuccess());

      assertThatThrownBy(() -> client.authorize(aCommand().build()))
          .isInstanceOf(AcquiringBankUnavailableException.class)
          .hasMessageContaining("no body");
    }
  }

  private static ResponseCreator authorizedResponse(String authorizationCode) {
    return withSuccess("""
        {"authorized": true, "authorization_code": "%s"}
        """.formatted(authorizationCode), MediaType.APPLICATION_JSON);
  }
}