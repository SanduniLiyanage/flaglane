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
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
@SecurityScheme(
    name = OpenApiConfiguration.SDK_KEY,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "flg_srv_… or flg_cli_…",
    description =
        "An API key issued for one environment. A server key reads every flag; a client key"
            + " reads client-side-visible flags only, with no user overrides.")
public class OpenApiConfiguration {

  /** The scheme name a {@code /api/**} endpoint refers to with {@code @SecurityRequirement}. */
  public static final String BEARER_JWT = "bearer-jwt";

  /** The scheme name an {@code /sdk/**} endpoint refers to. */
  public static final String SDK_KEY = "sdk-key";

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

  /**
   * Marks every property of a success response's schemas required, because a response record writes
   * every component, null or not; left unmarked, a client generated from the document would treat
   * every field of every answer as possibly absent. A schema that a request body also uses keeps
   * its declared requirements: in a request, leaving a field out means something. Error answers are
   * Problem Details and are labelled with the media type they are sent as.
   */
  @Bean
  OpenApiCustomizer describeResponses() {
    return openApi -> {
      if (openApi.getPaths() == null || openApi.getComponents() == null) {
        return;
      }
      Map<String, Schema> schemas = openApi.getComponents().getSchemas();
      if (schemas == null) {
        return;
      }
      Set<String> answered = new HashSet<>();
      Set<String> sent = new HashSet<>();
      for (PathItem item : openApi.getPaths().values()) {
        for (Operation operation : item.readOperations()) {
          if (operation.getRequestBody() != null
              && operation.getRequestBody().getContent() != null) {
            operation
                .getRequestBody()
                .getContent()
                .values()
                .forEach(media -> collect(media.getSchema(), schemas, sent));
          }
          if (operation.getResponses() == null) {
            continue;
          }
          operation
              .getResponses()
              .forEach(
                  (status, response) -> {
                    if (response.getContent() == null) {
                      return;
                    }
                    if (status.startsWith("2")) {
                      response
                          .getContent()
                          .values()
                          .forEach(media -> collect(media.getSchema(), schemas, answered));
                    } else {
                      relabelProblems(response.getContent());
                    }
                  });
        }
      }
      answered.removeAll(sent);
      for (String name : answered) {
        Schema<?> schema = schemas.get(name);
        if (schema != null && schema.getProperties() != null) {
          schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
        }
      }
    };
  }

  /**
   * Adds the component schemas {@code schema} refers to, and the ones they refer to, to {@code
   * into}.
   */
  private static void collect(Schema<?> schema, Map<String, Schema> schemas, Set<String> into) {
    if (schema == null) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null) {
      String name = ref.substring(ref.lastIndexOf('/') + 1);
      if (into.add(name)) {
        collect(schemas.get(name), schemas, into);
      }
      return;
    }
    if (schema.getProperties() != null) {
      schema.getProperties().values().forEach(property -> collect(property, schemas, into));
    }
    collect(schema.getItems(), schemas, into);
    if (schema.getAdditionalProperties() instanceof Schema<?> values) {
      collect(values, schemas, into);
    }
  }

  private static void relabelProblems(Content content) {
    String problem = "#/components/schemas/ProblemDetail";
    List<String> labels =
        content.entrySet().stream()
            .filter(entry -> entry.getValue().getSchema() != null)
            .filter(entry -> problem.equals(entry.getValue().getSchema().get$ref()))
            .map(Map.Entry::getKey)
            .toList();
    for (String label : labels) {
      MediaType media = content.remove(label);
      content.addMediaType(
          org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE, media);
    }
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
