// SPDX-License-Identifier: Apache-2.0
/** One indexed-roots observation shared by Library and the palette. No persistence. */
import { z } from 'zod';
import { indexedRootViewSchema, type IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { parseWireContract } from '../../api/schemas.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { getActiveProfileId, subscribeProfileSwitch } from './UserStateDocument.js';

const ENDPOINT = '/api/indexing-roots/substrate';
const schema = z.object({ items: z.array(indexedRootViewSchema), count: z.number().optional() }).loose();
export interface IndexedRootsObservation {
  readonly roots: IndexedRootView[];
  readonly paths: Readonly<Record<string, string>>;
  readonly confirmed: boolean;
  readonly error: string | null;
}
let snapshot: IndexedRootsObservation = { roots: [], paths: {}, confirmed: false, error: null };
let serial = 0;
let profile = getActiveProfileId();
const listeners = new Set<() => void>();
const emit = (): void => { for (const listener of listeners) listener(); };
export function getIndexedRoots(): IndexedRootsObservation {
  if (profile !== getActiveProfileId()) {
    profile = getActiveProfileId();
    serial++;
    snapshot = { roots: [], paths: {}, confirmed: false, error: null };
  }
  return snapshot;
}
export function subscribeIndexedRoots(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
subscribeProfileSwitch(() => {
  serial++;
  snapshot = { roots: [], paths: {}, confirmed: false, error: null };
  emit();
});

export async function readIndexedRoots(
  fetcher: (path: string) => Promise<Response> = authorizedFetch,
  counts = false,
  apiBase = '',
): Promise<IndexedRootsObservation | null> {
  getIndexedRoots();
  const read = ++serial, scope = profile;
  // A switch advances `serial` through the subscription above; the scope comparison also holds
  // when that subscription is absent.
  const current = (): boolean => read === serial && scope === getActiveProfileId();
  try {
    const response = await fetcher(ENDPOINT + (counts ? '?counts=true' : ''));
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const body = parseWireContract(schema, await response.json(), ENDPOINT);
    if (!current()) return null;
    const previous = new Map(snapshot.roots.map(root => [root.pathHash, root.fileCount]));
    const roots = body.items.map(root => !counts && (root.fileCount ?? -1) < 0 && (previous.get(root.pathHash) ?? -1) >= 0
      ? { ...root, fileCount: previous.get(root.pathHash)! } : root);
    const hashes = new Set(roots.map(root => root.pathHash));
    snapshot = { roots, paths: Object.fromEntries(Object.entries(snapshot.paths).filter(([hash]) => hashes.has(hash))), confirmed: true, error: null };
    emit();
    // Resolve in batches below the existing resolver's in-flight bound; no tail is lost.
    void (async () => {
      for (let start = 0; start < roots.length && current(); start += 32) {
        await Promise.all(roots.slice(start, start + 32).map(async root => {
          const hash = root.pathHash;
          if (!hash || snapshot.paths[hash]) return;
          const path = await resolvePathLazy('core.indexed-roots', hash, { apiBase });
          if (!current() || !path || !snapshot.roots.some(row => row.pathHash === hash)) return;
          snapshot = { ...snapshot, paths: { ...snapshot.paths, [hash]: path } };
          emit();
        }));
      }
    })();
    return snapshot;
  } catch (error) {
    if (!current()) return null;
    snapshot = { ...snapshot, confirmed: false, error: error instanceof Error ? error.message : String(error) };
    emit();
    return null;
  }
}

/** A successful removal is stronger than any earlier in-flight observation. */
export function forgetIndexedRoots(hashes: readonly string[]): void {
  serial++;
  snapshot = { ...snapshot, roots: snapshot.roots.filter(row => !hashes.includes(row.pathHash ?? '')),
    paths: Object.fromEntries(Object.entries(snapshot.paths).filter(([hash]) => !hashes.includes(hash))) };
  emit();
}
