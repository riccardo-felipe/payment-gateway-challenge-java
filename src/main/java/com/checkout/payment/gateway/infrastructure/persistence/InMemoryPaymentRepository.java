package com.checkout.payment.gateway.infrastructure.persistence;

import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.domain.payment.PaymentRepository;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * In-memory adapter standing in for a real storage engine, as the requirements allow.
 *
 * <p>Backed by a ConcurrentHashMap because this bean is a singleton serving concurrent HTTP
 * requests: a plain HashMap can corrupt its internal structure under parallel writes, and that
 * failure surfaces as a hung request rather than an obvious exception.
 *
 * <p>No defensive copying on the way in or out - Payment is an immutable record, so handing
 * out the same instance is safe.
 */
@Repository
public class InMemoryPaymentRepository implements PaymentRepository {

  private final Map<UUID, Payment> payments = new ConcurrentHashMap<>();

  @Override
  public void save(Payment payment) {
    Objects.requireNonNull(payment, "payment must not be null");
    Objects.requireNonNull(payment.id(), "payment id must be assigned before saving");
    payments.put(payment.id(), payment);
  }

  @Override
  public Optional<Payment> findById(UUID id) {
    // Fails rather than returning empty: a null id is a programming error, and turning
    // it into "no such payment" would answer the merchant with a 404 that hides the real
    // fault. Empty is reserved for an id that is well formed but unknown.
    Objects.requireNonNull(id, "payment id must not be null");
    return Optional.ofNullable(payments.get(id));
  }
}