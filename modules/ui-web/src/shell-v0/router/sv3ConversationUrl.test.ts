/**
 * @vitest-environment happy-dom
 *
 * Search v3's conversation identity in the URL — tempdoc 864 Layer 3(a) / PR C.
 *
 * THE INCIDENT (864 §0, reproduced live 2026-08-25 as PR-0 leg L6): focus was parked on a sidebar
 * row's select button, a bare `Space` activated it, the whole transcript was replaced by another
 * conversation — and the hash was VERBATIM UNCHANGED, so the browser had recorded nothing and Back
 * did nothing (L8). Recovery was a reload. §2.7a found why: `core.search-v3-surface` is declared
 * frontend-only, the schema registry is fed from the wire, so the surface had no stateSchema and
 * `URLProjector.activateProjection` returned early for ALL of it.
 *
 * The whole loop is exercised here rather than the projector alone, because every one of these
 * cases spans four modules that each looked correct in isolation: the store that holds the claim,
 * the adapter that projects it, the handler that distributes an address, and the URL source that
 * turns a popstate into one. `it('Back undoes a conversation swap')` is the incident's own
 * regression test (§4.3 test 9).
 *
 * The history stack is modelled rather than mocked away: a spy that only counts `pushState` calls
 * cannot tell "an entry was added" from "an entry was added at the right point in the stack", and
 * ordering is exactly what the back button reads.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetBootstrapForTest,
  registerCoreStores,
} from './bootstrap.js';
import { createNavigationHandler, type NavigationHandler } from './navigationHandler.js';
import { __resetStoreRegistryForTest } from './storeRegistry.js';
import {
  __resetSurfaceSchemasForTest,
  getSurfaceStateSchema,
  registerSurfaceStateSchema,
} from './surfaceSchemas.js';
import {
  __flushPendingWriteForTest,
  activateProjection,
  deactivateProjection,
} from './URLProjector.js';
import { createURLSource } from './sources/URLSource.js';
import type { Intent } from './types.js';
import {
  __resetConversationListForTest,
  deleteConversation,
  getConversationListState,
  setActiveConversation,
} from '../state/conversationListStore.js';
import {
  resetUnifiedChatState,
  restoreUnifiedChat,
} from '../state/unifiedChatState.js';

import { __resetJournalForTest, recordNavigation, getJournalState, canGoBack, navigateBack, navigateForward } from '../state/NavigationJournal.js';

const SV3 = 'core.search-v3-surface';
/** The shipped chat window — a live surface with a real wire schema, used as the untouched control. */
const CHAT = 'core.unified-chat-surface';

/** The address a conversation projects to. Asserted literally — the format is the contract. */
const addressFor = (id: string): string =>
  `#justsearch://surface/core.search-v3-surface?conversationId=${id}`;

/** The hero: nothing claimed is the BARE surface address, not an empty argument. */
const HERO_ADDRESS = '#justsearch://surface/core.search-v3-surface';

/**
 * A real back/forward stack over the two history writes the projector makes.
 *
 * `pushState` truncates the forward tail and appends; `replaceState` overwrites in place; `back` /
 * `forward` move the cursor and fire `popstate` at the URLSource, which is what the browser does
 * and what the projector's push-vs-replace decision is ultimately judged by.
 */
