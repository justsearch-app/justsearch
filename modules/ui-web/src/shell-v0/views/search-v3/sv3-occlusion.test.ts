// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * The floating composer and the band it occludes (tempdoc 859 §B).
 *
 * §1-B's finding was that the composer sits in document flow, so the transcript is cut off hard at
 * its top edge and the Sources disclosure renders where wheel-scroll cannot reach. The fix is a
 * viewport-ownership change, not a style tweak: the scroller owns the full column, the dock floats
 * over it, and its measured height is published as ONE variable that every consumer reads.
 *
 * What this file can and cannot see is worth stating, because the honest half is most of it.
 * happy-dom lays nothing out and implements neither `scroll-padding` nor `backdrop-filter`, so the
 * cases here pin the WIRING — the rules that are authored, the variable that is published, the
 * consumers that read it — and the rendered result (does the blur read, does `block: 'center'`
 * actually centre in the visible region, is the disclosure reachable) is carried by the measured
 * ui-shot step and the live legs recorded in 859 §B. A green file here is not a working overlay.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { SearchV3View } from './SearchV3View.js';
import { Sv3Main } from './Sv3Main.js';
import { Sv3Composer } from './Sv3Composer.js';
import { Sv3ContextBar } from './Sv3ContextBar.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import type { Sv3Turn } from './sv3-sessions.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

/** A component's OWN stylesheet — the last entry in `static styles`, after the shared sheets. */
function own(ctor: { styles?: unknown }): string {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
}

/** The text of the rule opened by `selector`, up to its closing brace. */
function ruleIn(styles: string, selector: string): string {
  const at = styles.indexOf(selector);
  expect(at, `no rule for ${selector}`).toBeGreaterThan(-1);
  return styles.slice(at, styles.indexOf('}', at));
}

