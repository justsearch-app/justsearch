/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

import java.util.Map;
import java.util.Set;

/**
 * Per-request sampling parameters for LLM completions.
 *
 * <p>Presets are tuned for different workloads:
 *
 * <ul>
 *   <li>{@link #THINKING} — Higher temperature for chain-of-thought reasoning (Qwen3 recommended)
 *   <li>{@link #DETERMINISTIC} — Low temperature for structured/factual output
 *   <li>{@link #VDU} — Slightly creative for document understanding extraction
 *   <li>{@link #VDU_PROBE} — Varied temperature for the tempdoc 677 Stage 2 re-sample probe
 *   <li>{@link #AGENT} — Balanced for agent tool calling with multi-step reasoning
 * </ul>
 *
 * @param temperature sampling temperature (0.0–2.0)
 * @param topP nucleus sampling probability mass (0.0–1.0)
 * @param toolChoice tool_choice parameter for llama-server; null = server default ("auto")
 * @param grammar GBNF grammar string for output-format constraints; null = no grammar
 * @param enableThinking if non-null, emits {@code chat_template_kwargs={"enable_thinking":<value>}};
 *     use {@code false} on mechanical turns (E0a, DECIDING) to suppress thinking-prompt formatting
 * @param responseFormat JSON schema for llama-server's response_format constraint (363). Mutually
 *     exclusive with grammar — if both are set, response_format takes precedence. Pass as {@code
 *     Map.of("type", "json_object", "schema", schemaMap)}.
 * @param seed llama-server's RNG seed; null = omit the field, which is what every preset does and
 *     what every caller got before this component existed (lane F PR 0b). A seed alone does not
 *     make a completion reproducible — the server must also be running a single slot and the same
 *     prompt — but without one it cannot be, which is why the workflow fixture pins it through the
 *     request's optional {@code sampling} override rather than relying on temperature 0 alone.
 */
