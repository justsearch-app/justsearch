// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Independent round-seven verifier check (verify-vdu7), written before reading the builder's tests.
// A person acting is modelled by capturing the shell's own input-activity seam (startUserActivity)
// and invoking the callback it registered; untouched mounts never invoke it.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const activity = vi.hoisted(() => ({ callbacks: [] as Array<() => Promise<unknown>>, stops: 0 }));
vi.mock('../utils/userActivity.js', () => ({
  startUserActivity: (_t: EventTarget, report: () => Promise<unknown>) => {
    activity.callbacks.push(report);
    return () => { activity.stops += 1; const i = activity.callbacks.indexOf(report); if (i >= 0) activity.callbacks.splice(i, 1); };
  },
}));

type Call = { url: string; method: string; body: unknown };
let calls: Call[] = [];
let backend: Record<string, { title: string | null; titleSource: string | null }> = {};
let titleStatus = 200;
let holdTitles: Array<() => void> | null = null;
let holdList: Array<() => void> | null = null;

function installFetch(): void {
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = (init?.method ?? 'GET').toUpperCase();
    const body = init?.body ? JSON.parse(String(init.body)) : undefined;
    calls.push({ url, method, body });
    if (url.includes('/api/chat/conversations?')) {
      // The body is the server's state when it answered; delivery can be held to model transit.
      const body = JSON.stringify({ sessions: Object.entries(backend).map(([id, v]) => ({
        sessionId: id, title: v.title, titleSource: v.titleSource, createdAtMs: 1, lastActiveAtMs: 2, messageCount: 2,
      })) });
      if (holdList) await new Promise<void>((r) => holdList!.push(r));
      return new Response(body, { status: 200, headers: { 'Content-Type': 'application/json' } });
    }
    const m = url.match(/\/api\/chat\/conversations\/([^/]+)\/title$/);
    if (m && method === 'POST') {
      if (holdTitles) await new Promise<void>((r) => holdTitles!.push(r));
      if (titleStatus >= 200 && titleStatus < 300) backend[decodeURIComponent(m[1]!)] = { title: (body as { title: string }).title, titleSource: (body as { source: string }).source };
      return new Response('{}', { status: titleStatus });
    }
    return new Response('{}', { status: 404 });
  }) as typeof fetch;
}

async function freshTab() {
  vi.resetModules();
  return await import('./conversationListStore.js');
}
const titlePosts = () => calls.filter((c) => c.method === 'POST' && c.url.endsWith('/title'));
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); await new Promise((r) => setTimeout(r, 0)); };
async function personActs(): Promise<void> { const cbs = [...activity.callbacks]; for (const cb of cbs) await cb(); await flush(); }

beforeEach(() => {
  calls = []; backend = {}; titleStatus = 200; holdTitles = null; holdList = null; activity.callbacks.length = 0; activity.stops = 0;
  localStorage.clear(); installFetch();
});
afterEach(() => { vi.restoreAllMocks(); });

