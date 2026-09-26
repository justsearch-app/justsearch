/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.indexerworker.extract.ExtractionConfiguration;
import io.justsearch.reranker.CitationScorerConfig;
import io.justsearch.reranker.RerankerConfig;
import io.justsearch.reranker.WorkerModelDiscovery;
import java.util.List;
import java.util.Objects;

/** Immutable startup inputs reused when the same physical index reconstructs its services. */
public record WorkerServiceConfiguration(
    ResolvedConfig snapshot,
    ExtractionConfiguration extraction,
    RerankerConfig.ChunkRerankerConfig chunkReranker,
    CitationScorerConfig citationScorer,
    List<WorkerModelDiscovery.DiscoveredModel> discoveredModels) {
  public WorkerServiceConfiguration {
    Objects.requireNonNull(snapshot, "snapshot");
    Objects.requireNonNull(extraction, "extraction");
    Objects.requireNonNull(chunkReranker, "chunkReranker");
    Objects.requireNonNull(citationScorer, "citationScorer");
    discoveredModels = List.copyOf(discoveredModels);
  }

  public static WorkerServiceConfiguration capture(ResolvedConfig snapshot, int maxOcrWorkers) {
    return new WorkerServiceConfiguration(snapshot,
        ExtractionConfiguration.capture(snapshot, maxOcrWorkers,
            DefaultWorkerAppServices.buildSkipPolicy(snapshot)),
        RerankerConfig.ChunkRerankerConfig.from(snapshot),
        CitationScorerConfig.from(snapshot),
        WorkerModelDiscovery.discoverAll(snapshot));
  }
}
