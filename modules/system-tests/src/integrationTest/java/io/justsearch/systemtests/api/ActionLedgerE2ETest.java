package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-stack verification for tempdoc 550 Slice C1 (Outcome face, backend half): the
 * unified action-ledger read-view merges Operation + Navigation records into one
 * attributed, chronological stream — the read-view the receipt / timeline / undo /
 * trust-audit are meant to read from (FE re-pointing is the remaining flagged cutover).
 *
 * <p>Tempdoc 879: the merge is now exercised by two invocations rather than one.
 * {@code core.navigate-to-surface} declares {@code AuditPolicy.NONE}, and since the audit
 * axis is enforced at dispatch it contributes only the Navigation record — no Operation
 * record. {@code core.set-chat-enabled} (LOW · no-confirm · no capability gate ·
 * {@code METADATA_ONLY}) supplies the Operation record. What the test asserts is unchanged:
 * both kinds land in ONE attributed, chronological read-view.
 */
@DisplayName("Unified Action Ledger E2E (tempdoc 550 Slice C1)")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ActionLedgerE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("Operation and Navigation records merge into one attributed ledger")
  void operationAndNavigationMergeIntoOneLedger() throws Exception {
    // Navigation record: core.navigate-to-surface forwards the navigation. It declares
    // AuditPolicy.NONE, so (tempdoc 879) it deliberately leaves no Operation record.
    HttpResponse<String> navigate =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.navigate-to-surface/invoke"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "{\"args\":{\"surfaceId\":\"core.library\"}}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, navigate.statusCode(), "navigate-to-surface invoked: " + navigate.body());

    // Operation record: core.set-chat-enabled is METADATA_ONLY, so it IS recorded. LOW risk,
    // no confirm, no capability gate — deliberately ungatable (tempdoc 737 §12b), which makes
    // it the reliable operation-row producer on an isolated fixture backend.
    HttpResponse<String> invoke =
        CLIENT.send(
            HttpRequest.newBuilder(
                    URI.create(base() + "/api/operations/core.set-chat-enabled/invoke"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"args\":{\"enabled\":false}}"))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, invoke.statusCode(), "set-chat-enabled invoked: " + invoke.body());

    HttpResponse<String> ledger =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/action-ledger"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, ledger.statusCode(), "action-ledger endpoint is wired: " + ledger.body());

    JsonNode entries = MAPPER.readTree(ledger.body()).get("entries");
    assertTrue(entries.isArray() && entries.size() >= 2, "merged ledger has entries: " + ledger.body());

    boolean hasOperation = false;
    boolean hasNavigation = false;
    boolean chronological = true;
    String prev = null;
    for (JsonNode e : entries) {
      String kind = e.path("kind").asText();
      if ("operation".equals(kind) && "core.set-chat-enabled".equals(e.path("operationId").asText())) {
        hasOperation = true;
      }
      if ("navigation".equals(kind) && "core.library".equals(e.path("targetSurface").asText())) {
        hasNavigation = true;
      }
      // every entry is attributed + timestamped
      assertTrue(!e.path("originator").asText().isBlank(), "entry attributed: " + e);
      String occurredAt = e.path("occurredAt").asText();
      if (prev != null && occurredAt.compareTo(prev) < 0) {
        chronological = false;
      }
      prev = occurredAt;
    }
    assertTrue(hasOperation, "operation record present in unified ledger: " + ledger.body());
    assertTrue(hasNavigation, "navigation record present in unified ledger: " + ledger.body());
    assertTrue(chronological, "entries are chronological (oldest first): " + ledger.body());
  }

  @Test
  @DisplayName("F1: re-POSTing an FE effect with the same id appears once in the one log (idempotent)")
  void reIngestedEffectAppearsOnce() throws Exception {
    String body =
        "{\"id\":\"fe-effect:1001\",\"effectKind\":\"navigate\",\"originator\":\"user\","
            + "\"subject\":\"#somewhere\"}";
    // Ingest the same effect twice (e.g. a page reload re-POSTing the persisted journal).
    for (int i = 0; i < 2; i++) {
      HttpResponse<String> post =
          CLIENT.send(
              HttpRequest.newBuilder(URI.create(base() + "/api/action-ledger/events"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(body))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(202, post.statusCode(), "effect ingested: " + post.body());
    }

    HttpResponse<String> ledger =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/action-ledger"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, ledger.statusCode(), ledger.body());

    int count = 0;
    for (JsonNode e : MAPPER.readTree(ledger.body()).get("entries")) {
      if ("fe-effect:1001".equals(e.path("id").asText())) {
        count++;
      }
    }
    assertEquals(1, count, "the re-ingested effect appears exactly once (id-keyed one log): " + ledger.body());
  }

  private static String base() {
    return "http://127.0.0.1:" + BACKEND.port();
  }
}