function historyHarness(): {
  window: Window;
  entries: () => string[];
  current: () => string;
  back: () => void;
  forward: () => void;
  seed: (url: string) => void;
  restore: () => void;
} {
  let entries: string[] = [];
  let states: unknown[] = [];
  let index = -1;
  const popstateListeners = new Set<EventListenerOrEventListenerObject>();

  const fakeWindow = {
    get location() {
      return { hash: index >= 0 ? entries[index] : '' };
    },
    addEventListener(type: string, handler: EventListenerOrEventListenerObject): void {
      if (type === 'popstate') popstateListeners.add(handler);
    },
    removeEventListener(type: string, handler: EventListenerOrEventListenerObject): void {
      if (type === 'popstate') popstateListeners.delete(handler);
    },
  } as unknown as Window;

  const pushSpy = vi
    .spyOn(window.history, 'pushState')
    .mockImplementation((_state: unknown, _title: string, url?: string | URL | null) => {
      entries = entries.slice(0, index + 1);
      entries.push(String(url));
      states = states.slice(0, index + 1);
      states.push(_state);
      index = entries.length - 1;
    });
  const replaceSpy = vi
    .spyOn(window.history, 'replaceState')
    .mockImplementation((_state: unknown, _title: string, url?: string | URL | null) => {
      if (index < 0) {
        entries.push(String(url));
        states.push(_state);
        index = 0;
        return;
      }
      if (url !== undefined && url !== null) entries[index] = String(url);
      states[index] = _state;
    });
  const stateSpy = vi.spyOn(window.history, 'state', 'get').mockImplementation(() => states[index] ?? null);

  const fire = (): void => {
    for (const handler of popstateListeners) {
      if (typeof handler === 'function') handler(new Event('popstate'));
      else handler.handleEvent(new Event('popstate'));
    }
  };

  return {
    window: fakeWindow,
    entries: () => [...entries],
    current: () => entries[index] ?? '',
    back: () => {
      if (index > 0) index -= 1;
      fire();
    },
    forward: () => {
      if (index < entries.length - 1) index += 1;
      fire();
    },
    seed: (url: string) => {
      entries = [url];
      states = [null];
      index = 0;
    },
    restore: () => {
      pushSpy.mockRestore();
      replaceSpy.mockRestore();
      stateSpy.mockRestore();
    },
  };
}

