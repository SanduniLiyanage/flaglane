package io.github.sanduniliyanage.flaglane.apikey.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiKeyFormatTest {

  private final SecureRandom random = new SecureRandom();

  @Test
  void aServerKeyIsTheMarkerFollowedByFortyThreeBase64urlCharacters() {
    String key = ApiKeyFormat.generate(KeyType.SERVER, random);

    assertThat(key).startsWith("flg_srv_").hasSize(8 + 43).matches("flg_srv_[A-Za-z0-9_-]{43}");
    assertThat(ApiKeyFormat.isWellFormed(key)).isTrue();
  }

  @Test
  void aClientKeyCarriesTheClientMarker() {
    assertThat(ApiKeyFormat.generate(KeyType.CLIENT, random)).startsWith("flg_cli_");
  }

  @Test
  void thePrefixIsTheMarkerAndTheFirstEightRandomCharacters() {
    String key = ApiKeyFormat.generate(KeyType.SERVER, random);

    assertThat(ApiKeyFormat.prefix(key)).hasSize(16).isEqualTo(key.substring(0, 16));
  }

  @Test
  void keysDoNotRepeat() {
    Set<String> keys = new HashSet<>();
    for (int i = 0; i < 10_000; i++) {
      keys.add(ApiKeyFormat.generate(KeyType.SERVER, random));
    }

    assertThat(keys).hasSize(10_000);
  }

  @Test
  void theHashIsSha256InLowercaseHex() {
    // FIPS 180-2's test vector for SHA-256("abc").
    assertThat(ApiKeyFormat.hash("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "flg_srv_",
        "flg_srv_tooshort",
        "flg_xyz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "FLG_SRV_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        "flg_srv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
        "flg_srv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        " flg_srv_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      })
  void anythingElseIsNotAKey(String candidate) {
    assertThat(ApiKeyFormat.isWellFormed(candidate)).isFalse();
  }

  @Test
  void nullIsNotAKey() {
    assertThat(ApiKeyFormat.isWellFormed(null)).isFalse();
  }
}
