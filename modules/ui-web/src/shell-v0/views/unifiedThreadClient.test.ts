// SPDX-License-Identifier: Apache-2.0
// Tempdoc S4a (risk-review finding #1) — regression coverage for forward-tolerant thread-event parsing.
// Before this change, `threadResponseSchema` validated the WHOLE `events` array atomically
// (z.array(threadEventSchema)): one event with an unrecognized `kind` failed the entire parse and
// `fetchUnifiedThread` returned EMPTY — the whole conversation blanked. These tests pin the per-event
// parsing that replaced it: an unknown kind degrades to a generic item; a structurally invalid event is
// dropped (with a warning) but never sinks its siblings; known-kind strictness is unchanged.

import { describe, it, expect, vi, afterEach } from 'vitest';
import { fetchUnifiedThread, parseThreadEvent } from './unifiedThreadClient.js';

function jsonResponse(body: unknown): Response {
  return { ok: true, json: async () => body } as Response;
}

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('parseThreadEvent (S4a per-event parsing)', () => {
  it('parses a known-kind event under the unchanged strict schema', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      content: 'hello',
      attributes: {},
    });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('ASSISTANT_MESSAGE');
    expect(event!.content).toBe('hello');
  });

  // Tempdoc S4b — SEARCH is now a real, known kind (see the dedicated `SEARCH-kind event parsing`
  // describe block below), so these forward-tolerance tests use a still-unrecognized placeholder kind
  // (`WORKSPACE_SYNCED`) instead.
  it('degrades an unrecognized kind STRING to a generic UNKNOWN event carrying rawKind', () => {
    const event = parseThreadEvent({
      id: 's1',
      occurredAt: '2026-01-01T00:00:02Z',
      kind: 'WORKSPACE_SYNCED',
      originator: 'agent',
      content: 'syncing…',
      attributes: { query: 'invoices' },
    });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('UNKNOWN');
    expect(event!.rawKind).toBe('WORKSPACE_SYNCED');
    expect(event!.attributes.query).toBe('invoices');
  });

  it('degrades an unrecognized kind even when originator/content/attributes are absent (loose passthrough)', () => {
    const event = parseThreadEvent({
      id: 's2',
      occurredAt: '2026-01-01T00:00:03Z',
      kind: 'WORKSPACE_SYNCED',
    });
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('UNKNOWN');
    expect(event!.rawKind).toBe('WORKSPACE_SYNCED');
    expect(event!.originator).toBe('');
    expect(event!.content).toBe('');
    expect(event!.attributes).toEqual({});
  });

  it('drops a structurally invalid event (missing id) regardless of kind', () => {
    expect(
      parseThreadEvent({ occurredAt: '2026-01-01T00:00:01Z', kind: 'USER_MESSAGE', content: 'hi' }),
    ).toBeNull();
    expect(
      parseThreadEvent({ occurredAt: '2026-01-01T00:00:01Z', kind: 'WORKSPACE_SYNCED' }),
    ).toBeNull();
  });

  // 4c — verify + preserve current known-kind strictness: a KNOWN kind whose required field is missing
  // (or wrong-typed) is NOT silently downgraded to a generic UNKNOWN item; it is dropped, exactly as it
  // would have failed the old all-or-nothing schema (only the BLAST RADIUS changed — one event, not the
  // whole array).
  it('a known kind with a missing required field (content) still fails THAT event, not silently as UNKNOWN', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'ASSISTANT_MESSAGE',
      originator: 'agent',
      // content missing — required by the known-kind schema
      attributes: {},
    });
    expect(event).toBeNull();
  });

  it('a known kind with a wrong-typed required field (content: number) still fails THAT event', () => {
    const event = parseThreadEvent({
      id: 'a1',
      occurredAt: '2026-01-01T00:00:01Z',
      kind: 'USER_MESSAGE',
      originator: 'user',
      content: 42,
      attributes: {},
    });
    expect(event).toBeNull();
  });
});

