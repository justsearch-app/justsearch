// SPDX-License-Identifier: Apache-2.0
/**
 * Backend-served Resource catalog (registry-resource namespace).
 *
 * Slice 3a.1.4b: parallel of `errorCatalog.ts` for the `registry-resource`
 * namespace. Resolves Resource presentation labels (label/description) and
 * MetricRef labels declared on HealthEvent body fields.
 *
 * The backend serves `GET /api/messages/registry-resource/{locale}` via
 * `MessageCatalogController` (one instance per namespace; see tempdoc 429
 * §E.8.b + §E.17). This module fetches the en catalog at app boot and
 * exposes a synchronous `localizeResourceKey(key)` lookup callable directly
 * from Lit components (tempdoc 742: the Lingui React runtime this originally
 * avoided coupling to was removed entirely — zero live consumers).
 *
 * Lookup contract: keys missing from the catalog return the raw key
 * (defensive — surfaces missing translations honestly rather than
 * silently substituting an empty string).
 *
 * Caching mirrors `errorCatalog.ts`: localStorage-cached body + ETag,
 * conditional GET on subsequent boots, graceful degradation if storage
 * is unavailable. Cache keys are namespace-scoped to avoid pollution.
 */

/**
 * 478 §4.F refactor (slice 477 follow-on): owner-scoped resource
 * catalog. The flat `Record<string, string>` is split into:
 *   - `core`: server-fetched authoritative translations
 *   - `plugins`: per-plugin namespace, keyed by plugin id
 *
 * Lookup priority: core > plugins (server-authoritative wins).
 *
 * Cross-plugin and plugin-overrides-core writes are now structurally
 * impossible: writers go through namespace-scoped APIs. This eliminates
 * the V1.5 alpha workaround where PluginRegistry tracked an
 * `installedTranslationKeys: string[]` array per plugin to support
 * removal-by-key-list. Removal is now a single namespace delete.
 */

// This module is deliberately import-free (foundational i18n layer; shell-v0 imports FROM
// it, per the FSD-style boundary in eslint.config.js — see the 9 shell-v0 consumers).
// Importing `authorizedFetch` from shell-v0/api would invert that dependency direction.
// Both catalog fetches below are GETs (TOKEN_FREE_METHODS in authorizedFetch.ts exempts GET
// from session-token attachment), so the two are behaviorally identical here regardless.
/* eslint-disable no-restricted-globals -- see note above; GET-only boot fetches, no token needed */

let coreCatalog: Record<string, string> = {};
const pluginCatalogs = new Map<string, Record<string, string>>();
/**
 * Tempdoc 941 — did the `registry-resource` boot actually GET AN ANSWER (200 or 304)?
 *
 * Was `bootAttempted`, tested together with "`coreCatalog` is non-empty". That pair cannot tell
 * the two cases apart: the boot seeds from the localStorage body BEFORE fetching, AND every
 * sibling namespace (`registry-surface`, `health-events`, …) merges into this same object, so a
 * `registry-resource` fetch that never landed still saw a non-empty catalog and was never
 * retried. Success is recorded where success happens.
 */
let bootSucceeded = false;
/** Shared in-flight boot, so a readiness-driven re-attempt joins the request rather than racing it. */
let inFlightBoot: Promise<void> | null = null;
let missingKeyLogged = new Set<string>();

/**
 * Tempdoc 855 fix-round F2 (S2) — `localizeResourceKey` is synchronous and, on a cold deep-link
 * boot, resolves BEFORE the async catalog fetch below completes: the raw key renders, and nothing
 * previously told a mounted consumer to re-render once the real label arrived (the raw key stuck
 * around forever, not just until the fetch settled). This is a tiny fan-out so a consumer that
 * renders localized labels — `SettingsNav`, `SettingsSurface` — can subscribe once (typically in
 * `connectedCallback`, calling `requestUpdate()`) and unsubscribe on disconnect, rather than every
 * future consumer needing its own polling/re-fetch workaround.
 */
type CatalogListener = () => void;
const catalogListeners = new Set<CatalogListener>();

