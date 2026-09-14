/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.loop.IndexingLoop;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.CoreStatus;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code searchable_doc_count} must be counted on the reader that SERVES search, and must never
 * report a hard 0 because the reader hiccupped.
 *
 * <p>Two defects, one field. (1) The count was taken on {@code ingestCountOps} while search is
 * served from {@code searchCountOps} — during a rebuild those are DIFFERENT generations, so
 * "Searching N documents" described a corpus no query could reach. (2) The count bottomed out at 0
 * on a reader {@code IOException}, and the FE renders a known 0 as the "nothing indexed yet"
 * empty-state CTA — a transient IO blip therefore claimed an empty index.
 */
@DisplayName("searchableDocCount — reader selection + IO fallback")
final class SearchableDocCountReaderSelectionTest extends io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase {

  @TempDir Path tempDir;
  private RunningRuntime serving;
  private RunningRuntime building;

  @AfterEach
  void tearDown() throws Exception {
    if (serving != null) {
      serving.close();
    }
    if (building != null) {
      building.close();
    }
  }

  @Test
  @DisplayName("during a rebuild the field follows the SEARCH reader, not the half-built ingest one")
  void followsSearchServingReader() throws Exception {
    // The generation search still serves: 3 default-scope documents.
    serving = open("serving");
    index(serving, doc("s1", "/docs/a.txt", null));
    index(serving, doc("s2", "/docs/b.txt", "my-notes"));
    index(serving, doc("s3", "/docs/c.txt", null));
    index(serving, doc("sr1", "/runs/r1.md", SchemaFields.AGENT_HISTORY_COLLECTION));
    commit(serving);

    // The generation being rebuilt: only one document written so far.
    building = open("building");
    index(building, doc("b1", "/docs/a.txt", null));
    commit(building);

    CoreStatus core =
        buildStatus(building.indexCountOps(), serving.indexCountOps()).getCore();

    assertEquals(
        3,
        core.getSearchableDocCount(),
        "searchable must describe what a search can return NOW — the serving generation");
    assertEquals(
        1, core.getDocCount(), "docCount keeps its meaning: the ingest reader's non-chunk total");
    assertNotEquals(
        core.getDocCount(),
        core.getSearchableDocCount(),
        "the fixture must exercise the divergence, not accidentally agree");
  }

  @Test
  @DisplayName("a reader IOException falls back to the unscoped count, never a hard 0")
  void ioExceptionFallsBackInsteadOfReportingZero() throws Exception {
    IndexCountOps failing = mock(IndexCountOps.class);
    when(failing.docCount()).thenReturn(12L);
    when(failing.countByField(anyString(), anyString())).thenReturn(2);
    when(failing.countQueryOrThrow(any())).thenThrow(new IOException("reader unavailable"));

    CoreStatus core = buildStatus(failing, null).getCore();

    assertEquals(
        10,
        core.getSearchableDocCount(),
        "a transient reader error must yield the fallback (12 docs - 2 chunks), not 0 — the FE"
            + " renders a known 0 as the empty-index CTA");
  }

  @Test
  @DisplayName("positive control — with a healthy reader the scoped count is what is reported")
  void healthyReaderReportsScopedCount() throws Exception {
    IndexCountOps healthy = mock(IndexCountOps.class);
    when(healthy.docCount()).thenReturn(12L);
    when(healthy.countByField(anyString(), anyString())).thenReturn(2);
    when(healthy.countQueryOrThrow(any())).thenReturn(7);

    CoreStatus core = buildStatus(healthy, null).getCore();

    assertEquals(
        7,
        core.getSearchableDocCount(),
        "the scoped count wins when it is available — the fallback is not the normal path");
  }

  // ---- fixture ----------------------------------------------------------------

  private RunningRuntime open(String name) throws Exception {
    return IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(tempDir.resolve(name)).withExecutorRegistrations(testLuceneExecutors())
        .open();
  }

  private static void index(RunningRuntime runtime, IndexDocument doc) {
    runtime.indexingCoordinator().indexSingle(doc);
  }

  private static void commit(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }

  private static IndexDocument doc(String id, String path, String collection) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "#0");
    fields.put(SchemaFields.PATH, path);
    fields.put(SchemaFields.CONTENT, "content of " + id);
    if (collection != null) {
      fields.put(SchemaFields.COLLECTION, collection);
    }
    return new IndexDocument(fields);
  }

  private io.justsearch.ipc.StatusResponse buildStatus(
      IndexCountOps ingestCountOps, IndexCountOps searchCountOps) {
    JobQueue jobQueue = mock(JobQueue.class);
    when(jobQueue.jobStateCounts()).thenReturn(new JobQueue.JobStateCounts(0, 0, 0, 0, 0));
    when(jobQueue.pendingBytes()).thenReturn(JobQueue.PendingBytes.EMPTY);

    IndexStatusOps ops =
        new IndexStatusOps(
            jobQueue,
            tempDir,
            ingestCountOps,
            searchCountOps,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            OperationalMetrics.getInstance(),
            mock(IndexingLoop.class),
            mock(WorkerSignalBus.class),
            0L);
    return ops.buildStatusResponse();
  }
}
