package com.checkout.payment.gateway.interfaces.payment.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 400 Bad Request body for a payment request that failed gateway validation and therefore never
 * reached the acquiring bank.
 *
 * <p>Named for the single case it serves rather than for errors in general. The status field
 * carries payment vocabulary - Rejected means "no payment could be created from this data" - which
 * only makes sense while creating one. Other failures (an unknown id, an unreachable bank) have no
 * payment status to report and use ProblemDetail instead.
 *
 * <p>The merchant needs Rejected spelled out because it must be told apart from Declined:
 * the first is a payload to fix, the second is a business outcome to record.
 *
 * <p>The status field overrides the example inherited from PaymentResponseStatus. Left alone,
 * the generated sample showed "Authorized" on a rejection - a value this response can never carry,
 * and a contradiction worse than a vague example.
 */
@Schema(
    name = "PaymentRejectedResponse",
    description = "Returned when a request fails validation. No payment was created and the"
        + " acquiring bank was never called, so there is nothing to retrieve or reconcile."
        + " Every invalid field is reported at once rather than one per attempt.",
    example = """
        {
          "status": "Rejected",
          "errors": [
            {
              "field": "card_number",
              "message": "card_number must be 14-19 numeric characters"
            },
            {
              "field": "cvv",
              "message": "cvv must be 3-4 numeric characters"
            }
          ]
        }
        """)
public record PaymentRejectedResponse(

    // allowableValues narrows here rather than duplicating: on a field it replaces the
    // values derived from the enum, where the same attribute on the enum's own @Schema would
    // have been appended to them. The generated client then sees a single legal value, which
    // is the truth for this response.
    @Schema(
        description = "Always Rejected on this response. Distinct from Declined, which means"
            + " the bank ruled on the payment and refused it.",
        allowableValues = {"Rejected"},
        example = "Rejected",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("status")
    PaymentResponseStatus status,

    @Schema(
        description = "One entry per invalid field. Never empty on a 400.",
        requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("errors")
    List<ValidationError> errors
) {

  public PaymentRejectedResponse(List<ValidationError> errors) {
    this(PaymentResponseStatus.REJECTED, errors);
  }

  /**
   * field uses the name as it appears in the request JSON, not the Java property name - telling a
   * merchant that "cardNumber" is invalid is unhelpful when they sent "card_number".
   */
  @Schema(
      name = "ValidationError",
      description = "A single rule that the submitted payment did not satisfy")
  public record ValidationError(

      @Schema(
          description = "The offending field, spelled as it appears in the request body."
              + " Absent when the problem is with the body as a whole, such as malformed"
              + " JSON or a value of the wrong type.",
          example = "card_number",
          nullable = true)
      @JsonProperty("field")
      String field,

      @Schema(
          description = "What the field must satisfy. Safe to show to an integrator; it"
              + " never echoes the submitted value.",
          example = "card_number must be 14-19 numeric characters",
          requiredMode = Schema.RequiredMode.REQUIRED)
      @JsonProperty("message")
      String message
  ) {

  }
}