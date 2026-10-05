package io.github.sanduniliyanage.flaglane.project.domain;

import java.time.Instant;

/** A project as the service returns it. Identified outside the service by its key alone. */
public record Project(String key, String name, Instant createdAt) {}
