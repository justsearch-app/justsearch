// SPDX-License-Identifier: Apache-2.0
/**
 * NavigationJournal — slice 501 §3.1.
 *
 * FE-owned ring buffer that records surface transitions, providing:
 *   - Back/forward button enabled state (cursor tracking)
 *   - Peek labels for tooltips ("Go back to Search")
 *   - navigateBack/navigateForward (dispatch journal entries via IntentRouter)
 *   - Recent surfaces for future tray menu / history surface
 *
 * Storage: separate localStorage key (not UserStateDocument — see §2.2).
 * The journal is system-generated (automatic, FIFO-evicted); UserStateDocument
 * is user-authored (explicit, permanent).
 *
 * Integration: Shell subscribes IntentRouter.subscribe → recordNavigation().
 * Back/forward set isNavigatingHistory flag to suppress self-recording.
 */

import type { Intent } from '../router/types.js';
import type { DispatchOptions } from '../router/intentRouter.js';
import { parseUrl } from '../router/parser.js';
// Tempdoc 543 §13.2.2 — NavigationJournal is the first production
// consumer of the Effect Journal substrate: every navigation in main
// also lands as a typed `navigate` Effect in the journal.
import {
  recordEffect,
  consumeReplayNavSuppression,
} from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

const STORAGE_KEY = 'justsearch.navigationJournal.v1';
const CAPACITY = 50;
const CONVERSATION_SURFACE = 'core.search-v3-surface';

export interface NavigationJournalEntry {
  readonly id: string;
  readonly surfaceId: string;
  readonly url: string;
  readonly label: string;
  readonly transport: string;
  readonly timestamp: number;
}

interface PersistedJournal {
  readonly entries: NavigationJournalEntry[];
  readonly cursor: number;
}

type JournalListener = (state: JournalSnapshot) => void;

export interface JournalSnapshot {
  readonly entries: readonly NavigationJournalEntry[];
  readonly cursor: number;
}

export type DispatchFn = (intent: Intent, options?: DispatchOptions) => Promise<unknown>;

let entries: NavigationJournalEntry[] = [];
let cursor = -1;
let isNavigatingHistory = false;
const listeners = new Set<JournalListener>();
let initialized = false;

function ensureInitialized(): void {
  if (initialized) return;
  initialized = true;
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed: PersistedJournal = JSON.parse(raw);
      if (Array.isArray(parsed.entries) && typeof parsed.cursor === 'number') {
        entries = parsed.entries.slice(-CAPACITY);
        cursor = Math.min(Math.max(parsed.cursor, -1), entries.length - 1);
        return;
      }
    }
  } catch {
    // Malformed — start fresh.
  }
  entries = [];
  cursor = -1;
}

function persist(): void {
  try {
    const data: PersistedJournal = { entries, cursor };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
  } catch {
    // localStorage full or unavailable — non-fatal.
  }
}

function fireListeners(): void {
  const snap = getJournalState();
  for (const l of listeners) {
    try {
      l(snap);
    } catch {
      // Listener errors swallowed (matching project posture).
    }
  }
}

