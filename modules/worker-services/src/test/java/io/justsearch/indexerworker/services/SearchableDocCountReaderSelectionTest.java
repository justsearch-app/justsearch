/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
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
  // during a rebuild the field follows the SEARCH reader, not the half-built ingest one
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

    var status = buildStatus(building.indexCountOps(), serving.indexCountOps());
    CoreStatus core = status.getCore();
    assertEquals(1, status.getMigration().getBuildingDocCount());
    assertEquals(4, status.getMigration().getActiveDocCount());

    assertEquals(
        3,
        core.getSearchableDocCount(),
        "searchable must describe what a search can return NOW — the serving generation");
    assertEquals(
        4, core.getDocCount(), "indexed count follows searchable parents in the serving generation");
    // Reopen the serving generation with the queue history unchanged.
    serving.close();
    serving = open("serving");
    assertEquals(4, buildStatus(building.indexCountOps(), serving.indexCountOps()).getCore().getDocCount());
    assertNotEquals(
        core.getDocCount(),
        core.getSearchableDocCount(),
        "the fixture must exercise the divergence, not accidentally agree");
  }

  @Test
  // a failed filtered read is unknown until that population has been observed
  void ioExceptionFallsBackInsteadOfReportingZero() throws Exception {
    IndexCountOps failing = mock(IndexCountOps.class);
    when(failing.docCount()).thenReturn(12L);
    when(failing.countByField(anyString(), anyString())).thenReturn(2);
    // The real IndexCountOps answers the throwing and non-throwing count forms alike when its
    // reader is healthy; the parent count is read through the throwing form (t-20261013-ef3c41).
    when(failing.docCountOrThrow()).thenReturn(12L);
    parentSnapshotReturns(failing, 10L);
    when(failing.countByFieldOrThrow(anyString(), anyString())).thenReturn(2);
    when(failing.countQueryOrThrow(any())).thenThrow(new IOException("reader unavailable"));

    CoreStatus core = buildStatus(failing, null).getCore();

    assertEquals(0, core.getSearchableDocCount());
    org.junit.jupiter.api.Assertions.assertTrue(core.getDocCountUnknown(),
        "An unfiltered parent count cannot certify the default search population");
  }

  @Test
  // positive control — with a healthy reader the scoped count is what is reported
  void healthyReaderReportsScopedCount() throws Exception {
    IndexCountOps healthy = mock(IndexCountOps.class);
    when(healthy.docCount()).thenReturn(12L);
    when(healthy.countByField(anyString(), anyString())).thenReturn(2);
    // The real IndexCountOps answers the throwing and non-throwing count forms alike when its
    // reader is healthy; the parent count is read through the throwing form (t-20261013-ef3c41).
    when(healthy.docCountOrThrow()).thenReturn(12L);
    parentSnapshotReturns(healthy, 10L);
    when(healthy.countByFieldOrThrow(anyString(), anyString())).thenReturn(2);
    when(healthy.countQueryOrThrow(any())).thenReturn(7);

    CoreStatus core = buildStatus(healthy, null).getCore();

    assertEquals(
        7,
        core.getSearchableDocCount(),
        "the scoped count wins when it is available — the fallback is not the normal path");
  }

  @Test
  void parentCountsDoNotSubtractIndependentReaderObservations() throws Exception {
    serving = open("serving");
    index(serving, doc("p1", "/docs/one", null));
    index(serving, doc("p2", "/docs/two", null));
    commit(serving);
    var counts = spy(serving.indexCountOps());
    doReturn(3L).when(counts).docCountOrThrow();
    doReturn(4).when(counts).countByFieldOrThrow(SchemaFields.IS_CHUNK, "true");
    assertEquals(2, buildStatus(counts, null).getCore().getDocCount(),
        "a refresh between legacy reads must not fabricate a parent population");
  }

  @Test
  void aReadOnlyServingReaderReportsFinishedEnrichmentAfterReopen() throws Exception {
    Path path = tempDir.resolve("short-only");
    var catalogFields = new java.util.ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String stage : java.util.List.of(SchemaFields.SPLADE_STATUS, SchemaFields.NER_STATUS)) {
      catalogFields.add(new FieldCatalogDef.FieldDef(stage, "keyword", true, true,
          java.util.List.of("filter"), null, null, false));
    }
    var catalog = new FieldCatalogDef("short-library", catalogFields);
    serving = IndexSchema.fromCatalog(catalog).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, "short");
    fields.put(SchemaFields.DOC_UID, "short#0");
    fields.put(SchemaFields.PATH, "/docs/short.txt");
    fields.put(SchemaFields.CONTENT, "short");
    fields.put(SchemaFields.CONTENT_SHA256, io.justsearch.indexing.chunking.ChunkParentRevision.sha256Hex("short"));
    fields.put(SchemaFields.VECTOR, new float[] {1, 0, 0, 0});
    fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_COMPLETED);
    fields.put(SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_COMPLETED_EMPTY);
    fields.put(SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_COMPLETED_EMPTY);
    io.justsearch.indexerworker.rag.ChunkDocumentWriter.declare(fields,
        io.justsearch.indexerworker.rag.ChunkDocumentWriter.plan("short", "text/plain", "text"));
    index(serving, new IndexDocument(fields));
    commit(serving);
    serving.close();
    serving = IndexSchema.fromCatalog(catalog).atPath(path)
        .withExecutorRegistrations(testLuceneExecutors()).open();
    var status = buildStatus(null, serving.indexCountOps());
    assertEquals(1, status.getEnrichment().getEmbedding().getCompletedCount());
    assertEquals(1, status.getEnrichment().getCompletedNerCount());
    assertEquals(0, status.getEnrichment().getChunk().getDocCount());
    var field = status.getEnrichment().getChunk().getDescriptorForType().findFieldByName("chunking_not_required");
    org.junit.jupiter.api.Assertions.assertNotNull(field);
    assertEquals(Boolean.TRUE, status.getEnrichment().getChunk().getField(field));
  }

  // ---- fixture ----------------------------------------------------------------

  private static void parentSnapshotReturns(IndexCountOps counts, long value) throws Exception {
    try {
      IndexCountOps.class.getMethod("parentDocCountOrThrow").invoke(doReturn(value).when(counts));
    } catch (NoSuchMethodException beforeSnapshotCount) {
      // The baseline reads total and chunk counts separately.
    }
  }

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
    when(jobQueue.completedCount()).thenReturn(91L);
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
