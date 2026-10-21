// @vitest-environment happy-dom

/**
 * Search v3's sidebar sessions (tempdoc 822 Phase A2; conversational since F1) — the WINDOW-level
 * consequences of `sv3-sessions.ts`, whose value semantics are tested without a DOM in
 * `sv3-sessions.test.ts`.
 *
 * A session is a CONVERSATION now, so these cases are driven by asks rather than by searches. The
 * stores are the real shared ones; the only stub is their single exit, the global fetch, routed by
 * URL so an ask and a (palette-only) search can both be in play. Two mechanisms are asserted rather
 * than appearances:
 *
 *  - **A row click claims, and issues nothing.** A click that dispatched anything shows up as a
 *    fetch — which is exactly the A2 behaviour F1 had to retire.
 *  - **The in-motion dot belongs to the session that is running.** The stream is held open
 *    deliberately so the in-flight frame can be read; a case that only looked at the settled frame
 *    would pass with the indicator wired to nothing.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3SessionRow } from './Sv3SessionRow.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { SV3_COMMAND_SEARCH } from './fixtures.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;

const hit = (path: string): Record<string, unknown> => ({ id: `doc:${path}`, fields: { path } });

/** The observed state in which the ask tier is genuinely available. */
function aiOnline(): void {
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

interface Router {
  /** Emit one SSE frame into the OPEN dispatch stream. */
  emit(event: string, data: unknown): void;
  /** Close the open dispatch stream (after a terminal frame). */
  end(): void;
  /** Answer the next search with this body, held until the returned release is called. */
  holdSearch(body: Record<string, unknown>): () => void;
}

/** One fetch stub for both exits: the chat dispatch streams, the search store answers JSON. */
function stubFetch(): Router {
  const encoder = new TextEncoder();
  const queued: Array<{ done: boolean; value?: Uint8Array }> = [];
  let wake: (() => void) | null = null;
  let searchBody: Record<string, unknown> = { results: [] };
  let held: Promise<void> | null = null;
  const push = (frame: { done: boolean; value?: Uint8Array }): void => {
    queued.push(frame);
    wake?.();
    wake = null;
  };
  fetchMock.mockImplementation(async (url: unknown, init: { signal?: AbortSignal }) => {
    if (String(url).includes('/api/chat/dispatch')) {
      const signal = init?.signal ?? null;
      return {
        ok: true,
        status: 200,
        body: {
          getReader: () => ({
            read: async () => {
              while (queued.length === 0) {
                if (signal?.aborted === true) throw new Error('The operation was aborted.');
                await new Promise<void>((resolve) => {
                  wake = resolve;
                  signal?.addEventListener('abort', () => resolve(), { once: true });
                });
              }
              return queued.shift();
            },
            releaseLock: () => {},
          }),
        },
      };
    }
    if (held) await held;
    return { ok: true, status: 200, json: () => Promise.resolve(searchBody) };
  });
  return {
    emit: (event, data) =>
      push({
        done: false,
        value: encoder.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
      }),
    end: () => push({ done: true }),
    holdSearch: (body) => {
      searchBody = body;
      let release = (): void => {};
      held = new Promise<void>((resolve) => {
        release = () => {
          held = null;
          resolve();
        };
      });
      return release;
    },
  };
}

let router: Router;

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  router = stubFetch();
  __resetAiStateForTest();
  aiOnline();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.appendChild(el);
  // WP1.1 — a fresh window starts in Search mode; these cases are about Ask, so they choose it.
  (el as unknown as { tier: string }).tier = 'ask';
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

async function fieldOf(el: Mounted): Promise<HTMLTextAreaElement> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
    '[data-testid="sv3-composer-input"]',
  );
  if (!field) throw new Error('no field in the composer');
  return field;
}

/** Type a draft and press send — the affordances a user has, not an internal call. */
async function send(el: Mounted, draft: string): Promise<void> {
  const composer = await region(el, 'jf-sv3-composer');
  const field = await fieldOf(el);
  field.value = draft;
  field.dispatchEvent(new Event('input'));
  await composer.updateComplete;
  composer.shadowRoot
    ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
    ?.click();
}

