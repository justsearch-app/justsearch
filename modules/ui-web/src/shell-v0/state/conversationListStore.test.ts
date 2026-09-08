// @vitest-environment happy-dom

/**
 * Slice 515 FIX-9 — conversationListStore branching tests.
 *
 * Covers the store-level surface added in slice 513 (branchConversation,
 * fetchMessageIds, resumeConversation parent pointers). Vitest mocks
 * globalThis.fetch so the tests stay purely unit-level — no dev stack needed.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  setConversationApiBase,
  branchConversation,
  fetchMessageIds,
  resumeConversation,
  deleteConversationWithCascade,
  siblingSessionsAt,
  getRecentSessions,
  recordRecentSession,
  getConversationListState,
  loadConversations,
  setActiveConversation,
  setConversationTitle,
  generateConversationTitle,
  type Conversation,
  __resetConversationListForTest,
} from './conversationListStore.js';

function conv(id: string, opts: Partial<Conversation> = {}): Conversation {
  return {
    id,
    title: null,
    titleSource: null,
    createdAt: 0,
    lastActiveAt: 0,
    messageCount: 0,
    firstUserMessage: '',
    shapeId: 'core.rag-ask',
    storeBacked: true,
    ...opts,
  };
}

describe('Tempdoc 610 Phase B — siblingSessionsAt', () => {
  it('returns the base alone when there are no branches', () => {
    const convs = [conv('P'), conv('other', { parentSessionId: 'P', branchPointMessageId: 'XX' })];
    expect(siblingSessionsAt(convs, 'P', 'm1')).toEqual(['P']);
  });

  it('orders base first, then branches at the same fork by creation time', () => {
    const convs = [
      conv('P'),
      conv('B2', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 200 }),
      conv('B1', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 100 }),
      // different branch point — excluded
      conv('B3', { parentSessionId: 'P', branchPointMessageId: 'm9', createdAt: 150 }),
      // different parent — excluded
      conv('X', { parentSessionId: 'Q', branchPointMessageId: 'm1', createdAt: 50 }),
    ];
    expect(siblingSessionsAt(convs, 'P', 'm1')).toEqual(['P', 'B1', 'B2']);
  });

  it('groups empty-prefix (first-message) edits under the sentinel key', () => {
    const convs = [
      conv('P'),
      conv('E1', { parentSessionId: 'P', branchPointMessageId: '__empty_prefix__', createdAt: 10 }),
    ];
    expect(siblingSessionsAt(convs, 'P', '__empty_prefix__')).toEqual(['P', 'E1']);
  });
});

interface RecordedCall {
  url: string;
  method: string;
  /** The request body as sent, or '' for a bodyless request. */
  body: string;
}

