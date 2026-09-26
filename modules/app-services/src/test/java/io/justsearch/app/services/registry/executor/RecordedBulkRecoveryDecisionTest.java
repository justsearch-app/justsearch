/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.app.services.bootstrap.OperationAuthority;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Adversarial policy proof for the accepted prepared bulk/rebuild continuation. */
final class RecordedBulkRecoveryDecisionTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-21T00:00:00Z");
  private static final Clock KEY_CLOCK = Clock.fixed(OCCURRED_AT, java.time.ZoneOffset.UTC);
  private static final UUID NONCE = UUID.fromString("00000000-0000-4000-8000-000000000521");
  private static final String INPUTS = "{\"dimension\":768}";

  @Test
  void acceptedAndRunningRowsForBothProfilesAuthorizeTheirExactFrozenPlan(@TempDir Path temp)
      throws IOException {
    for (RecordedBulkPlan.Profile profile : RecordedBulkPlan.Profile.values()) {
      for (OperationState state : List.of(OperationState.ACCEPTED, OperationState.RUNNING)) {
        Fixture fixture = fixture(temp.resolve(profile.name() + "-" + state.name()), profile,
            state, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);

        var decision = fixture.authority().evaluateRecordedBulk(fixture.row(), fixture.preparation());

        var authorized = assertInstanceOf(OperationAuthority.RecordedBulkRecoveryDecision.Authorized.class,
            decision, profile + " " + state + " continuation must recover");
        assertEquals(fixture.plan(), authorized.plan(), "recovery returns the accepted plan unchanged");
      }
    }
  }

  @Test
  void copiedContinuationKeyOrNonceRefusesBeforePolicyEvaluation(@TempDir Path temp)
      throws IOException {
    Fixture fixture = fixture(temp.resolve("identity"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.RUNNING, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);
    var basis = new OperationAuthorizationBasis.PreparedContinuation(fixture.row().key(), NONCE);
    String otherKey = OperationKeys.generate(Clock.fixed(OCCURRED_AT.plusSeconds(1), java.time.ZoneOffset.UTC));

    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        withContext(fixture.row(), fixture.row().context().withGrantReference(Optional.of(
            new OperationAuthorizationBasis.PreparedContinuation(otherKey, NONCE).encode()))),
        fixture.preparation()));
    UUID otherNonce = UUID.fromString("00000000-0000-4000-8000-000000000523");
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        withContext(fixture.row(), fixture.row().context().withGrantReference(Optional.of(
            new OperationAuthorizationBasis.PreparedContinuation(fixture.row().key(), otherNonce).encode()))),
        fixture.preparation()));
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        fixture.row(), new OperationStore.Preparation(
            UUID.fromString("00000000-0000-4000-8000-000000000522"), fixture.preparation().payload())));
    assertEquals(basis.encode(), fixture.row().context().grantReference().orElseThrow());
  }

  @Test
  void wrongSchemaProfileArgumentsAndDescriptorRefuse(@TempDir Path temp) throws IOException {
    Fixture fixture = fixture(temp.resolve("binding"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.ACCEPTED, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);

    OperationPreparation wrongSchema = new OperationPreparation(fixture.arguments(),
        "recorded-bulk-reindex-other", fixture.plan().toReplayPayload(),
        OperationPreparation.Content.METADATA);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        fixture.row(), acceptedPreparation(fixture, fixture.row().descriptor(), wrongSchema)));

    RecordedBulkPlan wrongProfilePlan = new RecordedBulkPlan(RecordedBulkPlan.Profile.RECOVERY_REBUILD,
        MigrationSource.USER_REQUESTED_REBUILD.wire(), fixture.plan().scope(), fixture.plan().target());
    OperationPreparation wrongProfile = new OperationPreparation(fixture.arguments(),
        RecordedBulkPlan.SCHEMA, wrongProfilePlan.toReplayPayload(), OperationPreparation.Content.METADATA);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        fixture.row(), acceptedPreparation(fixture, fixture.row().descriptor(), wrongProfile)));

    String changedArguments = "{\"corpusIds\":[\"other\"]}";
    OperationDescriptor changedArgsDescriptor = OperationDescriptor.invocation(OperationKind.REINDEX,
        RecordedBulkPlan.Profile.USER_BULK.operationRef(), changedArguments, false);
    OperationPreparation changedArgumentsPreparation = new OperationPreparation(changedArguments,
        RecordedBulkPlan.SCHEMA, fixture.plan().toReplayPayload(), OperationPreparation.Content.METADATA);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        fixture.row(), acceptedPreparation(fixture, changedArgsDescriptor, changedArgumentsPreparation)));

    OperationDescriptor wrongKind = OperationDescriptor.invocation(OperationKind.INGEST,
        RecordedBulkPlan.Profile.USER_BULK.operationRef(), fixture.arguments(), false);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        withDescriptor(fixture.row(), wrongKind), fixture.preparation()));
    OperationDescriptor wrongRef = OperationDescriptor.invocation(OperationKind.REINDEX,
        RecordedBulkPlan.Profile.RECOVERY_REBUILD.operationRef(), fixture.arguments(), false);
    assertRefused("RECOVERY_BINDING_INVALID", fixture.authority().evaluateRecordedBulk(
        withDescriptor(fixture.row(), wrongRef), fixture.preparation()));
  }

  @Test
  void inactiveRowsRefuseWhileCompleteWithGapsCanContinue(@TempDir Path temp) throws IOException {
    Fixture fixture = fixture(temp.resolve("inactive"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.RUNNING, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);

    for (OperationState state : List.of(OperationState.COMPLETE, OperationState.FAILED,
        OperationState.CANCELLED)) {
      assertRefused("RECOVERY_OPERATION_INACTIVE", fixture.authority().evaluateRecordedBulk(
          withState(fixture.row(), state), fixture.preparation()));
    }
    assertInstanceOf(OperationAuthority.RecordedBulkRecoveryDecision.Authorized.class,
        fixture.authority().evaluateRecordedBulk(
            withState(fixture.row(), OperationState.COMPLETE_WITH_GAPS), fixture.preparation()));
  }

  @Test
  void removedWatchedRootRefusesTheFrozenPlan(@TempDir Path temp) throws IOException {
    Path watched = Files.createDirectories(temp.resolve("roots/watched"));
    Path replacement = Files.createDirectories(temp.resolve("roots/replacement"));
    Fixture fixture = fixture(temp.resolve("roots/data"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.RUNNING, List.of(watched), List.of(watched), TransportTag.SYSTEM_INTERNAL);
    fixture.authority().scope().bindIndexedRoots(ignored -> List.of(replacement));

    assertRefused("RECOVERY_SCOPE_REFUSED",
        fixture.authority().evaluateRecordedBulk(fixture.row(), fixture.preparation()));
  }

  @Test
  void currentHardStopRefusesUntrustedContinuationAndTransportMutationRefusesBinding(
      @TempDir Path temp) throws IOException {
    Fixture untrusted = fixture(temp.resolve("hard-stop"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.RUNNING, List.of(), List.of(), TransportTag.MCP);
    untrusted.authority().hardStop().engage();
    assertRefused("RECOVERY_AUTHORIZATION_REFUSED",
        untrusted.authority().evaluateRecordedBulk(untrusted.row(), untrusted.preparation()));

    Fixture trusted = fixture(temp.resolve("transport-binding"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.RUNNING, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);
    EngineContext changedTransport = EngineProvenance.context(
        trusted.row().context().clientKind(), trusted.row().context().clientId(),
        trusted.row().context().sessionId(), trusted.row().context().grantReference(),
        TransportTag.MCP, trusted.row().context().survival(), trusted.row().context().urgency());
    assertRefused("RECOVERY_BINDING_INVALID", trusted.authority().evaluateRecordedBulk(
        withContext(trusted.row(), changedTransport), trusted.preparation()));
  }

  @Test
  void emptyFrozenPlanIsValidOnlyWithAnAvailableCurrentRootsSupplier(@TempDir Path temp)
      throws IOException {
    Fixture fixture = fixture(temp.resolve("empty-scope"), RecordedBulkPlan.Profile.USER_BULK,
        OperationState.ACCEPTED, List.of(), List.of(), TransportTag.SYSTEM_INTERNAL);
    assertEquals(List.of(), fixture.plan().scope().roots());
    var decision = fixture.authority().evaluateRecordedBulk(fixture.row(), fixture.preparation());
    assertInstanceOf(OperationAuthority.RecordedBulkRecoveryDecision.Authorized.class, decision,
        "an available empty roots view may legitimately cover a frozen empty plan");

    fixture.authority().scope().bindIndexedRoots(ignored -> null);
    assertRefused("RECOVERY_SCOPE_REFUSED",
        fixture.authority().evaluateRecordedBulk(fixture.row(), fixture.preparation()));
    fixture.authority().scope().bindIndexedRoots(ignored -> {
      throw new IllegalStateException("roots unavailable");
    });
    assertRefused("RECOVERY_SCOPE_REFUSED",
        fixture.authority().evaluateRecordedBulk(fixture.row(), fixture.preparation()));
  }

  private static Fixture fixture(Path dataDirectory, RecordedBulkPlan.Profile profile,
      OperationState state, List<Path> watchedRoots, List<Path> plannedRoots, TransportTag transport)
      throws IOException {
    Files.createDirectories(dataDirectory);
    for (Path root : watchedRoots) Files.createDirectories(root);
    for (Path root : plannedRoots) Files.createDirectories(root);
    writeWatchedRoots(dataDirectory, watchedRoots);
    OperationAuthority authority = OperationAuthority.load(dataDirectory);

    String key = OperationKeys.generate(KEY_CLOCK);
    String arguments = profile == RecordedBulkPlan.Profile.USER_BULK
        ? "{\"corpusIds\":[\"docs\"]}" : "{}";
    OperationDescriptor descriptor = OperationDescriptor.invocation(OperationKind.REINDEX,
        profile.operationRef(), arguments, false);
    EngineContext acceptanceContext = EngineProvenance.context(EngineContext.ClientKind.INTERNAL,
        "bulk-recovery-owner", Optional.of("bulk-session"), Optional.empty(), transport,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
    List<RecordedRootPlan.Root> roots = plannedRoots.stream()
        .map(root -> new RecordedRootPlan.Root(root, "documents", true, false, List.of(), List.of()))
        .toList();
    RecordedRootPlan scope = new RecordedRootPlan("frozen-serving-generation", roots);
    RecordedBulkPlan plan = new RecordedBulkPlan(profile, profile.defaultSource(), scope,
        new IndexTargetSnapshot(sha256(INPUTS), INPUTS));
    OperationPreparation preparation = new OperationPreparation(arguments,
        RecordedBulkPlan.SCHEMA, plan.toReplayPayload(), OperationPreparation.Content.METADATA);
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, NONCE, descriptor, preparation, acceptanceContext,
        EngineProvenance.invocation(acceptanceContext, ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    OperationStore.Preparation stored = new OperationStore.Preparation(NONCE, codec.encode(envelope));

    var continuation = new OperationAuthorizationBasis.PreparedContinuation(key, NONCE);
    EngineContext acceptedContext = acceptanceContext.withGrantReference(Optional.of(continuation.encode()));
    OperationRecord row = new OperationRecord(1L, key, descriptor, acceptedContext, "UI",
        "bulk-recovery-owner", "bulk-session", state,
        state == OperationState.ACCEPTED ? "accepted" : "running", null, 0L, 0L,
        state == OperationState.RUNNING ? 1 : 0, OCCURRED_AT.toEpochMilli(),
        state == OperationState.RUNNING ? OCCURRED_AT.plusSeconds(1).toEpochMilli() : null,
        OCCURRED_AT.plusSeconds(1).toEpochMilli(), null, null, null,
        io.justsearch.app.api.operations.OperationHistoryMode.NONE, OCCURRED_AT, null);
    return new Fixture(authority, arguments, plan, row, stored, acceptanceContext);
  }

  private static OperationStore.Preparation acceptedPreparation(Fixture fixture,
      OperationDescriptor descriptor, OperationPreparation preparation) {
    var codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(fixture.row().key(), NONCE, descriptor, preparation,
        fixture.acceptanceContext(), EngineProvenance.invocation(fixture.acceptanceContext(),
            ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    return new OperationStore.Preparation(NONCE, codec.encode(envelope));
  }

  private static void writeWatchedRoots(Path dataDirectory, List<Path> roots) throws IOException {
    String entries = roots.stream().map(path -> "{\"path\":\""
        + path.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
            .replace("\"", "\\\"") + "\"}")
        .collect(java.util.stream.Collectors.joining(","));
    Files.writeString(dataDirectory.resolve("watched_roots.json"),
        "{\"schemaVersion\":1,\"roots\":[" + entries + "]}");
  }

  private static OperationRecord withContext(OperationRecord row, EngineContext context) {
    return new OperationRecord(row.id(), row.key(), row.descriptor(), context, row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withDescriptor(OperationRecord row, OperationDescriptor descriptor) {
    return new OperationRecord(row.id(), row.key(), descriptor, row.context(), row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withState(OperationRecord row, OperationState state) {
    return new OperationRecord(row.id(), row.key(), row.descriptor(), row.context(), row.executor(),
        row.initiator(), row.correlationId(), state,
        state == OperationState.ACCEPTED ? "accepted"
            : state == OperationState.COMPLETE_WITH_GAPS ? "awaiting_acceptance" : "terminal",
        row.checkpointCursor(), row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(),
        row.startedAt(), row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(),
        row.historyMode(), row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static void assertRefused(String expectedCode,
      OperationAuthority.RecordedBulkRecoveryDecision decision) {
    var refused = assertInstanceOf(OperationAuthority.RecordedBulkRecoveryDecision.Refused.class,
        decision, "expected permanent bulk recovery refusal");
    assertEquals(expectedCode, refused.receipt().code());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Fixture(OperationAuthority authority, String arguments, RecordedBulkPlan plan,
      OperationRecord row, OperationStore.Preparation preparation, EngineContext acceptanceContext) {}
}
