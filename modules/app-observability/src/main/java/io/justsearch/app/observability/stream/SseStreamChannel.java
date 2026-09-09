/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * Per-stream coordinator unifying sequence + ring buffer + listener fan-out under the
 * universal SSE envelope shape.
 *
 * <p>Per slice 436: each stream (one per {@link StreamId}) has its own monotonic
 * sequence counter, ring buffer of recent UPDATE frames, and listener set. The channel
 * is the single integration point that stream producers (the various change registries)
 * delegate to.
 *
 * <p>Frame discipline:
 *
 * <ul>
 *   <li>{@link #publish(SseFrameKind, Object)} — broadcast frame: assigns next seq from
 *       the shared tracker, wraps in an envelope, appends to ring (only if
 *       {@link SseFrameKind#UPDATE}), notifies all listeners. Used for catalog/state
 *       updates that all subscribers see. Heartbeat lifecycle frames also typically use
 *       publish (broadcast to all clients).
 *   <li>{@link #nextEnvelope(SseFrameKind, Object)} — per-client frame: same envelope
 *       construction (with shared seq for monotonicity within the wire stream), but
 *       returns the envelope without appending to ring or broadcasting. The caller is
 *       expected to send it directly to a single connected client. Used for connected /
 *       snapshot / closing / reset lifecycle frames that vary per connection.
 * </ul>
 *
 * <p><strong>Subscribe atomicity</strong> (tempdoc 834 §1.3.1). {@link #subscribe} registers
 * a listener and nothing else; the caller replays separately, so a frame published between
 * the two can be missed. {@link #subscribeAndReplay} closes that race for the
 * <em>resume</em> path: replay-snapshot and listener registration happen under one write
 * lock that {@link #publish} excludes, so a published frame reaches a resuming subscriber
 * <em>either</em> via the replay <em>or</em> via the live fan-out — never both, never
 * neither.
 *
 * <p>The race is NOT closed for the no-cursor path (17 of the 18 production catalog routes
 * on a fresh connect), and this class does not pretend otherwise: closing it would mean
 * invoking a caller-supplied snapshot supplier under this monitor — lock inversion across
 * 18 controllers, each free to take its own locks inside that supplier. Tempdoc 834 §1.3.1
 * scopes the fix to the resume branch deliberately; a catalog self-corrects at its next
 * snapshot, whereas a run stream that drops a chunk yields a permanently corrupted answer,
 * and run streams never take the no-cursor path.
 *
 * <p><strong>Lock cost, measured.</strong> The listener set is concurrent and only the ring
 * synchronizes per method, so the channel was lock-free before 834; some streams run at
 * ~30 fps. {@code publish} therefore takes only the READ lock (an uncontended acquire, once
 * per frame, on top of the ring's existing monitor) and {@code subscribeAndReplay} takes the
 * write lock once per connection, held only while snapshotting the frames to replay —
 * never across a socket write (see the two-phase handoff below). Tempdoc 834's probe P8 ran
 * (§14/D2): p99 read-lock acquire is 1.1 µs under 153 publishes/s with 228 concurrent
 * write-lock replays, so the read-lock primary stands and the doc's generation-counter
 * fallback is not promoted.
 */
public final class SseStreamChannel {

  private final StreamId streamId;
  private final StreamSequenceTracker sequence;
  private final FrameHistoryRingBuffer history;
  private final Set<Consumer<SseEnvelope>> listeners = ConcurrentHashMap.newKeySet();
  private final Clock clock;

  /**
   * Guards the publish-vs-subscribe boundary, NOT the ring (which has its own monitor).
   * Read = publish, write = atomic subscribe-and-replay.
   */
  private final ReentrantReadWriteLock subscribeLock = new ReentrantReadWriteLock();

  public SseStreamChannel(StreamId streamId) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC());
  }

  public SseStreamChannel(
      StreamId streamId,
      StreamSequenceTracker sequence,
      FrameHistoryRingBuffer history,
      Clock clock) {
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.history = Objects.requireNonNull(history, "history");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public StreamId streamId() {
    return streamId;
  }

  /** Returns the current (most-recently-issued) sequence number. 0 before any frames. */
  public long currentSeq() {
    return sequence.current();
  }

  /**
   * Publishes a frame to all subscribers. Assigns the next monotonic seq, wraps in an
   * envelope, appends to ring (UPDATE only), broadcasts.
   *
   * <p>Listeners that throw are removed inline (mirrors the legacy registry pattern).
   *
   * <p>Ring-append and fan-out run under the read lock as one unit, so an atomic subscriber
   * (see {@link #subscribeAndReplay}) never observes the half-state where a frame is in the
   * ring but has not yet been fanned out, or vice versa.
   */
  public void publish(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    SseEnvelope envelope = nextEnvelope(frameKind, payload);
    subscribeLock.readLock().lock();
    try {
      if (frameKind == SseFrameKind.UPDATE) {
        history.append(envelope);
      }
      listeners.removeIf(
          listener -> {
            try {
              listener.accept(envelope);
              return false;
            } catch (RuntimeException e) {
              return true;
            }
          });
    } finally {
      subscribeLock.readLock().unlock();
    }
  }

  /**
   * Creates and returns an envelope for a per-client frame WITHOUT appending to the ring
   * buffer or broadcasting. The seq is consumed from the shared tracker so the wire seq
   * remains monotonic within a single client connection. Used for connected / snapshot /
   * closing / reset lifecycle frames.
   */
  public SseEnvelope nextEnvelope(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    long seq = sequence.next();
    Instant ts = clock.instant();
    String resumeToken = ResumeTokenCodec.encode(streamId, seq);
    return new SseEnvelope(streamId, frameKind, seq, ts, payload, resumeToken);
  }

  /**
   * Returns frames retained in the ring buffer whose seq > sinceSeq, in chronological
   * order. Empty list if no frames newer than sinceSeq are retained.
   */
  public List<SseEnvelope> framesSince(long sinceSeq) {
    return history.framesSince(sinceSeq);
  }

  /**
   * Returns the seq of the oldest frame still retained in the ring buffer, or 0 if the
   * buffer is empty. Callers use this to detect "resume token predates the buffer."
   */
  public long oldestRetainedSeq() {
    return history.oldestSeqOrZero();
  }

  /**
   * True when {@code sinceSeq} lies inside the replayable window. The three "outside
   * window" cases (slice 436 Fix B) are: a cursor from a future / different server lifetime
   * ({@code sinceSeq > current}); an empty buffer with a positive cursor (server restarted,
   * or no UPDATEs since the cursor was issued — the gap cannot be validated); and a cursor
   * predating the oldest retained frame.
   */
  public boolean isWithinResumeWindow(long sinceSeq) {
    long current = currentSeq();
    if (sinceSeq > current) {
      return false;
    }
    long oldest = oldestRetainedSeq();
    return !(sinceSeq > 0 && (oldest == 0 || sinceSeq < oldest));
  }

  /**
   * How many listeners are currently registered.
   *
   * <p>This is the OBSERVER-COUNT authority for run channels (tempdoc 834 §3): it reads the live
   * set, so a listener evicted by {@link #publish}'s evict-on-throw stops being counted the moment
   * its socket dies. A count maintained separately by a caller would keep a dead observer on the
   * books, and the zero-observer park would never fire — the exact failure R4 names.
   */
  public int listenerCount() {
    return listeners.size();
  }

  /** Subscribes a listener; returns a {@link Subscription} for explicit unsubscribe. */
  public Subscription subscribe(Consumer<SseEnvelope> listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /**
   * Atomically validates {@code sinceSeq} against the resume window, registers
   * {@code listener}, and replays the retained frames newer than the cursor to it.
   *
   * <p>Returns empty when the cursor is outside the window — no listener is registered and
   * nothing is replayed, so the caller falls back to reset + snapshot + {@link #subscribe}.
   *
   * <p><strong>Two-phase handoff</strong> (tempdoc 834 §2). Replaying inside the lock would
   * stall every publisher behind one slow-but-alive reattacher's socket, since the
   * listener's terminal action is a blocking write. So:
   *
   * <ol>
   *   <li>Under the write lock: check the window, snapshot the frames to replay, and
   *       register the listener in a <em>buffering</em> state.
   *   <li>Outside the lock: drain the snapshot to the listener, then drain whatever arrived
   *       while that was happening.
   *   <li>Flip to pass-through under the write lock with the buffer empty — the one moment
   *       at which "no publisher is mid-fan-out" is guaranteed.
   * </ol>
   *
   * <p>A listener that throws during the handoff is unregistered and the exception
   * propagates, matching {@link #publish}'s evict-on-throw contract.
   *
   * <p>MUST NOT be called from inside a listener of this same channel: {@code publish}
   * holds the read lock across the fan-out, and a read-to-write upgrade deadlocks. Callers
   * are connection handler threads, which never hold the read lock.
   */
  public Optional<Subscription> subscribeAndReplay(
      Consumer<SseEnvelope> listener, long sinceSeq) {
    Objects.requireNonNull(listener, "listener");
    HandoffListener handoff = new HandoffListener(listener);
    List<SseEnvelope> replay;
    subscribeLock.writeLock().lock();
    try {
      if (!isWithinResumeWindow(sinceSeq)) {
        return Optional.empty();
      }
      replay = history.framesSince(sinceSeq);
      listeners.add(handoff);
    } finally {
      subscribeLock.writeLock().unlock();
    }
    boolean handedOff = false;
    try {
      handoff.handOff(replay);
      handedOff = true;
    } catch (RuntimeException | Error e) {
      listeners.remove(handoff);
      throw e;
    } finally {
      if (!handedOff) {
        handoff.clear();
      }
    }
    return Optional.of(() -> {
      listeners.remove(handoff);
      handoff.clear();
    });
  }

  /**
   * Buffers broadcasts until the replay has been written, then passes through. Buffering is
   * cheap (an enqueue) so a publisher never waits on the new subscriber's socket; the drain
   * itself runs outside every lock.
   */
  private final class HandoffListener implements Consumer<SseEnvelope> {

    private final Consumer<SseEnvelope> delegate;
    private final Queue<SseEnvelope> buffered;
    private volatile boolean passThrough;
    private volatile boolean overflowed;

    HandoffListener(Consumer<SseEnvelope> delegate) {
      this.delegate = delegate;
      this.buffered = new ArrayBlockingQueue<>(history.capacity());
    }

    @Override
    public void accept(SseEnvelope envelope) {
      // Always called with the read lock held (from publish), so passThrough cannot flip
      // underneath this check — the flip below takes the write lock.
      if (passThrough) {
        delegate.accept(envelope);
      } else {
        synchronized (buffered) {
          if (overflowed || !buffered.offer(envelope)) {
            overflowed = true;
            buffered.clear();
            throw new IllegalStateException(
                "SSE replay handoff buffer overflowed for " + streamId);
          }
        }
      }
    }

    void handOff(List<SseEnvelope> replay) {
      for (SseEnvelope frame : replay) {
        requireHealthy();
        delegate.accept(frame);
      }
      while (true) {
        List<SseEnvelope> batch = new ArrayList<>();
        subscribeLock.writeLock().lock();
        try {
          requireHealthy();
          if (buffered.isEmpty()) {
            passThrough = true;
            return;
          }
          SseEnvelope frame;
          while ((frame = buffered.poll()) != null) {
            batch.add(frame);
          }
        } finally {
          subscribeLock.writeLock().unlock();
        }
        for (SseEnvelope frame : batch) {
          requireHealthy();
          delegate.accept(frame);
        }
      }
    }

    private void requireHealthy() {
      if (overflowed) {
        throw new IllegalStateException(
            "SSE replay handoff could not keep up for " + streamId);
      }
    }

    void clear() {
      buffered.clear();
    }
  }

  @FunctionalInterface
  public interface Subscription {
    void unsubscribe();
  }
}
