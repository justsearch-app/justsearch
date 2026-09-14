/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.SettingsService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.api.settings.SettingsV2;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.telemetry.Telemetry;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** HTTP projection and request attribution; the accepted settings service owns every mutation. */
public class SettingsController {
  static final String UI_MODE_INTENT_HEADER = "X-JustSearch-UI-Mode-Intent";
  private static final ObjectMapper MAPPER =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  private final UiSettingsStore settingsStore;
  private final Path defaultIndexBasePath;
  private final Telemetry telemetry;
  private final SettingsService settingsService;

  /** Read-only fallback; missing application composition never creates an unrecorded writer. */
  public SettingsController(UiSettingsStore store, Path defaultIndexBasePath, Telemetry telemetry) {
    this(store, defaultIndexBasePath, telemetry, null);
  }

  public SettingsController(UiSettingsStore store, Path defaultIndexBasePath,
      Telemetry telemetry, SettingsService settingsService) {
    this.settingsStore = store;
    this.defaultIndexBasePath = defaultIndexBasePath;
    this.telemetry = telemetry;
    this.settingsService = settingsService;
  }

  /**
   * GET /api/settings/v2 - Returns the canonical {@link SettingsV2} shape.
   */
  public void handleGetSettingsV2(Context ctx) {
    try {
      var snapshot = settingsStore.inspect();
      UiSettings settings = snapshot.settings();
      if ((settings.getIndexBasePath() == null || settings.getIndexBasePath().isBlank())
          && defaultIndexBasePath != null) {
        settings.setIndexBasePath(defaultIndexBasePath.toString());
      }
      ctx.json(io.justsearch.app.services.settings.SettingsV2Projection.toSettingsV2(
          settings, settingsStore.mode(), snapshot.witness()));
    } catch (io.justsearch.configuration.persistence.CorruptDurableStoreException
        | io.justsearch.configuration.persistence.UnsupportedStoreVersionException
        | java.io.UncheckedIOException failure) {
      var payload = ApiErrorHandler.toResponse(ApiErrorCode.INVALID_STATE,
          "Settings recovery is required before a revision can be read", telemetry, ApiErrorHandler.routeOf(ctx));
      payload.put("errorCode", "SETTINGS_RECOVERY_REQUIRED");
      payload.put("retryable", false);
      ctx.status(503).json(payload);
    }
  }

  /** POST /api/settings/v2 preserves the logical attempt's witness and key. */
  public void handleUpdateSettingsV2(Context ctx) {
    final SettingsV2 incoming;
    try {
      incoming = MAPPER.readValue(ctx.body(), SettingsV2.class);
    } catch (tools.jackson.core.JacksonException malformed) {
      writeRefusal(ctx, OperationResult.failure("Invalid settings format", "INVALID_REQUEST", Map.of(), false));
      return;
    }
    if (settingsService == null) {
      writeRefusal(ctx, OperationResult.failure(
          "Settings commit owner is unavailable", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
      return;
    }
    try {
      var result = settingsService.applyPublic(incoming, ctx.header(UI_MODE_INTENT_HEADER), RequestEngineContext.get(ctx));
      var data = new java.util.LinkedHashMap<String, Object>(result.response().structuredData());
      data.put("operationKey", result.record().key());
      data.put("operationRecordId", result.record().id());
      data.put("state", result.record().state().name());
      if (!result.response().success()) {
        data.remove("witness");
        if (result.response().errorCode().filter("OPERATION_STORAGE_FAILED"::equals).isPresent()) data.remove("state");
        writeRefusal(ctx, new OperationResult(false, result.response().message(), java.util.Optional.empty(), data,
            result.response().errorCode(), Map.of(), result.response().retryable()));
        return;
      }
      if (result.record().state() != io.justsearch.app.api.operations.OperationState.COMPLETE) {
        data.remove("witness");
        data.remove("acceptedRevision");
        ctx.status(202).json(MAPPER.convertValue(data, SettingsV2.class));
      } else {
        ctx.json(MAPPER.convertValue(data, SettingsV2.class));
      }
    } catch (io.justsearch.agent.api.registry.OperationPreparationRefused failure) {
      writeRefusal(ctx, failure.refusal());
    } catch (io.justsearch.app.api.settings.SettingsCommitOwner.Refused failure) {
      writeRefusal(ctx, failure.response());
    } catch (io.justsearch.app.api.operations.OperationStoreException failure) {
      var refusal = io.justsearch.app.api.registry.OperationInvocationResponse.fromStoreFailure(failure);
      writeRefusal(ctx, OperationResult.failure(refusal.message(), refusal.errorCode(), Map.of(),
          Boolean.TRUE.equals(refusal.retryable())));
    } catch (IllegalArgumentException invalid) {
      writeRefusal(ctx, OperationResult.failure("Invalid settings request", "INVALID_REQUEST", Map.of(), false));
    } catch (RuntimeException failure) {
      org.slf4j.LoggerFactory.getLogger(SettingsController.class).error("Settings update failed", failure);
      writeRefusal(ctx, OperationResult.failure(
          "Settings update could not complete", "SETTINGS_RECOVERY_REQUIRED", Map.of(), false));
    }
  }

  private void writeRefusal(Context ctx, OperationResult refusal) {
    String code = refusal.errorCode().orElse("SETTINGS_RECOVERY_REQUIRED");
    int status = switch (code) {
      case "INVALID_REQUEST", "INVALID_PATH", "OPERATION_KEY_INVALID" -> 400;
      case "SETTINGS_READ_ONLY", "VERSION_CONFLICT", "RECONFIGURE_IN_PROGRESS",
          "OPERATION_KEY_EXPIRED", "OPERATION_KEY_REUSED", "OPERATION_PREPARATION_UNAVAILABLE" -> 409;
      case "SETTINGS_RECOVERY_REQUIRED", "OPERATIONS_CAPACITY" -> 503;
      default -> 500;
    };
    ApiErrorCode classification = switch (code) {
      case "SETTINGS_READ_ONLY" -> ApiErrorCode.SETTINGS_READ_ONLY;
      case "INVALID_PATH" -> ApiErrorCode.INVALID_PATH;
      case "RECONFIGURE_IN_PROGRESS", "OPERATIONS_CAPACITY" -> ApiErrorCode.SERVICE_UNAVAILABLE;
      case "SETTINGS_RECOVERY_REQUIRED" -> ApiErrorCode.INVALID_STATE;
      case "INVALID_REQUEST", "OPERATION_KEY_INVALID", "VERSION_CONFLICT", "OPERATION_KEY_EXPIRED",
          "OPERATION_KEY_REUSED", "OPERATION_PREPARATION_UNAVAILABLE" -> ApiErrorCode.INVALID_REQUEST;
      default -> ApiErrorCode.INTERNAL_ERROR;
    };
    var payload = ApiErrorHandler.toResponse(classification, refusal.message(), telemetry, ApiErrorHandler.routeOf(ctx));
    payload.put("errorCode", code);
    payload.put("retryable", refusal.retryable().orElse(false));
    for (String field : java.util.List.of("operationKey", "operationRecordId", "state")) {
      Object value = refusal.structuredData().get(field);
      if (value != null) payload.put(field, value);
    }
    ctx.status(status).json(payload);
  }
}
