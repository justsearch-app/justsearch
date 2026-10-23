/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.AgentEventPayloads;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RefusedToolExplanationTest {
  @TempDir Path temp;

  @Test
  void freshAndReloadedToolOutputsKeepTheSentenceAndCodeWithoutAnExtraField() throws Exception {
    Clock clock = Clock.systemUTC();
    var context = new EngineContext(EngineContext.ClientKind.INTERNAL, "refusal-test", Optional.empty(),
        Optional.empty(), "system", "SYSTEM_INTERNAL", EngineContext.Survival.INTERACTIVE,
        EngineContext.Urgency.BACKGROUND);
    String key;
    Map<String, Object> fresh;
    try (var store = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, clock, Set.of());
      key = OperationKeys.generate(clock);
      var request = new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.REINDEX, "core.bulk-reindex", "{}", false), context, null);
      var result = runner.start(runner.accept(request), ignored -> OperationExecution.finished(
          OperationResult.failure("Recorded ingestion could not complete", "BULK_GENERATION_REFUSED", Map.of(), false)));
      fresh = AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("call", result.response()));
      assertEquals("BULK_GENERATION_REFUSED", fresh.get("errorCode"));
    }
    try (var reopened = new SqliteOperationStore(temp.resolve("operations.db"))) {
      var runner = new OperationAttemptRunnerImpl(reopened, clock, Set.of());
      var request = new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.REINDEX, "core.bulk-reindex", "{}", false), context, null);
      var replay = runner.start(runner.accept(request), ignored -> {
        fail("A recorded refusal must not run the rebuild again"); return null;
      });
      var payload = AgentEventPayloads.base(new AgentEvent.ToolExecutionCompleted("call", replay.response()));
      var record = Map.<String, Object>of("timestamp", "2026-10-22T17:00:00Z",
          "eventType", "tool_exec_completed", "payload", payload);
      var thread = AgentInteractionMapper.fromRunEvent(record, "conversation").orElseThrow();
      assertEquals(payload.get("output"), thread.attributes().get("output"));
      assertAll(() -> assertExplanation(fresh.get("output")),
          () -> assertExplanation(thread.attributes().get("output")));
    }
  }

  private static void assertExplanation(Object output) {
    String text = String.valueOf(output);
    assertTrue(text.contains("could not safely start or resume the new index"), text);
    String detail = "(Code: BULK_GENERATION_REFUSED)";
    assertTrue(text.contains(detail), text);
    assertEquals(text.indexOf(detail), text.lastIndexOf(detail), text);
  }
}
