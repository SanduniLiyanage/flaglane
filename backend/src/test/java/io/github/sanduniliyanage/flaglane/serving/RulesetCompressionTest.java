package io.github.sanduniliyanage.flaglane.serving;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.sanduniliyanage.flaglane.FlaglaneIntegrationTest;
import io.github.sanduniliyanage.flaglane.ManagementApiClient;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;

/**
 * {@code GET /sdk/config} sends the ruleset gzipped to a client that accepts gzip, and as it is to
 * one that does not (ADR-033). The JDK's client is used because it does not decompress on its own,
 * so the test sees what goes over the wire.
 *
 * <p>The gzipped body decompresses to exactly the bytes of the plain one, for either key type, so
 * everything {@code ClientKeyExposureTest} proves of the plain body holds for the compressed one.
 */
@FlaglaneIntegrationTest
class RulesetCompressionTest {

  private final ManagementApiClient api;
  private final HttpClient client = HttpClient.newHttpClient();
  private final int port;

  private String serverKey;
  private String clientKey;

  RulesetCompressionTest(
      @Autowired TestRestTemplate http, @Autowired ObjectMapper json, @LocalServerPort int port) {
    this.api = new ManagementApiClient(http, json);
    this.port = port;
  }

  @BeforeEach
  void aProjectWithAVisibleAndAHiddenFlag() {
    String token = api.signUp("compression");
    String project = api.createProject(token, "compression");
    String flags = "/api/projects/" + project + "/flags";
    api.post(flags, token, Map.of("key", "banner", "name", "Banner", "clientSideVisible", true));
    api.post(flags, token, Map.of("key", "billing", "name", "Billing"));
    api.exchange(
        HttpMethod.PUT,
        flags + "/banner/config/production/overrides",
        token,
        Map.of("overrides", List.of(Map.of("userKey", "amara@example.com", "value", true))));
    String keys = "/api/projects/" + project + "/environments/production/keys";
    serverKey =
        api.read(api.post(keys, token, Map.of("name", "s", "type", "server"))).path("key").asText();
    clientKey =
        api.read(api.post(keys, token, Map.of("name", "c", "type", "client"))).path("key").asText();
  }

  @Test
  void aClientThatAcceptsGzipGetsTheSameBytesGzippedWithTheSameEtag() throws Exception {
    for (String key : List.of(serverKey, clientKey)) {
      HttpResponse<byte[]> plain = get(key, null, null);
      HttpResponse<byte[]> gzipped = get(key, "gzip, deflate, br", null);

      assertThat(plain.headers().firstValue("content-encoding")).isEmpty();
      assertThat(gzipped.statusCode()).isEqualTo(200);
      assertThat(gzipped.headers().firstValue("content-encoding")).hasValue("gzip");
      assertThat(gzipped.headers().firstValue("content-type")).hasValue("application/json");
      assertThat(gzipped.headers().firstValueAsLong("content-length"))
          .hasValue(gzipped.body().length);
      assertThat(gunzip(gzipped.body())).isEqualTo(plain.body());
      assertThat(gzipped.headers().firstValue("etag"))
          .isEqualTo(plain.headers().firstValue("etag"));
    }
  }

  @Test
  void aClientThatRefusesGzipGetsThePlainBody() throws Exception {
    HttpResponse<byte[]> response = get(serverKey, "gzip;q=0, identity", null);

    assertThat(response.headers().firstValue("content-encoding")).isEmpty();
    assertThat(new String(response.body(), StandardCharsets.UTF_8)).startsWith("{");
  }

  @Test
  void anUnchangedRulesetIsNotModifiedWhicheverCodingIsAccepted() throws Exception {
    String etag = get(serverKey, null, null).headers().firstValue("etag").orElseThrow();

    for (String acceptEncoding : new String[] {null, "gzip"}) {
      HttpResponse<byte[]> again = get(serverKey, acceptEncoding, etag);

      assertThat(again.statusCode()).as(acceptEncoding).isEqualTo(304);
      assertThat(again.body()).as(acceptEncoding).isEmpty();
      assertThat(again.headers().firstValue("content-encoding")).as(acceptEncoding).isEmpty();
      assertThat(again.headers().firstValue("etag")).as(acceptEncoding).hasValue(etag);
    }
  }

  @Test
  void theAnswerVariesByKeyAndByAcceptedCoding() throws Exception {
    for (String etag : new String[] {null, "\"0-server\""}) {
      List<String> vary =
          get(serverKey, "gzip", etag).headers().allValues("vary").stream()
              .flatMap(value -> List.of(value.split(",")).stream())
              .map(String::strip)
              .toList();

      assertThat(vary).contains("Authorization", "Accept-Encoding");
    }
  }

  private HttpResponse<byte[]> get(String key, String acceptEncoding, String ifNoneMatch)
      throws IOException, InterruptedException {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sdk/config"))
            .header("Authorization", "Bearer " + key);
    if (acceptEncoding != null) {
      request.header("Accept-Encoding", acceptEncoding);
    }
    if (ifNoneMatch != null) {
      request.header("If-None-Match", ifNoneMatch);
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
  }

  private static byte[] gunzip(byte[] bytes) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      return in.readAllBytes();
    }
  }
}
