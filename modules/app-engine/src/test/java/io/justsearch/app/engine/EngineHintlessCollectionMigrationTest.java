/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.adapters.lucene.runtime.LuceneRuntime;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FieldExistsQuery;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** b68264: actual worker writes, ordered deletions, restart recovery and live publication. */
@Timeout(600)
final class EngineHintlessCollectionMigrationTest {
  @Test
  void hintlessChildAdmissionIsDeletedAcrossPausedMigration(@TempDir Path data) throws Exception {
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    var opening = new MigrationTransitionBarrier.Controlled("migration-before-live-green-open");
    var switching = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var activated = new CountDownLatch(1);
    try (var engine = EngineTestHarness.start(data, transition -> {
      if ("migration-after-live-activation".equals(transition.point())) activated.countDown();
      opening.await(transition);
      switching.await(transition);
    })) {
      engine.addRootAsThePerson(parent);
      engine.addRootAsThePerson(child);
      declarations(data, parent, child);
      // The RPC reports Blue's visible deletion count. Seed Blue explicitly so that count
      // does not depend on whether a new Green admission has already projected into serving.
      Path existing = Files.writeString(child.resolve("already-indexed.txt"),
          EngineTestHarness.chunkedContent("existingchildmarker"));
      assertEquals(1, engine.client().submitBatch(List.of(existing), false, "C",
          TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(existing), "existingchildmarker", false, 120_000));
      String blue = engine.status().getMigration().getActiveGenerationId();
      assertTrue(engine.client().startMigration("hintless_collection", TestEngineContexts.FOREGROUND).accepted());
      assertTrue(opening.awaitReached(120, TimeUnit.SECONDS));
      assertTrue(engine.client().pauseMigration("hintless_collection", TestEngineContexts.FOREGROUND));
      opening.release();
      assertTrue(engine.awaitLiveGreen(60_000));
      Path file = Files.writeString(child.resolve("nested.txt"), EngineTestHarness.chunkedContent("hintlesschildmarker"));
      assertEquals(1, engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(file), "hintlesschildmarker", true, 120_000));
      try (var view = engine.captureServingView()) {
        assertLabels(view.ingestRuntime(), file, "C");
      }
      assertTrue(engine.client().deleteDocsByCollection("C", TestEngineContexts.FOREGROUND) > 0);
      assertTrue(engine.client().resumeMigration(TestEngineContexts.FOREGROUND));
      assertTrue(switching.awaitReached(180, TimeUnit.SECONDS));
      switching.release();
      assertTrue(activated.await(180, TimeUnit.SECONDS), "live cutover must publish Green");
      assertNotEquals(blue, engine.status().getMigration().getActiveGenerationId());
      try (var view = engine.captureServingView()) {
        assertEquals(0, view.searchRuntime().indexCountOps().countByIdAndChunksStrict(key(file)),
            "the collection deletion removes parent and every chunk; enumeration cannot resurrect them");
      }
    } finally {
      opening.release();
      switching.release();
    }
  }

  @Test
  void declaredDefaultReadmissionClearsParentAndChunkLabelsBeforeDeletingParentCollection(
      @TempDir Path data) throws Exception {
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("default"));
    Path file = Files.writeString(child.resolve("default.txt"), EngineTestHarness.chunkedContent("olddefaultmarker"));
    try (var engine = EngineTestHarness.start(data)) {
      engine.addRootAsThePerson(parent);
      engine.addRootAsThePerson(child);
      new ObjectMapper().writeValue(data.resolve("watched_roots.json").toFile(),
          Map.of("schemaVersion", 1, "roots", List.of(
              Map.of("path", parent.toString(), "collection", "P"), Map.of("path", child.toString()))));
      assertEquals(1, engine.client().submitBatch(List.of(file), false, "P",
          TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(file), "olddefaultmarker", false, 120_000));
      Files.writeString(file, EngineTestHarness.chunkedContent("defaultsurvivormarker"));
      assertEquals(1, engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(file), "defaultsurvivormarker", false, 120_000));
      try (var view = engine.captureServingView()) {
        assertLabels(view.searchRuntime(), file, null);
      }
      assertTrue(engine.client().deleteDocsByCollection("P", TestEngineContexts.FOREGROUND) >= 0);
      try (var view = engine.captureServingView()) {
        assertLabels(view.searchRuntime(), file, null);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void legacyNullJobAndReceiptCannotSurviveCollectionDeletionAfterRestart(boolean removeDeclaration, @TempDir Path data)
      throws Exception {
    if (removeDeclaration) {
      removedDeclarationRecovery(data);
      return;
    }
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    // Record physical root identity through the real add-root route, then stop our Engine.
    try (var engine = EngineTestHarness.start(data)) {
      engine.addRootAsThePerson(parent);
      engine.addRootAsThePerson(child);
      declarations(data, parent, child);
    }
    Path file = Files.writeString(child.resolve("legacy.txt"), EngineTestHarness.chunkedContent("legacychildmarker"));
    var manager = new IndexGenerationManager(data.resolve("index"));
    String blue = manager.readStateBestEffort().active_generation();
    String green = manager.startMigration("legacy_collection").building_generation();
    try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
      queue.open();
      // A standalone old-version queue has no declaration supplier: persist its exact NULL job
      // and NULL switch-buffer source witness, before accepting the later collection deletion.
      assertTrue(queue.enqueueAndBufferFileForGeneration(green, JobQueue.EnqueueEntry.stat(file), null));
      assertNull(queue.listSwitchBufferOpsStrict().stream().filter(op -> "UPSERT".equals(op.op()))
          .map(op -> io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload()))
          .findFirst().orElseThrow().collection());
      assertTrue(queue.putSwitchBufferForGeneration(green, "collection:C", "DELETE_COLLECTION", "C"));
    }
    var switching = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var activated = new CountDownLatch(1);
    try (var engine = EngineTestHarness.start(data, transition -> {
      if ("migration-after-live-activation".equals(transition.point())) activated.countDown();
      switching.await(transition);
    })) {
      assertTrue(switching.awaitReached(180, TimeUnit.SECONDS));
      try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
        queue.open();
        assertTrue(queue.listSwitchBufferOpsStrict().stream()
            .filter(op -> "UPSERT".equals(op.op()))
            .map(op -> io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload()))
            .noneMatch(upsert -> key(file).equals(upsert.path()) && upsert.collection() == null),
            "the legacy NULL receipt must resolve to C or removal before ordered deletion replay");
      }
      switching.release();
      assertTrue(activated.await(180, TimeUnit.SECONDS), "live cutover must publish Green");
      assertNotEquals(blue, engine.status().getMigration().getActiveGenerationId());
      try (var view = engine.captureServingView()) {
        assertEquals(0, view.searchRuntime().indexCountOps().countByIdAndChunksStrict(key(file)),
            "the older NULL admission belongs to C and is removed by its later deletion");
      }
      engine.restart();
      try (var view = engine.captureServingView()) {
        assertEquals(0, view.searchRuntime().indexCountOps().countByIdAndChunksStrict(key(file)));
      }
    } finally {
      switching.release();
    }
  }

  private static void removedDeclarationRecovery(Path data) throws Exception {
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path file = Files.writeString(child.resolve("legacy.txt"), EngineTestHarness.chunkedContent("legacychildmarker"));
    declarations(data, parent, child);
    var opening = new MigrationTransitionBarrier.Controlled("migration-before-live-green-open");
    var switching = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var activated = new CountDownLatch(1);
    try (var engine = EngineTestHarness.startWithPersistedRoots(data, transition -> {
      if ("migration-after-live-activation".equals(transition.point())) activated.countDown();
      opening.await(transition);
      switching.await(transition);
    })) {
      engine.addRootAsThePerson(parent);
      engine.addRootAsThePerson(child);
      assertEquals(1, engine.client().submitBatch(List.of(file), false, "C",
          TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(file), "legacychildmarker", false, 120_000));
      String blue = engine.status().getMigration().getActiveGenerationId();
      assertTrue(engine.client().startMigration("legacy_collection", TestEngineContexts.FOREGROUND).accepted());
      assertTrue(opening.awaitReached(120, TimeUnit.SECONDS));
      assertTrue(engine.client().pauseMigration("legacy_collection", TestEngineContexts.FOREGROUND));
      opening.release();
      assertTrue(engine.awaitLiveGreen(60_000));
      String green = engine.status().getMigration().getBuildingGenerationId();
      try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
        queue.open();
        // Plant the old-version NULL owner even if Blue's earlier row still carries C.
        assertEquals(1, queue.enqueueEntriesWithExactCollection(List.of(JobQueue.EnqueueEntry.stat(file)), null));
        assertTrue(queue.enqueueAndBufferFileForGeneration(green, JobQueue.EnqueueEntry.stat(file), null));
        assertNull(queue.listSwitchBufferOpsStrict().stream().filter(op -> "UPSERT".equals(op.op()))
            .map(op -> io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload()))
            .filter(upsert -> key(file).equals(upsert.path())).findFirst().orElseThrow().collection());
      }
      // Remove the ancestor first: its scoped deletion preserves the independently declared child.
      // Removing the child then records its own deletion, as production does, before restart.
      assertTrue(engine.client().removeWatchedPath(parent, TestEngineContexts.FOREGROUND) >= 0);
      assertTrue(engine.client().removeWatchedPath(child, TestEngineContexts.FOREGROUND) >= 0);
      assertTrue(io.justsearch.configuration.persistence.WatchedRootsFormat.declaredPaths(data).isEmpty(),
          "production Remove must retire both declarations");
      try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
        queue.open();
        assertTrue(queue.listSwitchBufferOpsStrict().stream().anyMatch(op -> "DELETE_PREFIX".equals(op.op())
            && io.justsearch.adapters.lucene.runtime.PathPrefixScope.decode(op.payload()).contains(key(file))),
            "production Remove must record a scoped deletion covering the legacy file");
      }
      assertTrue(engine.client().deleteDocsByCollection("C", TestEngineContexts.FOREGROUND) >= 0);
      engine.restart();
      assertTrue(engine.client().resumeMigration(TestEngineContexts.FOREGROUND));
      assertTrue(switching.awaitReached(180, TimeUnit.SECONDS));
      try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
        queue.open();
        assertTrue(queue.listSwitchBufferOpsStrict().stream()
            .filter(op -> "UPSERT".equals(op.op()))
            .map(op -> io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload()))
            .noneMatch(upsert -> key(file).equals(upsert.path()) && upsert.collection() == null),
            "the legacy NULL receipt must resolve to C or removal before ordered deletion replay");
      }
      switching.release();
      assertTrue(activated.await(180, TimeUnit.SECONDS), "live cutover must publish Green");
      assertNotEquals(blue, engine.status().getMigration().getActiveGenerationId());
      try (var view = engine.captureServingView()) {
        assertEquals(0, view.searchRuntime().indexCountOps().countByIdAndChunksStrict(key(file)),
            "the older NULL admission belongs to C and is removed by its later deletion");
      }
      engine.restart();
      try (var view = engine.captureServingView()) {
        assertEquals(0, view.searchRuntime().indexCountOps().countByIdAndChunksStrict(key(file)));
      }
    } finally {
      opening.release();
      switching.release();
    }
  }

  @Test
  void outsideAdmissionKeepsNullJobReceiptAndDocumentAcrossMigrationRestart(@TempDir Path data) throws Exception {
    Path declared = Files.createDirectories(data.resolve("declared"));
    Path file = data.resolve("outside.txt");
    var opening = new MigrationTransitionBarrier.Controlled("migration-before-live-green-open");
    var switching = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    var activated = new CountDownLatch(1);
    try (var engine = EngineTestHarness.start(data, transition -> {
      if ("migration-after-live-activation".equals(transition.point())) activated.countDown();
      opening.await(transition);
      switching.await(transition);
    })) {
      engine.addRootAsThePerson(declared);
      new ObjectMapper().writeValue(data.resolve("watched_roots.json").toFile(), Map.of(
          "schemaVersion", 1, "roots", List.of(Map.of("path", declared.toString(), "collection", "C"))));
      String blue = engine.status().getMigration().getActiveGenerationId();
      assertTrue(engine.client().startMigration("outside_collection", TestEngineContexts.FOREGROUND).accepted());
      assertTrue(opening.awaitReached(120, TimeUnit.SECONDS));
      assertTrue(engine.client().pauseMigration("outside_collection", TestEngineContexts.FOREGROUND));
      opening.release();
      assertTrue(engine.awaitLiveGreen(60_000));
      Files.writeString(file, EngineTestHarness.chunkedContent("outsidesurvivormarker"));
      assertEquals(1, engine.client().submitBatch(List.of(file), TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIngestChunks(key(file), "outsidesurvivormarker", true, 120_000));
      engine.restart();
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("jobs.db"));
          var query = connection.prepareStatement("SELECT collection FROM jobs WHERE path=?")) {
        query.setString(1, key(file));
        try (var rows = query.executeQuery()) {
          assertTrue(rows.next(), "restart must keep the out-of-root job");
          assertNull(rows.getString(1));
        }
      }
      try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
        queue.open();
        assertTrue(queue.listSwitchBufferOpsStrict().stream()
            .filter(op -> "UPSERT".equals(op.op()))
            .map(op -> io.justsearch.indexerworker.queue.SwitchBufferUpsert.decode(op.payload()))
            .anyMatch(upsert -> key(file).equals(upsert.path()) && upsert.collection() == null),
            "restart must retain an out-of-root NULL admission as an UPSERT");
      }
      assertTrue(engine.client().resumeMigration(TestEngineContexts.FOREGROUND));
      assertTrue(switching.awaitReached(180, TimeUnit.SECONDS));
      switching.release();
      assertTrue(activated.await(180, TimeUnit.SECONDS), "live cutover must publish Green");
      assertNotEquals(blue, engine.status().getMigration().getActiveGenerationId());
      try (var view = engine.captureServingView()) {
        assertEquals(1, view.searchRuntime().readPathOps().search(
            new TermQuery(new Term(SchemaFields.DOC_ID, key(file))), 1, Set.of(), null, null).totalHits(),
            "the out-of-root parent must survive replay and cutover");
        assertLabels(view.searchRuntime(), file, null);
      }
      engine.restart();
      try (var view = engine.captureServingView()) {
        assertEquals(1, view.searchRuntime().readPathOps().search(
            new TermQuery(new Term(SchemaFields.DOC_ID, key(file))), 1, Set.of(), null, null).totalHits(),
            "the out-of-root parent must survive replay and cutover");
        assertLabels(view.searchRuntime(), file, null);
      }
    } finally {
      opening.release();
      switching.release();
    }
  }

  private static void declarations(Path data, Path parent, Path child) throws Exception {
    new ObjectMapper().writeValue(data.resolve("watched_roots.json").toFile(),
        Map.of("schemaVersion", 1, "roots", List.of(
            Map.of("path", parent.toString(), "collection", "P"),
            Map.of("path", child.toString(), "collection", "C"))));
  }

  private static void assertLabels(LuceneRuntime runtime, Path file, String collection) throws Exception {
    assertEquals(collection, runtime.documentFieldOps().getDocumentField(key(file), SchemaFields.COLLECTION));
    var chunks = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, key(file)));
    long count = runtime.readPathOps().search(chunks, 1, Set.of(), null, null).totalHits();
    assertTrue(count >= 2, "the real extractor must write multiple chunks");
    var labelled = new BooleanQuery.Builder().add(chunks, BooleanClause.Occur.FILTER)
        .add(collection == null ? new FieldExistsQuery(SchemaFields.COLLECTION)
            : new TermQuery(new Term(SchemaFields.COLLECTION, collection)), BooleanClause.Occur.FILTER).build();
    assertEquals(collection == null ? 0 : count,
        runtime.readPathOps().search(labelled, 1, Set.of(), null, null).totalHits());
  }

  private static String key(Path path) { return PathNormalizer.normalizeKey(path); }

}
