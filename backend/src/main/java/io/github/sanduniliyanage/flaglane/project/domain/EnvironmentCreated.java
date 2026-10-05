package io.github.sanduniliyanage.flaglane.project.domain;

import io.github.sanduniliyanage.flaglane.common.tenancy.EnvironmentScope;

/**
 * Published inside the transaction that creates an environment, so that whatever must exist in
 * every environment is created in the same transaction: a configuration for every flag the project
 * already has (FR-ENV-004). Listeners run synchronously; one that fails rolls the environment back.
 */
public record EnvironmentCreated(EnvironmentScope environment) {}
