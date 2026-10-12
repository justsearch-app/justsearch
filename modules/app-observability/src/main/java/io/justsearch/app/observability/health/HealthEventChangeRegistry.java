/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.health;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Listener-based broadcast for {@link HealthEvent} stream changes.
 *
 * <p>Per slice 436 retrofit (post-impl): the registry now delegates to a per-stream
 * {@link SseStreamChannel} so every broadcast is wrapped in the universal envelope
 * shape, retained in the per-stream ring buffer for resume, and fanned out to envelope-
 * shaped listeners. The legacy {@code HealthChangeEvent} record (with redundant
 * {@code catalogVersion}) is replaced by {@link HealthDelta} carried as the envelope
 * payload; envelope.seq supplies the cursor.
 *
 * <p>Subscriber model (unchanged in shape; changed in payload type):
 *
 * <ul>
 *   <li>Snapshot is delivered by the SSE controller from the {@link ConditionStore} +
 *       {@link OccurrenceLog} on subscribe — not by this registry.
 *   <li>Push changes only — broadcasts assign the next monotonic seq, append the frame
 *       to the channel's ring buffer, and notify all envelope-listeners.
 *   <li>Heartbeats are emitted by the SSE controller's scheduler via
 *       {@link SseStreamChannel#publish} with {@link SseFrameKind#LIFECYCLE}.
 *   <li>Immediate unsubscribe on disconnect; broadcast failure removes the dead listener
 *       inline (channel-level discipline).
 * </ul>
 */
public final class HealthEventChangeRegistry {

  /** Stable StreamId for the health-event stream (per slice 436 §B.3). */
  public static final StreamId STREAM_ID = StreamId.surface("health-events");

  private final SseStreamChannel channel;

  public HealthEventChangeRegistry() {
    this(new SseStreamChannel(STREAM_ID));
  }

  public HealthEventChangeRegistry(SseStreamChannel channel) {
    this.channel = Objects.requireNonNull(channel, "channel").withInterruptibleDelivery();
  }

  /**
   * Returns the SSE wire's seq cursor — bumped on every published frame (broadcast
   * UPDATE or per-client lifecycle). FE consumers compare on reconnect to decide whether
   * to refetch via the resume-token mechanism.
   */
  public long currentSeq() {
    return channel.currentSeq();
  }

  /** Returns the underlying channel for controller-side per-connection writer wiring. */
  public SseStreamChannel channel() {
    return channel;
  }

  /** Subscribes a listener; returns a {@link SseStreamChannel.Subscription}. */
  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  /**
   * Broadcasts a state change. Wraps the (kind, event) tuple into a {@link HealthDelta}
   * payload, assigns the next monotonic seq, appends the frame to the ring buffer, and
   * delivers to every active envelope-listener. Listeners that throw are removed.
   *
   * <p>Listener code runs on the channel's own delivery threads, never on the caller's: the
   * caller waits a bounded time for delivery and is never interrupted when a listener goes away.
   */
  public void broadcast(Kind kind, HealthEvent event) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(event, "event");
    HealthDelta payload = new HealthDelta(deltaKindName(kind), event);
    channel.publish(SseFrameKind.UPDATE, payload);
  }

  /** Publishes a condition transition without letting an observer gate state publication. */
  public void broadcastAsync(Kind kind, HealthEvent event) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(event, "event");
    channel.publishAsync(SseFrameKind.UPDATE, new HealthDelta(deltaKindName(kind), event));
  }

  private static String deltaKindName(Kind kind) {
    return switch (kind) {
      case CONDITION_ADDED -> "condition-added";
      case CONDITION_MODIFIED -> "condition-modified";
      case CONDITION_REMOVED -> "condition-removed";
      case OCCURRENCE_APPENDED -> "occurrence-appended";
    };
  }

  /** Wire payload for a single health-event delta. Carried in the envelope's payload. */
  public record HealthDelta(String kind, HealthEvent event) {
    public HealthDelta {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(event, "event");
    }
  }

  /** Discriminator for delta payloads. The wire form is the kebab-cased name. */
  public enum Kind {
    CONDITION_ADDED,
    CONDITION_MODIFIED,
    CONDITION_REMOVED,
    OCCURRENCE_APPENDED
  }

  /**
   * Typed-listener convenience for emitter unit tests (and any future consumer that wants
   * the {@code (catalogVersion, kind, event)} triplet without walking the envelope). The
   * production controller subscribes with the envelope-typed API; this is a thin adapter
   * that retains the pre-436 typed shape for callers that don't care about envelope-level
   * fields.
   */
  public record HealthChangeEvent(long catalogVersion, Kind kind, HealthEvent event) {
    public HealthChangeEvent {
      Objects.requireNonNull(kind, "kind");
      Objects.requireNonNull(event, "event");
    }
  }

  /**
   * Subscribes a typed listener; envelope-level fields are projected into a
   * {@link HealthChangeEvent} record. Returns the underlying subscription handle.
   */
  public SseStreamChannel.Subscription subscribeTyped(Consumer<HealthChangeEvent> listener) {
    Objects.requireNonNull(listener, "listener");
    return channel.subscribe(
        env -> {
          if (env.payload() instanceof HealthDelta delta) {
            Kind k = Kind.valueOf(delta.kind().toUpperCase().replace('-', '_'));
            listener.accept(new HealthChangeEvent(env.seq(), k, delta.event()));
          }
        });
  }
}
