package com.checkout.payment.gateway.interfaces.payment;

import static com.checkout.payment.gateway.testsupport.PaymentFixture.aDeclinedPayment;
import static com.checkout.payment.gateway.testsupport.PaymentFixture.aPayment;
import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.aValidRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.PaymentNotFoundException;
import com.checkout.payment.gateway.application.payment.PaymentService;
import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;
import com.checkout.payment.gateway.domain.payment.Payment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the HTTP contract: status codes, headers, and the shape of both error bodies.
 *
 * <p>The service is mocked, so nothing here depends on the bank or on storage. What is being
 * tested is the translation - what a merchant sees for each outcome the gateway can produce.
 */
@WebMvcTest(PaymentsController.class)
@Import(PaymentsControllerTest.FrozenClockConfiguration.class)
@DisplayName("PaymentsController")
class PaymentsControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private ObjectMapper objectMapper;

  @MockitoBean
  private PaymentService paymentService;

  /**
   * The web slice does not load ClockConfig, and ExpiryDateValidator cannot be built without a
   * Clock. Freezing it also keeps the expiry cases below deterministic.
   */
  @TestConfiguration
  static class FrozenClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Nested
  @DisplayName("processing a payment")
  class Processing {

    @Test
    @DisplayName("answers 201 with the payment and a Location header")
    void returnsCreatedForAnAuthorizedPayment() throws Exception {
      Payment payment = aPayment().build();
      when(paymentService.process(any())).thenReturn(payment);

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(aValidRequest().build())))
          .andExpect(status().isCreated())
          .andExpect(header().string("Location", "/payments/" + payment.id()))
          .andExpect(jsonPath("$.id").value(payment.id().toString()))
          .andExpect(jsonPath("$.status").value("Authorized"))
          .andExpect(jsonPath("$.last_four_card_digits").value("8877"))
          .andExpect(jsonPath("$.currency").value("GBP"))
          .andExpect(jsonPath("$.amount").value(100));
    }

    /**
     * A decline is still a created resource: it has an id and can be retrieved for reconciliation.
     * Answering 200 or 402 here would tell the merchant something different from what the
     * requirements describe.
     */
    @Test
    @DisplayName("answers 201 for a declined payment too")
    void returnsCreatedForADeclinedPayment() throws Exception {
      when(paymentService.process(any())).thenReturn(aDeclinedPayment().build());

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(aValidRequest().build())))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("Declined"));
    }

    @Test
    @DisplayName("passes the request through to the service as a command")
    void mapsTheRequestOntoACommand() throws Exception {
      when(paymentService.process(any())).thenReturn(aPayment().build());

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  aValidRequest()
                      .withCardNumber("2222405343248877")
                      .withCurrency("USD")
                      .withAmount(1050)
                      .build())))
          .andExpect(status().isCreated());

      ArgumentCaptor<ProcessPaymentCommand> command =
          ArgumentCaptor.forClass(ProcessPaymentCommand.class);
      verify(paymentService).process(command.capture());

      assertThat(command.getValue().cardNumber()).isEqualTo("2222405343248877");
      assertThat(command.getValue().currency()).isEqualTo("USD");
      assertThat(command.getValue().amount()).isEqualTo(1050);
    }
  }

  @Nested
  @DisplayName("rejecting a request")
  class Rejecting {

    /**
     * The field name must match what the merchant sent. Reporting "cardNumber" for a field called
     * "card_number" sends them looking for something that is not in their payload.
     */
    @Test
    @DisplayName("answers 400 naming the offending field as the merchant spelled it")
    void returnsRejectedWithSnakeCaseFieldNames() throws Exception {
      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  aValidRequest()
                      .withCardNumber("123")
                      .build())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value("Rejected"))
          .andExpect(jsonPath("$.errors[*].field").value(hasItem("card_number")));
    }

    @Test
    @DisplayName("reports every invalid field at once")
    void reportsAllInvalidFields() throws Exception {
      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  aValidRequest()
                      .withCardNumber("123")
                      .withCvv("1")
                      .withCurrency("JPY")
                      .build())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    @DisplayName("answers 400 for a card that has already expired")
    void rejectsAnExpiredCard() throws Exception {
      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(
                  aValidRequest()
                      .withExpiry(5, 2026)
                      .build())))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value("Rejected"));
    }

    /**
     * A fractional amount must be refused, not rounded. Jackson truncates a float into an int by
     * default, so "amount": 10.50 would become 10 - and on a field carrying minor currency units
     * that is the wrong amount charged, returned with a 201 saying it worked. A merchant asking for
     * GBP 10.50 would take 10 pence and be told it succeeded.
     */
    @Test
    @DisplayName("answers 400 for a fractional amount rather than truncating it")
    void refusesAFractionalAmount() throws Exception {
      String bodyWithFractionalAmount = """
          {
            "card_number": "2222405343248877",
            "expiry_month": 12,
            "expiry_year": 2099,
            "currency": "GBP",
            "amount": 10.50,
            "cvv": "123"
          }
          """;

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(bodyWithFractionalAmount))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value("Rejected"));

      verifyNoInteractions(paymentService);
    }

    /**
     * Jackson's parse errors quote the offending payload. Echoing that back would put a card number
     * into a response body and into every access log along the way.
     */
    @Test
    @DisplayName("answers 400 for a malformed body without echoing the card number")
    void doesNotLeakTheCardNumberOnAParseError() throws Exception {
      String bodyWithWrongType = """
          {
            "card_number": "2222405343248877",
            "expiry_month": 12,
            "expiry_year": 2099,
            "currency": "GBP",
            "amount": "not-a-number",
            "cvv": "123"
          }
          """;

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(bodyWithWrongType))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.status").value("Rejected"))
          .andExpect(content().string(not(containsString("2222405343248877"))));
    }
  }

  @Nested
  @DisplayName("retrieving a payment")
  class Retrieving {

    @Test
    @DisplayName("answers 200 with the stored payment")
    void returnsAKnownPayment() throws Exception {
      Payment payment = aPayment().build();
      when(paymentService.findById(payment.id())).thenReturn(payment);

      mockMvc.perform(get("/payments/{id}", payment.id()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(payment.id().toString()))
          .andExpect(jsonPath("$.status").value("Authorized"))
          .andExpect(jsonPath("$.last_four_card_digits").value("8877"));
    }

    @Test
    @DisplayName("answers 404 as a problem detail for an unknown id")
    void returnsNotFoundForAnUnknownId() throws Exception {
      UUID unknownId = UUID.randomUUID();
      when(paymentService.findById(unknownId)).thenThrow(new PaymentNotFoundException(unknownId));

      mockMvc.perform(get("/payments/{id}", unknownId))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.status").value(404))
          .andExpect(jsonPath("$.title").value("Payment not found"));
    }

    /**
     * An id that is not a UUID is not a missing payment, it is a malformed request. Spring rejects
     * it before the controller runs, which is the behaviour we want.
     */
    @Test
    @DisplayName("answers 400 for an id that is not a UUID")
    void rejectsAMalformedId() throws Exception {
      mockMvc.perform(get("/payments/{id}", "not-a-uuid"))
          .andExpect(status().isBadRequest());
    }
  }

  @Nested
  @DisplayName("when the bank is unreachable")
  class BankUnavailable {

    /**
     * 502 rather than 503: this gateway is healthy, an upstream dependency is not. A 503 would tell
     * the merchant to back off from us when the right action is to retry later.
     */
    @Test
    @DisplayName("answers 502 as a problem detail")
    void returnsBadGateway() throws Exception {
      when(paymentService.process(any()))
          .thenThrow(new AcquiringBankUnavailableException("bank is down"));

      mockMvc.perform(post("/payments")
              .contentType(MediaType.APPLICATION_JSON)
              .content(objectMapper.writeValueAsString(aValidRequest().build())))
          .andExpect(status().isBadGateway())
          .andExpect(jsonPath("$.status").value(502))
          .andExpect(jsonPath("$.title").value("Acquiring bank unavailable"));
    }
  }
}