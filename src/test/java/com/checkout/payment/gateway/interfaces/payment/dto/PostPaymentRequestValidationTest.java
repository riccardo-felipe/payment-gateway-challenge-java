package com.checkout.payment.gateway.interfaces.payment.dto;

import static com.checkout.payment.gateway.testsupport.PaymentRequestFixture.aValidRequest;
import static org.assertj.core.api.Assertions.assertThat;

import com.checkout.payment.gateway.interfaces.payment.validation.ExpiryDateValidator;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Walks the validation table from the requirements, field by field.
 *
 * <p>Runs a standalone Validator rather than a Spring context - these rules are declared on
 * the record and need nothing else to be true. The one piece of wiring that is unavoidable is the
 * validator factory below: ExpiryDateValidator has no no-argument constructor, so the default
 * factory cannot build it.
 */
@DisplayName("PostPaymentRequest validation")
class PostPaymentRequestValidationTest {

  private static final Clock JUNE_2026 =
      Clock.fixed(Instant.parse("2026-06-15T12:00:00Z"), ZoneOffset.UTC);

  private static ValidatorFactory validatorFactory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    validatorFactory = Validation
        .byDefaultProvider()
        .configure()
        .constraintValidatorFactory(new ClockAwareValidatorFactory(JUNE_2026))
        .buildValidatorFactory();

