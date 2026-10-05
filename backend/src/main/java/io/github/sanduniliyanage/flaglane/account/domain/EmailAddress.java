package io.github.sanduniliyanage.flaglane.account.domain;

import java.util.Locale;

/**
 * How an email address is stored and looked up: trimmed and lower-cased, so {@code
 * Amara@Example.com} and {@code amara@example.com} are one account rather than two (ADR-021).
 */
public final class EmailAddress {

  private EmailAddress() {}

  public static String normalize(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }
}
