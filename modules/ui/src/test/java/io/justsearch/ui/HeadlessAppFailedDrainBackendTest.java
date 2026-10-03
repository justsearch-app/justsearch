/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.api.operations.OperationAttemptRunner;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest.Reason;
import io.justsearch.app.inference.InferenceConfig;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.HeadAssembly;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
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
      var manager = new InferenceLifecycleManager(
          new io.justsearch.core.execution.TestEngineExecutors(),
          new InferenceConfig(Path.of("missing-llama-server.exe"), Path.of("missing-model.gguf"),
              null, 9991, 4096, 0, false));
      var alive = new AtomicBoolean(true);
      ProcessHandle handle = mock(ProcessHandle.class);
      when(handle.isAlive()).thenAnswer(ignored -> alive.get());
      when(handle.pid()).thenReturn(22004L);
      when(handle.destroy()).thenAnswer(ignored -> { alive.set(false); return true; });
      when(handle.onExit()).thenReturn(CompletableFuture.completedFuture(handle));
      Object server = readField(manager, "serverOps");
      if (external) {
        setField(server, "usingExternal", true);
      } else {
        Process process = mock(Process.class);
        when(process.toHandle()).thenReturn(handle);
        setField(server, "process", process);
      }
      try {
        var head = mock(HeadAssembly.class);
        doAnswer(call -> {
          manager.stopServerForTerminalShutdown(call.getArgument(0));
          return null;
        }).when(head).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
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

        assertFalse(sequence.run(reason).clean());
        assertEquals(external || !reason.stopsGenerativeBackend(), alive.get());
        verify(head, never()).close();
        if (reason.stopsGenerativeBackend()) {
          verify(head).stopGenerativeBackendForTerminalShutdown(Duration.ofSeconds(12));
        } else {
          verify(head, never()).stopGenerativeBackendForTerminalShutdown(any(Duration.class));
        }
        if (external || !reason.stopsGenerativeBackend()) verify(handle, never()).destroy();
        else verify(handle).destroy();
      } finally {
        manager.setStopServerOnClose(false);
        manager.close();
      }
    }
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
