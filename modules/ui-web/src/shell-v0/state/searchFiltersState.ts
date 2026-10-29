// SPDX-License-Identifier: Apache-2.0
/** Session-only Search restrictions. Persisted pins retain their date-only type. */
import type { SearchFilterSpec as PinFilterSpec } from './UserStateDocument.js';
import type { SearchFilters } from '../../api/domains/search.js';

export type SizeUnit = 'KB' | 'MB' | 'GB';
interface SizeInput { readonly text: string; readonly unit: SizeUnit }
export interface SearchFilterSpec extends PinFilterSpec {
  readonly pathPrefix?: string;
  readonly sizeFromBytes?: number;
  readonly sizeToBytes?: number;
  readonly sizeFromInput?: SizeInput;
  readonly sizeToInput?: SizeInput;
}

/** The generated Engine filter contract, shared by Search and retrieval-backed Ask. */
export function restrictionWire(spec: SearchFilterSpec): SearchFilters {
  const out: SearchFilters = {};
  if (spec.pathPrefix !== undefined) out.pathPrefix = spec.pathPrefix;
  const from = spec.modifiedFromMs === 0 ? undefined : spec.modifiedFromMs;
  const to = spec.modifiedToMs === 0 ? undefined : spec.modifiedToMs;
  if (from !== undefined || to !== undefined) {
    out.modifiedAt = { ...(from !== undefined ? { fromMs: from } : {}),
      ...(to !== undefined ? { toMs: to } : {}) };
  }
  if (spec.sizeFromBytes !== undefined || spec.sizeToBytes !== undefined) {
    out.sizeBytes = { ...(spec.sizeFromBytes !== undefined ? { fromBytes: spec.sizeFromBytes } : {}),
      ...(spec.sizeToBytes !== undefined ? { toBytes: spec.sizeToBytes } : {}) };
  }
  return out;
}

const EMPTY: SearchFilterSpec = Object.freeze({});

let current: SearchFilterSpec = EMPTY;
const listeners = new Set<(s: SearchFilterSpec) => void>();

/** Snapshot of the active filter spec. */
export function getFilters(): SearchFilterSpec {
  return current;
}

/**
 * Subscribe to filter changes. Listener fires once with the
 * current value on subscribe, then on every mutating call.
 * Returns an unsubscribe handle.
 */
export function subscribeFilters(
  listener: (s: SearchFilterSpec) => void,
): () => void {
  listeners.add(listener);
  try {
    listener(current);
  } catch {
    // Match userConfigState posture: swallow listener errors.
  }
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Replace only the active date range. Pass `undefined` for either
 * bound to omit it. Passing both `undefined` clears only the date restriction.
 *
 * No-op when the new spec is value-equal to the current one
 * (saves listeners from spurious fires).
 */
export function setFilterRange(
  fromMs: number | undefined,
  toMs: number | undefined,
): void {
  const { modifiedFromMs: _from, modifiedToMs: _to, ...rest } = current;
  setFilters({ ...rest,
    ...(typeof fromMs === 'number' && Number.isFinite(fromMs) ? { modifiedFromMs: fromMs } : {}),
    ...(typeof toMs === 'number' && Number.isFinite(toMs) ? { modifiedToMs: toMs } : {}) });
}

/** Replace live restrictions only. Never writes pins, storage or the address. */
export function setFilters(next: SearchFilterSpec): void {
  if (JSON.stringify(next) === JSON.stringify(current)) return;
  current = Object.freeze({ ...next });
  for (const listener of listeners) {
    try { listener(current); } catch { /* Same isolation as the other session stores. */ }
  }
}

export function clearFilters(): void { setFilters(EMPTY); }

export function hasActiveFilter(spec: SearchFilterSpec): boolean {
  return Object.keys(restrictionWire(spec)).length > 0;
}

// ----- Tempdoc 577 Goal 1 Phase 6 (Move E) — keyword facet selections -----
//
// The clickable half of dual filtering: per-field value selections projected
// from the response's emitted facet counts. Session-ephemeral like the date
// range (not persisted, not yet pinned — pin-snapshot integration is a named
// follow-up). The selections feed buildSearchIntent, which maps them onto the
// wire's existing keyword filters (fileKind / mimeBase / language / metaAuthor).

let facetSelections: Record<string, string[]> = {};
const facetListeners = new Set<(s: Record<string, string[]>) => void>();

/** Snapshot of the active facet selections (field → selected values). */
export function getFacetSelections(): Record<string, string[]> {
  return facetSelections;
}

export function subscribeFacetSelections(
  listener: (s: Record<string, string[]>) => void,
): () => void {
  facetListeners.add(listener);
  try {
    listener(facetSelections);
  } catch {
    // Match the date-filter posture: swallow listener errors.
  }
  return () => {
    facetListeners.delete(listener);
  };
}

/** Toggle one facet value on/off; empty fields are dropped from the map. */
export function toggleFacetValue(field: string, value: string): void {
  const cur = facetSelections[field] ?? [];
  const next = cur.includes(value) ? cur.filter((v) => v !== value) : [...cur, value];
  const out: Record<string, string[]> = { ...facetSelections };
  if (next.length === 0) delete out[field];
  else out[field] = next;
  facetSelections = out;
  for (const l of facetListeners) {
    try {
      l(facetSelections);
    } catch {
      // ignore
    }
  }
}

export function clearFacetSelections(): void {
  if (Object.keys(facetSelections).length === 0) return;
  facetSelections = {};
  for (const l of facetListeners) {
    try {
      l(facetSelections);
    } catch {
      // ignore
    }
  }
}

export function hasFacetSelections(): boolean {
  return Object.keys(facetSelections).length > 0;
}

/**
 * Test-only: reset the module-level state. Mirrors
 * `__resetUserStateForTest`. Not exported via barrel files.
 */
export function __resetSearchFiltersForTest(): void {
  current = EMPTY;
  listeners.clear();
  facetSelections = {};
  facetListeners.clear();
}

// ----- Slice 489 §5 — URL substrate adapter -----
//
// Exposes searchFiltersState as a URL-addressable store under the abstract
// storeId "search.filters". The router's URLProjector subscribes for
// state→URL writes; the NavigationHandler (slice 492) calls
// restoreFromSnapshot when realizing a Navigation Intent.

export interface SearchFiltersSnapshot {
  modifiedFromMs?: string | string[];
  modifiedToMs?: string | string[];
}

export function serializeSearchFilters(): SearchFiltersSnapshot {
  const out: SearchFiltersSnapshot = {};
  if (typeof current.modifiedFromMs === 'number') {
    out.modifiedFromMs = String(current.modifiedFromMs);
  }
  if (typeof current.modifiedToMs === 'number') {
    out.modifiedToMs = String(current.modifiedToMs);
  }
  return out;
}

export function restoreSearchFilters(snapshot: SearchFiltersSnapshot): void {
  const fromRaw = Array.isArray(snapshot.modifiedFromMs)
    ? snapshot.modifiedFromMs[0]
    : snapshot.modifiedFromMs;
  const toRaw = Array.isArray(snapshot.modifiedToMs)
    ? snapshot.modifiedToMs[0]
    : snapshot.modifiedToMs;
  const from =
    typeof fromRaw === 'string' && fromRaw.length > 0 ? Number(fromRaw) : undefined;
  const to =
    typeof toRaw === 'string' && toRaw.length > 0 ? Number(toRaw) : undefined;
  setFilterRange(
    typeof from === 'number' && Number.isFinite(from) ? from : undefined,
    typeof to === 'number' && Number.isFinite(to) ? to : undefined,
  );
}
