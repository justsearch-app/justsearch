/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.app.api.status.OrtCudaView;
import io.justsearch.ort.EncoderRole;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-function derivation of an {@link EncoderRuntimeView} from the (policy snapshot,
 * OrtCudaView) pair produced by {@code RemoteKnowledgeClient} (tempdoc 422).
 *
 * <p>Implements the explainer decision tree from tempdoc 422 §3 Path A: maps the configured
 * accelerator (from the policy's {@code variant.executionProvider}) and the runtime probe
 * outcome (from {@link OrtCudaView}) to a stable {@code currentAccelerator} string + a
 * human-readable explanation.
 *
 * <p>Defensive against missing/malformed policy JSON: the policy sub-map is decoupled from
 * compile-time types per §14.28 U4, so this explainer must not crash if the JSON shape evolves.
 *
 * <p>Defer (per tempdoc 422 sequencing): CPU-forced-mode honest reporting (case 5 below)
 * still uses the slightly-imperfect "until first inference" wording when configured for GPU
 * but not yet attempted; a follow-up will plumb the assembler's accelerator-decision through
 * a typed channel.
 */
public final class EncoderRuntimeExplainer {

  public static final String ACCEL_CUDA = "cuda";
  public static final String ACCEL_CPU = "cpu";
  public static final String ACCEL_UNAVAILABLE = "unavailable";

  /** Observed execution provider when no runtime view is available at all. */
  public static final String EP_UNKNOWN = "unknown";

  /** Observed execution provider when the encoder is not part of the active configuration. */
  public static final String EP_NONE = "none";

  private EncoderRuntimeExplainer() {}

  /**
   * Derives a runtime view for every encoder named by the Worker's policy snapshot (tempdoc 805
   * G.3). Extracted from {@code EncoderRuntimeController} so the correlation of policy snapshot ×
   * OrtCuda probe lives in ONE place: {@code GET /api/inference/encoders} and {@code GET
   * /api/ai/runtime/status}'s observed-EP fields are two projections of this derivation, not two
   * derivations.
   *
   * @param sessionPolicies the {@code {configStatus, runtime, models}} map from {@code
   *     RemoteKnowledgeClient.getSessionPolicies()}
   * @param views per-role OrtCuda probe views from {@code
   *     RemoteKnowledgeClient.getEncoderOrtCudaViews()}
   * @return one view per role the policy snapshot names; empty when the snapshot carries no models
   *     (worker unreachable / policy unavailable — the caller decides how to report that)
   */
  public static Map<EncoderRole, EncoderRuntimeView> explainAll(
      Map<String, Object> sessionPolicies, Map<EncoderRole, OrtCudaView> views) {
    Map<EncoderRole, EncoderRuntimeView> out = new EnumMap<>(EncoderRole.class);
    if (sessionPolicies == null) return out;
    Object modelsNode = sessionPolicies.get("models");
    if (!(modelsNode instanceof Map<?, ?> modelsMap) || modelsMap.isEmpty()) return out;
    Map<EncoderRole, OrtCudaView> probes = views == null ? Map.of() : views;
    for (Map.Entry<?, ?> entry : modelsMap.entrySet()) {
      Object roleKey = entry.getKey();
      if (roleKey == null) continue;
      EncoderRole role = parseRole(roleKey.toString());
      if (role == null) continue;
      Map<String, Object> policySubMap = coercePolicy(entry.getValue());
      OrtCudaView view = probes.getOrDefault(role, OrtCudaView.notConfigured());
      out.put(role, explain(role, view, policySubMap));
    }
    return out;
  }

  /**
   * Maps the JSON policy key (uppercase enum-name shape per {@code
   * WorkerIngestService.getSessionPolicies}) to its {@link EncoderRole}; returns {@code null} if the
   * key isn't a known role (defensive — shouldn't happen given Worker's serialiser).
   */
  public static EncoderRole parseRole(String key) {
    if (key == null) return null;
    try {
      return EncoderRole.valueOf(key.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> coercePolicy(Object node) {
    if (node instanceof Map<?, ?>) {
      return (Map<String, Object>) node;
    }
    return Map.of();
  }

  /**
   * The OBSERVED execution-provider projection of an already-derived {@link EncoderRuntimeView}
   * (tempdoc 805 G.3 W-TRUTH). Round 11's defect was {@code /api/ai/runtime/status} reporting a
   * feature "active" from model-file discovery while its ORT session had silently fallen back to
   * CPU; this projection carries what actually happened beside that intent, without re-deriving it.
   *
   * @param executionProvider {@code "cuda"} | {@code "cpu"} | {@code "none"} | {@code "unknown"}
   * @param gpuFallback true when GPU was the configured intent but the session runs on CPU
   * @param fallbackReason concrete reason when {@code gpuFallback} — the probe's failure reason plus
   *     any missing DLL names; {@code null} otherwise
   */
  public record ObservedExecutionProvider(
      String executionProvider, boolean gpuFallback, String fallbackReason) {

    /** The state before any Worker runtime view is available (never a positive claim). */
    public static ObservedExecutionProvider unknown() {
      return new ObservedExecutionProvider(EP_UNKNOWN, false, null);
    }
  }

  /** Projects one derived view onto the observed-EP triple. */
  public static ObservedExecutionProvider observed(EncoderRuntimeView view) {
    if (view == null) return ObservedExecutionProvider.unknown();
    String current = view.currentAccelerator();
    String ep;
    if (ACCEL_CUDA.equals(current)) {
      ep = ACCEL_CUDA;
    } else if (ACCEL_CPU.equals(current)) {
      ep = ACCEL_CPU;
    } else if (ACCEL_UNAVAILABLE.equals(current)) {
      ep = EP_NONE;
    } else {
      ep = EP_UNKNOWN;
    }
    // "GPU was intended" reads the policy's own executionProvider, so a role that is CPU by design
    // (citation-scorer, EncoderRole.isCpuOnly) is never reported as a fallback.
    String configured = view.configuredAccelerator();
    boolean gpuIntended = !isBlank(configured) && !ACCEL_CPU.equalsIgnoreCase(configured);
    boolean fallback = ACCEL_CPU.equals(ep) && gpuIntended;
    return new ObservedExecutionProvider(ep, fallback, fallback ? summarizeReason(view) : null);
  }

  private static String summarizeReason(EncoderRuntimeView view) {
    OrtCudaView details = view.details();
    String base = isBlank(details.failureReason()) ? view.explanation() : details.failureReason();
    List<String> missing = details.missingDlls();
    if (missing != null && !missing.isEmpty()) {
      return base + " (missing: " + String.join(", ", missing) + ")";
    }
    return base;
  }

  /**
   * Derives a runtime view for one encoder.
   *
   * @param role encoder role (used only for log messages and policy null-handling cases)
   * @param view runtime OrtCuda probe view; {@code null} maps to {@link OrtCudaView#notConfigured()}
   * @param policySubMap raw {@code models[ROLE]} sub-map from
   *     {@code RemoteKnowledgeClient.getSessionPolicies()}; {@code null} when the role is not
   *     active in the current configuration
   */
  public static EncoderRuntimeView explain(
      EncoderRole role, OrtCudaView view, Map<String, Object> policySubMap) {
    OrtCudaView details = view == null ? OrtCudaView.notConfigured() : view;
    String configuredAccelerator = extractExecutionProvider(policySubMap);

    // Case 1: policy missing → encoder isn't part of the active configuration.
    if (policySubMap == null) {
      return new EncoderRuntimeView(
          ACCEL_UNAVAILABLE,
          configuredAccelerator,
          false,
          "Encoder not active in current configuration.",
          Map.of(),
          details);
    }

    // Case 2: policy explicitly opts into CPU.
    if ("CPU".equalsIgnoreCase(configuredAccelerator)) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "Encoder configured for CPU by design.",
          policySubMap,
          details);
    }

    // Case 3: GPU attempted and succeeded.
    if (details.attempted() && details.available()) {
      String arenaInfo = formatArenaInfo(policySubMap);
      String message =
          "GPU initialized successfully on CUDA device 0" + arenaInfo + ".";
      return new EncoderRuntimeView(
          ACCEL_CUDA, configuredAccelerator, true, message, policySubMap, details);
    }

    // Case 4: GPU attempted but failed → CPU fallback with a concrete reason.
    if (details.attempted() && !details.available() && !isBlank(details.failureReason())) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "GPU init failed: " + details.failureReason() + ". Running on CPU fallback.",
          policySubMap,
          details);
    }

    // Case 5: configured for GPU but not yet attempted (defer honest reporting per 422).
    if (!details.attempted() && details.configured()) {
      return new EncoderRuntimeView(
          ACCEL_CPU,
          configuredAccelerator,
          true,
          "GPU configured but not yet attempted; running on CPU until first inference.",
          policySubMap,
          details);
    }

    // Case 6: catch-all → CPU with a pointer to the raw policy snapshot.
    return new EncoderRuntimeView(
        ACCEL_CPU,
        configuredAccelerator,
        true,
        "GPU not active; running on CPU. See /api/debug/session-policies for raw policy.",
        policySubMap,
        details);
  }

  /**
   * Reads {@code policy.variant.executionProvider} defensively; returns {@code ""} if the path
   * is missing or shaped unexpectedly.
   */
  private static String extractExecutionProvider(Map<String, Object> policy) {
    if (policy == null) return "";
    Object variantNode = policy.get("variant");
    if (!(variantNode instanceof Map<?, ?> variantMap)) return "";
    Object ep = variantMap.get("executionProvider");
    return ep == null ? "" : ep.toString();
  }

  /**
   * Reads {@code policy.gpu.arenaCapBytes} defensively and formats as " arena cap N MB" if
   * present, otherwise returns the empty string.
   */
  private static String formatArenaInfo(Map<String, Object> policy) {
    if (policy == null) return "";
    Object gpuNode = policy.get("gpu");
    if (!(gpuNode instanceof Map<?, ?> gpuMap)) return "";
    Object cap = gpuMap.get("arenaCapBytes");
    if (cap == null) return "";
    long bytes;
    if (cap instanceof Number num) {
      bytes = num.longValue();
    } else {
      try {
        bytes = Long.parseLong(cap.toString());
      } catch (NumberFormatException e) {
        return "";
      }
    }
    if (bytes <= 0) return "";
    long mb = bytes / (1024L * 1024L);
    return "; arena cap " + mb + " MB";
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
