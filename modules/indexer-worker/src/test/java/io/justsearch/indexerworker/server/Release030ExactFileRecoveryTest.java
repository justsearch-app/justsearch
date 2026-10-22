/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static io.justsearch.indexerworker.server.Release030UpgradeCharacterizationTest.*;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** C2: exact-file authority does not depend on the drive inferred from an indexed sample. */
@Timeout(180)
final class Release030ExactFileRecoveryTest {
  @Test
  void isolatedQueuedUpdateIndexesAndSurvivesRestart(@TempDir Path temporary) throws Exception {
    Path root = isolated(temporary, true);
    Path file = root.resolve("explicit.txt");
    Files.writeString(file, "isolatedcobaltupdated");
    Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        for (int boot = 0; boot < 2; boot++) {
          try (var executors = new TestEngineExecutors(); var server = bootWithoutRoots(root, executors)) {
            if (boot == 0) assertTrue(search(server, "apricot").contains(key(file)));
            activate(server);
            await(() -> search(server, "isolatedcobaltupdated").contains(key(file)),
                "C2-1: an isolated queued update is indexed without another explicit add", 20);
            await(() -> !Files.exists(checkpoint(root)), "completed exact work clears its debt");
            assertEquals(1, count(root, "path='" + key(file) + "' AND state='DONE'"));
            assertEquals("IDLE", state(root).migration_state());
          }
        }
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void neverIndexedMissingExactFileWaitsThenReturns(@TempDir Path temporary) throws Exception {
    Path root = isolated(temporary, false);
    Path file = root.resolve("explicit.txt");
    Path held = Files.move(file, root.resolve("held-explicit.txt"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = bootWithoutRoots(root, executors)) {
          activate(server);
          await(() -> count(root, "path='" + key(file) + "' AND (state='DONE' OR retry_after>0)") == 1,
              "missing exact work is resolved or deferred", 20);
          assertEquals(0, count(root, "path='" + key(file) + "' AND state='DONE'"),
              "C2-2: absence without volume evidence cannot retire a never-indexed exact path");
          assertTrue(Files.exists(checkpoint(root)), "missing exact work remains durable debt");
        }
        Files.move(held, file);
        try (var executors = new TestEngineExecutors(); var server = bootWithoutRoots(root, executors)) {
          activate(server);
          await(() -> search(server, "copper").contains(key(file)), "original exact work returns", 90);
          await(() -> !Files.exists(checkpoint(root)), "returned exact work completes");
        }
      }
    } finally {
      if (Files.exists(held)) Files.move(held, file);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"migrating", "switching"})
  void nativeInterruptedMigrationCompletesWithoutStateEdits(String fixture, @TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack(fixture, temporary);
    Path file = Files.writeString(root.resolve("native-explicit.txt"), "nativecobaltqueued");
    sql(root, "INSERT INTO jobs(path,state,attempts,last_updated) VALUES ('" + key(file) + "','PENDING',0,0)");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        activateNative(server);
        var resumed = server.appServices().ingestService().resumeMigration(
            io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(),
            io.justsearch.indexerworker.services.CallContext.none());
        assertTrue(resumed.getAccepted(), resumed.getError());
        System.out.println("Native fixture " + fixture + " failed=" + count(root, "state='FAILED'")
            + " queued=" + count(root, "state IN ('PENDING','PROCESSING')"));
        await(() -> "IDLE".equals(state(root).migration_state())
                && search(server, "nativecobaltqueued").contains(key(file)),
            "C2-3: actual " + fixture + " cutover completes with its explicit content; phase="
                + state(root).migration_state(), 30);
        assertEquals(0, count(root, "last_reason_code='MISSING_INGESTION_BOUNDARY'"));
        assertEquals(1, count(root, "path='" + key(file) + "' AND state='DONE'"));
        assertEquals(1, count(root, "state='FAILED' AND ingestion_root IS NULL"),
            "the original unowned failure is retained and cannot veto native cutover");
      }
    } finally { ConfigStore.restoreGlobal(installed, previous); }
  }

  @Test
  void nativeCutoverKeepsUnavailableExactOldContentUntilItReturns(@TempDir Path temporary)
      throws Exception {
    Path root = Release030Fixture.unpack("migrating", temporary);
    Path file = root.resolve("explicit.txt");
    Path held = Files.move(file, root.resolve("held-explicit.txt"));
    // The authentic interrupted Green has no commit containing this exact source yet.
    sql(root, "UPDATE jobs SET state='PENDING',attempts=0,retry_after=NULL WHERE path='" + key(file) + "'");
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models; var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
        assertTrue(search(server, "copper").contains(key(file)), "old exact content starts searchable");
        activateNative(server);
        assertTrue(server.appServices().ingestService().resumeMigration(
            io.justsearch.ipc.MigrationResumeRequest.getDefaultInstance(),
            io.justsearch.indexerworker.services.CallContext.none()).getAccepted());
        await(() -> count(root, "path='" + key(file) + "' AND state='PENDING' AND retry_after>0") == 1,
            "unavailable exact work gets a bounded retry rather than an unprepared hold", 20);
        long settling = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
        await(() -> "IDLE".equals(state(root).migration_state())
                || (System.nanoTime() >= settling && "SWITCHING".equals(state(root).migration_state())),
            "native cutover reaches its drain decision", 10);
        assertTrue(search(server, "copper").contains(key(file)),
            "native backoff-only cutover cannot discard unavailable exact content from Blue");
        Files.move(held, file);
        await(() -> "IDLE".equals(state(root).migration_state())
                && search(server, "copper").contains(key(file)),
            "returned exact work resumes and native cutover completes", 90);
      }
    } finally {
      if (Files.exists(held)) Files.move(held, file);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-a-number", "18446744073709551616"})
  void corruptCooldownCannotStarveAnotherRoot(String badValue, @TempDir Path temporary) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path alpha = root.resolve("roots/alpha"), beta = root.resolve("roots/beta");
    Path heldAlpha = Files.move(alpha, root.resolve("held-alpha"));
    Path heldBeta = Files.move(beta, root.resolve("held-beta"));
    ConfigStore previous = ConfigStore.globalOrNull();
    var installed = configure(root);
    try {
      var models = new ReleasedModelIdentities(root);
      try (models) {
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          assertNotNull(server.lifecycleManagerForTests());
          stopRecovery(server);
          Properties properties = properties(root);
          properties.setProperty("retry|" + key(alpha), badValue);
          try (var out = Files.newOutputStream(checkpoint(root))) { properties.store(out, "corrupt alpha timer only"); }
        }
        Files.move(heldAlpha, alpha);
        Files.move(heldBeta, beta);
        Path added = Files.writeString(beta.resolve("cobalt-new.txt"), "betacobaltindependent");
        try (var executors = new TestEngineExecutors(); var server = boot(root, executors)) {
          stopRecovery(server);
          activate(server);
          recover(server);
          assertNotEquals("pending", properties(root).getProperty("root|" + key(beta)),
              "C2-4: a corrupt alpha cooldown cannot stop beta's actual recovery walk");
          assertEquals("pending", properties(root).getProperty("root|" + key(alpha)),
              "corruption retains the affected root's debt");
          assertTrue(search(server, "nebula").contains(key(alpha.resolve("file-01.txt"))),
              "corruption never authorizes removal of old indexed content");
          await(() -> search(server, "betacobaltindependent").contains(key(added)), "other root becomes current");
        }
      }
    } finally {
      if (Files.exists(heldAlpha)) Files.move(heldAlpha, alpha);
      if (Files.exists(heldBeta)) Files.move(heldBeta, beta);
      ConfigStore.restoreGlobal(installed, previous);
    }
  }

  @Test
  void mergedEnqueuePreservesWaitAndRevisionButOrdinaryAdmissionRenewsThem(@TempDir Path root)
      throws Exception {
    Files.createDirectories(root.resolve("data"));
    Path file = Files.writeString(root.resolve("file.txt"), "revision");
    try (var queue = new SqliteJobQueue(root.resolve("data/jobs.db"))) {
      queue.open();
      var entries = List.of(new JobQueue.EnqueueEntry(file, Files.size(file), null, null, file));
      queue.enqueueEntries(entries, null, null);
      String before = revision(root, file);
      sql(root, "UPDATE jobs SET retry_after=4102444800000,last_outcome_class='PARSER_TIMEOUT',attempts=2");
      queue.enqueueMaintenanceScanEntries(entries, null, null);
      assertEquals(before, revision(root, file), "maintenance cannot replace the waiting admission");
      assertEquals(1, count(root, "retry_after=4102444800000 AND attempts=2"));
      queue.enqueueEntries(entries, null, null);
      assertNotEquals(before, revision(root, file), "ordinary admission has a fresh unit revision");
      assertEquals(1, count(root, "retry_after IS NULL AND attempts=0"));
    }
  }

  /** Complete the model work suppressed by boot(): real backfill/certification, synthetic vectors. */
  static void activateNative(KnowledgeServer server) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField("ingestLifecycle");
    field.setAccessible(true);
    var runtime = (io.justsearch.adapters.lucene.runtime.RunningRuntime) field.get(server);
    int dimension = java.util.Objects.requireNonNull(runtime.schema().ssotVectorDimension());
    var provider = org.mockito.Mockito.mock(io.justsearch.indexerworker.embed.EmbeddingProvider.class,
        org.mockito.Mockito.CALLS_REAL_METHODS);
    org.mockito.Mockito.when(provider.availability())
        .thenReturn(io.justsearch.indexerworker.embed.EmbeddingProvider.Availability.READY);
    org.mockito.Mockito.when(provider.dimension()).thenReturn(dimension);
    org.mockito.Mockito.when(provider.embedDocument(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> vector(dimension));
    org.mockito.Mockito.when(provider.embedQuery(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(call -> vector(dimension));
    org.mockito.Mockito.when(provider.embedDocumentBatch(org.mockito.ArgumentMatchers.anyList()))
        .thenAnswer(call -> ((List<?>) call.getArgument(0)).stream().map(text -> vector(dimension)).toList());
    server.appServices().wireEmbeddingProvider(provider);
    io.justsearch.indexerworker.loop.ops.EmbeddingRecoveryOps.rescueBlockedLegacyIndex(
        server.embeddingCompatController(), runtime, 100,
        org.slf4j.LoggerFactory.getLogger(Release030ExactFileRecoveryTest.class));
    activate(server);
  }

  private static float[] vector(int dimension) {
    float[] vector = new float[dimension];
    vector[0] = 1f;
    return vector;
  }

  private static Path isolated(Path temporary, boolean indexed) throws Exception {
    Path root = Release030Fixture.unpack("small", temporary);
    Path file = root.resolve("explicit.txt");
    Path generation = Release030CompatibilityTest.generation(root);
    var metadata = Release030CompatibilityTest.readMetadata(generation);
    try (var directory = FSDirectory.open(generation);
        var writer = new IndexWriter(directory, new IndexWriterConfig()
            .setOpenMode(IndexWriterConfig.OpenMode.APPEND))) {
      var others = new BooleanQuery.Builder().add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
      if (indexed) others.add(new TermQuery(new Term("doc_id", key(file))), BooleanClause.Occur.MUST_NOT);
      writer.deleteDocuments(others.build());
      writer.setLiveCommitData(metadata.entrySet());
      writer.commit();
    }
    sql(root, "DELETE FROM jobs WHERE path<>'" + key(file) + "'");
    // No watched-root/help discovery can turn this into a many-sample test later.
    for (String name : List.of("roots", "orphan", "release")) {
      try (var paths = Files.walk(root.resolve(name))) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
      }
    }
    return root;
  }

  private static KnowledgeServer bootWithoutRoots(Path root, TestEngineExecutors executors) throws Exception {
    var server = org.mockito.Mockito.spy(new KnowledgeServer(executors,
        WorkerBootFixture.workerConfig(root.resolve("data")), null));
    org.mockito.Mockito.doNothing().when(server).startDeferredModelInitialization(org.mockito.ArgumentMatchers.any());
    server.bindBootRootBindings(List.of());
    server.bindExclusionRoots(List::of);
    try { server.start(); } catch (Exception | Error failure) { server.close(); throw failure; }
    return server;
  }

  private static String revision(Path root, Path file) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("data/jobs.db"));
        var query = connection.prepareStatement("SELECT unit_revision FROM jobs WHERE path=?")) {
      query.setString(1, key(file));
      try (var row = query.executeQuery()) { assertTrue(row.next()); return row.getString(1); }
    }
  }

  private static Path checkpoint(Path root) { return root.resolve("data/upgrade-030-recovery.properties"); }
  private static Properties properties(Path root) throws Exception {
    var properties = new Properties();
    try (var in = Files.newInputStream(checkpoint(root))) { properties.load(in); }
    return properties;
  }
  private static void stopRecovery(KnowledgeServer server) throws Exception {
    var field = KnowledgeServer.class.getDeclaredField("release030RecoveryTask"); field.setAccessible(true);
    if (field.get(server) instanceof java.util.concurrent.Future<?> task) task.cancel(false);
  }
  private static void recover(KnowledgeServer server) throws Exception {
    var method = KnowledgeServer.class.getDeclaredMethod("recoverRelease030RootsSafely");
    method.setAccessible(true); method.invoke(server);
  }
}
