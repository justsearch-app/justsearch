/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ipc;

/**
 * Standard pipeline presets as proto {@link PipelineConfig} instances.
 *
 * <p>These mirror the app-api {@code io.justsearch.app.api.knowledge.PipelineConfig} presets but
 * use the proto builder type for direct use in in-process port request construction. Both layers (app-api and
 * ipc-common) are authoritative for their own type.
 */
public final class PipelineConfigs {
  private PipelineConfigs() {}

  /** BM25 sparse search with LambdaMART reranking. Expansion enabled. */
  public static final PipelineConfig TEXT =
      PipelineConfig.newBuilder()
          .setSparseEnabled(true)
          .setLambdamartEnabled(true)
          .setExpansionEnabled(true)
          .setPipelineName("sparse+lmart")
          .build();

  /** KNN dense retrieval only. */
  public static final PipelineConfig VECTOR =
      PipelineConfig.newBuilder()
          .setDenseEnabled(true)
          .setPipelineName("dense")
          .build();

  /** BM25 + KNN fused with RRF, LambdaMART reranking. */
  public static final PipelineConfig HYBRID =
      PipelineConfig.newBuilder()
          .setSparseEnabled(true)
          .setDenseEnabled(true)
          .setFusionAlgorithm("rrf")
          .setLambdamartEnabled(true)
          .setPipelineName("sparse+dense+lmart")
          .build();
}
