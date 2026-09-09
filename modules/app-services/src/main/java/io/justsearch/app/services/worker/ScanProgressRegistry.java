/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.scan.ScanProgressEvent;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 419 / T4 — bridges the in-process producer (the gRPC progress consumer running on
 * whichever thread fired the scan) and one or more SSE consumers
 * ({@code GET /api/scans/{scanId}/progress} subscribers).
 *
 * <p>A subscriber that arrives while the scan is in flight sees every event from the moment
 * the buffer was created. A subscriber that arrives <em>after</em> the scan completes still
 * sees the full event sequence as long as the buffer is still in memory (default retention
 * window: 30s after completion). Subscribers that arrive past retention see a synthetic
 * terminal event ({@code UNKNOWN_SCAN_OR_RETENTION_EXPIRED}) signaling that the scan has
 * already completed and its progression is no longer available.
 *
 * <p>The registry uses domain-level {@link ScanProgressEvent} records, not proto types,
 * because {@code ui.api} cannot depend on {@code ipc} message types per the architecture
 * rule. The conversion happens at the producer boundary
 * ({@link KnowledgeHttpApiAdapter#scanRoot}).
 *
 * <p>Cancellation: when {@link #register} is called with a {@link CancelToken}, the registry
 * holds the token. {@link #cancel} fires it; the underlying gRPC scan terminates with
 * {@code CLIENT_CANCELLED}. This closes the loop noted in T3 (HTTP SSE close → registry
 * cancel → gRPC cancel propagation).
 *
 * <p>Memory: in-memory only, per-Head-process. A periodic prune (1-min interval) removes
 * completed buffers older than the retention window. Caller must invoke {@link #close} to
 * stop the prune thread on Head shutdown.
 */
public final class ScanProgressRegistry implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(ScanProgressRegistry.class);

  /** Sentinel pushed into per-subscriber queues to signal "no more events". */
  private static final ScanProgressEvent END_OF_STREAM =
      ScanProgressEvent.terminal("", "END_OF_STREAM");

  private final long retentionMs;
  private final Map<String, ScanBuffer> buffers = new HashMap<>();
  private final EngineExecutorRegistry.Registration pruneRegistration;
  private final java.util.concurrent.ScheduledExecutorService pruneExecutor;

  public ScanProgressRegistry(EngineExecutorRegistry processExecutors) {
    this(processExecutors, 30_000L);
  }

  /** Visible for tests so retention can be made tight without long sleeps. */
  ScanProgressRegistry(EngineExecutorRegistry processExecutors, long retentionMs) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    this.retentionMs = retentionMs;
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

  /** Registers a scan with its cancel handle. Idempotent; second call replaces the token. */
  public synchronized void register(String scanId, CancelToken cancelToken) {
    if (scanId == null || scanId.isBlank()) {
      return;
    }
    buffers.computeIfAbsent(scanId, id -> new ScanBuffer()).cancelToken = cancelToken;
  }

  /**
   * Records a progress event. Auto-creates a buffer if {@link #register} was not called first
   * (defensive UPSERT semantics — happens when the worker emits the first event before the
   * Head-side wiring sees the scanId).
   */
  public synchronized void record(String scanId, ScanProgressEvent event) {
    if (scanId == null || scanId.isBlank() || event == null) {
      return;
    }
    ScanBuffer buffer = buffers.computeIfAbsent(scanId, id -> new ScanBuffer());
    buffer.history.add(event);
    for (BlockingQueue<ScanProgressEvent> q : buffer.subscribers) {
      q.offer(event);
    }
    if (event.complete()) {
      buffer.complete = true;
      buffer.completedAtMs = System.currentTimeMillis();
      for (BlockingQueue<ScanProgressEvent> q : buffer.subscribers) {
        q.offer(END_OF_STREAM);
      }
    }
  }

  /**
   * Synthesizes a terminal completion for cases where the scan ended without emitting a final
   * event (e.g., immediate I/O failure caught by the caller). No-op if the buffer is already
   * complete (so calling this defensively after the iterator drain is safe).
   */
  public synchronized void markComplete(String scanId, ScanProgressEvent terminalEvent) {
    if (scanId == null || scanId.isBlank()) {
      return;
    }
    ScanBuffer buffer = buffers.computeIfAbsent(scanId, id -> new ScanBuffer());
    if (buffer.complete) {
      return;
    }
    buffer.complete = true;
    buffer.completedAtMs = System.currentTimeMillis();
    if (terminalEvent != null) {
      buffer.history.add(terminalEvent);
      for (BlockingQueue<ScanProgressEvent> q : buffer.subscribers) {
        q.offer(terminalEvent);
      }
    }
    for (BlockingQueue<ScanProgressEvent> q : buffer.subscribers) {
      q.offer(END_OF_STREAM);
    }
  }

  /**
   * Subscribes to progress events for the given scan. The returned iterable yields all
   * historical events first, then blocks for new events until the scan completes. Returns a
   * synthetic terminal-only event for unknown scans (never registered or pruned past
   * retention).
   */
  public Iterable<ScanProgressEvent> subscribe(String scanId) {
    final List<ScanProgressEvent> historicalSnapshot;
    final BlockingQueue<ScanProgressEvent> queue;
    final boolean alreadyComplete;
    synchronized (this) {
      ScanBuffer buffer = buffers.get(scanId);
      if (buffer == null) {
        return List.of(
            ScanProgressEvent.terminal(
                scanId == null ? "" : scanId, "UNKNOWN_SCAN_OR_RETENTION_EXPIRED"));
      }
      historicalSnapshot = new ArrayList<>(buffer.history);
      alreadyComplete = buffer.complete;
      if (alreadyComplete) {
        queue = null;
      } else {
        queue = new LinkedBlockingQueue<>();
        buffer.subscribers.add(queue);
      }
    }
    return () -> new SubscriberIterator(historicalSnapshot, queue);
  }

  /**
   * Cancels the underlying scan if a cancel token was registered. Returns {@code true} if a
   * token was found and {@code cancel} was invoked. Safe to call on already-complete or
   * unknown scans (no-op).
   */
  public synchronized boolean cancel(String scanId) {
    ScanBuffer buffer = buffers.get(scanId);
    if (buffer == null || buffer.cancelToken == null) {
      return false;
    }
    buffer.cancelToken.cancel("client closed scan progress subscription");
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
    pruneExecutor.shutdownNow();
    pruneRegistration.close();
  }

  // ===================================================================================

  private static final class ScanBuffer {
    final List<ScanProgressEvent> history = new ArrayList<>();
    final List<BlockingQueue<ScanProgressEvent>> subscribers = new CopyOnWriteArrayList<>();
    volatile CancelToken cancelToken;
    volatile boolean complete;
    volatile long completedAtMs;
  }

  private static final class SubscriberIterator implements Iterator<ScanProgressEvent> {
    private final Iterator<ScanProgressEvent> historyIter;
    private final BlockingQueue<ScanProgressEvent> liveQueue;
    private ScanProgressEvent next;
    private boolean exhausted;

    SubscriberIterator(List<ScanProgressEvent> history, BlockingQueue<ScanProgressEvent> liveQueue) {
      this.historyIter = history.iterator();
      this.liveQueue = liveQueue;
      advance();
    }

    @Override
    public boolean hasNext() {
      return !exhausted;
    }

    @Override
    public ScanProgressEvent next() {
      if (exhausted) {
        throw new java.util.NoSuchElementException();
      }
      ScanProgressEvent current = next;
      advance();
      return current;
    }

    private void advance() {
      if (historyIter.hasNext()) {
        next = historyIter.next();
        return;
      }
      if (liveQueue == null) {
        exhausted = true;
        return;
      }
      try {
        ScanProgressEvent event = liveQueue.poll(60, TimeUnit.SECONDS);
        if (event == null || event == END_OF_STREAM) {
          exhausted = true;
        } else {
          next = event;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        exhausted = true;
      }
    }
  }
}
