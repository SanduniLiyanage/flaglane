package io.github.sanduniliyanage.flaglane.evaluation;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * The committed key set every bucketing suite runs against: {@code fixtures/user-keys.txt}, 100,000
 * keys including non-ASCII keys and keys containing {@code :} (docs/TESTING.md).
 */
final class UserKeyFixture {

  static final int SIZE = 100_000;

  private static final String RESOURCE = "/fixtures/user-keys.txt";
  private static final List<String> KEYS = load();

  private UserKeyFixture() {}

  static List<String> keys() {
    return KEYS;
  }

  private static List<String> load() {
    try (InputStream in = UserKeyFixture.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is not on the test classpath");
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (text.indexOf('\r') >= 0) {
        throw new IllegalStateException(
            RESOURCE + " has CRLF line endings; .gitattributes must keep fixtures LF");
      }
      // Split on '\n' alone, as the TypeScript suite does, so both read the same keys.
      List<String> keys = Arrays.asList(text.split("\n"));
      if (keys.size() != SIZE) {
        throw new IllegalStateException(RESOURCE + " holds " + keys.size() + " keys, not " + SIZE);
      }
      return List.copyOf(keys);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
