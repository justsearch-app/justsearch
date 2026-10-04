/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/** Operator decision to cancel one exact, uncommitted recorded rebuild. */
public final class CancelReindexHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;

  public CancelReindexHandler(RecordedIngestionService ingestion) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
  }

  private static String key(String argumentsJson) {
    JsonNode root = HandlerJson.MAPPER.readTree(argumentsJson);
    if (!root.isObject() || root.size() != 1 || !root.path("reindexKey").isString()) {
      throw new IllegalArgumentException("Exactly one rebuild key is required");
    }
    String key = root.path("reindexKey").asString();
    OperationKeys.timestampMillis(key);
    return key;
  }

  @Override public OperationPreparation prepare(String argumentsJson,
      InvocationProvenance provenance, EngineContext context) {
    if (context.clientKind() != EngineContext.ClientKind.WEBVIEW) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Only the webview can cancel a rebuild", "REINDEX_CANCEL_REQUIRES_USER",
          Map.of(), false));
    }
    try { key(argumentsJson); }
    catch (RuntimeException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Invalid rebuild key", "BAD_REQUEST", Map.of(), false));
    }
    return OperationPreparation.passthrough(argumentsJson);
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    return new OperationApprovalPreview("Cancel recorded rebuild " + key(prepared.argumentsJson()));
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    try { return ingestion.cancelReindex(key(argumentsJson), context); }
    catch (IllegalArgumentException invalid) {
      return OperationResult.failure("Invalid rebuild key", "BAD_REQUEST", Map.of(), false);
    }
  }
}
