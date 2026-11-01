/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * All three stages of the VDU abstention cascade (tempdoc 677 §Proposed design): pure, stateless
 * verdict logic deciding whether a page/document's vision-extraction output should be trusted.
 *
 * <p><b>Stage 0 — input legibility</b> ({@link #inputVerdict}): a per-page check on raw pixel
 * signals, before any model call. <b>CAUTION (quoted from the tempdoc, verbatim):</b> "the gate
 * must key on 'no textual signal present for anything' (blur/contrast floor), not 'OCR confidence
 * low', or it would defeat VDU's purpose." {@link #LAPLACIAN_FLOOR} / {@link #CONTRAST_FLOOR} are
 * therefore deliberately conservative — Stage 0 must only catch pages carrying no textual signal
 * at all (blank/blown-out/uniformly-flat), not merely hard-to-read ones; a stray ambiguous case
 * must fall through to Stage 1, not get silently abstained here. <b>PROVISIONAL:</b> unlike
 * Stages 1/2 below, these two floors have not been through the 2026-07-11 calibration pass (that
 * pass targeted output-side confidence signals, not input-pixel signals) — they remain a
 * conservative starting point.
 *
 * <p><b>Stage 1 — same-call output confidence</b> ({@link #outputVerdict}): a document-level
 * three-band check on the signals returned alongside the model's own Pass 1 output (mean
 * logprob, low-confidence-token fraction, finish reason). {@link Band#REJECT} abstains outright;
 * {@link Band#AMBIGUOUS} defers to Stage 2 (the caller — {@code VduProcessor} — must re-sample
 * before deciding); {@link Band#PASS} trusts the output as-is.
 *
 * <p><b>CALIBRATED (2026-07-11 harness, tempdoc 677 §Calibration):</b> {@link
 * #REJECT_MEAN_LOGPROB_FLOOR}, {@link #REJECT_LOW_CONFIDENCE_FRACTION_CEILING}, {@link
 * #AMBIGUOUS_MEAN_LOGPROB_FLOOR}, {@link #AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING}, and {@link
 * #AGREEMENT_FLOOR} are derived from a live llama-server (Qwen3.5-9B) run over {@code
 * golden/synth-scan-v1} (n=30 confabulated documents) and a legible fixture set (n=16), not first-cut
 * guesses:
 *
 * <ul>
 *   <li><b>legible (n=16):</b> meanLogprob ∈ [-0.058, -0.006], lowConfidenceFraction = 0.000 (all
 *       16), re-sample (Stage 2) agreement = 1.000 (all 16).
 *   <li><b>confabulated, SHORT/refusal-shaped mode (22/30):</b> meanLogprob ≤ -0.41,
 *       lowConfidenceFraction ≥ 0.07 — comfortably caught by the REJECT arm.
 *   <li><b>confabulated, LONG/confident mode (8/30 — the mode that evades a naive Stage 1
 *       floor):</b> meanLogprob ∈ [-0.25, -0.049], lowConfidenceFraction ∈ [0.013, 0.048];
 *       re-sample agreement 0.047-0.239 (vs. legible 1.000; short-confab-mode agreement
 *       0.231-0.379). This is the population {@link #AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING}
 *       (0.005) is tuned to catch: every one of the 8 has lowConfidenceFraction ≥ 0.013, well
 *       above the legible population's flat 0.000, so the AMBIGUOUS band always fires for this
 *       mode even on the sub-cases whose meanLogprob alone (as high as -0.049) would clear {@link
 *       #AMBIGUOUS_MEAN_LOGPROB_FLOOR}. Stage 2's re-sample agreement (max 0.239) then separates
 *       these from a genuinely legible page (1.000) with wide margin.
 * </ul>
 */
public final class VduAbstentionGate {

  /** {@link GateVerdict#stage()} value for a Stage 0 (input legibility) rejection. */
  public static final String STAGE_INPUT_LEGIBILITY = "input_legibility";

  /** {@link GateVerdict#stage()} value for a Stage 1 (output confidence) rejection. */
  public static final String STAGE_OUTPUT_CONFIDENCE = "output_confidence";

  /** {@link GateVerdict#stage()} value for a Stage 2 (re-sample agreement probe) rejection. */
  public static final String STAGE_AGREEMENT = "agreement";

  /**
   * PROVISIONAL Stage 0 Laplacian-variance floor. Paired conjunctively with {@link
   * #CONTRAST_FLOOR} via {@link LegibilityMeasures#belowFloor} — both must be breached to reject.
   * Chosen far below the magnitude any real text content produces: {@code
   * ImageLegibilityTest.sharpTextHasHighVarianceAndContrast}'s sparse 3-line synthetic text
   * fixture demonstrates real text edges dominate the variance (large, well-distributed
   * responses), landing orders of magnitude above this floor; a genuinely blank/flat page
   * measures exactly {@code 0.0} ({@code uniformImageHasNearZeroSignals}). 15.0 leaves a wide
   * margin between "flat page" and "any real content" for calibration to tighten later.
   */
  public static final double LAPLACIAN_FLOOR = 15.0;

  /**
   * PROVISIONAL Stage 0 RMS-contrast floor, paired conjunctively with {@link #LAPLACIAN_FLOOR}
   * (see {@link LegibilityMeasures#belowFloor}). Because the two floors are ANDed, a page cannot
   * be rejected on contrast alone — {@code ImageLegibilityTest}'s sparse-text fixture (contrast
   * just above 0.05) would still pass Stage 0 even at this floor, since its Laplacian variance is
   * nowhere near {@link #LAPLACIAN_FLOOR}. A genuinely flat page measures {@code 0.0} contrast.
   */
  public static final double CONTRAST_FLOOR = 0.06;

  /**
   * CALIBRATED Stage 1 REJECT mean-logprob floor (reject when the aggregated mean logprob is
   * strictly below this). tempdoc 677 §Calibration: legible mean ∈ [-0.058, -0.006];
   * short-confab-mode mean ≤ -0.41. -0.35 sits just above the short-confab floor (comfortable
   * margin against that population) while leaving the entire long-confab-mode band ([-0.25,
   * -0.049]) to fall through to {@link #AMBIGUOUS_MEAN_LOGPROB_FLOOR} / Stage 2 instead of a
   * (wrong) outright reject — the long-confab mode is exactly the population Stage 2 exists for.
   */
  public static final double REJECT_MEAN_LOGPROB_FLOOR = -0.35;

  /**
   * CALIBRATED Stage 1 REJECT low-confidence-token-fraction ceiling (reject when the aggregated
   * fraction is strictly above this). tempdoc 677 §Calibration: legible fraction = 0.000 (all 16);
   * short-confab-mode fraction ≥ 0.07. 0.06 sits between the two — above every legible
   * observation, below the short-confab-mode floor — while still leaving the long-confab-mode
   * band ([0.013, 0.048]) below this ceiling so it falls through to the AMBIGUOUS arm instead of
   * an outright reject.
   */
  public static final double REJECT_LOW_CONFIDENCE_FRACTION_CEILING = 0.06;

  /**
   * CALIBRATED Stage 1 AMBIGUOUS mean-logprob floor (below {@link #REJECT_MEAN_LOGPROB_FLOOR} but
   * below this floor too ⇒ ambiguous, not an outright pass). tempdoc 677 §Calibration:
   * long-confab-mode mean ∈ [-0.25, -0.049]; legible mean ≥ -0.058. -0.09 sits just below the
   * legible population's ceiling (-0.058), catching the bulk of the long-confab-mode band on the
   * logprob arm alone while leaving legible docs a real margin above it.
   */
  public static final double AMBIGUOUS_MEAN_LOGPROB_FLOOR = -0.09;

  /**
   * CALIBRATED Stage 1 AMBIGUOUS low-confidence-token-fraction ceiling (above {@link
   * #REJECT_LOW_CONFIDENCE_FRACTION_CEILING}'s complement but above this ceiling ⇒ ambiguous).
   * tempdoc 677 §Calibration: legible fraction = 0.000 (all 16, no exceptions); long-confab-mode
   * fraction ∈ [0.013, 0.048] (all 8, no exceptions). 0.005 sits strictly between the two
   * populations with wide margin on the legible side and catches every long-confab-mode document
   * on this arm alone (min observed 0.013 &gt; 0.005), independent of whether that document's
   * meanLogprob also breaches {@link #AMBIGUOUS_MEAN_LOGPROB_FLOOR}.
   */
  public static final double AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING = 0.005;

  /**
   * CALIBRATED Stage 2 agreement floor (reject when the re-sample's Jaccard word-set agreement
   * with Pass 1's own text is strictly below this). tempdoc 677 §Calibration: legible agreement =
   * 1.000 (all 16, no exceptions — a genuine transcription is stable under re-sampling);
   * confabulated agreement 0.047-0.379 (both long- and short-confab modes, whichever reaches
   * Stage 2). 0.5 sits at the sample's exact midpoint of the observed gap (max confabulated 0.379
   * to min legible 1.000), a wide margin on both sides.
   */
  public static final double AGREEMENT_FLOOR = 0.5;

  /** Unicode letters and digits, including short transcriptions. */
  private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+");

  private VduAbstentionGate() {}

  /**
   * Stage 0 per-page verdict: below-floor pixel signals (see {@link #LAPLACIAN_FLOOR} / {@link
   * #CONTRAST_FLOOR}) reject; anything else passes. Aggregating this per-page verdict into a
   * document-level "every page illegible" decision is the caller's responsibility ({@code
   * VduProcessor}), since only the caller knows the full page set.
   *
   * @param measures the page's legibility measurement ({@link ImageLegibility#measure})
   * @return a verdict carrying the page's {@code laplacianVariance}/{@code rmsContrast} as
   *     evidence; {@link GateVerdict#stage()} is {@link #STAGE_INPUT_LEGIBILITY} iff rejected
   */
  public static GateVerdict inputVerdict(LegibilityMeasures measures) {
    boolean rejected = measures.belowFloor(LAPLACIAN_FLOOR, CONTRAST_FLOOR);
    return new GateVerdict(
        rejected,
        rejected ? GateVerdict.Band.REJECT : GateVerdict.Band.PASS,
        rejected ? STAGE_INPUT_LEGIBILITY : null,
        null,
        null,
        null,
        null,
        measures.laplacianVariance(),
        measures.rmsContrast(),
        null,
        null);
  }

  /**
   * Stage 1 document-level verdict over the pages actually sent to the model — a three-band
   * decision, not a binary reject/pass (see the class javadoc's §Calibration section for the
   * derivation of every threshold below).
   *
   * <p>{@link GateVerdict.Band#REJECT} when ANY of:
   *
   * <ul>
   *   <li>{@code meanLogprob != null && meanLogprob < REJECT_MEAN_LOGPROB_FLOOR}
   *   <li>{@code lowConfidenceFraction != null && lowConfidenceFraction >
   *       REJECT_LOW_CONFIDENCE_FRACTION_CEILING}
   *   <li>{@code finishReason != null && !"stop".equals(finishReason) &&
   *       !"length".equals(finishReason)}
   * </ul>
   *
   * <p>Otherwise {@link GateVerdict.Band#AMBIGUOUS} when ANY of:
   *
   * <ul>
   *   <li>{@code lowConfidenceFraction != null && lowConfidenceFraction >
   *       AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING}
   *   <li>{@code meanLogprob != null && meanLogprob < AMBIGUOUS_MEAN_LOGPROB_FLOOR}
   * </ul>
   *
   * <p>Otherwise {@link GateVerdict.Band#PASS}.
   *
   * <p><b>Null semantics (CRITICAL):</b> a {@code null} {@code meanLogprob} or {@code
   * lowConfidenceFraction} means the server returned NO SIGNAL (logprobs not requested or
   * unsupported) — it must NEVER count toward REJECT or AMBIGUOUS. A document with both signals
   * null and no anomalous finish reason is {@link GateVerdict.Band#PASS}, not ambiguous. Only the
   * finish-reason arm can still reject when the logprob signals are absent.
   *
   * <p><b>The {@code "length"} exception:</b> a truncated-but-otherwise-clean completion
   * ({@code finish_reason == "length"}) is excluded from the finish-reason trigger — hitting the
   * token budget is not, by itself, evidence of confabulation (tempdoc 677 task: "note truncation
   * but do NOT reject on it alone"). The raw {@code "length"} value still flows through into the
   * returned verdict's {@link GateVerdict#finishReason()} for the evidence trail, it just does
   * not independently flip the band.
   *
   * @param signals the token-weighted aggregate across every page sent to the model
   * @return a verdict carrying {@code signals}' fields as evidence; {@link GateVerdict#stage()}
   *     is {@link #STAGE_OUTPUT_CONFIDENCE} iff the band is REJECT ({@code stage()} is {@code
   *     null} for an AMBIGUOUS verdict — nothing has rejected it yet)
   */
  public static GateVerdict outputVerdict(AggregatedPageSignals signals) {
    boolean logprobBelowRejectFloor =
        signals.meanLogprob() != null && signals.meanLogprob() < REJECT_MEAN_LOGPROB_FLOOR;
    boolean lowConfidenceAboveRejectCeiling =
        signals.lowConfidenceFraction() != null
            && signals.lowConfidenceFraction() > REJECT_LOW_CONFIDENCE_FRACTION_CEILING;
    boolean finishReasonSuspect =
        signals.finishReason() != null
            && !"stop".equals(signals.finishReason())
            && !"length".equals(signals.finishReason());
    boolean rejected =
        logprobBelowRejectFloor || lowConfidenceAboveRejectCeiling || finishReasonSuspect;

    boolean logprobBelowAmbiguousFloor =
        signals.meanLogprob() != null && signals.meanLogprob() < AMBIGUOUS_MEAN_LOGPROB_FLOOR;
    boolean lowConfidenceAboveAmbiguousCeiling =
        signals.lowConfidenceFraction() != null
            && signals.lowConfidenceFraction() > AMBIGUOUS_LOW_CONFIDENCE_FRACTION_CEILING;
    boolean ambiguous =
        !rejected && (logprobBelowAmbiguousFloor || lowConfidenceAboveAmbiguousCeiling);

    GateVerdict.Band band =
        rejected ? GateVerdict.Band.REJECT : ambiguous ? GateVerdict.Band.AMBIGUOUS : GateVerdict.Band.PASS;

    return new GateVerdict(
        rejected,
        band,
        rejected ? STAGE_OUTPUT_CONFIDENCE : null,
        signals.meanLogprob(),
        signals.lowConfidenceFraction(),
        signals.tokenCount(),
        signals.finishReason(),
        null,
        null,
        null,
        null);
  }

  /**
   * Stage 2 verdict: resolves an AMBIGUOUS Stage-1 verdict using the re-sample agreement probe
   * (tempdoc 677 §Proposed design — "re-run Pass 1 once with varied seed/temperature; ... low
   * agreement → reject"). Rejects when {@code jaccardAgreement < AGREEMENT_FLOOR}; see the class
   * javadoc's §Calibration section for the floor's derivation.
   *
   * <p>This method is deliberately narrow — it only judges the agreement score itself. It does
   * not know which page was probed ({@code probedPage} is {@code null} on the returned verdict);
   * the caller ({@code VduProcessor}, which alone knows the page index) attaches that via {@link
   * GateVerdict#withProbedPage}.
   *
   * @param jaccardAgreement the {@link #jaccardAgreement} score between the probed page's Pass 1
   *     text and its re-sampled probe text
   * @return a verdict carrying {@code jaccardAgreement} as {@link GateVerdict#agreement()}; {@link
   *     GateVerdict#stage()} is {@link #STAGE_AGREEMENT} iff rejected
   */
  public static GateVerdict agreementVerdict(double jaccardAgreement) {
    boolean rejected = jaccardAgreement < AGREEMENT_FLOOR;
    return new GateVerdict(
        rejected,
        rejected ? GateVerdict.Band.REJECT : GateVerdict.Band.PASS,
        rejected ? STAGE_AGREEMENT : null,
        null,
        null,
        null,
        null,
        null,
        null,
        jaccardAgreement,
        null);
  }

  /**
   * Jaccard word-set agreement between two texts — the tempdoc 677 Stage 2 signal ("CE-OCR
   * pattern") measuring how much a re-sample agrees with the original, as a proxy for whether the
   * original was a genuine reading of the page (stable under re-sampling) vs. a confabulation
   * (different every time).
   *
   * <p>Tokenization uses lowercase Unicode letters and digits with the existing three-character
   * word floor. Scripts conventionally written without word spaces use character bigrams.
   * When both token sets are empty, normalized text equality distinguishes short transcriptions
   * from disagreement. Two blank outputs agree; blank and nonblank outputs disagree.
   *
   * @param a first text (order-independent — this is a symmetric measure)
   * @param b second text
   * @return the Jaccard agreement in {@code [0.0, 1.0]}
   */
  public static double jaccardAgreement(String a, String b) {
    Set<String> tokensA = tokenize(a);
    Set<String> tokensB = tokenize(b);
    if (tokensA.isEmpty() && tokensB.isEmpty()) {
      return (a == null ? "" : a.strip().toLowerCase(Locale.ROOT))
          .equals(b == null ? "" : b.strip().toLowerCase(Locale.ROOT)) ? 1.0 : 0.0;
    }
    Set<String> intersection = new HashSet<>(tokensA);
    intersection.retainAll(tokensB);
    Set<String> union = new HashSet<>(tokensA);
    union.addAll(tokensB);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> tokenize(String text) {
    if (text == null || text.isEmpty()) {
      return Set.of();
    }
    Set<String> tokens = new HashSet<>();
    Matcher matcher = WORD_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String word = matcher.group();
      int[] points = word.codePoints().toArray();
      boolean unspaced = word.codePoints().anyMatch(cp -> switch (Character.UnicodeScript.of(cp)) {
        case HAN, HIRAGANA, KATAKANA, THAI, LAO, KHMER, MYANMAR -> true;
        default -> false;
      });
      if (unspaced && points.length > 1) {
        for (int i = 0; i < points.length - 1; i++) tokens.add(new String(points, i, 2));
      } else if (points.length >= 3) {
        tokens.add(word);
      }
    }
    return tokens;
  }
}