describe('sv3 conversation identity in the URL (864 PR C)', () => {
  let history: ReturnType<typeof historyHarness>;
  let handler: NavigationHandler;
  let activeSurface: string | null;
  let stopSource: (() => void) | null;

  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    __resetConversationListForTest();
    __resetJournalForTest();
    resetUnifiedChatState();
    deactivateProjection();
    history = historyHarness();
    activeSurface = null;
    registerCoreStores();
    // The chat window's schema arrives from the wire in production; registering the same shape here
    // is what lets the "other surfaces are untouched" case run against the real projector and the
    // real adapter rather than a stand-in.
    registerSurfaceStateSchema(CHAT, {
      schema: '{"type":"object","properties":{"query":{"type":"string"}}}',
      bindings: [{ schemaPath: '/query', storeId: 'unified-chat', storeKey: 'query' }],
    });
    handler = createNavigationHandler({
      setActiveSurface: (id) => {
        activeSurface = id;
      },
      isKnownSurface: () => true,
    });
    stopSource = null;
  });

  afterEach(() => {
    stopSource?.();
    deactivateProjection();
    history.restore();
    vi.restoreAllMocks();
  });

  /** Start the URL ingress against the modelled stack, as `Shell.connectedCallback` does. */
  function startUrlSource(): void {
    const source = createURLSource({ windowImpl: history.window });
    // URLSource.start is synchronous by construction (it installs one listener); the IntentSource
    // contract allows a Promise for the Tauri source, which this one never returns.
    stopSource = source.start((intent: Intent, options) => {
      if (intent.address.kind !== 'navigate') return;
      void handler.handle(intent.address, { push: options?.pushHistory });
    }) as () => void;
  }

  /**
   * Arrive on sv3 reading `id` and leave both halves live: the projector, and the popstate ingress
   * a Back press arrives through. The source starts on an EMPTY hash so its boot read finds
   * nothing — this arrival is the programmatic one (a palette navigation, a rail click), so the
   * single entry it leaves is the handler's, not a boot read replaying it.
   */
  async function arriveOn(id: string): Promise<void> {
    startUrlSource();
    await handler.handle({ kind: 'navigate', target: SV3, state: { conversationId: id } });
  }

  it('the frontend-only surface HAS a state schema — the §2.11 blocker is gone', () => {
    // The wire declares no `search-v3` surface (zero hits in Java sources), which is why the
    // projector used to refuse the whole surface. The schema is registered locally instead.
    const schema = getSurfaceStateSchema(SV3);
    expect(schema).toBeDefined();
    expect(schema?.bindings).toEqual([
      { schemaPath: '/conversationId', storeId: 'sv3.conversation', storeKey: 'conversationId' },
    ]);
  });

  it('S16.4 app Back returns an original-navigation claim to its branch without looping', async () => {
    await arriveOn('old-branch');
    // Shell records the initial address through its router subscription.
    recordNavigation(SV3, addressFor('old-branch').slice(1), 'Search v3', 'URL_BAR');
    // Open original uses the same conversation claim as the pager and row navigation.
    setActiveConversation('old-parent');
    __flushPendingWriteForTest();
    expect(canGoBack()).toBe(true);
    expect(getJournalState().entries.map(e => e.url)).toEqual([
      addressFor('old-branch').slice(1), addressFor('old-parent').slice(1),
    ]);
    const dispatch = vi.fn(async (intent: Intent, options?: { pushHistory?: boolean }) => {
      if (intent.address.kind === 'navigate') await handler.handle(intent.address, { push: options?.pushHistory });
    });
    await navigateBack(dispatch);
    __flushPendingWriteForTest();
    expect(getConversationListState().activeId).toBe('old-branch');
    expect(canGoBack()).toBe(false);
    await navigateBack(dispatch);
    expect(dispatch).toHaveBeenCalledTimes(1);
    expect(getJournalState().entries).toHaveLength(2);
    await navigateForward(dispatch);
    __flushPendingWriteForTest();
    expect(getConversationListState().activeId).toBe('old-parent');
    // Native Back replays via URLSource, and Shell observes that URL navigation as usual.
    history.back();
    recordNavigation(SV3, history.current().slice(1), 'Search v3', 'URL_BAR');
    __flushPendingWriteForTest();
    expect(getConversationListState().activeId).toBe('old-branch');
    expect(canGoBack()).toBe(false);
    expect(getJournalState().entries).toHaveLength(2);
  });

  it('a conversation swap moves the hash and adds a history entry', async () => {
    await arriveOn('conv-a');
    expect(history.current()).toBe(addressFor('conv-a'));

    setActiveConversation('conv-b');
    __flushPendingWriteForTest();

    expect(history.current()).toBe(addressFor('conv-b'));
    expect(history.entries()).toEqual([addressFor('conv-a'), addressFor('conv-b')]);
  });

  it('Back undoes a conversation swap', async () => {
    // THE INCIDENT'S REGRESSION TEST (864 §4.3 test 9). A bare `Space` on a focused row button
    // swaps the conversation — by design, per §2.7c, the platform activating a focused button is
    // not a defect. What made it destructive was that it could not be undone.
    await arriveOn('conv-a');
    setActiveConversation('conv-b');
    __flushPendingWriteForTest();
    expect(getConversationListState().activeId).toBe('conv-b');

    history.back();

    expect(getConversationListState().activeId).toBe('conv-a');
    expect(history.current()).toBe(addressFor('conv-a'));
  });

  it('Back does not push an entry of its own — a second Back keeps going', async () => {
    await arriveOn('conv-a');
    setActiveConversation('conv-b');
    __flushPendingWriteForTest();
    setActiveConversation('conv-c');
    __flushPendingWriteForTest();

    history.back();
    // The restore the popstate performed re-enters the projector; if it were treated as a claim it
    // would stack a fourth entry and trap the reader one press from where they started.
    __flushPendingWriteForTest();
    expect(history.entries()).toHaveLength(3);

    history.back();
    expect(getConversationListState().activeId).toBe('conv-a');
  });

  it('forward and back walk several conversation switches', async () => {
    await arriveOn('conv-a');
    for (const id of ['conv-b', 'conv-c', 'conv-d']) {
      setActiveConversation(id);
      __flushPendingWriteForTest();
    }
    expect(history.entries()).toHaveLength(4);

    history.back();
    expect(getConversationListState().activeId).toBe('conv-c');
    history.back();
    expect(getConversationListState().activeId).toBe('conv-b');
    history.forward();
    expect(getConversationListState().activeId).toBe('conv-c');
    history.forward();
    expect(getConversationListState().activeId).toBe('conv-d');
  });

  it('a new session is a navigation, and Back returns to the conversation it left', async () => {
    // §3.3(b): `onSessionNew` also clears the reload pointer, which is what made an accidental one
    // survive even a reload. With the claim in the address, the previous entry still holds it.
    await arriveOn('conv-a');
    setActiveConversation(null);
    __flushPendingWriteForTest();
    expect(history.current()).toBe(HERO_ADDRESS);

    history.back();

    expect(getConversationListState().activeId).toBe('conv-a');
  });

  it('forward onto the hero clears the claim — an absent id is a value on a traversal', async () => {
    await arriveOn('conv-a');
    setActiveConversation(null);
    __flushPendingWriteForTest();
    history.back();
    expect(getConversationListState().activeId).toBe('conv-a');

    history.forward();

    expect(getConversationListState().activeId).toBeNull();
    expect(history.current()).toBe(HERO_ADDRESS);
  });

  it('a fresh load deep-links into the conversation the hash names', async () => {
    // Nothing has been claimed in this tab; the address is the only thing that knows.
    history.seed(addressFor('conv-deep'));
    expect(getConversationListState().activeId).toBeNull();

    startUrlSource();
    await Promise.resolve();

    expect(getConversationListState().activeId).toBe('conv-deep');
    expect(activeSurface).toBe(SV3);
  });

  it('a reload keeps the reader on the conversation they were reading', async () => {
    await arriveOn('conv-a');
    setActiveConversation('conv-b');
    __flushPendingWriteForTest();
    const urlAtReload = history.current();

    // A reload is a fresh module graph reading the same address back.
    stopSource?.();
    deactivateProjection();
    __resetConversationListForTest();
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    registerCoreStores();
    history.seed(urlAtReload);

    startUrlSource();
    await Promise.resolve();

    expect(getConversationListState().activeId).toBe('conv-b');
  });

  it('re-claiming the conversation already open adds no entry', async () => {
    // Every completed ask re-claims its own conversation and re-lists the roster. Without the
    // adapter's activeId dedup each of those would be a history entry.
    await arriveOn('conv-a');
    setActiveConversation('conv-a');
    __flushPendingWriteForTest();
    expect(history.entries()).toEqual([addressFor('conv-a')]);
  });

  it('a claim still inside the debounce survives an immediate surface change', async () => {
    // F4: the claim's write is settled before the surface change moves history, so the conversation
    // the reader was in is still on the stack under the new surface's entry. Deliberately NOT
    // flushed by the test — the whole point is that nothing else flushes it either.
    await arriveOn('conv-a');
    setActiveConversation('conv-b');

    await handler.handle({ kind: 'navigate', target: CHAT, state: {} });

    expect(history.entries()).toEqual([
      addressFor('conv-a'),
      addressFor('conv-b'),
      '#justsearch://surface/core.unified-chat-surface',
    ]);
  });

  it('deleting the open conversation corrects the address without an entry', async () => {
    // F3: the delete paths drop the claim under the `list` reason, and that is the behaviour the
    // vocabulary's doc now states — a deleted conversation is not somewhere Back can lead.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, status: 200, json: async () => ({}) })),
    );
    await arriveOn('conv-a');
    const entriesBefore = history.entries().length;

    await deleteConversation('conv-a');
    __flushPendingWriteForTest();

    expect(getConversationListState().activeId).toBeNull();
    expect(history.entries()).toHaveLength(entriesBefore);
    expect(history.current()).toBe(HERO_ADDRESS);
    vi.unstubAllGlobals();
  });

  it('other surfaces still project with replaceState only', async () => {
    // The regression guard for slice 489's contract: an in-surface refinement is not a navigation,
    // and nothing in this PR may make one push. An adapter that never declares a navigation is
    // byte-for-byte on its old path — this is what makes that claim a test rather than a promise.
    const pushCalls = (): number =>
      (window.history.pushState as unknown as { mock: { calls: unknown[] } }).mock.calls.length;
    const before = pushCalls();
    await handler.handle({ kind: 'navigate', target: CHAT, state: { query: 'rust' } });
    const afterNavigation = pushCalls();
    expect(afterNavigation).toBe(before + 1); // the surface change itself, and nothing else

    restoreUnifiedChat({ query: 'rust ownership' });
    __flushPendingWriteForTest();

    expect(pushCalls()).toBe(afterNavigation);
    expect(history.current()).toBe(
      '#justsearch://surface/core.unified-chat-surface?query=rust%20ownership',
    );
  });
});

