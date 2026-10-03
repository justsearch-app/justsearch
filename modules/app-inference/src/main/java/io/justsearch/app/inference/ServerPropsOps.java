/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import tools.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Interprets llama-server /props JSON and manages external server diagnostics.
 *
 * <p>Pure interpretation layer: takes a {@link JsonNode} from /props, extracts model identity,
 * context size, and external adoption diagnostics. No HTTP I/O, no process management. Extracted
 * from {@link LlamaServerOps} to reduce class size.
 */
final class ServerPropsOps {
  private static final Logger LOG = LoggerFactory.getLogger(ServerPropsOps.class);

  // ==================== Vision Capability ====================

  private final AtomicBoolean hasVisionCapability = new AtomicBoolean(false);

  // ==================== Build Version Pin (tempdoc 682 Item 2) ====================

  private final AtomicReference<String> observedServerBuild = new AtomicReference<>(null);
  private final AtomicReference<String> lastBuildMismatchWarned = new AtomicReference<>(null);
  private final AtomicReference<String> lastReasoningCapsLogged = new AtomicReference<>(null);

  // ==================== External Server Adoption Diagnostics ====================

  private final AtomicBoolean externalServerVerified = new AtomicBoolean(false);
  private final AtomicReference<String> externalServerVerificationError =
      new AtomicReference<>(null);
  private final AtomicReference<String> externalServerModelId = new AtomicReference<>(null);
  private final AtomicReference<Integer> externalServerContextTokens = new AtomicReference<>(null);
  private final AtomicBoolean externalServerModelMismatch = new AtomicBoolean(false);
  private final AtomicBoolean externalServerContextTooSmall = new AtomicBoolean(false);
  private final AtomicLong externalServerAdoptedAtMs = new AtomicLong(0);

  // ==================== Injected Dependencies ====================

  private final PropsObserver propsObserver;
  /** The {@code -c} this process actually launched with, or 0 when it launched nothing. */
  private final IntSupplier requestedContextTokens;

  ServerPropsOps(
      PropsObserver propsObserver,
      IntSupplier requestedContextTokens) {
    this.requestedContextTokens = requestedContextTokens;
    this.propsObserver = propsObserver;
  }

  // ==================== Props Interpretation ====================

  void updateFromPropsBestEffort(JsonNode root, LlamaServerOps.StartResult expectedOwner) {
    if (root == null) return;

    applyModelInsightsFromProps(root, expectedOwner);
    applyContextInsightsFromProps(root);
    applyVisionCapabilityFromProps(root);
    applyBuildInsightsFromProps(root, expectedOwner);
    applyReasoningCapabilityFromProps(root);
    applyExternalAdoptionInsightsFromProps(root, expectedOwner);
  }

  /**
   * Tempdoc 835 §5.2 signal 2 — records the running build's chat-template capabilities. This is a
   * <em>secondary</em> signal on purpose: b8571's {@code chat_template_caps} carries
   * {@code supports_preserve_reasoning} but no {@code supports_enable_thinking}, so per-request
   * thinking support is not advertised and cannot be read here. Launch-argument acceptance
   * ({@code LlamaServerOps}) remains the authoritative verdict; this only says "the build is
   * reasoning-aware". De-duplicated so repeated {@code /props} reads do not spam.
   */
  private void applyReasoningCapabilityFromProps(JsonNode root) {
    boolean capsPresent = hasChatTemplateCaps(root);
    boolean preserveReasoning = supportsPreserveReasoning(root);
    String signature = capsPresent + "|" + preserveReasoning;
    if (!signature.equals(lastReasoningCapsLogged.getAndSet(signature))) {
      LOG.info(
          "llama-server chat-template capabilities: chat_template_caps={}, "
              + "supports_preserve_reasoning={} (recorded, not gating)",
          capsPresent,
          preserveReasoning);
    }
  }

  /** True when {@code /props} carries a {@code chat_template_caps} object at all. */
  static boolean hasChatTemplateCaps(JsonNode root) {
    return root != null && root.path("chat_template_caps").isObject();
  }

  /** True when the build advertises {@code chat_template_caps.supports_preserve_reasoning}. */
  static boolean supportsPreserveReasoning(JsonNode root) {
    return root != null
        && root.path("chat_template_caps").path("supports_preserve_reasoning").asBoolean(false);
  }

