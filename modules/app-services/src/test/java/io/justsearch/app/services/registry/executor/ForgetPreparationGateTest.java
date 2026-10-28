/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import io.justsearch.app.services.registry.operations.handlers.ForgetIndexedPathHandler;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** The production dispatcher, real preparation handler and trust evaluator, with a refusing owner. */
final class ForgetPreparationGateTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {
      "A watched folder or a folder containing one cannot be forgotten. Remove the folder in Library instead.",
      "Help and agent-history content is managed by its own data controls"})
  void refusedTargetReturnsItsReasonBeforeConsentOrOperationalAdmission(String reason) throws Exception {
    var clock = Clock.systemUTC();
    var context = EngineProvenance.context(EngineContext.ClientKind.WEBVIEW, "forget-gate-test",
        Optional.empty(), Optional.empty(), TransportTag.BUTTON, EngineContext.Survival.DURABLE,
        EngineContext.Urgency.BACKGROUND);
    var provenance = EngineProvenance.invocation(context, ExecutorTag.UI, clock.instant(), Optional.empty());
    var owner = mock(ForgetService.class);
    when(owner.prepare(anyString(), any(), any())).thenThrow(new IllegalArgumentException(reason));
    var handlers = new HandlerRegistry();
    var operation = new CoreOperationCatalog().findById(new OperationRef("core.forget-indexed-path")).orElseThrow();
    handlers.register(operation.id(), new ForgetIndexedPathHandler(() -> owner));
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var attempts = new OperationAttemptRunnerImpl(store, clock, Set.of());
      var admission = mock(EngineAdmissionService.class);
      var executor = new OperationExecutorImpl(attempts, admission, handlers, null, Map.of(), clock,
          new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, new ConsentCapsuleService());
      String key = OperationKeys.generate(clock);
      String arguments = JsonMapper.builder().build().writeValueAsString(
          Map.of("path", directory.resolve("protected").toString(), "scope", "FOLDER"));
      var response = assertDoesNotThrow(() -> executor.dispatch(operation, arguments, provenance,
          Optional.empty(), context, key), "a refused target must not produce a confirmation gate");
      assertFalse(response.success());
      assertEquals(reason, response.message());
      assertEquals("FORGET_PREPARATION_REFUSED", response.errorCode().orElseThrow());
      assertTrue(store.find(key).isEmpty(), "pure refusal precedes operational admission");
      verifyNoInteractions(admission);
      verify(owner, never()).execute(any(), any());
    }
  }
}
