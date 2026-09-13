/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.scan.ScanProgressEvent;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded in-process scan observation and replay. Subscribers read one shared ring per scan.
 * A slow subscriber overtaken by the ring receives UNKNOWN_SCAN_OR_RETENTION_EXPIRED.
 * New subscribers replay the retained suffix; completed buffers expire after 30 seconds or
 * oldest-first when a new scan needs capacity. Callers close their subscription on disconnect.
 */
public final class ScanProgressRegistry implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScanProgressRegistry.class);

  private final long retentionMs;
  private final long subscriberIdleMs;
  private final int eventLimit;
  private final int bufferLimit;
  private final int subscriberLimit;
  private final int retryAfterSeconds;
  private final Map<String, ScanBuffer> buffers = new LinkedHashMap<>();
  private int subscribers;
  private boolean closed;
  private final EngineExecutorRegistry.Registration pruneRegistration;
  private final java.util.concurrent.ScheduledExecutorService pruneExecutor;

  public ScanProgressRegistry(EngineExecutorRegistry processExecutors) {
    this(processExecutors, 30_000L);
  }

  /** Visible for tests so retention can be made tight without long sleeps. */
  ScanProgressRegistry(EngineExecutorRegistry processExecutors, long retentionMs) {
    this(processExecutors, retentionMs, 60_000L);
  }

  /** Test timing seam; production keeps the existing sixty-second subscriber idle window. */
  ScanProgressRegistry(EngineExecutorRegistry processExecutors, long retentionMs, long subscriberIdleMs) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    this.retentionMs = retentionMs;
    if (subscriberIdleMs <= 0) throw new IllegalArgumentException("subscriberIdleMs must be positive");
    this.subscriberIdleMs = subscriberIdleMs;
    this.eventLimit = Math.max(1, processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND).maxQueue());
    this.bufferLimit = processExecutors.maxConcurrentWork();
    this.subscriberLimit = Math.max(1, processExecutors.limits(EngineExecutorSpec.Kind.FOREGROUND).maxQueue());
    this.retryAfterSeconds = processExecutors.retryAfterSeconds();
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.scan-progress-prune",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      this.pruneRegistration = registration;
      this.pruneExecutor =
          registration.openScheduled(
              r -> {
                Thread t = new Thread(r, "scan-progress-prune");
                t.setDaemon(true);
                return t;
              });
      this.pruneExecutor.scheduleAtFixedRate(this::pruneStale, 60, 60, TimeUnit.SECONDS);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  /** Registers the cancellation owner before publishing the first observation. */
  public synchronized void register(String scanId, CancelToken cancelToken) {
    if (scanId == null || scanId.isBlank()) return;
    bufferFor(scanId).cancelToken = cancelToken;
  }

  private ScanBuffer bufferFor(String scanId) {
    if (closed) throw refusal(EngineExecutorRejectedException.Reason.CLOSED);
    ScanBuffer existing = buffers.get(scanId);
    if (existing != null) return existing;
    pruneStale();
    if (buffers.size() >= bufferLimit) {
      var entries = buffers.entrySet().iterator();
      while (entries.hasNext()) {
        if (entries.next().getValue().complete) {
          entries.remove();
          break;
        }
      }
    }
    if (buffers.size() >= bufferLimit) throw refusal(EngineExecutorRejectedException.Reason.QUEUE_LIMIT);
    ScanBuffer created = new ScanBuffer();
    buffers.put(scanId, created);
    return created;
  }

  private EngineExecutorRejectedException refusal(EngineExecutorRejectedException.Reason reason) {
    return new EngineExecutorRejectedException(reason, "head.scan-progress", retryAfterSeconds);
  }

  /** Records one observation, retaining only the policy-sized suffix. */
  public synchronized void record(String scanId, ScanProgressEvent event) {
    if (scanId == null || scanId.isBlank() || event == null) return;
    ScanBuffer buffer = bufferFor(scanId);
    if (buffer.complete) return;
    if (buffer.history.size() == eventLimit) {
      buffer.history.removeFirst();
      buffer.firstSequence++;
    }
    buffer.history.addLast(event);
    if (event.complete()) {
      buffer.complete = true;
      buffer.completedAtMs = System.currentTimeMillis();
      buffer.cancelToken = null;
    }
    notifyAll();
  }

  /** Completes an existing observation after a producer failure; never allocates on cleanup. */
  public synchronized void markComplete(String scanId, ScanProgressEvent terminalEvent) {
    ScanBuffer buffer = buffers.get(scanId);
    if (buffer == null || buffer.complete || closed) return;
    if (terminalEvent != null) record(scanId, terminalEvent);
    buffer.complete = true;
    buffer.completedAtMs = System.currentTimeMillis();
    buffer.cancelToken = null;
    notifyAll();
  }

  /** Opens one bounded cursor. The caller must close it if iteration stops early. */
  public synchronized Subscription subscribe(String scanId) {
    if (closed) throw refusal(EngineExecutorRejectedException.Reason.CLOSED);
    if (subscribers >= subscriberLimit) throw refusal(EngineExecutorRejectedException.Reason.QUEUE_LIMIT);
    subscribers++;
    ScanBuffer buffer = buffers.get(scanId);
    return new Subscription(scanId == null ? "" : scanId, buffer);
  }

  /** Cancels an active scan without invoking arbitrary cancellation callbacks under our monitor. */
  public boolean cancel(String scanId) {
    final CancelToken token;
    synchronized (this) {
      ScanBuffer buffer = buffers.get(scanId);
      token = buffer == null ? null : buffer.cancelToken;
    }
    if (token == null) return false;
    token.cancel("client closed scan progress subscription");
    return true;
  }

  /** Returns the current buffer count. Test-only — see {@code UnreferencedCodeTest} exemption. */
  @SuppressWarnings("unused") // ScanProgressRegistryTest only — exempted in UnreferencedCodeTest.
  synchronized int activeBufferCount() {
    return buffers.size();
  }

  private synchronized void pruneStale() {
    long cutoff = System.currentTimeMillis() - retentionMs;
    int beforeSize = buffers.size();
    buffers
        .entrySet()
        .removeIf(e -> e.getValue().complete && e.getValue().completedAtMs < cutoff);
    int pruned = beforeSize - buffers.size();
    if (pruned > 0) {
      log.debug("ScanProgressRegistry pruned {} completed buffers (retention={}ms)", pruned, retentionMs);
    }
  }

  /** Test-only — runs the prune sweep synchronously (see {@code UnreferencedCodeTest} exemption). */
  @SuppressWarnings("unused") // ScanProgressRegistryTest only — exempted in UnreferencedCodeTest.
  void pruneNow() {
    pruneStale();
  }

  @Override
  public void close() {
    synchronized (this) {
      closed = true;
      buffers.clear();
      notifyAll();
    }
    pruneExecutor.shutdownNow();
    pruneRegistration.close();
  }

  private static final class ScanBuffer {
    final ArrayDeque<ScanProgressEvent> history = new ArrayDeque<>();
    long firstSequence;
    CancelToken cancelToken;
    boolean complete;
    long completedAtMs;
  }

  /** One cursor into shared replay storage; no per-subscriber event queue or history copy. */
  public final class Subscription implements Iterable<ScanProgressEvent>, Iterator<ScanProgressEvent>, AutoCloseable {
    private final String scanId;
    private ScanBuffer buffer;
    private long sequence;
    private ScanProgressEvent next;
    private boolean released;
    private boolean iterated;

    private Subscription(String scanId, ScanBuffer buffer) {
      this.scanId = scanId;
      this.buffer = buffer;
      this.sequence = buffer == null ? 0 : buffer.firstSequence;
    }

    @Override
    public Iterator<ScanProgressEvent> iterator() {
      if (iterated) throw new IllegalStateException("Scan subscription is single-use");
      iterated = true;
      return this;
    }

    @Override
    public boolean hasNext() {
      synchronized (ScanProgressRegistry.this) {
        if (next != null) return true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(subscriberIdleMs);
        while (!released) {
          if (closed || buffer == null || sequence < buffer.firstSequence) {
            next = ScanProgressEvent.terminal(scanId, "UNKNOWN_SCAN_OR_RETENTION_EXPIRED");
            buffer = null;
            return true;
          }
          long offset = sequence - buffer.firstSequence;
          if (offset < buffer.history.size()) {
            var events = buffer.history.iterator();
            for (long i = 0; i < offset; i++) events.next();
            next = events.next();
            sequence++;
            if (next.complete()) buffer = null;
            return true;
          }
          if (buffer.complete) {
            release();
            return false;
          }
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            release();
            return false;
          }
          try {
            TimeUnit.NANOSECONDS.timedWait(ScanProgressRegistry.this, remaining);
          } catch (InterruptedException interrupted) {
            release();
            Thread.currentThread().interrupt();
          }
        }
        return false;
      }
    }

    @Override
    public ScanProgressEvent next() {
      synchronized (ScanProgressRegistry.this) {
        if (!hasNext()) throw new java.util.NoSuchElementException();
        ScanProgressEvent result = next;
        next = null;
        if (result.complete()) release();
        return result;
      }
    }

    private void release() {
      if (!released) {
        released = true;
        buffer = null;
        subscribers--;
      }
    }

    @Override
    public void close() {
      synchronized (ScanProgressRegistry.this) {
        next = null;
        release();
        ScanProgressRegistry.this.notifyAll();
      }
    }
  }
}