/** Ask and let the answer finish, so the session comes to rest. */
async function ask(el: Mounted, draft: string): Promise<void> {
  await send(el, draft);
  await settle(el);
  router.emit('chunk', { text: 'An answer.' });
  router.emit('done', {});
  router.end();
  await settle(el);
}

/** Settle the round trip and both renders (the window's, then the region's). */
async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 6; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function rowsOf(el: Mounted): Promise<Sv3SessionRow[]> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [
    ...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? []),
  ] as Sv3SessionRow[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows;
}

const groupLabelsOf = async (el: Mounted): Promise<string[]> => {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  return [
    ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
  ].map((n) => n.textContent?.trim() ?? '');
};

/** Click a row the way a pointer does: on its button, which bubbles out through the shadow root. */
const clickRow = async (row: Sv3SessionRow): Promise<void> => {
  row.shadowRoot?.querySelector<HTMLButtonElement>('button')?.click();
};

async function newSession(el: Mounted): Promise<void> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  sidebar.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-sidebar-new"]')?.click();
  await el.updateComplete;
}

const questionsOf = async (el: Mounted): Promise<string[]> => {
  const main = await region(el, 'jf-sv3-main');
  return [
    ...(main.shadowRoot?.querySelectorAll('[data-testid="sv3-turn-question"]') ?? []),
  ].map((n) => n.textContent?.trim() ?? '');
};

describe('a submitted question becomes a session in the sidebar', () => {
  it('creates exactly one session, titled with the question', async () => {
    const el = await mount();
    await ask(el, 'northfield lease');

    const rows = await rowsOf(el);
    expect(rows).toHaveLength(1);
    expect(rows[0]?.label).toBe('northfield lease');
    // The answer settled, so the conversation rests on the Recent shelf (Phase F3 state shelves).
    expect(await groupLabelsOf(el)).toEqual(['Recent']);
    // The empty state is gone the moment there is a session to show.
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-empty"]')).toBeNull();
  });

  it('APPENDS a turn to the same session rather than adding a second row', async () => {
    const el = await mount();
    await ask(el, 'vendor risk');
    await ask(el, 'and the register?');

    const rows = await rowsOf(el);
    expect(rows).toHaveLength(1);
    // The row keeps the OPENING question: a title that followed the latest turn would change the
    // row's identity under the reader.
    expect(rows[0]?.label).toBe('vendor risk');
    expect(await questionsOf(el)).toEqual(['vendor risk', 'and the register?']);
  });

  it('renders no group label at all before the first question', async () => {
    const el = await mount();
    expect(await groupLabelsOf(el)).toEqual([]);
    expect(await rowsOf(el)).toHaveLength(0);
  });

  it('keeps the group label out of the tab order once one exists', async () => {
    const el = await mount();
    await ask(el, 'freight');
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const labels = [
      ...(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group-label"]') ?? []),
    ];
    expect(labels).toHaveLength(1);
    for (const label of labels) {
      expect(label.tagName).toBe('DIV');
      expect(label.hasAttribute('tabindex')).toBe(false);
      expect(label.querySelector('button, a, input')).toBeNull();
    }
  });
});

describe('New session parks the conversation and returns the window to the hero', () => {
  it('empties the draft, un-docks, and keeps the session that was there', async () => {
    const el = await mount();
    await ask(el, 'first question');
    expect(el.getAttribute('composer-state')).toBe('docked');

    await newSession(el);
    expect(el.getAttribute('composer-state')).toBe('hero');
    // A leftover draft would be the previous session's text sitting in a fresh one.
    expect((await fieldOf(el)).value).toBe('');
    // The previous session is parked, not dropped...
    const rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['first question']);
    // ...and nothing is claimed, so no row is current.
    expect(rows.filter((r) => r.active)).toHaveLength(0);
  });

  it('opens the NEXT question as a second session, newest at the top', async () => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);
    await ask(el, 'second question');

    const rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['second question', 'first question']);
    expect(rows.filter((r) => r.active).map((r) => r.label)).toEqual(['second question']);
  });

  it('leaves the region with no transcript at all, not the parked one', async () => {
    const el = await mount();
    await ask(el, 'first question');
    expect(await questionsOf(el)).toEqual(['first question']);

    await newSession(el);
    // The parked session's turns belong to the parked session; the fresh one has asked nothing.
    expect(await questionsOf(el)).toEqual([]);
  });

  it('does not call an answer left streaming "stopped" when the reader starts a new session', async () => {
    const el = await mount();
    await send(el, 'a long one');
    await settle(el);
    router.emit('chunk', { text: 'Half' });
    await settle(el);

    await newSession(el);
    await settle(el);
    // Leaving is not a Stop: the Engine finishes and saves the answer, so back on the parked
    // session nothing reads as stopped, nor as forever-running; the record shows what was saved.
    await clickRow((await rowsOf(el))[0] as Sv3SessionRow);
    await settle(el);
    const main = await region(el, 'jf-sv3-main');
    const statuses = [...(main.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-turn"]') ?? [])].map(
      (turn) => turn.dataset.status,
    );
    expect(statuses).not.toContain('halted');
    expect(statuses).not.toContain('streaming');
  });
});

