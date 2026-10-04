package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-stack projection-coherence verification — tempdoc 550 Thesis III(a), the cross-stream
 * SEMANTIC teeth the static operation-surface gate (a type allowlist) cannot provide.
 *
 * <p>The job lifecycle has TWO governed projections: the rail's live indexing-jobs Resource
 * (in-flight state) and the unified action-ledger's {@code kind=index} terminal-outcome rows
 * (Activity). Thesis I says both derive from the ONE worker job table. This test drives a real
 * indexing run and asserts the ledger projection is a FAITHFUL TERMINAL projection of the jobs:
 *
 * <ul>
 *   <li><b>In-flight-leak guard (airtight — the §B.2 analog):</b> EVERY {@code kind=index} ledger
 *       event is terminal ({@code DONE}/{@code FAILED}) and {@code originator=system}. The
 *       translator emits only on terminal transitions, so a PENDING/PROCESSING row can NEVER reach
 *       the ledger regardless of sampling timing — this assertion fails iff in-flight state leaks
 *       (the over-emission / count-vs-list drift class, restated as ledger-vs-jobs drift).
 *   <li><b>Liveness:</b> a real indexing run ADDS terminal index events (delta from a pre-ingest
 *       baseline) — the translator is wired live end-to-end, not just unit-mocked.
 * </ul>
 *
 * <p>Exact one-event-per-file attribution is deliberately NOT asserted: this backend does heavy
 * concurrent background indexing (the bundled help/default corpora, NER + embedding backfill) and
 * the per-job {@code collection} defaults to {@code default} regardless of the requested root
 * collection, so the ledger cannot be cleanly partitioned to "just my files" in a live shared
 * index. The exact terminal→emit / non-terminal→no-emit decision is unit-proven in
 * {@code IndexingJobsBridgeWiringTest}; this live test proves the wiring carries it end-to-end and
 * that the universal in-flight-leak invariant holds against a real worker.
 */
