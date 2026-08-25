package com.checkout.payment.gateway.application.payment;

import com.checkout.payment.gateway.application.payment.AuthorizationResult.Authorized;
import com.checkout.payment.gateway.application.payment.AuthorizationResult.Declined;
import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.domain.payment.PaymentRepository;
import com.checkout.payment.gateway.domain.payment.PaymentStatus;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The two use cases the requirements describe: process a payment, and retrieve one.
 *
 * <p>This is the only place that knows the whole sequence, and it stays deliberately thin -
 * orchestration, not business rules. Input validation already happened at the HTTP boundary and the
 * acquirer's protocol is hidden behind the gateway port, so what is left here is the order in which
 * things must happen.
 */
@Service
public class PaymentService {

  private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

  private final AcquiringBankGateway acquiringBank;
  private final PaymentRepository paymentRepository;

  public PaymentService(AcquiringBankGateway acquiringBank, PaymentRepository paymentRepository) {
    this.acquiringBank = acquiringBank;
    this.paymentRepository = paymentRepository;
  }

  /**
   * Authorizes with the bank first, then persists.
   *
   * <p>The order matters: an id is only issued once the bank has given a verdict. If
   * authorization throws, nothing is stored - which is exactly right, because a request that never
   * reached the bank produced no payment for the merchant to retrieve later.
   *
   * <p>A declined payment is stored just like an authorized one. Declined is a business
   * outcome the merchant needs for reconciliation, not an error.
   */
  public Payment process(ProcessPaymentCommand command) {
    AuthorizationResult authorization = acquiringBank.authorize(command);

    // Identity is assigned here rather than by an adapter: it belongs to the payment,
    // not to whichever technology happens to store or transmit it.
    UUID id = UUID.randomUUID();

    // Exhaustive over the sealed type, so a future third verdict cannot slip through as
    // an implicit decline - the compiler stops here until it is handled.
    Payment payment = switch (authorization) {
      case Authorized(String authorizationCode) ->
          buildPayment(id, command, PaymentStatus.AUTHORIZED, authorizationCode);
      case Declined _ -> buildPayment(id, command, PaymentStatus.DECLINED, null);
    };

    paymentRepository.save(payment);

    // The audit line. Storage here is a map that dies with the process, so this is the
    // only durable record that a payment ever happened - the difference between being
    // able to answer a merchant's dispute three days later and not. Card data is limited
    // to the last four digits, which is the same fragment the merchant reconciles with.
    log.info("Payment {} {} for {} {} on card ending {}",
        payment.id(),
        payment.status(),
        payment.amount(),
        payment.currency(),
        payment.lastFourCardDigits());

    return payment;
  }

  /**
   * Throws when the id is unknown so the exception handler can answer with a 404 in the same error
   * format as every other failure. Returning an Optional would have pushed that decision into the
   * controller and produced an empty 404 body, inconsistent with the 400 the same merchant sees
   * when a request is rejected.
   */
  public Payment findById(UUID id) {
    return paymentRepository.findById(id)
        .orElseThrow(() -> new PaymentNotFoundException(id));
  }

  /**
   * Shared by both branches of the switch so the aggregate is assembled in one place and the two
   * outcomes differ only in the two fields that actually differ.
   *
   * <p>The full PAN stops here: only the last four digits reach storage.
   */
  private static Payment buildPayment(UUID id,
      ProcessPaymentCommand command,
      PaymentStatus status,
      String authorizationCode) {
    return new Payment(
        id,
        status,
        command.lastFourCardDigits(),
        command.expiryMonth(),
        command.expiryYear(),
        command.currency(),
        command.amount(),
        authorizationCode
    );
  }
}