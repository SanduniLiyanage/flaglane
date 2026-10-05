package io.github.sanduniliyanage.flaglane.project.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.project.service.ProjectService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's projects. */
@RestController
@RequestMapping("/api/projects")
@Tag(name = "Projects")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
public class ProjectController {

  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  @GetMapping
  @Operation(summary = "List your projects", description = "Only projects you own (FR-PRJ-003).")
  List<ProjectResponse> list(OwnerScope owner) {
    return projects.list(owner).stream().map(ProjectResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Create a project",
      description =
          "Creates the project with `development`, `staging` and `production` environments"
              + " (FR-PRJ-001, FR-ENV-001). The key is unique across Flaglane and can never"
              + " change.")
  @ApiResponse(responseCode = "201", description = "Created")
  @ApiResponse(
      responseCode = "409",
      description = "The key is taken",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  ProjectResponse create(OwnerScope owner, @Valid @RequestBody CreateProjectRequest request) {
    return ProjectResponse.from(projects.create(owner, request.key(), request.name()));
  }
}
