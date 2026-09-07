// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 448 phase 5 — FE client for `/api/registry/diagnostic-channels`.
 *
 * Mirrors {@link ./ResourceCatalogClient.ts} structurally: localStorage-cached
 * body + ETag, conditional GET on subsequent boots, defensive fallback on
 * missing data. Per slice 448 §B.B.G decision: a parallel implementation rather
 * than a generic `CatalogClient<T>` extraction. With three primitives sharing
 * roughly the same client shape (Resource, Operation, DiagnosticChannel), the
 * generic-extraction cost-benefit is borderline; CLAUDE.md guidance is to
 * prefer concrete code over premature abstraction. Extraction lands when a
 * fourth primitive's client provides genuine pressure.
 *
 * Lookup contract:
 *  - `getDiagnosticChannel(id)`: synchronous lookup; `undefined` when the id
 *    isn't in the catalog.
 *  - `listDiagnosticChannels()`: snapshot of all entries.
 *  - `onDiagnosticChannelCatalogChange(listener)`: invoked after a fresh fetch
 *    updates the in-memory catalog.
 */

import type {
  DiagnosticChannel,
  DiagnosticChannelCatalog,
  DiagnosticChannelRef,
} from '../types/diagnostic.js';
import { diagnosticChannelCatalogSchema } from '../types/diagnostic.js';
import { parseWireContract } from '../schemas.js';

const STORAGE_KEY_BODY = 'justsearch.diagnosticChannelCatalog.body';
const STORAGE_KEY_ETAG = 'justsearch.diagnosticChannelCatalog.etag';

let entriesById: Map<string, DiagnosticChannel> = new Map();
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
  body: DiagnosticChannelCatalog;
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
      !Array.isArray((parsed as DiagnosticChannelCatalog).entries)
    ) {
      return null;
    }
    return { body: parsed as DiagnosticChannelCatalog, etag };
  } catch {
    return null;
  }
}

function saveToStorage(body: DiagnosticChannelCatalog, etag: string): void {
  try {
    if (typeof localStorage === 'undefined') return;
    localStorage.setItem(STORAGE_KEY_BODY, JSON.stringify(body));
    localStorage.setItem(STORAGE_KEY_ETAG, etag);
  } catch {
    // best-effort cache
  }
}

function rebuildIndex(catalog: DiagnosticChannelCatalog): void {
  const next = new Map<string, DiagnosticChannel>();
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
 * Boot-time fetch of the DiagnosticChannel catalog. Call from app boot (see `i18n.ts`).
 * No-op once the backend has actually ANSWERED (200 or 304); until then every call re-attempts,
 * which is what makes the boot list safe to re-run on the shell's backend-ready edge
 * (`i18n.ts` `watchForBackendReady`, tempdoc 941).
 */
export async function bootDiagnosticChannelRegistry(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  if (bootSucceeded) return;
  if (inFlightBoot) return inFlightBoot;
  inFlightBoot = fetchDiagnosticChannelCatalog(baseUrl, fetchImpl).finally(() => {
    inFlightBoot = null;
  });
  return inFlightBoot;
}

async function fetchDiagnosticChannelCatalog(
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
    const response = await fetchImpl(`${baseUrl}/api/registry/diagnostic-channels`, {
      headers,
    });

    if (response.status === 304) {
      bootSucceeded = true;
      return;
    }

    if (!response.ok) {
      console.debug(
        `[DiagnosticChannelCatalogClient] /api/registry/diagnostic-channels returned ${response.status}; ` +
          'using cached catalog if any',
      );
      return;
    }

    // Tempdoc 560 §4c: validate the raw wire against the GENERATED Zod at the parse boundary (a
    // mismatch logs `[WireContract]` loudly, non fail-open), then assert the FE shape. The generated
    // diagnosticChannelWireSchema is the single runtime authority for the element shape.
    const raw: unknown = await response.json();
    const body = parseWireContract(
      diagnosticChannelCatalogSchema,
      raw,
      'GET /api/registry/diagnostic-channels',
    ) as DiagnosticChannelCatalog;
    if (body && Array.isArray(body.entries)) {
      const etag = response.headers.get('ETag') ?? '';
      if (etag) saveToStorage(body, etag);
      rebuildIndex(body);
      bootSucceeded = true;
    } else {
      console.debug(
        '[DiagnosticChannelCatalogClient] response missing `entries` array; cached catalog retained',
      );
    }
  } catch (err) {
    console.debug(
      '[DiagnosticChannelCatalogClient] /api/registry/diagnostic-channels fetch failed; cached catalog retained',
      err,
    );
  }
}

/** Synchronous lookup. `undefined` when the id isn't in the catalog. */
export function getDiagnosticChannel(
  id: DiagnosticChannelRef,
): DiagnosticChannel | undefined {
  return entriesById.get(id);
}

/** Snapshot of all entries. Read-only — mutations don't propagate back. */
export function listDiagnosticChannels(): DiagnosticChannel[] {
  return Array.from(entriesById.values());
}

/**
 * Subscribe to catalog changes. The listener fires after a successful fetch
 * updates the in-memory catalog. Returns an unsubscribe function.
 */
export function onDiagnosticChannelCatalogChange(
  listener: () => void,
): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
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
export function __seedForTest(catalog: DiagnosticChannelCatalog): void {
  rebuildIndex(catalog);
  bootSucceeded = true;
}
