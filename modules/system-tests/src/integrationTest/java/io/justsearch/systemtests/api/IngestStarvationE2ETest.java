package io.justsearch.systemtests.api;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.systemtests.harness.IsolatedBackendFixture;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live-stack ingest-starvation guard — tempdoc 798 B1 / T4.
 *
 * <p>The round-7 livelock: once the indexing loop had drained one ingest batch and real
 * enrichment work ran out, the background-enrichment tight loop spun forever on documents that
 * could never advance, so the loop never returned to poll the job queue. Every <em>subsequent</em>
 * ingest sat PENDING forever — {@code processingJobsCount} stuck at 0, zero errors, worker
 * lifecycle READY, all health surfaces green. Only a worker restart drained the queue.
 *
 * <p><strong>Why this test is two-batch.</strong> The defect is invisible to a single-batch
 * ingest test: batch A is claimed by the loop <em>before</em> the tight loop can capture it, so a
 * one-batch test passes against the broken worker. The property that actually distinguishes fixed
 * from broken is <em>batch B is still claimed after batch A has fully drained, in the same worker
 * lifetime</em> — no restart between the batches.
 *
 * <p><strong>Waiting for the enrichment tail.</strong> Step 3 does not merely wait for the queue
 * to reach zero; it waits for the background enrichment counters to stop moving
 * ({@link #enrichmentSignature} unchanged across {@link #STABLE_SAMPLES} consecutive fresh polls,
 * on a drained queue). That is the exact state in which the livelock began — the tight loop only
 * became pathological once it ran out of documents it could actually advance. A fixed-duration
 * sleep would not establish that precondition; a moving-counter check does.
 *
 * <p><strong>Oracle.</strong> {@code indexedDocuments} must strictly increase over its stable
 * post-batch-A value and the queue must drain again, within a bounded window. A timeout here is
 * the failure this test exists to catch: it means batch B was admitted but never claimed.
 */
@DisplayName("Two-batch ingest starvation (tempdoc 798 B1 / T4)")
@Timeout(value = 6, unit = TimeUnit.MINUTES)
class IngestStarvationE2ETest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final IsolatedBackendFixture BACKEND = new IsolatedBackendFixture();
  private static final HttpClient CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final Duration POLL = Duration.ofMillis(500);

  /** Startup/seed indexing plus its enrichment tail must settle before batch A is meaningful. */
  private static final Duration STARTUP_IDLE_TIMEOUT = Duration.ofSeconds(90);

  /** Batch A: admitted → drained → enrichment tail idle. */
  private static final Duration BATCH_A_IDLE_TIMEOUT = Duration.ofSeconds(90);

  /** Batch B: the starvation window. Exceeding this is the livelock. */
  private static final Duration BATCH_B_TIMEOUT = Duration.ofSeconds(90);

  /** Consecutive unchanged enrichment samples that count as "the enrichment tail went idle". */
  private static final int STABLE_SAMPLES = 6;

  private static final int DOCS_PER_BATCH = 4;

  /** Comfortably past ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS (2000) so chunk docs get written. */
  private static final int DOC_CHARS = 6000;

  @BeforeAll
  static void startBackend() throws Exception {
    BACKEND.start();
  }

  @AfterAll
  static void stopBackend() {
    BACKEND.stop();
  }

  @Test
  @DisplayName("batch B is still claimed and indexed after batch A drains, same worker lifetime")
  void secondBatchIsClaimedAfterFirstBatchFullyDrains() throws Exception {
    // 1. Let startup/seed indexing and its enrichment tail settle, so batch A starts from a
    //    quiet worker and the docCount deltas below are attributable to our batches.
    assertTrue(
        awaitEnrichmentTailIdle(STARTUP_IDLE_TIMEOUT),
        "startup indexing + enrichment tail never went idle within "
            + STARTUP_IDLE_TIMEOUT.toSeconds()
            + "s. Last status: "
            + lastStatusBody);
    long docsBeforeA = indexedDocuments();

    // 2. Batch A.
    Path corpusA = createBatch("a");
    String operationA = ingest(corpusA);
    assertTrue(!operationA.isBlank(), "batch A has a durable operation identity");

    // 3. Batch A must drain AND the background enrichment tail must go idle — the precondition
    //    under which the tight loop used to capture the indexing loop forever.
    assertTrue(
        awaitEnrichmentTailIdle(BATCH_A_IDLE_TIMEOUT),
        "batch A never drained to a quiet enrichment tail within "
            + BATCH_A_IDLE_TIMEOUT.toSeconds()
            + "s. Either indexing stalled on the FIRST batch, or the background enrichment "
            + "counters never stopped moving (a runaway backfill loop). Last status: "
            + lastStatusBody);
    long docsAfterA = indexedDocuments();
    assertTrue(
        docsAfterA > docsBeforeA,
        "batch A actually indexed documents (harness sanity: without this, a batch-B failure "
            + "below would not be attributable to starvation). indexedDocuments "
            + docsBeforeA
            + " → "
            + docsAfterA);

    // 4. Batch B — same worker lifetime, no restart. This is the whole point of the test.
    Path corpusB = createBatch("b");
    String operationB = ingest(corpusB);
    assertTrue(!operationB.isBlank(), "batch B has a durable operation identity");

    // 5. Batch B must be claimed and indexed within a bounded window.
    boolean drained = awaitBatchIndexed(docsAfterA, BATCH_B_TIMEOUT);
    assertTrue(
        drained,
        "batch B operation "
            + operationB
            + " was accepted but never got claimed and indexed within "
            + BATCH_B_TIMEOUT.toSeconds()
            + "s while the worker stayed up. This is the tempdoc-798 ingest livelock: the "
            + "background-enrichment loop never returns to poll the job queue, so every ingest "
            + "after the first sits PENDING with zero errors and green health. "
            + "maxProcessingJobsCountSeen="
            + maxProcessingSeen
            + ", indexedDocuments floor="
            + docsAfterA
            + ". Last status: "
            + lastStatusBody);
  }

  // ------------------------------------------------------------------------------
  // Await helpers — bounded condition-polls, never fixed sleeps
  // ------------------------------------------------------------------------------

  private static volatile String lastStatusBody = "<no status read>";
  private static volatile long maxProcessingSeen = 0L;

  /**
   * Polls {@code /api/knowledge/status} until the job queue is drained AND the background
   * enrichment counters have stopped moving for {@link #STABLE_SAMPLES} consecutive fresh
   * samples. "Fresh" excludes cached/stale views ({@code statusStale=true}), which would
   * otherwise look identical to a quiet worker.
   */
  private static boolean awaitEnrichmentTailIdle(Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    String previous = null;
    int stableStreak = 0;
    while (System.currentTimeMillis() < deadline) {
      String signature = null;
      try {
        JsonNode status = status();
        maxProcessingSeen = Math.max(maxProcessingSeen, status.path("processingJobsCount").asLong(0));
        boolean queueDrained =
            status.path("pendingJobsCount").asLong(-1L) == 0L
                && status.path("processingJobsCount").asLong(-1L) == 0L;
        boolean fresh = !status.path("statusStale").asBoolean(false);
        signature = enrichmentSignature(status);
        if (queueDrained && fresh && signature.equals(previous)) {
          if (++stableStreak >= STABLE_SAMPLES) {
            return true;
          }
        } else {
          stableStreak = 0;
        }
      } catch (Exception e) {
        stableStreak = 0;
      }
      previous = signature;
      Thread.sleep(POLL.toMillis());
    }
    return false;
  }

  /**
   * Polls until {@code indexedDocuments} has strictly increased past {@code floor} AND the queue
   * has drained again. Returns false on timeout — the starvation signal.
   */
  private static boolean awaitBatchIndexed(long floor, Duration timeout) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeout.toMillis();
    while (System.currentTimeMillis() < deadline) {
      try {
        JsonNode status = status();
        maxProcessingSeen = Math.max(maxProcessingSeen, status.path("processingJobsCount").asLong(0));
        boolean grew = status.path("indexedDocuments").asLong(0L) > floor;
        boolean queueDrained =
            status.path("pendingJobsCount").asLong(-1L) == 0L
                && status.path("processingJobsCount").asLong(-1L) == 0L;
        if (grew && queueDrained && !status.path("statusStale").asBoolean(false)) {
          return true;
        }
      } catch (Exception ignored) {
        // keep polling; the deadline is the failure signal
      }
      Thread.sleep(POLL.toMillis());
    }
    return false;
  }

  /**
   * The moving parts of background enrichment. When every one of these holds still on a drained
   * queue, the enrichment tail is done (or, in the broken worker, spinning without progress —
   * which is precisely the state batch B has to survive).
   */
  private static String enrichmentSignature(JsonNode status) {
    return status.path("indexedDocuments").asLong(0L)
        + "|" + status.path("activeIndexedDocuments").asLong(0L)
        + "|" + status.path("buildingIndexedDocuments").asLong(0L)
        + "|" + status.path("embeddingCoveragePercent").asDouble(0.0)
        + "|" + status.path("spladeCoveragePercent").asDouble(0.0)
        + "|" + status.path("pendingNerCount").asInt(0)
        + "|" + status.path("completedNerCount").asInt(0)
        + "|" + status.path("chunkEmbeddingReady").asBoolean(false)
        + "|" + status.path("switchBufferDepth").asLong(0L);
  }

  private static long indexedDocuments() throws Exception {
    return status().path("indexedDocuments").asLong(0L);
  }

  // ------------------------------------------------------------------------------
  // HTTP helpers
  // ------------------------------------------------------------------------------

  /**
   * Creates a small corpus under the fixture data dir so its tempdir cleanup removes it.
   *
   * <p>Each document is deliberately pushed past {@code ChunkDocumentWriter.CHUNK_THRESHOLD_CHARS}
   * (2000) so the ingest produces CHUNK documents, not just parent documents. Chunk docs are the
   * population the tempdoc-798 tight loop spun on — a corpus of one-line files would leave the
   * background-enrichment path with nothing of the implicated shape to work over.
   */
  private static Path createBatch(String label) throws Exception {
    String marker = "starve-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
    Path corpus = Files.createDirectory(BACKEND.dataDir().resolve(marker));
    for (int i = 0; i < DOCS_PER_BATCH; i++) {
      StringBuilder text = new StringBuilder(DOC_CHARS + 128);
      text.append("Ingest starvation batch ").append(marker).append(" document ").append(i);
      int sentence = 0;
      while (text.length() < DOC_CHARS) {
        text.append(" Paragraph ")
            .append(sentence++)
            .append(" of document ")
            .append(i)
            .append(" in ")
            .append(marker)
            .append(" describes indexing lifecycle behaviour with unique retrievable wording.");
      }
      Files.writeString(corpus.resolve("doc-" + i + ".txt"), text.toString());
    }
    return corpus;
  }

  private static String ingest(Path corpus) throws Exception {
    String body =
        MAPPER.writeValueAsString(Map.of("paths", List.of(corpus.toAbsolutePath().toString())));
    JsonNode parsed = MAPPER.readTree(httpPost("/api/knowledge/ingest", body));
    if (!parsed.path("success").asBoolean()) {
      throw new IllegalStateException("Ingest operation failed: " + parsed);
    }
    return parsed.path("structuredData").path("operationKey").asText("");
  }

  private static JsonNode status() throws Exception {
    String body = httpGet("/api/knowledge/status");
    lastStatusBody = body;
    return MAPPER.readTree(body);
  }

  private static String httpGet(String path) throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + BACKEND.port() + path))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException(
          "GET " + path + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }

  private static String httpPost(String path, String jsonBody) throws Exception {
    HttpResponse<String> resp =
        CLIENT.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + BACKEND.port() + path))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    if (resp.statusCode() != 200) {
      throw new IllegalStateException(
          "POST " + path + " returned " + resp.statusCode() + ": " + resp.body());
    }
    return resp.body();
  }
}