describe('clicking a session claims it and shows its transcript', () => {
  const twoSessions = async (): Promise<SearchV3View & Mounted> => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);
    await ask(el, 'second question');
    return el;
  };

  it('issues NOTHING — a claim is not a re-ask', async () => {
    const el = await twoSessions();
    const dispatches = (): unknown[][] =>
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/chat/dispatch'));
    const before = dispatches().length;
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    // No ISSUANCE — the A2 behaviour F1 retired. It does READ (Phase F6: the claimed conversation's
    // canonical record), which is the opposite of a re-ask and is asserted as such below.
    expect(dispatches()).toHaveLength(before);
    expect(
      fetchMock.mock.calls.filter((call) => String(call[0]).includes('/api/thread/')),
    ).not.toHaveLength(0);
    // What it DOES do is show that conversation.
    expect(await questionsOf(el)).toEqual(['first question']);
  });

  it('moves the claim to the clicked row, and moves aria-current with it', async () => {
    const el = await twoSessions();
    let rows = await rowsOf(el);
    const ariaOf = (row: Sv3SessionRow): string | null =>
      row.shadowRoot?.querySelector('button')?.getAttribute('aria-current') ?? null;
    // The newest session is the top row and holds the claim, having just been asked.
    expect(rows.map((r) => r.label)).toEqual(['second question', 'first question']);
    expect(rows.map(ariaOf)).toEqual(['true', null]);

    // Click the OTHER row, so this is genuinely a move rather than a re-assertion.
    await clickRow(rows[1] as Sv3SessionRow);
    await settle(el);
    rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['second question', 'first question']);
    expect(rows.map(ariaOf)).toEqual([null, 'true']);
    expect(rows.filter((r) => r.active)).toHaveLength(1);
  });

  it('docks the window again when a row is clicked from the hero', async () => {
    const el = await twoSessions();
    await newSession(el);
    expect(el.getAttribute('composer-state')).toBe('hero');

    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    expect(el.getAttribute('composer-state')).toBe('docked');
    expect((await rowsOf(el)).filter((r) => r.active).map((r) => r.label)).toEqual([
      'first question',
    ]);
  });

  it('claims in place: the list neither grows nor reorders', async () => {
    const el = await twoSessions();
    const before = (await rowsOf(el)).map((r) => r.label);
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    expect((await rowsOf(el)).map((r) => r.label)).toEqual(before);
  });
});

