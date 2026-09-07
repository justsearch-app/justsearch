// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C0) — FE client for `/api/registry/shapes`.
 *
 * Parallel to {@link SurfaceCatalogClient} but for the ConversationShape
 * Manifest tier. Fetches the merged catalog envelope, caches by ETag, exposes
 * synchronous lookups + change-listener subscriptions.
 *
 * <p>Per the §9.E A3 finding: SurfaceCatalogClient is the chosen template
 * (plugin-aware out of the gate). C5 adds `mergePluginShapeContributions` +
 * `removePluginShapeContributions` mirroring SurfaceCatalogClient's
 * plugin-merge layer; C0 ships catalog-fetch + index + listeners only.
 *
 * <p>Consumed by:
 * - `<jf-chat-shape-mount>` to resolve `shape-id` → ConversationShape entry.
 * - `viewFactoryRegistry` for runtime view-factory lookup keyed by shape ref.
 * - The C5 `check-shape-view-coverage.mjs` CI script (offline mode reads the
 *   in-memory catalog snapshot through this client's `listShapes()`).
 */

import type {
  ConversationShape,
  ConversationShapeCatalog,
  ConversationShapeRef,
} from '../types/conversation-shape.js';

const STORAGE_KEY_BODY = 'justsearch.conversationShapeCatalog.body';
const STORAGE_KEY_ETAG = 'justsearch.conversationShapeCatalog.etag';

let entriesById: Map<string, ConversationShape> = new Map();
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
const listeners: Set<() => void> = new Set();

interface CachedEntry {
  body: ConversationShapeCatalog;
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
      !Array.isArray((parsed as ConversationShapeCatalog).entries)
    ) {
      return null;
    }
    return { body: parsed as ConversationShapeCatalog, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: ConversationShapeCatalog, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // best-effort cache; quota errors / private-mode swallow
  }
}

function rebuildIndex(catalog: ConversationShapeCatalog): void {
  const next = new Map<string, ConversationShape>();
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
 * Boot-time fetch of the ConversationShape catalog. Call once from app boot
 * (alongside `bootSurfaceRegistry`).
 *
 * First-install retry mirrors `bootSurfaceRegistry`: when there's no cached
 * catalog and the initial fetch fails (transient backend boot race), retry
 * with capped exponential backoff so the FE chat-shape mount doesn't end up
 * permanently empty until refresh.
 */
const FIRST_INSTALL_RETRY_DELAYS_MS = [500, 1000, 2000, 4000];

export async function bootConversationShapeRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchConversationShapeCatalog(baseUrl, fetchImpl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

/**
 * Tempdoc 941 — the boot is re-attemptable: calling it again after an unanswered attempt refetches
 * (`i18n.ts` `watchForBackendReady` does exactly that on the shell's backend-ready edge). The
 * first-install backoff above covers a backend that is merely SLOW; the re-attempt covers one that
 * was not there at all when the document loaded.
 */
async function fetchConversationShapeCatalog(
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

  const isFirstInstall = !cached && entriesById.size === 0;
  const maxAttempts = isFirstInstall ? 1 + FIRST_INSTALL_RETRY_DELAYS_MS.length : 1;

  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    if (attempt > 0) {
      const delay = FIRST_INSTALL_RETRY_DELAYS_MS[attempt - 1] ?? 4000;
      await new Promise((r) => setTimeout(r, delay));
    }
    const populated = await tryFetchAndPopulate(baseUrl, fetchImpl, cached?.etag);
    if (populated) {
      // 941 — the backend answered AND the catalog is populated: the boot is closed.
      bootSucceeded = true;
      return;
    }
    if (!isFirstInstall) return;
  }
}

async function tryFetchAndPopulate(
  baseUrl: string,
  fetchImpl: typeof fetch,
  etag: string | undefined,
): Promise<boolean> {
  try {
    const headers: Record<string, string> = {};
    if (etag) headers['If-None-Match'] = etag;
    const response = await fetchImpl(`${baseUrl}/api/registry/shapes`, {
      headers,
    });

    if (response.status === 304) {
      return entriesById.size > 0;
    }

    if (!response.ok) {
      console.debug(
        `[ConversationShapeCatalogClient] /api/registry/shapes returned ${response.status}`,
      );
      return false;
    }

    let body: ConversationShapeCatalog;
    try {
      body = (await response.json()) as ConversationShapeCatalog;
    } catch (parseErr) {
      console.debug(
        '[ConversationShapeCatalogClient] response body parse failed',
        parseErr,
      );
      return false;
    }
    if (!body || !Array.isArray(body.entries)) {
      console.debug(
        '[ConversationShapeCatalogClient] response missing `entries` array; cached catalog retained',
      );
      return false;
    }
    const newEtag = response.headers.get('ETag') ?? '';
    if (newEtag) saveToStorage(body, newEtag);
    rebuildIndex(body);
    return entriesById.size > 0;
  } catch (err) {
    console.debug(
      '[ConversationShapeCatalogClient] /api/registry/shapes fetch failed',
      err,
    );
    return false;
  }
}

/** Synchronous lookup. `undefined` when the id isn't in the catalog. */
export function getShape(id: ConversationShapeRef): ConversationShape | undefined {
  return entriesById.get(id);
}

/** Snapshot of all entries. Read-only — mutations don't propagate back. */
export function listShapes(): ConversationShape[] {
  return Array.from(entriesById.values());
}

/**
 * Subscribe to catalog changes. The listener fires after a successful fetch
 * updates the in-memory catalog. Returns an unsubscribe function.
 */
export function onConversationShapeCatalogChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

// ============================================================
// Test-only helpers
// ============================================================

/** Reset internal state between tests. */
export function __resetConversationShapeCatalogForTest(): void {
  entriesById = new Map();
  bootSucceeded = false;
  inFlightBoot = null;
  listeners.clear();
  try {
    if (typeof localStorage !== 'undefined') {
      localStorage.removeItem(STORAGE_KEY_BODY);
      localStorage.removeItem(STORAGE_KEY_ETAG);
    }
  } catch {
    // swallow
  }
}

/** Seed the catalog directly for unit tests (skips fetch path). */
export function __seedConversationShapeCatalogForTest(
  catalog: ConversationShapeCatalog,
): void {
  bootSucceeded = true;
  rebuildIndex(catalog);
}
