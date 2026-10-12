/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.stream;

import io.justsearch.app.api.stream.SseEnvelope;
import io.justsearch.app.api.stream.SseFrameKind;
import io.justsearch.app.api.stream.StreamId;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
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
public final class SseStreamChannel implements AutoCloseable {
  private final StreamId streamId;
  private final StreamSequenceTracker sequence;
  private final FrameHistoryRingBuffer history;
  private final Set<HandoffListener> listeners = new LinkedHashSet<>();
  private final Clock clock;
  private final Object publicationGate = new Object();
  private final UUID incarnation = UUID.randomUUID();
  private ExecutorService deliveryOwner;
  private Runnable releaseDeliveryOwner;
  private boolean interruptCallerDelivery;
  private boolean closed;

  public SseStreamChannel(StreamId streamId) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC());
  }

  /** The supplied executor owns asynchronous delivery and is shut down with this channel. */
  public SseStreamChannel(StreamId streamId, ExecutorService deliveryOwner) {
    this(streamId);
    this.deliveryOwner = Objects.requireNonNull(deliveryOwner, "deliveryOwner");
  }

  /** Also releases the owner's registration when this channel's lifetime ends. */
  public SseStreamChannel(StreamId streamId, ExecutorService deliveryOwner, Runnable releaseOwner) {
    this(streamId, deliveryOwner);
    this.releaseDeliveryOwner = Objects.requireNonNull(releaseOwner, "releaseOwner");
  }

  /** Health delivery includes synchronous observers; other streams keep their caller's owner. */
  public SseStreamChannel withInterruptibleDelivery() {
    synchronized (publicationGate) { interruptCallerDelivery = true; }
    return this;
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
    List<HandoffListener> targets = enqueuePublication(frameKind, payload);
    Error fatal = null;
    // Notify overflow owners before any target can block in a socket write.
    for (HandoffListener listener : targets) {
      try {
        listener.notifyRetirement();
      } catch (RuntimeException ignored) {
        // Other owners and healthy listeners must still be notified/drained.
      } catch (Error failure) {
        if (fatal == null) fatal = failure;
        else if (fatal != failure) fatal.addSuppressed(failure);
      }
    }
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

  /**
   * Publishes state immediately and drains each observer independently. A blocked observer cannot
   * gate the publisher or another subscriber. Each handoff still has one bounded serial drainer.
   */
  public void publishAsync(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    for (var listener : enqueuePublication(frameKind, payload)) {
      listener.drainAsync();
    }
  }

  /** Ends every subscription without waiting for arbitrary observer code to return. */
  @Override
  public void close() {
    List<HandoffListener> retired;
    ExecutorService owner;
    synchronized (publicationGate) {
      if (closed) return;
      closed = true;
      retired = List.copyOf(listeners);
      for (var listener : retired) {
        listener.retireLocked();
        if (listener.callerDelivery != null && listener.callerDelivery != Thread.currentThread())
          listener.callerDelivery.interrupt();
      }
      owner = deliveryOwner;
    }
    if (owner != null) owner.shutdownNow();
    Throwable failure = null;
    for (var listener : retired) {
      try { listener.notifyRetirement(); }
      catch (RuntimeException | Error problem) {
        if (failure == null) failure = problem;
        else if (failure != problem) failure.addSuppressed(problem);
      }
    }
    if (releaseDeliveryOwner != null) {
      try { releaseDeliveryOwner.run(); }
      catch (RuntimeException | Error problem) {
        if (failure == null) failure = problem;
        else if (failure != problem) failure.addSuppressed(problem);
      }
    }
    if (failure instanceof RuntimeException problem) throw problem;
    if (failure instanceof Error problem) throw problem;
  }

  private List<HandoffListener> enqueuePublication(SseFrameKind frameKind, Object payload) {
    List<HandoffListener> targets;
    synchronized (publicationGate) {
      if (closed) return List.of();
      SseEnvelope envelope = nextEnvelope(frameKind, payload);
      if (frameKind == SseFrameKind.UPDATE) {
        history.append(envelope);
      }
      targets = List.copyOf(listeners);
      for (HandoffListener listener : targets) {
        listener.enqueue(envelope);
      }
    }
    return targets;
  }

  /** Allocates a per-client control frame without retaining or broadcasting it. */
  public SseEnvelope nextEnvelope(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    synchronized (publicationGate) {
      long seq = sequence.next();
      return new SseEnvelope(streamId, frameKind, seq, clock.instant(), payload,
          ResumeTokenCodec.encode(streamId, seq, incarnation));
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
      if (closed) throw new IllegalStateException("SSE channel is closed");
      listeners.add(handoff);
    }
    return handoff;
  }

  /** Capture before reading external state; no source query or socket write holds this lock. */
  public SnapshotBoundary captureSnapshotBoundary() {
    synchronized (publicationGate) {
      return new SnapshotBoundary(this, currentSeq());
    }
  }

  /** Strong resume: a missing/foreign incarnation can never identify this channel's history. */
  public Optional<Subscription> subscribeAndReplay(
      Consumer<SseEnvelope> listener, String token, Runnable beforeReplay) {
    return subscribeAndReplay(listener, token, beforeReplay, subscription -> {});
  }

  /** Registers transport ownership outside the source lock, before any prefix or replay I/O. */
  public Optional<Subscription> subscribeAndReplay(
      Consumer<SseEnvelope> listener, String token, Runnable beforeReplay,
      Consumer<Subscription> onRegistered) {
    Optional<ResumeTokenCodec.Decoded> decoded = ResumeTokenCodec.decode(token);
    if (decoded.isEmpty() || !streamId.equals(decoded.get().streamId())
        || !incarnation.equals(decoded.get().incarnation())) {
      return Optional.empty();
    }
    return subscribeAndReplay(listener, new SnapshotBoundary(this, decoded.get().seq()), beforeReplay,
        onRegistered);
  }

  /** Validate/register before sending the candidate snapshot, then replay and drain outside locks. */
  public Optional<Subscription> subscribeAndReplay(
      Consumer<SseEnvelope> listener, SnapshotBoundary boundary, Runnable beforeReplay) {
    return subscribeAndReplay(listener, boundary, beforeReplay, subscription -> {});
  }

  public Optional<Subscription> subscribeAndReplay(
      Consumer<SseEnvelope> listener, SnapshotBoundary boundary, Runnable beforeReplay,
      Consumer<Subscription> onRegistered) {
    Objects.requireNonNull(boundary, "boundary");
    Objects.requireNonNull(beforeReplay, "beforeReplay");
    Objects.requireNonNull(onRegistered, "onRegistered");
    HandoffListener handoff = new HandoffListener(Objects.requireNonNull(listener, "listener"));
    List<SseEnvelope> replay;
    synchronized (publicationGate) {
      if (closed || boundary.owner != this || boundary.seq > currentSeq()
          || boundary.seq < history.droppedThroughSeq()) {
        return Optional.empty();
      }
      replay = history.framesSince(boundary.seq).stream()
          .sorted(Comparator.comparingLong(SseEnvelope::seq)).toList();
      handoff.draining = true;
      handoff.callerDelivery = Thread.currentThread();
      listeners.add(handoff);
    }
    try {
      onRegistered.accept(handoff);
      handoff.runPrefix(beforeReplay);
      handoff.handOff(replay);
    } catch (RuntimeException | Error failure) {
      handoff.retireAfter(failure);
      throw failure;
    } finally {
      handoff.releaseCaller();
    }
    return Optional.of(handoff);
  }

  /** Source-owned boundary; callers cannot construct a cursor for another channel or future state. */
  public static final class SnapshotBoundary {
    private final SseStreamChannel owner;
    private final long seq;

    private SnapshotBoundary(SseStreamChannel owner, long seq) {
      this.owner = owner;
      this.seq = seq;
    }

    public String resumeToken() {
      return ResumeTokenCodec.encode(owner.streamId, seq, owner.incarnation);
    }
  }

  /** Registers and snapshots replay atomically, then delivers outside the publication lock. */
  public Optional<Subscription> subscribeAndReplay(Consumer<SseEnvelope> listener, long sinceSeq) {
    return subscribeAndReplay(listener, sinceSeq, subscription -> {});
  }

  /** Numeric run policy with ownership acquired before replay can block. */
  public Optional<Subscription> subscribeAndReplay(Consumer<SseEnvelope> listener, long sinceSeq,
      Consumer<Subscription> onRegistered) {
    return subscribeAndReplay(listener, sinceSeq, () -> {}, onRegistered);
  }

  /** Numeric run prefix follows ownership acquisition and precedes captured replay. */
  public Optional<Subscription> subscribeAndReplay(Consumer<SseEnvelope> listener, long sinceSeq,
      Runnable beforeReplay, Consumer<Subscription> onRegistered) {
    Objects.requireNonNull(onRegistered, "onRegistered");
    Objects.requireNonNull(beforeReplay, "beforeReplay");
    HandoffListener handoff = new HandoffListener(Objects.requireNonNull(listener, "listener"));
    List<SseEnvelope> replay;
    synchronized (publicationGate) {
      if (closed || !isWithinResumeWindow(sinceSeq)) {
        return Optional.empty();
      }
      replay = history.framesSince(sinceSeq);
      handoff.draining = true;
      handoff.callerDelivery = Thread.currentThread();
      listeners.add(handoff);
    }
    try {
      onRegistered.accept(handoff);
      handoff.runPrefix(beforeReplay);
      handoff.handOff(replay);
    } catch (RuntimeException | Error failure) {
      handoff.retireAfter(failure);
      throw failure;
    } finally {
      handoff.releaseCaller();
    }
    return Optional.of(handoff);
  }

  /** Permanent bounded serial delivery owner, including the initial replay handoff. */
  private final class HandoffListener implements Subscription {
    private final Consumer<SseEnvelope> delegate;
    private final Queue<SseEnvelope> buffered;
    private final List<Runnable> retirementListeners = new ArrayList<>();
    private boolean draining;
    private boolean retired;
    private boolean overflowed;
    private FutureTask<Void> delivery;
    private Thread callerDelivery;

    HandoffListener(Consumer<SseEnvelope> delegate) {
      this.delegate = delegate;
      this.buffered = new ArrayBlockingQueue<>(history.capacity());
    }

    /** Called only under publicationGate. */
    void enqueue(SseEnvelope envelope) {
      if (!retired && !buffered.offer(envelope)) {
        overflowed = true;
        retireLocked();
      }
    }

    void drainIfAvailable() {
      boolean claimed = false;
      try {
        synchronized (publicationGate) {
          if (retired || draining) {
            return;
          }
          draining = true;
          callerDelivery = Thread.currentThread();
          claimed = true;
        }
        drainOwned();
      } finally {
        if (claimed) releaseCaller();
        notifyRetirement();
      }
    }

    void drainAsync() {
      FutureTask<Void> task;
      ExecutorService owner;
      synchronized (publicationGate) {
        if (retired && retirementListeners.isEmpty() || !retired && draining) return;
        if (deliveryOwner == null) deliveryOwner = Executors.newVirtualThreadPerTaskExecutor();
        owner = deliveryOwner;
        boolean deliver = !retired;
        if (deliver) draining = true;
        task = new FutureTask<>(() -> {
          try { if (deliver) drainOwned(); }
          finally { notifyRetirement(); }
          return null;
        });
        if (deliver) delivery = task;
      }
      try { owner.execute(task); }
      catch (RuntimeException failure) {
        // A stopped delivery owner refuses new callbacks. Retire without invoking transport or
        // observer cleanup inline inside the producer's component publication boundary.
        synchronized (publicationGate) { retireLocked(); }
      }
    }

    void runPrefix(Runnable prefix) {
      synchronized (publicationGate) {
        requireHealthy();
        if (retired) return;
      }
      prefix.run();
    }

    void handOff(List<SseEnvelope> replay) {
      try {
        synchronized (publicationGate) {
          requireHealthy();
          if (retired) return;
          callerDelivery = Thread.currentThread();
        }
        for (SseEnvelope frame : replay) {
          synchronized (publicationGate) {
            requireHealthy();
            if (retired) return;
          }
          delegate.accept(frame);
        }
        drainOwned();
      } catch (RuntimeException | Error failure) {
        retireAfter(failure);
        throw failure;
      } finally {
        releaseCaller();
      }
    }

    void releaseCaller() {
      synchronized (publicationGate) {
        if (callerDelivery == Thread.currentThread()) callerDelivery = null;
      }
    }

    private void drainOwned() {
      try {
        while (true) {
          SseEnvelope frame;
          synchronized (publicationGate) {
            if (delivery != null && deliveryOwner.isShutdown()) retireLocked();
            requireHealthy();
            frame = buffered.poll();
            if (retired || frame == null) {
              draining = false;
              delivery = null;
              return;
            }
          }
          delegate.accept(frame);
        }
      } catch (RuntimeException | Error failure) {
        retireAfter(failure);
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
        retireLocked();
      }
      notifyRetirement();
    }

    private void retireAfter(Throwable failure) {
      try {
        retire();
      } catch (RuntimeException | Error cleanupFailure) {
        if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
      }
    }

    private void retireLocked() {
      retired = true;
      listeners.remove(this);
      buffered.clear();
      if (delivery != null) delivery.cancel(true);
      if (interruptCallerDelivery && callerDelivery != null
          && callerDelivery != Thread.currentThread()) callerDelivery.interrupt();
    }

    @Override
    public void unsubscribe() {
      retire();
    }

    @Override
    public void onRetire(Runnable listener) {
      Objects.requireNonNull(listener, "listener");
      synchronized (publicationGate) {
        if (!retired) {
          retirementListeners.add(listener);
          return;
        }
      }
      listener.run();
    }

    private void notifyRetirement() {
      List<Runnable> callbacks;
      synchronized (publicationGate) {
        if (!retired || retirementListeners.isEmpty()) {
          return;
        }
        callbacks = List.copyOf(retirementListeners);
        retirementListeners.clear();
      }
      Throwable failure = null;
      for (Runnable callback : callbacks) {
        try {
          callback.run();
        } catch (RuntimeException | Error callbackFailure) {
          if (failure == null) {
            failure = callbackFailure;
          } else if (failure != callbackFailure) {
            failure.addSuppressed(callbackFailure);
          }
        }
      }
      if (failure instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }

  public interface Subscription {
    void unsubscribe();

    /** Fires once per registration, outside channel locks, including already-retired handles. */
    void onRetire(Runnable listener);
  }
}
