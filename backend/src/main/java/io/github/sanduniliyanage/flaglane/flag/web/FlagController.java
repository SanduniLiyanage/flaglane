package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.flag.domain.FlagChange;
import io.github.sanduniliyanage.flaglane.flag.service.FlagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A project's flags. */
@RestController
@RequestMapping("/api/projects/{projectKey}/flags")
@Tag(name = "Flags")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
@ApiResponse(
    responseCode = "404",
    description = "No such project or flag among yours",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class FlagController {

  private final FlagService flags;

  public FlagController(FlagService flags) {
    this.flags = flags;
  }

  @GetMapping
  @Operation(summary = "List a project's flags", description = "Archived flags included.")
  List<FlagResponse> list(ProjectScope project) {
    return flags.list(project).stream().map(FlagResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a flag",
      description =
          "Every environment gets a configuration: disabled, falling through to `false`, at 0%"
              + " (FR-FLG-003).")
  @ApiResponse(responseCode = "201", description = "Created")
  @ApiResponse(
      responseCode = "409",
      description = "The key is used by a live or an archived flag",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  FlagResponse create(ProjectScope project, @Valid @RequestBody CreateFlagRequest request) {
    return FlagResponse.from(
        flags.create(
            project,
            request.key(),
            request.name(),
            request.description(),
            Boolean.TRUE.equals(request.clientSideVisible())));
  }

  @GetMapping("/{flagKey}")
  @Operation(summary = "Get a flag")
  FlagResponse get(ProjectScope project, @PathVariable String flagKey) {
    return FlagResponse.from(flags.get(project, flagKey));
  }

  @PatchMapping("/{flagKey}")
  @Operation(
      summary = "Edit a flag",
      description = "Name, description and client-side visibility. The key never changes.")
  @ApiResponse(
      responseCode = "409",
      description = "The flag is archived",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  FlagResponse update(
      ProjectScope project,
      @PathVariable String flagKey,
      @Valid @RequestBody UpdateFlagRequest request) {
    return FlagResponse.from(
        flags.update(
            project,
            flagKey,
            new FlagChange(request.name(), request.description(), request.clientSideVisible())));
  }

  @PostMapping("/{flagKey}/archive")
  @Operation(
      summary = "Archive a flag",
      description =
          "Removes it from every ruleset. Never a deletion: its history stays, its key stays"
              + " reserved, and it can be restored (FR-FLG-005).")
  FlagResponse archive(ProjectScope project, @PathVariable String flagKey) {
    return FlagResponse.from(flags.archive(project, flagKey));
  }

  @PostMapping("/{flagKey}/restore")
  @Operation(
      summary = "Restore an archived flag",
      description = "Same key, same configurations (FR-FLG-007).")
  FlagResponse restore(ProjectScope project, @PathVariable String flagKey) {
    return FlagResponse.from(flags.restore(project, flagKey));
  }
}
