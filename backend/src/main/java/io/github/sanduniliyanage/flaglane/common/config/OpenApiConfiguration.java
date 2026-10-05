package io.github.sanduniliyanage.flaglane.common.config;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.OwnerScope;
import io.github.sanduniliyanage.flaglane.common.tenancy.ProjectScope;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Document-level metadata for the generated OpenAPI description at {@code /v3/api-docs}. Every
 * endpoint is documented from its own source (NFR-MNT-002); this class only names the document and
 * the authentication schemes endpoints refer to, and teaches the generator about tenant scopes.
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

  private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");

  static {
    // Scopes are resolved from the path and the token, not sent by the caller.
    SpringDocUtils.getConfig()
        .addRequestWrapperToIgnore(OwnerScope.class, ProjectScope.class, EnvironmentScope.class);
  }

  /**
   * Declares every variable a path names. Controllers receive {@code {projectKey}} and {@code
   * {envKey}} as resolved scopes rather than as parameters, so the generator would otherwise leave
   * them undeclared, which OpenAPI does not allow.
   */
  @Bean
  OpenApiCustomizer declarePathVariables() {
    return openApi -> {
      if (openApi.getPaths() == null) {
        return;
      }
      openApi
          .getPaths()
          .forEach(
              (path, item) -> {
                Matcher variables = PATH_VARIABLE.matcher(path);
                while (variables.find()) {
                  String name = variables.group(1);
                  item.readOperations().forEach(operation -> declare(operation, name));
                }
              });
    };
  }

  private static void declare(Operation operation, String name) {
    List<Parameter> declared = operation.getParameters();
    boolean present =
        declared != null
            && declared.stream()
                .anyMatch(
                    parameter ->
                        "path".equals(parameter.getIn()) && name.equals(parameter.getName()));
    if (!present) {
      operation.addParametersItem(new PathParameter().name(name).schema(new StringSchema()));
    }
  }
}
