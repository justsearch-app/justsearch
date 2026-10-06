/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference.chat;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.chat.GenerationActivity;
import io.justsearch.app.api.chat.GenerationLease;
import io.justsearch.app.api.chat.GenerationUnavailableException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one generation admission and in-flight signal of the managed chat server (Spec A §5.2,
 * reconciliation F13). It replaces {@code GenerativeRequestGate}.
 *
 * <p>Every llama-server exchange holds a lease: GENERATE for chat, vision and completions
 * (in-process and through the {@code /v1} proxy), AUX for tokenize, templates and {@code
 * /v1/models}. A caller first takes a {@link Ticket} (counted as {@linkplain #queued() queued}; a
 * GENERATE ticket with a work handle starts the indexing-pacing lifetime at once), then opens the
 * lease once it holds the request lock. A drain closes admission and waits for open leases only,
 * not for the queue: a queued caller's admission then fails fast.
 *
 * <p>{@link #generating()} is true while at least one GENERATE lease is open. AUX leases keep a
 * drain waiting but never count as generating.
 */
public final class GenerationTracker implements GenerationActivity {

  private static final Logger LOG = LoggerFactory.getLogger(GenerationTracker.class);

  /** The lease kind. */
  public enum Kind {
    /** Chat, vision and completions: counts as generating and paces indexing. */
    GENERATE,
    /** Tokenize, templates and model metadata: blocks drains only. */
    AUX
  }

  private record Edge(boolean generating, long epoch) {}

  private final Function<EngineWorkHandle, Runnable> generationLifetime;
  private final Executor edgeDelivery;
  private final LongSupplier nanoTime;

  private final Object monitor = new Object();
  private int closers;
  private boolean recoveryFenced;
  private int generateLeases;
  private int auxLeases;
  private int queuedTickets;
  private volatile boolean generating;
  private volatile long epoch;
  private volatile long idleSinceNanos;

  private final List<EdgeListener> listeners = new CopyOnWriteArrayList<>();
  private final ArrayDeque<Edge> pendingEdges = new ArrayDeque<>();
  private boolean delivering;

  /**
   * @param generationLifetime the indexing-pacing seam a GENERATE ticket with a work handle enters
   *     (EngineRoot's {@code beginGeneration}); its release runs once when the ticket closes
   * @param edgeDelivery where edge listeners run, one edge at a time and in order
   */
  public GenerationTracker(
      Function<EngineWorkHandle, Runnable> generationLifetime, Executor edgeDelivery) {
    this(generationLifetime, edgeDelivery, System::nanoTime);
  }

  GenerationTracker(
      Function<EngineWorkHandle, Runnable> generationLifetime,
      Executor edgeDelivery,
      LongSupplier nanoTime) {
    this.generationLifetime = Objects.requireNonNull(generationLifetime, "generationLifetime");
    this.edgeDelivery = Objects.requireNonNull(edgeDelivery, "edgeDelivery");
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.idleSinceNanos = nanoTime.getAsLong();
  }

  // ==================== GenerationActivity ====================

  @Override
  public boolean generating() {
    return generating;
  }

  @Override
  public int inFlight() {
    synchronized (monitor) {
      return generateLeases;
    }
  }

  @Override
  public int queued() {
    synchronized (monitor) {
      return queuedTickets;
    }
  }

  @Override
  public long idleSinceNanos() {
    return generating ? Long.MIN_VALUE : idleSinceNanos;
  }

  @Override
  public long epoch() {
    return epoch;
  }

  @Override
  public AutoCloseable onEdge(EdgeListener listener) {
    Objects.requireNonNull(listener, "listener");
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  // ==================== Admission ====================

  /**
   * Takes a ticket for one exchange. A GENERATE ticket with a work handle enters the pacing
   * lifetime now, before the caller queues for the request lock.
   */
  public Ticket queue(Kind kind, EngineWorkHandle work) {
    Objects.requireNonNull(kind, "kind");
    Runnable lifetime = null;
    if (kind == Kind.GENERATE && work != null) {
      lifetime = Objects.requireNonNull(generationLifetime.apply(work), "generation release");
    }
    synchronized (monitor) {
      queuedTickets++;
      return new Ticket(kind, lifetime);
    }
  }

  /**
   * Admits one exchange that does not queue for a lock (the proxy, the token endpoints). Closing
   * the lease ends the exchange: it closes the lease and releases the pacing lifetime.
   *
   * @throws GenerationUnavailableException when admission is closed (draining, relaunching or
   *     fenced)
   */
  public GenerationLease admit(Kind kind, EngineWorkHandle work) {
    Ticket ticket = queue(kind, work);
    try {
      ticket.admit();
    } catch (RuntimeException | Error refused) {
      ticket.close();
      throw refused;
    }
    return ticket::close;
  }

  /** @throws GenerationUnavailableException when admission is closed */
  public void requireOpen() {
    synchronized (monitor) {
      requireOpenLocked();
    }
  }

  /** True when a new exchange would be admitted. */
  public boolean admissionOpen() {
    synchronized (monitor) {
      return !recoveryFenced && closers == 0;
    }
  }

  private void requireOpenLocked() {
    if (recoveryFenced || closers > 0) {
      throw new GenerationUnavailableException("Generative component is unavailable");
    }
  }

  /** Boot recovery failure: admission stays closed even when an existing drain hold closes. */
  public void fenceForRecovery() {
    synchronized (monitor) {
      recoveryFenced = true;
      monitor.notifyAll();
    }
  }

  // ==================== Drains ====================

  /**
   * Closes admission and treats open leases as {@code policy} says (Spec A §3.4). The hold reopens
   * admission when closed, unless another hold or the recovery fence still keeps it closed.
   *
   * @throws IllegalStateException when the policy refuses: an AFTER_IDLE drain with {@link
   *     ChatDrainPolicy.Expiry#REFUSE_BUSY} whose bound ran out (admission reopened), an IF_IDLE
   *     drain with a lease open, or a drain after the recovery fence
   * @throws InterruptedException when interrupted while waiting (admission reopened)
   */
  public ChatActuator.DrainHold drain(ChatDrainPolicy policy) throws InterruptedException {
    Objects.requireNonNull(policy, "policy");
    return switch (policy.waiting()) {
      case NOW -> closeNow();
      case IF_IDLE -> {
        ChatActuator.DrainHold hold = closeIfIdle();
        if (hold == null) {
          throw new IllegalStateException(
              "Generation in flight; " + policy.reason() + " waits for the next idle edge");
        }
        yield hold;
      }
      case AFTER_IDLE -> closeAndDrain(policy.bound(), policy.onExpiry());
    };
  }

  /** Closes admission without waiting; open leases end with the server. */
  public ChatActuator.DrainHold closeNow() {
    synchronized (monitor) {
      requireNotFencedLocked();
      closers++;
      return new Hold();
    }
  }

  /**
   * Closes admission only when no lease is open, in one step with admission, so a lease is never
   * open under the returned hold (Spec A T21).
   *
   * @return the hold, or {@code null} when a lease is open (nothing changes)
   */
  public ChatActuator.DrainHold closeIfIdle() {
    synchronized (monitor) {
      requireNotFencedLocked();
      if (generateLeases + auxLeases != 0) return null;
      closers++;
      return new Hold();
    }
  }

  private ChatActuator.DrainHold closeAndDrain(Duration bound, ChatDrainPolicy.Expiry onExpiry)
      throws InterruptedException {
    long remaining = bound == null ? Long.MAX_VALUE : bound.toNanos();
    long deadline = bound == null ? 0 : nanoTime.getAsLong() + remaining;
    synchronized (monitor) {
      requireNotFencedLocked();
      closers++;
      Hold hold = new Hold();
      boolean returned = false;
      try {
        while (generateLeases + auxLeases != 0) {
          if (remaining <= 0) {
            if (onExpiry == ChatDrainPolicy.Expiry.PROCEED_NOW) break;
            throw new IllegalStateException("Generative requests did not drain before replacement");
          }
          if (bound == null) {
            monitor.wait();
          } else {
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            remaining = deadline - nanoTime.getAsLong();
          }
        }
        returned = true;
        return hold;
      } finally {
        if (!returned) hold.releaseLocked();
      }
    }
  }

  private void requireNotFencedLocked() {
    if (recoveryFenced) {
      throw new IllegalStateException("Generative replacement already owns admission");
    }
  }

  private final class Hold implements ChatActuator.DrainHold {
    private boolean released;

    @Override
    public void close() {
      synchronized (monitor) {
        releaseLocked();
      }
    }

    private void releaseLocked() {
      if (released) return;
      released = true;
      closers--;
      monitor.notifyAll();
    }
  }

  // ==================== Tickets and leases ====================

  /**
   * One exchange from queueing to its end. EngineFutures closes it only on actual producer exit,
   * including cancellation; closing it closes an open lease and releases the pacing lifetime, once.
   */
  public final class Ticket implements AutoCloseable {
    private final Kind kind;
    private Runnable lifetime;
    private boolean queued = true;
    private boolean leased;
    private boolean leaseOpen;
    private boolean closed;

    private Ticket(Kind kind, Runnable lifetime) {
      this.kind = kind;
      this.lifetime = lifetime;
    }

    /**
     * Opens this exchange's lease, after the caller holds the request lock. Called once.
     *
     * @throws IllegalStateException when admission is closed; the ticket stops counting as queued
     */
    public GenerationLease admit() {
      boolean edge;
      synchronized (monitor) {
        if (closed) throw new IllegalStateException("generation ticket is closed");
        if (leased) throw new IllegalStateException("generation ticket already admitted");
        if (queued) {
          queued = false;
          queuedTickets--;
        }
        requireOpenLocked();
        leased = true;
        leaseOpen = true;
        edge = openLeaseLocked(kind);
      }
      if (edge) deliverEdges();
      return this::closeLease;
    }

    private void closeLease() {
      boolean edge;
      synchronized (monitor) {
        if (!leaseOpen) return;
        leaseOpen = false;
        edge = closeLeaseLocked(kind);
      }
      if (edge) deliverEdges();
    }

    @Override
    public void close() {
      Runnable release;
      synchronized (monitor) {
        if (closed) return;
        closed = true;
        if (queued) {
          queued = false;
          queuedTickets--;
        }
        release = lifetime;
        lifetime = null;
      }
      try {
        closeLease();
      } finally {
        if (release != null) release.run();
      }
    }
  }

  /** @return true when this opened the idle-to-generating edge */
  private boolean openLeaseLocked(Kind kind) {
    if (kind == Kind.AUX) {
      auxLeases++;
      return false;
    }
    if (generateLeases++ != 0) return false;
    long next = epoch + 1;
    epoch = next;
    generating = true;
    pendingEdges.add(new Edge(true, next));
    return true;
  }

  /** @return true when this closed the generating-to-idle edge */
  private boolean closeLeaseLocked(Kind kind) {
    boolean edge = false;
    if (kind == Kind.AUX) {
      auxLeases--;
    } else if (--generateLeases == 0) {
      idleSinceNanos = nanoTime.getAsLong();
      generating = false;
      pendingEdges.add(new Edge(false, epoch));
      edge = true;
    }
    monitor.notifyAll();
    return edge;
  }

  // ==================== Edge delivery ====================

  private void deliverEdges() {
    synchronized (monitor) {
      if (delivering || pendingEdges.isEmpty()) return;
      delivering = true;
    }
    try {
      edgeDelivery.execute(this::pumpEdges);
    } catch (RejectedExecutionException rejected) {
      synchronized (monitor) {
        delivering = false;
        pendingEdges.clear();
      }
      LOG.warn("Generation edge delivery refused; listeners miss these edges", rejected);
    }
  }

  private void pumpEdges() {
    for (;;) {
      Edge edge;
      synchronized (monitor) {
        edge = pendingEdges.poll();
        if (edge == null) {
          delivering = false;
          return;
        }
      }
      for (EdgeListener listener : listeners) {
        try {
          listener.onEdge(edge.generating(), edge.epoch());
        } catch (RuntimeException failure) {
          LOG.warn("Generation edge listener failed", failure);
        }
      }
    }
  }
}
