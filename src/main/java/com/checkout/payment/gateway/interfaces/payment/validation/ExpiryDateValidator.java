package com.checkout.payment.gateway.interfaces.payment.validation;

import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.YearMonth;

/**
 * Takes a Clock by injection so tests can freeze time instead of depending on the machine's real
 * date, which would otherwise make expiry fixtures rot.
 */
public class ExpiryDateValidator implements
    ConstraintValidator<ValidExpiryDate, PostPaymentRequest> {

  private final Clock clock;

  public ExpiryDateValidator(Clock clock) {
    this.clock = clock;
  }

  @Override
  public boolean isValid(PostPaymentRequest request, ConstraintValidatorContext context) {
    if (request == null) {
      return true;
    }

    Integer month = request.expiryMonth();
    Integer year = request.expiryYear();

    // Missing or out-of-range values are already reported by the field constraints;
    // returning true here avoids duplicate messages for the same mistake.
    if (month == null || year == null || month < 1 || month > 12) {
      return true;
    }

    YearMonth expiry = YearMonth.of(year, month);
    YearMonth current = YearMonth.now(clock);

    // A card stays valid through the last day of its expiry month.
    boolean valid = !expiry.isBefore(current);

    if (!valid) {
      context.disableDefaultConstraintViolation();
      context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
          .addPropertyNode("expiryYear")
          .addConstraintViolation();
    }
    return valid;
  }
}