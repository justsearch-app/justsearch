// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.4 — FE client for `/api/registry/resources`.
 *
 * Fetches and caches the Resource catalog. Mirrors the
 * `i18n/resourceCatalog.ts` pattern (localStorage-cached body + ETag,
 * conditional GET on subsequent boots, defensive fallback on missing
 * data) but exposes typed `Resource` entries instead of i18n keys.
 *
 * Thread-safety / re-entrancy: the module is a singleton-ish
 * (in-memory map per process). Call `bootResourceRegistry(baseUrl)`
 * once at app boot; subsequent calls are no-ops unless the previous
 * fetch failed.
 *
 * Naming: this module exports `bootResourceRegistry`, not
 * `bootResourceCatalog` — the latter exists in `i18n/resourceCatalog.ts`
 * for the i18n companion catalog. Slice 3a.1.9 §B.B.B D4 disambiguated
 * the two by giving the registry-side function a name that matches the
 * client class (`ResourceCatalogClient` → `bootResourceRegistry`,
 * mirroring the "registry" terminology in `RegistryController` /
 * `/api/registry/resources`).
 *
 * Lookup contract:
 *  - `getResource(id)`: returns `Resource | undefined` synchronously.
 *  - `listResources()`: returns the full entry list (read-only view).
 *  - `listResourcesByCategory(c)`: filtered by Category axis.
 *  - `onCatalogChange(listener)`: invoked after a fresh fetch updates
 *    the in-memory catalog. Useful for FE consumers that want to
 *    re-derive their state when the catalog changes mid-session.
 *
 * The catalog refresh cadence is currently boot-only; live refresh
 * (poll-on-interval or stream-driven) is a follow-up if catalog
 * mutability becomes a real concern.
 */

import type { Resource, ResourceCatalog, Category, Provenance } from '../types/registry.js';
import { resourceCatalogSchema } from '../types/registry.js';
import { parseWireContract } from '../schemas.js';

const STORAGE_KEY_BODY = 'justsearch.resourceCatalog.body';
const STORAGE_KEY_ETAG = 'justsearch.resourceCatalog.etag';

let entriesById: Map<string, Resource> = new Map();
/**
 * Tempdoc 941 — did the boot fetch actually GET AN ANSWER (200 or 304)?
 *
 * Was `bootAttempted`, tested together with "the index is non-empty". The boot seeds the index
 * from the localStorage body BEFORE it fetches, so a boot that raced an unanswering backend left
 * the guard set AND the index non-empty — permanently closed on whatever the previous session (or
 * the previous VERSION) had cached. Success is recorded where success happens.
 */
let bootSucceeded = false;
/** Shared in-flight boot, so a readiness-driven re-attempt joins the request rather than racing it. */
let inFlightBoot: Promise<void> | null = null;
let listeners: Set<() => void> = new Set();

interface CachedEntry {
  body: ResourceCatalog;
  etag: string;
}

function loadFromStorage(): CachedEntry | null {
  try {
    if (typeof localStorage === 'undefined') return null;
    const bodyJson = localStorage.getItem(STORAGE_KEY_BODY);
    const etag = localStorage.getItem(STORAGE_KEY_ETAG);
    if (!bodyJson || !etag) return null;
    const parsed = JSON.parse(bodyJson);
    if (
      !parsed ||
      typeof parsed !== 'object' ||
      !Array.isArray((parsed as ResourceCatalog).entries)
    ) {
      return null;
    }
    return { body: parsed as ResourceCatalog, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: ResourceCatalog, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // best-effort cache
  }
}

function rebuildIndex(catalog: ResourceCatalog): void {
  const next = new Map<string, Resource>();
  for (const entry of catalog.entries) {
    next.set(entry.id, entry);
  }
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors
    }
  }
}

/**
 * Boot-time fetch of the Resource catalog. Call once from app boot.
 * On subsequent calls, no-op unless the previous fetch failed and the
 * catalog is empty.
 *
 * Returns void; consumers query via `getResource` / `listResources`
 * after `await`-ing this function (or proceed eagerly with the
 * cached body if available).
 */
export async function bootResourceRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchResourceRegistry(baseUrl, fetchImpl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

/**
 * Tempdoc 941 — re-attemptable by construction: until the backend has actually answered, calling
 * `bootResourceRegistry` again refetches (`i18n.ts` `watchForBackendReady` does exactly that on
 * the shell's backend-ready edge).
 */
async function fetchResourceRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch,
): Promise<void> {
  const cached = loadFromStorage();
  if (cached) {
    rebuildIndex(cached.body);
  }

  if (!baseUrl) {
    return;
  }

  try {
    const headers: Record<string, string> = {};
    if (cached?.etag) {
      headers['If-None-Match'] = cached.etag;
    }
    const response = await fetchImpl(`${baseUrl}/api/registry/resources`, {
      headers,
    });

    if (response.status === 304) {
      bootSucceeded = true;
      return;
    }

    if (!response.ok) {
      console.debug(
        `[ResourceCatalogClient] /api/registry/resources returned ${response.status}; ` +
          'using cached catalog if any',
      );
      return;
    }

    // Tempdoc 560 §4c: validate the raw wire against the GENERATED Zod at the parse boundary (a
    // mismatch logs `[WireContract]` loudly), then assert the tightened FE shape. The Zod is the
    // single runtime authority; the always-present fields are asserted post-validation.
    const raw: unknown = await response.json();
    const body = parseWireContract(
      resourceCatalogSchema,
      raw,
      'GET /api/registry/resources',
    ) as ResourceCatalog;
    if (body && Array.isArray(body.entries)) {
      const etag = response.headers.get('ETag') ?? '';
      if (etag) saveToStorage(body, etag);
      rebuildIndex(body);
      bootSucceeded = true;
    } else {
      console.debug(
        '[ResourceCatalogClient] response missing `entries` array; cached catalog retained',
      );
    }
  } catch (err) {
    console.debug(
      '[ResourceCatalogClient] /api/registry/resources fetch failed; cached catalog retained',
      err,
    );
  }
}

