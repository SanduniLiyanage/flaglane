package io.github.sanduniliyanage.flaglane;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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
    Map<String, String> bobsResources = Map.of("projectKey", bobsProject, "envKey", "bob-only");
    SoftAssertions softly = new SoftAssertions();

    for (Endpoint endpoint : tenantScopedEndpoints()) {
      ResponseEntity<String> response = call(endpoint, bobsResources);

      softly
          .assertThat(response.getStatusCode())
          .as("%s on Bob's project, as Alice", endpoint)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    softly.assertAll();
    assertBobsProjectIsUntouched();
  }

  @Test
  void anotherTenantsResourceIsNotFoundEvenInsideYourOwnProject() {
    Map<String, String> bobsInsideAlices =
        Map.of("projectKey", alicesProject, "envKey", "bob-only");
    SoftAssertions softly = new SoftAssertions();

    for (Endpoint endpoint : tenantScopedEndpoints()) {
      if (endpoint.variables().equals(Set.of("projectKey"))) {
        continue;
      }
      ResponseEntity<String> response = call(endpoint, bobsInsideAlices);

      softly
          .assertThat(response.getStatusCode())
          .as("%s on Alice's project naming Bob's resources", endpoint)
          .isEqualTo(HttpStatus.NOT_FOUND);
    }

    softly.assertAll();
    assertBobsProjectIsUntouched();
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
