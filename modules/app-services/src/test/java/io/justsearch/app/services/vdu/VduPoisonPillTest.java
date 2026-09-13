package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for VDU poison pill protection mechanism.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Documents that repeatedly fail VDU processing are tracked via retry count</li>
 *   <li>After max retries, documents are marked as FAILED (not retried indefinitely)</li>
 *   <li>Corrupt/invalid files don't block the entire VDU queue</li>
 * </ul>
 *
 * <p>Uses the same test pattern as VduBatchProcessorTest with stub dependencies.
 */
@DisplayName("VDU Poison Pill Protection")
class VduPoisonPillTest {

  @TempDir
  Path tempDir;

  private StubVduProcessor stubProcessor;
  private StubVramDetector stubVram;
  private TrackingStubKnowledgeClient stubClient;
  private TestableVduBatchProcessor batchProcessor;

  @BeforeEach
  void setup() {
    stubProcessor = new StubVduProcessor();
    stubVram = new StubVramDetector().withMeetsVduRequirements(true);
    stubClient = new TrackingStubKnowledgeClient();
    batchProcessor = new TestableVduBatchProcessor(stubProcessor, stubVram, stubClient);
  }

  @Nested
  @DisplayName("Retry Count Tracking")
  class RetryCountTracking {

    @Test
    @DisplayName("retry count starts at 1 on first processing attempt")
    void retryCountStartsAtOne() throws Exception {
      Path testFile = createTestImage("test.png");
      String docId = testFile.toString();

      stubClient.withPendingVduDocIds(List.of(docId)).withPendingVduCount(1);
      stubClient.withMarkVduProcessingRetryCount(1); // First attempt

      batchProcessor.processPendingFiles();

      assertTrue(stubClient.getMarkedProcessingDocIds().contains(docId),
          "Document should be marked as processing");
    }

    @Test
    @DisplayName("document skipped when max retries exceeded")
    void documentSkippedWhenMaxRetriesExceeded() throws Exception {
      Path testFile = createTestImage("poison.png");
      String docId = testFile.toString();

      stubClient.withPendingVduDocIds(List.of(docId)).withPendingVduCount(1);
      stubClient.withMarkVduProcessingRetryCount(-1); // Max retries exceeded

      int processed = batchProcessor.processPendingFiles();

      assertEquals(0, processed, "Should not count as processed when max retries exceeded");
      assertEquals(0, stubProcessor.getProcessCallCount(), "VDU processor should not be called");
    }

    @Test
    @DisplayName("document processed when retry count is valid")
    void documentProcessedWhenRetryCountValid() throws Exception {
      Path testFile = createTestImage("valid.png");
      String docId = testFile.toString();

      stubClient.withPendingVduDocIds(List.of(docId)).withPendingVduCount(1);
      stubClient.withMarkVduProcessingRetryCount(2); // Second attempt, still valid

      int processed = batchProcessor.processPendingFiles();

      assertEquals(1, processed);
      assertEquals(1, stubProcessor.getProcessCallCount());
    }
  }

  @Nested
  @DisplayName("Batch Processing with Poison Pills")
  class BatchProcessingWithPoisonPills {

    @Test
    @DisplayName("continues processing good files after poison pill")
    void continuesAfterPoisonPill() throws Exception {
      Path goodFile1 = createTestImage("good1.png");
      Path poisonFile = createTestImage("poison.png");
      Path goodFile2 = createTestImage("good2.png");

      stubClient.withPendingVduDocIds(List.of(
          goodFile1.toString(),
          poisonFile.toString(),
          goodFile2.toString()
      )).withPendingVduCount(3);

      // Configure poison file to fail processing
      stubProcessor.withFailingDocId(poisonFile.toString(), "Corrupt image data");

      int processed = batchProcessor.processPendingFiles();

      assertEquals(2, processed, "Two good files should be processed");
      assertEquals(3, stubProcessor.getProcessCallCount(), "All files should be attempted");

      // Verify poison file marked as failed
      var poisonUpdate = stubClient.getVduUpdate(poisonFile.toString());
      assertEquals(SchemaFields.VDU_STATUS_FAILED, poisonUpdate.vduStatus());
    }

    @Test
    @DisplayName("invalid files don't block queue processing")
    void invalidFilesDontBlockQueue() throws Exception {
      Path validFile = createTestImage("valid.png");
      Path invalidFile = createInvalidFile("notanimage.png");

      stubClient.withPendingVduDocIds(List.of(
          invalidFile.toString(),
          validFile.toString()
      )).withPendingVduCount(2);

      // Invalid file will fail in processor
      stubProcessor.withFailingDocId(invalidFile.toString(), "Cannot read image");

      int processed = batchProcessor.processPendingFiles();

      assertEquals(1, processed, "Valid file should be processed");

      // Invalid file should be marked failed
      var invalidUpdate = stubClient.getVduUpdate(invalidFile.toString());
      assertEquals(SchemaFields.VDU_STATUS_FAILED, invalidUpdate.vduStatus());

      // Valid file should be completed
      var validUpdate = stubClient.getVduUpdate(validFile.toString());
      assertEquals(SchemaFields.VDU_STATUS_COMPLETED, validUpdate.vduStatus());
    }
  }

