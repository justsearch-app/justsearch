package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
 * HTTP File Watcher E2E Tests - Verifies that file changes trigger automatic reindexing.
 *
 * <p>This tests the REAL file watcher integration:
 * <ol>
 *   <li>Add root folder via HTTP API (starts file watcher)</li>
 *   <li>Create NEW file in that folder (watcher should detect)</li>
 *   <li>Verify new file becomes searchable (indexing triggered)</li>
 * </ol>
 *
 * <p>The key difference from {@link HttpIndexingE2ETest}:
 * <ul>
 *   <li>{@code HttpIndexingE2ETest}: Creates files BEFORE adding root → initial scan indexes</li>
 *   <li>{@code HttpFileWatcherE2ETest}: Creates files AFTER adding root → watcher detects and indexes</li>
 * </ul>
 *
 * <p>This catches bugs where:
 * <ul>
 *   <li>File watcher isn't started when root is added</li>
 *   <li>Watcher events don't trigger indexing</li>
 *   <li>Watcher debouncing breaks event delivery</li>
 * </ul>
 *
 * <p>REQUIRES a running JustSearch server with worker available.
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpFileWatcherE2ETest*" \
 *       -Djustsearch.api.port=9001
 * </pre>
 *
 * <p><b>Note:</b> These tests may be flaky on Windows due to WatchService latency.
 * Uses generous timeouts (30s) to accommodate slow systems.
 *
 * <p><b>Implementation Note:</b> File watcher is now started for dynamically added roots via HTTP API.
 * {@code KnowledgeClient.addWatchedRoot()} starts both indexing and file watching.
 */
