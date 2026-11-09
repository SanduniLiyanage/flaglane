package io.github.sanduniliyanage.flaglane.common.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties.ForwardHeadersStrategy;

/** Only the strategy that trusts proxies by address may read forwarded headers (ADR-037). */
class ForwardedHeadersConfigurationTest {

  @Test
  void theFrameworkStrategyIsRefusedBecauseItBelievesAnyClient() {
    ServerProperties server = new ServerProperties();
    server.setForwardHeadersStrategy(ForwardHeadersStrategy.FRAMEWORK);

    assertThatIllegalStateException()
        .isThrownBy(() -> new ForwardedHeadersConfiguration(server))
        .withMessageContaining("FLAGLANE_FORWARDED_HEADERS");
  }

  @Test
  void noStrategyTheNativeOneOrNoneAtAllIsAccepted() {
    for (ForwardHeadersStrategy strategy :
        new ForwardHeadersStrategy[] {
          ForwardHeadersStrategy.NONE, ForwardHeadersStrategy.NATIVE, null
        }) {
      ServerProperties server = new ServerProperties();
      server.setForwardHeadersStrategy(strategy);

      assertThatCode(() -> new ForwardedHeadersConfiguration(server))
          .as("strategy %s", strategy)
          .doesNotThrowAnyException();
    }
  }
}
