// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.5 — Per-Category subscription strategy registry.
 *
 * Each entry maps `(Category, SubscriptionMode)` to a standard
 * data-acquisition + reducer pair. Replaces the per-Resource
 * hand-rolled reducers in `HealthLitView`, `LedgerLitView`, and
 * `CrashListLitView` with one shared strategy per Category × Mode.
 *
 * Wire-shape facts (verified by live probe 2026-05-06):
 *  - LIFECYCLE.snapshot frames carry payload `{kind: 'snapshot', ...}`
 *    plus per-Category extras (e.g., `entries`, `items`, `events`).
 *  - LIFECYCLE.reset → reset state to initial.
 *  - LIFECYCLE.connected/heartbeat/closing → no state change beyond
 *    catalogVersion.
 *  - UPDATE frames carry per-Category typed payloads:
 *    - EVENT_STREAM / HISTORY: payload is the entry record itself.
 *    - TABULAR: payload is `{row: T}` (insert/update) or
 *      `{pathHash: string}` (delete) or `{items: T[]}`
 *      (snapshot-replaced).
 *    - STATE × SSE_STREAM: payload is `{value: T}`.
 *
 * The TABULAR Insert vs Update distinction is currently absent from
 * the wire (slice 445 §B.B follow-up: add `kind` discriminator).
 * Both produce a `{row}` payload shape and are upserted by the FE
 * keyed-map reducer, so semantic equivalence holds for V1.
 *
 * Typed state shape: every strategy returns
 * `{ catalogVersion: number; data: T }`. The renderer reads `data`;
 * the host reads `catalogVersion` for stale-snapshot detection.
 */

// Allowlisted in eslint.config.js — see 511-followup-B.
// SubscriptionStrategy is substrate-adjacent (Category-keyed dispatch
// of the SSE-subscription routing, parallel to the renderer registry).
import type { Resource, Category, SubscriptionMode } from '../../api/types/registry.js';
import type { EnvelopeReducer } from '../streaming/EnvelopeStream.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

/** Common envelope: every strategy's state carries the seq cursor + a typed payload. */
export interface StrategyState<T> {
  catalogVersion: number;
  data: T;
}

/** Default cap for ring buffers (events / entries / log lines). */
const DEFAULT_CAP = 200;

/**
 * Strategy bundle returned per Resource. The host uses these to:
 *  - Initialize state from `initialState`.
 *  - Reduce envelopes via `reducer`.
 *  - For ONE_SHOT / POLLING modes, periodically `fetchSnapshot()`.
 */
export interface SubscriptionStrategy<T> {
  initialState: StrategyState<T>;
  reducer: EnvelopeReducer<StrategyState<T>>;
  /** Optional REST snapshot fetcher; required for ONE_SHOT / POLLING modes. */
  fetchSnapshot?: (baseUrl: string) => Promise<StrategyState<T>>;
  /** Polling cadence in ms; required for POLLING mode. */
  pollIntervalMs?: number;
}

/**
 * State payload type per Category. Renderer-facing.
 */
export type StateData<T = unknown> = T | null;
export interface EventStreamData<T = unknown> {
  events: T[];
  cap: number;
}
export interface HistoryData<T = unknown> {
  entries: T[];
  cap: number;
}
export interface TabularData<T = unknown> {
  /** Items keyed by Resource.primaryKey value. */
  items: Map<string, T>;
  /** primaryKey field name (cached for reducer access). */
  primaryKey: string;
}
// LogTailData deleted in slice 448 phase 6 — Category.LOG_TAIL retired per
// CONFLICT-LEDGER C-012 path-b. Operator-trace surfaces are modeled by the
// sibling DiagnosticChannel primitive (slice 448), with its own strategy at
// `./diagnosticChannelStrategy.ts`.

// ============================================================
// STATE × SSE_STREAM
// ============================================================