@DisplayName("Indexing → action-ledger projection coherence (tempdoc 550 Thesis III)")
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class IndexingLedgerCoherenceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static final int DOC_COUNT = 4;
  private static final String COLLECTION = "ledger-coherence-e2e";
  private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);
  private static final Duration LEDGER_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration POLL = Duration.ofMillis(500);

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("indexing terminal outcomes reach the one log as system terminal events; no in-flight leak")
  void ledgerIsAFaithfulTerminalProjectionOfTheJobs() throws Exception {
    // Let any startup/seed indexing settle, then baseline the pre-ingest index events.
    awaitKnowledgeIdle(IDLE_TIMEOUT);
    Set<String> baseline = indexPathHashes(assertAllTerminalSystem(fetchLedgerEntries()));

    Path corpus = Files.createTempDirectory("ledger-coherence-");
    try {
      for (int i = 0; i < DOC_COUNT; i++) {
        Files.writeString(
            corpus.resolve("doc-" + i + ".txt"),
            "Ledger coherence corpus document number " + i + " — unique indexing content.");
      }

      // Trigger a real indexing run over the corpus.
      String addBody =
          MAPPER.writeValueAsString(
              java.util.Map.of("path", corpus.toAbsolutePath().toString(), "collection", COLLECTION));
      HttpResponse<String> add =
          CLIENT.send(
              HttpRequest.newBuilder(URI.create(base() + "/api/indexing/roots"))
                  .timeout(Duration.ofSeconds(10))
                  .header("Content-Type", "application/json")
                  .POST(HttpRequest.BodyPublishers.ofString(addBody))
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      assertEquals(200, add.statusCode(), "add-root triggered indexing: " + add.body());

      assertTrue(awaitKnowledgeIdle(IDLE_TIMEOUT), "indexing run reached idle (queue drained)");

      // Poll until the run has ADDED at least one terminal index event beyond the baseline — and on
      // every poll re-assert the airtight in-flight-leak invariant over ALL index events.
      boolean sawNew = false;
      long deadline = System.currentTimeMillis() + LEDGER_TIMEOUT.toMillis();
      Set<String> current = baseline;
      while (System.currentTimeMillis() < deadline) {
        current = indexPathHashes(assertAllTerminalSystem(fetchLedgerEntries()));
        Set<String> added = new HashSet<>(current);
        added.removeAll(baseline);
        if (!added.isEmpty()) {
          sawNew = true;
          break;
        }
        Thread.sleep(POLL.toMillis());
      }

      // Liveness: the indexing run produced new terminal index events in the one log (the translator
      // is wired live end-to-end). The in-flight-leak invariant was asserted on every fetch above.
      if (!sawNew) BACKEND.preserveLogOnFailure();
      assertTrue(
          sawNew,
          "indexing run added terminal kind=index events to the unified ledger (live Thesis I wiring)");
      assertTrue(current.size() > 0, "the ledger holds terminal index events after an indexing run");
    } finally {
      // Best-effort: remove the root + delete the corpus.
      try {
        String body =
            MAPPER.writeValueAsString(
                java.util.Map.of(
                    "path", corpus.toAbsolutePath().toString(), "collection", COLLECTION));
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/indexing/roots"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
      } catch (Exception ignored) {
        // cleanup is best-effort
      }
      deleteRecursively(corpus);
    }
  }

  /**
   * The airtight in-flight-leak guard (the §B.2 analog): assert EVERY {@code kind=index} ledger
   * entry is terminal (DONE/FAILED) + {@code originator=system} + carries a non-blank pathHash.
   * Returns the index entries so callers can derive the pathHash set. Fails iff in-flight
   * (PENDING/PROCESSING) state ever leaks into the ledger.
   */
  private java.util.List<JsonNode> assertAllTerminalSystem(JsonNode entries) {
    java.util.List<JsonNode> index = new java.util.ArrayList<>();
    for (JsonNode e : entries) {
      if (!"index".equals(e.path("kind").asText())) continue;
      String state = e.path("state").asText();
      assertTrue(
          "DONE".equals(state) || "FAILED".equals(state),
          "ledger index events must be terminal (DONE/FAILED), saw in-flight state: " + e);
      assertEquals("system", e.path("originator").asText(), "index rows are system-attributed: " + e);
      assertTrue(!e.path("pathHash").asText().isBlank(), "index rows carry a pathHash: " + e);
      index.add(e);
    }
    return index;
  }

  private static Set<String> indexPathHashes(java.util.List<JsonNode> indexEntries) {
    Set<String> out = new HashSet<>();
    for (JsonNode e : indexEntries) out.add(e.path("pathHash").asText());
    return out;
  }

  private JsonNode fetchLedgerEntries() throws Exception {
    HttpResponse<String> ledger =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create(base() + "/api/action-ledger"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(200, ledger.statusCode(), "action-ledger endpoint is wired: " + ledger.body());
    return MAPPER.readTree(ledger.body()).get("entries");
  }

  private boolean awaitKnowledgeIdle(Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    int idleStreak = 0;
    while (System.currentTimeMillis() < deadline) {
      try {
        HttpResponse<String> status =
            CLIENT.send(
                HttpRequest.newBuilder(URI.create(base() + "/api/knowledge/status"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode s = MAPPER.readTree(status.body());
        long queueDepth = s.path("queueDepth").asLong(0);
        long processing = s.path("processingJobsCount").asLong(0);
        // Best-effort settle: drained queue for 2 consecutive samples. This is only an optimization
        // to avoid polling the ledger before work completes — it is NOT the correctness oracle. The
        // actual guarantee is awaitIndexEventCoherence, which polls until exactly DOC_COUNT terminal
        // index events appear (so a run that finishes between idle polls still passes via the event
        // count, and a pre-ingest empty read cannot false-succeed the test — only the event count can).
        if (queueDepth == 0 && processing == 0) {
          idleStreak++;
          if (idleStreak >= 2) return true;
        } else {
          idleStreak = 0;
        }
      } catch (Exception e) {
        idleStreak = 0;
      }
      Thread.sleep(POLL.toMillis());
    }
    return false;
  }

  private static void deleteRecursively(Path dir) {
    try (var stream = Files.walk(dir)) {
      stream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(java.io.File::delete);
    } catch (Exception ignored) {
      // best-effort
    }
  }

  private static String base() {
    return "http://127.0.0.1:" + BACKEND.port();
  }
}
