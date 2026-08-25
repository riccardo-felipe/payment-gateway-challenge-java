package com.checkout.payment.gateway.infrastructure.acquiring;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.AuthorizationResult;
import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankRequest;
import com.checkout.payment.gateway.infrastructure.acquiring.dto.AcquiringBankResponse;

/**
 * Translation between the application's vocabulary and the acquirer's wire format.
 *
 * <p>Lives in infrastructure because everything it knows about - snake_case fields, the
 * MM/yyyy expiry string, a flat boolean plus nullable code - belongs to the vendor's protocol. Once
 * the HTTP client adapter grows, these can collapse into private methods on it; they are kept
 * public here so the translation can be unit tested without standing up the simulator.
 */
public final class AcquiringBankMapper {

  private AcquiringBankMapper() {
  }

  public static AcquiringBankRequest toBankRequest(ProcessPaymentCommand command) {
    return new AcquiringBankRequest(
        command.cardNumber(),
        "%02d/%d".formatted(command.expiryMonth(), command.expiryYear()),
        command.currency(),
        command.amount(),
        command.cvv()
    );
  }

  /**
   * Collapses the bank's boolean-plus-nullable-code into a shape where the code only exists on the
   * branch that has one.
   *
   * <p>An approval with no code is a broken response, not an approval: the merchant would
   * have nothing to reconcile against. It is reported as unavailability rather than allowed to
   * become a NullPointerException deeper in the call stack.
   */
  public static AuthorizationResult toAuthorizationResult(AcquiringBankResponse response) {
    if (!response.authorized()) {
      return new AuthorizationResult.Declined();
    }

    if (response.authorizationCode() == null || response.authorizationCode().isBlank()) {
      throw new AcquiringBankUnavailableException(
          "Acquiring bank authorized the payment without an authorization code");
    }

    return new AuthorizationResult.Authorized(response.authorizationCode());
  }
}