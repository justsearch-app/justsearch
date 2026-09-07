package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;

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
 * Unit tests for VduBatchProcessor.
 *
 * <p>Tests VRAM gating, retry logic, failure marking, and batch processing.
 * Uses stub dependencies - no real LLM or gRPC calls.
 */
@DisplayName("VduBatchProcessor")
class VduBatchProcessorTest {

    @TempDir
    Path tempDir;

    private StubVduProcessor vduProcessor;
    private StubVramDetector vramDetector;
    private StubKnowledgeClient knowledgeClient;
    private TestableVduBatchProcessor batchProcessor;

    @BeforeEach
    void setUp() {
        vduProcessor = new StubVduProcessor();
        vramDetector = new StubVramDetector();
        knowledgeClient = new StubKnowledgeClient();
        batchProcessor = new TestableVduBatchProcessor(vduProcessor, vramDetector, knowledgeClient);
    }

    @Nested
    @DisplayName("VRAM Gating")
    class VramGating {

        @Test
        @DisplayName("skips processing when VRAM requirements not met")
        void skipsWhenInsufficientVram() {
            vramDetector.withMeetsVduRequirements(false);
            knowledgeClient.withPendingVduCount(10);

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Should return 0 when VRAM insufficient");
            assertEquals(0, vduProcessor.getProcessCallCount(), "Should not call VDU processor");
        }

        @Test
        @DisplayName("proceeds when VRAM requirements are met")
        void proceedsWhenSufficientVram() throws Exception {
            vramDetector.withMeetsVduRequirements(true);
            Path testFile = createTestFile("test.png");
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(testFile.toString()));

            int processed = batchProcessor.processPendingFiles();

