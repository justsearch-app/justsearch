// SPDX-License-Identifier: Apache-2.0
/**
 * statusPoll — shared /api/status pub-sub poller (slice 461).
 * Mirrors the inferencePoll pattern. Single-fetch fan-out.
 *
 * Tempdoc 557 §2.B (tier-1 collapse): the snapshot type IS the generated wire
 * authority `StatusResponse` — which carries the full tri-state `readiness`
 * (per-component `state` + `stale`) and `composites`, plus every leaf the
 * surfaces read. The former hand-written lossy interface (which silently
 * stripped readiness/composites/stale) is deleted; `StatusSnapshot` is kept
 * only as a name-alias so the poller's consumers stay stable while the single
 * authority is the generated type.
 */
import type { StatusResponse } from '../../api/generated/index.js';
import { parseWireContract } from '../../api/schemas.js';
import { statusResponseSchema } from '../../api/generated/schema-types/status-response.js';
import { authorizedFetch } from '../api/authorizedFetch.js';

export type StatusSnapshot = StatusResponse;

type Listener = (snapshot: StatusSnapshot | null) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let controller: AbortController | null = null;
let inFlight: Promise<void> | null = null;
let queuedRefresh: Promise<void> | null = null;
let lastSnapshot: StatusSnapshot | null = null;
let apiBase = '';

const INTERVAL_MS = 10000;

function fetchOnce(): Promise<void> {
  if (!controller) return Promise.resolve();
  if (inFlight) return inFlight;
  const current = controller;
  inFlight = readSnapshot(current).finally(() => {
    if (controller === current) inFlight = null;
  });
  return inFlight;
}

async function readSnapshot(current: AbortController): Promise<void> {
  try {
    const res = await authorizedFetch((apiBase || '') + '/api/status', { signal: current.signal });
    if (controller !== current) return;
    if (!res.ok) {
      publish(current, null);
      return;
    }
    // Tempdoc 564 Phase A (status collapse): validate the raw /api/status response against the
    // single generated wire contract (`statusResponseSchema`, record → JSON Schema → Zod) at the
    // parse boundary — the faithful, non-fail-open gate (`[WireContract]` on drift). `StatusSnapshot`
    // is now that same generated `StatusResponse` (the barrel re-exports schema-types), so the
    // validated value IS the consumer type — no cross-projection cast.
    const raw = await res.json();
    if (controller !== current) return;
    const data: StatusSnapshot = parseWireContract(statusResponseSchema, raw, 'GET /api/status');
    lastSnapshot = data;
    publish(current, data);
  } catch {
    publish(current, null);
  }
}

function publish(current: AbortController, snapshot: StatusSnapshot | null): void {
  for (const listener of listeners) {
    if (controller !== current) return;
    listener(snapshot);
  }
}

function ensureRunning(): void {
  if (controller) return;
  controller = new AbortController();
  void fetchOnce();
  timer = window.setInterval(() => void fetchOnce(), INTERVAL_MS);
}

function stop(): void {
  const obsolete = controller;
  controller = null;
  inFlight = null;
  queuedRefresh = null;
  obsolete?.abort();
  if (timer !== null) {
    window.clearInterval(timer);
    timer = null;
  }
  lastSnapshot = null;
}

/**
 * Tempdoc 727 F-8 — force an immediate `/api/status` fetch, bypassing the interval wait. A caller
 * that just performed an action the backend reflects in this snapshot (e.g. unlocking chat
 * encryption) can call this so dependent projections (the DATA PROTECTION row) catch up to the
 * fresh truth immediately instead of waiting up to `INTERVAL_MS` for the next scheduled poll.
 * If a request is already in flight, queue a fresh read after it; interval ticks still join the
 * current request. A no-op (resolves immediately) when no subscriber is currently polling.
 */
export function refreshStatusNow(): Promise<void> {
  if (listeners.size === 0) return Promise.resolve();
  if (!inFlight) return fetchOnce();
  if (queuedRefresh) return queuedRefresh;
  const current = controller;
  queuedRefresh = inFlight.then(() => {
    if (controller !== current) return;
    queuedRefresh = null;
    return fetchOnce();
  });
  return queuedRefresh;
}

export function subscribeStatus(listener: Listener): () => void {
  listeners.add(listener);
  if (lastSnapshot !== null) listener(lastSnapshot);
  ensureRunning();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) stop();
  };
}

export function setStatusApiBase(base: string): void {
  if (apiBase !== base) {
    apiBase = base;
    if (controller) {
      stop();
      ensureRunning();
    }
  }
}

export function __resetStatusPollForTest(): void {
  stop();
  listeners.clear();
  apiBase = '';
}
