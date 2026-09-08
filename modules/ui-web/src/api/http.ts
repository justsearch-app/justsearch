// SPDX-License-Identifier: Apache-2.0
/**
 * Core HTTP utilities for API requests.
 *
 * This module provides the foundational request infrastructure:
 * - Retry logic with exponential backoff
 * - Error handling and type-safe error creation
 * - Request cancellation via AbortController
 */

import { isTauriRuntime as isProbablyTauriRuntime } from '../utils/tauriRuntime';

// ==================== Constants ====================

const DEFAULT_HOST = '127.0.0.1';
const MAX_RETRIES = 3;
const RETRY_DELAY = 1000;

// ==================== Types ====================

interface ApiError extends Error {
  code?: string;
  status?: number;
  requestId?: string;
  errorClass?: string;
  retryable?: boolean;
}

export interface ApiEndpoint {
  port: number | null;
  baseUrl: string | null;
  source: 'url' | 'bridge' | 'tauri' | 'env' | 'auto' | 'proxy' | 'unresolved';
  /**
   * Tempdoc 501: producer-published instance identity. Populated when the
   * endpoint was confirmed against `GET /api/runtime/manifest`. Consumers
   * caching the endpoint should compare instanceId across resolutions to
   * detect a producer restart (and invalidate their caches accordingly).
   */
  instanceId?: string | null;
}

/**
 * Tempdoc 501 §3.5 — minimal browser-side manifest view returned by
 * {@link fetchRuntimeManifest}. Mirrors the wire shape served at
 * `GET /api/runtime/manifest` (with the credential-class `sessionToken`
 * field stripped by the producer's HTTP redaction; consumers reading this
 * type must not assume the field is populated).
 */
export interface RuntimeManifestView {
  schemaVersion: number;
  instanceId: string;
  pid: number;
  startedAt: string;
  dataDir: string;
  /**
   * Tempdoc 501 §12.1 projection from `LifecycleProjection.derive`. One of
   * `STARTING` | `READY` | `DEGRADED` | `ERROR`. Null only during the brief
   * head-only initial publish before {@code lifecycle} is computed.
   */
  lifecycle?: string | null;
  head: {
    apiPort: number;
    apiBaseUrl: string;
    /**
     * Filesystem transport carries this; HTTP transport redacts it.
     * Optional in the type because HTTP consumers never see it populated.
     */
    sessionToken?: string | null;
    readyAt: string;
  };
  /**
   * Tempdoc 501 Phase 12: worker projection. {@code state} is always
   * present once the worker sub-record exists: `pending` | `ready` |
   * `failed`. `spawnError` carries the upstream reason when state=`failed`.
   */
  worker?: {
    state: 'pending' | 'ready' | 'failed';
    grpcPort?: number | null;
    indexBasePath?: string | null;
    readyAt?: string | null;
    spawnError?: string | null;
  } | null;
  /**
   * Tempdoc 501 Phase 13: AI projection from `InferenceCapability`.
   * `phase` carries the `CapabilityHealth.name()` value (`PENDING` |
   * `READY` | `DEGRADED` | `OFFLINE` | `RECOVERING`). `required` reports
   * whether inference is configured at all — `required=false` with
   * `phase=OFFLINE` is the expected state, not a failure.
   *
   * Tempdoc 883 decision 1 (backend `RuntimeManifest.AiInfo`) — `contextWindow` is the window the
   * engine this process launched was actually started with, and why: `rung` in tokens, `reason` one
   * of `top-rung` / `override` / `stepped-from:<planned rung>`, plus the slot count, KV cache type
   * and the NVML free VRAM recorded at plan time. Null when this process launched no server
   * (nothing started yet, or an adopted external instance whose window it did not choose).
   *
   * This is INTENT, not observation: the window the running server reports (`/props` `n_ctx`,
   * published as `llmContextTokens`) stays authoritative — never present one as the other
   * (ADR-0047). The SAME record is published on `/api/inference/status.contextWindow`, which is
   * the transport the shell actually renders it from (`state/aiStateStore.ts` → the
   * `core.ai.contextWindow` fact → Brain / Settings); it is typed here so a consumer reading the
   * manifest sees the full AI block rather than a silently-narrower copy of it.
   *
   * Nullability follows the GENERATED schema
   * (`api/generated/schema-types/inference-status-response.ts`), not the Java record: `rung` and
   * `slots` are primitive `int` on `OnlineAiRuntimeIntrospection.ContextWindow` and optional here,
   * so read them defensively — as `state/aiStateStore.ts` does, treating a missing or non-positive
   * `rung` as "no derived window" rather than as a rung of zero.
   */
  ai?: {
    phase: string;
    required: boolean;
    pendingReason?: string | null;
    readyAt?: string | null;
    contextWindow?: {
      rung?: number;
      reason?: string | null;
      slots?: number;
      kvType?: string | null;
      freeVramBytes?: number | null;
    } | null;
  } | null;
  /**
   * Tempdoc 501 Phase 30 (§13.4.2): typed reachability axis. Each
   * transport carries `kind` (`http-rest` | `sse` | `well-known` |
   * `filesystem` | `mcp` | `probe`) + `url` (URL for HTTP-class kinds,
   * tool name for MCP, filesystem path for filesystem) + `audience`
   * (`public` | `full`). HTTP-served records only carry audience=public
   * entries; filesystem-served records carry both.
   */
  reachability?: {
    transports: Array<{
      kind: string;
      url: string;
      audience: string;
    }>;
  } | null;
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'DELETE' | 'PUT' | undefined;
  body?: unknown | undefined;
  signal?: AbortSignal | undefined;
  retries?: number | undefined;
}

