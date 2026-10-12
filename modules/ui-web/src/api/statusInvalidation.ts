// SPDX-License-Identifier: Apache-2.0
/** Server publication metadata is the only automatic invalidation authority. */

/** What a canonical read reported back: refused or failed reads back off before the next one. */
export interface StatusReadOutcome { ok: boolean; retryAfter?: string | null }

type Refresh = () => Promise<StatusReadOutcome | void>;

const BASE_BACKOFF_MS = 1_000;
const MAX_BACKOFF_MS = 30_000;

let binding: { refresh: Refresh; invalidate?: () => void } | null = null;
let queued: Promise<void> | null = null;
let displayedEpoch: string | null = null;
let displayedCounter = 0;
let hasDisplayedSnapshot = false;
/** Advances whenever a body is displayed; a read records it when issued. */
let displayGeneration = 0;
let seenEpoch: string | null = null;
let seenCounter = 0;
/** The newest observed value a fresh read was started for: one fresh read per observed value. */
let attemptedEpoch: string | null = null;
let attemptedCounter = -1;
let failures = 0;
let backoffUntil = 0;
const retiredEpochs = new Set<string>();
const responses = new WeakMap<Response, Promise<void>>();

export function bindStatusRefresh(callback: Refresh, invalidate?: () => void): () => void {
  const current = { refresh: callback, invalidate };
  binding = current;
  return () => {
    if (binding === current) {
      binding = null; queued = null;
      displayedEpoch = seenEpoch = attemptedEpoch = null;
      displayedCounter = seenCounter = 0;
      attemptedCounter = -1;
      failures = 0; backoffUntil = 0;
      hasDisplayedSnapshot = false;
      retiredEpochs.clear();
    }
  };
}

function counter(value: unknown): number | null {
  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\d+$/.test(value))) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : null;
}

/** Marks the issue of a canonical read; pass the result to the display checks for its response. */
export function statusReadIssued(): number {
  return displayGeneration;
}

/**
 * Whether a canonical body may replace the display. A body with metadata never replaces a newer
 * epoch or reflected revision; at an equal revision it must answer a read issued after the
 * displayed body was received. A body without metadata is ordered by what this client knows: it
 * may display when no metadata was ever seen, or when it answers a read issued after the displayed
 * body was received and no newer epoch is known to contradict it.
 */
export function statusSnapshotCanDisplay(response: Response, issued: number = displayGeneration): boolean {
  const epoch = response.headers?.get('X-JustSearch-Status-Epoch');
  const revision = counter(response.headers?.get('X-JustSearch-Status-Reflected'));
  const issuedAfterDisplay = issued === displayGeneration;
  if (!epoch || revision === null) {
    if (seenEpoch === null) return true;
    return hasDisplayedSnapshot && seenEpoch === displayedEpoch && issuedAfterDisplay;
  }
  return !retiredEpochs.has(epoch) && (seenEpoch === null || epoch === seenEpoch)
    && (epoch !== displayedEpoch || revision > displayedCounter
      || (revision === displayedCounter && issuedAfterDisplay));
}

/** Called after the validated canonical body has been delivered to snapshot subscribers. */
export function statusSnapshotDisplayed(response: Response, issued: number = displayGeneration): void {
  const epoch = response.headers?.get('X-JustSearch-Status-Epoch');
  const reflected = counter(response.headers?.get('X-JustSearch-Status-Reflected'));
  if (!statusSnapshotCanDisplay(response, issued)) return;
  hasDisplayedSnapshot = true;
  displayGeneration++;
  if (!epoch || reflected === null) return;
  displayedCounter = displayedEpoch === epoch ? Math.max(displayedCounter, reflected) : reflected;
  displayedEpoch = epoch;
  remember(epoch, counter(response.headers?.get('X-JustSearch-Status-Change')) ?? reflected);
}

function remember(epoch: string, value: number): void {
  if (seenEpoch !== epoch) {
    if (seenEpoch) retiredEpochs.add(seenEpoch);
    seenEpoch = epoch; seenCounter = value;
    if (hasDisplayedSnapshot && displayedEpoch !== epoch) binding?.invalidate?.();
  } else seenCounter = Math.max(seenCounter, value);
}

