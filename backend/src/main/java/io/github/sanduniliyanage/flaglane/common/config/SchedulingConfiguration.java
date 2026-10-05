package io.github.sanduniliyanage.flaglane.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background work that must stay off the request path, such as flushing API key last-use times
 * (FR-KEY-006). Runs on Spring's single scheduler thread.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfiguration {}
