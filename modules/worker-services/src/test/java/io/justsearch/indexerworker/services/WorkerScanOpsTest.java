package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.ipc.ScanRootProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 418 Phase A + B-H.2 — exercises {@link WorkerScanOps} against a real temp directory.
 *
 * <p>B-H.2 tightened the admission filter (defect A — IngestionSkipPolicy is now applied at walk
 * time), the progress stream (defect D — directory paths are SHA-256 hashed), and the cloud
 * placeholder branch (defect E — cloud placeholders now produce DEFERRED_POLICY ledger events
 * via {@link CloudPlaceholderRecorder}).
 */
final class WorkerScanOpsTest {

  @TempDir Path tempDir;

  @Test
  void scansRootAndEnqueuesRegularFiles() throws Exception {
    Path root = tempDir.resolve("src");
    Files.createDirectories(root.resolve("nested"));
    Path a = Files.writeString(root.resolve("a.txt"), "alpha");
    Path b = Files.writeString(root.resolve("nested").resolve("b.txt"), "beta");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    List<ScanRootProgress> emissions = new ArrayList<>();
    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, "docs", WorkerScanOps.ScanMode.INITIAL, List.of()),
            emissions::add);

    assertTrue(terminal.getComplete(), "Terminal progress must have complete=true");
    assertEquals("", terminal.getTerminalReasonCode(), "Clean walk has no terminal reason code");
    assertEquals(2L, terminal.getFilesAdmitted());
    assertEquals(2L, queue.enqueuedPaths.size(), "Both regular files enqueued");
    assertTrue(queue.enqueuedPaths.contains(a));
    assertTrue(queue.enqueuedPaths.contains(b));
    assertEquals("docs", queue.lastCollection);
  }

  @Test
  void skipsConfiguredSkipDirectories() throws Exception {
    Path root = tempDir.resolve("project");
    Files.createDirectories(root.resolve(".git").resolve("objects"));
    Files.createDirectories(root.resolve("node_modules").resolve("foo"));
    Files.createDirectories(root.resolve("src"));
    Files.writeString(root.resolve(".git").resolve("objects").resolve("blob"), "should-skip");
    Files.writeString(root.resolve("node_modules").resolve("foo").resolve("dep.js"), "skip-dep");
    Path keep = Files.writeString(root.resolve("src").resolve("main.txt"), "keep-me");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            p -> {});

    assertEquals(1L, terminal.getFilesAdmitted(), ".git and node_modules subtrees pruned");
    assertEquals(List.of(keep), queue.enqueuedPaths);
  }

  /** B-H.2 defect A — file-level skip rules apply at walk time, not extraction time. */
  @Test
  void appliesIngestionSkipPolicyAtWalkTime() throws Exception {
    Path root = tempDir.resolve("policy-skip");
    Files.createDirectories(root);
    Files.writeString(root.resolve("module.pyc"), "bytecode");
    Files.writeString(root.resolve("~$Office.docx"), "lock-file");
    Files.writeString(root.resolve("draft.tmp"), "temp");
    Files.writeString(root.resolve("Thumbs.db"), "system");
    Path keep = Files.writeString(root.resolve("notes.md"), "kept");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            p -> {});

    assertEquals(1L, terminal.getFilesAdmitted(), "Only the regular file should be admitted");
    assertEquals(4L, terminal.getFilesSkipped(), "All 4 policy-skipped files counted");
    assertEquals(List.of(keep), queue.enqueuedPaths);
  }

  @Test
  void appliesCallerSuppliedExcludeGlobs() throws Exception {
    Path root = tempDir.resolve("globs");
    Files.createDirectories(root);
    Path keep = Files.writeString(root.resolve("keep.txt"), "k");
    Files.writeString(root.resolve("excluded.log"), "i");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of("*.log")),
            p -> {});

    assertEquals(1L, terminal.getFilesAdmitted());
    assertEquals(List.of(keep), queue.enqueuedPaths);
  }

  @Test
  void emitsTerminalReasonWhenRootIsNotDirectory() throws Exception {
    Path notADir = Files.writeString(tempDir.resolve("file.txt"), "x");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    List<ScanRootProgress> emissions = new ArrayList<>();
    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                notADir, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            emissions::add);

    assertTrue(terminal.getComplete());
    assertEquals("ROOT_NOT_DIRECTORY", terminal.getTerminalReasonCode());
    assertEquals(0L, terminal.getFilesAdmitted());
    assertTrue(queue.enqueuedPaths.isEmpty(), "Non-directory root must not enqueue anything");
  }

  @Test
  void emitsProgressDuringWalkForLargeRoots() throws Exception {
    Path root = tempDir.resolve("many");
    Files.createDirectories(root);
    for (int i = 0; i < 250; i++) {
      Files.writeString(root.resolve("f-" + i + ".txt"), "x");
    }
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    List<ScanRootProgress> emissions = new ArrayList<>();
    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            emissions::add);

    assertEquals(250L, terminal.getFilesAdmitted());
    long inFlightCount = emissions.stream().filter(p -> !p.getComplete()).count();
    assertTrue(
        inFlightCount >= 2,
        "Walk of 250 files should emit at least 2 in-flight progress events (every 100 files); got "
            + inFlightCount);
    assertNotNull(terminal.getCurrentDirectory());
  }

  /**
   * B-H.2 defect D — nested directory paths are emitted as SHA-256 hex; only the requested root
   * is emitted verbatim. The terminal emission carries the root, so it stays plaintext.
   */
  @Test
  void hashesCurrentDirectoryForNestedPaths() throws Exception {
    Path root = tempDir.resolve("hash-check");
    Files.createDirectories(root.resolve("nested-leak-name"));
    // Need >= 100 files in the nested dir to trigger an in-flight emission with the nested
    // directory string.
    for (int i = 0; i < 150; i++) {
      Files.writeString(root.resolve("nested-leak-name").resolve("f-" + i + ".txt"), "x");
    }
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    List<ScanRootProgress> emissions = new ArrayList<>();
    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            emissions::add);

    assertEquals(root.toString(), terminal.getCurrentDirectory(),
        "Terminal emission carries the requested root verbatim");
    Set<String> dirs = new HashSet<>();
    for (ScanRootProgress p : emissions) {
      if (!p.getComplete()) {
        dirs.add(p.getCurrentDirectory());
      }
    }
    assertFalse(dirs.isEmpty(), "Walk produced at least one in-flight emission");
    for (String dir : dirs) {
      assertFalse(
          dir.contains("nested-leak-name"),
          "In-flight current_directory must not leak the nested folder name; got: " + dir);
      // SHA-256 hex is 64 lowercase hex chars OR the literal root path. Either is acceptable
      // depending on which directory the walker was inside when the emission fired.
      boolean isRoot = dir.equals(root.toString());
      boolean isHash = dir.matches("[0-9a-f]{64}");
      assertTrue(isRoot || isHash,
          "current_directory must be the root or a 64-char sha256 hex; got: " + dir);
    }
  }

  /**
   * B-H.3 defect B — Worker now owns queue-depth backpressure. Walk a 5,000-file root with a
   * queue depth that starts above HIGH and drops below LOW after 3 polls; assert the
   * backpressure waiter was invoked, then that the walk completes.
   */
  @Test
  void backpressureWaiterInvokedWhenQueueAboveHighWatermark() throws Exception {
    Path root = tempDir.resolve("backpressure");
    Files.createDirectories(root);
    // ENQUEUE_BATCH_SIZE is 2_000 — write enough files to trigger at least two flushes.
    for (int i = 0; i < 5_000; i++) {
      Files.writeString(root.resolve("f-" + i + ".txt"), "x");
    }
    RecordingQueue queue = new RecordingQueue();
    AtomicInteger waitCalls = new AtomicInteger();
    AtomicInteger depthCalls = new AtomicInteger();
    LongSupplier queueDepth =
        () -> {
          int call = depthCalls.incrementAndGet();
          // First entry pushes above HIGH; subsequent calls keep it above LOW for 3 ticks,
          // then drop to 0 to release the loop.
          if (call <= 4) return WorkerScanOps.QUEUE_HIGH_WATERMARK + 1L;
          return 0L;
        };
    WorkerScanOps.BackpressureWaiter waiter =
        millis -> {
          waitCalls.incrementAndGet();
          return true;
        };
    WorkerScanOps ops =
        new WorkerScanOps(
            queue,
            new CloudPlaceholderRecorder(queue),
            file -> false,
            queueDepth,
            () -> false,
            waiter,
            paths -> {});

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            p -> {});

    assertEquals(5_000L, terminal.getFilesAdmitted());
    assertTrue(
        waitCalls.get() >= 1,
        "Backpressure waiter must be invoked at least once when queue depth >= HIGH; got "
            + waitCalls.get());
  }

  /**
   * B-H.3 defect C — cancellation returns terminal CLIENT_CANCELLED and stops walking before
   * the full tree is consumed. The {@code isCancelled} flag flips to true after the first batch
   * flush, so we expect filesWalked &lt; total and getTerminalReasonCode == "CLIENT_CANCELLED".
   */
  @Test
  void cancellationProducesClientCancelledTerminalAndStopsWalking() throws Exception {
    Path root = tempDir.resolve("cancel");
    Files.createDirectories(root);
    // 5_000 files = 2 full batches + 1 partial. We expect to terminate after the first flush.
    for (int i = 0; i < 5_000; i++) {
      Files.writeString(root.resolve("f-" + i + ".txt"), "x");
    }
    RecordingQueue queue = new RecordingQueue();
    AtomicInteger flushObservations = new AtomicInteger();
    BooleanSupplier isCancelled =
        () -> {
          // Toggle to true after the first invocation so the first flush triggers
          // cancellation on its post-flush check.
          return flushObservations.incrementAndGet() >= 1;
        };
    WorkerScanOps ops =
        new WorkerScanOps(
            queue,
            new CloudPlaceholderRecorder(queue),
            file -> false,
            () -> 0L,
            isCancelled,
            millis -> true,
            paths -> {});

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            p -> {});

    assertTrue(terminal.getComplete(), "Terminal progress must have complete=true");
    assertEquals("CLIENT_CANCELLED", terminal.getTerminalReasonCode());
    assertTrue(
        terminal.getFilesWalked() < 5_000L,
        "Cancellation must stop the walk before the tree is exhausted; walked="
            + terminal.getFilesWalked());
  }

  /**
   * B-H.2 defect E — cloud-only placeholders now route through {@link CloudPlaceholderRecorder}
   * so the file surfaces in the ingestion ledger as DEFERRED_POLICY/CLOUD_PLACEHOLDER. The real
   * detector reads a Windows-only DOS attribute that cannot be portably synthesised, so the
   * test injects a synthetic predicate via the test seam.
   */
  @Test
  void cloudPlaceholderTriggersRecorderAndIsNotEnqueued() throws Exception {
    Path root = tempDir.resolve("cloud");
    Files.createDirectories(root);
    Path placeholder = Files.writeString(root.resolve("only-in-cloud.txt"), "stub");
    Path real = Files.writeString(root.resolve("real.txt"), "real");
    RecordingQueue queue = new RecordingQueue();
    AtomicInteger recordCalls = new AtomicInteger();
    List<Path> recordedFiles = new ArrayList<>();
    CloudPlaceholderRecorder stubRecorder =
        new CloudPlaceholderRecorder(queue) {
          @Override
          void record(Path file, String collection, JobQueue.EnqueueProvenance provenance) {
            recordCalls.incrementAndGet();
            recordedFiles.add(file);
          }
        };
    WorkerScanOps ops =
        new WorkerScanOps(queue, stubRecorder, file -> file.equals(placeholder));

    ScanRootProgress terminal =
        ops.scan(
            new WorkerScanOps.ScanRequest(
                root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
            p -> {});

    assertEquals(1L, terminal.getFilesAdmitted(), "Only the real file is admitted");
    assertEquals(List.of(real), queue.enqueuedPaths,
        "Cloud placeholder must NOT reach the queue (would trigger network hydration)");
    assertEquals(1, recordCalls.get(), "Cloud placeholder ledger event recorded once");
    assertEquals(List.of(placeholder), recordedFiles,
        "Recorder must be called with the placeholder path");
  }

  /**
   * Tempdoc 419 / T2 — every emitted {@link ScanRootProgress} carries the same {@code scan_id}
   * value supplied via the {@link WorkerScanOps.ScanRequest}. The gRPC entry point
   * ({@code WorkerIngestService.scanRoot}) allocates a UUID per RPC; this test pins the
   * propagation contract end-to-end at the WorkerScanOps level.
   */
  @Test
  void scanIdIsStampedOnEveryEmittedProgressEvent() throws Exception {
    Path root = tempDir.resolve("scanid-pin");
    Files.createDirectories(root);
    // Enough files to trigger at least one mid-scan progress emission (every 100 files) plus
    // the terminal complete=true emission. PROGRESS_EMIT_EVERY_N_FILES is 100, so write 250.
    for (int i = 0; i < 250; i++) {
      Files.writeString(root.resolve("doc-" + i + ".txt"), "x");
    }
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);
    String expectedScanId = "test-scan-" + java.util.UUID.randomUUID();

    List<ScanRootProgress> emissions = new ArrayList<>();
    ops.scan(
        new WorkerScanOps.ScanRequest(
            root, null, WorkerScanOps.ScanMode.INITIAL, List.of(), expectedScanId),
        emissions::add);

    assertTrue(emissions.size() >= 2,
        "Expected at least one in-flight progress emission plus the terminal one");
    for (ScanRootProgress event : emissions) {
      assertEquals(expectedScanId, event.getScanId(),
          "Every emitted ScanRootProgress must carry the same scan_id (got "
              + event.getScanId() + " on event with complete=" + event.getComplete() + ")");
    }
  }

  /**
   * Tempdoc 812 D2 — the scan key reaches the QUEUE, not just the progress stream. The walk's
   * enqueue is the only place the row's {@code scan_id} can be set (the insert is
   * {@code INSERT OR REPLACE}, so a later side-channel write would be erased by any re-enqueue),
   * and this is the seam the 813 merge rewired from {@code enqueue} to {@code enqueueEntries} —
   * a silent drop here would leave every scanned row keyless and no other test would notice.
   */
  @Test
  void scanIdIsStampedOnTheEnqueuedJobs() throws Exception {
    Path root = tempDir.resolve("scanid-enqueue");
    Files.createDirectories(root);
    Files.writeString(root.resolve("a.txt"), "x");
    Files.writeString(root.resolve("b.txt"), "y");
    RecordingQueue queue = new RecordingQueue();

    new WorkerScanOps(queue)
        .scan(
            new WorkerScanOps.ScanRequest(
                root, "scifact", WorkerScanOps.ScanMode.INITIAL, List.of(), "scan-77"),
            p -> {});

    assertEquals("scan-77", queue.lastScanId, "the walk stamps its scan on every enqueue");
    assertEquals("scifact", queue.lastCollection, "and still carries 813's collection");
    assertEquals(2, queue.enqueuedEntries.size(), "and still carries 813's sized entries");
    assertTrue(
        queue.enqueuedEntries.stream().allMatch(e -> e.sizeBytes() >= 0),
        "sizes survive the scan-key overload — one call states both facts");
  }

  /**
   * Tempdoc 419 / T2 back-compat — the legacy 4-arg {@link WorkerScanOps.ScanRequest}
   * constructor (used by older callers and tests) defaults {@code scanId} to the empty string.
   * This pin confirms the back-compat shim doesn't accidentally start emitting null scan_id
   * values, which would break the proto contract (proto strings are never null).
   */
  /**
   * Tempdoc 813 Slice B — the scan walk already holds {@code BasicFileAttributes}, so every
   * enqueued entry must carry that file's real byte size. A regression here (dropping back to the
   * path-only enqueue) is invisible to the path assertions above, which is why the size is pinned
   * against the actual file length rather than merely asserted non-negative.
   */
  @Test
  void scanEnqueuesCarryEachFilesByteSize() throws Exception {
    Path root = tempDir.resolve("sized");
    Files.createDirectories(root);
    Path small = Files.writeString(root.resolve("small.txt"), "abc");
    Path large = Files.writeString(root.resolve("large.txt"), "0123456789");
    RecordingQueue queue = new RecordingQueue();
    WorkerScanOps ops = new WorkerScanOps(queue);

    ops.scan(
        new WorkerScanOps.ScanRequest(root, "docs", WorkerScanOps.ScanMode.INITIAL, List.of()),
        p -> {});

    assertEquals(2, queue.enqueuedEntries.size(), "Both files enqueued as sized entries");
    for (JobQueue.EnqueueEntry entry : queue.enqueuedEntries) {
      assertEquals(
          Files.size(entry.path()),
          entry.sizeBytes(),
          "Entry for " + entry.path() + " must carry the file's real size");
    }
    long smallSize = Files.size(small);
    long largeSize = Files.size(large);
    assertTrue(
        queue.enqueuedEntries.stream().anyMatch(e -> e.sizeBytes() == smallSize),
        "The 3-byte file's size must be recorded");
    assertTrue(
        queue.enqueuedEntries.stream().anyMatch(e -> e.sizeBytes() == largeSize),
        "The 10-byte file's size must be recorded");
  }

  @Test
  void scanRequestBackCompatConstructorDefaultsScanIdToEmptyString() {
    WorkerScanOps.ScanRequest legacy =
        new WorkerScanOps.ScanRequest(
            tempDir, "docs", WorkerScanOps.ScanMode.INITIAL, List.of("*.tmp"));
    assertEquals("", legacy.scanId(), "Back-compat constructor must default scanId to \"\"");
  }

  // ===== Tempdoc 821 §3-C3 — FORCE_REINDEX marks the paths it admits =====
  //
  // Before this, every scan reached the Worker as SCAN_MODE_INITIAL and `request.mode()` was read
  // only to build the DTO. Nothing downstream consulted it, so a user's force-reindex admitted the
  // same paths and JobBatchExtractor took its UNCHANGED branch on all of them. The companion
  // JobBatchExtractorForcedPathTest proves the other half: that a path in this set actually
  // bypasses that branch.

  @Test
  void forceReindexScanMarksEveryAdmittedPathForced() throws Exception {
    Path root = tempDir.resolve("forced");
    Files.createDirectories(root.resolve("nested"));
    Path a = Files.writeString(root.resolve("a.txt"), "alpha");
    Path b = Files.writeString(root.resolve("nested").resolve("b.txt"), "beta");
    RecordingQueue queue = new RecordingQueue();
    List<String> forced = new ArrayList<>();
    WorkerScanOps ops = new WorkerScanOps(queue, () -> 0L, () -> false, forced::addAll);

    ops.scan(
        new WorkerScanOps.ScanRequest(
            root, null, WorkerScanOps.ScanMode.FORCE_REINDEX, List.of(), "scan-force"),
        p -> {});

    // The key must be EXACTLY what FileFreshnessSnapshot.capture writes into the envelope
    // (toAbsolutePath().normalize(), then PathNormalizer) — the extractor looks the path up by
    // that string, so a key differing by separator, case, or a `..` segment would silently no-op.
    assertEquals(
        Set.of(normalizedKey(a), normalizedKey(b)),
        Set.copyOf(forced),
        "every path a FORCE_REINDEX scan admits must be marked forced, keyed as the envelope is");
  }

  @Test
  void ordinaryScansMarkNothingForced() throws Exception {
    Path root = tempDir.resolve("unforced");
    Files.createDirectories(root);
    Files.writeString(root.resolve("a.txt"), "alpha");

    for (WorkerScanOps.ScanMode mode :
        List.of(WorkerScanOps.ScanMode.INITIAL, WorkerScanOps.ScanMode.RESCAN)) {
      RecordingQueue queue = new RecordingQueue();
      List<String> forced = new ArrayList<>();
      WorkerScanOps ops = new WorkerScanOps(queue, () -> 0L, () -> false, forced::addAll);

      ScanRootProgress terminal =
          ops.scan(
              new WorkerScanOps.ScanRequest(root, null, mode, List.of(), "scan-" + mode), p -> {});

      // Precision: the walk DID admit the file, so an empty `forced` is the mode gate firing —
      // not a scan that silently admitted nothing.
      assertEquals(1L, terminal.getFilesAdmitted(), mode + " must still admit the file");
      assertTrue(forced.isEmpty(), mode + " must not mark anything forced");
    }
  }

  @Test
  void refusedAdmissionCannotProduceCleanCompletionOrAdmittedProgress() throws Exception {
    Path root = Files.createDirectory(tempDir.resolve("refused"));
    for (int i = 0; i < 101; i++) Files.writeString(root.resolve(i + ".txt"), "data");
    JobQueue refused = org.mockito.Mockito.mock(JobQueue.class);
    List<ScanRootProgress> progress = new ArrayList<>();
    var failure = assertThrows(WorkerServiceException.class, () -> new WorkerScanOps(refused).scan(
        new WorkerScanOps.ScanRequest(root, null, WorkerScanOps.ScanMode.INITIAL, List.of()),
        progress::add));
    assertEquals(WorkerServiceException.Status.UNAVAILABLE, failure.status());
    assertTrue(progress.stream().noneMatch(ScanRootProgress::getComplete));
    assertTrue(progress.stream().allMatch(frame -> frame.getFilesAdmitted() == 0));
    assertFalse(progress.isEmpty(), "exercise the progress-before-flush branch too");
  }

  private static String normalizedKey(Path p) {
    return io.justsearch.indexerworker.util.PathNormalizer.normalizePath(
        p.toAbsolutePath().normalize().toString());
  }

  private static final class RecordingQueue implements JobQueue {
    final List<Path> enqueuedPaths = new ArrayList<>();
    final List<EnqueueEntry> enqueuedEntries = new ArrayList<>();
    String lastCollection;
    String lastScanId;

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      enqueuedPaths.addAll(paths);
      lastCollection = collection;
      return paths.size();
    }

    @Override
    public int enqueueEntries(List<EnqueueEntry> entries, String collection) {
      enqueuedEntries.addAll(entries);
      return enqueue(EnqueueEntry.paths(entries), collection);
    }

    @Override
    public int enqueueEntries(List<EnqueueEntry> entries, String collection, String scanId) {
      // Tempdoc 812 D2: record the scan key the walk stamps on its enqueues, then fall through to
      // the sized path so 813's size assertions keep seeing every entry.
      lastScanId = scanId;
      return enqueueEntries(entries, collection);
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {}

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void recordIngestionEvent(
        Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {}

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