function mockFetch(handler: (url: string, method: string) => Response): RecordedCall[] {
  const calls: RecordedCall[] = [];
  globalThis.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
    const u = url.toString();
    const method = init?.method ?? 'GET';
    calls.push({ url: u, method, body: String(init?.body ?? '') });
    return Promise.resolve(handler(u, method));
  }) as typeof fetch;
  return calls;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('conversationListStore branching', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('branchConversation POSTs the branch endpoint with fromMsgId and returns new sessionId', async () => {
    const calls = mockFetch((url) => {
      if (url.includes('/branch?fromMsgId=')) {
        return jsonResponse({
          sessionId: 'uc-branch-1',
          parentSessionId: 'parent-1',
          branchPointMessageId: 'msg-X',
        });
      }
      return jsonResponse({}, 404);
    });
    const result = await branchConversation('parent-1', 'msg-X', 'first-msg preview');
    expect(result).toBe('uc-branch-1');
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toContain('/api/chat/conversations/parent-1/branch');
    expect(calls[0]?.url).toContain('fromMsgId=msg-X');
  });

  it('branchConversation URL-encodes parent + msgId path segments', async () => {
    const calls = mockFetch(() => jsonResponse({ sessionId: 'uc-x' }));
    await branchConversation('weird/parent id', 'msg with spaces', 'preview');
    expect(calls[0]?.url).toContain('weird%2Fparent%20id');
    expect(calls[0]?.url).toContain('msg%20with%20spaces');
  });

  it('branchConversation returns null on non-2xx response', async () => {
    mockFetch(() => jsonResponse({ error: 'bad' }, 400));
    const result = await branchConversation('parent-1', 'msg-X');
    expect(result).toBeNull();
  });

  it('branchConversation returns null when response lacks sessionId', async () => {
    mockFetch(() => jsonResponse({}));
    const result = await branchConversation('p', 'm');
    expect(result).toBeNull();
  });

  it('branchConversation returns null on network error', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('netdown'))) as typeof fetch;
    const result = await branchConversation('p', 'm');
    expect(result).toBeNull();
  });

  it('fetchMessageIds returns the message list with ids', async () => {
    mockFetch((url) => {
      if (url.includes('/history')) {
        return jsonResponse({
          messages: [
            { role: 'user', content: 'q1', id: 'idA' },
            { role: 'assistant', content: 'a1', id: 'idB' },
          ],
        });
      }
      return jsonResponse({}, 404);
    });
    const out = await fetchMessageIds('parent-1');
    expect(out).not.toBeNull();
    expect(out).toEqual([
      { role: 'user', content: 'q1', id: 'idA' },
      { role: 'assistant', content: 'a1', id: 'idB' },
    ]);
  });

  it('fetchMessageIds returns null on non-2xx', async () => {
    mockFetch(() => jsonResponse({}, 500));
    expect(await fetchMessageIds('x')).toBeNull();
  });

  it('fetchMessageIds returns null on network error', async () => {
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('boom'))) as typeof fetch;
    expect(await fetchMessageIds('x')).toBeNull();
  });

  it('resumeConversation surfaces parentSessionId + branchPointMessageId when the backend includes them', async () => {
    mockFetch(() =>
      jsonResponse({
        messages: [{ role: 'user', content: 'q', id: 'mid-1' }],
        parentSessionId: 'parent-A',
        branchPointMessageId: 'mid-1',
      }),
    );
    const resumed = await resumeConversation('branch-A', 'core.free-chat');
    expect(resumed.parentSessionId).toBe('parent-A');
    expect(resumed.branchPointMessageId).toBe('mid-1');
    expect(resumed.messages).toEqual([{ role: 'user', content: 'q', id: 'mid-1' }]);
  });

  it('resumeConversation CLAIMS the active conversation by default, and not when the caller declines', async () => {
    // Tempdoc 852 S1 — the claim is right for a window's open path and wrong for a companion load
    // running while the reader is elsewhere. Both directions asserted: a default that stopped
    // claiming would break the shipped window's open, and an opt-out that still claimed would let a
    // background read move the product's idea of where the reader is.
    mockFetch(() => jsonResponse({ messages: [] }));
    setActiveConversation('uc-open');
    await resumeConversation('uc-read-only', 'core.free-chat', { claim: false });
    expect(getConversationListState().activeId).toBe('uc-open');
    await resumeConversation('uc-navigated-to', 'core.free-chat');
    expect(getConversationListState().activeId).toBe('uc-navigated-to');
  });

  /**
   * Tempdoc 941 — a resumed thread carries the same PER-MESSAGE shape data as a live one.
   *
   * `FileConversationStore.enrichMessage` stamps `shapeId` per appended message (863 §4.A.5 F1)
   * exactly because the conversation-level shape is first-wins and therefore wrong for a mixed
   * conversation. The endpoint returns the store's maps verbatim, so the fact was already on the
   * wire; this reader dropped it, and the frame authority then classified a reloaded RAG answer
   * by the conversation's opening mode.
   */
  describe('941 — resumeConversation preserves per-message shape data', () => {
    it('reads the top-level per-message shapeId the store stamps', async () => {
      mockFetch(() =>
        jsonResponse({
          messages: [
            { role: 'user', content: 'q', id: 'm1', shapeId: 'core.rag-ask' },
            { role: 'assistant', content: 'a', id: 'm2', shapeId: 'core.agent-run' },
          ],
        }),
      );
      const resumed = await resumeConversation('mixed-1', 'core.free-chat');
      expect(resumed.messages.map((m) => m.shapeId)).toEqual([
        'core.rag-ask',
        'core.agent-run',
      ]);
      // The conversation-level shape is untouched — the two are different facts.
      expect(resumed.shapeId).toBe('core.free-chat');
    });

    it('also accepts the unified-thread spelling (attributes.shapeId)', async () => {
      mockFetch(() =>
        jsonResponse({
          messages: [
            { role: 'assistant', content: 'a', id: 'm1', attributes: { shapeId: 'core.extract' } },
          ],
        }),
      );
      const resumed = await resumeConversation('c', 'core.free-chat');
      expect(resumed.messages[0]?.shapeId).toBe('core.extract');
    });

    it('leaves shapeId absent for a pre-863 row, and for a blank one', async () => {
      mockFetch(() =>
        jsonResponse({
          messages: [
            { role: 'user', content: 'legacy', id: 'm1' },
            { role: 'user', content: 'blank', id: 'm2', shapeId: '   ' },
            { role: 'user', content: 'wrongtype', id: 'm3', shapeId: 42 },
          ],
        }),
      );
      const resumed = await resumeConversation('legacy-1', 'core.free-chat');
      // Absent, not defaulted: the caller's conversation-level fallback is the only fact
      // available for these rows, and a stamped guess would claim otherwise.
      expect(resumed.messages.every((m) => m.shapeId === undefined)).toBe(true);
    });

    it('941 review — returns the TRIMMED value, so a padded shapeId still narrows', async () => {
      // The blankness test already trims; returning the raw value meant `" core.rag-ask "` passed
      // the guard here and then failed the exact-match narrowing in `asKnownShape` — accepted at
      // one end, silently dropped at the other.
      mockFetch(() =>
        jsonResponse({
          messages: [
            { role: 'user', content: 'q', id: 'm1', shapeId: '  core.rag-ask\n' },
            {
              role: 'assistant',
              content: 'a',
              id: 'm2',
              attributes: { 'rag.standaloneQuestion': '  padded question  ' },
            },
          ],
        }),
      );
      const resumed = await resumeConversation('c', 'core.free-chat');
      expect(resumed.messages[0]?.shapeId).toBe('core.rag-ask');
      expect(resumed.messages[1]?.standaloneQuestion).toBe('padded question');
    });

    it('carries rag.standaloneQuestion when the record has it, under either spelling', async () => {
      mockFetch(() =>
        jsonResponse({
          messages: [
            {
              role: 'assistant',
              content: 'a',
              id: 'm1',
              attributes: { 'rag.standaloneQuestion': 'What did the Q3 report say about churn?' },
            },
            {
              role: 'assistant',
              content: 'b',
              id: 'm2',
              attributes: { standaloneQuestion: 'Second spelling' },
            },
            { role: 'assistant', content: 'c', id: 'm3' },
          ],
        }),
      );
      const resumed = await resumeConversation('c', 'core.rag-ask');
      expect(resumed.messages.map((m) => m.standaloneQuestion)).toEqual([
        'What did the Q3 report say about churn?',
        'Second spelling',
        undefined,
      ]);
    });
  });

  it('resumeConversation omits parent pointers for a root session', async () => {
    mockFetch(() => jsonResponse({ messages: [{ role: 'user', content: 'q', id: 'a' }] }));
    const resumed = await resumeConversation('root-1', 'core.free-chat');
    expect(resumed.parentSessionId).toBeUndefined();
    expect(resumed.branchPointMessageId).toBeUndefined();
  });

  it('Slice 516 FIX-T5: resumeConversation surfaces parentFirstUserMessage from response', async () => {
    mockFetch(() =>
      jsonResponse({
        messages: [{ role: 'user', content: 'q', id: 'mid' }],
        parentSessionId: 'parent-1',
        branchPointMessageId: 'mid',
        parentFirstUserMessage: 'How do I summarize PDFs?',
      }),
    );
    const resumed = await resumeConversation('branch-1', 'core.free-chat');
    expect(resumed.parentFirstUserMessage).toBe('How do I summarize PDFs?');
  });

  it('Slice 516 FIX-T5: branchConversation records the new session as recent (when preview supplied)', async () => {
    mockFetch(() => jsonResponse({ sessionId: 'uc-branched-recent' }));
    // Pre-condition: localStorage has no entry for the new id.
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    const result = await branchConversation('p', 'm', 'first user msg preview');
    expect(result).toBe('uc-branched-recent');
    const stored = JSON.parse(localStorage.getItem(KEY) ?? '[]') as Array<Record<string, unknown>>;
    const entry = stored.find((s) => s.sessionId === 'uc-branched-recent');
    expect(entry).toBeDefined();
    // Tempdoc 562: the recent-session pointer must NOT carry message content (no plaintext at rest); the
    // preview is derived from the lock-safe backend list instead.
    expect(entry).not.toHaveProperty('firstMessage');
    expect(JSON.stringify(entry)).not.toContain('first user msg preview');
    localStorage.removeItem(KEY); // cleanup
  });

  it('Slice 516 FIX-T5: branchConversation does NOT record when no preview is supplied', async () => {
    mockFetch(() => jsonResponse({ sessionId: 'uc-no-preview' }));
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    await branchConversation('p', 'm'); // no preview
    const stored = JSON.parse(localStorage.getItem(KEY) ?? '[]') as Array<{
      sessionId: string;
    }>;
    expect(stored.find((s) => s.sessionId === 'uc-no-preview')).toBeUndefined();
    localStorage.removeItem(KEY); // cleanup
  });

  it('Tempdoc 562: recordRecentSession stores ONLY the pointer (no message content)', () => {
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    recordRecentSession('uc-pointer-only');
    const raw = localStorage.getItem(KEY) ?? '[]';
    expect(raw).not.toContain('firstMessage');
    const stored = JSON.parse(raw) as Array<Record<string, unknown>>;
    expect(stored[0]).toMatchObject({ sessionId: 'uc-pointer-only' });
    expect(stored[0]).not.toHaveProperty('firstMessage');
    localStorage.removeItem(KEY);
  });

  it('Tempdoc 562: getRecentSessions purges legacy cached plaintext at rest', () => {
    const KEY = 'jf-chat-recent-sessions';
    // Seed a LEGACY entry that carried plaintext message content (the pre-562 shape).
    localStorage.setItem(
      KEY,
      JSON.stringify([{ sessionId: 'uc-legacy', firstMessage: 'SECRET plaintext leak', timestamp: 123 }]),
    );
    const sessions = getRecentSessions();
    // The reader returns pointer-only entries…
    expect(sessions).toEqual([{ sessionId: 'uc-legacy', timestamp: 123 }]);
    // …AND rewrites localStorage so the cached plaintext is purged at rest.
    const after = localStorage.getItem(KEY) ?? '';
    expect(after).not.toContain('SECRET plaintext leak');
    expect(after).not.toContain('firstMessage');
    localStorage.removeItem(KEY);
  });
});

