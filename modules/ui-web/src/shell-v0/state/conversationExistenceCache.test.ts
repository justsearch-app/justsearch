// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  __resetConversationListForTest, getConversationListState, loadConversations,
  rememberServerConversation, setActiveConversation,
} from './conversationListStore.js';

let instanceId: unknown;
let deletionCount: unknown;
let deletedIds: string[];
let exists: unknown;
let reads: string[];
let answer: (() => Promise<Response>) | undefined;

beforeEach(() => {
  __resetConversationListForTest();
  localStorage.clear();
  instanceId = 'engine-one';
  deletionCount = 0;
  deletedIds = [];
  exists = true;
  reads = [];
  answer = undefined;
  vi.stubGlobal('fetch', vi.fn(async (url: unknown) => {
    const path = String(url);
    if (path.includes('/history?existsOnly=true')) {
      reads.push(path);
      return answer ? answer() : Response.json({ exists });
    }
    return Response.json({ sessions: [], instanceId, deletionCount, deletedIds });
  }));
});
afterEach(() => {
  __resetConversationListForTest();
  vi.unstubAllGlobals();
});

it('EC1 reads each of 25 held beyond-limit identities once across 20 refreshes', async () => {
  for (let i = 0; i < 25; i++) rememberServerConversation(`older-${i}`);
  for (let i = 0; i < 20; i++) await loadConversations();
  expect(reads).toHaveLength(25);
  expect(new Set(reads).size).toBe(25);
  expect(getConversationListState().deletedIds).toEqual([]);
});

it('EC1 coalesces existence reads from overlapping refreshes', async () => {
  rememberServerConversation('older');
  let release!: (response: Response) => void;
  const pending = new Promise<Response>(resolve => { release = resolve; });
  answer = () => pending;
  const first = loadConversations();
  await vi.waitFor(() => expect(reads.length).toBeGreaterThan(0));
  const second = loadConversations();
  // Let the second list finish rendering before resolving either existence read.
  await vi.waitFor(() => expect(getConversationListState().loading).toBe(false));
  answer = undefined;
  release(Response.json({ exists: true }));
  await Promise.all([first, second]);
  expect(reads).toHaveLength(1);
});

it('EC2 EB1 drops a cached identity after an Engine restart with lost markers', async () => {
  rememberServerConversation('gone');
  setActiveConversation('gone');
  deletionCount = 80;
  await loadConversations();
  instanceId = 'engine-two';
  deletionCount = 0;
  exists = false;
  await loadConversations();
  expect(reads).toHaveLength(2);
  expect(getConversationListState().deletedIds).toContain('gone');
  expect(getConversationListState().activeId).toBeNull();
});

it('EC3 removes a cached identity from same-run deletion markers', async () => {
  rememberServerConversation('gone');
  setActiveConversation('gone');
  await loadConversations();
  deletedIds = ['gone'];
  deletionCount = 1;
  await loadConversations();
  expect(reads).toHaveLength(1);
  expect(getConversationListState().deletedIds).toContain('gone');
  expect(getConversationListState().activeId).toBeNull();
});

it('EB1 rechecks all cached identities when more than 100 deletions hide a missed delete', async () => {
  rememberServerConversation('gone');
  rememberServerConversation('older');
  await loadConversations();
  deletionCount = 101;
  deletedIds = Array.from({ length: 100 }, (_, i) => `other-${i}`);
  answer = async () => Response.json({ exists: !reads.at(-1)?.includes('/gone/') });
  await loadConversations();
  expect(reads).toHaveLength(4);
  expect(getConversationListState().deletedIds).toContain('gone');
  expect(getConversationListState().deletedIds).not.toContain('older');
  await loadConversations();
  expect(reads).toHaveLength(4);
});

it('EB1 keeps confirmations through repeated advances within the marker window', async () => {
  rememberServerConversation('older');
  await loadConversations();
  for (let i = 1; i <= 3; i++) {
    deletionCount = i * 100;
    deletedIds = Array.from({ length: 100 }, (_, j) => `other-${i}-${j}`);
    await loadConversations();
  }
  expect(reads).toHaveLength(1);
});

