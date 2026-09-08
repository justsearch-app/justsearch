// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.4 — FE client for `/api/registry/operations`.
 *
 * Parallel to {@link ResourceCatalogClient} but for the Operation
 * primitive. Fetches the merged `core` + `agent-tools` catalog
 * envelope (per slice 3a.1.2's RegistryController.handleOperations),
 * caches by ETag, exposes `getOperation(id)` / `listOperations()`.
 *
 * Used by `<jf-row-actions>` (slice 3a.1.9 §A.7) to look up an
 * Operation's `presentation.labelKey` for i18n + its `policy.confirm`
 * for risk-driven UX. Used by `<jf-resource-view>` to render
 * `itemOperations` from a Resource's catalog declaration.
 */

import type {
  Operation,
  OperationCatalog,
} from '../types/registry.js';
import { operationCatalogSchema } from '../types/registry.js';
import { parseWireContract } from '../schemas.js';

// Tempdoc 511-followup Track E (2026-05-18): the normalizer block
// that used to live here (upper* helpers + normalizeOperationFromWire
// + normalizeCatalog) was a band-aid over UIOperationEmitter's
// inconsistent casing — lowercase risk/audit/tier/executors via
// `.toLowerCase()`, `confirm.type` discriminator. Track E flipped
// the emitter to emit canonical uppercase enum names + the
// `confirm.kind` discriminator the FE type already used. The
// normalization is now a passthrough by contract; if the wire ever
// breaks the contract, the per-strategy behavioral Pass-8 tests
// catch it at the next FE build.
//
// Storage cache key bumped to v3 to invalidate v2 entries that
// were normalized-from-lowercase; clients refetch on next boot.
const STORAGE_KEY_BODY = 'justsearch.operationCatalog.body.v3';
const STORAGE_KEY_ETAG = 'justsearch.operationCatalog.etag.v3';

let entriesById: Map<string, Operation> = new Map();
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
  body: OperationCatalog;
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
      !Array.isArray((parsed as OperationCatalog).entries)
    ) {
      return null;
    }
    return { body: parsed as OperationCatalog, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: OperationCatalog, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // best-effort cache
  }
}

function rebuildIndex(catalog: OperationCatalog): void {
  const next = new Map<string, Operation>();
  for (const entry of catalog.entries) {
    next.set(entry.id, entry);
  }
  entriesById = next;
  for (const listener of listeners) {
    try {
      listener();
    } catch {
      // swallow
    }
  }
}

export async function bootOperationRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchOperationCatalog(baseUrl, fetchImpl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

/**
 * Tempdoc 941 — re-attemptable by construction: until the backend has actually answered, calling
 * `bootOperationRegistry` again refetches (`i18n.ts` `watchForBackendReady` does exactly that on
 * the shell's backend-ready edge).
 */
async function fetchOperationCatalog(
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
    const response = await fetchImpl(`${baseUrl}/api/registry/operations`, {
      headers,
    });

    if (response.status === 304) {
      bootSucceeded = true;
      return;
    }

    if (!response.ok) {
      console.debug(
        `[OperationCatalogClient] /api/registry/operations returned ${response.status}; ` +
          'using cached catalog if any',
      );
      return;
    }

    // Tempdoc 560 §4c Phase B: validate the raw wire against the GENERATED Zod at the parse boundary
    // (a mismatch logs `[WireContract]` loudly), then assert the tightened FE shape. The generated
    // operationWireSchema is the single runtime authority for the Operation wire shape.
    const raw: unknown = await response.json();
    const body = parseWireContract(
      operationCatalogSchema,
      raw,
      'GET /api/registry/operations',
    ) as unknown as OperationCatalog;
    if (body && Array.isArray(body.entries)) {
      const etag = response.headers.get('ETag') ?? '';
      if (etag) saveToStorage(body, etag);
      rebuildIndex(body);
      bootSucceeded = true;
    }
  } catch (err) {
    console.debug(
      '[OperationCatalogClient] fetch failed; cached catalog retained',
      err,
    );
  }
}

export function getOperation(id: string): Operation | undefined {
  return entriesById.get(id);
}

export function listOperations(): Operation[] {
  return Array.from(entriesById.values());
}

export function onCatalogChange(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Mid-session refresh of the Operation catalog (slice 3a-1-8e). Bypasses
 * the boot-time guard; intended for invocation in response to a
 * `catalog-membership-changed { category: "operation" }` contract event.
 *
 * Discharges the slice 3a.1.9 §B.B.E.1 deferred follow-up alongside the
 * sibling `ResourceCatalogClient.refreshResourceCatalog`.
 */
export async function refreshOperationCatalog(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (!baseUrl) return;
  try {
    const response = await fetchImpl(`${baseUrl}/api/registry/operations`);
    if (!response.ok) {
      console.debug(
        `[OperationCatalogClient] refresh: /api/registry/operations returned ${response.status}; cached catalog retained`,
      );
      return;
    }
    // Tempdoc 560 §4c Phase B: validate the raw wire against the GENERATED Zod at the parse boundary
    // (a mismatch logs `[WireContract]` loudly), then assert the tightened FE shape. The generated
    // operationWireSchema is the single runtime authority for the Operation wire shape.
    const raw: unknown = await response.json();
    const body = parseWireContract(
      operationCatalogSchema,
      raw,
      'GET /api/registry/operations',
    ) as unknown as OperationCatalog;
    if (body && Array.isArray(body.entries)) {
      const etag = response.headers.get('ETag') ?? '';
      if (etag) saveToStorage(body, etag);
      rebuildIndex(body);
    }
  } catch (err) {
    console.debug(
      '[OperationCatalogClient] refresh: fetch failed; cached catalog retained',
      err,
    );
  }
}

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

export function __seedForTest(catalog: OperationCatalog): void {
  rebuildIndex(catalog);
  bootSucceeded = true;
}
