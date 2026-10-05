package io.github.sanduniliyanage.flaglane.targeting.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code PUT .../config/{envKey}/overrides}: the whole set, one entry per user key (FR-RUL-001).
 */
@ReplaceOverridesRequest.DistinctUserKeys
public record ReplaceOverridesRequest(
    @Schema(
            description =
                "An empty list removes every override (ADR-023: at most 1,000). Overrides do not"
                    + " apply to client keys, on either serving path (FR-KEY-008)")
        @NotNull
        @Size(max = 1000)
        List<@Valid @NotNull OverrideRequest> overrides) {

  public ReplaceOverridesRequest {
    // Kept as received, nulls included, so validation can name a bad entry; unmodifiable after.
    overrides = overrides == null ? null : Collections.unmodifiableList(new ArrayList<>(overrides));
  }

  /** One override per user key: a user cannot be given two values. */
  @Target(ElementType.TYPE)
  @Retention(RetentionPolicy.RUNTIME)
  @Constraint(validatedBy = DistinctUserKeys.Validator.class)
  @interface DistinctUserKeys {

    String message() default "each user key may appear once";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Checks the user keys are distinct. */
    class Validator implements ConstraintValidator<DistinctUserKeys, ReplaceOverridesRequest> {

      @Override
      public boolean isValid(ReplaceOverridesRequest request, ConstraintValidatorContext context) {
        if (request == null || request.overrides() == null) {
          return true;
        }
        Set<String> seen = new HashSet<>();
        return request.overrides().stream()
            .filter(Objects::nonNull)
            .map(OverrideRequest::userKey)
            .filter(Objects::nonNull)
            .allMatch(seen::add);
      }
    }
  }
}
