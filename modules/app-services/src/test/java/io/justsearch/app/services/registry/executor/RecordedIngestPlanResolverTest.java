/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.justsearch.agent.api.encryption.StoreCipher;
import io.justsearch.agent.api.registry.ExecutorTag;
import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.TransportTag;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationPreparedPayload;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationState;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.core.context.EngineContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

final class RecordedIngestPlanResolverTest {
  private static final Instant OCCURRED_AT = Instant.parse("2026-09-14T00:00:00Z");
  private static final Clock KEY_CLOCK = Clock.fixed(OCCURRED_AT, java.time.ZoneOffset.UTC);
  private static final String PUBLIC_ARGUMENTS =
      "{\"roots\":[\"C:/public-requested-root\"],\"generation\":\"public-generation\"}";
  private static final String PRIVATE_ROOT = "C:/private-frozen-root";

  @Test
  void acceptsCoreIngestFilesAndReturnsFrozenPlanInsteadOfPublicArguments() {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);

    RecordedRootPlan resolved = new RecordedIngestPlanResolver()
        .resolve(fixture.parent(), fixture.preparation());

    assertEquals(fixture.plan(), resolved);
    assertEquals(Path.of(PRIVATE_ROOT).toAbsolutePath().normalize(),
        resolved.roots().getFirst().path());
    assertFalse(resolved.toReplayPayload().contains("public-requested-root"));
  }

  @Test
  void acceptsCoreReindexUsingTheSameMetadataRootPlanContract() {
    Fixture fixture = fixture(OperationKind.REINDEX, "core.reindex", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);

    assertEquals(fixture.plan(), new RecordedIngestPlanResolver()
        .resolve(fixture.parent(), fixture.preparation()));
  }

  @ParameterizedTest
  @MethodSource("unsupportedProducerDescriptors")
  void rejectsUnrelatedOrContributedProducerIdentity(OperationDescriptor descriptor) {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(
            withDescriptor(fixture.parent(), descriptor), fixture.preparation()));

    assertEquals("Unsupported recorded ingestion producer", error.getMessage());
  }

  private static Stream<OperationDescriptor> unsupportedProducerDescriptors() {
    return Stream.of(
        OperationDescriptor.invocation(OperationKind.OPERATION, "core.file-note", PUBLIC_ARGUMENTS, false),
        OperationDescriptor.invocation(OperationKind.INGEST, "plugin.ingest-files", PUBLIC_ARGUMENTS, false),
        OperationDescriptor.invocation(OperationKind.REINDEX, "plugin.reindex", PUBLIC_ARGUMENTS, false));
  }

  @Test
  void rejectsUndoPreparationEvenWhenItsEnvelopeIsOtherwiseValid() {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", true,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(fixture.parent(), fixture.preparation()));

    assertEquals("Recorded ingestion preparation binding mismatch", error.getMessage());
  }

  @Test
  void rejectsSealedPayloadBeforeTreatingItAsMetadata() {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);
    OperationStore.Preparation sealed = new OperationStore.Preparation(
        fixture.nonce(), new OperationPreparedPayload(true, "sealed-value"));

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(fixture.parent(), sealed));

    assertEquals("Unsupported recorded ingestion producer", error.getMessage());
  }

  @Test
  void rejectsWrongReplaySchemaAfterCodecValidation() {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        "different-root-plan.v1", OperationPreparation.Content.METADATA);

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(fixture.parent(), fixture.preparation()));

    assertEquals("Recorded ingestion preparation binding mismatch", error.getMessage());
  }

  @ParameterizedTest
  @MethodSource("rowBindingMismatches")
  void rejectsRowContextProvenanceAndExecutorMismatches(String mismatch) {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);
    OperationRecord mismatched = switch (mismatch) {
      case "context" -> withContext(fixture.parent(), EngineProvenance.internal(
          "different-owner", EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND));
      case "provenance" -> withProvenance(fixture.parent(), "different-owner", "session-id", OCCURRED_AT);
      case "executor" -> withExecutor(fixture.parent(), ExecutorTag.AGENT.name());
      default -> throw new AssertionError(mismatch);
    };

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(mismatched, fixture.preparation()));

    assertEquals("Recorded ingestion preparation binding mismatch", error.getMessage());
  }

  private static Stream<String> rowBindingMismatches() {
    return Stream.of("context", "provenance", "executor");
  }

  @ParameterizedTest
  @MethodSource("changedBindings")
  void rejectsChangedKeyOrNonceThroughTheRealCodecBinding(String changedBinding) {
    Fixture fixture = fixture(OperationKind.INGEST, "core.ingest-files", false,
        RecordedRootPlan.SCHEMA, OperationPreparation.Content.METADATA);
    OperationRecord row = fixture.parent();
    OperationStore.Preparation preparation = fixture.preparation();
    if (changedBinding.equals("key")) {
      row = withKey(row, OperationKeys.generate(Clock.systemUTC()));
    } else {
      preparation = new OperationStore.Preparation(UUID.randomUUID(), preparation.payload());
    }
    OperationRecord expectedRow = row;
    OperationStore.Preparation expectedPreparation = preparation;

    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> new RecordedIngestPlanResolver().resolve(expectedRow, expectedPreparation));

    assertEquals("Invalid persisted preparation", error.getMessage());
  }

  private static Stream<String> changedBindings() {
    return Stream.of("key", "nonce");
  }

  private static Fixture fixture(OperationKind kind, String operationRef, boolean undo,
      String replaySchema, OperationPreparation.Content content) {
    String key = OperationKeys.generate(KEY_CLOCK);
    UUID nonce = UUID.randomUUID();
    OperationDescriptor descriptor = OperationDescriptor.invocation(kind, operationRef, PUBLIC_ARGUMENTS, undo);
    EngineContext context = EngineProvenance.context(EngineContext.ClientKind.INTERNAL, "root-owner",
        Optional.of("session-id"), Optional.empty(), TransportTag.SYSTEM_INTERNAL,
        EngineContext.Survival.DURABLE, EngineContext.Urgency.FOREGROUND);
    RecordedRootPlan plan = new RecordedRootPlan("frozen-generation", List.of(
        new RecordedRootPlan.Root(Path.of(PRIVATE_ROOT), "documents", true, false,
            List.of("*.tmp"), List.of())));
    OperationPreparation preparation = new OperationPreparation(PUBLIC_ARGUMENTS, replaySchema,
        plan.toReplayPayload(), content);
    PreparedInvocationCodec codec = new PreparedInvocationCodec(StoreCipher.disabled());
    var envelope = codec.freeze(key, nonce, descriptor, preparation, context,
        EngineProvenance.invocation(context, ExecutorTag.UI, OCCURRED_AT, Optional.empty()));
    OperationPreparedPayload payload = codec.encode(envelope);
    OperationStore.Preparation stored = new OperationStore.Preparation(nonce, payload);
    return new Fixture(key, nonce, plan, row(key, descriptor, context, "UI", "root-owner",
        "session-id", OCCURRED_AT), stored);
  }

  private static OperationRecord row(String key, OperationDescriptor descriptor, EngineContext context,
      String executor, String initiator, String correlationId, Instant occurredAt) {
    return new OperationRecord(1L, key, descriptor, context, executor, initiator, correlationId,
        OperationState.ACCEPTED, "accepted", null, 0L, 0L, 0, 1L, null, 1L, null,
        null, null, io.justsearch.app.api.operations.OperationHistoryMode.NONE,
        occurredAt, null);
  }

  private static OperationRecord withDescriptor(OperationRecord row, OperationDescriptor descriptor) {
    return new OperationRecord(row.id(), row.key(), descriptor, row.context(), row.executor(),
        row.initiator(), row.correlationId(), row.state(), row.phase(), row.checkpointCursor(),
        row.unitsCompleted(), row.unitsFailed(), row.attempts(), row.acceptedAt(), row.startedAt(),
        row.updatedAt(), row.completedAt(), row.failureReason(), row.receipt(), row.historyMode(),
        row.provenanceOccurredAt(), row.expectedSettingsRevision());
  }

  private static OperationRecord withContext(OperationRecord row, EngineContext context) {
    return with(row, row.key(), row.descriptor(), context, row.executor(), row.initiator(),
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static OperationRecord withProvenance(OperationRecord row, String initiator,
      String correlationId, Instant occurredAt) {
    return with(row, row.key(), row.descriptor(), row.context(), row.executor(), initiator,
        correlationId, occurredAt);
  }

  private static OperationRecord withExecutor(OperationRecord row, String executor) {
    return with(row, row.key(), row.descriptor(), row.context(), executor, row.initiator(),
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static OperationRecord withKey(OperationRecord row, String key) {
    return with(row, key, row.descriptor(), row.context(), row.executor(), row.initiator(),
        row.correlationId(), row.provenanceOccurredAt());
  }

  private static OperationRecord with(OperationRecord row, String key, OperationDescriptor descriptor,
      EngineContext context, String executor, String initiator, String correlationId, Instant occurredAt) {
    return row(row.id(), key, descriptor, context, executor, initiator, correlationId, occurredAt);
  }

  private static OperationRecord row(long id, String key, OperationDescriptor descriptor,
      EngineContext context, String executor, String initiator, String correlationId, Instant occurredAt) {
    return new OperationRecord(id, key, descriptor, context, executor, initiator, correlationId,
        OperationState.ACCEPTED, "accepted", null, 0L, 0L, 0, 1L, null, 1L, null,
        null, null, io.justsearch.app.api.operations.OperationHistoryMode.NONE,
        occurredAt, null);
  }

  private record Fixture(String key, UUID nonce, RecordedRootPlan plan, OperationRecord parent,
      OperationStore.Preparation preparation) {}
}