async function mountWindow(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ results: [] }) }),
  );
  __feedForTest({
    inference: { mode: 'online', available: true } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('859 §B — the dock floats over the transcript', () => {
  it('wraps the context bar and the composer in ONE positioned wrapper inside the column', async () => {
    // ONE wrapper, not two floating siblings: the band has to be a single OBSERVABLE box, and
    // summing two boxes re-introduces the enumeration this change exists to avoid.
    const el = await mountWindow();
    const dock = el.shadowRoot?.querySelector('[data-testid="sv3-dock"]');
    expect(dock).toBeTruthy();
    expect(dock?.parentElement?.classList.contains('column')).toBe(true);
    expect(dock?.querySelector('jf-sv3-context-bar')).toBeTruthy();
    expect(dock?.querySelector('jf-sv3-composer')).toBeTruthy();
  });

  it('is click-through except for the two centred content columns inside it', () => {
    // The transcript underneath must stay wheel-scrollable right up to the glass edge, so the dock
    // takes no pointer events and exactly the two real content boxes take them back.
    expect(ruleIn(own(SearchV3View), '.dock {')).toContain('pointer-events: none');
    // The BARE `.band` rule, not the `:host([morphing]) .band` one that precedes it in the sheet:
    // the click-through division must hold in every state, not only mid-morph.
    expect(ruleIn(own(Sv3Composer), '\n      .band {')).toContain('pointer-events: auto');
    expect(ruleIn(own(Sv3ContextBar), '.bar {')).toContain('pointer-events: auto');
  });

  it('floats only in DOCKED, at the sticky rung, anchored to the bottom', () => {
    // State-gated on purpose: the hero composer is `position: absolute` against the nearest
    // positioned ancestor, so a dock positioned in hero too would steal hero's containing block
    // from `.column` and collapse the centred landing into a bottom strip.
    const rule = ruleIn(own(SearchV3View), ":host([composer-state='docked']) .dock {");
    expect(rule).toContain('position: absolute');
    expect(rule).toContain('inset-block-end: 0');
    expect(rule).toContain('inset-inline: 0');
    // Below the overlay rung, which the hero composer, the overlaid pane and the palette share.
    expect(rule).toContain('z-index: var(--z-sticky)');
  });

  it('D11 — both resize grips move one rung UP, so DOM order cannot decide who covers whom', () => {
    // Before this slice both grips sat at --z-sticky, the dock's own rung; the sidebar grip renders
    // BEFORE `.column` and the pane grip AFTER it, so the dock would have painted over one and been
    // painted over by the other. A full-height boundary control belongs above a bottom band.
    const styles = own(SearchV3View);
    expect(ruleIn(styles, 'button.sidebar-grip {')).toContain('z-index: var(--z-overlay)');
    expect(ruleIn(styles, 'button.pane-grip {')).toContain('z-index: var(--z-overlay)');
  });

  it('D12 — the veil is strictly the strip BELOW the glass box, never behind it', () => {
    // `backdrop-filter` samples what is painted behind the element, so a veil under the glass would
    // be what gets blurred and the content-glides-beneath effect would vanish into a flat colour.
    const rule = ruleIn(own(SearchV3View), ":host([composer-state='docked']) .dock::after {");
    expect(rule).toContain('inset-block-end: 0');
    expect(rule).toContain('block-size: var(--floating-content-inset)');
    expect(rule).toContain('background: var(--background)');
    // Intra-dock stacking, not a window z-rung: a POSITIONED pseudo-element at z-index auto paints
    // above static in-flow siblings whatever the source order, so without the pair the veil would
    // cover the composer's own bottom edge and clip its elevation.
    expect(rule).toContain('z-index: 0');
    expect(ruleIn(own(SearchV3View), ":host([composer-state='docked']) .dock > * {")).toContain(
      'z-index: 1',
    );
  });

  it('adds no scroller — the one-scroller invariant holds by construction', () => {
    // `SearchV3View.test.ts`'s four structural assertions are the authority; this is the direct
    // statement that the dock did not become a fifth region with an overflow of its own.
    const styles = own(SearchV3View);
    expect(styles).not.toContain('overflow-y: auto');
    expect(styles).not.toContain('overflow: auto');
    expect(styles).not.toContain('overflow: scroll');
  });
});

describe('859 §B — the scroller reads the band', () => {
  it('pads AND scroll-pads by it, with the calc() that keeps the existing inset', () => {
    const rule = ruleIn(own(Sv3Main), '.scroller {');
    // A bare `padding-block-end` would REPLACE the shorthand's 12px inset rather than add to it,
    // silently changing the transcript's bottom rhythm.
    expect(rule).toContain('padding: var(--floating-content-inset)');
    expect(rule).toContain(
      'padding-block-end: calc(var(--floating-content-inset) + var(--sv3-composer-occlusion))',
    );
    // The half that makes `scrollIntoView({block:'center'})` centre in the VISIBLE region and stops
    // `{block:'nearest'}` counting an occluded target as already in view.
    expect(rule).toContain('scroll-padding-block-end: var(--sv3-composer-occlusion)');
  });

  it('declares a NON-ZERO default, because a zero default is the silent trap', () => {
    // Without a ResizeObserver nothing ever writes the variable. A `0` default would restore
    // exactly the clipping this slice removes — silently, and only on that platform.
    const tokens = (sv3Tokens as unknown as { cssText: string }).cssText;
    const match = /--sv3-composer-occlusion:\s*([^;]+);/.exec(tokens);
    expect(match, 'the band token is not declared in the sheet').toBeTruthy();
    const value = match?.[1]?.trim() ?? '0';
    expect(value).not.toBe('0');
    expect(value).not.toBe('0px');
    expect(Number.parseFloat(value)).toBeGreaterThan(0);
  });
});

describe('859 §B — the window publishes the band', () => {
  const OCCLUSION = '--sv3-composer-occlusion';
  const dockOf = (el: Mounted): HTMLElement =>
    el.shadowRoot?.querySelector('.dock') as HTMLElement;

  /** Give the dock a laid-out box; happy-dom reports 0 for everything otherwise. */
  const stubDockHeight = (el: Mounted, height: number): void => {
    dockOf(el).getBoundingClientRect = () =>
      ({ top: 0, left: 0, right: 800, bottom: height, width: 800, height, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  };

  it('writes the measured height on the host once docked', async () => {
    const el = (await mountWindow()) as Mounted & { composerState: string };
    stubDockHeight(el, 132);
    el.composerState = 'docked';
    await el.updateComplete;
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('132px');
  });

  it('writes a DELIBERATE zero in hero, where the composer IS the content region', async () => {
    // Publishing hero's box would pad the scroller by a whole column height. This zero is a
    // decision; the one below is an accident, and they must not be confused.
    const el = (await mountWindow()) as Mounted & { composerState: string };
    stubDockHeight(el, 900);
    el.composerState = 'docked';
    await el.updateComplete;
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('900px');
    el.composerState = 'hero';
    await el.updateComplete;
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('0px');
  });

  it('D8 — a ZERO measurement never replaces a real one', async () => {
    // An inline setProperty REPLACES the :host default rather than flooring it, so a transient
    // unmeasurable dock (mid-morph, detached, hidden) must not be allowed to say "no band".
    const el = (await mountWindow()) as Mounted & { composerState: string };
    stubDockHeight(el, 132);
    el.composerState = 'docked';
    await el.updateComplete;
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('132px');
    stubDockHeight(el, 0);
    (el as unknown as { publishOcclusion(): void }).publishOcclusion();
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('132px');
  });

  it("D8 — hero's deliberate zero does NOT survive into an unmeasurable docked state", async () => {
    // The trap a plain skip would leave: hero writes 0px legitimately, the morph to docked finds
    // nothing to measure, and the transcript is clipped by the whole band with nothing in the code
    // saying so. Clearing the inline value hands the question back to the non-zero token default.
    // happy-dom measures every element as 0, so this is the path a mount actually takes here.
    const el = (await mountWindow()) as Mounted & { composerState: string };
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('0px'); // hero, deliberate
    el.composerState = 'docked';
    await el.updateComplete;
    expect(dockOf(el).getBoundingClientRect().height).toBe(0);
    expect(el.style.getPropertyValue(OCCLUSION)).toBe('');
  });

  it('needs no ResizeObserver to be correct on a platform that has none', async () => {
    // happy-dom DEFINES ResizeObserver as a class that never fires, so the `typeof` guard never
    // trips under test and asserting through it would assert nothing. Delete it outright: the
    // window must still mount, publish on the state change it can see, and otherwise leave the
    // non-zero token default in charge.
    const saved = (globalThis as Record<string, unknown>).ResizeObserver;
    delete (globalThis as Record<string, unknown>).ResizeObserver;
    try {
      const el = (await mountWindow()) as Mounted & { composerState: string };
      stubDockHeight(el, 118);
      el.composerState = 'docked';
      await el.updateComplete;
      expect(el.style.getPropertyValue(OCCLUSION)).toBe('118px');
    } finally {
      (globalThis as Record<string, unknown>).ResizeObserver = saved;
    }
  });

  it('D5 — publishing a new band triggers a nav remeasure, not a re-render', async () => {
    // The controller's own ResizeObserver watches the SCROLLER, whose box no longer changes when
    // the composer grows. Without this signal the reading window and the FOCUS ring go stale on
    // exactly the keystroke that changed the band.
    const el = (await mountWindow()) as Mounted & { composerState: string };
    const main = el.shadowRoot?.querySelector('jf-sv3-main') as Sv3Main;
    const remeasure = vi.spyOn(main, 'remeasureReadingWindow');
    stubDockHeight(el, 140);
    el.composerState = 'docked';
    await el.updateComplete;
    expect(remeasure).toHaveBeenCalled();
  });
});

describe('859 §B / D3 — the two appearance escapes are ONE code path', () => {
  it('zeroes the blur multiplier under prefers-reduced-transparency', () => {
    // The block already existed and lowered tint alphas while leaving every blur running — it made
    // surfaces MORE transparent under a preference asking for less. This single declaration also
    // fixes DragOverlay / IndexingOverlay / ProvenanceBadge, which honour the multiplier but were
    // never reached by this preference, and makes reduced-transparency and
    // [data-surface-mode="solid"] arrive downstream as the same fact.
    const css = readFileSync(resolve(process.cwd(), 'src/styles/tokens.css'), 'utf8');
    const at = css.indexOf('@media (prefers-reduced-transparency: reduce)');
    expect(at).toBeGreaterThan(-1);
    const block = css.slice(at, css.indexOf('\n}', css.indexOf('{', at)));
    expect(block).toContain('--glass-blur-scale: 0');
  });
});

/* ── The follow-the-end gate (D1) ─────────────────────────────────────────────────────────────
   Gated on CONTENT GROWTH, not on a render and not on a `streaming` flag. `streaming` is assigned
   at three sites, all inside the ask path, so a stream-gated snap would stop the transcript
   following agent runs entirely; and `followEnd` starts armed, so it would also land a freshly
   opened thread at the TOP. Growth needs no source enumeration at all. */
describe('859 §B / D1 — the transcript follows CONTENT GROWTH, not every render', () => {
  const turn = (over: Partial<Sv3Turn> & { id: string }): Sv3Turn => ({
    recordId: null,
    assistantRecordId: null,
    recordOpenedByUser: false,
    kind: 'ask',
    question: 'why did the renewal fail?',
    answer: 'It expired.',
    status: 'complete',
    evidence: null,
    detail: '',
    toolCalls: 0,
    activity: [],
    askedAt: 1,
    standaloneQuestion: '',
    reasoning: [],
    durationMs: null,
    modelLabel: null,
    ...over,
  });

  type MountedMain = Sv3Main & { updateComplete: Promise<unknown>; requestUpdate: () => void };

  async function mountMain(turns: readonly Sv3Turn[]): Promise<MountedMain> {
    const el = document.createElement('jf-sv3-main') as MountedMain;
    el.turns = [...turns];
    document.body.appendChild(el);
    await el.updateComplete;
    return el;
  }

  const scrollerOf = (el: MountedMain): HTMLElement =>
    el.shadowRoot?.querySelector('.scroller') as HTMLElement;

  /** Stub the content height; the scroll position stays a real, writable value. */
  const setContentHeight = (conv: HTMLElement, px: number): void => {
    Object.defineProperty(conv, 'scrollHeight', { configurable: true, value: px });
  };

  async function laidOut(turns: readonly Sv3Turn[]): Promise<[MountedMain, HTMLElement]> {
    const el = await mountMain(turns);
    const conv = scrollerOf(el);
    expect(conv, 'the transcript arm rendered no .scroller').toBeTruthy();
    Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 600 });
    Object.defineProperty(conv, 'scrollTop', { configurable: true, writable: true, value: 0 });
    return [el, conv];
  }

  it('(c) a cold thread open lands at the END, not at the top', async () => {
    const [el, conv] = await laidOut([turn({ id: 't1' })]);
    // The transcript's content becomes measurable: growth from nothing.
    setContentHeight(conv, 2000);
    el.requestUpdate();
    await el.updateComplete;
    expect(conv.scrollTop).toBe(2000);
  });

  it('(a) an agent-run feed that GROWS the content is followed', async () => {
    // The delegate path never sets `streaming`; a stream-gated snap would have stopped following
    // agent runs entirely, which is why the gate is growth.
    const [el, conv] = await laidOut([turn({ id: 't1', kind: 'agent' })]);
    setContentHeight(conv, 2000);
    el.requestUpdate();
    await el.updateComplete;
    setContentHeight(conv, 2400);
    el.requestUpdate();
    await el.updateComplete;
    expect(conv.scrollTop).toBe(2400);
  });

  it('resets its yardstick when the scroller NODE swaps arms', async () => {
    // Found by the post-implementation pass, not by the design: this element emits `.scroller` from
    // four different render arms (the same trap 857 PR-A/A2 fixed in the navigation controller). A
    // height carried across an arm swap compares two different boxes, so a SHORTER new arm reads as
    // "did not grow" and the reader lands at the top of a transcript that just mounted.
    const [el, tall] = await laidOut([turn({ id: 't1' })]);
    setContentHeight(tall, 4000);
    el.requestUpdate();
    await el.updateComplete;
    expect(tall.scrollTop).toBe(4000);

    // A fresh, SHORTER node standing in for the arm swap.
    const fresh = document.createElement('div');
    Object.defineProperty(fresh, 'scrollHeight', { configurable: true, value: 1500 });
    Object.defineProperty(fresh, 'scrollTop', { configurable: true, writable: true, value: 0 });
    const scrollerGetter = Object.getOwnPropertyDescriptor(
      Object.getPrototypeOf(el),
      'scroller',
    );
    Object.defineProperty(el, 'scroller', { configurable: true, get: () => fresh });
    try {
      el.requestUpdate();
      await el.updateComplete;
      expect(fresh.scrollTop).toBe(1500);
    } finally {
      delete (el as unknown as Record<string, unknown>).scroller;
      expect(scrollerGetter).toBeTruthy(); // the prototype accessor is what we fell back to
    }
  });

  it('a render that added NOTHING moves the reader nowhere', async () => {
    // The old code re-asserted `scrollTop = scrollHeight` on every render while armed — one of the
    // two candidate causes of §7's unreachable disclosure.
    const [el, conv] = await laidOut([turn({ id: 't1' })]);
    setContentHeight(conv, 2000);
    el.requestUpdate();
    await el.updateComplete;
    conv.scrollTop = 500;
    el.requestUpdate();
    await el.updateComplete;
    expect(conv.scrollTop).toBe(500);
  });

  it('(b) opening a disclosure is NOT followed — a reveal is a navigation intent', async () => {
    // Opening the Sources panel GROWS the content, so growth alone would drag the reader to the end
    // of the transcript instead of to the thing they just revealed. The toggle disarms the follow
    // and then says where the view should go itself (`revealSources`).
    const evidence = {
      sources: [
        {
          parentDocId: 'doc-1',
          chunkIndex: 0,
          chunkTotal: 1,
          startChar: 0,
          endChar: 10,
          score: 0.9,
          excerpt: 'a passage',
          startLine: 1,
          endLine: 2,
          headingText: 'Heading',
          headingLevel: 2,
        },
      ],
      matches: [],
      marks: [],
      retrievalMode: '',
    } as unknown as Sv3Turn['evidence'];
    const [el, conv] = await laidOut([turn({ id: 't1', evidence })]);
    setContentHeight(conv, 2000);
    el.requestUpdate();
    await el.updateComplete;
    expect(conv.scrollTop).toBe(2000);

    const trigger = el.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-turn-sources"]');
    expect(trigger, 'no Sources disclosure trigger rendered for a turn with evidence').toBeTruthy();
    conv.scrollTop = 1200;
    trigger?.click();
    await el.updateComplete;
    // The panel mounting grew the content; the follow is disarmed, so the reader stays put.
    setContentHeight(conv, 2600);
    el.requestUpdate();
    await el.updateComplete;
    expect(conv.scrollTop).toBe(1200);
  });
});

describe('859 §B — the transcript reads the visible region', () => {
  it('feeds the navigation controller the published band', async () => {
    // One measurement, three readers (padding, scroll-padding, the reading window) and no way for
    // them to disagree. happy-dom resolves an element's OWN inline custom property but does not
    // inherit one, so the value is seeded on the scroller here; in the browser it arrives by
    // inheritance from the window host, which is the same lookup.
    const el = document.createElement('jf-sv3-main') as Sv3Main & { updateComplete: Promise<unknown> };
    el.turns = [];
    document.body.appendChild(el);
    await el.updateComplete;
    const conv = el.shadowRoot?.querySelector('.scroller') as HTMLElement;
    expect(conv).toBeTruthy();
    const read = (el as unknown as { occludedEndPx: () => number }).occludedEndPx.bind(el);
    expect(read()).toBe(0);
    conv.style.setProperty('--sv3-composer-occlusion', '132px');
    expect(read()).toBe(132);
  });
});