describe('deleteConversationWithCascade (Slice 517 FIX-U1)', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('happy path: 2xx delete returns ok:true and clears local state', async () => {
    const calls = mockFetch(() => jsonResponse({ ok: true }));
    const result = await deleteConversationWithCascade('uc-1');
    expect(result).toEqual({ ok: true });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe('DELETE');
  });

  it('409 without onChildsFound callback returns ok:false with childIds', async () => {
    mockFetch(() =>
      jsonResponse({
        error: 'has branches',
        errorCode: 'BRANCHES_PREVENT_DELETION',
        childSessionIds: ['child-1', 'child-2'],
      }, 409),
    );
    const result = await deleteConversationWithCascade('parent-1');
    expect(result.ok).toBe(false);
    expect(result.childIds).toEqual(['child-1', 'child-2']);
  });

  it('409 with onChildsFound returning false leaves parent + children intact', async () => {
    const calls = mockFetch(() =>
      jsonResponse({
        errorCode: 'BRANCHES_PREVENT_DELETION',
        childSessionIds: ['child-X'],
      }, 409),
    );
    const onChildsFound = vi.fn().mockResolvedValue(false);
    const result = await deleteConversationWithCascade('parent-X', onChildsFound);
    expect(result.ok).toBe(false);
    expect(result.childIds).toEqual(['child-X']);
    expect(onChildsFound).toHaveBeenCalledWith(['child-X']);
    // Only the original DELETE attempt — no cascade.
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('409 with onChildsFound returning true cascades: children first, then parent', async () => {
    // Sequence the fetch responses: first parent DELETE → 409 with one
    // child id; child DELETE → 200; parent retry → 200.
    let parentAttempt = 0;
    const fetched: string[] = [];
    globalThis.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      const u = url.toString();
      const method = init?.method ?? 'GET';
      fetched.push(`${method} ${u}`);
      if (u.includes('/parent-1') && method === 'DELETE') {
        parentAttempt++;
        if (parentAttempt === 1) {
          return Promise.resolve(jsonResponse({
            errorCode: 'BRANCHES_PREVENT_DELETION',
            childSessionIds: ['child-A'],
          }, 409));
        }
        return Promise.resolve(jsonResponse({ ok: true }));
      }
      if (u.includes('/child-A') && method === 'DELETE') {
        return Promise.resolve(jsonResponse({ ok: true }));
      }
      return Promise.resolve(jsonResponse({}, 404));
    }) as typeof fetch;

    const onChildsFound = vi.fn().mockResolvedValue(true);
    const result = await deleteConversationWithCascade('parent-1', onChildsFound);
    expect(result).toEqual({ ok: true });
    expect(onChildsFound).toHaveBeenCalledWith(['child-A']);
    // Sequence: parent-1 (409) → child-A (200) → parent-1 (200).
    expect(fetched).toEqual([
      'DELETE /api/chat/conversations/parent-1',
      'DELETE /api/chat/conversations/child-A',
      'DELETE /api/chat/conversations/parent-1',
    ]);
  });

  it('cascade aborts if a child delete fails — parent stays intact', async () => {
    let parentAttempt = 0;
    globalThis.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      const u = url.toString();
      const method = init?.method ?? 'GET';
      if (u.includes('/parent-2') && method === 'DELETE') {
        parentAttempt++;
        return Promise.resolve(jsonResponse({
          errorCode: 'BRANCHES_PREVENT_DELETION',
          childSessionIds: ['child-fail'],
        }, 409));
      }
      if (u.includes('/child-fail') && method === 'DELETE') {
        return Promise.resolve(jsonResponse({ error: 'network glitch' }, 500));
      }
      return Promise.resolve(jsonResponse({}, 404));
    }) as typeof fetch;

    const onChildsFound = vi.fn().mockResolvedValue(true);
    const result = await deleteConversationWithCascade('parent-2', onChildsFound);
    expect(result.ok).toBe(false);
    // parent was attempted exactly once — no retry after child failure.
    expect(parentAttempt).toBe(1);
  });
});

