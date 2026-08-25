package com.checkout.payment.gateway.interfaces.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * Body of GET /payments/{id}.
 *
 * <p>The shape is identical to PostPaymentResponse today, but the two contracts are kept
 * apart because they evolve independently: retrieval is the one that tends to grow reconciliation
 * fields such as created_at or authorization_code.
 */
@Schema(description = "A previously made payment, for reconciliation and reporting")
public record GetPaymentResponse(

    @Schema(example = "6a1b3c9e-0000-4000-8000-000000000001")
    @JsonProperty("id") UUID id,

    // Narrowed to the two verdicts a Payment can hold. Rejected exists on the enum but is
    // unreachable from here: it describes a request that never became a payment, so a client
    // generated from this schema should not have to handle it.
    @Schema(
        description = "The bank's verdict. Authorized means the money was reserved; Declined"
            + " means the bank refused. Both are payments that exist and can be retrieved.",
        allowableValues = {"Authorized", "Declined"},
        example = "Authorized",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("status") PaymentResponseStatus status,

    @Schema(description = "The only part of the card number this gateway keeps or returns.",
        example = "8877")
    @JsonProperty("last_four_card_digits") String lastFourCardDigits,

    @JsonProperty("expiry_month") int expiryMonth,

    @JsonProperty("expiry_year") int expiryYear,

    @JsonProperty("currency") String currency,

    @Schema(description = "Amount in the minor currency unit, as submitted.", example = "1050")
    @JsonProperty("amount") int amount
) {

}