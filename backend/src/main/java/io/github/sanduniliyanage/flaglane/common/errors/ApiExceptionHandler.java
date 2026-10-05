package io.github.sanduniliyanage.flaglane.common.errors;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error as RFC 9457 Problem Details (docs/API.md). Spring's own exceptions — unreadable
 * bodies, unsupported methods — are rendered by the superclass; the exceptions services throw are
 * mapped here by category, so services never name an HTTP status.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException e) {
    return problem(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(CredentialsRejectedException.class)
  ProblemDetail credentialsRejected(CredentialsRejectedException e) {
    return problem(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  /**
   * Adds an {@code errors} member naming each invalid field and what is wrong with it. The rejected
   * value is never echoed: the field may be a password.
   */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    ProblemDetail problem = e.getBody();
    problem.setDetail("The request has invalid fields");
    List<Map<String, String>> errors =
        e.getFieldErrors().stream().map(ApiExceptionHandler::describe).toList();
    problem.setProperty("errors", errors);
    return handleExceptionInternal(e, problem, headers, status, request);
  }

  private static Map<String, String> describe(FieldError error) {
    return Map.of(
        "field",
        error.getField(),
        "message",
        Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid"));
  }

  private static ProblemDetail problem(HttpStatus status, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(status.getReasonPhrase());
    return problem;
  }
}
