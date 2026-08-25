package com.checkout.payment.gateway.interfaces.payment.validation;

import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.aValidRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The clock is frozen at 15 June 2026 for every case here.
 *
 * <p>That is the whole reason the validator takes a Clock instead of calling
 * YearMonth.now(): pinned to the real calendar, these fixtures would start failing on their own
 * once the dates drifted into the past, and the failure would look like a regression.
 */
@DisplayName("ExpiryDateValidator")
class ExpiryDateValidatorTest {

  private static final Clock JUNE_2026 =
      Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

  private final ExpiryDateValidator validator = new ExpiryDateValidator(JUNE_2026);

  @ParameterizedTest(name = "{0}/{1} is accepted")
  @CsvSource({
      // The current month counts as valid: a card is good through the last day of it.
      // This is the boundary the whole test exists for.
      "6, 2026",
      "7, 2026",
      "12, 2026",
      "1, 2027",
      "1, 2099"
  })
  @DisplayName("accepts an expiry in the current month or later")
  void acceptsFutureExpiry(int month, int year) {
    ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    boolean valid = validator.isValid(requestExpiring(month, year), context);

    assertThat(valid).isTrue();
  }

  @ParameterizedTest(name = "{0}/{1} is rejected")
  @CsvSource({
      "5, 2026",
      "1, 2026",
      "12, 2025",
      "6, 2025"
  })
  @DisplayName("rejects an expiry that has already passed")
  void rejectsPastExpiry(int month, int year) {
    ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    boolean valid = validator.isValid(requestExpiring(month, year), context);

    assertThat(valid).isFalse();
  }

  @Test
  @DisplayName("attaches the violation to expiryYear so the merchant is told which field to fix")
  void reportsViolationAgainstExpiryYear() {
    ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    validator.isValid(requestExpiring(1, 2020), context);

    verify(context).disableDefaultConstraintViolation();
    verify(
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate()))
        .addPropertyNode("expiryYear");
  }

  /**
   * Missing or out-of-range values are already reported by the field-level constraints. Returning
   * true here is what keeps a single mistake from producing two messages, so it is behaviour worth
   * pinning rather than an implementation detail.
   */
  @ParameterizedTest(name = "month={0} year={1} defers to the field constraints")
  @CsvSource(nullValues = "null", value = {
      "null, 2027",
      "6, null",
      "null, null",
      "0, 2027",
      "13, 2027",
      "-1, 2027"
  })
  @DisplayName("stays silent when month or year is absent or out of range")
  void defersToFieldConstraints(Integer month, Integer year) {
    ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    boolean valid = validator.isValid(requestExpiring(month, year), context);

    assertThat(valid).isTrue();
  }

  @Test
  @DisplayName("treats a null request as valid, leaving @NotNull to complain")
  void acceptsNullRequest() {
    ConstraintValidatorContext context = mock(ConstraintValidatorContext.class, RETURNS_DEEP_STUBS);

    boolean valid = validator.isValid(null, context);

    assertThat(valid).isTrue();
  }

  /**
   * Every other field comes from the fixture and is valid, so nothing but the expiry can be blamed
   * when one of these cases fails.
   */
  private static PostPaymentRequest requestExpiring(Integer expiryMonth, Integer expiryYear) {
    return aValidRequest().withExpiry(expiryMonth, expiryYear).build();
  }
}