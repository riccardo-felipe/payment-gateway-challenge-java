package com.checkout.payment.gateway.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the application's notion of "now".
 *
 * <p>Injected rather than read through Clock.systemUTC() at the call site so tests can
 * freeze time. Without it, any test asserting on card expiry would depend on the real calendar and
 * start failing on its own the year the fixture dates run out.
 *
 * <p>UTC on purpose: card expiry, and later any timestamp on a payment, must not shift
 * meaning with the server's timezone or with daylight saving.
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}