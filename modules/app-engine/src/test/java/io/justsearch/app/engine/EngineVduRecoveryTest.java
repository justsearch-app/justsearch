/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.util.PathNormalizer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.vdu.VduRecoverySystemTest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> Its subject was the
 * VDU crash-recovery contract: a document left in {@code vdu_status=PROCESSING} — the state a
 * process death mid-extraction leaves behind — must be reset to {@code PENDING} by
 * {@code recoverVduProcessing}, exactly once per stuck document and never for a document that is
 * not stuck. It spawned a Worker purely to get a real index behind that RPC. The recovery itself
 * is index state, so the whole contract carries over: index an image (which routes to
 * {@code PENDING}), mark it {@code PROCESSING}, observe it leave the pending list, recover, observe
 * it return.
 *
 * <p><b>Isolation is stronger here, not weaker.</b> The retired test hand-deleted {@code index/}
 * and {@code job_queue.db} under a shared temp directory in {@code @BeforeEach}, tolerating files
 * it could not delete ({@code log.debug("Could not delete …")}). That best-effort clean was
 * load-bearing for two of the three assertions — "recovers exactly 1", "recovers exactly 3", and
 * "returns 0 when nothing is stuck" are all counts over the whole index. Here each test gets its
 * own {@code @TempDir} and its own {@link EngineTestHarness}, so the index is empty by construction
 * rather than by deletion, and the exact counts mean what they say.
 *
 * <p><b>Two things changed shape.</b>
 *
 * <ol>
 *   <li><b>The image fixture.</b> The retired test drew a 100x100 PNG with {@code Graphics2D} and
 *       wrote the word "TEST" into it. Whether that document reached {@code PENDING} therefore
 *       depended on whether OCR read the glyphs — it passed because OCR did not. This uses
 *       {@link EngineTestImages#minimalPng()}, a 1x1 image with no text by construction, which
 *       lands on {@code VisualRoutingDecision}'s extraction-dropout branch deterministically. See
 *       that class's javadoc for the routing citation.
 *   <li><b>Doc-id derivation.</b> The retired test reimplemented the Worker's path normalisation
 *       inline (lowercase on Windows). This calls the production normaliser directly,
 *       {@code PathNormalizer.normalizeKey} (PathNormalizer.java:50-52), which is the same rule
 *       plus the absolutize+normalize steps the ingestion key actually uses — so a mismatch is now
 *       a real defect rather than a divergence between the test's copy of the rule and the
 *       Worker's.
 * </ol>
 *
 * <p><b>What was dropped, and why.</b> {@code worker.spawnWorker()}'s returned PID (logged, never
 * asserted), the {@code mmf.keepAlive()} heartbeat and the {@code awaitPort} handshake — all three
 * addressed the second process, which no longer exists.
 */
@DisplayName("Engine VDU recovery (in-process)")
@Timeout(300)
final class EngineVduRecoveryTest {

  @TempDir Path tempDir;

  private EngineTestHarness harness;
  private Path testImageDir;

