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
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Freezes watched-root incremental reindex before acceptance; the recorded owner runs effects. */
public final class ReindexHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;
  private final Function<EngineContext, List<RootBinding>> roots;
  private final Function<EngineContext, String> generation;
  private final Supplier<List<String>> exclusions;

  public ReindexHandler(RecordedIngestionService ingestion,
      Function<EngineContext, List<RootBinding>> roots,
      Function<EngineContext, String> generation, Supplier<List<String>> exclusions) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.generation = Objects.requireNonNull(generation, "generation");
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Reindex requires an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    boolean force = forceOf(argumentsJson);
    List<RootBinding> bindings = List.copyOf(roots.apply(context));
    List<String> patterns = List.copyOf(exclusions.get());
    String target = generation.apply(context);
    var planned = bindings.stream().map(root -> new RecordedRootPlan.Root(
        root.path(), root.collection(), force, false, patterns, List.of())).toList();
    var plan = RecordedRootPlan.partition(target, planned);
    return new OperationPreparation(argumentsJson, RecordedRootPlan.SCHEMA, plan.toReplayPayload());
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    frozenPlan(prepared);
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var plan = frozenPlan(prepared);
    String targets = plan.roots().stream().limit(6).map(root -> root.path().toString())
        .map(path -> path.length() > 256 ? path.substring(0, 253) + "..." : path)
        .collect(java.util.stream.Collectors.joining("\n"));
    return new OperationApprovalPreview("Reindex " + plan.roots().size() + " watched locations"
        + (forceOf(prepared.argumentsJson()) ? ", including unchanged files" : "")
        + ":\n" + targets + (plan.roots().size() > 6 ? "\nAdditional locations: " + (plan.roots().size() - 6) : ""));
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    Objects.requireNonNull(record, "Accepted reindex record");
    return ingestion.execute(record, context);
  }

  private static RecordedRootPlan frozenPlan(OperationPreparation prepared) {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedRootPlan.SCHEMA.equals(prepared.replaySchema())) {
      throw new IllegalArgumentException("Reindex requires a metadata root plan");
    }
    boolean force = forceOf(prepared.argumentsJson());
    var plan = RecordedRootPlan.fromReplayPayload(prepared.replayPayloadJson());
    if (plan.roots().stream().anyMatch(root -> root.singleFile() || root.force() != force)) {
      throw new IllegalArgumentException("Reindex root policy differs from its prepared invocation");
    }
    return plan;
  }

  private static boolean forceOf(String argumentsJson) {
    final tools.jackson.databind.JsonNode args;
    try {
      args = HandlerJson.MAPPER.readTree(argumentsJson);
    } catch (RuntimeException invalid) {
      throw badArguments();
    }
    if (args == null || !args.isObject()) throw badArguments();
    var force = args.get("force");
    if (force != null && !force.isBoolean()) throw badArguments();
    return force != null && force.asBoolean();
  }

  private static OperationPreparationRefused badArguments() {
    return new OperationPreparationRefused(OperationResult.failure(
        "Reindex arguments must be an object with an optional boolean force",
        "BAD_REQUEST", Map.of(), false));
  }
}
