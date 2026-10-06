/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;

import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

@DisplayName("InferenceLifecycleManager shutdown")
final class InferenceLifecycleManagerShutdownTest {

  @Test
  void terminalStopEndsTheOwnedChildWhileUndrainedWorkHoldsLifecycleLock() throws Exception {
    var control = new RecordingChildControl();
    var manager = manager(control);
    var child = launchOwned(manager, control);
    try {
      synchronized (runner(manager).lock()) {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> manager.stopServerForTerminalShutdown(Duration.ofMillis(250)));
        assertTrue(child.exit().toCompletableFuture().isDone());
      }
      var stop = control.stops.getFirst();
      assertSame(child, stop.children().getFirst());
      assertEquals(StopReason.QUIT, stop.request().reason(), "no recorded reason: a plain quit");
      assertEquals("engine-shutdown", stop.request().requestedBy());
    } finally {
      manager.close();
    }
  }

  /** Spec B §2.8: the terminal stop carries the Engine's recorded reason and detail. */
  @Test
  void terminalStopCarriesTheRecordedEngineReason() throws Exception {
    var control = new RecordingChildControl();
    var manager = manager(control);
    var child = launchOwned(manager, control);
    var hang = StopRequest.of(StopReason.ENGINE_RESTART, StopMode.NOW, "engine-shutdown")
        .withDetail(StopDetail.HANG);
    try {
      manager.recordTerminalStop(hang);
      manager.recordTerminalStop(StopRequest.of(StopReason.QUIT, StopMode.NOW, "engine-shutdown"));
      manager.stopServerForTerminalShutdown(Duration.ofMillis(250));
      assertTrue(child.exit().toCompletableFuture().isDone());
      assertSame(hang, control.stops.getFirst().request(), "the first record wins");
    } finally {
      manager.close();
    }
  }

  @Test
  @SuppressWarnings("removal")
  void closeAlwaysStopsServer() {
    // Close always stops the server on the real manager close path.
    // ADR-0051 decision 2: every child ends with its Engine; a request to leave it running is
    // refused.
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.setStopServerOnClose(false);
      manager.close();

      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () -> verify(fakeServerOps).stopLlamaServer(Mockito.any()),
          "close must stop the server even after a request to leave it running");
    }
  }

  /** Spec B §2.8: with no Engine reason recorded, the stop on close is a quit. */
  @Test
  void closeStopsTheServerAsAQuit() {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class)) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(
          new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      manager.close();

      verify(fakeServerOps).stopLlamaServer(argThat(request -> request.reason() == StopReason.QUIT));
    }
  }

  @Test
  void ordinaryTerminalCloseStopsTheOwnedChildBeforeWaitingForTheLifecycleLock() throws Exception {
    var control = new RecordingChildControl();
    var manager = manager(control);
    var runner = runner(manager);
    var child = launchOwned(manager, control);
    var close = new java.util.concurrent.FutureTask<Void>(() -> { manager.close(); return null; });
    try {
      synchronized (runner.lock()) {
        Thread.ofPlatform().daemon(true).name("test-terminal-close").start(close);
        var exit = child.exit().toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(StopReason.QUIT, exit.reason().orElseThrow());
        assertFalse(close.isDone(), "dependency closure must still respect the transition lock");
      }
      close.get(2, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      manager.close();
    }
  }

  @Test
  void terminalFenceIsAppliedToTheRealLaunchPath() throws Exception {
    var control = new RecordingChildControl();
    var manager = manager(control);
    var server = serverOps(manager);
    try {
      manager.stopServerForTerminalShutdown(Duration.ofMillis(25));
      var start = LlamaServerOps.class.getDeclaredMethod("startManagedChild",
          ChildSpec.class, Path.class, LlamaServerOps.StartResult.class, int.class);
      start.setAccessible(true);
      var result = new LlamaServerOps.StartResult(
          new LlamaServerConfigContext(fakeConfig(), ResolvedConfig.builder().build()),
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
          "declared");
      var refused = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> start.invoke(server, spec(), Path.of("llama.log"), result, 9991));
      assertInstanceOf(java.io.IOException.class, refused.getCause());
      assertTrue(control.started.isEmpty(), "the fenced launch never reaches ChildControl.start");
    } finally {
      manager.close();
    }
  }

  @Test
  @DisplayName("a failed terminal pre-stop still runs ordinary cleanup before it is reported")
  void failedTerminalPreStopStillClosesManagerResources() {
    try (MockedConstruction<LlamaServerOps> construction =
        Mockito.mockConstruction(LlamaServerOps.class, (ops, context) ->
            Mockito.doThrow(new IllegalStateException("child survived the deadline"))
                .when(ops).stopServerForTerminalShutdown(Mockito.any(), Mockito.any()))) {
      InferenceLifecycleManager manager = new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(), fakeConfig());
      LlamaServerOps fakeServerOps = construction.constructed().getFirst();

      var failure = assertThrows(IllegalStateException.class, manager::close);

      assertTrue(failure.getMessage().contains("child survived the deadline"), failure.getMessage());
      // Ordinary cleanup ran despite the pre-stop failure.
      verify(fakeServerOps).stopLlamaServer(Mockito.any());
      verify(fakeServerOps).shutdown();
    }
  }

  private static InferenceLifecycleManager manager(ChildControl control) {
    return new InferenceLifecycleManager(new io.justsearch.core.execution.TestEngineExecutors(),
        fakeConfig(), NoopInferenceTelemetryEvents.INSTANCE, control, null, ignored -> () -> {});
  }

  private static TransitionRunner runner(InferenceLifecycleManager manager) throws Exception {
    var runnerField = InferenceLifecycleManager.class.getDeclaredField("runner");
    runnerField.setAccessible(true);
    return (TransitionRunner) runnerField.get(manager);
  }

  private static LlamaServerOps serverOps(InferenceLifecycleManager manager) throws Exception {
    var serverField = InferenceLifecycleManager.class.getDeclaredField("serverOps");
    serverField.setAccessible(true);
    return (LlamaServerOps) serverField.get(manager);
  }

  /** A server launched through the manager's own ownership, as {@code startManagedChild} does. */
  private static RecordingChildControl.Child launchOwned(
      InferenceLifecycleManager manager, RecordingChildControl control) throws Exception {
    var server = serverOps(manager);
    var ownershipField = LlamaServerOps.class.getDeclaredField("processOwnership");
    ownershipField.setAccessible(true);
    var ownership = (LlamaServerProcessOwnership) ownershipField.get(server);
    var child = control.child(ChildKind.LLAMA_SERVER);
    ownership.launch(() -> child);
    var childField = LlamaServerOps.class.getDeclaredField("child");
    childField.setAccessible(true);
    childField.set(server, child);
    return child;
  }

  private static ChildSpec spec() {
    return new ChildSpec(ChildKind.LLAMA_SERVER, List.of("missing-llama-server.exe"), null,
        Map.of(), new ChildSpec.Stdio.AppendToLog(Path.of("llama.log")), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "llama-server");
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
