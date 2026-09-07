/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.BatchResponse;
import io.justsearch.ipc.DocumentContent;
import io.justsearch.ipc.SyncDirectoryResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.CompleteIndexingWorkflowE2ETest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> It spawned a Worker
 * process, discovered its gRPC port through the memory-mapped signal file, and drove the full
 * document lifecycle across the wire: create → index → search → modify → re-index → delete →
 * sync-prune, plus batch ingest, nested-directory discovery via sync, image admission, a large
 * file, an unsupported file, mass deletion, and concurrent index-while-search. <em>None</em> of
 * that is a property of the second process. Every one of those is a property of the index half:
 * what got indexed, what is findable, what got pruned, and whether the index survives concurrent
 * use. That half is now composed in-process by {@link EngineRoot}, so all eight tests carry over
 * with their subjects intact.
 *
 * <p><b>What was dropped, and why.</b> The retired test's {@code mmf.keepAlive()} calls (in
 * {@code @BeforeEach} and scattered through every method) fed the suicide-pact heartbeat that kept
 * a spawned Worker from self-terminating when its parent vanished. There is no second process to
 * keep alive and no watchdog to feed, so those calls are gone rather than translated. The
 * {@code awaitPort} / {@code isHealthy}-before-tests handshake collapses into
 * {@link EngineTestHarness#start}, which either returns a working client or throws.
 *
 * <p><b>Two places where this is deliberately more precise than the original, stated rather than
 * smoothed over.</b>
 *
 * <ol>
 *   <li><b>Doc-count baselines are captured before the work is enqueued, not after.</b> The retired
 *       test read {@code getDocCount()} <em>after</em> {@code submitBatch}/{@code syncDirectory} in
 *       tests 3, 7 and 8 and then waited for {@code that + n}. If any submitted document had
 *       already been indexed by the time the count was read, the target was unreachable and the
 *       wait would burn its full timeout. Reading the baseline first makes the target exactly "n
 *       more documents than before this step", which is what the step actually claims.
 *   <li><b>The concurrency test asserts instead of logging.</b> The retired test 8 caught every
 *       exception from both worker threads, logged it at WARN and let the test pass; its only
 *       "assertion" was a final {@code log.info}. That is a test that cannot fail. Here the two
 *       threads record their failures and the test fails on any of them, and the engine must still
 *       be healthy and still serve a search afterwards. This is a strengthening, not a translation
 *       — flagged because it means a red here is new information the old test could not produce.
 * </ol>
 */
@DisplayName("Engine indexing workflow (in-process)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
final class EngineIndexingWorkflowTest {

  /** Unique marker suffix per run, exactly as the retired test used it. */
  private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

  @TempDir static Path tempDir;

  private static EngineTestHarness harness;

  /**
   * The corpus the tests create files in. Deliberately a <em>sibling</em> of the Engine's data
   * directory, never a parent of it: several tests call {@code syncDirectory} on this root, and a
   * sync that walked the Lucene index directory would enqueue the index into itself. The retired
   * test had the same separation ({@code env.getTempDir()/e2e-workflow-data-<id>} beside the
   * worker's own data dir).
   */
  private static Path corpus;

  @BeforeAll
  static void startEngine() throws Exception {
    corpus = tempDir.resolve("corpus");
    Files.createDirectories(corpus);
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(), "the engine must be healthy before the workflow runs");
  }

  @AfterAll
  static void stopEngine() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  // =========================================================================
  // Helpers — the retired test's private helpers, minus the heartbeat
  // =========================================================================

  private static Path createTestFile(String name, String content) throws IOException {
    Path file = corpus.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
    return file;
  }

  private static Path createSubDir(String name) throws IOException {
    Path dir = corpus.resolve(name);
    Files.createDirectories(dir);
    return dir;
  }

  private static long docCount() {
    return harness.status().getCore().getDocCount();
  }

  private static void awaitIndexing(long minDocCount) throws InterruptedException {
    // Supplier form: the failure message re-reads the doc count, which must not run on the
    // happy path.
    assertTrue(
        harness.awaitIndexed(minDocCount, 60_000),
        () ->
            "indexing should complete (expected >= "
                + minDocCount
                + " docs, saw "
                + docCount()
                + ")");
  }

  private static void assertSearchableByMarker(String marker) throws InterruptedException {
    assertTrue(
        harness.awaitSearchable(marker, 30_000),
        () -> "expected to find results for marker '" + marker + "'");
  }

  /**
   * Asserts the given files are no longer <em>in</em> the index, by doc id.
   *
   * <p>A "search for the marker returns zero results" assertion is not sound in this class and one
   * of the conversions proved it: every marker embeds the shared {@link #RUN_ID}, the text analyzer
   * splits {@code massdelete-0-6a6339fa} into tokens, and by test 7 a dozen surviving documents from
   * tests 1-6 carry the {@code RUN_ID} token — so the query matched documents that were never
   * supposed to be deleted, and the "still findable" verdict was about the wrong documents. Test 1
   * only escaped it by running first, with nothing else in the index to bleed in. Doc-id absence is
   * the claim these steps actually make ("the pruned files are gone"), and it cannot be satisfied or
   * defeated by an unrelated document.
   */
  private static void assertDocsGone(List<Path> deleted) throws InterruptedException {
    List<String> docIds = deleted.stream().map(PathNormalizer::normalizeKey).toList();
    long deadline = System.currentTimeMillis() + 30_000;
    List<String> stillPresent = List.of();
    while (System.currentTimeMillis() < deadline) {
      stillPresent =
          harness.client().fetchDocuments(docIds).getDocumentsList().stream()
              .filter(DocumentContent::getFound)
              .map(DocumentContent::getDocId)
              .toList();
      if (stillPresent.isEmpty()) {
        return;
      }
      Thread.sleep(250);
    }
    assertTrue(
        stillPresent.isEmpty(),
        "the pruned files must no longer be in the index; still present: " + stillPresent);
  }

  // =========================================================================
  // Test 1: Complete Document Lifecycle
  // =========================================================================

  @Test
  @Order(1)
  @DisplayName("Document lifecycle: create -> index -> search -> modify -> re-index -> delete -> sync")
  void completeDocumentLifecycle() throws Exception {
    String marker1 = "lifecycle-initial-" + RUN_ID;
    String marker2 = "lifecycle-modified-" + RUN_ID;

    // 1. Create test file.
    Path testFile = createTestFile("lifecycle-test.txt", "Initial content: " + marker1);

    // 2. Index it (the CREATE event).
    long initialCount = docCount();
    BatchResponse submitted = harness.client().submitBatch(List.of(testFile));
    assertEquals(1, submitted.getAcceptedCount(), "the lifecycle file must be accepted");
    awaitIndexing(initialCount + 1);

    // 3. Verify searchable.
    assertSearchableByMarker(marker1);

    // 4. Modify the file's content.
    Files.writeString(testFile, "Modified content: " + marker2);

    // 5. Re-index (the MODIFY event). Deliberately NOT force=true: the retired test submitted
    // without force, so the freshness check's ability to notice a rewritten file is part of what
    // step 6 proves. Forcing would make step 6 pass even if freshness detection were broken.
    long countBeforeModify = docCount();
    harness.client().submitBatch(List.of(testFile));

    // 6. Verify the UPDATED content is searchable.
    assertSearchableByMarker(marker2);

    // 7. The retired test deliberately did not assert that the OLD content disappeared, on the
    // stated grounds that a superseded version can linger in a segment until merge. That
    // judgement is carried over unchanged: marker2 becoming findable is what proves the update
    // landed. The doc count is observed but not asserted for the same reason.
    assertTrue(
        docCount() >= countBeforeModify,
        "a re-index must not lose documents (before=" + countBeforeModify + ")");

    // 8. Delete the file from disk.
    Files.delete(testFile);

    // 9. Sync to detect and prune the orphan.
    SyncDirectoryResponse sync = harness.client().syncDirectory(corpus.toString(), true);
    assertTrue(
        sync.getFilesDeleted() >= 1,
        "sync should prune at least 1 orphan, got " + sync.getFilesDeleted());

    // 10. Verify it is no longer in the index (by doc id — see assertDocsGone).
    assertDocsGone(List.of(testFile));
  }

  // =========================================================================
  // Test 2: Batch Indexing
  // =========================================================================

  @Test
  @Order(2)
  @DisplayName("Batch indexing of multiple files")
  void batchIndexingMultipleFiles() throws Exception {
    int fileCount = 10;
    List<Path> files = new ArrayList<>();
    List<String> markers = new ArrayList<>();

    for (int i = 0; i < fileCount; i++) {
      String marker = "batch-" + i + "-" + RUN_ID;
      markers.add(marker);
      files.add(createTestFile("batch/file-" + i + ".txt", "Batch content: " + marker));
    }

    long initialCount = docCount();
    BatchResponse response = harness.client().submitBatch(files);
    assertEquals(fileCount, response.getAcceptedCount(), "all files should be accepted");

    awaitIndexing(initialCount + fileCount);

    for (String marker : markers) {
      assertSearchableByMarker(marker);
    }
  }

  // =========================================================================
  // Test 3: Nested Directory Structure
  // =========================================================================

  @Test
  @Order(3)
  @DisplayName("Nested directory structure indexing via sync")
  void nestedDirectoryIndexing() throws Exception {
    createSubDir("nested/level1/level2/level3");

    String rootMarker = "nested-root-" + RUN_ID;
    String l1Marker = "nested-l1-" + RUN_ID;
    String l2Marker = "nested-l2-" + RUN_ID;
    String l3Marker = "nested-l3-" + RUN_ID;

    createTestFile("nested/root.txt", "Root level: " + rootMarker);
    createTestFile("nested/level1/l1.txt", "Level 1: " + l1Marker);
    createTestFile("nested/level1/level2/l2.txt", "Level 2: " + l2Marker);
    createTestFile("nested/level1/level2/level3/l3.txt", "Level 3: " + l3Marker);

    // Baseline BEFORE the sync enqueues anything (see the class javadoc, precision note 1).
    long initialCount = docCount();

    Path nestedRoot = corpus.resolve("nested");
    SyncDirectoryResponse sync = harness.client().syncDirectory(nestedRoot.toString(), true);
    assertTrue(
        sync.getFilesAdded() >= 4,
        "sync should find at least 4 files, got " + sync.getFilesAdded());

    awaitIndexing(initialCount + 4);

    assertSearchableByMarker(rootMarker);
    assertSearchableByMarker(l1Marker);
    assertSearchableByMarker(l2Marker);
    assertSearchableByMarker(l3Marker);
  }

  // =========================================================================
  // Test 4: Image admission and VDU demand
  // =========================================================================

  @Test
  @Order(4)
  @DisplayName("Image files are detected and can be queried")
  void imageFilesDetected() throws Exception {
    Path testImage = corpus.resolve("vdu-test-image.png");
    Files.write(testImage, EngineTestImages.minimalPng());

    long initialCount = docCount();
    assertEquals(
        1,
        harness.client().submitBatch(List.of(testImage)).getAcceptedCount(),
        "the image must be accepted");
    awaitIndexing(initialCount + 1);

    // The retired test's own words: "The image might or might not be in pending depending on MIME
    // detection. The key assertion is that the worker didn't crash and the image was indexed."
    // That judgement is carried over verbatim — the pending list is read but not asserted on here;
    // EngineVduRecoveryTest is where the PENDING transition is pinned precisely.
    List<String> pendingVdu = harness.client().queryPendingVduDocIds(100);
    assertNotNull(pendingVdu, "the pending-VDU query must return a list, not null");
    assertTrue(
        harness.client().isHealthy(), "the engine should remain healthy after indexing an image");
  }

  // =========================================================================
  // Test 5: Large File Handling
  // =========================================================================

  @Test
  @Order(5)
  @DisplayName("Large file is indexed without timeout")
  void largeFileIndexing() throws Exception {
    String largeMarker = "largefile-" + RUN_ID;
    StringBuilder content = new StringBuilder();
    content.append("Large file marker: ").append(largeMarker).append("\n\n");
    for (int i = 0; i < 5000; i++) {
      content
          .append("Line ")
          .append(i)
          .append(": Lorem ipsum dolor sit amet, consectetur adipiscing elit. ")
          .append("Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.\n");
    }

    Path largeFile = createTestFile("large-file.txt", content.toString());
    long fileSizeKb = Files.size(largeFile) / 1024;
    assertTrue(fileSizeKb > 100, "the large-file probe must actually be large: " + fileSizeKb + "KB");

    long initialCount = docCount();
    assertEquals(
        1,
        harness.client().submitBatch(List.of(largeFile)).getAcceptedCount(),
        "the large file must be accepted");

    // The retired test allowed 60s here against its 30s default; kept at 60s.
    assertTrue(
        harness.awaitIndexed(initialCount + 1, 60_000),
        "large file should be indexed within 60 seconds");

    assertSearchableByMarker(largeMarker);
  }

  // =========================================================================
  // Test 6: Unsupported File Types
  // =========================================================================

  @Test
  @Order(6)
  @DisplayName("Unsupported file types are handled gracefully")
  void unsupportedFileTypesHandled() throws Exception {
    Path binaryFile = corpus.resolve("binary.bin");
    byte[] binaryData = new byte[1024];
    for (int i = 0; i < binaryData.length; i++) {
      binaryData[i] = (byte) (i % 256);
    }
    Files.write(binaryFile, binaryData);

    // The property is "does not blow up". Whether a binary is admitted or refused is a policy the
    // retired test explicitly declined to pin (it logged the accepted count and moved on), so the
    // count is not asserted here either — only that the call returns rather than throwing.
    BatchResponse response = harness.client().submitBatch(List.of(binaryFile));
    assertNotNull(response, "submitting an unsupported file must return a response");

    // Let the loop actually attempt the file before checking health: the queue must drain, which
    // is a stronger and less arbitrary wait than the retired test's Thread.sleep(2000).
    assertTrue(
        harness.awaitIndexed(docCount(), 60_000),
        "the queue must drain after an unsupported file rather than wedging");
    assertTrue(
        harness.client().isHealthy(),
        "the engine should remain healthy after an unsupported binary file");
  }

  // =========================================================================
  // Test 7: Mass Deletion via Sync
  // =========================================================================

  @Test
  @Order(7)
  @DisplayName("Sync handles mass file deletion")
  void syncHandlesMassDeletion() throws Exception {
    int fileCount = 15;
    List<Path> files = new ArrayList<>();
    List<String> markers = new ArrayList<>();

    Path massDeleteDir = createSubDir("mass-delete");
    for (int i = 0; i < fileCount; i++) {
      String marker = "massdelete-" + i + "-" + RUN_ID;
      markers.add(marker);
      files.add(createTestFile("mass-delete/file-" + i + ".txt", "Delete test: " + marker));
    }

    // Baseline BEFORE the submit (see the class javadoc, precision note 1).
    long initialCount = docCount();
    assertEquals(
        fileCount,
        harness.client().submitBatch(files).getAcceptedCount(),
        "all mass-delete files must be accepted");
    awaitIndexing(initialCount + fileCount);

    assertSearchableByMarker(markers.get(0));

    for (Path file : files) {
      Files.deleteIfExists(file);
    }

    SyncDirectoryResponse sync = harness.client().syncDirectory(massDeleteDir.toString(), true);
    assertTrue(
        sync.getFilesDeleted() >= fileCount,
        "should prune at least " + fileCount + " orphans, got " + sync.getFilesDeleted());

    // The retired test stopped at the sync count, calling it "authoritative" and noting that
    // searcher refresh lags. The harness polls for the disappearance, so the end-to-end claim —
    // pruned AND no longer findable — is now asserted rather than assumed.
    assertDocsGone(files);
  }

  // =========================================================================
  // Test 8: Concurrent Operations Stability
  // =========================================================================

  @Test
  @Order(8)
  @DisplayName("Concurrent indexing and searching is stable")
  void concurrentOperationsStable() throws Exception {
    assertTrue(harness.client().isHealthy(), "the engine must be healthy entering the concurrency test");

    createSubDir("concurrent");
    List<Path> initialPaths = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      initialPaths.add(
          createTestFile("concurrent/initial-" + i + ".txt", "Initial concurrent content " + i));
    }

    // Baseline BEFORE the submit (see the class javadoc, precision note 1).
    long baseCount = docCount();
    assertEquals(
        3,
        harness.client().submitBatch(initialPaths).getAcceptedCount(),
        "the initial concurrent files must be accepted");
    awaitIndexing(baseCount + 3);

    List<Throwable> failures = new CopyOnWriteArrayList<>();
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Future<?>> futures = new ArrayList<>();

    // Thread 1: keep indexing.
    futures.add(
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < 2; i++) {
                  Path file =
                      createTestFile(
                          "concurrent/new-" + i + ".txt",
                          "New concurrent content " + i + " " + RUN_ID);
                  harness.client().submitBatch(List.of(file));
                  Thread.sleep(200);
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
              } catch (Exception e) {
                failures.add(e);
              }
            }));

    // Thread 2: keep searching.
    futures.add(
        executor.submit(
            () -> {
              try {
                for (int i = 0; i < 10; i++) {
                  harness.client().search("concurrent", 10);
                  Thread.sleep(100);
                }
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failures.add(interrupted);
              } catch (Exception e) {
                failures.add(e);
              }
            }));

    for (Future<?> f : futures) {
      f.get(60, TimeUnit.SECONDS);
    }
    executor.shutdown();
    assertTrue(
        executor.awaitTermination(30, TimeUnit.SECONDS), "the concurrency executor must terminate");

    // Class javadoc, precision note 2: the retired test swallowed these.
    assertTrue(failures.isEmpty(), "concurrent index+search must not raise: " + failures);

    // And the index must still be usable afterwards, which is the actual stability claim.
    assertTrue(harness.client().isHealthy(), "the engine must remain healthy after concurrent use");
    assertTrue(
        harness.awaitSearchable("concurrent", 60_000),
        "search must still serve results after concurrent index+search");
  }
}
