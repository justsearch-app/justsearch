/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.core.component.ComponentRecoveryAction;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import io.justsearch.core.execution.TestEngineExecutors;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Spec A §4.2 and §7.2 (and C9): the component monitor no longer admits generative retries. The
 * chat controller decides every automatic relaunch from the classified exit, so the generative
 * action the Head binds is operator-only: monitor ticks start no attempt and exhaust no budget,
 * and each operator retry (the recover endpoint) is one attempt (O6), however many came before.
 */
final class GenerativeRecoveryAdmissionTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @Test
  void theMonitorStartsNoGenerativeAttemptAndEachOperatorRetryIsOne() throws Exception {
    long[] now = {1_000};
    try (var components = new TestEngineComponents()) {
      var generative = components.register(new ComponentSpec("generative", false, Set.of(),
          ComponentSpec.ComposeCapability.IN_PLACE, Duration.ZERO, 2));
      generative.transition(ComponentState.FAILED, LifecycleReasonCode.INFERENCE_CRASHED.code(),
          "llama-server exited");
      var attempts = new AtomicInteger();
      var escalations = new AtomicInteger();
      InferenceLifecycleManager manager = mock(InferenceLifecycleManager.class);
      when(manager.recoverComponent(any(), any())).thenAnswer(invocation -> {
        ComponentRecoveryAction.Request request = invocation.getArgument(0);
        assertTrue(request.begin());
        attempts.incrementAndGet();
        var terminal = request.complete(generative.snapshot(), ComponentState.FAILED,
                LifecycleReasonCode.INFERENCE_CRASHED.code(), "relaunch failed")
            .orElseThrow(() -> new AssertionError("recovery completion was superseded"));
        return ComponentRecoveryAction.Result.failed(terminal);
      });
      ComponentRecoveryAction action = headActionFor(manager);

      var bootstrap = mock(KnowledgeServerBootstrap.class);
      when(bootstrap.hasClient()).thenReturn(false);
      try (var monitor = new KnowledgeServerHealthMonitor(processExecutors, bootstrap, 10_000L,
          () -> now[0], new BootRecoveryPolicy(5, 0, 0))) {
        monitor.componentRegistry(components);
        monitor.componentRecoveryBindings(
            Map.of("generative",
                new ComponentRecoveryBinding(components.handle("generative"), action)),
            ignored -> escalations.incrementAndGet());

        for (int tick = 0; tick < 4; tick++) {
          monitor.tick();
          await(() -> !monitor.recoveryAttemptRunningForTest());
          now[0] += 60_000;
        }
        assertEquals(0, attempts.get(), "the monitor starts no generative attempt on its own");
        assertNotEquals(LifecycleReasonCode.COMPONENT_RECOVERY_EXHAUSTED.code(),
            generative.snapshot().reasonCode(), "no budget is kept, so none is exhausted");

        for (int retry = 1; retry <= 3; retry++) {
          assertEquals(ComponentRecoveryAuthority.Outcome.ACCEPTED,
              monitor.requestComponentRecovery("generative"), "operator retry " + retry);
          int expected = retry;
          await(() -> attempts.get() == expected);
          await(() -> !monitor.recoveryAttemptRunningForTest());
        }
        assertEquals(3, attempts.get(), "each operator retry is one attempt, past the old two");
        assertEquals(0, escalations.get());
      }
    }
  }

  /** The production generative action, built by the Head around the given manager. */
  private static ComponentRecoveryAction headActionFor(InferenceLifecycleManager manager)
      throws Exception {
    HeadAssembly head = mock(HeadAssembly.class, CALLS_REAL_METHODS);
    Field field = HeadAssembly.class.getDeclaredField("inferenceManager");
    field.setAccessible(true);
    field.set(head, manager);
    return head.generativeRecoveryAction();
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
    assertTrue(condition.getAsBoolean(), "condition did not become true");
  }
}
