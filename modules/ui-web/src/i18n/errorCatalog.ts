// SPDX-License-Identifier: Apache-2.0
/**
 * Backend-served error message catalog.
 *
 * Replaces the deleted `utils/errorMessages.ts` static catalog (per tempdoc 431).
 * The backend is now the single source of truth: `errors.en.properties` in
 * `app-api`, served at `GET /api/messages/errors/{locale}`. This module fetches
 * the catalog at app boot and exposes the same `localizeError` /
 * `getErrorMessage` API the old module had — drop-in for existing consumers.
 *
 * Lookup order (per tempdoc 434 §3 fallback chain):
 *   1. `error.i18nKey` — preferred; emitted by all error surfaces post-tempdoc-431-Option-A.
 *   2. `"errors." + error.code` — defensive derivation for malformed payloads.
 *   3. `error.message` — the wire-level English message; usually present.
 *   4. The raw lookup key as a literal — surfaces the missing-key honestly.
 *
 * Caching (per tempdoc 434 §4 + tempdoc 431 §F.5):
 *   The boot fetch persists the catalog body + ETag in `localStorage`. On subsequent
 *   page loads we send `If-None-Match: <etag>` and use the in-storage body when the
 *   server returns 304. This exercises the wire's strong-ETag contract end-to-end
 *   instead of bypassing it with a fresh full-body fetch every page load. localStorage
 *   may be unavailable (test environments, restricted webviews); the implementation
 *   degrades gracefully to "no cache" in that case.
 *
 * On boot-fetch failure: the catalog falls back to the localStorage-cached body if
 * any, else stays empty (raw-key fallback). This is intentional — we don't block app
 * mount on a backend round-trip (per tempdoc 434 design commitment §4).
 */

// This module is deliberately import-free (foundational i18n layer; shell-v0 imports FROM
// it, per the FSD-style boundary in eslint.config.js — see the 9 shell-v0 consumers).
// Importing `authorizedFetch` from shell-v0/api would invert that dependency direction.
// The catalog fetch below is a GET (TOKEN_FREE_METHODS in authorizedFetch.ts exempts GET
// from session-token attachment), so the two are behaviorally identical here regardless.
/* eslint-disable no-restricted-globals -- see note above; GET-only boot fetch, no token needed */

/** Module-level catalog cache. Populated by `bootErrorCatalog`; empty until then. */
let catalog: Record<string, string> = {};
/**
 * Tempdoc 941 — did a boot attempt actually GET AN ANSWER from the backend (200 or 304)?
 *
 * This used to be `bootAttempted`, and the re-attempt guard read `bootAttempted && catalog is
 * non-empty`. That inference is wrong in the one case that matters: the boot seeds `catalog`
 * from the localStorage body BEFORE it fetches, so a boot that raced an unanswering backend left
 * the guard set AND the catalog non-empty — permanently closed, holding whatever the previous
 * VERSION of the app had cached. Success is now recorded where success happens, so "we have not
 * heard from the backend yet" can never be mistaken for "we are done".
 */
let bootSucceeded = false;
/**
 * The in-flight boot, so concurrent callers (app boot racing the backend-ready re-attempt) share
 * one request instead of each opening their own.
 */
let inFlightBoot: Promise<void> | null = null;
let missingKeyLogged = new Set<string>();

const STORAGE_KEY_BODY = 'justsearch.errorCatalog.en.body';
const STORAGE_KEY_ETAG = 'justsearch.errorCatalog.en.etag';

interface ErrorLike {
  message?: string;
  code?: string;
  i18nKey?: string;
}

interface LocalizedError {
  /** The localized message, or the raw message / key if localization failed. */
  message: string;
  /** True if the error was found in the boot-fetched catalog. */
  localized: boolean;
  /** The original error code, if any. */
  code?: string;
}

interface ErrorCatalogResponse {
  schemaVersion?: string;
  locale?: string;
  namespace?: string;
  messages?: Record<string, string>;
}

