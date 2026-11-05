/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.queue;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.*;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.loop.IndexingLoopOptions;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.server.WorkerExecutorRegistrations;
import io.justsearch.indexerworker.util.PathNormalizer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** FU-4: real SQLite ownership and the real timebox/parser/writer, with admissions before writes. */
@Timeout(60)
final class RetirementMaintenanceAdmissionTest extends LuceneExecutorTestBase {
  @TempDir Path temporary;

  @Test
  void claimedRootKeepsOriginalExtractionAcrossFreshReceipts() throws Exception {
    extractionAcrossAdmissions(1);
  }

  @Test
  void outstandingBatchPublishesOnceAndLaterBatchProgresses() throws Exception {
    extractionAcrossAdmissions(4);
  }

  @Test
  void legacyRootWithoutSourceIdentityKeepsOriginalExtraction() throws Exception {
    extractionAcrossAdmissions(1, false, false, true);
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"false,false", "true,false", "false,true"})
  void changedDuringExtractionNeverPublishesObsoleteBytes(boolean alternate, boolean withoutIdentity) throws Exception {
    extractionAcrossAdmissions(1, true, alternate, withoutIdentity);
  }

  private void extractionAcrossAdmissions(int count) throws Exception {
    extractionAcrossAdmissions(count, false, false);
  }

  private void extractionAcrossAdmissions(int count, boolean changed, boolean alternate) throws Exception {
    extractionAcrossAdmissions(count, changed, alternate, false);
  }

  private void extractionAcrossAdmissions(int count, boolean changed, boolean alternate, boolean withoutIdentity) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    var files = new ArrayList<Path>();
    for (int i = 0; i < count + 1; i++) files.add(Files.writeString(root.resolve(i + ".txt"), "violetmarker" + i));
    Path db = initialize();
    try (var q = open(db);
         var owners = new TestEngineExecutors();
         var identities = new SqliteDocumentIdentityStore(db);
         var runtime = IndexSchema.fromCatalog(new JustSearchConfigurationLoader().loadFieldCatalog())
             .ephemeral().withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var registrations = new WorkerExecutorRegistrations(owners);
      admit(q, root, files, false, "docs");
      if (alternate) for (var file : files)
        assertTrue(q.enqueueAndBufferFileForGeneration("building", entry(file, root), "docs", null));
      if (withoutIdentity) sql(db, "UPDATE jobs SET admitted_source_sha256=NULL");
      var claims = q.pollPending(count);
      assertEquals(count, claims.size());
      var before = claims.stream().map(c -> stable(q, c.path())).toList();
      var snapshots = new ArrayList<List<Map<String,String>>>();
      var calls = new HashMap<Path,Integer>();
      var parser = new ContentExtractor();
      var sandbox = new ExtractionSandbox() {
        @Override public ExtractionArtifact extract(Path file) throws java.io.IOException, ContentExtractor.ExtractionException {
          calls.merge(file, 1, Integer::sum);
          var extracted = ExtractionArtifact.full(parser.extract(file), "tika");
          if (changed && file.equals(files.getFirst()) && calls.get(file) == 1) {
            var stamp = Files.getLastModifiedTime(file);
            Files.writeString(file, "orangemarker0");
            Files.setLastModifiedTime(file, stamp);
          }
          // Extraction is synchronously held here. Each tick journals a fresh ROOT; all batch
          // writes happen after the last extraction, exactly as in the production batch loop.
          for (int tick = 0; !file.equals(files.getLast()) && calls.get(file) == 1 && tick < 3; tick++) {
            if (alternate) assertEquals(files.size(), q.enqueueRelease030LiveSyncEntries(
                files.stream().map(f -> entry(f, root)).toList(), "docs", true, () -> false));
            else admit(q, root, files, true, "docs");
            snapshots.add(claims.stream().map(c -> stable(q, c.path())).toList());
          }
          return extracted;
        }
        @Override public TikaExtractionPolicy policy() { return TikaExtractionPolicy.defaults(); }
        @Override public void close() { }
      };
      try (var box = new TimeboxedContentExtractor(registrations.extractionTimebox(), sandbox,
              java.time.Duration.ofSeconds(30), null);
           var loop = new IndexingLoop(registrations.pdfOcr(), registrations.extractionTimebox(), q,
               runtime.indexingCoordinator(), runtime.commitOps(), runtime.documentFieldOps(), runtime.indexCountOps(),
               () -> ResolvedConfig.builder().build(), mock(WorkerSignalBus.class), IndexingPacing.unthrottled(),
               null, null, null, null, box, null,
               new IndexingLoopOptions(false, null, identities, null, null, null))) {
        var running = IndexingLoop.class.getDeclaredField("running");
        running.setAccessible(true);
        ((AtomicBoolean) running.get(loop)).set(true);
        try {
          process(loop, claims);
          if (changed) {
            finish(loop);
            assertNull(runtime.documentFieldOps().getDocumentField(key(files.getFirst()), "content"),
                "obsolete extracted bytes must never publish");
            assertFalse(q.ownsClaimForPublication(claims.getFirst()));
            if (withoutIdentity) {
              admit(q, root, files, true, "docs");
              // Advance the real stale-source deferral's due time; never sleep or reset IO/parser waits.
              sql(db, "UPDATE jobs SET retry_after=0 WHERE state='PENDING' AND last_outcome_class='STALE_SOURCE'");
            }
            var current = q.pollPending(10);
            assertEquals(new HashSet<>(files), new HashSet<>(current.stream().map(JobQueue.IndexJob::path).toList()));
            process(loop, current);
            finish(loop);
            assertEquals("orangemarker0", runtime.documentFieldOps().getDocumentContent(key(files.getFirst())).strip());
            assertEquals(2, calls.get(files.getFirst()));
            assertEquals(1, calls.get(files.getLast()));
            assertTrue(q.pollPending(10).isEmpty());
            return;
          }
          for (var snapshot : snapshots) assertEquals(before, snapshot,
              "fresh ROOT receipts must retain the claimed member revision and ownership");
          assertEquals(count * 3, snapshots.size());
          finish(loop);
          for (var claim : claims) {
            assertEquals("DONE", stable(q, claim.path()).get("state"));
            assertEquals("violetmarker" + files.indexOf(claim.path()),
                runtime.documentFieldOps().getDocumentContent(key(claim.path())).strip());
          }
          var later = q.pollPending(10);
          assertEquals(List.of(files.getLast()), later.stream().map(JobQueue.IndexJob::path).toList());
          process(loop, later);
          finish(loop);
          assertEquals("violetmarker" + count,
              runtime.documentFieldOps().getDocumentContent(key(files.getLast())).strip());
          assertTrue(q.pollPending(10).isEmpty());
          assertEquals(files.size(), calls.size());
          assertTrue(calls.values().stream().allMatch(n -> n == 1), "one parser request per file");
        } finally { ((AtomicBoolean) running.get(loop)).set(false); }
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @ValueSource(booleans = {false, true})
  void readyPendingRetainsHistoryAndCanBeClaimedAfterReopen(boolean withoutIdentity) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("pending.txt"), "unchanged pending");
    Path db = initialize();
    Map<String,String> before;
    try (var q = open(db)) {
      admit(q, root, List.of(file), false, "docs");
      sql(db, "UPDATE jobs SET attempts=3,retry_after=1234,first_failed_at=123,error_message='previous failure',"
          + "last_outcome_class='SANDBOX_FAILED',last_reason_code='SANDBOX_FAILED',last_retry_policy='RETRY_WITH_BACKOFF'");
      if (withoutIdentity) sql(db, "UPDATE jobs SET admitted_source_sha256=NULL");
      before = stable(q, file);
      for (int tick = 0; tick < 3; tick++) {
        admit(q, root, List.of(file), true, "docs");
        assertEquals(before, stable(q, file), "ready PENDING retains revision, attribution and retry history");
      }
    }
    try (var q = open(db)) {
      q.prepareRetirementClaims();
      assertEquals(before, stable(q, file));
      var claim = q.pollPending(1).getFirst();
      assertEquals(before.get("unit_revision"), claim.unitRevision());
      assertTrue(q.ownsClaimForPublication(claim));
    }
  }

  @Test
  void alternateMaintenanceRetainsRootAndExactReceiptWitness() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path rootFile = Files.writeString(root.resolve("root.txt"), "root bytes");
    Path exactFile = Files.writeString(root.resolve("exact.txt"), "exact bytes");
    Path db = initialize();
    try (var q = open(db)) {
      admit(q, root, List.of(rootFile), false, "docs");
      assertTrue(q.enqueueAndBufferFileForGeneration("building", entry(exactFile, root), "docs", null));
      var claims = q.pollPending(10);
      assertEquals(2, claims.size());
      var before = claims.stream().map(c -> stable(q, c.path())).toList();
      var receipts = q.retirementReplayOps();
      for (int tick = 0; tick < 3; tick++) {
        assertEquals(2, q.enqueueRelease030LiveSyncEntries(
            List.of(entry(rootFile, root), entry(exactFile, root)), "docs", true, () -> false));
        assertEquals(before, claims.stream().map(c -> stable(q, c.path())).toList(),
            "alternate maintenance retains current ROOT and exact owners");
        assertEquals(receipts, q.retirementReplayOps(), "exact receipt and its source witness are immutable");
        assertTrue(claims.stream().allMatch(q::ownsClaimForPublication));
      }
    }
  }

  @Test
  void hintlessAlternateMaintenanceRetainsReservedExactOwner() throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("history.txt"), "internal history");
    Path db = initialize();
    try (var q = open(db)) {
      q.useDeclaredCollectionRoots(() -> List.of(
          new io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding(root, "docs")));
      assertTrue(q.enqueueAndBufferFileForGeneration("building", entry(file, root), "agent-history", null));
      var claim = q.pollPending(1).getFirst();
      var before = stable(q, file);
      var receipts = q.retirementReplayOps();
      for (int tick = 0; tick < 3; tick++) {
        assertEquals(1, q.enqueueRelease030LiveSyncEntries(List.of(entry(file, root)), null, true, () -> false));
        assertEquals(before, stable(q, file), "hintless admission keeps the internal corpus and its exact owner");
        assertEquals(receipts, q.retirementReplayOps());
        assertTrue(q.ownsClaimForPublication(claim));
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @CsvSource({"bytes,false", "collection,false", "root,false", "force,false", "live,false",
      "bytes,true", "collection,true", "root,true", "force,true", "live,true"})
  void differentObligationStillReplacesTheClaim(String change, boolean rootAdmission) throws Exception {
    Path root = Files.createDirectory(temporary.resolve("sources"));
    Path file = Files.writeString(root.resolve("source.txt"), "old!");
    Path db = initialize();
    try (var q = open(db)) {
      admit(q, root, List.of(file), false, "docs");
      var claim = q.pollPending(1).getFirst();
      var stamp = Files.getLastModifiedTime(file);
      if (change.equals("bytes")) { Files.writeString(file, "new!"); Files.setLastModifiedTime(file, stamp); }
      var incoming = entry(file, change.equals("root") ? temporary : root).withForceReindex(change.equals("force"));
      String collection = change.equals("collection") ? "other" : "docs";
      if (rootAdmission) {
        Path boundary = change.equals("root") ? temporary : root;
        String prefix = PathNormalizer.normalizePathPrefix(boundary.toString());
        assertTrue(q.putSyncRootForGeneration("building", "sync_root:" + prefix,
            new SwitchBufferSyncRoot(prefix, false, null), collection));
        assertEquals(1, q.enqueueRetirementRootEntries(q.retirementRootReceipt("building", "sync_root:" + prefix),
            List.of(incoming), collection, !change.equals("live"), false, () -> false));
      } else assertEquals(1, q.enqueueRelease030LiveSyncEntries(List.of(incoming),
          collection, !change.equals("live"), () -> false));
      assertFalse(q.ownsClaimForPublication(claim));
      assertNotEquals(claim.unitRevision(), stable(q, file).get("unit_revision"));
    }
  }

  private Path initialize() throws Exception {
    Path db = temporary.resolve("jobs.db");
    try (var q = new SqliteJobQueue(db)) { q.open(); }
    Release030RecoveryStateFixture.capture(db);
    Release030RecoveryState.load(db).beginRetirement("active", "building", Map.of());
    return db;
  }

  private static SqliteJobQueue open(Path db) throws Exception {
    var q = new SqliteJobQueue(db);
    q.open(); q.inventoryRetirementRoots(); q.retainRelease030Retirement("building", List.of(), Map.of());
    return q;
  }

  private static JobQueue.EnqueueEntry entry(Path file, Path root) {
    return JobQueue.EnqueueEntry.stat(file, new JobQueue.EnqueueProvenance("user", "HTTP")).withinRoot(root);
  }

  private static void admit(SqliteJobQueue q, Path root, List<Path> files, boolean maintenance, String collection) {
    String prefix = PathNormalizer.normalizePathPrefix(root.toString());
    assertTrue(q.putSyncRootForGeneration("building", "sync_root:" + prefix,
        new SwitchBufferSyncRoot(prefix, false, null), collection));
    var receipt = q.retirementRootReceipt("building", "sync_root:" + prefix);
    assertEquals(files.size(), q.enqueueRetirementRootEntries(receipt,
        files.stream().map(f -> entry(f, root)).toList(), collection, maintenance, false, () -> false));
  }

  private static Map<String,String> stable(SqliteJobQueue q, Path file) {
    var row = new LinkedHashMap<>(q.watcherReconciliationRow(key(file)));
    row.remove("last_updated");
    return row;
  }

  private static String key(Path path) { return PathNormalizer.normalizeKey(path); }

  private static void sql(Path db, String sql) throws Exception {
    try (var c = DriverManager.getConnection("jdbc:sqlite:" + db); var s = c.createStatement()) { s.executeUpdate(sql); }
  }

  private static void process(IndexingLoop loop, List<JobQueue.IndexJob> claims) throws Exception {
    var method = IndexingLoop.class.getDeclaredMethod("processBatch", List.class);
    method.setAccessible(true); method.invoke(loop, claims);
  }

  private static void finish(IndexingLoop loop) throws Exception {
    var method = IndexingLoop.class.getDeclaredMethod("finishIdleCommit");
    method.setAccessible(true); assertEquals(true, method.invoke(loop));
  }
}