// ==================== Helpers ====================

export function normalizePort(value: unknown): number | null {
  if (value == null) return null;
  const port = Number(value);
  return Number.isFinite(port) && port > 0 ? port : null;
}

export function normalizeNonBlankString(value: unknown): string | null {
  if (value == null) return null;
  const s = String(value).trim();
  return s.length > 0 ? s : null;
}

export function createApiError(
  message: string,
  code?: string,
  status?: number,
  requestId?: string,
  errorClass?: string,
  retryable?: boolean
): ApiError {
  const error = new Error(message) as ApiError;
  if (code !== undefined) error.code = code;
  if (status !== undefined) error.status = status;
  if (requestId !== undefined) error.requestId = requestId;
  if (errorClass !== undefined) error.errorClass = errorClass;
  if (retryable !== undefined) error.retryable = retryable;
  return error;
}

// ==================== Session Token Constants ====================

/** Header name for the desktop session token (must match backend). */
export const SESSION_TOKEN_HEADER = 'X-JustSearch-Session';

/**
 * The webview's half of the backend binding (tempdoc 805 G.1).
 *
 * The session token is a per-BOOT fact of one Head incarnation, not a constant of the app's
 * lifetime. Holding it in three independent module variables made "forget it" a three-assignment
 * operation nobody performed, so a backend restart left every mutating call 401-ing forever
 * (R11-F2). One object, one `invalidate()`.
 *
 * `resolved` marks the resolution FINAL — see `resolveSessionTokenFromTauri` for when that is
 * legitimate. `inFlight` is shared by concurrent callers so a retryable null cannot stampede
 * `invoke('session_token')`.
 */
interface SessionTokenBinding {
  token: string | null;
  resolved: boolean;
  inFlight: Promise<string | null> | null;
  /** Bumped by every invalidation, so a resolution in flight across one knows it is obsolete. */
  generation: number;
}

const sessionBinding: SessionTokenBinding = {
  token: null,
  resolved: false,
  inFlight: null,
  generation: 0,
};

