/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.CorruptionRebuildE2ETest} (tempdoc 628's self-heal guard).
 *
 * <p><b>The headline claim this pins, unchanged.</b> Corrupt the active index and the product must
 * detect it, <em>back it up rather than delete it</em>, and rebuild a losslessly equivalent index
 * from the source files still on disk — so the same document is searchable again. Not one clause of
 * that is a property of the two-process architecture: the detection is in the Lucene open path, the
 * backup and the rebuild-from-source are the recovery policy, and the sources are files. The
 * retired test spawned a Worker only because that was the only way to reach the index.
 *
 * <p><b>What "respawn until it heals" becomes.</b> The rebuild is a blue/green migration, and it
 * ends by asking for a restart the way any cutover does. The retired test looped on
 * {@code worker.isAlive()} and re-spawned; here the loop closes and re-opens the Engine
 * ({@link EngineTestHarness#start} / {@code close}) whenever the marker is not yet findable — a
 * re-open rather than {@link EngineTestHarness#restart()} because a boot that lands between two
 * rebuild generations can itself fail, and the loop has to tolerate that. Under one
 * JVM the restart request is a latch countdown the composition root owns
 * (KnowledgeServer.java:2109-2112) rather than a process exit anyone can observe. The budget and the
 * two assertions at the end — searchable again, and the damaged index preserved — are the retired
 * test's own.
 *
 * <p><b>Recovery posture.</b> The retired test relied on the shipped worker distribution's
 * {@code application.yaml}. There is no distribution here, so the two values it depended on are
 * contributed to the resolved config explicitly: {@code index.recovery.policy=BACKUP_REBUILD} and
 * {@code index.auto_recovery=true}. Stating them is stricter than inheriting them — the test can no
 * longer pass or fail because of a posture change made somewhere else.
 */
@Timeout(900)
final class EngineCorruptionRebuildTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final Map<String, String> RECOVERY_POSTURE =
      Map.of(
          "index.recovery.policy", "BACKUP_REBUILD",
          "index.auto_recovery", "true");

  private EngineTestHarness engine;

  @AfterEach
  void tearDown() {
    if (engine != null) {
      engine.close();
      engine = null;
    }
  }

  @Test
  @DisplayName("a corrupt index self-heals: the damaged index is backed up and the document is"
      + " rebuilt from source and searchable again")
  void corruptIndexRebuildsFromSourceAndIsSearchableAgain(@TempDir Path tempDir) throws Exception {
    Path dataDir = tempDir.resolve("data");
    Path docsDir = dataDir.resolve("rebuild-docs");
    Files.createDirectories(docsDir);

    String marker = "rebuildmarker" + System.nanoTime();
    Path file = docsDir.resolve("hello.txt");
    Files.writeString(file, "hello " + marker);
    Files.createDirectories(dataDir);
    JSON.writeValue(
        dataDir.resolve("watched_roots.json").toFile(),
        Map.of("roots", List.of(Map.of("path", docsDir.toString()))));

    // 1) Baseline: index the file and confirm it is searchable.
    engine = EngineTestHarness.start(dataDir, dataDir.resolve("index"), RECOVERY_POSTURE);
    Path indexBase = engine.indexBase();
    assertTrue(
        engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount() > 0,
        "the file must be accepted for indexing");
    assertTrue(engine.awaitIndexed(1, 120_000), "indexing must complete");
    assertTrue(engine.awaitSearchable(marker, 60_000), "the marker is searchable before corruption");

    // 2) Close the Engine to release the index handles, then corrupt the active generation's
    //    commit file. The close is the in-process equivalent of the retired test's graceful stop:
    //    Windows will not let us rewrite a file Lucene still has open.
    engine.close();
    engine = null;
    assertFalse(anyBackupExists(indexBase), "no backup may exist before the self-heal");
    corruptActiveSegmentsFile(indexBase);

    // 3) Re-open and let the Engine self-heal across however many rebuild generations it needs.
    //    The helper hands back the healed Engine (or null); the field takes it so @AfterEach
    //    closes it whatever the assertions below do.
    engine = recoverAndAwaitSearchable(dataDir, indexBase, marker, 300_000);
    assertNotNull(
        engine, "after corruption the Engine must rebuild from source and find the marker again");

    // 4) The damaged index was preserved, never deleted. This is the half of the claim that a
    //    "delete and rebuild" implementation would also satisfy for the search assertion above.
    assertTrue(anyBackupExists(indexBase), "the corrupt index must be backed up, never deleted");
  }

  // =========================================================================

  /**
   * Drives the Engine through its corruption self-heal: re-opens it whenever the marker is not yet
   * findable (the rebuild migration promotes a new generation and then asks for a restart, possibly
   * more than once).
   *
   * @return the healed Engine, still open, as soon as the marker is searchable again; {@code null}
   *     if the budget ran out, in which case every Engine this method opened has been closed
   */
  private EngineTestHarness recoverAndAwaitSearchable(
      Path dataDir, Path indexBase, String marker, long timeoutMs) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      EngineTestHarness reopened;
      try {
        reopened = EngineTestHarness.start(dataDir, indexBase, RECOVERY_POSTURE);
      } catch (Exception bootFailedMidRecovery) {
        // A boot that lands between two rebuild generations can fail; the loop re-opens.
        Thread.sleep(1_000);
        continue;
      }
      long budget = Math.min(30_000, Math.max(1_000, deadline - System.currentTimeMillis()));
      if (reopened.awaitSearchable(marker, budget)) {
        return reopened;
      }
      reopened.close();
      Thread.sleep(500);
    }
    return null;
  }

  /**
   * Overwrites a byte in the active generation's {@code segments_N} commit file so the next open
   * detects {@code CORRUPT_INDEX}. Verbatim from the retired test, retargeted at the index base
   * path the harness publishes.
   */
  private static void corruptActiveSegmentsFile(Path indexBase) throws IOException {
    Path statePath = indexBase.resolve("state.json");
    JsonNode state = JSON.readTree(Files.readString(statePath, StandardCharsets.UTF_8));
    String active = state.path("active_generation").asText();
    Path genDir = indexBase.resolve("indices").resolve(active);
    Path segments;
    try (Stream<Path> s = Files.list(genDir)) {
      segments =
          s.filter(p -> p.getFileName().toString().startsWith("segments_"))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("no segments_N in " + genDir));
    }
    byte[] bytes = Files.readAllBytes(segments);
    bytes[bytes.length / 2] = (byte) (bytes[bytes.length / 2] ^ 0xFF);
    Files.write(segments, bytes);
  }

  private static boolean anyBackupExists(Path indexBase) throws IOException {
    Path indices = indexBase.resolve("indices");
    if (!Files.isDirectory(indices)) {
      return false;
    }
    try (Stream<Path> s = Files.list(indices)) {
      return s.anyMatch(p -> p.getFileName().toString().contains(".bak-"));
    }
  }
}
