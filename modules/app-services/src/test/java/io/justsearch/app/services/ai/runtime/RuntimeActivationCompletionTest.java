/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.RuntimeVariantService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationKind;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.registry.operations.handlers.ActivateRuntimeVariantHandler;
import io.justsearch.app.services.registry.operations.handlers.DeactivateRuntimeVariantHandler;
import io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActivationCompletionTest {
  @TempDir Path temp;

  @Test
  void recordedActivationAndDeactivationWaitForActualOwnerCleanup() throws Exception {
    String previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", temp.toString());
    try (var executors = new TestEngineExecutors();
        var owner = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE), null, null);
        var store = store()) {
      var service = new RuntimeVariantServiceImpl(owner);
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      for (boolean activate : new boolean[] {true, false}) {
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var lease = mock(OperationLeaseHandle.class);
        doAnswer(call -> {
          cleanupEntered.countDown();
          assertTrue(releaseCleanup.await(5, TimeUnit.SECONDS));
          return null;
        }).when(lease).release(any());
        var leases = mock(OperationLeaseService.class);
        when(leases.register(anyString(), any(), anyLong(), anyMap())).thenReturn(lease);
        owner.setOperationLeaseService(leases);
        OperationHandler handler = activate ? new ActivateRuntimeVariantHandler(() -> service)
            : new DeactivateRuntimeVariantHandler(() -> service);
        var request = request(activate ? "core.activate-runtime-variant" : "core.deactivate-runtime-variant");
        var accepted = runner.accept(request);
        try {
          var attempt = runner.start(accepted, handle -> handler.executeRecorded(
              "{\"variantId\":\"fixture-missing\"}", null, request.context(), handle));
          assertTrue(attempt.response().success(), "the existing immediate started response is preserved");
          assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
          assertFalse(attempt.completion().toCompletableFuture().isDone());
          assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
          assertThrows(IllegalStateException.class, () -> owner.startActivate("other"));
          releaseCleanup.countDown();
          var row = attempt.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
          assertEquals(OperationState.FAILED, row.state());
          assertEquals(activate ? "RUNTIME_VARIANT_NOT_INSTALLED" : "RUNTIME_BASELINE_NOT_FOUND", row.failureReason());
          verify(lease).release(OpLeaseOutcome.FAILURE);
        } finally { releaseCleanup.countDown(); }
      }
    } finally {
      if (previousHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", previousHome);
    }
  }

  @Test
  void leaseRegistrationRefusalDoesNotPermanentlyOccupyTheOwner() throws Exception {
    String previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", temp.toString());
    try (var executors = new TestEngineExecutors();
        var owner = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE), null, null)) {
      var leases = mock(OperationLeaseService.class);
      when(leases.register(anyString(), any(), anyLong(), anyMap()))
          .thenThrow(new IllegalStateException("fixture lease refusal"));
      owner.setOperationLeaseService(leases);
      assertThrows(IllegalStateException.class, () -> owner.startActivate("fixture-missing"));
      assertEquals("failed", owner.getActivationStatus().state);
      assertEquals("RUNTIME_ACTIVATION_START_FAILED", owner.getActivationStatus().errorCode);
      owner.setOperationLeaseService(OperationLeaseService.noOp());
      assertEquals("RUNTIME_VARIANT_NOT_INSTALLED", owner.startActivate("fixture-missing").completion()
          .toCompletableFuture().get(5, TimeUnit.SECONDS).errorCode);
    } finally {
      if (previousHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", previousHome);
    }
  }

  @Test
  void completedSelfTestWithoutActivationCannotProduceASuccessReceipt() throws Exception {
    try (var store = store()) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      for (String result : java.util.List.of("passed", "failed", "inconclusive")) {
        var completion = new CompletableFuture<AiRuntimeActivationStatus>();
        var service = mock(RuntimeVariantService.class);
        when(service.activate("fixture")).thenReturn(new RuntimeVariantService.Attempt(Map.of("state", "running"), completion));
        var handler = new ActivateRuntimeVariantHandler(() -> service);
        var request = request("core.activate-runtime-variant");
        var attempt = runner.start(runner.accept(request), handle -> handler.executeRecorded(
            "{\"variantId\":\"fixture\"}", null, request.context(), handle));
        assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
        var status = new AiRuntimeActivationStatus();
        status.state = "completed";
        status.result = result;
        status.message = "owner terminal";
        completion.complete(status);
        var row = attempt.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("passed".equals(result) ? OperationState.COMPLETE : OperationState.FAILED, row.state());
        assertEquals(switch (result) {
          case "passed" -> "SUCCESS";
          case "failed" -> "SELF_TEST_FAILED";
          default -> "SELF_TEST_INCONCLUSIVE";
        }, row.receipt().code());
      }
    }
  }

  @Test
  void statusReadFailureCannotStartAnEffectAndLoseItsCompletion() {
    var helper = mock(io.justsearch.app.api.RuntimeActivationService.class);
    when(helper.getStatus()).thenThrow(new IllegalStateException("fixture status read failure"));
    var service = new RuntimeVariantServiceImpl(helper);
    assertThrows(IllegalStateException.class, () -> service.activate("fixture"));
    assertThrows(IllegalStateException.class, service::deactivate);
    verify(helper, never()).startActivate(anyString());
    verify(helper, never()).startDeactivate();
  }

  @Test
  void exceptionalOwnerExitReleasesLeaseAndPublishesFailedStatus() throws Exception {
    String previousHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", temp.toString());
    try (var executors = new TestEngineExecutors();
        var owner = new RuntimeActivationService(executors, OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE), null, null)) {
      var lease = mock(OperationLeaseHandle.class);
      var leases = mock(OperationLeaseService.class);
      when(leases.register(anyString(), any(), anyLong(), anyMap())).thenReturn(lease);
      owner.setOperationLeaseService(leases);
      var attempt = owner.startActivate("invalid" + (char) 0 + "name");
      assertEquals("running", attempt.started().state);
      var failure = assertThrows(java.util.concurrent.ExecutionException.class,
          () -> attempt.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));
      assertInstanceOf(java.nio.file.InvalidPathException.class, failure.getCause());
      assertEquals("failed", owner.getActivationStatus().state);
      assertEquals("RUNTIME_ACTIVATION_FAILED", owner.getActivationStatus().errorCode);
      verify(lease).release(OpLeaseOutcome.FAILURE);
      owner.setOperationLeaseService(OperationLeaseService.noOp());
      var second = owner.startDeactivate().completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
      assertEquals("RUNTIME_BASELINE_NOT_FOUND", second.errorCode);
      assertEquals("running", attempt.started().state, "the started snapshot cannot alias later attempts");
    } finally {
      if (previousHome == null) System.clearProperty("justsearch.home");
      else System.setProperty("justsearch.home", previousHome);
    }
  }

  private SqliteOperationStore store() throws Exception {
    return new SqliteOperationStore(temp.resolve("operations.db"));
  }

  private static OperationAttemptRunner.Request request(String ref) {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "activation-fixture",
        Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    return new OperationAttemptRunner.Request(OperationKeys.generate(Clock.systemUTC()),
        OperationDescriptor.invocation(OperationKind.OPERATION, ref, "{}", false), context, null);
  }
}
