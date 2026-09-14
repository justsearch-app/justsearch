package io.justsearch.app.services.bootstrap.phases;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.app.services.registry.operations.CoreOperationCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.io.TempDir;

class OperationSubstrateInitTest {
  @TempDir Path operationDirectory;

  @Test
  void committedDispatcherCompletionPublishesOnceThroughTheSourceHook() throws Exception {
    verifyDispatcherHistory(false);
  }

  @Test
  void failedCompletionPersistenceStillPublishesUnkeyedStorageFailure() throws Exception {
    verifyDispatcherHistory(true);
  }

  private void verifyDispatcherHistory(boolean refuseCompletion) throws Exception {
    var database = operationDirectory.resolve("operations.db");
    try (var store = new SqliteOperationStore(database);
        var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      if (refuseCompletion) {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement()) {
          statement.execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON operations WHEN NEW.state = 'COMPLETE' "
              + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
        }
      }
      var attempts = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of());
      var admission = mock(io.justsearch.app.api.EngineAdmissionService.class);
      when(admission.attach(any())).thenAnswer(call -> {
        var work = mock(io.justsearch.app.api.EngineWorkHandle.class);
        when(work.context()).thenReturn(call.getArgument(0));
        when(work.retain()).thenReturn(work);
        return work;
      });
      var id = new OperationRef("core.completion-write-test");
      var handlers = new HandlerRegistry();
      handlers.register(id, (args, context) -> OperationResult.success("ok"));
      var operation = new Operation(id,
          Presentation.of(new I18nKey("test.completion"), new I18nKey("test.completion.desc")),
          Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
          new OperationPolicy(RiskTier.LOW, ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY,
              RetryPolicy.noRetry(), Set.of(), false),
          OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
          new Provenance(TrustTier.CORE, "test", "1.0"), Set.of(ExecutorTag.AGENT));
      var out = OperationSubstrateInit.run(store, attempts, admission, executors, handlers,
          OperationCatalog.of("core", List.of(operation)), OperationCatalog.of("core", List.of()),
          req -> true, new io.justsearch.app.observability.surface.CoreSurfaceCatalog(),
          io.justsearch.agent.api.encryption.StoreCipher.disabled());
      var rollup = out.scanRollupLedger();
      try (rollup) {
        var projector = OperationSubstrateInit.attachHistoryProjection(store, executors, out);
        try (projector) {
          var observed = new CopyOnWriteArrayList<OperationHistoryEntry>();
          out.operationHistoryStore().addAppendListener(observed::add);
          if (refuseCompletion) {
            assertThrows(OperationStoreException.class, () -> out.operationExecutor().dispatch(operation,
                "{}", io.justsearch.app.services.TestEngineContexts.internal()));
            assertEquals(1, observed.size());
            assertEquals(OperationOutcome.FAILURE, observed.getFirst().outcome());
            assertEquals(Optional.of("STORAGE_FAILED"), observed.getFirst().diagnosticsLink());
            assertTrue(observed.getFirst().operationKey().isEmpty());
            assertTrue(store.recentHistory(10).isEmpty());
            assertEquals(OperationState.RUNNING, store.openRecords().getFirst().state());
          } else {
            out.operationExecutor().dispatch(operation, "{}", io.justsearch.app.services.TestEngineContexts.internal());
            assertEquals(1, observed.size());
            assertEquals(OperationOutcome.SUCCESS, observed.getFirst().outcome());
            assertTrue(observed.getFirst().operationKey().isPresent());
            assertEquals(1, store.recentHistory(10).size());
          }
        }
      }
    }
  }

  @Test
  void run_returnsFullyPopulatedOutputAndRegistersNavigateHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    OperationCatalog agentTools = OperationCatalog.of("core", List.of());

    OperationSubstrateInit.Output out =
        OperationSubstrateInit.run(mock(OperationStore.class), mock(OperationAttemptRunner.class), mock(io.justsearch.app.api.EngineAdmissionService.class), new io.justsearch.core.execution.TestEngineExecutors(),
            handlers,
            ops,
            agentTools,
            req -> true,
            new io.justsearch.app.observability.surface.CoreSurfaceCatalog(),
            io.justsearch.agent.api.encryption.StoreCipher.disabled());

    assertNotNull(out.operationHistoryResourceCatalog());
    assertNotNull(out.operationHistoryStore());
    assertNotNull(out.operationHistoryChangeRegistry());
    assertNotNull(out.advisoryClassRegistry());
    assertNotNull(out.advisoryChangeRegistry());
    assertNotNull(out.advisoryResourceCatalog());
    assertNotNull(out.advisoryLogs());
    assertNotNull(out.promptCatalog());
    assertNotNull(out.intentSourceCatalog());
    assertNotNull(out.operationExecutor());
    assertNotNull(out.capabilitiesChangeRegistry());
    assertNotNull(out.intentEnvelopeChangeRegistry());
    assertNotNull(out.backendIntentRouter());
    assertNotNull(out.healthRecoveryProjector());
    // Tempdoc 875 C.3: durable grants are never wired without the argument scope that bounds them —
    // the phase constructs both and setDurableGrantStore requires both, so this pins the wiring.
    assertNotNull(out.durableGrantStore());
    assertNotNull(out.durableGrantScope());

    // The phase function registers the navigate-to-surface handler as a side effect.
    assertTrue(
        handlers.resolve(CoreOperationCatalog.NAVIGATE_TO_SURFACE).isPresent(),
        "NAVIGATE_TO_SURFACE handler should be registered by the phase function");
  }

  @Test
  void runReusesPrebuiltAuthorityAndHardStopRevokesOnlyNonUserGrants() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationCatalog ops = OperationCatalog.of("core", List.of());
    OperationCatalog agentTools = OperationCatalog.of("core", List.of());
    OperationAuthority authority = OperationAuthority.inMemory();
    authority.grants().grantAllowAlways("core.authority-untrusted", SourceTier.UNTRUSTED);
    authority.grants().grantAllowAlways("core.authority-trusted", SourceTier.TRUSTED);
    String untrustedCapsule =
        authority.capsules().mint("core.authority-untrusted", "{}", SourceTier.UNTRUSTED);
    String trustedCapsule =
        authority.capsules().mint("core.authority-trusted", "{}", SourceTier.TRUSTED);

    try (var executors = new io.justsearch.core.execution.TestEngineExecutors()) {
      OperationSubstrateInit.Output out =
          OperationSubstrateInit.run(
              mock(OperationStore.class),
              mock(OperationAttemptRunner.class),
              mock(io.justsearch.app.api.EngineAdmissionService.class),
              executors,
              handlers,
              ops,
              agentTools,
              req -> true,
              new io.justsearch.app.observability.surface.CoreSurfaceCatalog(),
              io.justsearch.agent.api.encryption.StoreCipher.disabled(),
              authority);
      var rollup = out.scanRollupLedger();
      try (rollup) {
        OperationExecutorImpl executor =
            (OperationExecutorImpl) out.operationExecutor();
        assertSame(authority.sources(), out.intentSourceCatalog());
        assertSame(authority.capsules(), out.consentCapsuleService());
        assertSame(authority.hardStop(), out.globalHardStop());
        assertSame(authority.evaluator(), out.intentGateEvaluator());
        assertSame(authority.evaluator(), executor.intentGateEvaluator());
        assertSame(authority.grants(), out.durableGrantStore());
        assertSame(authority.scope(), out.durableGrantScope());

        assertEquals(
            GateBehavior.TYPED_CONFIRM,
            authority.evaluator().evaluate(RiskTier.MEDIUM, TransportTag.AGENT_LOOP).gateBehavior());
        assertEquals(
            GateBehavior.AUTO,
            authority.evaluator().evaluate(RiskTier.MEDIUM, TransportTag.BUTTON).gateBehavior());

        authority.hardStop().engage();
        assertTrue(authority.hardStop().isEngaged());
        assertEquals(
            GateBehavior.DENY,
            out.intentGateEvaluator().evaluate(RiskTier.MEDIUM, TransportTag.AGENT_LOOP).gateBehavior());
        assertEquals(
            GateBehavior.AUTO,
            out.intentGateEvaluator().evaluate(RiskTier.MEDIUM, TransportTag.BUTTON).gateBehavior());
        assertTrue(
            authority.grants().isAllowed(
                "core.authority-trusted", RiskTier.MEDIUM,
                io.justsearch.app.services.TestEngineContexts.ui()));
        assertFalse(
            authority.grants().isAllowed(
                "core.authority-untrusted", RiskTier.MEDIUM,
                io.justsearch.app.services.TestEngineContexts.agent()));
        assertTrue(
            authority.capsules().verifyAndConsume(
                trustedCapsule, "core.authority-trusted", "{}"));
        assertFalse(
            authority.capsules().verifyAndConsume(
                untrustedCapsule, "core.authority-untrusted", "{}"));
      }
    }
  }
}
