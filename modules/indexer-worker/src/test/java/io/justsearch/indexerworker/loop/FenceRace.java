/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Runs work on its own thread and tells when it waits for the file-mutation fence, so a test can
 * hold a writer inside the fence until a prune either waits for it or finishes without it.
 */
public final class FenceRace {
  private FenceRace() {}

  /** A started task and its thread. */
  public record Started<T>(FutureTask<T> task, Thread thread) {
    public T get() throws Exception {
      return task.get(60, TimeUnit.SECONDS);
    }
  }

  public static <T> Started<T> start(String name, Callable<T> work) {
    FutureTask<T> task = new FutureTask<>(work);
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    thread.start();
    return new Started<>(task, thread);
  }

  /**
   * Waits until {@code started} waits for the fence or has finished, then runs {@code release}
   * (which lets the held writer go on). Returns whether it waited for the fence.
   */
  public static boolean releaseOnceWaitingOrDone(Runnable release, Started<?> started)
      throws InterruptedException {
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (System.nanoTime() < deadline) {
        if (waitsForFence(started.thread())) return true;
        if (started.task().isDone()) return false;
        Thread.sleep(5);
      }
      return false;
    } finally {
      release.run();
    }
  }

  private static boolean waitsForFence(Thread thread) {
    if (thread.getState() != Thread.State.WAITING) return false;
    for (StackTraceElement frame : thread.getStackTrace()) {
      if (frame.getClassName().endsWith(".FileMutationFence")) return true;
    }
    return false;
  }
}
