/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.StatusSnapshotProvider;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostic controller SPI bindings published before serving requests. */
public final class BootstrapLateBindings {

  private final AtomicReference<DebugStateProvider> debugStateProvider = new AtomicReference<>();
  private final AtomicReference<StatusSnapshotProvider> statusSnapshotProvider =
      new AtomicReference<>();

  /** Set by LocalApiServer after DebugStateController exists. */
  public void setDebugStateProvider(DebugStateProvider provider) {
    this.debugStateProvider.set(provider);
  }

  /** Set by LocalApiServer after StatusLifecycleHandler exists. */
  public void setStatusSnapshotProvider(StatusSnapshotProvider provider) {
    this.statusSnapshotProvider.set(provider);
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public DebugStateProvider debugStateProvider() {
    return debugStateProvider.get();
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public StatusSnapshotProvider statusSnapshotProvider() {
    return statusSnapshotProvider.get();
  }
}