/**
 * Reads `(body, etag)` from `localStorage` if both are present and the body
 * parses as a non-empty key→string map. Returns `null` on any error
 * (storage unavailable, malformed JSON, missing keys, empty body).
 *
 * Empty-body guard: an empty cached body would cause a subtle bug if we
 * still sent `If-None-Match` against it — a 304 response would leave us
 * stuck with no catalog. Treating an empty body as "no usable cache"
 * forces a full 200 fetch.
 */
function loadFromStorage(): { body: Record<string, string>; etag: string } | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    const bodyJson = localStorage.getItem(STORAGE_KEY_BODY);
    const etag = localStorage.getItem(STORAGE_KEY_ETAG);
    if (!bodyJson || !etag) return null;
    const parsed = JSON.parse(bodyJson);
    if (!parsed || typeof parsed !== 'object') return null;
    const body = parsed as Record<string, string>;
    if (Object.keys(body).length === 0) return null;
    return { body, etag };
  } catch {
    return null;
  }
}

/**
 * Persists `(body, etag)` to `localStorage`. Best-effort: failures
 * (quota exceeded, storage disabled, etc.) are swallowed silently — the
 * cache is an optimization, not a correctness requirement.
 */
function saveToStorage(body: Record<string, string>, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // ignore — see contract above
  }
}

/**
 * Boot-time fetch of the error catalog from the backend.
 *
 * Call at app boot (from `i18n.ts`). Subsequent calls are no-ops once the backend has actually
 * ANSWERED (200 or 304); until then every call re-attempts. That idempotence is what makes the
 * whole boot list safe to re-run on the shell's backend-ready edge (`i18n.ts`
 * `watchForBackendReady`, tempdoc 941) — the re-attempt IS calling this again.
 *
 * Conditional GET (per tempdoc 431 §F.5): if a previous boot persisted a body
 * + ETag in `localStorage`, the body is seeded as the in-memory catalog
 * immediately (so `localizeError` can resolve keys before the network round-trip
 * completes), and the request sends `If-None-Match: <etag>`. The backend may
 * respond:
 *   - 304 Not Modified: the seeded body is already current; no further work.
 *   - 200 with new body: the seeded body is replaced; new (body, etag) is
 *     persisted.
 *
 * Failure mode: if the fetch fails (network error, non-OK status, malformed
 * body), the seeded body (if any) is retained; otherwise the catalog stays
 * empty and `localizeError` falls back to the raw `error.message` or key.
 */
