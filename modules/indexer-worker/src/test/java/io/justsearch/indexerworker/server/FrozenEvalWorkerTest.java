/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.disambiguation.EntityClusterStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.ipc.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** Real committed Lucene/SQLite fixtures; no encoder models or quality inputs are used. */
@Timeout(90)
final class FrozenEvalWorkerTest {
  @TempDir Path directory;
  final Map<String, String> properties = new HashMap<>();
  final ConfigStore previous = ConfigStore.globalOrNull();

  void property(String key, String value) {
    if (!properties.containsKey(key)) properties.put(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  @AfterEach void restore() {
    properties.forEach((key, value) -> {
      if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    });
    ConfigStore.restoreGlobal(ConfigStore.global(), previous);
  }

  WorkerBootFixture.Layout seed() throws Exception {
    return seed(null);
  }

  WorkerBootFixture.Layout seed(String fingerprintOverride) throws Exception {
    var layout = WorkerBootFixture.layout(directory);
    var configuration = new HashMap<String, String>();
    for (var key : new EnvRegistry[] {EnvRegistry.AI_EMBED_ENABLED, EnvRegistry.SPLADE_ENABLED,
        EnvRegistry.NER_ENABLED, EnvRegistry.RERANK_ENABLED, EnvRegistry.RERANK_CHUNKS_ENABLED,
        EnvRegistry.CITATION_SCORER_ENABLED, EnvRegistry.BGE_M3_ENABLED}) {
      configuration.put(key.configKey(), "false");
      property(key.sysProp(), "false");
    }
    configuration.put("justsearch.models.dir", directory.resolve("no-models").toString());
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE", configuration);
    WorkerBootFixture.seedDocument(layout.activePath(), fingerprintOverride,
        directory.resolve("removed-source.txt").toString(), "retained#0", "frozen witness document");
    try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) { queue.open(); }
    try (var clusters = new EntityClusterStore(layout.dataDir().resolve("entity-clusters.db"))) {
      clusters.open(); clusters.upsert("Witness", "PERSON", "cluster-1", "Witness", 1.0);
    }
    return layout;
  }

  void frozen() {
    property("justsearch.eval.mode", "true");
    property("justsearch.eval.frozen", "true");
  }

  KnowledgeServer server(WorkerBootFixture.Layout layout, TestEngineExecutors executors) {
    return new KnowledgeServer(executors, WorkerBootFixture.workerConfig(layout.dataDir()));
  }

  @Test void preservesCommittedPayloadThroughRealCompositionSearchMutationsAndStop() throws Exception {
    var layout = seed();
    var before = payload(layout.indexBase());
    var schema = schema(layout.dataDir().resolve("jobs.db"));
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      server.start();
      assertTrue(server.awaitIndexRecoveryModels(30_000), "actual model-free composition must finish");
      try (var serving = server.captureServingView()) {
        assertInstanceOf(io.justsearch.adapters.lucene.runtime.ReadOnlyRuntime.class, serving.searchRuntime());
        assertSame(serving.searchRuntime(), serving.ingestRuntime());
        assertEquals(layout.activePath(), serving.searchRuntime().openedIndexPath());
      }
      var services = server.appServices();
      var status = services.ingestService().indexStatus(StatusRequest.getDefaultInstance(), CallContext.none());
      assertEquals(1, status.getCore().getDocCount());
      assertEquals(layout.genManager().readStateBestEffort().active_generation(), status.getMigration().getActiveGenerationId());
      var result = services.searchService().search(SearchRequest.newBuilder()
          .setQuery("witness").setLimit(10).build(), CallContext.none());
      assertTrue(result.getResultsCount() > 0, "the original document is searchable even without its source");
      assertNotNull(server.disambiguationService);
      assertTrue(server.disambiguationService.isAvailable(), "persisted cluster query wiring stays available");
      var ingest = services.ingestService();
      assertThrows(RuntimeException.class, () -> ingest.submitBatch(BatchRequest.newBuilder()
          .addFilePaths(directory.resolve("new-source.txt").toString()).build(), CallContext.none()));
      assertFalse(ingest.resetIndex(ResetIndexRequest.getDefaultInstance(), CallContext.none()).getSuccess());
      assertThrows(RuntimeException.class, () -> ingest.watchRoot(WatchRootRequest.newBuilder()
          .setRootPath(directory.toString()).build(), CallContext.none()));
      assertThrows(RuntimeException.class, () -> ingest.deleteByPath(DeleteByPathRequest.newBuilder()
          .setPath(directory.toString()).build(), CallContext.none()));
      assertThrows(RuntimeException.class, () -> ingest.deleteById(DeleteByIdRequest.newBuilder()
          .setDocId(directory.resolve("removed-source.txt").toString()).build(), CallContext.none()));
      assertThrows(RuntimeException.class, () -> ingest.unwatchRoot(UnwatchRootRequest.newBuilder()
          .setClearAllRoots(true).build(), CallContext.none()));
      assertFalse(ingest.startMigration(MigrationStartRequest.newBuilder().setReason("manual").build(), CallContext.none()).getAccepted());
      assertFalse(ingest.pauseMigration(MigrationPauseRequest.getDefaultInstance(), CallContext.none()).getAccepted());
      assertFalse(ingest.resumeMigration(MigrationResumeRequest.getDefaultInstance(), CallContext.none()).getAccepted());
      assertFalse(ingest.requestCutover(MigrationCutoverRequest.newBuilder().setForceSwitching(true).build(), CallContext.none()).getAccepted());
      assertFalse(ingest.runIndexGc(IndexGcRequest.getDefaultInstance(), CallContext.none()).getAccepted());
      ingest.seedConfiguredRoots(List.of(directory));
      ingest.retryRootVolumeSeeding();
      services.startIndexingLoop(); // late entry cannot enable root reconciliation or a producer
      assertFalse(Files.exists(layout.indexBase().resolve("root-volumes.properties")));
      assertFalse(Files.exists(layout.indexBase().resolve("root-volumes-pending.properties")));
      assertEquals(before, payload(layout.indexBase()));
    }
    assertEquals(before, payload(layout.indexBase()));
    assertEquals(schema, schema(layout.dataDir().resolve("jobs.db")));
  }

