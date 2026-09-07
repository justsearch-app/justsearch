/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.inference.LlmServerException;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadApiTags;
import io.justsearch.ipc.CircuitBreakerOpenException;
import io.justsearch.ipc.KnowledgeServerNotConnectedException;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.telemetry.catalog.CounterMetric;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Utility for sanitizing API error responses.
 *
 * <p>Goals:
 * <ul>
 *   <li>Hide internal file paths from API consumers</li>
 *   <li>Map exceptions to user-friendly error codes</li>
 *   <li>Provide consistent error response format with error classification</li>
 * </ul>
 *
 * <p>The resolver maps exceptions to {@link ApiErrorCode} enum values, each carrying
 * an {@link io.justsearch.app.api.ErrorClass} that tells the frontend whether to
 * retry, abort, or escalate.
 */
public final class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    /**
     * Cache of error counters keyed by {@link LocalTelemetry} identity. Each {@code LocalTelemetry}
     * is a process-singleton (one Head, one Worker, one Launcher), so this map has at most a
     * handful of entries in production. Tempdoc 417 critical-analysis fix B2.
     */
    private static final Map<LocalTelemetry, CounterMetric<HeadApiTags.ApiErrorTags>>
            ERROR_COUNTER_CACHE = new ConcurrentHashMap<>();

    /**
     * Tracks {@link LocalTelemetry} instances for which we've already logged the
     * "no api.error.total definition registered" warning, so the warning fires once per instance
     * regardless of error rate.
     */
    private static final Set<LocalTelemetry> MISSING_DEFINITION_LOGGED =
            ConcurrentHashMap.newKeySet();

    // Pattern to match Windows paths like C:\Users\... or D:\code\...
    private static final Pattern WINDOWS_PATH = Pattern.compile("[A-Z]:\\\\[^\\s,\"]+");
    // Pattern to match Unix paths like /home/user/...
    private static final Pattern UNIX_PATH = Pattern.compile("/(?:home|usr|var|tmp|opt)[/][^\\s,\"]+");
    // Pattern to match internal class names
    private static final Pattern INTERNAL_CLASS = Pattern.compile("io\\.justsearch\\.[a-zA-Z.]+(?:Exception)?");

    private ApiErrorHandler() {
        // Utility class
    }

    // ── Typed API (uses ApiErrorCode enum) ─────────────────────────────────

    /**
     * Creates a sanitized error response map with typed error code.
     *
     * <p>Response includes {@code errorClass} and {@code retryable} fields for
     * data-driven frontend behavior.
     *
     * @param code the typed error code
     * @param e the exception (used for message extraction)
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId"
     */
    public static Map<String, Object> toResponse(ApiErrorCode code, Exception e) {
        String message = sanitizeMessage(e != null ? e.getMessage() : null);
        return buildTypedResponse(message, code);
    }

    /**
     * Creates a sanitized error response map with typed error code and custom message.
     *
     * @param code the typed error code
     * @param customMessage the message to use instead of extracting from exception
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId"
     */
    public static Map<String, Object> toResponse(ApiErrorCode code, String customMessage) {
        String message = sanitizeMessage(customMessage);
        return buildTypedResponse(message, code);
    }

    // ── Auto-recording overloads (build response + record telemetry) ────────

    /**
     * Creates a sanitized error response and records a telemetry counter.
     *
     * @param code the typed error code
     * @param e the exception (used for message extraction)
     * @param telemetry the telemetry instance (nullable — no-op if null)
     * @param route the API route (e.g. "/api/knowledge/search")
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId"
     */
    public static Map<String, Object> toResponse(
            ApiErrorCode code, Exception e, Telemetry telemetry, String route) {
        recordError(telemetry, code, route);
        return toResponse(code, e);
    }

    /**
     * Creates a sanitized error response with custom message and records a telemetry counter.
     *
     * @param code the typed error code
     * @param customMessage the message to use instead of extracting from exception
     * @param telemetry the telemetry instance (nullable — no-op if null)
     * @param route the API route (e.g. "/api/knowledge/search")
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId"
     */
    public static Map<String, Object> toResponse(
            ApiErrorCode code, String customMessage, Telemetry telemetry, String route) {
        recordError(telemetry, code, route);
        return toResponse(code, customMessage);
    }

    /**
     * Resolves an exception to a typed error response and records a telemetry counter.
     *
     * @param e the exception to convert
     * @param telemetry the telemetry instance (nullable — no-op if null)
     * @param route the API route (e.g. "/api/knowledge/search")
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId"
     */
    public static Map<String, Object> toResponse(
            Exception e, Telemetry telemetry, String route) {
        if (e == null) {
            ApiErrorCode code = ApiErrorCode.INTERNAL_ERROR;
            recordError(telemetry, code, route);
            return buildTypedResponse("Internal error", code);
        }
        ApiErrorCode code = resolve(e);
        recordError(telemetry, code, route);
        String message = sanitizeMessage(e.getMessage());
        return buildTypedResponse(message, code);
    }

    /**
     * Records an API error metric using a typed error code.
     *
     * <p>Tempdoc 417 Phase 3e: routes through the catalog ({@link HeadApiMetricCatalog#errorTotal})
     * when {@code telemetry} is a {@link LocalTelemetry} with the catalog DEFINITIONS registered.
     * Otherwise no-op. The legacy {@code Telemetry.counter} indirection is gone — the catalog
     * counter is the single emit path for {@code api.error.total}.
     *
     * <p>Phase 3 critical-analysis fix B2: caches the typed counter per {@link LocalTelemetry}
     * instance instead of constructing a fresh wrapper on each call. Logs a one-shot WARN if
     * the catalog DEFINITIONS aren't registered, so missed bootstrap wireup is visible at runtime
     * instead of silently dropping error metrics.
     *
     * @param telemetry the telemetry instance (nullable — no-op if null or non-LocalTelemetry)
     * @param code the typed error code
     * @param route the API route (e.g. "/api/knowledge/search")
     */
    // Note: all error classes (including VALIDATION) are recorded. Use the error_class
    // tag to filter dashboards (e.g. error_class != "VALIDATION" for operational alerts).
    public static void recordError(Telemetry telemetry, ApiErrorCode code, String route) {
        if (code == null || !(telemetry instanceof LocalTelemetry lt)) {
            return;
        }
        CounterMetric<HeadApiTags.ApiErrorTags> counter =
                ERROR_COUNTER_CACHE.computeIfAbsent(lt, ApiErrorHandler::buildErrorCounter);
        if (counter == null) {
            return; // catalog not registered on this LocalTelemetry — already logged once
        }
        try {
            counter.increment(
                    new HeadApiTags.ApiErrorTags(
                            code, code.errorClass(), route != null ? route : "unknown"));
        } catch (Exception ignored) {
            // best-effort — never fail the error response path
        }
    }

    /**
     * Builds the {@code api.error.total} counter against the supplied registry, or returns
     * {@code null} (and logs once) if the catalog DEFINITIONS aren't registered.
     */
    private static CounterMetric<HeadApiTags.ApiErrorTags> buildErrorCounter(LocalTelemetry lt) {
        try {
            return lt.registry().<HeadApiTags.ApiErrorTags>buildCounter(
                    HeadApiMetricCatalog.ERROR_TOTAL);
        } catch (Exception e) {
            if (MISSING_DEFINITION_LOGGED.add(lt)) {
                log.warn(
                        "api.error.total counter unavailable on this LocalTelemetry — register "
                                + "HeadApiMetricCatalog.DEFINITIONS at boot to enable error metrics. "
                                + "Cause: {}",
                        e.getMessage());
            }
            return null;
        }
    }

    /**
     * Test-only: clears the per-LocalTelemetry caches. Package-private so unit tests in the same
     * package can ensure isolation across test methods. Tempdoc 417 critical-analysis fix B2.
     */
    static void clearCachesForTest() {
        ERROR_COUNTER_CACHE.clear();
        MISSING_DEFINITION_LOGGED.clear();
    }

    /**
     * Maps an exception to a typed {@link ApiErrorCode}.
     *
     * <p>Maps exceptions to {@link ApiErrorCode} values using a priority chain:
     * typed exceptions first, then standard Java exceptions, then message hints.
     */
    public static ApiErrorCode resolve(Exception e) {
        if (e == null) {
            return ApiErrorCode.INTERNAL_ERROR;
        }

        // IndexRuntimeIOException: surface the specific reason
        if (e instanceof IndexRuntimeIOException ire) {
            return switch (ire.reason()) {
                case DISK_FULL -> ApiErrorCode.INDEX_DISK_FULL;
                case CORRUPT_INDEX -> ApiErrorCode.INDEX_CORRUPT;
                case LOCKED -> ApiErrorCode.INDEX_LOCKED;
                case SCHEMA_MISMATCH -> ApiErrorCode.INDEX_SCHEMA_MISMATCH;
                case BACKPRESSURE -> ApiErrorCode.INDEX_BACKPRESSURE;
                default -> ApiErrorCode.INDEX_ERROR; // DISK_IO, CONFIGURATION
            };
        }

        // Port failure: map the client status. Lane F review B1 — this replaced the
        // io.grpc.StatusRuntimeException branch, which became unreachable at item A6 (the client
        // stopped being a gRPC stub) and silently sent every worker error to INTERNAL_ERROR/500.
        // The mapping is carried across unchanged, minus NOT_FOUND, which no worker service emits.
        if (e instanceof KnowledgeClientException kce) {
            return switch (kce.status()) {
                case DEADLINE_EXCEEDED -> ApiErrorCode.TIMEOUT;
                case UNAVAILABLE -> ApiErrorCode.SERVICE_UNAVAILABLE;
                case INVALID_ARGUMENT -> ApiErrorCode.INVALID_REQUEST;
                case RESOURCE_EXHAUSTED -> ApiErrorCode.SERVICE_UNAVAILABLE;
                default -> ApiErrorCode.INTERNAL_ERROR;
            };
        }

        // LLM server HTTP error: classify by status code
        if (e instanceof LlmServerException lse) {
            return switch (lse.httpStatus()) {
                case 400 -> ApiErrorCode.CONTEXT_TOO_LARGE;
                case 429, 503 -> ApiErrorCode.LLM_OVERLOADED;
                default -> ApiErrorCode.INTERNAL_ERROR;
            };
        }

        // Knowledge Server not connected
        if (e instanceof KnowledgeServerNotConnectedException) {
            return ApiErrorCode.SERVICE_UNAVAILABLE;
        }

        // Circuit breaker open: service temporarily unavailable
        if (e instanceof CircuitBreakerOpenException) {
            return ApiErrorCode.SERVICE_UNAVAILABLE;
        }

        if (e instanceof UnsupportedOperationException) {
            return ApiErrorCode.NOT_SUPPORTED;
        }
        if (e instanceof IllegalArgumentException) {
            return ApiErrorCode.INVALID_REQUEST;
        }
        if (e instanceof IllegalStateException) {
            return ApiErrorCode.INVALID_STATE;
        }
        if (e instanceof java.util.concurrent.TimeoutException) {
            return ApiErrorCode.TIMEOUT;
        }
        if (e instanceof java.io.IOException) {
            return ApiErrorCode.IO_ERROR;
        }

        return ApiErrorCode.INTERNAL_ERROR;
    }

    /**
     * Resolves a string error code name to an {@link ApiErrorCode} enum value.
     * Falls back to {@link ApiErrorCode#INTERNAL_ERROR} if the name is null, blank, or unknown.
     */
    public static ApiErrorCode resolveByName(String name) {
        if (name == null || name.isBlank()) {
            return ApiErrorCode.INTERNAL_ERROR;
        }
        try {
            return ApiErrorCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown API error code name: '{}', falling back to INTERNAL_ERROR", name);
            return ApiErrorCode.INTERNAL_ERROR;
        }
    }

    // ── Legacy resolve-and-respond (no telemetry recording) ────────────────

    /**
     * Creates a sanitized error response map by resolving the exception to an error code.
     *
     * <p>Prefer the auto-recording overload {@code toResponse(Exception, Telemetry, String)}
     * when telemetry is available.
     *
     * @param e the exception to convert
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId" keys
     */
    public static Map<String, Object> toResponse(Exception e) {
        if (e == null) {
            return buildTypedResponse("An error occurred", ApiErrorCode.INTERNAL_ERROR);
        }
        ApiErrorCode code = resolve(e);
        String message = sanitizeMessage(e.getMessage());
        return buildTypedResponse(message, code);
    }

    /**
     * Creates a sanitized error response map with a custom message.
     *
     * @param e the exception for error code resolution
     * @param customMessage the message to use instead of e.getMessage()
     * @return map with "error", "errorCode", "errorClass", "retryable", and optional "requestId" keys
     */
    public static Map<String, Object> toResponse(Exception e, String customMessage) {
        ApiErrorCode code = resolve(e);
        String message = sanitizeMessage(customMessage);
        return buildTypedResponse(message, code);
    }

    // ── Message sanitization ────────────────────────────────────────────────

    /**
     * Removes sensitive information from error messages.
     */
    public static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "An error occurred";
        }

        String sanitized = message;

        // Replace file paths with [path]
        sanitized = WINDOWS_PATH.matcher(sanitized).replaceAll("[path]");
        sanitized = UNIX_PATH.matcher(sanitized).replaceAll("[path]");

        // Replace internal class references
        sanitized = INTERNAL_CLASS.matcher(sanitized).replaceAll("[internal]");

        // Trim result
        sanitized = sanitized.trim();

        // If the message became empty or just [path]/[internal], provide generic message
        if (sanitized.isBlank() || sanitized.equals("[path]") || sanitized.equals("[internal]")) {
            return "An error occurred";
        }

        return sanitized;
    }

    // ── gRPC → HTTP status mapping ─────────────────────────────────────────

    /**
     * Maps a gRPC status code to the most appropriate HTTP status code.
     *
     * @param code the gRPC status code (nullable — returns 500 if null)
     * @return the corresponding HTTP status code
     */
    /**
     * Maps a port failure onto its HTTP status.
     *
     * <p>Lane F review B1: this replaces {@code mapGrpcToHttp(io.grpc.Status.Code)}, which the
     * controllers called from catch blocks that stopped being reachable at item A6. The mapping is
     * <b>identical</b>, deliberately — the point of the fix is that the same failures get the same
     * answers again, not that the answers improve. So {@code ABORTED}, {@code UNIMPLEMENTED} and
     * {@code CANCELLED} fall to 500 exactly as they fell through the old {@code default} arm; if any
     * of them deserves a better status, that is a contract change with its own reasoning, not a
     * side effect of deleting a transport.
     */
    public static int mapClientStatusToHttp(KnowledgeClientException.Status status) {
        if (status == null) return 500;
        return switch (status) {
            case INVALID_ARGUMENT -> 400;
            case FAILED_PRECONDITION -> 409;
            case RESOURCE_EXHAUSTED -> 429;
            case UNAVAILABLE -> 503;
            case DEADLINE_EXCEEDED -> 504;
            default -> 500;
        };
    }

    // ── HTTP status derivation ──────────────────────────────────────────────

    /**
     * Maps a resolved {@link ApiErrorCode} to an HTTP status code.
     *
     * <p>Uses the code's {@link ErrorClass} for the default mapping, with specific
     * overrides for codes that have well-known HTTP semantics (e.g. {@code TIMEOUT → 504}).
     *
     * @param code the resolved error code (nullable — returns 500 if null)
     * @return the corresponding HTTP status code
     */
    public static int httpStatusFor(ApiErrorCode code) {
        if (code == null) return 500;
        return switch (code) {
            case TIMEOUT -> 504;
            case SERVICE_UNAVAILABLE, INDEX_UNAVAILABLE, AI_STARTING,
                 LLM_OVERLOADED, MANIFEST_UNAVAILABLE, SETTINGS_UNAVAILABLE -> 503;
            case NOT_FOUND -> 404;
            case SETTINGS_READ_ONLY -> 409;
            // Tempdoc 806 W1: an encrypted store with a locked key is 423 Locked — the status the
            // tempdoc-629 KeyLockedException mapping already emits and the shell already reads.
            case STORE_LOCKED -> 423;
            default -> switch (code.errorClass()) {
                case VALIDATION -> 400;
                case TRANSIENT -> 503;
                case POLICY -> 403;
                case PERMANENT -> 500;
            };
        };
    }

    // ── Route extraction ─────────────────────────────────────────────────────

    /**
     * Extracts the matched route pattern from Javalin's Context.
     *
     * <p>Returns the endpoint handler path (e.g. {@code "/api/knowledge/search"})
     * rather than the actual request path (which may contain path params).
     * Falls back to {@code "unknown"} if the path cannot be determined.
     */
    public static String routeOf(io.javalin.http.Context ctx) {
        try {
            return ctx.endpointHandlerPath();
        } catch (Exception e) {
            return "unknown";
        }
    }

    // ── Response builders ───────────────────────────────────────────────────

    private static Map<String, Object> buildTypedResponse(String message, ApiErrorCode code) {
        Map<String, Object> response = new LinkedHashMap<>(7);
        response.put("error", message);
        response.put("errorCode", code.name());
        response.put("errorClass", code.errorClass().name());
        response.put("retryable", code.isRetryable());
        response.put("i18nKey", "errors." + code.name());
        String requestId = MDC.get("request_id");
        if (requestId != null && !requestId.isEmpty()) {
            response.put("requestId", requestId);
        }
        return response;
    }
}
