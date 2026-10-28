// @vitest-environment happy-dom

/**
 * The Search v3 shell (tempdoc 822 slice 1) — the window mounts, all five regions are present,
 * the scroll policy holds, and the surface is registered exactly the way a hidden dev route is.
 *
 * These cases measure geometry, structure and registration. The search wiring they now sit beside
 * has its own file (`SearchV3View.search.test.ts`); what is needed here is only that no case can
 * reach the network — the window subscribes to the real shared store, so the ONE exit that store
 * has (the global fetch) is stubbed for every case.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Topbar } from './Sv3Topbar.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { createCorePluginManifest } from '../../plugin-api/CorePlugin.js';
import { isLazySurface } from '../lazySurfaceRegistry.js';
import { COMPONENT_TAGS } from '../../renderers/component-vocabulary.generated.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { HERO_HEADLINE } from './fixtures.js';
import { SV3_EFFORT_DEFAULT, sv3EffortLabel } from './sv3-ask.js';
import { SV3_MORPH_ROOT_ATTR, sv3MorphSheetAdopted } from './sv3-composer-morph.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/**
 * happy-dom implements no View Transitions, so each case that exercises the animated path installs
 * its own stub and the shared teardown removes it.
 */
type ViewTransitionStub = (cb: () => Promise<void> | void) => { finished: Promise<unknown> };

const stubViewTransition = (impl: ViewTransitionStub): void => {
  (document as unknown as Record<string, unknown>).startViewTransition = impl;
};

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

beforeEach(() => {
  // Phase F6 wired this window to APP-WIDE, process-lifetime authorities (the conversation store,
  // the per-tab reload pointer, the shared draft controller). Each is a module singleton or a
  // storage key, so a case that did not reset them would be reading the previous case's state.
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ results: [] }) }),
  );
  // The window these cases measure is the window with a working model behind it: an unreachable one
  // honestly refuses a send and carries an extra banner (Phase F1), which is a state with its own
  // file (`SearchV3View.ask.test.ts`), not the shape this file is about.
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
});

/**
 * Every case starts from a document holding no window: the morph sheet is ref-counted against
 * connected windows, so a window left mounted by an earlier case would mask the release.
 */
afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  document.documentElement.removeAttribute(SV3_MORPH_ROOT_ATTR);
  delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
  // The stores are module singletons; a search one case ran would otherwise be the next case's state.
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** A nested region renders into its OWN shadow root; settle it before reading. */
async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

/** A component's OWN stylesheet — the last entry in `static styles`, after the shared sheets. */
function own(ctor: { styles?: unknown }): string {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
}

