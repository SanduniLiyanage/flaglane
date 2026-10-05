package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Suite 9, the server's half (FR-SDK-007). The SDK runs the same fixtures in {@code
 * sdk/test/parity.test.ts}; both must produce every expected value, so a divergence between the two
 * implementations is a test failure rather than a production incident.
 *
 * <p>The rulesets are in exactly the shape {@code GET /sdk/config} serves, read here into the
 * engine's types field for field, as the SDK reads them into its own.
 */
class ParityFixturesTest {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonNode EVALUATION = read("/fixtures/evaluation-parity.json");
  private static final JsonNode BUCKETING = read("/fixtures/bucketing-vectors.json");

  static Stream<Arguments> evaluationCases() {
    return StreamSupport.stream(EVALUATION.path("cases").spliterator(), false)
        .map(row -> Arguments.of(Named.of(row.path("name").asText(), row)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("evaluationCases")
  void theEngineGivesEveryExpectedAnswer(JsonNode row) {
    Ruleset ruleset = ruleset(EVALUATION.path("rulesets").path(row.path("ruleset").asText()));
    JsonNode context = row.path("context");
    UserContext user =
        UserContext.fromRaw(
            context.hasNonNull("key") ? context.path("key").asText() : null,
            JSON.convertValue(
                context.path("attributes"), new TypeReference<Map<String, Object>>() {}));

    Evaluation evaluation =
        TestEvaluators.quiet()
            .evaluate(ruleset, row.path("flag").asText(), user, row.path("fallback").asBoolean());

    assertThat(evaluation)
        .isEqualTo(
            new Evaluation(
                row.at("/expected/value").asBoolean(),
                Reason.valueOf(row.at("/expected/reason").asText())));
  }

  static Stream<Arguments> murmurVectors() {
    return StreamSupport.stream(BUCKETING.path("murmur3").spliterator(), false)
        .map(row -> Arguments.of(Named.of(row.path("hash").asText(), row)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("murmurVectors")
  void murmurHashMatchesTheSharedVectors(JsonNode row) {
    int seed = (int) row.path("seed").asLong();

    int hash =
        MurmurHash3.x86_32(row.path("input").asText().getBytes(StandardCharsets.UTF_8), seed);

    assertThat(String.format("%08x", hash)).isEqualTo(row.path("hash").asText());
  }

  static Stream<Arguments> bucketVectors() {
    return StreamSupport.stream(BUCKETING.path("buckets").spliterator(), false)
        .map(row -> Arguments.of(Named.of(String.valueOf(row.path("bucket").asInt()), row)));
  }

  @ParameterizedTest(name = "bucket {0}")
  @MethodSource("bucketVectors")
  void bucketsMatchTheSharedVectors(JsonNode row) {
    assertThat(Bucketing.bucket(row.path("salt").asText(), row.path("userKey").asText()))
        .isEqualTo(row.path("bucket").asInt());
  }

  @Test
  void everyBucketOfTheCommittedKeySetMatchesTheSharedDigest() throws Exception {
    JsonNode keySet = BUCKETING.path("keySet");
    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

    for (String key : UserKeyFixture.keys()) {
      sha256.update(
          (Bucketing.bucket(keySet.path("salt").asText(), key) + "\n")
              .getBytes(StandardCharsets.US_ASCII));
    }

    assertThat(HexFormat.of().formatHex(sha256.digest())).isEqualTo(keySet.path("sha256").asText());
  }

  @Test
  void everyFlagOfEveryFixtureRulesetIsExercised() {
    List<String> exercised = new ArrayList<>();
    EVALUATION.path("cases").forEach(row -> exercised.add(row.path("flag").asText()));

    EVALUATION
        .path("rulesets")
        .forEach(
            ruleset ->
                ruleset
                    .path("flags")
                    .forEach(
                        flag ->
                            assertThat(exercised)
                                .as("cases for %s", flag.path("key").asText())
                                .contains(flag.path("key").asText())));
  }

  /** The served JSON shape into the engine's types, field for field. */
  static Ruleset ruleset(JsonNode served) {
    List<FlagConfig> flags = new ArrayList<>();
    for (JsonNode flag : served.path("flags")) {
      FlagConfig.Builder builder =
          FlagConfig.builder(flag.path("key").asText())
              .enabled(flag.path("enabled").asBoolean())
              .fallthroughValue(flag.path("fallthroughValue").asBoolean())
              .rolloutBasisPoints(flag.path("rolloutBasisPoints").asInt())
              .rolloutSalt(flag.path("rolloutSalt").asText());
      for (JsonNode override : flag.path("overrides")) {
        builder.override(override.path("userKey").asText(), override.path("value").asBoolean());
      }
      for (JsonNode rule : flag.path("rules")) {
        builder.rule(
            new TargetingRule(
                rule.path("priority").asInt(),
                rule.path("attribute").asText(),
                rule.path("operator").asText(),
                JSON.convertValue(rule.path("matchValues"), new TypeReference<List<Object>>() {}),
                rule.path("resultValue").asBoolean()));
      }
      flags.add(builder.build());
    }
    return Ruleset.of(served.path("environment").asText(), served.path("version").asLong(), flags);
  }

  private static JsonNode read(String resource) {
    try (InputStream in = ParityFixturesTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " is not on the test classpath");
      }
      return JSON.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
