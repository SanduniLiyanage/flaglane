package io.github.sanduniliyanage.flaglane.streaming.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The stream's limits and timings (FR-STR-002, FR-STR-004, ADR-039). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StreamProperties.class)
public class StreamingConfiguration {}