/** Subscribe to catalog updates. Returns an unsubscribe function. */
export function onCatalogUpdated(listener: CatalogListener): () => void {
  catalogListeners.add(listener);
  return () => {
    catalogListeners.delete(listener);
  };
}

/** Notified after every merge into `coreCatalog` (both boot paths below), and by `__seedForTest`
 *  so a test can simulate a late-arriving catalog without a real fetch. */
function notifyCatalogUpdated(): void {
  for (const listener of catalogListeners) {
    listener();
  }
}

const STORAGE_KEY_BODY = 'justsearch.resourceCatalog.en.body';
const STORAGE_KEY_ETAG = 'justsearch.resourceCatalog.en.etag';

interface ResourceCatalogResponse {
  schemaVersion?: string;
  locale?: string;
  namespace?: string;
  messages?: Record<string, string>;
}

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

function saveToStorage(body: Record<string, string>, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // ignore — best-effort cache
  }
}

/**
 * Boot-time fetch of the registry-resource catalog from the backend.
 *
 * Call at app boot (from `i18n.ts`). Subsequent calls are no-ops once the backend has actually
 * ANSWERED (200 or 304); until then every call re-attempts. That idempotence is what makes the
 * whole boot list safe to re-run on the shell's backend-ready edge (`i18n.ts`
 * `watchForBackendReady`, tempdoc 941).
 *
 * On success: in-memory `catalog` is populated; subsequent
 * `localizeResourceKey` calls resolve keys synchronously.
 *
 * On 304: the seeded localStorage body is retained as the in-memory catalog.
 *
 * On failure: the catalog stays empty (or seeded from prior session) and
 * `localizeResourceKey` falls back to raw-key passthrough.
 */