    validator = validatorFactory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    validatorFactory.close();
  }

  @Test
  @DisplayName("accepts a request where every field is valid")
  void acceptsAValidRequest() {
    assertThat(validator.validate(aValidRequest().build())).isEmpty();
  }

  @Nested
  @DisplayName("card number")
  class CardNumber {

    @ParameterizedTest(name = "{0} digits is accepted")
    @ValueSource(strings = {
        "22224053432488",      // 14, the lower bound
        "2222405343248877",    // 16, the common case
        "2222405343248877123"  // 19, the upper bound
    })
    void acceptsLengthsInRange(String cardNumber) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCardNumber(cardNumber)
                  .build()
          )
      ).doesNotContain("cardNumber");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2222405343248",        // 13, one short
        "22224053432488771234", // 20, one long
        "2222405343248X77",     // letter in the middle
        "2222 4053 4324 8877",  // spaces, as a card is often written
        ""
    })
    @DisplayName("rejects anything that is not 14 to 19 digits")
    void rejectsInvalidCardNumbers(String cardNumber) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCardNumber(cardNumber)
                  .build()
          )
      ).contains("cardNumber");
    }

    @Test
    void rejectsAMissingCardNumber() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCardNumber(null)
                  .build()
          )
      ).contains("cardNumber");
    }

    /**
     * The record trims on construction, so surrounding whitespace must not make an otherwise valid
     * card fail. Pinning this stops the normalisation from being removed as apparently redundant.
     */
    @Test
    @DisplayName("accepts a card number padded with whitespace")
    void trimsSurroundingWhitespace() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCardNumber("  2222405343248877  ")
                  .build()
          )
      ).doesNotContain("cardNumber");
    }
  }

  @Nested
  @DisplayName("expiry month")
  class ExpiryMonth {

    @ParameterizedTest(name = "month {0} is accepted")
    @ValueSource(ints = {1, 6, 12})
    void acceptsMonthsInRange(int month) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withExpiryMonth(month)
                  .build()
          )
      ).doesNotContain("expiryMonth");
    }

    @ParameterizedTest(name = "month {0} is rejected")
    @ValueSource(ints = {0, 13, -1, 99})
    void rejectsMonthsOutOfRange(int month) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withExpiryMonth(month)
                  .build()
          )
      ).contains("expiryMonth");
    }

    @Test
    void rejectsAMissingMonth() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withExpiryMonth(null)
                  .build()
          )
      ).contains("expiryMonth");
    }
  }

  @Nested
  @DisplayName("expiry date as a whole")
  class Expiry {

    /**
     * One mistake should produce one message. An out-of-range month must not be reported twice -
     * once by @Max and once by the class-level expiry check.
     */
    @Test
    @DisplayName("reports an impossible month only once")
    void doesNotDuplicateMessagesForAnInvalidMonth() {
      Set<ConstraintViolation<PostPaymentRequest>> violations =
          validator.validate(
              aValidRequest()
                  .withExpiry(13, 2027)
                  .build()
          );

      assertThat(violations).hasSize(1);
    }

    @Test
    @DisplayName("rejects a card that expired last month")
    void rejectsAnExpiredCard() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withExpiry(5, 2026)
                  .build()
          )
      ).contains("expiryYear");
    }
  }

  @Nested
  @DisplayName("currency")
  class Currency {

    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {"GBP", "USD", "EUR"})
    void acceptsSupportedCurrencies(String currency) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCurrency(currency)
                  .build()
          )
      ).doesNotContain("currency");
    }

    /**
     * The record upper-cases on construction. A merchant sending "gbp" is sending a valid currency,
     * and rejecting it would be a needless integration failure.
     */
    @ParameterizedTest(name = "{0} is accepted after normalisation")
    @ValueSource(strings = {"gbp", "Usd", "eUr"})
    @DisplayName("accepts supported currencies in any casing")
    void normalisesCasing(String currency) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCurrency(currency)
                  .build()
          )
      ).doesNotContain("currency");
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {
        "JPY",  // a real ISO code, but outside the three this gateway supports
        "GB",   // two characters
        "GBPP", // four characters
        "123",
        ""
    })
    void rejectsUnsupportedCurrencies(String currency) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCurrency(currency)
                  .build()
          )
      ).contains("currency");
    }

    @Test
    void rejectsAMissingCurrency() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCurrency(null)
                  .build()
          )
      ).contains("currency");
    }
  }

  @Nested
  @DisplayName("amount")
  class Amount {

    @ParameterizedTest(name = "{0} minor units is accepted")
    @ValueSource(ints = {1, 100, 1050, Integer.MAX_VALUE})
    void acceptsPositiveAmounts(int amount) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withAmount(amount)
                  .build()
          )
      ).doesNotContain("amount");
    }

    /**
     * Zero is rejected as a deliberate reading of the requirements, which say nothing about it. A
     * zero-value authorization is a real technique for card verification, so this is an assumption
     * worth documenting rather than an obvious rule.
     */
    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(ints = {0, -1, -1050})
    void rejectsZeroAndNegativeAmounts(int amount) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withAmount(amount)
                  .build()
          )
      ).contains("amount");
    }

    @Test
    void rejectsAMissingAmount() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withAmount(null)
                  .build()
          )
      ).contains("amount");
    }
  }

  @Nested
  @DisplayName("cvv")
  class Cvv {

    @ParameterizedTest(name = "{0} is accepted")
    @ValueSource(strings = {"123", "4321"})
    void acceptsThreeAndFourDigits(String cvv) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCvv(cvv)
                  .build()
          )
      ).doesNotContain("cvv");
    }

    @ParameterizedTest(name = "{0} is rejected")
    @ValueSource(strings = {"12", "12345", "12a", "abc", ""})
    void rejectsAnythingElse(String cvv) {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCvv(cvv)
                  .build()
          )
      ).contains("cvv");
    }

    @Test
    void rejectsAMissingCvv() {
      assertThat(
          violatedFields(
              aValidRequest()
                  .withCvv(null)
                  .build()
          )
      ).contains("cvv");
    }
  }

  @Nested
  @DisplayName("multiple problems")
  class MultipleProblems {

    /**
     * A merchant with several mistakes should learn about all of them at once, rather than fixing
     * one field per round trip.
     */
    @Test
    @DisplayName("reports every invalid field in one pass")
    void reportsAllInvalidFields() {
      PostPaymentRequest request = aValidRequest()
          .withCardNumber("abc")
          .withCurrency("JPY")
          .withCvv("1")
          .build();

      assertThat(violatedFields(request))
          .contains("cardNumber", "currency", "cvv");
    }
  }

  private static Set<String> violatedFields(PostPaymentRequest request) {
    return validator
        .validate(request)
        .stream()
        .map(violation -> violation.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }

  /**
   * ExpiryDateValidator takes a Clock, which the default factory cannot supply because it only
   * knows how to call a no-argument constructor. In production Spring does this job; here the
   * frozen clock is handed over so expiry cases stay deterministic.
   */
  private record ClockAwareValidatorFactory(Clock clock) implements ConstraintValidatorFactory {

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
      if (key == ExpiryDateValidator.class) {
        return key.cast(new ExpiryDateValidator(clock));
      }
      try {
        return key.getDeclaredConstructor().newInstance();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("Could not instantiate validator " + key, e);
      }
    }

    @Override
    public void releaseInstance(ConstraintValidator<?, ?> instance) {
      // Nothing to release: instances are plain objects with no pooled resources.
    }
  }
}