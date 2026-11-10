/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.Mode;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.runtime.child.ChildControl;
import io.justsearch.app.api.runtime.child.ChildHandle;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ExitClass;
import io.justsearch.app.api.runtime.child.TerminateResult;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.ExitOutcome;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Callers consume an already-exited registered-start result through their normal failure path. */
@Timeout(30)
final class RegistrationExitCallersTest {
  @TempDir Path tmp;

  @Test
  void chatFirstLaunchAndRelaunchKeepTheEarlyExitDiagnosticAndLog() throws Exception {
    assertChatEarlyExit(exitedControl(), null, 23);
  }

  @Test
  void realChatFirstLaunchAndRelaunchWaitForRegistrationExitPublication() throws Exception {
    try (var fixture = new io.justsearch.app.api.runtime.child.RegistrationPublicationFixture(tmp,
        "main: exiting due to an unexpected error")) {
      assertChatEarlyExit(fixture.control, fixture, 37);
      assertEquals(2, fixture.exitRecords.get());
      assertEquals(2, fixture.real.recentExits(32).size());
    }
  }

  private void assertChatEarlyExit(ChildControl control,
      io.justsearch.app.api.runtime.child.RegistrationPublicationFixture fixture, int code) throws Exception {
    String diagnostic = "main: exiting due to an unexpected error";
    AtomicInteger launches = new AtomicInteger();
    AtomicReference<Path> launchLog = new AtomicReference<>();
    ChildHandle child = exitedChild();
    if (fixture == null) when(control.start(any(), any())).thenAnswer(call -> {
      ChildSpec spec = call.getArgument(0);
      Path log = ((ChildSpec.Stdio.AppendToLog) spec.stdio()).file();
      Files.writeString(log, diagnostic + "\n");
      launchLog.set(log);
      launches.incrementAndGet();
      return child;
    });
    Path chatDir = fixture == null ? tmp : Files.createTempDirectory(tmp, "chat-");
    Path executable = HealthyFakeLlamaServer.install(chatDir.resolve("fake-llama"));
    Path model = Files.createFile(chatDir.resolve("model.gguf"));
    var resolved = ResolvedConfig.builder()
        .putDefault("justsearch.data.dir", chatDir.resolve("data").toString())
        .putSettings("justsearch.context.size", "4096").build();
    PropsObserver observer = mock(PropsObserver.class);
    var ops = new LlamaServerOps(
        new InferenceExecutorRegistrations(new io.justsearch.core.execution.TestEngineExecutors()),
        HttpClient.newHttpClient(), new ObjectMapper(), null, () -> Mode.OFFLINE, observer,
        ignored -> {}, (ignored, guard) -> {}, NoopInferenceTelemetryEvents.INSTANCE, control);
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
          port = socket.getLocalPort();
        }
        var config = new InferenceConfig(executable, model, null, port, 4096, 0, false);
        LlamaServerOps.StartRequest request = new LlamaServerOps.StartRequest(
            new LlamaServerConfigContext(config, resolved),
            LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS);
        LlamaServerOps.StartResult start;
        if (fixture == null) {
          start = assertDoesNotThrow(() -> ops.startLlamaServer(request));
        } else {
          try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> ops.startLlamaServer(request));
            assertTrue(fixture.publishing.await(10, java.util.concurrent.TimeUnit.SECONDS));
            boolean returnedBeforePublication;
            try {
              returnedBeforePublication = fixture.returned.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
              fixture.release();
            }
            start = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertFalse(returnedBeforePublication, "chat registration returned without its exit outcome");
            launchLog.set(chatDir.resolve("data/logs/llama-server.log"));
          }
        }
        ModeTransitionException failure = assertThrows(ModeTransitionException.class,
            () -> ops.waitForServerHealth(start));
        assertEquals(ModeTransitionException.Reason.PROCESS_EXITED, failure.reason());
        assertEquals("START_FAILED", assertInstanceOf(LlamaServerExitException.class, failure).failedCode().toString());
        assertTrue(failure.getMessage().contains("exit code " + code), failure.getMessage());
        assertTrue(failure.getMessage().contains(launchLog.get().toString()), failure.getMessage());
        assertTrue(Files.readString(launchLog.get()).contains(diagnostic));
        assertFalse(ops.activeManagedCandidateAlive(start));
        ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
        if (fixture != null) fixture.nextLaunch();
      }
      if (fixture == null) assertEquals(2, launches.get());
      verifyNoInteractions(observer);
    } finally {
      ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP);
      ops.shutdown();
    }
  }

  @Test
  void selfTestReportsTheActualEarlyExitAndNeverProbesOrPasses() throws Exception {
    ChildControl control = exitedControl();
    ChildHandle child = exitedChild();
    when(control.start(any(), any())).thenReturn(child);
    SelfTestSpec.Probe probe = mock(SelfTestSpec.Probe.class);
    var run = new ChildSelfTestLauncher(control).run(new SelfTestSpec(
        List.of("llama-server"), tmp, List.of(), tmp.resolve("self-test.log"),
        8082, Duration.ofSeconds(1), probe));
    assertFalse(run.exercised());
    assertTrue(run.failure().contains("exit code 23"), run.failure());
    verifyNoInteractions(probe);
  }

  @Test
  void realSelfTestCannotPassWhileRegistrationExitPublicationIsPending() throws Exception {
    try (var fixture = new io.justsearch.app.api.runtime.child.RegistrationPublicationFixture(tmp,
        "self-test startup failure"); var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
      SelfTestSpec.Probe probe = mock(SelfTestSpec.Probe.class);
      when(probe.healthy(anyInt())).thenReturn(true);
      var exercised = new java.util.concurrent.CountDownLatch(1);
      doAnswer(call -> { exercised.countDown(); return null; }).when(probe).exercise(anyInt());
      var result = executor.submit(() -> new ChildSelfTestLauncher(fixture.control).run(new SelfTestSpec(
          fixture.command("self-test startup failure"), tmp, List.of(), tmp.resolve("real-self-test.log"),
          8082, Duration.ofSeconds(1), probe)));
      assertTrue(fixture.publishing.await(10, java.util.concurrent.TimeUnit.SECONDS));
      boolean returnedBeforePublication;
      try {
        returnedBeforePublication = fixture.returned.await(200, java.util.concurrent.TimeUnit.MILLISECONDS);
        if (returnedBeforePublication) assertTrue(exercised.await(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        fixture.release();
      }
      var run = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertAll(
          () -> assertFalse(returnedBeforePublication, "registration returned before its exit outcome"),
          () -> assertFalse(run.exercised(), "self-test must never pass: " + run),
          () -> assertTrue(run.failure() != null && run.failure().contains("exit code 37"), run.failure()),
          () -> verifyNoInteractions(probe),
          () -> assertEquals(1, fixture.exitRecords.get()),
          () -> assertEquals(1, fixture.real.recentExits(32).size()));
    }
  }

  private static ChildControl exitedControl() throws InterruptedException {
    ChildControl control = mock(ChildControl.class);
    when(control.stop(any(), any())).thenReturn(CompletableFuture.completedFuture(exit()));
    when(control.awaitGpuRelease(any())).thenReturn(true);
    return control;
  }

  private static ChildHandle exitedChild() {
    ChildHandle child = mock(ChildHandle.class);
    when(child.childId()).thenReturn("registration-exit");
    when(child.pid()).thenReturn(Long.MAX_VALUE);
    when(child.state()).thenReturn(ChildHandle.State.EXITED);
    when(child.exit()).thenReturn(CompletableFuture.completedFuture(exit()));
    when(child.descendants()).thenReturn(List.of());
    return child;
  }

  private static ExitOutcome exit() {
    return new ExitOutcome("registration-exit", ChildKind.LLAMA_SERVER, ExitClass.UNEXPECTED,
        Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.of(23), Optional.empty(),
        Instant.now(), Optional.empty(), Optional.empty(), TerminateResult.NOT_CALLED, false,
        ExitOutcome.Phase.STARTING, true);
  }
}
