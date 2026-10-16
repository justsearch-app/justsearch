/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.Mode;
import io.justsearch.app.api.runtime.child.*;
import io.justsearch.app.inference.telemetry.NoopInferenceTelemetryEvents;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

final class LlamaStopVerificationTest {
  @Test
  void exitedRootWithUnreadableDescendantsIsUnverifiedAndLaterStopRecovers() throws Exception {
    AtomicBoolean unreadable = new AtomicBoolean(true);
    try (var control = DefaultChildControl.unrecorded(ManagedChildRegistry.noop())) {
      var argv = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", Path.of(IdleRoot.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
          IdleRoot.class.getName());
      ChildHandle real = control.start(new ChildSpec(ChildKind.LLAMA_SERVER, argv, null, Map.of(),
          new ChildSpec.Stdio.Pipes(), false, OptionalLong.empty(), Optional.empty(),
          ChildSpec.CooperativeStop.NONE, "stop-report-check"));
      ChildHandle observed = mock(ChildHandle.class);
      when(observed.pid()).thenReturn(real.pid());
      when(observed.processStartedAt()).thenReturn(real.processStartedAt());
      when(observed.state()).thenAnswer(invocation -> real.state());
      when(observed.exit()).thenReturn(real.exit());
      when(observed.descendants()).thenAnswer(invocation -> {
        if (unreadable.get()) throw new IllegalStateException("transient membership query failure");
        return real.descendants();
      });
      ChildControl reports = mock(ChildControl.class);
      when(reports.stop(eq(observed), any(StopRequest.class)))
          .thenAnswer(invocation -> control.stop(real, invocation.getArgument(1)));
      var ops = new LlamaServerOps(new InferenceExecutorRegistrations(
          new io.justsearch.core.execution.TestEngineExecutors()), HttpClient.newHttpClient(),
          new tools.jackson.databind.ObjectMapper(), null, () -> Mode.ONLINE,
          mock(PropsObserver.class), guard -> {}, (message, guard) -> {},
          NoopInferenceTelemetryEvents.INSTANCE, reports);
      var config = new InferenceConfig(Path.of("llama-server"), Path.of("model.gguf"),
          null, 1, 4096, 0, false);
      var context = new LlamaServerConfigContext(config, TestResolvedConfigHelper.fromEntries(Map.of()));
      var start = new LlamaServerOps.StartResult(context,
          LlamaServerOps.AdoptionPolicy.REQUIRE_MANAGED_CONFIG_WITNESS,
          LlamaServerOps.StartDisposition.LAUNCHED_MANAGED, "stop-report-fixture");
      LlamaServerTestAccess.installChild(ops, start, observed);
      try {
        IllegalStateException report = assertThrows(IllegalStateException.class,
            () -> ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP));
        real.exit().toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(ChildHandle.State.EXITED, real.state());
        assertTrue(report.getMessage().contains("exited, descendants unverified"),
            "confirmed exit must not be reported as survival: " + report.getMessage());
        unreadable.set(false);
        assertDoesNotThrow(() -> ops.stopLlamaServer(LlamaServerTestAccess.TEST_STOP));
        var held = LlamaServerOps.class.getDeclaredField("child");
        held.setAccessible(true);
        assertNull(held.get(ops), "later verified cleanup releases the retained handle");
        assertTrue(ops.activeStartResult().isEmpty());
      } finally {
        unreadable.set(false);
        ops.shutdown();
      }
    }
  }

  /** Root ownership/birth is recorded by the real control; only its native handle is stopped. */
  public static final class IdleRoot {
    private IdleRoot() {}
    public static void main(String[] args) throws Exception { System.in.readAllBytes(); }
  }
}
