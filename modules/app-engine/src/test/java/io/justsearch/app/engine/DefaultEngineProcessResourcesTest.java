/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.core.component.ComponentSpec;
import io.justsearch.core.component.ComponentSpec.ComposeCapability;
import io.justsearch.core.component.EngineComponentRegistry.ApplyAttempt;
import io.justsearch.core.context.EngineContext;
import io.justsearch.core.context.RetainedStateBudget;
import io.justsearch.core.execution.EngineExecutorRejectedException;
import io.justsearch.core.execution.EngineExecutorRegistry.Limits;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultEngineProcessResourcesTest {
  @Test
  void onePolicyFeedsAdmissionExecutorsAndComponentApplyAccounting() {
    RetainedStateBudget retained = new RetainedStateBudget();
    retained.declare("attempted-configurations", 2, "test");
    var policy = new EngineResourcePolicy(Map.of(
        "perContextLimit", 1,
        "aggregateLimit", 2,
        "retryAfterSeconds", 7,
        "foregroundThreads", 2,
        "foregroundQueue", 4,
        "backgroundThreads", 3,
        "backgroundQueue", 5,
        "timerRegistrations", 6,
        "directMemoryMiB", 8), retained);

    try (var resources = new DefaultEngineProcessResources(policy)) {
      assertSame(policy, resources.policy());
      assertSame(resources.admission(), resources.admission());
      assertSame(resources.admission(), resources.operationLeases());
      assertSame(resources.executors(), resources.executors());
      assertSame(resources.components(), resources.components());
      assertEquals(java.util.List.of(), resources.components().snapshot().components());

      assertEquals(2, resources.executors().maxConcurrentWork());
      assertEquals(7, resources.executors().retryAfterSeconds());
      assertEquals(new Limits(2, 4),
          resources.executors().limits(Kind.FOREGROUND));
      assertEquals(new Limits(3, 5),
          resources.executors().limits(Kind.BACKGROUND));

      var first = resources.admission().admit(context("a"), false);
      assertEquals(EngineAdmissionException.Reason.CONTEXT_LIMIT,
          assertThrows(EngineAdmissionException.class,
              () -> resources.admission().admit(context("a"), false)).reason());
      var second = resources.admission().admit(context("b"), false);
      assertEquals(EngineAdmissionException.Reason.ENGINE_LIMIT,
          assertThrows(EngineAdmissionException.class,
              () -> resources.admission().admit(context("c"), false)).reason());
      first.close();
      second.close();

      assertEquals(0, retained.snapshot().getFirst().count());
      var apply = assertInstanceOf(ApplyAttempt.Acquired.class, resources.components().tryApply());
      assertEquals(1, retained.snapshot().getFirst().count());
      apply.lease().close();
      assertEquals(0, retained.snapshot().getFirst().count());
    }
  }

  @Test
  void finalCloseIsIdempotentAndRejectsComponentAndExecutorWork() {
    var resources = new DefaultEngineProcessResources(policy());
    var registration = resources.executors().register(new EngineExecutorSpec(
        "owned", Kind.FOREGROUND, Mode.PLATFORM, 1, 1, 1));
    var executor = registration.open(runnable -> {
      Thread thread = new Thread(runnable, "process-resource-test");
      thread.setDaemon(true);
      return thread;
    });

    resources.close();
    resources.close();

    assertEquals(ApplyAttempt.Reason.CLOSED,
        assertInstanceOf(ApplyAttempt.Refused.class, resources.components().tryApply()).reason());
    assertThrows(IllegalStateException.class,
        () -> resources.components().register(new ComponentSpec("api", true, Set.of(),
            ComposeCapability.IN_PLACE, Duration.ZERO, 0)));
    assertEquals(EngineExecutorRejectedException.Reason.CLOSED,
        assertThrows(EngineExecutorRejectedException.class,
            () -> executor.submit(() -> {})).reason());
    assertEquals(EngineExecutorRejectedException.Reason.CLOSED,
        assertThrows(EngineExecutorRejectedException.class,
            () -> resources.executors().register(
                EngineExecutorSpec.virtual("after-close", Kind.FOREGROUND, 1))).reason());
  }

  @Test
  void refusedComponentCloseRetainsExecutorsUntilTheApplyLeaseDrains() throws Exception {
    try (var resources = new DefaultEngineProcessResources(policy())) {
      var apply = assertInstanceOf(ApplyAttempt.Acquired.class, resources.components().tryApply());
      try {
        assertThrows(IllegalStateException.class, resources::close);
        try (var registration = resources.executors().register(
            EngineExecutorSpec.virtual("retained-apply", Kind.FOREGROUND, 1))) {
          assertEquals(42, registration.openVirtual().submit(() -> 42).get());
        }
      } finally {
        apply.lease().close();
      }
      resources.close();
      assertEquals(ApplyAttempt.Reason.CLOSED,
          assertInstanceOf(ApplyAttempt.Refused.class, resources.components().tryApply()).reason());
      assertThrows(EngineExecutorRejectedException.class, () -> resources.executors().register(
          EngineExecutorSpec.virtual("after-retry", Kind.FOREGROUND, 1)));
    }
  }

  private static EngineResourcePolicy policy() {
    RetainedStateBudget retained = new RetainedStateBudget();
    retained.declare("attempted-configurations", 1, "test");
    return new EngineResourcePolicy(Map.of(
        "perContextLimit", 1,
        "aggregateLimit", 1,
        "retryAfterSeconds", 2,
        "foregroundThreads", 1,
        "foregroundQueue", 1,
        "backgroundThreads", 1,
        "backgroundQueue", 1,
        "timerRegistrations", 1,
        "directMemoryMiB", 1), retained);
  }

  private static EngineContext context(String client) {
    return new EngineContext(EngineContext.ClientKind.MCP_CLIENT, client, Optional.of("session"),
        Optional.empty(), "UNTRUSTED", "MCP", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.FOREGROUND);
  }
}
