package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

class BatchUpdateIntegrationTest extends LuceneExecutorTestBase {

  @Test
  void updateDocumentsBatchUpdatesAllDocs() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index 3 documents with SPLADE_STATUS=PENDING
      for (int i = 0; i < 3; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.PATH, "test/doc-" + i + ".txt",
                    SchemaFields.CONTENT, "content " + i,
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Batch-update all 3 to COMPLETED
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        batchUpdates.add(Map.entry("doc-" + i, updates));
      }
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(3, result.updatedCount());
      assertEquals(0, result.notFoundCount());

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify all 3 now have COMPLETED status
      for (int i = 0; i < 3; i++) {
        String status = runtime.documentFieldOps().getDocumentField("doc-" + i, SchemaFields.SPLADE_STATUS);
        assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, status, "doc-" + i);
      }

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchReturnsCorrectCounts() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index only 2 documents
      for (int i = 0; i < 2; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.PATH, "test/doc-" + i + ".txt",
                    SchemaFields.CONTENT, "content " + i,
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Batch-update 3 entries (2 existing + 1 non-existent)
      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED);
        batchUpdates.add(Map.entry("doc-" + i, updates));
      }
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(2, result.updatedCount());
      assertEquals(1, result.notFoundCount());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchPreservesUnchangedFields() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "important content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Update only SPLADE_STATUS
      List<Map.Entry<String, Map<String, Object>>> batchUpdates =
          List.of(
              Map.entry(
                  "doc-0",
                  Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED)));
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);

      assertEquals(1, result.updatedCount());

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Verify content and path are preserved
      String content = runtime.documentFieldOps().getDocumentContent("doc-0");
      assertEquals("important content", content);
      String path = runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.PATH);
      assertEquals("test/doc-0.txt", path);
      String status = runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED, status);

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocumentsBatchNullAndEmptyReturnZero() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-batchupdate-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      var nullResult = runtime.indexingCoordinator().updateDocumentsBatch(null);
      assertEquals(0, nullResult.updatedCount());
      assertEquals(0, nullResult.notFoundCount());

      var emptyResult = runtime.indexingCoordinator().updateDocumentsBatch(List.of());
      assertEquals(0, emptyResult.updatedCount());
      assertEquals(0, emptyResult.notFoundCount());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  void updateDocument_refreshesBeforeRead() throws Exception {
    // Tests that updateDocument performs its own refresh so a previously committed doc is found.
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-updaterefresh-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();

      // Index a doc and commit (but do NOT call maybeRefreshBlocking)
      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-refresh-1",
                  SchemaFields.DOC_UID, "doc-refresh-1#0",
                  SchemaFields.PATH, "test/doc-refresh-1.txt",
                  SchemaFields.CONTENT, "original content",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      runtime.commitOps().commitAndTrack();
      // Deliberately skip maybeRefreshBlocking here

      // updateDocument must do its own refresh — the doc should be found and updated
      boolean updated = runtime.indexingCoordinator().updateDocument(
          "doc-refresh-1",
          Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
      assertTrue(updated, "updateDocument should find and update the committed doc without an explicit refresh");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /**
   * reset-status lane (tempdoc 711): an RMW that omits the {@code splade} field on a COMPLETED doc
   * drops the SPLADE FeatureField data, so the engine must downgrade {@code splade_status} to
   * PENDING (and reset its retry counter) to force a re-encode — even though the caller only touched
   * an unrelated (NER) field. Before 711 this path either preserved COMPLETED (silent data loss) or
   * dropped the status entirely.
   */
  @Test
  void rmwDowngradesCompletedSpladeStatusToPendingWhenDataDropped() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-splade-downgrade-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content that has been SPLADE-encoded",
                  SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f),
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
                  SchemaFields.SPLADE_RETRY_COUNT, "3")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // NER-style RMW: no SPLADE fields in updates → SPLADE data is dropped by the rewrite.
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"));
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "COMPLETED must downgrade to PENDING — the SPLADE data was just dropped, re-encode needed");
      String retryAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_RETRY_COUNT);
      assertEquals("0", retryAfter, "retry counter must reset on the COMPLETED->PENDING downgrade");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- reset-status lane state-matrix coverage (tempdoc 711) ----
  //
  // The reset-status lane preserves FAILED / non-terminal statuses, downgrades COMPLETED to PENDING
  // (data dropped), heals a missing status to PENDING, and always yields to a caller-supplied status.

  /** doc=FAILED, updates=entity — FAILED must survive (resurrecting it would mask real failures). */
  @Test
  void rmwPreservesFailedSpladeStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-splade-failed-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content that permanently failed SPLADE",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_FAILED,
                  SchemaFields.SPLADE_RETRY_COUNT, "5")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"));
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_FAILED,
          statusAfter,
          "FAILED status must survive an unrelated RMW — resurrecting FAILED as PENDING would mask "
              + "real failures and waste encoder cycles");
      String retryAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_RETRY_COUNT);
      assertEquals("5", retryAfter, "retry counter must be preserved alongside a preserved status");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** doc=COMPLETED, updates carry splade_status=PENDING — the caller's explicit value must win. */
  @Test
  void callerSuppliedSpladeStatusOverridesEngine() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-caller-override-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content",
                  SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f),
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED)));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      // Caller explicitly supplies SPLADE_STATUS in the update map — the reset-status lane must
      // yield to it (here it happens to match the engine's own downgrade, but the point is the
      // engine does not fight a caller-provided status).
      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING));
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "Caller-supplied SPLADE_STATUS must win over the reset-status engine");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** doc=PENDING (non-terminal), updates=entity — a non-terminal status is preserved as-is. */
  @Test
  void rmwPreservesNonTerminalSpladeStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-splade-pending-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      runtime.indexingCoordinator().indexSingle(
          new IndexDocument(
              Map.of(
                  SchemaFields.DOC_ID, "doc-0",
                  SchemaFields.DOC_UID, "doc-0#0",
                  SchemaFields.PATH, "test/doc-0.txt",
                  SchemaFields.CONTENT, "content awaiting SPLADE",
                  SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                  SchemaFields.SPLADE_RETRY_COUNT, "2")));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"));
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "a non-terminal PENDING status must survive an unrelated RMW unchanged");
      String retryAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_RETRY_COUNT);
      assertEquals("2", retryAfter, "retry counter must be preserved for a non-terminal status");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** doc=none (no status anywhere), updates=entity — a missing status heals to PENDING. */
  @Test
  void rmwHealsMissingSpladeStatusToPending() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-splade-heal-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      // Index WITHOUT splade_status (a pre-fix corrupted doc). The reset-status lane must heal it
      // to PENDING so it becomes visible to the backfill.
      Map<String, Object> fields = new HashMap<>();
      fields.put(SchemaFields.DOC_ID, "doc-0");
      fields.put(SchemaFields.DOC_UID, "doc-0#0");
      fields.put(SchemaFields.PATH, "test/doc-0.txt");
      fields.put(SchemaFields.CONTENT, "corrupted doc with no splade_status");
      runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      boolean updated =
          runtime.indexingCoordinator().updateDocument(
              "doc-0", Map.of("entity_persons_raw", "Alice"));
      assertTrue(updated);

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      String statusAfter =
          runtime.documentFieldOps().getDocumentField("doc-0", SchemaFields.SPLADE_STATUS);
      assertEquals(
          SchemaFields.SPLADE_STATUS_PENDING,
          statusAfter,
          "a missing status must heal to PENDING so the doc becomes visible to backfill");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /** Batch RMW path: COMPLETED downgrades to PENDING per doc when SPLADE data is dropped. */
  @Test
  void rmwBatchDowngradesCompletedSpladeStatus() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-splade-batch-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithNonStoredSpladeStatus();

      for (int i = 0; i < 2; i++) {
        runtime.indexingCoordinator().indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, "doc-" + i,
                    SchemaFields.DOC_UID, "doc-" + i + "#0",
                    SchemaFields.PATH, "test/doc-" + i + ".txt",
                    SchemaFields.CONTENT, "content " + i,
                    SchemaFields.SPLADE, Map.of("alpha", 2.0f, "beta", 1.0f),
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED,
                    SchemaFields.SPLADE_RETRY_COUNT, "0")));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      List<Map.Entry<String, Map<String, Object>>> batchUpdates = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        batchUpdates.add(Map.entry("doc-" + i, Map.of("entity_persons_raw", "Alice")));
      }
      var result = runtime.indexingCoordinator().updateDocumentsBatch(batchUpdates);
      assertEquals(2, result.updatedCount());

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      for (int i = 0; i < 2; i++) {
        String statusAfter =
            runtime.documentFieldOps().getDocumentField("doc-" + i, SchemaFields.SPLADE_STATUS);
        assertEquals(
            SchemaFields.SPLADE_STATUS_PENDING,
            statusAfter,
            "batch RMW must downgrade each COMPLETED doc to PENDING when SPLADE data is dropped");
      }

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- Tempdoc 393 item 1.4: concurrent-writer race reproducer ----
  //
  // WritePathOps.readModifyWrite reads the doc via IndexSearcher and writes via
  // IndexWriter. The sequence is not atomic. Two concurrent RMW calls on the same
  // docId that update different fields can interleave as
  // {read_A → read_B → write_A → write_B}, silently losing write_A's update.
  //
  // This test reproduces the race in-process by racing two threads on the same
  // docId with orthogonal field updates. Both writes should survive; a surviving
  // single write is a lost-update race hit.
  //
  // REGRESSION GATE (tempdoc 402): after the coordinator landed, this test asserts
  // no lost updates occur when two threads race on the same docId with orthogonal
  // field updates. Both writes MUST survive every iteration — if `lostUpdates > 0`,
  // the single-writer invariant (402 §1.4 fix) has regressed. The formerly-diagnostic
  // reproducer is now a hard gate against re-introducing direct WritePathOps callers
  // or breaking the dispatchLock serialization.

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void concurrentRmwOnSameDocIdSerializedByCoordinator_402() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-rmw-race-test-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntimeWithTwoOrthogonalFields();

      final int iterations = 50;
      AtomicInteger lostUpdates = new AtomicInteger(0);
      AtomicInteger writeFailures = new AtomicInteger(0);

      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        for (int i = 0; i < iterations; i++) {
          String docId = "doc-" + i;
          // Seed the doc with both fields empty.
          runtime.indexingCoordinator().indexSingle(
              new IndexDocument(
                  Map.of(
                      SchemaFields.DOC_ID, docId,
                      SchemaFields.DOC_UID, docId + "#0",
                      SchemaFields.PATH, "test/" + docId + ".txt",
                      SchemaFields.CONTENT, "seed " + i,
                      SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();

          final String docIdFinal = docId;
          CountDownLatch ready = new CountDownLatch(2);
          CountDownLatch go = new CountDownLatch(1);
          CountDownLatch done = new CountDownLatch(2);

          // Thread A writes field_a="A"; Thread B writes field_b="B" (the NER-backfill shape:
          // an RMW touching only a subset of fields). Barrier ensures both threads are poised at
          // updateDocument before releasing.
          Runnable writerA = () -> {
            try {
              ready.countDown();
              go.await();
              runtime.indexingCoordinator().updateDocument(docIdFinal, Map.of("field_a", "A"));
            } catch (Exception e) {
              writeFailures.incrementAndGet();
            } finally {
              done.countDown();
            }
          };
          Runnable writerB = () -> {
            try {
              ready.countDown();
              go.await();
              runtime.indexingCoordinator().updateDocument(docIdFinal, Map.of("field_b", "B"));
            } catch (Exception e) {
              writeFailures.incrementAndGet();
            } finally {
              done.countDown();
            }
          };

          pool.submit(writerA);
          pool.submit(writerB);
          ready.await();
          go.countDown();
          assertTrue(done.await(10, TimeUnit.SECONDS), "writers hung on iteration " + i);

          runtime.commitOps().commitAndTrack();
          runtime.commitOps().maybeRefreshBlocking();

          String fieldA = runtime.documentFieldOps().getDocumentField(docIdFinal, "field_a");
          String fieldB = runtime.documentFieldOps().getDocumentField(docIdFinal, "field_b");
          boolean aPresent = "A".equals(fieldA);
          boolean bPresent = "B".equals(fieldB);
          if (!(aPresent && bPresent)) {
            lostUpdates.incrementAndGet();
          }
        }
      } finally {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
      }

      // Regression gate (tempdoc 402): the coordinator's dispatchLock serializes
      // same-docId RMW, so BOTH writes must survive every iteration.
      assertEquals(0, writeFailures.get(), "unexpected writer exceptions");
      assertEquals(
          0,
          lostUpdates.get(),
          "coordinator must prevent concurrent-RMW lost updates — "
              + lostUpdates.get()
              + "/"
              + iterations
              + " iterations lost one of the two writes; single-writer invariant regressed");

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- helpers ----

  private Path writeTestConfig(Path base) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: batchupdatetest\n      roots: ['ignored']\n"
            + "  vector:\n    dimension: 4\n";
    Path cfg = Files.createTempFile("justsearch-config-", ".yaml");
    Files.writeString(cfg, yaml);
    return cfg;
  }

  private RunningRuntime createRuntime() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Production-matching schema: {@code splade_status} and {@code splade_retry_count} are
   * {@code stored:false, docValues:true}, so they do not survive a stored-field read during
   * a read-modify-write cycle. This is the schema that exposes the tempdoc 334-era bug.
   */
  private RunningRuntime createRuntimeWithNonStoredSpladeStatus() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "splade", "type": "splade", "stored": false, "docValues": false, "rmwPolicy": "reset-status:splade_status" },
              { "id": "entity_persons_raw", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Schema for tempdoc 393 item 1.4 race reproducer: same production-matching
   * non-stored splade_status, plus two orthogonal keyword fields (field_a,
   * field_b) that two threads can write concurrently without colliding on the
   * same key. If both writes survive, no race; if only one survives, race hit.
   */
  private RunningRuntime createRuntimeWithTwoOrthogonalFields() {
    try {
      String json =
          """
          {
            "fields": [
              { "id": "doc_id", "type": "keyword", "stored": true, "docValues": true, "roles": ["id"] },
              { "id": "doc_uid", "type": "keyword", "stored": false, "docValues": true, "roles": ["tiebreak"] },
              { "id": "path", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "content", "type": "text", "stored": true, "docValues": false },
              { "id": "splade_status", "type": "keyword", "stored": false, "docValues": true, "roles": ["filter"] },
              { "id": "splade_retry_count", "type": "long", "stored": false, "docValues": true },
              { "id": "field_a", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "field_b", "type": "keyword", "stored": true, "docValues": true, "roles": ["filter"] },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(fieldMapper, new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(), io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new, new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(), null).ephemeral().withExecutorRegistrations(testLuceneExecutors()).open();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void restoreConfig(String prev, Path base, Path cfg) {
    if (prev == null) {
      System.clearProperty("justsearch.config");
    } else {
      System.setProperty("justsearch.config", prev);
    }
    try {
      if (cfg != null) Files.deleteIfExists(cfg);
      if (base != null) {
        try (var walk = Files.walk(base)) {
          walk.sorted(java.util.Comparator.reverseOrder())
              .forEach(
                  p -> {
                    try {
                      Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                      // best-effort cleanup; a locked file (e.g. Windows file lock) should not fail the test
                    }
                  });
        }
      }
    } catch (Exception ignored) {
      // best-effort cleanup; failures here must not mask the test's actual assertions
    }
  }
}
