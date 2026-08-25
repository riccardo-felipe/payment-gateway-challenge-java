package com.checkout.payment.gateway.interfaces.payment.dto;

import com.checkout.payment.gateway.interfaces.payment.validation.ValidExpiryDate;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Payment request submitted by the merchant.
 *
 * <p>Sensitive data (full PAN and CVV) enters here but is never returned or persisted. The
 * record's generated toString is overridden because it would otherwise print the card number in any
 * log line or stack trace that touches this object.
 *
 * <p>Field names are pinned one by one with @JsonProperty rather than derived from a naming
 * strategy. This is a contract we publish, so the same reasoning applies as to the acquirer's DTOs:
 * the wire name should not depend on a setting somebody can change elsewhere. It also keeps the
 * names visible to tooling that reads Jackson annotations but does not understand Jackson 3's
 * databind package - the documentation generator among them.
 *
 * <p>@Schema is used selectively: on fields a merchant could reasonably misread, and on the
 * two that would otherwise be given an invented example. Fields whose name says everything -
 * expiry_month, expiry_year - are left alone rather than padded with restatements.
 */
@ValidExpiryDate
@Schema(description = "A card payment to be authorized with the acquiring bank")
public record PostPaymentRequest(

    // An auto-generated example here would be a made-up card number appearing in public
    // documentation. Better to choose one, and to choose a test card.
    @Schema(example = "2222405343248877", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("card_number")
    @NotBlank(message = "card_number is required")
    @Pattern(regexp = "^[0-9]{14,19}$",
        message = "card_number must be 14-19 numeric characters")
    String cardNumber,

    @Schema(example = "4", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("expiry_month")
    @NotNull(message = "expiry_month is required")
    @Min(value = 1, message = "expiry_month must be between 1 and 12")
    @Max(value = 12, message = "expiry_month must be between 1 and 12")
    Integer expiryMonth,

    @Schema(
        description = "Four-digit year. Together with expiry_month it must not be in the past;"
            + " a card is valid through the last day of its expiry month.",
        example = "2030",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("expiry_year")
    @NotNull(message = "expiry_year is required")
    Integer expiryYear,

    @Schema(
        description = "ISO 4217 code. This gateway accepts GBP, USD and EUR.",
        allowableValues = {"GBP", "USD", "EUR"},
        example = "GBP",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("currency")
    @NotBlank(message = "currency is required")
    @Pattern(regexp = "^(GBP|USD|EUR)$",
        message = "currency must be one of GBP, USD, EUR")
    String currency,

    // The field where a misreading costs real money: 1050 is ten pounds fifty, not one
    // thousand and fifty. Worth spelling out even though the type is obvious.
    @Schema(
        description = "Amount in the minor currency unit. GBP 10.50 is sent as 1050, and"
            + " GBP 0.01 as 1.",
        example = "1050",
        minimum = "1",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("amount")
    @NotNull(message = "amount is required")
    @Positive(message = "amount must be a positive integer in the minor currency unit")
    Integer amount,

    @Schema(example = "123", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("cvv")
    @NotBlank(message = "cvv is required")
    @Pattern(regexp = "^[0-9]{3,4}$", message = "cvv must be 3-4 numeric characters")
    String cvv

) {

  public PostPaymentRequest {
    cardNumber = cardNumber == null ? null : cardNumber.trim();
    cvv = cvv == null ? null : cvv.trim();
    currency = currency == null ? null : currency.trim().toUpperCase();
  }

  @Override
  public String toString() {
    return "PostPaymentRequest[cardNumber=****, expiryMonth=%s, expiryYear=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(expiryMonth, expiryYear, currency, amount);
  }
}