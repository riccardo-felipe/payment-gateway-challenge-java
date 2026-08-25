package com.checkout.payment.gateway.application.payment;

/**
 * Outbound port for the acquiring bank.
 *
 * <p>Declared in the application layer and implemented in infrastructure so the dependency
 * points inwards. Both the argument and the return type are application-owned, which keeps the
 * acquirer's JSON contract from leaking into the use case.
 */
public interface AcquiringBankGateway {

  AuthorizationResult authorize(ProcessPaymentCommand command);
}