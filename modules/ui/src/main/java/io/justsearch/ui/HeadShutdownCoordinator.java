/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;

/**
 * The {@code ui}-facing adapter onto {@link EngineShutdownSequence} (stage B item B4).
 *
 * <p>This class used to own the ordered close: the idempotency, the receipt, the exit guard and the
 * result type were all here, and {@code HeadlessApp} supplied the eight steps as an opaque
 * {@code Supplier}. Design 7.3 puts that ownership in the composition root, because the sequence
 * spans all three rings and the root is the only place allowed to see all of them.
 *
 * <p>The remaining adapter is the normal-quit entry point used by the lifecycle API. It holds no
 * state; every run-once and exit-once guarantee belongs to the sequence.
 */
public final class HeadShutdownCoordinator {

  /** Kept for callers that referenced the receipt name through this class. */
  public static final String RECEIPT_FILE = EngineShutdownSequence.RECEIPT_FILE;

  private final EngineShutdownSequence sequence;

  public HeadShutdownCoordinator(EngineShutdownSequence sequence) {
    this.sequence = sequence;
  }

  /**
   * The normal-quit entry point ({@code POST /api/lifecycle/shutdown}, tempdoc 805 G.1) and the JVM
   * hook. Exits, because the caller — the shell — waits on child exit before force-killing.
   */
  public void shutdownAndExit() {
    sequence.runAndExit(Reason.QUIT);
  }
}
