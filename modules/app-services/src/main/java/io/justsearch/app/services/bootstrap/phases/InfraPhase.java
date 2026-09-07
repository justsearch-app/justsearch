/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import com.sun.net.httpserver.HttpHandler;
import io.justsearch.app.config.ConfigManagerBootstrap;
import io.justsearch.app.config.ConfigSnapshot;
import io.justsearch.app.observability.CapabilitiesController;
import io.justsearch.app.observability.CapabilitiesService;
import io.justsearch.app.observability.InfraDiagnosticsService;
import io.justsearch.app.observability.InfraHealthBootstrap;
import io.justsearch.app.services.bootstrap.BootstrapCapabilitiesFactory;
import io.justsearch.app.services.bootstrap.PhaseOutcome;
import io.justsearch.app.util.RepoPaths;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Tempdoc 519 §4 Phase 1 — infrastructure setup. Constructs the diagnostics service, the infra
 * health bootstrap, and the {@code /infra/capabilities} HTTP handler. Returns the projectable
 * handle the bootstrap needs to hold; the transient collaborators (diagnostics service, health
 * bootstrap) are constructed for their side effects only.
 *
 * <p><b>Lane F stage A item A14 removed the second thing this phase used to start</b> — an
 * {@code InfraHealthGrpcService} on its own Netty server at {@code justsearch.infra.health.port}.
 * It went with the rest of gRPC. Checked before deleting, not assumed: no script under
 * {@code scripts/}, no Rust in {@code modules/shell/}, no dev-MCP tool and no {@code docs/}
 * reference dialled either of its two RPCs ({@code CurrentSnapshot}, {@code StreamSnapshots}), and
 * the same {@code InfraDiagnosticsService} payload already has an HTTP shape in
 * {@code InfraHealthController}. A second transport nobody called is a second wire to keep alive,
 * not a capability.
 *
 * <p>The capabilities handler reads {@code catalogVersionSupplier} on each request so the bootstrap
 * can wire it to the late-bound CapabilitiesChangeRegistry.currentSeq() without circular
 * construction order.
 */
public final class InfraPhase {

  private InfraPhase() {}

  /** Held output — the capabilities HTTP handler. */
  public record Output(HttpHandler capabilitiesHandler) {}

  /**
   * Tempdoc 541 §5.3 + fix-pass Tier 5 + §12.F: sealed-sum entry. The one remaining failure
   * mode (capabilities-handler factory exception) is caught and reported as
   * {@link PhaseOutcome.Failed}. No Degraded scenarios today — InfraPhase is binary
   * (the handler binds or it fails). §12.F: legacy {@code run()} delegated target inlined
   * here; there are no external callers (HeadAssembly uses {@code runWithOutcome} since the
   * Tier 5 sealed-sum migration).
   */
  public static PhaseOutcome<Output> runWithOutcome(
      ResolvedConfig rc,
      ConfigSnapshot snapshot,
      ConfigManagerBootstrap configManager,
      LongSupplier catalogVersionSupplier) {
    try {
      InfraDiagnosticsService diagnostics =
          new InfraDiagnosticsService(BootstrapHelpers.toInfraHealthConfig(rc.infraHealth()));
      BootstrapHelpers.configureAutomationDiagnostics(diagnostics);
      diagnostics.setConfigValidSupplier(() -> true);
      diagnostics.setMetadataSupplier(
          () -> Map.of("config_loaded_at", snapshot.loadedAt().toString()));
      InfraHealthBootstrap infraHealthBootstrap = new InfraHealthBootstrap(diagnostics);
      infraHealthBootstrap.bindConfigManager(configManager);
      configManager.registerListener(
          snap ->
              diagnostics.setMetadataSupplier(
                  () -> Map.of("config_loaded_at", snap.loadedAt().toString())),
          false);
      boolean allowFakeCapabilities = !rc.policy().prodMode();
      HttpHandler capabilitiesHandler =
          BootstrapCapabilitiesFactory.createCapabilitiesHandler(
              allowFakeCapabilities ? System.getProperty("app.api.fake_capabilities") : null,
              allowFakeCapabilities ? System.getenv("APP_API_FAKE_CAPABILITIES") : null,
              FileBackedCapabilitiesHandler::new,
              () ->
                  new CapabilitiesController(
                      new CapabilitiesService(
                          RepoPaths.findRepoRoot(), catalogVersionSupplier)));
      return new PhaseOutcome.Ready<>(new Output(capabilitiesHandler));
    } catch (RuntimeException e) {
      return PhaseOutcome.Failed.of(e);
    }
  }
}
