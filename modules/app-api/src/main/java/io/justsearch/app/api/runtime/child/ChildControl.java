/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.child;

import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * The one way the Engine starts and stops a held child process (ADR-0051). Every stop records its
 * intent in the exit ledger before any terminate call, so each exit can be classified afterwards.
 */
public interface ChildControl extends AutoCloseable {

  /**
   * Spawns the child, opens an OS handle and records {@code child-spawn} (before) and {@code
   * child-start} (after). It never waits on the GPU unless {@code spec.gpu()}.
   */
  ChildHandle start(ChildSpec spec) throws IOException;

  /** Idempotent: the first reason wins. The intent is durable, or recorded as a gap, first. */
  CompletionStage<ExitOutcome> stop(ChildHandle child, StopReason reason, StopMode mode);

  /** Stops several children with one shared cooperative grace window. */
  CompletionStage<List<ExitOutcome>> stopAll(
      Collection<ChildHandle> children, StopReason reason, StopMode mode);

  /**
   * Appends an owner-supplied diagnosis as {@code child-note}. The token is bounded (for example
   * {@code oom}, {@code dll_not_found}, {@code startup_failed}).
   */
  void note(ChildHandle child, String token);

  /**
   * Barrier before any GPU load: waits for pending GPU-release polls, at most {@code max}.
   *
   * @return true when no poll was still pending at return
   */
  boolean awaitGpuRelease(Duration max) throws InterruptedException;

  /** The live children, for the status surface. */
  List<ChildView> list();

  /** The most recent exits, newest first, from an in-memory ring. */
  List<ExitOutcome> recentExits(int max);

  @Override
  void close();
}
