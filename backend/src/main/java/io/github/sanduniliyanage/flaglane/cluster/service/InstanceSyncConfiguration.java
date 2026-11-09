package io.github.sanduniliyanage.flaglane.cluster.service;

import io.github.sanduniliyanage.flaglane.cluster.domain.InstanceId;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** This instance's identity, and the settings for hearing other instances (ADR-041). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InstanceSyncProperties.class)
public class InstanceSyncConfiguration {

  @Bean
  InstanceId instanceId() {
    return new InstanceId(UUID.randomUUID());
  }
}