/**
 * Drop the cached token so the next resolve re-invokes the shell (tempdoc 805 G.1).
 *
 * Called when the shell announces a backend restart, and when the backend answers a mutating call
 * with `UI_TOKEN_REQUIRED` — the two ways the webview learns its binding died. An in-flight
 * resolution is abandoned rather than awaited: it is resolving against the DEAD instance.
 */
export function invalidateSessionToken(): void {
  sessionBinding.token = null;
  sessionBinding.resolved = false;
  sessionBinding.inFlight = null;
  sessionBinding.generation += 1;
}

// ==================== Tauri Integration ====================

/**
 * Resolves the API port from the Tauri shell via `invoke('api_port')`.
 * Returns null in non-Tauri runtimes (browser dev mode).
 *
 * Restores the discovery path deleted by tempdoc 326 (commit 05fa52739).
 * Without this, a production Tauri build has no working port discovery
 * because the legacy `window.justSearch` bridge is never installed.
 */
async function resolvePortFromTauri(): Promise<number | null> {
  if (!isProbablyTauriRuntime()) {
    return null;
  }
  try {
    const { invoke } = await import('@tauri-apps/api/core');
    const port = await invoke('api_port');
    return normalizePort(port);
  } catch (err) {
    console.warn('tauri api_port invoke failed', err);
    return null;
  }
}

/**
 * Resolves the session token from Tauri (prod mode only).
 * Returns null if not in Tauri runtime or no token is available.
 *
 * Caching rule (tempdoc 804 D3): the resolution is marked final ONLY when it
 * cannot improve — a genuinely non-Tauri runtime (no token will ever exist) or
 * a token actually obtained. A null FROM a Tauri runtime is transient: the
 * shell's `session_token` command waits at most ~10s, so a slow cold start
 * yields null while the real token arrives moments later. Caching that null
 * permanently would 401 every mutating call for the app's lifetime with no
 * recovery, so it stays unresolved and the next caller retries.
 */
export async function resolveSessionTokenFromTauri(): Promise<string | null> {
  // Return cached value if already resolved
  if (sessionBinding.resolved) {
    return sessionBinding.token;
  }

  // Avoid calling into Tauri APIs in browser mode
  if (!isProbablyTauriRuntime()) {
    sessionBinding.resolved = true;
    sessionBinding.token = null;
    return null;
  }

  // Single-flight: concurrent callers on the retryable path share one invoke.
  if (sessionBinding.inFlight) {
    return sessionBinding.inFlight;
  }

  const generation = sessionBinding.generation;
  const pending = (async (): Promise<string | null> => {
    try {
      const { invoke } = await import('@tauri-apps/api/core');
      const token = await invoke('session_token');
      const normalized = normalizeNonBlankString(token);
      // An invalidate() mid-flight bumped the generation: this answer describes the DEAD
      // instance, so it is returned to the caller that asked but never cached.
      if (normalized !== null && sessionBinding.generation === generation) {
        sessionBinding.token = normalized;
        sessionBinding.resolved = true;
      }
      return normalized;
    } catch (err) {
      // Best-effort: token may not be available yet (or at all) — retryable.
      console.debug('tauri session_token invoke failed (expected in dev mode)', err);
      return null;
    } finally {
      // Only clear our own slot — an invalidation already replaced it otherwise.
      if (sessionBinding.generation === generation) sessionBinding.inFlight = null;
    }
  })();
  sessionBinding.inFlight = pending;

  return pending;
}

/**
 * Gets the cached session token synchronously.
 * Returns null if the token has not been resolved yet or is not available.
 * Call resolveSessionTokenFromTauri() first to ensure the token is resolved.
 */
export function getSessionToken(): string | null {
  return sessionBinding.token;
}

