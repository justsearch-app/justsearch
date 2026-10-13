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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p>Publishing never interrupts the publishing thread. Only the delivery threads this channel runs
 * on its owner-supplied executor and a subscriber's own attaching thread are ever interrupted, and
 * only to release that subscriber's observer code when its registration ends.
 *
 * <p>The channel never creates an executor (C1-5/C1-16): asynchronous and interruptible delivery
 * run on the executor its constructing owner supplies (in production a registration of the executor
 * registry), which this channel shuts down, with the owner's release hook, when it closes.
 */
public final class SseStreamChannel implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(SseStreamChannel.class);

  /**
   * How long a synchronous {@link #publish} on an owned-delivery channel waits for its frame to
   * reach the observers it handed it to. Healthy observers take microseconds; a blocked one is
   * left to its owned drainer and is not waited for again until it has delivered its whole backlog.
   */
  static final long SYNCHRONOUS_DELIVERY_BOUND_NANOS = TimeUnit.SECONDS.toNanos(1);

  private final StreamId streamId;
  private final StreamSequenceTracker sequence;
  private final FrameHistoryRingBuffer history;
  private final Set<HandoffListener> listeners = new LinkedHashSet<>();
  private final Clock clock;
  private final Object publicationGate = new Object();
  private final UUID incarnation = UUID.randomUUID();
  private final ExecutorService deliveryOwner;
  private final Runnable releaseDeliveryOwner;
  private boolean ownedDelivery;
  private int deliveryWaiters;
  private boolean closed;

  public SseStreamChannel(StreamId streamId) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC());
  }

  /** The supplied executor owns asynchronous delivery and is shut down with this channel. */
  public SseStreamChannel(StreamId streamId, ExecutorService deliveryOwner) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC(),
        Objects.requireNonNull(deliveryOwner, "deliveryOwner"), null);
  }

  /** Also releases the owner's registration when this channel's lifetime ends. */
  public SseStreamChannel(StreamId streamId, ExecutorService deliveryOwner, Runnable releaseOwner) {
    this(streamId, new StreamSequenceTracker(), new FrameHistoryRingBuffer(), Clock.systemUTC(),
        Objects.requireNonNull(deliveryOwner, "deliveryOwner"),
        Objects.requireNonNull(releaseOwner, "releaseOwner"));
  }

  /**
   * Observer code runs only on threads this channel owns, so a retired observer is released by
   * interrupting its drainer without touching any publisher. {@link #publish} hands each frame to
   * the per-listener drainers and waits at most {@link #SYNCHRONOUS_DELIVERY_BOUND_NANOS} for it to
   * be delivered; a publisher already interrupted, or interrupted while waiting, returns at once
   * with its interrupt intact. Other streams keep delivering on the caller's thread.
   *
   * @throws IllegalStateException when this channel was built without an owner-supplied delivery
   *     executor: the drainers run only on threads an executor owner created
   */
  public SseStreamChannel withInterruptibleDelivery() {
    requireDeliveryOwner("Interruptible delivery");
    synchronized (publicationGate) { ownedDelivery = true; }
    return this;
  }

  public SseStreamChannel(
      StreamId streamId, StreamSequenceTracker sequence, FrameHistoryRingBuffer history, Clock clock) {
    this(streamId, sequence, history, clock, null, null);
  }

  private SseStreamChannel(StreamId streamId, StreamSequenceTracker sequence,
      FrameHistoryRingBuffer history, Clock clock, ExecutorService deliveryOwner,
      Runnable releaseOwner) {
    this.streamId = Objects.requireNonNull(streamId, "streamId");
    this.sequence = Objects.requireNonNull(sequence, "sequence");
    this.history = Objects.requireNonNull(history, "history");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.deliveryOwner = deliveryOwner;
    this.releaseDeliveryOwner = releaseOwner;
  }

  private void requireDeliveryOwner(String use) {
    if (deliveryOwner == null) {
      throw new IllegalStateException(use + " on " + streamId
          + " needs a delivery executor supplied by its owner; the channel never creates one");
    }
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
    boolean owned;
    synchronized (publicationGate) { owned = ownedDelivery; }
    if (owned) {
      publishOwned(frameKind, payload);
      return;
    }
    List<HandoffListener> targets = enqueuePublication(frameKind, payload).targets();
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
   *
   * @throws IllegalStateException when this channel was built without an owner-supplied delivery
   *     executor; nothing is sequenced or retained in that case
   */
  public void publishAsync(SseFrameKind frameKind, Object payload) {
    Objects.requireNonNull(frameKind, "frameKind");
    requireDeliveryOwner("Asynchronous publication");
    for (var listener : enqueuePublication(frameKind, payload).targets()) {
      listener.drainAsync();
    }
  }

  /**
   * Owned-delivery publish: each listener's serial drainer runs on the channel's executor, so
   * per-listener order is sequence order and a blocked observer delays only itself. The publisher
   * waits, bounded, for the observers it handed the frame to, so in-process observers still see
   * the frame before {@code publish} returns in the normal case.
   */
  private void publishOwned(SseFrameKind frameKind, Object payload) {
    Publication publication = enqueuePublication(frameKind, payload);
    for (var listener : publication.targets()) {
      listener.drainAsync();
    }
    awaitDelivery(publication);
  }

  private void awaitDelivery(Publication publication) {
    Thread self = Thread.currentThread();
    // An interrupted publisher is being asked to stop: never hold it, never clear its flag.
    if (publication.targets().isEmpty() || self.isInterrupted()) return;
    long deadline = System.nanoTime() + SYNCHRONOUS_DELIVERY_BOUND_NANOS;
    long seq = publication.seq();
    Error fatal = null;
    synchronized (publicationGate) {
      // A publish from observer code running on this channel's own delivery must not wait for
      // the drainer that is running it.
      for (var listener : listeners) {
        if (listener.drainer == self || listener.callerDelivery == self) return;
      }
      deliveryWaiters++;
      try {
        while (true) {
          boolean pending = false;
          for (var listener : publication.targets()) pending |= listener.awaitsLocked(seq);
          if (!pending) break;
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            for (var listener : publication.targets()) {
              if (listener.awaitsLocked(seq)) listener.stalled = true;
            }
            break;
          }
          try {
            TimeUnit.NANOSECONDS.timedWait(publicationGate, remaining);
          } catch (InterruptedException ownInterrupt) {
            // Only the publisher's own owner interrupts it: keep that request and stop waiting.
            self.interrupt();
            break;
          }
        }
      } finally {
        deliveryWaiters--;
      }
      for (var listener : publication.targets()) {
        if (listener.fatal != null && listener.fatalSeq == seq) {
          if (fatal == null) fatal = listener.fatal;
          else if (fatal != listener.fatal) fatal.addSuppressed(listener.fatal);
        }
      }
    }
    if (fatal != null) throw fatal;
  }

  /** Called only under publicationGate. */
  private void wakeDeliveryWaitersLocked() {
    if (deliveryWaiters > 0) publicationGate.notifyAll();
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
        listener.interruptCallerLocked();
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

  private record Publication(List<HandoffListener> targets, long seq) {}

  private Publication enqueuePublication(SseFrameKind frameKind, Object payload) {
    synchronized (publicationGate) {
      if (closed) return new Publication(List.of(), 0);
      SseEnvelope envelope = nextEnvelope(frameKind, payload);
      if (frameKind == SseFrameKind.UPDATE) {
        history.append(envelope);
      }
      List<HandoffListener> targets = List.copyOf(listeners);
      for (HandoffListener listener : targets) {
        listener.enqueue(envelope);
      }
      return new Publication(targets, envelope.seq());
    }
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
    boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
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
      handoff.restoreCallerInterrupt(interruptedOnEntry);
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
    boolean interruptedOnEntry = Thread.currentThread().isInterrupted();
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
      handoff.restoreCallerInterrupt(interruptedOnEntry);
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
    /** The subscriber's own attaching thread while it runs the prefix, replay and first drain. */
    private Thread callerDelivery;
    private boolean callerInterrupted;
    /** The owned thread currently running this listener's serial drain. */
    private Thread drainer;
    private long deliveredSeq;
    /**
     * A bounded wait gave up on this listener; publishers skip it until it has caught up with its
     * whole backlog, not merely finished the frame it was blocked in.
     */
    private boolean stalled;
    private Error fatal;
    private long fatalSeq;

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

    /** Caller-thread delivery for streams without owned delivery; the caller is never recorded. */
    void drainIfAvailable() {
      try {
        synchronized (publicationGate) {
          if (retired || draining) {
            return;
          }
          draining = true;
        }
        drainOwned();
      } finally {
        notifyRetirement();
      }
    }

    /** Called only under publicationGate: still owes frame {@code seq} to a waiting publisher. */
    boolean awaitsLocked(long seq) {
      return !retired && !stalled && callerDelivery == null && deliveredSeq < seq;
    }

    void drainAsync() {
      FutureTask<Void> task;
      ExecutorService owner;
      synchronized (publicationGate) {
        if (retired && retirementListeners.isEmpty() || !retired && draining) return;
        // Only publishAsync and owned publish reach here; both require an owner-supplied executor.
        owner = deliveryOwner;
        boolean deliver = !retired;
        if (deliver) draining = true;
        task = new FutureTask<>(() -> {
          try {
            if (deliver) {
              synchronized (publicationGate) { drainer = Thread.currentThread(); }
              drainOwned();
            }
          } catch (Error failure) {
            // Nobody reads this task's outcome, and a publisher that stopped waiting no longer
            // collects the Error: report it to the channel's owner, never to a publisher.
            LOG.error("An observer of {} failed during owned delivery and was retired", streamId,
                failure);
            throw failure;
          } finally {
            if (deliver) {
              synchronized (publicationGate) {
                if (drainer == Thread.currentThread()) drainer = null;
              }
            }
            notifyRetirement();
          }
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

    /**
     * After {@link #releaseCaller} nothing interrupts this thread on the listener's behalf. An
     * interrupt this listener raised to release its own observer code ends with the attachment.
     */
    void restoreCallerInterrupt(boolean interruptedOnEntry) {
      boolean raised;
      synchronized (publicationGate) {
        raised = callerInterrupted;
        callerInterrupted = false;
      }
      if (raised && !interruptedOnEntry) Thread.interrupted();
    }

    /** Called only under publicationGate. Interrupts only the subscriber's own attaching thread. */
    void interruptCallerLocked() {
      if (callerDelivery != null && callerDelivery != Thread.currentThread()) {
        callerInterrupted = true;
        callerDelivery.interrupt();
      }
    }

    private void drainOwned() {
      SseEnvelope frame = null;
      try {
        while (true) {
          synchronized (publicationGate) {
            if (frame != null) {
              deliveredSeq = frame.seq();
              // A stall ends only once the backlog has drained (one frame is not catching up).
              if (buffered.isEmpty()) stalled = false;
              wakeDeliveryWaitersLocked();
            }
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
        if (failure instanceof Error error && frame != null) {
          synchronized (publicationGate) {
            fatal = error;
            fatalSeq = frame.seq();
          }
        }
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
      if (ownedDelivery) interruptCallerLocked();
      wakeDeliveryWaitersLocked();
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
