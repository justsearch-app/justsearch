/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.app.services.HeadAssembly;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Real ordered binding and real inference ownership cleanup, without any OS process control. */
final class HeadlessAppFailedDrainBackendTest {
  @ParameterizedTest
  @EnumSource(Reason.class)
  void failedDrainStopsOnlyTerminalOwnedServer(Reason reason, @TempDir Path tempDir)
      throws Exception {
    for (boolean external : new boolean[] {false, true}) {
      var exit = new CompletableFuture<ExitOutcome>();
      ChildHandle handle = mock(ChildHandle.class);
      when(handle.pid()).thenReturn(22004L);
      when(handle.childId()).thenReturn("c-test");
      when(handle.kind()).thenReturn(ChildKind.LLAMA_SERVER);
      when(handle.exit()).thenReturn(exit);
      when(handle.state()).thenAnswer(ignored ->
          exit.isDone() ? ChildHandle.State.EXITED : ChildHandle.State.RUNNING);
      List<StopRequest> stops = new CopyOnWriteArrayList<>();
      ChildControl control = mock(ChildControl.class);
      when(control.stopAll(any(), any())).thenAnswer(call -> {
        StopRequest request = call.getArgument(1);
        stops.add(request);
        exit.complete(requested(request));
        return CompletableFuture.completedFuture(List.of(exit.join()));
      });
      when(control.stop(any(), any(StopRequest.class))).thenAnswer(call -> {
        StopRequest request = call.getArgument(1);
        stops.add(request);
        exit.complete(requested(request));
        return exit;
      });
      var manager = new InferenceLifecycleManager(
          new io.justsearch.core.execution.TestEngineExecutors(),
          new InferenceConfig(Path.of("missing-llama-server.exe"), Path.of("missing-model.gguf"),
              null, 9991, 4096, 0, false),
          NoopInferenceTelemetryEvents.INSTANCE, control, null, ignored -> () -> { });
      Object server = readField(manager, "serverOps");
      if (external) {
        setField(server, "usingExternal", true);
      } else {
        // Owned as the launch path owns it: a started reservation and the live handle.
        @SuppressWarnings("unchecked")
        var owned = (Set<CompletableFuture<ChildHandle>>)
            readField(readField(server, "processOwnership"), "owned");
        owned.add(CompletableFuture.completedFuture(handle));
        setField(server, "child", handle);
      }
      try {
        var head = mock(HeadAssembly.class);
        Field inference = HeadAssembly.class.getDeclaredField("inferenceManager");
        inference.setAccessible(true);
        inference.set(head, manager);
        doCallRealMethod().when(head).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
        doCallRealMethod().when(head).recordTerminalStop(any(StopRequest.class));
        var admission = mock(EngineAdmissionService.class);
        var attempts = mock(OperationAttemptRunner.class);
        when(admission.awaitDrained(Duration.ofSeconds(5))).thenReturn(false);
        when(attempts.awaitDrained(Duration.ofSeconds(5))).thenReturn(false);
        var sequence = new EngineShutdownSequence(tempDir.resolve(reason.wire() + external),
            HeadlessApp.orderedShutdownSteps(null, head, null, null, null, null, null, null,
                mock(OperationLeaseService.class), admission,
                mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
                mock(io.justsearch.app.api.operations.OperationStore.class), attempts, null),
            ignored -> {});

        Object runner = readField(manager, "runner");
        var lockMethod = runner.getClass().getDeclaredMethod("lock");
        lockMethod.setAccessible(true);
        // An actual Head -> manager -> owned-handle stop must complete while another thread
        // holds the transition lock. A no-op Head forwarding method also fails the exit assertion.
        synchronized (lockMethod.invoke(runner)) {
          assertFalse(assertTimeoutPreemptively(Duration.ofSeconds(2), () -> sequence.run(reason)).clean());
          assertEquals(external || !reason.stopsGenerativeBackend(), !exit.isDone());
        }
        verify(head, never()).close();
        if (reason.stopsGenerativeBackend()) {
          verify(head).stopGenerativeBackendForTerminalShutdown(Duration.ofSeconds(12));
        } else {
          verify(head, never()).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
        }
        if (external || !reason.stopsGenerativeBackend()) {
          assertEquals(List.of(), stops, "the user's server is never stopped");
        } else {
          // Spec B §2.8: the server's stop is recorded with the Engine's own reason.
          assertEquals(List.of(HeadlessApp.terminalStopOf(reason)), stops);
        }
      } finally {
        manager.setStopServerOnClose(false);
        manager.close();
      }
    }
  }

  private static ExitOutcome requested(StopRequest request) {
    Instant now = Instant.now();
    return new ExitOutcome("c-test", ChildKind.LLAMA_SERVER, ExitClass.REQUESTED,
        Optional.of(request.reason()), request.detail(), Optional.of(request.mode()),
        OptionalInt.of(0x4A530001), Optional.of(now), now, Optional.of(now), Optional.of(now),
        TerminateResult.OK, true, ExitOutcome.Phase.STOPPING, true);
  }

  private static Object readField(Object target, String name) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