describe('the in-motion dot belongs to the running session alone', () => {
  const pingingOf = (rows: Sv3SessionRow[]): Sv3SessionRow[] =>
    rows.filter((r) => r.shadowRoot?.querySelector('.sv3-anim-status-ping') !== null);

  it('shows on the session whose answer is streaming, and on no other row', async () => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);

    await send(el, 'second question');
    await settle(el);
    let rows = await rowsOf(el);
    expect(rows).toHaveLength(2);
    // Exactly one dot, on the session that asked — the parked one is resting and says its age.
    expect(pingingOf(rows).map((r) => r.label)).toEqual(['second question']);
    expect(rows[0]?.status).toBe('in-motion');
    expect(rows[1]?.status).toBe('resting');
    expect(rows[1]?.meta).toBe('now');

    router.emit('done', {});
    router.end();
    await settle(el);
    rows = await rowsOf(el);
    // ...and the colour is spent only while the answer runs: a settled session shows a timestamp.
    expect(pingingOf(rows)).toHaveLength(0);
    expect(rows.map((r) => r.status)).toEqual(['resting', 'resting']);
    expect(rows[0]?.meta).toBe('now');
  });

  it('stays on the streaming session even after the reader claims another one', async () => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);
    await send(el, 'second question');
    await settle(el);

    // Claim the OLDER session mid-answer: the dot is a property of the running session, not of the
    // claim, so it must not follow the pointer.
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);
    const rows = await rowsOf(el);
    expect(rows.filter((r) => r.active).map((r) => r.label)).toEqual(['first question']);
    expect(pingingOf(rows).map((r) => r.label)).toEqual(['second question']);

    router.emit('done', {});
    router.end();
    await settle(el);
    expect(pingingOf(await rowsOf(el))).toHaveLength(0);
  });

  it('lights no row for a search — a search is not a turn, and leaves the conversation one click away', async () => {
    // WP1.1 (S1.8): a search shows its results in the window's own place, outside every
    // conversation, so the store's busy flag — which cannot say who asked — belongs to no session.
    // The conversation that was open stays in the sidebar, resting, ready to reopen.
    const el = await mount();
    await ask(el, 'first question');

    let release = router.holdSearch({ results: [hit('a/1.md')], totalHits: 1, matchCount: 1 });
    await searchByPalette(el, 'freight');
    release();
    await settle(el);

    release = router.holdSearch({ results: [hit('a/2.md')], totalHits: 1, matchCount: 1 });
    await searchByPalette(el, 'freight again');
    await el.updateComplete;
    const rows = await rowsOf(el);
    expect(rows.map((r) => r.label)).toEqual(['first question']);
    expect(rows.filter((r) => r.active)).toHaveLength(0);
    expect(pingingOf(rows)).toHaveLength(0);
    release();
    await settle(el);
    expect(pingingOf(await rowsOf(el))).toHaveLength(0);
  });

  async function searchByPalette(el: Mounted, draft: string): Promise<void> {
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    field.value = draft;
    field.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    const palette = el.shadowRoot?.querySelector('jf-sv3-palette') as
      | (HTMLElement & {
          show(i: HTMLElement | null): Promise<void>;
          updateComplete: Promise<unknown>;
        })
      | null;
    if (!palette) throw new Error('no palette in the window');
    await palette.show(null);
    await palette.updateComplete;
    palette.shadowRoot
      ?.querySelector<HTMLElement>(`#sv3-palette-item-${SV3_COMMAND_SEARCH}`)
      ?.click();
    await el.updateComplete;
  }
});

