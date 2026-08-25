package com.checkout.payment.gateway.interfaces.payment.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status vocabulary exposed to the merchant over HTTP.
 *
 * <p>Wider than the domain enum: it adds REJECTED, which describes a request that failed
 * validation and never reached the acquiring bank. That value is deliberately unreachable from a
 * Payment - it only ever appears in PaymentRejectedResponse.
 *
 * <p>The exact casing required by the API contract is pinned here with @JsonValue, keeping
 * that detail in the layer that actually answers HTTP.
 */
@Schema(
    description = "Authorized and Declined describe payments the bank ruled on. Rejected"
        + " describes a request that never reached the bank, and appears only on a 400.",
    example = "Authorized")
public enum PaymentResponseStatus {

  AUTHORIZED("Authorized"),
  DECLINED("Declined"),
  REJECTED("Rejected");

  private final String value;

  PaymentResponseStatus(String value) {
    this.value = value;
  }

  @JsonValue
  public String value() {
    return value;
  }

  /**
   * Makes deserialization symmetric with @JsonValue, so a response can be read back into this type
   * - integration tests that parse the body being the immediate reason.
   *
   * <p>Matching is exact rather than case-insensitive: this enum is one side of a published
   * contract, and quietly accepting "authorized" would let a client drift from the agreed spelling
   * without ever being told.
   */
  @JsonCreator
  public static PaymentResponseStatus fromValue(String value) {
    for (PaymentResponseStatus status : values()) {
      if (status.value.equals(value)) {
        return status;
      }
    }
    throw new IllegalArgumentException("Unknown payment status: " + value);
  }
}