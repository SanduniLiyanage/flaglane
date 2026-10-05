package io.github.sanduniliyanage.flaglane.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Suite 2 — bucketing determinism (FR-EVL-003). Substantiates: a user never sees the interface
 * flicker.
 *
 * <p>The second run uses a copy of the bucketing code loaded by its own class loader, so no static
 * state — a cache, a seed taken at class initialisation — is shared between the two, and it walks
 * the keys in reverse so nothing carried from one call to the next can make the runs agree.
 */
class BucketingDeterminismTest {

  private static final String SALT = "new-checkout";

  @Test
  void independentInstancesAssignEveryKeyTheSameBucket() throws Exception {
    List<String> keys = UserKeyFixture.keys();

    int[] first = new int[keys.size()];
    for (int i = 0; i < keys.size(); i++) {
      first[i] = Bucketing.bucket(SALT, keys.get(i));
    }
    int[] second = new int[keys.size()];
    try (URLClassLoader isolated = isolatedLoader()) {
      MethodHandle bucket = bucketFunctionLoadedBy(isolated);
      for (int i = keys.size() - 1; i >= 0; i--) {
        second[i] = invoke(bucket, keys.get(i));
      }
    }

    assertThat(second).as("buckets from an independently loaded instance").containsExactly(first);
  }

  private static URLClassLoader isolatedLoader() {
    URL classes = Bucketing.class.getProtectionDomain().getCodeSource().getLocation();
    return new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
  }

  private static MethodHandle bucketFunctionLoadedBy(ClassLoader loader) throws Exception {
    Class<?> isolated = Class.forName(Bucketing.class.getName(), true, loader);
    assertThat(isolated).as("independently loaded class").isNotSameAs(Bucketing.class);
    return MethodHandles.publicLookup()
        .findStatic(
            isolated, "bucket", MethodType.methodType(int.class, String.class, String.class));
  }

  private static int invoke(MethodHandle bucket, String key) {
    try {
      return (int) bucket.invokeExact(SALT, key);
    } catch (Throwable e) {
      throw new AssertionError("bucket(" + SALT + ", key) threw", e);
    }
  }
}