@DisplayName("HTTP File Watcher E2E Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpFileWatcherE2ETest {

    private static final Logger log = LoggerFactory.getLogger(HttpFileWatcherE2ETest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpClient client;
    private static int port;
    private static boolean serverAvailable = false;
    private static boolean workerAvailable = false;

    // Timeouts - generous for file watcher latency
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WATCHER_TIMEOUT = Duration.ofSeconds(30); // Watcher + indexing
    private static final Duration WATCHER_STABILIZATION_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final int IDLE_STABILITY_SAMPLES = 3;

    // Per-test temp folder (cleaned up after each test)
    private Path tempFolder;

    @BeforeAll
    static void setup() {
        port = Integer.getInteger("justsearch.api.port", 8080);
        client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

        serverAvailable = checkServerAvailable();
        if (!serverAvailable) {
            log.warn("⚠️  Server not available at localhost:{}", port);
            return;
        }

        workerAvailable = checkWorkerAvailable();
        if (!workerAvailable) {
            log.warn("⚠️  Worker not available - file watcher tests will be skipped");
        }
    }

    @BeforeEach
    void createTempFolder() throws Exception {
        // Create unique temp folder for this test
        tempFolder = Files.createTempDirectory("filewatcher-e2e-test-");
        log.debug("Created temp folder: {}", tempFolder);
    }

    @AfterEach
    void cleanupTempFolder() {
        // Remove root from server (if added)
        if (tempFolder != null && serverAvailable) {
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

    private static boolean checkServerAvailable() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status"))
                    .timeout(Duration.ofSeconds(2))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean checkWorkerAvailable() {
        try {
            var resp = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health"))
                    .timeout(REQUEST_TIMEOUT)
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            if (resp.statusCode() == 200) {
                JsonNode json = MAPPER.readTree(resp.body());
                String workerState = json.path("components").path("worker").path("state").asText("");
                return "READY".equals(workerState);
            }
        } catch (Exception e) {
            log.debug("Worker check failed: {}", e.getMessage());
        }
        return false;
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private boolean addRoot(String path) throws Exception {
        String jsonBody = MAPPER.writeValueAsString(Map.of(
            "path", path,
            "collection", "watcher-test"
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
            "collection", "watcher-test"
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
     * Waits for knowledge queue to stay idle for a few consecutive samples. This replaces fixed
     * sleeps while still giving watcher registration and debounce paths time to settle.
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
                // its minimum duty (tempdoc 885 item 3 — it no longer stops it outright). Avoid
                // polling search while the worker is actively processing jobs, otherwise
                // watcher-driven indexing runs at a fraction of its rate and the test can time out.
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
                    log.debug("Document found after {} attempts", attempts);
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
     * Waits until a specific document is NOT searchable (removed from index).
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
                    log.debug("Document gone after {} attempts", attempts);
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

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("New file created AFTER adding root becomes searchable via watcher")
    void newFileCreatedAfterAddingRootBecomesSearchable() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assumeTrue(workerAvailable, "❌ Worker not available");

        // 1. Add EMPTY root folder first (starts watcher)
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");
        log.info("Added root (watcher should be active): {}", tempFolder);

        // 2. Wait for queue activity to settle before creating watcher-driven events.
        boolean stabilized = awaitKnowledgeIdle(
            WATCHER_STABILIZATION_TIMEOUT,
            IDLE_STABILITY_SAMPLES,
            "after add root (new file test)"
        );
        assertTrue(stabilized, "Knowledge queue should stabilize after root add");

        // 3. Create NEW file (watcher should detect this)
        // NOTE: Search queries are parsed by Lucene queryparser. Avoid special chars (e.g. '-') in test markers.
        String uniqueMarker = "WatcherCreateTest" + UUID.randomUUID().toString().substring(0, 8);
        Path newFile = tempFolder.resolve("watcher-detected-file.txt");
        Files.writeString(newFile, "This file was created AFTER root was added: " + uniqueMarker);
        log.info("Created new file (watcher should detect): {} with marker: {}", newFile.getFileName(), uniqueMarker);

        // 4. Wait for watcher to detect, debounce, index, and make searchable
        boolean found = awaitDocumentSearchable(uniqueMarker, newFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(found, "New file should become searchable via watcher within " + WATCHER_TIMEOUT.toSeconds() + "s");

        log.info("✅ File watcher detected new file and triggered indexing");
    }

    @Test
    @Order(2)
    @DisplayName("Modified file content is updated in index via watcher")
    void modifiedFileContentUpdatedViaWatcher() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assumeTrue(workerAvailable, "❌ Worker not available");

        // 1. Create initial file in temp folder
        String initialMarker = "WatcherModifyInitial" + UUID.randomUUID().toString().substring(0, 8);
        Path testFile = tempFolder.resolve("modify-test-file.txt");
        Files.writeString(testFile, "Initial content: " + initialMarker);

        // 2. Add root (will scan and index initial file)
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // Wait for initial indexing
        boolean initialFound = awaitDocumentSearchable(initialMarker, testFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(initialFound, "❌ Initial content not indexed");
        log.info("Initial content indexed: {}", initialMarker);

        // 3. Wait for queue activity to settle before modifying file content.
        boolean stabilized = awaitKnowledgeIdle(
            WATCHER_STABILIZATION_TIMEOUT,
            IDLE_STABILITY_SAMPLES,
            "after initial index (modify test)"
        );
        assertTrue(stabilized, "Knowledge queue should stabilize before file modification");

        // 4. Modify file with new unique content
        String modifiedMarker = "WatcherModifyUpdated" + UUID.randomUUID().toString().substring(0, 8);
        Files.writeString(testFile, "Modified content: " + modifiedMarker);
        log.info("Modified file with new content: {}", modifiedMarker);

        // 5. Wait for watcher to detect modification and update index
        boolean modifiedFound = awaitDocumentSearchable(modifiedMarker, testFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(modifiedFound, "Modified content should be searchable via watcher within " + WATCHER_TIMEOUT.toSeconds() + "s");

        log.info("✅ File watcher detected modification and updated index");
    }

    @Test
    @Order(3)
    @DisplayName("Deleted file is removed from index via watcher")
    void deletedFileRemovedFromIndexViaWatcher() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assumeTrue(workerAvailable, "❌ Worker not available");

        // 1. Create initial file in temp folder
        String marker = "WatcherDeleteTest" + UUID.randomUUID().toString().substring(0, 8);
        Path testFile = tempFolder.resolve("delete-test-file.txt");
        Files.writeString(testFile, "Content to be deleted: " + marker);

        // 2. Add root (will scan and index initial file)
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");

        // Wait for initial indexing
        boolean initialFound = awaitDocumentSearchable(marker, testFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(initialFound, "❌ Initial content not indexed");
        log.info("Initial file indexed: {}", marker);

        // 3. Wait for queue activity to settle before deleting file.
        boolean stabilized = awaitKnowledgeIdle(
            WATCHER_STABILIZATION_TIMEOUT,
            IDLE_STABILITY_SAMPLES,
            "after initial index (delete test)"
        );
        assertTrue(stabilized, "Knowledge queue should stabilize before file deletion");

        // 4. Delete the file (watcher should detect)
        Files.delete(testFile);
        log.info("Deleted file: {}", testFile.getFileName());

        // 5. Wait for watcher to detect deletion and remove from index
        boolean removed = awaitDocumentNotSearchable(marker, testFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(removed, "Deleted file should be removed from index via watcher within " + WATCHER_TIMEOUT.toSeconds() + "s");

        log.info("✅ File watcher detected deletion and removed from index");
    }

    @Test
    @Order(4)
    @DisplayName("File created in subdirectory is indexed via watcher")
    void fileCreatedInSubdirectoryIndexedViaWatcher() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assumeTrue(workerAvailable, "❌ Worker not available");

        // 1. Create subdirectory BEFORE adding root
        Path subFolder = tempFolder.resolve("watched-subfolder");
        Files.createDirectories(subFolder);

        // 2. Add root (starts watcher, should watch subdirectories too)
        boolean added = addRoot(tempFolder.toAbsolutePath().toString());
        assertTrue(added, "❌ Failed to add root folder");
        log.info("Added root with subfolder: {}", tempFolder);

        // 3. Wait for queue activity to settle before creating file in subdirectory.
        boolean stabilized = awaitKnowledgeIdle(
            WATCHER_STABILIZATION_TIMEOUT,
            IDLE_STABILITY_SAMPLES,
            "after add root (subdirectory test)"
        );
        assertTrue(stabilized, "Knowledge queue should stabilize before subdirectory file create");

        // 4. Create NEW file in subdirectory (watcher should detect)
        String marker = "WatcherSubdirTest" + UUID.randomUUID().toString().substring(0, 8);
        Path newFile = subFolder.resolve("subdir-file.txt");
        Files.writeString(newFile, "File in subdirectory: " + marker);
        log.info("Created file in subdirectory: {} with marker: {}", newFile.getFileName(), marker);

        // 5. Wait for watcher to detect and index
        boolean found = awaitDocumentSearchable(marker, newFile.getFileName().toString(), WATCHER_TIMEOUT);
        assertTrue(found, "File in subdirectory should become searchable via watcher within " + WATCHER_TIMEOUT.toSeconds() + "s");

        log.info("✅ File watcher detected new file in subdirectory");
    }
}
