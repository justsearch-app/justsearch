/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.*;
import io.justsearch.app.api.operations.RecordedImportResume;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;

/** Prepares fresh approval over the retained import, never a newly partitioned folder request. */
public final class ResumeImportHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;

  public ResumeImportHandler(RecordedIngestionService ingestion) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
  }

  @Override public OperationResult execute(String arguments, EngineContext context) {
    throw new IllegalStateException("Import renewal requires accepted prepared approval");
  }

  @Override public OperationPreparation prepare(String arguments, InvocationProvenance provenance,
      EngineContext context) {
    try {
      var args = HandlerJson.MAPPER.readTree(arguments);
      if (!args.isObject() || args.size() != 2 || !args.path("operationKey").isTextual()
          || !args.path("interruptionRevision").isIntegralNumber()) throw new IllegalArgumentException();
      var plan = ingestion.prepareImportResume(args.path("operationKey").asString(),
          args.path("interruptionRevision").asLong());
      return new OperationPreparation(arguments, RecordedImportResume.SCHEMA, plan.encode());
    } catch (IllegalArgumentException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "This import interruption has changed; refresh the Library", "IMPORT_RESUME_STALE", Map.of(), false));
    }
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedImportResume.SCHEMA.equals(prepared.replaySchema())) {
      throw new IllegalArgumentException("Invalid import renewal preparation");
    }
    var plan = RecordedImportResume.decode(prepared.replayPayloadJson());
    var args = HandlerJson.MAPPER.readTree(prepared.argumentsJson());
    if (!args.isObject() || args.size() != 2 || !args.path("operationKey").isTextual()
        || !args.path("interruptionRevision").isIntegralNumber()
        || !plan.operationKey().equals(args.path("operationKey").asString())
        || plan.interruptionRevision() != args.path("interruptionRevision").asLong()) {
      throw new IllegalArgumentException("Import renewal arguments changed");
    }
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    var plan = RecordedImportResume.decode(prepared.replayPayloadJson());
    StringBuilder summary = new StringBuilder("Resume the retained import: ");
    int shown = Math.min(6, plan.plan().roots().size());
    for (int i = 0; i < shown; i++) {
      var root = plan.plan().roots().get(i);
      String path = root.path().toString();
      summary.append("\n").append(root.singleFile() ? "File: " : "Folder: ")
          .append(path, 0, Math.min(path.length(), 256));
      if (path.length() > 256) summary.append("…");
      summary.append("; collection: ").append(root.collection() == null ? "original collection" : root.collection())
          .append("; excluded subfolders: ").append(root.excludedSubtrees().size())
          .append("; frozen exclusion rules: ").append(root.excludePatterns().size());
      if (root.force()) summary.append("; re-extract existing files");
    }
    if (shown < plan.plan().roots().size()) summary.append("\nAnd ")
        .append(plan.plan().roots().size() - shown).append(" more original locations shown in the Library.");
    summary.append("\nKeep the original folders, collections and exclusions. Current exclusions can restrict them further.");
    return new OperationApprovalPreview(summary.toString());
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    return ingestion.resumeImport(RecordedImportResume.decode(prepared.replayPayloadJson()),
        Objects.requireNonNull(record, "Accepted renewal"), context);
  }
}