  @Test void ordinaryPreparedWorkerStillUpgradesAndAllowsMigration() throws Exception {
    var layout = seed();
    property("justsearch.eval.mode", "true");
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      server.start();
      assertTrue(server.awaitIndexRecoveryModels(30_000));
      try (var serving = server.captureServingView()) {
        assertInstanceOf(io.justsearch.adapters.lucene.runtime.RunningRuntime.class, serving.ingestRuntime());
      }
      assertTrue(server.appServices().ingestService().startMigration(MigrationStartRequest.newBuilder()
          .setReason("manual").build(), CallContext.none()).getAccepted());
    }
  }

  @Test void missingGenerationRefusesWithoutCreatingOrAdopting() throws Exception {
    var layout = seed();
    Files.delete(layout.indexBase().resolve("state.json"));
    var before = payload(layout.indexBase());
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertThrows(Exception.class, server::start);
    }
    assertEquals(before, payload(layout.indexBase()));
  }

  @Test void migrationRefusesBeforeQueueRepair() throws Exception {
    var layout = seed();
    layout.genManager().startMigration("manual");
    var before = payload(layout.indexBase());
    byte[] database = Files.readAllBytes(layout.dataDir().resolve("jobs.db"));
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertThrows(Exception.class, server::start);
    }
    assertEquals(before, payload(layout.indexBase()));
    assertArrayEquals(database, Files.readAllBytes(layout.dataDir().resolve("jobs.db")));
  }

  @Test void foreignCommittedShapeRefusesWithoutRebuild() throws Exception {
    var layout = seed("f".repeat(64));
    var before = payload(layout.indexBase());
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertThrows(Exception.class, server::start);
    }
    assertEquals(before, payload(layout.indexBase()));
  }

  @Test void pendingQueueWorkRefusesAndRetainsItsAdmission() throws Exception {
    var layout = seed();
    Path source = Files.writeString(directory.resolve("pending.txt"), "never extract me");
    try (var queue = new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) {
      queue.open(); assertEquals(1, queue.enqueue(List.of(source), "personal"));
    }
    byte[] before = Files.readAllBytes(layout.dataDir().resolve("jobs.db"));
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertThrows(Exception.class, server::start);
    }
    assertArrayEquals(before, Files.readAllBytes(layout.dataDir().resolve("jobs.db")));
    assertEquals("never extract me", Files.readString(source));
  }

  @Test void incompleteCurrentQueueRefusesBeforeSchemaRepair() throws Exception {
    var layout = seed();
    Path database = layout.dataDir().resolve("jobs.db");
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) { statement.execute("DROP TABLE document_identity"); }
    byte[] before = Files.readAllBytes(database);
    frozen();
    try (var executors = new TestEngineExecutors(); var server = server(layout, executors)) {
      assertThrows(Exception.class, server::start);
    }
    assertArrayEquals(before, Files.readAllBytes(database));
  }

  static Map<String, String> payload(Path root) throws Exception {
    var result = new TreeMap<String, String>();
    try (var paths = Files.walk(root)) {
      for (var path : paths.filter(Files::isRegularFile).toList()) {
        result.put(root.relativize(path).toString(), HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))));
      }
    }
    return result;
  }

  static List<String> schema(Path path) throws Exception {
    try (var snapshot = io.justsearch.configuration.persistence.SqliteStoreSnapshot.open(path);
        var statement = snapshot.connection().createStatement();
        var rows = statement.executeQuery("SELECT type,name,sql FROM sqlite_master ORDER BY type,name")) {
      var result = new ArrayList<String>();
      while (rows.next()) result.add(rows.getString(1) + ":" + rows.getString(2) + ":" + rows.getString(3));
      return result;
    }
  }
}
