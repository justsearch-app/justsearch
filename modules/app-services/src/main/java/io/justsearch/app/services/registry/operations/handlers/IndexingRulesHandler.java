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
import io.justsearch.app.api.settings.SettingsWitness;
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

  private record Rules(IndexingPausePolicy policy, SettingsWitness basedOn) {}

  /**
   * The rule pair, and optionally the settings revision the caller's draft was based on. A based-on
   * revision makes the write conditional on it (a window's draft never overwrites a newer save);
   * without one the revision is taken when the write is prepared, as before.
   */
  private Rules rules(String arguments) {
    try {
      var root = JSON.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
      if (root == null || !root.isObject()) throw new IllegalArgumentException("schedule and pauseOnBattery require an object");
      if (!root.has("schedule")) throw new IllegalArgumentException("schedule is required");
      if (!root.has("pauseOnBattery")) throw new IllegalArgumentException("pauseOnBattery is required");
      if (root.size() != (root.has("witness") ? 3 : 2)) {
        throw new IllegalArgumentException("schedule, pauseOnBattery and witness are the only accepted fields");
      }
      SettingsWitness basedOn = root.has("witness") ? witness(root.get("witness")) : null;
      var rules = root.deepCopy();
      ((tools.jackson.databind.node.ObjectNode) rules).remove("witness");
      IndexingPausePolicy.validateRules(rules);
      return new Rules(new IndexingPausePolicy(false, null, null,
          IndexingPausePolicy.scheduleFromJson(root.get("schedule")), root.get("pauseOnBattery").booleanValue()), basedOn);
    } catch (RuntimeException invalid) {
      String message = invalid.getMessage();
      String field = message != null && message.startsWith("pauseOnBattery") ? "pauseOnBattery"
          : message != null && message.startsWith("witness") ? "witness"
          : message != null && message.startsWith("schedule.") ? message.split(" ", 2)[0] : "schedule";
      throw new OperationPreparationRefused(OperationResult.failure(
          "Invalid indexing rules: " + invalid.getMessage(), "BAD_REQUEST",
          Map.of("field", field), false));
    }
  }

  private static SettingsWitness witness(tools.jackson.databind.JsonNode node) {
    if (node == null || !node.isObject() || node.size() != 2
        || !node.has("acceptedRevision") || !node.has("lastCommittedOperationKey")) {
      throw new IllegalArgumentException("witness requires acceptedRevision and lastCommittedOperationKey only");
    }
    var revision = node.get("acceptedRevision");
    var key = node.get("lastCommittedOperationKey");
    if (!revision.isIntegralNumber() || !revision.canConvertToLong() || revision.longValue() < 0
        || revision.longValue() == Long.MAX_VALUE || !(key.isNull() || key.isTextual())) {
      throw new IllegalArgumentException("witness has an invalid revision");
    }
    return new SettingsWitness(revision.longValue(), key.isNull() ? null : key.textValue());
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Indexing rules require an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    var rules = rules(argumentsJson);
    return rules.basedOn() == null ? writer().prepareIntent(argumentsJson)
        : writer().prepareIntent(argumentsJson, rules.basedOn());
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    var frozen = RuntimeIntentPreparation.validate(prepared);
    var rules = rules(prepared.argumentsJson());
    if (rules.basedOn() != null && !rules.basedOn().equals(frozen)) {
      throw new IllegalArgumentException("Prepared revision differs from the draft's revision");
    }
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    validatePreparation(prepared);
    return new OperationApprovalPreview("Set indexing rules");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    var rules = rules(prepared.argumentsJson()).policy();
    return OperationExecution.finished(writer().applyIndexingRules(prepared,
        rules.schedule(), rules.pauseOnBattery(), Objects.requireNonNull(record, "accepted record")));
  }
}
