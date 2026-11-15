// SPDX-License-Identifier: Apache-2.0
/** One indexed-roots observation shared by Library and the palette. No persistence. */
import { z } from 'zod';
import { indexedRootViewSchema, type IndexedRootView } from '../../api/generated/schema-types/indexed-root-view.js';
import { parseWireContract } from '../../api/schemas.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { resolvePathLazy } from '../hooks/resolvePathLazy.js';
import { getAiState } from './aiStateStore.js';
import { rememberFailedCounts } from './folderStatus.js';
import { getStatusEpoch, subscribeStatusEpoch } from '../../api/statusInvalidation.js';
import { getActiveProfileId, subscribeProfileSwitch } from './UserStateDocument.js';

const ENDPOINT = '/api/indexing-roots/substrate';
const schema = z.object({ items: z.array(indexedRootViewSchema), count: z.number().optional() }).loose();
export interface IndexedRootsObservation {
  readonly roots: IndexedRootView[];
  readonly paths: Readonly<Record<string, string>>;
  readonly confirmed: boolean;
  readonly error: string | null;
  readonly receivedAtMs?: number;
  readonly lastKnownFailed?: Readonly<Record<string, number>>;
}
let snapshot: IndexedRootsObservation = { roots: [], paths: {}, confirmed: false, error: null };
/** Start order of reads. Several readers share this observation, so the newest answer wins, not the newest request. */
let serial = 0;
/** Reads started at or before this were invalidated (profile switch, new engine process, removal). */
let barrier = 0;
/** The start order of the answer now held. */
let accepted = 0;
let profile = getActiveProfileId();
const listeners = new Set<() => void>();
const emit = (): void => { for (const listener of listeners) listener(); };

/**
 * The one source of each watched folder's disk count for Library and Browse. Library's counted read
 * reports it by path hash and Browse's by path; the newest observation of a folder wins in both, so
 * the two never disagree and a slower counted answer is not lost behind a newer counts-free one.
 */
interface RootCount { readonly fileCount: number; readonly fileCountCapped?: boolean | undefined; readonly order: number }
let countOrder = 0;
let countsByHash = new Map<string, RootCount>();
let countsByPath = new Map<string, RootCount>();
const pathKey = (path: string): string => path.replace(/(.)[\\/]+$/, '$1');
const counted = (count: number | undefined, status?: string): boolean =>
  typeof count === 'number' && (count >= 0 || status !== 'unavailable');
function resetCounts(): void { countsByHash = new Map(); countsByPath = new Map(); }

/** The newest disk count known for one folder, by its path hash and/or its path. */
export function rootCount(pathHash: string | undefined, path: string | undefined): { fileCount: number; fileCountCapped?: boolean | undefined } | undefined {
  const candidates = [pathHash ? countsByHash.get(pathHash) : undefined, path ? countsByPath.get(pathKey(path)) : undefined];
  if (path && !pathHash) {
    for (const [hash, resolved] of Object.entries(snapshot.paths)) if (pathKey(resolved) === pathKey(path)) candidates.push(countsByHash.get(hash));
  }
  const newest = candidates.reduce<RootCount | undefined>((best, next) => next && (!best || next.order > best.order) ? next : best, undefined);
  return newest && { fileCount: newest.fileCount, fileCountCapped: newest.fileCountCapped };
}

/** Rows carrying the shared newest count; `paths` maps a path hash to its resolved path. */
export function withRootCounts(roots: readonly IndexedRootView[], paths: Readonly<Record<string, string>>): IndexedRootView[] {
  return roots.map(root => {
    const count = rootCount(root.pathHash, root.pathHash ? paths[root.pathHash] : undefined);
    return count && (count.fileCount !== root.fileCount || count.fileCountCapped !== root.fileCountCapped)
      ? { ...root, fileCount: count.fileCount, fileCountCapped: count.fileCountCapped } : root;
  });
}

/** Browse's counted read of the watched folders, by path. */
export function recordRootCounts(rows: readonly { path: string; fileCount?: number | undefined; fileCountCapped?: boolean | undefined }[]): void {
  let changed = false;
  for (const row of rows) {
    if (!counted(row.fileCount)) continue;
    countsByPath.set(pathKey(row.path), { fileCount: row.fileCount!, fileCountCapped: row.fileCountCapped, order: ++countOrder });
    changed = true;
  }
  if (!changed) return;
  snapshot = { ...snapshot, roots: withRootCounts(snapshot.roots, snapshot.paths) };
  emit();
}

