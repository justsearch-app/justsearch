/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineWorkCancelledException;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.indexerworker.services.WorkerServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real budget/worker-call/error translation must preserve failures through the VDU facade. */
class EngineEnrichmentBacklogFailureTest {
  @ParameterizedTest
  @EnumSource(value = WorkerServiceException.Status.class, names = {"CANCELLED", "UNAVAILABLE", "INTERNAL"})
  void workerFailuresCrossTheActualJavaPortWithoutBecomingEmpty(WorkerServiceException.Status status) {
    var worker = mock(WorkerIngestService.class);
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(worker);
    var failure = new WorkerServiceException(status, "backlog read failed");
    when(worker.queryPendingVdu(any(), any())).thenThrow(failure);
    when(worker.countPendingEmbeddings(any())).thenThrow(failure);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      for (Runnable read : java.util.List.<Runnable>of(
          () -> client.countPendingVdu(TestEngineContexts.FOREGROUND),
          () -> client.countPendingEmbeddings(TestEngineContexts.FOREGROUND),
          () -> client.queryPendingVduDocIds(TestEngineContexts.FOREGROUND))) {
        var thrown = assertThrows(KnowledgeClientException.class, read::run);
        assertEquals(KnowledgeClientException.Status.valueOf(status.name()), thrown.status());
        assertSame(failure, thrown.getCause());
        assertEquals(0, admission.activeWorkCount());
      }
    }
  }

  @Test
  void cancelledAdmittedWorkCannotReturnAnEmptyBacklogOrReachTheWorker() {
    var services = mock(WorkerAppServices.class);
    var admission = new EngineAdmissionController(8, 8, 1);
    try (var registry = new DefaultEngineExecutorRegistry();
        var client = new EngineKnowledgeClient(registry, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(), () -> {}, admission)) {
      try (var work = admission.admit(TestEngineContexts.FOREGROUND, false)) {
        work.cancel("user_stop");
        assertThrows(EngineWorkCancelledException.class, () -> client.countPendingVdu(work.context()));
        assertThrows(EngineWorkCancelledException.class, () -> client.countPendingEmbeddings(work.context()));
        assertThrows(EngineWorkCancelledException.class, () -> client.queryPendingVduDocIds(work.context()));
        verifyNoInteractions(services);
      }
      assertEquals(0, admission.activeWorkCount());
    }
  }
}
