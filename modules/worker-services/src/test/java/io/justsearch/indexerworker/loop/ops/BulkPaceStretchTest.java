/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec D test G5 (S4/S5, duration; §3.3): the longest stretch of GPU work a bulk producer runs
 * between two {@link IndexingPacing#pace()} calls stays within {@code bulkPaceTargetMs} (1 000 ms,
 * ADR-0050 decision 2 "about 1 s"), because that stretch is how long a contended foreground query
 * waits for its duty share.
 *
 * <p>The encoders are fakes that advance a fake clock by measurement M5's per-document costs
 * (embedding 7.3 ms, SPLADE 13.3 ms, NER 5.1 ms), and {@code IndexingPacing} reads that clock, so
 * every {@code pace()} call is recorded at the fake time it happened. Foreground is idle, so pacing
 * never sleeps and the stretches are pure encoder time.
 *
 * <p>Written against API that exists before the change: the bound is the spec's literal, not
 * {@code BulkPacePolicy}.
 */
final class BulkPaceStretchTest {

  private static final long EMBED_NANOS_PER_DOC = 7_300_000L;
  private static final long SPLADE_NANOS_PER_DOC = 13_300_000L;
  private static final long NER_NANOS_PER_DOC = 5_100_000L;
  private static final long BULK_PACE_TARGET_MS = 1_000L;
  private static final Logger LOG = LoggerFactory.getLogger(BulkPaceStretchTest.class);

  private final AtomicLong clockNanos = new AtomicLong();
  private final List<Long> paceAtNanos = new ArrayList<>();

