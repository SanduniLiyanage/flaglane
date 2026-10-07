package io.github.sanduniliyanage.flaglane.serving.service;

import io.github.sanduniliyanage.flaglane.evaluation.Evaluator;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The evaluation engine, as {@code POST /sdk/evaluate} runs it: the same engine the SDK mirrors.
 * And the serving API's rate limit setting (ADR-035).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SdkRateLimitProperties.class)
public class ServingConfiguration {

  @Bean
  Evaluator evaluator(Clock clock) {
    return new Evaluator(clock);
  }
}
