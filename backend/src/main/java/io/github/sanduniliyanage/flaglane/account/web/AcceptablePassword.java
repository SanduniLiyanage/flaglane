package io.github.sanduniliyanage.flaglane.account.web;

import io.github.sanduniliyanage.flaglane.account.domain.PasswordPolicy;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Optional;

/** A new password {@link PasswordPolicy} accepts. The message says which rule it broke. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = AcceptablePassword.Validator.class)
public @interface AcceptablePassword {

  String message() default "password is not acceptable";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};

  /** Applies {@link PasswordPolicy}. */
  class Validator implements ConstraintValidator<AcceptablePassword, String> {

    @Override
    public boolean isValid(String password, ConstraintValidatorContext context) {
      Optional<String> violation = PasswordPolicy.violation(password);
      if (violation.isEmpty()) {
        return true;
      }
      context.disableDefaultConstraintViolation();
      context.buildConstraintViolationWithTemplate(violation.get()).addConstraintViolation();
      return false;
    }
  }
}
