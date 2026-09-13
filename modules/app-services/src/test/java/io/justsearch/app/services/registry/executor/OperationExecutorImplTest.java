package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.AuditPolicy;
import io.justsearch.agent.api.registry.Binding;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.TrustGateDeniedException;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.IntentSourceCatalog;
import io.justsearch.agent.api.registry.HandlerRegistry;
import io.justsearch.agent.api.registry.I18nKey;
import io.justsearch.agent.api.registry.Interface;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationRef;
import io.justsearch.agent.api.registry.OperationPolicy;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationAvailability;
import io.justsearch.agent.api.registry.OperationLineage;
import io.justsearch.agent.api.registry.ResourceRef;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.Presentation;
import io.justsearch.agent.api.registry.Provenance;
import io.justsearch.agent.api.registry.RequiredCapability;
import io.justsearch.agent.api.registry.RetryPolicy;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.TrustTier;
import io.justsearch.agent.api.registry.TrustEvaluator;
import io.justsearch.core.context.EngineContext;
import io.justsearch.app.observability.advisory.OperationCompletionEvent;
import io.justsearch.app.services.intent.ConsentCapsuleService;
import io.justsearch.app.services.intent.CoreIntentSourceCatalog;
import io.justsearch.app.services.intent.CoreTrustEvaluator;
import io.justsearch.app.observability.operations.OperationHistoryEntry;
import io.justsearch.app.observability.operations.OperationOutcome;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OperationExecutorImpl} per tempdoc 429 §A.5 + §B.D + §F.7 closure.
 *
 * <p>Verifies the three-branch dispatch (CORE / TRUSTED_PLUGIN equivalent / UNTRUSTED
 * throws) and the undo-gating discipline per §E.3 (executor checks
 * {@code op.policy().undoSupported()} before delegating).
 */
final class OperationExecutorImplTest {

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path operationDirectory;
  private io.justsearch.app.observability.operations.SqliteOperationStore operationStore;
  private io.justsearch.app.api.operations.OperationAttemptRunner attempts;

  private final io.justsearch.app.api.EngineAdmissionService admission =
      org.mockito.Mockito.mock(io.justsearch.app.api.EngineAdmissionService.class);