it.each([undefined, -1, 1.5, '0', Number.MAX_SAFE_INTEGER + 1])(
  'EB1 does not cache with unavailable or invalid deletion count %s', async count => {
    deletionCount = count;
    rememberServerConversation('older');
    await loadConversations();
    await loadConversations();
    expect(reads).toHaveLength(2);
    expect(getConversationListState().deletedIds).toEqual([]);
  },
);

it('EB1 does not cache without current Engine identity', async () => {
  instanceId = undefined;
  rememberServerConversation('older');
  await loadConversations();
  await loadConversations();
  expect(reads).toHaveLength(2);
});

it('EC4 retries an inconclusive answer on the next refresh and keeps the conversation', async () => {
  rememberServerConversation('older');
  setActiveConversation('older');
  for (const unavailable of [
    async () => new Response('{}', { status: 503 }),
    async () => { throw new DOMException('timeout', 'TimeoutError'); },
    async () => Response.json({ messages: [] }),
    async () => Response.json({ exists: 'true' }),
  ]) {
    answer = unavailable;
    const before = reads.length;
    await loadConversations();
    await loadConversations();
    expect(reads.length - before).toBe(2);
    expect(getConversationListState().activeId).toBe('older');
    expect(getConversationListState().deletedIds).toEqual([]);
  }
});

it('EC5 keeps local drafts and beyond-limit conversations without saving the cache', async () => {
  rememberServerConversation('older');
  setActiveConversation('local-draft');
  const saved = { ...localStorage };
  await loadConversations();
  await loadConversations();
  expect(reads.every(path => path.includes('/older/history'))).toBe(true);
  expect(getConversationListState().activeId).toBe('local-draft');
  expect(getConversationListState().deletedIds).toEqual([]);
  expect({ ...localStorage }).toEqual(saved);
});

it('EB1 discards confirmations if deletion metadata disappears or regresses', async () => {
  for (const nextCount of [undefined, 0]) {
    __resetConversationListForTest();
    deletionCount = 50;
    exists = true;
    rememberServerConversation('gone');
    await loadConversations();
    deletionCount = nextCount;
    exists = false;
    await loadConversations();
    expect(getConversationListState().deletedIds).toContain('gone');
  }
});

it('EC2 EB1 a late pre-restart answer cannot populate the new Engine cache', async () => {
  rememberServerConversation('gone');
  let release!: (response: Response) => void;
  const pending = new Promise<Response>(resolve => { release = resolve; });
  answer = () => pending;
  const oldRefresh = loadConversations();
  await vi.waitFor(() => expect(reads.length).toBe(1));
  instanceId = 'engine-two';
  answer = async () => new Response('{}', { status: 503 });
  await loadConversations();
  release(Response.json({ exists: true }));
  await oldRefresh;
  answer = undefined;
  exists = false;
  await loadConversations();
  expect(getConversationListState().deletedIds).toContain('gone');
});

it('EC5 a newer refresh does not reuse an earlier absence for a restored beyond-limit identity', async () => {
  rememberServerConversation('restored');
  let release!: (response: Response) => void;
  const pending = new Promise<Response>(resolve => { release = resolve; });
  answer = async () => reads.length === 1 ? pending : Response.json({ exists: true });
  const first = loadConversations();
  await vi.waitFor(() => expect(reads.length).toBe(1));
  const second = loadConversations();
  await vi.waitFor(() => expect(getConversationListState().loading).toBe(false));
  release(Response.json({ exists: false }));
  await Promise.all([first, second]);
  expect(getConversationListState().deletedIds).not.toContain('restored');
});

it('EB1 uses the marker capacity when a restored deletion leaves a shorter marker list', async () => {
  rememberServerConversation('older');
  await loadConversations();
  // Another identity was deleted and restored, so its marker was removed, but the covered gap is 1.
  deletionCount = 1;
  deletedIds = [];
  await loadConversations();
  expect(reads).toHaveLength(1);
});
