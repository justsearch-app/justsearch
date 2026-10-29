/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** S38.1/5: accepted picture reading waits, and acknowledgment follows its last publication. */
final class IndexingRulesVduTest {
  private Object call(Object target, String name) {
    return assertDoesNotThrow(() -> target.getClass().getMethod(name).invoke(target));
  }

  private void pause(Object hold) {
    var shared = (io.justsearch.core.scheduling.IndexingHold) hold;
    shared.setOnBattery(true);
    shared.setPolicy(false, null, null, null, true);
  }

  @Test
  void explicitPassWaitsAndAnActivePassMustFinishBeforeQuiet() throws Exception {
    Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor().newInstance(), "picture reading must share the acknowledged hold");
    pause(hold);
    var client = VduAttemptTestSupport.client(invocation ->
        "indexingHold".equals(invocation.getMethod().getName()) ? hold : RETURNS_DEFAULTS.answer(invocation));
    var context = TestEngineContexts.durableInternal();
    var work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    var admission = mock(EngineAdmissionService.class);
    when(admission.attach(context)).thenReturn(work);
    var batch = mock(VduBatchProcessor.class);
    when(client.countPendingVdu(context)).thenReturn(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(batch.processPendingFiles(eq(context), any())).thenAnswer(ignored -> {
      entered.countDown();
      assertTrue(release.await(5, TimeUnit.SECONDS));
      return new OfflineProcessingOutcome(1, 1, 0, OfflineProcessingOutcome.BlockReason.NONE,
          OfflineProcessingOutcome.EmbeddingHandoff.NOT_NEEDED);
    });
    try (var executors = TestEngineExecutors.awaitingTermination();
        var coordinator = new OfflineCoordinator(executors, admission, batch, () -> client, new VduCapabilityState())) {
      try {
        var automatic = coordinator.startAutomaticProcessing(context, () -> true).toCompletableFuture();
        var skipped = assertDoesNotThrow(() -> automatic.get(150, TimeUnit.MILLISECONDS),
            "automatic work must yield its slot while paused so explicit work can be accepted");
        assertEquals(0, skipped.selected());
        var result = coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture();
        assertThrows(TimeoutException.class, () -> result.get(150, TimeUnit.MILLISECONDS));
        verify(client, never()).recoverVduProcessing(any());
        ((io.justsearch.core.scheduling.IndexingHold) hold).setPolicy(false, null, null, null, false);
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        pause(hold);
        assertEquals("PAUSING", call(call(hold, "snapshot"), "state").toString());
        release.countDown();
        assertEquals(1, result.get(5, TimeUnit.SECONDS).processed());
        assertEquals("PAUSED", call(call(hold, "snapshot"), "state").toString());
      } finally {
        release.countDown();
        ((io.justsearch.core.scheduling.IndexingHold) hold).setPolicy(false, null, null, null, false);
      }
    }
  }
}
