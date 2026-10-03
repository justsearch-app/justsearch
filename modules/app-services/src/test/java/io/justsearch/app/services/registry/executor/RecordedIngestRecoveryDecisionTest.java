/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RecordedIngestRecoveryDecisionTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final Clock KEY_CLOCK = Clock.fixed(OCCURRED_AT, java.time.ZoneOffset.UTC);
  private static final String PUBLIC_ARGUMENTS =
      "{\"paths\":[\"C:/public-requested-root\"],\"generation\":\"public-generation\"}";
  private static final String GENERATION = "frozen-generation";
  private static final UUID PREPARATION_NONCE =
      UUID.fromString("00000000-0000-4000-8000-000000000510");

  @Test
  void trustedStructuralAutoIngestAuthorizesTheExactFrozenPlan(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    var decision = fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true);

    var authorized = assertInstanceOf(
        OperationAuthority.RecordedIngestRecoveryDecision.Authorized.class, decision,
        "trusted structural AUTO ingest must authorize");
    assertEquals(
        fixture.plan(), authorized.plan(), "recovery must return the frozen plan exactly");
  }

  @Test
  void previouslyAutoMediumIngestRefusesWhenCurrentSourceIsUntrusted(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.StructuralAuto());

    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void reindexLowAutoWaitsForWorkerAndAuthorizesWhenWorkerIsOnline(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    var unavailable = fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> false);
    assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Wait.class, unavailable,
        "reindex recovery waits while WorkerOnline is false");

    var available = fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true);
    var authorized = assertInstanceOf(
        OperationAuthority.RecordedIngestRecoveryDecision.Authorized.class, available,
        "reindex recovery authorizes once WorkerOnline is true");
    assertEquals(fixture.plan(), authorized.plan());
  }

  @Test
  void ephemeralCapsuleBasisRefusesRestartEvenWhenCurrentGateIsAuto(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.EphemeralCapsule());

    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.row(), fixture.plan()),
        "the same bound plan may continue fresh but must not gain restart permission");
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void preparedBulkContinuationCannotAuthorizeOrdinaryIngestRecovery(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    String key = OperationKeys.generate(KEY_CLOCK);
    var continuation = new OperationAuthorizationBasis.PreparedContinuation(
        key, PREPARATION_NONCE);
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL, continuation, key);

    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void bindingFailuresRefuseContradictorySourceMissingBasisMalformedBasisBadPayloadAndIdentity(
      @TempDir Path temp) throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    EngineContext contradictorySource = rawContext(fixture.row().context(), "UNTRUSTED",
        TransportTag.SYSTEM_INTERNAL, fixture.row().context().grantReference());
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        withContext(fixture.row(), contradictorySource), fixture.preparation(),
        Optional.of(GENERATION), ignored -> true));

    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        withContext(fixture.row(), fixture.row().context().withGrantReference(Optional.empty())),
        fixture.preparation(), Optional.of(GENERATION), ignored -> true));
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        withContext(fixture.row(), fixture.row().context().withGrantReference(
            Optional.of("jsa1:unknown"))), fixture.preparation(), Optional.of(GENERATION),
        ignored -> true));

    OperationStore.Preparation malformed = new OperationStore.Preparation(
        fixture.preparation().nonce(), new OperationPreparedPayload(false, "{malformed"));
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        fixture.row(), malformed, Optional.of(GENERATION), ignored -> true));

    OperationDescriptor changedDescriptor = OperationDescriptor.invocation(
        OperationKind.INGEST, "core.ingest-files", "{\"paths\":[\"changed\"]}", false);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        withDescriptor(fixture.row(), changedDescriptor), fixture.preparation(),
        Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void unregisteredPluginTransportCannotUseTheUntrustedFallback(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.PLUGIN_EMITTED,
        new OperationAuthorizationBasis.StructuralAuto());
    assertTrue(fixture.authority().sources().findByTransport(TransportTag.PLUGIN_EMITTED).isEmpty());
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void exactOperationGrantCannotBeReplacedByFamilyGrantAfterRevoke(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    OperationAuthorizationBasis.OperationGrant selected =
        new OperationAuthorizationBasis.OperationGrant("core.ingest-files", SourceTier.UNTRUSTED);
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.AGENT_LOOP, selected);
    fixture.authority().grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    assertAuthorized(fixture, "selected operation grant must authorize");

    fixture.authority().grants().revoke("core.ingest-files", SourceTier.UNTRUSTED);
    fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void exactFamilyGrantCannotBeReplacedByOperationGrantAfterRevoke(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    OperationAuthorizationBasis.FamilyGrant selected =
        new OperationAuthorizationBasis.FamilyGrant("file-operations", SourceTier.UNTRUSTED);
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.AGENT_LOOP, selected);
    fixture.authority().grants().grantFamilyAllowAlways("file-operations", SourceTier.UNTRUSTED);
    assertAuthorized(fixture, "selected family grant must authorize");

    fixture.authority().grants().revokeFamily("file-operations", SourceTier.UNTRUSTED);
    fixture.authority().grants().grantAllowAlways("core.ingest-files", SourceTier.UNTRUSTED);
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void revokedOperationGrantCannotFallBackToCurrentUntrustedAuto(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    OperationAuthorizationBasis.OperationGrant selected =
        new OperationAuthorizationBasis.OperationGrant("core.reindex", SourceTier.UNTRUSTED);
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.AGENT_LOOP, selected);
    fixture.authority().grants().grantAllowAlways("core.reindex", SourceTier.UNTRUSTED);
    assertAuthorized(fixture, "selected reindex grant must authorize");

    fixture.authority().grants().revoke("core.reindex", SourceTier.UNTRUSTED);
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void hardStopRefusesLowAutoAndGrantIssuedAfterEngage(@TempDir Path temp) throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture auto = fixture(temp.resolve("data-auto"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.AGENT_LOOP,
        new OperationAuthorizationBasis.StructuralAuto());
    auto.authority().hardStop().engage();
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", auto.authority().evaluateRecordedIngest(
        auto.row(), auto.preparation(), Optional.of(GENERATION), ignored -> true));

    Path watchedRootWithGrant = Files.createDirectory(temp.resolve("watched-grant"));
    OperationAuthorizationBasis.OperationGrant selected =
        new OperationAuthorizationBasis.OperationGrant("core.reindex", SourceTier.UNTRUSTED);
    Fixture grant = fixture(temp.resolve("data-grant"), watchedRootWithGrant, OperationKind.REINDEX,
        "core.reindex", TransportTag.AGENT_LOOP, selected);
    grant.authority().hardStop().engage();
    grant.authority().grants().grantAllowAlways("core.reindex", SourceTier.UNTRUSTED);
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED", grant.authority().evaluateRecordedIngest(
        grant.row(), grant.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void structuralAutoIsRefusedWhenFrozenEffectIsOutOfRoot(@TempDir Path temp) throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Path outsideRoot = Files.createDirectory(temp.resolve("outside"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, outsideRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    assertTrue(fixture.authority().allowsFreshRecordedIngest(fixture.row(), fixture.plan()),
        "the same bound plan may continue fresh but must not gain restart permission");
    assertRefused("RECOVERY_SCOPE_REFUSED", fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true));
  }

  @Test
  void generationReadinessWaitsOnlyForAbsenceAndRefusesDifferentGeneration(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.INGEST,
        "core.ingest-files", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    var absent = fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.empty(), ignored -> true);
    assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Wait.class, absent,
        "missing serving generation is temporary readiness");
    assertRefused("RECOVERY_GENERATION_MISMATCH",
        fixture.authority().evaluateRecordedIngest(
            fixture.row(), fixture.preparation(), Optional.of("different-generation"),
            ignored -> true));
  }

  @Test
  void falseNullAndThrowingCapabilitiesWaitBeforeTrueAuthorizes(@TempDir Path temp)
      throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.StructuralAuto());

    assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Wait.class,
        fixture.authority().evaluateRecordedIngest(
            fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> false));
    assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Wait.class,
        fixture.authority().evaluateRecordedIngest(
            fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> null));
    assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Wait.class,
        fixture.authority().evaluateRecordedIngest(
            fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> {
              throw new IllegalStateException("capability unavailable");
            }));
    assertAuthorized(fixture, "true required capability must authorize");
  }

  @Test
  void permanentAuthorityRefusalWinsOverAbsentGenerationAndUnavailableCapability(
      @TempDir Path temp) throws IOException {
    Path watchedRoot = Files.createDirectory(temp.resolve("watched"));
    Fixture fixture = fixture(temp.resolve("data"), watchedRoot, OperationKind.REINDEX,
        "core.reindex", TransportTag.SYSTEM_INTERNAL,
        new OperationAuthorizationBasis.EphemeralCapsule());

    for (Optional<String> generation : List.<Optional<String>>of(Optional.empty(), Optional.of(GENERATION))) {
      assertRefused("RECOVERY_AUTHORIZATION_REFUSED", fixture.authority().evaluateRecordedIngest(
          fixture.row(), fixture.preparation(), generation, ignored -> {
            throw new IllegalStateException("must not inspect capability after refusal");
          }));
    }
  }

  private static void assertAuthorized(Fixture fixture, String message) {
    var decision = fixture.authority().evaluateRecordedIngest(
        fixture.row(), fixture.preparation(), Optional.of(GENERATION), ignored -> true);
    var authorized = assertInstanceOf(
        OperationAuthority.RecordedIngestRecoveryDecision.Authorized.class, decision, message);
    assertEquals(fixture.plan(), authorized.plan());
  }

  private static void assertRefused(String code,
      OperationAuthority.RecordedIngestRecoveryDecision decision) {
    var refused = assertInstanceOf(OperationAuthority.RecordedIngestRecoveryDecision.Refused.class,
        decision, "expected permanent recovery refusal");
    assertEquals(code, refused.receipt().code());
  }

  private static Fixture fixture(Path dataDirectory, Path watchedRoot, OperationKind kind,
      String operationRef, TransportTag transport, OperationAuthorizationBasis basis)
      throws IOException {
    return fixture(dataDirectory, watchedRoot, watchedRoot, kind, operationRef, transport, basis);
  }

  private static Fixture fixture(Path dataDirectory, Path watchedRoot, Path planRoot,
      OperationKind kind, String operationRef, TransportTag transport,
      OperationAuthorizationBasis basis) throws IOException {
    return fixture(dataDirectory, watchedRoot, planRoot, kind, operationRef, transport, basis,
        OperationKeys.generate(KEY_CLOCK));
  }

  private static Fixture fixture(Path dataDirectory, Path watchedRoot, Path planRoot,
      OperationKind kind, String operationRef, TransportTag transport,
      OperationAuthorizationBasis basis, String key) throws IOException {
    writeWatchedRoots(dataDirectory, watchedRoot);
    OperationAuthority authority = OperationAuthority.load(dataDirectory);
    UUID nonce = PREPARATION_NONCE;
    String arguments = kind == OperationKind.REINDEX ? "{\"force\":true}" : PUBLIC_ARGUMENTS;
    OperationDescriptor descriptor = OperationDescriptor.invocation(kind, operationRef, arguments, false);
    EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL,
        "recovery-owner", Optional.of("recovery-session"), Optional.of(basis.encode()), transport,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    RecordedRootPlan plan = new RecordedRootPlan(GENERATION, List.of(
        new RecordedRootPlan.Root(planRoot, "documents", true, false, List.of(), List.of())));
    OperationPreparation preparation = new OperationPreparation(arguments,
        RecordedRootPlan.SCHEMA, plan.toReplayPayload(), OperationPreparation.Content.METADATA);
    PreparedInvocationCodec codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, nonce, descriptor, preparation, context,
        EngineProvenance.invocation(context, ExecutorTag.AGENT, OCCURRED_AT, Optional.empty()));
    OperationStore.Preparation stored =
        new OperationStore.Preparation(nonce, codec.encode(envelope));
    OperationRecord row = row(key, descriptor, context, "AGENT", "recovery-owner",
        "recovery-session", OCCURRED_AT);
    return new Fixture(authority, plan, row, stored);
  }

  private static void writeWatchedRoots(Path dataDirectory, Path watchedRoot) throws IOException {
    Files.createDirectories(dataDirectory);
    String escaped = watchedRoot.toAbsolutePath().normalize().toString()
        .replace("\\", "\\\\").replace("\"", "\\\"");
    Files.writeString(dataDirectory.resolve("watched_roots.json"),
        "{\"schemaVersion\":1,\"roots\":[{\"path\":\"" + escaped + "\"}]}");
  }

  private static OperationRecord row(String key, OperationDescriptor descriptor,
      EngineContext context, String executor, String initiator, String correlationId,
      Instant occurredAt) {
    return new OperationRecord(1L, key, descriptor, context, executor, initiator, correlationId,
        OperationState.ACCEPTED, "accepted", null, 0L, 0L, 0, 1L, null, 1L, null,
        null, null, io.justsearch.app.api.operations.OperationHistoryMode.NONE,
        occurredAt, null);
  }

  private static OperationRecord withContext(OperationRecord row, EngineContext context) {
    return new OperationRecord(row.id(), row.key(), row.descriptor(), context, row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withDescriptor(
      OperationRecord row, OperationDescriptor descriptor) {
    return new OperationRecord(row.id(), row.key(), descriptor, row.context(), row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static EngineContext rawContext(EngineContext original, String sourceTier,
      TransportTag transport, Optional<String> basis) {
    return new EngineContext(
        original.clientKind(), original.clientId(), original.sessionId(), basis, sourceTier,
        transport.name(), original.survival(), original.urgency());
  }

  private record Fixture(OperationAuthority authority, RecordedRootPlan plan, OperationRecord row,
      OperationStore.Preparation preparation) {}
}
