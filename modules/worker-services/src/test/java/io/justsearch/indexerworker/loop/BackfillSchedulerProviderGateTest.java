/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackfillSchedulerProviderGateTest {
  private final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  private final IndexingCoordinator coordinator = mock(IndexingCoordinator.class);
  private final IndexCountOps counts = mock(IndexCountOps.class);
  private final EmbeddingProvider embedding = mock(EmbeddingProvider.class);
  private final EmbeddingProviderLifecycle lifecycle = mock(EmbeddingProviderLifecycle.class);
  private final ResolvedConfig config = mock(ResolvedConfig.class, RETURNS_DEEP_STUBS);
  private final NerService ner = mock(NerService.class);
  private final Map<String, Object> state = new HashMap<>();
  private final AtomicBoolean available = new AtomicBoolean();
  private final AtomicBoolean allowed = new AtomicBoolean(true);

  @BeforeEach
  void wire() throws Exception {
    when(lifecycle.embeddingProvider()).thenReturn(embedding);
    when(lifecycle.allowEmbeddingWrites()).thenAnswer(call -> allowed.get());
    when(embedding.isAvailable()).thenAnswer(call -> available.get());
    when(config.ai().backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    when(config.rag().chunkVectorsEnabled()).thenReturn(true);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenAnswer(call ->
        call.getArgument(1).equals(state.get(call.getArgument(0))) ? List.of("doc") : List.of());
    when(fields.getEnrichmentInputs(anyList())).thenAnswer(call -> {
      var metadata = new HashMap<String, String>();
      state.forEach((key, value) -> metadata.put(key, value.toString()));
      return Map.of("doc", new DocumentFieldOps.EnrichmentInput("content", metadata, null, Map.of()));
    });
    when(coordinator.updateDocumentIf(anyString(), any(DocumentFieldOps.EnrichmentInput.class), anyMap())).thenAnswer(call -> {
      state.putAll(call.getArgument(2));
      return true;
    });
    when(coordinator.updateDocumentsBatchIf(anyList(), anyMap())).thenAnswer(call -> {
      List<Map.Entry<String, Map<String, Object>>> updates = call.getArgument(0);
      updates.forEach(entry -> state.putAll(entry.getValue()));
      return new io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.BatchUpdateResult(updates.size(), 0);
    });
  }

  private BackfillScheduler scheduler(boolean nerEnabled) {
    return new BackfillScheduler(fields, coordinator, counts, mock(CommitOps.class),
        mock(WorkerSignalBus.class), IndexingPacing.unthrottled(), lifecycle,
        new AtomicBoolean(true), () -> config, () -> null, () -> null,
        () -> nerEnabled ? ner : null, () -> null);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unavailable", "starting", "writes-blocked"})
  void chunkBackfillWaitsWithoutSpendingRetriesThenDrains(String condition) {
    available.set(condition.equals("writes-blocked"));
    allowed.set(!condition.equals("writes-blocked"));
    when(embedding.availability()).thenAnswer(call -> available.get()
        ? EmbeddingProvider.Availability.READY
        : condition.equals("starting") ? EmbeddingProvider.Availability.STARTING
            : EmbeddingProvider.Availability.ABSENT);
    when(embedding.isAvailable()).thenCallRealMethod();
    state.put(SchemaFields.IS_CHUNK, "true");
    state.put(SchemaFields.CHUNK_EMBEDDING_STATUS, "PENDING");
    BackfillScheduler scheduler = scheduler(false);
    scheduler.runIdleCycle();
    assertFalse(state.containsKey(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
    verify(embedding, never()).embedDocumentBatch(anyList());
    verify(counts, never()).countByField(SchemaFields.EMBEDDING_STATUS, "PENDING");
    verify(fields, never()).queryDocIdsByField(eq(SchemaFields.CHUNK_EMBEDDING_STATUS), anyString(), anyInt());
    available.set(true);
    allowed.set(true);
    when(embedding.embedDocumentBatch(anyList())).thenReturn(List.of(new float[] {1, 0}));
    scheduler.runIdleCycle();
    assertEquals("COMPLETED", state.get(SchemaFields.CHUNK_EMBEDDING_STATUS));
    assertEquals("0", state.get(SchemaFields.CHUNK_EMBEDDING_RETRY_COUNT));
  }

  @Test
  void nerRunsWhenEmbeddingWritesAreBlocked() {
    available.set(true);
    allowed.set(false);
    when(config.rag().chunkVectorsEnabled()).thenReturn(false);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
    state.put(SchemaFields.EMBEDDING_STATUS, "PENDING");
    state.put(SchemaFields.NER_STATUS, "PENDING");
    when(counts.countByField(SchemaFields.EMBEDDING_STATUS, "PENDING")).thenReturn(1);
    scheduler(true).runIdleCycle();
    verify(ner).extractEntitiesBatch(List.of("content"));
    assertEquals("COMPLETED_EMPTY", state.get(SchemaFields.NER_STATUS));
    assertEquals("PENDING", state.get(SchemaFields.EMBEDDING_STATUS));
  }
}
