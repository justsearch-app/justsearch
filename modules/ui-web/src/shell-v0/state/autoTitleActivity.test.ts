// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0
/**
 * Scenario S7 — background picture reading runs while nobody uses the product and stops when a person
 * acts. What a person does is told apart by the route's declared ActivityRole: only USER_ACTION counts.
 *
 * Once a person's question has been answered, the window goes on by itself: it refreshes the list and
 * asks the model to name the new conversation. None of that is the person acting, so none of it may
 * reach a USER_ACTION route — it would stop a picture-reading pass with nobody at the keyboard. The
 * roles are read from the routes' own declarations, so this follows the server, not a copy of it.
 */
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  __resetConversationListForTest,
  generateConversationTitle,
  getConversationListState,
  loadConversations,
  setConversationApiBase,
} from './conversationListStore.js';

const api = resolve(import.meta.dirname, '../../../../ui/src/main/java/io/justsearch/ui/api');
const roles = new Map<string, string>();
for (const entry of readdirSync(api, { recursive: true }).filter((name) => String(name).endsWith('.java'))) {
  const source = readFileSync(resolve(api, String(entry)), 'utf8');
  for (const m of source.matchAll(/app\.(get|post|put|patch|delete|sse|head|options)\(\s*"([^"]+)"[\s\S]*?ActivityRole\.(\w+)\)/g)) {
    roles.set(`${m[1] === 'sse' ? 'GET' : m[1]!.toUpperCase()} ${m[2]}`, m[3]!);
  }
}
function roleOf(method: string, path: string): string | undefined {
  const direct = roles.get(`${method} ${path}`);
  if (direct) return direct;
  for (const [operation, role] of roles) {
    const [verb, template] = operation.split(' ') as [string, string];
    const pattern = '^' + template.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/\{[^}]+\}/g, '[^/]+') + '$';
    if (verb === method && new RegExp(pattern).test(path)) return role;
  }
  return undefined;
}

type Call = { method: string; path: string };
let calls: Call[] = [];
let titled = false;

beforeEach(() => {
  __resetConversationListForTest();
  setConversationApiBase('');
  localStorage.clear();
  calls = [];
  titled = false;
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://idle.test').pathname;
    const method = (init?.method ?? 'GET').toUpperCase();
    calls.push({ method, path });
    const json = (body: unknown): Response =>
      new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
    if (path === '/api/chat/conversations' && method === 'GET') {
      return json({ sessions: [{ sessionId: 'conv-1', shapeId: 'core.rag-ask', createdAtMs: 1, lastActiveAtMs: 2,
        messageCount: 2, ...(titled ? { title: 'Lease terms', titleSource: 'auto' } : {}) }] });
    }
    if (path.endsWith('/auto-title')) {
      titled = true;
      return json({ ok: true, generated: true, title: 'Lease terms', titleSource: 'auto' });
    }
    if (path === '/api/chat/dispatch') {
      // The model's reply to a title request made as a chat turn.
      return new Response('event: chunk\ndata: {"text":"Lease terms"}\n\nevent: done\ndata: {}\n\n', {
        status: 200, headers: { 'content-type': 'text/event-stream' },
      });
    }
    if (path.endsWith('/title') && method === 'POST') { titled = true; return json({ ok: true, title: 'Lease terms', titleSource: 'auto' }); }
    return json({ ok: true });
  }) as typeof fetch;
});
afterEach(() => { vi.restoreAllMocks(); });

it('the roles are really read: a person\'s own chat dispatch is the activity this guards', () => {
  expect(roleOf('POST', '/api/chat/dispatch')).toBe('USER_ACTION');
  expect(roleOf('GET', '/api/chat/conversations')).toBe('OBSERVATION');
});

it('an untouched window after an answer — list refresh and automatic naming — sends nothing that counts as a person acting', async () => {
  // What the window does by itself once the answer has landed (SearchV3View.onDone, UnifiedChatView).
  await loadConversations();
  const generated = await generateConversationTitle('conv-1', 'What are the lease terms?', 'They run 12 months.');
  await loadConversations();
  for (let i = 0; i < 5; i++) await new Promise<void>((r) => setTimeout(r, 0));

  expect(calls.length, 'the window must really make its follow-on requests').toBeGreaterThan(2);
  expect(calls.filter((call) => roleOf(call.method, call.path) !== 'OBSERVATION')
    .map((call) => `${call.method} ${call.path}: ${roleOf(call.method, call.path)}`),
  'every request the window makes by itself after an answer must be an observation').toEqual([]);
  // ...and the conversation is still named.
  expect(generated).toBe('Lease terms');
  expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
});