  /**
   * Tempdoc 682 Item 2: records the actually-running llama-server build ({@code build_info}
   * from {@code /props}) and warns LOUDLY when it drifts from the staged expectation (the
   * {@code runtime-version.txt} marker next to the configured executable). Missing marker or
   * missing {@code build_info} is a supported unknown — recorded, never warned about. The
   * warn is de-duplicated per (expected, actual) pair so repeated {@code /props} reads of
   * the same drifted server do not spam.
   */
  private void applyBuildInsightsFromProps(
      JsonNode root, LlamaServerOps.StartResult expectedOwner) {
    String actual = LlamaServerBuildCheck.actualFromProps(root);
    if (actual != null) {
      observedServerBuild.set(actual);
    }
    LlamaServerBuildCheck.BuildComparison cmp =
        LlamaServerBuildCheck.compare(
            expectedServerBuild(expectedOwner.context()), observedServerBuild.get());
    if (cmp.mismatch()) {
      String pair = cmp.expected() + "|" + cmp.actual();
      if (!pair.equals(lastBuildMismatchWarned.getAndSet(pair))) {
        LOG.warn(
            "llama-server build drift: expected {} (runtime-version.txt pin next to {}) but the"
                + " running server reports {}. Behavior differences (flag semantics, /props"
                + " shape, sampling defaults) may stem from this. Re-stage the pinned runtime"
                + " or update the pin intentionally.",
            cmp.expected(),
            configuredServerExecutable(expectedOwner.context()),
            cmp.actual());
      }
    }
  }

  /**
   * Expected llama-server build tag from the staging pin marker adjacent to the configured
   * executable; null (= unknown) when no config, no marker, or an unparseable marker — the
   * supported externally-started/adopted-server case.
   */
  String expectedServerBuild(LlamaServerConfigContext context) {
    return LlamaServerBuildCheck.readExpectedNextTo(
        configuredServerExecutable(context));
  }

  /** Actually-running llama-server build tag observed from {@code /props}; null until observed. */
  String actualServerBuild() {
    return observedServerBuild.get();
  }

  private Path configuredServerExecutable(LlamaServerConfigContext context) {
    return context.inference().serverExecutable();
  }

  private void applyModelInsightsFromProps(
      JsonNode root, LlamaServerOps.StartResult expectedOwner) {
    try {
      String modelId = extractModelIdFromProps(root);
      if (modelId != null && !modelId.isBlank()) {
        propsObserver.onModelIdObserved(modelId, expectedOwner.context());
        LOG.info("llama-server model: {}", modelId);
        warnIfThinkingMismatch(modelId, expectedOwner);
      }
    } catch (Exception e) {
      LOG.debug("updateFromPropsBestEffort: model extraction failed: {}", e.getMessage());
    }
  }

  private void warnIfThinkingMismatch(
      String modelId, LlamaServerOps.StartResult expectedOwner) {
    boolean thinkingEnabled = expectedOwner.context().resolved().ai().useThinking();
    boolean modelLooksThinking =
        modelId.toLowerCase(java.util.Locale.ROOT).contains("thinking");
    if (thinkingEnabled && !modelLooksThinking) {
      LOG.warn(
          "USE_THINKING is enabled but loaded model '{}' does not appear to be a Thinking variant. "
              + "Reasoning features (reasoning_content in SSE) may not work.",
          modelId);
    }
  }

  /**
   * Records the window the server reports and checks it against what we launched.
   *
   * <p>Caveat that bounds what this method can prove (tempdoc 883 fold [R1]): {@code /props.n_ctx}
   * reports the server's TOTAL context even when {@code kv_unified} is off, in which case each
   * request actually gets {@code n_ctx / n_parallel}. So a matching {@code n_ctx} is NOT evidence
   * that a request gets the full window. The guarantee for that is the argv — {@code -kvu} always
   * accompanying an explicit {@code -np}, pinned by the ordered launch-command test — plus reading
   * {@code n_ctx_seq} from the llama-server log in the live acceptance window. Do not add a check
   * here that claims to verify it; the field this method reads cannot.
   */
  private void applyContextInsightsFromProps(JsonNode root) {
    Integer actualContextSize = extractContextTokensFromProps(root);
    if (actualContextSize != null && actualContextSize > 0) {
      propsObserver.onContextTokensObserved(actualContextSize);
      LOG.info("llama-server context size: {} tokens", actualContextSize);
      warnOnContextWindowMismatch(actualContextSize);
      // Tempdoc 883 decision 3 — there used to be a second warning here, comparing a hardcoded
      // 3000-token "summarization budget" against this window and naming a SummaryController that
      // no longer exists. Every consumer budget is now DERIVED from this observed window
      // (ContextBudget), so a consumer constant that exceeds it is unrepresentable and there is
      // nothing left to warn about.
    } else {
      LOG.debug("llama-server /props did not include a parseable n_ctx value");
    }
  }

  private void applyVisionCapabilityFromProps(JsonNode root) {
    boolean vision = root.path("modalities").path("vision").asBoolean(false);
    hasVisionCapability.set(vision);
    if (vision) {
      LOG.info("llama-server reports vision capability (mmproj loaded)");
    }
  }