  /** The product's default combined batch: 100 parents with every stage pending, 50 chunk docs. */
  @Test
  void combinedBackfillPacesAtLeastEverySecondOfGpuWork() throws Exception {
    List<String> parents = ids("p", 100);
    List<String> chunks = ids("c", 50);
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(
            inv ->
                switch ((String) inv.getArgument(0)) {
                  case SchemaFields.EMBEDDING_STATUS, SchemaFields.NER_STATUS -> parents;
                  case SchemaFields.CHUNK_EMBEDDING_STATUS -> chunks;
                  default -> List.of();
                });
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt()))
        .thenAnswer(
            inv -> SchemaFields.SPLADE_STATUS.equals(inv.getArgument(0)) ? parents : List.of());
    when(fields.getDocumentContentBatch(anyList())).thenAnswer(inv -> contents(inv.getArgument(0)));
    when(fields.getDocumentFieldsBatch(anyList(), anySet()))
        .thenAnswer(
            inv -> {
              List<String> requested = inv.getArgument(0);
              Map<String, Map<String, String>> out = new HashMap<>();
              for (String id : requested) {
                out.put(
                    id,
                    id.startsWith("c")
                        ? Map.of(
                            SchemaFields.IS_CHUNK, "true",
                            SchemaFields.CHUNK_EMBEDDING_STATUS,
                                SchemaFields.EMBEDDING_STATUS_PENDING)
                        : Map.of(
                            SchemaFields.EMBEDDING_STATUS, SchemaFields.EMBEDDING_STATUS_PENDING,
                            SchemaFields.SPLADE_STATUS, SchemaFields.SPLADE_STATUS_PENDING,
                            SchemaFields.NER_STATUS, SchemaFields.NER_STATUS_PENDING));
              }
              return out;
            });
    IndexingCoordinator coordinator = writingCoordinator(fields);

    EmbeddingProvider embedder = mock(EmbeddingProvider.class);
    when(embedder.isAvailable()).thenReturn(true);
    when(embedder.embedDocumentBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              clockNanos.addAndGet(EMBED_NANOS_PER_DOC * texts.size());
              List<float[]> out = new ArrayList<>();
              texts.forEach(t -> out.add(new float[] {1f, 2f}));
              return out;
            });
    SpladeEncoder splade = spladeEncoder();
    NerService ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              clockNanos.addAndGet(NER_NANOS_PER_DOC * texts.size());
              List<NerResult> out = new ArrayList<>();
              texts.forEach(t -> out.add(NerResult.EMPTY));
              return out;
            });

    var outcome =
        CombinedEnrichmentBackfillOps.processCombinedBackfill(
            new CombinedEnrichmentBackfillOps.BackfillContext(
                fields,
                coordinator,
                mock(CommitOps.class),
                recordingPacing(),
                () -> embedder,
                () -> splade,
                () -> ner,
                () -> true,
                () -> true,
                100,
                LOG,
                /* chunkVectorsEnabled= */ true,
                /* chunkSpladeEnabled= */ false,
                /* lateChunkingEnabled= */ false,
                50,
                new ArrayDeque<>(),
                new ArrayDeque<>(),
                new int[] {0},
                () -> false,
                () -> false,
                () -> false,
                new WindowedEmbedProgress()));

    assertEquals(150, outcome.embedProcessed(), "every parent and chunk was embedded");
    assertEquals(100, outcome.spladeProcessed(), "every parent was SPLADE-encoded");
    assertEquals(100, outcome.nerProcessed(), "every parent went through NER");
    assertTrue(
        longestStretchMs() <= BULK_PACE_TARGET_MS,
        "longest GPU stretch between two pace() calls was "
            + longestStretchMs()
            + " ms (pace points at "
            + paceAtMs()
            + " ms, batch ended at "
            + clockNanos.get() / 1_000_000L
            + " ms); bulkPaceTargetMs is "
            + BULK_PACE_TARGET_MS);
  }

  /** The idle-branch SPLADE backfill: its default batch is 200 documents (M5: 2.5 s in one call). */
  @Test
  void spladeBackfillPacesAtLeastEverySecondOfGpuWork() throws Exception {
    List<String> docs = ids("s", 200);
    DocumentFieldOps fields = mock(DocumentFieldOps.class);
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(docs);
    when(fields.getDocumentContentBatch(anyList())).thenAnswer(inv -> contents(inv.getArgument(0)));
    IndexingCoordinator coordinator = writingCoordinator(fields);
    SpladeEncoder splade = spladeEncoder();

    var outcome =
        SpladeBackfillOps.processSpladeBackfill(
            new SpladeBackfillOps.BackfillContext(
                fields,
                coordinator,
                mock(CommitOps.class),
                recordingPacing(),
                () -> splade,
                () -> true,
                200,
                /* commitAfterBatch= */ false,
                /* chunkSpladeEnabled= */ false,
                LOG));

    assertTrue(outcome.success());
    assertEquals(200, outcome.docsProcessed(), "every document was encoded and written");
    assertTrue(
        longestStretchMs() <= BULK_PACE_TARGET_MS,
        "longest GPU stretch between two pace() calls was "
            + longestStretchMs()
            + " ms (pace points at "
            + paceAtMs()
            + " ms, batch ended at "
            + clockNanos.get() / 1_000_000L
            + " ms); bulkPaceTargetMs is "
            + BULK_PACE_TARGET_MS);
  }

  private IndexingPacing recordingPacing() {
    return new IndexingPacing(
        new ForegroundLoad(),
        IndexingPacing.DEFAULT_DUTY_PCT,
        IndexingPacing.DEFAULT_COOLDOWN_MS,
        () -> clockNanos.get() / 1_000_000L,
        () -> {
          long now = clockNanos.get();
          paceAtNanos.add(now);
          return now;
        },
        millis -> clockNanos.addAndGet(millis * 1_000_000L));
  }

  private SpladeEncoder spladeEncoder() throws Exception {
    SpladeEncoder splade = mock(SpladeEncoder.class);
    when(splade.encodeBatch(anyList()))
        .thenAnswer(
            inv -> {
              List<String> texts = inv.getArgument(0);
              clockNanos.addAndGet(SPLADE_NANOS_PER_DOC * texts.size());
              List<Map<String, Float>> out = new ArrayList<>();
              texts.forEach(t -> out.add(Map.of("token", 1f)));
              return out;
            });
    return splade;
  }

  private static IndexingCoordinator writingCoordinator(DocumentFieldOps fields) {
    IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
    BackfillSnapshotTestSupport.install(fields,coordinator);
    when(coordinator.updateDocumentsBatch(anyList()))
        .thenAnswer(
            inv ->
                new LuceneRuntimeTypes.BatchUpdateResult(
                    ((List<?>) inv.getArgument(0)).size(), 0));
    return coordinator;
  }

  /** The longest stretch from the batch start, through each pace() call, to the batch end. */
  private long longestStretchMs() {
    long longest = 0L;
    long previous = 0L;
    List<Long> points = new ArrayList<>(paceAtNanos);
    points.add(clockNanos.get());
    for (long point : points) {
      longest = Math.max(longest, point - previous);
      previous = point;
    }
    return longest / 1_000_000L;
  }

  private List<Long> paceAtMs() {
    return paceAtNanos.stream().map(nanos -> nanos / 1_000_000L).toList();
  }

  private static List<String> ids(String prefix, int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      ids.add(prefix + i);
    }
    return ids;
  }

  private static Map<String, String> contents(List<String> ids) {
    Map<String, String> out = new HashMap<>();
    for (String id : ids) {
      out.put(id, "content of " + id);
    }
    return out;
  }
}
