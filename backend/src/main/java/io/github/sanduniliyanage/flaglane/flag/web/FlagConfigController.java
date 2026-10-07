package io.github.sanduniliyanage.flaglane.flag.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.flag.domain.ConfigChange;
import io.github.sanduniliyanage.flaglane.flag.domain.RolloutPercentage;
import io.github.sanduniliyanage.flaglane.flag.service.FlagConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** One flag's configuration in one environment. */
@RestController
@RequestMapping("/api/projects/{projectKey}/flags/{flagKey}/config/{envKey}")
@Tag(name = "Flags")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
@ApiResponse(
    responseCode = "404",
    description = "No such project, environment or flag among yours",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class FlagConfigController {

  private final FlagConfigService configs;

  public FlagConfigController(FlagConfigService configs) {
    this.configs = configs;
  }

  @GetMapping
  @Operation(summary = "Get a flag's configuration in an environment")
  @ApiResponse(responseCode = "200", description = "The configuration")
  FlagConfigResponse get(EnvironmentScope environment, @PathVariable String flagKey) {
    return FlagConfigResponse.from(configs.get(environment, flagKey));
  }

  @PatchMapping
  @Operation(
      summary = "Change a flag's configuration in an environment",
      description =
          "Kill switch, fallthrough value, rollout percentage and rollout salt. Takes effect on"
              + " the environment's next ruleset. **Changing `rolloutSalt` re-buckets every user"
              + " of this flag.**")
  @ApiResponse(
      responseCode = "409",
      description = "The flag is archived",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(responseCode = "200", description = "The configuration as changed")
  FlagConfigResponse update(
      EnvironmentScope environment,
      @PathVariable String flagKey,
      @Valid @RequestBody UpdateFlagConfigRequest request) {
    Integer basisPoints =
        request.rolloutPercentage() == null
            ? null
            : RolloutPercentage.toBasisPoints(request.rolloutPercentage());
    return FlagConfigResponse.from(
        configs.update(
            environment,
            flagKey,
            new ConfigChange(
                request.enabled(),
                request.fallthroughValue(),
                basisPoints,
                request.rolloutSalt())));
  }
}