// Tempdoc S4b (Search Thread) — SEARCH's own strict attributes schema (searchThreadEventSchema),
// distinct from the generic per-kind schema every other known kind shares.
describe('parseThreadEvent (S4b SEARCH-kind strict attributes)', () => {
  function searchWireEvent(overrides: Record<string, unknown> = {}) {
    return {
      id: 's1',
      occurredAt: '2026-01-01T00:00:02Z',
      kind: 'SEARCH',
      originator: 'user',
      content: '',
      attributes: {
        query: 'invoices',
        mode: 'hybrid',
        matchCount: 42,
        resultCount: 10,
        docIds: ['a.pdf', 'b.pdf'],
        executedAt: '2026-01-01T00:00:02Z',
      },
      ...overrides,
    };
  }

  it('parses a well-formed SEARCH event with its typed attributes intact', () => {
    const event = parseThreadEvent(searchWireEvent());
    expect(event).not.toBeNull();
    expect(event!.kind).toBe('SEARCH');
    expect(event!.attributes.query).toBe('invoices');
    expect(event!.attributes.mode).toBe('hybrid');
    expect(event!.attributes.matchCount).toBe(42);
    expect(event!.attributes.resultCount).toBe(10);
    expect(event!.attributes.docIds).toEqual(['a.pdf', 'b.pdf']);
    expect(event!.attributes.executedAt).toBe('2026-01-01T00:00:02Z');
  });

  it('drops (not UNKNOWN-degrades) a SEARCH event missing a required attribute', () => {
    const missingDocIds = searchWireEvent({
      attributes: { query: 'invoices', mode: 'hybrid', matchCount: 42, resultCount: 10 },
    });
    expect(parseThreadEvent(missingDocIds)).toBeNull();
  });

  it('drops a SEARCH event with a wrong-typed attribute (matchCount as a string)', () => {
    const wrongType = searchWireEvent({
      attributes: {
        query: 'invoices',
        mode: 'hybrid',
        matchCount: '42',
        resultCount: 10,
        docIds: [],
        executedAt: '2026-01-01T00:00:02Z',
      },
    });
    expect(parseThreadEvent(wrongType)).toBeNull();
  });
});

describe('fetchUnifiedThread (S4a forward-tolerant array parsing)', () => {
  it('a thread with one unrecognized-kind event + two known events parses to 3 events; the thread is NOT empty', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        conversationId: 'c1',
        events: [
          {
            id: 'u1',
            occurredAt: '2026-01-01T00:00:01Z',
            kind: 'USER_MESSAGE',
            originator: 'user',
            content: 'find invoices',
            attributes: {},
          },
          {
            id: 's1',
            occurredAt: '2026-01-01T00:00:02Z',
            kind: 'WORKSPACE_SYNCED',
            originator: 'agent',
            content: 'syncing…',
            attributes: { query: 'invoices' },
          },
          {
            id: 'a1',
            occurredAt: '2026-01-01T00:00:03Z',
            kind: 'ASSISTANT_MESSAGE',
            originator: 'agent',
            content: 'done',
            attributes: {},
          },
        ],
        lifecycles: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res.events).toHaveLength(3);
    expect(res.events.map((e) => e.kind)).toEqual(['USER_MESSAGE', 'UNKNOWN', 'ASSISTANT_MESSAGE']);
    const unknown = res.events.find((e) => e.id === 's1')!;
    expect(unknown.rawKind).toBe('WORKSPACE_SYNCED');
  });

  it('drops a structurally invalid event but keeps its siblings, and warns once naming the count', async () => {
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        conversationId: 'c1',
        events: [
          {
            id: 'u1',
            occurredAt: '2026-01-01T00:00:01Z',
            kind: 'USER_MESSAGE',
            originator: 'user',
            content: 'hi',
            attributes: {},
          },
          // missing id — structurally invalid, must be dropped without failing the array.
          { occurredAt: '2026-01-01T00:00:02Z', kind: 'USER_MESSAGE', originator: 'user', content: 'x', attributes: {} },
          {
            id: 'a1',
            occurredAt: '2026-01-01T00:00:03Z',
            kind: 'ASSISTANT_MESSAGE',
            originator: 'agent',
            content: 'ok',
            attributes: {},
          },
        ],
        lifecycles: [],
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res.events.map((e) => e.id)).toEqual(['u1', 'a1']);
    expect(warnSpy).toHaveBeenCalledTimes(1);
    expect(warnSpy.mock.calls[0]![0]).toContain('dropped 1');
  });

  it('returns EMPTY when the top-level envelope itself is malformed (events not an array)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ conversationId: 'c1', events: 'not-an-array' }),
    );
    vi.stubGlobal('fetch', fetchMock);

    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res).toEqual({ events: [], lifecycles: [] });
  });

  it('returns EMPTY on a non-ok response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: async () => ({}) } as Response));
    const res = await fetchUnifiedThread('http://127.0.0.1:33221', 'c1');
    expect(res).toEqual({ events: [], lifecycles: [] });
  });
});

