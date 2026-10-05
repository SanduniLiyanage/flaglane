package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Suite 5 — tenant isolation (NFR-SEC-004, FR-PRJ-003). Signed in as Alice, every tenant-scoped
 * endpoint is called against Bob's project, reading and mutating, and every call must be a 404.
 *
 * <p>The endpoints are not listed here. They are enumerated at runtime from Spring's {@link
 * RequestMappingHandlerMapping}, and every {@code /api/**} mapping must be either tenant-scoped —
 * its path names {@code {projectKey}} — or on the reviewed list below. Anything unclassified fails
 * the build: a hand-written list only proves things about the endpoints someone remembered, which
 * is exactly the endpoint this suite exists to catch.
 */
@FlaglaneIntegrationTest
class TenantIsolationTest {

  /**
   * {@code /api/**} endpoints that are not about one project, each reviewed: open by design, or
   * scoped to the caller without naming a project.
   */
  private static final Map<String, String> NOT_TENANT_SCOPED =
      Map.of(
          "POST /api/auth/register", "open: creates an account, owns nothing yet",
          "POST /api/auth/login", "open: issues a token for the credentials given",
          "GET /api/projects", "the caller's own projects, by owner",
          "POST /api/projects", "creates a project owned by the caller");

  private static final Pattern VARIABLE = Pattern.compile("\\{([^}]+)}");

  private final ManagementApiClient api;
  private final RequestMappingHandlerMapping mappings;

  private String alice;
  private String bob;
  private String alicesProject;
  private String bobsProject;

  /**
   * Bob's resources below the environment, by path variable. Each new kind of child resource adds
   * an entry here, or the suite fails naming the variable it has no value for.
   */
  private Map<String, String> bobsChildren;

  TenantIsolationTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings) {
    this.api = new ManagementApiClient(http, json);
    this.mappings = mappings;
  }

  @BeforeEach
  void twoTenants() {
    alice = api.signUp("alice");
    bob = api.signUp("bob");
    alicesProject = api.createProject(alice, "alpha");
    bobsProject = api.createProject(bob, "beta");
    api.post(
        "/api/projects/" + bobsProject + "/environments",
        bob,
        Map.of("key", "bob-only", "name", "Bob only"));
    String bobsKeyId =
        api.read(api.post(bobsKeys(), bob, Map.of("name", "bob's key", "type", "server")))
            .path("id")
            .asText();
    String bobsFlagKey = ManagementApiClient.uniqueKey("bob-flag");
    api.post(
        "/api/projects/" + bobsProject + "/flags",
        bob,
        Map.of("key", bobsFlagKey, "name", "Bob's flag"));
    bobsChildren = Map.of("keyId", bobsKeyId, "flagKey", bobsFlagKey);
  }

  @Test
  void everyManagementEndpointIsEitherTenantScopedOrReviewed() {
    List<String> unclassified = new ArrayList<>();
    for (Endpoint endpoint : managementEndpoints()) {
      if (!endpoint.isTenantScoped() && !NOT_TENANT_SCOPED.containsKey(endpoint.toString())) {
        unclassified.add(endpoint.toString());
      }
    }

    assertThat(unclassified)
        .as("/api endpoints neither naming {projectKey} nor on the reviewed list")
        .isEmpty();
    assertThat(managementEndpoints().stream().map(Endpoint::toString).toList())
        .as("reviewed endpoints that no longer exist")
        .containsAll(NOT_TENANT_SCOPED.keySet());
  }

  @Test
  void anotherTenantsProjectIsNotFoundFromEveryTenantScopedEndpoint() {
    assertEveryEndpointIsNotFound(
        with(Map.of("projectKey", bobsProject, "envKey", "production")),
        endpoint -> true,
        "on Bob's project, as Alice");
  }

  @Test
  void anotherTenantsEnvironmentIsNotFoundInsideYourOwnProject() {
    assertEveryEndpointIsNotFound(
        with(Map.of("projectKey", alicesProject, "envKey", "bob-only")),
        endpoint -> endpoint.variables().contains("envKey"),
        "on Alice's project naming Bob's environment");
  }

  @Test
  void anotherTenantsResourcesAreNotFoundInsideYourOwnEnvironment() {
    assertEveryEndpointIsNotFound(
        with(Map.of("projectKey", alicesProject, "envKey", "production")),
        endpoint -> !Set.of("projectKey", "envKey").containsAll(endpoint.variables()),
        "on Alice's own environment naming Bob's resources");
  }

  private void assertEveryEndpointIsNotFound(
      Map<String, String> values, Predicate<Endpoint> applies, String description) {
    SoftAssertions softly = new SoftAssertions();
    int attempts = 0;

    for (Endpoint endpoint : tenantScopedEndpoints()) {
      if (!applies.test(endpoint)) {
        continue;
      }
      ResponseEntity<String> response = call(endpoint, values);
      attempts++;

      softly
          .assertThat(response.getStatusCode())
          .as("%s %s", endpoint, description)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    softly.assertAll();
    assertThat(attempts).as("endpoints attempted %s", description).isPositive();
    assertBobsProjectIsUntouched();
  }

  private Map<String, String> with(Map<String, String> scope) {
    Map<String, String> values = new HashMap<>(bobsChildren);
    values.putAll(scope);
    return values;
  }

  private String bobsKeys() {
    return "/api/projects/" + bobsProject + "/environments/production/keys";
  }

  private ResponseEntity<String> call(Endpoint endpoint, Map<String, String> values) {
    String path = endpoint.fill(values);
    Object body =
        endpoint.method().equals("GET") || endpoint.method().equals("DELETE") ? null : Map.of();
    return api.exchange(HttpMethod.valueOf(endpoint.method()), path, alice, body);
  }

  private void assertBobsProjectIsUntouched() {
    List<String> environments =
        StreamSupport.stream(
                api.read(api.get("/api/projects/" + bobsProject + "/environments", bob))
                    .spliterator(),
                false)
            .map(environment -> environment.path("key").asText())
            .toList();
    assertThat(environments)
        .as("Bob's environments after Alice's attempts")
        .containsExactlyInAnyOrder("development", "staging", "production", "bob-only");
    assertThat(api.read(api.get(bobsKeys(), bob)).get(0).path("revokedAt").isNull())
        .as("Bob's key is still live after Alice's attempts")
        .isTrue();
    JsonNode bobsFlags = api.read(api.get("/api/projects/" + bobsProject + "/flags", bob));
    assertThat(bobsFlags.size()).as("Bob's flags after Alice's attempts").isEqualTo(1);
    assertThat(bobsFlags.get(0).path("archivedAt").isNull())
        .as("Bob's flag is still live after Alice's attempts")
        .isTrue();
  }

  private List<Endpoint> tenantScopedEndpoints() {
    return managementEndpoints().stream().filter(Endpoint::isTenantScoped).toList();
  }

  private List<Endpoint> managementEndpoints() {
    Set<Endpoint> endpoints = new TreeSet<>();
    for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
      Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
      for (String pattern : info.getPatternValues()) {
        if (!pattern.startsWith("/api/")) {
          continue;
        }
        if (methods.isEmpty()) {
          endpoints.add(new Endpoint("ANY", pattern));
        }
        methods.forEach(method -> endpoints.add(new Endpoint(method.name(), pattern)));
      }
    }
    return List.copyOf(endpoints);
  }

  /** One method on one path pattern. */
  private record Endpoint(String method, String pattern) implements Comparable<Endpoint> {

    boolean isTenantScoped() {
      return variables().contains("projectKey");
    }

    Set<String> variables() {
      Set<String> names = new TreeSet<>();
      Matcher matcher = VARIABLE.matcher(pattern);
      while (matcher.find()) {
        names.add(matcher.group(1));
      }
      return names;
    }

    String fill(Map<String, String> values) {
      Matcher matcher = VARIABLE.matcher(pattern);
      StringBuilder path = new StringBuilder();
      while (matcher.find()) {
        String value = values.get(matcher.group(1));
        if (value == null) {
          throw new AssertionError(
              "Suite 5 has no value for {"
                  + matcher.group(1)
                  + "} in "
                  + this
                  + "; give it one of Bob's resources");
        }
        matcher.appendReplacement(path, Matcher.quoteReplacement(value));
      }
      matcher.appendTail(path);
      return path.toString();
    }

    @Override
    public int compareTo(Endpoint other) {
      return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
      return method + " " + pattern;
    }
  }
}
