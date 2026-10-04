/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.ipc.QueryPendingVduRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Procedure control must not turn an unavailable index into successful no-work. */
class EnrichmentBacklogReadTest extends LuceneExecutorTestBase {
  @TempDir Path tempDir;

  private WorkerIngestService service(RunningRuntime runtime) {
    return new WorkerIngestService(null, null, null, IndexingPacing.unthrottled(),
        null, null, runtime, runtime, null, 0L);
  }

  @Test
  void realReaderDistinguishesEmptyAndPendingAndKeepsTotalSeparateFromBoundedSelection() {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(0))
        .atPath(tempDir.resolve("index")).withExecutorRegistrations(testLuceneExecutors()).open()) {
      var service = service(runtime);
      assertEquals(0, service.countPendingEmbeddings(CallContext.none()));
      assertTrue(service.queryPendingVdu(QueryPendingVduRequest.getDefaultInstance(),
          CallContext.none()).getDocIdsList().isEmpty());
      for (int i = 0; i < 3; i++) {
        Map<String, Object> fields = new HashMap<>(Map.of(
            SchemaFields.DOC_ID, "pending-" + i, SchemaFields.DOC_UID, "identity-" + i,
            SchemaFields.CONTENT, "pending content", SchemaFields.VDU_STATUS, SchemaFields.VDU_STATUS_PENDING));
        if (i < 2) fields.put(SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING);
        runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
      }
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(2, service.countPendingEmbeddings(CallContext.none()));
      var bounded = service.queryPendingVdu(QueryPendingVduRequest.newBuilder().setLimit(1).build(),
          CallContext.none());
      assertEquals(3, bounded.getTotalCount());
      assertEquals(1, bounded.getDocIdsCount());
      assertEquals(3, service.queryPendingVdu(QueryPendingVduRequest.getDefaultInstance(),
          CallContext.none()).getDocIdsCount());
    }
  }

  @Test
  void missingRuntimeFailsBothReads() {
    var service = service(null);
    assertEquals(WorkerServiceException.Status.UNAVAILABLE,
        assertThrows(WorkerServiceException.class,
            () -> service.countPendingEmbeddings(CallContext.none())).status());
    assertEquals(WorkerServiceException.Status.UNAVAILABLE,
        assertThrows(WorkerServiceException.class, () -> service.queryPendingVdu(
            QueryPendingVduRequest.getDefaultInstance(), CallContext.none())).status());
  }

  @Test
  void selectionReaderFailurePreservesCause() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    var failure = new IOException("selection failed");
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt())).thenThrow(failure);

    var thrown = assertThrows(WorkerServiceException.class, () -> service(runtime).queryPendingVdu(
        QueryPendingVduRequest.getDefaultInstance(), CallContext.none()));
    assertEquals(WorkerServiceException.Status.INTERNAL, thrown.status());
    assertSame(failure, thrown.getCause());
  }

  @Test
  void failedTotalDoesNotPublishAnApparentlyEmptyOrPartialSelection() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var fields = mock(DocumentFieldOps.class);
    var counts = mock(IndexCountOps.class);
    when(runtime.documentFieldOps()).thenReturn(fields);
    when(runtime.indexCountOps()).thenReturn(counts);
    when(fields.queryDocIdsByFieldOrThrow(anyString(), anyString(), anyInt())).thenReturn(List.of("pending"));
    var failure = new IOException("count failed");
    when(counts.countByFieldOrThrow(anyString(), anyString())).thenThrow(failure);
    var service = service(runtime);

    assertSame(failure, assertThrows(WorkerServiceException.class, () -> service.queryPendingVdu(
        QueryPendingVduRequest.getDefaultInstance(), CallContext.none())).getCause());
    assertSame(failure, assertThrows(WorkerServiceException.class,
        () -> service.countPendingEmbeddings(CallContext.none())).getCause());
  }

  private static CallContext context(AtomicBoolean cancelled) {
    var base = CallContext.none();
    return new CallContext(null, null, cancelled::get, base.engineContext(), base.provenance(),
        base.childLifetime());
  }

  @Test
  void cancelledBeforeEntryDoesNotTouchTheReader() {
    var runtime = mock(RunningRuntime.class);
    var service = service(runtime);
    clearInvocations(runtime);
    var context = context(new AtomicBoolean(true));

    assertEquals(WorkerServiceException.Status.CANCELLED,
        assertThrows(WorkerServiceException.class, () -> service.countPendingEmbeddings(context)).status());
    assertEquals(WorkerServiceException.Status.CANCELLED,
        assertThrows(WorkerServiceException.class, () -> service.queryPendingVdu(
            QueryPendingVduRequest.getDefaultInstance(), context)).status());
    verifyNoInteractions(runtime);
  }

  @Test
  void cancellationDuringReadCannotPublishZeroAsSuccess() throws Exception {
    var runtime = mock(RunningRuntime.class);
    var counts = mock(IndexCountOps.class);
    when(runtime.indexCountOps()).thenReturn(counts);
    var cancelled = new AtomicBoolean();
    when(counts.countByFieldOrThrow(anyString(), anyString())).thenAnswer(invocation -> {
      cancelled.set(true);
      return 0;
    });

    assertEquals(WorkerServiceException.Status.CANCELLED,
        assertThrows(WorkerServiceException.class,
            () -> service(runtime).countPendingEmbeddings(context(cancelled))).status());
  }
}