describe('the window mounts with its five regions', () => {
  it('renders sidebar, topbar, main and composer inside the window host', async () => {
    const el = await mount();
    const root = el.shadowRoot;
    expect(root?.querySelector('jf-sv3-sidebar')).toBeTruthy();
    expect(root?.querySelector('.column')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-topbar')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-main')).toBeTruthy();
    expect(root?.querySelector('jf-sv3-composer')).toBeTruthy();
  });

  it('names the window in the topbar and carries ONLY controls that do something', async () => {
    // Phase F9: the two slice-1 placeholders (`Window settings` / `Window layout`) are gone. They
    // were 2 of the window's 16 resting interactive elements with no handler and no consumer, and
    // an affordance the reader can press that answers nothing is chrome spent on a lie. The palette
    // control is the topbar's one live act, so the band ends at one button.
    const el = await mount();
    const topbar = await region(el, 'jf-sv3-topbar');
    expect(topbar.shadowRoot?.querySelector('[data-testid="sv3-topbar-title"]')?.textContent).toBe(
      'Search v3',
    );
    expect(topbar.shadowRoot?.querySelectorAll('[data-testid="sv3-topbar-control"]')).toHaveLength(
      0,
    );
    const live = [...(topbar.shadowRoot?.querySelectorAll('button') ?? [])];
    expect(live).toHaveLength(1);
    expect(live[0]?.getAttribute('data-testid')).toBe('sv3-topbar-palette');
  });

  it('opens with an empty sidebar: no sessions until this window has searched', async () => {
    // The fixture session list is gone (Phase A2) — the sidebar's zero state is now reached the real
    // way. What the panel always offers is the way OUT of it: the New-search control.
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    expect(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-row"]')).toHaveLength(0);
    expect(sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-empty"]')).toBeTruthy();
    expect(sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-new"]')).toBeTruthy();
  });

  it('claims nothing about the corpus when the window is docked without a search', async () => {
    const el = await mount();
    // The dev attribute handle docks WITHOUT sending anything. The region has no result set and no
    // failure — so it must render neither rows nor a zero-results verdict nor a count.
    await (el as SearchV3View).setComposerState('docked');
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(0);
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-empty"]')).toBeNull();
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-unreachable"]')).toBeNull();
    expect(main.shadowRoot?.querySelector('[data-testid="sv3-main-count"]')).toBeNull();
  });

  it('gives the composer a field, its effort control and a send control', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-input"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-send"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-effort"]')).toBeTruthy();
    // The slice-3 scope placeholders are GONE, not merely unused (Phase F10).
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-scope"]')).toBeNull();
  });
});

/**
 * The composer (slice 3): its two forms and the morph between them. The state is the
 * WINDOW's, not the composer's, because it decides what the content region holds.
 */
describe('the composer has two anatomies and one way between them', () => {
  const composerOf = (el: Mounted): Promise<Mounted> => region(el, 'jf-sv3-composer');

  it('opens on the empty hero: headline up, content region empty', async () => {
    const el = await mount();
    expect(el.getAttribute('composer-state')).toBe('hero');
    const composer = await composerOf(el);
    expect(composer.getAttribute('state')).toBe('hero');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-headline"]')?.textContent)
      .toBe(HERO_HEADLINE);
    const main = await region(el, 'jf-sv3-main');
    expect(main.shadowRoot?.querySelectorAll('[data-testid="sv3-main-row"]')).toHaveLength(0);
  });

  it('docks into the band, dropping the headline and revealing the results', async () => {
    const el = await mount();
    await (el as SearchV3View).setComposerState('docked');
    const composer = await composerOf(el);
    expect(el.getAttribute('composer-state')).toBe('docked');
    expect(composer.getAttribute('state')).toBe('docked');
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-headline"]')).toBeNull();
    // Both anatomies keep the field, the controls and the action — one component, two states.
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-input"]')).toBeTruthy();
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-effort"]')).toBeTruthy();
    // The region the composer vacated is the results' — what it holds is the search's business
    // (SearchV3View.search.test.ts), not the morph's.
    expect(await region(el, 'jf-sv3-main')).toBeTruthy();
  });

  it('keeps the send control disabled until the field carries something to send', async () => {
    const el = await mount();
    const composer = await composerOf(el);
    const send = composer.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-composer-send"]',
    );
    expect(send?.disabled).toBe(true);
    // ...and the placeholder is the overlaid element, shown for exactly the same condition.
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-placeholder"]'))
      .toBeTruthy();

    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = '   ';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    // Whitespace is not content: the control stays refused.
    expect(
      composer.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
        ?.disabled,
    ).toBe(true);

    field!.value = 'northfield lease';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    expect(
      composer.shadowRoot?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
        ?.disabled,
    ).toBe(false);
    expect(composer.shadowRoot?.querySelector('[data-testid="sv3-composer-placeholder"]')).toBeNull();
  });

  it('docks on send and returns to the hero on Escape', async () => {
    const el = await mount();
    const composer = await composerOf(el);
    const field = composer.shadowRoot?.querySelector<HTMLTextAreaElement>(
      '[data-testid="sv3-composer-input"]',
    );
    field!.value = 'northfield lease';
    field!.dispatchEvent(new Event('input'));
    await composer.updateComplete;
    composer.shadowRoot
      ?.querySelector<HTMLButtonElement>('[data-testid="sv3-composer-send"]')
      ?.click();
    await el.updateComplete;
    expect(el.getAttribute('composer-state')).toBe('docked');

    field!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await el.updateComplete;
    expect(el.getAttribute('composer-state')).toBe('hero');
  });

  /**
   * The compaction is only half done if the label evaporates and leaves a blank box behind. The spec
   * compacts a control INTO its glyph, so the glyph
   * has to be real, and the name the text was carrying has to move somewhere assistive tech can read.
   */
  it('compacts the effort control into a real glyph that keeps its whole name', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const control = (): HTMLElement | null =>
      composer.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-composer-effort"]') ?? null;

    // Hero: the visible label is only the VALUE, so the accessible name carries both halves in both
    // forms — a value-carrying control that compacts must not lose what the value is OF.
    const named = `Effort: ${sv3EffortLabel(SV3_EFFORT_DEFAULT)}`;
    expect(control()?.getAttribute('aria-label')).toBe(named);
    expect(control()?.textContent?.trim()).toContain(sv3EffortLabel(SV3_EFFORT_DEFAULT));
    const glyph = control()?.querySelector('svg.control-glyph');
    expect(glyph).toBeTruthy();
    // A real stroke glyph, not a placeholder swatch: it is all that survives the compaction.
    expect(glyph?.querySelector('path, circle, ellipse, polyline')).toBeTruthy();
    expect(glyph?.getAttribute('stroke')).toBe('currentColor');
    expect(control()?.querySelector('svg.control-chevron')).toBeTruthy();

    await (el as SearchV3View).setComposerState('docked');
    await composer.updateComplete;
    // Docked: the text is width-collapsed by CSS, and the name (which never moved) still says it.
    expect(control()?.getAttribute('aria-label'), 'the control lost its name when compacted').toBe(
      named,
    );
    expect(control()?.querySelector('svg.control-glyph')).toBeTruthy();
    expect(control()?.querySelector('svg.control-chevron')).toBeTruthy();
  });

  it('routes an external write of the dev attribute through the same morph', async () => {
    const el = await mount();
    const seen: string[] = [];
    stubViewTransition((cb) => {
      seen.push('transition');
      return { finished: Promise.resolve(cb()) };
    });
    el.setAttribute('composer-state', 'docked');
    await el.updateComplete;
    await new Promise<void>((r) => setTimeout(r, 0));
    expect(seen).toEqual(['transition']);
    expect((el as SearchV3View).composerState).toBe('docked');
  });
});

