package com.checkout.payment.gateway.interfaces.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * 201 Created body for a payment that reached the acquiring bank. Status here is always Authorized
 * or Declined; REJECTED cannot arrive from a Payment.
 *
 * <p>Names are pinned per field rather than derived from a naming strategy, so the published
 * contract does not depend on a setting elsewhere - and so the documentation generator, which reads
 * Jackson annotations, sees the names merchants actually get.
 */
@Schema(description = "A payment that reached the acquiring bank, whatever its verdict")
public record PostPaymentResponse(

    @Schema(description = "Use this to retrieve the payment later.",
        example = "6a1b3c9e-0000-4000-8000-000000000001")
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

    // Named to make clear this is not a truncation of something longer that is stored: the
    // full number never leaves the acquirer call.
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