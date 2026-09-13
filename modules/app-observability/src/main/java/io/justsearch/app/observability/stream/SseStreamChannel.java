/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/**
 * Owns a stream's sequence, retained UPDATEs and ordered subscriber delivery.
 *
 * <p>Sequence allocation, ring append and bounded enqueue share one short publication
 * boundary. Each listener has one drainer; all listener calls run outside that boundary.
 * A publisher encountering a blocked drainer only enqueues. Overflow retires that listener,
 * and other listeners continue. No executor or socket I/O belongs to the channel lock.
 *
 * <p>Numeric {@link #subscribeAndReplay} is the run-stream attachment contract: zero
 * attaches to the retained tail, and positive cursors require the existing numeric window.
 */
public final class SseStreamChannel {
  private final StreamId streamId;
  private final StreamSequenceTracker sequence;
  private final FrameHistoryRingBuffer history;
  private final Set<HandoffListener> listeners = new LinkedHashSet<>();
  private final Clock clock;
  private final Object publicationGate = new Object();

  public SseStreamChannel(StreamId streamId) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC());
  }

  public SseStreamChannel(
      StreamId streamId, StreamSequenceTracker sequence, FrameHistoryRingBuffer history, Clock clock) {
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.history = Objects.requireNonNull(history, "history");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public StreamId streamId() {
    return streamId;
  }

  /** Returns the most recently issued sequence, or zero before any frame. */
  public long currentSeq() {
    return sequence.current();
  }

  /** Publishes in source order; failed or overflowing listeners are removed independently. */
  public void publish(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    List<HandoffListener> targets;
    synchronized (publicationGate) {
      SseEnvelope envelope = nextEnvelope(frameKind, payload);
      if (frameKind == SseFrameKind.UPDATE) {
        history.append(envelope);
      }
      targets = List.copyOf(listeners);
      for (HandoffListener listener : targets) {
        listener.enqueue(envelope);
      }
    }
    Error fatal = null;
    for (HandoffListener listener : targets) {
      try {
        // Claim only when this listener can actually be drained. Claiming all targets
        // before the first socket write would strand healthy listeners behind that socket.
        listener.drainIfAvailable();
      } catch (RuntimeException ignored) {
        // The drainer already retired this failed subscriber; continue healthy fan-out.
      } catch (Error failure) {
        if (fatal == null) {
          fatal = failure;
        } else if (fatal != failure) {
          fatal.addSuppressed(failure);
        }
      }
    }
    if (fatal != null) {
      throw fatal;
    }
  }

  /** Allocates a per-client control frame without retaining or broadcasting it. */
  public SseEnvelope nextEnvelope(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    synchronized (publicationGate) {
      long seq = sequence.next();
      return new SseEnvelope(streamId, frameKind, seq, clock.instant(), payload,
          ResumeTokenCodec.encode(streamId, seq));
    }
  }

  public List<SseEnvelope> framesSince(long sinceSeq) {
    return history.framesSince(sinceSeq);
  }

  public long oldestRetainedSeq() {
    return history.oldestSeqOrZero();
  }

  /** Numeric run policy; zero means a fresh attachment to the retained tail. */
  public boolean isWithinResumeWindow(long sinceSeq) {
    synchronized (publicationGate) {
      if (sinceSeq > currentSeq()) {
        return false;
      }
      long oldest = oldestRetainedSeq();
      return !(sinceSeq > 0 && (oldest == 0 || sinceSeq < oldest));
    }
  }

  /** Live registered observers, excluding failed and overflowing subscribers. */
  public int listenerCount() {
    synchronized (publicationGate) {
      return listeners.size();
    }
  }

  public Subscription subscribe(Consumer<SseEnvelope> listener) {
    HandoffListener handoff = new HandoffListener(Objects.requireNonNull(listener, "listener"));
    synchronized (publicationGate) {
      listeners.add(handoff);
    }
    return handoff::retire;
  }

  /** Registers and snapshots replay atomically, then delivers outside the publication lock. */
  public Optional<Subscription> subscribeAndReplay(Consumer<SseEnvelope> listener, long sinceSeq) {
    HandoffListener handoff = new HandoffListener(Objects.requireNonNull(listener, "listener"));
    List<SseEnvelope> replay;
    synchronized (publicationGate) {
      if (!isWithinResumeWindow(sinceSeq)) {
        return Optional.empty();
      }
      replay = history.framesSince(sinceSeq);
      handoff.draining = true;
      listeners.add(handoff);
    }
    handoff.handOff(replay);
    return Optional.of(handoff::retire);
  }

  /** Permanent bounded serial delivery owner, including the initial replay handoff. */
  private final class HandoffListener {
    private final Consumer<SseEnvelope> delegate;
    private final Queue<SseEnvelope> buffered;
    private boolean draining;
    private boolean retired;
    private boolean overflowed;

    HandoffListener(Consumer<SseEnvelope> delegate) {
      this.delegate = delegate;
      this.buffered = new ArrayBlockingQueue<>(history.capacity());
    }

    /** Called only under publicationGate. */
    void enqueue(SseEnvelope envelope) {
      if (!retired && !buffered.offer(envelope)) {
        overflowed = true;
        retire();
      }
    }

    void drainIfAvailable() {
      synchronized (publicationGate) {
        if (retired || draining) {
          return;
        }
        draining = true;
      }
      drainOwned();
    }

    void handOff(List<SseEnvelope> replay) {
      try {
        for (SseEnvelope frame : replay) {
          synchronized (publicationGate) {
            requireHealthy();
          }
          delegate.accept(frame);
        }
        drainOwned();
      } catch (RuntimeException | Error failure) {
        retire();
        throw failure;
      }
    }

    private void drainOwned() {
      try {
        while (true) {
          SseEnvelope frame;
          synchronized (publicationGate) {
            requireHealthy();
            frame = buffered.poll();
            if (retired || frame == null) {
              draining = false;
              return;
            }
          }
          delegate.accept(frame);
        }
      } catch (RuntimeException | Error failure) {
        retire();
        throw failure;
      }
    }

    private void requireHealthy() {
      if (overflowed) {
        throw new IllegalStateException("SSE replay handoff could not keep up for " + streamId);
      }
    }

    void retire() {
      synchronized (publicationGate) {
        retired = true;
        listeners.remove(this);
        buffered.clear();
      }
    }
  }

  @FunctionalInterface
  public interface Subscription {
    void unsubscribe();
  }
}
