// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest';
import { ConversationHistory } from './ConversationHistory.js';
import { EPHEMERAL_TOAST_EVENT } from '../advisory/ephemeralToast.js';
import { __resetConversationListForTest, type Conversation } from '../../state/conversationListStore.js';

vi.mock('../ConfirmDialog.js', () => ({ confirmAsync: vi.fn(async () => true) }));

afterEach(() => {
  document.body.replaceChildren();
  __resetConversationListForTest();
  vi.unstubAllGlobals();
});

it('classic delete displays the server refusal and keeps the conversation', async () => {
  vi.stubGlobal('fetch', vi.fn(async (_url: unknown, init?: RequestInit) => new Response(
    JSON.stringify(init?.method === 'DELETE'
      ? { errorCode: 'STORE_LOCKED', error: 'Unlock chat history before deleting.' } : { sessions: [] }),
    { status: init?.method === 'DELETE' ? 423 : 200 },
  )));
  const el = new ConversationHistory();
  document.body.append(el);
  el.open = true;
  el.conversations = [{ id: 'conversation', title: null, titleSource: null, createdAt: 1,
    lastActiveAt: 2, messageCount: 1, firstUserMessage: 'question', shapeId: 'core.free-chat', storeBacked: true } satisfies Conversation];
  await el.updateComplete;
  const seen: string[] = [];
  const listener = (event: Event): void => { seen.push((event as CustomEvent<{ message: string }>).detail.message); };
  document.addEventListener(EPHEMERAL_TOAST_EVENT, listener);
  try {
    el.shadowRoot?.querySelector<HTMLButtonElement>('.item-delete')?.click();
    for (let i = 0; i < 10; i++) { await Promise.resolve(); await el.updateComplete; }
    await vi.waitFor(() => expect(seen).toContain('Unlock chat history before deleting.'));
    await vi.waitFor(() => expect(el.conversations.map(c => c.id)).toContain('conversation'));
  } finally { document.removeEventListener(EPHEMERAL_TOAST_EVENT, listener); }
});
