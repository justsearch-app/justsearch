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
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.operations.CandidateIndexSelection;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy.RootBinding;
import io.justsearch.app.api.operations.RecordedIngestionService;
import io.justsearch.app.api.operations.RecordedInstallerGenerationPlan;
import io.justsearch.app.api.operations.RecordedRootPlan;
import io.justsearch.app.api.status.MigrationSource;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.services.registry.executor.RecordedInstallerAssetVerifier;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.core.context.EngineContext;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Prepares the separately approved activation of an installed generation candidate.
 *
 * <p>Acquisition owns the staged files. This handler only freezes the candidate, the settings
 * witness, the current serving generation and the Worker-owned target before the durable recorded
 * ingestion owner accepts the operation. In particular, it never writes settings or starts a
 * rebuild during preparation.
 */
public final class ActivateInstalledModelsHandler implements OperationHandler {
  private final Supplier<BrainInstallService> install;
  private final RecordedIngestionService ingestion;
  private final Function<EngineContext, List<RootBinding>> roots;
  private final Supplier<IndexingService> indexing;
  private final Supplier<List<String>> exclusions;

  public ActivateInstalledModelsHandler(
      Supplier<BrainInstallService> install,
      RecordedIngestionService ingestion,
      Function<EngineContext, List<RootBinding>> roots,
      Supplier<IndexingService> indexing,
      Supplier<List<String>> exclusions) {
    this.install = Objects.requireNonNull(install, "install");
    this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
    this.roots = Objects.requireNonNull(roots, "roots");
    this.indexing = Objects.requireNonNull(indexing, "indexing");
    this.exclusions = Objects.requireNonNull(exclusions, "exclusions");
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext context) {
    throw new IllegalStateException("Installed-model activation requires an accepted prepared invocation");
  }

  @Override
  public OperationPreparation prepare(
      String argumentsJson, InvocationProvenance provenance, EngineContext context) {
    requireSourceArguments(argumentsJson);
    BrainInstallService service = service();
    var candidate = service.prepareInstalledGenerationCandidate().orElseThrow(
        () -> refused("No retained installed generation candidate is available",
            "ACTIVATION_CANDIDATE_UNAVAILABLE"));
    if (candidate.generationBoundKeys().isEmpty()) {
      // Query-only and effective-no-op installs remain ordinary settings changes. Refusing here
      // prevents the durable REINDEX operation from becoming a false generation build.
      throw refused("Installed candidate has no generation-bound changes; use the ordinary settings owner",
          "GENERATION_REINDEX_NOT_REQUIRED");
    }

    IndexingService worker = Objects.requireNonNull(indexing.get(), "Indexing service unavailable");
    List<RootBinding> bindings = List.copyOf(Objects.requireNonNull(roots.apply(context), "root bindings"));
    List<String> patterns = List.copyOf(Objects.requireNonNull(exclusions.get(), "exclude patterns"));
    UiSettings settings = candidate.candidateSettings();
    ResolvedConfig resolved = ConfigStoreRebuilder.prepare(settings);

    // Bind the candidate to the serving generation around the Worker target capture. A target
    // from the newly resolved model paths must never be paired with a different serving source.
    String sourceGeneration = worker.captureServingGeneration(context);
    CandidateIndexSelection selected = worker.captureCandidateIndexSelection(resolved, context);
    var target = selected.target();
    if (!sourceGeneration.equals(worker.captureServingGeneration(context))) {
      throw new IllegalStateException("Serving generation changed while preparing installed-model activation");
    }

    List<RecordedInstallerGenerationPlan.ModelIdentity> models =
        new ArrayList<>(candidate.models());
    List<RecordedInstallerGenerationPlan.AssetIdentity> assets =
        new ArrayList<>(candidate.assets());
    Map<String, RecordedInstallerGenerationPlan.ModelIdentity> byPackage = new HashMap<>();
    for (var model : models) byPackage.put(model.packageId(), model);
    var retainedProvenance = new RecordedInstallerGenerationPlan.AcquisitionProvenance(
        RecordedInstallerGenerationPlan.AcquisitionProvenance.Kind.CANDIDATE_CAPTURE,
        sourceGeneration, target.fingerprint());
    for (var entry : selected.models().entrySet()) {
      var file = entry.getValue();
      var existing = byPackage.get(entry.getKey());
      if (existing != null) {
        if (!existing.path().equals(file.path()) || !existing.sha256().equals(file.sha256())
            || existing.sizeBytes() != file.sizeBytes()) {
          throw refused("Installed model " + entry.getKey() + " differs from the selected target",
              "ACTIVATION_MODEL_TARGET_CONFLICT");
        }
      } else {
        models.add(new RecordedInstallerGenerationPlan.ModelIdentity(entry.getKey(),
            file.path().getFileName().toString(), file.path(), file.sha256(),
            file.sizeBytes(), retainedProvenance));
        try {
          assets.addAll(RecordedInstallerAssetVerifier.captureSupportingAssets(
              entry.getKey(), file.path(), retainedProvenance));
        } catch (IllegalArgumentException invalid) {
          throw refused("Retained model assets are unavailable: " + entry.getKey(),
              "ACTIVATION_ASSET_INVALID");
        }
      }
    }

    var planned = bindings.stream().map(root -> new RecordedRootPlan.Root(
        root.path(), root.collection(), true, false, patterns, List.of())).toList();
    var scope = RecordedRootPlan.partition(sourceGeneration, planned);
    var plan = new RecordedInstallerGenerationPlan(
        sourceGeneration,
        scope,
        target,
        candidate.settingsWitness(),
        candidate.encodedSettings(),
        models,
        assets,
        candidate.provenance());
    return new OperationPreparation(argumentsJson, RecordedInstallerGenerationPlan.SCHEMA,
        plan.toReplayPayload());
  }

