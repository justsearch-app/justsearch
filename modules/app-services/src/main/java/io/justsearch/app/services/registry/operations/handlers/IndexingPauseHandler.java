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
import io.justsearch.app.api.settings.IndexingPausePolicy;
import io.justsearch.app.services.runtimestate.RuntimeIntentPreparation;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;

/** UI intent only: the existing accepted settings transaction owns persistence and publication. */
public final class IndexingPauseHandler implements OperationHandler {
  private static final ObjectReader JSON = HandlerJson.MAPPER.reader()
      .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final Supplier<RuntimeSpecStore> store;
  private final boolean paused;

  public IndexingPauseHandler(Supplier<RuntimeSpecStore> store, boolean paused) {
    this.store = Objects.requireNonNull(store, "store");
    this.paused = paused;
  }

  private RuntimeSpecStore writer() {
    var writer = store.get();
    if (writer == null) throw new OperationPreparationRefused(OperationResult.failure(
        "Settings commit owner unavailable", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
    return writer;
  }

  private IndexingPausePolicy policy(String arguments) {
    try {
      var root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      if (root == null || !root.isObject() || root.size() > (paused ? 1 : 0)
          || (root.size() == 1 && !root.has("untilEpochMs"))) {
        throw new IllegalArgumentException("Unexpected pause arguments");
      }
      var until = root.get("untilEpochMs");
      if (until != null && !until.isNull()
          && (!until.isIntegralNumber() || !until.canConvertToLong() || until.longValue() < 0)) {
        throw new IllegalArgumentException("untilEpochMs must be a non-negative epoch millisecond");
      }
      return paused ? new IndexingPausePolicy(true,
          until == null || until.isNull() ? null : until.longValue(), "USER_PAUSED") : null;
    } catch (RuntimeException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "Invalid indexing pause arguments", "BAD_REQUEST", Map.of(), false));
    }
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Indexing pause requires an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    policy(argumentsJson);
    return writer().prepareIntent(argumentsJson);
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    RuntimeIntentPreparation.validate(prepared);
    policy(prepared.argumentsJson());
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    return new OperationApprovalPreview(paused ? "Pause indexing" : "Resume indexing");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    return OperationExecution.finished(writer().applyIndexingPause(prepared,
        policy(prepared.argumentsJson()), Objects.requireNonNull(record, "accepted record")));
  }
}
