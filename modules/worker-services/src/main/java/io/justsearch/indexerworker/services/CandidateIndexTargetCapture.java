/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.app.api.operations.IndexTargetSnapshot;
import io.justsearch.configuration.resolved.OnnxModelDiscovery;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.embed.EmbeddingConfig;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.ort.ModelManifest;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.RerankerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Captures the physical index target selected by one candidate configuration snapshot. */
public final class CandidateIndexTargetCapture {
  private static final List<String> SPLADE_REQUIRED_FILES =
      List.of("model.onnx", "tokenizer.json", "vocab.txt");

  private CandidateIndexTargetCapture() {}

  /**
   * Resolves and hashes candidate model files without consulting process-wide fingerprint
   * providers.
   *
   * @throws IOException if SSOT inputs or a selected model file cannot be read
   */
  public static IndexTargetSnapshot capture(ResolvedConfig candidate) throws IOException {
    return captureWithRuntimeInputs(candidate).target();
  }

  /**
   * Resolves one candidate's physical target and the runtime-owned inputs used to derive it.
   *
   * <p>The model files are read once here. The returned runtime inputs are the exact values that
   * were supplied to {@link SsotCommitMetadataSource}; callers preparing a candidate runtime can
   * therefore reuse them without consulting the serving runtime's process-wide providers.
   *
   * @throws IOException if SSOT inputs or a selected model file cannot be read
   */
  public static CaptureResult captureWithRuntimeInputs(ResolvedConfig candidate) throws IOException {
    Objects.requireNonNull(candidate, "candidate");

    CapturedModel embedding = embeddingFingerprint(candidate);
    CapturedModel splade = spladeFingerprint(candidate);
    CapturedModel ner = nerFingerprint(candidate);
    SsotCommitMetadataSource.RuntimeFingerprintInputs runtimeInputs =
        new SsotCommitMetadataSource.RuntimeFingerprintInputs(
            effectiveVectorDimension(candidate),
            embedding.fingerprint(), splade.fingerprint(), ner.fingerprint());
    IndexFingerprint.Inputs inputs =
        new SsotCommitMetadataSource(candidate, runtimeInputs)
            .fingerprintInputs(
                candidate,
                runtimeInputs.effectiveVectorDimension(),
                runtimeInputs.embeddingModel(),
                runtimeInputs.spladeModel(),
                runtimeInputs.nerModel());
    byte[] canonicalInputs = IndexFingerprint.canonicalJson(inputs);
    String fingerprint =
        IndexFingerprint.compute(inputs)
            .orElseThrow(
                () -> new IllegalStateException("Candidate index target is indeterminate"));
    Map<String, ModelArtifact> selectedModels = new LinkedHashMap<>();
    addSelectedModel(selectedModels, "embedding", embedding);
    addSelectedModel(selectedModels, "splade", splade);
    addSelectedModel(selectedModels, "ner", ner);
    RerankerConfig reranker = RerankerConfig.from(candidate);
    if (reranker.isReady()) {
      addSelectedModel(selectedModels, "reranker",
          fingerprintSelectedModel(reranker.modelPath(), "reranker"));
    }
    CitationScorerConfig citation = CitationScorerConfig.from(candidate);
    if (citation.isReady()) {
      addSelectedModel(selectedModels, "citation-scorer",
          fingerprintSelectedModel(citation.modelPath(), "citation scorer"));
    }
    return new CaptureResult(
        new IndexTargetSnapshot(fingerprint, new String(canonicalInputs, StandardCharsets.UTF_8)),
        runtimeInputs, selectedModels);
  }

  private static void addSelectedModel(Map<String, ModelArtifact> selectedModels, String role,
      CapturedModel model) {
    if (model.file() != null) {
      selectedModels.put(role, new ModelArtifact(
          model.file().toAbsolutePath().normalize().toString(), model.fingerprint().sha()));
    }
  }

  private record CapturedModel(IndexFingerprint.ModelFingerprint fingerprint, Path file) {}

  /** Candidate target plus the exact index and query model files selected for its runtime. */
  public record CaptureResult(
      IndexTargetSnapshot target,
      SsotCommitMetadataSource.RuntimeFingerprintInputs runtimeFingerprintInputs,
      Map<String, ModelArtifact> selectedModels) {
    public CaptureResult {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(runtimeFingerprintInputs, "runtimeFingerprintInputs");
      selectedModels = Map.copyOf(selectedModels);
    }
  }

  private static Integer effectiveVectorDimension(ResolvedConfig candidate) {
    return "bge-m3".equalsIgnoreCase(candidate.ai().sparseModel()) ? 1024 : null;
  }

  private static CapturedModel embeddingFingerprint(ResolvedConfig candidate)
      throws IOException {
    EmbeddingConfig embedding = EmbeddingConfig.from(candidate);
    if (!"auto".equalsIgnoreCase(embedding.backend())
        && !"onnx".equalsIgnoreCase(embedding.backend())) {
      return new CapturedModel(IndexFingerprint.ModelFingerprint.notConfigured(), null);
    }
    return fingerprintSelectedModel(embedding.modelPath(), "embedding");
  }

  private static CapturedModel spladeFingerprint(ResolvedConfig candidate)
      throws IOException {
    Path configured = candidate.ai().splade().modelPath();
    OnnxModelDiscovery.Result discovery =
        OnnxModelDiscovery.resolve(
            candidate,
            configured == null ? null : configured.toString(),
            "splade",
            "splade/naver-splade-v3",
            SPLADE_REQUIRED_FILES,
            true);
    return fingerprintSelectedModel(discovery == null ? null : discovery.modelDir(), "SPLADE");
  }

  private static CapturedModel nerFingerprint(ResolvedConfig candidate)
      throws IOException {
    Path configured = candidate.ai().ner().modelPath();
    OnnxModelDiscovery.Result discovery =
        OnnxModelDiscovery.resolve(
            candidate,
            configured == null ? null : configured.toString(),
            "ner",
            "ner/distilbert-multilingual-ner-hrl");
    return fingerprintSelectedModel(discovery == null ? null : discovery.modelDir(), "NER");
  }

  private static CapturedModel fingerprintSelectedModel(
      Path modelDir, String role) throws IOException {
    if (modelDir == null) {
      return new CapturedModel(IndexFingerprint.ModelFingerprint.notConfigured(), null);
    }
    Path modelFile = ModelManifest.loadOrDefault(modelDir).resolveExistingModelFile(modelDir);
    if (!Files.isRegularFile(modelFile)) {
      throw new IOException(role + " model file is missing: " + modelFile);
    }
    return new CapturedModel(IndexFingerprint.ModelFingerprint.present(sha256(modelFile)),
        modelFile);
  }

  private static String sha256(Path file) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    try (InputStream input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
