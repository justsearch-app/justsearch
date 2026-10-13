/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.runtime.ManagedChildRegistry;
import io.justsearch.app.api.runtime.child.ChildKind;
import io.justsearch.app.api.runtime.child.ChildSpec;
import io.justsearch.app.api.runtime.child.DefaultChildControl;
import io.justsearch.app.api.runtime.child.StopReason;
import io.justsearch.app.api.runtime.child.VramProbe;
import io.justsearch.app.api.runtime.containment.EngineContainment;
import io.justsearch.app.inference.chat.ChatActuation;
import io.justsearch.app.inference.chat.ChatActuationRouter;
import io.justsearch.app.inference.chat.ChatActuator;
import io.justsearch.app.inference.chat.ChatDrainPolicy;
import io.justsearch.app.inference.chat.ChildSelfTestLauncher;
import io.justsearch.app.inference.chat.SelfTestSpec;
import io.justsearch.app.inference.telemetry.TransitionReason;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The real session releases its hold; the process owner still refuses the controller's reload. */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
final class SelfTestDescendantReloadTest {
  @TempDir Path tmp;

  @Test
  void closingRealSelfTestSessionCannotReloadBesideItsDescendant() throws Exception {
    TestEngineExecutors executors = new TestEngineExecutors();
    try (DefaultChildControl control = DefaultChildControl.openForEngine(tmp.resolve("data"),
        "session-descendant-test", ManagedChildRegistry.noop(), VramProbe.none(), EngineContainment.current())) {
      AtomicInteger mainSpawns = new AtomicInteger();
      ChatActuator actuator = new ChatActuator() {
        @Override public DrainHold drain(Duration bound) { return () -> {}; }
        @Override public DrainHold drain(ChatDrainPolicy policy) { return () -> {}; }
        @Override public <T> T execute(ChatActuation<T> step, DrainHold drained) throws Exception {
          return step.run(drained);
        }
        @Override public void terminalStop(StopReason reason, Duration timeout) { control.close(); }
        @Override public ChatActuation<Void> selfTestStop() {
          return ChatActuation.of(ChatActuation.Kind.SELF_TEST, drained -> null);
        }
        @Override public ChatActuation<Void> selfTestEnd() {
          return ChatActuation.of(ChatActuation.Kind.SELF_TEST_END, drained -> null);
        }
        @Override public ChatActuation<Void> activation(ChatActuationRouter.Activation activation) {
          return ChatActuation.of(activation.active() ? ChatActuation.Kind.ACTIVATE : ChatActuation.Kind.DEACTIVATE, drained -> {
            if (activation.active()) {
              control.start(new ChildSpec(ChildKind.LLAMA_SERVER,
                  SessionWrapperFixture.argv("leaf", tmp), null, Map.of(), new ChildSpec.Stdio.Pipes(),
                  false, OptionalLong.empty(), Optional.empty(), ChildSpec.CooperativeStop.NONE, "main-fixture"));
              mainSpawns.incrementAndGet();
            }
            return null;
          });
        }
      };
      var controller = new ChatServerControllerImpl(executors, actuator, new ChildSelfTestLauncher(control));
      try {
        try (var session = controller.beginSelfTest("runtime-variant-activation")) {
          session.run(new SelfTestSpec(SessionWrapperFixture.argv("wrapper", tmp), null, List.of(),
              tmp.resolve("self-test.log"), 12345, Duration.ofSeconds(20), new SelfTestSpec.Probe() {
                @Override public boolean healthy(int port) { return Files.exists(tmp.resolve("leaf.identity")); }
                @Override public void exercise(int port) throws Exception {
                  Files.writeString(tmp.resolve("wrapper.exit"), "exit");
                  long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                  while (control.recentExits(10).isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
                  assertFalse(control.recentExits(10).isEmpty(), "wrapper exit is published inside the successful probe");
                }
              }));
        }
        assertTrue(controller.openHolds().isEmpty(), "the real session has released the hold");
        var activation = new ChatActuationRouter.Activation(true, TransitionReason.AUTO_START);
        ExecutionException refused = assertThrows(ExecutionException.class,
            () -> controller.setActivation(activation).toCompletableFuture().get(15, TimeUnit.SECONDS),
            "a controller reload after real session close cannot start beside the surviving descendant");
        assertTrue(refused.getCause().getMessage().contains("descendant"), refused.toString());
        assertEquals(0, mainSpawns.get());
        releaseLeaf();
        controller.setActivation(activation).toCompletableFuture().get(15, TimeUnit.SECONDS);
        assertEquals(1, mainSpawns.get(), "the controller can retry after the descendant exits");
      } finally {
        releaseLeaf();
        controller.shutdown(StopReason.QUIT, Duration.ofSeconds(5));
      }
    } finally { executors.close(); }
  }

  private void releaseLeaf() throws Exception {
    Files.writeString(tmp.resolve("wrapper.exit"), "exit");
    Files.writeString(tmp.resolve("leaf.exit"), "exit");
    Path identity = tmp.resolve("leaf.identity");
    if (!Files.exists(identity)) return;
    long pid = Long.parseLong(Files.readString(identity).split("/")[0]);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    // Observation only: the fixture exits through its private file, never through a PID kill.
    while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(10);
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "our recorded leaf has exited");
  }
}
