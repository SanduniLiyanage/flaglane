package io.github.sanduniliyanage.flaglane.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.common.security.JwtConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

/**
 * Register, sign in, and use the token, through the running application and a real database
 * (FR-ACC-001, FR-ACC-002). Slice 1.6 adds no endpoint behind the token yet, so an unmapped {@code
 * /api} path stands in: 404 with a valid token, 401 without one.
 */
@FlaglaneIntegrationTest
class AuthenticationEndToEndTest {

  private static final String PASSWORD = "correct-horse-battery";

  private final TestRestTemplate http;
  private final ObjectMapper json;
  private final JdbcTemplate database;
  private final JwtEncoder encoder;
  private final Clock clock;

  AuthenticationEndToEndTest(
      @Autowired TestRestTemplate http,
      @Autowired ObjectMapper json,
      @Autowired JdbcTemplate database,
      @Autowired JwtEncoder encoder,
      @Autowired Clock clock) {
    this.http = http;
    this.json = json;
    this.database = database;
    this.encoder = encoder;
    this.clock = clock;
  }

  @Test
  void aRegisteredUserSignsInAndTheTokenOpensTheManagementApi() throws Exception {
    String email = unique("Amara.Perera");
    ResponseEntity<String> registered = register(email.toUpperCase(Locale.ROOT), PASSWORD);
    ResponseEntity<String> signedIn = login(email, PASSWORD);
    String token = json.readTree(signedIn.getBody()).path("accessToken").asText();

    assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    assertThat(json.readTree(registered.getBody()).path("email").asText()).isEqualTo(email);
    assertThat(signedIn.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(getProjects(token).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(getProjects(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void thePasswordIsStoredAsABcryptHash() {
    String email = unique("hash");
    register(email, PASSWORD);

    String stored =
        database.queryForObject(
            "select password_hash from users where email = ?", String.class, email);

    assertThat(stored).startsWith("{bcrypt}$2").doesNotContain(PASSWORD);
  }

  @Test
  void registeringTheSameEmailTwiceIsAConflict() {
    String email = unique("twice");
    register(email, PASSWORD);

    assertThat(register(email, PASSWORD).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  void aWrongPasswordAndAnUnknownEmailGetTheSameAnswer() throws Exception {
    String email = unique("same-answer");
    register(email, PASSWORD);

    ResponseEntity<String> wrongPassword = login(email, "wrong-horse-battery");
    ResponseEntity<String> unknownEmail = login(unique("nobody"), PASSWORD);

    assertThat(wrongPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(unknownEmail.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(json.readTree(wrongPassword.getBody()).path("detail"))
        .isEqualTo(json.readTree(unknownEmail.getBody()).path("detail"));
  }

  @Test
  void anExpiredTokenIsRefused() {
    Instant issued = clock.instant().minus(Duration.ofHours(9));
    String expired =
        sign(
            JwtClaimsSet.builder()
                .issuer(JwtConfiguration.ISSUER)
                .subject(UUID.randomUUID().toString())
                .issuedAt(issued)
                .expiresAt(issued.plus(Duration.ofHours(8))));

    assertThat(getProjects(expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void aTokenThatIsNotOursIsRefused() {
    assertThat(getProjects("not.a.token").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void pathsOutsideTheApisAndTheProbesAreClosed() {
    assertThat(http.getForEntity("/internal", String.class).getStatusCode())
        .isEqualTo(HttpStatus.FORBIDDEN);
  }

  private ResponseEntity<String> register(String email, String password) {
    return post("/api/auth/register", Map.of("email", email, "password", password));
  }

  private ResponseEntity<String> login(String email, String password) {
    return post("/api/auth/login", Map.of("email", email, "password", password));
  }

  private ResponseEntity<String> post(String path, Map<String, String> body) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return http.postForEntity(path, new HttpEntity<>(body, headers), String.class);
  }

  private ResponseEntity<String> getProjects(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return http.exchange("/api/projects", HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }

  private String sign(JwtClaimsSet.Builder claims) {
    return encoder
        .encode(
            JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
        .getTokenValue();
  }

  private static String unique(String name) {
    return (name + "-" + UUID.randomUUID() + "@example.com").toLowerCase(Locale.ROOT);
  }
}
