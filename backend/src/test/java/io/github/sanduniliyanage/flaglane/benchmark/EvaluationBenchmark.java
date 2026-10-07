package io.github.sanduniliyanage.flaglane.benchmark;

import io.github.sanduniliyanage.flaglane.evaluation.Evaluation;
import io.github.sanduniliyanage.flaglane.evaluation.Evaluator;
import io.github.sanduniliyanage.flaglane.evaluation.FlagConfig;
import io.github.sanduniliyanage.flaglane.evaluation.Reason;
import io.github.sanduniliyanage.flaglane.evaluation.Ruleset;
import io.github.sanduniliyanage.flaglane.evaluation.TargetingRule;
import io.github.sanduniliyanage.flaglane.evaluation.UserContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * NFR-PER-001 for the server's engine: per-call latency of {@link Evaluator#evaluate} with 1,000
 * flags loaded and 50 rules on the flag under evaluation. Run with {@code ./gradlew
 * :backend:evaluationBenchmark}; the method and the results are in {@code docs/BENCHMARKS.md}.
 *
 * <p>Each call is timed on its own with {@link System#nanoTime()}, so the percentiles are of single
 * evaluations, not of averages over batches. The timer's own cost is measured the same way and
 * reported beside them. The workload is built so that every measured call takes the longest path:
 * all 50 rules are tested, none matches, and the user is bucketed for the rollout.
 */
public final class EvaluationBenchmark {

  static final int FLAGS = 1_000;
  static final int RULES = 50;
  static final String FLAG = "flag-0500";
  static final int WARM_UP = 500_000;
  static final int SAMPLES = 1_000_000;
  static final int ROUNDS = 3;

  private static int sink;

  private EvaluationBenchmark() {}

  public static void main(String[] args) throws IOException {
    List<String> keys = userKeys();
    UserContext[] users = new UserContext[keys.size()];
    for (int i = 0; i < users.length; i++) {
      users[i] = UserContext.fromRaw(keys.get(i), attributes(i));
    }
    Evaluator evaluator = new Evaluator(Clock.systemUTC());
    Ruleset worst = ruleset(RULES);
    Ruleset plain = ruleset(0);
    requireLongestPath(evaluator, worst, users);

    System.out.printf(
        Locale.ROOT,
        "%s %s, %d available processors%n",
        System.getProperty("java.vm.name"),
        Runtime.version(),
        Runtime.getRuntime().availableProcessors());
    System.out.printf(
        Locale.ROOT,
        "%d flags, %d users, %d warm-up calls, then %d rounds of %d timed calls%n",
        FLAGS,
        users.length,
        WARM_UP,
        ROUNDS,
        SAMPLES);
    run("50 rules, none matches, then rollout", evaluator, worst, users);
    run("no rules, rollout only", evaluator, plain, users);
    timerOverhead();
    System.out.println("(checksum " + sink + ")");
  }

  private static void run(String name, Evaluator evaluator, Ruleset ruleset, UserContext[] users) {
    long[] samples = new long[SAMPLES];
    measure(evaluator, ruleset, users, new long[WARM_UP]);
    for (int round = 1; round <= ROUNDS; round++) {
      measure(evaluator, ruleset, users, samples);
      report(name + ", round " + round, samples);
    }
  }

  private static void measure(
      Evaluator evaluator, Ruleset ruleset, UserContext[] users, long[] samples) {
    int consumed = 0;
    for (int i = 0; i < samples.length; i++) {
      UserContext user = users[i % users.length];
      long start = System.nanoTime();
      Evaluation evaluation = evaluator.evaluate(ruleset, FLAG, user, false);
      long end = System.nanoTime();
      samples[i] = end - start;
      consumed += evaluation.value() ? 1 : 0;
    }
    sink += consumed;
  }

  private static void timerOverhead() {
    long[] samples = new long[SAMPLES];
    for (int i = 0; i < samples.length; i++) {
      long start = System.nanoTime();
      long end = System.nanoTime();
      samples[i] = end - start;
    }
    report("timer alone", samples);
  }

  private static void report(String name, long[] samples) {
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    System.out.printf(
        Locale.ROOT,
        "%-48s p50 %6.2f us  p95 %6.2f us  p99 %6.2f us  p99.9 %7.2f us  max %9.2f us%n",
        name,
        micros(percentile(sorted, 0.50)),
        micros(percentile(sorted, 0.95)),
        micros(percentile(sorted, 0.99)),
        micros(percentile(sorted, 0.999)),
        micros(sorted[sorted.length - 1]));
  }

  /** Nearest rank. */
  static long percentile(long[] sorted, double fraction) {
    int rank = (int) Math.ceil(fraction * sorted.length);
    return sorted[Math.max(0, rank - 1)];
  }

  private static double micros(long nanos) {
    return nanos / 1_000.0;
  }

  /**
   * 1,000 enabled flags, each rolled out to 30% with three rules; the flag under evaluation has
   * {@code rules} rules instead, none of which any benchmark user matches.
   */
  static Ruleset ruleset(int rules) {
    List<FlagConfig> flags = new ArrayList<>();
    for (int f = 0; f < FLAGS; f++) {
      String key = String.format(Locale.ROOT, "flag-%04d", f);
      FlagConfig.Builder flag =
          FlagConfig.builder(key)
              .enabled(true)
              .fallthroughValue(false)
              .rolloutBasisPoints(3_000)
              .rolloutSalt(key);
      int count = key.equals(FLAG) ? rules : 3;
      for (int r = 0; r < count; r++) {
        flag.rule(nonMatchingRule(r));
      }
      flags.add(flag.build());
    }
    return Ruleset.of("production", 1, flags);
  }

  /**
   * Rule {@code r} of a list no benchmark user matches, cycling through every operator and every
   * attribute type, with list operators holding ten values.
   */
  static TargetingRule nonMatchingRule(int r) {
    List<?> values =
        switch (r % 7) {
          case 0 -> List.of("ZZ" + r);
          case 1 -> List.of("tier-" + r);
          case 2 -> tenOf("Q", r);
          case 3 -> tenOf("enterprise-", r);
          case 4 -> List.of("@nowhere-" + r + ".invalid");
          case 5 -> List.of("internal-" + r);
          default -> List.of(1_000.0 + r);
        };
    String[] attributes = {"country", "tier", "country", "plan", "email", "plan", "age"};
    String[] operators = {"EQUALS", "NOT_EQUALS", "IN", "IN", "CONTAINS", "STARTS_WITH", "EQUALS"};
    String attribute = attributes[r % 7];
    String operator = operators[r % 7];
    if (r % 7 == 3 && (r / 7) % 2 != 0) {
      operator = "NOT_IN";
      attribute = "tier";
    }
    if (r % 7 == 5 && (r / 7) % 2 != 0) {
      operator = "ENDS_WITH";
      attribute = "email";
      values = List.of(".invalid-" + r);
    }
    return new TargetingRule(r, attribute, operator, values, true);
  }

  private static List<String> tenOf(String prefix, int r) {
    List<String> values = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      values.add(prefix + r + "-" + i);
    }
    return values;
  }

  /**
   * Every user has a country, plan, email, age and beta flag, and none has {@code tier}, so the
   * negative operators, which never match an absent attribute (ADR-019), are tested and fail.
   */
  static Map<String, Object> attributes(int i) {
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("country", List.of("LK", "IN", "GB", "US", "DE").get(i % 5));
    attributes.put("plan", i % 3 == 0 ? "pro" : "free");
    attributes.put("email", "user" + i + "@example.com");
    attributes.put("age", 18 + (i % 60));
    attributes.put("beta", i % 2 == 0);
    return attributes;
  }

  /** Fails the run unless every user takes the longest path: no rule matches. */
  private static void requireLongestPath(
      Evaluator evaluator, Ruleset ruleset, UserContext[] users) {
    for (UserContext user : users) {
      Reason reason = evaluator.evaluate(ruleset, FLAG, user, false).reason();
      if (reason != Reason.ROLLOUT && reason != Reason.FALLTHROUGH) {
        throw new IllegalStateException(user.key() + " ended at " + reason + ", not after rule 50");
      }
    }
  }

  private static List<String> userKeys() throws IOException {
    try (InputStream in =
            Objects.requireNonNull(
                EvaluationBenchmark.class.getResourceAsStream("/fixtures/user-keys.txt"),
                "fixtures/user-keys.txt");
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return reader.lines().toList();
    }
  }
}
