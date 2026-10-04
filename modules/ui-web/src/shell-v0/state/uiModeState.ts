// SPDX-License-Identifier: Apache-2.0
/**
 * uiModeState — the one app-wide "Simple vs Detailed" UI-mode authority
 * (tempdoc 557 Q8).
 *
 * The mode is a persisted user preference (settings `ui.mode`). It was only
 * read inside SettingsSurface, so other surfaces couldn't gate advanced-only
 * affordances on it. This tiny shared store lets any surface subscribe — e.g.
 * Search surfaces hide raw retrieval-trace diagnostics outside Detailed mode
 * (Q8). Seeded from `/api/settings/v2` at boot; SettingsSurface republishes on
 * load + change. Defaults to 'simple' (hide advanced affordances until known).
 */
import { createObservableStore } from './createObservableStore.js';
import { captureSettingsPatch, saveAbsoluteSettings, type SettingsPatch, type SettingsTransport, type SettingsCompletion } from '../../api/settingsAttempt.js';

export type UiMode = 'simple' | 'advanced';

// 569 Phase 0 — the shared observable-value primitive (value + listeners + notify + reset).
const store = createObservableStore<UiMode>('simple');
let revision = 0;
let persistenceTail: Promise<void> = Promise.resolve();
let fallbackPersistenceSequence = 0;
let fallbackPersistenceClientId = createPersistenceClientId();

const PERSISTENCE_CLIENT_STORAGE_KEY = 'justsearch.ui-mode-persistence-client';
const PERSISTENCE_SEQUENCE_STORAGE_KEY = 'justsearch.ui-mode-persistence-sequence';
const PERSISTENCE_SEQUENCE_LOCK = 'justsearch.ui-mode-persistence-sequence';

/** Local settings mutations should fail promptly rather than pinning later user intent forever. */
const UI_MODE_PERSISTENCE_TIMEOUT_MS = 10_000;
/** Backend-recognized ordering token for mode mutations that may outlive an aborted fetch. */
export const UI_MODE_INTENT_HEADER = 'X-JustSearch-UI-Mode-Intent';

interface UiModePersistenceOptions {
  /** Test seam; production callers use {@link UI_MODE_PERSISTENCE_TIMEOUT_MS}. */
  timeoutMs?: number;
}

export function getUiMode(): UiMode {
  return store.get();
}

export function isAdvancedMode(): boolean {
  return store.get() === 'advanced';
}

export function setUiMode(next: UiMode | string | undefined): void {
  const normalized: UiMode = next === 'advanced' ? 'advanced' : 'simple';
  if (store.get() === normalized) return;
  revision += 1;
  store.set(normalized);
}

/** Monotonic in-session revision used to reject stale asynchronous settings reads. */
export function getUiModeRevision(): number {
  return revision;
}

/**
 * Serialize every `ui.mode` write, regardless of which projection initiated it.
 *
 * The writer stays caller-owned because Shell, Settings, and Brain use different
 * host/fetch adapters and persistence feedback. The shared tail owns ordering and a bounded
 * failure path: a later user intent cannot reach `/api/settings/v2` before an earlier one settles,
 * while an aborting timeout prevents one lost response from pinning every later write forever.
 */
export function enqueueUiModePersistence<T>(
  writer: (signal: AbortSignal, intent: string) => Promise<T>,
  options: UiModePersistenceOptions = {},
): Promise<T> {
  return enqueuePersistence(writer, options, false);
}

/** Freeze event-time fields; observe the witness only when this queued writer starts. */
export function enqueueUiModeSettings(
  send: SettingsTransport, patch: SettingsPatch, options: UiModePersistenceOptions = {},
): Promise<SettingsCompletion> {
  const captured = captureSettingsPatch(patch);
  return enqueuePersistence((signal, intent) => saveAbsoluteSettings(send, captured, {
    signal, timeoutMs: options.timeoutMs,
    headers: { [UI_MODE_INTENT_HEADER]: intent },
  }), options, true);
}

