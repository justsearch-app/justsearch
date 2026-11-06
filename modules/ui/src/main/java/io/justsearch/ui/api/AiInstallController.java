/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.BrainInstallService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.settings.UiSettingsStore;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * v2 AI install controller — hardware-aware, package-based.
 *
 * <p>Same 5 endpoints as v1, same URLs. Holds the {@code app-api} {@link AiInstallService} interface,
 * not the {@code app-services} implementation: everything an HTTP handler here needs is on the
 * contract. The one thing that was not — the Worker late-bind — was never a controller concern; it is
 * a composition-root one, and {@code LocalApiServer.lateBindKnowledgeServer} now calls the service
 * directly instead of forwarding through this class.
 */
public final class AiInstallController {
  private static final Logger log = LoggerFactory.getLogger(AiInstallController.class);
  private static final JsonMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final AiInstallService service;
  private final Telemetry telemetry;

  public AiInstallController(AiInstallService service, Telemetry telemetry) {
    this.service = service;
    this.telemetry = telemetry;
  }

  public void handleGetManifest(Context ctx) {
    try {
      ctx.json(service.getManifest());
    } catch (Exception e) {
      log.error("Failed to load AI install manifest", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.MANIFEST_UNAVAILABLE,
                  "Failed to load manifest",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getStatus());
  }

  /**
   * Side-effect-free per-tier weight preview (tempdoc 657). Drives the honest first-run download
   * breakdown in the UI before the user commits.
   */
  public void handleGetPlanPreview(Context ctx) {
    try {
      // "Download again" previews the plan with these parts included; no saved choice changes.
      String include = ctx.queryParam("include");
      java.util.Set<String> parts = include == null || include.isBlank() ? java.util.Set.of()
          : java.util.Arrays.stream(include.split(",")).map(String::trim)
              .filter(id -> !id.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
      ctx.json(parts.isEmpty() ? service.previewInstallPlan() : service.previewInstallPlan(parts));
    } catch (Exception e) {
      log.error("Failed to compute AI install plan preview", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.MANIFEST_UNAVAILABLE,
                  "Failed to compute install plan preview",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  /** Advanced settings - Downloaded files: measured sizes and "Remove files" eligibility (design D4). */
  public void handleGetDownloadedFiles(Context ctx) {
    try {
      ctx.json(service.downloadedFiles());
    } catch (Exception e) {
      log.error("Failed to read downloaded AI files", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.AI_INSTALL_ERROR,
                  "Failed to read downloaded files",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleStart(Context ctx) {
    boolean acceptTerms = parseAcceptTerms(ctx);
    try {
      service.startInstall(acceptTerms);
      ctx.json(service.getStatus());
    } catch (AiInstallException e) {
      ctx.status(e.httpStatus())
          .json(
              ApiErrorHandler.toResponse(
                  e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_START_FAILED,
                  "Failed to start install",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleCancel(Context ctx) {
    try {
      service.cancel();
      ctx.json(service.getStatus());
    } catch (Exception e) {
      log.error("Failed to cancel AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_CANCEL_FAILED,
                  "Failed to cancel install",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleRepair(Context ctx) {
    boolean acceptTerms = parseAcceptTerms(ctx);
    try {
      service.repair(acceptTerms);
      ctx.json(service.getStatus());
    } catch (AiInstallException e) {
      ctx.status(e.httpStatus())
          .json(
              ApiErrorHandler.toResponse(
                  e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to repair AI install", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INSTALL_REPAIR_FAILED,
                  "Failed to repair",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Halts an in-flight install between files (tempdoc 840 Phase 4). A paused run keeps its op-lease
   * and its place in the set, so this is not a cancel and the response still reads {@code
   * state: "running"} — {@code paused: true} is the bit that changed.
   */
  public void handlePause(Context ctx) {
    withStatus(ctx, service::pauseInstall, ApiErrorCode.INSTALL_NOT_RUNNING, "Failed to pause install");
  }

  /** Continues a paused install at its next file. */
  public void handleResume(Context ctx) {
    withStatus(
        ctx, service::resumeInstall, ApiErrorCode.INSTALL_NOT_RUNNING, "Failed to resume install");
  }

  /** Records the user's decision not to install one component. */
  public void handleDeclinePackage(Context ctx) {
    setDeclined(ctx, true);
  }

  /** Withdraws that decision — the component is offered again. */
  public void handleAcceptPackage(Context ctx) {
    setDeclined(ctx, false);
  }

  private void setDeclined(Context ctx, boolean declined) {
    String packageId = ctx.pathParam("packageId");
    withStatus(
        ctx,
        () -> service.setPackageDeclined(packageId, declined),
        ApiErrorCode.AI_INSTALL_ERROR,
        "Failed to record the component choice");
  }

  /**
   * The family's handler shape, factored once: run the mutation, answer with the post-call status,
   * and map a typed {@link AiInstallException} onto its own status code and error code. The
   * {@code fallback} pair is only for the untyped path — an exception the service did not classify
   * must still leave a typed body behind, which is the round-16 lesson this family already carries.
   */
  private void withStatus(
      Context ctx, Runnable mutation, ApiErrorCode fallbackCode, String fallbackMessage) {
    try {
      mutation.run();
      ctx.json(service.getStatus());
    } catch (AiInstallException e) {
      ctx.status(e.httpStatus())
          .json(
              ApiErrorHandler.toResponse(
                  e.errorCode(), e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("{}", fallbackMessage, e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  fallbackCode, fallbackMessage, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  private static boolean parseAcceptTerms(Context ctx) {
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("acceptTerms")) {
        return root.get("acceptTerms").asBoolean(false);
      }
    } catch (Exception ignored) {
      // tolerate empty body
    }
    return false;
  }

  // ==========================================================================
  // BrainInstallService impl (slice 3a-2-c continuation).
  //
  // Mirrors handleStart / handleCancel / handleRepair's delegate path,
  // returning the post-call status as a Map<String, Object> so the app-api
  // interface doesn't leak the modules/ui-side AiInstallStatus type. HTTP
  // handlers retain typed-error → status-code mapping (AiInstallException
  // carries its own httpStatus + errorCode).
  // ==========================================================================
  // BrainInstallService impl moved to io.justsearch.app.services.braininstall.BrainInstallServiceImpl
  // (tempdoc 519 §9 Step 3).
}
