package com.checkout.payment.gateway.testsupport;

import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;

/**
 * Builds payment requests for tests, starting from one that is valid in every field.
 *
 * <p>The point is what each test does <em>not</em> say. Spelling out six fields in every
 * test buries the one that matters; starting from a valid request and overriding a single field
 * makes the intent readable at a glance - this request is fine <em>except</em> for the CVV.
 *
 * <p>It also keeps the definition of "valid" in one place. When a validation rule changes,
 * the tests that were never about that rule do not all have to be edited.
 */
public final class PaymentRequestFixture {

  /**
   * The simulator decides the outcome from the last digit of the card number, so these are not
   * interchangeable. Naming them by outcome stops someone from "tidying up" a number and silently
   * changing what a test asserts.
   */
  public static final String AUTHORIZED_CARD = "2222405343248877";  // odd  -> authorized
  public static final String DECLINED_CARD = "2222405343248112";    // even -> declined
  public static final String UNAVAILABLE_CARD = "2222405343248110"; // zero -> 503

  private String cardNumber = AUTHORIZED_CARD;
  private Integer expiryMonth = 12;
  // Far enough out that these fixtures will not start failing on their own. Tests about
  // expiry set this explicitly and freeze the clock, so nothing here depends on it.
  private Integer expiryYear = 2099;
  private String currency = "GBP";
  private Integer amount = 100;
  private String cvv = "123";

  private PaymentRequestFixture() {
  }

  public static PaymentRequestFixture aValidRequest() {
    return new PaymentRequestFixture();
  }

  public PaymentRequestFixture withCardNumber(String cardNumber) {
    this.cardNumber = cardNumber;
    return this;
  }

  public PaymentRequestFixture withExpiryMonth(Integer expiryMonth) {
    this.expiryMonth = expiryMonth;
    return this;
  }

  public PaymentRequestFixture withExpiryYear(Integer expiryYear) {
    this.expiryYear = expiryYear;
    return this;
  }

  public PaymentRequestFixture withExpiry(Integer expiryMonth, Integer expiryYear) {
    this.expiryMonth = expiryMonth;
    this.expiryYear = expiryYear;
    return this;
  }

  public PaymentRequestFixture withCurrency(String currency) {
    this.currency = currency;
    return this;
  }

  public PaymentRequestFixture withAmount(Integer amount) {
    this.amount = amount;
    return this;
  }

  public PaymentRequestFixture withCvv(String cvv) {
    this.cvv = cvv;
    return this;
  }

  public PostPaymentRequest build() {
    return new PostPaymentRequest(cardNumber, expiryMonth, expiryYear, currency, amount, cvv);
  }
}