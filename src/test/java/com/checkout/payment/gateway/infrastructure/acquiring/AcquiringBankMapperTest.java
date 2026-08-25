package com.checkout.payment.gateway.infrastructure.acquiring;

import static com.checkout.payment.gateway.testsupport.ProcessPaymentCommandFixture.aCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.AuthorizationResult;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Authorized;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Declined;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankRequest;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("AcquiringBankMapper")
class AcquiringBankMapperTest {

  @Nested
  @DisplayName("building the bank request")
  class ToBankRequest {

    /**
     * The simulator's contract is "04/2025", not "4/2025". A single-digit month without the leading
     * zero is the kind of mistake that passes code review and then fails only for cards expiring in
     * the first nine months of the year.
     */
    @ParameterizedTest(name = "month {0} of {1} becomes {2}")
    @CsvSource({
        "1, 2027, 01/2027",
        "4, 2025, 04/2025",
        "9, 2030, 09/2030",
        "10, 2028, 10/2028",
        "12, 2026, 12/2026"
    })
    @DisplayName("pads a single-digit month to two characters")
    void formatsExpiryDate(int month, int year, String expected) {
      AcquiringBankRequest request = AcquiringBankMapper.toBankRequest(
          aCommand().withExpiry(month, year).build());

      assertThat(request.expiryDate()).isEqualTo(expected);
    }

    @Test
    @DisplayName("passes the remaining fields through untouched")
    void copiesEveryOtherField() {
      AcquiringBankRequest request = AcquiringBankMapper.toBankRequest(
          aCommand()
              .withCardNumber("2222405343248877")
              .withCurrency("USD")
              .withAmount(1050)
              .withCvv("4321")
              .build());

      assertThat(request.cardNumber()).isEqualTo("2222405343248877");
      assertThat(request.currency()).isEqualTo("USD");
      assertThat(request.amount()).isEqualTo(1050);
      assertThat(request.cvv()).isEqualTo("4321");
    }
  }

  @Nested
  @DisplayName("reading the bank response")
  class ToAuthorizationResult {

    @Test
    @DisplayName("maps an approval to Authorized, carrying the code forward")
    void mapsApproval() {
      AuthorizationResult result = AcquiringBankMapper.toAuthorizationResult(
          new AcquiringBankResponse(true, "0bb07405-6d44-4b50-a14f-7ae0beff13ad"));

      assertThat(result).isEqualTo(new Authorized("0bb07405-6d44-4b50-a14f-7ae0beff13ad"));
    }

    /**
     * A decline arriving with an authorization code is a contradiction the old boolean-plus-code
     * shape allowed. Here the code has nowhere to go, which is the point of the sealed type.
     */
    @Test
    @DisplayName("maps a refusal to Declined and drops any code that came with it")
    void mapsRefusal() {
      AuthorizationResult result = AcquiringBankMapper.toAuthorizationResult(
          new AcquiringBankResponse(false, "should-not-survive"));

      assertThat(result).isEqualTo(new Declined());
    }

    /**
     * An approval the merchant cannot reconcile is worse than no approval: they would be told the
     * money was taken with no reference to prove it. Treated as a broken bank rather than allowed
     * through.
     */
    @ParameterizedTest(name = "authorization code = [{0}]")
    @CsvSource(nullValues = "null", value = {"null", "''", "'   '"})
    @DisplayName("refuses an approval that arrives without an authorization code")
    void rejectsApprovalWithoutCode(String authorizationCode) {
      assertThatThrownBy(() -> AcquiringBankMapper.toAuthorizationResult(
          new AcquiringBankResponse(true, authorizationCode)))
          .isInstanceOf(AcquiringBankUnavailableException.class)
          .hasMessageContaining("authorization code");
    }
  }
}