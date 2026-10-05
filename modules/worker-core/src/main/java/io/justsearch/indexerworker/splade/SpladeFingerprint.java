/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.util.Sha256SidecarCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and caches the SHA-256 fingerprint of the resolved SPLADE model file.
 *
 * <p>This fingerprint is recorded in Lucene commit metadata so that index/model mismatches can be
 * detected. The fingerprint is computed once per Worker boot and cached for the lifetime of the
 * process.
 *
 * <p>Follows the same pattern as {@link
 * io.justsearch.indexerworker.embed.EmbeddingFingerprint}.
 */
public final class SpladeFingerprint {
  private static final Logger log = LoggerFactory.getLogger(SpladeFingerprint.class);

  /** Commit metadata key for the SPLADE model fingerprint. */
  public static final String COMMIT_META_KEY = "splade_model_sha256";

  private static final AtomicReference<CachedResult> cachedResult = new AtomicReference<>();

  private SpladeFingerprint() {}

  /**
   * Returns the SHA-256 fingerprint of the current SPLADE model, computing it once if needed.
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
   * The resolved SPLADE model file, if one was discovered. Distinguishes "no SPLADE model is
   * configured for this deployment" (empty) from "a model file exists but its digest could not be
   * read" (present here, empty from {@link #get()}) — a distinction {@code index_fingerprint} needs,
   * because only the first is a determinate answer (tempdoc 915 §C).
   */
  public static Optional<Path> modelPath() {
    CachedResult cached = cachedResult.get();
    if (cached == null) {
      computeAndCache();
      cached = cachedResult.get();
    }
    return cached != null ? cached.modelPath() : Optional.empty();
  }

  /** Forces recomputation of the fingerprint on next access. */
  public static void invalidate() {
    cachedResult.set(null);
  }

  private static synchronized Optional<String> computeAndCache() {
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.fingerprint();
    }

    Path resolvedModelPath = ConfigStore.global().get().ai().splade().modelPath();
    String explicitPath = resolvedModelPath != null ? resolvedModelPath.toString() : null;
    SpladeModelDiscovery.Result discovery = SpladeModelDiscovery.resolve(explicitPath);
    if (discovery == null || discovery.modelDir() == null) {
      log.info("No SPLADE model found; fingerprint unavailable");
      cachedResult.set(new CachedResult(Optional.empty(), Optional.empty()));
      return Optional.empty();
    }

    // Tempdoc 374 sandbox round 4 issue H: resolve via ModelManifest so we
    // fingerprint whichever variant Install AI actually placed on disk
    // (FP32 model.onnx vs FP16 model_fp16.onnx).
    Path modelFile =
        io.justsearch.ort.ModelManifest.loadOrDefault(discovery.modelDir())
            .resolveExistingModelFile(discovery.modelDir());
    if (!Files.isRegularFile(modelFile)) {
      log.info(
          "SPLADE model file not found at {}; fingerprint unavailable", discovery.modelDir());
      // A model directory with no model file in it is a determinate "no SPLADE model here", not an
      // unanswered question: leaving modelPath empty keeps index_fingerprint computable instead of
      // silently disabling the parity check forever (tempdoc 915 §C).
      cachedResult.set(new CachedResult(Optional.empty(), Optional.empty()));
      return Optional.empty();
    }

    Optional<String> sha256 = Sha256SidecarCache.getOrCompute(modelFile);
    cachedResult.set(new CachedResult(Optional.of(modelFile), sha256));
    return sha256;
  }

  private record CachedResult(Optional<Path> modelPath, Optional<String> fingerprint) {}
}
