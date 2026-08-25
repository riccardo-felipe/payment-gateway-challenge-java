package com.checkout.payment.gateway.testsupport;

import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.domain.payment.PaymentStatus;
import java.util.UUID;

/**
 * Builds domain aggregates for tests that need a payment to already exist - retrieval paths,
 * mostly, where going through the whole authorization flow just to have something to read back
 * would obscure what is being tested.
 */
public final class PaymentFixture {

  private UUID id = UUID.randomUUID();
  private PaymentStatus status = PaymentStatus.AUTHORIZED;
  private String lastFourCardDigits = "8877";
  private int expiryMonth = 12;
  private int expiryYear = 2099;
  private String currency = "GBP";
  private int amount = 100;
  private String authorizationCode = "0bb07405-6d44-4b50-a14f-7ae0beff13ad";

  private PaymentFixture() {
  }

  public static PaymentFixture aPayment() {
    return new PaymentFixture();
  }

  /**
   * A declined payment carries no authorization code, so the two fields are set together. Offering
   * them separately would let a test build the contradiction the sealed AuthorizationResult exists
   * to prevent.
   */
  public static PaymentFixture aDeclinedPayment() {
    PaymentFixture fixture = new PaymentFixture();
    fixture.status = PaymentStatus.DECLINED;
    fixture.authorizationCode = null;
    return fixture;
  }

  public PaymentFixture withId(UUID id) {
    this.id = id;
    return this;
  }

  public PaymentFixture withLastFourCardDigits(String lastFourCardDigits) {
    this.lastFourCardDigits = lastFourCardDigits;
    return this;
  }

  public PaymentFixture withExpiry(int expiryMonth, int expiryYear) {
    this.expiryMonth = expiryMonth;
    this.expiryYear = expiryYear;
    return this;
  }

  public PaymentFixture withCurrency(String currency) {
    this.currency = currency;
    return this;
  }

  public PaymentFixture withAmount(int amount) {
    this.amount = amount;
    return this;
  }

  public Payment build() {
    return new Payment(
        id,
        status,
        lastFourCardDigits,
        expiryMonth,
        expiryYear,
        currency,
        amount,
        authorizationCode
    );
  }
}