export async function bootResourceCatalog(baseUrl: string): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchResourceCatalog(baseUrl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

async function fetchResourceCatalog(baseUrl: string): Promise<void> {
  const cached = loadFromStorage();
  if (cached) {
    // Tempdoc 565 §28 — MERGE, never replace `coreCatalog`. Every catalog boot
    // (surface/health/operation/workflow) Object.assigns into this one object and
    // they all run together in i18n.ts's `Promise.all` with no ordering. A bare
    // `coreCatalog = …` here would wipe a sibling catalog that merged first on a
    // cold boot (the §28 workflow-label clobber). Keep all writers merge-only.
    Object.assign(coreCatalog, cached.body);
    notifyCatalogUpdated();
  }

  if (!baseUrl) {
    console.debug('[resourceCatalog] no baseUrl available; using cached catalog if any (raw-key fallback otherwise)');
    return;
  }

  try {
    const headers: Record<string, string> = {};
    if (cached?.etag) {
      headers['If-None-Match'] = cached.etag;
    }
    const response = await fetch(`${baseUrl}/api/messages/registry-resource/en`, { headers });

    if (response.status === 304) {
      // The backend answered: the seeded body is current, and the boot is closed (941).
      bootSucceeded = true;
      return;
    }

    if (!response.ok) {
      console.debug(
        `[resourceCatalog] /api/messages/registry-resource/en returned ${response.status}; ` +
        `using cached catalog if any (raw-key fallback otherwise)`,
      );
      return;
    }

    const body = (await response.json()) as ResourceCatalogResponse;
    if (body && typeof body === 'object' && body.messages && typeof body.messages === 'object') {
      // §28 — merge, never replace (see the cache-seed note above): a cold-boot 200 here
      // must not clobber a sibling catalog (workflow/surface/operation) that already merged.
      Object.assign(coreCatalog, body.messages);
      bootSucceeded = true;
      notifyCatalogUpdated();
      const etag = response.headers.get('ETag');
      if (etag) {
        saveToStorage(body.messages, etag);
      }
    } else {
      console.debug('[resourceCatalog] /api/messages/registry-resource/en response missing `messages` map; cached catalog retained');
    }
  } catch (err) {
    console.debug('[resourceCatalog] /api/messages/registry-resource/en fetch failed; cached catalog retained', err);
  }
}

/**
 * Tempdoc 565 §28 cleanup — the ONE simple message-catalog boot. The
 * surface/health/operation/workflow catalogs are identical fetch-and-merge flows
 * differing only by namespace; collapsing them to this helper makes the
 * "merge into `coreCatalog`, never replace" invariant true BY CONSTRUCTION (a new
 * catalog is a one-line wrapper, not a copy that can drift into a `coreCatalog = …`
 * replacer — the bug §28 fixed). `bootResourceCatalog` stays separate: it owns the
 * extra localStorage/ETag/304 caching, but it too merges (see its note).
 *
 * Best-effort but NOT one-shot: an unreachable/!ok/malformed answer is retried on the schedule
 * below before the namespace is abandoned. Once the budget is exhausted the affected keys fall
 * back to raw-key passthrough (humanizeId / deriveTitle upstream).
 *
 * Tempdoc 941 round-18 F3 — this used to be a single terminal attempt (`if (!response.ok) return;`
 * plus a swallowing `catch`), and that is how a Settings window renders `settings.group.general`
 * as its own key. `registry-surface` is the ONLY source of every `settings.*` label
 * (`views/settingsRegister.ts`), the boot is fired once at module evaluation from `../i18n.ts`,
 * and — unlike `bootResourceCatalog` — nothing here persists a body, so there is no cached copy
 * to fall back on. The shell reloads itself the instant the Rust side reports a new backend
 * instance (`main.jsx` `installBackendRestartBridge` → `window.location.reload()`), so the boot
 * fetch routinely races a Head that is up enough to publish a manifest but not yet answering
 * `/api/messages/**`. One lost race = raw keys for the rest of that document's life. Consumers
 * already re-render on a late merge (`onCatalogUpdated`, 855 F2 S2), so retrying is enough.
 *
 * `cache: 'no-cache'` forces an end-to-end revalidation: the catalog is served with
 * `Cache-Control: public, max-age=3600` and a strong ETag
 * (`MessageCatalogController.CACHE_CONTROL`), so a webview whose HTTP cache survived an
 * over-install could otherwise satisfy this fetch from the PREVIOUS version's catalog body —
 * an answer that resolves the keys that version knew and no others.
 */
const CATALOG_RETRY_DELAYS_MS: readonly number[] = [250, 1000, 3000, 8000, 20000];

async function fetchCatalogMessages(
  baseUrl: string,
  namespace: string,
): Promise<Record<string, string> | null> {
  const response = await fetch(`${baseUrl}/api/messages/${namespace}/en`, { cache: 'no-cache' });
  if (!response.ok) return null;
  const body = (await response.json()) as ResourceCatalogResponse;
  if (body?.messages && typeof body.messages === 'object') return body.messages;
  return null;
}

/**
 * Tempdoc 941 — namespaces whose fetch actually landed, and the in-flight boot per namespace.
 *
 * The retry ladder above is bounded: after ~32 s it gives up and "keys in this namespace render
 * raw" for the life of the document. That is the right answer for a backend that is slow; it is
 * the wrong one for a backend that was not running yet and came up a minute later. Recording
 * which namespaces are still unresolved is what lets the shell's backend-ready re-attempt
 * (`i18n.ts` `watchForBackendReady`) re-run only those.
 */
const succeededNamespaces = new Set<string>();
const inFlightNamespaces = new Map<string, Promise<void>>();

async function bootMessageCatalog(baseUrl: string, namespace: string): Promise<void> {
  if (succeededNamespaces.has(namespace)) return;
  const inFlight = inFlightNamespaces.get(namespace);
  if (inFlight) return inFlight;
  const run = fetchMessageCatalogWithRetries(baseUrl, namespace).finally(() => {
    inFlightNamespaces.delete(namespace);
  });
  inFlightNamespaces.set(namespace, run);
  return run;
}

async function fetchMessageCatalogWithRetries(
  baseUrl: string,
  namespace: string,
): Promise<void> {
  if (!baseUrl) return;
  for (let attempt = 0; ; attempt++) {
    let messages: Record<string, string> | null = null;
    try {
      messages = await fetchCatalogMessages(baseUrl, namespace);
    } catch {
      // unreachable backend — indistinguishable from a !ok answer here; both retry below
    }
    if (messages) {
      Object.assign(coreCatalog, messages);
      succeededNamespaces.add(namespace);
      notifyCatalogUpdated();
      return;
    }
    const delay = CATALOG_RETRY_DELAYS_MS[attempt];
    if (delay === undefined) {
      console.debug(
        `[resourceCatalog] /api/messages/${namespace}/en unavailable after ` +
        `${CATALOG_RETRY_DELAYS_MS.length + 1} attempts; keys in this namespace render raw`,
      );
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, delay));
  }
}

