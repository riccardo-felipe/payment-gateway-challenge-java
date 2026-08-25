package com.checkout.payment.gateway.testsupport;

import com.checkout.payment.gateway.interfaces.payment.dto.PaymentResponseStatus;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentResponse;
import java.util.UUID;

/**
 * Builds response bodies for tests that need one to inspect.
 *
 * <p>Seven fields spelled out inline bury the two or three a given test actually cares
 * about; starting from a complete response and overriding only what matters makes the intent
 * readable without cross-checking against the assertions below it.
 *
 * <p>It also survives the contract growing: when a field is added to the record, this is
 * the only place that has to change.
 */
public final class PaymentResponseFixture {

  private UUID id = UUID.fromString("6a1b3c9e-0000-4000-8000-000000000001");
  private PaymentResponseStatus status = PaymentResponseStatus.AUTHORIZED;
  private String lastFourCardDigits = "8877";
  private int expiryMonth = 12;
  private int expiryYear = 2099;
  private String currency = "GBP";
  private int amount = 100;

  private PaymentResponseFixture() {
  }

  /**
   * The id is fixed rather than random so a failure message reads the same on every run, which
   * matters when the assertion is on serialised JSON.
   */
  public static PaymentResponseFixture aPaymentResponse() {
    return new PaymentResponseFixture();
  }

  public PaymentResponseFixture withId(UUID id) {
    this.id = id;
    return this;
  }

  public PaymentResponseFixture withStatus(PaymentResponseStatus status) {
    this.status = status;
    return this;
  }

  public PaymentResponseFixture withLastFourCardDigits(String lastFourCardDigits) {
    this.lastFourCardDigits = lastFourCardDigits;
    return this;
  }

  public PaymentResponseFixture withExpiry(int expiryMonth, int expiryYear) {
    this.expiryMonth = expiryMonth;
    this.expiryYear = expiryYear;
    return this;
  }

  public PaymentResponseFixture withCurrency(String currency) {
    this.currency = currency;
    return this;
  }

  public PaymentResponseFixture withAmount(int amount) {
    this.amount = amount;
    return this;
  }

  public PostPaymentResponse build() {
    return new PostPaymentResponse(
        id,
        status,
        lastFourCardDigits,
        expiryMonth,
        expiryYear,
        currency,
        amount
    );
  }
}