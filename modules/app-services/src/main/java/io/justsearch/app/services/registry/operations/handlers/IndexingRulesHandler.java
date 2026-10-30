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
public final class IndexingRulesHandler implements OperationHandler {
  private static final ObjectReader JSON = HandlerJson.MAPPER.reader()
      .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final Supplier<RuntimeSpecStore> store;

  public IndexingRulesHandler(Supplier<RuntimeSpecStore> store) {
    this.store = Objects.requireNonNull(store, "store");
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
      if (root == null || !root.isObject()) throw new IllegalArgumentException("schedule and pauseOnBattery require an object");
      if (!root.has("schedule")) throw new IllegalArgumentException("schedule is required");
      if (!root.has("pauseOnBattery")) throw new IllegalArgumentException("pauseOnBattery is required");
      if (root.size() != 2) throw new IllegalArgumentException("schedule and pauseOnBattery are the only accepted fields");
      IndexingPausePolicy.validateRules(root);
      return new IndexingPausePolicy(false, null, null,
          IndexingPausePolicy.scheduleFromJson(root.get("schedule")), root.get("pauseOnBattery").booleanValue());
    } catch (RuntimeException invalid) {
      String message = invalid.getMessage();
      String field = message != null && message.startsWith("pauseOnBattery") ? "pauseOnBattery"
          : message != null && message.startsWith("schedule.") ? message.split(" ", 2)[0] : "schedule";
      throw new OperationPreparationRefused(OperationResult.failure(
          "Invalid indexing rules: " + invalid.getMessage(), "BAD_REQUEST",
          Map.of("field", field), false));
    }
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Indexing rules require an accepted prepared invocation");
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
    return new OperationApprovalPreview("Set indexing rules");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    var rules = policy(prepared.argumentsJson());
    return OperationExecution.finished(writer().applyIndexingRules(prepared,
        rules.schedule(), rules.pauseOnBattery(), Objects.requireNonNull(record, "accepted record")));
  }
}
