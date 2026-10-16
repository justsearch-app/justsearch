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
import { observeAdmissionWait } from '../../api/admissionFetch.js';
import {
  bindStatusRefresh, statusReadIssued, statusSnapshotDisplayed, statusSnapshotCanDisplay,
  type StatusReadOutcome,
} from '../../api/statusInvalidation.js';

export type StatusSnapshot = StatusResponse;

type Listener = (snapshot: StatusSnapshot | null, invalidated?: boolean) => void;

const listeners = new Set<Listener>();
let timer: number | null = null;
let controller: AbortController | null = null;
let inFlight: Promise<StatusReadOutcome> | null = null;
let queuedRefresh: Promise<StatusReadOutcome> | null = null;
let lastSnapshot: StatusSnapshot | null = null;
let apiBase = '';
let unbindRefresh: (() => void) | null = null;

const INTERVAL_MS = 10000;
/**
 * A status read the server has not answered within one poll interval is abandoned: aborted, so it
 * holds no connection, and its slot released, so the poll schedule goes on (a fresh read queued
 * behind it runs next; otherwise the next ordinary read starts at once). One interval is the
 * cadence the display already promises: a read still unanswered when the next poll is due has been
 * overtaken by the schedule. It is not a server deadline (the server answers within its own 100 ms
 * budget), and it keeps the existing rules: at most one status read in flight, one read per
 * interval while reads hang. A wait the server itself asked for (a capacity or upgrade refusal with
 * its retry delay) is an answer, not silence, and is honoured.
 */
const READ_BOUND_MS = INTERVAL_MS;
/** The current read's abandonment timer, cleared when the poller stops. */
let readBound: ReturnType<typeof setTimeout> | null = null;

const SKIPPED: StatusReadOutcome = { ok: true };

function fetchOnce(fresh = false): Promise<StatusReadOutcome> {
  if (!controller) return Promise.resolve(SKIPPED);
  if (inFlight) return inFlight;
  const current = controller;
  const read = new AbortController();
  // Aborted when this read is abandoned or when the poller stops.
  const signal = AbortSignal.any([current.signal, read.signal]);
  let bound: ReturnType<typeof setTimeout> | undefined;
  const abandon = () => {
    read.abort();
    if (controller !== current || inFlight !== pending) return;
    inFlight = null;
    if (!queuedRefresh) void fetchOnce();
  };
  const arm = (ms: number) => {
    clearTimeout(bound);
    bound = setTimeout(abandon, ms);
    readBound = bound;
  };
  // A capacity or upgrade refusal is an answer: the server told this read to wait. Its wait is
  // the server's instruction, not a hang; the retried request again gets one interval.
  observeAdmissionWait(signal, (delayMs) => { if (!read.signal.aborted) arm(delayMs + READ_BOUND_MS); });
  const pending: Promise<StatusReadOutcome> = readSnapshot(current, signal, fresh).finally(() => {
    clearTimeout(bound);
    if (readBound === bound) readBound = null;
    if (controller === current && inFlight === pending) inFlight = null;
  });
  arm(READ_BOUND_MS);
  inFlight = pending;
  return pending;
}

/** Settles when {@code signal} aborts, even if the awaited step itself ignores the signal. */
function untilAbandoned<T>(step: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abandoned = () => reject(new DOMException('Status read abandoned', 'AbortError'));
    if (signal.aborted) abandoned();
    signal.addEventListener('abort', abandoned, { once: true });
    step.then(resolve, reject).finally(() => signal.removeEventListener('abort', abandoned));
  });
}

async function readSnapshot(current: AbortController, signal: AbortSignal, fresh: boolean): Promise<StatusReadOutcome> {
  // Bodies without change metadata are ordered by when their read was issued.
  const issued = statusReadIssued();
  try {
    const res = await untilAbandoned(
      authorizedFetch((apiBase || '') + '/api/status' + (fresh ? '?fresh=true' : ''), { signal }), signal);
    if (controller !== current) return SKIPPED;
    if (!res.ok) {
      publish(current, null);
      return { ok: false, retryAfter: res.headers?.get('Retry-After') ?? null };
    }
    // Tempdoc 564 Phase A (status collapse): validate the raw /api/status response against the
    // single generated wire contract (`statusResponseSchema`, record → JSON Schema → Zod) at the
    // parse boundary — the faithful, non-fail-open gate (`[WireContract]` on drift). `StatusSnapshot`
    // is now that same generated `StatusResponse` (the barrel re-exports schema-types), so the
    // validated value IS the consumer type — no cross-projection cast.
    const raw = await untilAbandoned(res.json() as Promise<unknown>, signal);
    if (controller !== current) return SKIPPED;
    const data: StatusSnapshot = parseWireContract(statusResponseSchema, raw, 'GET /api/status');
    if (!statusSnapshotCanDisplay(res, issued)) return { ok: true };
    lastSnapshot = data;
    publish(current, data);
    statusSnapshotDisplayed(res, issued);
    return { ok: true };
  } catch {
    // An abandoned read reports no status, unless a later read has displayed a body since.
    if (!signal.aborted || statusReadIssued() === issued) publish(current, null);
    return { ok: false };
  }
}

function publish(current: AbortController, snapshot: StatusSnapshot | null, invalidated = false): void {
  if (controller !== current) return;
  lastSnapshot = snapshot;
  for (const listener of listeners) {
    if (controller !== current) return;
    if (invalidated) listener(snapshot, true);
    else listener(snapshot);
  }
}

function ensureRunning(): void {
  if (controller) return;
  controller = new AbortController();
  const current = controller;
  unbindRefresh = bindStatusRefresh(refreshCanonical, () => publish(current, null, true),
    // An ordinary read joins an interval poll already in flight.
    () => (listeners.size === 0 ? Promise.resolve(SKIPPED) : fetchOnce()));
  void fetchOnce();
  timer = window.setInterval(() => void fetchOnce(), INTERVAL_MS);
}

function stop(): void {
  unbindRefresh?.();
  unbindRefresh = null;
  const obsolete = controller;
  controller = null;
  inFlight = null;
  queuedRefresh = null;
  obsolete?.abort();
  if (readBound !== null) {
    clearTimeout(readBound);
    readBound = null;
  }
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
  return refreshCanonical().then(() => undefined);
}

/** The fresh canonical read, reporting whether it was refused or failed. */
function refreshCanonical(): Promise<StatusReadOutcome> {
  if (listeners.size === 0) return Promise.resolve(SKIPPED);
  if (!inFlight) return fetchOnce(true);
  if (queuedRefresh) return queuedRefresh;
  const current = controller;
  queuedRefresh = inFlight.then(() => {
    if (controller !== current) return SKIPPED;
    queuedRefresh = null;
    return fetchOnce(true);
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
