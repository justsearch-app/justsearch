/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.*;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.disambiguation.DisambiguationService;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.loop.*;
import io.justsearch.indexerworker.loop.pacing.IndexingPacing;
import io.justsearch.indexerworker.queue.JobQueue;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DisambiguationAuthorityTest extends LuceneExecutorTestBase {
  @TempDir Path directory;

  @Test void providerReplacementAfterClusteringCannotAcknowledgeOrPublishThePage() throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var fields = mock(DocumentFieldOps.class);
      var service = mock(DisambiguationService.class);
      var signals = mock(WorkerSignalBus.class);
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class),
          runtime.indexCountOps(), runtime.commitOps());
      lifecycle.setEmbeddingProvider(mock(EmbeddingProvider.class));
      var scheduler = new BackfillScheduler(fields, runtime.indexingCoordinator(), runtime.indexCountOps(),
          runtime.commitOps(), signals, IndexingPacing.unthrottled(), lifecycle, new AtomicBoolean(true),
          () -> null, () -> null, () -> null, () -> null, () -> service);
      when(service.isAvailable()).thenReturn(true);
      var reader = new Object();
      var state = new DocumentFieldOps.EntityCorpusState(
          new DocumentFieldOps.EntityCorpusRevision(fields, 0, 1, 0), reader, 0);
      var reads = new AtomicInteger();
      when(fields.entityCorpusState()).thenAnswer(call -> {
        if (reads.incrementAndGet() == 2) lifecycle.setEmbeddingProvider(mock(EmbeddingProvider.class));
        return state;
      });
      when(service.prepareReconciliation(state.revision(), reader)).thenReturn(true);
      when(fields.entityPage(any(), anyInt(), anyInt())).thenReturn(
          new DocumentFieldOps.EntityPage(List.of(), 7, true));
      var process = BackfillScheduler.class.getDeclaredMethod("processDisambiguationBackfill");
      process.setAccessible(true);
      assertDoesNotThrow(() -> process.invoke(scheduler));
      assertEquals(2, reads.get(), "the race must occur after clustering and before acknowledgement");
      assertDoesNotThrow(() -> verify(service, never()).acknowledgeReconciliation(anyInt(), anyBoolean()),
          "the old page must not advance its cursor or publish its snapshot under the new authority");
    }
  }

  static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> pageStops() {
    return java.util.stream.Stream.of("shutdown", "interruption", "hold", "token", "unavailable")
        .flatMap(stop -> java.util.stream.Stream.of(
            org.junit.jupiter.params.provider.Arguments.of(stop, false),
            org.junit.jupiter.params.provider.Arguments.of(stop, true)));
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.MethodSource("pageStops")
  void hardStopsBoundEveryDisambiguationPage(String stop, boolean afterClustering) throws Exception {
    pageStop(stop, afterClustering);
  }

  @org.junit.jupiter.params.ParameterizedTest(name = "{displayName} [{index}] {arguments}")
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void serviceUnavailabilityCannotPublishOrAcknowledgeThePage(boolean afterClustering) throws Exception {
    pageStop("unavailable", afterClustering);
  }

  private void pageStop(String stop, boolean afterClustering) throws Exception {
    try (var runtime = IndexSchema.fromCatalog(FieldCatalogDef.forChunkTesting(4)).atPath(directory)
        .withExecutorRegistrations(testLuceneExecutors()).open()) {
      runtime.commitOps().stopCommitTimer();
      var fields = mock(DocumentFieldOps.class);
      var service = mock(DisambiguationService.class);
      var signals = mock(WorkerSignalBus.class);
      var lifecycle = new EmbeddingProviderLifecycle(signals, mock(JobQueue.class),
          runtime.indexCountOps(), runtime.commitOps());
      lifecycle.setEmbeddingProvider(mock(EmbeddingProvider.class));
      var running = new AtomicBoolean(true);
      var available = new AtomicBoolean(true);
      var scheduler = new BackfillScheduler(fields, runtime.indexingCoordinator(), runtime.indexCountOps(),
          runtime.commitOps(), signals, IndexingPacing.unthrottled(), lifecycle, running,
          () -> null, () -> null, () -> null, () -> null, () -> service);
      when(service.isAvailable()).thenAnswer(call -> available.get());
      var reader = new Object();
      var state = new DocumentFieldOps.EntityCorpusState(
          new DocumentFieldOps.EntityCorpusRevision(fields, 0, 1, 0), reader, 0);
      when(fields.entityCorpusState()).thenReturn(state);
      when(service.prepareReconciliation(state.revision(), reader)).thenReturn(true);
      Runnable trigger = () -> {
        switch (stop) {
          case "shutdown", "hold" -> running.set(false);
          case "interruption" -> Thread.currentThread().interrupt();
          case "token" -> lifecycle.setEmbeddingProvider(mock(EmbeddingProvider.class));
          case "unavailable" -> available.set(false);
          default -> throw new AssertionError(stop);
        }
      };
      when(fields.entityPage(any(), anyInt(), anyInt())).thenAnswer(call -> {
        if (!afterClustering) trigger.run();
        return new DocumentFieldOps.EntityPage(List.of(), 7, true);
      });
      doAnswer(call -> {
        trigger.run();
        return null;
      }).when(service).replaceSources(anyMap(), any());
      var process = BackfillScheduler.class.getDeclaredMethod("processDisambiguationBackfill");
      process.setAccessible(true);
      try {
        assertDoesNotThrow(() -> process.invoke(scheduler));
        assertDoesNotThrow(() -> verify(service, times(afterClustering ? 1 : 0)).replaceSources(anyMap(), any()));
        assertDoesNotThrow(() -> verify(service, never()).acknowledgeReconciliation(anyInt(), anyBoolean()));
      } finally { Thread.interrupted(); }
    }
  }

}