  @org.junit.jupiter.api.BeforeEach
  void openOperationRunner() throws Exception {
    org.mockito.Mockito.when(admission.attach(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
      var work = org.mockito.Mockito.mock(io.justsearch.app.api.EngineWorkHandle.class);
      org.mockito.Mockito.when(work.context()).thenReturn(call.getArgument(0));
      org.mockito.Mockito.when(work.retain()).thenReturn(work);
      return work;
    });
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(
        operationDirectory.resolve("operations.db"));
    attempts = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
        operationStore, Clock.systemUTC(), Set.of());
  }

  @org.junit.jupiter.api.AfterEach
  void closeOperationRunner() throws Exception {
    if (operationStore != null) operationStore.close();
  }

  @Test
  void unactivatedReplayPlanCannotEnterTheCurrentRowIdentity() throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.held-replay-test");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        calls.incrementAndGet();
        return OperationResult.success("effect");
      }
      @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
          EngineContext context) {
        return new OperationPreparation(args, "root-plan.v1", rootPlanPayload("held"));
      }
    });
    assertThrows(IllegalArgumentException.class,
        () -> new OperationExecutorImpl(attempts, admission, handlers)
        .dispatch(makeOp(id, TrustTier.CORE, false), "{}",
            io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals(0, calls.get());
    try (var connection = java.sql.DriverManager.getConnection(
        "jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT identity_json, state FROM operations")) {
      assertTrue(row.next());
      assertEquals("FAILED", row.getString("state"));
      assertFalse(row.getString("identity_json").contains("preparedInvocation"));
      assertFalse(row.next());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = OperationKind.class, names = {"REINDEX", "INGEST"})
  void declaredKindSelectsRecoveryAfterDispatcherAcceptance(OperationKind kind) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.arbitrary-kind-fixture");
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var context = io.justsearch.app.services.TestEngineContexts.durableInternal();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("The fixture owns asynchronous completion");
      }
      @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
          EngineContext ctx) {
        return OperationPreparation.passthrough(args);
      }
      @Override public OperationExecution executePrepared(OperationPreparation value,
          InvocationProvenance provenance, EngineContext ctx, OperationRecordHandle handle) {
        calls.incrementAndGet();
        return new OperationExecution(OperationResult.success("Accepted"),
            new java.util.concurrent.CompletableFuture<>());
      }
    });
    var op = makeOp(id, TrustTier.CORE, false, AuditPolicy.METADATA_ONLY, kind);
    assertTrue(new OperationExecutorImpl(attempts, admission, handlers).dispatch(op, "{}", context).success());
    var accepted = operationStore.openRecords();
    assertEquals(1, accepted.size());
    String key = accepted.getFirst().key();
    operationStore.close();
    operationStore = new io.justsearch.app.observability.operations.SqliteOperationStore(
        operationDirectory.resolve("operations.db"));
    var reopened = new io.justsearch.app.observability.operations.OperationAttemptRunnerImpl(
        operationStore, Clock.systemUTC(), Set.of(kind));
    var reconciliations = new java.util.concurrent.atomic.AtomicInteger();
    reopened.reconcile(kind, row -> {
      reconciliations.incrementAndGet();
      assertEquals(key, row.key());
      assertEquals(kind, row.descriptor().kind());
      assertEquals(id.value(), row.descriptor().operationRef());
      assertEquals(context.survival(), row.context().survival());
      assertFalse(row.descriptor().identityJson().contains("preparedInvocation"));
      return new io.justsearch.app.api.operations.OperationAttemptRunner.Reconciliation.Complete(
          new io.justsearch.app.api.operations.OperationReceipt("SUCCESS", null));
    });
    assertEquals(1, reconciliations.get(), "the declared kind, not the operation id, selects its owner");
    assertEquals(1, calls.get(), "classifying recovery must not dispatch the handler again");
    assertEquals(io.justsearch.app.api.operations.OperationState.COMPLETE,
        operationStore.find(key).orElseThrow().state());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void declaredKindAlsoCoversPreparationRefusalAndUndo(boolean undo) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.kind-refusal-fixture");
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("Refusal and undo must not invoke a forward effect");
      }
      @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
          EngineContext ctx) {
        throw new io.justsearch.agent.api.registry.OperationPreparationRefused(
            OperationResult.failure("Unavailable", "GENERATION_UNAVAILABLE", Map.of(), true));
      }
      @Override public OperationResult undo(String executionId, EngineContext ctx) {
        assertEquals("prior-execution", executionId);
        return OperationResult.success("Reversed");
      }
    });
    var executor = new OperationExecutorImpl(attempts, admission, handlers);
    var op = makeOp(id, TrustTier.CORE, true, AuditPolicy.METADATA_ONLY, OperationKind.NOTE);
    var context = io.justsearch.app.services.TestEngineContexts.internal();
    var response = undo ? executor.undo(op, "prior-execution", context) : executor.dispatch(op, "{}", context);
    assertEquals(undo, response.success());
    try (var connection = java.sql.DriverManager.getConnection(
        "jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement();
        var row = statement.executeQuery("SELECT kind, state, identity_json FROM operations")) {
      assertTrue(row.next());
      assertEquals("note", row.getString("kind"));
      assertEquals(undo ? "COMPLETE" : "FAILED", row.getString("state"));
      var identity = tools.jackson.databind.json.JsonMapper.builder().build().readTree(row.getString("identity_json"));
      assertEquals(undo ? "undo" : "invoke", identity.path("mode").asText());
      assertFalse(row.next(), "one attempted invocation has one durable row");
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void preparationFailureIsRecordedBeforeRefusalWithoutEffects(boolean fatal) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.preparation-failure");
    Throwable expected = fatal ? new AssertionError("fatal preparation failure")
        : new IllegalStateException("generation unavailable");
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("Failed preparation cannot have an effect");
      }
      @Override public OperationPreparation prepare(String args,
          InvocationProvenance provenance, EngineContext ctx) {
        if (expected instanceof Error error) throw error;
        throw (RuntimeException) expected;
      }
    });
    org.junit.jupiter.api.Assertions.assertSame(expected, assertThrows(expected.getClass(),
        () -> new OperationExecutorImpl(attempts, admission, handlers).dispatch(
            makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal())));
    if (fatal) {
      try (var connection = java.sql.DriverManager.getConnection(
          "jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
          var statement = connection.createStatement();
          var result = statement.executeQuery("SELECT COUNT(*) FROM operations")) {
        assertTrue(result.next());
        assertEquals(0, result.getInt(1), "fatal preparation cannot claim trustworthy acceptance or completion");
      }
    } else {
      assertGenericFailedAttempt("UNCAUGHT_EXCEPTION");
    }
    org.mockito.Mockito.verifyNoInteractions(admission);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {
      "changed-arguments", "malformed-payload", "null-preparation", "content-payload"})
  void invalidPreparedIdentityIsRecordedGenericallyWithoutEffects(String invalid) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.invalid-preparation");
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("Invalid preparation cannot have an effect");
      }
      @Override public OperationPreparation prepare(String args,
          InvocationProvenance provenance, EngineContext ctx) {
        if (invalid.equals("null-preparation")) return null;
        return new OperationPreparation(
            invalid.equals("changed-arguments") ? "{\"changed\":true}" : args,
            "root-plan.v1", invalid.equals("malformed-payload") ? "[]"
                : rootPlanPayload("root").replace("\"roots\":", "\"prompt\":\"must-not-persist\",\"roots\":"));
      }
    });
    assertThrows(RuntimeException.class, () -> new OperationExecutorImpl(attempts, admission, handlers)
        .dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal()));
    assertGenericFailedAttempt("UNCAUGHT_EXCEPTION");
    org.mockito.Mockito.verifyNoInteractions(admission);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "not-json"})
  void invalidPublicInputCannotEnterPreparation(String arguments) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.invalid-input-preparation");
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("Invalid input cannot execute");
      }
      @Override public OperationPreparation prepare(String args,
          InvocationProvenance provenance, EngineContext ctx) {
        throw new AssertionError("Invalid input cannot prepare");
      }
    });
    var base = makeOp(id, TrustTier.CORE, false);
    // The existing no-argument schema is deliberately a passthrough sentinel. Use a
    // constrained operation to prove a validation refusal happens before preparation.
    var op = new Operation(base.id(), base.presentation(), Interface.of(
        "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
        base.intf().result()), base.policy(), base.availability(), base.lineage(), base.binding(),
        base.provenance(), base.executors(), base.audience(), base.consumers());
    var result = new OperationExecutorImpl(attempts, admission, handlers).dispatch(
        op, arguments, io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals("BAD_REQUEST", result.errorCode().orElseThrow());
    assertGenericFailedAttempt("BAD_REQUEST");
    org.mockito.Mockito.verifyNoInteractions(admission);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void expectedPreparationRefusalPreservesTypedRetryableOutcome(boolean longestCode) throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.refused-preparation");
    String code = longestCode ? "G".repeat(96) : "GENERATION_UNAVAILABLE";
    var refusal = OperationResult.failure("Serving generation is unavailable", code, Map.of(), true);
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext ctx) {
        throw new AssertionError("Refused preparation cannot execute");
      }
      @Override public OperationPreparation prepare(String args,
          InvocationProvenance provenance, EngineContext ctx) {
        throw new io.justsearch.agent.api.registry.OperationPreparationRefused(refusal);
      }
    });
    var response = new OperationExecutorImpl(attempts, admission, handlers).dispatch(
        makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal());
    org.junit.jupiter.api.Assertions.assertSame(refusal, response);
    assertGenericFailedAttempt(code);
    org.mockito.Mockito.verifyNoInteractions(admission);
  }

  private void assertGenericFailedAttempt(String reason) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection(
        "jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT state, identity_json, failure_reason FROM operations")) {
      assertTrue(result.next());
      assertEquals("FAILED", result.getString("state"));
      assertFalse(result.getString("identity_json").contains("preparedInvocation"));
      assertFalse(result.getString("identity_json").contains("must-not-persist"));
      assertEquals(reason, result.getString("failure_reason"));
      assertFalse(result.next());
    }
  }

  private String rootPlanPayload(String root) {
    return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
        "generation", "g1", "roots", List.of(Map.of(
            "path", operationDirectory.resolve(root).toString(), "collection", "default",
            "force", false, "singleFile", false, "excludePatterns", List.of(), "excludedSubtrees", List.of()))));
  }

  @Test
  void recordedAsyncDispatchPublishesOnlyAfterActualDurableCompletion() {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.async-test");
    var actual = new java.util.concurrent.CompletableFuture<OperationResult>();
    var key = new java.util.concurrent.atomic.AtomicReference<String>();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Recorded dispatch must carry its accepted handle");
      }
      @Override public OperationExecution executeRecorded(
          String args, InvocationProvenance provenance, EngineContext context,
          OperationRecordHandle handle) {
        key.set(handle.key());
        assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING,
            operationStore.find(handle.key()).orElseThrow().state(), "durable acceptance/start precede the body");
        return new OperationExecution(OperationResult.success("Started"), actual);
      }
    });
    var emitted = new ArrayList<OperationHistoryEntry>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, entry -> {
      assertEquals(io.justsearch.app.api.operations.OperationState.COMPLETE,
          operationStore.find(key.get()).orElseThrow().state(), "history follows durable completion");
      emitted.add(entry);
    }, Clock.systemUTC());
    var response = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}",
        io.justsearch.app.services.TestEngineContexts.internal());
    assertEquals("Started", response.message());
    assertTrue(emitted.isEmpty());
    assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING,
        operationStore.find(key.get()).orElseThrow().state());
    actual.complete(OperationResult.success("Committed"));
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.SUCCESS, emitted.getFirst().outcome());
  }

  @Test
  void asynchronousTerminalWriteFailureEmitsFailureHistoryAndHealth() throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.async-write-failure");
    var actual = new java.util.concurrent.CompletableFuture<OperationResult>();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Recorded dispatch required");
      }
      @Override public OperationExecution executeRecorded(String args, InvocationProvenance provenance,
          EngineContext context, OperationRecordHandle handle) {
        return new OperationExecution(OperationResult.success("Started"), actual);
      }
    });
    var emitted = new ArrayList<OperationHistoryEntry>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());
    assertTrue(executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}",
        io.justsearch.app.services.TestEngineContexts.internal()).success());
    var accepted = operationStore.openRecords().getFirst();
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON operations WHEN NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
    }
    actual.complete(OperationResult.success("Effect committed"));
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.getFirst().outcome());
    assertEquals(Optional.of("STORAGE_FAILED"), emitted.getFirst().diagnosticsLink());
    assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING,
        operationStore.find(accepted.key()).orElseThrow().state());

    // Health may attach after failure during composition; the degradation must not disappear.
    var conditions = new io.justsearch.app.observability.health.ConditionStore();
    io.justsearch.app.services.operations.OperationRecoveryNotice.observePersistenceFailures(
        attempts, conditions, new io.justsearch.app.observability.health.HealthEventChangeRegistry(),
        new io.justsearch.app.observability.health.Source("head", "fixture", Optional.empty()), Clock.systemUTC());
    var event = conditions.find("operations.persistence_failed", "operations").orElseThrow();
    assertEquals(io.justsearch.app.observability.health.Severity.ERROR, event.severity());
    var condition = (io.justsearch.app.observability.health.AssertedCondition) event.body();
    assertTrue(condition.message().orElseThrow().contains(accepted.key()));
    assertTrue(condition.message().orElseThrow().contains("COMPLETE"));
  }

  @Test
  void unauditedAsyncFailureStillEmitsFailureAdvisory() {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.async-unaudited-failure");
    var actual = new java.util.concurrent.CompletableFuture<OperationResult>();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Prepared execution required");
      }
      @Override public OperationExecution executePrepared(OperationPreparation prepared,
          InvocationProvenance provenance, EngineContext context, OperationRecordHandle handle) {
        return new OperationExecution(OperationResult.success("Started"), actual);
      }
    });
    var history = new ArrayList<OperationHistoryEntry>();
    var advisories = new ArrayList<OperationCompletionEvent>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, history::add,
        Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add), Clock.systemUTC());
    assertTrue(executor.dispatch(makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS), AuditPolicy.NONE),
        "{}", io.justsearch.app.services.TestEngineContexts.internal()).success());
    actual.completeExceptionally(new IllegalStateException("private detail"));
    assertTrue(history.isEmpty());
    assertEquals(1, advisories.size());
    assertEquals(OperationOutcome.FAILURE, advisories.getFirst().outcome());
  }

  @Test
  void failedAcceptanceCannotReachDispatchHandler() throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.acceptance-test");
    var effects = new java.util.concurrent.atomic.AtomicInteger();
    handlers.register(id, (args, context) -> {
      effects.incrementAndGet();
      return OperationResult.success("Effect");
    });
    operationStore.close();
    var executor = new OperationExecutorImpl(attempts, admission, handlers);
    assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}",
            io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals(0, effects.get());
  }

  @Test
  void synchronousCompletionWriteFailureCannotReturnSuccess() throws Exception {
    try (var connection = java.sql.DriverManager.getConnection(
        "jdbc:sqlite:" + operationDirectory.resolve("operations.db")); var statement = connection.createStatement()) {
      statement.execute("CREATE TRIGGER refuse_complete BEFORE UPDATE ON operations WHEN NEW.state = 'COMPLETE' "
          + "BEGIN SELECT RAISE(ABORT, 'fixture'); END");
    }
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.completion-write-test");
    var effects = new java.util.concurrent.atomic.AtomicInteger();
    handlers.register(id, (args, context) -> {
      effects.incrementAndGet();
      return OperationResult.success("Effect committed");
    });
    var emitted = new ArrayList<OperationHistoryEntry>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());
    assertThrows(io.justsearch.app.api.operations.OperationStoreException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}",
            io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals(1, effects.get());
    assertEquals(1, emitted.size(), "failed persistence must publish failure, never a success receipt");
    assertEquals(OperationOutcome.FAILURE, emitted.getFirst().outcome());
    assertEquals(Optional.of("STORAGE_FAILED"), emitted.getFirst().diagnosticsLink());
    assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING, operationStore.openRecords().getFirst().state());
  }

  @Test
  void undoReceivesItsOwnAcceptedIdentity() {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.undo-record-test");
    var key = new java.util.concurrent.atomic.AtomicReference<String>();
    handlers.register(id, new OperationHandler() {
      @Override public OperationResult execute(String args, EngineContext context) {
        throw new AssertionError("Only undo expected");
      }
      @Override public OperationExecution undoRecorded(String executionId,
          EngineContext context, OperationRecordHandle handle) {
        key.set(handle.key());
        var row = operationStore.find(handle.key()).orElseThrow();
        assertTrue(row.descriptor().identityJson().contains("undo"));
        assertEquals("original-execution", executionId);
        return OperationExecution.finished(OperationResult.success("Undone"));
      }
    });
    var executor = new OperationExecutorImpl(attempts, admission, handlers);
    assertTrue(executor.undo(makeOp(id, TrustTier.CORE, true), "original-execution",
        io.justsearch.app.services.TestEngineContexts.internal()).success());
    assertEquals(io.justsearch.app.api.operations.OperationState.COMPLETE,
        operationStore.find(key.get()).orElseThrow().state());
  }


  @Test
  void coreProvenanceDispatchesToHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test");
    handlers.register(id, (args, engineContext) -> OperationResult.success("invoked"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.CORE, false);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals("invoked", result.message());
  }

  @Test
  void trustedPluginProvenanceDispatchesToHandlerSameAsCore() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.plugin");
    handlers.register(id, (args, engineContext) -> OperationResult.success("plugin-invoked"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals("plugin-invoked", result.message());
  }

  @Test
  void untrustedPluginThrowsUnsupportedOperation() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.untrusted");
    handlers.register(id, (args, engineContext) -> OperationResult.success("should-not-execute"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.UNTRUSTED_PLUGIN, false);
    UnsupportedOperationException ex =
        assertThrows(UnsupportedOperationException.class, () -> executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal()));
    assertTrue(
        ex.getMessage().contains("V1.5"),
        "UNTRUSTED_PLUGIN error should reference V1.5 sandbox: " + ex.getMessage());
  }

  @Test
  void undoFailsFastWhenPolicyDoesNotSupportIt() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.no-undo");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args, EngineContext engineContext) {
            return OperationResult.success("ok");
          }

          @Override
          public OperationResult undo(String executionId, EngineContext engineContext) {
            handlerInvoked[0] = true;
            return OperationResult.success("should-not-be-called");
          }
        });
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.CORE, false); // undoSupported = false
    OperationResult result = executor.undo(op, "exec-123", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertTrue(
        result.message().contains("Undo not supported"),
        "Failure message should explain undo unsupported: " + result.message());
    assertFalse(handlerInvoked[0], "Handler.undo must NOT be called when policy disallows");
  }

  @Test
  void undoDelegatesToHandlerWhenPolicySupportsIt() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.with-undo");
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args, EngineContext engineContext) {
            return OperationResult.success("ok", "exec-456");
          }

          @Override
          public OperationResult undo(String executionId, EngineContext engineContext) {
            return OperationResult.success("undone " + executionId);
          }
        });
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.CORE, true); // undoSupported = true
    OperationResult result = executor.undo(op, "exec-456", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals("undone exec-456", result.message());
  }

  @Test
  void successfulDispatchEmitsHistoryEntryWithSuccessOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-success");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(1, emitted.size(), "exactly one history entry should be emitted per dispatch");
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(id, entry.operationId());
    assertEquals(OperationOutcome.SUCCESS, entry.outcome());
    assertTrue(
        !entry.endTime().isBefore(entry.startTime()),
        "endTime must be at or after startTime");
  }

  @Test
  void failedDispatchEmitsHistoryEntryWithFailureOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-failure");
    handlers.register(id, (args, engineContext) -> OperationResult.failure("nope"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  @Test
  void thrownDispatchStillEmitsFailureHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-throw");
    handlers.register(
        id,
        (args, engineContext) -> {
          throw new RuntimeException("boom");
        });
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    assertThrows(
        RuntimeException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal()));
    assertEquals(1, emitted.size(), "history entry must still be emitted on thrown failure");
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  @Test
  void historyEmitterFailureDoesNotBreakDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.history-emit-throws");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            entry -> {
              throw new RuntimeException("emitter broke");
            },
            Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success(), "emitter failure must not propagate to dispatch caller");
  }

  // ----------------------------------------------------------------------------------
  // Tempdoc 879 — the declared AuditPolicy axis drives history emission.
  //
  // Acceptance criterion: flipping ONLY the audit declaration flips the behaviour. Each
  // pair below uses the same handler, same wiring, same everything — the operations differ
  // in exactly the OperationPolicy.audit field.
  // ----------------------------------------------------------------------------------

  @Test
  void metadataOnlyAuditEmitsHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.audit-axis");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false, AuditPolicy.METADATA_ONLY), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(
        1, emitted.size(), "METADATA_ONLY must record exactly one history entry per dispatch");
    assertEquals(OperationOutcome.SUCCESS, emitted.get(0).outcome());
  }

  @Test
  void mediumRiskUnauditedMutationStillAcceptsBeforeEffect() {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.unaudited-mutation");
    var key = new java.util.concurrent.atomic.AtomicReference<String>();
    handlers.register(id, (args, context) -> {
      var rows = operationStore.openRecords();
      assertEquals(1, rows.size(), "audit suppression cannot bypass durable acceptance");
      assertEquals(io.justsearch.app.api.operations.OperationState.RUNNING, rows.getFirst().state());
      key.set(rows.getFirst().key());
      return OperationResult.success("mutated");
    });
    var original = makeOp(id, TrustTier.CORE, false, AuditPolicy.NONE);
    var op = new Operation(original.id(), original.presentation(), original.intf(),
        new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.None.INSTANCE, AuditPolicy.NONE,
            RetryPolicy.noRetry(), Set.of(), false), original.availability(), original.lineage(),
        original.binding(), original.provenance(), original.executors());
    var history = new ArrayList<OperationHistoryEntry>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, history::add, Clock.systemUTC());
    assertTrue(executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal()).success());
    assertTrue(history.isEmpty());
    assertEquals(io.justsearch.app.api.operations.OperationState.COMPLETE,
        operationStore.find(key.get()).orElseThrow().state());
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(io.justsearch.app.api.EngineAdmissionException.Reason.class)
  void admissionRefusalRetainsItsReasonInDurableAttempt(io.justsearch.app.api.EngineAdmissionException.Reason reason)
      throws Exception {
    var handlers = new HandlerRegistry();
    var id = new OperationRef("core.admission-reason");
    handlers.register(id, (args, context) -> { throw new AssertionError("Refused admission cannot run"); });
    var refusal = new io.justsearch.app.api.EngineAdmissionException(reason, 3);
    org.mockito.Mockito.when(admission.attach(org.mockito.ArgumentMatchers.any())).thenThrow(refusal);
    var history = new ArrayList<OperationHistoryEntry>();
    var executor = new OperationExecutorImpl(attempts, admission, handlers, history::add, Clock.systemUTC());
    org.junit.jupiter.api.Assertions.assertSame(refusal,
        assertThrows(io.justsearch.app.api.EngineAdmissionException.class,
            () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}",
                io.justsearch.app.services.TestEngineContexts.internal())));
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + operationDirectory.resolve("operations.db"));
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT state, failure_reason FROM operations")) {
      assertTrue(rows.next());
      assertEquals("FAILED", rows.getString("state"));
      assertEquals(reason.name(), rows.getString("failure_reason"));
      assertFalse(rows.next());
    }
    assertEquals(1, history.size());
    assertEquals(Optional.of(reason.name()), history.getFirst().diagnosticsLink());
  }

  @Test
  void noneAuditSuppressesHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.audit-axis");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false, AuditPolicy.NONE), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success(), "suppressing the audit record must not affect the dispatch");
    assertTrue(
        emitted.isEmpty(),
        "AuditPolicy.NONE means 'no audit record' — no history entry may be emitted");
  }

  @Test
  void noneAuditSuppressesHistoryOnValidationFailure() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.audit-axis-validation");
    handlers.register(id, (args, engineContext) -> OperationResult.success("unreached"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
    assertTrue(
        emitted.isEmpty(),
        "the validation-failure branch calls emitHistory too — it must respect NONE");
  }

  @Test
  void noneAuditSuppressesHistoryOnThrownDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.audit-axis-throw");
    handlers.register(
        id,
        (args, engineContext) -> {
          throw new RuntimeException("boom");
        });
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    assertThrows(
        RuntimeException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false, AuditPolicy.NONE), "{}", io.justsearch.app.services.TestEngineContexts.internal()));

    assertTrue(
        emitted.isEmpty(),
        "the uncaught-exception branch calls emitHistory too — it must respect NONE");
  }

  /**
   * Precision test for the wrong-gate hazard in {@code emitHistory}: the per-Operation
   * advisory emission lives INSIDE that method, after the history block. Suppressing the
   * audit record with an early {@code return} would silently kill the advisory pipeline.
   *
   * <p>This is not hypothetical — {@code core.ping-backend} (CoreOperationCatalog) is the
   * real operation that declares {@code AuditPolicy.NONE} together with advisoryClass
   * {@code core.advisory-operation-completed}, and it is the advisory substrate's canary
   * producer. Audit policy governs history retention, not advisory delivery.
   */
  @Test
  void noneAuditSuppressesHistoryButStillFiresAdvisory() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.audit-axis-advisory");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            emitted::add,
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op =
        makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS), AuditPolicy.NONE);

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertTrue(emitted.isEmpty(), "AuditPolicy.NONE must suppress the history entry");
    assertEquals(
        1,
        advisories.size(),
        "advisory emission is a separate axis — NONE must NOT suppress it (core.ping-backend)");
    assertEquals(id, advisories.get(0).operationId());
    assertEquals(OperationOutcome.SUCCESS, advisories.get(0).outcome());
  }

  // -------------------- Slice 3a-2-c Phase C: schema validation --------------------

  /**
   * Required-arg missing → dispatcher returns BAD_REQUEST without ever calling the
   * handler. Catches the typo / missing-arg case centrally so handlers can simplify
   * their per-arg parsing.
   */
  @Test
  void schemaValidationRejectsMissingRequiredArg() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.requires-path");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        (args, engineContext) -> {
          handlerInvoked[0] = true;
          return OperationResult.success("should not reach here");
        });
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success(), "missing required arg must fail validation");
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
    assertFalse(
        handlerInvoked[0],
        "handler must NOT be invoked when args fail schema validation");
  }

  /**
   * Valid args → dispatcher passes through to the handler unchanged. Schema
   * enforcement is gating, not transforming.
   */
  @Test
  void schemaValidationPassesValidArgsThrough() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.requires-path-ok");
    boolean[] handlerInvoked = {false};
    handlers.register(
        id,
        (args, engineContext) -> {
          handlerInvoked[0] = true;
          return OperationResult.success("got args: " + args);
        });
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{\"path\":\"/some/path\"}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertTrue(handlerInvoked[0], "handler must be invoked when args validate");
  }

  /**
   * Wrong-type arg (path=number when schema expects string) → BAD_REQUEST. Validates
   * that the schema's type constraint is enforced, not just required-key presence.
   */
  @Test
  void schemaValidationRejectsWrongType() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.wrong-type");
    handlers.register(id, (args, engineContext) -> OperationResult.success("should not reach"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{\"path\":42}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success(), "wrong-type arg must fail validation");
    assertEquals("BAD_REQUEST", result.errorCode().orElse(null));
  }

  /**
   * Empty schema (Interface.of with `{"type":"object"}`) → no validation; handler
   * receives whatever args were sent. This is the existing behavior for
   * no-arg Operations like restart-worker.
   */
  @Test
  void schemaValidationSkippedForEmptySchema() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.empty-schema");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false), "{\"anything\":\"goes\"}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
  }

  /**
   * Validation failure emits a FAILURE history entry — consistent with the
   * uncaught-exception path which also emits FAILURE before propagating.
   * Validation rejection counts as a real dispatch attempt.
   */
  @Test
  void schemaValidationFailureEmitsFailureHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.validation-history");
    handlers.register(id, (args, engineContext) -> OperationResult.success("unreached"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    Operation op =
        new Operation(
            id,
            Presentation.of(new I18nKey("test." + id.value()), new I18nKey("test.desc")),
            Interface.of(
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}",
                "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.METADATA_ONLY,
                RetryPolicy.noRetry(),
                Set.of(),
                false),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
  }

  // -------------------- Slice 490 §4.B: InvocationProvenance retrofit --------------------

  /**
   * 3-arg dispatch threads typed provenance onto the emitted history entry. Slice 490
   * §4.B retrofit — the typed answer to "who triggered this?" is recorded on every
   * dispatch that supplies provenance.
   */
  @Test
  void threeArgDispatchThreadsProvenanceOntoHistoryEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-success");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("user:alice"),
            Instant.parse("2026-05-12T08:30:00Z"));
    OperationResult result =
        executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", provenance, io.justsearch.app.services.TestEngineContexts.forProvenance(provenance));

    assertTrue(result.success());
    assertEquals(1, emitted.size());
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(provenance, entry.provenance());
    assertEquals("head", entry.actor());
  }

  /**
   * 2-arg legacy dispatch defaults to system-internal provenance and derives an actor
   * string from the executor enum. Existing callers compile unchanged and observe
   * provenance.isPresent() == true with TransportTag.SYSTEM_INTERNAL.
   */
  @Test
  void legacyTwoArgDispatchDefaultsToSystemInternalProvenance() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-default");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    OperationResult result = executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(1, emitted.size());
    OperationHistoryEntry entry = emitted.get(0);
    assertEquals(TransportTag.SYSTEM_INTERNAL, entry.provenance().transport());
    assertEquals("head", entry.actor());
  }

  /** Provenance is also threaded onto FAILURE entries when the handler throws. */
  @Test
  void thrownDispatchStillCarriesProvenanceOnFailureEntry() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-throw");
    handlers.register(
        id,
        (args, engineContext) -> {
          throw new RuntimeException("boom");
        });
    List<OperationHistoryEntry> emitted = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, emitted::add, Clock.systemUTC());

    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.AGENT_LOOP,
            ExecutorTag.AGENT,
            Optional.of("app-services-test"),
            Instant.parse("2026-05-12T08:30:00Z"));

    assertThrows(
        RuntimeException.class,
        () -> executor.dispatch(makeOp(id, TrustTier.CORE, false), "{}", provenance, io.justsearch.app.services.TestEngineContexts.forProvenance(provenance)));
    assertEquals(1, emitted.size());
    assertEquals(OperationOutcome.FAILURE, emitted.get(0).outcome());
    assertEquals(provenance, emitted.get(0).provenance());
  }

  // -------------- Slice 490 follow-up: provenance integrity validation --------------

  @Test
  void coreTierDispatchAcceptsUserFacingTransports() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-core");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.CORE, false);
    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON, ExecutorTag.UI, Optional.of("test-webview"), Instant.now());

    OperationResult result = executor.dispatch(op, "{}", provenance, io.justsearch.app.services.TestEngineContexts.forProvenance(provenance));
    assertTrue(result.success());
  }

  @Test
  void trustedPluginCannotSpoofUserFacingButtonTransport() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    InvocationProvenance spoofed =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("plugin:malicious"),
            Instant.now());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class, () -> executor.dispatch(op, "{}", spoofed, io.justsearch.app.services.TestEngineContexts.forProvenance(spoofed)));
    assertTrue(ex.getMessage().contains("TRUSTED_PLUGIN"));
    assertTrue(ex.getMessage().contains("BUTTON"));
  }

  @Test
  void trustedPluginMayDispatchWithPluginEmittedTransport() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin-ok");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    InvocationProvenance ok =
        new InvocationProvenance(
            TransportTag.PLUGIN_EMITTED,
            ExecutorTag.AGENT,
            Optional.of("plugin:advisor"),
            Instant.now());

    OperationResult result = executor.dispatch(op, "{}", ok, io.justsearch.app.services.TestEngineContexts.forProvenance(ok));
    assertTrue(result.success());
  }

  @Test
  void trustedPluginCannotSpoofUrlBarOrLlmEmission() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.provenance-validation-plugin-mixed");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOp(id, TrustTier.TRUSTED_PLUGIN, false);
    for (TransportTag forbidden :
        new TransportTag[] {
          TransportTag.URL_BAR,
          TransportTag.URL_DEEPLINK,
          TransportTag.LLM_EMISSION,
          TransportTag.PALETTE,
          TransportTag.RAIL,
          TransportTag.MCP
        }) {
      InvocationProvenance spoof =
          InvocationProvenance.fromEngineContext(
              io.justsearch.app.services.TestEngineContexts.forTransport(forbidden),
              ExecutorTag.UI,
              Instant.now(),
              Optional.empty());
      assertThrows(
          IllegalArgumentException.class,
          () -> executor.dispatch(op, "{}", spoof, io.justsearch.app.services.TestEngineContexts.forProvenance(spoof)),
          "expected rejection of " + forbidden);
    }
  }

  // -------- Slice 490 §6.3 + Group B2: advisory emission via advisoryClass routing -------

  private static final ResourceRef TEST_ADVISORY_CLASS =
      new ResourceRef("core.advisory-operation-completed");

  @Test
  void advisoryEmittedWhenPolicyDeclaresAdvisoryClass() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-emit-test");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            history::add,
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS));
    InvocationProvenance provenance =
        new InvocationProvenance(
            TransportTag.BUTTON,
            ExecutorTag.UI,
            Optional.of("user:advisor-test"),
            Instant.parse("2026-05-12T09:00:00Z"));

    OperationResult result = executor.dispatch(op, "{}", provenance, io.justsearch.app.services.TestEngineContexts.forProvenance(provenance));

    assertTrue(result.success());
    assertEquals(1, history.size(), "exactly one history entry per dispatch");
    assertEquals(1, advisories.size(), "exactly one advisory per declared-class dispatch");
    OperationCompletionEvent advisory = advisories.get(0);
    assertEquals(id, advisory.operationId());
    assertEquals(OperationOutcome.SUCCESS, advisory.outcome());
    assertEquals(provenance, advisory.provenance());
    assertTrue(advisory.diagnosticsLink().isEmpty());
  }

  @Test
  void noAdvisoryEmittedWhenPolicyDoesNotDeclareAdvisoryClass() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-noop-test");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            history::add,
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.empty());

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(1, history.size(), "history entry still emitted regardless of advisory class");
    assertEquals(0, advisories.size(), "no advisory emitted for class-less Operation");
  }

  @Test
  void unmappedAdvisoryClassDoesNotBreakDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-unmapped-test");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ok"));
    List<OperationHistoryEntry> history = new ArrayList<>();
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission, handlers, history::add, Map.of(), Clock.systemUTC());

    Operation op =
        makeOpWithAdvisoryClass(
            id, Optional.of(new ResourceRef("core.advisory-not-registered")));

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals(1, history.size());
    assertEquals(0, advisories.size(), "no emitter registered for the declared class");
  }

  @Test
  void advisoryEmittedOnFailureOutcomeWhenAdvisoryClassDeclared() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.advisory-failure-test");
    handlers.register(id, (args, engineContext) -> OperationResult.failure("nope"));
    List<OperationCompletionEvent> advisories = new ArrayList<>();
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            entry -> {},
            Map.of(TEST_ADVISORY_CLASS, (Consumer<OperationCompletionEvent>) advisories::add),
            Clock.systemUTC());

    Operation op = makeOpWithAdvisoryClass(id, Optional.of(TEST_ADVISORY_CLASS));

    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(1, advisories.size());
    assertEquals(OperationOutcome.FAILURE, advisories.get(0).outcome());
  }

  private static Operation makeOpWithAdvisoryClass(
      OperationRef id, Optional<ResourceRef> advisoryClass) {
    return makeOpWithAdvisoryClass(id, advisoryClass, AuditPolicy.METADATA_ONLY);
  }

  private static Operation makeOpWithAdvisoryClass(
      OperationRef id, Optional<ResourceRef> advisoryClass, AuditPolicy audit) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            audit,
            RetryPolicy.noRetry(),
            Set.of(),
            false,
            advisoryClass),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  @Test
  void capabilityUnavailableReturnsFailureWithoutInvokingHandler() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, (args, engineContext) -> OperationResult.success("should-not-run"));
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertFalse(result.success());
    assertEquals(Optional.of("CAPABILITY_UNAVAILABLE"), result.errorCode());
    assertEquals(Optional.of(true), result.retryable());
  }

  @Test
  void capabilityAvailableAllowsDispatch() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> true);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
    assertEquals("ran", result.message());
  }

  @Test
  void noCapabilityResolverSkipsCheck() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-gated");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran-without-resolver"));
    OperationDispatcher executor = new OperationExecutorImpl(attempts, admission, handlers);

    Operation op = makeOpWithCapability(id, RequiredCapability.WorkerOnline.INSTANCE);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
  }

  @Test
  void emptyCapabilitySetAlwaysPasses() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-no-cap");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op = makeOp(id, TrustTier.CORE, false);
    OperationResult result = executor.dispatch(op, "{}", io.justsearch.app.services.TestEngineContexts.internal());

    assertTrue(result.success());
  }

  @Test
  void undoBlockedWhenCapabilityUnavailable() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-undo-gated");
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args, EngineContext engineContext) {
            return OperationResult.success("ran");
          }

          @Override
          public OperationResult undo(String executionId, EngineContext engineContext) {
            return OperationResult.success("undone — should not reach here");
          }
        });
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers, null, Map.of(), Clock.systemUTC(), null, null, req -> false);

    Operation op =
        new Operation(
            id,
            Presentation.of(
                new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                RiskTier.LOW,
                ConfirmStrategy.None.INSTANCE,
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(RequiredCapability.WorkerOnline.INSTANCE),
                true),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(id),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    OperationResult result = executor.undo(op, "exec-123", io.justsearch.app.services.TestEngineContexts.internal());
    assertFalse(result.success());
    assertEquals(Optional.of("CAPABILITY_UNAVAILABLE"), result.errorCode());
  }

  private static Operation makeOpWithCapability(OperationRef id, RequiredCapability cap) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(cap),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  // ----------------------------------------------------------------------------------
  // Tempdoc 550 Slice A1 (Authorize face) — consent capsule satisfies the trust gate.
  // ----------------------------------------------------------------------------------

  private OperationDispatcher latticeExecutorWithCapsule(
      HandlerRegistry handlers, ConsentCapsuleService capsule) {
    TrustEvaluator trust = new CoreTrustEvaluator();
    IntentSourceCatalog sources = CoreIntentSourceCatalog.catalog();
    return new OperationExecutorImpl(attempts, admission,
        handlers, null, Map.of(), Clock.systemUTC(), trust, sources, null, capsule);
  }

  private OperationDispatcher latticeExecutorWithGateSink(
      HandlerRegistry handlers,
      ConsentCapsuleService capsule,
      List<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry> sink) {
    return new OperationExecutorImpl(attempts, admission,
        handlers,
        null,
        Map.of(),
        Clock.systemUTC(),
        new CoreTrustEvaluator(),
        CoreIntentSourceCatalog.catalog(),
        null,
        capsule,
        sink::add);
  }

  /**
   * Tempdoc 550 E2: when the Global Hard Stop is engaged, an UNTRUSTED dispatch is DENIED (and
   * recorded), a TRUSTED (BUTTON) dispatch is unaffected, and releasing restores normal gating.
   */
  @Test
  void globalHardStop_deniesUntrusted_leavesUserActionsAlone() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    var sink =
        new ArrayList<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    var executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            null,
            Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService(),
            sink::add);
    var hardStop = new GlobalHardStop();
    executor.setGlobalHardStop(hardStop);
    Operation op = makeMediumOp(id);
    InvocationProvenance agent =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());
    InvocationProvenance user =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    // Engaged: the agent (UNTRUSTED) dispatch is DENIED outright + recorded DENIED.
    hardStop.engage();
    assertThrows(
        TrustGateDeniedException.class,
        () -> executor.dispatch(op, "{}", agent, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agent)),
        "hard stop denies the UNTRUSTED dispatch");
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.DENIED,
        sink.get(sink.size() - 1).disposition(),
        "the hard-stop denial is recorded as a DENIED ledger row");

    // Engaged: a user (TRUSTED BUTTON) dispatch is unaffected — TRUSTED×MEDIUM=AUTO → runs.
    OperationResult userResult = executor.dispatch(op, "{}", user, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(user));
    assertTrue(userResult.success(), "user/BUTTON action proceeds while the hard stop is engaged");

    // Released: the agent dispatch returns to normal gating (MEDIUM → confirmation-required).
    hardStop.release();
    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", agent, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agent)),
        "released → the normal lattice gate applies again (not a hard-stop deny)");
  }

  /** Tempdoc 550 Outcome face: a gate firing emits a GATED record AND still throws (fail-closed). */
  @Test
  void gateFireEmitsGatedOutcomeAndStillThrows() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    var sink = new ArrayList<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    OperationDispatcher executor = latticeExecutorWithGateSink(handlers, new ConsentCapsuleService(), sink);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted)),
        "the gate still throws — the emit is additive, fail-closed preserved");
    assertEquals(1, sink.size(), "the gate firing was recorded");
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.GATED,
        sink.get(0).disposition());
    assertEquals("core.test-medium", sink.get(0).operationId());
  }

  /** A capsule-satisfied gate records APPROVED and proceeds to the handler. */
  @Test
  void capsuleApprovalEmitsApprovedOutcome() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    var sink = new ArrayList<io.justsearch.app.observability.operations.AuthorizationOutcomeEntry>();
    OperationDispatcher executor = latticeExecutorWithGateSink(handlers, capsule, sink);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", untrusted, Optional.of(token), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted));
    assertTrue(result.success(), "capsule-authorized dispatch reaches the handler");
    assertEquals(1, sink.size());
    assertEquals(
        io.justsearch.app.observability.operations.AuthorizationDisposition.APPROVED,
        sink.get(0).disposition());
  }

  /**
   * Tempdoc 550 critical-analysis: the gate-firing emit is ADDITIVE and security-gate-adjacent.
   * The production emitter fans into the unified ledger SSE (publish + subscriber callbacks). A
   * throwing emitter MUST NOT change the gate's fail-closed semantics — the gated dispatch still
   * throws ConfirmationRequiredException, it does not leak the emitter's RuntimeException.
   */
  @Test
  void gateStillFailsClosedWhenOutcomeEmitterThrows() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            null,
            Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService(),
            entry -> {
              throw new IllegalStateException("boom — SSE publish failed");
            });
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted)),
        "a throwing outcome emitter must not break the gate — still ConfirmationRequired, not the emitter's exception");
  }

  /** UNTRUSTED source (LLM emission) × MEDIUM risk = TYPED_CONFIRM; no token => gated. */
  @Test
  void untrustedMediumWithoutTokenIsGated() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted)),
        "LLM-emitted MEDIUM op with no token hits the gate (the dead-end)");
  }

  /** A valid consent capsule bound to (op, args) satisfies the same gate. */
  @Test
  void validCapsuleSatisfiesTheGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", untrusted, Optional.of(token), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted));
    assertTrue(result.success(), "capsule-authorized dispatch reaches the handler");
  }

  /**
   * Tempdoc 550 C2 step 4: an UNTRUSTED source (LLM emission, agent loop, MCP) can no
   * longer satisfy the gate with a fabricated non-blank placeholder — it REQUIRES a valid
   * consent capsule. The capsule has replaced the nominal token where the fabrication
   * threat lives. (The legacy non-blank path is retained only for non-UNTRUSTED tiers
   * until their consumers migrate — C2 step 3.) This was the inverse assertion before the
   * tightening; flipping it makes the security upgrade visible and intentional.
   */
  @Test
  void untrustedNonCapsuleTokenIsRejected() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-medium");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeMediumOp(id);
    InvocationProvenance untrusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.LLM_EMISSION),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", untrusted, Optional.of("not-a-capsule"), io.justsearch.app.services.TestEngineContexts.forProvenance(untrusted)),
        "a fabricated non-capsule token no longer satisfies the gate for an UNTRUSTED source");
  }

  /**
   * Tempdoc 550 C2 step 3: with the legacy non-blank acceptance removed, the gate now fails
   * closed for NON-UNTRUSTED tiers too. A TRUSTED source (BUTTON transport) invoking a
   * HIGH-risk op (TRUSTED × HIGH = TYPED_CONFIRM) with a fabricated/nominal non-capsule
   * token is rejected — proving the nominal-token weakness is closed for ALL tiers, not
   * just UNTRUSTED. (Before C2 step 3, the op-id stand-in satisfied this gate.)
   */
  @Test
  void trustedHighNonCapsuleTokenIsRejected() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-high");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    OperationDispatcher executor =
        latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeHighOp(id);
    InvocationProvenance trusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(op, "{}", trusted, Optional.of("core.test-high"), io.justsearch.app.services.TestEngineContexts.forProvenance(trusted)),
        "a nominal (op-id) token from a TRUSTED source no longer satisfies the gate after C2"
            + " step 3");
  }

  /** A valid capsule from the same TRUSTED source DOES satisfy the gate (right-reason check). */
  @Test
  void trustedHighValidCapsuleSatisfiesGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.test-high");
    handlers.register(id, (args, engineContext) -> OperationResult.success("ran"));
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeHighOp(id);
    InvocationProvenance trusted =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    String token = capsule.mint(id.value(), "{}");
    OperationResult result = executor.dispatch(op, "{}", trusted, Optional.of(token), io.justsearch.app.services.TestEngineContexts.forProvenance(trusted));
    assertTrue(result.success(), "a bound capsule authorizes the TRUSTED HIGH dispatch");
  }

  private static Operation makeHighOp(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.HIGH,
            new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  private static Operation makeMediumOp(OperationRef id) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.MEDIUM,
            new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            false),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * Default test Operation. Declares {@link AuditPolicy#METADATA_ONLY} — the production
   * default (35 of the 41 catalog declarations) and, since tempdoc 879 wired the axis, the
   * declaration that actually asks for a history entry. Tests that need the suppressing
   * declaration pass it explicitly via the 4-arg overload.
   */
  private static Operation makeOp(OperationRef id, TrustTier tier, boolean undoSupported) {
    return makeOp(id, tier, undoSupported, AuditPolicy.METADATA_ONLY);
  }

  private static Operation makeOp(
      OperationRef id, TrustTier tier, boolean undoSupported, AuditPolicy audit) {
    return makeOp(id, tier, undoSupported, audit, OperationKind.OPERATION);
  }

  private static Operation makeOp(
      OperationRef id, TrustTier tier, boolean undoSupported, AuditPolicy audit, OperationKind kind) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            RiskTier.LOW,
            ConfirmStrategy.None.INSTANCE,
            audit,
            RetryPolicy.noRetry(),
            Set.of(),
            undoSupported).withRecordKind(kind),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(tier, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  // ----------------------------------------------------------------------------------
  // Tempdoc 875 — the durable-grant narrowing rules, enforced at the ONE place that knows a
  // confirmation was skipped: the durable short-circuit in enforceTrustLattice.
  // ----------------------------------------------------------------------------------

  private static final String FAMILY = "file-operations";
  private static final OperationRef INGEST = new OperationRef("core.ingest-files");
  private static final OperationRef MUTATE = new OperationRef("core.file-operations");

  /** A lattice executor with durable grants wired, plus the argument scope that bounds them. */
  private OperationExecutorImpl latticeExecutorWithGrants(
      HandlerRegistry handlers,
      io.justsearch.app.services.intent.DurableGrantStore grants,
      io.justsearch.app.services.intent.DurableGrantScope scope) {
    OperationExecutorImpl executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            null,
            Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService());
    executor.setDurableGrantStore(grants, scope);
    return executor;
  }

  private static InvocationProvenance agentLoop() {
    return InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.AGENT_LOOP),
            ExecutorTag.UI, Instant.now(), Optional.empty());
  }

  private static Operation makeFamilyOp(OperationRef id, RiskTier risk) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
                risk,
                new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
                AuditPolicy.NONE,
                RetryPolicy.noRetry(),
                Set.of(),
                false)
            .withCapabilityFamily(FAMILY),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /**
   * Tempdoc 875 C.2 — the risk ceiling, observed at the gate rather than at the store. One family
   * grant on "file-operations", one agent-loop dispatch each: the MEDIUM member proceeds (560 §28's
   * axis preserved), the HIGH member is gated. Before this change both were satisfied identically
   * (UNTRUSTED × MEDIUM and UNTRUSTED × HIGH are BOTH TYPED_CONFIRM), so a single family grant
   * durably reduced the strongest ceremony the system has to nothing.
   */
  @Test
  void durableFamilyGrantSatisfiesMediumButNeverHigh() {
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(INGEST, (args, engineContext) -> OperationResult.success("ingested"));
    handlers.register(MUTATE, (args, engineContext) -> OperationResult.success("mutated"));
    var grants = new io.justsearch.app.services.intent.DurableGrantStore();
    grants.grantFamilyAllowAlways(FAMILY, io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
    // A permissive scope isolates the risk ceiling from the argument-scope rule under test below.
    OperationExecutorImpl executor =
        latticeExecutorWithGrants(handlers, grants, (op, args, engineContext) -> true);

    OperationResult medium =
        executor.dispatch(makeFamilyOp(INGEST, RiskTier.MEDIUM), "{}", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop()));
    assertTrue(medium.success(), "the MEDIUM family member is still auto-approved by the grant");

    assertThrows(
        ConfirmationRequiredException.class,
        () ->
            executor.dispatch(
                makeFamilyOp(MUTATE, RiskTier.HIGH), "{}", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "a durable grant never satisfies a HIGH-risk gate — destructive work costs a fresh gesture");
  }

  /** The per-operation grant carries the same payload, so it must hit the same ceiling. */
  @Test
  void durablePerOperationGrantDoesNotSatisfyHighRiskGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(MUTATE, (args, engineContext) -> OperationResult.success("mutated"));
    var grants = new io.justsearch.app.services.intent.DurableGrantStore();
    grants.grantAllowAlways(
        MUTATE.value(), io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
    OperationExecutorImpl executor =
        latticeExecutorWithGrants(handlers, grants, (op, args, engineContext) -> true);

    assertThrows(
        ConfirmationRequiredException.class,
        () ->
            executor.dispatch(
                makeFamilyOp(MUTATE, RiskTier.HIGH), "{}", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "'Always allow this action' cannot durably suppress a HIGH-risk gate");
    // Right-reason check: the SAME grant, on the same op at MEDIUM, does satisfy the gate — so the
    // throw above is the risk ceiling firing, not a missing/mismatched grant.
    assertTrue(
        executor
            .dispatch(makeFamilyOp(MUTATE, RiskTier.MEDIUM), "{}", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop()))
            .success(),
        "the refusal is risk-driven, not grant-absence");
  }

  /**
   * Tempdoc 875 C.3 — the argument scope. With a durable grant on the ingest op, in-root `paths`
   * proceed and out-of-root `paths` fall through to the capsule path (a confirm that names the path).
   * The out-of-root capability is preserved (811 C-2a); only the blanket-consent shortcut is gone.
   */
  @Test
  void durableGrantCoversInRootIngestButNotOutOfRoot(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path base) throws Exception {
    java.nio.file.Path root = java.nio.file.Files.createDirectory(base.resolve("indexed"));
    java.nio.file.Path outside = java.nio.file.Files.createDirectory(base.resolve("elsewhere"));
    java.nio.file.Path inside = java.nio.file.Files.createFile(root.resolve("notes.txt"));
    java.nio.file.Path secret = java.nio.file.Files.createFile(outside.resolve("id_rsa"));
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(INGEST, (args, engineContext) -> OperationResult.success("ingested"));
    var grants = new io.justsearch.app.services.intent.DurableGrantStore();
    grants.grantAllowAlways(
        INGEST.value(), io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
    var scope =
        new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    scope.bindIndexedRoots(engineContext -> List.of(root));
    OperationExecutorImpl executor = latticeExecutorWithGrants(handlers, grants, scope);
    Operation ingest = makeFamilyOp(INGEST, RiskTier.MEDIUM);

    assertTrue(
        executor.dispatch(ingest, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())).success(),
        "an in-root ingest is inside the containment the grant was granted against");
    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.dispatch(ingest, pathsArgs(secret), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "an out-of-root ingest is outside it ⇒ the grant does not apply ⇒ a fresh confirm");
  }

  /**
   * Adverse preconditions (`green-masked-destructive`): the in-root green above depends on the roots
   * lookup being available. Each way it can be unavailable must produce a CONFIRM, not a silent
   * proceed — the failure mode of the scope is a prompt, never an unforeseen ingest.
   */
  @Test
  void durableGrantFailsClosedWhenRootsAreUnavailable(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    java.nio.file.Path inside = java.nio.file.Files.createFile(root.resolve("notes.txt"));
    var grants = new io.justsearch.app.services.intent.DurableGrantStore();
    grants.grantAllowAlways(
        INGEST.value(), io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
    Operation ingest = makeFamilyOp(INGEST, RiskTier.MEDIUM);

    // (a) never bound — a wiring regression must cost a prompt, not a silent grant.
    var unbound = new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    assertThrows(
        ConfirmationRequiredException.class,
        () ->
            executorFor(grants, unbound)
                .dispatch(ingest, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "(a) roots supplier never bound ⇒ confirm");

    // (b) the lookup throws — e.g. the Worker is unavailable.
    var throwing = new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    throwing.bindIndexedRoots(
        engineContext -> {
          throw new IllegalStateException("Worker unavailable");
        });
    assertThrows(
        ConfirmationRequiredException.class,
        () ->
            executorFor(grants, throwing)
                .dispatch(ingest, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "(b) roots supplier throws ⇒ confirm");

    // (c) no roots configured — the "nothing is contained" reading, not the "everything is" one.
    var empty = new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    empty.bindIndexedRoots(engineContext -> List.of());
    assertThrows(
        ConfirmationRequiredException.class,
        () ->
            executorFor(grants, empty)
                .dispatch(ingest, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "(c) empty roots ⇒ confirm");

    // Right-reason control: the SAME grant + args DO proceed once the roots are actually bound, so
    // the three throws above are the adverse precondition firing, not a broken fixture.
    var bound = new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    bound.bindIndexedRoots(engineContext -> List.of(root));
    assertTrue(
        executorFor(grants, bound)
            .dispatch(ingest, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop()))
            .success());
  }

  private OperationExecutorImpl executorFor(
      io.justsearch.app.services.intent.DurableGrantStore grants,
      io.justsearch.app.services.intent.DurableGrantScope scope) {
    HandlerRegistry handlers = new HandlerRegistry();
    handlers.register(INGEST, (args, engineContext) -> OperationResult.success("ingested"));
    return latticeExecutorWithGrants(handlers, grants, scope);
  }

  private static String pathsArgs(java.nio.file.Path path) {
    return "{\"paths\":[\""
        + path.toAbsolutePath().toString().replace("\\", "\\\\")
        + "\"]}";
  }

  // ----------------------------------------------------------------------------------
  // Tempdoc 875 §C.7 — the reversal of an operation is an operation and inherits its risk
  // class. Before this, undo() checked undoSupported and delegated: enforceTrustLattice
  // never ran, so the reverse of a HIGH-risk op was dispatched with NO gate at all.
  // ----------------------------------------------------------------------------------

  /** An undoable operation at {@code risk}; the handler records whether its undo was reached. */
  private static Operation makeUndoableOp(OperationRef id, RiskTier risk) {
    return new Operation(
        id,
        Presentation.of(
            new I18nKey("test." + id.value()), new I18nKey("test." + id.value() + ".desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(
            risk,
            new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
            AuditPolicy.NONE,
            RetryPolicy.noRetry(),
            Set.of(),
            true),
        OperationAvailability.empty(),
        OperationLineage.empty(),
        Binding.of(id),
        new Provenance(TrustTier.CORE, "test", "1.0"),
        Set.of(ExecutorTag.AGENT));
  }

  /** Registers a handler whose {@code undo} flips {@code reached[0]} — the "did it dispatch?" probe. */
  private static void registerUndoProbe(HandlerRegistry handlers, OperationRef id, boolean[] reached) {
    handlers.register(
        id,
        new OperationHandler() {
          @Override
          public OperationResult execute(String args, EngineContext engineContext) {
            return OperationResult.success("ran", "exec-1");
          }

          @Override
          public OperationResult undo(String executionId, EngineContext engineContext) {
            reached[0] = true;
            return OperationResult.success("undone " + executionId);
          }
        });
  }

  /**
   * The headline: an undo whose FORWARD form the lattice would refuse is refused the same way, with
   * the same exception class — and the handler's undo is never reached. The control in the same
   * test is what makes it a gate rather than a coincidence: the identical dispatch of the forward
   * op throws the identical class.
   */
  @Test
  void undoOfAGatedOperationIsRefusedExactlyAsItsForwardFormIs() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.undoable-high");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());
    Operation op = makeUndoableOp(id, RiskTier.HIGH);
    // BUTTON is the strongest source tier there is (TRUSTED); TRUSTED × HIGH is TYPED_CONFIRM, so
    // even a direct user gesture must carry a capsule.
    InvocationProvenance button =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    ConfirmationRequiredException forward =
        assertThrows(
            ConfirmationRequiredException.class,
            () -> executor.dispatch(op, "{}", button, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(button)),
            "control: the forward form is gated");
    ConfirmationRequiredException reversal =
        assertThrows(
            ConfirmationRequiredException.class,
            () -> executor.undo(op, "exec-1", button, Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(button)),
            "the reversal inherits the forward form's risk class, so it meets the same gate");

    assertEquals(
        forward.gateBehavior(),
        reversal.gateBehavior(),
        "the same gate behavior, because it is the same lattice cell — not a parallel rule");
    assertFalse(undoReached[0], "the handler's undo must never be reached through a refused gate");
  }

  /**
   * The other half: the gate must not have turned undo off. A capsule bound to
   * ({@code op.id()}, {@link OperationDispatcher#undoArguments}) satisfies it and the reversal runs
   * — the same capsule mechanism the forward path uses, over the reversal's own canonical args.
   */
  @Test
  void aPermittedUndoStillRuns() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.undoable-high");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeUndoableOp(id, RiskTier.HIGH);
    InvocationProvenance button =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    String token = capsule.mint(id.value(), OperationDispatcher.undoArguments("exec-1"));
    OperationResult result = executor.undo(op, "exec-1", button, Optional.of(token), io.justsearch.app.services.TestEngineContexts.forProvenance(button));

    assertTrue(result.success(), "a bound capsule authorizes the reversal: " + result.message());
    assertEquals("undone exec-1", result.message());
    assertTrue(undoReached[0], "the handler's undo actually ran");
  }

  /**
   * A capsule minted for the FORWARD invocation does not authorize the reversal. Capsules are
   * args-bound; the reversal's arguments are its own. Without this the "same gate" claim would be
   * satisfiable by replaying the approval the user gave for the opposite action.
   */
  @Test
  void aForwardCapsuleDoesNotAuthorizeTheReversal() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.undoable-high");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    ConsentCapsuleService capsule = new ConsentCapsuleService();
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, capsule);
    Operation op = makeUndoableOp(id, RiskTier.HIGH);
    InvocationProvenance button =
        InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty());

    String forwardToken = capsule.mint(id.value(), "{}");

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.undo(op, "exec-1", button, Optional.of(forwardToken), io.justsearch.app.services.TestEngineContexts.forProvenance(button)),
        "the forward invocation's capsule is bound to the forward arguments, not the reversal's");
    assertFalse(undoReached[0], "and the handler's undo is not reached");
  }

  /** An AUTO cell (any source × LOW) is untouched: an undoable read-class op reverses freely. */
  @Test
  void anAutoGateLeavesUndoUnchanged() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.undoable-low");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());

    OperationResult result =
        executor.undo(makeUndoableOp(id, RiskTier.LOW), "exec-1", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop()));

    assertTrue(result.success(), "LOW is AUTO for every source tier — no new refusal");
    assertTrue(undoReached[0]);
  }

  /** The DENY arm: an engaged hard stop denies the reversal exactly as it denies the forward form. */
  @Test
  void anEngagedHardStopDeniesTheReversalToo() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.undoable-medium");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    var executor =
        new OperationExecutorImpl(attempts, admission,
            handlers,
            null,
            Map.of(),
            Clock.systemUTC(),
            new CoreTrustEvaluator(),
            CoreIntentSourceCatalog.catalog(),
            null,
            new ConsentCapsuleService());
    var hardStop = new GlobalHardStop();
    executor.setGlobalHardStop(hardStop);
    Operation op = makeUndoableOp(id, RiskTier.MEDIUM);
    hardStop.engage();

    assertThrows(
        TrustGateDeniedException.class,
        () -> executor.dispatch(op, "{}", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "control: the forward form is denied");
    assertThrows(
        TrustGateDeniedException.class,
        () -> executor.undo(op, "exec-1", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "the emergency circuit-breaker is not a forward-only control");
    assertFalse(undoReached[0]);

    hardStop.release();
  }

  /**
   * Tempdoc 875 C.3 applied to the reversal: a durable grant that legitimately covers a governed
   * FORWARD invocation (paths inside the indexed roots) does NOT carry over to its undo, because a
   * reversal has no path arguments and containment therefore cannot be proven. The user is asked
   * instead — a prompt, never a silent reversal.
   */
  @Test
  void aDurableGrantDoesNotSilentlyAuthorizeTheReversalOfAGovernedOperation(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
    java.nio.file.Path inside = java.nio.file.Files.createFile(root.resolve("notes.txt"));
    HandlerRegistry handlers = new HandlerRegistry();
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, INGEST, undoReached);
    var grants = new io.justsearch.app.services.intent.DurableGrantStore();
    grants.grantAllowAlways(INGEST.value(), io.justsearch.agent.api.registry.SourceTier.UNTRUSTED);
    var scope = new io.justsearch.app.services.intent.IndexedRootGrantScope(Set.of(INGEST));
    scope.bindIndexedRoots(engineContext -> List.of(root));
    OperationExecutorImpl executor = latticeExecutorWithGrants(handlers, grants, scope);
    // Same shape as makeFamilyOp, but undoable — the family + MEDIUM risk is what the grant covers.
    Operation governed =
        new Operation(
            INGEST,
            Presentation.of(new I18nKey("test.ingest"), new I18nKey("test.ingest.desc")),
            Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
            new OperationPolicy(
                    RiskTier.MEDIUM,
                    new ConfirmStrategy.Typed(new I18nKey("test.confirm")),
                    AuditPolicy.NONE,
                    RetryPolicy.noRetry(),
                    Set.of(),
                    true)
                .withCapabilityFamily(FAMILY),
            OperationAvailability.empty(),
            OperationLineage.empty(),
            Binding.of(INGEST),
            new Provenance(TrustTier.CORE, "test", "1.0"),
            Set.of(ExecutorTag.AGENT));

    // Control: the grant DOES cover the forward invocation whose paths are inside a root.
    assertTrue(
        executor
            .dispatch(governed, pathsArgs(inside), agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop()))
            .success(),
        "control: the durable grant covers the in-root forward invocation");

    assertThrows(
        ConfirmationRequiredException.class,
        () -> executor.undo(governed, "exec-1", agentLoop(), Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(agentLoop())),
        "…but it cannot cover a reversal, whose arguments name no path to contain");
    assertFalse(undoReached[0]);
  }

  /** An operation that does not support undo still fails fast, ahead of any gate evaluation. */
  @Test
  void undoUnsupportedStillFailsFastAheadOfTheGate() {
    HandlerRegistry handlers = new HandlerRegistry();
    OperationRef id = new OperationRef("core.high-no-undo");
    boolean[] undoReached = {false};
    registerUndoProbe(handlers, id, undoReached);
    OperationDispatcher executor = latticeExecutorWithCapsule(handlers, new ConsentCapsuleService());

    OperationResult result =
        executor.undo(
            makeHighOp(id),
            "exec-1",
            InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty()),
            Optional.empty(), io.justsearch.app.services.TestEngineContexts.forProvenance(InvocationProvenance.fromEngineContext(
            io.justsearch.app.services.TestEngineContexts.forTransport(TransportTag.BUTTON),
            ExecutorTag.UI, Instant.now(), Optional.empty())));

    assertFalse(result.success());
    assertTrue(
        result.message().contains("Undo not supported"),
        "a typed denial, not a confirmation prompt for something that can never run: "
            + result.message());
    assertFalse(undoReached[0]);
  }

  /** The canonical reversal arguments are valid JSON and escape a hostile execution id. */
  @Test
  void undoArgumentsAreEscapedJson() {
    assertEquals("{\"executionId\":\"exec-1\"}", OperationDispatcher.undoArguments("exec-1"));
    assertEquals(
        "{\"executionId\":\"a\\\"b\\\\c\"}",
        OperationDispatcher.undoArguments("a\"b\\c"),
        "a quote or backslash in the id must not be able to reshape the arguments object");
  }
}
