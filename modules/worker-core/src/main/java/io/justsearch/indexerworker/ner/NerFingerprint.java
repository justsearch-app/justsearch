/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.util.Sha256SidecarCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Computes and caches the SHA-256 fingerprint of the resolved NER ONNX model file.
 *
 * <p>The NER model's output lands on disk: {@code entity_persons_raw},
 * {@code entity_organizations_raw} and {@code entity_locations_raw} are {@code stored} +
 * {@code docValues} fields written from it by {@code NerBackfillOps}. Swapping the model therefore
 * changes index content with no other descriptor moving, which is exactly what
 * {@code index_fingerprint} exists to catch (tempdoc 915 §C).
 *
 * <p>Same shape as {@link io.justsearch.indexerworker.splade.SpladeFingerprint} and
 * {@link io.justsearch.indexerworker.embed.EmbeddingFingerprint}: a lazy, once-per-boot file digest
 * with a tri-state answer. Lazy matters — the fingerprint providers are installed before the
 * ConfigStore-dependent model discovery would succeed, so nothing here runs until the first commit
 * metadata is built.
 */
public final class NerFingerprint {
  private static final Logger log = LoggerFactory.getLogger(NerFingerprint.class);

  /** Commit metadata key for the NER model fingerprint. */
  public static final String COMMIT_META_KEY = "ner_model_sha256";

  private static final AtomicReference<CachedResult> cachedResult = new AtomicReference<>();

  private NerFingerprint() {}

  /**
   * Returns the SHA-256 of the current NER model, or empty when no model is resolvable <em>or</em>
   * its digest could not be read. Use {@link #modelPath} to tell those two apart.
   */
  public static Optional<String> get() {
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.fingerprint();
    }
    return computeAndCache();
  }

  /**
   * The resolved NER model file, if one was found. Present with an empty {@link #get()} means the
   * digest is unreadable — an unanswered question, not an absent model.
   */
  public static Optional<Path> modelPath() {
    CachedResult cached = cachedResult.get();
    if (cached == null) {
      computeAndCache();
      cached = cachedResult.get();
    }
    return cached != null ? cached.modelPath() : Optional.empty();
  }

  /** Forces recomputation on next access. */
  public static void invalidate() {
    cachedResult.set(null);
  }

  private static synchronized Optional<String> computeAndCache() {
    CachedResult cached = cachedResult.get();
    if (cached != null) {
      return cached.fingerprint();
    }

    Path modelDir = resolveModelDir();
    if (modelDir == null) {
      log.info("No NER model found; fingerprint unavailable");
      cachedResult.set(new CachedResult(Optional.empty(), Optional.empty()));
      return Optional.empty();
    }

    Path modelFile =
        io.justsearch.ort.ModelManifest.loadOrDefault(modelDir).resolveExistingModelFile(modelDir);
    if (!Files.isRegularFile(modelFile)) {
      // A model directory with no model file in it is a determinate "no NER model here".
      log.info("NER model file not found at {}; fingerprint unavailable", modelDir);
      cachedResult.set(new CachedResult(Optional.empty(), Optional.empty()));
      return Optional.empty();
    }

    Optional<String> sha256 = Sha256SidecarCache.getOrCompute(modelFile);
    cachedResult.set(new CachedResult(Optional.of(modelFile), sha256));
    return sha256;
  }

  private static Path resolveModelDir() {
    try {
      ConfigStore store = ConfigStore.globalOrNull();
      String explicit = null;
      if (store != null) {
        Path configured = store.get().ai().ner().modelPath();
        explicit = configured != null ? configured.toString() : null;
      }
      NerModelDiscovery.Result discovery = NerModelDiscovery.resolve(explicit);
      return discovery == null ? null : discovery.modelDir();
    } catch (RuntimeException e) {
      log.debug("NER model discovery failed: {}", e.getMessage());
      return null;
    }
  }

  private record CachedResult(Optional<Path> modelPath, Optional<String> fingerprint) {}
}
