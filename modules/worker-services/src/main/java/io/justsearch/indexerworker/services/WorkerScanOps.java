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
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
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
  private final Runnable beforeRecordedAdmission;

  private static void denyUnguardedRecordedAdmission() {
    throw WorkerServiceException.unavailable("RECORDED_GENERATION_GUARD_REQUIRED");
  }

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
    this(jobQueue, queueDepthSupplier, isCancelled, forcedPathSink,
        WorkerScanOps::denyUnguardedRecordedAdmission);
  }

  WorkerScanOps(JobQueue jobQueue, LongSupplier queueDepthSupplier,
      BooleanSupplier isCancelled, ForcedPathSink forcedPathSink, Runnable beforeRecordedAdmission) {
    this(
        jobQueue,
        new CloudPlaceholderRecorder(jobQueue),
        SyncDirectoryOps::isCloudPlaceholder,
        queueDepthSupplier,
        isCancelled,
        WorkerScanOps::sleepForBackpressure,
        forcedPathSink, beforeRecordedAdmission);
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
    this(jobQueue, cloudPlaceholderRecorder, isCloudPlaceholder, queueDepthSupplier,
        isCancelled, backpressureWaiter, forcedPathSink, WorkerScanOps::denyUnguardedRecordedAdmission);
  }

  WorkerScanOps(JobQueue jobQueue, CloudPlaceholderRecorder cloudPlaceholderRecorder,
      Predicate<Path> isCloudPlaceholder, LongSupplier queueDepthSupplier,
      BooleanSupplier isCancelled, BackpressureWaiter backpressureWaiter,
      ForcedPathSink forcedPathSink, Runnable beforeRecordedAdmission) {
    this.jobQueue = Objects.requireNonNull(jobQueue, "jobQueue");
    this.cloudPlaceholderRecorder =
        Objects.requireNonNull(cloudPlaceholderRecorder, "cloudPlaceholderRecorder");
    this.isCloudPlaceholder = Objects.requireNonNull(isCloudPlaceholder, "isCloudPlaceholder");
    this.queueDepthSupplier = Objects.requireNonNull(queueDepthSupplier, "queueDepthSupplier");
    this.isCancelled = Objects.requireNonNull(isCancelled, "isCancelled");
    this.backpressureWaiter = Objects.requireNonNull(backpressureWaiter, "backpressureWaiter");
    this.forcedPathSink = Objects.requireNonNull(forcedPathSink, "forcedPathSink");
    this.beforeRecordedAdmission = Objects.requireNonNull(beforeRecordedAdmission, "beforeRecordedAdmission");
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
    LinkOption[] linkOptions = request.recordedEpoch() == null
        ? new LinkOption[0] : new LinkOption[] {LinkOption.NOFOLLOW_LINKS};
    if (request.singleFile() ? !Files.isRegularFile(root, linkOptions) : !Files.isDirectory(root, linkOptions)) {
      ScanRootProgress terminal =
          ScanRootProgress.newBuilder()
              .setComplete(true)
              .setTerminalReasonCode(request.singleFile() ? "ROOT_NOT_FILE" : "ROOT_NOT_DIRECTORY")
              .setScanId(scanId)
              .build();
      progressEmitter.accept(terminal);
      return terminal;
    }

    List<PathMatcher> excludes = buildExcludeMatchers(request.excludeGlobs(), request.recordedEpoch() != null);
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
        EnumSet.noneOf(FileVisitOption.class),
        request.singleFile() ? 0 : Integer.MAX_VALUE,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (request.singleFile()) throw new IOException("Recorded file changed kind during enumeration");
            // Review S5. Before this, the ONLY cancel poll in the whole walk was inside the
            // batch-flush branch below — so a scan only noticed a cancel once it had admitted a
            // full batch of files. Over a tree whose files are all excluded, skipped, or cloud
            // placeholders, no batch ever fills, so the walk was not cancellable at all: the user
            // pressed stop, the flow closed, and the walker kept traversing the disk to the end.
            // Two polls fix it, and this is the one that bounds the pathological case, because a
            // directory is the unit of work that has no other exit.
            if (isCancelled.getAsBoolean()) {
              cancelled[0] = true;
              return FileVisitResult.TERMINATE;
            }
            if (excludedByOwnership(request, dir)) return FileVisitResult.SKIP_SUBTREE;
            String name = dir.getFileName() != null ? dir.getFileName().toString() : "";
            if (IngestionSkipPolicy.isSkippedDirectoryName(name)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            currentDir[0] = displayDirectory(dir, root);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (request.recordedEpoch() != null && file.equals(root)
                && (!request.singleFile() || !attrs.isRegularFile())) {
              throw new IOException("Recorded root changed kind during enumeration");
            }
            // Review S5, the second poll: a directory of a million files is one preVisitDirectory
            // call, so the per-directory poll alone still leaves a long uncancellable stretch. The
            // read is a volatile load against per-file work that already includes an isReadable
            // syscall and a skip-policy match, so it is not measurable here.
            if (isCancelled.getAsBoolean()) {
              cancelled[0] = true;
              return FileVisitResult.TERMINATE;
            }
            counters[0]++; // walked
            if (excludedByOwnership(request, file)
                || request.recordedEpoch() != null && matchesAny(excludes, root, file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (attrs.isRegularFile() && !Files.isReadable(file) && request.recordedEpoch() != null) {
              throw new IOException("Recorded member is unreadable");
            }
            if (!attrs.isRegularFile() || !Files.isReadable(file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (IngestionSkipPolicy.shouldSkip(file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (isCloudPlaceholder.test(file)) {
              if (request.captureMode() == WorkerIngestService.RecordedScanMode.CAPTURED) {
                throw new IOException("Captured bulk member is a cloud placeholder");
              }
              cloudPlaceholderRecorder.record(file, collection, request.provenance());
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            if (matchesAny(excludes, root, file)) {
              counters[2]++;
              return FileVisitResult.CONTINUE;
            }
            // 813 Slice B: the walk already holds the size — no extra stat.
            String sourceHash = request.captureMode() == WorkerIngestService.RecordedScanMode.CAPTURED
                ? io.justsearch.indexerworker.loop.SourceContentHash.sha256(file) : null;
            batch.add(new JobQueue.EnqueueEntry(file, attrs.size(), request.provenance(), sourceHash));
            if (batch.size() >= ENQUEUE_BATCH_SIZE) {
              if (!awaitAdmissionCapacity(request) || isCancelled.getAsBoolean()) {
                cancelled[0] = true;
                return FileVisitResult.TERMINATE;
              }
              flushBatch(batch, collection, enqueueScanId, request.recordedEpoch(), forceReindex, counters, bytes);
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
          public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
            if (request.recordedEpoch() != null) {
              throw exc != null ? exc : new IOException("Recorded enumeration could not visit a member");
            }
            log.debug(
                "scan: skipping inaccessible path {} ({})",
                file,
                exc != null ? exc.getMessage() : "unknown");
            counters[2]++;
            return FileVisitResult.CONTINUE;
          }
        });

    if (!batch.isEmpty() && !cancelled[0]) {
      if (!awaitAdmissionCapacity(request) || isCancelled.getAsBoolean()) {
        cancelled[0] = true;
      } else {
        flushBatch(batch, collection, enqueueScanId, request.recordedEpoch(), forceReindex, counters, bytes);
        if (isCancelled.getAsBoolean()) cancelled[0] = true;
      }
    }

    String terminalReason = cancelled[0] ? "CLIENT_CANCELLED" : "";
    ScanRootProgress terminal =
        inFlightProgress(counters, bytes[0], root.toString(), true, terminalReason, scanId);
    progressEmitter.accept(terminal);
    return terminal;
  }

  private void flushBatch(
      List<JobQueue.EnqueueEntry> batch, String collection, String scanId, Long recordedEpoch, boolean forceReindex,
      long[] counters, long[] bytes) {
    String coll = collection == null || collection.isBlank() ? null : collection;
    if (recordedEpoch != null) beforeRecordedAdmission.run();
    int accepted = recordedEpoch == null
        ? jobQueue.enqueueEntries(List.copyOf(batch), coll, scanId == null || scanId.isBlank() ? null : scanId)
        : jobQueue.enqueueRecordedEntries(scanId, recordedEpoch, List.copyOf(batch), coll);
    if (accepted != batch.size()) {
      throw WorkerServiceException.unavailable("QUEUE_ADMISSION_FAILED");
    }
    counters[1] += accepted;
    for (JobQueue.EnqueueEntry entry : batch) bytes[0] += entry.sizeBytes();
    if (forceReindex && recordedEpoch == null && !batch.isEmpty()) {
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

  private boolean awaitAdmissionCapacity(ScanRequest request) {
    // Captured membership cannot drain until the whole manifest is COMPLETE. Waiting for queue
    // depth here would deadlock any corpus above the watermark; batches remain bounded at 2,000.
    return request.captureMode() == WorkerIngestService.RecordedScanMode.CAPTURED
        || awaitQueueBelowThreshold();
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
  private boolean awaitQueueBelowThreshold() {
    long depth = queueDepthSupplier.getAsLong();
    if (depth < QUEUE_HIGH_WATERMARK) {
      return !isCancelled.getAsBoolean();
    }
    log.debug(
        "Backpressure: queue depth {} above {}, waiting for < {}",
        depth,
        QUEUE_HIGH_WATERMARK,
        QUEUE_LOW_WATERMARK);
    while (queueDepthSupplier.getAsLong() >= QUEUE_LOW_WATERMARK) {
      if (isCancelled.getAsBoolean()) {
        return false;
      }
      if (!backpressureWaiter.waitForBatch(BACKPRESSURE_POLL_MS)) {
        return false;
      }
    }
    return !isCancelled.getAsBoolean();
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

  private static boolean excludedByOwnership(ScanRequest request, Path candidate) {
    Path normalized = candidate.toAbsolutePath().normalize();
    return request.excludedSubtrees().stream().anyMatch(normalized::startsWith);
  }

  private static List<PathMatcher> buildExcludeMatchers(List<String> globs, boolean strict) {
    if (globs == null || globs.isEmpty()) return List.of();
    FileSystem fs = FileSystems.getDefault();
    List<PathMatcher> matchers = new ArrayList<>(globs.size());
    for (String glob : globs) {
      if (glob == null || glob.isBlank()) continue;
      try {
        matchers.add(fs.getPathMatcher("glob:" + glob.trim()));
      } catch (IllegalArgumentException ignored) {
        if (strict) throw new IllegalArgumentException("Invalid recorded scan-exclude glob", ignored);
        log.debug("Ignoring invalid scan-exclude glob: {}", glob);
      }
    }
    return List.copyOf(matchers);
  }

  private static boolean matchesAny(List<PathMatcher> matchers, Path root, Path file) {
    if (matchers.isEmpty()) return false;
    Path relative;
    try {
      relative = root.equals(file) ? file.getFileName() : root.relativize(file);
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
   * <p>Tempdoc 419 / T2: {@code scanId} is allocated by {@link WorkerIngestService#scanRoot} and
   * stamped on every emitted {@link ScanRootProgress}. The worker {@link WorkerScanOps} reads it
   * but does not generate it.
   */
  record ScanRequest(
      Path root, String collection, ScanMode mode, List<String> excludeGlobs, String scanId,
      JobQueue.EnqueueProvenance provenance, Long recordedEpoch, List<Path> excludedSubtrees, boolean singleFile,
      WorkerIngestService.RecordedScanMode captureMode) {
    public ScanRequest {
      Objects.requireNonNull(root, "root");
      Objects.requireNonNull(provenance, "provenance");
      Objects.requireNonNull(captureMode, "captureMode");
      if (captureMode == WorkerIngestService.RecordedScanMode.CAPTURED
          && (recordedEpoch == null || singleFile || mode != ScanMode.FORCE_REINDEX)) {
        throw new IllegalArgumentException("Captured bulk requires recorded forced directory traversal");
      }
      excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
      mode = mode == null ? ScanMode.INITIAL : mode;
      scanId = scanId == null ? "" : scanId;
      excludedSubtrees = List.copyOf(excludedSubtrees);
      if (recordedEpoch != null && (recordedEpoch < 1 || scanId.isBlank())
          || recordedEpoch == null && (!excludedSubtrees.isEmpty() || singleFile)) {
        throw new IllegalArgumentException("Invalid recorded scan membership");
      }
      for (Path subtree : excludedSubtrees) {
        if (!subtree.isAbsolute() || !subtree.normalize().equals(subtree)
            || subtree.equals(root) || !subtree.startsWith(root)) {
          throw new IllegalArgumentException("Excluded subtree must be a normalized descendant");
        }
      }
    }

    public ScanRequest(Path root, String collection, ScanMode mode, List<String> excludeGlobs,
        String scanId, JobQueue.EnqueueProvenance provenance, Long recordedEpoch, List<Path> excludedSubtrees,
        boolean singleFile) {
      this(root, collection, mode, excludeGlobs, scanId, provenance, recordedEpoch, excludedSubtrees, singleFile,
          WorkerIngestService.RecordedScanMode.STREAMING);
    }

    public ScanRequest(Path root, String collection, ScanMode mode, List<String> excludeGlobs,
        String scanId, JobQueue.EnqueueProvenance provenance, Long recordedEpoch, List<Path> excludedSubtrees) {
      this(root, collection, mode, excludeGlobs, scanId, provenance, recordedEpoch, excludedSubtrees, false);
    }

    public ScanRequest(Path root, String collection, ScanMode mode, List<String> excludeGlobs,
        String scanId, JobQueue.EnqueueProvenance provenance) {
      this(root, collection, mode, excludeGlobs, scanId, provenance, null, List.of(), false);
    }

    /** Internal maintenance scan without a caller's admission attribution. */
    public ScanRequest(Path root, String collection, ScanMode mode, List<String> excludeGlobs,
        String scanId) {
      this(root, collection, mode, excludeGlobs, scanId, CallContext.none().provenance());
    }

    /** Internal maintenance scan without an externally allocated scan id. */
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