  /** Returns true if the last /props response indicated vision support. */
  boolean hasVisionCapability() {
    return hasVisionCapability.get();
  }

  private void applyExternalAdoptionInsightsFromProps(
      JsonNode root, LlamaServerOps.StartResult expectedOwner) {
    if (expectedOwner.disposition() != LlamaServerOps.StartDisposition.ADOPTED_EXTERNAL) {
      return;
    }

    externalServerModelId.set(propsObserver.observedModelId());
    externalServerContextTokens.set(propsObserver.observedContextTokens());
    boolean looksLike = looksLikeLlamaServerProps(root);
    externalServerVerified.set(looksLike);
    if (looksLike) {
      externalServerVerificationError.set(null);
    } else if (externalServerVerificationError.get() == null) {
      externalServerVerificationError.set("props_missing_expected_fields");
    }
    // Set adoption timestamp if not already set (e.g., test-only path via reflection).
    externalServerAdoptedAtMs.compareAndSet(0, System.currentTimeMillis());
    externalServerModelMismatch.set(detectExternalModelMismatch(root, expectedOwner));
    Integer ctx = propsObserver.observedContextTokens();
    externalServerContextTooSmall.set(isAdoptedContextTooSmall(ctx));
  }

  /**
   * Whether an ADOPTED external server's window is too small to work with.
   *
   * <p>Compared against the minimum usable window, not against our own configured one. Tempdoc 883
   * made the configured value a DERIVED ladder rung (32768 on a GPU box), and a BYO llama-server
   * running at a perfectly workable 8192 is not broken merely because it is smaller than the rung
   * we would have chosen for a server we launched ourselves. Judging someone else's server by our
   * preference would mark almost every adopted server "too small".
   *
   * <p>{@link ContextWindowPolicy#MIN_USABLE_ADOPTED_TOKENS} is the bottom rung of the ladder —
   * the smallest window this app is willing to run its own engine at, and therefore the honest
   * floor for one it adopts.
   */
  static boolean isAdoptedContextTooSmall(Integer observedContextTokens) {
    return observedContextTokens != null
        && observedContextTokens < ContextWindowPolicy.MIN_USABLE_ADOPTED_TOKENS;
  }

  // ==================== Model/Context Extraction ====================

  private String extractModelIdFromProps(JsonNode root) {
    JsonNode alias = root.get("model_alias");
    if (alias != null && alias.isTextual() && !alias.asText().isBlank()) {
      return alias.asText();
    }
    return extractModelPathFileName(root.get("model_path"), "Failed to extract model filename: {}");
  }

  private String extractModelPathFileName(JsonNode modelPathNode, String logOnFailureTemplate) {
    if (modelPathNode == null || !modelPathNode.isTextual() || modelPathNode.asText().isBlank()) {
      return null;
    }
    try {
      return Path.of(modelPathNode.asText()).getFileName().toString();
    } catch (Exception e) {
      if (logOnFailureTemplate != null) {
        LOG.debug(logOnFailureTemplate, e.getMessage());
      }
      return null;
    }
  }

  /**
   * Tempdoc 883: {@code /props} stays the authority for what window the server actually has, so a
   * disagreement with what THIS process asked for is a real condition — but the comparand has to be
   * the requested rung, not {@code config.contextSize()}.
   *
   * <p>The configured value is stale by construction once the launch ladder steps down (config
   * still says 32768 while the server was started at 16384), so comparing against it would fire a
   * spurious warning on every successful step-down — a warning that is wrong every time it appears
   * teaches operators to ignore the one time it is right.
   *
   * <p>No new state is published here: {@code /api/inference/status} already carries the intent
   * ({@code contextWindow.rung}) and the observation ({@code llmContextTokens}) as separate fields
   * from their own authorities, so the mismatch is derivable and does not need a third copy.
   */
  private void warnOnContextWindowMismatch(int actualContextSize) {
    int requested = requestedContextTokens.getAsInt();
    if (!isContextWindowMismatch(requested, actualContextSize)) {
      return;
    }
    LOG.warn(
        "Context window mismatch: launched with -c {} but llama-server reports n_ctx {}. Requests"
            + " budgeted against the larger number may fail with 400s; the /props value is the"
            + " authority.",
        requested,
        actualContextSize);
  }

  /**
   * True when the server reports a smaller window than the launch asked for.
   *
   * <p>Pure and package-private so the COMPARAND is testable: the defect this replaced was not the
   * comparison but what it compared against. A {@code requestedRung} of 0 means this process
   * launched nothing (an adopted external server), so there is no claim of ours to contradict and
   * adoption diagnostics own the case.
   */
  static boolean isContextWindowMismatch(int requestedRung, int actualContextSize) {
    return requestedRung > 0 && actualContextSize < requestedRung;
  }