/* ── Tempdoc 838 — the title is the BACKEND's fact now ───────────────────────────────────────── */

const TITLES_KEY = 'jf-conversation-titles';

/** One row of `GET /api/chat/conversations`, in the shape the Head emits. */
function wireRow(id: string, over: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sessionId: id,
    shapeId: 'core.rag-ask',
    createdAtMs: 1,
    lastActiveAtMs: 2,
    messageCount: 2,
    firstUserMessage: 'why did the renewal fail?',
    ...over,
  };
}

const titlePosts = (calls: readonly RecordedCall[]): RecordedCall[] =>
  calls.filter((c) => c.url.endsWith('/title') && c.method === 'POST');

describe('tempdoc 838 — conversation titles are written through to the backend', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  it('renames optimistically, then POSTs the name and its provenance', async () => {
    const calls = mockFetch((url) =>
      url.endsWith('/title')
        ? jsonResponse({ ok: true, title: 'Lease terms' })
        : jsonResponse({ sessions: [wireRow('uc-a')] }),
    );
    await loadConversations();

    const write = setConversationTitle('uc-a', 'Lease terms');
    // SYNCHRONOUSLY renamed — the row does not wait for the network, because a name that flickered
    // for the length of a request would be worse than one that can fail.
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
    expect(await write).toEqual({ ok: true, status: 200 });

    const posts = titlePosts(calls);
    expect(posts).toHaveLength(1);
    expect(posts[0]?.url).toBe('/api/chat/conversations/uc-a/title');
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({ title: 'Lease terms', source: 'user' });
    // And NOTHING was mirrored into the retired browser map — the whole point of the seam.
    expect(localStorage.getItem(TITLES_KEY)).toBeNull();
  });

  it('URL-encodes the session id and carries an auto-title as `auto`', async () => {
    const calls = mockFetch((url) =>
      url.endsWith('/title') ? jsonResponse({ ok: true }) : jsonResponse({ sessions: [] }),
    );
    await setConversationTitle('weird/id', 'Named by the model', 'auto');
    expect(calls[0]?.url).toBe('/api/chat/conversations/weird%2Fid/title');
    expect(JSON.parse(calls[0]?.body ?? '{}').source).toBe('auto');
  });

  it('REVERTS the row when the store refuses, and reports the status so the caller can word it', async () => {
    mockFetch((url) =>
      url.endsWith('/title')
        ? jsonResponse({ errorCode: 'STORE_LOCKED', locked: true }, 423)
        : jsonResponse({ sessions: [wireRow('uc-a', { title: 'Lease terms', titleSource: 'user' })] }),
    );
    await loadConversations();
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');

    const written = await setConversationTitle('uc-a', 'Renewal postmortem');
    expect(written).toEqual({ ok: false, status: 423 });
    // Back to the name the store actually holds — a refused write must not leave a name on screen
    // that nothing remembers.
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
    expect(getConversationListState().conversations[0]?.titleSource).toBe('user');
  });

  it('reverts on an unreachable Head too, and calls that status 0', async () => {
    mockFetch(() => jsonResponse({ sessions: [wireRow('uc-a')] }));
    await loadConversations();
    globalThis.fetch = vi.fn(() => Promise.reject(new Error('offline'))) as typeof fetch;

    expect(await setConversationTitle('uc-a', 'Lease terms')).toEqual({ ok: false, status: 0 });
    expect(getConversationListState().conversations[0]?.title).toBeNull();
  });

  it('holds a name the conversation was too young for (404) and re-sends it on the next list', async () => {
    // The §1d race: a rename between opening a conversation and its first message being appended.
    // The endpoint answers 404 rather than minting a zero-message row that would name nothing.
    let created = false;
    const calls = mockFetch((url) => {
      if (url.endsWith('/title')) {
        return created ? jsonResponse({ ok: true }) : jsonResponse({ error: 'no such' }, 404);
      }
      return jsonResponse({ sessions: created ? [wireRow('uc-young')] : [] });
    });

    const written = await setConversationTitle('uc-young', 'Lease terms');
    // Not reported as a failure: it is a race with creation, not a refusal, so the name STAYS.
    expect(written).toEqual({ ok: true, status: 404 });
    expect(titlePosts(calls)).toHaveLength(1);

    created = true;
    await loadConversations();
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(titlePosts(calls)).toHaveLength(2);
    expect(JSON.parse(titlePosts(calls)[1]?.body ?? '{}')).toEqual({
      title: 'Lease terms',
      source: 'user',
    });
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
  });

  it('adopts a legacy browser-local title, then ERASES the key — the migration completes', async () => {
    localStorage.setItem(TITLES_KEY, JSON.stringify({ 'uc-a': 'Lease terms' }));
    const calls = mockFetch((url) =>
      url.endsWith('/title') ? jsonResponse({ ok: true }) : jsonResponse({ sessions: [wireRow('uc-a')] }),
    );

    await loadConversations();
    // No regression in what the reader sees: the legacy name renders immediately, before any write.
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
    await new Promise<void>((r) => setTimeout(r, 0));

    const posts = titlePosts(calls);
    expect(posts).toHaveLength(1);
    // Adopted as the READER's: an existing name is one somebody kept, and calling it auto-generated
    // would license the next ask to overwrite it.
    expect(JSON.parse(posts[0]?.body ?? '{}')).toEqual({ title: 'Lease terms', source: 'user' });
    // The last entry is gone, so the whole key is gone — the plaintext-at-rest defect with it.
    expect(localStorage.getItem(TITLES_KEY)).toBeNull();
  });

  it('does NOT erase a legacy title the locked store refused, and stops re-POSTing it', async () => {
    localStorage.setItem(TITLES_KEY, JSON.stringify({ 'uc-a': 'Lease terms', 'uc-b': 'Renewal' }));
    const calls = mockFetch((url) =>
      url.endsWith('/title')
        ? jsonResponse({ errorCode: 'STORE_LOCKED' }, 423)
        : jsonResponse({ sessions: [wireRow('uc-a'), wireRow('uc-b')] }),
    );

    await loadConversations();
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(titlePosts(calls)).toHaveLength(2);
    // Nothing was lost to the lock: the next unlocked list adopts them.
    expect(JSON.parse(localStorage.getItem(TITLES_KEY) ?? '{}')).toEqual({
      'uc-a': 'Lease terms',
      'uc-b': 'Renewal',
    });

    // ...but this session stops asking. Twenty 423s per list refresh is a burst that cannot succeed.
    await loadConversations();
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(titlePosts(calls)).toHaveLength(2);
  });

  it('drops a legacy entry the backend has already outgrown, without a second write', async () => {
    localStorage.setItem(TITLES_KEY, JSON.stringify({ 'uc-a': 'Stale local name' }));
    const calls = mockFetch((url) =>
      url.endsWith('/title')
        ? jsonResponse({ ok: true })
        : jsonResponse({ sessions: [wireRow('uc-a', { title: 'Lease terms', titleSource: 'user' })] }),
    );

    await loadConversations();
    await new Promise<void>((r) => setTimeout(r, 0));
    // The backend's name wins outright, and the local one is not re-adopted over it.
    expect(getConversationListState().conversations[0]?.title).toBe('Lease terms');
    expect(titlePosts(calls)).toHaveLength(0);
    expect(localStorage.getItem(TITLES_KEY)).toBeNull();
  });

  it('reads title + provenance off the wire, and narrows an unknown provenance to none', async () => {
    mockFetch(() =>
      jsonResponse({
        sessions: [
          wireRow('uc-a', { title: 'Lease terms', titleSource: 'auto' }),
          wireRow('uc-b', { title: 'Named', titleSource: 'something-else' }),
          wireRow('uc-c'),
        ],
      }),
    );
    await loadConversations();
    const [a, b, c] = getConversationListState().conversations;
    expect([a?.title, a?.titleSource]).toEqual(['Lease terms', 'auto']);
    // An unrecognised value is NO provenance rather than a wrong one.
    expect([b?.title, b?.titleSource]).toEqual(['Named', null]);
    expect([c?.title, c?.titleSource]).toEqual([null, null]);
  });
});