/**
 * The containment obligation, made observable. `::view-transition-*` rules are document-level and a
 * sheet in `document.adoptedStyleSheets` outlives whoever added it, so the window's lifecycle — not
 * good intentions — is what keeps this hidden dev surface from changing how the shipped app animates.
 */
describe('the document-level morph sheet lives exactly as long as the window does', () => {
  it.each([
    ['Linux Tauri shell', 'X11; Linux x86_64', true, true, false, false],
    ['Windows Tauri shell', 'Windows NT 10.0; Win64; x64', true, true, false, true],
    ['macOS Tauri shell', 'Macintosh; Intel Mac OS X 10_15_7', true, true, false, true],
    ['Linux browser', 'X11; Linux x86_64', false, true, false, true],
    ['API absent', 'Windows NT 10.0; Win64; x64', true, false, false, false],
    ['reduced motion', 'Macintosh; Intel Mac OS X 10_15_7', true, true, true, false],
  ])('applies composer state once and clears morph flags in a %s', async (
    _label, platform, tauri, api, reduce, enabled,
  ) => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(`Mozilla/5.0 (${platform})`);
    vi.stubGlobal('__TAURI_INTERNALS__', tauri ? {} : undefined);
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: reduce } as MediaQueryList);
    const el = await mount() as SearchV3View;
    const composer = await region(el, 'jf-sv3-composer');
    const apply = vi.spyOn(el, 'composerState', 'set');
    const start = vi.fn((cb: () => Promise<void> | void) => ({ finished: Promise.resolve(cb()) }));
    if (api) stubViewTransition(start);

    await el.setComposerState('docked');

    expect(start).toHaveBeenCalledTimes(enabled ? 1 : 0);
    expect(apply).toHaveBeenCalledExactlyOnceWith('docked');
    expect(el.composerState).toBe('docked');
    expect(composer.getAttribute('state')).toBe('docked');
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
    expect(composer.hasAttribute('morphing')).toBe(false);
  });

  it('adopts on connect and removes on the last disconnect', async () => {
    expect(sv3MorphSheetAdopted()).toBe(false);
    const first = await mount();
    expect(sv3MorphSheetAdopted()).toBe(true);
    // Ref-counted: a second window must not let the first one's teardown strip the sheet.
    const second = await mount();
    first.remove();
    expect(sv3MorphSheetAdopted()).toBe(true);
    second.remove();
    expect(sv3MorphSheetAdopted()).toBe(false);
  });

  it('scopes the morph attribute to the transition and clears it afterwards', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const during: Array<boolean> = [];
    stubViewTransition((cb) => {
      during.push(
        document.documentElement.getAttribute(SV3_MORPH_ROOT_ATTR) === 'true',
        composer.hasAttribute('morphing'),
      );
      return { finished: Promise.resolve(cb()) };
    });
    await (el as SearchV3View).setComposerState('docked');
    // Set for the transition...
    expect(during).toEqual([true, true]);
    // ...and gone after it, so no other transition in the app can be caught by these rules.
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
    expect(composer.hasAttribute('morphing')).toBe(false);
  });

  it('never waits on a frame inside the transition callback, which the browser suspends', async () => {
    // Live measurement caught this: the callback awaited a frame, the browser suspends rendering
    // until the callback settles, so the frame never arrived and the transition hung to its ~4s
    // callback timeout — morph skipped, flag held for four seconds. Reproduce the suspension by
    // making frames unavailable for as long as the callback is pending.
    const el = await mount();
    const realRaf = globalThis.requestAnimationFrame;
    stubViewTransition((cb) => {
      globalThis.requestAnimationFrame = (() => 0) as typeof requestAnimationFrame;
      const finished = Promise.resolve(cb()).finally(() => {
        globalThis.requestAnimationFrame = realRaf;
      });
      return { finished };
    });
    const outcome = await Promise.race([
      (el as SearchV3View).setComposerState('docked').then(() => 'settled'),
      new Promise((resolve) => setTimeout(() => resolve('hung'), 500)),
    ]);
    globalThis.requestAnimationFrame = realRaf;
    expect(outcome).toBe('settled');
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
  });

  it('clears the flag on EVERY path a state change can take', async () => {
    // One escape leaves the shipped app's own transitions under this window's rules indefinitely,
    // so each path is walked rather than argued about.
    const paths: Array<[string, () => void]> = [
      ['view transitions unsupported', () => {}],
      [
        'transition resolves',
        () => stubViewTransition((cb) => ({ finished: Promise.resolve(cb()) })),
      ],
      [
        'transition rejects mid-flight',
        () =>
          stubViewTransition((cb) => {
            void cb();
            return { finished: Promise.reject(new Error('skipped')) };
          }),
      ],
      [
        'transition throws before running the update',
        () =>
          stubViewTransition(() => {
            throw new Error('no transition');
          }),
      ],
      [
        'reduced motion',
        () => {
          stubViewTransition((cb) => ({ finished: Promise.resolve(cb()) }));
          vi.spyOn(window, 'matchMedia').mockImplementation(
            (query: string) =>
              ({
                matches: query.includes('prefers-reduced-motion'),
                media: query,
                onchange: null,
                addListener: () => {},
                removeListener: () => {},
                addEventListener: () => {},
                removeEventListener: () => {},
                dispatchEvent: () => false,
              }) as unknown as MediaQueryList,
          );
        },
      ],
    ];
    for (const [label, arrange] of paths) {
      const el = await mount();
      const composer = await region(el, 'jf-sv3-composer');
      arrange();
      await (el as SearchV3View).setComposerState('docked');
      expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR), label).toBe(false);
      expect(composer.hasAttribute('morphing'), label).toBe(false);
      // The state change itself is never optional, whichever path ran.
      expect((el as SearchV3View).composerState, label).toBe('docked');
      el.remove();
      vi.restoreAllMocks();
      delete (document as unknown as { startViewTransition?: unknown }).startViewTransition;
    }
  });

  it('swaps instantly under reduced motion, without reaching the API at all', async () => {
    const el = await mount();
    let started = 0;
    stubViewTransition((cb) => {
      started += 1;
      return { finished: Promise.resolve(cb()) };
    });
    vi.spyOn(window, 'matchMedia').mockImplementation(
      (query: string) =>
        ({
          matches: query.includes('prefers-reduced-motion'),
          media: query,
          onchange: null,
          addListener: () => {},
          removeListener: () => {},
          addEventListener: () => {},
          removeEventListener: () => {},
          dispatchEvent: () => false,
        }) as unknown as MediaQueryList,
    );
    await (el as SearchV3View).setComposerState('docked');
    expect(started).toBe(0);
    // The state change is not optional — only its animation is.
    expect((el as SearchV3View).composerState).toBe('docked');
    expect(document.documentElement.hasAttribute(SV3_MORPH_ROOT_ATTR)).toBe(false);
  });
});

