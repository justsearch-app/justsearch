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

import io.justsearch.app.api.runtime.ManagedChild;
import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.StopDetail;
import io.justsearch.app.api.runtime.child.StopMode;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.StopRequest;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

@DisplayName("InferenceLifecycleManager shutdown")
final class InferenceLifecycleManagerShutdownTest {

  /**
   * The terminal stop ends every owned server while undrained work holds the lifecycle lock: one
   * that launched, and one whose manifest registration was refused and whose ROLLBACK stop left
   * it alive (a real process under a real {@code DefaultChildControl} whose kill failed until now).
   */
  @ParameterizedTest
  @ValueSource(strings = {"launched", "unregisteredRollbackProcess"})
  void terminalStopEndsTheOwnedChildWhileUndrainedWorkHoldsLifecycleLock(
      String owned, @TempDir Path tmp) throws Exception {
    if (owned.equals("unregisteredRollbackProcess")) {
      terminalStopEndsTheRollbackSurvivor(tmp);
      return;
    }
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

  private static void terminalStopEndsTheRollbackSurvivor(Path tmp) throws Exception {
    var terminator = new SurvivingTerminator();
    terminator.survive = true;
    var refusing = new ManagedChildRegistry() {
      @Override
      public List<ManagedChild> snapshot() {
        return List.of();
      }

      @Override
      public void register(ManagedChild child) throws IOException {
        throw new IOException("could not register managed llama-server (disk full)");
      }

      @Override
      public void remove(String childId) {}
    };
    var control = DefaultChildControl.of((type, fields) -> true, terminator, refusing);
    var manager = manager(control);
    try {
      var refused = assertThrows(java.lang.reflect.InvocationTargetException.class,
          () -> startManagedChild(serverOps(manager), sleepingSpec(tmp)));
      assertInstanceOf(IOException.class, refused.getCause());
      var survivor = terminator.alive();
      assertEquals(1, survivor.size(), "the rollback stop left the server alive");
      terminator.survive = false;
      synchronized (runner(manager).lock()) {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
            () -> manager.stopServerForTerminalShutdown(Duration.ofMillis(250)));
        assertFalse(survivor.getFirst().isAlive(),
            "the terminal stop ends the unregistered rollback survivor");
      }
    } finally {
      terminator.killAll();
      manager.close();
      control.close();
    }
  }

  private static void startManagedChild(LlamaServerOps server, ChildSpec spec) throws Exception {
    var start = LlamaServerOps.class.getDeclaredMethod("startManagedChild",
        ChildSpec.class, Path.class, LlamaServerOps.StartResult.class, int.class);
    start.setAccessible(true);
    var result = new LlamaServerOps.StartResult(
        new LlamaServerConfigContext(fakeConfig(), ResolvedConfig.builder().build()),
        LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
        LlamaServerOps.StartDisposition.LAUNCHED_MANAGED,
        "declared");
    start.invoke(server, spec, Path.of("llama.log"), result, 9991);
  }

  /** A real, long-lived stand-in llama-server: ManagedLlamaAdoptionTest's sleeping child. */
  private static ChildSpec sleepingSpec(Path tmp) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin",
        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    String classes = Path.of(ManagedLlamaAdoptionTest.SleepingChild.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI()).toString();
    return new ChildSpec(ChildKind.LLAMA_SERVER,
        List.of(java, "-cp", classes, ManagedLlamaAdoptionTest.SleepingChild.class.getName()),
        null, Map.of(), new ChildSpec.Stdio.AppendToLog(tmp.resolve("llama.log")), false,
        OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "llama-server");
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
      assertInstanceOf(IOException.class, refused.getCause());
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