describe('verify-vdu7 legacy title adoption', () => {
  it('L1 untouched mount sends no title write and still shows the retained title', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab();
    await store.loadConversations(); await flush();
    expect(titlePosts()).toEqual([]);
    expect(store.getConversationListState().conversations.find((c) => c.id === 'c1')?.title).toBe('Kept name');
    expect(JSON.parse(localStorage.getItem('jf-conversation-titles')!)).toEqual({ c1: 'Kept name' });
  });
  it('L2 repeated list loads (status updates) without input send no title write', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab();
    for (let i = 0; i < 5; i++) { await store.loadConversations(); await flush(); }
    expect(titlePosts()).toEqual([]);
    expect(activity.callbacks.length).toBeLessThanOrEqual(1);
  });
  it('L3 the first input adopts exactly once as the reader\'s title and erases the local copy', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab();
    await store.loadConversations(); await flush();
    await personActs();
    await personActs();
    expect(titlePosts().map((c) => c.body)).toEqual([{ title: 'Kept name', source: 'user' }]);
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
    expect(backend.c1).toEqual({ title: 'Kept name', titleSource: 'user' });
  });
  it('L4 reloads without input keep the title; the first input after a later reload adopts once', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    for (let r = 0; r < 3; r++) { const s = await freshTab(); await s.loadConversations(); await flush(); activity.callbacks.length = 0; }
    expect(titlePosts()).toEqual([]);
    const s = await freshTab(); await s.loadConversations(); await flush();
    expect(s.getConversationListState().conversations[0]?.title).toBe('Kept name');
    await personActs();
    expect(titlePosts().length).toBe(1);
    // After adoption a reload shows the backend title and arms nothing.
    activity.callbacks.length = 0;
    const after = await freshTab(); await after.loadConversations(); await flush();
    expect(after.getConversationListState().conversations[0]?.title).toBe('Kept name');
    expect(activity.callbacks.length).toBe(0);
    expect(titlePosts().length).toBe(1);
  });
  it('L5 two tabs: acting in one adopts once; acting later in the other does not duplicate', async () => {
    backend = { c1: { title: null, titleSource: null }, c2: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'One', c2: 'Two' }));
    const tabA = await freshTab(); await tabA.loadConversations(); await flush();
    const cbA = activity.callbacks.at(-1)!;
    const tabB = await freshTab(); await tabB.loadConversations(); await flush();
    const cbB = activity.callbacks.at(-1)!;
    expect(cbA).not.toBe(cbB);
    expect(titlePosts()).toEqual([]);
    await cbA(); await flush();
    expect(titlePosts().length).toBe(2);
    await cbB(); await flush();
    expect(titlePosts().length).toBe(2);
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
    expect(backend).toEqual({ c1: { title: 'One', titleSource: 'user' }, c2: { title: 'Two', titleSource: 'user' } });
  });
  it('L6 a locked store (423) keeps the local title; nothing is lost', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    titleStatus = 423;
    const store = await freshTab(); await store.loadConversations(); await flush();
    await personActs();
    expect(JSON.parse(localStorage.getItem('jf-conversation-titles')!)).toEqual({ c1: 'Kept name' });
    expect(store.getConversationListState().conversations[0]?.title).toBe('Kept name');
    titleStatus = 200;
    const reloaded = await freshTab(); await reloaded.loadConversations(); await flush();
    expect(titlePosts().length).toBe(1);
    await personActs();
    expect(titlePosts().length).toBe(2);
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
  });
  it('L7 a backend title wins: the stale local entry is forgotten without any write', async () => {
    backend = { c1: { title: 'Server name', titleSource: 'user' } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Old local' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    expect(titlePosts()).toEqual([]);
    expect(activity.callbacks.length).toBe(0);
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
    expect(store.getConversationListState().conversations[0]?.title).toBe('Server name');
  });
  it('L8 a person renaming still writes through the title route', async () => {
    backend = { c1: { title: null, titleSource: null } };
    const store = await freshTab(); await store.loadConversations(); await flush();
    const out = await store.setConversationTitle('c1', 'Renamed');
    expect(out.ok).toBe(true);
    expect(titlePosts().map((c) => c.body)).toEqual([{ title: 'Renamed', source: 'user' }]);
  });
  it('L9 (record) a rename made while adoption is still in flight is not overwritten by the legacy name', async () => {
    backend = { c1: { title: null, titleSource: null }, c2: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Legacy one', c2: 'Legacy two' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    holdTitles = [];
    const cb = activity.callbacks.at(-1)!;
    const adopting = cb();
    await flush();
    // Person renames c2 while the adoption of c1 is still waiting on the network.
    const renaming = store.setConversationTitle('c2', 'Person chose this');
    await flush();
    while (holdTitles.length) { holdTitles.shift()!(); await flush(); }
    holdTitles = null;
    await renaming; await adopting; await flush();
    console.log('L9 backend', JSON.stringify(backend), 'posts', JSON.stringify(titlePosts().map((c) => c.body)));
    expect(backend.c2).toEqual({ title: 'Person chose this', titleSource: 'user' });
  });
  it('L10 (live repro) the input that adopts also reloads the list: the shown title stays the retained one', async () => {
    // Live Chromium: clicking History is the first input; its pointerdown starts adoption and its
    // click reloads the list while the title POST is still in flight.
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    holdList = [];
    const adopting = activity.callbacks.at(-1)!();
    const listing = store.loadConversations();
    await flush();
    // Live order: the server answered the list with no title yet, the title write then completed
    // (erasing the local entry), and only then did the list response reach the page.
    await adopting; await flush();
    while (holdList.length) { holdList.shift()!(); await flush(); }
    holdList = null;
    await listing; await flush();
    const shown = store.getConversationListState().conversations.find((c) => c.id === 'c1')?.title;
    console.log('L10 shown', shown, 'backend', JSON.stringify(backend));
    expect(shown).toBe('Kept name');
  });
});
