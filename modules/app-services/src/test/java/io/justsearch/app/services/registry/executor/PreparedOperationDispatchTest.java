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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.List;
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
    return operation(RiskTier.MEDIUM);
  }

  private static Operation operation(RiskTier risk) {
    return new Operation(ID, Presentation.of(new I18nKey("test.prepared"), new I18nKey("test.prepared.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(risk, ConfirmStrategy.None.INSTANCE, AuditPolicy.METADATA_ONLY,
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

  private static OperationExecutorImpl bulkExecutor(SqliteOperationStore store, HandlerRegistry handlers,
      ConsentCapsuleService capsules) {
    return new OperationExecutorImpl(new OperationAttemptRunnerImpl(store, CLOCK, Set.of(OperationKind.REINDEX)),
        admission(), handlers, null, Map.of(), CLOCK, new CoreTrustEvaluator(),
        CoreIntentSourceCatalog.catalog(), null, capsules);
  }

  private static Operation bulkOperation(RecordedBulkPlan.Profile profile) {
    var id = new OperationRef(profile.operationRef());
    return new Operation(id, Presentation.of(new I18nKey("test.bulk"), new I18nKey("test.bulk.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE, AuditPolicy.METADATA_ONLY,
            RetryPolicy.noRetry(), Set.of(), false).withRecordKind(OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
        Provenance.core("1.0"), Set.of(ExecutorTag.UI));
  }

  private static Operation installerActivationOperation() {
    var id = new OperationRef(RecordedInstallerGenerationPlan.OPERATION_ID);
    return new Operation(id,
        Presentation.of(new I18nKey("test.activate"), new I18nKey("test.activate.desc")),
        Interface.of("{\"type\":\"object\"}", "{\"type\":\"object\"}"),
        new OperationPolicy(RiskTier.HIGH, ConfirmStrategy.Inline.INSTANCE,
            AuditPolicy.METADATA_ONLY, RetryPolicy.noRetry(), Set.of(), false)
            .withRecordKind(OperationKind.REINDEX)
            .withDeclaredSurvival(EngineContext.Survival.DURABLE),
        OperationAvailability.empty(), OperationLineage.empty(), Binding.of(id),
        Provenance.core("1.0"), Set.of(ExecutorTag.UI));
  }

  private static String bulkArguments(RecordedBulkPlan.Profile profile) {
    return profile == RecordedBulkPlan.Profile.USER_BULK ? "{\"corpusIds\":[\"docs\"]}" : "{}";
  }

  private static InvocationProvenance bulkProvenance(EngineContext context) {
    return EngineProvenance.invocation(context, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
  }

  private static OperationResult invokeBulk(OperationExecutorImpl executor, Operation operation, String arguments,
      InvocationProvenance provenance, Optional<String> token, EngineContext context, String key, UUID nonce) {
    return executor.dispatch(operation, arguments, provenance, token, context, key, nonce);
  }

  private static final class RecordedBulkFixture implements OperationHandler {
    final Operation operation;
    final String arguments;
    final RecordedBulkPlan.Profile profile;
    final Path root;
    final AtomicInteger effects = new AtomicInteger();
    final AtomicReference<EngineContext> usedContext = new AtomicReference<>();

    RecordedBulkFixture(RecordedBulkPlan.Profile profile, Path root) {
      this.profile = profile;
      this.operation = bulkOperation(profile);
      this.arguments = bulkArguments(profile);
      this.root = root;
    }

    @Override public OperationResult execute(String args, EngineContext context) {
      throw new AssertionError("Recorded bulk execution must use its frozen preparation");
    }

    @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
        EngineContext context) {
      var scope = new RecordedRootPlan("serving-generation", List.of(
          new RecordedRootPlan.Root(root, "documents", true, false, List.of("*.tmp"), List.of())));
      String inputs = "{\"dimension\":768}";
      var target = new IndexTargetSnapshot(sha256(inputs), inputs);
      var plan = new RecordedBulkPlan(profile, profile.defaultSource(), scope, target);
      return new OperationPreparation(args, RecordedBulkPlan.SCHEMA, plan.toReplayPayload(),
          OperationPreparation.Content.METADATA);
    }

    @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
      return new OperationApprovalPreview("Continue the approved bulk operation across restarts");
    }

    @Override public void validatePreparation(OperationPreparation prepared) {
      if (!RecordedBulkPlan.continuationPreparation(operation, prepared)
          || !arguments.equals(prepared.argumentsJson())) {
        throw new IllegalArgumentException("Invalid recorded bulk fixture preparation");
      }
    }

    @Override public OperationExecution executePrepared(OperationPreparation prepared,
        InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
      effects.incrementAndGet();
      usedContext.set(context);
      assertNotNull(record);
      return OperationExecution.finished(OperationResult.success("recorded bulk accepted"));
    }

    HandlerRegistry registry() {
      var handlers = new HandlerRegistry();
      handlers.register(operation.id(), this);
      return handlers;
    }
  }

  private static final class RecordedInstallerFixture implements OperationHandler {
    final Operation operation = installerActivationOperation();
    final Path root;
    final AtomicReference<EngineContext> usedContext = new AtomicReference<>();
    final AtomicReference<OperationPreparation> usedPreparation = new AtomicReference<>();

    RecordedInstallerFixture(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public OperationResult execute(String args, EngineContext context) {
      throw new AssertionError("Installer activation must execute its frozen preparation");
    }

    @Override public OperationPreparation prepare(String args, InvocationProvenance provenance,
        EngineContext context) {
      var acquisition = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
          RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY,
          "manifest-1", "a".repeat(64));
      var scope = new RecordedRootPlan("serving-generation", List.of(
          new RecordedRootPlan.Root(root, "documents", true, false, List.of(), List.of())));
      String inputs = "{\"dimension\":768}";
      var plan = new RecordedInstallerGenerationPlan("serving-generation", scope,
          new IndexTargetSnapshot(sha256(inputs), inputs),
          new io.justsearch.app.api.settings.SettingsWitness(3, OperationKeys.generate(CLOCK)),
          RecordedInstallerGenerationPlan.CandidateSettings.fromJson("{\"models\":{}}"),
          List.of(new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "fp32",
              root.resolve("embedding.onnx"), "a".repeat(64), 1, acquisition)),
          List.of(new RecordedInstallerGenerationPlan.AssetIdentity("embedding",
              root.resolve("embedding.bin"), "a".repeat(64), 1, acquisition)),
          RecordedInstallerGenerationPlan.ChatSelection.none(), acquisition);
      return new OperationPreparation(args, RecordedInstallerGenerationPlan.SCHEMA,
          plan.toReplayPayload(), OperationPreparation.Content.METADATA);
    }

    @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
      return new OperationApprovalPreview("Activate the downloaded model");
    }

    @Override public void validatePreparation(OperationPreparation prepared) {
      if (!RecordedInstallerGenerationPlan.continuationPreparation(operation, prepared)) {
        throw new IllegalArgumentException("Invalid installer activation preparation");
      }
    }

    @Override public OperationExecution executePrepared(OperationPreparation prepared,
        InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
      usedPreparation.set(prepared);
      usedContext.set(context);
      return OperationExecution.finished(OperationResult.success("activated"));
    }

    HandlerRegistry registry() {
      var handlers = new HandlerRegistry();
      handlers.register(operation.id(), this);
      return handlers;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone;
    MutableClock(Instant now) { this(now, ZoneId.of("UTC")); }
    private MutableClock(Instant now, ZoneId zone) {
      this.now = new AtomicReference<>(now);
      this.zone = zone;
    }
    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId requested) { return new MutableClock(now.get(), requested); }
    @Override public Instant instant() { return now.get(); }
    void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
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
      var storedPreparation = store.acceptedPreparation(row.id()).orElseThrow();
      var envelope = new PreparedInvocationCodec(io.justsearch.agent.api.encryption.StoreCipher.disabled())
          .decode(storedPreparation.payload(), key, storedPreparation.nonce(), row.descriptor());
      assertEquals(ORIGIN, envelope.context(), "Acceptance preserves the original preparation");
      assertEquals(Optional.of("jsa1:capsule"), row.context().grantReference());
      assertEquals(undo ? OperationHistoryMode.UNDO : OperationHistoryMode.UNDOABLE, row.historyMode());
      assertEquals(PROVENANCE.occurredAt(), row.provenanceOccurredAt());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(RecordedBulkPlan.Profile.class)
  void approvedRecordedBulkMintsBoundContinuationOnlyAfterCapsuleAndOverwritesCallerMarker(
      RecordedBulkPlan.Profile profile) throws Exception {
    var fixture = new RecordedBulkFixture(profile, directory.resolve("bulk-root"));
    Instant start = Instant.now();
    var capsuleClock = new MutableClock(start);
    var capsules = new ConsentCapsuleService(capsuleClock, Duration.ofMinutes(5));
    String key = OperationKeys.generate(CLOCK);
    EngineContext caller = ORIGIN.withGrantReference(Optional.of("jsa1:auto"));
    InvocationProvenance provenance = bulkProvenance(caller);
    try (var store = new SqliteOperationStore(directory.resolve("bulk-operations.db"))) {
      var executor = bulkExecutor(store, fixture.registry(), capsules);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invokeBulk(executor, fixture.operation, fixture.arguments, provenance, Optional.empty(), caller,
              key, null));
      UUID nonce = gated.preparationNonce();
      assertNotNull(nonce);
      assertTrue(store.find(key).isEmpty(), "Approval preparation must not create an accepted operation");
      assertTrue(store.openRecords().isEmpty());
      assertEquals(0, fixture.effects.get());

      String expiredToken = capsules.mintPrepared(fixture.operation.id().value(), fixture.arguments,
          SourceTier.UNTRUSTED, key, nonce);
      capsuleClock.advance(Duration.ofMinutes(6));
      String wrongKey = OperationKeys.generate(CLOCK);
      String wrongKeyToken = capsules.mintPrepared(fixture.operation.id().value(), fixture.arguments,
          SourceTier.UNTRUSTED, wrongKey, nonce);
      String wrongNonceToken = capsules.mintPrepared(fixture.operation.id().value(), fixture.arguments,
          SourceTier.UNTRUSTED, key, UUID.randomUUID());
      for (String invalid : List.of("not-a-capsule", wrongKeyToken, wrongNonceToken, expiredToken)) {
        assertThrows(ConfirmationRequiredException.class,
            () -> invokeBulk(executor, fixture.operation, fixture.arguments, provenance,
                Optional.of(invalid), caller, key, nonce));
        assertTrue(store.find(key).isEmpty(), "Invalid approval must not stamp a continuation basis");
        assertEquals(0, fixture.effects.get());
      }

      String validToken = capsules.mintPrepared(fixture.operation.id().value(), fixture.arguments,
          SourceTier.UNTRUSTED, key, nonce);
      var accepted = invokeBulk(executor, fixture.operation, fixture.arguments, provenance,
          Optional.of(validToken), caller, key, nonce);
      assertTrue(accepted.success());
      assertEquals(1, fixture.effects.get());

      var row = store.find(key).orElseThrow();
      var basis = assertInstanceOf(OperationAuthorizationBasis.PreparedContinuation.class,
          OperationAuthorizationBasis.decode(row.context().grantReference().orElseThrow()));
      assertEquals(key, basis.operationKey());
      assertEquals(nonce, basis.preparationNonce());
      assertEquals(row.context().grantReference(), fixture.usedContext.get().grantReference());
      assertNotEquals(caller.grantReference(), row.context().grantReference(),
          "Caller-supplied grant reference must not become server authorization evidence");
      var stored = store.acceptedPreparation(row.id()).orElseThrow();
      var envelope = new PreparedInvocationCodec(io.justsearch.agent.api.encryption.StoreCipher.disabled())
          .decode(stored.payload(), key, stored.nonce(), row.descriptor());
      assertEquals(caller.grantReference(), envelope.context().grantReference(),
          "The caller marker remains frozen only as attribution, not as accepted authorization");
      assertEquals(caller.clientId(), envelope.context().clientId());
      assertTrue(capsules.consumePreparedDeferred(validToken, fixture.operation.id().value(),
          fixture.arguments, key, nonce).isEmpty(), "The accepted capsule is consumed even with its original binding");

      var replay = invokeBulk(executor, fixture.operation, fixture.arguments, provenance,
          Optional.of(validToken), caller, key, nonce);
      assertTrue(replay.success());
      assertEquals(1, fixture.effects.get(), "Accepted retries reuse the receipt without replaying the effect");

      String replayKey = OperationKeys.generate(CLOCK);
      var secondGate = assertThrows(ConfirmationRequiredException.class,
          () -> invokeBulk(executor, fixture.operation, fixture.arguments, provenance, Optional.empty(), caller,
              replayKey, null));
      assertThrows(ConfirmationRequiredException.class,
          () -> invokeBulk(executor, fixture.operation, fixture.arguments, provenance,
              Optional.of(validToken), caller, replayKey, secondGate.preparationNonce()));
      assertTrue(store.find(replayKey).isEmpty(), "A consumed capsule cannot authorize a copied operation key");
      assertEquals(1, fixture.effects.get());
    }
  }

  @Test
  void installerCandidateIsBoundToTheRunnerKeyBeforeApprovalAndContinuation() throws Exception {
    var fixture = new RecordedInstallerFixture(directory.resolve("installer-root"));
    var capsules = new ConsentCapsuleService();
    String key = OperationKeys.generate(CLOCK);
    InvocationProvenance provenance = bulkProvenance(ORIGIN);
    try (var store = new SqliteOperationStore(directory.resolve("installer-operations.db"))) {
      var executor = bulkExecutor(store, fixture.registry(), capsules);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invokeBulk(executor, fixture.operation, "{}", provenance, Optional.empty(), ORIGIN,
              key, null));
      UUID nonce = gated.preparationNonce();
      var pending = store.pendingPreparation(key,
          OperationDescriptor.invocation(OperationKind.REINDEX, fixture.operation.id().value(), "{}", false))
          .orElseThrow();
      var pendingEnvelope = new PreparedInvocationCodec(
          io.justsearch.agent.api.encryption.StoreCipher.disabled())
          .decode(pending.payload(), key, nonce,
              OperationDescriptor.invocation(OperationKind.REINDEX,
                  fixture.operation.id().value(), "{}", false));
      assertTrue(RecordedInstallerGenerationPlan.continuationPreparation(
          fixture.operation, pendingEnvelope.preparation(), key));

      String token = capsules.mintPrepared(fixture.operation.id().value(), "{}",
          SourceTier.UNTRUSTED, key, nonce);
      assertTrue(invokeBulk(executor, fixture.operation, "{}", provenance, Optional.of(token),
          ORIGIN, key, nonce).success());
      var usedPlan = RecordedInstallerGenerationPlan.fromReplayPayload(
          fixture.usedPreparation.get().replayPayloadJson());
      assertEquals(key, usedPlan.operationKey());
      var basis = assertInstanceOf(OperationAuthorizationBasis.PreparedContinuation.class,
          OperationAuthorizationBasis.decode(
              fixture.usedContext.get().grantReference().orElseThrow()));
      assertEquals(key, basis.operationKey());
      assertEquals(nonce, basis.preparationNonce());
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

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = RiskTier.class, names = {"LOW", "MEDIUM"})
  void planningFreezesBeforeApprovalWithoutAcceptingEvenAnAutoOperation(RiskTier risk) throws Exception {
    var fixture = new Fixture(); var capsules = spy(new ConsentCapsuleService());
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), capsules);
      var outcome = executor.prepare(operation(risk), "{}", PROVENANCE, ORIGIN, null, true);
      assertEquals(0, fixture.effects.get(), "WATCH must be able to approve before a base-AUTO effect");
      var plan = assertInstanceOf(OperationDispatchPlan.Ready.class, outcome);
      assertTrue(store.find(plan.operationKey()).isEmpty(), "Planning must not accept work");
      assertNotNull(plan.preparationNonce());
      assertEquals("Write to original-target", plan.approvalPreview().orElseThrow().summary());
      fixture.target.set("changed-target");
      var retry = assertInstanceOf(OperationDispatchPlan.Ready.class,
          executor.prepare(operation(risk), "{}", PROVENANCE, ORIGIN, plan.operationKey(), true));
      assertEquals(plan, retry); assertEquals(1, fixture.prepares.get());
      assertEquals(0, fixture.effects.get()); verifyNoInteractions(capsules);
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> executor.prepare(operation(risk), "{\"changed\":true}",
              PROVENANCE, ORIGIN, plan.operationKey(), true)).code());
      assertEquals(1, fixture.prepares.get());
    }
  }

  @Test
  void planningReopensTheFrozenTargetAndTerminalReceiptsSkipDisplay() throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService();
    OperationDispatchPlan.Ready first;
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      first = assertInstanceOf(OperationDispatchPlan.Ready.class,
          executor(store, fixture.registry(), capsules).prepare(operation(), "{}", PROVENANCE, ORIGIN, null, true));
    }
    fixture.target.set("replacement");
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), capsules);
      assertEquals(first, executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, first.operationKey(), true));
      String token = capsules.mintPrepared(ID.value(), "{}", SourceTier.UNTRUSTED,
          first.operationKey(), first.preparationNonce());
      fixture.previewSupported = false;
      assertTrue(executor.dispatch(operation(), "{}", PROVENANCE, Optional.of(token), ORIGIN,
          first.operationKey(), first.preparationNonce()).success());
      var receipt = assertInstanceOf(OperationDispatchPlan.Recorded.class,
          executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, first.operationKey(), true));
      assertEquals(first.operationKey(), receipt.operationKey()); assertTrue(receipt.result().success());
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
      assertTrue(fixture.used.get().contains("original-target"));
      assertEquals(OperationStoreException.Code.OPERATION_KEY_REUSED,
          assertThrows(OperationStoreException.class, () -> executor.prepare(operation(), "{\"changed\":true}",
              PROVENANCE, ORIGIN, first.operationKey(), true)).code());
      var hardStop = new GlobalHardStop(); executor.setGlobalHardStop(hardStop); hardStop.engage();
      assertThrows(TrustGateDeniedException.class,
          () -> executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, first.operationKey(), true));
      assertThrows(TrustGateDeniedException.class,
          () -> executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, null, true));
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
    }
  }

  @Test
  void planningWithoutDisplayDoesNotRequireTheApprovalProjection() throws Exception {
    var fixture = new Fixture(); fixture.previewSupported = false;
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var executor = executor(store, fixture.registry(), new ConsentCapsuleService());
      var plan = assertInstanceOf(OperationDispatchPlan.Ready.class,
          executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, null, false));
      assertTrue(plan.approvalPreview().isEmpty()); assertNotNull(plan.preparationNonce());
      assertThrows(UnsupportedOperationException.class,
          () -> executor.prepare(operation(), "{}", PROVENANCE, ORIGIN, plan.operationKey(), true));
      assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      assertTrue(store.find(plan.operationKey()).isEmpty());
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(value = TransportTag.class, names = {"AGENT_LOOP", "WORKFLOW"})
  void orchestratorCarriesTheFrozenReferenceThroughTheRealRouterAndCapsuleAuthority(TransportTag transport) throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService();
    var context = transport == TransportTag.AGENT_LOOP ? TestEngineContexts.agent() : TestEngineContexts.workflow();
    try (var store = new SqliteOperationStore(directory.resolve("operations.db"))) {
      var dispatcher = executor(store, fixture.registry(), capsules);
      var router = new BackendIntentRouterImpl(OperationCatalog.of("core", List.of(operation())),
          dispatcher, CoreIntentSourceCatalog.catalog(),
          new io.justsearch.app.observability.intent.IntentEnvelopeChangeRegistry());
      var gated = new GatedOperationExecutor(() -> router, () -> capsules, transport);
      var plan = assertInstanceOf(OperationDispatchPlan.Ready.class,
          gated.prepare(operation(), "{}", context, null, true));
      assertEquals(0, fixture.effects.get()); assertTrue(store.find(plan.operationKey()).isEmpty());
      assertEquals("Write to original-target", plan.approvalPreview().orElseThrow().summary());
      fixture.target.set("replacement");
      assertTrue(gated.routePrepared(operation(), "{}", plan, context).success());
      assertEquals(1, fixture.prepares.get()); assertEquals(1, fixture.effects.get());
      assertTrue(fixture.used.get().contains("original-target"));
      assertEquals(transport, fixture.usedProvenance.get().transport());
      assertEquals(context.sessionId(), fixture.usedProvenance.get().correlationId());
      fixture.previewSupported = false;
      var receipt = assertInstanceOf(OperationDispatchPlan.Recorded.class,
          gated.prepare(operation(), "{}", context, plan.operationKey(), true));
      assertTrue(gated.routePrepared(operation(), "{}", receipt, context).success());
      var stop = new GlobalHardStop(); dispatcher.setGlobalHardStop(stop); stop.engage();
      assertThrows(TrustGateDeniedException.class, () -> gated.routePrepared(operation(), "{}", receipt, context));
      assertEquals(1, fixture.effects.get());
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
  void failedAcceptanceRetainsUnstampedPreparationAndCreatesNoAuthorizedRow() throws Exception {
    var fixture = new Fixture(); var capsules = new ConsentCapsuleService();
    Path database = directory.resolve("operations.db");
    try (var store = new SqliteOperationStore(database)) {
      var executor = executor(store, fixture.registry(), capsules);
      String key = OperationKeys.generate(CLOCK);
      var gated = assertThrows(ConfirmationRequiredException.class,
          () -> invoke(executor, false, key, null, Optional.empty()));
      var descriptor = OperationDescriptor.invocation(OperationKind.NOTE, ID.value(), "{}", false);
      var pending = store.pendingPreparation(key, descriptor).orElseThrow();
      try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
          var statement = connection.createStatement()) {
        statement.execute("CREATE TRIGGER refuse_accept BEFORE INSERT ON operations BEGIN SELECT RAISE(ABORT, 'injected acceptance failure'); END");
      }
      String token = capsules.mintPrepared(ID.value(), "{}", SourceTier.UNTRUSTED, key, gated.preparationNonce());
      assertThrows(OperationStoreException.class,
          () -> invoke(executor, false, key, gated.preparationNonce(), Optional.of(token)));
      assertTrue(store.find(key).isEmpty());
      assertEquals(pending, store.pendingPreparation(key, descriptor).orElseThrow());
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
      // A trusted retry cannot upgrade the frozen untrusted origin's authority.
      // After bound approval, original attribution still selects a fresh child.
      var current = TestEngineContexts.ui().withWorkId(UUID.randomUUID());
      var currentProvenance = EngineProvenance.invocation(current, ExecutorTag.UI, CLOCK.instant(), Optional.empty());
      assertThrows(ConfirmationRequiredException.class, () -> executor.dispatch(operation(), "{}",
          currentProvenance, Optional.empty(), current, key, gated.preparationNonce()));
      assertEquals(0, fixture.effects.get());
      String token = capsules.mintPrepared(ID.value(), "{}", SourceTier.UNTRUSTED, key, gated.preparationNonce());
      clearInvocations(admission);
      assertTrue(executor.dispatch(operation(), "{}", currentProvenance, Optional.of(token), current, key, gated.preparationNonce()).success());
      var accepted = ORIGIN.withGrantReference(Optional.of("jsa1:capsule"));
      verify(admission).attach(ORIGIN);
      verify(admission).attach(current);
      assertEquals(ORIGIN.clientId(), fixture.usedContext.get().clientId());
      assertNotEquals(current.workId(), fixture.usedContext.get().workId());
      assertEquals(accepted.withWorkId(fixture.usedContext.get().workId().orElseThrow()),
          fixture.usedContext.get());
      assertEquals(PROVENANCE, fixture.usedProvenance.get());
      assertEquals(accepted, store.find(key).orElseThrow().context());
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
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (second.isAlive() && second.getState() != Thread.State.WAITING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(Thread.State.WAITING, second.getState());
        release.countDown();
        var firstGate = firstResult.get(3, java.util.concurrent.TimeUnit.SECONDS);
        var secondGate = secondResult.get(3, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(firstGate.preparationNonce(), secondGate.preparationNonce());
        assertEquals(1, fixture.prepares.get()); assertEquals(0, fixture.effects.get());
      } finally {
        release.countDown(); first.join(Duration.ofSeconds(3));
        if (second.getState() != Thread.State.NEW) second.join(Duration.ofSeconds(3));
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
