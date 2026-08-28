package com.checkout.payment.gateway.interfaces.payment;

import com.checkout.payment.gateway.application.payment.PaymentService;
import com.checkout.payment.gateway.domain.payment.Payment;
import com.checkout.payment.gateway.interfaces.payment.dto.GetPaymentResponse;
import com.checkout.payment.gateway.interfaces.payment.dto.PaymentRejectedResponse;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentRequest;
import com.checkout.payment.gateway.interfaces.payment.dto.PostPaymentResponse;
import com.checkout.payment.gateway.interfaces.payment.mapper.PaymentDtoMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * HTTP entry point for the payment gateway.
 *
 * <p>Kept to routing and translation: parse, delegate, map the result back. Every failure
 * path leaves through an exception and is shaped by GlobalExceptionHandler, so there is no error
 * handling here to drift out of step with the rest of the API.
 *
 * <p>The @ApiResponse declarations exist because the generator infers only the happy path.
 * Without them a merchant reading the documentation would never learn that 400 carries a structured
 * list of invalid fields, nor that 502 is a distinct outcome from a decline.
 */
@RestController
@RequestMapping("/payments")
@Tag(name = "Payments", description = "Take card payments and retrieve previously made ones")
public class PaymentsController {

  private final PaymentService paymentService;

  public PaymentsController(PaymentService paymentService) {
    this.paymentService = paymentService;
  }

  /**
   * Processes a payment.
   *
   * <p>Returns 201 for both Authorized and Declined. A decline is still a payment that
   * exists, has an id, and can be retrieved for reconciliation - the resource was created either
   * way, and the verdict is in the body. Only a rejected request creates nothing, and that leaves
   * as a 400.
   *
   * <p>The @Valid is what triggers every constraint on the request, including the class-level
   * expiry check. Without it the annotations are inert and invalid cards reach the bank.
   */
  @PostMapping
  @Operation(
      summary = "Process a payment",
      description = "Sends the card details to the acquiring bank and records the verdict."
          + " Both an authorization and a decline create a payment: each has an id and can be"
          + " retrieved later. Only a request that fails validation creates nothing.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "201",
          description = "The payment reached the bank. Check status for the verdict:"
              + " Authorized or Declined.",
          // Declared rather than inferred: without it the generator reports */* for this
          // response, since the mapping does not narrow producible types. Narrowing it with
          // produces would also constrain error responses, which answer problem+json.
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = PostPaymentResponse.class))),
      @ApiResponse(
          responseCode = "400",
          description = "The request was rejected before reaching the bank. No payment"
              + " exists and nothing was charged. The body lists every invalid field. A body"
              + " that could not be parsed at all - malformed JSON, or a value of the wrong"
              + " type such as a fractional amount - reports a single error with no field"
              + " name attached.",
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = PaymentRejectedResponse.class))),
      @ApiResponse(
          responseCode = "502",
          description = "The acquiring bank gave no verdict. This is not a decline - nothing"
              + " was authorized, and the payment may be retried.",
          // The schema is kept for client generation, but a literal example is added
          // because the generated one describes ProblemDetail's shape rather than what this
          // gateway actually sends - including a properties map these responses never carry.
          content = @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = """
                  {
                    "type": "about:blank",
                    "title": "Acquiring bank unavailable",
                    "status": 502,
                    "detail": "The payment could not be processed because the acquiring bank did not respond"
                  }
                  """)))
  })
  public ResponseEntity<PostPaymentResponse> processPayment(
      @Valid @RequestBody PostPaymentRequest request
  ) {
    Payment payment = paymentService.process(PaymentDtoMapper.toCommand(request));

    // Built through URI template expansion rather than string concatenation: the id is
    // internally generated and safe, but expansion encodes the value and keeps this off the
    // list of places a future change could turn into header injection.
    URI location = UriComponentsBuilder.fromPath("/payments/{id}")
        .buildAndExpand(payment.id())
        .toUri();

    return ResponseEntity.created(location).body(PaymentDtoMapper.toPostResponse(payment));
  }

  /**
   * Retrieves a previously made payment.
   *
   * <p>An unknown id throws from the service and becomes a 404; Spring rejects a malformed
   * UUID before this method runs. Neither case needs handling here.
   */
  @GetMapping("/{id}")
  @Operation(
      summary = "Retrieve a payment",
      description = "Returns a previously made payment, authorized or declined, for"
          + " reconciliation and reporting.")
  @ApiResponses({
      @ApiResponse(
          responseCode = "200",
          description = "The payment was found.",
          content = @Content(
              mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = GetPaymentResponse.class))),
      @ApiResponse(
          responseCode = "404",
          description = "No payment exists with that id.",
          content = @Content(
              mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
              schema = @Schema(implementation = ProblemDetail.class),
              examples = @ExampleObject(value = """
                  {
                    "type": "about:blank",
                    "title": "Payment not found",
                    "status": 404,
                    "detail": "No payment found with id 6a1b3c9e-0000-4000-8000-000000000001"
                  }
                  """)))
  })
  public GetPaymentResponse getPayment(
      @PathVariable
      @Parameter(description = "The id returned when the payment was processed",
          example = "6a1b3c9e-0000-4000-8000-000000000001")
      UUID id) {
    return PaymentDtoMapper.toGetResponse(paymentService.findById(id));
  }
}