describe('shelves and the pin action (tempdoc 822 Phase F3)', () => {
  const shelfRows = async (el: Mounted): Promise<Record<string, string[]>> => {
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const shelves: Record<string, string[]> = {};
    for (const group of sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-group"]') ??
      []) {
      const label =
        group.querySelector('[data-testid="sv3-sidebar-group-label"]')?.textContent?.trim() ?? '';
      shelves[label] = [...group.querySelectorAll('jf-sv3-session-row')].map(
        (row) => (row as Sv3SessionRow).label,
      );
    }
    return shelves;
  };

  const pinOf = (row: Sv3SessionRow): HTMLButtonElement => {
    const button = row.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-session-row-pin"]',
    );
    if (!button) throw new Error('no pin action in the row');
    return button;
  };

  it('opens Active while an answer streams and Recent once it settles', async () => {
    const el = await mount();
    await ask(el, 'first question');
    expect(await shelfRows(el)).toEqual({ Recent: ['first question'] });

    await newSession(el);
    await send(el, 'second question');
    await settle(el);
    // A streaming conversation is on ACTIVE; the settled one keeps its place on Recent.
    expect(await shelfRows(el)).toEqual({
      Active: ['second question'],
      Recent: ['first question'],
    });

    router.emit('done', {});
    router.end();
    await settle(el);
    // The shelf empties rather than persisting as a heading over nothing.
    expect(await shelfRows(el)).toEqual({ Recent: ['second question', 'first question'] });
  });

  it('pins a conversation onto the Pinned shelf WITHOUT claiming it', async () => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);
    await ask(el, 'second question');

    const rows = await rowsOf(el);
    const older = rows[1] as Sv3SessionRow;
    expect(older.active).toBe(false);
    const fetches = fetchMock.mock.calls.length;

    pinOf(older).click();
    await settle(el);
    expect(await shelfRows(el)).toEqual({
      Pinned: ['first question'],
      Recent: ['second question'],
    });
    // Pinning is not navigation and not an issuance: the claim did not move and nothing was sent.
    const after = await rowsOf(el);
    expect(after.filter((r) => r.active).map((r) => r.label)).toEqual(['second question']);
    expect(fetchMock.mock.calls.length).toBe(fetches);

    // The control announces its own state, and a second press puts the row back where it was.
    const pinned = after.find((r) => r.label === 'first question') as Sv3SessionRow;
    expect(pinOf(pinned).getAttribute('aria-pressed')).toBe('true');
    pinOf(pinned).click();
    await settle(el);
    expect(await shelfRows(el)).toEqual({ Recent: ['second question', 'first question'] });
    expect(pinOf((await rowsOf(el))[1] as Sv3SessionRow).getAttribute('aria-pressed')).toBe('false');
  });

  it('keeps a pinned conversation on Active while it is running (blockers override)', async () => {
    const el = await mount();
    await ask(el, 'first question');
    pinOf((await rowsOf(el))[0] as Sv3SessionRow).click();
    await settle(el);
    expect(await shelfRows(el)).toEqual({ Pinned: ['first question'] });

    // The SAME conversation, now streaming a follow-up: a pin is the reader's intent about a
    // RESTING conversation and does not get to hide a working one.
    await send(el, 'and then?');
    await settle(el);
    expect(await shelfRows(el)).toEqual({ Active: ['first question'] });
    router.emit('done', {});
    router.end();
    await settle(el);
    expect(await shelfRows(el)).toEqual({ Pinned: ['first question'] });
  });

  it('marks a conversation unread when its answer lands while the reader is elsewhere', async () => {
    const el = await mount();
    await ask(el, 'the early one');
    await newSession(el);
    await send(el, 'the slow one');
    await settle(el);
    // Claim the OTHER conversation, leaving this one streaming behind the reader's back. (A row
    // click claims and abandons nothing — New session would have STOPPED the stream instead.)
    await clickRow((await rowsOf(el))[1] as Sv3SessionRow);
    await settle(el);

    let rows = await rowsOf(el);
    expect(rows.find((r) => r.label === 'the slow one')?.unread).toBe(false);

    router.emit('done', {});
    router.end();
    await settle(el);
    rows = await rowsOf(el);
    const slow = rows.find((r) => r.label === 'the slow one') as Sv3SessionRow;
    expect(slow.unread).toBe(true);
    expect(slow.hasAttribute('unread')).toBe(true);
    // The conversation the reader was IN never wakes: they watched that one finish.
    expect(rows.find((r) => r.label === 'the early one')?.unread).toBe(false);

    // Visiting is what clears it.
    await clickRow(slow);
    await settle(el);
    expect((await rowsOf(el)).find((r) => r.label === 'the slow one')?.unread).toBe(false);
  });
});

