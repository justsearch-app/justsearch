// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  __resetConversationListForTest, deleteConversationWithCascade, getConversationListState,
  setActiveConversation, type DeleteCascadeResult,
} from './conversationListStore.js';
import type { StatusSnapshot } from '../utils/statusPoll.js';

const status = vi.hoisted(() => ({ listeners: new Set<(snapshot: StatusSnapshot | null) => void>() }));
vi.mock('../utils/statusPoll.js', () => ({
  subscribeStatus: (listener: (snapshot: StatusSnapshot | null) => void) => {
    status.listeners.add(listener);
    return () => status.listeners.delete(listener);
  },
}));
const rows = [
  { sessionId: 'root', createdAtMs: 1 },
  { sessionId: 'child', parentSessionId: 'root', createdAtMs: 2 },
  { sessionId: 'single', createdAtMs: 3 },
];
let present: Set<string>;
let offline: boolean;
let deletes: string[];
let reads: string[];
let unreadable: string | undefined;
const respond = (body: unknown) => new Response(JSON.stringify(body));
const signal = (online: boolean) => {
  for (const listener of status.listeners) listener(online ? {} as StatusSnapshot : null);
};
beforeEach(() => {
  __resetConversationListForTest();
  present = new Set(rows.map(row => row.sessionId));
  offline = false; deletes = []; reads = []; unreadable = undefined;
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    const u = new URL(String(url), 'http://test');
    const id = u.pathname.split('/')[4] ?? '';
    if (init?.method === 'DELETE') {
      deletes.push(id); present.delete(id);
      // The Engine committed the first delete, then stopped before its reply reached the window.
      offline = true; signal(false);
      throw Error('Engine stopped after committing delete');
    }
    reads.push(String(url));
    if (offline || id === unreadable) throw Error('Engine unavailable');
    if (u.searchParams.get('existsOnly')) return respond({ exists: present.has(id) });
    const parent = u.searchParams.get('childrenOf');
    return respond({ childrenOf: parent, locked: false, sessions: rows.filter(row => present.has(row.sessionId)
      && (!parent || row.sessionId === parent || row.parentSessionId === parent)).map(row => ({ ...row, busy: false })) });
  }));
});
afterEach(() => {
  __resetConversationListForTest(); status.listeners.clear(); vi.unstubAllGlobals();
});

it.each(['single', 'root'])('TD7 Engine-only restart settles %s deletion without resuming it', async id => {
  const updates = vi.fn<(result: DeleteCascadeResult) => void>();
  const first = await deleteConversationWithCascade(id, async () => true, updates);
  expect(first.unconfirmedIds).toEqual(id === 'root' ? ['root', 'child'] : ['single']);
  const stoppedReads = reads.length;
  offline = false; signal(true);
  await vi.waitFor(() => expect(updates).toHaveBeenCalledWith(expect.objectContaining({
    deletedIds: [id === 'root' ? 'child' : 'single'],
    remainingIds: id === 'root' ? ['root'] : [], unconfirmedIds: [],
  })));
  expect(reads.length).toBeGreaterThan(stoppedReads);
  expect(deletes).toEqual([id === 'root' ? 'child' : 'single']);
  const settledReads = reads.length;
  signal(true); await Promise.resolve();
  expect(reads).toHaveLength(settledReads);
});
it('TD7 Engine-only restart closes the deleted open conversation', async () => {
  setActiveConversation('single');
  const updates = vi.fn();
  await deleteConversationWithCascade('single', async () => true, updates);
  expect(getConversationListState().activeId).toBe('single');
  offline = false; signal(true);
  await vi.waitFor(() => expect(getConversationListState().activeId).toBeNull());
  expect(getConversationListState().deletedIds).toContain('single');
  expect(deletes).toEqual(['single']);
});
it('TD7 keeps an unreadable outcome unconfirmed until a later status can settle it', async () => {
  const updates = vi.fn();
  await deleteConversationWithCascade('single', async () => true, updates);
  const previous = reads.length;
  offline = false; unreadable = 'single'; signal(true);
  await vi.waitFor(() => expect(reads.length).toBeGreaterThan(previous));
  // Finish the unavailable observation before the next successful status sample.
  await new Promise(resolve => setTimeout(resolve, 0));
  expect(updates).not.toHaveBeenCalled();
  unreadable = undefined; signal(true);
  await vi.waitFor(() => expect(updates).toHaveBeenCalledWith(expect.objectContaining({ deletedIds: ['single'], unconfirmedIds: [] })));
  expect(deletes).toEqual(['single']);
});
it('TD7 status changes cause no conversation reads without an unconfirmed delete', async () => {
  signal(false); signal(true); await Promise.resolve();
  expect(reads).toEqual([]);
  expect(deletes).toEqual([]);
});
