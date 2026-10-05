package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * NFR-MNT-001: {@code evaluation/} has no framework dependency and imports nothing from the rest of
 * Flaglane. Checked against the source, so the rule fails the build rather than a review.
 */
class EvaluationPurityTest {

  private static final Path SOURCE =
      Path.of("src/main/java/io/github/sanduniliyanage/flaglane/evaluation");
  private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)");

  @Test
  void evaluationImportsNothingButTheJdk() throws IOException {
    List<String> violations = new ArrayList<>();

    try (Stream<Path> files = Files.walk(SOURCE)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
          Matcher matcher = IMPORT.matcher(line.strip());
          if (matcher.find() && !matcher.group(1).startsWith("java.")) {
            violations.add(file.getFileName() + ": " + line.strip());
          }
        }
      }
    }

    assertThat(SOURCE).isDirectory();
    assertThat(violations).as("imports outside java.* in evaluation/").isEmpty();
  }
}
