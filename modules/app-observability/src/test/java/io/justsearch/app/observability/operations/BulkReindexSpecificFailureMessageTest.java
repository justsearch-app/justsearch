/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.operations;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

final class BulkReindexSpecificFailureMessageTest {
  @TempDir Path temp;
  private static final Clock CLOCK = Clock.systemUTC();
  private static final EngineContext CONTEXT = new EngineContext(EngineContext.ClientKind.INTERNAL,
      "refusal-specific-test", Optional.empty(), Optional.empty(), "system", "SYSTEM_INTERNAL",
      EngineContext.Survival.INTERACTIVE, EngineContext.Urgency.BACKGROUND);

  @ParameterizedTest
  @CsvSource({
      "core.activate-installed-models, ACTIVATION_PREVIEW_STALE, Installed activation candidate changed; request a fresh preview",
      "core.bulk-reindex, BULK_GENERATION_CONFLICT, Another bulk rebuild owns the index",
      "core.rebuild-index, FUTURE_FAILURE, A specific explanation from the operation"
  })
  void preservesSpecificGuidanceOutsideTheEightRefusals(String operation, String code, String message)
      throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("dispatch.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.REINDEX, operation, "{}", false), CONTEXT,
          InvocationProvenance.fromEngineContext(CONTEXT, ExecutorTag.UI, CLOCK.instant(), Optional.empty()),
          OperationHistoryMode.STANDARD);
      var first = runner.start(runner.accept(request), ignored -> OperationExecution.finished(
          OperationResult.failure(message, code, Map.of(), false)));
      assertEquals(message, first.response().message());
      assertEquals(Optional.of(code), first.response().errorCode());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"core.bulk-reindex", "core.rebuild-index", "core.activate-installed-models"})
  void activatedWithGapsDoesNotAcquireAFailureSentence(String operation) throws Exception {
    try (var store = new SqliteOperationStore(temp.resolve("gaps.db"))) {
      var runner = new OperationAttemptRunnerImpl(store, CLOCK, Set.of());
      var request = new OperationAttemptRunner.Request(OperationKeys.generate(CLOCK),
          OperationDescriptor.invocation(OperationKind.REINDEX, operation, "{}", false), CONTEXT,
          InvocationProvenance.fromEngineContext(CONTEXT, ExecutorTag.UI, CLOCK.instant(), Optional.empty()),
          OperationHistoryMode.STANDARD);
      var accepted = runner.accept(request);
      store.finish(accepted.accepted().id(), OperationState.FAILED,
          new OperationReceipt("PROMOTED_WITH_GAPS", null));
      var json = JsonMapper.builder().build();
      var outcome = json.valueToTree(store.outcome(request.key()));
      var history = json.valueToTree(new OperationHistoryStore(store).recent().getFirst());
      assertEquals("PROMOTED_WITH_GAPS", outcome.path("result").path("code").asText());
      assertEquals("PROMOTED_WITH_GAPS", history.path("diagnosticsLink").asText());
      assertTrue(outcome.path("message").isMissingNode() || outcome.path("message").isNull());
      assertTrue(history.path("message").isMissingNode() || history.path("message").isNull());
    }
  }
}