/**
 * The duplicate-entry downgrade, against a REAL `window.location` — independent review of PR #556,
 * finding F2.
 *
 * The suite above models the history stack but leaves `location.hash` where it started, so
 * `URLProjector.isCurrentUrl` is constant-false there and every one of those cases would still pass
 * with the guard deleted. It is load-bearing (a push that duplicates the current entry makes Back a
 * no-op that costs a press), so it gets a case that actually moves the hash.
 */
describe('a navigational write that duplicates the current address degrades to replace (F2)', () => {
  let pushSpy: ReturnType<typeof vi.spyOn>;
  let replaceSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    __resetConversationListForTest();
    deactivateProjection();
    registerCoreStores();
    pushSpy = vi.spyOn(window.history, 'pushState').mockImplementation(() => {
      /* the real location is driven by the test, not by these */
    });
    replaceSpy = vi.spyOn(window.history, 'replaceState').mockImplementation(() => {
      /* swallow */
    });
  });

  afterEach(() => {
    deactivateProjection();
    window.location.hash = '';
    vi.restoreAllMocks();
  });

  it('pushes when the browser is somewhere else', () => {
    window.location.hash = addressFor('conv-a');
    expect(window.location.hash).toBe(addressFor('conv-a')); // precondition, not decoration
    setActiveConversation('conv-a');
    activateProjection(SV3);

    setActiveConversation('conv-b');
    __flushPendingWriteForTest();

    expect(pushSpy.mock.calls.at(-1)?.[2]).toBe(addressFor('conv-b'));
  });

  it('replaces when the browser is already showing that address', () => {
    // The browser is at conv-b already — a popstate landed here, or the projector wrote it — and
    // something claims conv-b again. The claim is real; a second entry for it is not.
    window.location.hash = addressFor('conv-b');
    setActiveConversation('conv-a');
    activateProjection(SV3);
    const pushesBefore = pushSpy.mock.calls.length;

    setActiveConversation('conv-b');
    __flushPendingWriteForTest();

    expect(pushSpy.mock.calls.length).toBe(pushesBefore);
    expect(replaceSpy.mock.calls.at(-1)?.[2]).toBe(addressFor('conv-b'));
  });
});

