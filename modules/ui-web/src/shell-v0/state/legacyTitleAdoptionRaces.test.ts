// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
// Independent round-eight verifier check (verify-vdu8), copied in with its harness and assertions: the
// legacy-adoption cases L11-L17 (L1-L10 are legacyTitleAdoption.test.ts). Only its diagnostic
// console.log lines, and the titlePosts helper only they read, are left out.
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
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); await new Promise((r) => setTimeout(r, 0)); };
async function personActs(): Promise<void> { const cbs = [...activity.callbacks]; for (const cb of cbs) await cb(); await flush(); }

beforeEach(() => {
  calls = []; backend = {}; titleStatus = 200; holdTitles = null; holdList = null; activity.callbacks.length = 0; activity.stops = 0;
  localStorage.clear(); installFetch();
});
afterEach(() => { vi.restoreAllMocks(); });

describe('verify-vdu8 legacy title adoption races', () => {
  it('L11 the local copy stays until a list answer requested after the write reflects it', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    holdList = [];
    const adopting = activity.callbacks.at(-1)!(); await flush();
    // Title write reached the server; the confirming list request is still in transit.
    const serverDuringTransit = JSON.stringify(backend.c1);
    const keptDuringTransit = localStorage.getItem('jf-conversation-titles');
    const shownDuringTransit = store.getConversationListState().conversations[0]?.title;
    while (holdList.length) { holdList.shift()!(); await flush(); }
    holdList = null; await adopting; await flush();
    expect(serverDuringTransit).toBe(JSON.stringify({ title: 'Kept name', titleSource: 'user' }));
    expect(keptDuringTransit).not.toBeNull();
    expect(shownDuringTransit).toBe('Kept name');
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
    expect(store.getConversationListState().conversations[0]?.title).toBe('Kept name');
  });
  it('L12 a failed adoption write (500) keeps the local copy and the shown title; a later reload and input retries', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    titleStatus = 500;
    const store = await freshTab(); await store.loadConversations(); await flush();
    await personActs();
    expect(JSON.parse(localStorage.getItem('jf-conversation-titles')!)).toEqual({ c1: 'Kept name' });
    expect(store.getConversationListState().conversations[0]?.title).toBe('Kept name');
    titleStatus = 200;
    activity.callbacks.length = 0;
    const again = await freshTab(); await again.loadConversations(); await flush();
    await personActs();
    expect(backend.c1).toEqual({ title: 'Kept name', titleSource: 'user' });
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
  });
  it('L13 a rename of the SAME conversation made while its adoption is in flight wins on the server, on screen and after reload', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Legacy' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    holdTitles = [];
    const adopting = activity.callbacks.at(-1)!(); await flush();
    const renaming = store.setConversationTitle('c1', 'Person chose this'); await flush();
    while (holdTitles.length) { holdTitles.shift()!(); await flush(); }
    holdTitles = null; await renaming; await adopting; await flush();
    expect(backend.c1).toEqual({ title: 'Person chose this', titleSource: 'user' });
    expect(store.getConversationListState().conversations[0]?.title).toBe('Person chose this');
    activity.callbacks.length = 0;
    const reloaded = await freshTab(); await reloaded.loadConversations(); await flush();
    expect(reloaded.getConversationListState().conversations[0]?.title).toBe('Person chose this');
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
    expect(activity.callbacks.length).toBe(0);
  });
  it('L14 every list state from the first input on shows the retained title (no flash of the first message)', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Kept name' }));
    const store = await freshTab(); await store.loadConversations(); await flush();
    const seen: Array<string | null | undefined> = [];
    const unsub = store.subscribeConversationList(() => { seen.push(store.getConversationListState().conversations.find((c) => c.id === 'c1')?.title); });
    holdList = [];
    const adopting = activity.callbacks.at(-1)!();
    const listing = store.loadConversations();
    await flush(); await adopting; await flush();
    while (holdList.length) { holdList.shift()!(); await flush(); }
    holdList = null; await listing; await flush();
    await store.loadConversations(); await flush();
    if (typeof unsub === 'function') unsub();
    expect(seen.length).toBeGreaterThan(0);
    expect(seen.every((t) => t === 'Kept name')).toBe(true);
  });
  it('L15 two tabs: a rename made in tab B after B adopted is not overwritten when the person later acts in tab A', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Legacy' }));
    const tabA = await freshTab(); await tabA.loadConversations(); await flush();
    const cbA = activity.callbacks.at(-1)!;
    const tabB = await freshTab(); await tabB.loadConversations(); await flush();
    const cbB = activity.callbacks.at(-1)!;
    await cbB(); await flush();
    await tabB.setConversationTitle('c1', 'Renamed in B'); await flush();
    await cbA(); await flush();
    await tabA.loadConversations(); await flush();
    expect(backend.c1).toEqual({ title: 'Renamed in B', titleSource: 'user' });
    expect(tabA.getConversationListState().conversations[0]?.title).toBe('Renamed in B');
  });
  it('L16 (record) two tabs acting while the first adoption is still in transit: final server title and number of writes', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Legacy' }));
    const tabA = await freshTab(); await tabA.loadConversations(); await flush();
    const cbA = activity.callbacks.at(-1)!;
    const tabB = await freshTab(); await tabB.loadConversations(); await flush();
    const cbB = activity.callbacks.at(-1)!;
    holdTitles = [];
    const a = cbA(); const b = cbB(); await flush();
    while (holdTitles.length) { holdTitles.shift()!(); await flush(); }
    holdTitles = null; await a; await b; await flush();
    expect(backend.c1).toEqual({ title: 'Legacy', titleSource: 'user' });
    expect(localStorage.getItem('jf-conversation-titles')).toBeNull();
  });
  it('L17 (record) tab B renames while its own adoption is in transit and tab A acts before B confirms', async () => {
    backend = { c1: { title: null, titleSource: null } };
    localStorage.setItem('jf-conversation-titles', JSON.stringify({ c1: 'Legacy' }));
    const tabA = await freshTab(); await tabA.loadConversations(); await flush();
    const cbA = activity.callbacks.at(-1)!;
    const tabB = await freshTab(); await tabB.loadConversations(); await flush();
    const cbB = activity.callbacks.at(-1)!;
    holdTitles = [];
    const b = cbB(); await flush();
    const r = tabB.setConversationTitle('c1', 'Renamed in B'); await flush();
    holdList = [];
    while (holdTitles.length) { holdTitles.shift()!(); await flush(); }
    await r; const a = cbA(); await flush();
    while (holdTitles.length) { holdTitles.shift()!(); await flush(); }
    holdTitles = null;
    while (holdList.length) { holdList.shift()!(); await flush(); }
    holdList = null; await a; await b; await flush();
    expect(backend.c1).toEqual({ title: 'Renamed in B', titleSource: 'user' });
  });
});
