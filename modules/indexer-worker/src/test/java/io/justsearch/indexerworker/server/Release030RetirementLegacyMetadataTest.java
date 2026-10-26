/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static io.justsearch.indexerworker.server.Release030RetirementSafetyTest.queue;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.SwitchBufferCapableQueue;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** The released writer's DONE namespace survives a missing legacy boundary; absent Q proves none. */
@Timeout(300)
@org.junit.jupiter.api.extension.ExtendWith(io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementLegacyMetadataTest {
  @Test void namedDoneRawReceiptKeepsCollectionAndExactBoundary(@TempDir Path root) throws Exception {
    Release030Fixture.unpack("switching", root);
    var original = state(root);
    Path file = root.resolve("explicit.txt");
    // This DONE/SUCCESS_FULL row is from the real v0.3.0 writer, with no boundary column.
    assertReleasedDone(root, file);
    rawReceipt(root, file);
    Path neighbor = Files.writeString(root.resolve("neighbor-never-admitted.txt"), "unrequestedlegacyneighbor");
    saveOriginal(root, "named-original");
    var previous = ConfigStore.globalOrNull(); var installed = configure(root);
    try (var models = new ReleasedModelIdentities(root)) {
      try (var ex = new TestEngineExecutors(); var server = boot(root, ex)) {
        var op = raw(queue(server).listSwitchBufferOpsStrict(), file);
        var frozen = queue(server).release030RecoveryState().legacyExpectation(op);
        assertNotNull(frozen, "real original Q metadata must be frozen at boot");
        assertEquals("mcp-ingest", frozen.collection(), "missing boundary cannot discard known original collection");
        assertEquals(file, frozen.ingestionRoot(), "raw exact receipt grants only its own file boundary");
        activate(server);
        await(() -> state(root).previous_generation() == null, "named legacy exact admission recovers and retires B", 100);
        assertEquals("mcp-ingest", server.lifecycleManagerForTests().documentFieldOps()
            .getDocumentFieldOrThrow(key(file), io.justsearch.indexing.SchemaFields.COLLECTION));
        assertEquals(io.justsearch.indexerworker.loop.SourceContentHash.sha256(file), server.lifecycleManagerForTests()
            .documentFieldOps().getDocumentFieldOrThrow(key(file), io.justsearch.indexing.SchemaFields.SOURCE_SHA256));
        assertEquals(0, count(root, "path='" + key(neighbor) + "'"));
        assertTrue(search(server, "unrequestedlegacyneighbor").isEmpty());
        Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
      }
      try (var ex = new TestEngineExecutors(); var server = boot(root, ex)) {
        activate(server);
        assertEquals("mcp-ingest", server.lifecycleManagerForTests().documentFieldOps()
            .getDocumentFieldOrThrow(key(file), io.justsearch.indexing.SchemaFields.COLLECTION));
        Release030RetirementSafetyTest.assertRetired(root, original.building_generation());
      }
    } finally { try { saveFinal(root, "named-final"); } finally { ConfigStore.restoreGlobal(installed, previous); } }
  }

  @Test void missingOriginalQueueNamespaceStaysHeldAcrossChangedConfig(@TempDir Path root) throws Exception {
    Release030Fixture.unpack("switching", root);
    var original = state(root); Path file = root.resolve("explicit.txt");
    assertReleasedDone(root, file);
    // An older valid Q snapshot can lack this row; A's current namespace is not its receipt metadata.
    sql(root, "DELETE FROM jobs WHERE path='" + key(file) + "'");
    rawReceipt(root, file); saveOriginal(root, "missing-original");
    var previous = ConfigStore.globalOrNull(); var installed = configure(root);
    SwitchBufferCapableQueue.SwitchBufferOp first = null;
    try (var models = new ReleasedModelIdentities(root)) {
      for (int restart = 0; restart < 2; restart++) {
        var attempts = new AtomicInteger();
        try (var ex = new TestEngineExecutors(); var server = changedRoots(root, ex, "new-config-" + restart, attempts)) {
          var op = raw(queue(server).listSwitchBufferOpsStrict(), file);
          if (first == null) first = op; else assertEquals(first, op);
          assertNull(queue(server).release030RecoveryState().legacyExpectation(op),
              "missing Q and absent frozen namespace cannot be replaced by current configuration/default/A");
          activate(server);
          await(() -> attempts.get() >= 2, "real replay retries the unknown original admission", 30);
          assertEquals(op, raw(queue(server).listSwitchBufferOpsStrict(), file));
          assertEquals(0, count(root, "path='" + key(file) + "'"), "unknown namespace cannot invent a writer job");
          assertNull(queue(server).release030RecoveryState().legacyExpectation(op));
          assertTrue(search(server, "copper").contains(key(file)),
              "original indexed content remains searchable on each running boot");
          assertEquals("mcp-ingest", server.lifecycleManagerForTests().documentFieldOps()
              .getDocumentFieldOrThrow(key(file), io.justsearch.indexing.SchemaFields.COLLECTION),
              "unknown original receipt retains the original indexed namespace on both boots");
          assertEquals(original.active_generation(), state(root).active_generation());
          Release030RetirementSafetyTest.assertRetained(root, original.building_generation());
        }
      }
    } finally { try { saveFinal(root, "missing-final"); } finally { ConfigStore.restoreGlobal(installed, previous); } }
  }

  private static KnowledgeServer changedRoots(Path root, TestEngineExecutors ex, String collection,
      AtomicInteger attempts) throws Exception {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(ex, WorkerBootFixture.workerConfig(root.resolve("data")), null));
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of(
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/alpha"), collection),
        new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root.resolve("roots/beta"), collection)));
    server.bindExclusionRoots(() -> List.of(root.resolve("roots/alpha"), root.resolve("roots/beta")));
    server.installMigrationBarrierForTests(t -> { if (t.point().equals("release030-before-replay")) attempts.incrementAndGet(); });
    try { server.start(); } catch (Exception | Error failure) { server.close(); throw failure; }
    return server;
  }

  private static void assertReleasedDone(Path root, Path file) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db")); var q = c.createStatement()) {
      try (var r = q.executeQuery("PRAGMA user_version")) { assertTrue(r.next()); assertEquals(13, r.getInt(1)); }
      try (var r = q.executeQuery("SELECT state,collection,last_outcome_class FROM jobs WHERE path='" + key(file) + "'")) {
        assertTrue(r.next()); assertEquals("DONE", r.getString(1)); assertEquals("mcp-ingest", r.getString(2)); assertEquals("SUCCESS_FULL", r.getString(3));
      }
    }
  }
  private static void rawReceipt(Path root, Path file) throws Exception {
    // Seed only the authentic v13 raw receipt wire shape; never fabricate writer completion.
    sql(root, "INSERT OR REPLACE INTO switch_buffer(key,op,payload,last_updated) VALUES ('path:"
        + key(file) + "','UPSERT','" + key(file) + "'," + System.currentTimeMillis() + ")");
  }
  private static SwitchBufferCapableQueue.SwitchBufferOp raw(List<SwitchBufferCapableQueue.SwitchBufferOp> ops, Path file) {
    return ops.stream().filter(op -> op.op().equals("UPSERT") && op.payload().equals(key(file))).findFirst().orElseThrow();
  }
  private static void saveFinal(Path root, String label) throws Exception {
    String evidence = System.getenv("VERIFY_EVIDENCE"); if (evidence == null) return;
    Release030RetirementRootImageTest.capture(root, null, label,
        List.of(root.resolve("explicit.txt"), root.resolve("neighbor-never-admitted.txt")));
    Path folder = Path.of(evidence).resolve(label + "-stores");
    for (int n = 2; Files.exists(folder); n++) folder = Path.of(evidence).resolve(label + "-stores-" + n);
    Files.createDirectories(folder);
    // Servers have closed: preserve Q/WAL, sidecar, pointer copies and committed A/B bytes.
    try (var files = Files.walk(root.resolve("data"))) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        Path relative = root.resolve("data").relativize(file);
        if (relative.startsWith("telemetry") || relative.startsWith("logs")) continue;
        Path target = folder.resolve(relative); Files.createDirectories(target.getParent()); Files.copy(file, target);
      }
    }
  }

  private static void saveOriginal(Path root, String label) throws Exception {
    String evidence = System.getenv("VERIFY_EVIDENCE"); if (evidence == null) return;
    Path folder = Path.of(evidence).resolve(label);
    for (int n = 2; Files.exists(folder); n++) folder = Path.of(evidence).resolve(label + "-" + n);
    Files.createDirectories(folder);
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db")); var sql = c.createStatement()) {
      sql.execute("VACUUM INTO '" + folder.resolve("jobs.db") + "'");
    }
  }
}
