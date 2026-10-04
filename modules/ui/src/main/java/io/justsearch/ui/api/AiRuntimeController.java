/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP routing layer for runtime-variant activation endpoints. Service-impl logic for
 * {@code RuntimeVariantService} lives in
 * {@code io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl}
 * (tempdoc 519 §9 Step 3).
 *
 * <p>Tempdoc 737 (task 3): no longer holds its own {@code EnterprisePolicyService} — admin-policy
 * enforcement is the single {@link RuntimeActivationService#enforceActivationPolicy()} site.
 */
public final class AiRuntimeController implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(AiRuntimeController.class);
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final RuntimeActivationService service;
  private final Telemetry telemetry;
  private final boolean ownsService;

  public AiRuntimeController(RuntimeActivationService service, Telemetry telemetry) {
    this(service, telemetry, false);
  }

  AiRuntimeController(RuntimeActivationService service, Telemetry telemetry, boolean ownsService) {
    this.service = service;
    this.telemetry = telemetry;
    this.ownsService = ownsService;
  }

  @Override
  public void close() {
    if (ownsService) service.close();
  }

  public void handleGetStatus(Context ctx) {
    ctx.json(service.getStatus());
  }

  public void handleActivate(Context ctx) {
    String variantId = null;
    // Tempdoc 842 §2.4: optional chat-model profile. Absent (or an unparseable body, which the
    // pre-842 code already tolerated) leaves the activation flow byte-for-byte unchanged.
    String chatProfile = null;
    try {
      JsonNode root = MAPPER.readTree(ctx.body());
      if (root != null && root.has("variantId")) {
        variantId = root.get("variantId").asText(null);
      }
      if (root != null && root.has("chatProfile")) {
        chatProfile = root.get("chatProfile").asText(null);
      }
    } catch (Exception ignored) {
      // tolerate
    }
    if (variantId == null || variantId.isBlank()) {
      ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.VARIANT_ID_REQUIRED, "Missing variantId", telemetry, ApiErrorHandler.routeOf(ctx)));
      return;
    }
    // A non-blank profile that names nothing is rejected here rather than handed to
    // ChatModelProfile.resolve, whose warn-fallback to STANDARD is deliberate for BOOT flags (a bad
    // launch flag must not brick the engine) but is exactly the wrong answer for a request: a typo
    // would silently activate the standard 9B, the outcome tempdoc 842 exists to prevent.
    if (chatProfile != null && !chatProfile.isBlank() && !isKnownChatProfile(chatProfile)) {
      ctx.status(400)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.INVALID_REQUEST,
                  "Unknown chatProfile '"
                      + chatProfile
                      + "'. Valid values: "
                      + String.join(", ", knownChatProfileIds())
                      + ".",
                  telemetry,
                  ApiErrorHandler.routeOf(ctx)));
      return;
    }

    // Tempdoc 737 (task 3): the policy predicate itself lives once on
    // RuntimeActivationService.enforceActivationPolicy (same site runActivate's async path and
    // RuntimeVariantServiceImpl's operation-handler path call) — this stays a synchronous
    // fast-fail adapter so the HTTP caller gets an immediate 403 instead of having to poll
    // status to discover an async policy denial.
    try {
      service.enforceActivationPolicy();
    } catch (IllegalStateException e) {
      String msg = e.getMessage() == null ? "" : e.getMessage();
      ApiErrorCode code =
          msg.contains("GPU acceleration")
              ? ApiErrorCode.POLICY_GPU_DISABLED
              : ApiErrorCode.POLICY_ONLINE_AI_DISABLED;
      ctx.status(403)
          .json(ApiErrorHandler.toResponse(code, msg, telemetry, "/api/ai/runtime/activate"));
      return;
    }

    try {
      service.startActivate(variantId, chatProfile);
      ctx.json(service.getStatus());
    } catch (IllegalStateException e) {
      ctx.status(409).json(ApiErrorHandler.toResponse(ApiErrorCode.RUNTIME_ACTIVATION_RUNNING, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start runtime activation", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.RUNTIME_ACTIVATION_START_FAILED, "Failed to start runtime activation", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  public void handleDeactivate(Context ctx) {
    try {
      service.startDeactivate();
      ctx.json(service.getStatus());
    } catch (IllegalStateException e) {
      ctx.status(409).json(ApiErrorHandler.toResponse(ApiErrorCode.RUNTIME_ACTIVATION_RUNNING, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      log.error("Failed to start runtime deactivation", e);
      ctx.status(500)
          .json(
              ApiErrorHandler.toResponse(
                  ApiErrorCode.RUNTIME_DEACTIVATION_START_FAILED, "Failed to start runtime deactivation", telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Mirrors {@link ChatModelProfile#resolve(String)}'s normalization (trim, lowercase, underscores
   * to hyphens, enum names accepted) so a value this predicate accepts resolves to the profile the
   * caller meant. {@code resolve} itself stays lenient by design — this is the request-time guard.
   */
  private static boolean isKnownChatProfile(String raw) {
    String norm = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    for (String id : knownChatProfileIds()) {
      if (id.equals(norm)) {
        return true;
      }
    }
    for (ChatModelProfile p : ChatModelProfile.values()) {
      if (p.name().toLowerCase(Locale.ROOT).replace('_', '-').equals(norm)) {
        return true;
      }
    }
    return false;
  }

  /** The profile ids, plus the {@code qwen-vl} legacy alias {@code resolve} still honors. */
  private static List<String> knownChatProfileIds() {
    List<String> ids = new ArrayList<>();
    for (ChatModelProfile p : ChatModelProfile.values()) {
      ids.add(p.id());
    }
    ids.add("qwen-vl");
    return ids;
  }

  // RuntimeVariantService interface impl moved to
  // io.justsearch.app.services.runtimevariant.RuntimeVariantServiceImpl
  // (tempdoc 519 §9 Step 3). LocalApiServer constructs both this controller and the impl.
}