/**
 * The rendered half of the row model (the CSS half is pinned in sv3-tokens.test.ts): what the
 * fixture set actually puts on screen, and what it deliberately does not.
 */
describe('the session rows render the spec anatomy', () => {
  /**
   * The row is exercised as a COMPONENT here, one instance per state. It used to be exercised through
   * the sidebar's fixture list, which is gone (Phase A2): the sidebar now renders real sessions, and
   * a real session is only ever resting or in-motion — so the two states it cannot produce would
   * have lost their coverage if these cases had followed the fixtures out.
   */
  const rowWith = async (props: Partial<Sv3SessionRow>): Promise<Sv3SessionRow> => {
    const row = new Sv3SessionRow();
    Object.assign(row, props);
    document.body.appendChild(row);
    await row.updateComplete;
    return row;
  };

  const statusOf = (row: Sv3SessionRow): Element | null =>
    row.shadowRoot?.querySelector('[data-testid="sv3-session-row-status"]') ?? null;

  it('spends a status colour on the three non-resting states and none on the resting one', async () => {
    const colored = await Promise.all(
      (['act-now', 'in-motion', 'broken'] as const).map((status) => rowWith({ status })),
    );
    for (const row of colored) expect(statusOf(row), `${row.status} has no dot`).not.toBeNull();

    // The budget as a NEGATIVE assertion: a resting row must be silent, and say its age instead.
    const resting = await rowWith({ status: 'resting', meta: '2m' });
    expect(statusOf(resting)).toBeNull();
    expect(
      resting.shadowRoot?.querySelector('[data-testid="sv3-session-row-meta"]')?.textContent?.trim(),
    ).toBe('2m');
  });

  it('runs the duty-cycled ping on the in-motion row only', async () => {
    const rows = await Promise.all(
      (['resting', 'act-now', 'in-motion', 'broken'] as const).map((status) => rowWith({ status })),
    );
    const pinging = rows.filter((r) => r.shadowRoot?.querySelector('.sv3-anim-status-ping'));
    expect(pinging).toHaveLength(1);
    expect(pinging[0]?.status).toBe('in-motion');
  });

  it('puts every state on the HOST as an attribute, which is what the fills key off', () => {
    // A state carried only as a property would leave `:host([active])` never matching — the row
    // would render correctly and be styled as if it were resting.
    const row = new Sv3SessionRow();
    document.body.appendChild(row);
    row.active = true;
    row.selected = true;
    row.receded = true;
    row.unread = true;
    row.inflight = true;
    row.status = 'broken';
    return row.updateComplete.then(() => {
      for (const attr of ['active', 'selected', 'receded', 'unread', 'inflight']) {
        expect(row.hasAttribute(attr), `${attr} is not reflected`).toBe(true);
      }
      expect(row.getAttribute('status')).toBe('broken');
      row.remove();
    });
  });

  it('makes the row a real button and marks an active one for assistive tech', async () => {
    const active = await rowWith({ active: true });
    const resting = await rowWith({ active: false });
    for (const row of [active, resting]) {
      const button = row.shadowRoot?.querySelector('button');
      expect(button?.tagName).toBe('BUTTON');
      expect(button?.getAttribute('type')).toBe('button');
    }
    expect(active.shadowRoot?.querySelector('button')?.getAttribute('aria-current')).toBe('true');
    // ...and ONLY an active one: `aria-current` on every row would name no current row at all.
    expect(resting.shadowRoot?.querySelector('button')?.hasAttribute('aria-current')).toBe(false);
  });
});

