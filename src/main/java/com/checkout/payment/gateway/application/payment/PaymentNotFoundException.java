package com.checkout.payment.gateway.application.payment;

import java.util.UUID;

/**
 * Raised when no payment exists for the requested id.
 *
 * <p>Sits next to the use case, like the acquiring bank failure, so the HTTP layer can
 * translate it without importing infrastructure.
 *
 * <p>An exception rather than an Optional return, chosen for the sake of the error contract:
 * routing every failure through the exception handler keeps error bodies consistent, which matters
 * more to an integrating merchant than avoiding an exception for an expected miss.
 */
public class PaymentNotFoundException extends RuntimeException {

  public PaymentNotFoundException(UUID id) {
    super("No payment found with id " + id);
  }
}