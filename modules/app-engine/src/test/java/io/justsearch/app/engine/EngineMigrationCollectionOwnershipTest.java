/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.indexerworker.index.MigrationProgressSnapshot;
import io.justsearch.indexerworker.index.MigrationProgressStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.MigrationTransitionBarrier;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchRequest;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.TermQuery;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

/** Real queue, writer, chunk, serving projection and cutover coverage for M1–M9. */
@Timeout(600)
final class EngineMigrationCollectionOwnershipTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String CHILD_MARKER = "nestedownershipmarker";

  @ParameterizedTest
  @CsvSource(value = {
      "P,C,false,false", "P,C,true,false", "P,DEFAULT,false,false",
      "DEFAULT,C,true,false", "P,DEFAULT,true,true", "DEFAULT,C,true,true"
  }, nullValues = "DEFAULT")
  void migrationPreservesExactOwnershipThroughCutoverAndCollectionDeletion(
      String parentCollection, String childCollection, boolean childFirst, boolean repair,
      @TempDir Path data) throws Exception {
    migrateOwnership(parentCollection, childCollection, childFirst, repair, data, "ownership");
  }

  @ParameterizedTest
  @ValueSource(strings = {"M5", "M6", "M7"})
  void servingVectorsAndCollectionConsumersRetainNestedOwnership(String scenario, @TempDir Path data)
      throws Exception {
    migrateOwnership("P", "C", true, false, data, scenario);
  }

  private static void migrateOwnership(String parentCollection, String childCollection,
      boolean childFirst, boolean repair, Path data, String scenario) throws Exception {
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Path own = Files.writeString(Files.createDirectories(parent.resolve("undeclared"))
        .resolve("own.txt"), EngineTestHarness.chunkedContent("parentownershipmarker"));
    Path nested = Files.writeString(child.resolve("nested.txt"), EngineTestHarness.chunkedContent(CHILD_MARKER));
    var barrier = new MigrationTransitionBarrier.Controlled("migration-before-switching");
    try (var engine = EngineTestHarness.start(data, barrier)) {
      engine.addRootAsThePerson(parent);
      engine.addRootAsThePerson(child);
      writeRoots(data, parent, parentCollection, child, childCollection, childFirst);
      assertEquals(1, engine.client().submitBatch(List.of(own), false, parentCollection,
          TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertEquals(1, engine.client().submitBatch(List.of(nested), false,
          repair ? "stale-parent" : childCollection, TestEngineContexts.FOREGROUND).getAcceptedCount());
      assertTrue(engine.awaitIndexed(2, 120_000));
      assertTrue(engine.awaitIngestChunks(key(nested), CHILD_MARKER, false, 60_000));
      float[] vector;
      try (var view = engine.captureServingView()) {
        var runtime = (RunningRuntime) view.ingestRuntime();
        assertEquals(repair ? "stale-parent" : childCollection,
            runtime.documentFieldOps().getDocumentField(key(nested), SchemaFields.COLLECTION));
        vector = new float[runtime.schema().ssotVectorDimension()];
        vector[0] = 1;
        List<String> enriched = repair ? List.of(key(own)) : List.of(key(own), key(nested));
        var inputs = runtime.documentFieldOps().getEnrichmentInputs(enriched);
        assertEquals(enriched.size(), runtime.indexingCoordinator().updateDocumentsBatchIf(
            enriched.stream().map(id -> Map.entry(id, Map.<String, Object>of(
                SchemaFields.VECTOR, vector, SchemaFields.EMBEDDING_STATUS, "COMPLETED"))).toList(), inputs).updatedCount());
        runtime.commitOps().commitAndTrack();
        runtime.commitOps().maybeRefreshBlocking();
        assertArrayEquals(vector, committedVector(runtime.openedIndexPath(), key(own)), 0f);
      }
      String blue = engine.status().getMigration().getActiveGenerationId();
      assertTrue(engine.client().startMigration("collection_ownership", TestEngineContexts.FOREGROUND).accepted());
      assertTrue(engine.awaitLiveGreen(60_000));
      // Hold publication before SWITCHING, then wait for the real queue to drain. Later
      // publication barriers hold the lock needed to capture a serving view for inspection.
      try {
        assertTrue(barrier.awaitReached(180, TimeUnit.SECONDS), "the monitor must hold publication before SWITCHING");
        awaitCandidateDrain(engine);
        try (var view = engine.captureServingView()) {
          if ("ownership".equals(scenario)) {
            assertLabelsAndChunks(view.ingestRuntime(), nested, childCollection);
            assertLabelsAndChunks(view.ingestRuntime(), own, parentCollection);
            assertEquals(parentCollection, view.searchRuntime().documentFieldOps()
                .getDocumentField(key(own), SchemaFields.COLLECTION));
            assertEquals(childCollection, view.searchRuntime().documentFieldOps()
                .getDocumentField(key(nested), SchemaFields.COLLECTION),
                "M5/M9: changed labels may project into the serving generation");
          }
          if ("ownership".equals(scenario) || "M5".equals(scenario)) {
            assertEquals(childCollection, view.searchRuntime().documentFieldOps()
                .getDocumentField(key(nested), SchemaFields.COLLECTION),
                "M5: an already-correct serving label must remain correct");
            assertArrayEquals(vector, committedVector(view.searchRuntime().openedIndexPath(), key(own)), 0f,
                "M5: correctly labelled serving data retains its exact existing vector");
            if (!repair) {
              assertArrayEquals(vector, committedVector(view.searchRuntime().openedIndexPath(), key(nested)), 0f,
                  "M5: the independently declared child's serving vector is preserved too");
            }
          }
        }
        if ("ownership".equals(scenario)) {
          assertEquals(2, engine.status().getMigration().getEnumerator().getFilesSeen(),
              "M3/M4: undeclared descendants inherit P; declared C is admitted only by C");
        }
      } finally {
        barrier.release();
      }
      awaitCutover(engine, blue);
      if ("ownership".equals(scenario)) {
        try (var view = engine.captureServingView()) {
          assertLabelsAndChunks(view.searchRuntime(), nested, childCollection);
          assertLabelsAndChunks(view.searchRuntime(), own, parentCollection);
        }
      }
      if (!"M6".equals(scenario) && childCollection != null) {
        assertEquals(1, engine.client().search(SearchRequest.newBuilder().setQuery(CHILD_MARKER)
            .setLimit(10).setFilters(SearchFilters.newBuilder().addCollection(childCollection)).build(),
            TestEngineContexts.FOREGROUND).getTotalHits(), "M7: C's collection finds its file");
      }
      if (!"M6".equals(scenario) && parentCollection != null) {
        assertEquals(0, engine.client().search(SearchRequest.newBuilder().setQuery(CHILD_MARKER)
            .setLimit(10).setFilters(SearchFilters.newBuilder().addCollection(parentCollection)).build(),
            TestEngineContexts.FOREGROUND).getTotalHits(), "M7: P's collection excludes C");
      }
      if (!"M7".equals(scenario) && parentCollection != null) {
        assertTrue(engine.client().deleteDocsByCollection(parentCollection, TestEngineContexts.FOREGROUND) > 0);
        assertTrue(engine.awaitDocumentAbsent(key(own), 30_000));
      }
      assertTrue(engine.client().deleteDocsByPathPrefix(parent, TestEngineContexts.FOREGROUND) >= 0);
      assertTrue(engine.awaitSearchable(CHILD_MARKER, 30_000));
      try (var view = engine.captureServingView()) {
        assertLabelsAndChunks(view.searchRuntime(), nested, childCollection);
      }
    } finally {
      barrier.release();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void killedEnumerationRecoversExactOwnershipAndAllAcceptedDeletionReceipts(
      boolean legacy, @TempDir Path data) throws Exception {
    Path parent = Files.createDirectories(data.resolve("parent"));
    Path child = Files.createDirectories(parent.resolve("child"));
    Files.writeString(parent.resolve("own.txt"), EngineTestHarness.chunkedContent("parentownershipmarker"));
    Path nested = Files.writeString(child.resolve("nested.txt"), EngineTestHarness.chunkedContent(CHILD_MARKER));
    Path deleted = Files.writeString(child.resolve("deleted.txt"), EngineTestHarness.chunkedContent("explicitdeletemarker"));
    writeRoots(data, parent, "P", child, "C", true);
    Path args = data.resolve("kill.args");
    Files.writeString(args, "--enable-native-access=ALL-UNNAMED\n-cp\n\""
        + System.getProperty("java.class.path").replace("\\", "/").replace("\"", "\\\"")
        + "\"\n" + KillDuringEnumeration.class.getName() + "\n\""
        + data.toString().replace("\\", "/") + "\"\n" + legacy + "\n", StandardCharsets.UTF_8);
    Path log = data.resolve("kill.log");
    Process process = new ProcessBuilder(ProcessHandle.current().info().command().orElseThrow(), "@" + args)
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    long pid = process.pid();
    var creation = process.info().startInstant().orElseThrow();
    Files.writeString(data.resolve("kill.identity"), pid + " " + creation);
    try {
      assertTrue(process.waitFor(180, TimeUnit.SECONDS), "owned Engine probe timed out: " + log);
      assertEquals(73, process.exitValue(), () -> readLog(log));
    } finally {
      if (process.isAlive()) {
        assertEquals(creation, process.info().startInstant().orElseThrow());
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
      }
    }
    var interrupted = JSON.readTree(Files.readString(data.resolve("interrupted.json")));
    assertTrue(interrupted.path("seen").asLong() > 0);
    assertFalse(interrupted.path("done").asBoolean(), "the kill must interrupt a real partial walk");
    String blue = new io.justsearch.indexerworker.index.IndexGenerationManager(data.resolve("index"))
        .readStateBestEffort().active_generation();
    try (var engine = EngineTestHarness.start(data)) {
      if (!"IDLE".equalsIgnoreCase(engine.status().getMigration().getMigrationState())) {
        assertTrue(engine.client().requestCutover(true, TestEngineContexts.FOREGROUND).accepted());
      }
      awaitCutover(engine, blue);
      try (var view = engine.captureServingView()) {
        assertLabelsAndChunks(view.searchRuntime(), nested, "C");
      }
      assertTrue(engine.awaitDocumentAbsent(key(deleted), 30_000), "explicit DELETE survives replay");
      assertTrue(engine.awaitDocumentAbsent(key(parent.resolve("own.txt")), 30_000),
          "collection and scoped/legacy prefix DELETE survive replay");
      engine.restart();
      try (var view = engine.captureServingView()) {
        assertLabelsAndChunks(view.searchRuntime(), nested, "C");
      }
      assertTrue(engine.awaitDocumentAbsent(key(deleted), 30_000));
    }
  }

  /** The owned child really halts mid-walk, without closing the Engine or its SQLite connections. */
  public static final class KillDuringEnumeration {
    @SuppressWarnings("PMD.DoNotTerminateVM")
    public static void main(String[] args) throws Exception {
      Path data = Path.of(args[0]);
      Path parent = data.resolve("parent");
      Path child = parent.resolve("child");
      try (var engine = EngineTestHarness.start(data)) {
        engine.addRootAsThePerson(parent);
        engine.addRootAsThePerson(child);
        writeRoots(data, parent, "P", child, "C", true);
        assertTrue(engine.client().startMigration("kill_collection_walk", TestEngineContexts.FOREGROUND).accepted());
        assertTrue(engine.client().pauseMigration("kill_collection_walk", TestEngineContexts.FOREGROUND));
        assertTrue(engine.awaitLiveGreen(60_000));
        assertTrue(engine.client().deleteDocsByCollection("P", TestEngineContexts.FOREGROUND) >= 0);
        assertTrue(engine.client().deleteDocById(key(child.resolve("deleted.txt")), TestEngineContexts.FOREGROUND));
        assertTrue(engine.client().deleteDocsByPathPrefix(parent, TestEngineContexts.FOREGROUND) >= 0);
        if (Boolean.parseBoolean(args[1])) {
          try (var queue = new SqliteJobQueue(data.resolve("jobs.db"))) {
            queue.open();
            String prefix = PathNormalizer.normalizePathPrefix(parent.toString());
            assertTrue(queue.putSwitchBufferForGeneration(engine.status().getMigration().getBuildingGenerationId(),
                "prefix:" + prefix, "DELETE_PREFIX", prefix));
          }
        }
        Field rootField = EngineTestHarness.class.getDeclaredField("root");
        rootField.setAccessible(true);
        Field serverField = EngineRoot.class.getDeclaredField("server");
        serverField.setAccessible(true);
        KnowledgeServer server = (KnowledgeServer) serverField.get(rootField.get(engine));
        Field progressField = KnowledgeServer.class.getDeclaredField("migrationProgressStore");
        progressField.setAccessible(true);
        var progress = spy(new MigrationProgressStore(engine.indexBase()));
        doAnswer(call -> {
          MigrationProgressSnapshot snapshot = call.getArgument(0);
          if (snapshot.filesSeen() > 0 && !snapshot.enumeratorDone()) {
            JSON.writeValue(data.resolve("interrupted.json").toFile(),
                Map.of("seen", snapshot.filesSeen(), "done", snapshot.enumeratorDone()));
            Runtime.getRuntime().halt(73);
          }
          return call.callRealMethod();
        }).when(progress).writeBestEffort(any());
        progressField.set(server, progress);
        assertTrue(engine.client().resumeMigration(TestEngineContexts.FOREGROUND));
        Thread.sleep(120_000);
        throw new AssertionError("enumeration never reached the kill point");
      }
    }
  }

  private static String readLog(Path log) {
    try { return Files.readString(log); }
    catch (java.io.IOException failure) { return failure.toString(); }
  }

  private static void assertLabelsAndChunks(
      io.justsearch.adapters.lucene.runtime.LuceneRuntime runtime, Path file, String collection) throws Exception {
    String id = key(file);
    assertEquals(collection, runtime.documentFieldOps().getDocumentField(id, SchemaFields.COLLECTION), id);
    var chunks = new TermQuery(new Term(SchemaFields.PARENT_DOC_ID, id));
    long total = runtime.readPathOps().search(chunks, 1, Set.of(), null, null).totalHits();
    assertTrue(total >= 2, "real source must produce multiple chunks: " + id);
    if (collection != null) {
      var labelled = new BooleanQuery.Builder().add(chunks, BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.COLLECTION, collection)), BooleanClause.Occur.FILTER).build();
      assertEquals(total, runtime.readPathOps().search(labelled, 1, Set.of(), null, null).totalHits());
    } else {
      var incorrectlyLabelled = new BooleanQuery.Builder().add(chunks, BooleanClause.Occur.FILTER)
          .add(new org.apache.lucene.search.FieldExistsQuery(SchemaFields.COLLECTION), BooleanClause.Occur.FILTER).build();
      assertEquals(0, runtime.readPathOps().search(incorrectlyLabelled, 1, Set.of(), null, null).totalHits());
    }
    assertEquals(total + 1, runtime.indexCountOps().countByIdAndChunksStrict(id),
        "the final parent and chunk set has no duplicate identities");
  }

  private static float[] committedVector(Path index, String id) throws Exception {
    try (var directory = org.apache.lucene.store.FSDirectory.open(index);
        var reader = org.apache.lucene.index.DirectoryReader.open(directory)) {
      var hits = new org.apache.lucene.search.IndexSearcher(reader).search(idQuery(id), 1);
      assertEquals(1, hits.totalHits.value());
      int global = hits.scoreDocs[0].doc;
      var leaf = reader.leaves().get(org.apache.lucene.index.ReaderUtil.subIndex(global, reader.leaves()));
      var values = leaf.reader().getFloatVectorValues(SchemaFields.VECTOR);
      assertNotNull(values, "the committed serving document must retain its vector");
      var iterator = values.iterator();
      assertEquals(global - leaf.docBase, iterator.advance(global - leaf.docBase));
      return values.vectorValue(iterator.index()).clone();
    }
  }

  private static TermQuery idQuery(String id) {
    return new TermQuery(new Term(SchemaFields.DOC_ID, id));
  }

  private static String key(Path path) { return PathNormalizer.normalizeKey(path); }

  private static void awaitCandidateDrain(EngineTestHarness engine) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
    while (System.nanoTime() < deadline) {
      var status = engine.status();
      if (status.getMigration().getEnumerator().getDone()
          && status.getMigration().getBuildingDocCount() >= 2
          && status.getCore().getQueueDepth() == 0) return;
      Thread.sleep(100);
    }
    fail("the real candidate queue did not finish both files: " + engine.status());
  }

  private static void awaitCutover(EngineTestHarness engine, String blue) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
    while (System.nanoTime() < deadline) {
      var migration = engine.status().getMigration();
      if ("IDLE".equalsIgnoreCase(migration.getMigrationState())
          && !blue.equals(migration.getActiveGenerationId())) return;
      Thread.sleep(100);
    }
    fail("migration did not cut over: " + engine.status().getMigration());
  }

  private static void writeRoots(Path data, Path parent, String parentCollection,
      Path child, String childCollection, boolean childFirst) throws Exception {
    var roots = new ArrayList<Map<String, Object>>();
    for (var entry : List.of(new Object[] {parent, parentCollection}, new Object[] {child, childCollection})) {
      var node = new java.util.LinkedHashMap<String, Object>();
      node.put("path", entry[0].toString());
      node.put("collection", entry[1]);
      roots.add(node);
    }
    if (childFirst) Collections.reverse(roots);
    JSON.writeValue(data.resolve("watched_roots.json").toFile(), Map.of("schemaVersion", 1, "roots", roots));
  }
}
