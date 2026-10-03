package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.*;

/**
 * HTTP Smoke Tests - Minimal endpoint verification.
 *
 * <p>REQUIRES a running JustSearch server. Run with:
 * <pre>
 *   ./gradlew :modules:system-tests:integrationTest --tests "*HttpSmokeTest*" \
 *       -Djustsearch.api.port=8080
 * </pre>
 *
 * <p>These tests are intentionally minimal. The HTTP layer is thin Javalin routing.
 * Search ranking and summarization quality are tested by existing backend tests.
 */
@DisplayName("HTTP Smoke Tests")
class HttpSmokeTest {

    private static HttpClient client;
    private static int port;
    private static boolean serverAvailable = false;

    @BeforeAll
    static void setup() {
        port = Integer.getInteger("justsearch.api.port", 8080);
        client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        // Check if server is available
        serverAvailable = checkServerAvailable();
        if (!serverAvailable) {
            System.err.println("❌ Server not available at localhost:" + port);
            System.err.println("   Start server first: ./gradlew :modules:ui:run");
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
    @DisplayName("GET /api/status returns 200")
    void statusEndpointReturns200() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/status")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("GET /api/knowledge/status returns 200")
    void knowledgeStatusReturns200() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/status")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());
    }

    @Test
    @DisplayName("POST /api/knowledge/search returns 200 with valid structure")
    void searchEndpointReturns200WithValidStructure() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        String jsonBody = """
            {
                "query": "test",
                "limit": 10
            }
            """;

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/knowledge/search"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertEquals(200, response.statusCode());

        String body = response.body();
        // Verify response structure
        assertTrue(body.contains("results"), "Response should contain 'results' array");
        assertTrue(body.contains("totalHits"), "Response should contain 'totalHits' count");
        assertTrue(body.contains("tookMs"), "Response should contain 'tookMs' timing");

        // Verify it's valid JSON (should start with { and contain expected fields)
        assertTrue(body.trim().startsWith("{"), "Response should be JSON object");
        assertTrue(body.trim().endsWith("}"), "Response should be valid JSON");
    }

    @Test
    @DisplayName("GET /api/health returns 200 with component status")
    void healthEndpointReturns200WithComponentStatus() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/health")).build(),
            HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 503,
            "Expected 200 (healthy) or 503 (starting/unhealthy), got: " + response.statusCode()
        );

        String body = response.body();
        assertTrue(body.contains("schema_version"), "Response should contain 'schema_version'");
        assertTrue(body.contains("observed_at"), "Response should contain 'observed_at'");
        assertTrue(body.contains("lifecycle"), "Response should contain 'lifecycle'");
        assertTrue(body.contains("components"), "Response should contain 'components'");
        HttpHealthAssertions.assertSchema2(new tools.jackson.databind.ObjectMapper().readTree(body));
    }

    @Test
    @DisplayName("POST /api/chat/summarize returns valid response")
    void summarizeEndpointReturnsValidResponse() throws Exception {
        assumeTrue(serverAvailable, "❌ Server not running - start with ./gradlew :modules:ui:run");

        // Tempdoc 491 C1: legacy /api/summarize migrated to /api/chat/summarize (SummarizeShape).
        // Body shape unchanged; the substrate-driven engine streams SSE events.
        String jsonBody = """
            {
                "content": "This is a test content to summarize.",
                "limit": 50
            }
            """;

        var response = client.send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/chat/summarize"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );

        // The endpoint should return:
        // - 200 with summary when AI is available
        // - 400 when AI is unavailable
        // Both are valid responses indicating the endpoint is working
        assertTrue(
            response.statusCode() == 200 || response.statusCode() == 400,
            "Should return 200 (with summary) or 400 (translator unavailable), got: " + response.statusCode()
        );

        String body = response.body();
        if (response.statusCode() == 200) {
            assertTrue(body.contains("summary"), "Response should contain 'summary' field");
        } else {
            assertTrue(body.contains("error"), "Error response should contain 'error' field");
            assertTrue(body.contains("TRANSLATOR_UNAVAILABLE") || body.contains("unavailable"),
                "Error should indicate translator unavailable");
        }
    }
}
