package io.github.sanduniliyanage.flaglane.common.clock;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's single source of time. Everything that reads the time takes this {@link Clock},
 * never {@code Instant.now()}, so a test can fix or move it.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
