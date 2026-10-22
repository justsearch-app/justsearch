import { afterEach, describe, expect, it, vi } from 'vitest';
import { deleteConversationWithCascade } from './conversationListStore.js';

afterEach(() => vi.unstubAllGlobals());

describe('conversation deletion refusals', () => {
  it('returns a live-work reason without opening the branch confirmation', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ok: false, errorCode: 'CONVERSATION_BUSY', error: 'Stop the running Ask before deleting.',
    }), { status: 409 })));
    const cascade = vi.fn().mockResolvedValue(true);
    const result = await deleteConversationWithCascade('busy', cascade);
    expect(result).toMatchObject({ ok: false, error: 'Stop the running Ask before deleting.' });
    expect(cascade).not.toHaveBeenCalled();
  });

  it('returns the locked reason', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      ok: false, errorCode: 'STORE_LOCKED', error: 'Unlock chat history before deleting.',
    }), { status: 423 })));
    expect(await deleteConversationWithCascade('locked')).toMatchObject({
      ok: false, error: 'Unlock chat history before deleting.',
    });
  });

  it('reports the failed branch and the branches already deleted', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        errorCode: 'BRANCHES_PREVENT_DELETION', childSessionIds: ['first', 'second'],
      }), { status: 409 }))
      .mockResolvedValueOnce(new Response(JSON.stringify({ ok: true })))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        error: 'Stop the running agent before deleting.', errorCode: 'CONVERSATION_BUSY',
      }), { status: 409 })));
    expect(await deleteConversationWithCascade('parent', async () => true)).toMatchObject({
      ok: false, deletedIds: ['first'], failedId: 'second',
      error: expect.stringContaining('Stop the running agent'),
    });
  });
});