export function getIndexedRoots(): IndexedRootsObservation {
  if (profile !== getActiveProfileId()) {
    profile = getActiveProfileId();
    barrier = ++serial;
    snapshot = { roots: [], paths: {}, confirmed: false, error: null };
    resetCounts();
  }
  return snapshot;
}
export function subscribeIndexedRoots(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
subscribeStatusEpoch(() => {
  barrier = ++serial;
  snapshot = { roots: [], paths: {}, confirmed: false, error: null };
  resetCounts();
  emit();
});
subscribeProfileSwitch(() => {
  barrier = ++serial;
  snapshot = { roots: [], paths: {}, confirmed: false, error: null };
  resetCounts();
  emit();
});

export async function readIndexedRoots(
  fetcher: (path: string) => Promise<Response> = authorizedFetch,
  counts = false,
  apiBase = '',
  signal?: AbortSignal,
): Promise<IndexedRootsObservation | null> {
  getIndexedRoots();
  const read = ++serial, scope = profile, epoch = getStatusEpoch();
  // A switch advances the barrier through the subscription above; the scope comparison also holds
  // when that subscription is absent. An answer older than one already held never replaces it.
  const valid = (): boolean => !signal?.aborted && read > barrier && scope === getActiveProfileId()
    && (epoch === null || epoch === getStatusEpoch());
  const current = (): boolean => valid() && read > accepted;
  const held = (): boolean => valid() && read === accepted;
  try {
    const response = await fetcher(ENDPOINT + (counts ? '?counts=true' : ''));
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const body = parseWireContract(schema, await response.json(), ENDPOINT);
    // A counted answer's disk counts stay the newest counts even when a newer counts-free answer won.
    // A counts-free answer reports no count (-1), which is no observation of the disk.
    if (valid()) {
      for (const root of body.items) {
        if (root.pathHash && (counts ? counted(root.fileCount, root.status) : (root.fileCount ?? -1) >= 0)) {
          countsByHash.set(root.pathHash, { fileCount: root.fileCount!, fileCountCapped: root.fileCountCapped, order: ++countOrder });
        }
      }
    }
    // Overtaken by a newer answer: the caller receives that answer, never this older one.
    if (!current()) {
      if (!valid()) return null;
      if (counts) { snapshot = { ...snapshot, roots: withRootCounts(snapshot.roots, snapshot.paths) }; emit(); }
      return snapshot;
    }
    accepted = read;
    const roots = withRootCounts(body.items, snapshot.paths);
    const hashes = new Set(roots.map(root => root.pathHash));
    snapshot = { roots, paths: Object.fromEntries(Object.entries(snapshot.paths).filter(([hash]) => hashes.has(hash))), confirmed: true, error: null, receivedAtMs: Date.now(),
      lastKnownFailed: rememberFailedCounts(snapshot.lastKnownFailed ?? {}, roots, { provisional: getAiState().stability.kind === 'provisional' }) };
    emit();
    // Resolve in batches below the existing resolver's in-flight bound; no tail is lost.
    void (async () => {
      for (let start = 0; start < roots.length && held(); start += 32) {
        await Promise.all(roots.slice(start, start + 32).map(async root => {
          const hash = root.pathHash;
          if (!hash || snapshot.paths[hash]) return;
          const path = await resolvePathLazy('core.indexed-roots', hash, { apiBase });
          if (!held() || !path || !snapshot.roots.some(row => row.pathHash === hash)) return;
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
  barrier = ++serial;
  for (const hash of hashes) {
    const path = snapshot.paths[hash];
    countsByHash.delete(hash);
    if (path) countsByPath.delete(pathKey(path));
  }
  snapshot = { ...snapshot, roots: snapshot.roots.filter(row => !hashes.includes(row.pathHash ?? '')),
    paths: Object.fromEntries(Object.entries(snapshot.paths).filter(([hash]) => !hashes.includes(hash))) };
  emit();
}
