package io.github.sanduniliyanage.flaglane.common.errors;

import java.net.SocketException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
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

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, e.getMessage());
  }

  @ExceptionHandler(ConflictException.class)
  ProblemDetail conflict(ConflictException e) {
    return problem(HttpStatus.CONFLICT, e.getMessage());
  }

  @ExceptionHandler(CredentialsRejectedException.class)
  ProblemDetail credentialsRejected(CredentialsRejectedException e) {
    return problem(HttpStatus.UNAUTHORIZED, e.getMessage());
  }

  /**
   * A data access or transaction failure. If the database is unreachable, the management API cannot
   * work and says so with a 503 (docs/ARCHITECTURE.md section 7); the serving API never reaches
   * here, because it never asks the database. Anything else is a 500 that says nothing about the
   * query that failed.
   *
   * <p>Unreachability is read from the cause chain rather than the outer type. A connection that
   * dies mid-transaction surfaces as a failure to roll back, which Spring reports in place of the
   * query's own error.
   */
  @ExceptionHandler({DataAccessException.class, TransactionException.class})
  ProblemDetail dataAccessFailed(RuntimeException e) {
    if (isDatabaseUnreachable(e)) {
      return problem(
          HttpStatus.SERVICE_UNAVAILABLE, "The database is unavailable; try again shortly");
    }
    logger.error("Data access failed", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "The request could not be completed");
  }

  static boolean isDatabaseUnreachable(Throwable failure) {
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause()) {
      if (cause instanceof DataAccessResourceFailureException
          || cause instanceof CannotCreateTransactionException
          || cause instanceof SQLTransientConnectionException
          || cause instanceof SQLNonTransientConnectionException
          || cause instanceof SocketException
          || (cause instanceof SQLException sql
              && sql.getSQLState() != null
              && sql.getSQLState().startsWith(CONNECTION_EXCEPTION_CLASS))) {
        return true;
      }
    }
    return false;
  }

  /** SQLSTATE class 08: connection exception, in the SQL standard and in PostgreSQL. */
  private static final String CONNECTION_EXCEPTION_CLASS = "08";

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
