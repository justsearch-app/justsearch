/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.OperationRecord;
import io.justsearch.app.api.operations.OperationStore;
import io.justsearch.app.api.settings.SettingsCandidateContext;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.services.registry.executor.PreparedInvocationCodec;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Prepared dispatch for {@code core.reconfigure}.
 *
 * <p>The settings HTTP front owns admission and supplies this operation with one typed envelope.
 * Preparation only freezes that envelope. Acceptance, component composition, settings
 * replacement, and terminal outcome remain owned by {@link SettingsService} and the operation
 * runner; this handler never accepts or starts a nested settings operation.
 */
public final class ReconfigureHandler implements OperationHandler {
  public static final String SCHEMA = "settings-reconfigure-v1";
  private final Supplier<SettingsService> supplier;

  public ReconfigureHandler(Supplier<SettingsService> supplier) {
    this.supplier = Objects.requireNonNull(supplier, "supplier");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    throw new IllegalStateException("Reconfigure requires an accepted prepared invocation");
  }

  @Override
  public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    final Envelope envelope;
    try {
      envelope = decodeEnvelope(argumentsJson);
    } catch (RuntimeException malformed) {
      throw invalidArguments();
    }
    return new OperationPreparation(argumentsJson, SCHEMA,
        HandlerJson.MAPPER.writeValueAsString(envelope));
  }

  @Override
  public void validatePreparation(OperationPreparation prepared) {
    validatedEnvelope(prepared);
  }

  private static Envelope validatedEnvelope(OperationPreparation prepared) {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !SCHEMA.equals(prepared.replaySchema())) {
      throw new IllegalArgumentException("Reconfigure preparation schema mismatch");
    }
    Envelope publicEnvelope = decodeEnvelope(prepared.argumentsJson());
    Envelope replayEnvelope;
    try {
      replayEnvelope = decodeEnvelope(prepared.replayPayloadJson());
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Invalid reconfigure preparation");
    }
    if (!publicEnvelope.equals(replayEnvelope)) {
      throw new IllegalArgumentException("Reconfigure preparation differs from its invocation");
    }
    return replayEnvelope;
  }

  /** The settings owner verifies a physical refresh against this exact accepted invocation. */
  public static SettingsCandidateContext acceptedCandidateContext(OperationRecord row,
      OperationStore.Preparation accepted) {
    if (row.descriptor().kind() != io.justsearch.agent.api.registry.OperationKind.RECONFIGURE
        || !"core.reconfigure".equals(row.descriptor().operationRef())) {
      throw new IllegalArgumentException("Reconfigure candidate row mismatch");
    }
    Envelope envelope = validatedEnvelope(
        PreparedInvocationCodec.decodeAcceptedReconfigureMetadata(row, accepted));
    if (!row.key().equals(envelope.settings().operationKey())) {
      throw new IllegalArgumentException("Reconfigure candidate operation identity mismatch");
    }
    return envelope.refreshInference()
        ? new SettingsCandidateContext(null, true) : SettingsCandidateContext.NONE;
  }

  @Override
  public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    Envelope envelope = validatedEnvelope(prepared);
    return new OperationApprovalPreview(envelope.refreshInference()
        ? "Refresh the generative runtime with the accepted settings"
        : "Apply the accepted settings update");
  }

  @Override
  public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    Objects.requireNonNull(record, "accepted reconfigure record");
    Envelope envelope = decodeEnvelope(prepared.replayPayloadJson());
    if (!record.key().equals(envelope.settings().operationKey())) {
      throw new IllegalArgumentException("Reconfigure operation identity mismatch");
    }
    return OperationExecution.finished(
        service().applyAccepted(envelope.settings(), envelope.modeIntent(),
            Objects.requireNonNull(context, "context"), record, envelope.refreshInference()));
  }

  private SettingsService service() {
    SettingsService service = supplier.get();
    if (service == null) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Settings service unavailable", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
    }
    return service;
  }

  private static Envelope decodeEnvelope(String argumentsJson) {
    Objects.requireNonNull(argumentsJson, "argumentsJson");
    final JsonNode root;
    try {
      root = HandlerJson.MAPPER.readTree(argumentsJson);
    } catch (JacksonException malformed) {
      throw new IllegalArgumentException("Invalid reconfigure envelope");
    }
    if (root == null || !root.isObject() || (root.size() != 2 && root.size() != 3)
        || !root.has("settings") || !root.has("modeIntent")) {
      throw new IllegalArgumentException("Reconfigure envelope must contain settings and modeIntent");
    }
    JsonNode refreshInference = root.get("refreshInference");
    if ((root.size() == 3 && (refreshInference == null || !refreshInference.isBoolean()))
        || (root.size() == 2 && refreshInference != null)) {
      throw new IllegalArgumentException("refreshInference must be a boolean");
    }
    JsonNode modeIntent = root.get("modeIntent");
    if (!modeIntent.isNull() && !modeIntent.isTextual()) {
      throw new IllegalArgumentException("modeIntent must be a string or null");
    }
    final Envelope envelope;
    try {
      envelope = new Envelope(HandlerJson.MAPPER.treeToValue(root.get("settings"), SettingsV2.class),
          modeIntent.isNull() ? null : modeIntent.asText(),
          refreshInference != null && refreshInference.booleanValue());
    } catch (JacksonException | IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Invalid reconfigure settings envelope");
    }
    SettingsV2 settings = envelope.settings();
    if (settings == null || settings.witness() == null || settings.operationKey() == null) {
      throw new IllegalArgumentException("Reconfigure settings require a full witness and operation key");
    }
    OperationKeys.timestampMillis(settings.operationKey());
    return envelope;
  }

  private static OperationPreparationRefused invalidArguments() {
    return new OperationPreparationRefused(OperationResult.failure(
        "Invalid reconfigure envelope", "BAD_REQUEST", Map.of(), false));
  }

  /** Public input and durable replay use the same typed values. */
  public record Envelope(SettingsV2 settings, String modeIntent, boolean refreshInference) {}
}
