/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.runtime.child.ChildHandle;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The processes a llama child started, so a stop can wait for them as well as for the child (I6:
 * the next launch must not overlap a server still running under a wrapper). Waiting only: {@link
 * io.justsearch.app.api.runtime.child.ChildControl} ends the child, and once the child is gone its
 * descendants are the Engine containment's (Spec B step 7). A bare llama-server starts none.
 */
public final class StartedProcesses {

  private StartedProcesses() {}

  /** What {@code child} has started, taken while it is alive (a dead pid has no descendants). */
  public static List<ProcessHandle> of(ChildHandle child) {
    if (child == null || child.exit().toCompletableFuture().isDone()) return List.of();
    try {
      return ProcessHandle.of(child.pid())
          .map(root -> root.descendants().toList())
          .orElse(List.of());
    } catch (RuntimeException unreadable) {
      return List.of();
    }
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
        while (process.isAlive()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) return false;
          try {
            process.onExit().get(remaining, TimeUnit.NANOSECONDS);
          } catch (InterruptedException interrupt) {
            interrupted = true;
          } catch (ExecutionException | TimeoutException recheck) {
            // re-checked by the loop
          }
        }
      }
      return true;
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
