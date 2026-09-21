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
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.RecordedBulkPlan;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Freezes a complete rebuild before approval; the recorded owner controls its restart lifecycle. */
public final class BulkReindexHandler implements OperationHandler {
  private final RecordedIngestionService ingestion;
  private final Function<EngineContext, List<RootBinding>> roots;
  private final RecordedBulkPlan.Profile profile;
  private final Supplier<IndexingService> indexing;
  private final Supplier<List<String>> exclusions;

  public BulkReindexHandler(RecordedBulkPlan.Profile profile, RecordedIngestionService ingestion,
      Function<EngineContext, List<RootBinding>> roots,
      Supplier<IndexingService> indexing, Supplier<List<String>> exclusions) {
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.profile = Objects.requireNonNull(profile, "profile");
    this.indexing = Objects.requireNonNull(indexing, "indexing");
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
  }

  @Override public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Reindex requires an accepted prepared invocation");
  }

  @Override public OperationPreparation prepare(String argumentsJson, InvocationProvenance provenance,
      EngineContext context) {
    final String source;
    try { source = RecordedBulkPlan.sourceForArguments(profile, argumentsJson); }
    catch (IllegalArgumentException invalid) { throw badArguments(); }
    List<RootBinding> bindings = List.copyOf(roots.apply(context));
    List<String> patterns = List.copyOf(exclusions.get());
    IndexingService service = Objects.requireNonNull(indexing.get(), "Indexing service unavailable");
    String generation = captureSourceGeneration(service, context);
    var target = service.captureIndexTarget(context);
    if (!generation.equals(captureSourceGeneration(service, context))) {
      throw new IllegalStateException("Serving generation changed while preparing rebuild");
    }
    var planned = bindings.stream().map(root -> new RecordedRootPlan.Root(
        root.path(), root.collection(), true, false, patterns, List.of())).toList();
    var plan = new RecordedBulkPlan(profile, source, RecordedRootPlan.partition(generation, planned), target);
    return new OperationPreparation(argumentsJson, RecordedBulkPlan.SCHEMA, plan.toReplayPayload());
  }

  @Override public void validatePreparation(OperationPreparation prepared) {
    frozenPlan(prepared);
  }

  @Override public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var plan = frozenPlan(prepared).scope();
    String targets = plan.roots().stream().limit(6).map(root -> root.path().toString())
        .map(path -> path.length() > 256 ? path.substring(0, 253) + "..." : path)
        .collect(java.util.stream.Collectors.joining("\n"));
    return new OperationApprovalPreview("Rebuild all " + plan.roots().size() + " watched locations, including unchanged files"
        + ":\n" + targets + (plan.roots().size() > 6 ? "\nAdditional locations: " + (plan.roots().size() - 6) : "")
        + "\nThis one approved operation may continue through Engine restarts until completion or cancellation.");
  }

  @Override public OperationExecution executePrepared(OperationPreparation prepared,
      InvocationProvenance provenance, EngineContext context, OperationRecordHandle record) {
    validatePreparation(prepared);
    Objects.requireNonNull(record, "Accepted reindex record");
    return ingestion.execute(record, context);
  }

  private String captureSourceGeneration(IndexingService service, EngineContext context) {
    return profile == RecordedBulkPlan.Profile.RECOVERY_REBUILD
        ? service.captureRebuildGeneration(context) : service.captureServingGeneration(context);
  }

  private RecordedBulkPlan frozenPlan(OperationPreparation prepared) {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedBulkPlan.SCHEMA.equals(prepared.replaySchema())) {
      throw new IllegalArgumentException("Bulk reindex requires a metadata bulk plan");
    }
    var plan = RecordedBulkPlan.fromReplayPayload(prepared.replayPayloadJson());
    if (plan.profile() != profile
        || !plan.source().equals(RecordedBulkPlan.sourceForArguments(profile, prepared.argumentsJson()))) {
      throw new IllegalArgumentException("Bulk plan differs from its prepared invocation");
    }
    return plan;
  }

  private static OperationPreparationRefused badArguments() {
    return new OperationPreparationRefused(OperationResult.failure(
        "Invalid bulk rebuild arguments", "BAD_REQUEST", Map.of(), false));
  }
}