  @BeforeEach
  void startEngine() throws Exception {
    testImageDir = tempDir.resolve("test-images");
    Files.createDirectories(testImageDir);
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(), "the engine must be healthy before the VDU tests");
  }

  @AfterEach
  void stopEngine() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  @Test
  @DisplayName("recovers documents stuck in PROCESSING state")
  void recoversStuckProcessingDocuments() throws Exception {
    // 1. Create a test image.
    Path testImage = createTestImage("stuck-doc.png");
    String docId = PathNormalizer.normalizeKey(testImage);

    // 2. Submit for indexing.
    assertEquals(
        1, harness.client().submitBatch(List.of(testImage)).getAcceptedCount(), "should accept 1 file");

    // 3. Wait for indexing to complete.
    assertTrue(harness.awaitIndexed(1, 120_000), "document should be indexed");

    // 4. Images with no extractable text route to PENDING.
    assertTrue(awaitPending(docId, 30_000), "document should be pending VDU after indexing");

    // 5. Inject PROCESSING (what a crash mid-extraction leaves behind).
    int retryCount = harness.client().markVduProcessing(docId, 3);
    assertTrue(retryCount >= 0, "should be able to mark VDU PROCESSING, got " + retryCount);

    // 6. PROCESSING is not PENDING.
    assertTrue(
        awaitNotPending(docId, 30_000), "a PROCESSING document should not appear in the pending list");

    // 7. Recover.
    assertEquals(1, harness.client().recoverVduProcessing(), "should recover exactly 1 document");

    // 8. Back to PENDING.
    assertTrue(awaitPending(docId, 30_000), "document should be PENDING again after recovery");
  }

  @Test
  @DisplayName("recovery returns 0 when no documents stuck")
  void recoveryReturnsZeroWhenNoneStuck() {
    // Nothing indexed, so nothing can be stuck. The empty index is guaranteed by this test's own
    // @TempDir, not by a best-effort directory clean.
    assertEquals(
        0,
        harness.client().recoverVduProcessing(),
        "should recover 0 documents when none are stuck");
  }

  @Test
  @DisplayName("recovers multiple stuck documents")
  void recoversMultipleStuckDocuments() throws Exception {
    Path img1 = createTestImage("stuck-1.png");
    Path img2 = createTestImage("stuck-2.png");
    Path img3 = createTestImage("stuck-3.png");

    String docId1 = PathNormalizer.normalizeKey(img1);
    String docId2 = PathNormalizer.normalizeKey(img2);
    String docId3 = PathNormalizer.normalizeKey(img3);

    assertEquals(
        3,
        harness.client().submitBatch(List.of(img1, img2, img3)).getAcceptedCount(),
        "should accept 3 files");
    assertTrue(harness.awaitIndexed(3, 120_000), "all three documents should be indexed");

    assertTrue(awaitPending(docId1, 30_000), "doc 1 should be pending VDU after indexing");
    assertTrue(awaitPending(docId2, 30_000), "doc 2 should be pending VDU after indexing");
    assertTrue(awaitPending(docId3, 30_000), "doc 3 should be pending VDU after indexing");

    assertTrue(harness.client().markVduProcessing(docId1, 3) >= 0, "doc 1 should mark PROCESSING");
    assertTrue(harness.client().markVduProcessing(docId2, 3) >= 0, "doc 2 should mark PROCESSING");
    assertTrue(harness.client().markVduProcessing(docId3, 3) >= 0, "doc 3 should mark PROCESSING");

    assertTrue(awaitNotPending(docId1, 30_000), "doc 1 should not be pending after PROCESSING");
    assertTrue(awaitNotPending(docId2, 30_000), "doc 2 should not be pending after PROCESSING");
    assertTrue(awaitNotPending(docId3, 30_000), "doc 3 should not be pending after PROCESSING");

    assertEquals(
        3, harness.client().recoverVduProcessing(), "should recover all 3 documents");

    assertTrue(awaitPending(docId1, 30_000), "doc 1 should be PENDING after recovery");
    assertTrue(awaitPending(docId2, 30_000), "doc 2 should be PENDING after recovery");
    assertTrue(awaitPending(docId3, 30_000), "doc 3 should be PENDING after recovery");
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private Path createTestImage(String filename) throws IOException {
    Path imagePath = testImageDir.resolve(filename);
    Files.write(imagePath, EngineTestImages.minimalPng());
    return imagePath;
  }

  /** Polls until {@code docId} appears in the pending-VDU list; the searcher refreshes async. */
  private boolean awaitPending(String docId, long timeoutMs) throws InterruptedException {
    return awaitPendingContains(docId, timeoutMs, true);
  }

  /** Polls until {@code docId} is absent from the pending-VDU list. */
  private boolean awaitNotPending(String docId, long timeoutMs) throws InterruptedException {
    return awaitPendingContains(docId, timeoutMs, false);
  }

  private boolean awaitPendingContains(String docId, long timeoutMs, boolean expectPresent)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      try {
        if (harness.client().queryPendingVduDocIds(100).contains(docId) == expectPresent) {
          return true;
        }
      } catch (RuntimeException stillSettling) {
        // A query during a generation swap can fail; the loop re-reads.
      }
      Thread.sleep(200);
    }
    // A doc-id mismatch and a genuine status defect look identical from a bare boolean, so the
    // observed list is printed alongside the failure. Test console output is the test's report —
    // the reason `ruleset-tests.xml` drops SystemPrintln.
    System.err.println(
        "VDU pending wait timed out: docId="
            + docId
            + " expectPresent="
            + expectPresent
            + " pendingList="
            + describePending());
    return false;
  }

  private String describePending() {
    try {
      return harness.client().queryPendingVduDocIds(100).toString();
    } catch (RuntimeException unreadable) {
      return "<unreadable: " + unreadable + ">";
    }
  }
}
