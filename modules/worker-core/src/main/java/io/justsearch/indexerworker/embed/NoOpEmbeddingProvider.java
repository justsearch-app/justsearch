/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed;

import java.util.List;

/**
 * Placeholder implementation of {@link EmbeddingProvider}. Returns empty results and explicitly
 * reports absent, starting or failed. Startup selects the state before publishing a real model.
 */
public final class NoOpEmbeddingProvider implements EmbeddingProvider {

  public static final NoOpEmbeddingProvider INSTANCE = new NoOpEmbeddingProvider();

  private final Availability availability;

  private NoOpEmbeddingProvider() {
    this(Availability.ABSENT);
  }

  private NoOpEmbeddingProvider(Availability availability) {
    this.availability = availability;
  }

  /** Installed model selected, before asynchronous encoder publication. */
  public static EmbeddingProvider starting() {
    return new NoOpEmbeddingProvider(Availability.STARTING);
  }

  /** Failed model publication, preserving the actual cause. */
  public static EmbeddingProvider failed(Throwable cause) {
    return new NoOpEmbeddingProvider(new Availability(Availability.State.FAILED, cause));
  }

  @Override
  public float[] embedDocument(String text) {
    return new float[0];
  }

  @Override
  public float[] embedQuery(String text) {
    return new float[0];
  }

  @Override
  public List<float[]> embedDocumentBatch(List<String> texts) {
    return List.of();
  }

  @Override
  public EmbeddingService.ChunkedEmbedding embedWithSpans(String content, int[][] charSpans) {
    return null;
  }

  @Override
  public int dimension() {
    return 0;
  }

  @Override
  public Availability availability() {
    return availability;
  }

  @Override
  public boolean isUsingGpu() {
    return false;
  }
}
