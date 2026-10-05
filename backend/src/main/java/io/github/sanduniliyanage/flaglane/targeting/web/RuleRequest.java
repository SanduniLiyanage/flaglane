package io.github.sanduniliyanage.flaglane.targeting.web;

import io.github.sanduniliyanage.flaglane.evaluation.RuleValidator;
import io.github.sanduniliyanage.flaglane.evaluation.TargetingRule;
import io.github.sanduniliyanage.flaglane.targeting.domain.Rule;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** One rule of a {@code PUT .../rules} body. Its priority is its position in the list. */
@RuleRequest.Applicable
public record RuleRequest(
    @Schema(example = "country") @NotBlank @Size(max = 100) String attribute,
    @Schema(
            example = "IN",
            allowableValues = {
              "EQUALS",
              "NOT_EQUALS",
              "IN",
              "NOT_IN",
              "CONTAINS",
              "STARTS_WITH",
              "ENDS_WITH"
            })
        @NotBlank
        String operator,
    @Schema(
            description =
                "Strings, numbers or booleans. Exactly one for EQUALS, NOT_EQUALS, CONTAINS,"
                    + " STARTS_WITH and ENDS_WITH, the last three strings only; one or more of"
                    + " one type for IN and NOT_IN (ADR-019)",
            example = "[\"LK\", \"IN\"]")
        @NotNull
        @Size(min = 1, max = 1000)
        List<Object> matchValues,
    @Schema(description = "What the flag evaluates to when the rule matches") @NotNull
        Boolean resultValue) {

  public RuleRequest {
    // Kept as received, nulls included, so validation can name a bad value; unmodifiable after.
    matchValues =
        matchValues == null ? null : Collections.unmodifiableList(new ArrayList<>(matchValues));
  }

  Rule toRule() {
    return new Rule(attribute, operator, matchValues, resultValue);
  }

  /**
   * A rule the engine can apply (FR-RUL-010), by the engine's own definition. A malformed rule is
   * refused here so that it cannot reach the serving path at all.
   */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = Applicable.Validator.class)
  @interface Applicable {

    String message() default "rule cannot be applied";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Applies {@link RuleValidator}. Field checks report missing parts first. */
    class Validator implements ConstraintValidator<Applicable, RuleRequest> {

      @Override
      public boolean isValid(RuleRequest rule, ConstraintValidatorContext context) {
        if (rule == null
            || rule.attribute() == null
            || rule.operator() == null
            || rule.matchValues() == null
            || rule.resultValue() == null) {
          return true;
        }
        Optional<String> problem =
            RuleValidator.problem(
                new TargetingRule(
                    0, rule.attribute(), rule.operator(), rule.matchValues(), rule.resultValue()));
        if (problem.isEmpty()) {
          return true;
        }
        context.disableDefaultConstraintViolation();
        context
            .buildConstraintViolationWithTemplate(literal(problem.get()))
            .addConstraintViolation();
        return false;
      }

      /**
       * The problem can quote the caller's operator, and a violation template interprets braces and
       * expressions; escaped, it is reported as text and never evaluated.
       */
      private static String literal(String message) {
        return message
            .replace("\\", "\\\\")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("$", "\\$");
      }
    }
  }
}
