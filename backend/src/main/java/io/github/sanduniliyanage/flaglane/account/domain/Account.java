package io.github.sanduniliyanage.flaglane.account.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * A registered dashboard user, as the rest of the application sees one: never the password hash.
 *
 * @param displayName {@code null} when none was given at registration
 */
public record Account(UUID id, String email, String displayName, Instant createdAt) {}
