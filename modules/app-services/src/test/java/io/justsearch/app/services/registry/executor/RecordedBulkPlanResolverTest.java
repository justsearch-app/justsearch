/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RecordedBulkPlanResolverTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final Clock KEY_CLOCK = Clock.fixed(OCCURRED_AT, java.time.ZoneOffset.UTC);
  private static final String USER_ARGUMENTS = "{\"corpusIds\":[\"docs\"]}";
  private static final String INPUTS = "{\"dimension\":768}";
  private static final String ROOT_PATH = "C:/private-bulk-root";
  private static final UUID NONCE = UUID.fromString("00000000-0000-4000-8000-000000000121");

  @Test
  void resolvesAcceptedUserBulkIdentityAndFrozenTarget() {
    Fixture fixture = fixture(RecordedBulkPlan.Profile.USER_BULK, USER_ARGUMENTS,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), RecordedBulkPlan.Profile.USER_BULK);

    assertEquals(fixture.plan(), new RecordedBulkPlanResolver()
        .resolve(fixture.row(), fixture.preparation()));
  }

  @Test
  void resolvesAcceptedRecoveryRebuildWithoutCorpusLabels() {
    Fixture fixture = fixture(RecordedBulkPlan.Profile.RECOVERY_REBUILD, "{}",
        MigrationSource.USER_REQUESTED_REBUILD.wire(), RecordedBulkPlan.Profile.RECOVERY_REBUILD);

    assertEquals(fixture.plan(), new RecordedBulkPlanResolver()
        .resolve(fixture.row(), fixture.preparation()));
  }

  @Test
  void rejectsPlanProfileOrSourceThatDiffersFromPublicInvocation() {
    Fixture wrongProfile = fixture(RecordedBulkPlan.Profile.USER_BULK, USER_ARGUMENTS,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), RecordedBulkPlan.Profile.RECOVERY_REBUILD);
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(wrongProfile.row(), wrongProfile.preparation()));

    Fixture wrongSource = fixture(RecordedBulkPlan.Profile.USER_BULK,
        "{\"corpusIds\":[\"docs\"],\"source\":\"manual\"}",
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), RecordedBulkPlan.Profile.USER_BULK);
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(wrongSource.row(), wrongSource.preparation()));
  }

  @Test
  void refusesWrongOperationKindOrNonDurableRows() {
    Fixture fixture = fixture(RecordedBulkPlan.Profile.USER_BULK, USER_ARGUMENTS,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), RecordedBulkPlan.Profile.USER_BULK);
    OperationDescriptor wrongKind = OperationDescriptor.invocation(
        OperationKind.OPERATION, RecordedBulkPlan.Profile.USER_BULK.operationRef(), USER_ARGUMENTS, false);
    OperationRecord wrongKindRow = withDescriptor(fixture.row(), wrongKind);

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(wrongKindRow, fixture.preparation()));
    OperationDescriptor wrongRef = OperationDescriptor.invocation(OperationKind.REINDEX,
        RecordedBulkPlan.Profile.RECOVERY_REBUILD.operationRef(), USER_ARGUMENTS, false);
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(withDescriptor(fixture.row(), wrongRef), fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(withSurvival(fixture.row(), EngineContext.Survival.INTERACTIVE),
            fixture.preparation()));
  }

  @Test
  void acceptedPreparationMustMatchContinuationKeyNonceContextAndProvenance() {
    Fixture fixture = fixture(RecordedBulkPlan.Profile.USER_BULK, USER_ARGUMENTS,
        MigrationSource.USER_REQUESTED_BULK_REINDEX.wire(), RecordedBulkPlan.Profile.USER_BULK);
    OperationRecord differentKey = withKey(fixture.row(), OperationKeys.generate(
        Clock.fixed(OCCURRED_AT.plusSeconds(1), java.time.ZoneOffset.UTC)));
    OperationStore.Preparation differentNonce = new OperationStore.Preparation(
        UUID.fromString("00000000-0000-4000-8000-000000000122"), fixture.preparation().payload());
    OperationRecord differentExecutor = withExecutor(fixture.row(), "AGENT");
    EngineContext differentContext = EngineProvenance.internal(
        "different-owner", EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    OperationRecord differentProvenance = withProvenance(fixture.row(), "different-owner");

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(differentKey, fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(fixture.row(), differentNonce));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(withContext(fixture.row(), differentContext), fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(differentProvenance, fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedBulkPlanResolver().resolve(differentExecutor, fixture.preparation()));
  }

  private static Fixture fixture(RecordedBulkPlan.Profile rowProfile, String arguments,
      String planSource, RecordedBulkPlan.Profile planProfile) {
    String key = OperationKeys.generate(KEY_CLOCK);
    OperationDescriptor descriptor = OperationDescriptor.invocation(OperationKind.REINDEX,
        rowProfile.operationRef(), arguments, false);
    EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "root-owner",
        Optional.of("session-id"), Optional.empty(), TransportTag.SYSTEM_INTERNAL,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    RecordedRootPlan scope = new RecordedRootPlan("serving-generation", List.of(
        new RecordedRootPlan.Root(Path.of(ROOT_PATH), "documents", true, false,
            List.of("*.tmp"), List.of())));
    RecordedBulkPlan plan = new RecordedBulkPlan(planProfile, planSource, scope,
        new IndexTargetSnapshot(sha256(INPUTS), INPUTS));
    OperationPreparation preparation = new OperationPreparation(arguments,
        RecordedBulkPlan.SCHEMA, plan.toReplayPayload(), OperationPreparation.Content.METADATA);
    PreparedInvocationCodec codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, NONCE, descriptor, preparation, context,
        EngineProvenance.invocation(context, ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    OperationStore.Preparation stored = new OperationStore.Preparation(NONCE, codec.encode(envelope));
    OperationRecord row = row(key, descriptor,
        context.withGrantReference(Optional.of(new OperationAuthorizationBasis.PreparedContinuation(
            key, NONCE).encode())), "UI", "root-owner", "session-id", OCCURRED_AT);
    return new Fixture(plan, row, stored);
  }

  private static OperationRecord row(String key, OperationDescriptor descriptor, EngineContext context,
      String executor, String initiator, String correlationId, Instant occurredAt) {
    return new OperationRecord(1L, key, descriptor, context, executor, initiator, correlationId,
        OperationState.ACCEPTED, "accepted", null, 0L, 0L, 0, 1L, null, 1L, null,
        null, null, io.justsearch.app.api.operations.OperationHistoryMode.NONE, occurredAt, null);
  }

  private static OperationRecord withDescriptor(OperationRecord row, OperationDescriptor descriptor) {
    return new OperationRecord(row.id(), row.key(), descriptor, row.context(), row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withKey(OperationRecord row, String key) {
    return row(key, row.descriptor(), row.context(), row.executor(), row.initiator(),
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static OperationRecord withExecutor(OperationRecord row, String executor) {
    return new OperationRecord(row.id(), row.key(), row.descriptor(), row.context(), executor,
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withContext(OperationRecord row, EngineContext context) {
    return row(row.key(), row.descriptor(), context, row.executor(), row.initiator(),
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static OperationRecord withSurvival(OperationRecord row, EngineContext.Survival survival) {
    EngineContext original = row.context();
    EngineContext context = new EngineContext(original.clientKind(), original.clientId(), original.sessionId(),
        original.grantReference(), original.sourceTier(), original.transport(), survival, original.urgency());
    return withContext(row, context);
  }

  private static OperationRecord withProvenance(OperationRecord row, String initiator) {
    return row(row.key(), row.descriptor(), row.context(), row.executor(), initiator,
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Fixture(RecordedBulkPlan plan, OperationRecord row,
      OperationStore.Preparation preparation) {}

}
