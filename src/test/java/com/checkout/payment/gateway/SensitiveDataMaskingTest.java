package com.checkout.payment.gateway;

import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.aValidRequest;
import static com.checkout.payment.gateway.testsupport.ProcessPaymentCommandFixture.aCommand;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;
import com.checkout.payment.gateway.infrastructure.acquiring.AcquiringBankMapper;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankRequest;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One invariant, checked on every type that carries a card number: no object may reveal a PAN or a
 * CVV through toString.
 *
 * <p>Records generate a toString that prints every component. Three types here override it,
 * and the override is easy to lose - regenerating a record from the IDE silently removes it, and
 * the code keeps compiling. The consequence is not a failed test but card data in production logs
 * and stack traces, which is a reportable incident rather than a bug.
 *
 * <p>Deliberately gathered in one class instead of split across three packages. Read as a
 * list, the omission of a fourth carrier becomes obvious; scattered, nobody notices that the new
 * type was never added.
 */
@DisplayName("Sensitive data never leaks through toString")
class SensitiveDataMaskingTest {

  private static final String CARD_NUMBER = "2222405343248877";
  private static final String CVV = "987";

  @Test
  @DisplayName("the inbound request keeps the card number and cvv hidden")
  void postPaymentRequestIsMasked() {
    PostPaymentRequest request = aValidRequest()
        .withCardNumber(CARD_NUMBER)
        .withCvv(CVV)
        .build();

    assertThat(request.toString())
        .doesNotContain(CARD_NUMBER)
        .doesNotContain(CVV);
  }

  @Test
  @DisplayName("the command passed to the service keeps them hidden")
  void processPaymentCommandIsMasked() {
    ProcessPaymentCommand command = aCommand()
        .withCardNumber(CARD_NUMBER)
        .withCvv(CVV)
        .build();

    assertThat(command.toString())
        .doesNotContain(CARD_NUMBER)
        .doesNotContain(CVV);
  }

  @Test
  @DisplayName("the request sent to the bank keeps them hidden")
  void acquiringBankRequestIsMasked() {
    AcquiringBankRequest request = AcquiringBankMapper.toBankRequest(
        aCommand()
            .withCardNumber(CARD_NUMBER)
            .withCvv(CVV)
            .build());

    assertThat(request.toString())
        .doesNotContain(CARD_NUMBER)
        .doesNotContain(CVV);
  }

  /**
   * Masking must not turn toString into something useless. A log line that identifies nothing is
   * the failure mode people "fix" by printing the whole object again, which is how the card number
   * comes back.
   */
  @Test
  @DisplayName("what remains is still enough to diagnose a problem")
  void maskingKeepsTheHarmlessDetails() {
    ProcessPaymentCommand command = aCommand()
        .withCardNumber(CARD_NUMBER)
        .withCvv(CVV)
        .withCurrency("USD")
        .withAmount(1050)
        .build();

    assertThat(command.toString())
        .contains("USD")
        .contains("1050");
  }

  /**
   * The last four digits are the one fragment that may be shown, and they are what the merchant
   * reconciles against. Confirming they survive stops someone from "fixing" a masking failure by
   * removing the field.
   */
  @Test
  @DisplayName("the last four digits are still available where they are allowed")
  void lastFourDigitsRemainAccessible() {
    ProcessPaymentCommand command = aCommand()
        .withCardNumber(CARD_NUMBER)
        .build();

    assertThat(command.lastFourCardDigits()).isEqualTo("8877");
  }
}