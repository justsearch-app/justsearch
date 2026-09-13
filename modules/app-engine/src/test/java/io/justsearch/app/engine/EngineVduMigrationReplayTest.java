/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.ipc.StatusResponse;
import io.justsearch.ipc.VduUpdateOutcome;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Production-composition proof for the C2-2 legacy VDU replay generation gate.
 *
 * <p>The lower-level replay tests can inject an eligibility supplier directly. This test instead
 * boots the real {@code EngineRoot}/{@code KnowledgeServer} composition across a paused migration.
 * It first puts the parent into the real Green runtime, then plants a pre-C2 VDU row in the durable
 * queue. The paused-migration boot must preserve that exact row; forcing the production eligibility
 * answer true makes this assertion fail before source enumeration resumes. Only a later boot, after
 * the generation pointer has been promoted, may replay and remove it.
 *
 * <p>{@link EngineTestHarness#restart()} is an explicit test action. The assertions here establish
 * behavior after each reopen; they do not claim that the product automatically restarts at
 * cutover.
 */
@Timeout(900)
final class EngineVduMigrationReplayTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private EngineTestHarness engine;

  @AfterEach
  void closeEngine() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName(
      "legacy VDU replay waits for the promoted serving generation across real Engine boots")
  void legacyVduReplayWaitsForPromotedServingGeneration(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path watchedRoot = dataDir.resolve("watched");
    Files.createDirectories(watchedRoot);
    Path source = watchedRoot.resolve("document.txt");

    String blueMarker = marker("blue");
    String replacementMarker = marker("replacement");
    Files.writeString(source, "initial blue source " + blueMarker);
    writeWatchedRoots(dataDir, watchedRoot);

    engine = EngineTestHarness.start(dataDir);
    assertEquals(
        1,
        engine
            .client()
            .submitBatch(List.of(source), TestEngineContexts.FOREGROUND)
            .getAcceptedCount(),
        "the source must be admitted to Blue");
    assertTrue(engine.awaitIndexed(1, 120_000), "the Blue source must finish indexing");
    assertTrue(engine.awaitSearchable(blueMarker, 60_000), "the Blue baseline must be searchable");

    String activeBefore = engine.status().getMigration().getActiveGenerationId();
    assertFalse(activeBefore.isBlank(), "the Blue generation id must be visible");
    assertTrue(
        engine.client().startMigration("vdu_replay_gate", TestEngineContexts.FOREGROUND).accepted(),
        "startMigration must be accepted");
    assertTrue(
        engine.client().pauseMigration("vdu_replay_gate", TestEngineContexts.FOREGROUND),
        "pauseMigration must be accepted before the migration boot");

    engine.restart();
    StatusResponse paused = engine.status();
    assertTrue(paused.getMigration().getPaused(), "the pause flag must survive reopening");
    assertEquals(
        "MIGRATING",
        paused.getMigration().getMigrationState().trim().toUpperCase(java.util.Locale.ROOT),
        "the reopened Engine must own distinct Blue and Green runtimes");
    assertFalse(
        paused.getMigration().getBuildingGenerationId().isBlank(),
        "a real building generation must exist before the legacy row is introduced");

    assertEquals(
        1,
        engine
            .client()
            .submitBatch(List.of(source), TestEngineContexts.FOREGROUND)
            .getAcceptedCount(),
        "ordinary indexing must still target Green while only migration orchestration is paused");
    assertTrue(
        awaitBuildingIndexed(1, 120_000),
        "Green must contain the parent and the ordinary queue must drain before replay is tested");

    closeEngine();
    Files.writeString(source, "replacement source from resumed enumeration " + replacementMarker);

    Path queuePath = dataDir.resolve("jobs.db");
    String docId = PathNormalizer.normalizeKey(source);
    assertFalse(docId.isBlank(), "the source path must normalize to the indexed parent id");
    SwitchBufferCapableQueue.SwitchBufferOp seeded =
        seedLegacyVduUpdate(queuePath, docId);

    engine = EngineTestHarness.start(dataDir);
    StatusResponse ineligibleBoot = engine.status();
    assertTrue(ineligibleBoot.getMigration().getPaused(), "the migration must still be paused");
    assertEquals(
        "MIGRATING",
        ineligibleBoot.getMigration().getMigrationState().trim().toUpperCase(java.util.Locale.ROOT),
        "the first replay boot must still be generation-ineligible");
    assertEquals(
        seeded,
        requireBuffered(queuePath, seeded.key()),
        "an ineligible real KnowledgeServer boot must retain the exact durable VDU version");
    assertTrue(engine.awaitSearchable(blueMarker, 5_000), "the paused boot still serves Blue");

    assertTrue(
        engine.client().resumeMigration(TestEngineContexts.FOREGROUND),
        "resumeMigration must release source enumeration and cutover");
    assertTrue(
        awaitEnumeratorAndQueueDrain(180_000),
        "the resumed enumerator must enqueue the rewritten source and drain its ordinary work");
    assertTrue(
        awaitActiveGenerationChanged(engine.indexBase(), activeBefore, 300_000),
        "the migration must durably promote Green before VDU replay becomes eligible");
    assertEquals(
        seeded,
        requireBuffered(queuePath, seeded.key()),
        "promotion alone must not consume the row in the still-open pre-promotion runtime");

    engine.restart();
    assertTrue(
        engine.awaitSearchable(replacementMarker, 120_000),
        "enumeration must replace the Green parent before content-preserving VDU replay");
    assertTrue(
        engine.awaitNotSearchable(blueMarker, 5_000),
        "the original seeded Green parent must have been replaced, not merely replayed onto");
    assertEquals(0L, bufferedDepth(queuePath), "the committed VDU version must be removed");
    assertNotEquals(
        activeBefore,
        engine.status().getMigration().getActiveGenerationId(),
        "the replayed parent must come from the promoted generation");

    engine.restart();
    assertTrue(
        engine.awaitSearchable(replacementMarker, 60_000),
        "the replacement source preserved by VDU replay must survive a later reopen");
    assertEquals(0L, bufferedDepth(queuePath), "the removed legacy row must stay absent");
  }

  private boolean awaitBuildingIndexed(long expected, long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      StatusResponse status = engine.status();
      if (status.getMigration().getBuildingDocCount() >= expected
          && status.getCore().getQueueDepth() == 0) {
        return true;
      }
      Thread.sleep(200);
    }
    return false;
  }

  private boolean awaitEnumeratorAndQueueDrain(long timeoutMs) throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      StatusResponse status = engine.status();
      if (status.getMigration().getEnumerator().getDone()
          && status.getMigration().getEnumerator().getFilesSeen() > 0
          && status.getMigration().getEnumerator().getFilesEnqueued() > 0
          && status.getCore().getQueueDepth() == 0) {
        return true;
      }
      Thread.sleep(250);
    }
    return false;
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp seedLegacyVduUpdate(
      Path queuePath, String docId) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("doc_id", docId);
    payload.put("extracted_content", "");
    payload.put("has_extracted_content", false);
    payload.put("vdu_status", "");
    payload.put("vdu_enrichment", "");
    payload.put("page_count", 1);
    // SUCCESS_EMPTY preserves source content, so a lost/failed re-enumeration cannot be hidden
    // by replay replacing the old parent's text. Row removal still requires the covering commit.
    payload.put("outcome", VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_EMPTY.getNumber());
    String key = "vdu_update:" + docId;
    try (SqliteJobQueue queue = openQueue(queuePath)) {
      assertTrue(
          queue.putSwitchBuffer(key, "VDU_UPDATE", JSON.writeValueAsString(payload)),
          "the frozen pre-C2 VDU row must be written");
      return queue.listSwitchBufferOps().stream()
          .filter(op -> key.equals(op.key()))
          .findFirst()
          .orElseThrow();
    }
  }

  private static SwitchBufferCapableQueue.SwitchBufferOp requireBuffered(Path queuePath, String key)
      throws Exception {
    try (SqliteJobQueue queue = openQueue(queuePath)) {
      return queue.listSwitchBufferOps().stream()
          .filter(op -> key.equals(op.key()))
          .findFirst()
          .orElseThrow();
    }
  }

  private static long bufferedDepth(Path queuePath) throws Exception {
    try (SqliteJobQueue queue = openQueue(queuePath)) {
      return queue.switchBufferDepth();
    }
  }

  private static SqliteJobQueue openQueue(Path queuePath) throws Exception {
    SqliteJobQueue queue = new SqliteJobQueue(queuePath);
    queue.open();
    return queue;
  }

  private static void writeWatchedRoots(Path dataDir, Path root) throws IOException {
    Files.createDirectories(dataDir);
    JSON.writeValue(
        dataDir.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", root.toString()))));
  }

  private static boolean awaitActiveGenerationChanged(Path indexBase, String before, long timeoutMs)
      throws Exception {
    Path statePath = indexBase.resolve("state.json");
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      if (Files.exists(statePath)) {
        try {
          JsonNode state = JSON.readTree(Files.readString(statePath, StandardCharsets.UTF_8));
          String active = state.path("active_generation").asText();
          if (!active.isBlank() && !active.equals(before)) {
            return true;
          }
        } catch (RuntimeException midWrite) {
          // state.json is atomically replaced; an observation during replacement just retries.
        }
      }
      Thread.sleep(250);
    }
    return false;
  }

  private static String marker(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }
}