/** Synchronous lookup. `undefined` when the id isn't in the catalog. */
export function getResource(id: string): Resource | undefined {
  return entriesById.get(id);
}

/** Snapshot of all entries. Read-only — mutations don't propagate back. */
export function listResources(): Resource[] {
  return Array.from(entriesById.values());
}

/** Filtered by Category axis. */
export function listResourcesByCategory(category: Category): Resource[] {
  return listResources().filter((r) => r.category === category);
}

/**
 * Subscribe to catalog changes. The listener fires after a successful
 * fetch updates the in-memory catalog. Returns an unsubscribe function.
 */
export function onCatalogChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Mid-session refresh of the Resource catalog (slice 3a-1-8e). Bypasses
 * the boot-time guard; intended for invocation in response to a
 * `catalog-membership-changed { category: "resource" }` contract event.
 *
 * Discharges the slice 3a.1.9 §B.B.E.1 deferred follow-up: catalog
 * mutability becomes a real concern when 3a-1-8d Phase 2+ ships content
 * evolution; this function gives consumers a way to react.
 */
export async function refreshResourceCatalog(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (!baseUrl) return;
  try {
    const response = await fetchImpl(`${baseUrl}/api/registry/resources`);
    if (!response.ok) {
      console.debug(
        `[ResourceCatalogClient] refresh: /api/registry/resources returned ${response.status}; cached catalog retained`,
      );
      return;
    }
    // Tempdoc 560 §4c: validate the raw wire against the GENERATED Zod at the parse boundary (a
    // mismatch logs `[WireContract]` loudly), then assert the tightened FE shape. The Zod is the
    // single runtime authority; the always-present fields are asserted post-validation.
    const raw: unknown = await response.json();
    const body = parseWireContract(
      resourceCatalogSchema,
      raw,
      'GET /api/registry/resources',
    ) as ResourceCatalog;
    if (body && Array.isArray(body.entries)) {
      const etag = response.headers.get('ETag') ?? '';
      if (etag) saveToStorage(body, etag);
      rebuildIndex(body);
    } else {
      console.debug(
        '[ResourceCatalogClient] refresh: response missing `entries` array; cached catalog retained',
      );
    }
  } catch (err) {
    console.debug(
      '[ResourceCatalogClient] refresh: /api/registry/resources fetch failed; cached catalog retained',
      err,
    );
  }
}

/**
 * Slice 447-impl-C — merge plugin-contributed Resources into the live
 * catalog. Mirrors `mergePluginSurfaceContributions` (slice 471 / 465)
 * for parity. Each entry is keyed by `id`; existing entries with the
 * same id are replaced (the plugin contribution wins for THIS specific
 * id). Listeners fire after the merge.
 *
 * Per 447 §X.3.3 (revised in §X.10): plugin-overlay scope tightens to
 * ResourceCatalog only for V1. Other primitive catalogs (Operation,
 * Prompt, DiagnosticChannel) gate on named consumer slices per
 * C-018 (substrate-without-consumer discipline).
 *
 * Wire shape parity with the V1.5 surface-contributions pattern: the
 * plugin SDK provides a `Resource` payload; this function attaches
 * provenance based on the plugin's trust tier and contributor id.
 */
export function mergePluginResourceContributions(
  entries: ReadonlyArray<{
    pluginId: string;
    contribution: Omit<Resource, 'provenance'>;
    // 548 §4.3 — the uniform Provenance minted once at the PluginRegistry
    // install site, stored verbatim. The catalog no longer reconstructs a lossy
    // {tier, contributorId, version} partial (which dropped identity/capability/
    // installedAt). One authority, projected — not re-derived per merge site.
    provenance: Provenance;
  }>,
): void {
  const next = new Map(entriesById);
  for (const entry of entries) {
    const merged: Resource = {
      ...entry.contribution,
      provenance: entry.provenance,
    };
    next.set(merged.id, merged);
  }
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors — same discipline as the surface client
    }
  }
}

/**
 * Slice 447-impl-C — remove a plugin's contributed Resources. Called
 * during plugin uninstall so consumers (search/index views) stop
 * resolving the plugin's resource ids. Filter is by
 * `pluginId === provenance.contributorId`.
 */
export function removePluginResourceContributions(pluginId: string): void {
  const next = new Map(entriesById);
  let changed = false;
  for (const [id, resource] of entriesById) {
    if (resource.provenance.contributorId === pluginId) {
      next.delete(id);
      changed = true;
    }
  }
  if (!changed) return;
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow listener errors
    }
  }
}

/** Test-only: reset module state. */
export function __resetForTest(): void {
  entriesById = new Map();
  bootSucceeded = false;
  inFlightBoot = null;
  listeners = new Set();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // ignore
  }
}

/** Test-only: seed the catalog directly without an HTTP call. */
export function __seedForTest(catalog: ResourceCatalog): void {
  rebuildIndex(catalog);
  bootSucceeded = true;
}
