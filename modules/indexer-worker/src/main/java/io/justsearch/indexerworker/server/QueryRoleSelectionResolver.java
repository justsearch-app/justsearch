/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.app.api.operations.CandidateIndexSelection.ModelFile;
import io.justsearch.app.api.settings.QueryRoleSelection;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.DevModeVariantProbe;
import io.justsearch.ort.ModelManifest;
import io.justsearch.ort.EncoderRole;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Captures both query roles before the settings file can select a new serving view. */
final class QueryRoleSelectionResolver {
  private static final io.justsearch.configuration.model.ModelRegistry REGISTRY =
      ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

  private QueryRoleSelectionResolver() {}

  record BootResolution(QueryRoleSelection selection, Map<EncoderRole, String> unavailableRoles) {
    BootResolution {
      unavailableRoles = Map.copyOf(unavailableRoles);
    }
  }

  static BootResolution forBoot(QueryRoleSelection committed, ResolvedConfig resolved,
      GenerationModelSelection generation) {
    boolean rerankerOverride = isOperatorOverride(resolved,
        EnvRegistry.RERANK_MODEL_PATH.configKey());
    boolean citationOverride = isOperatorOverride(resolved,
        EnvRegistry.CITATION_SCORER_MODEL_PATH.configKey());
    if (!rerankerOverride && !citationOverride) {
      return new BootResolution(enrich(committed), Map.of());
    }
    var projection = EncoderConfigurationProjection.from(resolved);
    var reranker = projection.reranker();
    var citation = projection.citation();
    Map<EncoderRole, String> unavailable = new EnumMap<>(EncoderRole.class);
    QueryRoleSelection.Role selectedReranker = captureForBoot(EncoderRole.RERANKER,
        unavailable, () -> rerankerOverride
            ? select("reranker", true, reranker.modelPath(), null, generation, false,
                reranker.gpuEnabled())
            : select("reranker", false, reranker.modelPath(),
                committed == null ? null : committed.reranker(), generation,
                reranker.isReady(), reranker.gpuEnabled()));
    QueryRoleSelection.Role selectedCitation = captureForBoot(EncoderRole.CITATION,
        unavailable, () -> citationOverride
            ? select("citation-scorer", true, citation == null ? null : citation.modelPath(),
                null, generation, false,
                false)
            : select("citation-scorer", false, citation == null ? null : citation.modelPath(),
                committed == null ? null : committed.citation(), generation,
                citation != null && citation.isReady(), false));
    return new BootResolution(new QueryRoleSelection(selectedReranker, selectedCitation),
        unavailable);
  }

  private static QueryRoleSelection.Role captureForBoot(EncoderRole role,
      Map<EncoderRole, String> unavailable, BootCapture capture) {
    try {
      return capture.select();
    } catch (IOException | IllegalArgumentException failure) {
      unavailable.put(role, failure.toString());
      // This placeholder suppresses legacy discovery. BootResolution separately marks the role
      // unavailable, so a failed override is never mistaken for an intentional disablement.
      return QueryRoleSelection.Role.disabled();
    }
  }

  @FunctionalInterface
  private interface BootCapture {
    QueryRoleSelection.Role select() throws IOException;
  }

  private static boolean isOperatorOverride(ResolvedConfig resolved, String key) {
    var source = resolved.resolution(key);
    return source != null && source.isResolved()
        && source.sourceOrdinal() >= ResolvedConfigBuilder.ORDINAL_ENV_VAR;
  }

  static QueryRoleSelection.Role select(String packageId, boolean pathChanged,
      Path desiredDirectory, QueryRoleSelection.Role prior, GenerationModelSelection generation,
      boolean currentlyWired, boolean gpuEnabled) throws IOException {
    if (pathChanged) {
      if (desiredDirectory == null) return QueryRoleSelection.Role.disabled();
      var variant = DevModeVariantProbe.probe(desiredDirectory, gpuEnabled);
      if (variant == null) throw new IOException("Query model path has no selected ONNX: "
          + packageId);
      return capture(packageId, variant.modelFile(), gpuEnabled);
    }
    if (prior != null) {
      return enrich(packageId, prior);
    }
    if (generation != null && generation.includes(packageId)) {
      Path exact = generation.verify(packageId)
          .orElseThrow(() -> new IOException("Active query model bytes changed: " + packageId))
          .file();
      return capture(packageId, exact, gpuEnabled);
    }
    if (!currentlyWired) return QueryRoleSelection.Role.disabled();
    if (desiredDirectory == null) {
      throw new IOException("Active legacy query role has no model directory: " + packageId);
    }
    var variant = DevModeVariantProbe.probe(desiredDirectory, gpuEnabled);
    if (variant == null) throw new IOException("Active legacy query model is unavailable: "
        + packageId);
    return capture(packageId, variant.modelFile(), gpuEnabled);
  }

  static QueryRoleSelection enrich(QueryRoleSelection selection) {
    if (selection == null) return null;
    return new QueryRoleSelection(enrich("reranker", selection.reranker()),
        enrich("citation-scorer", selection.citation()));
  }

  private static QueryRoleSelection.Role enrich(String packageId, QueryRoleSelection.Role prior) {
    if (prior.state() == QueryRoleSelection.State.DISABLED || prior.precision() != null) {
      return prior;
    }
    ModelVariant descriptor = registered(packageId, prior.model());
    return descriptor == null ? prior : QueryRoleSelection.Role.selected(prior.variantId(),
        prior.model(), prior.tokenizer(), descriptor.precision(), descriptor.targetEP());
  }

  private static QueryRoleSelection.Role capture(String packageId, Path modelPath,
      boolean gpuEnabled) throws IOException {
    ModelFile model = GenerationModelSelection.captureIdentity(modelPath);
    ModelFile tokenizer = GenerationModelSelection.captureIdentity(
        Objects.requireNonNull(model.path().getParent(), "model directory")
            .resolve("tokenizer.json"));
    ModelVariant registered = registered(packageId, model);
    if (registered != null) {
      return QueryRoleSelection.Role.selected(model.path().getFileName().toString(), model,
          tokenizer, registered.precision(), registered.targetEP());
    }
    var variant = DevModeVariantProbe.probeExact(model.path(), gpuEnabled);
    if (variant == null) throw new IOException("Query model is not declared by its manifest: "
        + model.path());
    ModelManifest manifest = ModelManifest.loadOrDefault(model.path().getParent());
    Path cpu = manifest.resolveModelPath(model.path().getParent(), false).normalize();
    Path gpu = manifest.resolveModelPath(model.path().getParent(), true).normalize();
    ExecutionProvider targetEp = !cpu.equals(gpu) && model.path().equals(gpu)
        ? ExecutionProvider.CUDA : ExecutionProvider.CPU;
    return QueryRoleSelection.Role.selected(model.path().getFileName().toString(), model,
        tokenizer, variant.precision(), targetEp);
  }

  private static ModelVariant registered(String packageId, ModelFile file) {
    var modelPackage = REGISTRY.findPackage(packageId);
    if (modelPackage == null) return null;
    return modelPackage.variants().stream()
        .filter(variant -> variant.filename().equals(file.path().getFileName().toString())
            && variant.sha256().equalsIgnoreCase(file.sha256())
            && variant.sizeBytes() == file.sizeBytes())
        .findFirst().orElse(null);
  }
}
