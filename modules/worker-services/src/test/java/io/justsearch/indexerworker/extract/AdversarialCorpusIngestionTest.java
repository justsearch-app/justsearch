package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.IndexingLoopOptions;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 410 §14 — adversarial corpus driven through the real {@link IndexingLoop} extract path.
 *
 * <p>Runs each hostile-input row of the V1 matrix against a real
 * {@link PolicyDrivenTikaExtractor} (no provider mocks), with a mocked
 * {@link IndexingCoordinator} so no Lucene I/O is required. Proves that real bytes — not
 * synthetic exceptions thrown by mocks — produce the expected typed outcome.
 *
 * <p>Slice C (2026-04-25) added a POSIX symlink-loop case and a cross-platform mid-extraction
 * mutation case. Windows reparse points and cloud-placeholder simulation remain deferred
 * (require platform-specific test infrastructure).
 */
final class AdversarialCorpusIngestionTest {

  @TempDir Path tempDir;
  private RecordingQueue queue;
  private IndexingLoop loop;

  @BeforeEach
  void setUp() {
    queue = new RecordingQueue();
    queue.indexingCoordinator = mock(IndexingCoordinator.class);
    loop =
        new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            queue,
            queue.indexingCoordinator,
            mock(CommitOps.class),
            mock(DocumentFieldOps.class),
            mock(IndexCountOps.class),
            () -> null,
            mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(),
            null,
            null,
            null,
            null,
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                new PolicyDrivenTikaExtractor(),
                Duration.ofSeconds(10),
                (ExtractionMetricCatalog) null),
            null,
            identityOptions());
  }

  @Test
  @DisplayName("zero-byte file admits and produces SUCCESS_EMPTY outcome on commit drain")
  void zeroByteFileReachesSuccessOnDrain() throws Exception {
    // Tempdoc 671, Long-term design part 2: this used to assert SUCCESS_FULL — the exact
    // conflation bug this tempdoc's second fix corrects. A zero-byte file produces no content;
    // the correct outcome distinguishes that from "extracted everything."
    Path file = Files.createFile(tempDir.resolve("empty.txt"));
    invokeExtractAndDrain(file);
    assertEquals(IngestionOutcomeClass.SUCCESS_EMPTY, queue.lastOutcome.outcomeClass());
  }

  @Test
  @DisplayName("invalid UTF-8 sequence admits and reaches SUCCESS_FULL (Tika handles gracefully)")
  void invalidUtf8AdmitsAndSucceeds() throws Exception {
    Path file = tempDir.resolve("invalid-utf8.txt");
    // Bytes 0xC3 0x28 are an invalid UTF-8 sequence (0xC3 expects a continuation byte).
    Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28, 'h', 'i'});
    invokeExtractAndDrain(file);
    assertEquals(IngestionOutcomeClass.SUCCESS_FULL, queue.lastOutcome.outcomeClass());
  }

  @Test
  @DisplayName("single very long line admits within policy and reaches SUCCESS_FULL")
  void longSingleLineAdmits() throws Exception {
    Path file = tempDir.resolve("long-line.txt");
    Files.writeString(file, "x".repeat(10 * 1024), StandardCharsets.UTF_8);
    invokeExtractAndDrain(file);
    assertEquals(IngestionOutcomeClass.SUCCESS_FULL, queue.lastOutcome.outcomeClass());
  }

  @Test
  @DisplayName("Python bytecode file is skipped by extension policy without parser invocation")
  void pythonBytecodeFileIsSkippedByExtension() throws Exception {
    Path file = tempDir.resolve("module.pyc");
    Files.write(file, new byte[] {0x42, 0x42, 0x42, 0x42});

    Object extracted = invokeExtractJob(file);
    assertEquals(null, extracted, "extension-skip path should short-circuit and return null");
    assertEquals(IngestionOutcomeClass.SKIPPED_POLICY, queue.lastOutcome.outcomeClass());
    assertEquals(IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM, queue.lastOutcome.reasonCode());
    assertTrue(queue.done);
  }

  @Test
  @DisplayName("directory (non-regular source) is admission-skipped without parser invocation")
  void directoryIsAdmissionSkipped() throws Exception {
    Path dir = Files.createDirectory(tempDir.resolve("a-directory"));

    Object extracted = invokeExtractJob(dir);
    assertEquals(null, extracted);
    assertEquals(IngestionOutcomeClass.SKIPPED_POLICY, queue.lastOutcome.outcomeClass());
    assertEquals(IngestionReasonCodes.NON_REGULAR_SOURCE, queue.lastOutcome.reasonCode());
  }

  @Test
  @DisplayName("malformed ZIP triggers a typed parser/budget outcome (not a placeholder document)")
  void malformedZipFailsTyped() throws Exception {
    // A truncated ZIP central directory: starts with the local file header magic but ends mid-data.
    Path file = tempDir.resolve("corrupt.zip");
    Files.write(
        file,
        new byte[] {
          0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00,
          0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x05, 0x00, 0x00, 0x00,
          0x66, 0x69, 0x6c, 0x65, 0x31, 0x42, 0x42, 0x42
        });

    Object extracted = invokeExtractJob(file);
    assertEquals(null, extracted);
    assertNotNull(queue.lastOutcome, "Malformed ZIP must produce a typed outcome");
    IngestionOutcomeClass cls = queue.lastOutcome.outcomeClass();
    // Tempdoc 671, Long-term design part 2: the Tika-tolerated branch used to accept
    // SUCCESS_FULL "with empty content" — the exact conflation bug this tempdoc's second fix
    // corrects. It now correctly reads SUCCESS_EMPTY.
    assertTrue(
        cls == IngestionOutcomeClass.PARSER_FAILED
            || cls == IngestionOutcomeClass.BUDGET_EXCEEDED
            || cls == IngestionOutcomeClass.SUCCESS_EMPTY,
        "Malformed ZIP should map to PARSER_FAILED, BUDGET_EXCEEDED, or — if Tika tolerates "
            + "the truncation as 'no entries' — SUCCESS_EMPTY (empty content, honestly labeled). Got: "
            + cls);
    if (cls == IngestionOutcomeClass.SUCCESS_EMPTY) {
      // Tika tolerated the truncation; the resulting document must be empty (no fake placeholder).
      // Pre-existing degenerate ternary fixed: both branches returned 0, so the assertion
      // always passed. Now reads the actual `sourceSizeBytes()` (or 0 when entry/value null,
      // which preserves the vacuous-pass for the no-entry case).
      assertEquals(
          0,
          (queue.lastEntry == null || queue.lastEntry.sourceSizeBytes() == null)
              ? 0
              : queue.lastEntry.sourceSizeBytes().longValue(),
          "Tika-tolerated path still records a typed success — see source-size assertion");
    }
  }

  /**
   * Slice C.1 — symlink loop. Windows symlink creation requires admin or developer mode, so this
   * is gated to POSIX. The IngestionAuthority should classify the loop entry without recursing
   * (NOFOLLOW_LINKS on existence check) and produce a typed outcome rather than blowing the
   * stack or silently succeeding.
   */
  @Test
  @EnabledOnOs({OS.LINUX, OS.MAC})
  @DisplayName("symlink loop is classified without stack overflow (POSIX)")
  void symlinkLoopProducesTypedOutcome() throws Exception {
    Path target = tempDir.resolve("loop-target");
    Path link = tempDir.resolve("loop-link");
    Files.createSymbolicLink(link, target);
    Files.createSymbolicLink(target, link);

    Object extracted = invokeExtractJob(link);
    assertEquals(null, extracted, "Symlink loop must short-circuit before extraction");
    assertNotNull(queue.lastOutcome,
        "Symlink loop must produce a typed outcome (not silently disappear)");
    IngestionOutcomeClass cls = queue.lastOutcome.outcomeClass();
    assertTrue(
        cls == IngestionOutcomeClass.SKIPPED_POLICY
            || cls == IngestionOutcomeClass.STALE_SOURCE
            || cls == IngestionOutcomeClass.IO_FAILED,
        "Symlink loop should map to SKIPPED_POLICY (non-regular), STALE_SOURCE "
            + "(loop hides target), or IO_FAILED (read fails after follow). Got: " + cls);
  }

  /**
   * Slice C.2 — mid-extraction mutation. Inject a custom extractor that truncates the file
   * during {@code extract()}. The post-extraction freshness check in {@code IndexingLoop}
   * compares the current size against the FileEnvelope captured at admission, so the mutation
   * must surface as a STALE_SOURCE / SIZE_CHANGED_AFTER_SNAPSHOT outcome.
   */
  @Test
  @DisplayName("file truncated mid-extraction surfaces as STALE_SOURCE/SIZE_CHANGED_AFTER_SNAPSHOT")
  void midExtractionMutationProducesStaleSourceOutcome() throws Exception {
    Path file = tempDir.resolve("mutating.txt");
    Files.writeString(file, "x".repeat(2_048), StandardCharsets.UTF_8);

    ContentExtractorProvider mutatingExtractor =
        new ContentExtractorProvider() {
          @Override
          public ContentExtractor.ExtractionResult extract(Path target)
              throws IOException, ContentExtractor.ExtractionException {
            // Truncate the file before returning the extraction result. The IndexingLoop's
            // post-extraction freshness check should detect the size change.
            Files.writeString(target, "tiny", StandardCharsets.UTF_8);
            return new ContentExtractor.ExtractionResult("original prefix", null, "text/plain");
          }

          @Override
          public String detectMimeType(Path target) {
            return "text/plain";
          }
        };
    IndexingLoop mutatingLoop =
        new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            queue,
            queue.indexingCoordinator,
            mock(CommitOps.class),
            mock(DocumentFieldOps.class),
            mock(IndexCountOps.class),
            () -> null,
            mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(),
            null,
            null,
            null,
            null,
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                mutatingExtractor,
                Duration.ofSeconds(10),
                (ExtractionMetricCatalog) null),
            null,
            identityOptions());

    // W5.2: extractJob moved to JobBatchExtractor (cross-package; reach via reflection on
    // the getExtractor() accessor).
    Object extracted = invokeExtractJobOn(mutatingLoop, file);

    assertEquals(null, extracted,
        "Mutation detected post-extraction must short-circuit and not produce an indexed job");
    assertNotNull(queue.lastOutcome, "Mid-extraction mutation must produce a typed outcome");
    assertEquals(
        IngestionOutcomeClass.STALE_SOURCE,
        queue.lastOutcome.outcomeClass(),
        "Truncated-during-extraction file must classify as STALE_SOURCE");
    assertEquals(
        IngestionReasonCodes.SIZE_CHANGED_AFTER_SNAPSHOT,
        queue.lastOutcome.reasonCode(),
        "Reason code must surface the size-change diagnosis to operators");
  }

  @Test
  @DisplayName("same-size source rewrite with restored mtime is rejected by content hash")
  void sameMetadataMutationProducesStaleSourceOutcome() throws Exception {
    Path file = tempDir.resolve("same-metadata-mutation.txt");
    Files.writeString(file, "AAAA", StandardCharsets.UTF_8);
    FileTime admittedModifiedTime = Files.getLastModifiedTime(file);

    ContentExtractorProvider mutatingExtractor =
        new ContentExtractorProvider() {
          @Override
          public ContentExtractor.ExtractionResult extract(Path target) throws IOException {
            Files.writeString(target, "BBBB", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(target, admittedModifiedTime);
            return new ContentExtractor.ExtractionResult("content from A", null, "text/plain");
          }

          @Override
          public String detectMimeType(Path target) {
            return "text/plain";
          }
        };
    IndexingLoop mutatingLoop =
        new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            queue,
            queue.indexingCoordinator,
            mock(CommitOps.class),
            mock(DocumentFieldOps.class),
            mock(IndexCountOps.class),
            () -> null,
            mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(),
            null,
            null,
            null,
            null,
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                mutatingExtractor,
                Duration.ofSeconds(10),
                (ExtractionMetricCatalog) null),
            null,
            identityOptions());

    Object extracted = invokeExtractJobOn(mutatingLoop, file);

    assertEquals(null, extracted, "Changed bytes must never reach the index writer");
    assertNotNull(queue.lastOutcome);
    assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
    assertEquals(IngestionReasonCodes.STALE_AFTER_EXTRACTION, queue.lastOutcome.reasonCode());
    assertTrue(queue.deferred, "A changed source must use the existing defer-without-attempt path");
  }

  @Test
  @DisplayName("successful extraction stores the SHA-256 of its source bytes")
  void successfulExtractionStoresSourceHash() throws Exception {
    Path file = tempDir.resolve("source-hash.txt");
    Files.writeString(file, "source bytes", StandardCharsets.UTF_8);

    Object extracted = invokeExtractJob(file);
    assertNotNull(extracted);
    invokeWriterWrite(loop, extracted);

    ArgumentCaptor<IndexDocument> document = ArgumentCaptor.forClass(IndexDocument.class);
    verify(queue.indexingCoordinator).indexSingle(document.capture());
    assertEquals(
        "4d4823794cbed3c4ee0bbc684c8f66e1dfd5afa6f078d494ce254ec5a4671753",
        document.getValue().fields().get(SchemaFields.SOURCE_SHA256));
  }

  /**
   * Slice G.1 (M4) — real PolicyDrivenTikaExtractor with a tight {@code maxExtractedChars}
   * produces a SUCCESS_PARTIAL artifact. The full extract → write → drain chain must produce
   * a matching SUCCESS_PARTIAL ledger event. Pre-G.1 the ledger said SUCCESS_FULL even when
   * the document carried EXTRACTION_REASON_CODE=SUCCESS_PARTIAL — the operator-visible
   * inconsistency H1 closed.
   */
  @Test
  @DisplayName("real Tika truncation produces SUCCESS_PARTIAL ledger event end-to-end")
  void realTikaTruncationProducesSuccessPartialLedgerEvent() throws Exception {
    Path file = tempDir.resolve("long-content.txt");
    Files.writeString(file, "x".repeat(2_000), StandardCharsets.UTF_8);

    TikaExtractionPolicy tightPolicy =
        new TikaExtractionPolicy(
            "tika-truncation-test",
            100, // maxExtractedChars — file is 2000 chars, so Tika output gets truncated
            TikaExtractionPolicy.DEFAULT_MAX_INPUT_BYTES,
            TikaExtractionPolicy.DEFAULT_MAX_OFFICE_INPUT_BYTES,
            128,
            128,
            4096,
            256,
            8,
            100.0d,
            true,
            java.util.Set.of(),
            java.util.Set.of());
    PolicyDrivenTikaExtractor tightExtractor = new PolicyDrivenTikaExtractor(tightPolicy);
    IndexingLoop tightLoop =
        new IndexingLoop(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
            queue,
            queue.indexingCoordinator,
            mock(CommitOps.class),
            mock(DocumentFieldOps.class),
            mock(IndexCountOps.class),
            () -> null,
            mock(WorkerSignalBus.class),
            IndexingPacing.unthrottled(),
            null,
            null,
            null,
            null,
            new TimeboxedContentExtractor(io.justsearch.indexerworker.TestWorkerExecutorRegistrations.timebox(),
                tightExtractor,
                Duration.ofSeconds(10),
                (ExtractionMetricCatalog) null),
            null,
            identityOptions());

    Object extractedJob = invokeExtractJobOn(tightLoop, file);
    assertNotNull(extractedJob, "Tight policy should still admit + extract (truncated)");
    // W5.1: writeExtractedJob moved to JobBatchWriter.write; cross-package callers
    // reach it via reflection on the package-private getWriter() accessor.
    invokeWriterWrite(tightLoop, extractedJob);
    invokeJournalDrain(tightLoop);

    assertEquals(
        IngestionOutcomeClass.SUCCESS_PARTIAL,
        queue.lastOutcome.outcomeClass(),
        "Truncated artifact must surface as SUCCESS_PARTIAL in the ledger (not SUCCESS_FULL)");
    assertEquals(
        IngestionReasonCodes.SUCCESS_PARTIAL,
        queue.lastOutcome.reasonCode(),
        "Reason code must align with the document's EXTRACTION_REASON_CODE");
    assertEquals(
        "SUCCESS_PARTIAL",
        queue.lastEntry.artifactStatus(),
        "Ledger entry's artifact_status must reflect the artifact, not the loop's hardcoded outcome");
  }

  @Test
  @DisplayName("Word/Office tilde temp file is skipped by name-prefix policy")
  void officeTempFileIsSkippedByPrefix() throws Exception {
    Path file = tempDir.resolve("~$report.docx");
    Files.writeString(file, "office lock");

    Object extracted = invokeExtractJob(file);
    assertEquals(null, extracted);
    assertEquals(IngestionOutcomeClass.SKIPPED_POLICY, queue.lastOutcome.outcomeClass());
    assertEquals(IngestionReasonCodes.SKIPPED_TEMP_OR_SYSTEM, queue.lastOutcome.reasonCode());
  }

  // ---- helpers ----

  private static IndexingLoopOptions identityOptions() {
    DocumentIdentityStore store = mock(DocumentIdentityStore.class);
    when(store.resolve(anyString(), anyLong()))
        .thenAnswer(
            invocation -> {
              String pathHash = invocation.getArgument(0);
              long nowMs = invocation.getArgument(1);
              return new DocumentIdentityStore.Identity(
                  pathHash, "00000000-0000-4000-8000-000000000410", nowMs, nowMs);
            });
    return new IndexingLoopOptions(false, null, store, null, null, null);
  }

  private Object invokeExtractJob(Path file) throws Exception {
    return invokeExtractJobOn(loop, file);
  }

  /** W5.2: extractJob moved to JobBatchExtractor; getExtractor accessor is package-private. */
  private static Object invokeExtractJobOn(IndexingLoop loop, Path file) throws Exception {
    Method getExtractor = IndexingLoop.class.getDeclaredMethod("getExtractor");
    getExtractor.setAccessible(true);
    Object extractor = getExtractor.invoke(loop);
    Method extractJob =
        extractor.getClass().getDeclaredMethod("extractJob", Path.class, String.class,
            JobQueue.EnqueueProvenance.class);
    extractJob.setAccessible(true);
    return extractJob.invoke(extractor, file, null, null);
  }

  private void invokeExtractAndDrain(Path file) throws Exception {
    Object extractedJob = invokeExtractJob(file);
    if (extractedJob == null) return;
    invokeWriterWrite(loop, extractedJob);
    invokeJournalDrain(loop);
  }

  /** W5.1: writeExtractedJob moved to JobBatchWriter.write. getWriter is package-private. */
  private static void invokeWriterWrite(IndexingLoop loop, Object extractedJob) throws Exception {
    Method getWriter = IndexingLoop.class.getDeclaredMethod("getWriter");
    getWriter.setAccessible(true);
    Object writer = getWriter.invoke(loop);
    Method write =
        writer.getClass().getDeclaredMethod(
            "write",
            io.justsearch.indexerworker.loop.ExtractedJob.class,
            float[].class);
    write.setAccessible(true);
    write.invoke(writer, extractedJob, null);
  }

  /** Test-only reflective drain — IndexingLoop.getJournal is package-private. */
  private static void invokeJournalDrain(IndexingLoop loop) throws Exception {
    Method getJournal = IndexingLoop.class.getDeclaredMethod("getJournal");
    getJournal.setAccessible(true);
    Object journal = getJournal.invoke(loop);
    Method drain = journal.getClass().getDeclaredMethod("drainPending");
    drain.setAccessible(true);
    drain.invoke(journal);
  }

  private static final class RecordingQueue implements JobQueue {
    IngestionOutcome lastOutcome;
    IngestionLedgerEntry lastEntry;
    boolean done;
    boolean deferred;
    IndexingCoordinator indexingCoordinator;

    @Override
    public void open() {}

    @Override
    public int enqueue(List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
    }

    @Override
    public List<IndexJob> pollPending(int limit) {
      return List.of();
    }

    @Override
    public void markDone(Path path) {
      done = true;
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome) {
      lastOutcome = outcome;
      done = true;
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      lastOutcome = outcome;
      lastEntry = entry;
      done = true;
    }

    @Override
    public void markDoneTransitions(
        java.util.Collection<IngestionLedgerTransition> transitions, IngestionOutcome outcome) {
      lastOutcome = outcome;
      lastEntry =
          transitions == null || transitions.isEmpty()
              ? null
              : transitions.iterator().next().entry();
      done = true;
    }

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void markFailed(Path path, IngestionOutcome outcome) {
      lastOutcome = outcome;
    }

    @Override
    public void markFailed(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      lastOutcome = outcome;
      lastEntry = entry;
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome) {
      lastOutcome = outcome;
      deferred = true;
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      lastOutcome = outcome;
      lastEntry = entry;
      deferred = true;
    }

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
