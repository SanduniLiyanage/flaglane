package io.github.sanduniliyanage.flaglane.audit.web;

import io.github.sanduniliyanage.flaglane.audit.domain.AuditCursor;
import io.github.sanduniliyanage.flaglane.audit.service.AuditTrail;
import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A project's audit trail (FR-AUD-003). The project in the path is resolved against the signed-in
 * user's own before this runs; another user's is a 404.
 */
@RestController
@RequestMapping("/api/projects/{projectKey}/audit")
@Tag(name = "Audit", description = "The append-only record of every change to a project")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
public class AuditController {

  private final AuditTrail trail;

  public AuditController(AuditTrail trail) {
    this.trail = trail;
  }

  @GetMapping
  @Operation(
      summary = "Read the audit trail",
      description =
          "Newest first, a page at a time. Pagination is keyset on (created_at, id), not offset:"
              + " pass a page's `next` as `before` for the page after it, and an entry recorded"
              + " meanwhile neither repeats nor shifts what follows (E-025).")
  @ApiResponse(responseCode = "200", description = "One page")
  @ApiResponse(
      responseCode = "400",
      description = "A `before` that is not a cursor, or a `limit` outside 1 to 100",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "404",
      description = "No such project among yours, or no such environment or flag in it",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  AuditPageResponse page(
      ProjectScope project,
      @Parameter(description = "Only changes to this environment", example = "production")
          @RequestParam(required = false)
          String environment,
      @Parameter(description = "Only changes to this flag", example = "new-checkout")
          @RequestParam(required = false)
          String flag,
      @Parameter(
              description = "The `next` of the previous page; leave out for the newest entries",
              schema = @Schema(type = "string"))
          @RequestParam(required = false)
          AuditCursor before,
      @Parameter(description = "Entries per page, 1 to 100")
          @RequestParam(defaultValue = "" + AuditTrail.DEFAULT_LIMIT)
          @Min(1)
          @Max(AuditTrail.MAX_LIMIT)
          int limit) {
    return AuditMapper.toResponse(trail.page(project, environment, flag, before, limit));
  }
}
