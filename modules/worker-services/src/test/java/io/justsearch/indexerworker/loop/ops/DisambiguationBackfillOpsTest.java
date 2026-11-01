/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps.EntityCorpusRevision;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps.EntityCorpusState;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps.EntityDocument;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps.EntityPage;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class DisambiguationBackfillOpsTest {
  final DocumentFieldOps fields = mock(DocumentFieldOps.class);
  final DisambiguationService service = mock(DisambiguationService.class);

  DisambiguationBackfillOps.BackfillContext context(IndexingPacing pacing) {
    return new DisambiguationBackfillOps.BackfillContext(fields, pacing, () -> service,
        () -> true, 100, LoggerFactory.getLogger(getClass()));
  }

  @Test void absentServiceDoesNotReadCorpus() {
    var context = new DisambiguationBackfillOps.BackfillContext(fields, IndexingPacing.unthrottled(),
        () -> null, () -> true, 100, LoggerFactory.getLogger(getClass()));
    DisambiguationBackfillOps.processDisambiguationBackfill(context);
    verifyNoInteractions(fields, service);
  }

  @Test void unavailableServiceDoesNotReadCorpus() throws Exception {
    DisambiguationBackfillOps.processDisambiguationBackfill(context(IndexingPacing.unthrottled()));
    verifyNoInteractions(fields);
  }

  @Test void newlyVisiblePendingNerDefersReconciliation() throws Exception {
    when(service.isAvailable()).thenReturn(true);
    when(fields.entityCorpusState()).thenReturn(new EntityCorpusState(
        new EntityCorpusRevision(fields, 0, 1, 0), new Object(), 1));
    DisambiguationBackfillOps.processDisambiguationBackfill(context(IndexingPacing.unthrottled()));
    verify(service, never()).prepareReconciliation(any(), any());
  }

  @Test void failedSelectionDoesNotAcknowledgeCursor() throws Exception {
    when(service.isAvailable()).thenReturn(true);
    Object reader = new Object();
    var state = new EntityCorpusState(new EntityCorpusRevision(fields, 0, 1, 0), reader, 0);
    when(fields.entityCorpusState()).thenReturn(state);
    when(service.prepareReconciliation(state.revision(), reader)).thenReturn(true);
    when(fields.entityPage(any(), anyInt(), anyInt())).thenThrow(new IOException("selection failed"));
    DisambiguationBackfillOps.processDisambiguationBackfill(context(IndexingPacing.unthrottled()));
    verify(service, never()).acknowledgeReconciliation(anyInt(), anyBoolean());
  }

  @Test void processesAllDocsUnderForegroundLoad() throws Exception {
    when(service.isAvailable()).thenReturn(true);
    Object reader = new Object();
    var state = new EntityCorpusState(new EntityCorpusRevision(fields, 0, 2, 0), reader, 0);
    when(fields.entityCorpusState()).thenReturn(state);
    when(service.prepareReconciliation(state.revision(), reader)).thenReturn(true);
    var mentions = Map.of("PERSON", List.of("John Smith"));
    when(fields.entityPage(any(), anyInt(), anyInt())).thenReturn(new EntityPage(
        List.of(new EntityDocument("a", mentions), new EntityDocument("b", mentions)), 1, true));
    ForegroundLoad load = new ForegroundLoad(); load.started();
    var pacing = new IndexingPacing(load, 20, 500L, () -> 0L, () -> 0L, ms -> {});
    DisambiguationBackfillOps.processDisambiguationBackfill(context(pacing));
    verify(service).replaceSources(eq(Map.of("a", mentions, "b", mentions)), any());
    verify(service).acknowledgeReconciliation(1, true);
  }

  @Test void changedReaderDoesNotAcknowledgeMixedPass() throws Exception {
    when(service.isAvailable()).thenReturn(true);
    Object reader = new Object();
    var revision = new EntityCorpusRevision(fields, 0, 1, 0);
    when(fields.entityCorpusState()).thenReturn(new EntityCorpusState(revision, reader, 0),
        new EntityCorpusState(revision, new Object(), 0));
    when(service.prepareReconciliation(revision, reader)).thenReturn(true);
    when(fields.entityPage(any(), anyInt(), anyInt())).thenReturn(new EntityPage(List.of(), -1, true));
    DisambiguationBackfillOps.processDisambiguationBackfill(context(IndexingPacing.unthrottled()));
    verify(service, never()).acknowledgeReconciliation(anyInt(), anyBoolean());
  }
}