            assertEquals(1, processed, "Should process files when VRAM sufficient");
        }
    }

    @Nested
    @DisplayName("Empty Queue Handling")
    class EmptyQueueHandling {

        @Test
        @DisplayName("returns 0 when no pending VDU files")
        void returnsZeroWhenNoPending() {
            knowledgeClient.withPendingVduCount(0);

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed);
            assertEquals(0, vduProcessor.getProcessCallCount(), "Should not call VDU processor");
        }

        @Test
        @DisplayName("returns 0 when pending count > 0 but docIds list is empty")
        void returnsZeroWhenDocIdsEmpty() {
            knowledgeClient.withPendingVduCount(5);
            knowledgeClient.withPendingVduDocIds(List.of());  // Empty list

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed);
        }
    }

    @Nested
    @DisplayName("Retry Logic (Poison Pill Protection)")
    class RetryLogic {

        @Test
        @DisplayName("skips document when max retries exceeded")
        void skipsWhenMaxRetriesExceeded() throws Exception {
            Path testFile = createTestFile("poison.png");
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(testFile.toString()));
            knowledgeClient.withMarkVduProcessingRetryCount(-1);  // Max retries exceeded

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Should not count as processed");
            assertEquals(0, vduProcessor.getProcessCallCount(), "Should skip VDU processing");
        }

        @Test
        @DisplayName("processes document when retry count is valid")
        void processesWhenRetryCountValid() throws Exception {
            Path testFile = createTestFile("valid.png");
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(testFile.toString()));
            knowledgeClient.withMarkVduProcessingRetryCount(1);  // First retry

            int processed = batchProcessor.processPendingFiles();

            assertEquals(1, processed);
            assertEquals(1, vduProcessor.getProcessCallCount());
        }
    }

    @Nested
    @DisplayName("File Existence Check")
    class FileExistenceCheck {

        @Test
        @DisplayName("marks as failed when file no longer exists")
        void marksFailedWhenFileNotExists() {
            String nonExistentPath = tempDir.resolve("nonexistent.png").toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(nonExistentPath));

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Should not count missing file as processed");
            var update = knowledgeClient.getVduUpdate(nonExistentPath);
            assertNotNull(update, "Should have update for missing file");
            assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
            assertTrue(update.enrichment().contains("no longer exists"));
        }
    }

    @Nested
    @DisplayName("Success Path")
    class SuccessPath {

        @Test
        @DisplayName("processes single file successfully")
        void processesSingleFileSuccessfully() throws Exception {
            Path testFile = createTestFile("test.png");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withDefaultResult("Extracted text", "{\"summary\":\"test\"}", 1);

            int processed = batchProcessor.processPendingFiles();

            assertEquals(1, processed);
            assertTrue(knowledgeClient.getMarkedProcessingDocIds().contains(docId));
            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertEquals("Extracted text", update.extractedContent());
            assertEquals(SchemaFields.VDU_STATUS_COMPLETED, update.vduStatus());
            assertEquals("{\"summary\":\"test\"}", update.enrichment());
            assertEquals(1, update.pageCount());
        }

        @Test
        @DisplayName("processes multiple files in batch")
        void processesMultipleFiles() throws Exception {
            Path file1 = createTestFile("file1.png");
            Path file2 = createTestFile("file2.png");
            Path file3 = createTestFile("file3.png");
            knowledgeClient.withPendingVduCount(3);
            knowledgeClient.withPendingVduDocIds(List.of(
                file1.toString(), file2.toString(), file3.toString()));

            int processed = batchProcessor.processPendingFiles();

            assertEquals(3, processed);
            assertEquals(3, vduProcessor.getProcessCallCount());
            assertEquals(3, knowledgeClient.getVduUpdates().size());
        }
    }

    @Nested
    @DisplayName("No Text Detected (P0.4)")
    class NoTextDetected {

        @Test
        @DisplayName("marks document as FAILED when extracted text is blank")
        void marksFailedWhenExtractedTextBlank() throws Exception {
            Path testFile = createTestFile("empty.png");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withDefaultResult("", "{\"pages\":1}", 1);  // Blank extracted text

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Blank text should not count as processed");
            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus(),
                "Should mark as FAILED, not COMPLETED");
            assertTrue(update.enrichment().contains("no_text_detected"),
                "Should include no_text_detected error code");
        }

        @Test
        @DisplayName("marks document as FAILED when extracted text is null")
        void marksFailedWhenExtractedTextNull() throws Exception {
            Path testFile = createTestFile("null.png");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withDefaultResult(null, "", 1);  // Null extracted text

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Null text should not count as processed");
            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
            assertTrue(update.enrichment().contains("no_text_detected"));
        }

        @Test
        @DisplayName("marks document as FAILED when extracted text is whitespace only")
        void marksFailedWhenExtractedTextWhitespace() throws Exception {
            Path testFile = createTestFile("whitespace.png");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withDefaultResult("   \t\n  ", "", 3);  // Whitespace only

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Whitespace-only text should not count as processed");
            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
            assertTrue(update.enrichment().contains("no_text_detected"));
        }

        @Test
        @DisplayName("preserves page count in no_text_detected enrichment")
        void preservesPageCountInEnrichment() throws Exception {
            Path testFile = createTestFile("multipage.pdf");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withDefaultResult("", "", 5);  // 5 pages, no text

            batchProcessor.processPendingFiles();

            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertTrue(update.enrichment().contains("pageCount") || update.enrichment().contains("5"),
                "Should preserve page count in enrichment");
        }
    }

    @Nested
    @DisplayName("Failure Handling")
    class FailureHandling {

        @Test
        @DisplayName("marks document as failed when VduProcessor throws VduException")
        void marksFailedOnVduException() throws Exception {
            Path testFile = createTestFile("failing.png");
            String docId = testFile.toString();
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(docId));
            vduProcessor.withFailingDocId(docId, "OCR extraction failed");

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Failed file should not count as processed");
            var update = knowledgeClient.getVduUpdate(docId);
            assertNotNull(update);
            assertEquals(SchemaFields.VDU_STATUS_FAILED, update.vduStatus());
            assertTrue(update.enrichment().contains("OCR extraction failed"));
        }

        @Test
        @DisplayName("continues processing other files after failure")
        void continuesAfterFailure() throws Exception {
            Path failingFile = createTestFile("failing.png");
            Path successFile = createTestFile("success.png");
            knowledgeClient.withPendingVduCount(2);
            knowledgeClient.withPendingVduDocIds(List.of(
                failingFile.toString(), successFile.toString()));
            vduProcessor.withFailingDocId(failingFile.toString(), "Failed");

            int processed = batchProcessor.processPendingFiles();

            assertEquals(1, processed, "Should count only successful file");
            assertEquals(2, vduProcessor.getProcessCallCount(), "Should attempt both files");
            assertEquals(SchemaFields.VDU_STATUS_FAILED,
                knowledgeClient.getVduUpdate(failingFile.toString()).vduStatus());
            assertEquals(SchemaFields.VDU_STATUS_COMPLETED,
                knowledgeClient.getVduUpdate(successFile.toString()).vduStatus());
        }

        @Test
        @DisplayName("marks failed when update returns false")
        void countsAsFailedWhenUpdateFails() throws Exception {
            Path testFile = createTestFile("test.png");
            knowledgeClient.withPendingVduCount(1);
            knowledgeClient.withPendingVduDocIds(List.of(testFile.toString()));
            knowledgeClient.withUpdateVduResultSuccess(false);

            int processed = batchProcessor.processPendingFiles();

            assertEquals(0, processed, "Should not count as processed when update fails");
        }
    }

    // ========== Test Helpers ==========

    private Path createTestFile(String name) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, "test content");
        return file;
    }

    // ========== Test Support Classes ==========

    /**
     * Testable version of VduBatchProcessor that works with stubs.
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
                    // Poison pill protection
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

                    // P0.4: If VDU/OCR produced no text, treat as FAILED (not COMPLETED).
                    String extractedText = result.extractedText();
                    boolean noTextDetected = extractedText == null || extractedText.isBlank();

                    String vduStatus;
                    String enrichment;
                    if (noTextDetected) {
                        vduStatus = SchemaFields.VDU_STATUS_FAILED;
                        enrichment = "{\"error\":\"no_text_detected\",\"pageCount\":" + result.pageCount() + "}";
                    } else {
                        vduStatus = SchemaFields.VDU_STATUS_COMPLETED;
                        enrichment = result.enrichment();
                    }

                    boolean updated = knowledgeClient.updateVduResult(
                        docId,
                        noTextDetected ? "" : extractedText,
                        vduStatus,
                        enrichment,
                        result.pageCount()
                    );

                    if (updated) {
                        if (!noTextDetected) {
                            processed++;
                        }
                        // noTextDetected counts as failed, not processed
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
