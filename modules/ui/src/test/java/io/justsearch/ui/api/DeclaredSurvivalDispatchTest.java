/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.EngineAdmissionException;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.engine.EngineAdmissionController;
import io.justsearch.app.observability.operations.OperationAttemptRunnerImpl;
import io.justsearch.app.observability.operations.SqliteOperationStore;
import io.justsearch.app.services.intent.*;
import io.justsearch.app.services.registry.executor.OperationExecutorImpl;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DeclaredSurvivalDispatchTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void capacityAndFreezeLeavePreparedConsentUsableForTheSameKey(boolean freeze) throws Exception {
    try (var fixture = new Fixture(directory); var caller = fixture.admission.admit(fixture.origin, false)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, true);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}", SourceTier.UNTRUSTED,
          key, ready.preparationNonce());
      EngineWorkHandle blocker = freeze ? null : fixture.admission.admit(fixture.origin, false);
      String preparation = freeze ? fixture.admission.freezeAdmission("test admission").preparationId() : null;
      try {
        EngineAdmissionException refusal = assertThrows(EngineAdmissionException.class,
            () -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
        assertEquals(freeze ? EngineAdmissionException.Reason.FROZEN : EngineAdmissionException.Reason.CONTEXT_LIMIT,
            refusal.reason());
        assertTrue(fixture.store.find(key).isEmpty());
        assertEquals(0, fixture.effects.get());
      } finally {
        if (blocker != null) blocker.close();
        if (preparation != null) fixture.admission.releaseAdmission(preparation);
      }
      assertTrue(fixture.dispatch(caller.context(), key, token, ready.preparationNonce()).success());
      assertEquals(1, fixture.effects.get());
      assertEquals(OperationState.RUNNING, fixture.store.find(key).orElseThrow().state());
      assertEquals(EngineContext.Survival.DURABLE, fixture.store.find(key).orElseThrow().context().survival());
      assertEquals(2, fixture.admission.activeWorkCount());
      caller.cancel("waiting caller cancelled");
      try (var child = fixture.admission.attach(fixture.effectContext.get())) {
        assertTrue(child.cancellationReason().isEmpty());
        assertEquals(EngineContext.Urgency.BACKGROUND, child.context().urgency());
      }
      fixture.complete();
      assertEquals(1, fixture.admission.activeWorkCount());
      assertEquals(OperationState.COMPLETE, fixture.store.find(key).orElseThrow().state());
    }
  }

  @Test
  void normalCallerCompletionDetachesDurableChildWithoutRetainingParent() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var caller = fixture.admission.admit(fixture.origin, false);
      fixture.start(caller.context());
      caller.close();
      assertEquals(1, fixture.admission.activeWorkCount(), "handoff cannot retain its caller until child completion");
      try (var child = fixture.admission.attach(fixture.effectContext.get())) {
        assertEquals(EngineContext.Urgency.BACKGROUND, child.context().urgency());
        assertTrue(child.cancellationReason().isEmpty());
      }
      fixture.complete();
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void alreadyCancelledCallerCannotAcceptOrConsumeConsent() throws Exception {
    try (var fixture = new Fixture(directory); var caller = fixture.admission.admit(fixture.origin, false)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, true);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}", SourceTier.UNTRUSTED,
          key, ready.preparationNonce());
      caller.cancel("cancel before dispatch");
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
      assertTrue(fixture.store.find(key).isEmpty());
      assertEquals(0, fixture.effects.get());
      assertEquals(1, fixture.admission.activeWorkCount());
      assertTrue(fixture.capsules.verifyPreparedAndConsume(token, fixture.operation.id().value(), "{}", key,
          ready.preparationNonce()), "pre-accept cancellation must leave consent unspent");
    }
  }

  @Test
  void consumedSubscriberCanCloseCallerAfterAcceptanceBeforeHandlerEntry() throws Exception {
    try (var fixture = new Fixture(directory)) {
      var caller = fixture.admission.admit(fixture.origin, false);
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());
      var consumed = new AtomicInteger();
      fixture.capsules.setGrantEventSink(event -> {
        if (event instanceof io.justsearch.app.observability.ledger.ActionEvent.Grant grant
            && "CONSUMED".equals(grant.action())) {
          consumed.incrementAndGet();
          caller.close();
        }
      });

      assertTrue(fixture.dispatch(caller.context(), key, token, ready.preparationNonce()).success());

      OperationRecord accepted = fixture.store.find(key).orElseThrow();
      EngineContext handler = fixture.effectContext.get();
      assertEquals(1, consumed.get());
      assertEquals(OperationState.RUNNING, accepted.state());
      assertEquals(EngineContext.Urgency.FOREGROUND, accepted.context().urgency(),
          "the immutable acceptance row records the caller-present snapshot");
      assertEquals(EngineContext.Urgency.BACKGROUND, handler.urgency(),
          "the handler reads the live owner after the accepted caller has closed");
      assertTrue(accepted.context().workId().isEmpty(), "durable rows omit process-local work ids");
      assertTrue(handler.workId().isPresent());
      try (var owner = fixture.admission.attach(handler)) {
        assertEquals(handler.workId(), owner.context().workId());
        assertEquals(EngineContext.Survival.DURABLE, owner.context().survival());
      }
      assertEquals(accepted.context().grantReference(), handler.grantReference());
      assertEquals(Optional.of(new OperationAuthorizationBasis.EphemeralCapsule().encode()),
          handler.grantReference());
      assertEquals(1, fixture.handlerActiveOwners.get(),
          "only the durable child remains when the handler begins");
      assertEquals(1, fixture.admission.activeWorkCount());

      fixture.complete();
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void interactiveOverrideRemainsCancellableWithoutCancellingDurableParent() throws Exception {
    try (var fixture = new Fixture(directory, 4, false, EngineContext.Survival.DURABLE,
        EngineContext.Survival.INTERACTIVE);
        var caller = fixture.admission.admit(fixture.origin, false)) {
      fixture.start(caller.context());
      assertNotEquals(caller.context().workId(), fixture.effectContext.get().workId());
      assertEquals(EngineContext.Survival.INTERACTIVE, fixture.effectContext.get().survival());
      fixture.admission.cancelInteractive("cancel interactive override");
      assertTrue(caller.cancellationReason().isEmpty());
      try (var child = fixture.admission.attach(fixture.effectContext.get())) {
        assertEquals(Optional.of("cancel interactive override"), child.cancellationReason());
      }
      fixture.completion.completeExceptionally(new java.util.concurrent.CancellationException("producer exited"));
      assertEquals(1, fixture.admission.activeWorkCount());
    }
  }

  @Test
  void cancelledDurableCallerCannotAcceptInteractiveOverrideOrSpendConsent() throws Exception {
    try (var fixture = new Fixture(directory, 4, false, EngineContext.Survival.DURABLE,
        EngineContext.Survival.INTERACTIVE);
        var caller = fixture.admission.admit(fixture.origin, false)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());
      caller.cancel("cancel before interactive acceptance");
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
      assertTrue(fixture.store.find(key).isEmpty());
      assertEquals(0, fixture.effects.get());
      assertEquals(1, fixture.admission.activeWorkCount());
      assertTrue(fixture.capsules.verifyPreparedAndConsume(token, fixture.operation.id().value(), "{}",
          key, ready.preparationNonce()));
    }
  }

  @Test
  void interactiveCancellationDuringAcceptanceWaitsForHandoffAndPreventsHandler() throws Exception {
    try (var fixture = new Fixture(directory, 4, true, EngineContext.Survival.DURABLE,
        EngineContext.Survival.INTERACTIVE);
        var caller = fixture.admission.admit(fixture.origin, false);
        var calls = Executors.newFixedThreadPool(2)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());
      var dispatch = calls.submit(() -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
      assertTrue(fixture.barrier.entered.await(2, TimeUnit.SECONDS));
      var cancelStarted = new CountDownLatch(1);
      var cancel = calls.submit(() -> {
        cancelStarted.countDown();
        fixture.admission.cancelInteractive("cancel during acceptance");
      });
      try {
        assertTrue(cancelStarted.await(2, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> cancel.get(200, TimeUnit.MILLISECONDS),
            "cancellation delivery must wait for the in-progress acceptance handoff");
      } finally {
        fixture.barrier.release.countDown();
      }
      cancel.get(2, TimeUnit.SECONDS);
      assertInstanceOf(java.util.concurrent.CancellationException.class,
          assertThrows(ExecutionException.class, () -> dispatch.get(2, TimeUnit.SECONDS)).getCause());
      assertEquals(OperationState.CANCELLED, fixture.store.find(key).orElseThrow().state());
      assertEquals(0, fixture.effects.get());
      assertEquals(1, fixture.barrier.consumptions.get());
      assertEquals(1, fixture.admission.activeWorkCount());
      assertTrue(caller.cancellationReason().isEmpty());
    }
  }

  @Test
  void acceptedInteractiveOverrideCancelledByPublicationNeverEntersHandler() throws Exception {
    try (var fixture = new Fixture(directory, 4, false, EngineContext.Survival.DURABLE,
        EngineContext.Survival.INTERACTIVE);
        var caller = fixture.admission.admit(fixture.origin, false)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());
      fixture.capsules.setGrantEventSink(event -> {
        if (event instanceof io.justsearch.app.observability.ledger.ActionEvent.Grant grant
            && "CONSUMED".equals(grant.action())) fixture.admission.cancelInteractive("publication cancelled child");
      });
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
      assertEquals(OperationState.CANCELLED, fixture.store.find(key).orElseThrow().state());
      assertEquals(0, fixture.effects.get());
      assertEquals(1, fixture.admission.activeWorkCount());
      assertTrue(caller.cancellationReason().isEmpty());
    }
  }

  @Test
  void missingConsentPreviewCanReenterTheRunnerAndValidConsentDoesNotRecomputeIt()
      throws Exception {
    try (var fixture = new Fixture(directory);
        var caller = fixture.admission.admit(fixture.origin, false);
        var previewThread = Executors.newSingleThreadExecutor()) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      OperationAttemptRunner.Request request = fixture.request(key, caller.context());
      fixture.previewAction.set(() -> {
        var reentry = previewThread.submit(() -> {
          assertTrue(fixture.runner.withPreparation(request, scope -> scope.existing()).isEmpty());
          assertTrue(fixture.runner.lookup(request).isEmpty());
        });
        try {
          reentry.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new AssertionError("approval preview reentry was interrupted", interrupted);
        } catch (ExecutionException | TimeoutException failure) {
          throw new AssertionError("approval preview could not reenter the released key stripe", failure);
        }
      });

      ConfirmationRequiredException required = assertThrows(ConfirmationRequiredException.class,
          () -> fixture.executor.dispatch(fixture.operation, "{}", fixture.provenance,
              Optional.empty(), caller.context(), key, ready.preparationNonce()));
      assertEquals("Durable admission fixture", required.approvalPreview().summary());
      assertEquals(1, fixture.previews.get());
      assertTrue(fixture.store.find(key).isEmpty());
      assertEquals(1, fixture.admission.activeWorkCount(),
          "the temporary child reservation is released before preview publication");

      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());
      assertTrue(fixture.dispatch(caller.context(), key, token, ready.preparationNonce()).success());
      assertEquals(1, fixture.previews.get(),
          "a valid capsule reaches the effect without recomputing an approval preview");
      assertEquals(1, fixture.effects.get());
      fixture.complete();
    }
  }

  @Test
  void concurrentSameKeyCallsShareOneChildAcceptanceEffectAndConsentConsumption()
      throws Exception {
    try (var fixture = new Fixture(directory, 2, true);
        var caller = fixture.admission.admit(fixture.origin, false);
        var calls = Executors.newFixedThreadPool(2)) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) fixture.executor.prepare(fixture.operation, "{}",
          fixture.provenance, caller.context(), key, false);
      String token = fixture.capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, ready.preparationNonce());

      var first = calls.submit(
          () -> fixture.dispatch(caller.context(), key, token, ready.preparationNonce()));
      assertTrue(fixture.barrier.entered.await(2, TimeUnit.SECONDS));
      assertEquals(2, fixture.admission.activeWorkCount(),
          "one caller plus one reserved durable child exhaust the real limit");
      var secondStarted = new CountDownLatch(1);
      var second = calls.submit(() -> {
        secondStarted.countDown();
        return fixture.dispatch(caller.context(), key, token, ready.preparationNonce());
      });
      assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
      assertFalse(second.isDone(), "the competing same-key call waits for the acceptance winner");
      fixture.barrier.release.countDown();

      OperationResult firstResult = first.get(2, TimeUnit.SECONDS);
      OperationResult secondResult = second.get(2, TimeUnit.SECONDS);
      assertTrue(firstResult.success());
      assertTrue(secondResult.success());
      assertEquals(firstResult.structuredData().get("operationRecordId"),
          secondResult.structuredData().get("operationRecordId"));
      assertEquals(1, fixture.barrier.consumptions.get());
      assertEquals(1, fixture.effects.get());
      assertEquals(2, fixture.admission.activeWorkCount());

      caller.close();
      assertEquals(1, fixture.admission.activeWorkCount());
      fixture.complete();
      assertEquals(0, fixture.admission.activeWorkCount());
    }
  }

  private static final class Fixture implements AutoCloseable {
    final EngineAdmissionController admission;
    final EngineContext origin;
    final InvocationProvenance provenance;
    final ConsentCapsuleService capsules = new ConsentCapsuleService();
    final BarrierCapsules barrier;
    final AtomicInteger effects = new AtomicInteger();
    final AtomicInteger previews = new AtomicInteger();
    final AtomicInteger handlerActiveOwners = new AtomicInteger();
    final AtomicReference<EngineContext> effectContext = new AtomicReference<>();
    final AtomicReference<Runnable> previewAction = new AtomicReference<>(() -> {});
    final CompletableFuture<OperationResult> completion = new CompletableFuture<>();
    final SqliteOperationStore store;
    final OperationAttemptRunnerImpl runner;
    final Operation operation;
    final OperationExecutorImpl executor;

    Fixture(Path directory) throws Exception {
      this(directory, 4, false);
    }

    Fixture(Path directory, int aggregateLimit, boolean barrierConsent) throws Exception {
      this(directory, aggregateLimit, barrierConsent, EngineContext.Survival.INTERACTIVE,
          EngineContext.Survival.DURABLE);
    }

    Fixture(Path directory, int aggregateLimit, boolean barrierConsent,
        EngineContext.Survival callerSurvival, EngineContext.Survival effectSurvival) throws Exception {
      var incoming = TestRequestContexts.mcp("survival-dispatch");
      origin = new EngineContext(incoming.clientKind(), incoming.clientId(), incoming.sessionId(),
          incoming.grantReference(), incoming.sourceTier(), incoming.transport(), callerSurvival, incoming.urgency());
      provenance = EngineProvenance.invocation(origin, ExecutorTag.AGENT, Clock.systemUTC().instant(), Optional.empty());
      admission = new EngineAdmissionController(2, aggregateLimit, 1);
      barrier = barrierConsent ? new BarrierCapsules(capsules) : null;
      store = new SqliteOperationStore(directory.resolve("operations.db"));
      var id = new OperationRef("core.durable-admission-fixture");
      operation = new Operation(id, Presentation.of(new I18nKey("test.admission"), new I18nKey("test.admission.desc")),
          Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
          new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY,
              RetryPolicy.noRetry(), Set.of(), false).withRecordKind(OperationKind.NOTE)
              .withDeclaredSurvival(effectSurvival),
          OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
          new Provenance(TrustTier.CORE, "test", "1"), Set.of(ExecutorTag.AGENT));
      var handlers = new HandlerRegistry();
      handlers.register(id, new OperationHandler() {
        @Override public OperationResult execute(String args, EngineContext context) { throw new AssertionError("raw effect"); }
        @Override public OperationPreparation prepare(String args, InvocationProvenance provenance, EngineContext context) {
          return new OperationPreparation(args, "admission-fixture.v1", "{}");
        }
        @Override public void validatePreparation(OperationPreparation value) {
          if (!"admission-fixture.v1".equals(value.replaySchema())) throw new IllegalArgumentException("schema");
        }
        @Override public OperationApprovalPreview approvalPreview(OperationPreparation value) {
          previews.incrementAndGet();
          previewAction.get().run();
          return new OperationApprovalPreview("Durable admission fixture");
        }
        @Override public OperationExecution executePrepared(OperationPreparation value, InvocationProvenance provenance,
            EngineContext context, OperationRecordHandle handle) {
          effects.incrementAndGet();
          effectContext.set(context);
          handlerActiveOwners.set(admission.activeWorkCount());
          return new OperationExecution(OperationResult.success("started"), completion);
        }
      });
      runner = new OperationAttemptRunnerImpl(store, Clock.systemUTC(), Set.of(OperationKind.NOTE));
      executor = new OperationExecutorImpl(runner,
          admission, handlers, null, Map.of(), Clock.systemUTC(), new CoreTrustEvaluator(),
          CoreIntentSourceCatalog.catalog(), null, barrier == null ? capsules : barrier);
    }

    void start(EngineContext caller) {
      String key = OperationKeys.generate(Clock.systemUTC());
      var ready = (OperationDispatchPlan.Ready) executor.prepare(operation, "{}", provenance, caller, key, true);
      String token = capsules.mintPrepared(operation.id().value(), "{}", SourceTier.UNTRUSTED, key, ready.preparationNonce());
      assertTrue(dispatch(caller, key, token, ready.preparationNonce()).success());
    }

    OperationResult dispatch(EngineContext caller, String key, String token, java.util.UUID nonce) {
      return executor.dispatch(operation, "{}", provenance, Optional.of(token), caller, key, nonce);
    }

    OperationAttemptRunner.Request request(String key, EngineContext caller) {
      return new OperationAttemptRunner.Request(key,
          OperationDescriptor.invocation(OperationKind.NOTE, operation.id().value(), "{}", false),
          EngineProvenance.forOperation(caller, operation.policy()), provenance,
          OperationHistoryMode.STANDARD);
    }

    void complete() { completion.complete(OperationResult.success("finished")); }
    @Override public void close() throws Exception { complete(); store.close(); }
  }

  private static final class BarrierCapsules implements ConsentCapsuleAuthority {
    final ConsentCapsuleService delegate;
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    final AtomicInteger consumptions = new AtomicInteger();

    BarrierCapsules(ConsentCapsuleService delegate) {
      this.delegate = delegate;
    }

    @Override
    public String mint(String operationId, String argumentsJson, SourceTier sourceTier) {
      return delegate.mint(operationId, argumentsJson, sourceTier);
    }

    @Override
    public boolean verifyAndConsume(String token, String operationId, String argumentsJson) {
      return delegate.verifyAndConsume(token, operationId, argumentsJson);
    }

    @Override
    public Optional<Consumption> consumeDeferred(
        String token, String operationId, String argumentsJson) {
      return delegate.consumeDeferred(token, operationId, argumentsJson);
    }

    @Override
    public String mintPrepared(String operationId, String argumentsJson, SourceTier sourceTier,
        String operationKey, java.util.UUID preparationNonce) {
      return delegate.mintPrepared(
          operationId, argumentsJson, sourceTier, operationKey, preparationNonce);
    }

    @Override
    public boolean verifyPreparedAndConsume(String token, String operationId, String argumentsJson,
        String operationKey, java.util.UUID preparationNonce) {
      return delegate.verifyPreparedAndConsume(
          token, operationId, argumentsJson, operationKey, preparationNonce);
    }

    @Override
    public Optional<Consumption> consumePreparedDeferred(String token, String operationId,
        String argumentsJson, String operationKey, java.util.UUID preparationNonce) {
      consumptions.incrementAndGet();
      entered.countDown();
      try {
        if (!release.await(2, TimeUnit.SECONDS)) {
          throw new AssertionError("consent barrier was not released");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new AssertionError(interrupted);
      }
      return delegate.consumePreparedDeferred(
          token, operationId, argumentsJson, operationKey, preparationNonce);
    }
  }
}