function enqueuePersistence<T>(
  writer: (signal: AbortSignal, intent: string) => Promise<T>,
  options: UiModePersistenceOptions,
  writerOwnsDeadline: boolean,
): Promise<T> {
  const controller = new AbortController();
  // Start allocation at enqueue time, not request-start time: this sequence represents user intent
  // order even when an earlier network request is still holding the local persistence queue.
  const intent = allocatePersistenceIntent(controller.signal);
  const result = persistenceTail.then(async () => {
    const timeoutMs = options.timeoutMs ?? UI_MODE_PERSISTENCE_TIMEOUT_MS;
    let timeoutId: ReturnType<typeof setTimeout> | undefined;
    const timeout = new Promise<never>((_resolve, reject) => {
      timeoutId = setTimeout(() => {
        const error = new Error("Couldn't save detail level: the request timed out.");
        error.name = 'TimeoutError';
        controller.abort(error);
        reject(error);
      }, timeoutMs);
    });
    try {
      const persistence = intent.then((allocatedIntent) => {
        if (controller.signal.aborted) throw controller.signal.reason;
        // Allocation is bounded by the queue. Once the settings helper starts, its own
        // deadline retains the frozen attempt identity; a competing queue timer would lose it.
        if (writerOwnsDeadline && timeoutId !== undefined) clearTimeout(timeoutId);
        return writer(controller.signal, allocatedIntent);
      });
      return await Promise.race([persistence, timeout]);
    } finally {
      if (timeoutId !== undefined) clearTimeout(timeoutId);
    }
  });
  persistenceTail = result.then(
    () => undefined,
    () => undefined,
  );
  return result;
}

async function allocatePersistenceIntent(signal: AbortSignal): Promise<string> {
  const locks = globalThis.navigator?.locks;
  if (locks) {
    return locks.request(
      PERSISTENCE_SEQUENCE_LOCK,
      { signal },
      allocateStoredPersistenceIntent,
    );
  }
  return allocateStoredPersistenceIntent();
}

function allocateStoredPersistenceIntent(): string {
  try {
    const storage = globalThis.localStorage;
    let clientId = storage.getItem(PERSISTENCE_CLIENT_STORAGE_KEY);
    if (!clientId) {
      clientId = createPersistenceClientId();
      storage.setItem(PERSISTENCE_CLIENT_STORAGE_KEY, clientId);
    }
    const storedSequence = Number.parseInt(
      storage.getItem(PERSISTENCE_SEQUENCE_STORAGE_KEY) ?? '0',
      10,
    );
    const sequence = Number.isSafeInteger(storedSequence) && storedSequence >= 0
      ? storedSequence + 1
      : 1;
    storage.setItem(PERSISTENCE_SEQUENCE_STORAGE_KEY, String(sequence));
    return `${clientId}:${sequence}`;
  } catch {
    // Storage can be unavailable in hardened/embed contexts. Those contexts retain safe
    // same-page ordering; the normal desktop shell uses durable origin storage + Web Locks.
    return `${fallbackPersistenceClientId}:${++fallbackPersistenceSequence}`;
  }
}

function createPersistenceClientId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return `ui-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

export function subscribeUiMode(listener: (m: UiMode) => void): () => void {
  return store.subscribe(listener, { immediate: true });
}

/** Test-only reset. Preserve persistence to model a reload/new shell context. */
export function __resetUiModeForTest(options: { preservePersistence?: boolean } = {}): void {
  store.reset();
  revision = 0;
  persistenceTail = Promise.resolve();
  fallbackPersistenceSequence = 0;
  fallbackPersistenceClientId = createPersistenceClientId();
  if (!options.preservePersistence) {
    try {
      globalThis.localStorage?.removeItem(PERSISTENCE_CLIENT_STORAGE_KEY);
      globalThis.localStorage?.removeItem(PERSISTENCE_SEQUENCE_STORAGE_KEY);
    } catch {
      // A storage-disabled test environment already exercises the in-memory fallback.
    }
  }
}
