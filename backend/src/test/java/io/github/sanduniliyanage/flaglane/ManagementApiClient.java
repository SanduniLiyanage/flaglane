package io.github.sanduniliyanage.flaglane;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** The dashboard's side of the API, for end-to-end tests: sign up, sign in, call as that user. */
public final class ManagementApiClient {

  public static final String PASSWORD = "correct-horse-battery";

  private final TestRestTemplate http;
  private final ObjectMapper json;

  public ManagementApiClient(TestRestTemplate http, ObjectMapper json) {
    this.http = http;
    this.json = json;
  }

  /** Registers a new user under a unique email and returns their access token. */
  public String signUp(String name) {
    String email = name + "-" + UUID.randomUUID() + "@example.com";
    Map<String, String> credentials = Map.of("email", email, "password", PASSWORD);
    exchange(HttpMethod.POST, "/api/auth/register", null, credentials);
    return read(exchange(HttpMethod.POST, "/api/auth/login", null, credentials))
        .path("accessToken")
        .asText();
  }

  /** Creates a project under a unique key and returns the key. */
  public String createProject(String token, String prefix) {
    String key = uniqueKey(prefix);
    ResponseEntity<String> created =
        exchange(HttpMethod.POST, "/api/projects", token, Map.of("key", key, "name", prefix));
    if (!created.getStatusCode().is2xxSuccessful()) {
      throw new IllegalStateException("Could not create project " + key + ": " + created);
    }
    return key;
  }

  public ResponseEntity<String> get(String path, String token) {
    return exchange(HttpMethod.GET, path, token, null);
  }

  public ResponseEntity<String> post(String path, String token, Object body) {
    return exchange(HttpMethod.POST, path, token, body);
  }

  public ResponseEntity<String> exchange(
      HttpMethod method, String path, String token, Object body) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    if (body != null) {
      headers.setContentType(MediaType.APPLICATION_JSON);
    }
    return http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
  }

  public JsonNode read(ResponseEntity<String> response) {
    try {
      return json.readTree(response.getBody());
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A key in the URL-safe format, unique to this run. */
  public static String uniqueKey(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
