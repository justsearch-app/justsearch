/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.metrics.EncoderOrtRunSpans;
import io.justsearch.indexerworker.ner.NerResult;
import io.justsearch.indexerworker.ner.NerService;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexing.SchemaFields;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class EnrichmentLaneTracingTest {
  @Test
  void sparseNativeWorkInheritsTheEnrichmentSpan() throws Exception {
    checkParent(true);
  }

  @Test
  void nerNativeWorkInheritsTheEnrichmentSpan() throws Exception {
    checkParent(false);
  }

  private void checkParent(boolean sparse) throws Exception {
    var fields = mock(DocumentFieldOps.class);
    var coordinator = mock(IndexingCoordinator.class);
    BackfillSnapshotTestSupport.installReceipts(coordinator);
    BackfillSnapshotTestSupport.installSelection(fields);
    when(fields.queryDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of("doc"));
    when(fields.queryNonChunkDocIdsByField(anyString(), anyString(), anyInt())).thenReturn(List.of("doc"));
    var input = new DocumentFieldOps.EnrichmentInput("body", Map.of(
        sparse ? SchemaFields.SPLADE_STATUS : SchemaFields.NER_STATUS, "PENDING"), null, Map.of());
    when(fields.getEnrichmentInputs(anyList(), anyLong())).thenReturn(Map.of("doc", input));
    var expected = SpanContext.create("00000000000000000000000000000001", "0000000000000001",
        TraceFlags.getSampled(), TraceState.getDefault());
    var previous = Span.current().getSpanContext();
    try (var tracing = mockStatic(EncoderOrtRunSpans.class)) {
      tracing.when(EncoderOrtRunSpans::maybeEnrichmentBatch).thenReturn(Span.wrap(expected));
      StageOutcome outcome;
      if (sparse) {
        var encoder = mock(SpladeEncoder.class);
        when(encoder.encodeBatch(anyList())).thenAnswer(call -> {
          assertEquals(expected, Span.current().getSpanContext(), "native sparse work needs its enrichment parent");
          return List.of(Map.of("term", 1f));
        });
        outcome = SpladeBackfillOps.processParentBackfill(new SpladeBackfillOps.BackfillContext(fields,
            coordinator, IndexingPacing.unthrottled(), () -> encoder, () -> true, 1, false,
            LoggerFactory.getLogger(getClass()), () -> true), new EnrichmentSelection());
      } else {
        var service = mock(NerService.class);
        when(service.isAvailable()).thenReturn(true);
        when(service.extractEntitiesBatch(anyList())).thenAnswer(call -> {
          assertEquals(expected, Span.current().getSpanContext(), "native NER work needs its enrichment parent");
          return List.of(NerResult.EMPTY);
        });
        outcome = NerBackfillOps.processNerUnit(new NerBackfillOps.BackfillContext(fields, coordinator,
            IndexingPacing.unthrottled(), () -> service, () -> true, 1, LoggerFactory.getLogger(getClass()),
            () -> true), new EnrichmentSelection());
      }
      assertEquals(1, outcome.docsProcessed(), "the assertion must reach actual native work and publication");
    }
    assertEquals(previous, Span.current().getSpanContext(), "the lane must restore its caller's tracing context");
  }
}
