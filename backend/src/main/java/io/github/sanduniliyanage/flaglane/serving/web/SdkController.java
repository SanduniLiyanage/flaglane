package io.github.sanduniliyanage.flaglane.serving.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.evaluation.Evaluation;
import io.github.sanduniliyanage.flaglane.evaluation.Evaluator;
import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import io.github.sanduniliyanage.flaglane.evaluation.UserContext;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import io.github.sanduniliyanage.flaglane.serving.domain.ServedBody;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The serving API: what SDKs call, authenticated by API key. Every answer comes from the in-memory
 * ruleset cache; no request here touches the database (NFR-PER-004), which is what lets serving
 * outlive a database outage.
 */
@RestController
@RequestMapping("/sdk")
@Tag(name = "Serving", description = "For SDKs, with an API key")
@SecurityRequirement(name = OpenApiConfiguration.SDK_KEY)
@ApiResponse(
    responseCode = "401",
    description = "Missing, malformed or revoked key",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
    responseCode = "429",
    description =
        "The key has used its allowance for now; retry after the time given. A 304 costs a tenth"
            + " of any other answer",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(
    responseCode = "503",
    description = "The ruleset is not loaded yet; retry after the time given",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class SdkController {

  static final String RETRY_AFTER_SECONDS = "5";

  /** A quality value of zero, {@code 0} to {@code 0.000}: "not acceptable" (RFC 9110). */
  private static final Pattern ZERO_QUALITY = Pattern.compile("0(\\.0{0,3})?");

  private final RulesetCache cache;
  private final Evaluator evaluator;

  public SdkController(RulesetCache cache, Evaluator evaluator) {
    this.cache = cache;
    this.evaluator = evaluator;
  }

  @GetMapping(value = "/config", produces = MediaType.APPLICATION_JSON_VALUE)
  @Operation(
      summary = "Download the ruleset",
      description =
          "Every flag the key may read, for in-process evaluation (FR-SRV-001). A client key"
              + " gets client-side-visible flags only, with no overrides (FR-KEY-005, FR-KEY-008)."
              + " The ETag is the ruleset version and the key type; send it as `If-None-Match`"
              + " for a 304 while nothing has changed. Sent gzipped to a client that accepts gzip,"
              + " with the same ETag either way.")
  @ApiResponse(
      responseCode = "200",
      description = "The ruleset",
      content =
          @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string")))
  @ApiResponse(
      responseCode = "304",
      description = "Unchanged since the ETag sent",
      content =
          @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(type = "string")))
  void config(
      @AuthenticationPrincipal SdkCredential credential,
      @Parameter(description = "An ETag from an earlier response")
          @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
          String ifNoneMatch,
      @Parameter(description = "`gzip` to receive the ruleset compressed")
          @RequestHeader(value = HttpHeaders.ACCEPT_ENCODING, required = false)
          String acceptEncoding,
      HttpServletResponse response)
      throws IOException {
    RulesetSnapshot snapshot = snapshot(credential);
    String etag = snapshot.etag(credential.keyType());
    // The body depends on the request's Accept-Encoding as well as on its key, and a 304 carries
    // the Vary its 200 would have.
    response.addHeader(HttpHeaders.VARY, HttpHeaders.ACCEPT_ENCODING);
    response.setHeader(HttpHeaders.ETAG, etag);
    if (matches(ifNoneMatch, etag)) {
      response.setStatus(HttpStatus.NOT_MODIFIED.value());
      return;
    }
    ServedBody body = snapshot.served(credential.keyType());
    ServedBody.Coding coding =
        acceptsGzip(acceptEncoding) ? ServedBody.Coding.GZIP : ServedBody.Coding.IDENTITY;
    response.setStatus(HttpStatus.OK.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    if (coding == ServedBody.Coding.GZIP) {
      response.setHeader(HttpHeaders.CONTENT_ENCODING, "gzip");
    }
    response.setContentLength(body.length(coding));
    body.copyTo(response.getOutputStream(), coding);
  }

  @PostMapping("/evaluate")
  @Operation(
      summary = "Evaluate flags on the server",
      description =
          "For clients that cannot hold a ruleset. Runs the same engine as the SDK against the"
              + " same ruleset the key would download, so it answers exactly as in-process"
              + " evaluation would (FR-SRV-002). An unknown flag, or one this key may not read, is"
              + " the caller's fallback with reason `FLAG_NOT_FOUND`: never a 404 (FR-EVL-007).")
  @ApiResponse(responseCode = "200", description = "One result per flag")
  @ApiResponse(
      responseCode = "400",
      description = "Malformed body, or a flag without a fallback",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  EvaluateResponse evaluate(
      @AuthenticationPrincipal SdkCredential credential,
      @Valid @RequestBody EvaluateRequest request) {
    RulesetSnapshot snapshot = snapshot(credential);
    Ruleset ruleset = snapshot.ruleset(credential.keyType());
    UserContext user =
        request.context() == null
            ? UserContext.anonymous()
            : UserContext.fromRaw(request.context().key(), request.context().attributes());
    Map<String, EvaluateResponse.Result> results = new LinkedHashMap<>();
    for (EvaluateRequest.FlagRequest flag : request.flags()) {
      Evaluation evaluation = evaluator.evaluate(ruleset, flag.key(), user, flag.fallback());
      results.put(flag.key(), new EvaluateResponse.Result(evaluation.value(), evaluation.reason()));
    }
    return new EvaluateResponse(snapshot.environmentKey(), snapshot.version(), results);
  }

  private RulesetSnapshot snapshot(SdkCredential credential) {
    return cache
        .get(credential.environmentId())
        .orElseThrow(() -> new RulesetNotReadyException(RETRY_AFTER_SECONDS));
  }

  /**
   * RFC 9110 {@code Accept-Encoding}: whether gzip is acceptable, named or through {@code *}, with
   * a quality above zero. Without the header the body is sent as it is, because a client that did
   * not ask, such as {@code curl} without {@code --compressed}, may not decode it.
   */
  static boolean acceptsGzip(String acceptEncoding) {
    if (acceptEncoding == null) {
      return false;
    }
    Boolean gzip = null;
    Boolean any = null;
    for (String entry : acceptEncoding.split(",")) {
      String[] parts = entry.split(";");
      String coding = parts[0].strip().toLowerCase(Locale.ROOT);
      boolean acceptable = true;
      for (int i = 1; i < parts.length; i++) {
        String parameter = parts[i].strip().toLowerCase(Locale.ROOT);
        if (parameter.startsWith("q=")) {
          acceptable = !ZERO_QUALITY.matcher(parameter.substring(2).strip()).matches();
        }
      }
      if (coding.equals("gzip") || coding.equals("x-gzip")) {
        gzip = Boolean.TRUE.equals(gzip) || acceptable;
      } else if (coding.equals("*")) {
        any = acceptable;
      }
    }
    return gzip != null ? gzip : Boolean.TRUE.equals(any);
  }

  /** RFC 9110 {@code If-None-Match}: a list of entity tags, weak or strong, or {@code *}. */
  static boolean matches(String ifNoneMatch, String etag) {
    if (ifNoneMatch == null) {
      return false;
    }
    return Arrays.stream(ifNoneMatch.split(","))
        .map(String::strip)
        .map(tag -> tag.startsWith("W/") ? tag.substring(2) : tag)
        .anyMatch(tag -> tag.equals("*") || tag.equals(etag));
  }
}
