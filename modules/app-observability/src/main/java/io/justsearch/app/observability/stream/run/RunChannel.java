/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One run's observation channel — the run as an OBSERVED entity, projected onto the universal SSE
 * substrate (tempdoc 834 §1.3: PROJECTION, not fork).
 *
 * <p>Sealed on purpose. §0's thesis is "one shared OBSERVATION substrate; two EXECUTION semantics",
 * and the failure to guard against is a unification that flattens the one-shot column into the
 * stepped one. {@link SteppedRunChannel} has {@code setPark}; {@link OneShotRunChannel} has no
 * method to park a run with. Parking an ask is therefore a compile error, not a javadoc.
 *
 * <p>Everything shared lives on the {@link SseStreamChannel} underneath: monotonic sequence,
 * bounded replay, listener fan-out, evict-on-throw, and the atomic subscribe-and-replay that makes
 * "either via the replay or via the live fan-out, never both, never neither" true for run traffic.
 */
public sealed interface RunChannel permits SteppedRunChannel, OneShotRunChannel {

  /** This run's identity — an agent {@code sessionId} or a minted {@code run-<uuid>}. */
  RunId id();

  /** What the run is, independent of what it has emitted. */
  RunDescriptor descriptor();

  /** The underlying observation stream. */
  SseStreamChannel channel();

  /** The bounds + posture this channel was opened with. */
  RunChannelPolicy policy();

  /**
   * How many LIVE observers are attached. Read from the channel's listener set, not from a counter
   * this type maintains, so an observer EVICTED on a failed write (a dead socket) stops being
   * counted — the precondition §3's zero-observer park depends on.
   */
  int observerCount();

  /**
   * When this run last published a NARRATIVE frame, or its start when it has published none.
   *
   * <p>Lifecycle frames deliberately do NOT bump it. A parked run's only write is its heartbeat, so
   * counting that as activity would make every parked run look busy — which is the one lie an
   * "updated at" field is in a position to tell, and the enumeration (§5.1) is where it would be
   * believed.
   */
  long updatedAtEpochMs();

  /** Why this run is stopped and waiting; always empty for a one-shot pipeline (§6.4). */
  Optional<ParkState> park();

  /** The act-on-the-run primer pushed before every replay (§6.1); empty for a one-shot pipeline. */
  Optional<RunStateSnapshot> snapshot();

  /**
   * Publishes a narrative frame to every observer and to the replay ring. A retired channel
   * silently refuses — a late publish from a loop that has not yet noticed the terminal transition
   * must not resurrect a run the registry has already answered 404 for.
   *
   * @return true when the frame was published
   */
  boolean publish(RunFrame frame);

  /**
   * Emits a SEQUENCED-but-NOT-RETAINED lifecycle frame ({@code run_started}, heartbeat,
   * {@code replay_truncated}) — §3.2. The seq is consumed so the wire stays monotonic, but the
   * frame never occupies a ring slot, which is what lets a 15 s heartbeat run for the whole life of
   * a parked run without evicting a single narrative frame.
   */
  SseEnvelope lifecycle(RunFrame frame);

  /**
   * Attaches an observer, replaying everything after {@code sinceSeq} atomically with the
   * registration.
   *
   * <p>Returns EMPTY when {@code sinceSeq} is outside the replay window — and in that case NOTHING
   * is registered and nothing is replayed. That is why this returns an {@code Optional} where
   * §1.5's sketch returned a bare handle: the window miss has to be visible to the writer, whose
   * §15.1 protocol obligation is to emit {@code replay_truncated} + the snapshot and then
   * re-subscribe from 0. Silently handing back a dead stream is the failure mode.
   *
   * <p>{@code sinceSeq == 0} always succeeds (the channel's window rule rejects only a cursor
   * ahead of the stream or behind the retained window), so it is the guaranteed fallback path.
   */
  Optional<SseStreamChannel.Subscription> observe(Consumer<SseEnvelope> listener, long sinceSeq);

  /** Acquires observer ownership before replay delivery can block. */
  Optional<SseStreamChannel.Subscription> observe(Consumer<SseEnvelope> listener, long sinceSeq,
      Consumer<SseStreamChannel.Subscription> onRegistered);

  /** Sends the run primer after ownership acquisition and before replay, outside source locks. */
  Optional<SseStreamChannel.Subscription> observe(Consumer<SseEnvelope> listener, long sinceSeq,
      Runnable beforeReplay, Consumer<SseStreamChannel.Subscription> onRegistered);

  /** True once the run is terminal and the registry has retired it. */
  boolean retired();

  /**
   * Registers a callback fired ONCE when the run is retired, so an attached writer can close its
   * connection instead of holding a socket open on a run that is over. Vocabulary-free on purpose:
   * the substrate does not know which event name is terminal.
   */
  void onRetire(Runnable listener);
}
