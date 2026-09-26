/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.core.scheduling.GpuSchedulingGauge;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexerworker.server.KnowledgeServer;
import io.justsearch.indexerworker.server.RecordedIngestionLifecycle;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class EngineRecordedBindingTest {
  @Test
  void missingPhysicalAttachmentCannotReturnAUsableClient() throws Exception {
    var server = server();
    try (var root = new EngineRoot(mock(OperationStore.class), mock(OperationAttemptRunner.class),
        (gauge, executors, ingestion, indexComponent, encoderComponent) -> server, 1_000, 100)) {
      try {
        assertThrows(NullPointerException.class, () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()));
        verify(server).close();
      } finally { root.executors().close(); }
    }
  }

  @Test
  void failedBindAndClientCloseCannotExposeTheRetainedUnreadyClient() throws Exception {
    var bindFailure = new IllegalStateException("bind maintenance failed");
    var closeFailure = new IllegalStateException("client close incomplete");
    var failMaintenance = new AtomicBoolean();
    var attempts = mock(OperationAttemptRunner.class);
    doAnswer(call -> { if (failMaintenance.get()) throw bindFailure; return null; })
        .when(attempts).reconcile(any(), any());
    var attachment = new AtomicReference<RecordedIngestionLifecycle.Attachment>();
    var lifecycle = new AtomicReference<RecordedIngestionLifecycle>();
    var server = server();
    doAnswer(call -> {
      attachment.set(lifecycle.get().attach(mock(JobQueue.class), Optional::empty, () -> false));
      failMaintenance.set(true);
      return null;
    }).when(server).start();
    doAnswer(call -> { attachment.get().close(); return null; }).when(server).close();
    try (var clients = mockConstruction(EngineKnowledgeClient.class, (client, context) ->
        doThrow(closeFailure).doNothing().when(client).close());
        var root = new EngineRoot(mock(OperationStore.class), attempts,
            (gauge, executors, ingestion, indexComponent, encoderComponent) -> {
              lifecycle.set(ingestion);
              return server;
            }, 1_000, 100, ignored -> {}, () -> {}, OperationAuthority.inMemory())) {
      try {
        assertSame(bindFailure, assertThrows(IllegalStateException.class,
            () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop())));
        assertArrayEquals(new Throwable[] {closeFailure}, bindFailure.getSuppressed());
        assertEquals(1, clients.constructed().size());
        assertThrows(IOException.class, () -> root.start(new GpuSchedulingGauge(), IpcTelemetry.noop()),
            "retained client is an owner, not a successfully activated result");
        assertEquals(1, clients.constructed().size(), "retry cannot create another client over retained ownership");
        failMaintenance.set(false);
        root.close();
        verify(clients.constructed().getFirst(), times(2)).close();
        verify(server).close();
      } finally {
        failMaintenance.set(false);
        root.close();
        root.executors().close();
      }
    }
  }

  private static KnowledgeServer server() throws Exception {
    var server = mock(KnowledgeServer.class);
    when(server.foregroundLoad()).thenReturn(new ForegroundLoad());
    when(server.awaitClosed(anyLong())).thenReturn(true);
    return server;
  }
}
