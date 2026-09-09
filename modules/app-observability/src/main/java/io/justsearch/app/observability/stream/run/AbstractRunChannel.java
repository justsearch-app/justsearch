/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream.run;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.observability.stream.FrameHistoryRingBuffer;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The half of a run channel that is identical for both execution semantics (tempdoc 834 §0): the
 * observation substrate. The two subtypes add only what genuinely differs — park + snapshot.
 *
 * <p>Deliberately does NOT declare {@code implements RunChannel}: {@link RunChannel} is sealed to
 * its two sub-interfaces, so a shared base that implemented it directly would have to be listed in
 * the permits clause — which would make the base itself a third, park-agnostic subtype and hand
 * back exactly the uniform handle §3.4 exists to prevent. The concrete leaves implement the
 * sub-interfaces and inherit these methods; the compiler checks the match.
 */
abstract class AbstractRunChannel {

  private final RunId id;
  private final RunDescriptor descriptor;
  private final RunChannelPolicy policy;
  private final SseStreamChannel channel;
  private final java.time.Clock clock;
  private final java.util.concurrent.atomic.AtomicLong updatedAtEpochMs;
  private final AtomicBoolean retired = new AtomicBoolean(false);
  private final java.util.List<Runnable> retireListeners = new java.util.ArrayList<>();

  AbstractRunChannel(
      RunId id, RunDescriptor descriptor, RunChannelPolicy policy, java.time.Clock clock) {
    this.id = Objects.requireNonNull(id, "id");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.updatedAtEpochMs =
        new java.util.concurrent.atomic.AtomicLong(descriptor.startedAtEpochMs());
    this.channel =
        new SseStreamChannel(
            id.streamId(),
            new io.justsearch.app.observability.stream.StreamSequenceTracker(),
            new FrameHistoryRingBuffer(policy.frameRetention()),
            clock);
  }

  public final RunId id() {
    return id;
  }

  public final RunDescriptor descriptor() {
    return descriptor;
  }

  public final RunChannelPolicy policy() {
    return policy;
  }

  public final SseStreamChannel channel() {
    return channel;
  }

  public final int observerCount() {
    return channel.listenerCount();
  }

  public final long updatedAtEpochMs() {
    return updatedAtEpochMs.get();
  }

  public final boolean publish(RunFrame frame) {
    Objects.requireNonNull(frame, "frame");
    if (retired.get()) {
      return false;
    }
    // Stamped on NARRATIVE publishes only. The heartbeat goes through `lifecycle` and deliberately
    // does not bump this: a parked run's only write is its heartbeat, so counting it as activity
    // would make every parked run look busy — the one lie an "updated at" field is able to tell.
    updatedAtEpochMs.set(clock.millis());
    channel.publish(SseFrameKind.UPDATE, frame.asPayload());
    return true;
  }

  public final SseEnvelope lifecycle(RunFrame frame) {
    Objects.requireNonNull(frame, "frame");
    return channel.nextEnvelope(SseFrameKind.LIFECYCLE, frame.asPayload());
  }

  public final Optional<SseStreamChannel.Subscription> observe(
      Consumer<SseEnvelope> listener, long sinceSeq) {
    Objects.requireNonNull(listener, "listener");
    if (sinceSeq < 0) {
      throw new IllegalArgumentException("sinceSeq must be >= 0, got " + sinceSeq);
    }
    return channel.subscribeAndReplay(listener, sinceSeq);
  }

  public final boolean retired() {
    return retired.get();
  }

  public final void onRetire(Runnable listener) {
    Objects.requireNonNull(listener, "listener");
    synchronized (retireListeners) {
      if (!retired.get()) {
        retireListeners.add(listener);
        return;
      }
    }
    // Registration and terminal snapshot share a lock; callbacks run outside it.
    listener.run();
  }

  /**
   * Marks the run terminal and fires the retire listeners exactly once. Package-private: the whole
   * terminal transition is the REGISTRY's to own (§2 — retire is two sites today, and a linger that
   * only half-applies is how "retired-but-readable" and "gone" get conflated).
   */
  final void markRetired() {
    java.util.List<Runnable> listeners;
    synchronized (retireListeners) {
      if (!retired.compareAndSet(false, true)) return;
      listeners = java.util.List.copyOf(retireListeners);
      retireListeners.clear();
    }
    for (Runnable listener : listeners) {
      try {
        listener.run();
      } catch (RuntimeException ignored) {
        // A writer failing to close its own socket must not abort the retirement of the run or of
        // the other observers' connections.
      }
    }
  }
}
