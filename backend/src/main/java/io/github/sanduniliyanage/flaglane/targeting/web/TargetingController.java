package io.github.sanduniliyanage.flaglane.targeting.web;

import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.targeting.domain.Rule;
import io.github.sanduniliyanage.flaglane.targeting.domain.UserOverride;
import io.github.sanduniliyanage.flaglane.targeting.service.TargetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A flag's targeting rules and user overrides in one environment, each replaced as a whole. */
@RestController
@RequestMapping("/api/projects/{projectKey}/flags/{flagKey}/config/{envKey}")
@Tag(name = "Targeting")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
@ApiResponse(
    responseCode = "404",
    description = "No such project, environment or flag among yours",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class TargetingController {

  private final TargetingService targeting;

  public TargetingController(TargetingService targeting) {
    this.targeting = targeting;
  }

  /** {@code {"rules": [...]}}. */
  public record RulesResponse(List<RuleRequest> rules) {

    public RulesResponse {
      rules = List.copyOf(rules);
    }

    static RulesResponse from(List<Rule> rules) {
      return new RulesResponse(
          rules.stream()
              .map(
                  rule ->
                      new RuleRequest(
                          rule.attribute(),
                          rule.operator(),
                          rule.matchValues(),
                          rule.resultValue()))
              .toList());
    }
  }

  /** {@code {"overrides": [...]}}. */
  public record OverridesResponse(List<OverrideRequest> overrides) {

    public OverridesResponse {
      overrides = List.copyOf(overrides);
    }

    static OverridesResponse from(List<UserOverride> overrides) {
      return new OverridesResponse(
          overrides.stream()
              .map(override -> new OverrideRequest(override.userKey(), override.value()))
              .toList());
    }
  }

  @GetMapping("/rules")
  @Operation(summary = "Get a flag's rules in an environment", description = "In priority order.")
  RulesResponse rules(EnvironmentScope environment, @PathVariable String flagKey) {
    return RulesResponse.from(targeting.rules(environment, flagKey));
  }

  @PutMapping("/rules")
  @Operation(
      summary = "Replace a flag's rules in an environment",
      description =
          "The whole ordered list at once, in one transaction, so no partial or reordered state"
              + " is ever served (FR-RUL-004). A rule the engine could not apply is refused"
              + " (FR-RUL-010).")
  @ApiResponse(
      responseCode = "400",
      description = "A rule cannot be applied; `errors` names it",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  @ApiResponse(
      responseCode = "409",
      description = "The flag is archived",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  RulesResponse replaceRules(
      EnvironmentScope environment,
      @PathVariable String flagKey,
      @Valid @RequestBody ReplaceRulesRequest request) {
    return RulesResponse.from(
        targeting.replaceRules(
            environment, flagKey, request.rules().stream().map(RuleRequest::toRule).toList()));
  }

  @GetMapping("/overrides")
  @Operation(summary = "Get a flag's user overrides in an environment")
  OverridesResponse overrides(EnvironmentScope environment, @PathVariable String flagKey) {
    return OverridesResponse.from(targeting.overrides(environment, flagKey));
  }

  @PutMapping("/overrides")
  @Operation(
      summary = "Replace a flag's user overrides in an environment",
      description =
          "The whole set at once. Overrides apply to server keys only: a client key's ruleset"
              + " carries none, because their user keys are real user identifiers (FR-KEY-008).")
  @ApiResponse(
      responseCode = "409",
      description = "The flag is archived",
      content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
  OverridesResponse replaceOverrides(
      EnvironmentScope environment,
      @PathVariable String flagKey,
      @Valid @RequestBody ReplaceOverridesRequest request) {
    return OverridesResponse.from(
        targeting.replaceOverrides(
            environment,
            flagKey,
            request.overrides().stream().map(OverrideRequest::toOverride).toList()));
  }
}
