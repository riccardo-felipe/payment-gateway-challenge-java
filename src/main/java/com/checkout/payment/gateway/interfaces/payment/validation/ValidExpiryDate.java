package com.checkout.payment.gateway.interfaces.payment.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level constraint asserting that the expiry month and year together fall in the future. It
 * sits on the type rather than on a field because it needs both values.
 */
@Documented
@Target({TYPE, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ExpiryDateValidator.class)
public @interface ValidExpiryDate {

  String message() default "expiry date must be in the future";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}