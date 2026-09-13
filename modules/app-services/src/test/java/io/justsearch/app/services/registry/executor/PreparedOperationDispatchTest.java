/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.api.operations.*;
import io.justsearch.app.observability.operations.*;
import io.justsearch.app.services.TestEngineContexts;
import io.justsearch.app.services.intent.*;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreparedOperationDispatchTest {
  @TempDir Path directory;
  private static final Clock CLOCK = Clock.systemUTC();
  private static final OperationRef ID = new OperationRef("core.prepared-fixture");
  private static final EngineContext ORIGIN = TestEngineContexts.mcp();
  private static final InvocationProvenance PROVENANCE = EngineProvenance.invocation(
      ORIGIN, ExecutorTag.AGENT, CLOCK.instant(), Optional.empty());

  private static Operation operation() {
    return new Operation(ID, Presentation.of(new I18nKey("test.prepared"), new I18nKey("test.prepared.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.MEDIUM, ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(), Set.of(), true).withRecordKind(OperationKind.NOTE),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(ID),
        new Provenance(TrustTier.CORE, "test", "1"), Set.of(ExecutorTag.AGENT));
  }

  private static EngineAdmissionService admission() {
    var admission = mock(EngineAdmissionService.class);
    org.mockito.stubbing.Answer<EngineWorkHandle> owner = call -> {
      EngineContext supplied = call.getArgument(0);
      var work = mock(EngineWorkHandle.class);
      when(work.context()).thenReturn(supplied.workId().isPresent() ? supplied : supplied.withWorkId(UUID.randomUUID()));
      when(work.retain()).thenReturn(work);
      return work;
    };
    when(admission.attach(any())).thenAnswer(owner);
    when(admission.admit(any(), anyBoolean())).thenAnswer(owner);
    return admission;
  }

  private static OperationExecutorImpl executor(SqliteOperationStore store, HandlerRegistry handlers,
      ConsentCapsuleService capsules) {
    return new OperationExecutorImpl(new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.NOTE)),
        admission(), handlers, null, Map.of(), CLOCK, new CoreTrustEvaluator(),
        CoreIntentSourceCatalog.catalog(), null, capsules);
  }

  private static final class Fixture implements OperationHandler {
    final AtomicInteger prepares = new AtomicInteger();
    final AtomicInteger effects = new AtomicInteger();
    final AtomicReference<String> target = new AtomicReference<>("original-target");
    final AtomicReference<String> used = new AtomicReference<>();
    final AtomicReference<EngineContext> usedContext = new AtomicReference<>();
    final AtomicReference<InvocationProvenance> usedProvenance = new AtomicReference<>();
    final OperationPreparation.Content content;
    Runnable beforePrepare = () -> {};
    boolean previewSupported = true;
    Fixture() { this(OperationPreparation.Content.METADATA); }
    Fixture(OperationPreparation.Content content) { this.content = content; }
    @Override public OperationResult execute(String args, EngineContext context) {
      throw new AssertionError("Frozen execution must not use raw dispatch");
    }
    @Override public OperationPreparation prepare(String args, InvocationProvenance provenance, EngineContext context) {
      prepares.incrementAndGet(); String frozen = target.get(); beforePrepare.run();
      return new OperationPreparation(args, "fixture.v1", "{\"target\":\"" + frozen + "\"}", content);
    }
    @Override public OperationPreparation prepareUndo(String id, InvocationProvenance provenance, EngineContext context) {
      return prepare(OperationDispatcher.undoArguments(id), provenance, context);
    }
    @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
      if (!previewSupported) return OperationHandler.super.approvalPreview(prepared);
      String selected = tools.jackson.databind.json.JsonMapper.builder().build()
          .readTree(prepared.replayPayloadJson()).path("target").asText();
      return new OperationApprovalPreview(content == OperationPreparation.Content.CONTENT
          ? "Write to the selected private note target" : "Write to " + selected);
    }
    @Override public void validatePreparation(OperationPreparation prepared) {
      if (!"fixture.v1".equals(prepared.replaySchema()) || prepared.content() != content)
        throw new IllegalArgumentException("Unsupported fixture format");
      var payload = tools.jackson.databind.json.JsonMapper.builder().build().readTree(prepared.replayPayloadJson());
      if (!payload.isObject() || !payload.path("target").isTextual() || payload.size() != 1)
        throw new IllegalArgumentException("Invalid fixture payload");
    }
    @Override public OperationExecution executePrepared(OperationPreparation prepared,
        InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
      effects.incrementAndGet(); used.set(prepared.replayPayloadJson()); usedContext.set(context); usedProvenance.set(provenance);
      assertNotNull(record);
      return OperationExecution.finished(OperationResult.success("written"));
    }
    @Override public OperationExecution undoPrepared(OperationPreparation prepared, String executionId,
        InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
      assertEquals(OperationDispatcher.undoArguments(executionId), prepared.argumentsJson());
      return executePrepared(prepared, provenance, context, record);
    }
    HandlerRegistry registry() {
      var handlers = new HandlerRegistry(); handlers.register(ID, this); return handlers;
    }
  }

  private static OperationResult invoke(OperationExecutorImpl executor, boolean undo, String key, UUID nonce,
      Optional<String> token) {
    return undo ? executor.undo(operation(), "prior-operation", PROVENANCE, token, ORIGIN, key, nonce)
        : executor.dispatch(operation(), "{}", PROVENANCE, token, ORIGIN, key, nonce);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void gatedInvocationPersistsBeforeApprovalAndReusesFrozenTargetAfterRestart(boolean undo) throws Exception {
    var fixture = new Fixture();
    var handlers = fixture.registry();
    var capsules = new ConsentCapsuleService();
    String key = OperationKeys.generate(CLOCK);
    UUID nonce;
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, handlers, capsules);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, undo, key, null, Optional.empty()));
      assertEquals(1, fixture.prepares.get(), "The gate must refer to a preparation already persisted");
      assertEquals(key, gated.operationKey());
      nonce = gated.preparationNonce(); assertNotNull(nonce);
      assertTrue(store.openRecords().isEmpty(), "An unapproved preparation is not an accepted attempt");
    }
    fixture.target.set("changed-target");
    fixture.previewSupported = false; // approved execution and receipts must not compute display again
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, handlers, capsules);
      String token = capsules.mintPrepared(ID.value(), undo ? OperationDispatcher.undoArguments("prior-operation") : "{}", SourceTier.UNTRUSTED, key, nonce);
      var result = invoke(executor, undo, key, nonce, Optional.of(token));
      assertTrue(result.success());
      assertTrue(fixture.used.get().contains("original-target"));
      assertFalse(fixture.used.get().contains("changed-target"));
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
      var retry = invoke(executor, undo, key, null, Optional.empty());
      assertTrue(retry.success()); assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
      assertEquals(key, retry.structuredData().get("operationKey"));
      var row = store.find(key).orElseThrow();
      assertEquals(OperationState.COMPLETE, row.state());
      assertTrue(store.acceptedPreparation(row.id()).isPresent());
    }
  }

  @Test
  void approvalPreviewComesFromFrozenValueAfterReopen() throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService();
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), capsules);
      var gate = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      assertNotNull(gate.approvalPreview(), "The gate must display the server-selected target");
      assertEquals("Write to original-target", gate.approvalPreview().summary());
      assertFalse(gate.toString().contains("original-target"));
    }
    fixture.target.set("different-target");
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), capsules);
      var gate = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      assertNotNull(gate.approvalPreview(), "The gate must display the server-selected target");
      assertEquals("Write to original-target", gate.approvalPreview().summary());
      assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
    }
  }

  @Test
  void replayHandlerWithoutPreviewCannotOfferAnIncompleteApproval() throws Exception {
    var fixture = new Fixture(); fixture.previewSupported = false;
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), new ConsentCapsuleService());
      assertThrows(UnsupportedOperationException.class,
          () -> invoke(executor, false, OperationKeys.generate(CLOCK), null, Optional.empty()));
      assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      assertTrue(store.openRecords().isEmpty());
    }
  }

  private static void expirePending(Path directory, String key) throws Exception {
    try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("operations.db"));
        var update = connection.prepareStatement("UPDATE operation_preparations SET expires_at=0 WHERE operation_key=?")) {
      update.setString(1, key); assertEquals(1, update.executeUpdate());
    }
  }

  @Test
  void staleOrUnknownApprovalNeverPreparesAReplacement() throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService();
    String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), capsules);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      expirePending(directory, key);
      String oldToken = capsules.mintPrepared(ID.value(), "{}", SourceTier.UNTRUSTED, key, gated.preparationNonce());
      assertEquals(OperationStoreException.Code.OPERATION_PREPARATION_UNAVAILABLE,
          assertThrows(OperationStoreException.class,
              () -> invoke(executor, false, key, gated.preparationNonce(), Optional.of(oldToken))).code());
      assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      assertThrows(OperationStoreException.class,
          () -> invoke(executor, false, OperationKeys.generate(CLOCK), UUID.randomUUID(), Optional.empty()));
      assertEquals(1, fixture.prepares.get());
      fixture.target.set("replacement");
      var replacement = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      assertNotEquals(gated.preparationNonce(), replacement.preparationNonce());
      assertThrows(OperationStoreException.class,
          () -> invoke(executor, false, key, gated.preparationNonce(), Optional.of(oldToken)));
      assertEquals(2, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      assertTrue(store.openRecords().isEmpty());
    }
  }

  @Test
  void lockedContentRefusesPendingButTerminalReceiptBypassesDecode() throws Exception {
    var fixture = new Fixture(OperationPreparation.Content.CONTENT); fixture.target.set("private-note-body");
    var manager = new io.justsearch.app.services.encryption.DataKeyManager(
        new io.justsearch.app.services.encryption.EncryptionKeystore(directory));
    manager.setup("test-password".toCharArray());
    var capsules = new ConsentCapsuleService(); String key = OperationKeys.generate(CLOCK);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = new OperationExecutorImpl(new OperationAttemptRunnerImpl(store, CLOCK, Set.of()),
          admission(), fixture.registry(), null, Map.of(), CLOCK, new CoreTrustEvaluator(),
          CoreIntentSourceCatalog.catalog(), null, capsules, null, new io.justsearch.agent.api.encryption.StoreCipher(manager));
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      var pending = store.pendingPreparation(key, OperationDescriptor.invocation(OperationKind.NOTE, ID.value(), "{}", false)).orElseThrow();
      assertTrue(pending.payload().sealed()); assertFalse(pending.payload().value().contains("private-note-body"));
      manager.lock();
      assertThrows(io.justsearch.agent.api.encryption.KeyLockedException.class,
          () -> invoke(executor, false, key, gated.preparationNonce(), Optional.empty()));
      assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      manager.unlock("test-password".toCharArray());
      String token = capsules.mintPrepared(ID.value(), "{}", SourceTier.UNTRUSTED, key, gated.preparationNonce());
      assertTrue(invoke(executor, false, key, gated.preparationNonce(), Optional.of(token)).success());
      manager.lock();
      assertTrue(invoke(executor, false, key, UUID.randomUUID(), Optional.empty()).success());
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> executor.dispatch(operation(), "{\"changed\":true}",
              PROVENANCE, Optional.empty(), ORIGIN, key)).code());
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
    }
  }

  @Test
  void unconfiguredContentCannotPersistPlaintextOrReachApproval() throws Exception {
    var fixture = new Fixture(OperationPreparation.Content.CONTENT);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), new ConsentCapsuleService());
      String key = OperationKeys.generate(CLOCK);
      assertThrows(io.justsearch.agent.api.encryption.KeyLockedException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      assertTrue(store.openRecords().isEmpty());
      assertTrue(store.pendingPreparation(key, OperationDescriptor.invocation(OperationKind.NOTE, ID.value(), "{}", false)).isEmpty());
      assertEquals(0, fixture.effects.get());
    }
  }

  @Test
  void crossClientRetryPreservesFrozenOriginWithoutBorrowingItsWorkId() throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService(); var admission = admission();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = new OperationExecutorImpl(new OperationAttemptRunnerImpl(store, CLOCK, Set.of()),
          admission, fixture.registry(), null, Map.of(), CLOCK, new CoreTrustEvaluator(), CoreIntentSourceCatalog.catalog(), null, capsules);
      String key = OperationKeys.generate(CLOCK);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      // The current caller is authorized by its own current trust lattice, while frozen
      // original attribution selects a fresh child rather than relabelling this work handle.
      var current = TestEngineContexts.ui().withWorkId(UUID.randomUUID());
      var currentProvenance = EngineProvenance.invocation(current, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
      assertTrue(executor.dispatch(operation(), "{}", currentProvenance, Optional.empty(), current, key, gated.preparationNonce()).success());
      verify(admission).attach(ORIGIN);
      assertEquals(ORIGIN.clientId(), fixture.usedContext.get().clientId());
      assertNotEquals(current.workId(), fixture.usedContext.get().workId());
      assertEquals(PROVENANCE, fixture.usedProvenance.get());
      assertEquals(ORIGIN, store.find(key).orElseThrow().context());
    }
  }

  @Test
  void durableScopeMustExplicitlyCoverTheFrozenValue() throws Exception {
    var fixture = new Fixture(); var grants = new DurableGrantStore();
    grants.grantAllowAlways(ID.value(), SourceTier.UNTRUSTED);
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), new ConsentCapsuleService());
      executor.setDurableGrantStore(grants, (op, args, context) -> true);
      String key = OperationKeys.generate(CLOCK);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      fixture.target.set("different-target");
      executor.setDurableGrantStore(grants, new DurableGrantScope() {
        @Override public boolean coversArguments(Operation op, String args, EngineContext context) {
          throw new AssertionError("Public-only scope cannot cover a prepared target");
        }
        @Override public boolean coversPreparation(Operation op, OperationPreparation prepared, EngineContext context) {
          return prepared.replayPayloadJson().contains("original-target");
        }
      });
      assertTrue(invoke(executor, false, key, gated.preparationNonce(), Optional.empty()).success());
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
    }
  }

  @Test
  void concurrentUnknownDispatchesShareOnePreparationBeforeEitherApproval() throws Exception {
    var fixture = new Fixture();
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    fixture.beforePrepare = () -> {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
    };
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), new ConsentCapsuleService());
      String key = OperationKeys.generate(CLOCK);
      var firstResult = new java.util.concurrent.CompletableFuture<ConfirmationRequiredException>();
      var secondResult = new java.util.concurrent.CompletableFuture<ConfirmationRequiredException>();
      Thread first = new Thread(() -> captureGate(firstResult, executor, key), "first-dispatch");
      Thread second = new Thread(() -> captureGate(secondResult, executor, key), "second-dispatch");
      first.start();
      try {
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS));
        fixture.target.set("later-target"); second.start();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(3).toNanos();
        while (second.isAlive() && second.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, second.getState());
        release.countDown();
        var firstGate = firstResult.get(3, java.util.concurrent.TimeUnit.SECONDS);
        var secondGate = secondResult.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(firstGate.preparationNonce(), secondGate.preparationNonce());
        assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      } finally {
        release.countDown(); first.join(java.time.Duration.ofSeconds(3));
        if (second.getState() != Thread.State.NEW) second.join(java.time.Duration.ofSeconds(3));
      }
    }
  }

  private static void captureGate(java.util.concurrent.CompletableFuture<ConfirmationRequiredException> result,
      OperationExecutorImpl executor, String key) {
    try { result.complete(assertThrows(ConfirmationRequiredException.class,
        () -> invoke(executor, false, key, null, Optional.empty()))); }
    catch (Throwable failure) { result.completeExceptionally(failure); }
  }
}
