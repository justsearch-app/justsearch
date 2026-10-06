/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Objects;

/**
 * One captured enrichment pass, projected from acknowledged index outcomes. This is not the
 * global enrichment backlog. A failed unit has a committed no-text, rejection or failure result;
 * an unacknowledged write remains an unfinished unit even if an earlier effect may have occurred.
 */
public record OfflineProcessingOutcome(
    int selected, int processed, int failed, BlockReason blockedReason,
    EmbeddingHandoff embeddingHandoff) {
  public OfflineProcessingOutcome {
    Objects.requireNonNull(blockedReason, "blockedReason");
    Objects.requireNonNull(embeddingHandoff, "embeddingHandoff");
    if (selected < 0 || processed < 0 || failed < 0 || (long) processed + failed > selected) {
      throw new IllegalArgumentException("Invalid captured enrichment counts");
    }
  }

  public int remaining() { return selected - processed - failed; }
  public int blocked() { return blockedReason == BlockReason.NONE ? 0 : remaining(); }

  public boolean complete() {
    return remaining() == 0 && failed == 0 && blockedReason == BlockReason.NONE
        && embeddingHandoff == EmbeddingHandoff.NOT_NEEDED;
  }

  public OfflineProcessingOutcome withEmbeddingHandoff(EmbeddingHandoff handoff) {
    return new OfflineProcessingOutcome(selected, processed, failed, blockedReason, handoff);
  }

  public enum BlockReason {
    NONE, WORKER_UNAVAILABLE, INSUFFICIENT_VRAM, MISSING_VISION, AI_OFFLINE,
    ACTIVITY_OR_ENERGY, CIRCUIT_OPEN, PROCESSING_REFUSED
  }

  /**
   * Whether the pass got as far as embeddings. No pass stops chat for them any more (Spec A O2):
   * the index half's own backfill embeds beside chat, so a finished pass reports
   * {@link #NOT_NEEDED}.
   */
  public enum EmbeddingHandoff { NOT_EVALUATED, NOT_NEEDED }
}
