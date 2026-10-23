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
   *
   * <p>While the runtime manifest still lists children (until Spec B step 10), the child is also
   * registered there, and a child whose registration fails is stopped ({@code ROLLBACK}) and not
   * returned: start throws {@link ChildRegistrationRefusedException}, whose {@code survivor()} is
   * the child when that stop did not confirm its exit.
   *
   * <p>A chat server ({@code LLAMA_SERVER}, {@code LLAMA_SELF_TEST}) whose stop failed and that
   * may still be alive blocks the start of every chat server: start retries its stop first and
   * throws, starting nothing, while it is not confirmed gone.
   * What an exited chat root left running is ended first ({@link #endRemaining}); any
   * survivor keeps that barrier until it exits. Unreadable enumeration
   * refuses a start. A GPU start waits for every pending release poll to finish, released or
   * timed out, and is refused only when one still runs past the poll's own bound plus a margin.
   *
   * @param manifest what the manifest entry carries beyond the OS identity
   */
  ChildHandle start(ChildSpec spec, ManifestEntry manifest) throws IOException;

  /** {@link #start(ChildSpec, ManifestEntry)} with an entry derived from the spec. */
  default ChildHandle start(ChildSpec spec) throws IOException {
    return start(spec, ManifestEntry.DERIVED);
  }

  /** Idempotent: the first reason wins. The intent is durable, or recorded as a gap, first. */
  CompletionStage<ExitOutcome> stop(ChildHandle child, StopRequest request);

  /** {@link #stop(ChildHandle, StopRequest)} with no detail, asked for by the owner. */
  default CompletionStage<ExitOutcome> stop(ChildHandle child, StopReason reason, StopMode mode) {
    return stop(child, StopRequest.of(reason, mode));
  }

  /** Stops several children with one shared cooperative grace window. */
  CompletionStage<List<ExitOutcome>> stopAll(
      Collection<ChildHandle> children, StopRequest request);

  /** {@link #stopAll(Collection, StopRequest)} with no detail, asked for by the owner. */
  default CompletionStage<List<ExitOutcome>> stopAll(
      Collection<ChildHandle> children, StopReason reason, StopMode mode) {
    return stopAll(children, StopRequest.of(reason, mode));
  }

  /**
   * Ends what a chat child whose root has exited left running in its own containment: on Windows
   * the child's nested job, ended through the job handle the control holds, never by PID, image
   * name or parent. Elsewhere the original descendant ProcessHandles captured while an ancestor
   * lived receive TERM, a short bounded wait, then KILL; the JDK rechecks their birth before
   * signalling. Linux zombies count as exited even when unreaped. Each member is first recorded in the exit ledger as an intended cleanup with
   * {@code request}'s reason; the call returns once every recorded member has exited.
   *
   * @return how many processes were ended (0 when nothing remained)
   * @throws IOException when a survivor remains (the next chat start keeps refusing beside it) or
   *     the membership cannot be read
   * @throws IllegalStateException when the child's root is still running
   */
  default int endRemaining(ChildHandle child, StopRequest request) throws IOException {
    throw new IOException("this control cannot end what a child left running");
  }

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

  /**
   * The runtime manifest's view of a child beyond its OS identity (transitional: the manifest
   * {@code children} list retires in Spec B step 10). Null fields are derived: the endpoint is
   * {@code stdio} for piped children, and the declared hash is the SHA-256 of the argv.
   *
   * @param declaredConfigHash the SHA-256 of the configuration the owner declared, before any
   *     launch-time argv additions
   */
  record ManifestEntry(String endpoint, String modelPath, String declaredConfigHash) {
    /** Everything derived from the spec. */
    public static final ManifestEntry DERIVED = new ManifestEntry(null, null, null);
  }
}
