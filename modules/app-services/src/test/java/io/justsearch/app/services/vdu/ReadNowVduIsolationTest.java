/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.core.execution.TestEngineExecutors;
import io.justsearch.core.scheduling.IndexingHold;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/** S6c-6 (C1'): picture reading, explicit or automatic, stays held while file reading is released. */
final class ReadNowVduIsolationTest {
  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, "IndexingHold." + name);
  }

  @Test
  void explicitAndAutomaticPictureReadingStayHeldWhileReleased() throws Exception {
    var hold = new IndexingHold();
    hold.setOnBattery(true);
    hold.setPolicy(false, null, null, null, true);
    assertEquals("STARTED", call(call(hold, "readWaitingNow", true), "outcome").toString());
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
    when(batch.processPendingFiles(eq(context), any())).thenAnswer(ignored -> {
      entered.countDown();
      return new OfflineProcessingOutcome(1, 1, 0, OfflineProcessingOutcome.BlockReason.NONE,
          OfflineProcessingOutcome.EmbeddingHandoff.NOT_NEEDED);
    });
    try (var executors = TestEngineExecutors.awaitingTermination();
        var coordinator = new OfflineCoordinator(executors, admission, batch, () -> client, new VduCapabilityState())) {
      try {
        var automatic = coordinator.startAutomaticProcessing(context, () -> true).toCompletableFuture();
        assertEquals(0, automatic.get(2, TimeUnit.SECONDS).selected(), "automatic picture reading yields");
        var explicit = coordinator.startExplicitProcessing(context, ignored -> {}).toCompletableFuture();
        assertThrows(TimeoutException.class, () -> explicit.get(300, TimeUnit.MILLISECONDS),
            "explicit picture reading waits");
        assertEquals(1, entered.getCount(), "no picture was read while file reading was released");
        hold.setOnBattery(false);
        assertEquals(1, explicit.get(5, TimeUnit.SECONDS).processed(), "it runs once the rule clears");
      } finally {
        hold.setPolicy(false, null, null, null, false);
      }
    }
  }
}
