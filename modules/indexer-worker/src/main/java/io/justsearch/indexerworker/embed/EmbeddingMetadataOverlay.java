/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.indexerworker.splade.SpladeFingerprint;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * CommitMetadataSource overlay that conditionally includes model fingerprints.
 *
 * <p>This wraps the standard {@link SsotCommitMetadataSource} and adds the
 * {@code embedding_model_sha256} and {@code splade_model_sha256} fields when the respective
 * models are available and safe to stamp.
 *
 * <p>The overlay is used during Lucene commits to ensure model fingerprints are only
 * written when models are active, preventing false "OK" states.
 */
public final class EmbeddingMetadataOverlay implements CommitMetadataSource {

  private final CommitMetadataSource delegate;
  private final Supplier<Optional<String>> embeddingFingerprintSupplier;
  private final Supplier<Optional<String>> spladefingerprintSupplier;

  /**
   * Creates a new overlay with embedding fingerprint only.
   *
   * @param delegate the underlying metadata source (e.g., {@link SsotCommitMetadataSource})
   * @param fingerprintSupplier supplier that returns the embedding fingerprint to stamp
   */
  public EmbeddingMetadataOverlay(
      CommitMetadataSource delegate,
      Supplier<Optional<String>> fingerprintSupplier) {
    this(delegate, fingerprintSupplier, Optional::empty);
  }

  /**
   * Creates a new overlay with both embedding and SPLADE fingerprints.
   *
   * @param delegate the underlying metadata source
   * @param embeddingFingerprintSupplier supplier for embedding model fingerprint
   * @param spladefingerprintSupplier supplier for SPLADE model fingerprint
   */
  public EmbeddingMetadataOverlay(
      CommitMetadataSource delegate,
      Supplier<Optional<String>> embeddingFingerprintSupplier,
      Supplier<Optional<String>> spladefingerprintSupplier) {
    this.delegate = delegate;
    this.embeddingFingerprintSupplier = embeddingFingerprintSupplier;
    this.spladefingerprintSupplier = spladefingerprintSupplier;
  }

  @Override
  public Map<String, Object> build() {
    Map<String, Object> base = delegate.build();
    Optional<String> embeddingFp = embeddingFingerprintSupplier.get();
    Optional<String> spladefp = spladefingerprintSupplier.get();

    if (embeddingFp.isEmpty() && spladefp.isEmpty()) {
      return base;
    }

    Map<String, Object> result = new LinkedHashMap<>(base);
    embeddingFp.ifPresent(fp -> result.put(EmbeddingCompatibilityController.COMMIT_META_KEY, fp));
    spladefp.ifPresent(fp -> result.put(SpladeFingerprint.COMMIT_META_KEY, fp));
    return Map.copyOf(result);
  }

  @Override
  public List<String> indeterminateFingerprintInputs() {
    return delegate.indeterminateFingerprintInputs();
  }

  /**
   * Creates a supplier that produces overlaid metadata sources on demand.
   *
   * <p>Use this with {@link io.justsearch.adapters.lucene.runtime.IndexRuntimeFactory#createRuntime}
   * to inject the overlay into the Lucene runtime.
   *
   * @param fingerprintSupplier supplier that returns the embedding fingerprint to stamp
   * @return a supplier of CommitMetadataSource that includes the overlay
   */
  public static Supplier<CommitMetadataSource> createSupplier(
      Supplier<Optional<String>> fingerprintSupplier) {
    return createSupplier(fingerprintSupplier, Optional::empty);
  }

  /**
   * Creates a supplier that produces overlaid metadata sources with both fingerprints.
   *
   * @param embeddingFingerprintSupplier supplier for embedding model fingerprint
   * @param spladefingerprintSupplier supplier for SPLADE model fingerprint
   * @return a supplier of CommitMetadataSource that includes both overlays
   */
  public static Supplier<CommitMetadataSource> createSupplier(
      Supplier<Optional<String>> embeddingFingerprintSupplier,
      Supplier<Optional<String>> spladefingerprintSupplier) {
    return () -> new EmbeddingMetadataOverlay(
        new SsotCommitMetadataSource(),
        embeddingFingerprintSupplier,
        spladefingerprintSupplier);
  }
}
