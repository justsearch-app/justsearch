/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.VramFlagsUtil;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.settings.SettingsWitness;
import io.justsearch.app.api.status.InferenceGpuView;
import io.justsearch.app.api.status.InferenceStatusResponseBuilder;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.ComponentRecoveryAuthority;
import io.justsearch.core.component.ComponentHandle;
import io.justsearch.core.component.ComponentState;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.EnterprisePolicyService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator handling inference-related HTTP endpoints.
 *
 * <p>Extracted from {@link LocalApiServer} to reduce class size. Handles all endpoints registered
 * via {@link io.justsearch.ui.api.routes.InferenceRoutes}.
 */
final class InferenceHandlers {
  private static final Logger log = LoggerFactory.getLogger(InferenceHandlers.class);

  private final OnlineAiService onlineAiService;
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private final Supplier<ComponentRecoveryAuthority> componentRecovery;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; nvidia-smi availability
  // is read from gpuCapabilitiesService.snapshot().nvidiaSmi().available().
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final EnterprisePolicyService enterprisePolicyService;
  private final io.justsearch.app.services.settings.UiSettingsStore settingsStore;
  private final Telemetry telemetry;
  // Tempdoc 656 O2: nullable — lets a failed online-mode transition project a SPECIFIC reason onto
  // the runtime manifest's ai.pendingReason (the mode-transition path otherwise shows generic
  // "Inference offline"; Tasks 0-5 wired only the RuntimeActivationService path).
  private final ComponentHandle generativeComponent;
  // Tempdoc 737 fix pack (fix 4): the ONE runtime-intent authority for the /api/inference/mode
  // route. When present, handleSetInferenceMode records the chat-enabled intent through it (spec
  // write + reconciler nudge) instead of a raw onlineAi.switchTo* — removing the second dispatch
  // authority that bypassed the reconciler (§12b). Nullable for legacy test seams (HeadAssembly
  // absent); those fall back to the raw path.
  private final BrainRuntimeService brainRuntimeService;

  InferenceHandlers(
      OnlineAiService onlineAiService,
      KnowledgeServerBootstrap knowledgeServer,
      GpuCapabilitiesService gpuCapabilitiesService,
      EnterprisePolicyService enterprisePolicyService,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      Telemetry telemetry,
      ComponentHandle generativeComponent,
      BrainRuntimeService brainRuntimeService,
      Supplier<ComponentRecoveryAuthority> componentRecovery) {
    this.onlineAiService = onlineAiService;
    this.knowledgeServer = knowledgeServer;
    this.gpuCapabilitiesService = gpuCapabilitiesService;
    this.enterprisePolicyService = enterprisePolicyService;
    this.settingsStore = settingsStore;
    this.telemetry = telemetry;
    this.generativeComponent = generativeComponent;
    this.brainRuntimeService = brainRuntimeService;
    this.componentRecovery = Objects.requireNonNull(componentRecovery, "componentRecovery");
  }

  /** Late-binds the Knowledge Server after async Worker startup. */
  void setKnowledgeServer(KnowledgeServerBootstrap ks) {
    this.knowledgeServer = ks;
  }

