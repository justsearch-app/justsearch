/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationApprovalPreview;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationExecution;
import io.justsearch.agent.api.registry.OperationRecordHandle;
import io.justsearch.agent.api.registry.OperationPreparation;
import io.justsearch.agent.api.registry.OperationPreparationRefused;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.operations.ForgetService;
import io.justsearch.app.api.operations.ForgetPlan;
import io.justsearch.app.api.operations.OperationKeys;
import io.justsearch.core.context.EngineContext;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;

/** Explicit bounded retry or withdrawal of one original intent; caller arguments cannot retarget it. */
public final class ForgetDecisionHandler implements OperationHandler {
  private static final String SCHEMA = "forget-decision.v1";
  private static final ObjectReader JSON = HandlerJson.MAPPER.reader()
      .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private record Target(String forgetKey, String path, io.justsearch.configuration.excludes.LiteralExclusion.Scope scope) {
    Target {
      OperationKeys.timestampMillis(forgetKey);
      var normalized = new ForgetPlan.Request(path, scope);
      if (!normalized.path().equals(path)) throw new IllegalArgumentException("Invalid saved forget target");
    }
  }
  public enum Decision { RETRY, WITHDRAW }
  private final Supplier<ForgetService> owner;
  private final Decision decision;

  public ForgetDecisionHandler(Supplier<ForgetService> owner, Decision decision) {
    this.owner = Objects.requireNonNull(owner, "owner");
    this.decision = Objects.requireNonNull(decision, "decision");
  }

  private static String key(String argumentsJson) {
    var node = JSON.readTree(argumentsJson);
    if (node == null || !node.isObject() || node.size() != 1 || !node.path("forgetKey").isString()) {
      throw new IllegalArgumentException("Exactly one forget key is required");
    }
    String key = node.path("forgetKey").asString();
    OperationKeys.timestampMillis(key);
    return key;
  }

  @Override public OperationPreparation prepare(String argumentsJson,
      InvocationProvenance provenance, EngineContext context) {
    ForgetIndexedPathHandler.requireWebview(context);
    try {
      String key = key(argumentsJson);
      var actual = owner.get().observe(key, context).orElseThrow();
      if (actual.path() == null || actual.scope() == null) throw new IllegalStateException("Forget target is unknown");
      var prepared = new OperationPreparation(argumentsJson, SCHEMA,
          HandlerJson.MAPPER.writeValueAsString(new Target(key, actual.path(), actual.scope())));
      approvalPreview(prepared);
      return prepared;
    }
    catch (RuntimeException invalid) {
      throw new OperationPreparationRefused(OperationResult.failure(
          "The original forget target could not be established for this decision", "FORGET_DECISION_UNAVAILABLE", Map.of(), true));
    }
  }

  private static Target target(OperationPreparation prepared) {
    if (!SCHEMA.equals(prepared.replaySchema()) || prepared.content() != OperationPreparation.Content.METADATA) {
      throw new IllegalArgumentException("Forget decision requires its frozen exact target");
    }
    var node = JSON.readTree(prepared.replayPayloadJson());
    if (node == null || !node.isObject() || node.size() != 3 || !node.path("forgetKey").isString()
        || !node.path("path").isString() || !node.path("scope").isString()) {
      throw new IllegalArgumentException("Exactly one frozen forget target is required");
    }
    var target = new Target(node.path("forgetKey").asString(), node.path("path").asString(),
        io.justsearch.configuration.excludes.LiteralExclusion.Scope.valueOf(node.path("scope").asString()));
    if (!key(prepared.argumentsJson()).equals(target.forgetKey())) throw new IllegalArgumentException("Forget decision changed");
    return target;
  }

  @Override public void validatePreparation(OperationPreparation prepared) { target(prepared); }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var target = target(prepared);
    String exact = ForgetIndexedPathHandler.exactPath(target.path());
    return new OperationApprovalPreview(decision == Decision.RETRY
        ? "Retry this original unfinished forget for " + exact + " using its saved exact target. Original files and retained answers, logs and backups are not erased."
        : "Withdraw this unfinished forget for " + exact + " and release only its own keep-out. Prior index removal may already have happened; no indexed rows or original files will be restored.");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Forget decisions require their frozen exact target");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    ForgetIndexedPathHandler.requireWebview(context);
    Objects.requireNonNull(record, "Accepted forget decision");
    var target = target(prepared);
    return OperationExecution.finished(decision == Decision.RETRY ? owner.get().retry(target.forgetKey(), context)
        : owner.get().withdraw(target.forgetKey(), context));
  }
}
