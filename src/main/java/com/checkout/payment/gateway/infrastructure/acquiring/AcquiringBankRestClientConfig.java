package com.checkout.payment.gateway.infrastructure.acquiring;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * HTTP client used to reach the acquiring bank.
 *
 * <p>The timeouts are the load-bearing part of this file. Without them a socket can hang
 * indefinitely, no failure is ever recorded, and the circuit breaker configured next door never
 * trips - every thread waits on a bank that will not answer until the pool is empty. A breaker
 * without timeouts is decoration.
 *
 * <p>Properties are enabled here rather than scanned globally, so the binding stays scoped
 * to the adapter that actually needs it.
 */
@Configuration
@EnableConfigurationProperties(AcquiringBankProperties.class)
public class AcquiringBankRestClientConfig {

  @Bean
  public RestClient acquiringBankRestClient(RestClient.Builder builder,
      AcquiringBankProperties properties) {

    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(properties.connectTimeout());
    requestFactory.setReadTimeout(properties.readTimeout());

    return builder
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }
}