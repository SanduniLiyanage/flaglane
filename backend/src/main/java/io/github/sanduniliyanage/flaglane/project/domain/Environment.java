package io.github.sanduniliyanage.flaglane.project.domain;

import java.time.Instant;

/** An environment as the service returns it. Identified within its project by its key. */
public record Environment(String key, String name, Instant createdAt) {}
