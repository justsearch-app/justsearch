/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.executor;

import io.justsearch.agent.api.registry.OperationKind;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.app.api.operations.OperationAuthorizationBasis;
import io.justsearch.app.api.operations.OperationDescriptor;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Strict accepted-row binding for the installer-produced generation activation. */
public final class RecordedInstallerGenerationPlanResolver {
  private static final Set<String> SOURCE_ARGUMENT_FIELDS = Set.of("source");
  private static final JsonMapper JSON = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .build();

  /**
   * Resolves the immutable v2 candidate only when the accepted row and its stored preparation
   * describe the same durable activation. Current authority, target readiness and asset identity
   * are checked by the execution owner after this binding step.
   */
  public RecordedInstallerGenerationPlan resolve(
      OperationRecord row, OperationStore.Preparation stored) {
    Objects.requireNonNull(row, "row");
    Objects.requireNonNull(stored, "stored");
    var descriptor = row.descriptor();
    if (descriptor.kind() != OperationKind.REINDEX
        || !RecordedInstallerGenerationPlan.OPERATION_ID.equals(descriptor.operationRef())
        || row.context().survival() != EngineContext.Survival.DURABLE) {
      throw new IllegalArgumentException(
          "Installer generation preparation requires the durable activation reindex row");
    }

    OperationPreparation preparation = PreparedInvocationCodec.decodeAcceptedMetadata(row, stored);
    if (preparation.content() != OperationPreparation.Content.METADATA
        || !RecordedInstallerGenerationPlan.SCHEMA.equals(preparation.replaySchema())
        || !descriptor.hasSameIdentity(OperationDescriptor.invocation(
            OperationKind.REINDEX, RecordedInstallerGenerationPlan.OPERATION_ID,
            preparation.argumentsJson(), false))) {
      throw new IllegalArgumentException("Installer generation preparation identity mismatch");
    }

    var basis = OperationAuthorizationBasis.decode(
        row.context().grantReference().orElse(null));
    if (!(basis instanceof OperationAuthorizationBasis.PreparedContinuation continuation)
        || !continuation.operationKey().equals(row.key())
        || !continuation.preparationNonce().equals(stored.nonce())) {
      throw new IllegalArgumentException("Installer generation continuation identity mismatch");
    }

    String source = sourceForArguments(preparation.argumentsJson());
    RecordedInstallerGenerationPlan plan = RecordedInstallerGenerationPlan.fromReplayPayload(
        preparation.replaySchema(), preparation.replayPayloadJson());
    if (!RecordedInstallerGenerationPlan.OPERATION_ID.equals(plan.operationId())
        || plan.profile() != RecordedInstallerGenerationPlan.Profile.INSTALLER_GENERATION
        || !RecordedInstallerGenerationPlan.SOURCE.equals(plan.source())
        || !source.equals(plan.source())
        || plan.operationKey() == null
        || !row.key().equals(plan.operationKey())
        || !plan.sourceGeneration().equals(plan.scope().generation())) {
      throw new IllegalArgumentException("Installer generation plan differs from its accepted row");
    }
    return plan;
  }

  private static String sourceForArguments(String argumentsJson) {
    final Object decoded;
    try {
      decoded = JSON.readValue(argumentsJson, Object.class);
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid installer activation arguments", malformed);
    }
    if (!(decoded instanceof Map<?, ?> fields)
        || !fields.keySet().equals(SOURCE_ARGUMENT_FIELDS)
        || !(fields.get("source") instanceof String source)
        || !MigrationSource.INSTALLER_MODEL_ACTIVATION.wire().equals(source)) {
      throw new IllegalArgumentException("Installer activation source differs from its operation");
    }
    return source;
  }
}