export async function resolveSmokeRunId(): Promise<string | null> {
  // Browser override (useful for non-Tauri smoke runs): ?smoke_run_id=<uuid>
  const urlRunId = normalizeNonBlankString(
    new URLSearchParams(window.location.search).get('smoke_run_id')
  );
  if (urlRunId) return urlRunId;

  // Desktop (Tauri): invoke command (backed by JUSTSEARCH_SMOKE_RUN_ID in the host environment).
  if (!isProbablyTauriRuntime()) {
    return null;
  }
  try {
    const { invoke } = await import('@tauri-apps/api/core');
    const runId = await invoke('smoke_run_id');
    return normalizeNonBlankString(runId);
  } catch (err) {
    // Best-effort: smoke correlation should not break normal UI behavior.
    console.warn('tauri smoke_run_id invoke failed', err);
    return null;
  }
}

// ==================== Core Request Function ====================

/**
 * Core HTTP request function with retry logic.
 *
 * Features:
 * - Automatic retries with exponential backoff
 * - JSON body serialization
 * - Error handling with status codes
 * - Cancellation via AbortSignal
 * - Session token header on non-GET requests (prod mode)
 */
export async function request<T>(
  baseUrl: string,
  path: string,
  options: RequestOptions = {}
): Promise<T> {
  const { method = 'GET', body, signal, retries = MAX_RETRIES } = options;
  const url = `${baseUrl}${path}`;

  // For non-GET requests, ensure the session token is resolved before proceeding.
  // This makes token correctness a property of the helper, not each callsite.
  let tokenForRequest = sessionBinding.token;
  if (method !== 'GET' && !sessionBinding.resolved) {
    tokenForRequest = await resolveSessionTokenFromTauri();
  }

  let lastError: ApiError | null = null;

  for (let attempt = 0; attempt < retries; attempt++) {
    try {
      const headers: Record<string, string> = {};

      // Add Content-Type for JSON body
      if (body) {
        headers['Content-Type'] = 'application/json';
      }

      // Add session token for non-GET requests (prod mode security)
      if (method !== 'GET' && tokenForRequest) {
        headers[SESSION_TOKEN_HEADER] = tokenForRequest;
      }

      const fetchInit: RequestInit = { method };
      if (Object.keys(headers).length > 0) {
        fetchInit.headers = headers;
      }
      if (body) {
        fetchInit.body = JSON.stringify(body);
      }
      if (signal) {
        fetchInit.signal = signal;
      }
      const response = await fetch(url, fetchInit);

      if (!response.ok) {
        const text = await response.text().catch(() => '');
        let errorData: {
          error?: string;
          errorCode?: string;
          requestId?: string;
          errorClass?: string;
          retryable?: boolean;
        } | null = null;

        try {
          errorData = text ? JSON.parse(text) : null;
        } catch {
          // Not JSON
        }

        throw createApiError(
          errorData?.error || text || `Request failed: ${response.statusText}`,
          errorData?.errorCode,
          response.status,
          errorData?.requestId,
          errorData?.errorClass,
          errorData?.retryable
        );
      }

      return (await response.json()) as T;
    } catch (err: unknown) {
      // Don't retry if aborted
      if (err instanceof Error && err.name === 'AbortError') {
        throw err;
      }

      lastError = err as ApiError;

      // Don't retry 4xx errors
      const status = (err as ApiError).status;
      if (status && status >= 400 && status < 500) {
        throw err;
      }

      // Wait before retry
      if (attempt < retries - 1) {
        await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY * (attempt + 1)));
      }
    }
  }

  throw lastError || createApiError('Request failed after retries');
}

// ==================== Endpoint Resolution ====================

/**
 * Tempdoc 501 §3.5 — fetches the producer-published runtime manifest from
 * the HTTP transport. Returns null if the endpoint hasn't been resolved
 * yet or the manifest is unavailable (503 — producer still booting).
 *
 * Callers use this to enrich an already-resolved endpoint with the
 * producer's `instanceId` (so caches can invalidate on restart), or to
 * detect phased-readiness transitions (worker present yet?).
 */
