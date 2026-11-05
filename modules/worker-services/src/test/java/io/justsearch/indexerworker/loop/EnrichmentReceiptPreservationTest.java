/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.IndexSchema;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneExecutorTestBase;
import io.justsearch.adapters.lucene.runtime.RunningRuntime;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.configuration.FieldCatalogDef.FieldDef;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.OperationalMetrics;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Publication receipts and writer corrections feed only the new outcome accounting: which
 * documents a cycle excludes after publishing, and the counters and gauges that existed before
 * receipts, keep their previous inputs. Runs a real index under the scheduler on a fixed clock, so
 * no cycle budget can end a loop early.
 */
class EnrichmentReceiptPreservationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;
  private final List<String> embedCalls = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  @AfterEach
  void resetMetrics() {
    OperationalMetrics.getInstance().resetAll();
  }

  @Test
  void documentRejectedStaleInTheProbeStaysExcludedForTheRestOfTheCycle() throws Exception {
    try (var runtime = open()) {
      for (String id : List.of("a", "b", "conc")) {
        seed(runtime, id, "content " + id, Map.of(SchemaFields.SPLADE_STATUS, "COMPLETED_EMPTY"));
      }
      commitSeed(runtime);
      IndexingCoordinator coordinator = runtime.indexingCoordinator();
      var once = new AtomicBoolean();
      // Another writer changes the dense stage's own field of "conc" while its unit is encoded.
      // E4b: lanes publish their own stages, so only a change to a field the write carries
      // rejects it (a concurrent NER-field change no longer rejects the dense write).
      var embedding = embedding(texts -> {
        if (once.compareAndSet(false, true)) {
          coordinator.updateDocument("conc", Map.of(SchemaFields.EMBEDDING_RETRY_COUNT, "1"));
        }
      });

      scheduler(runtime, embedding, emptyNer()).runIdleCycle();
      runtime.commitOps().maybeRefreshBlocking();

      var fields = runtime.documentFieldOps();
      assertEquals(1, embedCalls.stream().filter(call -> call.contains("content conc")).count(),
          "the rejected document is not re-encoded in the same cycle; calls=" + embedCalls);
      assertEquals("PENDING", fields.getDocumentField("conc", SchemaFields.EMBEDDING_STATUS));
      assertEquals("1", fields.getDocumentField("conc", SchemaFields.EMBEDDING_RETRY_COUNT));
      assertEquals("COMPLETED_EMPTY", fields.getDocumentField("conc", SchemaFields.NER_STATUS),
          "the independent NER lane still serves the document");
      for (String id : List.of("a", "b")) {
        assertEquals("COMPLETED", fields.getDocumentField(id, SchemaFields.EMBEDDING_STATUS), id);
        assertEquals("COMPLETED_EMPTY", fields.getDocumentField(id, SchemaFields.NER_STATUS), id);
      }
    }
  }

  @Test
  void individualPassWhoseOnlyOutputTheWriterStoredFailedKeepsTheModeGauge() throws Exception {
    try (var runtime = open()) {
      seed(runtime, "only", "nan content", Map.of());
      commitSeed(runtime);

      // Embedding is the only available stage, so the cycle takes the individual path.
      scheduler(runtime, embedding(texts -> {}), null).runIdleCycle();
      runtime.commitOps().maybeRefreshBlocking();

      var fields = runtime.documentFieldOps();
      assertEquals("FAILED", fields.getDocumentField("only", SchemaFields.EMBEDDING_STATUS));
      assertEquals("0", fields.getDocumentField("only", SchemaFields.EMBEDDING_RETRY_COUNT));
      var metrics = OperationalMetrics.getInstance();
      // E4b retired "individual"/"combined": an admitted lane turn reports "active".
      assertEquals("active", metrics.getBackfillMode(),
          "a pass that moved a document to FAILED did work");
      assertEquals(1L, metrics.getEnrichmentCompleted().get("embed"),
          "the completion counter keeps its input; the correction is reported as an outcome");
    }
  }

  private EmbeddingProvider embedding(Consumer<List<String>> during) throws Exception {
    var embedding = mock(EmbeddingProvider.class);
    when(embedding.isAvailable()).thenReturn(true);
    when(embedding.documentWindowCount(anyString())).thenReturn(1);
    when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
      List<String> texts = call.getArgument(0);
      embedCalls.add("embedBatch" + texts);
      during.accept(texts);
      return texts.stream().map(EnrichmentReceiptPreservationTest::vector).toList();
    });
    when(embedding.embedDocument(anyString(), any())).thenAnswer(call -> {
      embedCalls.add("embed[" + call.getArgument(0) + "]");
      return vector(call.getArgument(0));
    });
    return embedding;
  }

  private static NerService emptyNer() {
    var ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenAnswer(call ->
        Collections.nCopies(((List<?>) call.getArgument(0)).size(), NerResult.EMPTY));
    return ner;
  }

  /** A text starting with "nan" encodes to a vector the field mapper cannot store. */
  private static float[] vector(String text) {
    if (text.startsWith("nan")) return new float[] {Float.NaN, 1, 0, 0};
    int h = Math.abs(text.hashCode());
    return new float[] {1 + h % 7, 1 + h % 5, 1 + h % 3, 1};
  }

  private static BackfillScheduler scheduler(
      RunningRuntime runtime, EmbeddingProvider embedding, NerService ner) {
    var signals = mock(WorkerSignalBus.class);
    var lifecycle = new EmbeddingProviderLifecycle(
        signals, mock(JobQueue.class), runtime.indexCountOps(), runtime.commitOps());
    lifecycle.setEmbeddingProvider(embedding);
    ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
    var pacing = mock(ResolvedConfig.Ai.BackfillPacing.class);
    when(pacing.embeddingBackfillBatchSize()).thenReturn(10);
    when(config.ai().backfillPacing()).thenReturn(pacing);
    return new BackfillScheduler(runtime.documentFieldOps(), runtime.indexingCoordinator(),
        runtime.indexCountOps(), runtime.commitOps(), signals, IndexingPacing.unthrottled(),
        lifecycle, new AtomicBoolean(true), () -> config, () -> null, () -> null, () -> ner,
        () -> null, () -> 0L);
  }

  private RunningRuntime open() {
    var fields = new ArrayList<>(FieldCatalogDef.forChunkTesting(4).fields());
    for (String name : List.of("source_sha256", "embedding_retry_count", "splade_status",
        "splade_retry_count", "ner_status", "ner_retry_count")) {
      fields.add(new FieldDef(name, name.endsWith("_count") ? "long" : "keyword",
          true, true, List.of("filter"), null, null, false));
    }
    fields.add(new FieldDef("splade", "splade", false, false, List.of(), null, null, false,
        "reset-status:splade_status"));
    var runtime = IndexSchema.fromCatalog(new FieldCatalogDef("receipt-preservation", fields))
        .atPath(directory).withExecutorRegistrations(testLuceneExecutors()).open();
    runtime.commitOps().stopCommitTimer();
    runtime.commitOps().suspendNrtRefresh();
    return runtime;
  }

  private static void seed(RunningRuntime runtime, String id, String content,
      Map<String, Object> overrides) {
    Map<String, Object> fields = new HashMap<>();
    fields.put(SchemaFields.DOC_ID, id);
    fields.put(SchemaFields.DOC_UID, id + "-uid");
    fields.put(SchemaFields.CONTENT, content);
    fields.put(SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.SOURCE_SHA256, ChunkParentRevision.sha256Hex(content));
    fields.put(SchemaFields.IS_CHUNK, "false");
    fields.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
    fields.put(SchemaFields.SPLADE_STATUS, "PENDING");
    fields.put(SchemaFields.NER_STATUS, "PENDING");
    for (String stage : List.of("embedding", "splade", "ner")) fields.put(stage + "_retry_count", "0");
    fields.putAll(overrides);
    runtime.indexingCoordinator().indexSingle(new IndexDocument(fields));
  }

  private static void commitSeed(RunningRuntime runtime) {
    runtime.commitOps().commitAndTrack();
    runtime.commitOps().maybeRefreshBlocking();
  }
}
