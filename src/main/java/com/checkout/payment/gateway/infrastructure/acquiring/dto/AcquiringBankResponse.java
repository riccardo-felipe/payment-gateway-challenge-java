package com.checkout.payment.gateway.infrastructure.acquiring.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 200 OK body returned by the bank simulator. authorizationCode is only present when authorized is
 * true.
 */
public record AcquiringBankResponse(
    @JsonProperty("authorized") boolean authorized,
    @JsonProperty("authorization_code") String authorizationCode
) {

}