describe('the window never scrolls; one inner region does', () => {
  it('clips the window host and the main column', () => {
    const styles = own(SearchV3View);
    const hostRule = styles.slice(styles.indexOf(':host {'), styles.indexOf('jf-sv3-sidebar {'));
    expect(hostRule).toContain('overflow: hidden');
    const columnRule = styles.slice(styles.indexOf('.column {'));
    expect(columnRule).toContain('overflow: hidden');
  });

  it('clips every region host, so no region can become a second scroller', () => {
    for (const ctor of [Sv3Sidebar, Sv3Main]) {
      const styles = own(ctor);
      const hostRule = styles.slice(styles.indexOf(':host {'), styles.indexOf('}'));
      expect(hostRule).toContain('overflow: hidden');
    }
  });

  it('keeps the ONE transcript scroller and only the bounded recovery data row may scroll separately', async () => {
    localStorage.setItem('justsearch.draft.search-v3.composer', 'Recover this draft');
    const el = await mount();
    const main = await region(el, 'jf-sv3-main');
    const scroller = main.shadowRoot?.querySelector('[data-testid="sv3-main-scroller"]');
    expect(scroller).toBeTruthy();
    expect(scroller?.classList.contains('sv3-scroller')).toBe(true);

    // Native schema text and recovery data own only their bounded contents, never the transcript.
    const schemaRule = own(Sv3Composer).match(/:host \.schema-field \[id='sv3-schema'\]\s*\{[^}]*\}/)?.[0];
    expect(schemaRule).toContain('max-block-size: 8rem');
    expect(schemaRule).toContain('overflow: auto');
    const recoveryRules = own(SearchV3View).match(/^\s*\.column > \.recovery-notice\s*\{[^}]*\}/gm) ?? [];
    expect(recoveryRules).toHaveLength(1);
    const recoveryRule = recoveryRules[0]!;
    expect(recoveryRule).toContain('max-block-size: 20%');
    expect(recoveryRule).toContain('overflow: auto');
    expect(recoveryRule).toContain('overflow-wrap: anywhere');
    expect(recoveryRule).toContain('flex: 0 0 auto');
    const recovery = el.shadowRoot!.querySelector('[data-testid="sv3-column"] > [data-testid="sv3-residency-notice"]');
    expect(recovery?.classList.contains('recovery-notice')).toBe(true);
    expect(el.shadowRoot!.querySelectorAll('.recovery-notice')).toHaveLength(1);
    expect(el.shadowRoot!.querySelectorAll('[data-testid="sv3-residency-notice"]')).toHaveLength(1);
    expect(el.shadowRoot!.querySelector('.recovery-notice')).toBe(recovery);
    expect(recovery?.querySelector('jf-sv3-main, .dock, .sv3-scroller')).toBeNull();
    expect(main.parentElement?.previousElementSibling).toBe(recovery);
    expect(el.shadowRoot!.querySelector('.dock')?.contains(recovery)).toBe(false);
    for (const ctor of [SearchV3View, Sv3Sidebar, Sv3Topbar, Sv3Composer]) {
      const styles = own(ctor).replace(ctor === Sv3Composer ? schemaRule ?? '' : '', '')
        .replace(ctor === SearchV3View ? recoveryRule : '', '');
      expect(styles).not.toContain('overflow-y: auto');
      expect(styles).not.toContain('overflow: auto');
      expect(styles).not.toContain('overflow: scroll');
    }
    // The content surface's own host is clipped; only its inner scroller scrolls.
    expect(own(Sv3Main)).not.toContain('overflow-y: auto');
  });

  it('adopts the scroller mixin exactly once across the rendered window', async () => {
    const el = await mount();
    let found = 0;
    for (const tag of ['jf-sv3-sidebar', 'jf-sv3-topbar', 'jf-sv3-main', 'jf-sv3-composer']) {
      const r = await region(el, tag);
      found += r.shadowRoot?.querySelectorAll('.sv3-scroller').length ?? 0;
    }
    found += el.shadowRoot?.querySelectorAll('.sv3-scroller').length ?? 0;
    expect(found).toBe(1);
  });
});

describe('the surface is registered as the main Search window', () => {
  it('declares core.search-v3-surface as USER/RAIL', () => {
    const surfaces = createCorePluginManifest().capabilities.surfaces ?? [];
    const v3 = surfaces.find((s) => s.id === 'core.search-v3-surface');
    expect(v3).toBeDefined();
    expect(v3?.mountTag).toBe('jf-sv3-window');
    expect(v3?.audience).toBe('USER');
    expect(v3?.placement).toBe('RAIL');
    expect(v3?.placement).not.toBe('DEEPLINK');
  });

  it('registers eagerly as the landing window without a lazy loader', () => {
    expect(isLazySurface('jf-sv3-window')).toBe(false);
    expect(customElements.get('jf-sv3-window')).toBeDefined();
  });

  it('is in the component vocabulary, with every region it composes', () => {
    for (const tag of [
      'jf-sv3-window',
      'jf-sv3-topbar',
      'jf-sv3-sidebar',
      'jf-sv3-session-row',
      'jf-sv3-main',
      'jf-sv3-composer',
    ]) {
      expect(COMPONENT_TAGS).toContain(tag);
    }
  });
});
