/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Derives the llama-server context window ({@code -c}) instead of reading it as a user preference.
 *
 * <p>Tempdoc 883 decision 1. The model ships with a 262k training context and the app used to run
 * it at 4096, because that was the shipped value of a settings field nobody could see. This policy
 * replaces the number with a <b>ladder of explicit {@code -c} values, stepped down on launch
 * failure</b>.
 *
 * <p>No VRAM arithmetic and no GGUF reader, deliberately (tempdoc 883 fold <b>[R3]</b>): the
 * packaged model is a Gated-Delta-Net hybrid whose KV footprint no dense-attention formula predicts
 * within 4x, {@code /props} on the bundled b8571 build does not expose {@code n_ctx_train}, and an
 * over-large {@code -c} is a fast hard abort — so trying a rung is both cheaper and more truthful
 * than estimating one. Free VRAM is recorded for diagnosis, never used as an input.
 *
 * <p><b>The top rung is a BUDGET, not a fit.</b> Measured 2026-09-02 on the 12 GB dev card, the
 * packaged 9B loads at its full 262144 training context with two slots (KV 4352 MiB, 10.3 GB
 * total) — so the ladder is deliberately leaving capacity on the table, and llama-server's
 * {@code "the full capacity of the model will not be utilized"} line at 32768 is EXPECTED, not a
 * warning to act on. The rung is capped because (a) prefill latency per RAG ask scales with the
 * prompt and the budget fractions will fill whatever window exists, so the rung is what bounds
 * worst-case latency; (b) KV is reserved up front for the whole {@code n_ctx} whether used or not,
 * and the same card must also hold the embedding / SPLADE / NER encoders, the reranker and VDU
 * batches — 544 MiB at 32k versus ~2.2 GB at 128k is headroom this app chooses to keep; and (c)
 * the ladder's job is stepping DOWN on smaller cards, not maximizing on big ones. A user who wants
 * more sets {@code contextLength} / {@code JUSTSEARCH_CONTEXT_SIZE}, which has no upper clamp.
 *
 * <p>Rungs above the top are skipped.
 *
 * <p>An explicit operator value ({@code justsearch.context.size} resolved above
 * {@code ORDINAL_AUTO_DETECT}) produces a <b>one-rung ladder</b>: it is honoured or the launch
 * fails loud. Silently serving a smaller window than an operator asked for would be the same
 * precedence lie this lane deletes elsewhere.
 */
public final class ContextWindowPolicy {

  /**
   * Top rung when at least one layer is offloaded to the GPU — a latency and co-residency BUDGET,
   * not the largest window that fits (see the class javadoc; the dev card fits 8x this).
   */
  public static final int GPU_TOP_RUNG = 32768;

  /** Top rung at {@code -ngl 0}: CPU prefill above this is minutes per RAG ask. */
  public static final int CPU_TOP_RUNG = 8192;

  /** Floor for an explicit operator override. */
  public static final int MIN_EXPLICIT_TOKENS = 512;

  /**
   * The smallest window this app will run its own engine at — the ladder's bottom rung.
   *
   * <p>Also the honest floor for judging an ADOPTED external server: a BYO llama-server is not
   * "too small" merely because it is below the rung we would have chosen for a server we launched.
   */
  public static final int MIN_USABLE_ADOPTED_TOKENS = 4096;

  /**
   * Recorded reason when the launch used the ladder's top rung and it loaded — no step-down.
   *
   * <p>Deliberately NOT called {@code fit}: the top rung is a BUDGET, not the largest window that
   * fits. Measured 2026-09-02 on the 12 GB dev card, the packaged 9B loads at its full
   * {@code n_ctx_train} of 262144 with two slots (KV 4352 MiB, 10.3 GB total), so a value named
   * "fit" would assert something the hardware disproves — and would read as the opposite of the
   * {@code -fit off} this launch passes.
   */
  public static final String REASON_TOP_RUNG = "top-rung";

  /** Recorded reason when an operator set {@code justsearch.context.size} explicitly. */
  public static final String REASON_OVERRIDE = "override";

  /** Prefix of the recorded reason after a step-down: {@code stepped-from:<planned top rung>}. */
  public static final String REASON_STEPPED_FROM_PREFIX =
      io.justsearch.app.api.chat.ChatReasonCodes.STEPPED_FROM_PREFIX;

  /** The full rung set, descending. A backend's ladder is the suffix at or below its top rung. */
  private static final List<Integer> RUNGS =
      List.of(GPU_TOP_RUNG, 16384, CPU_TOP_RUNG, MIN_USABLE_ADOPTED_TOKENS);

  private ContextWindowPolicy() {}

  /**
   * A window plan: the rung to try first and the rungs to fall back through.
   *
   * @param ladder rungs in descending order; never empty, first element is the rung to try
   * @param reason {@link #REASON_TOP_RUNG} or {@link #REASON_OVERRIDE} as planned (a step-down rewrites
   *     it to {@link #REASON_STEPPED_FROM_PREFIX} at launch time)
   * @param freeVramBytes NVML free VRAM at plan time, recorded only — null when unknown
   */
  public record Plan(List<Integer> ladder, String reason, Long freeVramBytes) {
    public Plan {
      Objects.requireNonNull(ladder, "ladder");
      Objects.requireNonNull(reason, "reason");
      if (ladder.isEmpty()) {
        throw new IllegalArgumentException("ladder must have at least one rung");
      }
      ladder = List.copyOf(ladder);
    }

    /** The rung to try first. */
    public int topRung() {
      return ladder.get(0);
    }

    /** The next rung strictly below {@code current}, or empty when the ladder is exhausted. */
    public OptionalInt nextRungBelow(int current) {
      for (int rung : ladder) {
        if (rung < current) {
          return OptionalInt.of(rung);
        }
      }
      return OptionalInt.empty();
    }
  }

  /**
   * The derived ladder for a backend.
   *
   * @param gpuBacked true when {@code -ngl} is greater than zero
   * @param freeVramBytes NVML free VRAM, recorded only (may be null)
   */
  public static Plan auto(boolean gpuBacked, Long freeVramBytes) {
    int top = gpuBacked ? GPU_TOP_RUNG : CPU_TOP_RUNG;
    return new Plan(RUNGS.stream().filter(rung -> rung <= top).toList(), REASON_TOP_RUNG, freeVramBytes);
  }

  /**
   * A one-rung ladder honouring an explicit operator value, clamped to {@link #MIN_EXPLICIT_TOKENS}.
   */
  public static Plan override(int tokens, Long freeVramBytes) {
    return new Plan(
        List.of(Math.max(MIN_EXPLICIT_TOKENS, tokens)), REASON_OVERRIDE, freeVramBytes);
  }

  /**
   * The rung an auto-derived window starts at — the single default for the quantity, used wherever
   * a caller previously fell back to a hardcoded 4096.
   */
  public static int autoTopRung(boolean gpuBacked) {
    return gpuBacked ? GPU_TOP_RUNG : CPU_TOP_RUNG;
  }

  /** The recorded reason after stepping down from {@code plannedTopRung}. */
  public static String steppedFrom(int plannedTopRung) {
    return REASON_STEPPED_FROM_PREFIX + plannedTopRung;
  }
}