  @Override
  public void validatePreparation(OperationPreparation prepared) {
    frozenPlan(prepared);
  }

  @Override
  public OperationApprovalPreview approvalPreview(OperationPreparation prepared) {
    var plan = frozenPlan(prepared);
    String targets = plan.scope().roots().stream().limit(6)
        .map(root -> root.path().toString())
        .map(path -> path.length() > 256 ? path.substring(0, 253) + "..." : path)
        .collect(java.util.stream.Collectors.joining("\n"));
    return new OperationApprovalPreview("Activate the downloaded AI models and rebuild "
        + plan.scope().roots().size() + " watched locations:\n" + targets
        + (plan.scope().roots().size() > 6
            ? "\nAdditional locations: " + (plan.scope().roots().size() - 6) : "")
        + "\nThis approved activation may continue through Engine restarts until completion or cancellation.");
  }

  @Override
  public OperationExecution executePrepared(
      OperationPreparation prepared,
      InvocationProvenance provenance,
      EngineContext context,
      OperationRecordHandle record) {
    var plan = frozenPlan(prepared);
    Objects.requireNonNull(record, "Accepted activation record");
    if (plan.operationKey() != null && !record.key().equals(plan.operationKey())) {
      throw new IllegalArgumentException("Installed activation operation identity mismatch");
    }
    final RecordedInstallerGenerationPlan current;
    try {
      current = frozenPlan(prepare(prepared.argumentsJson(), provenance, context));
    } catch (OperationPreparationRefused stale) {
      return OperationExecution.finished(stale.refusal());
    }
    // The approval names one frozen candidate. A new download, settings revision, roots or
    // Worker target requires a new preview before this accepted attempt may start effects.
    if (!plan.equals(new RecordedInstallerGenerationPlan(
        current.operationId(), current.profile(), current.source(), plan.operationKey(),
        current.sourceGeneration(), current.scope(), current.target(),
        current.settingsWitness(), current.candidateSettings(), current.models(),
        current.assets(), current.acquisition()))) {
      return OperationExecution.finished(OperationResult.failure(
          "Installed activation candidate changed; request a fresh preview",
          "ACTIVATION_PREVIEW_STALE", Map.of(), false));
    }
    return ingestion.execute(record, context);
  }

  private BrainInstallService service() {
    BrainInstallService service = install.get();
    if (service == null) {
      throw refused("Brain install service unavailable", "ACTIVATION_SERVICE_UNAVAILABLE");
    }
    return service;
  }

  private static RecordedInstallerGenerationPlan frozenPlan(OperationPreparation prepared) {
    Objects.requireNonNull(prepared, "prepared");
    if (prepared.content() != OperationPreparation.Content.METADATA
        || !RecordedInstallerGenerationPlan.SCHEMA.equals(prepared.replaySchema())) {
      throw new IllegalArgumentException("Installed activation requires a metadata generation plan");
    }
    requireSourceArguments(prepared.argumentsJson());
    return RecordedInstallerGenerationPlan.fromReplayPayload(prepared.replaySchema(),
        prepared.replayPayloadJson());
  }

  private static void requireSourceArguments(String argumentsJson) {
    final Object decoded;
    try {
      decoded = HandlerJson.MAPPER.readValue(argumentsJson, Object.class);
    } catch (RuntimeException malformed) {
      throw refused("Invalid installer activation arguments", "BAD_REQUEST");
    }
    if (!(decoded instanceof Map<?, ?> fields)
        || fields.size() != 1
        || !(fields.get("source") instanceof String source)
        || !MigrationSource.INSTALLER_MODEL_ACTIVATION.wire().equals(source)) {
      throw refused("Installer activation requires the installer model activation source",
          "BAD_REQUEST");
    }
  }

  private static OperationPreparationRefused refused(String message, String code) {
    return new OperationPreparationRefused(OperationResult.failure(
        message, code, Map.of(), false));
  }
}
