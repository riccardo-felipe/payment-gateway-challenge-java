package com.checkout.payment.gateway.application.payment;

/**
 * Input of the "process a payment" use case.
 *
 * <p>This is the application's own vocabulary, intentionally free of Jakarta and Jackson
 * annotations: by the time a command exists the request has already been validated at the HTTP
 * boundary, so the inner layers never depend on how the data arrived.
 *
 * <p>Carries the full PAN and the CVV because the acquiring bank needs them, but toString
 * is overridden so neither can leak into logs or stack traces.
 */
public record ProcessPaymentCommand(
    String cardNumber,
    int expiryMonth,
    int expiryYear,
    String currency,
    int amount,
    String cvv
) {

  /**
   * Last four digits of the PAN, the only fragment that may be stored or returned.
   */
  public String lastFourCardDigits() {
    return cardNumber.substring(cardNumber.length() - 4);
  }

  @Override
  public String toString() {
    return "ProcessPaymentCommand[cardNumber=****, expiryMonth=%s, expiryYear=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(expiryMonth, expiryYear, currency, amount);
  }
}