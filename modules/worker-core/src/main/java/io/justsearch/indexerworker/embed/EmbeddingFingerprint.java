/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.ort.ModelManifest;
import io.justsearch.indexerworker.util.Sha256SidecarCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and caches the SHA-256 fingerprint of the resolved embedding model file.
 *
 * <p>This fingerprint is used to detect embedding model changes that would make existing
 * vectors incompatible with new queries. The fingerprint is computed once per Worker boot
 * and cached for the lifetime of the process.
 *
 * <p>Discovery rules follow the embedding composition path in
 * {@code InferenceCompositionRoot.compose} (tempdoc 397 §7.6): {@link EmbeddingConfig#fromEnv()}
 * resolves the configured model path, and the same path is fingerprinted here for vector-
 * compatibility checks.
 */
public final class EmbeddingFingerprint {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingFingerprint.class);

  private static final AtomicReference<CachedResult> cachedResult = new AtomicReference<>();

  private EmbeddingFingerprint() {}

  /**
   * Returns the SHA-256 fingerprint of the current embedding model, computing it once if needed.
   *
   * @return the fingerprint as a 64-character hex string, or empty if no model is available
   */
  public static Optional<String> get() {
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.fingerprint();
    }
    return computeAndCache();
  }

  /**
   * Returns the resolved embedding model path, if any.
   *
   * @return the model path, or empty if no model is available
   */
  public static Optional<Path> modelPath() {
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.modelPath();
    }
    computeAndCache();
    cached = cachedResult.get();
    return cached != null ? cached.modelPath() : Optional.empty();
  }

  /**
   * Forces recomputation of the fingerprint on next access.
   *
   * <p>Intended for use after embedding model path changes (e.g., via Worker restart).
   */
  public static void invalidate() {
    cachedResult.set(null);
  }

  /**
   * Injects a fake fingerprint for testing (or {@code null} to force "no live fingerprint" — the
   * model-released case). Public so tests in other modules that depend on the shared, process-wide
   * static cache (e.g. {@code worker-services}' {@code EmbeddingProviderLifecycleTest}, which
   * gates on the live value per tempdoc 730 review item 3) can control it deterministically. Call
   * {@link #invalidate()} in {@code @AfterEach} to clean up.
   */
  public static void setForTesting(String fingerprint) {
    cachedResult.set(new CachedResult(Optional.empty(), Optional.ofNullable(fingerprint)));
  }

  private static synchronized Optional<String> computeAndCache() {
    // Double-check after acquiring lock
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.fingerprint();
    }

    Path modelPath = discoverModelPath();
    if (modelPath == null) {
      log.info("No embedding model found; fingerprint unavailable");
      cachedResult.set(new CachedResult(Optional.empty(), Optional.empty()));
      return Optional.empty();
    }

    Optional<String> sha256 = Sha256SidecarCache.getOrCompute(modelPath);
    cachedResult.set(new CachedResult(Optional.of(modelPath), sha256));
    return sha256;
  }

  private static Path discoverModelPath() {
    EmbeddingConfig config = EmbeddingConfig.fromEnv();
    String embedBackend = config.backend();

    if (!"auto".equalsIgnoreCase(embedBackend) && !"onnx".equalsIgnoreCase(embedBackend)) {
      return null;
    }

    // Tempdoc 374 alpha.20 Bug L: reuse EmbeddingConfig's already-resolved
    // modelPath instead of re-discovering via EmbeddingOnnxModelDiscovery.resolve(null).
    // Pre-alpha.20 this called resolve(null) which reads EnvRegistry.X.get() (sysprop OR
    // env var only) — diverging from EmbeddingConfig.from(config) which correctly
    // consults the resolved config. On cold restart the sysprop isn't set (Install AI's
    // applyOnnxSettings only runs during install), and the JUSTSEARCH_MODELS_DIR env var
    // doesn't inherit across GUI launches. Auto-discovery then failed because
    // paths.modelsDir() was null. (Pre-A19 the resolved config reached the index half as a
    // snapshot at ordinal 450; that tier is deleted — one JVM, one ResolvedConfig — which
    // changes how the value arrives, not the defect this comment records.)
    // Round-10 evidence: same JVM, 3ms apart — `EmbeddingService created … modelPath=…`
    // followed by `No embedding model found`. They disagreed because they used
    // different config-source priorities. EmbeddingConfig.from already resolves through
    // the snapshot; we mirror its result here.
    //
    // The alpha.16 Bug C fix (use ModelManifest.resolveExistingModelFile so fingerprint
    // computes against whichever variant Install AI placed on disk — FP16 vs FP32) is
    // preserved below.
    Path modelDir = config.modelPath();
    if (modelDir == null) {
      return null;
    }
    ModelManifest manifest = ModelManifest.loadOrDefault(modelDir);
    Path onnxModel = manifest.resolveExistingModelFile(modelDir);
    return Files.isRegularFile(onnxModel) ? onnxModel : null;
  }

  private record CachedResult(Optional<Path> modelPath, Optional<String> fingerprint) {}
}
