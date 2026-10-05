package io.github.sanduniliyanage.flaglane.serving.web;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The key's environment has no ruleset in the cache: the application is still loading, or the
 * environment was deleted a moment ago. A 503 with {@code Retry-After}, which an SDK treats as
 * transient and answers from its last ruleset meanwhile (docs/API.md).
 */
class RulesetNotReadyException extends ErrorResponseException {

  private static final long serialVersionUID = 1L;

  RulesetNotReadyException(String retryAfterSeconds) {
    super(HttpStatus.SERVICE_UNAVAILABLE, problem(), null);
    getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
  }

  private static ProblemDetail problem() {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE, "The ruleset is not loaded yet");
    problem.setType(URI.create("about:blank"));
    return problem;
  }
}
