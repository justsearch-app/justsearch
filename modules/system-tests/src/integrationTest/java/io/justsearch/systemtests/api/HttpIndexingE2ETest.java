package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP Indexing E2E Tests - Verifies the complete indexing workflow.
 *
 * <p>Tests the full flow:
 * <ol>
 *   <li>Add root folder via HTTP API</li>
 *   <li>Wait for indexing to complete</li>
 *   <li>Verify documents are searchable</li>
 *   <li>Clean up (remove root)</li>
 * </ol>
 *
 * <p>This catches bugs where:
 * <ul>
 *   <li>Add root API doesn't trigger indexing</li>
 *   <li>Indexing completes but documents aren't searchable</li>
 *   <li>Remove root doesn't clean up documents</li>
 * </ul>
 *
 * <p>Starts an isolated lite-mode Engine on an ephemeral port for this class.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpIndexingE2ETest*"
 * </pre>
 */
@DisplayName("HTTP Indexing E2E Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpIndexingE2ETest {

    private static final Logger log = LoggerFactory.getLogger(HttpIndexingE2ETest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpClient client;
    private static int port;
    private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();

    // Timeouts
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration INDEXING_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE_SETTLE_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int IDLE_STABILITY_SAMPLES = 2;

    // Test data - unique per test run to avoid conflicts
    private static final String UNIQUE_MARKER = UUID.randomUUID().toString().substring(0, 8);
    private static final String TEST_CONTENT = "This is unique test content for indexing verification: " + UNIQUE_MARKER;

    // Per-test temp folder (cleaned up after each test)
    private Path tempFolder;
    private Path testFile;

    @BeforeAll
    static void setup() throws Exception {
        BACKEND.start();
        port = BACKEND.port();
        client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        HttpHealthAssertions.assertIndexReady(client, port);
    }

    @AfterAll
    static void teardown() {
        BACKEND.stop();
    }

    @BeforeEach
    void createTempFolder() throws Exception {
        // Create unique temp folder for this test
        tempFolder = Files.createTempDirectory("indexing-e2e-test-");
        log.debug("Created temp folder: {}", tempFolder);
    }

    @AfterEach
    void cleanupTempFolder() {
        // Remove root from server (if added)
        if (tempFolder != null) {
            try {
                removeRoot(tempFolder.toAbsolutePath().toString());
                log.debug("Removed root from server: {}", tempFolder);
            } catch (Exception e) {
                log.warn("Failed to remove root: {}", e.getMessage());
            }
        }

        // Delete temp folder
        if (tempFolder != null) {
            try (var stream = Files.walk(tempFolder)) {
                stream.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
                log.debug("Deleted temp folder: {}", tempFolder);
            } catch (Exception e) {
                log.warn("Failed to delete temp folder: {}", e.getMessage());
            }
        }
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private boolean addRoot(String path) throws Exception {
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "path", path,
            "collection", "test-e2e"
        ));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() == 200) {
            return true;
        } else if (response.statusCode() == 503) {
            log.warn("Indexing service unavailable: {}", response.body());
            return false;
        } else {
            log.error("Failed to add root ({}): {}", response.statusCode(), response.body());
            return false;
        }
    }

    private void removeRoot(String path) throws Exception {
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "path", path,
            "collection", "test-e2e"
        ));
        client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        // Ignore errors - cleanup is best-effort
    }

    private void triggerReindex(boolean force) throws Exception {
        String url = "http://localhost:" + port + "/api/indexing/reindex";
        if (force) {
            url += "?force=true";
        }
        var response = client.send(
            HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to trigger reindex: " + response.body());
        }
    }

    private JsonNode getKnowledgeStatus() throws Exception {
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/status"))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return MAPPER.readTree(response.body());
    }

    /**
     * Waits for queue depth and processing jobs to remain at zero for consecutive samples.
     * This avoids fixed sleeps and gives asynchronous indexing operations time to converge.
     */
    private boolean awaitKnowledgeIdle(Duration timeout, int requiredIdleSamples, String phase) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int attempts = 0;
        int idleStreak = 0;
        long lastQueueDepth = -1;
        long lastProcessingJobs = -1;

        while (System.currentTimeMillis() < deadline) {
            attempts++;
            try {
                JsonNode status = getKnowledgeStatus();
                long queueDepth = status.path("queueDepth").asLong(0);
                long processingJobs = status.path("processingJobsCount").asLong(0);
                lastQueueDepth = queueDepth;
                lastProcessingJobs = processingJobs;

                if (queueDepth == 0 && processingJobs == 0) {
                    idleStreak++;
                    if (idleStreak >= requiredIdleSamples) {
                        log.debug(
                            "Knowledge queue idle for {} samples during '{}' after {} attempts",
                            requiredIdleSamples,
                            phase,
                            attempts
                        );
                        return true;
                    }
                } else {
                    idleStreak = 0;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                idleStreak = 0;
                log.debug("Knowledge status poll failed ({}): {}", phase, e.getMessage());
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.warn(
            "Knowledge queue did not stabilize for '{}' after {} attempts ({} ms, last queueDepth={}, processingJobs={})",
            phase,
            attempts,
            timeout.toMillis(),
            lastQueueDepth,
            lastProcessingJobs
        );
        return false;
    }

    /**
     * Waits until a specific document becomes searchable.
     */
    private boolean awaitDocumentSearchable(String query, String pathSubstring, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int attempts = 0;
        long lastQueueDepth = -1;
        long lastProcessingJobs = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                attempts++;
                // IMPORTANT: /api/knowledge/search is foreground load, which throttles indexing to
                // its minimum duty (tempdoc 885 item 3 — it no longer stops it outright). Hammering
                // search while jobs are pending or processing still slows the test to a crawl.
                JsonNode status = getKnowledgeStatus();
                long queueDepth = status.path("queueDepth").asLong(0);
                long processingJobs = status.path("processingJobsCount").asLong(0);
                lastQueueDepth = queueDepth;
                lastProcessingJobs = processingJobs;
                if (queueDepth > 0 || processingJobs > 0) {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                    continue;
                }
                if (searchResultsContainPath(query, pathSubstring)) {
                    return true;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("Search failed (attempt {}): {}", attempts, e.getMessage());
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.warn(
            "Document not found after {} attempts ({} ms, last queueDepth={}, processingJobs={})",
            attempts,
            timeout.toMillis(),
            lastQueueDepth,
            lastProcessingJobs
        );
        return false;
    }

    /**
     * Waits until a specific document is no longer searchable.
     */
    private boolean awaitDocumentNotSearchable(String query, String pathSubstring, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        int attempts = 0;
        long lastQueueDepth = -1;
        long lastProcessingJobs = -1;
        while (System.currentTimeMillis() < deadline) {
            try {
                attempts++;
                JsonNode status = getKnowledgeStatus();
                long queueDepth = status.path("queueDepth").asLong(0);
                long processingJobs = status.path("processingJobsCount").asLong(0);
                lastQueueDepth = queueDepth;
                lastProcessingJobs = processingJobs;
                if (queueDepth > 0 || processingJobs > 0) {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                    continue;
                }
                if (!searchResultsContainPath(query, pathSubstring)) {
                    return true;
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                log.debug("Search failed (attempt {}): {}", attempts, e.getMessage());
                try {
                    Thread.sleep(POLL_INTERVAL.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        log.warn(
            "Document still found after {} attempts ({} ms, last queueDepth={}, processingJobs={})",
            attempts,
            timeout.toMillis(),
            lastQueueDepth,
            lastProcessingJobs
        );
        return false;
    }

    /**
     * Searches for documents containing the given query.
     */
    private JsonNode search(String query) throws Exception {
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "query", query,
            "limit", 10
        ));
        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(REQUEST_TIMEOUT)
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        return MAPPER.readTree(response.body());
    }

    /**
     * Checks if search results contain a document with path containing the given substring.
     */
    private boolean searchResultsContainPath(String query, String pathSubstring) throws Exception {
        JsonNode results = search(query);
        JsonNode hits = results.path("results");
        if (hits.isArray()) {
            for (JsonNode hit : hits) {
                String id = hit.path("id").asText("");
                if (id.contains(pathSubstring)) {
                    return true;
                }
            }
        }
        return false;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Add root folder and verify documents become searchable")
    void addRootAndVerifyDocumentsSearchable() throws Exception {

        // 1. Create test file with unique content
        testFile = tempFolder.resolve("test-document-" + UNIQUE_MARKER + ".txt");
        Files.writeString(testFile, TEST_CONTENT);
        log.info("Created test file: {}", testFile);

        // 2. Add root folder
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "Should successfully add root folder");
        log.info("Added root: {}", tempFolder);

        // 3. Wait for the document to become searchable.
        // Do NOT gate on global docCount deltas: other tests may concurrently delete documents,
        // making global docCount baselines unstable.
        boolean found = awaitDocumentSearchable(UNIQUE_MARKER, testFile.getFileName().toString(), INDEXING_TIMEOUT);
        assertTrue(found, "Document should be searchable by unique content: " + UNIQUE_MARKER);

        log.info("✅ Document indexed and searchable");
    }

    @Test
    @Order(2)
    @DisplayName("Remove root folder and verify documents are deleted")
    void removeRootAndVerifyDocumentsDeleted() throws Exception {

        // 1. Create and index a test file
        // NOTE: Search queries are parsed by Lucene queryparser. Avoid special chars (e.g. '-') in test markers.
        String uniqueContent = "RemoveTest" + UUID.randomUUID().toString().substring(0, 8);
        testFile = tempFolder.resolve("remove-test.txt");
        Files.writeString(testFile, "Content for removal test: " + uniqueContent);

        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // Wait for document to become searchable
        boolean searchable = awaitDocumentSearchable(uniqueContent, testFile.getFileName().toString(), INDEXING_TIMEOUT);
        assertTrue(searchable, "❌ Document not searchable after indexing");

        // 2. Remove root
        removeRoot(tempFolder.toAbsolutePath().toString());
        log.info("Removed root: {}", tempFolder);

        // 3. Wait for deletion to propagate and verify the document is removed.
        boolean removed = awaitDocumentNotSearchable(
            uniqueContent,
            testFile.getFileName().toString(),
            INDEXING_TIMEOUT
        );
        assertTrue(removed, "Document should NOT be searchable after removal");

        log.info("✅ Document removed after root deletion");
    }

    @Test
    @Order(3)
    @DisplayName("Reindex with force flag updates document content")
    void reindexWithForceUpdatesContent() throws Exception {

        // 1. Create and index initial content
        // NOTE: Avoid '-' in markers (Lucene queryparser interprets it as NOT).
        String initialMarker = "InitialContent" + UUID.randomUUID().toString().substring(0, 8);
        testFile = tempFolder.resolve("reindex-test.txt");
        Files.writeString(testFile, "This file contains: " + initialMarker);

        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // Wait for initial content to be searchable
        boolean searchable = awaitDocumentSearchable(initialMarker, testFile.getFileName().toString(), INDEXING_TIMEOUT);
        assertTrue(searchable, "❌ Initial content not searchable after indexing");

        // 2. Update file content
        String updatedMarker = "UpdatedContent" + UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(testFile, "This file now contains: " + updatedMarker);
        log.info("Updated file with new content: {}", updatedMarker);

        // 3. Trigger force reindex
        triggerReindex(true);
        log.info("Triggered force reindex");

        // 4. Wait for updated content to be searchable
        boolean updatedSearchable = awaitDocumentSearchable(updatedMarker, testFile.getFileName().toString(), INDEXING_TIMEOUT);
        assertTrue(updatedSearchable, "Updated content should be searchable after force reindex");

        log.info("✅ Reindex updated document content");
    }

    @Test
    @Order(4)
    @DisplayName("Add root with empty folder succeeds without errors")
    void addRootWithEmptyFolderSucceeds() throws Exception {

        // Folder is already empty (created in @BeforeEach)
        // Add empty folder as root - should succeed or gracefully handle without crashing
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());

        // Empty folder might succeed or fail gracefully - both are acceptable
        // The key is that it doesn't crash or throw unexpected errors
        log.info("Added empty root (success={}): {}", added, tempFolder);

        boolean settled = awaitKnowledgeIdle(
            IDLE_SETTLE_TIMEOUT,
            IDLE_STABILITY_SAMPLES,
            "after add empty root"
        );
        assertTrue(settled, "Knowledge queue should settle after adding empty root");

        // No assertion on doc count - it could change due to other tests
        // The test passes if no exception was thrown
        log.info("✅ Empty folder handled gracefully");
    }

    @Test
    @Order(5)
    @DisplayName("Multiple files in folder are all indexed")
    void multipleFilesInFolderAreIndexed() throws Exception {

        // 1. Create multiple test files
        String marker1 = "MultiFile1" + UUID.randomUUID().toString().substring(0, 8);
        String marker2 = "MultiFile2" + UUID.randomUUID().toString().substring(0, 8);
        String marker3 = "MultiFile3" + UUID.randomUUID().toString().substring(0, 8);

        Path file1 = tempFolder.resolve("multi-test-1.txt");
        Path file2 = tempFolder.resolve("multi-test-2.txt");
        Path file3 = tempFolder.resolve("multi-test-3.txt");

        Files.writeString(file1, "First file content: " + marker1);
        Files.writeString(file2, "Second file content: " + marker2);
        Files.writeString(file3, "Third file content: " + marker3);

        // 2. Add root folder
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // 3. Wait for each file to be searchable
        boolean file1Searchable = awaitDocumentSearchable(marker1, "multi-test-1.txt", INDEXING_TIMEOUT);
        boolean file2Searchable = awaitDocumentSearchable(marker2, "multi-test-2.txt", INDEXING_TIMEOUT);
        boolean file3Searchable = awaitDocumentSearchable(marker3, "multi-test-3.txt", INDEXING_TIMEOUT);

        // 4. Verify each file is searchable
        assertTrue(file1Searchable, "File 1 should be searchable");
        assertTrue(file2Searchable, "File 2 should be searchable");
        assertTrue(file3Searchable, "File 3 should be searchable");

        log.info("✅ All 3 files indexed and searchable");
    }

    @Test
    @Order(6)
    @DisplayName("Subfolder files are indexed recursively")
    void subfolderFilesAreIndexedRecursively() throws Exception {

        // 1. Create nested folder structure
        Path subFolder = tempFolder.resolve("subfolder");
        Path deepFolder = subFolder.resolve("deep");
        Files.createDirectories(deepFolder);

        String rootMarker = "RootFile" + UUID.randomUUID().toString().substring(0, 8);
        String subMarker = "SubFile" + UUID.randomUUID().toString().substring(0, 8);
        String deepMarker = "DeepFile" + UUID.randomUUID().toString().substring(0, 8);

        Path rootFile = tempFolder.resolve("root-file.txt");
        Path subFile = subFolder.resolve("sub-file.txt");
        Path deepFile = deepFolder.resolve("deep-file.txt");

        Files.writeString(rootFile, "Root level: " + rootMarker);
        Files.writeString(subFile, "Subfolder level: " + subMarker);
        Files.writeString(deepFile, "Deep level: " + deepMarker);

        // 2. Add root folder
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // 3. Wait for each file to be searchable
        boolean rootSearchable = awaitDocumentSearchable(rootMarker, "root-file.txt", INDEXING_TIMEOUT);
        boolean subSearchable = awaitDocumentSearchable(subMarker, "sub-file.txt", INDEXING_TIMEOUT);
        boolean deepSearchable = awaitDocumentSearchable(deepMarker, "deep-file.txt", INDEXING_TIMEOUT);

        // 4. Verify each level is searchable
        assertTrue(rootSearchable, "Root file should be searchable");
        assertTrue(subSearchable, "Subfolder file should be searchable");
        assertTrue(deepSearchable, "Deep file should be searchable");

        log.info("✅ All nested files indexed recursively");
    }
}
