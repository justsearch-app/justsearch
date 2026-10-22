/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

final class BulkReindexFailureProjectionTest {
  @TempDir Path temp;
  private static final Clock CLOCK = Clock.systemUTC();
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "refusal-test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);
  private static final Map<String, String> CAUSES = Map.of(
      "cancelled", "cancelled before activation",
      "RECOVERY_AUTHORIZATION_REFUSED", "permissions or prepared model files",
      "RECOVERY_SCOPE_REFUSED", "source scope could not be confirmed",
      "RECOVERY_BINDING_INVALID", "saved request could not be validated",
      "BULK_GENERATION_REFUSED", "could not safely start or resume the new index",
      "BULK_CAPTURE_FAILED", "could not finish collecting files",
      "INGEST_RECOVERY_ATTEMPTS_EXHAUSTED", "too many recovery attempts",
      "ACTIVATION_PRECOMMIT_REFUSED", "before activating the new index and settings");

  static Stream<Arguments> refusals() {
    return Stream.of("core.bulk-reindex", "core.rebuild-index", "core.activate-installed-models")
        .flatMap(operation -> CAUSES.entrySet().stream()
            .map(refusal -> Arguments.of(operation, refusal.getKey(), refusal.getValue())));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("refusals")
  void everyRefusalExplainsItsCauseInHistoryAndOutcomeWithoutChangingStoredCodes(
      String operation, String code, String cause) throws Exception {
    Path database = temp.resolve("operations.db");
    final OperationRecord terminal;
    final tools.jackson.databind.JsonNode live;
    try (var store = new SqliteOperationStore(database)) {
      var row = accept(store, operation);
      terminal = store.finish(row.id(), code.equals("cancelled") ? OperationState.CANCELLED
          : OperationState.FAILED, new OperationReceipt(code, null)).orElseThrow();
      live = JSON.valueToTree(OperationHistoryProjection.entry(OperationHistoryRow.from(terminal)));
    }
    try (var reopened = new SqliteOperationStore(database)) {
      var history = JSON.valueToTree(new OperationHistoryStore(reopened).recent().getFirst());
      var outcome = JSON.valueToTree(reopened.outcome(terminal.key()));
      String message = history.path("message").asText();
      assertAll(
          () -> assertTrue(message.contains(cause), code + ": " + message),
          () -> assertEquals(live, history),
          () -> assertEquals(message, outcome.path("message").asText()),
          () -> assertEquals(code, history.path("diagnosticsLink").asText()),
          () -> assertEquals(code, outcome.path("reason").asText()),
          () -> assertEquals(code, outcome.path("result").path("code").asText()),
          () -> assertEquals(code, reopened.find(terminal.key()).orElseThrow().failureReason()),
          () -> assertEquals(code, reopened.find(terminal.key()).orElseThrow().receipt().code()),
          () -> assertFalse(message.contains("searchable"), "a refusal cannot establish source health or current availability"),
          () -> assertFalse(message.contains("unchanged"), "the source may have received normal indexing updates"),
          () -> {
            if (!code.equals("cancelled")) assertFalse(message.contains(code), "keep the support code separate");
            if (code.equals("BULK_GENERATION_REFUSED")) {
              assertFalse(message.contains("did not replace"), "the observed serving generation may have changed");
            } else {
              assertTrue(message.contains("this request did not replace the existing index"));
            }
          });
    }
  }

  @Test
  void firstResponseAndReplayAndAgentEventExplainTheSameFailureAndRetainTheCode() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("runner.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.REINDEX, "core.bulk-reindex", "{}", false), CONTEXT, null);
      var first = runner.start(runner.accept(request), ignored -> OperationExecution.finished(
          OperationResult.failure("Recorded ingestion could not complete", "BULK_GENERATION_REFUSED", Map.of(), false)));
      var replay = runner.start(runner.accept(request), ignored -> {
        fail("Replay cannot repeat the rebuild");
        return null;
      });
      var event = new io.justsearch.agent.api.AgentEvent.ToolExecutionCompleted("call", replay.response());
      var agentWire = io.justsearch.agent.api.AgentEventPayloads.base(event);
      String cause = "could not safely start or resume the new index";
      assertAll(
          () -> assertTrue(first.response().message().contains(cause)),
          () -> assertEquals(Optional.of("BULK_GENERATION_REFUSED"), first.response().errorCode()),
          () -> assertTrue(replay.response().message().contains(cause)),
          () -> assertEquals(first.response().message(), replay.response().message()),
          () -> assertEquals(first.response().errorCode(), replay.response().errorCode()),
          () -> assertTrue(String.valueOf(agentWire.get("output")).contains(cause)),
          () -> assertEquals("BULK_GENERATION_REFUSED", agentWire.get("errorCode")));
    }
  }

  @Test
  void otherOperationsNeverAcquireBulkSafetyAssurancesAndUnknownCodesStayVisible() throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("scope.db"))) {
      var other = accept(store, "core.reindex");
      store.finish(other.id(), OperationState.FAILED, new OperationReceipt("RECOVERY_SCOPE_REFUSED", null));
      assertTrue(JSON.valueToTree(store.outcome(other.key())).path("message").isMissingNode());
      var otherMessage = JSON.valueToTree(new OperationHistoryStore(store).recent().getFirst()).path("message");
      assertTrue(otherMessage.isMissingNode() || otherMessage.isNull());
      var unknown = accept(store, "core.rebuild-index");
      store.finish(unknown.id(), OperationState.FAILED, new OperationReceipt("FUTURE_FAILURE", null));
      var wire = JSON.valueToTree(store.outcome(unknown.key()));
      assertAll(
          () -> assertEquals("FUTURE_FAILURE", wire.path("result").path("code").asText()),
          () -> assertTrue(wire.path("message").asText().contains("did not complete")),
          () -> assertFalse(wire.path("message").asText().contains("searchable")));
    }
  }

  private static OperationRecord accept(SqliteOperationStore store, String operation) {
    return store.accept(OperationKeys.generate(CLOCK), OperationDescriptor.invocation(OperationKind.REINDEX,
        operation, "{}", false), CONTEXT, InvocationProvenance.fromEngineContext(CONTEXT,
            ExecutorTag.UI, Instant.now(), Optional.empty()), OperationHistoryMode.STANDARD).record();
  }
}