function stateSseStrategy<T>(): SubscriptionStrategy<StateData<T>> {
  return {
    initialState: { catalogVersion: 0, data: null },
    reducer: (s, env) => {
      const next = { ...s, catalogVersion: env.seq };
      if (env.frameKind === 'LIFECYCLE') {
        const p = env.payload as { kind?: string; value?: T };
        if (p.kind === 'snapshot' && p.value !== undefined) {
          next.data = p.value;
        } else if (p.kind === 'reset') {
          next.data = null;
        }
      } else if (env.frameKind === 'UPDATE') {
        const p = env.payload as { value?: T };
        if (p.value !== undefined) {
          next.data = p.value;
        } else {
          // Some STATE × SSE_STREAM streams emit the value directly as the payload.
          next.data = env.payload as T;
        }
      }
      return next;
    },
  };
}

// ============================================================
// EVENT_STREAM × SSE_STREAM
// ============================================================

/**
 * Return the declared row identity when this strategy opted into keyed
 * convergence. Identity is deliberately read only from the declared field;
 * operationId and other `*Id` fields are not fallback identities.
 */
type EntryKey = string | number | boolean;

function entryKey<T>(entry: T, primaryKey: string): EntryKey | undefined {
  if (!primaryKey || entry === null || typeof entry !== 'object') return undefined;
  const value = (entry as Record<string, unknown>)[primaryKey];
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string') return value.trim() === '' ? undefined : value;
  if (typeof value === 'number') return Number.isFinite(value) ? value : undefined;
  if (typeof value === 'boolean') return value;
  return undefined;
}

/**
 * Merge rows in arrival order while replacing a previously seen declared key
 * in place. Rows without a usable declared identity remain independent
 * observations. The final slice is the same bounded tail used by the legacy
 * append reducer.
 */
function mergeBoundedRows<T>(
  existing: readonly T[],
  incoming: readonly T[],
  cap: number,
  primaryKey: string,
): T[] {
  if (!primaryKey.trim()) {
    const appended = [...existing, ...incoming];
    return appended.length > cap ? appended.slice(-cap) : appended;
  }

  const merged: T[] = [];
  const positions = new Map<EntryKey, number>();
  for (const row of [...existing, ...incoming]) {
    const key = entryKey(row, primaryKey);
    if (key === undefined) {
      merged.push(row);
      continue;
    }
    const position = positions.get(key);
    if (position === undefined) {
      positions.set(key, merged.length);
      merged.push(row);
    } else {
      // Keep the first-seen slot stable while making the newest value
      // authoritative. This preserves useful ordering under the 200-row cap.
      merged[position] = row;
    }
  }
  return merged.length > cap ? merged.slice(-cap) : merged;
}

function eventStreamStrategy<T>(
  cap: number = DEFAULT_CAP,
  primaryKey = '',
): SubscriptionStrategy<EventStreamData<T>> {
  return {
    initialState: { catalogVersion: 0, data: { events: [], cap } },
    reducer: (s, env) => {
      const next: StrategyState<EventStreamData<T>> = {
        catalogVersion: env.seq,
        data: s.data,
      };
      if (env.frameKind === 'LIFECYCLE') {
        const p = env.payload as { kind?: string; entries?: T[] };
        if (p.kind === 'snapshot' && Array.isArray(p.entries)) {
          next.data = {
            events: mergeBoundedRows([], p.entries, cap, primaryKey),
            cap,
          };
        } else if (p.kind === 'reset') {
          next.data = { events: [], cap };
        }
      } else if (env.frameKind === 'UPDATE') {
        const entry = env.payload as T;
        next.data = {
          events: mergeBoundedRows(s.data.events, [entry], cap, primaryKey),
          cap,
        };
      }
      return next;
    },
  };
}

// ============================================================
// HISTORY × SSE_STREAM
// ============================================================

function historyStrategy<T>(
  cap: number = DEFAULT_CAP,
  primaryKey = '',
): SubscriptionStrategy<HistoryData<T>> {
  // Same wire shape as EVENT_STREAM (slice 444b operation-history).
  // Distinct strategy keeps the type axis (entries vs events) honest
  // for renderers that read the field name.
  return {
    initialState: { catalogVersion: 0, data: { entries: [], cap } },
    reducer: (s, env) => {
      const next: StrategyState<HistoryData<T>> = {
        catalogVersion: env.seq,
        data: s.data,
      };
      if (env.frameKind === 'LIFECYCLE') {
        const p = env.payload as { kind?: string; entries?: T[] };
        if (p.kind === 'snapshot' && Array.isArray(p.entries)) {
          next.data = {
            entries: mergeBoundedRows([], p.entries, cap, primaryKey),
            cap,
          };
        } else if (p.kind === 'reset') {
          next.data = { entries: [], cap };
        }
      } else if (env.frameKind === 'UPDATE') {
        const entry = env.payload as T;
        next.data = {
          entries: mergeBoundedRows(s.data.entries, [entry], cap, primaryKey),
          cap,
        };
      }
      return next;
    },
  };
}