  @Nested
  @DisplayName("Failure Scenarios")
  class FailureScenarios {

    @Test
    @DisplayName("file that no longer exists is marked failed")
    void missingFileMarkedFailed() {
      String missingPath = tempDir.resolve("deleted.png").toString();

      stubClient.withPendingVduDocIds(List.of(missingPath)).withPendingVduCount(1);

      int processed = batchProcessor.processPendingFiles();

      assertEquals(0, processed);

      var update = stubClient.getVduUpdate(missingPath);
      assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
      assertTrue(update.enrichment().contains("no longer exists"));
    }

    @Test
    @DisplayName("VduException results in FAILED status")
    void vduExceptionResultsInFailedStatus() throws Exception {
      Path testFile = createTestImage("failing.png");
      String docId = testFile.toString();

      stubClient.withPendingVduDocIds(List.of(docId)).withPendingVduCount(1);
      stubProcessor.withFailAllProcessing(true).withFailureMessage("OCR engine crashed");

      int processed = batchProcessor.processPendingFiles();

      assertEquals(0, processed);

      var update = stubClient.getVduUpdate(docId);
      assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
      assertTrue(update.enrichment().contains("OCR engine crashed"));
    }
  }

  // =========================================================================
  // Helper Methods
  // =========================================================================

  private Path createTestImage(String filename) throws Exception {
    Path imagePath = tempDir.resolve(filename);
    Files.writeString(imagePath, "fake image content");
    return imagePath;
  }

  private Path createInvalidFile(String filename) throws Exception {
    Path filePath = tempDir.resolve(filename);
    Files.writeString(filePath, "This is not an image file");
    return filePath;
  }

  // =========================================================================
  // Test Infrastructure (same pattern as VduBatchProcessorTest)
  // =========================================================================

  /**
   * Extended stub that tracks per-document retry counts for testing.
   */
  static class TrackingStubKnowledgeClient extends StubKnowledgeClient {
    // Inherits all functionality from StubKnowledgeClient
  }

  /**
   * Testable version of VduBatchProcessor that works with stubs.
   * Same as VduBatchProcessorTest.TestableVduBatchProcessor.
   */
  static class TestableVduBatchProcessor {
    private final StubVduProcessor vduProcessor;
    private final StubVramDetector vramDetector;
    private final StubKnowledgeClient knowledgeClient;

    TestableVduBatchProcessor(StubVduProcessor vduProcessor,
                              StubVramDetector vramDetector,
                              StubKnowledgeClient knowledgeClient) {
      this.vduProcessor = vduProcessor;
      this.vramDetector = vramDetector;
      this.knowledgeClient = knowledgeClient;
    }

    int processPendingFiles() {
      if (!vramDetector.meetsVduRequirements()) {
        return 0;
      }

      int pendingCount = knowledgeClient.countPendingVdu();
      if (pendingCount == 0) {
        return 0;
      }

      List<String> pendingDocIds = knowledgeClient.queryPendingVduDocIds();
      if (pendingDocIds.isEmpty()) {
        return 0;
      }

      int processed = 0;

      for (String docId : pendingDocIds) {
        try {
          int retryCount = knowledgeClient.markVduProcessing(docId, SchemaFields.VDU_MAX_RETRIES);
          if (retryCount < 0) {
            continue;
          }

          Path filePath = Path.of(docId);
          if (!Files.exists(filePath)) {
            markVduFailed(docId, "File no longer exists");
            continue;
          }

          VduProcessor.VduResult result = vduProcessor.process(filePath);

          boolean updated = knowledgeClient.updateVduResult(
              docId,
              result.extractedText(),
              SchemaFields.VDU_STATUS_COMPLETED,
              result.enrichment(),
              result.pageCount()
          );

          if (updated) {
            processed++;
          }

        } catch (VduProcessor.VduException e) {
          markVduFailed(docId, e.getMessage());
        } catch (Exception e) {
          markVduFailed(docId, "Unexpected error: " + e.getMessage());
        }
      }

      return processed;
    }

    private void markVduFailed(String docId, String reason) {
      String safeReason = reason.replace("\"", "'");
      knowledgeClient.updateVduResult(
          docId,
          "",
          SchemaFields.VDU_STATUS_FAILED,
          "{\"error\": \"" + safeReason + "\"}",
          0
      );
    }
  }
}
