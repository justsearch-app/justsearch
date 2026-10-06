/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.runtime;

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Identity-safe predecessor cleanup performed before any child-capable bootstrap begins.
 *
 * <p>ADR-0051 decision 2: no child outlives the Engine that started it. Every registered child
 * whose identity (PID, start time and executable) still matches a live process was started by a
 * predecessor Engine and is terminated, the predecessor's llama-server included; none is kept for
 * adoption. A record whose identity cannot be read is left alone with its live process (it may be
 * unrelated), and a record proved stale by a mismatch is dropped without touching the process
 * that now holds the PID.
 */
public final class ManagedChildReconciler {
  private final ManagedChildRegistry registry;
  private final Termination termination;

  public ManagedChildReconciler(ManagedChildRegistry registry) {
    this(registry, ManagedChildReconciler::terminateMatched);
  }

  @FunctionalInterface
  interface Termination {
    boolean terminate(ProcessHandle handle);
  }

  ManagedChildReconciler(ManagedChildRegistry registry, Termination termination) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.termination = Objects.requireNonNull(termination, "termination");
  }

  public void reconcile() throws IOException {
    for (ManagedChild child : registry.snapshot()) reconcile(child);
  }

  private void reconcile(ManagedChild child) throws IOException {
    Optional<ProcessHandle> found = ProcessHandle.of(child.pid());
    if (found.isEmpty() || !found.get().isAlive()) {
      registry.remove(child.id());
      return;
    }
    ProcessHandle handle = found.get();
    ManagedChild.IdentityMatch identity = child.identityOf(handle);
    if (identity == ManagedChild.IdentityMatch.UNKNOWN) {
      return; // unknown identity: preserve evidence and do not touch the live process
    }
    if (identity == ManagedChild.IdentityMatch.MISMATCH) {
      registry.remove(child.id()); // proved stale/mismatched record; live process is unrelated
      return;
    }
    // Verified-owned predecessor child, of every kind: it ends with the Engine that started it.
    if (termination.terminate(handle)) registry.remove(child.id());
  }

  private static boolean terminateMatched(ProcessHandle handle) {
    handle.destroy();
    try {
      if (handle.isAlive()) handle.onExit().get(5, TimeUnit.SECONDS);
      if (handle.isAlive()) {
        handle.destroyForcibly();
        handle.onExit().get(5, TimeUnit.SECONDS);
      }
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
    }
    return !handle.isAlive();
  }
}
