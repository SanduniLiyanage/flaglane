package io.github.sanduniliyanage.flaglane.apikey.web;

import io.github.sanduniliyanage.flaglane.apikey.service.ApiKeyService;
import io.github.sanduniliyanage.flaglane.common.config.OpenApiConfiguration;
import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** An environment's API keys. */
@RestController
@RequestMapping("/api/projects/{projectKey}/environments/{envKey}/keys")
@Tag(name = "API keys")
@SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT)
@ApiResponse(
    responseCode = "404",
    description = "No such project, environment or key among yours",
    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
public class ApiKeyController {

  private final ApiKeyService keys;

  public ApiKeyController(ApiKeyService keys) {
    this.keys = keys;
  }

  @GetMapping
  @Operation(summary = "List an environment's keys", description = "Prefix and metadata only.")
  List<ApiKeyResponse> list(EnvironmentScope environment) {
    return keys.list(environment).stream().map(ApiKeyResponse::from).toList();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(
      summary = "Issue a key",
      description =
          "The response carries the key, and is the only response that ever will (FR-KEY-002)."
              + " Store it now; Flaglane keeps only a hash.")
  @ApiResponse(responseCode = "201", description = "Issued")
  IssuedApiKeyResponse issue(
      EnvironmentScope environment, @Valid @RequestBody IssueApiKeyRequest request) {
    return IssuedApiKeyResponse.from(keys.issue(environment, request.name(), request.type()));
  }

  @DeleteMapping("/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(
      summary = "Revoke a key",
      description =
          "Refused on every SDK request from the moment this returns (FR-KEY-003). Revoking a"
              + " revoked key is accepted and changes nothing.")
  @ApiResponse(responseCode = "204", description = "Revoked")
  void revoke(EnvironmentScope environment, @PathVariable UUID keyId) {
    keys.revoke(environment, keyId);
  }
}
