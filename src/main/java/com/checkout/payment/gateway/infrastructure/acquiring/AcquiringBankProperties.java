package com.checkout.payment.gateway.infrastructure.acquiring;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Typed configuration for the acquiring bank connection.
 *
 * <p>A record with constructor binding, so the values are final and cannot drift after
 * startup. Relaxed binding maps the kebab-case keys in application.yml onto these names.
 *
 * <p>Validated so a missing or malformed setting fails at startup rather than on the first
 * payment of the day - a context that refuses to start is far easier to diagnose than a gateway
 * that boots cleanly and then cannot reach the bank.
 *
 * <p>The timeouts carry defaults because sensible values matter more than forcing every
 * environment to restate them; baseUrl deliberately has none, since there is no safe guess.
 */
@Validated
@ConfigurationProperties(prefix = "acquiring-bank")
public record AcquiringBankProperties(

    @NotBlank(message = "acquiring-bank.base-url must be configured")
    String baseUrl,

    @NotNull
    @DefaultValue("2s")
    Duration connectTimeout,

    @NotNull
    @DefaultValue("5s")
    Duration readTimeout
) {

}