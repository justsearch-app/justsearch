/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import io.justsearch.app.api.DebugStateProvider;
import io.justsearch.app.api.StatusSnapshotProvider;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed process/controller SPI bindings published before serving requests. */
public final class BootstrapLateBindings {

  private final AtomicReference<io.justsearch.app.api.operations.ForgetService> forgetService =
      new AtomicReference<>();

  /** The same-JVM Engine owner is published once before UI request admission. */
  public void bindForgetService(io.justsearch.app.api.operations.ForgetService owner) {
    if (!forgetService.compareAndSet(null, java.util.Objects.requireNonNull(owner, "owner"))) {
      throw new IllegalStateException("Forget owner is already bound");
    }
  }

  public io.justsearch.app.api.operations.ForgetService forgetService() {
    var owner = forgetService.get();
    return owner == null ? io.justsearch.app.api.operations.ForgetService.unavailable() : owner;
  }

  private final AtomicReference<DebugStateProvider> debugStateProvider = new AtomicReference<>();
  private final AtomicReference<StatusSnapshotProvider> statusSnapshotProvider =
      new AtomicReference<>();
  private final AtomicReference<ComponentRecoveryAuthority> componentRecoveryAuthority =
      new AtomicReference<>();

  /** Set by LocalApiServer after DebugStateController exists. */
  public void setDebugStateProvider(DebugStateProvider provider) {
    this.debugStateProvider.set(provider);
  }

  /** Set by LocalApiServer after StatusLifecycleHandler exists. */
  public void setStatusSnapshotProvider(StatusSnapshotProvider provider) {
    this.statusSnapshotProvider.set(provider);
  }

  /** Published after the health monitor has started and owns component recovery. */
  public void setComponentRecoveryAuthority(ComponentRecoveryAuthority authority) {
    this.componentRecoveryAuthority.set(authority);
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public DebugStateProvider debugStateProvider() {
    return debugStateProvider.get();
  }

  /** Read by DiagnosticsServiceImpl on each exportDiagnostics() call. */
  public StatusSnapshotProvider statusSnapshotProvider() {
    return statusSnapshotProvider.get();
  }

  /** Read at invocation time by every component-recovery entry point. */
  public ComponentRecoveryAuthority componentRecoveryAuthority() {
    return componentRecoveryAuthority.get();
  }
}
