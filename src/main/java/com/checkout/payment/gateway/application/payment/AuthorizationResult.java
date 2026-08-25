package com.checkout.payment.gateway.application.payment;

import java.util.Objects;

/**
 * Outcome of an authorization attempt, expressed in the application's own terms.
 *
 * <p>This is the anti-corruption boundary: the acquirer's wire format is translated into
 * this type inside the infrastructure adapter, so swapping the acquirer changes nothing above this
 * line.
 *
 * <p>Sealed rather than a boolean-plus-code pair so that illegal states cannot be built.
 * The previous shape allowed an authorization with no code, and a decline that carried one; neither
 * means anything, and both would have had to be guarded against by hand. Here the code exists only
 * on the branch where it has meaning.
 *
 * <p>The second benefit is exhaustiveness: a switch over this type must cover every case,
 * so adding a third outcome - some acquirers report a "referred" verdict meaning the cardholder
 * must call their issuer - breaks the build at each place that has to decide what to do about it,
 * rather than silently falling into an else.
 *
 * <p>Deliberately not a failure vocabulary: an acquirer that could not be reached at all is
 * an AcquiringBankUnavailableException, because there is no verdict to represent.
 */
public sealed interface AuthorizationResult {

  /**
   * The bank approved the payment and issued a reference for it.
   *
   * @param authorizationCode the acquirer's reference, required - an approval without one would
   *                          leave the merchant unable to reconcile the transaction
   */
  record Authorized(String authorizationCode) implements AuthorizationResult {

    public Authorized {
      Objects.requireNonNull(authorizationCode,
          "an authorized result must carry an authorization code");
    }
  }

  /**
   * The bank refused the payment.
   *
   * <p>Carries no data: a refusal has nothing to reconcile. This is a business outcome the
   * merchant records, not an error - the request was well formed and the bank answered.
   */
  record Declined() implements AuthorizationResult {

  }
}