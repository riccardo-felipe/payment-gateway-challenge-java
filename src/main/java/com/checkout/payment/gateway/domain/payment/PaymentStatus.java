package com.checkout.payment.gateway.domain.payment;

/**
 * Outcome of a payment that reached the acquiring bank.
 *
 * <p>Only two values exist because only two are reachable: a request that fails gateway
 * validation never produces a Payment, so there is no persisted rejected state to model. The wider
 * vocabulary the merchant sees over HTTP lives in PaymentResponseStatus.
 *
 * <p>Free of serialization annotations on purpose - how a status is spelled on the wire is
 * a concern of the inbound adapter, not of the domain.
 */
public enum PaymentStatus {
  AUTHORIZED,
  DECLINED
}