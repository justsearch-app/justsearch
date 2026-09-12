/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationKind;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.braininstall.BrainInstallServiceImpl;
import io.justsearch.app.services.registry.operations.handlers.StartAiInstallHandler;
import io.justsearch.app.services.registry.operations.handlers.RepairAiInstallHandler;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AiInstallOperationCompletionTest {
  @TempDir Path temp;

  @Test
  void installAndRepairKeepTheirRowsAndGuardUntilOwnerCleanupExits() throws Exception {
    var owner = ownerWithBlockedHome();
    var service = new BrainInstallServiceImpl(owner);
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      for (boolean repair : new boolean[] {false, true}) {
        var cleanupEntered = new CountDownLatch(1);
        var releaseCleanup = new CountDownLatch(1);
        var lease = mock(OperationLeaseHandle.class);
        doAnswer(call -> {
          cleanupEntered.countDown();
          assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS));
          return null;
        }).when(lease).release(any());
        var leases = mock(OperationLeaseService.class);
        when(leases.register(anyString(), any(), anyLong(), anyMap(), any())).thenReturn(lease);
        owner.setOperationLeaseService(leases);
        OperationHandler handler = repair ? new RepairAiInstallHandler(() -> service) : new StartAiInstallHandler(() -> service);
        var request = request(repair ? "core.repair-ai-install" : "core.start-ai-install");
        try {
          var result = runner.start(runner.accept(request), handle -> handler.executeRecorded(
              "{\"acceptTerms\":true}", null, request.context(), handle));
          assertTrue(result.response().success(), result.response().toString());
          assertEquals("running", result.response().structuredData().get("state"));
          assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
          assertTrue(owner.isInstallRunning());
          assertFalse(result.completion().toCompletableFuture().isDone());
          assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
          assertThrows(AiInstallException.class, () -> owner.startInstall(true));
          releaseCleanup.countDown();
          var row = result.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
          assertEquals(OperationState.FAILED, row.state());
          assertEquals("INSTALL_IO_ERROR", row.failureReason());
          assertFalse(owner.isInstallRunning());
          assertFalse(owner.getStatus().paused);
          verify(lease).release(OpLeaseOutcome.FAILURE);
        } finally { releaseCleanup.countDown(); }
      }
    }
  }

  @Test
  void registrationRefusalLeavesNoPhantomRunAndAllowsANewAttempt() throws Exception {
    var owner = ownerWithBlockedHome();
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap(), any()))
        .thenThrow(new IllegalStateException("fixture lease refusal"));
    owner.setOperationLeaseService(leases);
    assertThrows(IllegalStateException.class, () -> owner.startInstall(true));
    assertFalse(owner.isInstallRunning());
    assertEquals("failed", owner.getStatus().state);
    assertEquals("INSTALL_START_FAILED", owner.getStatus().errorCode);
    owner.setOperationLeaseService(OperationLeaseService.noOp());
    var attempt = owner.repair(true);
    assertEquals("running", attempt.started().state);
    assertEquals("", attempt.started().errorCode);
    var terminal = attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS);
    assertEquals("INSTALL_IO_ERROR", terminal.errorCode);
    assertEquals("running", attempt.started().state, "started and terminal snapshots cannot alias");
  }

  @Test
  void terminalOwnerStatesDriveReceiptsIncludingActualCancellation() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      for (String state : java.util.List.of("completed", "failed", "cancelled", "running")) {
        var actual = new CompletableFuture<AiInstallStatus>();
        var initial = new AiInstallStatus();
        initial.state = "running";
        var service = mock(BrainInstallService.class);
        when(service.startInstall(true)).thenReturn(new io.justsearch.app.api.AiInstallService.Attempt(initial, actual));
        var handler = new StartAiInstallHandler(() -> service);
        var request = request("core.start-ai-install");
        var result = runner.start(runner.accept(request), handle -> handler.executeRecorded(
            "{\"acceptTerms\":true}", null, request.context(), handle));
        assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
        var terminal = new AiInstallStatus();
        terminal.state = state;
        terminal.message = "fixture outcome";
        terminal.installedFully = false; // Completed plan attempts preserve the existing limitations contract.
        if ("failed".equals(state)) terminal.errorCode = "INSTALL_IO_ERROR";
        actual.complete(terminal);
        var row = result.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(switch (state) {
          case "completed" -> OperationState.COMPLETE;
          case "cancelled" -> OperationState.CANCELLED;
          default -> OperationState.FAILED;
        }, row.state());
        if ("running".equals(state)) assertEquals("INSTALL_INCOMPLETE", row.failureReason());
      }
    }
  }

  @Test
  void cancellationBeforeFirstEffectWaitsForRealOwnerCleanup() throws Exception {
    Path home = temp.resolve("cancelled-home");
    var owner = new AiInstallService(null, null, null, null, home);
    var cleanupEntered = new CountDownLatch(1);
    var releaseCleanup = new CountDownLatch(1);
    var lease = mock(OperationLeaseHandle.class);
    doAnswer(call -> {
      cleanupEntered.countDown();
      assertTrue(releaseCleanup.await(10, TimeUnit.SECONDS));
      return null;
    }).when(lease).release(any());
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap(), any())).thenAnswer(call -> {
      ((Runnable) call.getArgument(4)).run(); // Real upgrade cancellation before the owner starts.
      return lease;
    });
    owner.setOperationLeaseService(leases);
    var service = new BrainInstallServiceImpl(owner);
    var handler = new StartAiInstallHandler(() -> service);
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var request = request("core.start-ai-install");
      try {
        var result = runner.start(runner.accept(request), handle -> handler.executeRecorded(
            "{\"acceptTerms\":true}", null, request.context(), handle));
        assertTrue(cleanupEntered.await(10, TimeUnit.SECONDS));
        assertFalse(Files.exists(home), "cancellation before start cannot create install files");
        assertTrue(owner.isInstallRunning());
        assertFalse(result.completion().toCompletableFuture().isDone());
        assertEquals(OperationState.RUNNING, store.find(request.key()).orElseThrow().state());
        releaseCleanup.countDown();
        assertEquals(OperationState.CANCELLED, result.completion().toCompletableFuture()
            .get(10, TimeUnit.SECONDS).state());
        assertFalse(owner.isInstallRunning());
        verify(lease).release(OpLeaseOutcome.FAILURE);
      } finally { releaseCleanup.countDown(); }
    }
  }

  @Test
  void leaseReleaseFailureSurfacesThroughTheActualCompletion() throws Exception {
    var owner = ownerWithBlockedHome();
    var lease = mock(OperationLeaseHandle.class);
    doThrow(new IllegalStateException("fixture lease release failed")).when(lease).release(any());
    var leases = mock(OperationLeaseService.class);
    when(leases.register(anyString(), any(), anyLong(), anyMap(), any())).thenReturn(lease);
    owner.setOperationLeaseService(leases);
    var attempt = owner.startInstall(true);
    var failure = assertThrows(java.util.concurrent.ExecutionException.class,
        () -> attempt.completion().toCompletableFuture().get(10, TimeUnit.SECONDS));
    assertEquals("fixture lease release failed", failure.getCause().getMessage());
    assertFalse(owner.isInstallRunning());
    assertEquals("INSTALL_OWNER_FAILED", owner.getStatus().errorCode);
    verify(lease).release(OpLeaseOutcome.FAILURE);
  }

  private AiInstallService ownerWithBlockedHome() throws Exception {
    Path blocked = temp.resolve("blocked-home");
    Files.writeString(blocked, "fixture prevents installation before any download");
    return new AiInstallService(null, null, null, null, blocked);
  }

  private static OperationAttemptRunner.Request request(String ref) {
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "install-fixture",
        Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
        EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.FOREGROUND);
    return new OperationAttemptRunner.Request(OperationKeys.generate(Clock.systemUTC()),
        OperationDescriptor.invocation(OperationKind.OPERATION, ref, "{}", false), context, null);
  }
}
