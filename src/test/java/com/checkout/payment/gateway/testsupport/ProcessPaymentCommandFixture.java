package com.checkout.payment.gateway.testsupport;

import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;

/**
 * Builds commands for tests of the application and infrastructure layers.
 *
 * <p>Separate from PaymentRequestFixture on purpose. Building a command by running a
 * request through PaymentDtoMapper would make every service and adapter test depend on the HTTP
 * mapper, so a bug there would fail tests that have nothing to do with HTTP.
 *
 * <p>Card constants are reused from PaymentRequestFixture so both layers exercise the same
 * numbers and the same simulator outcomes.
 */
public final class ProcessPaymentCommandFixture {

  private String cardNumber = PaymentRequestFixture.AUTHORIZED_CARD;
  private int expiryMonth = 12;
  private int expiryYear = 2099;
  private String currency = "GBP";
  private int amount = 100;
  private String cvv = "123";

  private ProcessPaymentCommandFixture() {
  }

  public static ProcessPaymentCommandFixture aCommand() {
    return new ProcessPaymentCommandFixture();
  }

  public ProcessPaymentCommandFixture withCardNumber(String cardNumber) {
    this.cardNumber = cardNumber;
    return this;
  }

  public ProcessPaymentCommandFixture withExpiry(int expiryMonth, int expiryYear) {
    this.expiryMonth = expiryMonth;
    this.expiryYear = expiryYear;
    return this;
  }

  public ProcessPaymentCommandFixture withCurrency(String currency) {
    this.currency = currency;
    return this;
  }

  public ProcessPaymentCommandFixture withAmount(int amount) {
    this.amount = amount;
    return this;
  }

  public ProcessPaymentCommandFixture withCvv(String cvv) {
    this.cvv = cvv;
    return this;
  }

  public ProcessPaymentCommand build() {
    return new ProcessPaymentCommand(cardNumber, expiryMonth, expiryYear, currency, amount, cvv);
  }
}