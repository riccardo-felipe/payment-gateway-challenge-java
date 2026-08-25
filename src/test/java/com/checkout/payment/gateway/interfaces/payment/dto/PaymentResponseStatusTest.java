package com.checkout.payment.gateway.interfaces.payment.dto;

import static com.checkout.payment.gateway.testsupport.PaymentResponseFixture.aPaymentResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the exact spelling the API promises.
 *
 * <p>"Authorized" with a capital A is part of the published contract, not a formatting
 * preference: a merchant branching on that string breaks the day someone lower-cases it. The enum
 * constant names cannot be relied on for this, since Jackson would otherwise serialise them as
 * AUTHORIZED.
 */
@DisplayName("PaymentResponseStatus")
class PaymentResponseStatusTest {

  // Jackson 3 mappers are immutable and built rather than constructed and then mutated.
  private final JsonMapper objectMapper = JsonMapper.builder().build();

  @Nested
  @DisplayName("on the wire")
  class Serialisation {

    @ParameterizedTest(name = "{0} is written as {1}")
    @CsvSource({
        "AUTHORIZED, '\"Authorized\"'",
        "DECLINED, '\"Declined\"'",
        "REJECTED, '\"Rejected\"'"
    })
    void writesTheContractSpelling(PaymentResponseStatus status, String expectedJson) {
      assertThat(objectMapper.writeValueAsString(status)).isEqualTo(expectedJson);
    }

    /**
     * Serialisation and deserialisation must agree. Without the @JsonCreator this direction fails,
     * and the failure only shows up in a test that parses a response body - long after the code
     * looks finished.
     */
    @ParameterizedTest(name = "{0} is read back")
    @ValueSource(strings = {"Authorized", "Declined", "Rejected"})
    void readsBackWhatItWrote(String json) {
      PaymentResponseStatus status =
          objectMapper.readValue("\"" + json + "\"", PaymentResponseStatus.class);

      assertThat(objectMapper.writeValueAsString(status)).isEqualTo("\"" + json + "\"");
    }
  }

  @Nested
  @DisplayName("parsing a value")
  class Parsing {

    @ParameterizedTest(name = "{0} maps to {1}")
    @CsvSource({
        "Authorized, AUTHORIZED",
        "Declined, DECLINED",
        "Rejected, REJECTED"
    })
    void acceptsTheContractSpelling(String value, PaymentResponseStatus expected) {
      assertThat(PaymentResponseStatus.fromValue(value)).isEqualTo(expected);
    }

    /**
     * Matching is deliberately exact. Quietly accepting "authorized" would let a client drift from
     * the agreed spelling and never be told, until the day something downstream compares strings
     * and stops matching.
     */
    @ParameterizedTest(name = "{0} is refused")
    @ValueSource(strings = {"authorized", "AUTHORIZED", "Auth", "Captured", ""})
    void refusesAnythingElse(String value) {
      assertThatThrownBy(() -> PaymentResponseStatus.fromValue(value))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(value);
    }
  }

  @Nested
  @DisplayName("inside a response body")
  class WithinAPayload {

    /**
     * The enum is only ever seen through a DTO, so it is worth proving that the spelling survives
     * the surrounding record and its naming strategy rather than testing the enum in isolation and
     * assuming.
     */
    @Test
    @DisplayName("keeps its spelling and sits under a snake_case key")
    void serialisesWithinAPaymentResponse() {
      PostPaymentResponse response = aPaymentResponse()
          .withStatus(PaymentResponseStatus.DECLINED)
          .withLastFourCardDigits("8877")
          .withExpiry(12, 2099)
          .build();

      String json = objectMapper.writeValueAsString(response);

      assertThat(json)
          .contains("\"status\":\"Declined\"")
          .contains("\"last_four_card_digits\":\"8877\"")
          .contains("\"expiry_month\":12");
    }
  }
}