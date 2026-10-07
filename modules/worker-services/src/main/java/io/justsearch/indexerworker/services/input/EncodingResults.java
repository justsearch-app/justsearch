/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services.input;

import io.justsearch.ort.QueryCpuRecourse;
import java.util.Objects;

/**
 * Captured outcomes of all three encoding modalities for one request (tempdoc 517).
 *
 * <p>Produced by {@code SearchInputCapture} as part of {@link SearchInputs} and
 * read by {@code SearchPlanner} to decide leg-execution. Reason codes for
 * encoding failures live on the {@code Failed} variant of each modality and are
 * read directly by the wire emission — no duplication across decision variants.
 *
 * @param denseServed the device that encoded the dense leg's query and whether the CPU recourse
 *     took it, or null when no encoder lease was taken for it (the search's trace records it)
 * @param spladeServed the same for the SPLADE leg
 */
public record EncodingResults(
    VectorEncoding vector,
    SpladeEncoding splade,
    BgeM3Encoding bgeM3,
    QueryCpuRecourse.Served denseServed,
    QueryCpuRecourse.Served spladeServed) {

  public EncodingResults {
    Objects.requireNonNull(vector, "vector");
    Objects.requireNonNull(splade, "splade");
    Objects.requireNonNull(bgeM3, "bgeM3");
  }

  /** Outcomes with no record of the devices that served the legs. */
  public EncodingResults(VectorEncoding vector, SpladeEncoding splade, BgeM3Encoding bgeM3) {
    this(vector, splade, bgeM3, null, null);
  }

  /** Returns {@code true} if every modality is {@link VectorEncoding.NotRequested}. */
  public boolean allNotRequested() {
    return vector instanceof VectorEncoding.NotRequested
        && splade instanceof SpladeEncoding.NotRequested
        && bgeM3 instanceof BgeM3Encoding.NotRequested;
  }
}
