/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.indexing;

import io.justsearch.app.api.indexing.IndexingJobView;
import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import io.justsearch.app.observability.stream.SseStreamChannel;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Per-Resource change registry for {@code core.indexing-jobs} TABULAR Resource
 * (slice 445 §A.5). Mirrors the {@code HealthEventChangeRegistry} pattern:
 * wraps an {@link SseStreamChannel} keyed on a stable {@link StreamId} and
 * broadcasts typed {@link Delta} payloads inside the universal SSE envelope.
 *
 * <p>Per slice 445 lean scope (verification commit {@code 044b21ab3}):
 * concrete to {@link IndexingJobView}, not parameterized over a generic
 * {@code TabularChangeRegistry<K, V>}. A second TABULAR Resource is the
 * trigger for extracting a primitive.
 *
 * <p>Wire shape: each broadcast becomes one {@link SseFrameKind#UPDATE}
 * envelope with a {@link Delta} payload. Snapshots are NOT broadcast — they
 * are returned synchronously from the controller's subscribe handler so the
 * snapshot↔delta seq cursor stays consistent (snapshot carries the channel's
 * current seq; subsequent deltas have strictly greater seq).
 */
public final class IndexingJobsChangeRegistry {

  /** Stable StreamId for the indexing-jobs SSE stream. */
  public static final StreamId STREAM_ID = StreamId.surface("indexing-jobs");

  private final SseStreamChannel channel;

  public IndexingJobsChangeRegistry() {
    this.channel = new SseStreamChannel(STREAM_ID);
  }

  public IndexingJobsChangeRegistry(SseStreamChannel channel) {
    this.channel = Objects.requireNonNull(channel, "channel");
  }

  /** Underlying channel for controller-side SSE writer wiring. */
  public SseStreamChannel channel() {
    return channel;
  }

  /** Current monotonic seq cursor on the channel. */
  public long currentSeq() {
    return channel.currentSeq();
  }

  /**
   * Broadcasts a delta event. Wraps the delta in an UPDATE envelope, assigns
   * the next monotonic seq, appends to the ring buffer, and delivers to every
   * active envelope-listener.
   *
   * <p>Slice 3a.1.9 §B.B.B B1 / §B.B.A.1 closure: the broadcast payload is now
   * a discriminated {@link DeltaEnvelope} wrapping the {@link Delta}. Without
   * the wrapper, Insert and Update both serialized as {@code {row: T}} (the
   * sealed interface variants share field shape); the FE consumer's strategy
   * had to dispatch on payload-shape probing rather than a {@code kind}
   * discriminator. The new wrapper carries an explicit {@code kind} field
   * ({@code "insert" | "update" | "delete" | "snapshot-replaced"}); the FE
   * strategy reads it directly. Mirrors the {@link
   * io.justsearch.app.observability.health.HealthEventChangeRegistry.HealthDelta}
   * precedent.
   */
  public void broadcast(Delta delta) {
    Objects.requireNonNull(delta, "delta");
    channel.publish(SseFrameKind.UPDATE, DeltaEnvelope.of(delta));
  }

  /** Assigns the cursor now, delivering SSE observers on the channel's owned executor. */
  public void broadcastAsync(Delta delta) {
    Objects.requireNonNull(delta, "delta");
    channel.publishAsync(SseFrameKind.UPDATE, DeltaEnvelope.of(delta));
  }

  /** Subscribes an envelope-shaped listener for raw SSE frames. */
  public SseStreamChannel.Subscription subscribe(Consumer<SseEnvelope> listener) {
    return channel.subscribe(listener);
  }

  /**
   * Subscribes a typed listener for {@link Delta}-only events. Envelope-level
   * fields (seq, frameKind) are dropped; convenience for tests and consumers
   * that don't care about transport metadata.
   *
   * <p>Unwraps the {@link DeltaEnvelope} wrapper so listeners observe the raw
   * {@link Delta} variant — preserves backward-compat with consumers that
   * predate the §B.B.B B1 wire-shape change.
   */
  public SseStreamChannel.Subscription subscribeTyped(Consumer<Delta> listener) {
    Objects.requireNonNull(listener, "listener");
    return channel.subscribe(
        env -> {
          Object payload = env.payload();
          if (payload instanceof DeltaEnvelope envelope) {
            Delta delta = envelope.toDelta();
            if (delta != null) listener.accept(delta);
          } else if (payload instanceof Delta delta) {
            // Belt-and-suspenders for any legacy publishers that haven't
            // adopted the wrapper. Removable once codebase-audit confirms
            // single broadcast site.
            listener.accept(delta);
          }
        });
  }

  /**
   * Wire payload for a single indexing-jobs delta. Discriminated union;
   * exhaustive switches at the FE consumer wire it to keyed-map mutations.
   *
   * <p>{@link Delete} carries only the {@code pathHash} primary key — the row
   * is gone from the producer.
   *
   * <p>{@link SnapshotReplaced} is emitted on stream-reconnect so consumers
   * rebuild their keyed map. The initial snapshot at subscribe-time is
   * delivered via the controller's snapshot helper, NOT through broadcast,
   * so the seq cursor remains monotonically aligned.
   */
  public sealed interface Delta {
    record Insert(IndexingJobView row) implements Delta {}

    record Update(IndexingJobView row) implements Delta {}

    record Delete(String pathHash) implements Delta {}

    record SnapshotReplaced(List<IndexingJobView> items) implements Delta {}
  }

  /**
   * Slice 3a.1.9 §B.B.B B1 — wire envelope that wraps a {@link Delta} variant
   * with an explicit {@code kind} discriminator. Slice 445 originally
   * published the sealed-interface variants directly; without polymorphic
   * type info Jackson serialized {@code Insert(row)} and {@code Update(row)}
   * with the same shape ({@code {"row": …}}), forcing FE consumers to
   * dispatch on payload-shape probing. The wrapper makes the delta type
   * the wire-explicit primary discriminator.
   *
   * <p>Wire shape:
   *
   * <ul>
   *   <li>{@code {"kind": "insert", "row": IndexingJobView}}
   *   <li>{@code {"kind": "update", "row": IndexingJobView}}
   *   <li>{@code {"kind": "delete", "primaryKeyValue": "<sha256>"}}
   *   <li>{@code {"kind": "snapshot-replaced", "items": IndexingJobView[]}}
   * </ul>
   *
   * <p>The {@code primaryKeyValue} field is named generically so the wire
   * shape is reusable across TABULAR Resources with different primary-key
   * field names — each Resource's FE strategy reads this single field
   * regardless of the per-row {@code pathHash} / {@code id} / {@code key}
   * naming. (For inserts/updates the row payload still carries the
   * Resource-specific field; only the delete-by-key field is generic.)
   *
   * <p>FE consumers (the TABULAR subscription strategy in
   * {@code shell-v0/strategies/subscriptionStrategy.ts}) prefer the
   * {@code kind}-discriminated dispatch; payload-shape probing remains the
   * fallback for forward-compat with legacy producers (none today).
   */
  public record DeltaEnvelope(
      String kind,
      IndexingJobView row,
      String primaryKeyValue,
      List<IndexingJobView> items) {

    public DeltaEnvelope {
      Objects.requireNonNull(kind, "kind");
      // Defensive Map-of behavior: Jackson serializes nulls as JSON nulls;
      // FE strategy treats `null` fields as "not this variant." No need to
      // strip nulls here.
    }

    /** Convenience factory: derives wire fields from a {@link Delta} variant. */
    public static DeltaEnvelope of(Delta delta) {
      Objects.requireNonNull(delta, "delta");
      return switch (delta) {
        case Delta.Insert i -> new DeltaEnvelope("insert", i.row(), null, null);
        case Delta.Update u -> new DeltaEnvelope("update", u.row(), null, null);
        case Delta.Delete d -> new DeltaEnvelope("delete", null, d.pathHash(), null);
        case Delta.SnapshotReplaced s ->
            new DeltaEnvelope("snapshot-replaced", null, null, s.items());
      };
    }

    /**
     * Inverse of {@link #of(Delta)}: reconstructs the typed {@link Delta}
     * variant. Returns {@code null} when the envelope's {@code kind} is
     * unrecognized (forward-compat for future delta types).
     */
    public Delta toDelta() {
      return switch (kind) {
        case "insert" -> row == null ? null : new Delta.Insert(row);
        case "update" -> row == null ? null : new Delta.Update(row);
        case "delete" -> primaryKeyValue == null ? null : new Delta.Delete(primaryKeyValue);
        case "snapshot-replaced" ->
            items == null ? null : new Delta.SnapshotReplaced(items);
        default -> null;
      };
    }
  }
}
