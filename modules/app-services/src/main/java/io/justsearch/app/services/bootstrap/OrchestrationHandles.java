/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 6: typed record holding {@link AutoCloseable} handles for the bootstrap's
 * background-behavior starts. Each non-null handle is closed in reverse construction order
 * (LIFO) by {@link #close()}; close failures are logged and swallowed.
 *
 * <p>Items whose stop semantics aren't a plain {@code close()} (e.g., a thread's interrupt+join,
 * or a listener-removal coupled to another shutdown) are wrapped at construction site into a
 * lambda that performs the full teardown. (Lane F stage A item A14 removed the one gRPC entry,
 * the infra-health server, whose shutdown+awaitTermination this note was written for.)
 *
 * <p>This record is populated by {@link io.justsearch.app.services.HeadAssembly} during
 * construction (and partially populated again after {@code connectKnowledgeServer} runs and the
 * worker channel comes up). Step 7 will refactor the construction site into a phase function
 * that returns this record; Step 8 will rewrite the bootstrap's {@code close()} to consult it.
 */
public record OrchestrationHandles(
    AutoCloseable gplAutoTrigger,
    AutoCloseable lambdaMartReranker,
    AutoCloseable jobQueueDepthProducer,
    AutoCloseable documentsIndexedRateProducer,
    AutoCloseable gpuUtilizationProducer,
    AutoCloseable gpuMemoryUtilizationProducer,
    AutoCloseable inferenceManager,
    AutoCloseable indexingService,
    AutoCloseable documentService,
    AutoCloseable diagnosticChannelAppender,
    AutoCloseable indexingJobsBridgeRegistry,
    AutoCloseable agentSearchAdapterReranker,
    AutoCloseable indexingJobsBridge,
    AutoCloseable agentToolHandlers)
    implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(OrchestrationHandles.class);

  /**
   * Closes all non-null handles in reverse construction order. Close failures are logged and
   * swallowed so a single bad handle does not block the rest of the teardown.
   */
  @Override
  public void close() {
    List<AutoCloseable> ordered = new ArrayList<>(14);
    ordered.add(gplAutoTrigger);
    ordered.add(lambdaMartReranker);
    ordered.add(jobQueueDepthProducer);
    ordered.add(documentsIndexedRateProducer);
    ordered.add(gpuUtilizationProducer);
    ordered.add(gpuMemoryUtilizationProducer);
    ordered.add(inferenceManager);
    ordered.add(indexingService);
    ordered.add(documentService);
    ordered.add(diagnosticChannelAppender);
    ordered.add(indexingJobsBridgeRegistry);
    ordered.add(agentSearchAdapterReranker);
    ordered.add(indexingJobsBridge);
    ordered.add(agentToolHandlers);
    Collections.reverse(ordered);
    for (AutoCloseable handle : ordered) {
      if (handle == null) {
        continue;
      }
      try {
        handle.close();
      } catch (Exception e) {
        log.warn("OrchestrationHandles close failed for {}: {}", handle.getClass().getSimpleName(), e.getMessage());
      }
    }
  }

  /** Returns an empty handles record where all fields are {@code null}. */
  public static OrchestrationHandles empty() {
    return new OrchestrationHandles(
        null, null, null, null, null, null, null, null, null, null, null, null, null, null);
  }
}
