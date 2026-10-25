/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Completed, unjournalled release effects and uncertain source obligations across real boots. */
@Timeout(240)
@org.junit.jupiter.api.extension.ExtendWith(
    io.justsearch.indexerworker.services.NoVolumeIdentityExtension.class)
final class Release030RetirementSafetyTest {
  @Test
  void alreadyDoneExplicitFileOnServingGenerationSurvivesResume(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary);
    var original = state(root);
    Path explicit = root.resolve("explicit.txt");
    Files.writeString(root.resolve("neighbor.txt"), "unrequestedneighbor");
    Path sentinel = buildingSentinel(root);
    String owner = Files.readString(sentinel);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        Files.writeString(sentinel, "unverified ownership");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          assertTrue(search(server, "copper").contains(key(explicit)), "released A contains the explicit file");
          // The authentic admitted file already has its bytes on A. Model the released DONE row
          // using the real queue completion API, after proving those bytes, never invented DDL.
          assertEquals(Release030Fixture.sha256(Files.readAllBytes(explicit)),
              ((io.justsearch.adapters.lucene.runtime.LuceneRuntime)
                  Release030ExtractionRecoveryTest.field(server, "searchLifecycle"))
                  .documentFieldOps().getDocumentFieldOrThrow(
                  key(explicit), io.justsearch.indexing.SchemaFields.SOURCE_SHA256));
          queue(server).markDone(explicit);
          assertEquals(1, count(root, "path='" + key(explicit) + "' AND state='DONE'"));
        } finally { Files.writeString(sentinel, owner); }
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            if ("IDLE".equals(state(root).migration_state())) {
              activate(server);
            } else {
              Release030ExactFileRecoveryTest.activateNative(server);
              assertTrue(server.appServices().ingestService().resumeMigration(
                  io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(), CallContext.none()).getAccepted());
            }
            await(() -> "IDLE".equals(state(root).migration_state()) && state(root).previous_generation() == null,
                "completed explicit content is preserved while the old build retires", 90);
            assertTrue(search(server, "copper").contains(key(explicit)),
                "already-DONE explicit content outside every root must stay searchable");
            assertEquals(original.active_generation(), state(root).active_generation());
            assertRetired(root, original.building_generation());
            assertTrue(search(server, "unrequestedneighbor").isEmpty());
            assertEquals(0, count(root, "path='" + key(root.resolve("neighbor.txt")) + "'"),
                "DONE explicit recovery grants no authority to its neighbor");
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"migrating", "switching"})
  void unavailableRootKeepsBuildAndOldSearchWhileOtherMutationsProceed(String fixture,
      @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    var original = state(root);
    Path beta = root.resolve("roots/beta");
    Path updated = Files.writeString(beta.resolve("file-01.txt"), "returnedrootviolet");
    if (fixture.equals("migrating")) {
      try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
        queue.open();
        assertTrue(queue.enqueueAndBufferFileForGeneration(original.building_generation(),
            io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(updated).withinRoot(beta), null));
      }
    }
    Path held = Files.move(beta, root.resolve("held-beta"));
    Path unrelated = root.resolve("roots/alpha/unrelated.txt");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            Files.writeString(unrelated, "unrelatedcobalt");
            assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
                .newBuilder().addFilePaths(unrelated.toString()).setRootAdded(true).build(), CallContext.none())
                .getAcceptedCount());
            await(() -> search(server, "unrelatedcobalt").contains(key(unrelated)),
                "unrelated writes remain available during unresolved replay");
            assertEquals(original.active_generation(), state(root).active_generation());
            assertEquals(original.building_generation(), state(root).previous_generation());
            assertRetained(root, original.building_generation());
            assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
            assertTrue(search(server, "nebula").contains(key(beta.resolve("file-01.txt"))));
          }
        }
        Files.move(held, beta);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          await(() -> state(root).previous_generation() == null,
              "returned root resolves its durable obligation and permits B retirement", 100);
          assertEquals(original.active_generation(), state(root).active_generation());
          assertTrue(search(server, "unrelatedcobalt").contains(key(unrelated)));
          assertTrue(search(server, "returnedrootviolet").contains(key(updated)));
          assertRetired(root, original.building_generation());
          assertFalse(search(server, "nebula").contains(key(updated)));
        }
      }
    } finally {
      if (Files.exists(held)) Files.move(held, beta);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void reappearedLegacyDeleteProvesCurrentBytesBeforeAcknowledgement(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("switching", temporary);
    var original = state(root);
    Path file = Files.writeString(root.resolve("roots/beta/file-01.txt"), "reappearedviolet");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            await(() -> state(root).previous_generation() == null
                && search(server, "reappearedviolet").contains(key(file)),
                "a reappeared DELETE is acknowledged only for A's current committed bytes", 100);
            assertEquals(original.active_generation(), state(root).active_generation());
            assertRetired(root, original.building_generation());
            assertFalse(search(server, "nebula").contains(key(file)));
            assertTrue(queue(server).listSwitchBufferOpsStrict().isEmpty());
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"analyzer_fp", "vector_format", "unresolved-model"})
  void uncertifiedServingGenerationKeepsOriginalMigration(String failure, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary);
    var original = state(root);
    Path active = Release030CompatibilityTest.generation(root);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        if (failure.equals("unresolved-model")) {
          Class<?> type = io.justsearch.indexerworker.embed.EmbeddingFingerprint.class;
          var field = type.getDeclaredField("cachedResult"); field.setAccessible(true);
          @SuppressWarnings("unchecked")
          var cache = (java.util.concurrent.atomic.AtomicReference<Object>) field.get(null);
          var constructor = Class.forName(type.getName() + "$CachedResult")
              .getDeclaredConstructor(java.util.Optional.class, java.util.Optional.class);
          constructor.setAccessible(true);
          cache.set(constructor.newInstance(java.util.Optional.of(root.resolve("unreadable-model.onnx")),
              java.util.Optional.empty()));
        } else {
          var metadata = new java.util.HashMap<>(Release030CompatibilityTest.readMetadata(active));
          var json = new tools.jackson.databind.ObjectMapper();
          var inputs = (tools.jackson.databind.node.ObjectNode) json.readTree(metadata.get("index_fingerprint_inputs"));
          inputs.put(failure, failure.equals("analyzer_fp") ? "b".repeat(64) : "incompatible-vector-format");
          metadata.put("index_fingerprint_inputs", json.writeValueAsString(inputs));
          metadata.remove("index_fingerprint");
          try (var directory = org.apache.lucene.store.FSDirectory.open(active);
              var writer = new org.apache.lucene.index.IndexWriter(directory,
                  new org.apache.lucene.index.IndexWriterConfig().setOpenMode(
                      org.apache.lucene.index.IndexWriterConfig.OpenMode.APPEND))) {
            writer.setLiveCommitData(metadata.entrySet()); writer.commit();
          }
        }
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
            assertEquals(original.migration_state(), state(root).migration_state());
            assertRetained(root, original.building_generation());
          }
        }
        assertEquals(original.migration_state(), state(root).migration_state());
        assertEquals(original.active_generation(), state(root).active_generation());
        assertEquals(original.building_generation(), state(root).building_generation());
        var checkpoint = new java.util.Properties();
        try (var input = Files.newInputStream(root.resolve("data/upgrade-030-recovery.properties"))) {
          checkpoint.load(input);
        }
        assertNull(checkpoint.getProperty("retire|active"), "uncertainty never becomes a cancellation certificate");
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @ParameterizedTest
  @ValueSource(strings = {"migrating", "switching"})
  void corruptCommitKeepsNativeRecoveryInsteadOfRetiring(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    var original = state(root);
    Path active = Release030CompatibilityTest.generation(root);
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        java.util.List<io.justsearch.indexerworker.queue.SwitchBufferCapableQueue.SwitchBufferOp> before;
        try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
          queue.open(); before = queue.listSwitchBufferOpsStrict();
        }
        String commit;
        try (var directory = org.apache.lucene.store.FSDirectory.open(active);
            var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
          commit = reader.getIndexCommit().getSegmentsFileName();
        }
        byte[] damaged = {1, 2, 3};
        Files.write(active.resolve(commit), damaged);
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopMonitor(server);
          assertEquals(original.migration_state(), state(root).migration_state());
          assertEquals(original.active_generation(), state(root).active_generation());
          assertEquals(original.building_generation(), state(root).building_generation());
          assertTrue(Files.isDirectory(root.resolve("data/index/default/indices").resolve(original.building_generation())));
          assertEquals(before, queue(server).listSwitchBufferOpsStrict(), "native recovery retains the same receipts as base");
          assertTrue(search(server, "nebula").isEmpty(), "today's backup-first path boots an empty replacement A");
          assertTrue(io.justsearch.adapters.lucene.runtime.IndexRecoveryMarker.exists(active));
          try (var siblings = Files.list(active.getParent())) {
            var backups = siblings.filter(path -> path.getFileName().toString()
                .startsWith(original.active_generation() + ".bak-")).toList();
            assertEquals(1, backups.size(), "today's backup-first recovery retains the corrupt original");
            assertArrayEquals(damaged, Files.readAllBytes(backups.getFirst().resolve(commit)));
          }
          var checkpoint = new java.util.Properties();
          try (var input = Files.newInputStream(root.resolve("data/upgrade-030-recovery.properties"))) {
            checkpoint.load(input);
          }
          assertNull(checkpoint.getProperty("retire|active"), "a corrupt commit never certifies obsolete retirement");
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void modernUnownedAdmissionCannotBorrowRetiredBuildAuthority(@TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary);
    var original = state(root);
    Path file = Files.writeString(root.resolve("explicit.txt"), "unownedreplacementviolet");
    Path neighbor = Files.writeString(root.resolve("neighbor.txt"), "unrequestedneighbor");
    Files.delete(root.resolve("roots/beta/file-01.txt"));
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      assertEquals(1, queue.enqueueEntries(java.util.List.of(
          io.justsearch.indexerworker.queue.JobQueue.EnqueueEntry.stat(file)), null));
    }
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int restart = 0; restart < 2; restart++) {
          try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
            assertEquals("IDLE", state(root).migration_state());
            activate(server);
            Path unrelated = Files.writeString(root.resolve("roots/alpha/unrelated.txt"), "independentauthoritycobalt");
            assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
                .newBuilder().addFilePaths(unrelated.toString()).setRootAdded(true).build(), CallContext.none())
                .getAcceptedCount());
            await(() -> search(server, "independentauthoritycobalt").contains(key(unrelated)),
                "normal admissions proceed while unowned recovery remains quarantined");
            assertFalse(queue(server).isRelease030ExactFile(file));
            assertTrue(search(server, "copper").contains(key(file)), "A retains the acknowledged original bytes");
            assertTrue(search(server, "unownedreplacementviolet").isEmpty());
            assertEquals(original.building_generation(), state(root).previous_generation());
            assertRetained(root, original.building_generation());
            assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
            assertEquals(1, count(root, "path='" + key(file)
                + "' AND ingestion_root IS NULL AND unit_revision LIKE 'release030-unscoped/%'"));
          }
        }
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          activate(server);
          assertEquals(1, server.appServices().ingestService().submitBatch(io.justsearch.ipc.BatchRequest
              .newBuilder().addFilePaths(file.toString()).setRootAdded(true).setForceReindex(true).build(),
              CallContext.none()).getAcceptedCount());
          await(() -> search(server, "unownedreplacementviolet").contains(key(file))
              && state(root).previous_generation() == null, "a deliberate exact-file admission settles its own debt", 100);
          assertTrue(search(server, "nebula").contains(key(root.resolve("roots/alpha/file-01.txt"))));
          assertFalse(search(server, "nebula").contains(key(root.resolve("roots/beta/file-01.txt"))));
          assertTrue(search(server, "unrequestedneighbor").isEmpty());
          assertEquals(0, count(root, "path='" + key(neighbor) + "'"));
          assertEquals(original.active_generation(), state(root).active_generation());
          assertRetired(root, original.building_generation());
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  static void assertRetained(Path root, String building) {
    assertTrue(Files.isDirectory(root.resolve("data/index/default/indices").resolve(building)),
        "uncertainty retains the physical B generation");
    assertTrue(Files.exists(root.resolve("data/upgrade-030-recovery.properties")));
  }

  static void assertRetired(Path root, String building) throws Exception {
    Path base = root.resolve("data/index/default");
    try {
      await(() -> {
        if (Files.exists(base.resolve("migration_progress.json"))
            || Files.exists(base.resolve("migration_progress.json.tmp"))) return false;
        try (var entries = Files.list(base.resolve("indices"))) {
          return entries.noneMatch(path -> path.getFileName().toString().startsWith(building));
        }
      }, "physical B and migration leftovers are retired", 15);
    } catch (AssertionError incomplete) {
      try (var entries = Files.list(base.resolve("indices"))) {
        throw new AssertionError("Retirement leftovers: " + entries.map(Path::getFileName).toList()
            + "; progress=" + Files.exists(base.resolve("migration_progress.json"))
            + "; progress.tmp=" + Files.exists(base.resolve("migration_progress.json.tmp"))
            + "; state=" + state(root), incomplete);
      }
    }
  }

  static SqliteJobQueue queue(KnowledgeServer server) throws Exception {
    return (SqliteJobQueue) Release030ExtractionRecoveryTest.field(server, "jobQueue");
  }

  static Path buildingSentinel(Path root) {
    return root.resolve("data/index/default/indices").resolve(state(root).building_generation())
        .resolve(".justsearch-generation.sentinel");
  }

  static void stopMonitor(KnowledgeServer server) throws Exception {
    if (Release030ExtractionRecoveryTest.field(server, "migrationCutoverThread") instanceof Thread monitor) {
      monitor.interrupt(); monitor.join(10_000); assertFalse(monitor.isAlive());
    }
  }
}
