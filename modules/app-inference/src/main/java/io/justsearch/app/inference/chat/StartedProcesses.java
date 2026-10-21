/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ProcessTerminator;
import java.time.Duration;
import java.util.List;

/**
 * The processes a llama child started, so a stop can wait for them as well as for the child (I6:
 * the next launch must not overlap a server still running under a wrapper). Waiting only: {@link
 * io.justsearch.app.api.runtime.child.ChildControl} ends the child, and once the child is gone its
 * descendants are the Engine containment's (Spec B step 7). A bare llama-server starts none.
 */
public final class StartedProcesses {

  private StartedProcesses() {}

  /** The owner's retained scope, including Windows job members after the root exits. */
  public static List<ProcessHandle> of(ChildHandle child) {
    return child == null ? List.of() : child.descendants();
  }

  /**
   * Waits, at most {@code max} in total, for every process to exit. An interrupt does not end the
   * wait (the next launch must not overlap them); it is restored for the caller.
   *
   * @return true when all have exited
   */
  public static boolean awaitExit(List<ProcessHandle> processes, Duration max) {
    long deadline = System.nanoTime() + max.toNanos();
    boolean interrupted = false;
    try {
      for (ProcessHandle process : processes) {
        while (ProcessTerminator.isRunning(process)) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) return false;
          try {
            if (!ProcessTerminator.awaitExit(process, Duration.ofNanos(remaining))) return false;
          } catch (InterruptedException interrupt) {
            interrupted = true;
          }
        }
      }
      return true;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
