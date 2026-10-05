package io.github.sanduniliyanage.flaglane.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A project, environment or flag key, or a rollout salt: the URL-safe format the database checks
 * (docs/DATABASE.md). It excludes {@code :}, which separates salt from user key in the bucket hash
 * (FR-EVL-002). Checked here as well so a bad key is a 400 naming the field, not a 500 from a
 * constraint.
 */
@NotNull
@Pattern(regexp = ResourceKey.REGEX)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResourceKey {

  String REGEX = "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$";

  String message() default
      "must be 1 to 63 lowercase letters, digits and hyphens, starting and ending with a letter"
          + " or digit";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
