package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.flag.service.FlagConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Every flag's configuration in one environment, for the dashboard's flag list (FR-UI-003). */
@RestController
@Tag(name = "Flags")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
public class EnvironmentConfigsController {

  private final FlagConfigService configs;

  public EnvironmentConfigsController(FlagConfigService configs) {
    this.configs = configs;
  }

  @GetMapping("/api/projects/{projectKey}/environments/{envKey}/configs")
  @Operation(
      summary = "List every flag's configuration in an environment",
      description =
          "One configuration per flag, archived flags included, ordered by flag key. Saves a"
              + " client listing flags with their state one request per flag.")
  @ApiResponse(responseCode = "200", description = "The configurations")
  @ApiResponse(
      responseCode = "404",
      description = "No such project or environment among yours",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  List<FlagConfigResponse> list(EnvironmentScope environment) {
    return configs.list(environment).stream().map(FlagConfigResponse::from).toList();
  }
}
