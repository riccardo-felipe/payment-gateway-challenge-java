package com.checkout.payment.gateway.application.payment;

/**
 * Raised when the acquiring bank could not give a verdict on a payment.
 *
 * <p>This is neither Declined nor Rejected: the bank did not say no, and the merchant's
 * request was not malformed. Nothing was authorized and no Payment exists, so there is no status to
 * persist - which is why this is an exception rather than a third enum value.
 *
 * <p>Declared next to the port instead of in infrastructure so the dependency arrows keep
 * pointing inwards: the adapter throws it and the HTTP layer catches it, and neither has to know
 * about the other. Swapping the acquirer changes what causes this, never its type.
 *
 * <p>Unchecked because no caller between the adapter and the exception handler can do
 * anything useful with it; forcing a try/catch at every level would only add noise.
 */
public class AcquiringBankUnavailableException extends RuntimeException {

  public AcquiringBankUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  public AcquiringBankUnavailableException(String message) {
    super(message);
  }
}