/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.core.context.EngineContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 879 — the tool-retry loop is driven by the operation's own {@link RetryPolicy}
 * declaration, not by a hard-coded risk check.
 *
 * <p>The acceptance criterion these tests encode: flipping the declaration flips the behaviour.
 * Before 879 the loop gated on {@code risk == RiskTier.LOW} with a fixed count of 1, so a
 * {@code noRetry()} LOW operation was retried anyway and an {@code autoRetry(2, …)} one still got a
 * single attempt — the declaration could not be made to agree with the runtime. The MEDIUM-risk
 * case below is the precision assertion: it fails if the gate is still risk-shaped, and it cannot
 * pass "for the wrong reason" the way an all-LOW fixture could.
 */
@DisplayName("AgentToolDispatcher retry is declaration-driven")
final class AgentToolDispatcherRetryTest {

  /** Counts dispatches and always throws, so every attempt is observable. */
  private static final class ThrowingDispatcher implements OperationDispatcher {
    final AtomicInteger dispatches = new AtomicInteger();

    @Override
    public OperationResult dispatch(Operation op, String argumentsJson, EngineContext engineContext) {
      dispatches.incrementAndGet();
      throw new IllegalStateException("transient failure");
    }

    @Override
    public OperationResult dispatch(
        Operation op,
        String argumentsJson,
        io.justsearch.agent.api.registry.InvocationProvenance provenance,
        Optional<String> confirmationToken,
        EngineContext engineContext) {
      return dispatch(op, argumentsJson, engineContext);
    }

    @Override
    public OperationResult undo(Operation op, String executionId, EngineContext engineContext) {
      return OperationResult.failure("undo not supported");
    }

    @Override
    public OperationResult undo(
        Operation op,
        String executionId,
        io.justsearch.agent.api.registry.InvocationProvenance provenance,
        Optional<String> confirmationToken,
        EngineContext engineContext) {
      return undo(op, executionId, engineContext);
    }
  }

  private static Operation operation(RiskTier risk, RetryPolicy retry) {
    OperationRef ref = new OperationRef("core.retry-probe");
    return new Operation(
        ref,
        new Presentation(
            new I18nKey("test.retry-probe.label"),
            new I18nKey("test.retry-probe.description"),
            Optional.empty(),
            Optional.empty()),
        Interface.of("{\"type\":\"object\",\"properties\":{}}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            risk, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE, retry, Set.of(), false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(ref),
        Provenance.core("1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static int dispatchCount(Operation op) {
    ThrowingDispatcher dispatcher = new ThrowingDispatcher();
    AgentToolDispatcher subject =
        new AgentToolDispatcher(
            dispatcher, AgentTelemetry.noop(), () -> null, () -> null, () -> null);

    OperationResult result =
        subject.executeOperationWithPolicy(
            op,
            new ToolCallRequest("call_1", "core_retry_probe", "{}"),
            "session-1",
            EngineContextTestFixtures.AGENT_LOOP, null);

    assertFalse(result.success(), "an always-throwing handler must surface as a failure result");
    return dispatcher.dispatches.get();
  }

  @Test
  @DisplayName("noRetry() is dispatched exactly once")
  void noRetryDeclarationIsDispatchedOnce() {
    assertEquals(1, dispatchCount(operation(RiskTier.LOW, RetryPolicy.noRetry())));
  }

  @Test
  @DisplayName("autoRetry(2, …) is dispatched exactly three times (initial + 2 retries)")
  void autoRetryDeclarationBoundsTheReplayCount() {
    assertEquals(
        3, dispatchCount(operation(RiskTier.LOW, RetryPolicy.autoRetry(2, "core.retry-probe"))));
  }

  @Test
  @DisplayName("autoRetry at MEDIUM risk still retries — the gate is the declaration, not the tier")
  void autoRetryIsHonouredAboveLowRisk() {
    assertEquals(
        3, dispatchCount(operation(RiskTier.MEDIUM, RetryPolicy.autoRetry(2, "core.retry-probe"))));
  }
}
