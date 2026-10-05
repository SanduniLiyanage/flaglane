package io.github.sanduniliyanage.flaglane.apikey.domain;

import java.util.UUID;

/**
 * What a live key authenticates as on {@code /sdk/**}: one environment, with one key type, both as
 * the database recorded them.
 */
public record SdkCredential(UUID keyId, UUID environmentId, KeyType keyType) {}
