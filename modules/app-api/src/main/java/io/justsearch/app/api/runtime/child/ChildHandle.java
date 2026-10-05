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
