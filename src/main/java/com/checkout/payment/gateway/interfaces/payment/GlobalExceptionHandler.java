package com.checkout.payment.gateway.interfaces.payment;

import com.checkout.payment.gateway.application.payment.AcquiringBankUnavailableException;
import com.checkout.payment.gateway.application.payment.PaymentNotFoundException;
import com.checkout.payment.gateway.interfaces.payment.dto.PaymentRejectedResponse;
import com.checkout.payment.gateway.interfaces.payment.dto.PaymentRejectedResponse.ValidationError;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates failures into HTTP responses.
 *
 * <p>Two error shapes, on purpose. A rejected payment answers with PaymentRejectedResponse
 * because it has payment vocabulary to report: the status Rejected and which fields were
 * wrong. Everything else answers with ProblemDetail (RFC 9457), which is the Spring and
 * industry default and carries no domain concepts it would have to invent.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  /**
   * Bean validation failures on the payment request. Nothing was sent to the bank, so no
   * payment exists and the merchant is told exactly which fields to fix.
   *
   * <p>Walks every error rather than field errors and class-level errors separately. A
   * class-level constraint that names no property would otherwise be dropped in silence,
   * answering 400 with an empty list and leaving the merchant nothing to act on.
   */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<PaymentRejectedResponse> handleInvalidRequest(
      MethodArgumentNotValidException exception) {
    List<ValidationError> errors = new ArrayList<>();

    exception.getBindingResult().getAllErrors().forEach(error -> {
      String field = error instanceof FieldError fieldError
          ? toJsonFieldName(fieldError.getField())
          : null;
      errors.add(new ValidationError(field, error.getDefaultMessage()));
    });

    // Debug rather than warn: an invalid payload is the merchant's defect, not an anomaly in
    // this service, and warning on every one would drown the log for a single clumsy
    // integration. Field names only - the values are precisely what must never be logged,
    // since one of them is the card number.
    log.debug("Rejected a payment request, invalid fields: {}",
        errors.stream().map(ValidationError::field).toList());

    return ResponseEntity.badRequest().body(new PaymentRejectedResponse(errors));
  }

  /**
   * Malformed JSON, or a value of the wrong type. Also a rejection: the request never
   * became a payment. Deliberately does not echo the parser's message, which can quote
   * the offending payload and would put card data in a response body and in logs.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<PaymentRejectedResponse> handleUnreadableRequest() {
    ValidationError error = new ValidationError(null, "Request body is malformed or has fields of the wrong type");
    return ResponseEntity.badRequest().body(new PaymentRejectedResponse(List.of(error)));
  }

  @ExceptionHandler(PaymentNotFoundException.class)
  public ProblemDetail handlePaymentNotFound(PaymentNotFoundException exception) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    problem.setTitle("Payment not found");
    return problem;
  }

  /**
   * 502 rather than 503: the gateway itself is healthy, an upstream dependency is not.
   * Answering 503 would tell the merchant to back off from us, when the right action is
   * to retry the payment later.
   */
  @ExceptionHandler(AcquiringBankUnavailableException.class)
  public ProblemDetail handleAcquiringBankUnavailable() {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
        "The payment could not be processed because the acquiring bank did not respond");
    problem.setTitle("Acquiring bank unavailable");
    return problem;
  }

  /**
   * Converts a Java property name to the snake_case key the merchant actually sent.
   * Without this the response would blame "cardNumber" for a field named "card_number".
   */
  private static String toJsonFieldName(String propertyName) {
    StringBuilder result = new StringBuilder(propertyName.length() + 4);
    for (char character : propertyName.toCharArray()) {
      if (Character.isUpperCase(character)) {
        result.append('_').append(Character.toLowerCase(character));
      } else {
        result.append(character);
      }
    }
    return result.toString().toLowerCase(Locale.ROOT);
  }
}