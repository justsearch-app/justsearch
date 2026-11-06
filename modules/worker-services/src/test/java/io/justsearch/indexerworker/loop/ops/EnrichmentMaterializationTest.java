/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexDocument;
import io.justsearch.indexing.chunking.ChunkParentRevision;
import io.justsearch.ort.SessionTemporarilyUnavailableException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.apache.lucene.document.FeatureField;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

/** Lazy native results cross the real lane and Lucene publication boundaries. */
final class EnrichmentMaterializationTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  enum Lane { DENSE_PARENT, DENSE_CHUNK, SPLADE_PARENT, SPLADE_CHUNK }
  enum Fault { ORDINARY, STALE, RETIRED, CANCELLED, INTERRUPTED, HELD }

  static Stream<Arguments> cases() {
    return Arrays.stream(Lane.values()).flatMap(lane ->
        Stream.of(0, 1, 2).flatMap(position -> Arrays.stream(Fault.values())
            .map(fault -> Arguments.of(lane, position, fault))));
  }

  @ParameterizedTest(name = "{0} read failure at {1}: {2}")
  @MethodSource("cases")
  void batchReadFailurePreservesOnlyPublishableResults(Lane lane, int failureAt, Fault fault)
      throws Exception {
    boolean dense = lane == Lane.DENSE_PARENT || lane == Lane.DENSE_CHUNK;
    boolean chunk = lane == Lane.DENSE_CHUNK || lane == Lane.SPLADE_CHUNK;
    String status = dense
        ? (chunk ? SchemaFields.CHUNK_EMBEDDING_STATUS : SchemaFields.EMBEDDING_STATUS)
        : SchemaFields.SPLADE_STATUS;
    String retry = dense
        ? (chunk ? SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT : SchemaFields.EMBEDDING_RETRY_COUNT)
        : SchemaFields.SPLADE_RETRY_COUNT;
    try (var runtime = open()) {
      runtime.commitOps().stopCommitTimer();
      runtime.commitOps().suspendNrtRefresh();
      List<String> ids = List.of("a", "b", "c", "later");
      for (String id : ids) seed(runtime, id, status, retry, chunk);
      runtime.commitOps().commitAndTrack();
      runtime.commitOps().maybeRefreshBlocking();

      var embedding = mock(EmbeddingProvider.class);
      when(embedding.isAvailable()).thenReturn(true);
      var sparse = mock(SpladeEncoder.class);
      var ready = new AtomicBoolean(true);
      var reads = new AtomicInteger();
      var selected = new AtomicReference<List<String>>();
      Runnable failRead = () -> {
        switch (fault) {
          case STALE -> {
            String staleId = selected.get().getFirst();
            String contentId = chunk ? "parent-" + staleId : staleId;
            try {
              runtime.indexingCoordinator().updateDocument(contentId, Map.of(
                  SchemaFields.CONTENT, "replacement",
                  SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex("replacement")));
            } catch (Exception failure) {
              throw new AssertionError("source replacement must succeed", failure);
            }
          }
          case RETIRED -> ready.set(false);
          case CANCELLED -> throw new CancellationException("cancelled lazy read");
          case INTERRUPTED -> {
            Thread.currentThread().interrupt();
            throw new CancellationException("interrupted lazy read");
          }
          case HELD -> throw new SessionTemporarilyUnavailableException("held lazy read");
          case ORDINARY -> { }
        }
        throw new IllegalArgumentException("ordinary materialization failure");
      };
      when(embedding.embedDocumentBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        assertEquals(3, texts.size(), "one native batch must include both sides of the fault");
        selected.set(texts.stream().map(text -> text.substring(5)).toList());
        return new AbstractList<float[]>() {
          @Override public int size() { return 3; }
          @Override public float[] get(int index) {
            reads.incrementAndGet();
            if (index >= failureAt) failRead.run();
            return new float[] {1, 0};
          }
        };
      });
      when(sparse.encodeBatch(anyList())).thenAnswer(call -> {
        List<String> texts = call.getArgument(0);
        assertEquals(3, texts.size(), "one native batch must include both sides of the fault");
        selected.set(texts.stream().map(text -> text.substring(5)).toList());
        return new AbstractList<Map<String, Float>>() {
          @Override public int size() { return 3; }
          @Override public Map<String, Float> get(int index) {
            reads.incrementAndGet();
            if (index >= failureAt) failRead.run();
            return Map.of("token", 1f);
          }
        };
      });
      when(sparse.encode(anyString(), any())).thenThrow(new IllegalArgumentException("bad fallback"));
      StageOutcome outcome;
      boolean interrupted;
      try {
        if (dense) {
          var context = new CombinedEnrichmentBackfillOps.BackfillContext(
              runtime.documentFieldOps(), runtime.indexingCoordinator(), IndexingPacing.unthrottled(),
              () -> ready.get() ? embedding : null, () -> true, () -> true, 3,
              LoggerFactory.getLogger(getClass()), chunk, false, 3, () -> false, () -> false,
              new WindowedEmbedProgress(), new HashSet<>(), gpu -> true, stage -> true);
          outcome = CombinedEnrichmentBackfillOps.processDenseUnit(context, new EnrichmentSelection(),
              chunk ? EnrichmentAccounting.Stage.CHUNK_EMBED : EnrichmentAccounting.Stage.EMBED);
        } else {
          var context = new SpladeBackfillOps.BackfillContext(
              runtime.documentFieldOps(), runtime.indexingCoordinator(), IndexingPacing.unthrottled(),
              () -> ready.get() ? sparse : null, () -> true, 3, chunk,
              LoggerFactory.getLogger(getClass()), () -> true);
          outcome = chunk
              ? SpladeBackfillOps.processChunkBackfill(context, new EnrichmentSelection())
              : SpladeBackfillOps.processParentBackfill(context, new EnrichmentSelection());
        }
        interrupted = Thread.currentThread().isInterrupted();
      } finally {
        // Clear only this test's injected interrupt before inspecting/closing the real writer.
        Thread.interrupted();
      }
      assertNotNull(selected.get(), "the production lane must reach native work");
      assertEquals(failureAt + 1, reads.get(), "stop reading at the fault");
      assertEquals(fault == Fault.INTERRUPTED, interrupted, "restore the caller's interrupt");
      assertDoesNotThrow(() -> runtime.commitOps().commitAndTrack(),
          "the writer must remain usable after the lane returns");
      runtime.commitOps().maybeRefreshBlocking();
      boolean guarded = fault == Fault.RETIRED || fault == Fault.CANCELLED
          || fault == Fault.INTERRUPTED || fault == Fault.HELD;
      if (guarded) assertEquals(0, outcome.docsProcessed(), "guarded results never publish");
      if (fault == Fault.HELD) {
        Object provider = dense ? embedding : sparse;
        assertTrue(outcome.accounting().acquisitions().stream()
            .anyMatch(acquisition -> acquisition.provider() == provider && !acquisition.available()),
            "the scheduler must receive the provider hold");
      }
      for (int i = 0; i < selected.get().size(); i++) {
        String id = selected.get().get(i);
        boolean completed = i < failureAt && !guarded && !(fault == Fault.STALE && i == 0);
        assertEquals(completed ? "COMPLETED" : "PENDING",
            runtime.documentFieldOps().getDocumentField(id, status), "stored status " + id);
        assertEquals(!dense && i >= failureAt && !guarded && !(fault == Fault.STALE && i == 0)
                ? "1" : "0",
            runtime.documentFieldOps().getDocumentField(id, retry), "stored retry " + id);
        if (completed) {
          var query = dense
              ? new KnnFloatVectorQuery(chunk ? SchemaFields.CHUNK_VECTOR : SchemaFields.VECTOR,
                  new float[] {1, 0}, 10)
              : FeatureField.newLinearQuery(SchemaFields.SPLADE, "token", 1f);
          assertTrue(runtime.readPathOps().search(query, 10, Set.of(SchemaFields.DOC_ID),
              LuceneRuntimeTypes.RuntimeSearchSort.RELEVANCE, null).hits().stream()
              .anyMatch(hit -> id.equals(hit.fields().get(SchemaFields.DOC_ID))),
              "COMPLETED must have a searchable artifact: " + id);
        }
      }
      for (String id : ids) {
        if (selected.get().contains(id)) continue;
        assertEquals("PENDING", runtime.documentFieldOps().getDocumentField(id, status));
        assertEquals("0", runtime.documentFieldOps().getDocumentField(id, retry),
            "the unselected tail is never charged");
      }
      verify(embedding, never()).embedDocument(anyString(), any());
      if (guarded) verify(sparse, never()).encode(anyString(), any());
    }
  }

  private RunningRuntime open() throws Exception {
    var defs = new ArrayList<>(FieldCatalogDef.forChunkTesting(2).fields());
    for (String key : List.of(SchemaFields.EMBEDDING_RETRY_COUNT, SchemaFields.SPLADE_RETRY_COUNT)) {
      defs.add(new FieldCatalogDef.FieldDef(key, "long", true, true, List.of(), null, null, false));
    }
    defs.add(new FieldCatalogDef.FieldDef(SchemaFields.SPLADE_STATUS, "keyword", true, true,
        List.of("filter"), null, null, false));
    defs.add(new FieldCatalogDef.FieldDef(SchemaFields.SPLADE, "splade", false, false,
        List.of(), null, null, false, "reset-status:splade_status"));
    return IndexSchema.fromCatalog(new FieldCatalogDef("materialization", defs)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open();
  }

  private static void seed(RunningRuntime runtime, String id, String status, String retry,
      boolean chunk) throws Exception {
    String text = "text " + id;
    Map<String, Object> row = new HashMap<>();
    row.put(SchemaFields.DOC_ID, id);
    row.put(SchemaFields.DOC_UID, id);
    row.put(status, "PENDING");
    row.put(retry, "0");
    if (chunk) {
      String parent = "parent-" + id;
      runtime.indexingCoordinator().indexSingle(new IndexDocument(Map.of(
          SchemaFields.DOC_ID, parent, SchemaFields.DOC_UID, parent, SchemaFields.CONTENT, text,
          SchemaFields.CONTENT_SHA256, ChunkParentRevision.sha256Hex(text))));
      row.put(SchemaFields.IS_CHUNK, "true");
      row.put(SchemaFields.PARENT_DOC_ID, parent);
      row.put(SchemaFields.CHUNK_INDEX, 0);
      row.put(SchemaFields.CHUNK_START_CHAR, 0);
      row.put(SchemaFields.CHUNK_END_CHAR, text.length());
      row.put(SchemaFields.CHUNK_PARENT_CONTENT_SHA256, ChunkParentRevision.sha256Hex(text));
    } else {
      row.put(SchemaFields.CONTENT, text);
    }
    runtime.indexingCoordinator().indexSingle(new IndexDocument(row));
  }
}
