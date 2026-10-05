package io.github.sanduniliyanage.flaglane.account.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import org.junit.jupiter.api.Test;

class EmailAddressTest {

  @Test
  void addressesDifferingOnlyInCaseAndSurroundingSpaceAreOneAddress() {
    assertThat(EmailAddress.normalize("  Amara.Perera@Example.COM "))
        .isEqualTo(EmailAddress.normalize("amara.perera@example.com"))
        .isEqualTo("amara.perera@example.com");
  }

  @Test
  void normalisationDoesNotDependOnTheDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      // Turkish lower-cases 'I' to a dotless 'ı', which would make INFO@ a different address.
      Locale.setDefault(Locale.forLanguageTag("tr"));

      assertThat(EmailAddress.normalize("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
    } finally {
      Locale.setDefault(original);
    }
  }
}
