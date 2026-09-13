/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 884 item 23 — the fail-closed refusal at the layer that actually assembles the server.
 *
 * <p>{@code ApiSecurityFiltersTest} proves the guard fires when the filters object is constructed
 * directly. That is necessary but NOT sufficient: {@code LocalApiServer} constructs
 * {@code ApiSecurityFilters} in its own constructor, deliberately OUTSIDE the try/catch that
 * handles bind failures. Nothing in a unit test of {@code ApiSecurityFilters} alone would notice a
 * later refactor that wrapped that construction in a {@code catch} — the refusal would be swallowed,
 * the server would bind, mutation-gating would be silently off, and all four of those tests would
 * still pass.
 *
 * <p>So this test drives the REAL assembly path: a prod-mode {@link ConfigStore} with no session
 * token on the builder, and the assertion that {@link LocalApiServer#builder}'s {@code build()}
 * refuses rather than returning a running server. It is the regression guard for "someone made the
 * refusal recoverable", which is the only way this defect comes back.
 */
@DisplayName("Local API fails closed: prod mode without a session token refuses to assemble")
final class LocalApiServerFailClosedTest {

  @TempDir Path tempDir;

  private ConfigStore previousStore;

  @BeforeEach
  void installProdModeConfig() {
    // Saved and restored around every case: ConfigStore.setGlobal is JVM-wide, and modules/ui runs
    // many server tests in the same JVM. Leaking prodMode=true would turn unrelated suites red.
    previousStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(
        new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of("justsearch.prod", "true"))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

  private UiSettingsStore settingsStore() {
    return new UiSettingsStore(
        UiSettingsStore.PersistenceMode.IN_MEMORY, tempDir.resolve("settings.json"));
  }

  private OnlineAiService offlineAi() {
    OnlineAiService onlineAi = mock(OnlineAiService.class);
    when(onlineAi.isAvailable()).thenReturn(false);
    when(onlineAi.isStartingUp()).thenReturn(false);
    return onlineAi;
  }

  @Test
  @DisplayName("build() refuses, and the refusal is not swallowed into a bound server")
  void prodModeWithoutTokenRefusesToAssemble() {
    LocalApiServer.Builder builder =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore(), tempDir.resolve("index"))
            .onlineAiService(offlineAi());

    IllegalStateException thrown = assertThrows(IllegalStateException.class, builder::build);

    assertTrue(
        thrown.getMessage().contains(LifecycleReasonCode.LOCAL_API_SESSION_TOKEN_MISSING.code()),
        "the assembly-layer refusal must carry the same wire reason code as the filter-layer one, "
            + "so the fatal log line is diagnosable wherever it is thrown: "
            + thrown.getMessage());
  }

  @Test
  @DisplayName("the same assembly succeeds once a token is supplied — the guard is not blanket")
  void prodModeWithTokenAssemblesNormally() {
    // Without this, the test above would still pass if build() had been made to throw
    // unconditionally in prod mode, which would break the shipped launch path (HeadlessApp pairs
    // prodMode with generateSessionToken). This is the case that distinguishes "refuses when
    // unsafe" from "refuses always".
    LocalApiServer server =
        LocalApiServer.builder(new io.justsearch.core.execution.TestEngineExecutors(), settingsStore(), tempDir.resolve("index"))
            .onlineAiService(offlineAi())
            .sessionToken("test-token-not-a-secret")
            .build();
    try {
      assertTrue(server.getPort() > 0, "a token-carrying prod assembly must bind normally");
    } finally {
      server.stop();
    }
  }
}
