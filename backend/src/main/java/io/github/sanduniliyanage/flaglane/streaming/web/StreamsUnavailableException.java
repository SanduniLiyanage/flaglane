package io.github.sanduniliyanage.flaglane.streaming.web;

import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The server is not opening streams, because it is shutting down. A 503 with {@code Retry-After},
 * which the SDK waits out while it polls.
 */
class StreamsUnavailableException extends ErrorResponseException {

  private static final long serialVersionUID = 1L;

  StreamsUnavailableException(String retryAfterSeconds) {
    super(HttpStatus.SERVICE_UNAVAILABLE, problem(), null);
    getHeaders().set(HttpHeaders.RETRY_AFTER, retryAfterSeconds);
  }

  private static ProblemDetail problem() {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Streams are not being opened now; poll for changes meanwhile");
    problem.setType(URI.create("about:blank"));
    return problem;
  }
}
