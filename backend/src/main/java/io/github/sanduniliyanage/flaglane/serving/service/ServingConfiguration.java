package io.github.sanduniliyanage.flaglane.serving.service;

import io.github.sanduniliyanage.flaglane.evaluation.Evaluator;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The evaluation engine, as {@code POST /sdk/evaluate} runs it: the same engine the SDK mirrors.
 */
@Configuration(proxyBeanMethods = false)
public class ServingConfiguration {

  @Bean
  Evaluator evaluator(Clock clock) {
    return new Evaluator(clock);
  }
}
