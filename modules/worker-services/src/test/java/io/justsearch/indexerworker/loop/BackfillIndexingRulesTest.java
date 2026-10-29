/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.core.scheduling.IndexingSchedule;
import io.justsearch.indexerworker.coordination.InProcessWorkerSignalBus;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.ops.BackfillSnapshotTestSupport;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** S38.8: the real scheduler/bus/hold keep pending enrichment and resume it when all rules clear. */
final class BackfillIndexingRulesTest {
  @Test
  void scheduleAndBatteryHoldPendingNerUntilBothClearWithoutManualResume() throws Exception {
    var hold = new IndexingHold();
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
    hold.setOnBattery(true);
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), true);
    var signals = new InProcessWorkerSignalBus(new GpuSchedulingGauge(hold));
    var fields = mock(DocumentFieldOps.class);
    var writes = mock(IndexingCoordinator.class);
    var counts = mock(IndexCountOps.class);
    var commits = mock(CommitOps.class);
    BackfillSnapshotTestSupport.install(fields, writes, true, Set.of());
    when(fields.queryDocIdsByField(eq(SchemaFields.NER_STATUS), eq(SchemaFields.NER_STATUS_PENDING), anyInt()))
        .thenReturn(List.of("pending"));
    when(fields.getDocumentContent("pending")).thenReturn("content for pending enrichment");
    when(writes.updateDocumentsBatch(anyList())).thenReturn(new LuceneRuntimeTypes.BatchUpdateResult(1, 0));
    var ner = mock(NerService.class);
    when(ner.isAvailable()).thenReturn(true);
    when(ner.extractEntitiesBatch(anyList())).thenReturn(List.of(NerResult.EMPTY));
    var embeddings = mock(EmbeddingProviderLifecycle.class);
    when(embeddings.embeddingProvider()).thenReturn(mock(EmbeddingProvider.class));
    var config = mock(ResolvedConfig.class);
    var ai = mock(ResolvedConfig.Ai.class);
    when(config.ai()).thenReturn(ai);
    when(ai.backfillPacing()).thenReturn(ResolvedConfig.Ai.BackfillPacing.DEFAULTS);
    when(config.rag()).thenReturn(mock(ResolvedConfig.Rag.class));
    var scheduler = new BackfillScheduler(fields, writes, counts, commits, signals, IndexingPacing.unthrottled(),
        embeddings, new AtomicBoolean(true), () -> config, () -> null, () -> null, () -> ner, () -> null);
    assertFalse(scheduler.runIdleCycle());
    verify(ner, never()).extractEntitiesBatch(anyList());
    hold.setPolicy(false, null, null, null, true);
    assertFalse(scheduler.runIdleCycle(), "removing hours still leaves battery holding enrichment");
    verify(ner, never()).extractEntitiesBatch(anyList());
    hold.setOnBattery(false);
    scheduler.runIdleCycle();
    verify(ner).extractEntitiesBatch(List.of("content for pending enrichment"));
    verify(writes).updateDocumentsBatch(anyList());
  }
}
