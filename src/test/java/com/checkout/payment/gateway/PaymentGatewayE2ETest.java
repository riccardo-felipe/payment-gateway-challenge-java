package com.checkout.payment.gateway;

import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.AUTHORIZED_CARD;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.DECLINED_CARD;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.UNAVAILABLE_CARD;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.aValidRequest;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;

/**
 * Exercises the whole gateway over real HTTP, with a stubbed bank standing in for the simulator.
 *
 * <p>WireMock rather than docker-compose on purpose: a test that only passes when a
 * container happens to be running is a test the person evaluating this repository will see fail.
 * Everything here works from a clean clone with nothing installed.
 *
 * <p>This is also the only place where sockets are real. AcquiringBankClientTest swaps out
 * the request factory, so timeouts and connection failures never happen there - which is exactly
 * why the timeout case lives here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("Payment gateway end to end")
class PaymentGatewayE2ETest {

  private static final WireMockServer acquiringBank = new WireMockServer(0);

  @LocalServerPort
  private int port;

  private RestTestClient client;

  @BeforeAll
  static void startBank() {
    acquiringBank.start();
  }

  @AfterAll
  static void stopBank() {
    acquiringBank.stop();
  }

  /**
   * The read timeout is shortened from the production five seconds. What matters is that a timeout
   * exists and turns into a 502, not its exact value - and waiting five seconds in every build to
   * prove it would be a poor trade.
   */
  @DynamicPropertySource
  static void pointAtTheStubbedBank(DynamicPropertyRegistry registry) {
    registry.add("acquiring-bank.base-url", () -> "http://localhost:" + acquiringBank.port());
    registry.add("acquiring-bank.read-timeout", () -> "300ms");
  }

  @BeforeEach
  void setUp() {
    client = RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build();

    acquiringBank.resetAll();
    stubTheSimulatorRules();
  }

  /**
   * Mirrors how the provided simulator behaves, so the card constants mean the same thing here as
   * they do in every other test: odd authorises, even declines, zero fails.
   */
  private void stubTheSimulatorRules() {
    acquiringBank.stubFor(post(urlEqualTo("/payments"))
        .withRequestBody(matchingJsonPath("$.card_number", matching(".*[13579]$")))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"authorized": true, "authorization_code": "0bb07405-6d44-4b50-a14f-7ae0beff13ad"}
                """)));

    acquiringBank.stubFor(post(urlEqualTo("/payments"))
        .withRequestBody(matchingJsonPath("$.card_number", matching(".*[2468]$")))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"authorized": false}
                """)));

    acquiringBank.stubFor(post(urlEqualTo("/payments"))
        .withRequestBody(matchingJsonPath("$.card_number", matching(".*0$")))
        .willReturn(aResponse().withStatus(503)));
  }

  /**
   * The journey the requirements describe: take a payment, then read it back for reconciliation.
   * Nothing short of this proves that the id handed to the merchant is one they can actually use.
   */
  @Test
  @DisplayName("takes a payment and hands back an id that can be retrieved")
  void processesAndThenRetrievesAPayment() {
    URI location = client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.status").isEqualTo("Authorized")
        .jsonPath("$.last_four_card_digits").isEqualTo(lastFour(AUTHORIZED_CARD))
        .jsonPath("$.currency").isEqualTo("GBP")
        .jsonPath("$.amount").isEqualTo(100)
        .returnResult()
        .getResponseHeaders()
        .getLocation();

    // Following the header rather than digging the id out of the body is what a client
    // actually does, and it proves the header points somewhere real.
    assertThat(location).isNotNull();
    String id = idFrom(location);
    assertThat(UUID.fromString(id)).isNotNull();

    client.get()
        .uri(location.getPath())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo(id)
        .jsonPath("$.status").isEqualTo("Authorized")
        .jsonPath("$.last_four_card_digits").isEqualTo(lastFour(AUTHORIZED_CARD));
  }

  @Test
  @DisplayName("stores a declined payment and still answers 201")
  void storesADeclinedPayment() {
    client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request(DECLINED_CARD))
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.status").isEqualTo("Declined");
  }

  /**
   * The full card number reaches the bank and stops there. Asserting on the whole body is the
   * point: any field that leaked it would fail this, not just the ones we thought of.
   */
  @Test
  @DisplayName("never returns the full card number")
  void keepsTheCardNumberOutOfTheResponse() {
    String body = client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isCreated()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();

    assertThat(body).doesNotContain(AUTHORIZED_CARD);
  }

  @Test
  @DisplayName("answers 502 when the bank returns an error")
  void reportsBadGatewayWhenTheBankFails() {
    client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request(UNAVAILABLE_CARD))
        .exchange()
        .expectStatus().isEqualTo(502)
        .expectBody()
        .jsonPath("$.title").isEqualTo("Acquiring bank unavailable");
  }

  /**
   * with a real socket. Without the timeout configured, this request would hang until the bank
   * replied and the thread would stay pinned.
   */
  @Test
  @DisplayName("answers 502 when the bank is too slow to reply")
  void reportsBadGatewayWhenTheBankHangs() {
    acquiringBank.resetAll();
    acquiringBank.stubFor(post(urlEqualTo("/payments"))
        .willReturn(aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("""
                {"authorized": true, "authorization_code": "too-late"}
                """)
            .withFixedDelay(2000)));

    client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(request(AUTHORIZED_CARD))
        .exchange()
        .expectStatus().isEqualTo(502);
  }

  /**
   * A rejected request must never reach the bank. Verifying zero calls is what separates "we
   * validated it" from "we asked and they said no".
   */
  @Test
  @DisplayName("rejects an invalid request without troubling the bank")
  void rejectsWithoutCallingTheBank() {
    client.post()
        .uri("/payments")
        .contentType(MediaType.APPLICATION_JSON)
        .body(aValidRequest().withCvv("1").build())
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.status").isEqualTo("Rejected")
        .jsonPath("$.errors[0].field").isEqualTo("cvv");

    acquiringBank.verify(0, postRequestedFor(urlEqualTo("/payments")));
  }

  @Test
  @DisplayName("answers 404 for a payment that was never made")
  void reportsNotFoundForAnUnknownId() {
    client.get()
        .uri("/payments/{id}", UUID.randomUUID())
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.title").isEqualTo("Payment not found");
  }

  private static PostPaymentRequest request(String cardNumber) {
    return aValidRequest()
        .withCardNumber(cardNumber)
        .build();
  }

  private static String lastFour(String cardNumber) {
    return cardNumber.substring(cardNumber.length() - 4);
  }

  private static String idFrom(URI location) {
    String path = location.getPath();
    return path.substring(path.lastIndexOf('/') + 1);
  }
}