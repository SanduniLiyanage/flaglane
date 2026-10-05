package io.github.sanduniliyanage.flaglane;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

/**
 * The whole application on a random port, against {@link FlaglanePostgres#shared()}.
 *
 * <p>Every class carrying this annotation and nothing else that changes the context shares one
 * Spring context and one container, so the suite pays for each once rather than once per class. A
 * test that stops its database, or needs an empty one, starts its own instead.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = FlaglaneIntegrationTest.SharedDatabase.class)
public @interface FlaglaneIntegrationTest {

  /** Points the context at the shared container with the two-role configuration. */
  class SharedDatabase implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
      TestPropertyValues.of(FlaglanePostgres.shared().connectionProperties()).applyTo(context);
    }
  }
}
