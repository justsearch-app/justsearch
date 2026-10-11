// SPDX-License-Identifier: Apache-2.0
/** Server publication metadata is the only automatic invalidation authority. */
let binding: { refresh: () => Promise<void>; invalidate?: () => void } | null = null;
let queued: Promise<void> | null = null;
let displayedEpoch: string | null = null;
let displayedCounter = 0;
let hasDisplayedSnapshot = false;
let seenEpoch: string | null = null;
let seenCounter = 0;
const retiredEpochs = new Set<string>();
const responses = new WeakMap<Response, Promise<void>>();

export function bindStatusRefresh(callback: () => Promise<void>, invalidate?: () => void): () => void {
  const current = { refresh: callback, invalidate };
  binding = current;
  return () => {
    if (binding === current) {
      binding = null; queued = null;
      displayedEpoch = seenEpoch = null;
      displayedCounter = seenCounter = 0;
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

/** An older in-flight head response must not replace a snapshot after a restart signal. */
export function statusSnapshotCanDisplay(response: Response): boolean {
  const epoch = response.headers?.get('X-JustSearch-Status-Epoch');
  const revision = counter(response.headers?.get('X-JustSearch-Status-Reflected'));
  if (!epoch || revision === null) return seenEpoch === null;
  return !retiredEpochs.has(epoch) && (seenEpoch === null || epoch === seenEpoch)
    && (epoch !== displayedEpoch || revision >= displayedCounter);
}

/** Called after the validated canonical body has been delivered to snapshot subscribers. */
export function statusSnapshotDisplayed(response: Response): void {
  const epoch = response.headers?.get('X-JustSearch-Status-Epoch');
  const reflected = counter(response.headers?.get('X-JustSearch-Status-Reflected'));
  if (!statusSnapshotCanDisplay(response)) return;
  hasDisplayedSnapshot = true;
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

export function observeStatusSignal(epoch: unknown, value: unknown): Promise<void> {
  const revision = counter(value);
  if (typeof epoch !== 'string' || !epoch || revision === null || retiredEpochs.has(epoch)) return Promise.resolve();
  remember(epoch, revision);
  if (!binding || !newer()) return Promise.resolve();
  if (queued) return queued;
  const current = binding;
  const request = Promise.resolve().then(async () => {
    // A change during a read gets one trailing read. A blocked/stale owner never makes a hot loop:
    // with no new server publication, the next ordinary request or poll retries observation.
    for (let reads = 0; reads < 2; reads++) {
      const targetEpoch = seenEpoch, targetCounter = seenCounter;
      if (binding !== current || !newer()) return;
      await current.refresh();
      if (seenEpoch === targetEpoch && seenCounter === targetCounter) return;
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
