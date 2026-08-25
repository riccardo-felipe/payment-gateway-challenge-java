package com.checkout.payment.gateway.domain.payment;

import java.util.UUID;

/**
 * Payment aggregate, persisted by the repository and returned on retrieval.
 *
 * <p>Deliberately holds neither the full PAN nor the CVV: only the last four digits
 * survive the call to the acquiring bank. The application service assembles this from the command
 * plus the authorization result, which keeps the domain free of any dependency on the outer
 * layers.
 */
public record Payment(
    UUID id,
    PaymentStatus status,
    String lastFourCardDigits,
    int expiryMonth,
    int expiryYear,
    String currency,
    int amount,
    String authorizationCode
) {

}