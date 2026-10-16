/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.services.worker.IpcTelemetry;
import io.justsearch.app.services.worker.WatchedRootsState;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.indexerworker.loop.pacing.ForegroundLoad;
import io.justsearch.indexerworker.server.WorkerAppServices;
import io.justsearch.indexerworker.services.WorkerIngestService;
import io.justsearch.ipc.RecoverVduProcessingRequest;
import io.justsearch.ipc.RecoverVduProcessingResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real bounded unary dispatch with the process-closing ordering fixed before the call starts. */
@Timeout(15)
final class EngineVduRetainedCleanupTest {
  @Test void ownedRecoveryFinishesAfterAdmissionClosesWithoutAdmittingNewWork() throws Exception {
    var admission = new EngineAdmissionController(4, 8, 1);
    var ingest = mock(WorkerIngestService.class);
    var services = mock(WorkerAppServices.class);
    when(services.ingestService()).thenReturn(ingest);
    try (var executors = TestEngineExecutors.awaitingTermination();
        var client = new EngineKnowledgeClient(executors, () -> services,
            new ForegroundLoadGate(new ForegroundLoad()), 5_000, 100, IpcTelemetry.noop(),
            () -> {}, admission, WatchedRootsState.inMemory());
        var owner = admission.admit(TestEngineContexts.BACKGROUND, false)) {
      when(ingest.recoverVduProcessing(any(), any())).thenAnswer(call -> {
        RecoverVduProcessingRequest request = call.getArgument(0);
        var context = call.getArgument(1, io.justsearch.indexerworker.services.CallContext.class);
        assertEquals(owner.context().workId().orElseThrow().toString(), request.getProcedureOwner());
        assertEquals(owner.context(), context.engineContext());
        assertFalse(context.cancelled());
        assertTrue(admission.isClosing());
        assertFalse(admission.awaitDrained(Duration.ZERO), "cleanup still owns exact live work");
        return RecoverVduProcessingResponse.newBuilder().setRecoveredCount(1).build();
      });
      admission.beginClosing();
      assertEquals(EngineAdmissionException.Reason.FROZEN,
          assertThrows(EngineAdmissionException.class, () -> admission.attach(owner.context())).reason());
      assertEquals(EngineAdmissionException.Reason.FROZEN,
          assertThrows(EngineAdmissionException.class,
              () -> admission.admit(TestEngineContexts.BACKGROUND, true)).reason());
      assertEquals(1, assertDoesNotThrow(() -> recoverOwned(client, owner)),
          "an existing owner returns its claims after the new-work fence");
      verify(ingest, times(1)).recoverVduProcessing(any(), any());
      assertEquals(1, admission.activeWorkCount());
      owner.close();
      assertTrue(admission.awaitDrained(Duration.ofSeconds(1)));
      assertEquals(EngineAdmissionException.Reason.WORK_FINISHED,
          assertThrows(EngineAdmissionException.class, () -> recoverOwned(client, owner)).reason());
      verifyNoMoreInteractions(ingest);
    }
  }

  /** Replay the existing context-only path on revisions predating retained-owner recovery. */
  private static int recoverOwned(EngineKnowledgeClient client, EngineWorkHandle owner) throws Exception {
    java.lang.reflect.Method retained;
    try {
      retained = client.getClass().getMethod("recoverVduProcessingForRetainedOwner", EngineWorkHandle.class);
    } catch (NoSuchMethodException beforeRetainedRecovery) {
      return client.recoverVduProcessingForOwner(owner.context());
    }
    try {
      return (int) retained.invoke(client, owner);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
      if (failure.getCause() instanceof Error error) throw error;
      throw failure;
    }
  }
}