// ============================================================
// TABULAR × SSE_STREAM
// ============================================================

/**
 * TABULAR × ONE_SHOT strategy (slice 3a.1.9 §B.B.D Stream A).
 *
 * REST snapshot: GET endpoint returns `{jobs: T[], count: number}` (slice
 * 3a.1.9's wire shape for the failed-indexing-jobs Resource). Reducer
 * is a no-op (no streaming frames); state hydrates from fetchSnapshot.
 *
 * Same state shape as TABULAR × SSE_STREAM so the renderer doesn't
 * branch on subscription mode.
 */
function tabularOneShotStrategy<T extends Record<string, unknown>>(
  primaryKey: string,
  endpoint: string,
): SubscriptionStrategy<TabularData<T>> {
  if (!primaryKey) {
    throw new Error('TABULAR strategy requires non-blank primaryKey from Resource.primaryKey');
  }
  return {
    initialState: {
      catalogVersion: 0,
      data: { items: new Map(), primaryKey },
    },
    // No-op reducer — ONE_SHOT doesn't consume envelope frames.
    reducer: (s) => s,
    fetchSnapshot: async (baseUrl: string) => {
      const url = (baseUrl || '') + endpoint;
      try {
        const response = await authorizedFetch(url);
        if (!response.ok) {
          return { catalogVersion: 0, data: { items: new Map(), primaryKey } };
        }
        const body = (await response.json()) as { jobs?: T[]; count?: number };
        const items = new Map<string, T>();
        for (const row of body.jobs ?? []) {
          const key = String((row as Record<string, unknown>)[primaryKey]);
          items.set(key, row);
        }
        return { catalogVersion: 1, data: { items, primaryKey } };
      } catch {
        return { catalogVersion: 0, data: { items: new Map(), primaryKey } };
      }
    },
  };
}

export function tabularStrategy<T extends Record<string, unknown>>(
  primaryKey: string,
): SubscriptionStrategy<TabularData<T>> {
  if (!primaryKey) {
    throw new Error('TABULAR strategy requires non-blank primaryKey from Resource.primaryKey');
  }
  return {
    initialState: {
      catalogVersion: 0,
      data: { items: new Map(), primaryKey },
    },
    reducer: (s, env) => {
      const next: StrategyState<TabularData<T>> = {
        catalogVersion: env.seq,
        data: s.data,
      };
      if (env.frameKind === 'LIFECYCLE') {
        const p = env.payload as { kind?: string; items?: T[] };
        if (p.kind === 'snapshot' && Array.isArray(p.items)) {
          const items = new Map<string, T>();
          for (const row of p.items) {
            const key = String((row as Record<string, unknown>)[primaryKey]);
            items.set(key, row);
          }
          next.data = { items, primaryKey };
        } else if (p.kind === 'reset') {
          next.data = { items: new Map(), primaryKey };
        }
      } else if (env.frameKind === 'UPDATE') {
        const p = env.payload as Record<string, unknown>;
        const items = new Map(s.data.items);
        // Slice 3a.1.9 §B.B.B B1: prefer the `kind` discriminator from
        // IndexingJobsChangeRegistry.DeltaEnvelope. Falls back to
        // payload-shape probing for legacy producers (none today;
        // belt-and-suspenders during the transition).
        const kind = typeof p['kind'] === 'string' ? p['kind'] : '';
        const hasItemsField = Array.isArray(p['items']);
        const hasRowField = p['row'] && typeof p['row'] === 'object';
        if (kind === 'snapshot-replaced' || (kind === '' && hasItemsField)) {
          // SnapshotReplaced — full collection replacement.
          items.clear();
          for (const row of (p['items'] as T[]) ?? []) {
            items.set(String((row as Record<string, unknown>)[primaryKey]), row);
          }
        } else if (
          kind === 'insert' ||
          kind === 'update' ||
          (kind === '' && hasRowField)
        ) {
          // Insert / Update upsert. Both carry the row; the FE keyed-map
          // upsert produces semantic equivalence regardless of which
          // variant fired — the discriminator's value is wire clarity, not
          // FE state-machine differentiation.
          const row = p['row'] as T;
          if (row) items.set(String((row as Record<string, unknown>)[primaryKey]), row);
        } else if (kind === 'delete' || kind === '') {
          // Delete — DeltaEnvelope.primaryKeyValue carries the row's
          // primary-key value (generic across TABULAR Resources;
          // doesn't leak the per-Resource field name). Legacy
          // fallback: scan for the Resource's primaryKey field on the
          // payload (slice 445 pre-§B.B.B B1 wire shape used field
          // name = primaryKey directly).
          const value = p['primaryKeyValue'];
          if (typeof value === 'string') {
            items.delete(value);
          } else {
            const legacy = p[primaryKey];
            if (typeof legacy === 'string') items.delete(legacy);
          }
        }
        next.data = { items, primaryKey };
      }
      return next;
    },
  };
}

