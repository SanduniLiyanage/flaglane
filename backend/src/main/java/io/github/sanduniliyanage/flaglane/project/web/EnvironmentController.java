package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.github.sanduniliyanage.flaglane.project.service.EnvironmentService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Environments of one project. The project and environment in the path are resolved against the
 * signed-in user's own before any of these methods run; another user's are a 404.
 */
@RestController
@RequestMapping("/api/projects/{projectKey}/environments")
@Tag(name = "Environments")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
@ApiResponse(
    responseCode = "404",
    description = "No such project or environment among yours",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class EnvironmentController {

  private final EnvironmentService environments;

  public EnvironmentController(EnvironmentService environments) {
    this.environments = environments;
  }

  @GetMapping
  @Operation(summary = "List a project's environments")
  @ApiResponse(responseCode = "200", description = "The project's environments")
  List<EnvironmentResponse> list(ProjectScope project) {
    return environments.list(project).stream().map(EnvironmentResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create an environment",
      description =
          "Every flag in the project gets a configuration here, disabled, falling through to"
              + " `false` at 0% (FR-ENV-004).")
  @ApiResponse(responseCode = "201", description = "Created")
  @ApiResponse(
      responseCode = "409",
      description = "The project already has an environment with this key",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  EnvironmentResponse create(
      ProjectScope project, @Valid @RequestBody CreateEnvironmentRequest request) {
    return EnvironmentResponse.from(environments.create(project, request.key(), request.name()));
  }

  @DeleteMapping("/{envKey}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Delete an environment",
      description =
          "Deletes the environment with its keys, configurations, rules and overrides. Its"
              + " audit entries are kept. Refused while it holds a key that is not revoked"
              + " (FR-ENV-003).")
  @ApiResponse(responseCode = "204", description = "Deleted")
  @ApiResponse(
      responseCode = "409",
      description = "The environment holds an API key that is not revoked",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  void delete(EnvironmentScope environment) {
    environments.delete(environment);
  }
}