export const bootSurfaceCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-surface');

export const bootHealthEventsCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'health-events');

export const bootOperationMessageCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-operation');

/**
 * Tempdoc 565 §27.4: the run-window workflow picker's authored-label catalog, so
 * `present({kind:'workflow', labelKey})` resolves the authored label instead of the
 * `humanizeId(workflow id)` fallback.
 */
export const bootWorkflowCatalog = (baseUrl: string): Promise<void> =>
  bootMessageCatalog(baseUrl, 'registry-workflow');

/**
 * Synchronous lookup against the in-memory catalog. Returns the localized
 * string if present, or the raw key as a defensive fallback.
 *
 * 478 §4.F: lookup is core-first then per-plugin scoped. Server-fetched
 * core catalog wins over plugin contributions (authoritative). Plugin
 * scope is searched in insertion order (V1.5.1 alpha doesn't expose
 * order semantics; first-installed wins for shared plugin keys, but
 * cross-plugin shared keys are an anti-pattern — plugins should
 * namespace their own keys).
 *
 * Emits a one-shot diagnostic per missing key so contributors can spot
 * unbacked keys without log spam.
 */
export function localizeResourceKey(key: string): string {
  // Core (server-authoritative) wins.
  const coreMessage = coreCatalog[key];
  if (coreMessage !== undefined) return coreMessage;
  // Then check plugin scopes.
  for (const pluginScope of pluginCatalogs.values()) {
    const message = pluginScope[key];
    if (message !== undefined) return message;
  }
  if (!missingKeyLogged.has(key)) {
    missingKeyLogged.add(key);
    console.debug(`[resourceCatalog] missing key in catalog: ${key} (rendering raw key as fallback)`);
  }
  return key;
}

/** Placeholder syntax used by the backend message catalogs: `{name}` (see the key-shape header of
 *  `modules/app-api/src/main/resources/messages/health-events.en.properties`). */
const MESSAGE_PLACEHOLDER = /\{([A-Za-z0-9_]+)\}/g;

/**
 * Tempdoc 941 F4 — substitute `{name}` placeholders in a catalog message from the emitter-supplied
 * parameter map. Until this existed, `localizeResourceKey` was the whole pipeline: a parameterized
 * message (`An indexing job failed for {path}: {errorClass}.`) reached the user verbatim, braces
 * and all, because nothing between the catalog and the render boundary ever filled it in.
 *
 * Returns `null` — not a partially-filled or blanked string — when the template asks for a
 * parameter the caller did not supply. Both alternatives are worse than declining: a leftover
 * `{path}` is the defect itself, and silently blanking it produces a sentence that reads as
 * complete while asserting something the emitter never said. `null` hands the caller the decision
 * (fall back to the short label, the id, or nothing) rather than inventing copy here.
 *
 * A template with no placeholders is returned unchanged, so this is safe to apply unconditionally.
 */
export function interpolateMessage(
  template: string,
  params: Readonly<Record<string, unknown>>,
): string | null {
  if (!template.includes('{')) return template;
  let unresolved = false;
  const filled = template.replace(MESSAGE_PLACEHOLDER, (whole, name: string) => {
    // hasOwn (not `in`) so a parameter named `constructor`/`toString` resolves from the map's own
    // keys or not at all — a prototype hit would substitute a function body into user-facing copy.
    if (!Object.hasOwn(params, name)) {
      unresolved = true;
      return whole;
    }
    const value = params[name];
    if (value === null || value === undefined || value === '') {
      unresolved = true;
      return whole;
    }
    return typeof value === 'object' ? JSON.stringify(value) : String(value);
  });
  return unresolved ? null : filled;
}