// LOG_TAIL × SSE_STREAM strategy deleted in slice 448 phase 6 — Category.LOG_TAIL
// retired per CONFLICT-LEDGER C-012 path-b. Operator-trace surfaces are modeled
// by the sibling DiagnosticChannel primitive; the equivalent strategy lives at
// `./diagnosticChannelStrategy.ts`.

// ============================================================
// Strategy lookup
// ============================================================

/**
 * Look up the standard strategy for a Resource's `(category, subscriptionMode)`
 * pair. Returns `null` when no strategy is registered for the combination —
 * the caller falls back to a "Category not yet implemented" placeholder.
 *
 * Strategies are returned with Resource-specific configuration applied:
 * TABULAR always uses `Resource.primaryKey`; EVENT_STREAM and HISTORY opt
 * into keyed snapshot/update convergence only when that declaration is set.
 */
export function strategyFor(
  resource: Resource,
): SubscriptionStrategy<unknown> | null {
  const key = `${resource.category}_${resource.subscriptionMode}`;
  switch (key) {
    case 'STATE_SSE_STREAM':
      return stateSseStrategy() as SubscriptionStrategy<unknown>;
    case 'EVENT_STREAM_SSE_STREAM':
      return eventStreamStrategy(DEFAULT_CAP, resource.primaryKey) as SubscriptionStrategy<unknown>;
    case 'HISTORY_SSE_STREAM':
      return historyStrategy(DEFAULT_CAP, resource.primaryKey) as SubscriptionStrategy<unknown>;
    case 'TABULAR_SSE_STREAM':
      return tabularStrategy(resource.primaryKey) as SubscriptionStrategy<unknown>;
    case 'TABULAR_ONE_SHOT':
      return tabularOneShotStrategy(
        resource.primaryKey,
        resource.endpoint,
      ) as SubscriptionStrategy<unknown>;
    default:
      // STATE × ONE_SHOT, *_POLLING, TIMESERIES — not yet wired.
      // STATE × ONE_SHOT and POLLING modes need REST fetchSnapshot
      // implementations; deferred to follow-up.
      // TIMESERIES has its own poll-driven existing pattern (slice 3a.1.4 Phase 7).
      return null;
  }
}

/** True when the (Category × SubscriptionMode) pair has a registered strategy. */
export function hasStrategyFor(
  category: Category,
  subscriptionMode: SubscriptionMode,
): boolean {
  const key = `${category}_${subscriptionMode}`;
  return [
    'STATE_SSE_STREAM',
    'EVENT_STREAM_SSE_STREAM',
    'HISTORY_SSE_STREAM',
    'TABULAR_SSE_STREAM',
    'TABULAR_ONE_SHOT',
  ].includes(key);
}

// Test-only re-exports for direct strategy access.
export const _strategies = {
  stateSseStrategy,
  eventStreamStrategy,
  historyStrategy,
  tabularStrategy,
};
