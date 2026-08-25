package com.checkout.payment.gateway.interfaces.payment.mapper;

import com.checkout.payment.gateway.application.payment.ProcessPaymentCommand;
import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.domain.payment.PaymentStatus;
import com.checkout.payment.gateway.interfaces.payment.dto.GetPaymentResponse;
import com.checkout.payment.gateway.interfaces.payment.dto.PaymentResponseStatus;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentResponse;

/**
 * Translation between the HTTP contract and the inner layers.
 *
 * <p>Deliberately knows nothing about the acquiring bank: an inbound adapter has no reason
 * to understand the acquirer's wire format. That half of the translation lives in infrastructure,
 * which keeps every dependency here pointing inwards.
 *
 * <p>Values are unwrapped from their boxed types without null checks because the request is
 * validated before a command is ever built.
 */
public final class PaymentDtoMapper {

  private PaymentDtoMapper() {
  }

  public static ProcessPaymentCommand toCommand(PostPaymentRequest request) {
    return new ProcessPaymentCommand(
        request.cardNumber(),
        request.expiryMonth(),
        request.expiryYear(),
        request.currency(),
        request.amount(),
        request.cvv()
    );
  }

  public static PostPaymentResponse toPostResponse(Payment payment) {
    return new PostPaymentResponse(
        payment.id(),
        toResponseStatus(payment.status()),
        payment.lastFourCardDigits(),
        payment.expiryMonth(),
        payment.expiryYear(),
        payment.currency(),
        payment.amount()
    );
  }

  public static GetPaymentResponse toGetResponse(Payment payment) {
    return new GetPaymentResponse(
        payment.id(),
        toResponseStatus(payment.status()),
        payment.lastFourCardDigits(),
        payment.expiryMonth(),
        payment.expiryYear(),
        payment.currency(),
        payment.amount()
    );
  }

  /**
   * No default branch on purpose: an exhaustive switch means that adding a domain status breaks the
   * build here, forcing a conscious decision about how to expose it.
   */
  private static PaymentResponseStatus toResponseStatus(PaymentStatus status) {
    return switch (status) {
      case AUTHORIZED -> PaymentResponseStatus.AUTHORIZED;
      case DECLINED -> PaymentResponseStatus.DECLINED;
    };
  }
}