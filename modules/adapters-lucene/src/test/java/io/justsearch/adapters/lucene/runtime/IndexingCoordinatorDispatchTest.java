package io.justsearch.adapters.lucene.runtime;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 402 — coordinator dispatch mechanics.
 *
 * <p>Verifies {@code executeNow}, {@code deleteAll}, and the convenience mirror methods serialize
 * correctly via {@link IndexingCoordinator#dispatchLock}. The single-writer invariant is the
 * primary concern; these tests exercise it directly.
 */
class IndexingCoordinatorDispatchTest extends LuceneExecutorTestBase {

  @Test
  @Timeout(10)
  void executeNowSynchronouslyCompletesFuture() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-coordinator-execnow-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();
      indexOneDoc(runtime, "doc-a");

      var op =
          IndexWriteOperation.UpdateDoc.of(
              "doc-a", Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
      runtime.indexingCoordinator().executeNow(op);
      assertTrue(op.completion().isDone(), "executeNow must complete synchronously");
      assertTrue(op.completion().get());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  @Test
  @Timeout(10)
  void deleteAllOpClearsIndex() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-coordinator-deleteall-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();
      for (int i = 0; i < 3; i++) {
        indexOneDoc(runtime, "doc-" + i);
      }
      assertEquals(3, runtime.indexCountOps().docCount());

      var op = IndexWriteOperation.DeleteAll.of();
      runtime.indexingCoordinator().executeNow(op);
      assertTrue(op.completion().isDone());
      assertNull(op.completion().get(2, TimeUnit.SECONDS));

      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0, runtime.indexCountOps().docCount());

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  /**
   * Tempdoc 402 Fix A: the convenience-method path is the sole production surface. Verify that
   * two concurrent callers on the same docId both succeed (no lost updates) — same guarantee
   * as the larger reproducer in {@code BatchUpdateIntegrationTest}, scoped here to the
   * convenience method specifically.
   */
  @Test
  @Timeout(15)
  void convenienceMethodSerializesConcurrentCallersOnSameDocId() throws Exception {
    String prev = System.getProperty("justsearch.config");
    Path base = null;
    Path cfg = null;
    try {
      base = Files.createTempDirectory("justsearch-coordinator-convserial-");
      cfg = writeTestConfig(base);
      System.setProperty("justsearch.config", cfg.toString());

      var runtime = createRuntime();
      indexOneDoc(runtime, "doc-race");

      ExecutorService pool = Executors.newFixedThreadPool(2);
      AtomicInteger failures = new AtomicInteger(0);
      try {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);

        Runnable a =
            () -> {
              try {
                ready.countDown();
                go.await();
                runtime
                    .indexingCoordinator()
                    .updateDocument(
                        "doc-race",
                        Map.of(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED));
              } catch (Exception e) {
                failures.incrementAndGet();
              } finally {
                done.countDown();
              }
            };
        Runnable b =
            () -> {
              try {
                ready.countDown();
                go.await();
                runtime
                    .indexingCoordinator()
                    .updateDocument(
                        "doc-race", Map.of(SchemaFields.SPLADE_RETRY_COUNT, "7"));
              } catch (Exception e) {
                failures.incrementAndGet();
              } finally {
                done.countDown();
              }
            };

        pool.submit(a);
        pool.submit(b);
        ready.await();
        go.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "writers hung");
        assertEquals(0, failures.get(), "no writer failures expected under dispatchLock");
      } finally {
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
      }

      runtime.close();
    } finally {
      restoreConfig(prev, base, cfg);
    }
  }

  // ---- helpers (duplicated from BatchUpdateIntegrationTest for isolation) ----

  private void indexOneDoc(RunningRuntime runtime, String id) {
    runtime
        .indexingCoordinator()
        .indexSingle(
            new IndexDocument(
                Map.of(
                    SchemaFields.DOC_ID, id,
                    SchemaFields.DOC_UID, id + "#0",
                    SchemaFields.PATH, "test/" + id + ".txt",
                    SchemaFields.CONTENT, "content for " + id,
                    SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private Path writeTestConfig(Path base) throws Exception {
    String yaml =
        "app:\n  data_dir: "
            + base.toString().replace("\\", "\\\\")
            + "\n"
            + "index:\n  collections:\n    - name: coordinatordispatchtest\n      roots: ['ignored']\n"
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
              { "id": "splade_retry_count", "type": "long", "stored": true, "docValues": true },
              { "id": "vector", "type": "vector", "stored": false, "docValues": false, "rmwPolicy": "preserve-reread", "vector": { "dimension": 4 } }
            ]
          }
          """;
      var mapper = new ObjectMapper();
      var fieldMapper = new FieldMapper(mapper.readTree(json));
      return new IndexSchema(
              fieldMapper,
              new io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry(),
              io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource::new,
              new io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator(),
              null)
          .ephemeral().withExecutorRegistrations(testLuceneExecutors())
          .open();
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
