// SPDX-License-Identifier: Apache-2.0
/**
 * recallCursor — Tempdoc 577 §2.14 Root I (#20): the ONE "seen" authority.
 *
 * Before this, the "What the AI did" digest owned a PRIVATE last-seen cursor (a journal-entry-id
 * in `justsearch.ai-digest.last-seen.v1`) while History / Timeline / Inbox projected the one 550
 * federated ledger with no notion of "seen" at all — two authorities for recall (the 553 drift
 * class: the digest could say "3 new" off a cursor nothing else respected). This store is the
 * single owner of "have I seen this yet", keyed over the unified log's chronological axis
 * (an ISO-8601 `occurredAt` timestamp), so EVERY recall surface — the catch-up digest, and any
 * "new since you looked" marker on History/Inbox — reads ONE cursor over ONE log.
 *
 * The unit is an ISO-8601 timestamp string, stored as written. "Newer than seen" compares the
 * parsed instants ({@link compareInstants}): the Engine writes `Instant.toString()` (no fraction on a
 * whole second, up to nine digits otherwise) and this window writes three digits, so a string
 * compare would put "…00.100Z" before "…00Z". The empty string is "never looked" (everything is new).
 *
 * Scope: one cursor per browser storage, shared by every window, profile and recall surface. Marking
 * read moves it to the newest loaded record a surface holds, whatever that surface's filters show.
 */
import { safeLocalStorage } from '../../primitives/storage.js';
import { compareInstants, isInstant } from '../../primitives/instant.js';

const SEEN_KEY = 'justsearch.recall.seen-cursor.v1';

let cursor = readCursor();
const subscribers = new Set<() => void>();

function readCursor(): string {
  const raw = safeLocalStorage()?.getItem(SEEN_KEY);
  // An ISO-8601 string or '' (never looked). A value that is not an instant cannot order anything.
  return isInstant(raw) ? raw : '';
}

function writeCursor(iso: string): void {
  safeLocalStorage()?.setItem(SEEN_KEY, iso);
}

function notify(): void {
  for (const fn of subscribers) fn();
}

/** The current seen-cursor (an ISO-8601 timestamp, or '' for "never looked"). */
export function getSeenCursor(): string {
  return cursor;
}

/**
 * Advance the cursor to `iso` (monotonic — a stale/older mark is ignored, so an out-of-order
 * refresh can never rewind "seen"). Notifies subscribers only on a real advance.
 */
export function markSeen(iso: string): void {
  if (!isInstant(iso)) return;
  if (cursor === '' || compareInstants(iso, cursor) > 0) {
    cursor = iso;
    writeCursor(iso);
    notify();
  }
}

/** True iff `occurredAt` is newer than the seen-cursor (the "new since you looked" predicate). */
export function isNewSinceSeen(occurredAt: string): boolean {
  return isInstant(occurredAt) && (cursor === '' || compareInstants(occurredAt, cursor) > 0);
}

/** Subscribe to cursor advances. Returns an unsubscribe fn. */
export function subscribeSeenCursor(fn: () => void): () => void {
  subscribers.add(fn);
  return () => {
    subscribers.delete(fn);
  };
}

/** Test-only: reset the in-memory + persisted cursor. */
export function __resetRecallCursor(): void {
  cursor = '';
  safeLocalStorage()?.removeItem(SEEN_KEY);
  notify();
}
