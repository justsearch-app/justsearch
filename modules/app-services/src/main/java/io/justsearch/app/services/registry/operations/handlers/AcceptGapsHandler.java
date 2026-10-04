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
import io.justsearch.app.api.operations.RecordedGapAcceptancePlan;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;

/** Webview-only recorded decision on one exact nonterminal migration gap list. */
public final class AcceptGapsHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;

  public AcceptGapsHandler(RecordedIngestionService ingestion) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Gap acceptance requires a prepared recorded invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson,
      InvocationProvenance provenance, EngineContext context) {
    if (context.clientKind() != EngineContext.ClientKind.WEBVIEW) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Only the webview can accept migration gaps", "GAP_ACCEPTANCE_REQUIRES_USER",
          Map.of(), false));
    }
    final RecordedGapAcceptancePlan plan;
    try { plan = RecordedGapAcceptancePlan.decode(argumentsJson); }
    catch (RuntimeException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Invalid gap decision", "BAD_REQUEST", Map.of(), false));
    }
    return new OperationPreparation(argumentsJson, RecordedGapAcceptancePlan.SCHEMA,
        plan.toReplayPayload());
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedGapAcceptancePlan.SCHEMA.equals(prepared.replaySchema())
        || !RecordedGapAcceptancePlan.decode(prepared.argumentsJson()).equals(
            RecordedGapAcceptancePlan.decode(prepared.replayPayloadJson()))) {
      throw new IllegalArgumentException("Gap acceptance preparation changed");
    }
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    var plan = RecordedGapAcceptancePlan.decode(prepared.replayPayloadJson());
    return new OperationApprovalPreview("Accept current migration gaps for operation "
        + plan.reindexKey() + " (list " + plan.gapListHash().substring(0, 12) + ")");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    Objects.requireNonNull(record, "Accepted gap decision record");
    var plan = RecordedGapAcceptancePlan.decode(prepared.replayPayloadJson());
    return OperationExecution.finished(ingestion.acceptGaps(
        plan.reindexKey(), plan.gapListHash(), context));
  }
}