// Every recognized kind must retain strict required fields after the vocabulary moves.
describe('thread vocabulary preservation', () => {
  const kinds = ['USER_MESSAGE', 'ASSISTANT_MESSAGE', 'TOOL_ACTIVITY', 'PROGRESS', 'ERROR', 'HANDOFF', 'SEARCH'];
  const searchAttributes = {
    query: 'invoices', mode: 'hybrid', matchCount: 3, resultCount: 1,
    docIds: ['invoice.pdf'], executedAt: '2026-01-01T00:00:02Z',
  };
  it.each(kinds)('%s parses valid events and drops malformed known events', (kind) => {
    const event = { id: 'event', occurredAt: '2026-01-01T00:00:02Z', kind,
      originator: 'agent', content: 'hello', attributes: kind === 'SEARCH' ? searchAttributes : {} };
    expect(parseThreadEvent(event)).toMatchObject(event);
    for (const field of ['id', 'occurredAt', 'originator', 'content', 'attributes']) {
      const invalid: Record<string, unknown> = { ...event };
      delete invalid[field];
      expect(parseThreadEvent(invalid), `missing ${field}`).toBeNull();
      invalid[field] = 42;
      expect(parseThreadEvent(invalid), `wrong type for ${field}`).toBeNull();
    }
  });
  it.each(Object.keys(searchAttributes))('SEARCH requires typed %s', (field) => {
    const attributes: Record<string, unknown> = { ...searchAttributes };
    const event = { id: 's', occurredAt: '2026-01-01', kind: 'SEARCH', originator: 'user', content: '' };
    delete attributes[field];
    expect(parseThreadEvent({ ...event, attributes })).toBeNull();
    attributes[field] = field === 'matchCount' || field === 'resultCount' ? '3' : 42;
    expect(parseThreadEvent({ ...event, attributes })).toBeNull();
  });
});

describe('mixed thread recovery signaling', () => {
  it('keeps valid and future events, drops malformed known siblings and marks the response incomplete', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const valid = { id: 'u', occurredAt: '2026-01-01', kind: 'USER_MESSAGE', originator: 'user', content: 'hello', attributes: {} };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      conversationId: 'c1', events: [valid,
        { ...valid, id: 'bad', kind: 'PROGRESS', content: 42 },
        { id: 'future', occurredAt: '2026-01-02', kind: 'FUTURE_EVENT' },
      ], lifecycles: [],
    })));
    const result = await fetchUnifiedThread('http://thread.test', 'c1');
    expect(result.events.map((event) => [event.id, event.kind, event.rawKind])).toEqual([
      ['u', 'USER_MESSAGE', undefined], ['future', 'UNKNOWN', 'FUTURE_EVENT'],
    ]);
    expect(result.incomplete).toBe(true);
  });
  it.each([undefined, null, 42, {}, []])('drops a non-string kind %j', (kind) => {
    expect(parseThreadEvent({ id: 'x', occurredAt: '2026-01-01', kind })).toBeNull();
  });
});
