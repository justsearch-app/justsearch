package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.SearchMode;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.SearchResponse;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 687 R3d — the boot-time search-path warm-up ({@code
 * WorkerSearchService#warmUpSearchPath} / {@code SearchOrchestrator#warmUp}).
 *
 * <p>Two properties matter beyond "it runs without throwing": (1) it must no-op on an empty
 * index rather than doing pointless/undefined work, and (2) it must be invisible to the
 * worker's own search telemetry ({@code OperationalMetrics.recordSearch}, surfaced on {@code
 * /api/status}) — a synthetic boot-time pass must not look like the user's first real search.
 */
@DisplayName("WorkerSearchService search-path warm-up (tempdoc 687 R3d)")
class WorkerSearchServiceWarmUpTest {

  @TempDir Path tempDir;
  private RunningRuntime lifecycle;
  private WorkerSearchService service;

  @BeforeEach
  void setUp() throws Exception {
    System.clearProperty("justsearch.config");
    lifecycle = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0)).atPath(tempDir).open();
    service = new WorkerSearchService(lifecycle);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (lifecycle != null) {
      lifecycle.close();
    }
  }

  private void indexDoc(String docId, String content) throws Exception {
    lifecycle.indexingCoordinator().indexSingle(
        new IndexDocument(
            Map.of(
                SchemaFields.DOC_ID, docId,
                SchemaFields.DOC_UID, docId + "#0",
                SchemaFields.PATH, "C:/docs/" + docId + ".txt",
                SchemaFields.CONTENT, content)));
  }

  private SearchResponse search(String query) {
    SearchResponse result =
        service.search(
            SearchRequest.newBuilder()
                .setQuery(query)
                .setLimit(10)
                .setMode(SearchMode.SEARCH_MODE_TEXT)
                .build(),
            CallContext.none());
    assertNotNull(result);
    return result;
  }

  @Test
  @DisplayName("no-ops gracefully on an empty index (returns false, never throws)")
  void emptyIndex_noOpsGracefully() {
    assertEquals(0L, lifecycle.indexCountOps().docCount(), "precondition: index must be empty");
    boolean ran = assertDoesNotThrow(() -> service.warmUpSearchPath());
    assertFalse(ran, "warm-up must report skipped on an empty index");
  }

  @Test
  @DisplayName("runs the pass and returns true once the index has documents")
  void nonEmptyIndex_runsAndReturnsTrue() throws Exception {
    indexDoc("doc-1", "the quick brown fox jumps over the lazy dog");
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    boolean ran = assertDoesNotThrow(() -> service.warmUpSearchPath());
    assertTrue(ran, "warm-up must report it ran once the index is non-empty");
  }

  @Test
  @DisplayName("does not increment OperationalMetrics search counters (unlike a real search)")
  void warmUp_doesNotRecordSearchMetrics() throws Exception {
    indexDoc("doc-1", "the quick brown fox jumps over the lazy dog");
    lifecycle.commitOps().commitAndTrack();
    lifecycle.commitOps().maybeRefreshBlocking();

    OperationalMetrics metrics = OperationalMetrics.getInstance();
    long before = metrics.getSearchesTotal();

    boolean ran = service.warmUpSearchPath();
    assertTrue(ran);
    long afterWarmUp = metrics.getSearchesTotal();
    assertEquals(
        before,
        afterWarmUp,
        "warm-up must not touch OperationalMetrics.searchesTotal — it would masquerade as the "
            + "user's first real search on /api/status");

    // Sanity check: a REAL search through the same service DOES increment the counter, proving
    // the counter is live in this harness and the equality above isn't a no-op assertion.
    search("fox");
    long afterRealSearch = metrics.getSearchesTotal();
    assertTrue(
        afterRealSearch > afterWarmUp,
        "a real search() call must increment searchesTotal (counter sanity check)");
  }
}