function newer(): boolean {
  return seenEpoch !== null && (hasDisplayedSnapshot && seenEpoch !== displayedEpoch
    || seenCounter > displayedCounter);
}

/** A fresh read is due for a newer value no fresh read was started for, outside any back-off. */
function due(): boolean {
  return newer() && (seenEpoch !== attemptedEpoch || seenCounter > attemptedCounter)
    && Date.now() >= backoffUntil;
}

function retryAfterMs(header: string | null | undefined): number {
  if (!header) return 0;
  const seconds = /^\d+$/.test(header.trim()) ? Number(header) * 1_000 : Date.parse(header) - Date.now();
  return Number.isFinite(seconds) ? Math.min(MAX_BACKOFF_MS * 10, Math.max(0, seconds)) : 0;
}

function backOff(outcome: StatusReadOutcome): void {
  failures++;
  const exponential = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2 ** (failures - 1));
  backoffUntil = Date.now() + Math.max(exponential, retryAfterMs(outcome.retryAfter));
}

export function observeStatusSignal(epoch: unknown, value: unknown): Promise<void> {
  const revision = counter(value);
  if (typeof epoch !== 'string' || !epoch || revision === null || retiredEpochs.has(epoch)) return Promise.resolve();
  remember(epoch, revision);
  if (!binding || !newer()) return Promise.resolve();
  if (queued) return queued;
  if (!due()) return Promise.resolve();
  const current = binding;
  const request = Promise.resolve().then(async () => {
    // At most one fresh read in flight and one per observed value. A change during a read gets
    // one trailing read for the new value. A read that returns without reaching its value starts
    // no other for that value; a refused or failed read backs off. Ordinary polls keep catching up.
    for (let reads = 0; reads < 2; reads++) {
      if (binding !== current || !due()) return;
      attemptedEpoch = seenEpoch; attemptedCounter = seenCounter;
      let outcome: StatusReadOutcome | void;
      try {
        outcome = await current.refresh();
      } catch {
        outcome = { ok: false };
      }
      if (binding !== current) return;
      if (outcome && !outcome.ok) {
        backOff(outcome);
        return;
      }
      failures = 0;
    }
  }).catch(() => { /* Observation failure cannot revoke a completed publication. */ })
    .finally(() => { if (queued === request) queued = null; });
  queued = request;
  return request;
}

/** Fresh reads record metadata without waiting on or recursively scheduling themselves. */
export function observeStatusResponse(input: RequestInfo | URL, response: Response): Promise<void> {
  const url = new URL(typeof Request !== 'undefined' && input instanceof Request ? input.url : String(input), 'http://status.local');
  const existing = responses.get(response);
  if (existing) return existing;
  if (url.pathname === '/api/status') {
    const epoch = response.headers?.get('X-JustSearch-Status-Epoch');
    const revision = counter(response.headers?.get('X-JustSearch-Status-Change'));
    if (epoch && revision !== null && !retiredEpochs.has(epoch)) remember(epoch, revision);
    const reflected = counter(response.headers?.get('X-JustSearch-Status-Reflected'));
    if (url.searchParams.get('fresh') === 'true'
        || (epoch === seenEpoch && reflected !== null && reflected >= seenCounter)) {
      return Promise.resolve();
    }
  }
  const observation = observeStatusSignal(response.headers?.get('X-JustSearch-Status-Epoch'),
    response.headers?.get('X-JustSearch-Status-Change'));
  // The poller still has to consume this body and release its in-flight slot before refreshing.
  const completion = url.pathname === '/api/status' ? Promise.resolve() : observation;
  responses.set(response, completion);
  return completion;
}

export function observeStatusEvent(payload: unknown): Promise<void> {
  if (!payload || typeof payload !== 'object') return Promise.resolve();
  const data = payload as Record<string, unknown>;
  return observeStatusSignal(data.statusChangeEpoch, data.statusChangeCounter);
}
