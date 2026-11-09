package io.github.sanduniliyanage.flaglane.serving.domain;

import java.util.UUID;

/**
 * An environment's snapshot in the ruleset cache has moved to a newer version. Published after the
 * swap, so whoever hears it and asks the cache gets this version or a later one: the stream tells
 * SDKs to download the ruleset only once there is a new one to download (FR-STR-001).
 *
 * @param environmentKey the key SDKs know the environment by
 * @param version the {@code ruleset_version} now served
 */
public record RulesetSwapped(UUID environmentId, String environmentKey, long version) {}
