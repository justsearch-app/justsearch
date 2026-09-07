/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.ingest.IngestionSkipPolicy;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.ScanRootProgress;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Worker-owned filesystem traversal for tempdoc 418.
 *
 * <p>Walks a single root, applies the shared {@link IngestionSkipPolicy} admission rules + the
 * cloud-placeholder ledger seam, enqueues admitted files into the {@link JobQueue}, and emits
 * {@link ScanRootProgress} every {@link #PROGRESS_EMIT_EVERY_N_FILES} files. Phase A was purely
 * additive scaffolding; Phase B Hardening (B-H.2) made the admission filter and ledger semantics
 * exactly match the {@link SyncDirectoryOps} sync-walk path so Worker-owned scans no longer
 * silently differ from the in-process pipeline.
 *
 * <p>Differences from {@link SyncDirectoryOps}:
 * <ul>
 *   <li>No prune phase (callers ask for a focused walk; pruning remains a separate concern).</li>
 *   <li>No user-activity throttling — scan intent is operator-driven, not periodic-sync.</li>
 *   <li>Optional caller-supplied glob excludes layered on top of the standard skip set.</li>
 *   <li>Streams progress to a {@link Consumer} sink supplied by the caller, which decides where
 *       each frame goes.</li>
 * </ul>
 */
final class WorkerScanOps {
  private static final Logger log = LoggerFactory.getLogger(WorkerScanOps.class);

  private static final int PROGRESS_EMIT_EVERY_N_FILES = 100;
  private static final int ENQUEUE_BATCH_SIZE = 2_000;

  /**
   * Backpressure thresholds — match the prior Head-side {@code RootLifecycleOps} constants so
   * Worker-owned scans throttle at the same depths users have observed historically.
   */
  static final long QUEUE_HIGH_WATERMARK = 90_000L;

  static final long QUEUE_LOW_WATERMARK = 70_000L;
  static final long BACKPRESSURE_POLL_MS = 2_000L;

  private final JobQueue jobQueue;
  private final CloudPlaceholderRecorder cloudPlaceholderRecorder;
  private final Predicate<Path> isCloudPlaceholder;
  private final LongSupplier queueDepthSupplier;
  private final BooleanSupplier isCancelled;
  private final BackpressureWaiter backpressureWaiter;
  private final ForcedPathSink forcedPathSink;

  /** Production constructor — uses the real cloud-placeholder detector and no-op pacing hooks. */
  WorkerScanOps(JobQueue jobQueue) {
    this(
        jobQueue,
        new CloudPlaceholderRecorder(jobQueue),
        SyncDirectoryOps::isCloudPlaceholder,
        jobQueue::queueDepth,
        () -> false,
        WorkerScanOps::sleepForBackpressure,
        paths -> {});
  }

  /**
   * Constructor with cancellation + backpressure hooks but the real cloud-placeholder
   * detector. Used by {@link WorkerIngestService} so the Worker-owned scan honours the call's
   * cancellation signal ({@link CallContext#cancelled()}) and the live queue depth.
   */
  WorkerScanOps(
      JobQueue jobQueue,
      LongSupplier queueDepthSupplier,
      BooleanSupplier isCancelled,
      ForcedPathSink forcedPathSink) {
    this(
        jobQueue,
        new CloudPlaceholderRecorder(jobQueue),
        SyncDirectoryOps::isCloudPlaceholder,
        queueDepthSupplier,
        isCancelled,
        WorkerScanOps::sleepForBackpressure,
        forcedPathSink);
  }

  /**
   * Test seam — lets {@link WorkerScanOpsTest} substitute a stub recorder, a synthetic
   * cloud-placeholder predicate (the real Windows {@code FILE_ATTRIBUTE_RECALL_ON_DATA_ACCESS}
   * bit cannot be portably set in tests), a queue-depth supplier, a cancellation flag, and a
   * sleep callback that counts invocations rather than blocking the test thread.
   */
  WorkerScanOps(
      JobQueue jobQueue,
      CloudPlaceholderRecorder cloudPlaceholderRecorder,
      Predicate<Path> isCloudPlaceholder,
      LongSupplier queueDepthSupplier,
      BooleanSupplier isCancelled,
      BackpressureWaiter backpressureWaiter,
      ForcedPathSink forcedPathSink) {
    this.jobQueue = Objects.requireNonNull(jobQueue, "jobQueue");
    this.cloudPlaceholderRecorder =
        Objects.requireNonNull(cloudPlaceholderRecorder, "cloudPlaceholderRecorder");
    this.isCloudPlaceholder = Objects.requireNonNull(isCloudPlaceholder, "isCloudPlaceholder");
    this.queueDepthSupplier = Objects.requireNonNull(queueDepthSupplier, "queueDepthSupplier");
    this.isCancelled = Objects.requireNonNull(isCancelled, "isCancelled");
    this.backpressureWaiter = Objects.requireNonNull(backpressureWaiter, "backpressureWaiter");
    this.forcedPathSink = Objects.requireNonNull(forcedPathSink, "forcedPathSink");
  }

  /** Test-only convenience for the prior 3-arg constructor. */
  WorkerScanOps(
      JobQueue jobQueue,
      CloudPlaceholderRecorder cloudPlaceholderRecorder,
      Predicate<Path> isCloudPlaceholder) {
    this(
        jobQueue,
        cloudPlaceholderRecorder,
        isCloudPlaceholder,
        () -> 0L,
        () -> false,
        WorkerScanOps::sleepForBackpressure,
        paths -> {});
  }

  /**
   * Walks {@code root} and admits regular files into the queue. Cloud-only placeholders are
   * routed through the standard ledger event path (deferred, never enqueued for extraction
   * which would trigger network hydration).
   *
   * @param request scan parameters (root, collection, mode, exclude globs)
   * @param progressEmitter callback invoked every {@link #PROGRESS_EMIT_EVERY_N_FILES} files
   *     with an in-flight {@link ScanRootProgress}, and once at the end with
   *     {@code complete=true}.
   * @return final {@link ScanRootProgress} ({@code complete=true})
   */
  ScanRootProgress scan(ScanRequest request, Consumer<ScanRootProgress> progressEmitter)
      throws IOException {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(progressEmitter, "progressEmitter");
    String scanId = request.scanId();
    Path root = request.root();
    if (!Files.isDirectory(root)) {
      ScanRootProgress terminal =
          ScanRootProgress.newBuilder()
              .setComplete(true)
              .setTerminalReasonCode("ROOT_NOT_DIRECTORY")
              .setScanId(scanId)
              .build();
      progressEmitter.accept(terminal);
      return terminal;
    }

    List<PathMatcher> excludes = buildExcludeMatchers(request.excludeGlobs());
    long[] counters = new long[3]; // [walked, admitted, skipped]
    long[] bytes = new long[1];
    String[] currentDir = new String[] {root.toString()};
    List<JobQueue.EnqueueEntry> batch = new ArrayList<>(ENQUEUE_BATCH_SIZE);
    boolean[] cancelled = {false};
    String collection = request.collection();
    // Tempdoc 821 §3-C3: a FORCE_REINDEX scan marks every path it admits forced, so the batch
    // extractor bypasses its unchanged-check and actually re-runs extraction + enrichment.
    // Same sink WorkerIngestService#submitBatch already uses for ForceReindex on the batch API —
    // no new mechanism, just the arm that never consulted request.mode().
    boolean forceReindex = request.mode() == ScanMode.FORCE_REINDEX;
    // Tempdoc 812 D2: every job this walk admits remembers the scan that admitted it, so the Head
    // can roll the per-document terminal outcomes up into ONE durable scan-completion audit row.
    String enqueueScanId = scanId;

    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            currentDir[0] = displayDirectory(dir, root);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            counters[0]++; // walked
            if (!attrs.isRegularFile() || !Files.isReadable(file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (IngestionSkipPolicy.shouldSkip(file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (isCloudPlaceholder.test(file)) {
              cloudPlaceholderRecorder.record(file);
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (matchesAny(excludes, root, file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            counters[1]++;
            bytes[0] += attrs.size();
            // 813 Slice B: the walk already holds the size — no extra stat.
            batch.add(new JobQueue.EnqueueEntry(file, attrs.size()));
            if (batch.size() >= ENQUEUE_BATCH_SIZE) {
              awaitQueueBelowThreshold();
              flushBatch(batch, collection, enqueueScanId, forceReindex);
              if (isCancelled.getAsBoolean()) {
                cancelled[0] = true;
                return FileVisitResult.TERMINATE;
              }
            }
            if (counters[0] % PROGRESS_EMIT_EVERY_N_FILES == 0) {
              progressEmitter.accept(
                  inFlightProgress(counters, bytes[0], currentDir[0], false, "", scanId));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            log.debug(
                "scan: skipping inaccessible path {} ({})",
                file,
                exc != null ? exc.getMessage() : "unknown");
            counters[2]++;
            return FileVisitResult.CONTINUE;
          }
        });

    if (!batch.isEmpty()) {
      awaitQueueBelowThreshold();
      flushBatch(batch, collection, enqueueScanId, forceReindex);
      if (isCancelled.getAsBoolean()) {
        cancelled[0] = true;
      }
    }

    String terminalReason = cancelled[0] ? "CLIENT_CANCELLED" : "";
    ScanRootProgress terminal =
        inFlightProgress(counters, bytes[0], root.toString(), true, terminalReason, scanId);
    progressEmitter.accept(terminal);
    return terminal;
  }

  private void flushBatch(
      List<JobQueue.EnqueueEntry> batch, String collection, String scanId, boolean forceReindex) {
    String coll = collection == null || collection.isBlank() ? null : collection;
    jobQueue.enqueueEntries(
        List.copyOf(batch), coll, scanId == null || scanId.isBlank() ? null : scanId);
    if (forceReindex && !batch.isEmpty()) {
      // Mark per batch rather than once at the end: a long walk's early batches are already
      // being extracted while later directories are still being visited, so a deferred mark
      // would arrive after the extractor had skipped them as UNCHANGED.
      // The key MUST be byte-identical to FileFreshnessSnapshot.capture's, so both sides derive it
      // through the one shared PathNormalizer#normalizeKey: a key that differs by one `..` segment
      // would make `forcedPaths.remove(normalizedPath)` miss and the force silently do nothing.
      forcedPathSink.markForced(
          batch.stream().map(e -> PathNormalizer.normalizeKey(e.path())).toList());
    }
    batch.clear();
  }

  /**
   * Sink for paths a FORCE_REINDEX scan admits (tempdoc 821 §3-C3). Production wiring is {@code
   * IndexingLoop::markForced} — the same set {@code WorkerIngestService#submitBatch} feeds for the
   * batch API's {@code force_reindex} flag.
   */
  @FunctionalInterface
  interface ForcedPathSink {
    void markForced(Collection<String> normalizedPaths);
  }

  /**
   * Blocks the scan thread until the queue depth falls below {@link #QUEUE_LOW_WATERMARK} when it
   * was previously above {@link #QUEUE_HIGH_WATERMARK}. Sleep duration is delegated through
   * {@link BackpressureWaiter} so tests can substitute a counter without blocking.
   */
  private void awaitQueueBelowThreshold() {
    long depth = queueDepthSupplier.getAsLong();
    if (depth < QUEUE_HIGH_WATERMARK) {
      return;
    }
    log.debug(
        "Backpressure: queue depth {} above {}, waiting for < {}",
        depth,
        QUEUE_HIGH_WATERMARK,
        QUEUE_LOW_WATERMARK);
    while (queueDepthSupplier.getAsLong() >= QUEUE_LOW_WATERMARK) {
      if (isCancelled.getAsBoolean()) {
        return;
      }
      if (!backpressureWaiter.waitForBatch(BACKPRESSURE_POLL_MS)) {
        return;
      }
    }
  }

  private static boolean sleepForBackpressure(long millis) {
    try {
      Thread.sleep(millis);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Test seam for {@link #awaitQueueBelowThreshold} — return false to break the loop. */
  @FunctionalInterface
  interface BackpressureWaiter {
    boolean waitForBatch(long millis);
  }

  /**
   * Returns the requested root verbatim (clients already know it) or a SHA-256 hex hash for any
   * directory under the root. Mirrors the privacy property of the ingestion ledger so progress
   * streams don't leak user folder names.
   */
  private static String displayDirectory(Path dir, Path root) {
    if (dir.equals(root)) {
      return root.toString();
    }
    return CloudPlaceholderRecorder.sha256Hex(dir.toString());
  }

  private static ScanRootProgress inFlightProgress(
      long[] counters,
      long bytesWalked,
      String currentDirectory,
      boolean complete,
      String terminalReasonCode,
      String scanId) {
    return ScanRootProgress.newBuilder()
        .setFilesWalked(counters[0])
        .setFilesAdmitted(counters[1])
        .setFilesSkipped(counters[2])
        .setBytesWalked(bytesWalked)
        .setCurrentDirectory(currentDirectory != null ? currentDirectory : "")
        .setComplete(complete)
        .setTerminalReasonCode(terminalReasonCode == null ? "" : terminalReasonCode)
        .setScanId(scanId == null ? "" : scanId)
        .build();
  }

  private static List<PathMatcher> buildExcludeMatchers(List<String> globs) {
    if (globs == null || globs.isEmpty()) return List.of();
    FileSystem fs = FileSystems.getDefault();
    List<PathMatcher> matchers = new ArrayList<>(globs.size());
    for (String glob : globs) {
      if (glob == null || glob.isBlank()) continue;
      try {
        matchers.add(fs.getPathMatcher("glob:" + glob.trim()));
      } catch (IllegalArgumentException ignored) {
        log.debug("Ignoring invalid scan-exclude glob: {}", glob);
      }
    }
    return List.copyOf(matchers);
  }

  private static boolean matchesAny(List<PathMatcher> matchers, Path root, Path file) {
    if (matchers.isEmpty()) return false;
    Path relative;
    try {
      relative = root.relativize(file);
    } catch (IllegalArgumentException ignored) {
      relative = file;
    }
    for (PathMatcher matcher : matchers) {
      if (matcher.matches(relative) || matcher.matches(file)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Caller-side request DTO so this class doesn't depend on the proto types.
   *
   * <p>Tempdoc 419 / T2: {@code scanId} is allocated by the gRPC entry point ({@link
   * WorkerIngestService#scanRoot}) and stamped on every emitted {@link ScanRootProgress}. The
   * worker {@link WorkerScanOps} reads it but does not generate it.
   */
  record ScanRequest(
      Path root, String collection, ScanMode mode, List<String> excludeGlobs, String scanId) {
    public ScanRequest {
      Objects.requireNonNull(root, "root");
      excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
      mode = mode == null ? ScanMode.INITIAL : mode;
      scanId = scanId == null ? "" : scanId;
    }

    /** Back-compat constructor for callers that don't supply a scanId. */
    public ScanRequest(Path root, String collection, ScanMode mode, List<String> excludeGlobs) {
      this(root, collection, mode, excludeGlobs, "");
    }
  }

  enum ScanMode {
    INITIAL,
    RESCAN,
    FORCE_REINDEX
  }
}