  /** Schedules one same-configuration component recovery through the registered owner. */
  void handleRecoverComponent(Context ctx) {
    ComponentRecoveryAuthority authority = componentRecovery.get();
    if (authority == null) {
      ctx.status(503).json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE,
          "Component recovery is still initializing", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    String name = ctx.pathParam("name");
    ComponentRecoveryAuthority.Outcome verdict = authority.requestComponentRecovery(name);
    switch (verdict) {
      case ACCEPTED -> ctx.status(202).json(Map.of("success", true, "component", name,
          "recovery", verdict.name()));
      case ALREADY_RUNNING -> ctx.status(429).json(ApiErrorHandler.toResponse(
          ApiErrorCode.ADMISSION_ENGINE_LIMIT, "A component recovery is already running",
          telemetry, ApiErrorHandler.routeOf(ctx)));
      case EXHAUSTED -> ctx.status(503).json(ApiErrorHandler.toResponse(
          ApiErrorCode.WORKER_RECOVERY_EXHAUSTED,
          "Component recovery budget is spent; restart the application to retry",
          telemetry, ApiErrorHandler.routeOf(ctx)));
      case NOT_APPLICABLE -> ctx.status(409).json(ApiErrorHandler.toResponse(
          ApiErrorCode.INVALID_STATE, "Component does not need recovery",
          telemetry, ApiErrorHandler.routeOf(ctx)));
      case UNKNOWN_COMPONENT -> ctx.status(404).json(ApiErrorHandler.toResponse(
          ApiErrorCode.NOT_FOUND, "Unknown Engine component: " + name,
          telemetry, ApiErrorHandler.routeOf(ctx)));
      case OWNER_UNAVAILABLE -> ctx.status(503).json(ApiErrorHandler.toResponse(
          ApiErrorCode.SERVICE_UNAVAILABLE, "Component has no available local recovery owner",
          telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Handles GET /api/inference/status - returns current inference mode and queue sizes.
   *
   * <p>Tempdoc 663 §L/Stage 4 — builds the typed {@link InferenceStatusResponse} record instead of
   * a hand-built {@code Map}. Every field/condition below is unchanged from the prior Map-based
   * version (see git history if diffing behavior); only the assembly mechanism changed.
   */
  void handleInferenceStatus(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    OnlineAiService onlineAi = onlineAiService;
    InferenceStatusResponseBuilder builder = InferenceStatusResponseBuilder.builder()
        .mode(onlineAi.getCurrentMode())
        .available(onlineAi.isAvailable())
        .starting(onlineAi.isStartingUp())
        .llmContextTokens(onlineAi.llmContextTokens())
        .configuredContextTokens(onlineAi.configuredContextTokens())
        .embeddingQueueSize(countPendingEmbeddings(engineContext))
        .vduQueueSize(countPendingVdu(engineContext));

    // External server adoption diagnostics, CUDA warnings, and startup timer (best-effort; additive fields).
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        var ext = introspection.externalServerStatus();
        if (ext != null) {
          builder.externalServer(ext);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        String cudaWarning = introspection.cudaRuntimeWarning();
        if (cudaWarning != null && !cudaWarning.isBlank()) {
          builder.cudaRuntimeWarning(cudaWarning);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        long startupMs = introspection.lastStartupDurationMs();
        if (startupMs >= 0) {
          builder.lastStartupDurationMs(startupMs);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        builder.hasVisionCapability(introspection.hasVisionCapability());
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        String modelId = introspection.activeModelId();
        if (modelId != null && !modelId.isBlank()) {
          builder.activeModelId(modelId);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        // Tempdoc 883: the window this process launched with and why. Absent (not zero) when it
        // launched nothing — an adopted external server's window is not ours to claim.
        var window = introspection.contextWindow();
        if (window != null) {
          builder.contextWindow(window);
        }
      } catch (Exception ignored) {
        // best-effort
      }
      try {
        // Tempdoc 518 Appendix F W3.3 — generation counter (frontend detects mid-session restart).
        long gen = introspection.currentGeneration();
        if (gen >= 0) {
          builder.generation(gen);
        }
      } catch (Exception ignored) {
        // best-effort
      }
    }

    // Hardware capabilities. Tempdoc 374 alpha.14 fix P1-A: drive
    // `cudaAvailable` and `vramDescription` from the NVML-first effective
    // capability snapshot rather than the legacy nvidia-smi shell-out probe.
    // Pre-alpha.14 this endpoint reported `cudaAvailable: false` and
    // `vramDescription: "Unknown (nvidia-smi not available)"` on every host
    // without nvidia-smi.exe on PATH — including the round-5 sandbox where
    // chat was actually running on GPU at 64 tok/s with 8.6 GB VRAM held.
    // The legacy `nvidia-smi` probe and the `nvidiaSmiAvailable` field stay,
    // both as a diagnostic signal and to preserve frontend compatibility for
    // callers that explicitly want the legacy answer.
    // Tempdoc 374 alpha.27: read nvidia-smi availability from the snapshot's
    // nvidiaSmi() accessor instead of a direct VramDetector reference. The
    // snapshot's NvidiaSmi probe runs the same nvidia-smi shell-out internally.
    GpuCapabilities snapshot = null;
    try {
      snapshot = gpuCapabilitiesService.snapshot();
    } catch (Exception ignored) {
      // best-effort
    }
    boolean nvidiaSmiAvailable =
        snapshot != null && snapshot.nvidiaSmi() != null && snapshot.nvidiaSmi().available();
    boolean cudaAvailable = snapshot != null
        && snapshot.effective() != null
        && snapshot.effective().cudaAvailable();
    Long effectiveVramBytes = snapshot != null && snapshot.effective() != null
        ? snapshot.effective().totalVramBytes()
        : null;
    String vramDescription = effectiveVramBytes != null
        ? String.format(
            java.util.Locale.ROOT,
            "%.1f GB",
            effectiveVramBytes / (1024.0 * 1024.0 * 1024.0))
        : "Unknown";
    String vramDetectionSource = snapshot != null && snapshot.effective() != null
        ? snapshot.effective().source()
        : (nvidiaSmiAvailable ? "nvidia-smi" : "none");

    boolean nvmlAvailable = false;
    Long nvmlTotalVramBytes = null;
    String nvmlDriverVersion = null;
    try {
      var nvml = snapshot != null ? snapshot.nvml() : null;
      if (nvml != null) {
        nvmlAvailable = nvml.available();
        if (nvmlAvailable) {
          nvmlTotalVramBytes = nvml.totalVramBytes();
          nvmlDriverVersion = nvml.driverVersion();
        }
      }
    } catch (Exception ignored) {
      nvmlAvailable = false;
    }

    // Tempdoc 623 U7: record the pinned CUDA major (a constant — no ORT init) so the
    // benchmark-release hardware projection can publish it. The ORT *version* is captured
    // WORKER-side (where ORT is initialized) and surfaced via the health effective_config map
    // into /api/debug/state — NOT here, because the Head runs no ORT sessions. Best-effort.
    String cudaVersion;
    try {
      cudaVersion = io.justsearch.ort.OrtCudaHelper.CUDA_TOOLKIT_MAJOR;
    } catch (Exception ignored) {
      // constant lookup is informational only.
      cudaVersion = null;
    }

    builder.gpu(new InferenceGpuView(
        cudaAvailable,
        effectiveVramBytes,
        vramDescription,
        vramDetectionSource,
        nvidiaSmiAvailable,
        nvmlAvailable,
        nvmlTotalVramBytes,
        nvmlDriverVersion,
        cudaVersion));

    // computeHardwareTier still wants a VRAM number; pass the effective bytes
    // (NVML-first, with nvidia-smi fallback handled internally by the snapshot).
    // Tempdoc 374 alpha.27: dropped the explicit smiVramBytes path — the snapshot's
    // effective() value is already the merged NVML-first / smi-fallback reading.
    Long smiOnly = snapshot != null && snapshot.nvidiaSmi() != null
        ? snapshot.nvidiaSmi().totalVramBytes()
        : null;
    long tierVramBytes = effectiveVramBytes != null ? effectiveVramBytes
        : (smiOnly != null ? smiOnly : -1L);
    builder.tier(computeHardwareTier(tierVramBytes, onlineAi.isAvailable(), onlineAi.isStartingUp()));

    ctx.json(builder.build());
  }

  /**
   * Handles GET /api/inference/transitions - returns the N most recent mode transitions
   * (success + failure) recorded by the runtime's transition-history ring buffer.
   *
   * <p>Tempdoc 518 Appendix F W3.2. Query param: {@code limit} (default 10, max 20). Response
   * shape: {@code {"transitions": [{timestampMs, fromMode, toMode, reason, success, durationMs,
   * wireCode}, ...]}}.
   */
  void handleInferenceTransitions(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    int limit = 10;
    try {
      String raw = ctx.queryParam("limit");
      if (raw != null && !raw.isBlank()) {
        limit = Integer.parseInt(raw);
      }
    } catch (NumberFormatException ignored) {
      // best-effort; default to 10
    }
    if (limit < 0) limit = 0;
    if (limit > 20) limit = 20;

    List<Map<String, Object>> transitions = new ArrayList<>();
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        for (io.justsearch.app.api.OnlineAiRuntimeIntrospection.TransitionRecord rec :
            introspection.recentTransitions(limit)) {
          Map<String, Object> row = new HashMap<>();
          row.put("timestampMs", rec.timestampMs());
          row.put("fromMode", rec.fromMode());
          row.put("toMode", rec.toMode());
          row.put("reason", rec.reason());
          row.put("success", rec.success());
          row.put("durationMs", rec.durationMs());
          if (rec.wireCode() != null) {
            row.put("wireCode", rec.wireCode());
          }
          transitions.add(row);
        }
      } catch (Exception ignored) {
        // best-effort
      }
    }
    ctx.json(Map.of("transitions", transitions));
  }

  /**
   * Handles GET /api/inference/failures - returns the N most recent inference-runtime failures
   * recorded by the {@link io.justsearch.app.api.OnlineAiRuntimeIntrospection} ring buffer.
   *
   * <p>Tempdoc 518 Appendix F W2.1. Query param: {@code limit} (default 5, max 20). Response
   * shape: <code>{"failures": [{timestampMs, category, wireCode, detail}, ...]}</code>.
   */
  void handleInferenceFailures(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    int limit = 5;
    try {
      String raw = ctx.queryParam("limit");
      if (raw != null && !raw.isBlank()) {
        limit = Integer.parseInt(raw);
      }
    } catch (NumberFormatException ignored) {
      // best-effort; default to 5
    }
    if (limit < 0) limit = 0;
    if (limit > 20) limit = 20;

    List<Map<String, Object>> failures = new ArrayList<>();
    if (onlineAi instanceof io.justsearch.app.api.OnlineAiRuntimeIntrospection introspection) {
      try {
        for (io.justsearch.app.api.OnlineAiRuntimeIntrospection.FailureRecord rec :
            introspection.recentFailures(limit)) {
          Map<String, Object> row = new HashMap<>();
          row.put("timestampMs", rec.timestampMs());
          row.put("category", rec.category());
          row.put("wireCode", rec.wireCode());
          row.put("detail", rec.detail());
          failures.add(row);
        }
      } catch (Exception ignored) {
        // best-effort; return whatever we accumulated
      }
    }
    ctx.json(Map.of("failures", failures));
  }

  /**
   * Handles GET /api/gpu/capabilities - returns NVML-first GPU capability snapshot (with fallback
   * details).
   */
  void handleGpuCapabilities(Context ctx) {
    try {
      ctx.json(gpuCapabilitiesService.snapshot());
    } catch (Exception e) {
      log.warn("Failed to compute GPU capabilities (best-effort)", e);
      ctx.status(500)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.GPU_CAPABILITIES_FAILED, "Failed to compute GPU capabilities", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  static String computeHardwareTier(
      long totalVramBytes, boolean onlineAvailable, boolean onlineStarting) {
    // If we can't detect VRAM, distinguish CPU-only vs GPU-unknown based on whether Online AI is
    // running.
    if (totalVramBytes < 0) {
      return (onlineAvailable || onlineStarting) ? "gpu_unknown" : "cpu_only";
    }

    // Delegate to VramFlagsUtil for consistent tier detection across codebase.
    String tier = VramFlagsUtil.detectVramTier(totalVramBytes);
    return switch (tier) {
      case "12gb_plus" -> "gpu_12gb_plus";
      case "8gb" -> "gpu_8gb";
      case "4gb", "under_4gb" -> "gpu_lt_8gb";
      default -> "gpu_unknown";
    };
  }

  /** Handles POST /api/inference/mode - switches between online and indexing modes. */
  @SuppressWarnings("unchecked")
  void handleSetInferenceMode(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    Map<String, Object> body;
    try {
      body = ctx.bodyAsClass(Map.class);
    } catch (Exception e) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_JSON, "Invalid JSON body", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    String mode = body != null && body.get("mode") instanceof String value ? value : null;
    if (mode == null || mode.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Missing 'mode' field", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    Object suppliedKey = body.get("idempotencyKey");
    if (suppliedKey != null && !(suppliedKey instanceof String)) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST,
          "idempotencyKey must be a string", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    // Tempdoc 737 fix pack (fix 4): route through the ONE runtime-intent authority. This is an
    // intent write (chatEnabled spec + reconciler nudge), NOT a raw mode switch — so it never
    // bypasses the reconciler and cannot re-introduce the §3b circular denial. Policy / GPU
    // enforcement is a convergence ceiling inside the reconciler, not an intent-time 4xx denial.
    BrainRuntimeService brainRuntime = this.brainRuntimeService;
    if (brainRuntime != null) {
      try {
        ctx.json(modeTransitionPayload(brainRuntime.switchInferenceMode(mode, RequestEngineContext.get(ctx),
            (String) suppliedKey)));
      } catch (io.justsearch.agent.api.registry.OperationPreparationRefused e) {
        writeIntentRefusal(ctx, e.refusal());
      } catch (io.justsearch.app.api.settings.SettingsCommitOwner.Refused e) {
        writeIntentRefusal(ctx, e.response());
      } catch (io.justsearch.app.api.operations.OperationStoreException e) {
        var response = io.justsearch.app.api.registry.OperationInvocationResponse.fromStoreFailure(e);
        writeIntentRefusal(ctx, io.justsearch.agent.api.registry.OperationResult.failure(
            response.message(), response.errorCode(), Map.of(), Boolean.TRUE.equals(response.retryable())));
      } catch (IllegalArgumentException e) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST,
            e.getMessage() == null ? "Invalid mode" : e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
      } catch (Exception e) {
        log.error("Failed to record inference-mode intent: {}", mode, e);
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        Map<String, Object> payload = ApiErrorHandler.toResponse(
            ApiErrorCode.MODE_SWITCH_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
        payload.put("mode", mode);
        ctx.status(500).json(payload);
      }
      return;
    }

    // Legacy/test seam (no BrainRuntimeService wired): retain the raw path + rich failure mapping.
    // The accepted settings identity fences a delayed failure from an intervening disable/re-enable
    // pair whose final field values happen to equal the values at request start.
    SettingsWitness requestWitness =
        settingsStore == null ? null : settingsStore.inspect().witness();
    try {
      if ("online".equalsIgnoreCase(mode)) {
        try {
          if (enterprisePolicyService != null
              && !enterprisePolicyService.snapshot().onlineAiEnabled()) {
            Map<String, Object> policyErr = ApiErrorHandler.toResponse(ApiErrorCode.POLICY_ONLINE_AI_DISABLED, "Online AI is disabled by administrator policy.", telemetry, ApiErrorHandler.routeOf(ctx));
            policyErr.put("mode", mode);
            ctx.status(403).json(policyErr);
            return;
          }
        } catch (Exception ignored) {
          // best-effort; do not weaken enforcement elsewhere
        }
        onlineAi.switchToOnlineMode();
      } else if ("indexing".equalsIgnoreCase(mode)) {
        onlineAi.switchToIndexingMode();
      } else {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Invalid mode. Use 'online' or 'indexing'", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      ctx.json(modeTransitionPayload(ModeTransitionOutcome.of(mode, onlineAi.getCurrentMode())));
    } catch (Exception e) {
      log.error("Failed to switch inference mode to: {}", mode, e);
      String msg = e.getMessage() != null ? e.getMessage() : e.toString();

      // Check cause chain for typed ModeTransitionException (wrapped by OnlineAiServiceImpl)
      ModeTransitionException mte = findCause(e, ModeTransitionException.class);

      if ("online".equalsIgnoreCase(mode)) {
        reportModeFailure(requestWitness, mte);
      }
      if (mte != null
          && mte.reason() == ModeTransitionException.Reason.EXTERNAL_SERVER_POLICY_BLOCKED) {
        Map<String, Object> payload = ApiErrorHandler.toResponse(
            ApiErrorCode.POLICY_EXTERNAL_SERVER_DISALLOWED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
        payload.put("mode", mode);
        ctx.status(403).json(payload);
        return;
      }

      Map<String, Object> payload = ApiErrorHandler.toResponse(
          ApiErrorCode.MODE_SWITCH_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx));
      payload.put("mode", mode);
      payload.put("causes", buildCauseChain(e));

      // Precondition failures (missing model, insufficient VRAM) are caller-fixable
      // configuration issues, not server faults. Return 412 so middleware/clients
      // don't retry as if it's a transient 5xx and so the UI can render a sticky
      // "fix this configuration" banner.
      int statusCode = 500;
      if (mte != null) {
        ModeTransitionException.Reason reason = mte.reason();
        if (reason == ModeTransitionException.Reason.INVALID_CONFIG
            || reason == ModeTransitionException.Reason.INSUFFICIENT_VRAM) {
          statusCode = 412;
        }
      }
      ctx.status(statusCode).json(payload);
    }
  }

  /**
   * Tempdoc 804 §B6: the success payload of {@code POST /api/inference/mode}.
   *
   * <p>{@code success} is retained with its narrow meaning — the intent write itself succeeded —
   * and {@code mode} stays the live mode, so existing callers are unaffected. What the shape used
   * to lack is the relation between the two: the transition is asynchronous, so a live {@code mode}
   * read taken at return time is not the transition's result. {@code requested} + {@code state}
   * ({@code recorded} | {@code converged}) say that outright instead of leaving
   * {@code {"success":true,"mode":"indexing"}} to be misread as a successful switch to ONLINE
   * (round-10 evidence).
   */
  private static Map<String, Object> modeTransitionPayload(ModeTransitionOutcome outcome) {
    var payload = new java.util.LinkedHashMap<String, Object>(Map.of(
        "success", true,
        "requested", outcome.requested() == null ? "" : outcome.requested(),
        "mode", outcome.mode() == null ? "" : outcome.mode(),
        "state", outcome.state()));
    if (outcome.operationKey() != null) payload.put("operationKey", outcome.operationKey());
    if (outcome.acceptedRevision() != null) payload.put("acceptedRevision", outcome.acceptedRevision());
    return payload;
  }

  private void writeIntentRefusal(Context ctx, io.justsearch.agent.api.registry.OperationResult refusal) {
    String code = refusal.errorCode().orElse("SETTINGS_RECOVERY_REQUIRED");
    int status = switch (code) {
      case "INVALID_REQUEST", "OPERATION_KEY_INVALID" -> 400;
      case "SETTINGS_READ_ONLY", "VERSION_CONFLICT", "RECONFIGURE_IN_PROGRESS",
          "OPERATION_KEY_EXPIRED", "OPERATION_KEY_REUSED", "OPERATION_PREPARATION_UNAVAILABLE" -> 409;
      case "SETTINGS_RECOVERY_REQUIRED", "OPERATIONS_CAPACITY" -> 503;
      default -> 500;
    };
    ApiErrorCode classification = switch (code) {
      case "SETTINGS_READ_ONLY" -> ApiErrorCode.SETTINGS_READ_ONLY;
      case "INVALID_REQUEST", "OPERATION_KEY_INVALID", "VERSION_CONFLICT", "OPERATION_KEY_EXPIRED",
          "OPERATION_KEY_REUSED", "OPERATION_PREPARATION_UNAVAILABLE" -> ApiErrorCode.INVALID_REQUEST;
      case "RECONFIGURE_IN_PROGRESS", "OPERATIONS_CAPACITY" -> ApiErrorCode.SERVICE_UNAVAILABLE;
      default -> ApiErrorCode.INVALID_STATE;
    };
    var payload = ApiErrorHandler.toResponse(classification, refusal.message(), telemetry, ApiErrorHandler.routeOf(ctx));
    payload.put("errorCode", code);
    payload.put("retryable", refusal.retryable().orElse(false));
    for (String field : List.of("operationKey", "operationRecordId")) {
      Object value = refusal.structuredData().get(field);
      if (value != null) payload.put(field, value);
    }
    ctx.status(status).json(payload);
  }

  /**
   * Tempdoc 656 O2: maps a mode-transition failure {@link ModeTransitionException.Reason} onto the
   * closed {@link LifecycleReasonCode} taxonomy, reusing the inference codes added in Tasks 0-5 (no
   * new code, so no readiness-reason-codes gate change). Mirrors
   * {@code RuntimeActivationService.mapToLifecycleReason}. A null/untyped failure (or any unmapped
   * reason) falls back to the activation-failed catch-all.
   */
  private static LifecycleReasonCode mapModeReason(ModeTransitionException mte) {
    if (mte == null) return LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED;
    return switch (mte.reason()) {
      // "the llama-server executable / a required DLL is missing" — the runtime isn't installed.
      case EXECUTABLE_NOT_FOUND, MISSING_DLL -> LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      // config validation "executable/model not found" also reduces to runtime-not-installed for the
      // user's purposes (the doctor's next remedy is the same: provision the runtime).
      case INVALID_CONFIG, CONFIG_REQUIRED -> LifecycleReasonCode.INFERENCE_RUNTIME_NOT_INSTALLED;
      default -> LifecycleReasonCode.INFERENCE_ACTIVATION_FAILED;
    };
  }

  private void reportModeFailure(SettingsWitness requestWitness, ModeTransitionException failure) {
    if (generativeComponent == null || settingsStore == null || requestWitness == null) {
      return;
    }
    while (true) {
      var expected = generativeComponent.snapshot();
      var settings = settingsStore.inspect();
      if (!requestWitness.equals(settings.witness())
          || !Boolean.TRUE.equals(settings.settings().getChatEnabled())
          || expected.state() == ComponentState.ABSENT
          || expected.state() == ComponentState.READY) {
        return;
      }
      LifecycleReasonCode reason = mapModeReason(failure);
      ComponentState state = modePrerequisiteFailure(failure)
          ? ComponentState.UNAVAILABLE : ComponentState.FAILED;
      if (generativeComponent.transitionIfUnchanged(
          expected, state, reason.code(), "inference mode transition failed")) {
        return;
      }
    }
  }

  private static boolean modePrerequisiteFailure(ModeTransitionException failure) {
    if (failure == null) {
      return false;
    }
    return switch (failure.reason()) {
      case EXECUTABLE_NOT_FOUND,
          MISSING_DLL,
          INVALID_CONFIG,
          CONFIG_REQUIRED,
          INSUFFICIENT_VRAM,
          EXTERNAL_SERVER_POLICY_BLOCKED -> true;
      default -> false;
    };
  }

  /**
   * Handles POST /api/inference/detach - detaches from an adopted external llama-server instance (if
   * any) and starts a managed llama-server on a new free port.
   */
  void handleDetachExternalInferenceServer(Context ctx) {
    OnlineAiService onlineAi = onlineAiService;
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      ctx.status(503)
          .json(ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Inference runtime control unavailable", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }

    try {
      if (enterprisePolicyService != null
          && !enterprisePolicyService.snapshot().onlineAiEnabled()) {
        ctx.status(403)
            .json(
                ApiErrorHandler.toResponse(ApiErrorCode.POLICY_ONLINE_AI_DISABLED, "Online AI is disabled by administrator policy.", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
    } catch (Exception ignored) {
      // best-effort; do not weaken enforcement elsewhere
    }

    try {
      OnlineAiRuntimeControl.DetachExternalServerResult r = control.detachExternalServer();
      ctx.json(
          Map.of(
              "success",
              true,
              "detached",
              r.detached(),
              "previousPort",
              r.previousPort(),
              "newPort",
              r.newPort(),
              "mode",
              onlineAi.getCurrentMode()));
    } catch (Exception e) {
      log.error("Failed to detach external inference server", e);
      String msg = e.getMessage();
      if (msg == null || msg.isBlank()) {
        msg = e.toString();
      }
      ctx.status(500).json(ApiErrorHandler.toResponse(ApiErrorCode.INFERENCE_DETACH_FAILED, msg, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** One-release tombstone response for the retired Worker restart endpoint. */
  void handleRetiredWorkerRestartTombstone(Context ctx) {
    ctx.status(410).json(ApiErrorHandler.toResponse(ApiErrorCode.ENDPOINT_RETIRED,
        "This endpoint is retired; use POST /api/engine/components/index/recover",
        telemetry, ApiErrorHandler.routeOf(ctx)));
  }

  /**
   * Counts documents with pending embedding status.
   *
   * <p>Uses Knowledge Server gRPC to query the index. Falls back to 0 if unavailable.
   */
  private int countPendingEmbeddings(EngineContext engineContext) {
    KnowledgeServerBootstrap server = knowledgeServer;
    if (server == null || !server.isReady()) {
      return 0;
    }
    try (var lease = server.captureClient()) {
      return lease.withClient(client -> client.countPendingEmbeddings(engineContext));
    } catch (Exception e) {
      log.debug("Failed to count pending embeddings", e);
      return 0;
    }
  }

  /**
   * Counts documents with pending VDU status.
   *
   * <p>Uses Knowledge Server gRPC to query the index. Falls back to 0 if unavailable.
   */
  private int countPendingVdu(EngineContext engineContext) {
    KnowledgeServerBootstrap server = knowledgeServer;
    if (server == null || !server.isReady()) {
      return 0;
    }
    try (var lease = server.captureClient()) {
      return lease.withClient(client -> client.countPendingVdu(engineContext));
    } catch (Exception e) {
      log.debug("Failed to count pending VDU", e);
      return 0;
    }
  }

  /** Walks the cause chain looking for an exception of the given type (max 10 levels). */
  @SuppressWarnings("unchecked")
  private static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      if (type.isInstance(cur)) {
        return (T) cur;
      }
      cur = cur.getCause();
      depth++;
    }
    return null;
  }

  /** Builds a compact cause chain for error diagnostics (max 10 levels). */
  private static List<Map<String, Object>> buildCauseChain(Throwable e) {
    List<Map<String, Object>> causes = new ArrayList<>();
    Throwable cur = e;
    int depth = 0;
    while (cur != null && depth < 10) {
      Map<String, Object> c = new HashMap<>();
      c.put("type", cur.getClass().getName());
      c.put("message", cur.getMessage());
      causes.add(c);
      cur = cur.getCause();
      depth++;
    }
    return causes;
  }

  // ==========================================================================
  // BrainRuntimeService impl (slice 3a-2-c continuation).
  //
  // Re-uses the same OnlineAiRuntimeControl + OnlineAiService + settingsStore
  // dependencies the HTTP handlers above rely on. Throws on error rather than
  // writing to a Context — Operation handlers map the exception's message
  // into OperationResult.failure; the existing HTTP handlers retain their
  // typed-exception → status-code mapping.
  // ==========================================================================
  // BrainRuntimeService impl moved to io.justsearch.app.services.brainruntime.BrainRuntimeServiceImpl
  // (tempdoc 519 §9 Step 3).
}
