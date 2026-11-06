// SPDX-License-Identifier: Apache-2.0
// Tempdoc 610 §J.3 — the single FE source of truth for the active conversation's hidden retrieved
// sources. Search's details pane reads and writes it; persistence rides `setSourceExcluded`.

import { setSourceExcluded } from './conversationListStore.js';

const US = String.fromCharCode(0x1f);

/** The unit-separator-joined exclusion key for a source (matches the backend split). */
export function sourceExcludeKey(parentDocId: string, chunkIndex: number): string {
  return `${parentDocId}${US}${chunkIndex}`;
}

let _ids: Set<string> = new Set();
/** The conversation {@link _ids} belongs to. */
let _owner: string | null = null;
const _subs = new Set<() => void>();

function notify(): void {
  for (const s of _subs) {
    try {
      s();
    } catch {
      /* swallow */
    }
  }
}

export function getExcludedSources(): ReadonlySet<string> {
  return _ids;
}

/** Replace the active conversation's hidden-source set (on resume / reset). */
export function setExcludedSources(ids: Iterable<string>, conversationId: string | null = _owner): void {
  _ids = new Set(ids);
  _owner = conversationId;
  notify();
}

/**
 * Persist (via the conversation store endpoint) + update the active conversation's hidden-source set.
 * Returns true on success. A response that lands after the reader moved on stays out of the set:
 * the change is saved for `sessionId`, which reads it back when it is next shown.
 */
export async function toggleExcludedSource(
  sessionId: string,
  key: string,
  excluded: boolean,
): Promise<boolean> {
  const ok = await setSourceExcluded(sessionId, key, excluded);
  if (!ok) return false;
  if (sessionId !== _owner) return true;
  const next = new Set(_ids);
  if (excluded) next.add(key);
  else next.delete(key);
  _ids = next;
  notify();
  return true;
}

export function subscribeExcludedSources(listener: () => void): () => void {
  _subs.add(listener);
  return () => _subs.delete(listener);
}
