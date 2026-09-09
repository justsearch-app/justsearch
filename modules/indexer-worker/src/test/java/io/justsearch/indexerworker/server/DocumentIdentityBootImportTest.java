/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.identity.DocumentIdentityStore;
import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.indexerworker.queue.SqliteDocumentIdentityStore;
import io.justsearch.indexerworker.queue.SqliteJobQueue;
import io.justsearch.indexerworker.services.CallContext;
import io.justsearch.indexerworker.services.WorkerServiceException;
import io.justsearch.indexerworker.util.PathNormalizer;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.PathMapping;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import io.justsearch.ipc.SearchResult;
import io.justsearch.ipc.UpdatePathsRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(180)
@DisplayName("KnowledgeServer document identity bootstrap")
final class DocumentIdentityBootImportTest {

  private KnowledgeServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      try {
        server.close();
      } catch (Exception ignored) {
        // teardown best-effort
      }
      server = null;
    }
  }

  @Test
  @DisplayName("first V11 boot imports parent identities from the serving index")
  void firstBootImportsExistingParentUids(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      for (int i = 0; i < 3; i++) {
        String normalized = PathNormalizer.normalizeKey(Path.of("seed-" + i));
        var identity = probe.lookup(DocumentIdentityStore.pathHash(normalized)).orElseThrow();
        assertEquals("seed-" + i + "#0", identity.docUid());
      }
    }
  }

  @Test
  @DisplayName("the recorded generation import stops the next boot from re-scanning the index")
  // server = null after the mid-test close() guards tearDown() against a double-close if a
  // later step throws before the second `server = new KnowledgeServer(...)` below.
  @SuppressWarnings("PMD.UnusedAssignment")
  void secondBootOverTheSameGenerationDoesNotRescan(@TempDir Path tempDir) throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 3);
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");
    Path dbPath = layout.dataDir().resolve("jobs.db");
    String generationId =
        layout.genManager().readStateBestEffort().active_generation();

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    server.close();
    server = null;

    assertEquals(List.of(3L, 3L, 0L), importRow(dbPath, generationId));

    // A parent that appears in the serving index AFTER the recorded import is exactly what the
    // guard must not go looking for: admission is the only path that mints identity from here on.
    WorkerBootFixture.seedDocument(
        layout.activePath(), null, "after-import.txt", "after-import-uid", "added post-import");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    assertEquals(List.of(3L, 3L, 0L), importRow(dbPath, generationId));
    try (SqliteDocumentIdentityStore probe = new SqliteDocumentIdentityStore(dbPath)) {
      String normalized = PathNormalizer.normalizeKey(Path.of("after-import.txt"));
      assertTrue(
          probe.lookup(DocumentIdentityStore.pathHash(normalized)).isEmpty(),
          "the second boot must not have walked the index again");
      assertEquals(3L, probe.identityCount());
    }
  }

  /** Reads (parents_seen, parents_imported, parents_skipped) for one generation. */
  private static List<Long> importRow(Path dbPath, String generationId) throws Exception {
    try (var conn =
            java.sql.DriverManager.getConnection(
                "jdbc:sqlite:" + dbPath.toString().replace('\\', '/'));
        var stmt =
            conn.prepareStatement(
                "SELECT parents_seen, parents_imported, parents_skipped"
                    + " FROM document_identity_import WHERE generation_id = ?")) {
      stmt.setString(1, generationId);
      try (var rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "no import row recorded for generation " + generationId);
        return List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3));
      }
    }
  }

  @Test
  @DisplayName("a stale serving path cannot reverse an already persisted rename")
  void staleServingIndexDoesNotUndoStoreAuthoritativeRename(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    WorkerBootFixture.seed(layout.activePath(), null, 1);
    Path dbPath = layout.dataDir().resolve("jobs.db");
    try (SqliteJobQueue queue = new SqliteJobQueue(dbPath)) {
      queue.open();
    }

    String oldPath = PathNormalizer.normalizeKey(Path.of("seed-0"));
    String newPath = PathNormalizer.normalizeKey(tempDir.resolve("renamed-seed-0"));
    String uid = "seed-0#0";
    try (SqliteDocumentIdentityStore store = new SqliteDocumentIdentityStore(dbPath)) {
      store.importExisting(DocumentIdentityStore.pathHash(newPath), uid, 10L);
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "FAIL_CLOSED");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    try (SqliteDocumentIdentityStore probe = new SqliteDocumentIdentityStore(dbPath)) {
      assertTrue(
          probe.lookup(DocumentIdentityStore.pathHash(oldPath)).isEmpty(),
          "a stale Blue snapshot must not move the uid back to its pre-rename path");
      assertEquals(
          uid,
          probe.lookup(DocumentIdentityStore.pathHash(newPath)).orElseThrow().docUid());
    }
  }

  @Test
  @DisplayName("Blue identity is imported before a paused migration reindexes into Green")
  // server = null between the mid-test close() and the later reassignment guards
  // tearDown() against acting on an already-torn-down handle if a later step throws.
  @SuppressWarnings("PMD.UnusedAssignment")
  void blueUidIsImportedBeforePausedMigrationReindexesIntoGreen(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Path root = Files.createDirectories(tempDir.resolve("documents"));
    Path source = root.resolve("continuity.txt");
    Files.writeString(source, "identity continuity through migration ".repeat(400));
    String docId = PathNormalizer.normalizeKey(source);
    String blueUid = "00000000-0000-4000-8000-0000000000aa";
    String wrongGreenUid = "00000000-0000-4000-8000-0000000000bb";

    WorkerBootFixture.seedDocument(
        layout.activePath(), null, docId, blueUid, "old Blue content");
    var migration = layout.genManager().startMigration("identity_continuity_test");
    Path greenPath =
        layout.genManager().resolveGenerationPathStrict(migration.building_generation());
    WorkerBootFixture.seedDocument(greenPath, null, docId, wrongGreenUid, "stale Green content");
    layout.genManager().setMigrationPaused(true, "identity continuity assertion");
    Files.writeString(
        layout.dataDir().resolve("watched_roots.json"),
        new tools.jackson.databind.ObjectMapper().writeValueAsString(List.of(root.toString())));
    WorkerBootFixture.publishConfig(
        layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    server.releaseModelReadyLatchForTests();

    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertEquals(
          blueUid,
          probe.lookup(DocumentIdentityStore.pathHash(docId)).orElseThrow().docUid(),
          "the serving Blue index must seed identity authority before enumeration starts");
    }
    assertEquals(
        wrongGreenUid,
        server
            .lifecycleManagerForTests()
            .documentFieldOps()
            .getDocumentField(docId, SchemaFields.DOC_UID),
        "the pause must hold Green at its adverse pre-reindex identity");

    Path renamed = root.resolve("continuity-renamed.txt");
    Files.move(source, renamed);
    String renamedDocId = PathNormalizer.normalizeKey(renamed);
    var renamedResponse =
        server
            .appServices()
            .ingestService()
            .updateDocumentPaths(
                UpdatePathsRequest.newBuilder()
                    .addMappings(
                        PathMapping.newBuilder()
                            .setOldPath(docId)
                            .setNewPath(renamedDocId)
                            .build())
                    .build(),
                CallContext.none());
    assertEquals(1, renamedResponse.getUpdatedCount());
    assertTrue(renamedResponse.getFailedPathsList().isEmpty());
    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertTrue(probe.lookup(DocumentIdentityStore.pathHash(docId)).isEmpty());
      assertEquals(
          blueUid,
          probe
              .lookup(DocumentIdentityStore.pathHash(renamedDocId))
              .orElseThrow()
              .docUid(),
          "the production ingest service must be wired to the durable identity store");
    }
    assertEquals(
        wrongGreenUid,
        server
            .lifecycleManagerForTests()
            .documentFieldOps()
            .getDocumentField(renamedDocId, SchemaFields.DOC_UID),
        "the supported rename moves Green's adverse row without rewriting its uid");

    server.indexGenerationManagerForTests().setMigrationPaused(false, null);
    awaitGreenUid(renamedDocId, blueUid, Duration.ofSeconds(15));

    // The chunks are written in a pass AFTER the parent (ChunkDocumentWriter
    // .regenerateChunksFromExistingParent runs once the parent row is in), so "the parent's uid is
    // visible" does not yet mean "its chunks are visible" — a one-shot scan here failed on a loaded
    // CI runner. Wait for the condition the assertion actually needs, with the same bound; a
    // migration that never derives the chunks still fails, with the state in the message.
    List<Map<String, String>> chunkHits = awaitGreenChunks(renamedDocId, 2, Duration.ofSeconds(15));
    for (Map<String, String> fields : chunkHits) {
      assertEquals(renamedDocId, fields.get(SchemaFields.PARENT_DOC_ID));
      assertEquals(
          blueUid + "#" + fields.get(SchemaFields.CHUNK_INDEX),
          fields.get(SchemaFields.DOC_UID),
          "ordinary migration indexing must derive chunks from the parent uid");
    }

    // Tempdoc 931 §C.4 — the rebuild claim, read where PR-B's consumer reads it.
    //
    // Everything above reaches Green through the Worker's own fixture handles. The value feedback
    // is keyed on is not that one: it is the `doc_uid` entry in a SearchResult's generic fields map
    // (SearchResponseBuilder.java:653-656 for a chunk hit's parent metadata, the stored-field
    // projection for a whole-document hit), which KnowledgeSearchController.withFeedbackUid adds to
    // the projection on every Head search. A uid that survives the migration in the index but not
    // in the served response orphans every label just the same.
    //
    // Green is not the SERVING generation while the migration is in flight — searchLifecycle is
    // Blue by construction — so the search has to happen after the cutover. The production cutover
    // ends in a Worker restart (KnowledgeServerMigrationOps promotes, then calls
    // initiateShutdownAction), so this reproduces exactly that sequence: commit Green, close, promote,
    // boot again. Searching the in-flight server instead would have queried BLUE and passed on
    // Blue's own uid, proving nothing about the rebuild.
    server.lifecycleManagerForTests().commitOps().commitAndTrack();
    server.close();
    // server = null: if promoteBuildingGenerationToActive() below throws before the reassignment
    // further down, tearDown() reads this field and must not act on the already-closed server.
    server = null;

    IndexGenerationManager postMigration = new IndexGenerationManager(layout.indexBase());
    postMigration.promoteBuildingGenerationToActive();

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();
    server.releaseModelReadyLatchForTests();

    SearchRequest asHeadSendsIt =
        SearchRequest.newBuilder()
            .setQuery("continuity")
            .setLimit(10)
            .addProjection(SchemaFields.PATH)
            .addProjection(SchemaFields.DOC_UID)
            // Tempdoc 931 §C.6 — feedback keys on (uid, content revision), so the rebuild claim
            // covers both. Head projects them together; a revision lost across the rebuild would
            // age every surviving label into staleness just as silently as a re-minted uid.
            .addProjection(SchemaFields.CONTENT_SHA256)
            .build();
    // A fresh boot over an index that already has segments opens DEFERRED: read-only first, with
    // the writer and the analyzers arriving on the background upgrade. So both "not ready yet"
    // shapes are polled through — the call failing outright, and the call answering empty — rather
    // than being read as the answer.
    List<SearchResult> uidBearingHits = List.of();
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    Object lastOutcome = "no search attempted";
    while (System.nanoTime() < deadline && uidBearingHits.isEmpty()) {
      try {
        SearchResponse searchResponse =
            server.appServices().searchService().search(asHeadSendsIt, CallContext.none());
        lastOutcome = searchResponse;
        uidBearingHits =
            searchResponse.getResultsList().stream()
                .filter(hit -> hit.getFieldsMap().containsKey(SchemaFields.DOC_UID))
                .toList();
      } catch (WorkerServiceException notReadyYet) {
        lastOutcome = notReadyYet.status() + ": " + notReadyYet.getMessage();
      }
      if (uidBearingHits.isEmpty()) {
        Thread.sleep(200L);
      }
    }
    assertFalse(
        uidBearingHits.isEmpty(),
        "the promoted Green must serve the document through the search service AND carry the"
            + " feedback key; last outcome="
            + lastOutcome);
    for (SearchResult hit : uidBearingHits) {
      assertEquals(
          renamedDocId, hit.getId(), "the hit identifies the renamed parent, not a chunk");
      assertEquals(
          blueUid,
          hit.getFieldsMap().get(SchemaFields.DOC_UID),
          "the promoted generation must serve the uid Blue served: this is the exact value Head"
              + " keys feedback on, so a re-minted uid here silently orphans every label authored"
              + " before the rebuild");
      String revision = hit.getFieldsMap().get(SchemaFields.CONTENT_SHA256);
      assertNotNull(revision, "the promoted generation must serve the content revision too");
      assertTrue(
          revision.matches("[0-9a-f]{64}"),
          "the served revision must be lowercase SHA-256 hex, not a truncated or upper-cased form:"
              + " " + revision);
    }
  }

  /** Chunk hits of {@code parentDocId} once at least {@code minimum} are visible, else fails. */
  private List<Map<String, String>> awaitGreenChunks(
      String parentDocId, int minimum, Duration timeout) throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<Map<String, String>> chunkHits = List.of();
    while (true) {
      var documents =
          server
              .lifecycleManagerForTests()
              .readPathOps()
              .search(
                  new MatchAllDocsQuery(),
                  100,
                  Set.of(
                      SchemaFields.DOC_UID,
                      SchemaFields.IS_CHUNK,
                      SchemaFields.PARENT_DOC_ID,
                      SchemaFields.CHUNK_INDEX),
                  LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE,
                  null);
      chunkHits =
          documents.hits().stream()
              .map(hit -> hit.fields())
              .filter(fields -> "true".equals(fields.get(SchemaFields.IS_CHUNK)))
              .filter(fields -> parentDocId.equals(fields.get(SchemaFields.PARENT_DOC_ID)))
              .toList();
      if (chunkHits.size() >= minimum) {
        return chunkHits;
      }
      if (System.nanoTime() >= deadline) {
        break;
      }
      Thread.sleep(100L);
    }
    assertTrue(
        chunkHits.size() >= minimum,
        "Green never derived "
            + minimum
            + " chunks for "
            + parentDocId
            + " before the bounded timeout (saw "
            + chunkHits.size()
            + "); state="
            + server.indexGenerationManagerForTests().readStateBestEffort()
            + ", queue="
            + server.jobQueueForTests().jobStateCounts()
            + ", failures="
            + server.jobQueueForTests().failureSummary());
    return chunkHits;
  }

  @Test
  @DisplayName("a pending boot job resolves identity only after Blue import establishes authority")
  void pendingJobAtBootReusesBlueUidBeforeMigrationEnumeration(@TempDir Path tempDir)
      throws Exception {
    WorkerBootFixture.Layout layout = WorkerBootFixture.layout(tempDir);
    Path source = tempDir.resolve("pending-before-boot.txt");
    Files.writeString(source, "pending boot identity continuity");
    String docId = PathNormalizer.normalizeKey(source);
    String blueUid = "00000000-0000-4000-8000-0000000000cc";

    WorkerBootFixture.seedDocument(layout.activePath(), null, docId, blueUid, "Blue authority");
    layout.genManager().startMigration("pending_boot_identity_test");
    layout.genManager().setMigrationPaused(true, "exclude migration enumeration from assertion");
    try (SqliteJobQueue queue =
        new SqliteJobQueue(layout.dataDir().resolve("jobs.db"))) {
      queue.open();
      assertEquals(1, queue.enqueue(List.of(source)));
    }
    WorkerBootFixture.publishConfig(layout.dataDir(), layout.indexBase(), "BLUE_GREEN_MIGRATE");

    server = new KnowledgeServer(new io.justsearch.core.execution.TestEngineExecutors(), WorkerBootFixture.workerConfig(layout.dataDir()));
    server.start();

    awaitGreenUid(docId, blueUid, Duration.ofSeconds(15));
    try (SqliteDocumentIdentityStore probe =
        new SqliteDocumentIdentityStore(layout.dataDir().resolve("jobs.db"))) {
      assertEquals(
          blueUid,
          probe.lookup(DocumentIdentityStore.pathHash(docId)).orElseThrow().docUid());
    }
  }

  private void awaitGreenUid(String docId, String expectedUid, Duration timeout)
      throws Exception {
    long deadline = System.nanoTime() + timeout.toNanos();
    String observed = null;
    while (System.nanoTime() < deadline) {
      observed =
          server
              .lifecycleManagerForTests()
              .documentFieldOps()
              .getDocumentField(docId, SchemaFields.DOC_UID);
      if (expectedUid.equals(observed)) {
        return;
      }
      Thread.sleep(100L);
    }
    assertEquals(
        expectedUid,
        observed,
        "Green did not reuse the durable Blue identity before the bounded timeout; state="
            + server.indexGenerationManagerForTests().readStateBestEffort()
            + ", queue="
            + server.jobQueueForTests().jobStateCounts()
            + ", failures="
            + server.jobQueueForTests().failureSummary());
  }
}
