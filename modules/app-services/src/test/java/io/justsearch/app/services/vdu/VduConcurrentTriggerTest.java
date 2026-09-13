/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.api.OfflineProcessingOutcome.BlockReason;
import io.justsearch.app.api.OfflineProcessingOutcome.EmbeddingHandoff;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Contending callers exercise the production single-flight guard and its actual-exit release. */
@Timeout(15)
class VduConcurrentTriggerTest {
  @Test
  void concurrentAndRapidTriggersRunOnlyOneBodyAndRefuseWhileItIsHeld() throws Exception {
    var start = new CountDownLatch(1);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var accepted = new AtomicReference<CompletionStage<OfflineProcessingOutcome>>();
    try (var fixture = new Fixture(); var callers = Executors.newFixedThreadPool(5)) {
      when(fixture.batch.processPendingFiles(any(), any())).thenAnswer(invocation -> {
        entered.countDown();
        assertTrue(release.await(5, TimeUnit.SECONDS));
        return fixture.outcome;
      });
      try {
        var submissions = new ArrayList<Future<Boolean>>();
        for (int i = 0; i < 5; i++) {
          submissions.add(callers.submit(() -> {
            assertTrue(start.await(5, TimeUnit.SECONDS));
            try {
              accepted.set(fixture.coordinator.startOfflineProcessing(fixture.context, outcome -> {}));
              return true;
            } catch (IllegalStateException refusal) {
              assertEquals("Enrichment is already running", refusal.getMessage());
              return false;
            }
          }));
        }
        start.countDown();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        int admitted = 0;
        for (var submission : submissions) if (submission.get(5, TimeUnit.SECONDS)) admitted++;
        assertEquals(1, admitted);
        assertTrue(fixture.coordinator.isProcessing());
        for (int i = 0; i < 100; i++) {
          assertThrows(IllegalStateException.class,
              () -> fixture.coordinator.startOfflineProcessing(fixture.context, outcome -> {}));
        }
        assertFalse(accepted.get().toCompletableFuture().isDone());
        verify(fixture.batch, times(1)).processPendingFiles(any(), any());
        release.countDown();
        assertTrue(accepted.get().toCompletableFuture().get(5, TimeUnit.SECONDS).complete());
        assertFalse(fixture.coordinator.isProcessing());
      } finally {
        start.countDown();
        release.countDown();
      }
    }
  }

  @Test
  void aCompletedPassAllowsTheNextPassWithoutATimingDelay() throws Exception {
    try (var fixture = new Fixture()) {
      for (int i = 0; i < 2; i++) {
        assertTrue(fixture.coordinator.startOfflineProcessing(fixture.context, outcome -> {})
            .toCompletableFuture().get(5, TimeUnit.SECONDS).complete());
        assertFalse(fixture.coordinator.isProcessing());
      }
      verify(fixture.batch, times(2)).processPendingFiles(any(), any());
      verify(fixture.work, times(2)).close();
    }
  }

  private static final class Fixture implements AutoCloseable {
    final TestEngineExecutors executors = TestEngineExecutors.awaitingTermination();
    final io.justsearch.core.context.EngineContext context = TestEngineContexts.durableInternal();
    final EngineWorkHandle work = mock(EngineWorkHandle.class);
    final VduBatchProcessor batch = mock(VduBatchProcessor.class);
    final OfflineProcessingOutcome outcome = new OfflineProcessingOutcome(
        1, 1, 0, BlockReason.NONE, EmbeddingHandoff.NOT_EVALUATED);
    final OfflineCoordinator coordinator;

    Fixture() {
      var admission = mock(EngineAdmissionService.class);
      when(admission.attach(context)).thenReturn(work);
      when(work.context()).thenReturn(context);
      when(work.cancellationReason()).thenReturn(Optional.empty());
      when(work.onCancel(any())).thenReturn(() -> {});
      var inference = mock(OnlineAiLifecycleControl.class);
      when(inference.isOnline()).thenReturn(true);
      var client = mock(KnowledgeClient.class);
      when(client.countPendingVdu(any())).thenReturn(1);
      when(batch.processPendingFiles(any(), any())).thenReturn(outcome);
      coordinator = new OfflineCoordinator(executors, admission, inference, null, batch,
          () -> client, new VduCapabilityState());
    }

    @Override public void close() {
      try { coordinator.close(); }
      finally { executors.close(); }
    }
  }
}
