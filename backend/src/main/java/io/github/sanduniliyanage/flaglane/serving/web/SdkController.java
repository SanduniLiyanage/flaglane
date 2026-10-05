package io.github.sanduniliyanage.flaglane.serving.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.evaluation.Evaluation;
import io.github.sanduniliyanage.flaglane.evaluation.Evaluator;
import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import io.github.sanduniliyanage.flaglane.evaluation.UserContext;
import io.github.sanduniliyanage.flaglane.serving.domain.RulesetSnapshot;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
    responseCode = "503",
    description = "The ruleset is not loaded yet; retry after the time given",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class SdkController {

  static final String RETRY_AFTER_SECONDS = "5";

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
              + " for a 304 while nothing has changed.")
  @ApiResponse(responseCode = "200", description = "The ruleset")
  @ApiResponse(responseCode = "304", description = "Unchanged since the ETag sent")
  ResponseEntity<String> config(
      @AuthenticationPrincipal SdkCredential credential,
      @Parameter(description = "An ETag from an earlier response")
          @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
          String ifNoneMatch) {
    RulesetSnapshot snapshot = snapshot(credential);
    String etag = snapshot.etag(credential.keyType());
    if (matches(ifNoneMatch, etag)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
    }
    return ResponseEntity.ok()
        .eTag(etag)
        .contentType(MediaType.APPLICATION_JSON)
        .body(snapshot.body(credential.keyType()));
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
