/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/**
 * One held child. It deliberately exposes no {@link Process}: only {@link ChildControl} stops
 * children, so callers cannot destroy one behind the ledger's back.
 */
public interface ChildHandle {
  String childId();

  ChildKind kind();

  long pid();

  Instant processStartedAt();

  /**
   * Descendants retained across this child's exit. Windows chat handles query their native job;
   * other Engine handles retain identities observed on demand. An unreadable membership query
   * throws: it is never a successful empty snapshot.
   */
  default java.util.List<ProcessHandle> descendants() {
    var root = ProcessHandle.of(pid());
    if (root.isEmpty() || !root.get().isAlive()) return java.util.List.of();
    var birth = root.get().info().startInstant().orElseThrow(
        () -> new IllegalStateException("child identity unavailable while enumerating descendants"));
    if (!birth.equals(processStartedAt())) return java.util.List.of();
    try (var descendants = root.get().descendants()) {
      return descendants.toList();
    }
  }

  /** The child's stdin. Only for {@link ChildSpec.Stdio.Pipes}. */
  OutputStream stdin();

  /** The child's stdout. Only for {@link ChildSpec.Stdio.Pipes}. */
  InputStream stdout();

  /** The child's stderr. Only for {@link ChildSpec.Stdio.Pipes}. */
  InputStream stderr();

  /** Completes once, for every exit, requested or not. */
  CompletionStage<ExitOutcome> exit();

  State state();

  /** The lifecycle of a held child. */
  enum State {
    STARTING,
    RUNNING,
    STOPPING,
    EXITED,
    /** Termination was attempted twice and the process is still alive. */
    SURVIVED
  }
}