export async function fetchRuntimeManifest(
  baseUrl: string,
  signal?: AbortSignal,
): Promise<RuntimeManifestView | null> {
  if (!baseUrl) return null;
  try {
    const init: RequestInit = {};
    if (signal) init.signal = signal;
    const res = await fetch(`${baseUrl}/api/runtime/manifest`, init);
    if (!res.ok) return null;
    return (await res.json()) as RuntimeManifestView;
  } catch {
    return null;
  }
}

/**
 * Resolves the API endpoint using various discovery methods:
 * 1. URL parameter (?api_port=33221)
 * 2. JavaFX WebView bridge (legacy — kept for parity with old shells)
 * 3. Tauri command `invoke('api_port')` (production desktop path)
 * 4. Environment variable
 *
 * Tempdoc 501 §3.5: once an endpoint is resolved (port + baseUrl known),
 * we make a best-effort fetch against `GET /api/runtime/manifest` to
 * confirm the producer's identity and populate `endpoint.instanceId`. A
 * failure here is non-fatal — the endpoint still resolves; consumers
 * caching by `instanceId` will treat the absence as "no identity check
 * possible" and use ambient liveness signals (request failures, SSE
 * disconnects).
 */
export async function resolveApiEndpoint(): Promise<ApiEndpoint> {
  let resolved: ApiEndpoint;

  // URL parameter override (testing): ?api_port=33221
  const urlPort = normalizePort(new URLSearchParams(window.location.search).get('api_port'));
  if (urlPort) {
    resolved = { port: urlPort, baseUrl: `http://${DEFAULT_HOST}:${urlPort}`, source: 'url' };
  } else {
    // Check bridge (legacy JavaFX WebView)
    const bridgePort = normalizePort(
      (window as unknown as Record<string, unknown>)?.justSearch &&
        typeof (
          (window as unknown as Record<string, unknown>).justSearch as Record<string, unknown>
        )?.getApiPort === 'function'
        ? (
            (window as unknown as Record<string, unknown>).justSearch as {
              getApiPort: () => unknown;
            }
          ).getApiPort()
        : null
    );
    if (bridgePort) {
      resolved = { port: bridgePort, baseUrl: `http://${DEFAULT_HOST}:${bridgePort}`, source: 'bridge' };
    } else {
      // Tauri shell exposes the bound port via `invoke('api_port')`. This is the
      // production desktop discovery path.
      const tauriPort = await resolvePortFromTauri();
      if (tauriPort) {
        resolved = { port: tauriPort, baseUrl: `http://${DEFAULT_HOST}:${tauriPort}`, source: 'tauri' };
      } else {
        // Check environment variable
        const envPort = normalizePort(
          ((import.meta as unknown as Record<string, unknown>).env as Record<string, unknown>)
            ?.VITE_JUSTSEARCH_API_PORT ??
            ((import.meta as unknown as Record<string, unknown>).env as Record<string, unknown>)?.VITE_API_PORT
        );
        if (envPort) {
          resolved = { port: envPort, baseUrl: `http://${DEFAULT_HOST}:${envPort}`, source: 'env' };
        } else if (
          (import.meta as unknown as Record<string, unknown>).env &&
          ((import.meta as unknown as Record<string, unknown>).env as Record<string, unknown>)?.DEV
          && !isProbablyTauriRuntime()
        ) {
          // In browser Vite dev mode, the dev server proxies /api/* to the backend.
          // A desktop host with no bound Engine must remain unresolved, including Tauri dev.
          // Use window.location.origin so relative API paths route through the proxy.
          resolved = { port: null, baseUrl: window.location.origin, source: 'proxy' };
        } else {
          return { port: null, baseUrl: null, source: 'unresolved' };
        }
      }
    }
  }

  // Tempdoc 501 §3.5: enrich the resolved endpoint with the producer's
  // instance identity. Best-effort — endpoint still works without it.
  if (resolved.baseUrl) {
    const manifest = await fetchRuntimeManifest(resolved.baseUrl);
    if (manifest && manifest.instanceId) {
      resolved.instanceId = manifest.instanceId;
    }
  }
  return resolved;
}