/**
 * 478 §4.F — register plugin-contributed translations under a
 * scoped namespace. Replaces a flat catalog merge.
 *
 * The plugin's keys live under `pluginCatalogs.<pluginId>` — they
 * cannot overwrite core (server-fetched) keys, and they cannot
 * overwrite ANOTHER plugin's keys. Cross-plugin shared keys are
 * structurally impossible: each plugin owns its own scope.
 *
 * Lookup is core-first (per `localizeResourceKey`); plugins resolve
 * keys their own scope provides + any keys other plugins or core
 * have provided. Plugins should namespace their keys
 * (`<pluginId>.<feature>.label`) to avoid surprise collisions.
 *
 * Returns the count of keys registered (NOT the keys added net of
 * core's existing keys — V1.5.1 alpha behavior was inconsistent
 * about this; §4.F's clean separation makes the count unambiguous).
 *
 * If `entries` is empty or non-string-valued, the plugin scope is
 * left empty (legitimate for plugins with no translations).
 */
export function registerPluginCatalog(
  pluginId: string,
  entries: Record<string, string>,
): number {
  const scope: Record<string, string> = {};
  let added = 0;
  for (const key of Object.keys(entries)) {
    const value = entries[key];
    if (typeof value === 'string') {
      scope[key] = value;
      added++;
    }
  }
  pluginCatalogs.set(pluginId, scope);
  return added;
}

/**
 * 478 §4.F — counterpart to {@link registerPluginCatalog}. Removes
 * the plugin's entire scope in O(1). PluginRegistry.uninstall calls
 * this; no longer needs to track per-plugin key arrays.
 *
 * Idempotent: removing an unknown plugin id is a no-op.
 */
export function unregisterPluginCatalog(pluginId: string): void {
  pluginCatalogs.delete(pluginId);
}

/**
 * V1.5 alpha back-compat shim — old name routes to a synthetic
 * 'legacy' plugin scope. Existing callers continue to work.
 *
 * @deprecated Use {@link registerPluginCatalog} with an explicit
 *   plugin id. The legacy bucket is preserved for back-compat but
 *   has no namespace isolation: all calls to this function share
 *   the same scope, so the last writer wins for shared keys.
 *
 * Returns the count of keys added net of core's existing keys (the
 * V1.5 alpha behavior — preserves the existing return contract).
 */
export function registerCatalogEntries(
  entries: Record<string, string>,
): number {
  const existing = pluginCatalogs.get('__legacy__') ?? {};
  let added = 0;
  for (const key of Object.keys(entries)) {
    const value = entries[key];
    if (typeof value === 'string' && coreCatalog[key] === undefined) {
      existing[key] = value;
      added++;
    }
  }
  pluginCatalogs.set('__legacy__', existing);
  return added;
}

/**
 * V1.5 alpha back-compat shim. Removes keys from the legacy bucket.
 *
 * @deprecated Use {@link unregisterPluginCatalog} with an explicit
 *   plugin id.
 */
export function unregisterCatalogEntries(keys: ReadonlyArray<string>): void {
  const legacy = pluginCatalogs.get('__legacy__');
  if (!legacy) return;
  for (const key of keys) {
    delete legacy[key];
  }
  if (Object.keys(legacy).length === 0) {
    pluginCatalogs.delete('__legacy__');
  }
}

/** Test-only: reset the module's cached state. */
export function __resetForTest(): void {
  coreCatalog = {};
  pluginCatalogs.clear();
  bootSucceeded = false;
  inFlightBoot = null;
  succeededNamespaces.clear();
  inFlightNamespaces.clear();
  missingKeyLogged = new Set<string>();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // ignore
  }
}

/** Test-only: seed the catalog directly without an HTTP call. Notifies subscribers — a test can
 *  use this AFTER mounting a consumer to simulate a late-arriving catalog boot (S2). */
export function __seedForTest(messages: Record<string, string>): void {
  coreCatalog = { ...messages };
  bootSucceeded = true;
  notifyCatalogUpdated();
}
