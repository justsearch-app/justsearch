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
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RecordedInstallerGenerationPlanResolverTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final Clock KEY_CLOCK = Clock.fixed(OCCURRED_AT, java.time.ZoneOffset.UTC);
  private static final String ARGUMENTS = "{\"source\":\"installer_model_activation\"}";
  private static final String ROOT_PATH = "C:/private-installer-root";
  private static final String SETTINGS = "{\"models\":{}}";
  private static final String TARGET = "{\"dimension\":768}";
  private static final UUID NONCE = UUID.fromString("00000000-0000-4000-8000-000000000121");

  @Test
  void resolvesExactAcceptedInstallerActivationCandidate() {
    Fixture fixture = fixture();

    assertEquals(fixture.plan(), new RecordedInstallerGenerationPlanResolver()
        .resolve(fixture.row(), fixture.preparation()));
  }

  @Test
  void rejectsWrongOperationKindOrOperationReference() {
    Fixture fixture = fixture();
    OperationDescriptor wrongKind = OperationDescriptor.invocation(
        OperationKind.OPERATION, RecordedInstallerGenerationPlan.OPERATION_ID, ARGUMENTS, false);
    OperationDescriptor wrongReference = OperationDescriptor.invocation(
        OperationKind.REINDEX, "core.bulk-reindex", ARGUMENTS, false);

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            withDescriptor(fixture.row(), wrongKind), fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            withDescriptor(fixture.row(), wrongReference), fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            withSurvival(fixture.row(), EngineContext.Survival.INTERACTIVE), fixture.preparation()));
  }

  @Test
  void rejectsWrongSchemaOrPublicSource() {
    Fixture fixture = fixture();
    OperationPreparation wrongSchema = new OperationPreparation(
        ARGUMENTS, "recorded-bulk-reindex-v1", fixture.plan().toReplayPayload(),
        OperationPreparation.Content.METADATA);
    OperationPreparation wrongSource = new OperationPreparation(
        "{\"source\":\"user_requested_rebuild\"}",
        RecordedInstallerGenerationPlan.SCHEMA, fixture.plan().toReplayPayload(),
        OperationPreparation.Content.METADATA);
    OperationRecord wrongSourceRow = withDescriptor(fixture.row(), OperationDescriptor.invocation(
        OperationKind.REINDEX, RecordedInstallerGenerationPlan.OPERATION_ID,
        wrongSource.argumentsJson(), false));

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            fixture.row(), reencode(fixture, wrongSchema)));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            wrongSourceRow, reencode(fixture, wrongSource, wrongSourceRow.descriptor())));
  }

  @Test
  void rejectsMalformedV2Payload() {
    Fixture fixture = fixture();
    OperationPreparation malformed = new OperationPreparation(
        ARGUMENTS, RecordedInstallerGenerationPlan.SCHEMA, "{\"operationId\":\"broken\"}",
        OperationPreparation.Content.METADATA);

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            fixture.row(), reencode(fixture, malformed)));
  }

  @Test
  void rejectsPlanWithUnboundOrDifferentAcceptedRunnerKey() {
    Fixture fixture = fixture();
    RecordedInstallerGenerationPlan unbound = new RecordedInstallerGenerationPlan(
        fixture.plan().sourceGeneration(), fixture.plan().scope(), fixture.plan().target(),
        fixture.plan().settingsWitness(), fixture.plan().candidateSettings(),
        fixture.plan().models(), fixture.plan().assets(), fixture.plan().acquisition());
    OperationStore.Preparation unboundPreparation = reencode(fixture, new OperationPreparation(
        ARGUMENTS, RecordedInstallerGenerationPlan.SCHEMA, unbound.toReplayPayload(),
        OperationPreparation.Content.METADATA));

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            fixture.row(), unboundPreparation));
  }

  @Test
  void rejectsForgedContinuationKeyOrNonce() {
    Fixture fixture = fixture();
    String differentKey = OperationKeys.generate(Clock.fixed(OCCURRED_AT.plusSeconds(1),
        java.time.ZoneOffset.UTC));
    OperationRecord differentKeyRow = withContext(fixture.row(), fixture.row().context()
        .withGrantReference(Optional.of(new OperationAuthorizationBasis.PreparedContinuation(
            differentKey, NONCE).encode())));
    OperationStore.Preparation differentNonce = new OperationStore.Preparation(
        UUID.fromString("00000000-0000-4000-8000-000000000122"), fixture.preparation().payload());

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            differentKeyRow, fixture.preparation()));
    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(
            fixture.row(), differentNonce));
  }

  @Test
  void rejectsNonContinuationAuthorizationBasis() {
    Fixture fixture = fixture();
    OperationRecord auto = withContext(fixture.row(), fixture.row().context()
        .withGrantReference(Optional.of("jsa1:auto")));

    assertThrows(IllegalArgumentException.class,
        () -> new RecordedInstallerGenerationPlanResolver().resolve(auto, fixture.preparation()));
  }

  private static Fixture fixture() {
    String key = OperationKeys.generate(KEY_CLOCK);
    OperationDescriptor descriptor = OperationDescriptor.invocation(
        OperationKind.REINDEX, RecordedInstallerGenerationPlan.OPERATION_ID, ARGUMENTS, false);
    EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "root-owner",
        Optional.of("session-id"), Optional.empty(), TransportTag.SYSTEM_INTERNAL,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    RecordedRootPlan scope = new RecordedRootPlan("serving-generation", List.of(
        new RecordedRootPlan.Root(Path.of(ROOT_PATH), "documents", true, false,
            List.of("*.tmp"), List.of())));
    var acquisition = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.REGISTRY,
        "manifest-1", "a".repeat(64));
    var model = new RecordedInstallerGenerationPlan.ModelIdentity("embedding", "fp32",
        Path.of(ROOT_PATH).resolve("embedding.onnx"), "a".repeat(64), 1, acquisition);
    var asset = new RecordedInstallerGenerationPlan.AssetIdentity("embedding",
        Path.of(ROOT_PATH).resolve("embedding.bin"), "a".repeat(64), 1, acquisition);
    RecordedInstallerGenerationPlan plan = new RecordedInstallerGenerationPlan(
        key, "serving-generation", scope,
        new IndexTargetSnapshot(sha256(TARGET), TARGET), new SettingsWitness(3, key),
        RecordedInstallerGenerationPlan.CandidateSettings.fromJson(SETTINGS),
        List.of(model), List.of(asset), acquisition);
    OperationPreparation preparation = new OperationPreparation(
        ARGUMENTS, RecordedInstallerGenerationPlan.SCHEMA, plan.toReplayPayload(),
        OperationPreparation.Content.METADATA);
    PreparedInvocationCodec codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, NONCE, descriptor, preparation, context,
        EngineProvenance.invocation(context, ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    OperationStore.Preparation stored = new OperationStore.Preparation(NONCE, codec.encode(envelope));
    OperationRecord row = row(key, descriptor,
        context.withGrantReference(Optional.of(new OperationAuthorizationBasis.PreparedContinuation(
            key, NONCE).encode())));
    return new Fixture(plan, row, stored);
  }

  private static OperationStore.Preparation reencode(Fixture fixture,
      OperationPreparation preparation) {
    return reencode(fixture, preparation, fixture.row().descriptor());
  }

  private static OperationStore.Preparation reencode(Fixture fixture,
      OperationPreparation preparation, OperationDescriptor descriptor) {
    PreparedInvocationCodec codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(fixture.row().key(), NONCE, descriptor, preparation,
        fixture.row().context().withGrantReference(Optional.empty()),
        EngineProvenance.invocation(fixture.row().context().withGrantReference(Optional.empty()),
            ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    return new OperationStore.Preparation(NONCE, codec.encode(envelope));
  }

  private static OperationRecord row(String key, OperationDescriptor descriptor, EngineContext context) {
    return new OperationRecord(1L, key, descriptor, context, "UI", "root-owner", "session-id",
        OperationState.ACCEPTED, "accepted", null, 0L, 0L, 0, 1L, null, 1L, null,
        null, null, io.justsearch.app.api.operations.OperationHistoryMode.NONE,
        OCCURRED_AT, null);
  }

  private static OperationRecord withDescriptor(OperationRecord row, OperationDescriptor descriptor) {
    return new OperationRecord(row.id(), row.key(), descriptor, row.context(), row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withContext(OperationRecord row, EngineContext context) {
    return new OperationRecord(row.id(), row.key(), row.descriptor(), context, row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withSurvival(OperationRecord row, EngineContext.Survival survival) {
    EngineContext original = row.context();
    return withContext(row, new EngineContext(original.clientKind(), original.clientId(),
        original.sessionId(), original.grantReference(), original.sourceTier(), original.transport(),
        survival, original.urgency()));
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private record Fixture(RecordedInstallerGenerationPlan plan, OperationRecord row,
      OperationStore.Preparation preparation) {}
}
