/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerSearchService;
import io.justsearch.ipc.ClearFailedJobsResponse;
import io.justsearch.ipc.SearchResponse;
import org.junit.jupiter.api.Test;

/** Replaces the retired label-parity pin with actual port dispatch and an adverse urgency case. */
final class EnginePortForegroundTest {
  @Test
  void realSearchAndFormerlyExcludedIngestCallsCountOnlyWhenForeground() {
    var load = new ForegroundLoad();
    var services = mock(WorkerAppServices.class);
    var search = mock(WorkerSearchService.class);
    var ingest = mock(WorkerIngestService.class);
    when(services.searchService()).thenReturn(search);
    when(services.ingestService()).thenReturn(ingest);
    when(search.search(any(), any())).thenReturn(SearchResponse.getDefaultInstance());
    when(ingest.clearFailedJobs(any(), any())).thenReturn(ClearFailedJobsResponse.getDefaultInstance());
    try (var client = new EngineKnowledgeClient(
        new io.justsearch.core.execution.TestEngineExecutors(), () -> services,
        new ForegroundLoadGate(load), 5_000, 100, IpcTelemetry.noop())) {
      client.search("probe", 10, TestEngineContexts.FOREGROUND);
      assertEquals(1, load.startedTotal());
      client.search("probe", 10, TestEngineContexts.BACKGROUND);
      assertEquals(1, load.startedTotal());
      client.clearFailedJobs(TestEngineContexts.FOREGROUND);
      assertEquals(2, load.startedTotal(), "ingest was outside the old operation-label set");
      client.clearFailedJobs(TestEngineContexts.BACKGROUND);
      assertEquals(2, load.startedTotal());
      assertEquals(0, load.inFlight());
    }
  }
}
