// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { __resetConversationListForTest, deleteConversationWithCascade } from './conversationListStore.js';

const rows = [
  { sessionId: 'root', title: 'Same', createdAtMs: 1, messageCount: 2 },
  { sessionId: 'child', parentSessionId: 'root', branchPointMessageId: 'answer', title: 'Same', createdAtMs: 2, messageCount: 0 },
  { sessionId: 'leaf', parentSessionId: 'child', branchPointMessageId: 'answer', title: 'Same', createdAtMs: 3, messageCount: 0 },
  { sessionId: 'other', title: 'Keep' },
];
let present: Set<string>;
let deletes: string[];
let failId: string | undefined;
let busyId: string | undefined;
let loseResponse = false;
let offline = false;
let refuse: string | undefined;
function respond(body: unknown, status = 200): Response { return new Response(JSON.stringify(body), { status }); }
beforeEach(() => {
  __resetConversationListForTest();
  present = new Set(rows.map(r => r.sessionId)); deletes = []; failId = busyId = refuse = undefined;
  loseResponse = offline = false;
  vi.stubGlobal('fetch', vi.fn(async (url: unknown, init?: RequestInit) => {
    const u = new URL(String(url), 'http://test');
    const id = u.pathname.split('/')[4] ?? '';
    if (init?.method === 'DELETE') {
      deletes.push(id);
      if (id === failId) return respond({ error: 'Disk refused deletion', errorCode: 'CONVERSATION_DELETE_INCOMPLETE' }, 503);
      const children = rows.filter(r => r.parentSessionId === id && present.has(r.sessionId));
      if (children.length || id === refuse) return respond({ errorCode: 'BRANCHES_PREVENT_DELETION', childSessionIds: children.map(r => r.sessionId) }, 409);
      present.delete(id);
      if (loseResponse) { offline = true; throw new Error('response lost'); }
      return respond({ ok: true, agentRunsDeleted: 1 });
    }
    if (offline) throw new Error('Engine unreachable');
    if (u.searchParams.get('existsOnly') === 'true') return respond({ exists: present.has(id) });
    const parent = u.searchParams.get('childrenOf');
    if (parent) return respond({ childrenOf: parent, sessions: rows.filter(r => present.has(r.sessionId) && (r.sessionId === parent || r.parentSessionId === parent))
      .map(r => ({ ...r, busy: r.sessionId === busyId })), locked: false });
    return respond({ sessions: rows.filter(r => present.has(r.sessionId)).slice(0, 1) });
  }));
});
afterEach(() => { __resetConversationListForTest(); vi.unstubAllGlobals(); });

it('TD2 confirms every depth before any DELETE and removes leaves first', async () => {
  const confirm = vi.fn(async (_ids: string[]) => { expect(deletes).toEqual([]); return true; });
  const result = await deleteConversationWithCascade('root', confirm);
  expect(result.ok).toBe(true);
  expect(confirm.mock.calls[0]?.[0]).toEqual(['child', 'leaf']);
  expect(deletes).toEqual(['leaf', 'child', 'root']);
  expect(present).toEqual(new Set(['other']));
});
it('TD2 TD9 cancellation sends no DELETE including a single conversation', async () => {
  const result = await deleteConversationWithCascade('other', async () => false);
  expect(result.ok).toBe(false);
  expect(deletes).toEqual([]);
});
it('TD3 TD4 stops at disk failure and identifies deleted and remaining conversations', async () => {
  failId = 'child';
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(deletes).toEqual(['leaf', 'child']);
  expect(result).toMatchObject({ ok: false, deletedIds: ['leaf'], remainingIds: ['root', 'child'], unconfirmedIds: [] });
  expect(present.has('other')).toBe(true);
});
it('TD6 refuses known active work in any descendant before deleting', async () => {
  busyId = 'child';
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(result).toMatchObject({ ok: false, errorCode: 'CONVERSATION_BUSY' });
  expect(deletes).toEqual([]);
});
it('TD6 rechecks work admitted while the confirmation was open', async () => {
  const result = await deleteConversationWithCascade('root', async () => { busyId = 'root'; return true; });
  expect(result).toMatchObject({ ok: false, errorCode: 'CONVERSATION_BUSY' });
  expect(deletes).toEqual([]);
});
it('TD7 reconciles a lost DELETE response by exact existence rather than the recent list', async () => {
  let once = true;
  const fetcher = globalThis.fetch;
  vi.stubGlobal('fetch', vi.fn(async (url: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'DELETE' && once) { once = false; present.delete('leaf'); deletes.push('leaf'); throw new Error('response lost'); }
    return fetcher(url, init);
  }));
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(result).toMatchObject({ ok: false, deletedIds: ['leaf'], remainingIds: ['root', 'child'], unconfirmedIds: [] });
  expect(deletes).toEqual(['leaf']);
});
it('TD7 keeps unanswered outcomes unconfirmed and never resumes automatically', async () => {
  loseResponse = true;
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(result).toMatchObject({ ok: false, deletedIds: [], remainingIds: [], unconfirmedIds: ['root', 'child', 'leaf'] });
  expect(deletes).toEqual(['leaf']);
});
it('TD5 does not delete a newly discovered child outside the confirmed tree', async () => {
  refuse = 'root';
  const result = await deleteConversationWithCascade('root', async () => true);
  expect(result).toMatchObject({ ok: false, deletedIds: ['child', 'leaf'], remainingIds: ['root'] });
  expect(deletes).toEqual(['leaf', 'child', 'root']);
});
