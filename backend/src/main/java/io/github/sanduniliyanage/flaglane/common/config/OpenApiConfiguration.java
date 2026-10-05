package io.github.sanduniliyanage.flaglane.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Document-level metadata for the generated OpenAPI description at {@code /v3/api-docs}. Every
 * endpoint is documented from its own source (NFR-MNT-002); this class only names the document and
 * the authentication schemes endpoints refer to.
 *
 * <p>The version is stated here as well as in {@code backend/build.gradle.kts}; bump both.
 */
@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "Flaglane API",
            version = "0.1.0",
            description =
                "Feature flags and remote configuration. `/api/**` serves the dashboard with a"
                    + " JWT; `/sdk/**` serves SDKs with an API key.",
            license =
                @License(
                    name = "Apache License 2.0",
                    url = "https://www.apache.org/licenses/LICENSE-2.0")))
@SecurityScheme(
    name = OpenApiConfiguration.BEARER_JWT,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
    description = "An access token from `POST /api/auth/login`, valid for 8 hours.")
public class OpenApiConfiguration {

  /** The scheme name a {@code /api/**} endpoint refers to with {@code @SecurityRequirement}. */
  public static final String BEARER_JWT = "bearer-jwt";
}
