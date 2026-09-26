/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.adapters.lucene.commit.IndexFingerprint;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.indexerworker.index.IndexGenerationManager.GenerationManifest;
import io.justsearch.indexerworker.index.IndexGenerationManager.ModelArtifact;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.ort.DevModeVariantProbe;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves exact model files owned by an active generation, independent of desired settings. */
final class GenerationModelSelection {
  private final Map<String, ModelArtifact> models;
  private final String sparseModel;
  private final Integer vectorDimension;
  private final Set<String> unavailable = ConcurrentHashMap.newKeySet();

  private GenerationModelSelection(GenerationManifest manifest) {
    this.models = Map.copyOf(manifest.models());
    this.sparseModel = manifest.sparse_model();
    this.vectorDimension = manifest.vector_dimension();
  }

  private GenerationModelSelection(Map<String, ModelArtifact> models, String sparseModel,
      Integer vectorDimension) {
    this.models = Map.copyOf(models);
    this.sparseModel = sparseModel;
    this.vectorDimension = vectorDimension;
  }

  /** An empty legacy manifest has no model-selection authority. */
  static Optional<GenerationModelSelection> from(GenerationManifest manifest) {
    Objects.requireNonNull(manifest, "manifest");
    return manifest.models().isEmpty()
        ? Optional.empty() : Optional.of(new GenerationModelSelection(manifest));
  }

  static GenerationModelSelection accepted(Map<String, ModelArtifact> models, String sparseModel,
      int vectorDimension) {
    if (models == null || models.isEmpty() || sparseModel == null || vectorDimension <= 0) {
      throw new IllegalArgumentException("Accepted generation model selection is incomplete");
    }
    return new GenerationModelSelection(models, sparseModel, vectorDimension);
  }

  Optional<String> sparseModel() {
    return Optional.ofNullable(sparseModel);
  }

  Optional<Integer> vectorDimension() {
    return Optional.ofNullable(vectorDimension);
  }

  boolean includes(String packageId) {
    return models.containsKey(packageId);
  }

  IndexFingerprint.ModelFingerprint fingerprint(String packageId) {
    ModelArtifact artifact = models.get(packageId);
    return artifact == null ? IndexFingerprint.ModelFingerprint.notConfigured()
        : IndexFingerprint.ModelFingerprint.present(artifact.sha256());
  }

  Optional<String> availableFingerprint(String packageId) {
    return verify(packageId).map(VerifiedModel::sha256);
  }

  SsotCommitMetadataSource.RuntimeFingerprintInputs runtimeFingerprintInputs() {
    return new SsotCommitMetadataSource.RuntimeFingerprintInputs(
        Objects.requireNonNull(vectorDimension, "generation vector dimension"),
        fingerprint("embedding"), fingerprint("splade"), fingerprint("ner"));
  }

  boolean hasUnavailableModel() {
    return !unavailable.isEmpty();
  }

  Optional<Path> metadataDirectory(String packageId) {
    ModelArtifact artifact = models.get(packageId);
    return artifact == null ? Optional.empty() : Optional.of(Path.of(artifact.id()).getParent());
  }

  /** Exact file plus its own metadata directory; no sibling variant is substituted. */
  record VerifiedModel(Path file, Path metadataDirectory, String sha256) {}

  /**
   * Returns an empty result for an absent, unreadable, or changed selected file. The caller keeps
   * text search serving and reports the model role unavailable in that case.
   */
  Optional<VerifiedModel> verify(String packageId) {
    ModelArtifact artifact = models.get(packageId);
    if (artifact == null) return Optional.empty();
    Path file = Path.of(artifact.id());
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    try {
      if (!artifact.sha256().equals(sha256(file))) return Optional.empty();
      return Optional.of(new VerifiedModel(file, file.getParent(), artifact.sha256()));
    } catch (IOException unreadable) {
      return Optional.empty();
    }
  }

  /** Exact generation variant for the current execution policy; never consults install state. */
  Optional<VariantSelection> variant(String packageId, boolean gpuEnabled) {
    Optional<VariantSelection> selected = verify(packageId)
        .map(model -> DevModeVariantProbe.probeExact(model.file(), gpuEnabled));
    if (selected.isEmpty() && includes(packageId)) unavailable.add(packageId);
    return selected;
  }

  private static String sha256(Path file) throws IOException {
    final MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
    try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[64 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) {
        if (read > 0) digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }
}
