package io.github.sanduniliyanage.flaglane.streaming.web;

import io.github.sanduniliyanage.flaglane.apikey.domain.SdkCredential;
import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.serving.service.RulesetCache;
import io.github.sanduniliyanage.flaglane.serving.web.RulesetNotReadyException;
import io.github.sanduniliyanage.flaglane.streaming.service.StreamRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /sdk/stream}: Server-Sent Events telling an SDK, within moments, that its
 * environment's ruleset has changed (FR-SRV-003). Like the rest of the serving API it reads memory
 * only, so it keeps its streams through a database outage (NFR-PER-004).
 */
@RestController
@RequestMapping("/sdk")
@Tag(name = "Serving", description = "For SDKs, with an API key")
@SecurityRequirement(name = OpenApiConfiguration.SDK_KEY)
public class StreamController {

  static final String RETRY_AFTER_SECONDS = "5";

  /** Asks nginx and proxies that follow it not to hold events back in a buffer. */
  static final String NO_PROXY_BUFFERING = "X-Accel-Buffering";

  private final StreamRegistry streams;
  private final RulesetCache cache;

  public StreamController(StreamRegistry streams, RulesetCache cache) {
    this.streams = streams;
    this.cache = cache;
  }

  @GetMapping("/stream")
  @Operation(
      summary = "Subscribe to ruleset changes",
      description =
          "Server-Sent Events. The first event, and one each time the environment's ruleset"
              + " changes, is `ruleset-changed`, with the event id and the data's `version` the"
              + " version now served: download it with `GET /sdk/config` and the ETag held. The"
              + " event never carries the ruleset. A comment arrives at least every 30 seconds."
              + " The server ends a stream after 30 minutes by default, and on shutdown; reconnect,"
              + " and keep polling while there is no stream. A stream is charged against the key's"
              + " allowance once, when it opens.")
  @ApiResponse(
      responseCode = "200",
      description = "The stream",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              schema = @Schema(type = "string")))
  @ApiResponse(
      responseCode = "401",
      description = "Missing, malformed or revoked key",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "429",
      description =
          "The key's allowance is used up, or the key or its environment holds as many streams as"
              + " it may; retry after the time given, and poll meanwhile",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "503",
      description =
          "The ruleset is not loaded yet, or the server is shutting down; retry after the time"
              + " given",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  SseEmitter stream(
      @AuthenticationPrincipal SdkCredential credential, HttpServletResponse response) {
    if (cache.get(credential.environmentId()).isEmpty()) {
      throw new RulesetNotReadyException(RETRY_AFTER_SECONDS);
    }
    SseEmitter emitter =
        streams
            .open(credential)
            .orElseThrow(() -> new StreamsUnavailableException(RETRY_AFTER_SECONDS));
    response.setHeader(NO_PROXY_BUFFERING, "no");
    return emitter;
  }
}