export async function bootErrorCatalog(baseUrl: string): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchErrorCatalog(baseUrl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

async function fetchErrorCatalog(baseUrl: string): Promise<void> {
  // Seed catalog from any previous-session cache. Resolves keys immediately
  // before the network round-trip completes, exercising the wire's ETag
  // contract on subsequent boots.
  const cached = loadFromStorage();
  if (cached) {
    catalog = cached.body;
  }

  if (!baseUrl) {
    console.debug('[errorCatalog] no baseUrl available; using cached catalog if any (raw-key fallback otherwise)');
    return;
  }

  try {
    const headers: Record<string, string> = {};
    if (cached?.etag) {
      headers['If-None-Match'] = cached.etag;
    }
    const response = await fetch(`${baseUrl}/api/messages/errors/en`, { headers });

    // 304 Not Modified: cached body is current. Nothing to do; we already seeded — and the
    // backend ANSWERED, which is what closes the boot (941).
    if (response.status === 304) {
      bootSucceeded = true;
      return;
    }

    if (!response.ok) {
      console.debug(
        `[errorCatalog] /api/messages/errors/en returned ${response.status}; ` +
        `using cached catalog if any (raw-key fallback otherwise)`,
      );
      return;
    }

    const body = (await response.json()) as ErrorCatalogResponse;
    if (body && typeof body === 'object' && body.messages && typeof body.messages === 'object') {
      catalog = body.messages;
      bootSucceeded = true;
      const etag = response.headers.get('ETag');
      if (etag) {
        saveToStorage(body.messages, etag);
      }
    } else {
      console.debug('[errorCatalog] /api/messages/errors/en response missing `messages` map; cached catalog retained');
    }
  } catch (err) {
    console.debug('[errorCatalog] /api/messages/errors/en fetch failed; cached catalog retained', err);
  }
}

/**
 * Returns the message for the given i18n key, or undefined if not in the catalog.
 * Emits a one-shot diagnostic for missing keys (per tempdoc 434 §3).
 */
function lookup(key: string | undefined | null): string | undefined {
  if (!key) return undefined;
  const message = catalog[key];
  if (message !== undefined) return message;
  if (!missingKeyLogged.has(key)) {
    missingKeyLogged.add(key);
    console.debug(`[errorCatalog] missing key in catalog: ${key} (rendering raw key as fallback)`);
  }
  return undefined;
}

/**
 * One-shot guard for the "wire envelope missing i18nKey" diagnostic.
 * After tempdoc 431 Option A (commit 6cc82f28e), every error surface
 * (REST, summary SSE, agent SSE) emits `i18nKey` on the wire. The derivation
 * fallback below is defensive — for malformed payloads or future
 * third-party plugin errors that don't conform. If the diagnostic ever
 * fires, that's a signal the wire contract is being violated upstream.
 */
const fallbackDerivationLogged = new Set<string>();

/**
 * Localizes an API error using the wire-emitted `i18nKey` (preferred) or the
 * derived `"errors." + code` key (defensive fallback). Returns a
 * `LocalizedError` with the best available message.
 *
 * Drop-in replacement for the deleted `utils/errorMessages.ts` `localizeError`.
 */
export function localizeError(error: ErrorLike | null | undefined): LocalizedError {
  if (!error) {
    const fallback = lookup('errors.INTERNAL_ERROR') ?? 'An internal error occurred. Please try again.';
    return { message: fallback, localized: catalog['errors.INTERNAL_ERROR'] !== undefined };
  }

  const code = error.code;
  // Prefer wire-emitted i18nKey (all error surfaces emit it post-tempdoc-431-Option-A);
  // fall back to derivation only if the envelope is malformed or pre-Option-A.
  let wireKey: string | undefined;
  if (error.i18nKey) {
    wireKey = error.i18nKey;
  } else if (code) {
    wireKey = `errors.${code}`;
    if (!fallbackDerivationLogged.has(code)) {
      fallbackDerivationLogged.add(code);
      console.debug(
        `[errorCatalog] error envelope for code "${code}" missing i18nKey; ` +
        `deriving "${wireKey}" locally. Per tempdoc 431 Option A all error ` +
        `surfaces emit i18nKey on the wire — this fallback is defensive only ` +
        `and should not normally fire. Investigate the upstream emitter.`,
      );
    }
  }
  const fromCatalog = lookup(wireKey);

  if (fromCatalog !== undefined) {
    return { message: fromCatalog, localized: true, ...(code !== undefined ? { code } : {}) };
  }

  // Catalog miss — fall back to the wire message, then to the raw key as a last resort.
  const fallback =
    error.message ??
    wireKey ??
    lookup('errors.INTERNAL_ERROR') ??
    'An internal error occurred. Please try again.';
  return { message: fallback, localized: false, ...(code !== undefined ? { code } : {}) };
}

/**
 * Convenience function returning just the localized message string.
 * Drop-in replacement for the deleted `utils/errorMessages.ts` `getErrorMessage`.
 */
export function getErrorMessage(error: ErrorLike | null | undefined): string {
  return localizeError(error).message;
}

/**
 * Test-only: reset the module's cached state. Not exported via the package
 * barrel — used by unit tests only.
 */
export function __resetForTest(): void {
  catalog = {};
  bootSucceeded = false;
  inFlightBoot = null;
  missingKeyLogged = new Set<string>();
  fallbackDerivationLogged.clear();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // ignore
  }
}

/**
 * Test-only: seed the catalog directly without an HTTP call.
 */
export function __seedForTest(messages: Record<string, string>): void {
  catalog = { ...messages };
  bootSucceeded = true;
}