/**
 * The row's action set, end to end (tempdoc 831). Every case is a MUTATION probe: the affordance is
 * pressed the way a pointer presses it, and what is asserted afterwards is the state it claimed to
 * change — the stored title, shelf membership, the list itself, the authority it was written to.
 * A control that raised its event into nothing would pass a "the event fired" case and fail these.
 */
describe('the row actions each change the state they name', () => {
  const actionOf = (row: Sv3SessionRow, name: string): HTMLButtonElement => {
    const button = row.shadowRoot?.querySelector<HTMLButtonElement>(
      `[data-testid="sv3-session-row-${name}"]`,
    );
    if (!button) throw new Error(`no ${name} action in the row`);
    return button;
  };

  const deleteCalls = (): string[] =>
    fetchMock.mock.calls
      .filter(([, init]) => (init as { method?: string } | undefined)?.method === 'DELETE')
      .map(([url]) => String(url));

  it('renames the conversation from the row action, and the title is the stored one', async () => {
    const el = await mount();
    await ask(el, 'first question');

    actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'rename').click();
    await settle(el);
    const editing = (await rowsOf(el))[0] as Sv3SessionRow;
    const input = editing.shadowRoot?.querySelector<HTMLInputElement>(
      '[data-testid="sv3-session-row-rename-input"]',
    );
    if (!input) throw new Error('the rename action opened no editor');
    input.value = 'northfield lease review';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await settle(el);

    // The MUTATION: the row's label comes from the session list, so a changed label is a changed
    // record — not an input that kept its own text.
    expect((await rowsOf(el)).map((r) => r.label)).toEqual(['northfield lease review']);
  });

  it('moves the conversation between shelves from the row action', async () => {
    const el = await mount();
    await ask(el, 'first question');
    actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'pin').click();
    await settle(el);
    expect(await groupLabelsOf(el)).toEqual(['Pinned']);
    actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'pin').click();
    await settle(el);
    expect(await groupLabelsOf(el)).toEqual(['Recent']);
  });

  it('discards the conversation and deletes it at the authority that owns its existence', async () => {
    const el = await mount();
    await ask(el, 'first question');
    await newSession(el);
    await ask(el, 'second question');
    expect(deleteCalls()).toEqual([]);

    const older = (await rowsOf(el))[1] as Sv3SessionRow;
    actionOf(older, 'remove').click();
    await settle(el);

    // Gone from the list...
    expect((await rowsOf(el)).map((r) => r.label)).toEqual(['second question']);
    // ...and gone at the conversation store, which is where a conversation EXISTS: a window that
    // only dropped its own row would resurrect it on the next list load.
    expect(deleteCalls()).toHaveLength(1);
    expect(deleteCalls()[0]).toContain('/api/chat/conversations/');
    // The claim did not move to the survivor as a side effect of someone else being discarded.
    expect((await rowsOf(el)).filter((r) => r.active).map((r) => r.label)).toEqual([
      'second question',
    ]);
  });

  it('returns the window to the hero when the conversation ON SCREEN is discarded', async () => {
    const el = await mount();
    await ask(el, 'the only one');
    actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'remove').click();
    await settle(el);

    expect(await rowsOf(el)).toHaveLength(0);
    // The transcript went with it — a window still rendering the turns of a conversation it just
    // discarded would be showing the reader something that no longer exists.
    expect(await questionsOf(el)).toEqual([]);
    expect(deleteCalls()).toHaveLength(1);
  });

  /**
   * Where the KEYBOARD ends up after a discard (tempdoc 831 D2, from the independent measured
   * audit). Lit reuses the row nodes, so the button the reader pressed stays under their finger and
   * silently becomes the NEXT conversation's Delete: the reproduction was 4 rows → Enter → 3 rows →
   * Enter → 2 rows, a conversation deleted that was never chosen. Every case here presses ENTER on
   * the control, which is what a keyboard reader actually does, rather than calling `.click()`.
   */
  describe('the keyboard lands somewhere safe after a discard', () => {
    /** Enter on a focused native button IS a click; happy-dom does not synthesize it, so both. */
    const pressEnter = (button: HTMLButtonElement): void => {
      button.focus();
      button.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
      button.click();
    };

    /**
     * The focused node, walked down through the shadow roots — and the element whose root holds it.
     * Only the focus CHAIN is walked: happy-dom throws on `shadowRoot.activeElement` for a root
     * that has none, so a sweep over every row would report an exception rather than a place.
     */
    const deepFocus = (): { node: Element | null; host: Element | null } => {
      let node: Element | null = document.activeElement;
      let host: Element | null = null;
      while (node?.shadowRoot?.activeElement != null) {
        host = node;
        node = node.shadowRoot.activeElement;
      }
      return { node, host };
    };

    const focusedTestId = (_el: Mounted): string | null =>
      deepFocus().node?.getAttribute('data-testid') ?? null;

    const focusedRowOf = (): Sv3SessionRow | null => {
      const { host } = deepFocus();
      return host?.tagName.toLowerCase() === 'jf-sv3-session-row' ? (host as Sv3SessionRow) : null;
    };

    const announcement = async (el: Mounted): Promise<string> => {
      const bar = await region(el, 'jf-sv3-sidebar');
      return (
        bar.shadowRoot
          ?.querySelector('[data-testid="sv3-sidebar-announcer"]')
          ?.textContent?.trim() ?? ''
      );
    };

    /** Three settled conversations; rows render newest-first, so this is ['third','second','first']. */
    async function threeConversations(el: Mounted): Promise<void> {
      for (const question of ['first', 'second', 'third']) {
        await newSession(el);
        await ask(el, question);
      }
    }

    it('moves to the SUCCESSOR row button — never its Delete — when a middle row goes', async () => {
      const el = await mount();
      await threeConversations(el);
      expect((await rowsOf(el)).map((r) => r.label)).toEqual(['third', 'second', 'first']);

      pressEnter(actionOf((await rowsOf(el))[1] as Sv3SessionRow, 'remove'));
      await settle(el);

      expect((await rowsOf(el)).map((r) => r.label)).toEqual(['third', 'first']);
      // The row BUTTON, so a repeat of the same key claims a conversation instead of deleting one.
      expect(focusedTestId(el)).toBe('sv3-session-row-button');
      const focusedRow = focusedRowOf() as Sv3SessionRow;
      expect(focusedRow.label).toBe('first');
    });

    it('cannot delete a second conversation from a second Enter', async () => {
      // The reproduction, run forwards: 3 → Enter → 2 → Enter → still 2.
      const el = await mount();
      await threeConversations(el);
      pressEnter(actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'remove'));
      await settle(el);
      expect((await rowsOf(el)).map((r) => r.label)).toEqual(['second', 'first']);

      const landed = document.activeElement as Element | null;
      let deep: Element | null = landed;
      while (deep?.shadowRoot?.activeElement != null) deep = deep.shadowRoot.activeElement;
      (deep as HTMLElement | null)?.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }),
      );
      (deep as HTMLElement | null)?.click();
      await settle(el);

      // Nothing else was discarded, and exactly one deletion reached the authority.
      expect((await rowsOf(el)).map((r) => r.label)).toEqual(['second', 'first']);
      expect(deleteCalls()).toHaveLength(1);
    });

    it('falls back to the PREDECESSOR when the last row in the list goes', async () => {
      const el = await mount();
      await threeConversations(el);
      pressEnter(actionOf((await rowsOf(el))[2] as Sv3SessionRow, 'remove'));
      await settle(el);

      expect((await rowsOf(el)).map((r) => r.label)).toEqual(['third', 'second']);
      const focusedRow = focusedRowOf() as Sv3SessionRow;
      expect(focusedRow.label).toBe('second');
    });

    it('falls back to the new-search control when the list empties, rather than dropping focus', async () => {
      const el = await mount();
      await ask(el, 'the only one');
      pressEnter(actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'remove'));
      await settle(el);

      expect(await rowsOf(el)).toHaveLength(0);
      expect(focusedTestId(el)).toBe('sv3-sidebar-new');
    });

    it('announces the deletion politely, and says WHICH conversation went', async () => {
      const el = await mount();
      await threeConversations(el);
      expect(await announcement(el)).toBe('');

      pressEnter(actionOf((await rowsOf(el))[1] as Sv3SessionRow, 'remove'));
      await settle(el);
      expect(await announcement(el)).toBe('second deleted');

      // The region is a LEAF holding text: a live region wrapped around controls re-announces its
      // whole subtree on every render, which is noise rather than news.
      const bar = await region(el, 'jf-sv3-sidebar');
      const announcer = bar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-announcer"]');
      expect(announcer?.getAttribute('aria-live')).toBe('polite');
      expect(announcer?.querySelectorAll('button, [role="button"], input')).toHaveLength(0);
    });

    it('MUTATES the region for every discard, even two conversations with the same title', async () => {
      // A live region speaks on mutation. Setting the same string twice is silence — so the region
      // is emptied on the request and filled when the removal lands, and both steps are observed
      // here rather than inferred from the final text.
      const el = await mount();
      for (const _ of [0, 1]) {
        await newSession(el);
        await ask(el, 'same title');
      }
      const bar = await region(el, 'jf-sv3-sidebar');
      const announcer = bar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-announcer"]');
      if (!announcer) throw new Error('no announcer');
      const seen: string[] = [];
      new MutationObserver(() => seen.push(announcer.textContent?.trim() ?? '')).observe(announcer, {
        childList: true,
        characterData: true,
        subtree: true,
      });

      pressEnter(actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'remove'));
      await settle(el);
      pressEnter(actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'remove'));
      await settle(el);

      expect((await rowsOf(el)).map((r) => r.label)).toEqual([]);
      // Said, cleared, said again. The region starts empty, so the first clear is a no-op; what
      // matters is the clear BETWEEN the two identical announcements, without which the second
      // discard would set a string the region already held and say nothing at all.
      expect(seen).toEqual(['same title deleted', '', 'same title deleted']);
    });
  });

  it('puts the keyboard back on the row after a rename is committed or cancelled', async () => {
    // Audit advisory: both keyboard routes out of the edit dropped focus to <body>, because the
    // input holding it is removed when the edit closes.
    const el = await mount();
    await ask(el, 'first question');
    const inputOf = async (): Promise<HTMLInputElement> => {
      const row = (await rowsOf(el))[0] as Sv3SessionRow;
      const field = row.shadowRoot?.querySelector<HTMLInputElement>(
        '[data-testid="sv3-session-row-rename-input"]',
      );
      if (!field) throw new Error('no rename editor');
      return field;
    };

    for (const key of ['Enter', 'Escape']) {
      actionOf((await rowsOf(el))[0] as Sv3SessionRow, 'rename').click();
      await settle(el);
      const field = await inputOf();
      field.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
      await settle(el);
      const row = (await rowsOf(el))[0] as Sv3SessionRow;
      expect(
        row.shadowRoot?.activeElement?.getAttribute('data-testid'),
        `focus after ${key}`,
      ).toBe('sv3-session-row-button');
    }
  });

  it('offers NO discard while the conversation is streaming, and offers one the moment it settles', async () => {
    const el = await mount();
    await send(el, 'the slow one');
    await settle(el);
    const running = (await rowsOf(el))[0] as Sv3SessionRow;
    // The window projected the run state onto the row, so the control is not there to press.
    expect(running.live).toBe(true);
    expect(
      running.shadowRoot?.querySelector('[data-testid="sv3-session-row-remove"]'),
    ).toBeNull();

    router.emit('done', {});
    router.end();
    await settle(el);
    const settled = (await rowsOf(el))[0] as Sv3SessionRow;
    expect(settled.live).toBe(false);
    actionOf(settled, 'remove').click();
    await settle(el);
    expect(await rowsOf(el)).toHaveLength(0);
    expect(deleteCalls()).toHaveLength(1);
  });
});
