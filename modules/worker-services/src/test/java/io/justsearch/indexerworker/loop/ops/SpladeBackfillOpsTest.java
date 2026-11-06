/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitReason;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.MMapDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

@DisplayName("SpladeBackfillOps")
@ExtendWith(MockitoExtension.class)
class SpladeBackfillOpsTest extends LuceneExecutorTestBase {

  @TempDir Path tempDir;

  @org.junit.jupiter.api.BeforeEach void snapshots() throws Exception {
    BackfillSnapshotTestSupport.install(documentFieldOps, indexingCoordinator, false, java.util.Set.of(SchemaFields.SPLADE_STATUS, SchemaFields.IS_CHUNK));
  }

  @Mock DocumentFieldOps documentFieldOps;
  @Mock IndexingCoordinator indexingCoordinator;
  @Mock SpladeEncoder encoder;

  @Test
  void interleavedSpladeAfterFinalPrimaryCommitIsPersistedByTimer() throws Exception {
    List<FieldDef> fields = new ArrayList<>(FieldCatalogDef.forTesting(4).fields());
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    fields.add(new FieldDef("splade_status", "keyword", false, true, List.of("filter"), null, null, false));
    fields.add(new FieldDef("splade_retry_count", "long", false, true, List.of(), null, null, false));
    CommitMetadataSource source = () -> Map.of();
    var schema = IndexSchema.fromCatalog(new FieldCatalogDef("splade-accounting", fields), source, metadata -> {});
    var config = new ResolvedConfigBuilder()
        .put("index.commit.timer_interval_ms", 500, "jvm_arg", "test", "1")
        .put("index.commit.meta.enabled", 500, "jvm_arg", "test", "false")
        .build();
    try (var runtime = schema.atPath(tempDir).withConfig(config)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, "parent", SchemaFields.DOC_UID, "parent#0",
          SchemaFields.PATH, "parent.txt", SchemaFields.CONTENT, "parent body",
          SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING)));
      // Reproduce the final primary commit immediately before the loop's interleaved pass.
      runtime.commitOps().commitAndTrack(CommitReason.INDEXING_LOOP_BUFFER);
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(0L, runtime.runtimeGaugesSnapshot().writerPendingDocs());
      when(encoder.encodeBatch(List.of("parent body")))
          .thenReturn(new ArrayList<>(List.of(Map.of("token", 1.0f))));

      StageOutcome outcome = LaneBackfillTestSupport.runSparse(
          new SpladeBackfillOps.BackfillContext(runtime.documentFieldOps(),
        runtime.indexingCoordinator(),
        IndexingPacing.unthrottled(),
        () -> encoder,
        () -> true,
        100,
        false,
        LoggerFactory.getLogger(SpladeBackfillOpsTest.class), () -> true));
      assertTrue(outcome.success());
      assertEquals(1, outcome.docsProcessed());
      assertEquals(1L, runtime.runtimeGaugesSnapshot().commitCount(), "interleaved backfill must not commit itself");
      assertEquals(1L, runtime.runtimeGaugesSnapshot().writerPendingDocs(), "actual batch RMW must signal the timer");
      runtime.commitOps().maybeRefreshBlocking();
      assertEquals(SchemaFields.SPLADE_STATUS_COMPLETED,
          runtime.documentFieldOps().getDocumentField("parent", SchemaFields.SPLADE_STATUS));
      try (var dir = new MMapDirectory(tempDir); var reader = DirectoryReader.open(dir)) {
        assertEquals(0, new IndexSearcher(reader).count(new TermQuery(new Term(SchemaFields.SPLADE, "token"))));
      }

      CountDownLatch committed = new CountDownLatch(1);
      runtime.commitOps().setCommitCompletedListener(reason -> {
        if (reason == CommitReason.TIMER) committed.countDown();
      });
      try {
        runtime.commitOps().startCommitTimer();
        assertTrue(committed.await(5, TimeUnit.SECONDS), "timer must persist the otherwise idle interleaved update");
      } finally {
        runtime.commitOps().stopCommitTimer();
      }
      assertEquals(0L, runtime.runtimeGaugesSnapshot().writerPendingDocs());
      try (var dir = new MMapDirectory(tempDir); var reader = DirectoryReader.open(dir)) {
        assertEquals(1, new IndexSearcher(reader).count(new TermQuery(new Term(SchemaFields.SPLADE, "token"))));
      }
    }
  }

  @Test
  @DisplayName("encodes reconstructed chunk text from the parent-aware batch reader")
  void reconstructedChunkTextIsEncodedThroughBatchReader() throws Exception {
    when(documentFieldOps.queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS),
            eq(SchemaFields.SPLADE_STATUS_PENDING),
            anyInt()))
        .thenReturn(List.of("chunk-1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("chunk-1")))
        .thenReturn(Map.of("chunk-1", "reconstructed slice"));
    when(encoder.encodeBatch(List.of("reconstructed slice")))
        .thenReturn(new ArrayList<>(List.of(Map.of("token", 1.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet())).thenReturn(Map.of("chunk-1",
        Map.of(SchemaFields.SPLADE_STATUS, "PENDING", SchemaFields.IS_CHUNK, "true")));
    StageOutcome outcome = LaneBackfillTestSupport.runSparse(context());

    assertTrue(outcome.success());
    assertEquals(1, outcome.docsProcessed());
    verify(documentFieldOps).getDocumentContentBatch(List.of("chunk-1"));
    verify(documentFieldOps, never())
        .getDocumentField("chunk-1", SchemaFields.CHUNK_CONTENT);
    verify(indexingCoordinator)
        .updateDocumentsBatch(
            argThat(
                batch ->
                    batch.size() == 1
                        && "chunk-1".equals(batch.get(0).getKey())
                        && Map.of("token", 1.0f)
                            .equals(batch.get(0).getValue().get(SchemaFields.SPLADE))
                        && SchemaFields.SPLADE_STATUS_COMPLETED.equals(
                            batch.get(0).getValue().get(SchemaFields.SPLADE_STATUS))));
  }

  @Test
  @DisplayName(
      "chunk-SPLADE flag OFF: the lane selects whole documents only — it must not encode sparse"
          + " data the configuration says not to produce (tempdoc 931)")
  void chunkSpladeOff_selectsWholeDocumentsOnly() throws Exception {
    when(documentFieldOps.queryNonChunkDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS),
            eq(SchemaFields.SPLADE_STATUS_PENDING),
            anyInt()))
        .thenReturn(List.of("parent-1"));
    when(documentFieldOps.getDocumentContentBatch(List.of("parent-1")))
        .thenReturn(Map.of("parent-1", "parent body"));
    when(encoder.encodeBatch(List.of("parent body")))
        .thenReturn(new ArrayList<>(List.of(Map.of("token", 1.0f))));
    when(indexingCoordinator.updateDocumentsBatch(anyList()))
        .thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));

    when(documentFieldOps.getDocumentFieldsBatch(anyList(), anySet())).thenReturn(Map.of("parent-1",
        Map.of(SchemaFields.SPLADE_STATUS, "PENDING")));
    StageOutcome outcome = LaneBackfillTestSupport.runSparse(context(false));

    assertEquals(1, outcome.docsProcessed());
    verify(documentFieldOps, never())
        .queryDocIdsByField(
            eq(SchemaFields.SPLADE_STATUS), eq(SchemaFields.SPLADE_STATUS_PENDING), anyInt());
  }

  @Test
  void terminalSpladeWarningRequiresPublication() throws Exception {
    String id = "terminal";
    var log = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(getClass());
    var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    appender.start();
    log.addAppender(appender);
    when(documentFieldOps.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of(id));
    when(documentFieldOps.getEnrichmentInputs(anyList(), anyLong())).thenReturn(Map.of(id,
        new DocumentFieldOps.EnrichmentInput("bad input", Map.of(SchemaFields.SPLADE_STATUS, "PENDING",
            SchemaFields.SPLADE_RETRY_COUNT, Integer.toString(SchemaFields.SPLADE_MAX_RETRIES - 1)), null, Map.of())));
    when(encoder.encodeBatch(anyList())).thenThrow(new IllegalArgumentException("bad input"));
    when(encoder.encode(anyString(), any())).thenThrow(new IllegalArgumentException("bad input"));
    var context = new SpladeBackfillOps.BackfillContext(documentFieldOps,
        indexingCoordinator,
        IndexingPacing.unthrottled(),
        () -> encoder,
        () -> true,
        1,
        false,
        log, () -> true);
    try {
      org.mockito.Mockito.doReturn(new LuceneRuntimeTypes.BatchUpdateResult(0, 1))
          .when(indexingCoordinator).updateDocumentsBatchIf(anyList(), anyMap());
      SpladeBackfillOps.processParentBackfill(context, new EnrichmentSelection());
      assertEquals(0, appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).count());
      org.mockito.Mockito.doAnswer(call -> {
        assertEquals(0, appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).count(),
            "the diagnostic must follow accepted publication");
        return new LuceneRuntimeTypes.BatchUpdateResult(1, 0);
      }).when(indexingCoordinator).updateDocumentsBatchIf(anyList(), anyMap());
      SpladeBackfillOps.processParentBackfill(context, new EnrichmentSelection());
      assertEquals(1, appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).count());
      appender.list.clear();
      org.mockito.Mockito.doReturn(new LuceneRuntimeTypes.EnrichmentPublicationReceipt(List.of(id), List.of(),
          new IllegalStateException("publication accepted before a later failure")))
          .when(indexingCoordinator).publishEnrichmentBatch(anyList(), anyMap());
      SpladeBackfillOps.processParentBackfill(context, new EnrichmentSelection());
      assertEquals(1, appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).count(),
          "an accepted failure is diagnosed once even if result delivery throws");
      appender.list.clear();
      when(documentFieldOps.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of(id, "peer"));
      var input = new DocumentFieldOps.EnrichmentInput("bad input", Map.of(SchemaFields.SPLADE_STATUS, "PENDING",
          SchemaFields.SPLADE_RETRY_COUNT, Integer.toString(SchemaFields.SPLADE_MAX_RETRIES - 1)), null, Map.of());
      when(documentFieldOps.getEnrichmentInputs(anyList(), anyLong())).thenReturn(Map.of(id, input, "peer", input));
      org.mockito.Mockito.doAnswer(call -> {
        assertEquals(0, appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).count());
        List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
        return new LuceneRuntimeTypes.EnrichmentPublicationReceipt(updates.stream().map(Map.Entry::getKey).toList(), List.of(), null);
      }).when(indexingCoordinator).publishEnrichmentBatch(anyList(), anyMap());
      SpladeBackfillOps.processParentBackfill(new SpladeBackfillOps.BackfillContext(documentFieldOps,
          indexingCoordinator, IndexingPacing.unthrottled(), () -> encoder, () -> true, 2, false, log, () -> true), new EnrichmentSelection());
      var warnings = appender.list.stream().filter(event -> event.getFormattedMessage().contains("reached terminal SPLADE FAILED")).toList();
      assertEquals(1, warnings.size(), "one lane operation emits one terminal warning");
      assertTrue(warnings.getFirst().getFormattedMessage().contains("2 document(s)"));
      assertTrue(warnings.getFirst().getFormattedMessage().contains(id));
      assertTrue(warnings.getFirst().getFormattedMessage().contains("peer"));
    } finally {
      log.detachAppender(appender);
      appender.stop();
    }
  }

  private SpladeBackfillOps.BackfillContext context() {
    return context(true);
  }

  private SpladeBackfillOps.BackfillContext context(boolean chunkSpladeEnabled) {
    return new SpladeBackfillOps.BackfillContext(documentFieldOps,
        indexingCoordinator,
        IndexingPacing.unthrottled(),
        () -> encoder,
        () -> true,
        100,
        chunkSpladeEnabled,
        LoggerFactory.getLogger(SpladeBackfillOpsTest.class), () -> true);
  }
}
