/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.bootstrap.PhaseOutcome;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Classifies boot availability around an already-owned read-only capability graph.
 * ServicePhase later connects inference producers to the generative component handle.
 * A missing bootstrap or unconfigured inference yields a degraded phase outcome;
 * this classification never creates another lifecycle authority.
 */
public final class CapabilityPhase {

  /** Reason code: the index bootstrap was absent at phase entry (async-start path). */
  public static final String REASON_ENGINE_NOT_STARTED = "engine.not_started";

  /** Reason code: inference not configured (lite mode, AI disabled, or env flag). */
  public static final String REASON_INFERENCE_NOT_CONFIGURED = "inference.not_configured";

  private CapabilityPhase() {}

  /**
   * Tempdoc 541 §5.3 — sealed-sum entry point. The single production entry; pattern-match on
   * Ready / Degraded / Failed arms to introspect outcome. Use {@code .orThrow()} for callers
   * that want the legacy "throw on non-Ready" semantics.
   */
  public static PhaseOutcome<CapabilityGraph> runWithOutcome(
      KnowledgeServerBootstrap knowledgeServer,
      boolean inferenceConfigured,
      CapabilityGraph graph) {
    try {
      java.util.Objects.requireNonNull(graph, "graph");
      Set<String> reasons = new LinkedHashSet<>();
      if (knowledgeServer == null) {
        reasons.add(REASON_ENGINE_NOT_STARTED);
      }
      if (!inferenceConfigured) {
        reasons.add(REASON_INFERENCE_NOT_CONFIGURED);
      }
      if (reasons.isEmpty()) {
        return new PhaseOutcome.Ready<>(graph);
      }
      return new PhaseOutcome.Degraded<>(graph, reasons);
    } catch (RuntimeException e) {
      return PhaseOutcome.Failed.of(e);
    }
  }
}
