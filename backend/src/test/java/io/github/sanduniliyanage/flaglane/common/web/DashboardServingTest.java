package io.github.sanduniliyanage.flaglane.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

/**
 * The API serves the dashboard from its own origin (ADR-031): the page at its routes, its assets,
 * and nothing else that was closed before. A fixture stands in for the build, which the image puts
 * where {@code spring.web.resources.static-locations} points.
 */
@FlaglaneIntegrationTest
@TestPropertySource(
    properties = "spring.web.resources.static-locations=classpath:/dashboard-fixture/")
class DashboardServingTest {

  private final TestRestTemplate http;

  DashboardServingTest(@Autowired TestRestTemplate http) {
    this.http = http;
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/",
        "/sign-in",
        "/projects",
        "/projects/storefront",
        "/projects/storefront/production/flags",
        "/projects/storefront/production/flags/new-checkout"
      })
  void everyDashboardRouteIsAnsweredWithItsPage(String path) {
    ResponseEntity<String> response = http.getForEntity(path, String.class);

    assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType())
        .as(path)
        .satisfies(type -> assertThat(type.isCompatibleWith(MediaType.TEXT_HTML)).isTrue());
    assertThat(response.getBody()).as(path).contains("dashboard-fixture");
  }

  @Test
  void theBuiltAssetsAreServed() {
    ResponseEntity<String> response = http.getForEntity("/assets/app.js", String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).contains("dashboard-fixture asset");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/internal", "/projectsx", "/admin/index.html", "/actuator/env"})
  void pathsThatAreNotTheDashboardsStayClosed(String path) {
    assertThat(http.getForEntity(path, String.class).getStatusCode())
        .as(path)
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theDashboardsRoutesAreReadOnly() {
    assertThat(http.postForEntity("/projects", "{}", String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  void theManagementApiIsNotShadowedByTheDashboard() {
    assertThat(http.getForEntity("/api/projects", String.class).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