/**
 * D3 (closing-window findings, 2026-08-25) — a top-level `?theme=` search param must not suppress
 * the hash-borne conversationId restore on a cold load. Against a REAL `window.location` (not the
 * modeled harness above, which never carries a `search`): the WHATWG URL/Location split of `search`
 * from `hash` is unconditional — `window.location.hash` names only the fragment regardless of what
 * precedes the `#` — and `URLSource.parseHash` (`router/sources/URLSource.ts`) reads exactly that,
 * never `location.search` or `location.href`. Diagnosis (verified against this exact combined-URL
 * fixture, both at this router-substrate layer and live in a real browser): the restore already
 * succeeds on main. This case pins the invariant so a future regression that starts parsing the
 * WHOLE href, or gates ingress on `location.search` being empty, fails loudly here instead of
 * surfacing only as a live "sidebar row unhighlighted, transcript blank" symptom.
 */
describe('D3: a top-level search param does not suppress the hash-borne conversationId restore', () => {
  beforeEach(() => {
    __resetStoreRegistryForTest();
    __resetSurfaceSchemasForTest();
    __resetBootstrapForTest();
    __resetConversationListForTest();
    deactivateProjection();
    registerCoreStores();
  });

  afterEach(() => {
    deactivateProjection();
    window.history.replaceState(null, '', '/');
  });

  it('a cold load with ?theme= ahead of the hash still deep-links into the conversation the hash names', async () => {
    window.history.replaceState(
      null,
      '',
      `/?theme=light${addressFor('conv-deep')}`,
    );
    expect(window.location.search).toBe('?theme=light'); // precondition: the top-level param is real
    expect(window.location.hash).toBe(addressFor('conv-deep')); // precondition: the fragment is intact

    let activeSurface: string | null = null;
    const handler = createNavigationHandler({
      setActiveSurface: (id) => { activeSurface = id; },
      isKnownSurface: () => true,
    });
    const source = createURLSource({ windowImpl: window });
    const stop = source.start((intent: Intent, options) => {
      if (intent.address.kind !== 'navigate') return;
      void handler.handle(intent.address, { push: options?.pushHistory });
    }) as () => void;
    await Promise.resolve();

    expect(getConversationListState().activeId).toBe('conv-deep');
    expect(activeSurface).toBe(SV3);
    stop();
  });
});
