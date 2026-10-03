/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

@DisplayName("InferenceLifecycleManager shutdown")
final class InferenceLifecycleManagerShutdownTest {

  @ParameterizedTest
  @ValueSource(strings = {"process", "adoptedManagedHandle", "unregisteredRollbackProcess"})
  void terminalStopTerminatesOwnedChildWhileUndrainedWorkHoldsLifecycleLock(String ownedField)
      throws Exception {
    var manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    var runnerField = InferenceLifecycleManager.class.getDeclaredField("runner");
    runnerField.setAccessible(true);
    var runner = (TransitionRunner) runnerField.get(manager);
    var serverField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    serverField.setAccessible(true);
    var server = (LlamaServerOps) serverField.get(manager);
    var handle = mock(ProcessHandle.class);
    var exited = new CompletableFuture<ProcessHandle>();
    when(handle.isAlive()).thenAnswer(ignored -> !exited.isDone());
    when(handle.onExit()).thenReturn(exited);
    when(handle.destroy()).thenReturn(false);
    when(handle.destroyForcibly()).thenAnswer(ignored -> { exited.complete(handle); return true; });
    var field = LlamaServerOps.class.getDeclaredField(ownedField);
    field.setAccessible(true);
    if (ownedField.equals("adoptedManagedHandle")) field.set(server, handle);
    else {
      var process = mock(Process.class);
      when(process.toHandle()).thenReturn(handle);
      field.set(server, process);
    }
    try {
      synchronized (runner.lock()) {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> manager.stopServerForTerminalShutdown(Duration.ofMillis(250)));
        assertFalse(handle.isAlive());
        verify(handle).destroy();
        verify(handle).destroyForcibly();
      }
    } finally {
      field.set(server, null);
      manager.setStopServerOnClose(false);
      manager.close();
    }
  }

  @Test
  @DisplayName("the close directive controls the real manager close path")
  void closeDirectiveControlsServerStop() {
    assertCloseStopsServer(true);
    assertCloseLeavesServerRunning(false);
  }

  @Test
  void ordinaryTerminalCloseStopsTheOwnedChildBeforeWaitingForTheLifecycleLock() throws Exception {
    var manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    var runnerField = InferenceLifecycleManager.class.getDeclaredField("runner");
    runnerField.setAccessible(true);
    var runner = (TransitionRunner) runnerField.get(manager);
    var serverField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    serverField.setAccessible(true);
    var server = (LlamaServerOps) serverField.get(manager);
    var handle = mock(ProcessHandle.class);
    var exited = new CompletableFuture<ProcessHandle>();
    when(handle.isAlive()).thenAnswer(ignored -> !exited.isDone());
    when(handle.onExit()).thenReturn(exited);
    when(handle.destroy()).thenAnswer(ignored -> { exited.complete(handle); return true; });
    var process = mock(Process.class);
    when(process.toHandle()).thenReturn(handle);
    var field = LlamaServerOps.class.getDeclaredField("process");
    field.setAccessible(true);
    field.set(server, process);
    var close = new java.util.concurrent.FutureTask<Void>(() -> { manager.close(); return null; });
    try {
      synchronized (runner.lock()) {
        Thread.ofPlatform().daemon(true).name("test-terminal-close").start(close);
        assertTrue(exited.get(2, java.util.concurrent.TimeUnit.SECONDS) == handle);
        assertFalse(handle.isAlive());
        assertFalse(close.isDone(), "dependency closure must still respect the transition lock");
      }
      close.get(2, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      field.set(server, null);
      manager.setStopServerOnClose(false);
      manager.close();
    }
  }

  @Test
  void terminalFenceIsAppliedToTheRealProcessBuilderPath() throws Exception {
    var manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
    var serverField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    serverField.setAccessible(true);
    var server = (LlamaServerOps) serverField.get(manager);
    var builder = mock(ProcessBuilder.class);
    try {
      manager.stopServerForTerminalShutdown(Duration.ofMillis(25));
      var start = LlamaServerOps.class.getDeclaredMethod("startManagedProcess",
          ProcessBuilder.class, Path.class, java.util.List.class, LlamaServerOps.StartResult.class);
      start.setAccessible(true);
      var refused = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> start.invoke(server, builder, Path.of("llama.log"), java.util.List.of(), null));
      assertInstanceOf(java.io.IOException.class, refused.getCause());
      verify(builder, never()).start();
    } finally {
      manager.setStopServerOnClose(false);
      manager.close();
    }
  }

  private static void assertCloseStopsServer(boolean stop) {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.setStopServerOnClose(stop);
      manager.close();

      verify(fakeServerOps).stopLlamaServer();
    }
  }

  private static void assertCloseLeavesServerRunning(boolean stop) {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.setStopServerOnClose(stop);
      manager.close();

      verify(fakeServerOps, never()).stopLlamaServer();
    }
  }

  private static InferenceConfig fakeConfig() {
    return new InferenceConfig(
        Path.of("missing-llama-server.exe"),
        Path.of("missing-model.gguf"),
        null,
        9991,
        4096,
        0,
        false);
  }
}
