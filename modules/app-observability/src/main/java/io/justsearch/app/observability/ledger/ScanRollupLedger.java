/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.ledger;

import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.io.Closeable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 812 D2 — the scan-rollup aggregator: turns a burst of per-document indexing outcomes
 * into ONE durable audit record per directory scan.
 *
 * <p>Per-document {@link ActionEvent.Index} rows stay exactly what they were: ring-only ephemera,
 * sacrificed first when the bounded log fills. What a scan DID — "indexed 5,184 documents in
 * scifact over 6m12s, 3 failed" — is the audit fact, and it is recorded once as an
 * {@code OPERATION}-kind {@link ActionEvent.ScanRollup}, the tier whose retention is a guarantee
 * rather than a side effect of ring pressure.
 *
 * <p><b>The counts come from the real terminal states.</b> This aggregator counts
 * {@code DONE}/{@code FAILED} {@link ActionEvent.Index} events as the indexing-jobs bridge observes
 * them (the worker's terminal job transitions), NOT the scan's enqueue-time {@code filesAdmitted}.
 * The admitted count is used only to know when to STOP waiting; it is also reported so a partial
 * scan is legible ("N of M"). An audit row must state what happened, not what was attempted.
 *
 * <p><b>Identity is the capture-side key, not a render heuristic.</b> Each job row carries the
 * {@code scanId} of the scan that enqueued it (worker {@code jobs.scan_id} → {@code IndexingJobView}
 * → {@code ActionEvent.Index}), so grouping is exact even when two scans interleave. Rows without a
 * scanId (single-file ingest, watcher-driven enqueues, pre-812 rows) are simply not aggregated —
 * the FE's adjacency collapse still covers them.
 *
 * <p><b>Lifecycle of one scan</b> (the Head observes it through two channels):
 * <ol>
 *   <li>{@link #scanStarted} — the first {@code ScanRootProgress} frame reveals the worker's scan
 *       id; a {@code STARTED} rollup is emitted so a scan that never finishes still left a trace.
 *   <li>{@link #scanEnumerated} — the walk finished; now the expected document count is known.
 *   <li>terminal {@code index} events arrive (before AND after enumeration ends — indexing runs
 *       concurrently with the walk).
 *   <li>the {@code FINISHED} rollup is emitted when every admitted document reached a terminal
 *       state ({@code COMPLETED}), or when the scan goes quiet for {@link #quiesceMs}
 *       ({@code PARTIAL} — cancellation, worker restart, or a re-enqueue that stole a row).
 * </ol>
 */
public final class ScanRollupLedger implements Closeable {

  private static final Logger log = LoggerFactory.getLogger(ScanRollupLedger.class);

  /** Enumeration ended without a usable admitted count (RPC failure / cancellation). */
  public static final int ADMITTED_UNKNOWN = -1;

  /** How long a scan may go without a terminal outcome before it is closed out as PARTIAL. */
  private static final long DEFAULT_QUIESCE_MS = 120_000L;

  private final ActionLedgerChangeRegistry registry;
  private final Executor emitExecutor;
  private final EngineExecutorRegistry.Registration sweeperRegistration;
  private final ScheduledExecutorService sweeper;
  private final ScheduledFuture<?> sweeperTask;
  private final LongSupplier clock;
  private final long quiesceMs;

  /** Open scans by scanId. Guarded by {@code this}. */
  private final Map<String, OpenScan> open = new HashMap<>();

  private volatile boolean closed;

  /** Production wiring: a registry-owned daemon sweeper thread + the real clock. */
  public ScanRollupLedger(
      EngineExecutorRegistry processExecutors, ActionLedgerChangeRegistry registry) {
    this(processExecutors, registry, openSweeper(processExecutors),
        System::currentTimeMillis, DEFAULT_QUIESCE_MS);
  }

  private ScanRollupLedger(
      EngineExecutorRegistry processExecutors,
      ActionLedgerChangeRegistry registry,
      SchedulerResources resources,
      LongSupplier clock,
      long quiesceMs) {
    this(
        processExecutors,
        registry,
        null,
        resources.scheduler(),
        resources.registration(),
        clock,
        quiesceMs);
  }

  /**
   * Test/composition seam. The registry is still required for construction; an explicit sweeper
   * remains a borrowed test scheduler and is not registered or closed by this ledger. A null
   * sweeper disables periodic quiescence so the test can drive {@link #sweep()} itself.
   */
  ScanRollupLedger(
      EngineExecutorRegistry processExecutors,
      ActionLedgerChangeRegistry registry,
      Executor emitExecutor,
      ScheduledExecutorService sweeper,
      LongSupplier clock,
      long quiesceMs) {
    this(processExecutors, registry, emitExecutor, sweeper, null, clock, quiesceMs);
  }

  private ScanRollupLedger(
      EngineExecutorRegistry processExecutors,
      ActionLedgerChangeRegistry registry,
      Executor emitExecutor,
      ScheduledExecutorService sweeper,
      EngineExecutorRegistry.Registration sweeperRegistration,
      LongSupplier clock,
      long quiesceMs) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.sweeperRegistration = sweeperRegistration;
    this.sweeper = sweeper;
    this.emitExecutor = emitExecutor != null ? emitExecutor : (sweeper != null ? sweeper : Runnable::run);
    this.clock = Objects.requireNonNull(clock, "clock");
    this.quiesceMs = quiesceMs;
    try {
      registry.addEventListener(this::onEvent);
      if (sweeper != null) {
        long period = Math.max(1_000L, quiesceMs / 2);
        this.sweeperTask =
            sweeper.scheduleWithFixedDelay(
                this::sweepQuietly, period, period, TimeUnit.MILLISECONDS);
      } else {
        this.sweeperTask = null;
      }
    } catch (RuntimeException | Error failure) {
      if (sweeperRegistration != null) {
        try {
          sweeperRegistration.close();
        } catch (RuntimeException | Error cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private static SchedulerResources openSweeper(EngineExecutorRegistry processExecutors) {
    Objects.requireNonNull(processExecutors, "processExecutors");
    EngineExecutorRegistry.Limits background =
        processExecutors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    EngineExecutorRegistry.Registration registration =
        processExecutors.register(
            new EngineExecutorSpec(
                "head.scan-rollup-ledger",
                EngineExecutorSpec.Kind.BACKGROUND,
                EngineExecutorSpec.Mode.SCHEDULED,
                1,
                background.maxQueue(),
                1));
    try {
      ScheduledExecutorService scheduler =
          registration.openScheduled(
              runnable -> {
                Thread thread = new Thread(runnable, "scan-rollup-ledger");
                thread.setDaemon(true);
                return thread;
              });
      return new SchedulerResources(registration, scheduler);
    } catch (RuntimeException | Error failure) {
      try {
        registration.close();
      } catch (RuntimeException | Error cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private record SchedulerResources(
      EngineExecutorRegistry.Registration registration, ScheduledExecutorService scheduler) {}

  /**
   * The Head learned the worker's scan id (first progress frame). Records the scan as open and
   * emits its {@code STARTED} rollup. Idempotent per scanId — repeated frames do not re-open it.
   */
  public void scanStarted(String scanId, String collection, String root) {
    if (closed || scanId == null || scanId.isBlank()) {
      return;
    }
    OpenScan scan;
    synchronized (this) {
      if (open.containsKey(scanId)) {
        return;
      }
      scan = new OpenScan(scanId, collection, root, clock.getAsLong());
      open.put(scanId, scan);
    }
    emit(
        ActionLedgerProjection.projectScanRollup(
            scanId,
            scan.collection,
            scan.root,
            "STARTED",
            0,
            0,
            ADMITTED_UNKNOWN,
            0L,
            Instant.ofEpochMilli(scan.startedAtMs)));
  }

  /**
   * The worker's walk finished: {@code admitted} documents were queued (or
   * {@link #ADMITTED_UNKNOWN} when the RPC failed before a usable count). A scan that admitted
   * nothing is closed out immediately — there is nothing to wait for.
   */
  public void scanEnumerated(String scanId, int admitted) {
    if (closed || scanId == null || scanId.isBlank()) {
      return;
    }
    OpenScan finished = null;
    synchronized (this) {
      OpenScan scan = open.get(scanId);
      if (scan == null) {
        return;
      }
      scan.admitted = admitted;
      scan.enumerated = true;
      scan.lastActivityMs = clock.getAsLong();
      if (isComplete(scan)) {
        finished = takeInternal(scanId);
      }
    }
    if (finished != null) {
      emitFinished(finished, "COMPLETED");
    }
  }

  /** Terminal indexing outcomes as the bridge observes them — the ONLY source of the counts. */
  private void onEvent(ActionEvent event) {
    if (closed || !(event instanceof ActionEvent.Index idx) || idx.scanId().isEmpty()) {
      return;
    }
    OpenScan finished = null;
    synchronized (this) {
      OpenScan scan = open.get(idx.scanId());
      if (scan == null) {
        return;
      }
      // Defense in depth behind the registry's ring dedup: count each DOCUMENT's terminal outcome
      // once. The ring keys on event id, so it catches a re-delivered event but not a second
      // terminal for the same document (a retry re-enqueued under the same scan mints a new id).
      // Either would inflate docsDone/docsFailed and could trip `isComplete` before every admitted
      // document actually finished — an audit row must state what happened.
      if (!scan.countFirstTerminalFor(idx.pathHash())) {
        return;
      }
      // Tempdoc 885 item 21b: RETRY_EXHAUSTED counts as failed. Leaving it in the else arm would
      // have made a scan whose files gave up after a week report them as successfully indexed.
      if (io.justsearch.app.api.indexing.IndexingJobView.STATE_FAILED.equalsIgnoreCase(idx.state())
          || io.justsearch.app.api.indexing.IndexingJobView.STATE_RETRY_EXHAUSTED.equalsIgnoreCase(
              idx.state())) {
        scan.docsFailed++;
      } else {
        scan.docsDone++;
      }
      scan.lastActivityMs = clock.getAsLong();
      if (isComplete(scan)) {
        finished = takeInternal(idx.scanId());
      }
    }
    if (finished != null) {
      emitFinished(finished, "COMPLETED");
    }
  }

  /**
   * Close out every scan that has gone quiet past the quiescence window. Package-visible so a test
   * drives it deterministically instead of waiting on the sweeper thread.
   */
  synchronized List<String> sweep() {
    long now = clock.getAsLong();
    List<OpenScan> due = new ArrayList<>();
    for (OpenScan scan : open.values()) {
      if (scan.enumerated && now - scan.lastActivityMs >= quiesceMs) {
        due.add(scan);
      }
    }
    List<String> ids = new ArrayList<>(due.size());
    for (OpenScan scan : due) {
      open.remove(scan.scanId);
      ids.add(scan.scanId);
      // A quiesced scan whose counts DID reach the admitted total is still COMPLETED — the
      // completion just arrived alongside the sweep rather than on a terminal event.
      emitFinished(scan, isComplete(scan) ? "COMPLETED" : "PARTIAL");
    }
    return ids;
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      log.warn("scan-rollup sweep failed; continuing", e);
    }
  }

  /**
   * Shutdown mid-scan is a real outcome, not an absence of one. Every open scan already has a
   * DURABLE {@code STARTED} row in the audit journal, so clearing the map silently left that row
   * dangling forever — the journal would claim a scan that never ended. Each open scan is therefore
   * closed out with the sweeper's own {@code PARTIAL} shape (counts so far), which is exactly what
   * quiescence would have emitted had the process stayed up.
   */
  @Override
  public void close() {
    closed = true;
    List<OpenScan> stranded;
    synchronized (this) {
      stranded = new ArrayList<>(open.values());
      open.clear();
    }
    // Published on the CALLING thread, not via emitExecutor: in production the emit executor IS
    // the sweeper being shut down here, and a daemon thread's queued task is not guaranteed to run
    // before the JVM exits — a shutdown row that races shutdown is no row at all. Re-entrancy, the
    // reason the normal path defers, cannot bite: `closed` is already true, so the listener
    // callback returns immediately.
    for (OpenScan scan : stranded) {
      try {
        registry.broadcastActionEvent(finishedRow(scan, "PARTIAL"));
      } catch (RuntimeException e) {
        log.warn("scan-rollup shutdown row for {} was not recorded", scan.scanId, e);
      }
    }
    if (sweeper != null) {
      if (sweeperTask != null) {
        sweeperTask.cancel(false);
      }
      sweeper.shutdownNow();
    }
    if (sweeperRegistration != null) {
      sweeperRegistration.close();
    }
  }

  /** Callers hold {@code this}. */
  private static boolean isComplete(OpenScan scan) {
    return scan.enumerated
        && scan.admitted != ADMITTED_UNKNOWN
        && scan.docsDone + scan.docsFailed >= scan.admitted;
  }

  /** Callers hold {@code this}. */
  private OpenScan takeInternal(String scanId) {
    return open.remove(scanId);
  }

  private void emitFinished(OpenScan scan, String outcome) {
    emit(finishedRow(scan, outcome));
  }

  private ActionEvent finishedRow(OpenScan scan, String outcome) {
    return ActionLedgerProjection.projectScanRollup(
        scan.scanId,
        scan.collection,
        scan.root,
        outcome,
        scan.docsDone,
        scan.docsFailed,
        scan.admitted,
        Math.max(0L, clock.getAsLong() - scan.startedAtMs),
        Instant.ofEpochMilli(clock.getAsLong()));
  }

  private void emit(ActionEvent event) {
    try {
      emitExecutor.execute(() -> registry.broadcastActionEvent(event));
    } catch (RuntimeException e) {
      log.warn("scan-rollup emit rejected ({}); the rollup row is lost", e.toString());
    }
  }

  /** Mutable per-scan accumulator. All fields guarded by the ledger's monitor. */
  private static final class OpenScan {
    private final String scanId;
    private final String collection;
    private final String root;
    private final long startedAtMs;
    private long lastActivityMs;
    private boolean enumerated;
    private int admitted = ADMITTED_UNKNOWN;
    private int docsDone;
    private int docsFailed;

    /**
     * Documents whose terminal outcome has already been counted, keyed by the event's
     * {@code pathHash}. Bounded by the scan's own admitted total: each entry corresponds to one
     * counted document, and the scan is closed out the moment the counts reach {@code admitted},
     * so this set cannot outgrow the population the scan enumerated. The explicit size guard below
     * makes that bound hold even if a producer emits terminals for documents this scan never
     * admitted.
     */
    private final java.util.Set<String> countedDocs = new java.util.HashSet<>();

    private OpenScan(String scanId, String collection, String root, long startedAtMs) {
      this.scanId = scanId;
      this.collection = collection == null ? "" : collection;
      this.root = root == null ? "" : root;
      this.startedAtMs = startedAtMs;
      this.lastActivityMs = startedAtMs;
    }

    /**
     * True when this document's terminal outcome has not been counted yet. An event with no
     * {@code pathHash} carries no document identity to dedup on, so it is counted — the pre-dedup
     * behaviour, not a silent drop. Callers hold the ledger's monitor.
     */
    private boolean countFirstTerminalFor(String pathHash) {
      if (pathHash == null || pathHash.isEmpty()) {
        return true;
      }
      if (admitted != ADMITTED_UNKNOWN && countedDocs.size() >= admitted) {
        return true;
      }
      return countedDocs.add(pathHash);
    }
  }
}