public record SamplingParams(
    double temperature,
    double topP,
    String toolChoice,
    String grammar,
    Boolean enableThinking,
    Map<String, Object> responseFormat,
    Long seed) {

  private static final Set<String> VALID_TOOL_CHOICES = Set.of("auto", "required", "none");

  /** Validates that all fields are within expected ranges. */
  public SamplingParams {
    if (temperature < 0.0 || temperature > 2.0) {
      throw new IllegalArgumentException("temperature must be 0.0–2.0, got " + temperature);
    }
    if (topP < 0.0 || topP > 1.0) {
      throw new IllegalArgumentException("topP must be 0.0–1.0, got " + topP);
    }
    if (toolChoice != null && !VALID_TOOL_CHOICES.contains(toolChoice)) {
      throw new IllegalArgumentException(
          "toolChoice must be null, \"auto\", \"required\", or \"none\", got \"" + toolChoice + "\"");
    }
  }

  /** Backward-compatible constructor (pre-lane-F-PR-0b, no seed) — delegates with seed null. */
  public SamplingParams(
      double temperature,
      double topP,
      String toolChoice,
      String grammar,
      Boolean enableThinking,
      Map<String, Object> responseFormat) {
    this(temperature, topP, toolChoice, grammar, enableThinking, responseFormat, null);
  }

  /** Backward-compatible constructor: enableThinking and responseFormat default to null. */
  public SamplingParams(
      double temperature, double topP, String toolChoice, String grammar, Boolean enableThinking) {
    this(temperature, topP, toolChoice, grammar, enableThinking, null);
  }

  /** Backward-compatible constructor: enableThinking defaults to null. */
  public SamplingParams(double temperature, double topP, String toolChoice, String grammar) {
    this(temperature, topP, toolChoice, grammar, null);
  }

  /** Backward-compatible constructor: toolChoice and grammar default to null. */
  public SamplingParams(double temperature, double topP, String toolChoice) {
    this(temperature, topP, toolChoice, null);
  }

  /** Backward-compatible constructor: toolChoice defaults to null (= server default "auto"). */
  public SamplingParams(double temperature, double topP) {
    this(temperature, topP, null);
  }

  /** Returns a copy with the given toolChoice override, preserving all other fields. */
  public SamplingParams withToolChoice(String toolChoice) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat, seed);
  }

  /** Returns a copy with the given grammar override, preserving all other fields. */
  public SamplingParams withGrammar(String grammar) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat, seed);
  }

  /**
   * Returns a copy with the given enableThinking override, preserving all other fields.
   *
   * <p>Use {@code false} on mechanical turns (E0a, DECIDING) to suppress thinking-prompt
   * formatting. Use {@code null} to let the server apply its default.
   */
  public SamplingParams withEnableThinking(Boolean enableThinking) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat, seed);
  }

  /** Returns a copy with the given responseFormat, preserving all other fields. (363) */
  public SamplingParams withResponseFormat(Map<String, Object> responseFormat) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat, seed);
  }

  /**
   * Returns a copy with the given llama-server RNG seed, preserving all other fields. {@code null}
   * restores the default (omit the field). Lane F PR 0b — the agent chat request's optional
   * {@code sampling} override is the only production caller.
   */
  public SamplingParams withSeed(Long seed) {
    return new SamplingParams(
        temperature, topP, toolChoice, grammar, enableThinking, responseFormat, seed);
  }

  /** Recommended for thinking/reasoning models (Qwen3.5 defaults: temp=0.7, top_p=0.8). */
  public static final SamplingParams THINKING = new SamplingParams(0.7, 0.8);

  /**
   * Near-deterministic output for structured extraction and factual responses — the preset the
   * pipeline's mechanical calls use (query rewrite, query understanding, filter normalization,
   * context sufficiency, query expansion, section summarize).
   *
   * <p>Thinking is disabled here, not at each call site (tempdoc 835 §10f). Once the server-wide
   * reasoning budget is on by default, every call that leaves {@code enableThinking} null inherits
   * it: a measured rag-ask turn logged 95 reasoning activations across 101 completion requests,
   * i.e. roughly six thinking calls per turn where only one produces an answer a user reads. These
   * calls either discard {@code reasoning_content} outright or feed a parser, so reasoning on them
   * is pure latency and pure token cost. Three sites had already hand-applied
   * {@code withEnableThinking(false)}, which is the duplication this default retires. Calls that
   * SHOULD think use {@link #THINKING}, or the conversation path's request-derived params.
   */
  public static final SamplingParams DETERMINISTIC = new SamplingParams(0.1, 0.9, null, null, false);

  /**
   * Deterministic preset for vision document understanding (VDU).
   *
   * <p>Temperature 0 for deterministic OCR output. Thinking disabled because VLM output goes to
   * {@code reasoning_content} (lost) instead of {@code content} when thinking is enabled.
   */
  public static final SamplingParams VDU = new SamplingParams(0.0, 0.9, null, null, false);

  /**
   * Tempdoc 677 Stage 2: re-sample preset for the abstention gate's agreement probe. Identical to
   * {@link #VDU} except temperature 0.8 (vs. {@code VDU}'s 0.0) — the probe exists to re-sample
   * an AMBIGUOUS Stage-1 output and see whether it agrees with itself; re-sampling at temperature
   * 0 would just reproduce the exact same (possibly confabulated) output deterministically,
   * telling the gate nothing (tempdoc 677 code map: "a naive re-sample repeats the same
   * hallucination; a consistency probe must vary seed/temperature explicitly").
   */
  public static final SamplingParams VDU_PROBE = new SamplingParams(0.8, 0.9, null, null, false);

  /**
   * Preset for agent tool calling, tuned to Qwen3.5 recommended sampling.
   *
   * <p>temp=0.7, top_p=0.8 per Qwen3.5 model card recommendations. Higher temperature improves
   * PRIMARY's text response quality; top_p=0.8 is tighter than Qwen3's 0.95, reducing low-quality
   * tail tokens. For tool-call turns, grammar constraints override sampling anyway.
   */
  public static final SamplingParams AGENT = new SamplingParams(0.7, 0.8);
}
