package com.checkout.payment.gateway.domain.payment;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the Payment aggregate.
 *
 * <p>Declared in the domain so the model owns the vocabulary of how it is stored and
 * retrieved, while the actual storage engine stays an infrastructure concern. Nothing here hints at
 * how persistence happens - no transactions, no paging, no query language.
 *
 * <p>Kept to the two operations the requirements actually need. A merchant stores a payment
 * once and reads it back by id; anything beyond that would be speculation.
 */
public interface PaymentRepository {

  void save(Payment payment);

  /**
   * Returns empty rather than null so callers are forced to handle the miss - the controller turns
   * it into a 404 instead of a NullPointerException.
   */
  Optional<Payment> findById(UUID id);
}