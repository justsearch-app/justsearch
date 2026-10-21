/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.BulkPacePolicy;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Spec D §1 ("a test pins the use of each parameter, not its value"): the combined backfill's pace
 * slices are the sizes {@link BulkPacePolicy} names for each phase. Uses a policy whose sizes differ
 * from the defaults and from each other, and counts how many documents of each phase run between
 * two {@code pace()} calls.
 */
final class BulkPacePolicyUseTest {

  private int embedSincePace;
  private int spladeSincePace;
  private int nerSincePace;
  private int maxEmbedBetweenPaces;
  private int maxSpladeBetweenPaces;
  private int maxNerBetweenPaces;
  private int phasesMixedInOneInterval;

  @Test
  void eachPhaseIsSlicedAtTheSizeThePolicyNames() throws Exception {
    List<String> parents = new ArrayList<>();
    for (int i = 0; i < 40; i++) {
      parents.add("p" + i);
    }
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(
            inv ->
                SchemaFields.EMBEDDING_STATUS.equals(inv.getArgument(0))
                        || SchemaFields.NER_STATUS.equals(inv.getArgument(0))
                    ? parents
                    : List.of());
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt()))
        .thenReturn(parents);
    when(fields.getDocumentContentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, String> out = new HashMap<>();
              ids.forEach(id -> out.put(id, "content of " + id));
              return out;
            });
    when(fields.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> ids = inv.getArgument(0);
              Map<String, Map<String, String>> out = new HashMap<>();
              ids.forEach(
                  id ->
                      out.put(
                          id,
                          Map.of(
                              SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
                              SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                              SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING)));
              return out;
            });
    IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
    BackfillSnapshotTestSupport.install(fields,coordinator);
    when(coordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv ->
                new LuceneRuntimeTypes.BatchUpdateResult(
                    ((List<?>) inv.getArgument(0)).size(), 0));

    EmbeddingProvider embedder = mock(EmbeddingProvider.class);
    when(embedder.isAvailable()).thenReturn(true);
    when(embedder.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              embedSincePace += texts.size();
              List<float[]> out = new ArrayList<>();
              texts.forEach(t -> out.add(new float[] {1f}));
              return out;
            });
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.encodeBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              spladeSincePace += texts.size();
              List<Map<String, Float>> out = new ArrayList<>();
              texts.forEach(t -> out.add(Map.of("token", 1f)));
              return out;
            });
    NerService ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              nerSincePace += texts.size();
              return texts.stream().map(t -> NerResult.EMPTY).toList();
            });

    IndexingPacing pacing =
        new IndexingPacing(
            new ForegroundLoad(),
            IndexingPacing.DEFAULT_DUTY_PCT,
            IndexingPacing.DEFAULT_COOLDOWN_MS,
            () -> 0L,
            () -> {
              closeInterval();
              return 0L;
            },
            millis -> {});

    var outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            new CombinedEnrichmentBackfillOps.BackfillContext(
                fields,
                coordinator,
                mock(CommitOps.class),
                pacing,
                () -> embedder,
                () -> splade,
                () -> ner,
                () -> true,
                () -> true,
                100,
                LoggerFactory.getLogger(BulkPacePolicyUseTest.class),
                false,
                false,
                false,
                0,
                new ArrayDeque<>(),
                new ArrayDeque<>(),
                new int[] {0},
                () -> false,
                () -> false,
                () -> false,
                new WindowedEmbedProgress()),
            new BulkPacePolicy(1_000L, 5, 24, 7));
    closeInterval();

    assertEquals(40, outcome.embedProcessed());
    assertEquals(40, outcome.spladeProcessed());
    assertEquals(40, outcome.nerProcessed());
    assertEquals(24, maxEmbedBetweenPaces, "embeddingPhaseDocs bounds an embedding slice");
    assertEquals(5, maxSpladeBetweenPaces, "spladePhaseDocs bounds a SPLADE slice");
    assertEquals(7, maxNerBetweenPaces, "nerPhaseDocs bounds a NER slice");
    assertEquals(0, phasesMixedInOneInterval, "a phase boundary is a pace point");
  }

  private void closeInterval() {
    maxEmbedBetweenPaces = Math.max(maxEmbedBetweenPaces, embedSincePace);
    maxSpladeBetweenPaces = Math.max(maxSpladeBetweenPaces, spladeSincePace);
    maxNerBetweenPaces = Math.max(maxNerBetweenPaces, nerSincePace);
    int phases = (embedSincePace > 0 ? 1 : 0) + (spladeSincePace > 0 ? 1 : 0) + (nerSincePace > 0 ? 1 : 0);
    if (phases > 1) {
      phasesMixedInOneInterval++;
    }
    embedSincePace = 0;
    spladeSincePace = 0;
    nerSincePace = 0;
  }
}