function makeId(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `nj-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/**
 * Snapshot the departing surface's final state into the current journal entry.
 *
 * Called at the top of recordNavigation BEFORE any mutation. At this point,
 * window.location.hash still holds the PREVIOUS surface's last replaceState'd
 * URL (IntentRouter.subscribe fires before NavigationHandler runs). This
 * captures edits the user made after arriving (typing a query, changing
 * filters) that replaceState recorded but the original journal entry missed.
 */
function snapshotCurrentEntry(): void {
  if (cursor < 0 || cursor >= entries.length) return;
  if (typeof window === 'undefined') return;
  const hash = window.location.hash;
  if (!hash || !hash.startsWith('#justsearch://')) return;
  const currentUrl = hash.slice(1);
  // On boot/popstate the URL already names the incoming surface, not the departing one.
  const address = parseUrl(currentUrl);
  if (address?.kind !== 'navigate' || address.target !== entries[cursor]!.surfaceId) return;
  if (entries[cursor]!.url !== currentUrl) {
    entries[cursor] = { ...entries[cursor]!, url: currentUrl };
  }
}

/**
 * Record a new navigation. Called from IntentRouter.subscribe when:
 *   - intent.address.kind === 'navigate'
 *   - outcome === 'dispatched'
 *   - isNavigatingHistory === false
 *
 * Truncates forward entries when cursor is not at the end (branch).
 */
export function recordNavigation(
  surfaceId: string,
  url: string,
  label: string,
  transport: string,
): void {
  ensureInitialized();
  if (isNavigatingHistory) return;

  // A browser traversal/reload already moved to an existing view. Its browser-state marker
  // names this journal's entry, so restore the cursor rather than appending a return trip.
  if (surfaceId === CONVERSATION_SURFACE && transport === 'URL_BAR' && typeof window !== 'undefined') {
    const entryId = window.history.state?.navigationJournalId;
    const restored = entries.findIndex(entry => entry.id === entryId && entry.url === url);
    if (restored >= 0) {
      cursor = restored;
      persist();
      fireListeners();
      return;
    }
  }

  // Tempdoc 543 §13.2.2 — Effect Journal consumer wire. Record EVERY
  // navigation as a typed Effect, including same-surface URL changes
  // (search refinements, filter toggles) that NavigationJournal
  // de-dups into the existing ring-buffer entry. The Effect Journal
  // tracks a finer-grained log than the ring buffer; downstream
  // consumers (undo, audit) should see every URL transition, not just
  // surface transitions.
  //
  // 543-fwd #1 — EXCEPT when this navigation was itself driven by a cursor
  // undo/redo replay: re-journaling it would reset the undo cursor and truncate
  // the redo stack. The effects substrate flags the target; consume it here to
  // skip exactly that one re-record (the navigation itself still happens + the
  // ring buffer below still tracks it for browser-history purposes).
  if (!consumeReplayNavSuppression(url)) {
    recordEffect({ kind: 'navigate', to: url }, CORE_PROVENANCE);
  }

  // Fix A: capture the departing surface's final state before recording the new entry.
  snapshotCurrentEntry();

  // Refinements within one view coalesce; opening another conversation replaces the subject
  // even though it stays on Search v3, and must remain reachable with the app's Back control.
  const current = entries[cursor];
  const from = surfaceId === CONVERSATION_SURFACE && current ? parseUrl(current.url) : null;
  const to = surfaceId === CONVERSATION_SURFACE ? parseUrl(url) : null;
  const changedConversation = from?.kind === 'navigate' && to?.kind === 'navigate'
    && from.state.conversationId !== to.state.conversationId;
  if (current?.surfaceId === surfaceId && !changedConversation) {
    entries[cursor] = { ...entries[cursor]!, url, label, transport, timestamp: Date.now() };
    persist();
    fireListeners();
    return;
  }

  // Truncate forward entries (branching — same as browser behavior).
  if (cursor < entries.length - 1) {
    entries = entries.slice(0, cursor + 1);
  }

  const entry: NavigationJournalEntry = {
    id: makeId(),
    surfaceId,
    url,
    label,
    transport,
    timestamp: Date.now(),
  };

  entries.push(entry);

  // Capacity eviction (FIFO from front).
  if (entries.length > CAPACITY) {
    entries = entries.slice(entries.length - CAPACITY);
  }

  cursor = entries.length - 1;
  persist();
  fireListeners();
}

export function getJournalState(): JournalSnapshot {
  ensureInitialized();
  return { entries: [...entries], cursor };
}

export function subscribeJournal(listener: JournalListener): () => void {
  ensureInitialized();
  listeners.add(listener);
  // Fire once with current state on subscribe (matching project convention).
  try {
    listener(getJournalState());
  } catch {
    // Swallowed.
  }
  return () => {
    listeners.delete(listener);
  };
}

export function canGoBack(): boolean {
  ensureInitialized();
  return cursor > 0;
}

export function canGoForward(): boolean {
  ensureInitialized();
  return cursor < entries.length - 1;
}

export function peekBack(): NavigationJournalEntry | null {
  ensureInitialized();
  return cursor > 0 ? (entries[cursor - 1] ?? null) : null;
}

export function peekForward(): NavigationJournalEntry | null {
  ensureInitialized();
  return cursor < entries.length - 1 ? (entries[cursor + 1] ?? null) : null;
}

/**
 * Navigate to the previous journal entry. Sets isNavigatingHistory flag
 * to suppress self-recording, dispatches the entry's address through
 * the provided dispatch function, then clears the flag.
 */
export async function navigateBack(dispatch: DispatchFn): Promise<void> {
  ensureInitialized();
  if (!canGoBack()) return;
  cursor--;
  const entry = entries[cursor]!;
  const parsed = parseUrl(entry.url);
  const state = parsed && parsed.kind === 'navigate' ? parsed.state : {};
  isNavigatingHistory = true;
  try {
    await dispatch(
      {
        address: { kind: 'navigate', target: entry.surfaceId, state },
        transport: 'BUTTON',
      },
      { pushHistory: false },
    );
  } finally {
    isNavigatingHistory = false;
  }
  persist();
  fireListeners();
}

/**
 * Navigate to the next journal entry. Same isNavigatingHistory pattern.
 */
export async function navigateForward(dispatch: DispatchFn): Promise<void> {
  ensureInitialized();
  if (!canGoForward()) return;
  cursor++;
  const entry = entries[cursor]!;
  const parsed = parseUrl(entry.url);
  const state = parsed && parsed.kind === 'navigate' ? parsed.state : {};
  isNavigatingHistory = true;
  try {
    await dispatch(
      {
        address: { kind: 'navigate', target: entry.surfaceId, state },
        transport: 'BUTTON',
      },
      { pushHistory: false },
    );
  } finally {
    isNavigatingHistory = false;
  }
  persist();
  fireListeners();
}

/**
 * Tempdoc 609 §R (T1.2) — jump directly to a journal entry by id (the Recents menu). Generalizes
 * navigateBack/Forward (which step by one) to an arbitrary entry, the "future tray menu / history surface"
 * the journal was built for (§3.1). Moves the cursor to that entry and dispatches its address with the same
 * `isNavigatingHistory` suppression, so selecting a recent surface restores it without re-journaling.
 */
export async function navigateToEntry(id: string, dispatch: DispatchFn): Promise<void> {
  ensureInitialized();
  const idx = entries.findIndex((e) => e.id === id);
  if (idx < 0) return;
  cursor = idx;
  const entry = entries[idx]!;
  const parsed = parseUrl(entry.url);
  const state = parsed && parsed.kind === 'navigate' ? parsed.state : {};
  isNavigatingHistory = true;
  try {
    await dispatch(
      {
        address: { kind: 'navigate', target: entry.surfaceId, state },
        transport: 'BUTTON',
      },
      { pushHistory: false },
    );
  } finally {
    isNavigatingHistory = false;
  }
  persist();
  fireListeners();
}

/**
 * Returns true when a navigation dispatch should be suppressed from recording.
 * Exposed for Shell's IntentRouter.subscribe callback.
 */
export function isNavigatingHistoryNow(): boolean {
  return isNavigatingHistory;
}

/** Test-only: reset all state. */
export function __resetJournalForTest(): void {
  entries = [];
  cursor = -1;
  isNavigatingHistory = false;
  listeners.clear();
  initialized = false;
  try {
    localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Test env may not have localStorage.
  }
}
