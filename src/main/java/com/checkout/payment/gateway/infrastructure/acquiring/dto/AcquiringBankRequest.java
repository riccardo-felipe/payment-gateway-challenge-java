package com.checkout.payment.gateway.infrastructure.acquiring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Payload sent to the bank simulator, on {@code POST /payments}.
 *
 * <p>This is an external contract we do not control, so field names are pinned with
 * {@code @JsonProperty} rather than relying on a global naming strategy that someone could
 * change in application properties. expiryDate follows the MM/yyyy format, e.g. "04/2025".
 */
public record AcquiringBankRequest(
    @JsonProperty("card_number") String cardNumber,
    @JsonProperty("expiry_date") String expiryDate,
    @JsonProperty("currency") String currency,
    @JsonProperty("amount") int amount,
    @JsonProperty("cvv") String cvv
) {
  @Override
  public String toString() {
    return "AcquiringBankRequest[cardNumber=****, expiryDate=%s, currency=%s, amount=%s, cvv=***]"
        .formatted(expiryDate, currency, amount);
  }
}