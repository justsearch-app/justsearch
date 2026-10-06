/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.OfflineProcessingOutcome;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The guard and admitted work survive a failing pass while its failure remains observable. The
 * coordinator no longer brackets the pass in a reconciler procedure (Spec A step 6: the VDU batch
 * holds the chat controller itself), so there is no procedure cleanup to fail.
 */
@Timeout(15)
class OfflineCoordinatorFailureCleanupTest {
  private final List<TestEngineExecutors> registries = new ArrayList<>();
  private final EngineContext context = TestEngineContexts.durableInternal();

  @AfterEach
  void tearDown() {
    registries.forEach(TestEngineExecutors::close);
  }

  @Test
  void bodyFailureIsPrimaryAndReleasesTheGuardForTheNextPass() throws Exception {
    IllegalStateException body = new IllegalStateException("client unavailable");
    AtomicInteger calls = new AtomicInteger();
    OfflineCoordinator coordinator = coordinator(() -> {
      if (calls.getAndIncrement() == 0) throw body;
      return null;
    });

    assertSame(body, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertEquals(0, body.getSuppressed().length);
    assertFalse(coordinator.isProcessing());

    await(coordinator.startOfflineProcessing(context, ignored -> {}));
    assertEquals(2, calls.get());
    assertFalse(coordinator.isProcessing());
  }

  @Test
  void fatalBodyFailureIsPreservedAndReleasesTheGuard() throws Exception {
    AssertionError body = new AssertionError("fatal procedure failure");
    OfflineCoordinator coordinator = coordinator(() -> { throw body; });

    assertSame(body, failureOf(coordinator.startOfflineProcessing(context, ignored -> {})));
    assertEquals(0, body.getSuppressed().length);
    assertFalse(coordinator.isProcessing());
  }

  private OfflineCoordinator coordinator(Supplier<KnowledgeClient> clientSupplier) {
    TestEngineExecutors executors = new TestEngineExecutors();
    registries.add(executors);
    EngineAdmissionService admission = mock(EngineAdmissionService.class);
    EngineWorkHandle work = mock(EngineWorkHandle.class);
    when(work.context()).thenReturn(context);
    when(work.cancellationReason()).thenReturn(Optional.empty());
    when(work.onCancel(any())).thenReturn(mock(EngineWorkHandle.Registration.class));
    when(admission.attach(context)).thenReturn(work);
    return new OfflineCoordinator(executors, admission, mock(VduBatchProcessor.class),
        clientSupplier, new VduCapabilityState());
  }

  private static OfflineProcessingOutcome await(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
  }

  private static Throwable failureOf(CompletionStage<OfflineProcessingOutcome> stage)
      throws Exception {
    ExecutionException thrown = assertThrows(ExecutionException.class,
        () -> stage.toCompletableFuture().get(5, TimeUnit.SECONDS));
    return thrown.getCause();
  }
}