  private boolean detectExternalModelMismatch(
      JsonNode root, LlamaServerOps.StartResult expectedOwner) {
    String externalName =
        extractModelPathFileName(root.get("model_path"), "Model mismatch detection failed: {}");
    if (externalName == null || externalName.isBlank()) {
      return false;
    }
    String configuredName =
        expectedOwner.context().inference().modelPath().getFileName().toString();
    return !externalName.equalsIgnoreCase(configuredName);
  }

  // ==================== Static Parsing Utilities ====================

  /**
   * Extracts llama-server context size (n_ctx) from the /props JSON.
   *
   * <p>Different llama-server versions nest n_ctx in different places. We look in:
   *
   * <ul>
   *   <li>{@code n_ctx}
   *   <li>{@code default_generation_settings.n_ctx}
   *   <li>{@code default_generation_settings.params.n_ctx}
   *   <li>{@code params.n_ctx}
   * </ul>
   *
   * @return n_ctx, or null if not found / not parseable
   */
  static Integer extractContextTokensFromProps(JsonNode root) {
    if (root == null) return null;

    Integer direct = asPositiveInt(root.get("n_ctx"));
    if (direct != null) return direct;

    JsonNode dgs = root.get("default_generation_settings");
    if (dgs != null) {
      Integer nested = asPositiveInt(dgs.get("n_ctx"));
      if (nested != null) return nested;
      JsonNode params = dgs.get("params");
      if (params != null) {
        Integer nestedParams = asPositiveInt(params.get("n_ctx"));
        if (nestedParams != null) return nestedParams;
      }
    }

    JsonNode params = root.get("params");
    if (params != null) {
      Integer nestedParams = asPositiveInt(params.get("n_ctx"));
      if (nestedParams != null) return nestedParams;
    }

    return null;
  }

  static Integer asPositiveInt(JsonNode node) {
    if (node == null || node.isNull()) return null;
    try {
      int value;
      if (node.isInt() || node.isLong() || node.isNumber()) {
        value = node.asInt();
      } else if (node.isTextual()) {
        value = Integer.parseInt(node.asText().trim());
      } else {
        return null;
      }
      return value > 0 ? value : null;
    } catch (Exception e) {
      LOG.debug("asPositiveInt: parsing failed: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Checks whether a /props JSON response looks like it came from a llama-server. Returns {@code
   * true} if the root contains a model_alias, model_path, or positive n_ctx.
   */
  static boolean looksLikeLlamaServerProps(JsonNode root) {
    if (root == null) return false;
    Integer ctx = extractContextTokensFromProps(root);
    JsonNode alias = root.get("model_alias");
    if (alias != null && alias.isTextual() && !alias.asText().isBlank()) return true;
    JsonNode modelPath = root.get("model_path");
    if (modelPath != null && modelPath.isTextual() && !modelPath.asText().isBlank()) return true;
    return ctx != null && ctx > 0;
  }

  // ==================== External Diagnostics State ====================

  /**
   * Resets all external server adoption diagnostics to initial state. Called when adopting a new
   * external server.
   */
  void resetExternalAdoptionState(boolean verified, String verificationError) {
    hasVisionCapability.set(false);
    // Tempdoc 682 Item 2: a newly-adopted server is a different process — clear the previous
    // build observation (and re-arm the drift warn) so stale versions don't survive adoption.
    observedServerBuild.set(null);
    lastBuildMismatchWarned.set(null);
    externalServerAdoptedAtMs.set(System.currentTimeMillis());
    externalServerVerified.set(verified);
    externalServerVerificationError.set(verificationError);
    externalServerModelId.set(null);
    externalServerContextTokens.set(null);
    externalServerModelMismatch.set(false);
    externalServerContextTooSmall.set(false);
  }

  /**
   * Builds a snapshot of external server diagnostics for API exposure. Periodic health monitoring
   * fields are passed in because they remain owned by {@link LlamaServerOps}.
   */
  InferenceLifecycleManager.ExternalServerDiagnostics buildExternalDiagnostics(
      boolean usingExternal,
      long lastPeriodicHealthOkAtMs,
      String lastPeriodicHealthError,
      int consecutiveHealthFailures) {
    return new InferenceLifecycleManager.ExternalServerDiagnostics(
        usingExternal,
        externalServerVerified.get(),
        externalServerVerificationError.get(),
        externalServerModelId.get(),
        externalServerContextTokens.get(),
        externalServerModelMismatch.get(),
        externalServerContextTooSmall.get(),
        externalServerAdoptedAtMs.get(),
        lastPeriodicHealthOkAtMs,
        lastPeriodicHealthError,
        consecutiveHealthFailures);
  }
}