describe('tempdoc 835 §10f — auto-title generation is plumbing, not an answer', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  it('suppresses thinking on the throwaway title turn', async () => {
    // The reasoning budget is on server-wide, so a dispatch that omits enableThinking buys a full
    // reasoning pass — here, to produce a 3-5 word title. The kwarg must be false on the wire.
    const calls = mockFetch((url) => {
      if (url.includes('/api/chat/dispatch')) {
        return new Response('event: chunk\ndata: {"text":"Lease terms"}\n\n', {
          status: 200,
          headers: { 'content-type': 'text/event-stream' },
        });
      }
      return jsonResponse({});
    });

    await generateConversationTitle('sess-1', 'What are the lease terms?', 'They run 12 months.');

    const dispatch = calls.find((c) => c.url.includes('/api/chat/dispatch'));
    expect(dispatch).toBeDefined();
    expect(JSON.parse(dispatch!.body).enableThinking).toBe(false);
  });
});

describe('tempdoc 859 slice C PR-2 — the list joins a second record, so a row can be run-backed', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('reads a synthesized delegate row without inventing a message count', async () => {
    mockFetch(() =>
      jsonResponse({
        sessions: [
          {
            sessionId: 'conv-delegate',
            shapeId: 'core.agent-run',
            createdAtMs: 5,
            lastActiveAtMs: 9,
            firstUserMessage: 'summarise the renewal folder',
            storeBacked: false,
          },
        ],
      }),
    );
    await loadConversations();

    const c = getConversationListState().conversations[0];
    expect(c?.id).toBe('conv-delegate');
    expect(c?.storeBacked).toBe(false);
    // Absent, not zero: "0 messages" on a conversation with a full transcript would be a claim, and
    // a wrong one. The row renders no count at all.
    expect(c?.messageCount).toBeUndefined();
  });

  it('treats a row with no storeBacked key as store-backed, so every existing row is unchanged', async () => {
    mockFetch(() => jsonResponse({ sessions: [wireRow('uc-a')] }));
    await loadConversations();

    const c = getConversationListState().conversations[0];
    expect(c?.storeBacked).toBe(true);
    expect(c?.messageCount).toBe(2);
  });
});
