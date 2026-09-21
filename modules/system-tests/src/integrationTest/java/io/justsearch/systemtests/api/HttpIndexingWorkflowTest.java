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
import org.junit.jupiter.api.*;

/**
 * HTTP Indexing Workflow Tests - Tests the indexing root management API.
 *
 * <p>REQUIRES a running JustSearch server. Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpIndexingWorkflowTest*" \
 *       -Djustsearch.api.port=9001
 * </pre>
 *
 * <p>Tests the following endpoints:
 * <ul>
 *   <li>GET /api/indexing/roots - List watched roots</li>
 *   <li>POST /api/indexing/roots - Add a watched root</li>
 *   <li>DELETE /api/indexing/roots - Remove a watched root</li>
 *   <li>POST /api/indexing/reindex - Trigger reindex</li>
 * </ul>
 */
@DisplayName("HTTP Indexing Workflow Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HttpIndexingWorkflowTest {

    private static HttpClient client;
    private static int port;
    private static boolean serverAvailable = false;
    private static Path tempTestDir;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void setup() throws Exception {
        port = Integer.getInteger("justsearch.api.port", 8080);
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        // Check if server is available
        serverAvailable = checkServerAvailable();
        if (!serverAvailable) {
            System.err.println("⚠️  Server not available at localhost:" + port);
            System.err.println("    Start server first: ./gradlew :modules:ui:run");
            return;
        }

        // Create a temporary test directory with a test file
        tempTestDir = Files.createTempDirectory("indexing-workflow-test-");
        Path testFile = tempTestDir.resolve("test-document.txt");
        Files.writeString(testFile, "This is a test document for indexing workflow verification.");
    }

    @AfterAll
    static void cleanup() throws Exception {
        // Clean up temp directory
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            try (var walk = Files.walk(tempTestDir)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
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

    @Test
    @Order(1)
    @DisplayName("GET /api/indexing/roots returns list of roots")
    void listRootsReturnsValidResponse() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertTrue(json.has("roots"), "Response should contain 'roots' array");
        assertTrue(json.get("roots").isArray(), "'roots' should be an array");
    }

    @Test
    @Order(2)
    @DisplayName("POST /api/indexing/roots adds a new root")
    void addRootSucceeds() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(tempTestDir != null, "❌ Temp directory not created");

        String jsonBody = objectMapper.writeValueAsString(java.util.Map.of(
            "path", tempTestDir.toAbsolutePath().toString(),
            "collection", "test-collection"
        ));

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // Should succeed (200) or indicate service unavailable (503 if worker not running)
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 503,
            "Should return 200 (OK) or 503 (service unavailable), got: " + response.statusCode()
        );

        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            assertEquals("ok", json.get("status").asText(), "Status should be 'ok'");
        }
    }

    @Test
    @Order(3)
    @DisplayName("POST /api/indexing/roots rejects empty path")
    void addRootRejectsEmptyPath() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        String jsonBody = objectMapper.writeValueAsString(java.util.Map.of(
            "path", "",
            "collection", "test-collection"
        ));

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode(), "Should reject empty path with 400");
        assertTrue(response.body().contains("error"), "Response should contain error message");
    }

    @Test
    @Order(4)
    @DisplayName("POST /api/indexing/reindex triggers reindex")
    void reindexTriggers() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/reindex"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // Should succeed (200) or indicate service unavailable (503)
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 503,
            "Should return 200 (OK) or 503 (service unavailable), got: " + response.statusCode()
        );

        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            assertTrue(json.path("success").asBoolean(), response.body());
            assertFalse(json.path("structuredData").path("operationKey").asText().isBlank(), response.body());
            assertTrue(json.path("structuredData").path("operationRecordId").asLong() > 0, response.body());
        }
    }

    @Test
    @Order(5)
    @DisplayName("POST /api/indexing/reindex?force=true triggers force reindex")
    void forceReindexTriggers() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/reindex?force=true"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // Should succeed (200) or indicate service unavailable (503)
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 503,
            "Should return 200 (OK) or 503 (service unavailable), got: " + response.statusCode()
        );

        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            assertTrue(json.path("success").asBoolean(), response.body());
            assertFalse(json.path("structuredData").path("operationKey").asText().isBlank(), response.body());
            assertTrue(json.path("structuredData").path("operationRecordId").asLong() > 0, response.body());
        }
    }

    @Test
    @Order(6)
    @DisplayName("DELETE /api/indexing/roots removes a root")
    void removeRootSucceeds() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");
        assertTrue(tempTestDir != null, "❌ Temp directory not created");

        String jsonBody = objectMapper.writeValueAsString(java.util.Map.of(
            "path", tempTestDir.toAbsolutePath().toString(),
            "collection", "test-collection"
        ));

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // Should succeed (200) or indicate service unavailable (503)
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 503,
            "Should return 200 (OK) or 503 (service unavailable), got: " + response.statusCode()
        );

        if (response.statusCode() == 200) {
            JsonNode json = objectMapper.readTree(response.body());
            assertEquals("ok", json.get("status").asText(), "Status should be 'ok'");
            assertTrue(json.has("deletedJobs"), "Response should contain 'deletedJobs'");
        }
    }

    @Test
    @Order(7)
    @DisplayName("DELETE /api/indexing/roots rejects empty path")
    void removeRootRejectsEmptyPath() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        String jsonBody = objectMapper.writeValueAsString(java.util.Map.of(
            "path", ""
        ));

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots"))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(400, response.statusCode(), "Should reject empty path with 400");
    }

    @Test
    @Order(8)
    @DisplayName("GET /api/indexing/roots?counts=true includes file counts")
    void listRootsWithCountsIncludesFileCounts() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/indexing/roots?counts=true"))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        assertEquals(200, response.statusCode());

        JsonNode json = objectMapper.readTree(response.body());
        assertTrue(json.has("roots"), "Response should contain 'roots' array");

        // If there are roots, verify fileCount is present
        JsonNode roots = json.get("roots");
        if (roots.size() > 0) {
            JsonNode firstRoot = roots.get(0);
            assertTrue(firstRoot.has("fileCount"), "Root should have 'fileCount' when counts=true");
        }
    }
}
