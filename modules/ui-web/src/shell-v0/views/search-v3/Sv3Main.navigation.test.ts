// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * The run spine's KEYBOARD NAVIGATION in Search v3 (tempdoc 857 PR-A) — J/K steps focus through a
 * run's landmarks, the only keyboard navigation the product has for run steps and the reason the
 * mechanism was ported rather than dropped with `UnifiedChatView`.
 *
 * The suite is built around ONE structural risk, because the feature's failure mode is silent: a
 * mis-stamped anchor yields zero landmarks and every J/K test still passes, since the retiree's own
 * tests (and `primitives/navigation.test.ts`) hand-assign `nav.landmarks` and never exercise
 * `measure()`. Compounding it, happy-dom lays nothing out, so `getBoundingClientRect()` returns
 * all-zero rects and `measure()` DROPS every zero-height element (`primitives/navigation.ts:345`) —
 * a perfectly-stamped transcript would measure to nothing.
 *
 * So the rects are stubbed per case (the idiom, and the reason for it, are this directory's own:
 * `SearchV3View.pane.test.ts:21/58`, `SearchV3View.sidebar.test.ts:13/43`), and the wiring cases
 * assert the landmark ids the CONTROLLER holds against the DOM queried from the exact element
 * `scrollEl()` returns. That last clause is what also makes these cases cover the scroller-swap
 * defect: a controller bound to a detached arm fails them.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
import './Sv3Main.js';
import '../../commands/CommandPalette.js';
import type { Sv3Main } from './Sv3Main.js';
import type { CommandPalette } from '../../commands/CommandPalette.js';
import { __resetModalityForTest } from '../../primitives/modality.js';
import { deepActiveElement, isTypingTarget } from '../../utils/keyboardHandler.js';
import type { NavigationController } from '../../primitives/navigation.js';
import type { Sv3Turn } from './sv3-sessions.js';
import type { Sv3RunFeedItem, Sv3RunPrompt, Sv3RunView } from './sv3-run.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import { budgetContinueSteps, projectBudgetGateFacts } from '../budgetProjection.js';

type Mounted = Sv3Main & { updateComplete: Promise<unknown>; requestUpdate: () => void };

const navOf = (el: Mounted): NavigationController =>
  (el as unknown as { nav: NavigationController }).nav;

/**
 * Tempdoc 859 §D §2.4/§2.5 — a held budget gate, built through the REAL projections rather than
 * hand-written. These cases are about anchor stamping, not about the panel; deriving the fact panel
 * and the ladder here keeps the fixture from becoming a second, drifting answer to what a gate is.
 */
const budgetPrompt = (tokensNeeded: number, tokensRemaining: number): Sv3RunPrompt => ({
  kind: 'budget',
  id: 'run-budget-gate',
  tokensNeeded,
  tokensRemaining,
  facts: projectBudgetGateFacts({
    totalTokensConsumed: 90,
    toolCallsExecuted: 1,
    iterationsUsed: 2,
    askedAt: null,
    now: 0,
    lastAction: null,
  }),
  steps: budgetContinueSteps({ totalTokensConsumed: 90, tokensNeeded, tokensRemaining }),
});

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
  disposition: null,
  ...over,
});

const toolCall = (callId: string): ToolCall => ({
  callId,
  toolName: 'grep',
  arguments: '{}',
  risk: 'LOW',
  status: 'pending',
});

const runView = (over: {
  turnId: string;
  items?: readonly Sv3RunFeedItem[];
  prompts?: readonly Sv3RunPrompt[];
}): Sv3RunView => ({
  turnId: over.turnId,
  phase: 'running',
  feed: {
    items: over.items ?? [],
    toolCallCount: (over.items ?? []).filter((i) => i.kind === 'tool').length,
    pendingApprovals: [],
    errored: false,
  },
  prompts: over.prompts ?? [],
});

async function mount(fields: Partial<Sv3Main> = {}): Promise<Mounted> {
  document.body.innerHTML = '';
  const el = document.createElement('jf-sv3-main') as Mounted;
  Object.assign(el, fields);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const scrollerOf = (el: Mounted): HTMLElement | null =>
  (el.shadowRoot?.querySelector('.scroller') ?? null) as HTMLElement | null;

/**
 * Give the scroll column and every stamped element a laid-out box, then drive renders until the
 * controller has measured. `measure()` is coalesced to one leading pass per frame (tempdoc 857 A9),
 * so a stub applied AFTER the mount render needs another cycle plus a macrotask to be seen.
 */
async function layOut(el: Mounted): Promise<HTMLElement> {
  const conv = scrollerOf(el);
  if (conv === null) throw new Error('no .scroller rendered — the transcript arm was not taken');
  conv.getBoundingClientRect = () =>
    ({ top: 0, left: 0, right: 800, bottom: 600, width: 800, height: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
  Object.defineProperty(conv, 'scrollHeight', { configurable: true, value: 2000 });
  Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 600 });
  let top = 0;
  for (const node of conv.querySelectorAll('[data-item-id]')) {
    const y = top;
    (node as HTMLElement).getBoundingClientRect = () =>
      ({ top: y, left: 0, right: 800, bottom: y + 40, width: 800, height: 40, x: 0, y, toJSON: () => ({}) }) as DOMRect;
    top += 40;
  }
  for (let i = 0; i < 4 && navOf(el).landmarks.length === 0; i++) {
    el.requestUpdate();
    await el.updateComplete;
    await new Promise((r) => setTimeout(r, 0));
  }
  return conv;
}

/** The stamped ids in DOM order, read from the element the controller actually navigates. */
const stampedIds = (conv: HTMLElement): string[] =>
  [...conv.querySelectorAll('[data-item-id]')].map((n) => n.getAttribute('data-item-id') ?? '');

const press = (key: string, init: KeyboardEventInit = {}): void => {
  window.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init }));
};

afterEach(() => {
  document.body.innerHTML = '';
  // 864 Layer 2(d) — modality depth is module state and outlives the DOM: a case that left a modal
  // entered would make every LATER case's global keys silently inert.
  __resetModalityForTest();
});

describe('857 PR-A — the stamped transcript becomes the controller’s landmarks (the wiring)', () => {
  it('measures every stamped anchor, in DOM order, from the element scrollEl() returns', async () => {
    const el = await mount({
      turns: [
        turn({ id: 't1' }),
        turn({
          id: 't2',
          kind: 'agent',
          question: 'and the invoice?',
          activity: [
            { kind: 'text', id: 'e1', text: 'checked the ledger' },
            { kind: 'note', id: 'e2', label: 'Progress', text: 'two files left' },
          ],
        }),
      ],
    });
    const conv = await layOut(el);

    // The DOM is stamped as designed: a question and an answer for the ask turn, a question and one
    // anchor per run step for the agent turn (whose answer is its activity, not an `.answer` div).
    expect(stampedIds(conv)).toEqual(['t1:q', 't1:a', 't2:q', 'e1', 'e2']);
    // …and the controller HOLDS them. This is the assertion nothing else in the repo makes: every
    // other navigation test assigns `landmarks` by hand, so the path from stamped DOM to measured
    // landmark has never been covered for either adopter.
    expect(navOf(el).landmarks.map((l) => l.id)).toEqual(['t1:q', 't1:a', 't2:q', 'e1', 'e2']);
  });

  it('covers a live run: its feed steps AND the decision it is parked on are landmarks', async () => {
    const el = await mount({
      turns: [turn({ id: 'r1', kind: 'agent', question: 'fix the build' })],
      run: runView({
        turnId: 'r1',
        items: [
          { kind: 'text', id: 'x1', text: 'reading the log' },
          { kind: 'tool', id: 'call-9', call: toolCall('call-9') },
        ],
        // The approval's own id IS the tool call's id — the collision the `:hold` suffix exists for.
        prompts: [{ kind: 'approval', id: 'call-9', toolName: 'grep', risk: 'LOW' }],
      }),
    });
    const conv = await layOut(el);

    const ids = navOf(el).landmarks.map((l) => l.id);
    expect(ids).toEqual(['r1:q', 'x1', 'call-9', 'call-9:hold']);
    // Read back from the scroller the controller navigates, so the list above is the DOM's order and
    // not merely the order the projection happened to produce.
    expect(stampedIds(conv)).toEqual(ids);
    // The held decision renders OUTSIDE `.run-feed` precisely so it cannot be scrolled past; without
    // the fourth stamp site it would be the one run element J/K skips.
    expect(ids).toContain('call-9:hold');
  });

  it('rebinds across the scroller swap: a hero window that receives its first turn navigates', async () => {
    // The hero arm renders an EMPTY `.scroller` from a different template than the transcript arm,
    // so the node the controller first sees is not the node it must end up bound to. The authority
    // binds its observer and listeners once per node, so a controller that stayed active across the
    // swap would keep measuring a detached element.
    const el = await mount({ state: 'docked' });
    const heroScroller = scrollerOf(el);
    expect(heroScroller).not.toBeNull();
    expect(navOf(el).landmarks).toHaveLength(0);

    el.turns = [turn({ id: 'first' })];
    await el.updateComplete;
    const conv = await layOut(el);

    expect(conv).not.toBe(heroScroller);
    expect(navOf(el).landmarks.map((l) => l.id)).toEqual(['first:q', 'first:a']);
  });
});

describe('857 PR-A — the anchor id space', () => {
  it('mints no duplicate data-item-id across turns, records, a live feed and a held approval', async () => {
    const el = await mount({
      turns: [
        turn({ id: 'a' }),
        turn({
          id: 'b',
          kind: 'agent',
          activity: [
            { kind: 'text', id: 'rec-1', text: 'from the record' },
            { kind: 'tool', id: 'rec-2', call: toolCall('rec-2') },
          ],
        }),
        turn({ id: 'c', kind: 'agent', question: 'and now?' }),
      ],
      run: runView({
        turnId: 'c',
        items: [{ kind: 'tool', id: 'live-1', call: toolCall('live-1') }],
        prompts: [
          budgetPrompt(10, 2),
          { kind: 'approval', id: 'live-1', toolName: 'grep', risk: 'LOW' },
        ],
      }),
    });
    const conv = await layOut(el);

    const ids = stampedIds(conv);
    expect(ids.length).toBeGreaterThan(0);
    expect(new Set(ids).size).toBe(ids.length);
    // The sharp case: the tool card and the hold that names the same call are DIFFERENT anchors, so
    // the hold is reachable and the landmark list carries no duplicate. Stamping `prompt.id` bare
    // fails this line.
    expect(ids).toContain('live-1');
    expect(ids).toContain('live-1:hold');
  });

  it('emits one anchor per RENDERED item — a question with no text and one open for edit emit none', async () => {
    const el = await mount({
      turns: [
        turn({ id: 'plain' }),
        turn({ id: 'silent', question: '' }),
        turn({ id: 'editing' }),
      ],
      editingTurnId: 'editing',
    });
    const conv = await layOut(el);

    // NOT `2 × turns.length`: `question()` returns nothing for an empty question and returns the
    // editor for a turn being rewritten, so both of those turns contribute an answer anchor only.
    expect(stampedIds(conv)).toEqual(['plain:q', 'plain:a', 'silent:a', 'editing:a']);
  });
});

describe('857 PR-A — J/K stepping', () => {
  async function transcript(): Promise<Mounted> {
    const el = await mount({
      turns: [
        turn({ id: 'q1' }),
        turn({
          id: 'q2',
          kind: 'agent',
          question: 'go on',
          activity: [{ kind: 'note', id: 'step-1', label: 'Progress', text: 'working' }],
        }),
      ],
      run: null,
    });
    await layOut(el);
    return el;
  }

  it('j steps forward, k steps back, and any other key is ignored', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const ids = nav.landmarks.map((l) => l.id);
    expect(ids).toEqual(['q1:q', 'q1:a', 'q2:q', 'step-1']);

    // jumpTo is mocked → the focus never moves → both presses resolve from the SAME state, so a
    // forward press and a back press must pick different landmarks. That is what proves direction is
    // honoured, independently of whatever the derived focus happens to be. (The transcript is
    // scrolled to its end, so nothing is derived and the retiree's own index math sends a first `j`
    // to the head of the list and a first `k` to its tail.)
    press('j');
    expect(jumpTo).toHaveBeenLastCalledWith(ids[0]);
    press('k');
    expect(jumpTo).toHaveBeenLastCalledWith(ids[ids.length - 1]);
    expect(jumpTo).toHaveBeenCalledTimes(2);

    press('x');
    expect(jumpTo).toHaveBeenCalledTimes(2);
  });

  it('walks a mixed ask/agent transcript in DOM order, including a run’s held decision', async () => {
    const el = await mount({
      turns: [turn({ id: 'm1' }), turn({ id: 'm2', kind: 'agent', question: 'now delegate' })],
      run: runView({
        turnId: 'm2',
        items: [{ kind: 'text', id: 'p1', text: 'thinking' }],
        prompts: [budgetPrompt(9, 1)],
      }),
    });
    await layOut(el);
    const nav = navOf(el);
    const order = ['m1:q', 'm1:a', 'm2:q', 'p1', 'run-budget-gate:hold'];
    expect(nav.landmarks.map((l) => l.id)).toEqual(order);

    // Walk the whole spine forward with the REAL jumpTo, so each press moves the pinned focus and the
    // next press resolves against it — the property a mocked jumpTo cannot show. The transcript sits
    // at its end (it follows a streaming answer), so nothing is derived and the first press lands on
    // the head of the list; from there each press advances exactly one landmark.
    const visited: string[] = [];
    for (let i = 0; i < order.length; i++) {
      press('j');
      visited.push(nav.activeId);
    }
    expect(visited).toEqual(order);

    // …and back, ending on the first landmark.
    for (let i = 1; i < order.length; i++) press('k');
    expect(nav.activeId).toBe(order[0]);
  });

  it('859 live-leg: four presses that ORIGINATE on the focused step advance four landmarks', async () => {
    // THE DEFECT THIS FILE COULD NOT SEE. Every other case here dispatches at `window`, so the press
    // never traverses the scroller — and the scroller is where the authority listens for the gesture
    // that releases a pin. In a browser the second press onward originates on the landmark `jumpTo`
    // just focused (that focus move is the feature's whole accessibility payload), bubbles through
    // the scroller's `keydown` listener FIRST, and released the pin before this window's handler read
    // `activeId`. Every press then resolved from the scroll-derived position instead of from the last
    // jump, and the live walk oscillated between two adjacent landmarks instead of stepping the run.
    //
    // happy-dom lays nothing out, so the scroll never actually moves and the derived position is
    // always the head of the list: the defect degrades here from an oscillation into a STALL on
    // landmark 1, which is the same claim — four presses, four landmarks, in order.
    const el = await mount({
      turns: [turn({ id: 'w1' }), turn({ id: 'w2', kind: 'agent', question: 'keep going' })],
      run: runView({
        turnId: 'w2',
        items: [{ kind: 'text', id: 's1', text: 'thinking' }],
        prompts: [budgetPrompt(9, 1)],
      }),
    });
    await layOut(el);
    const nav = navOf(el);
    const order = ['w1:q', 'w1:a', 'w2:q', 's1', 'run-budget-gate:hold'];
    expect(nav.landmarks.map((l) => l.id)).toEqual(order);

    // `composed: true` so the press escapes this element's shadow root and still reaches the window
    // listener — the real path of a keypress made while a step inside the transcript has focus.
    const pressFromFocus = (key: string): void => {
      const target: EventTarget = (el.shadowRoot?.activeElement as HTMLElement | null) ?? window;
      target.dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true, composed: true, cancelable: true }),
      );
    };

    const visited: string[] = [];
    for (let i = 0; i < order.length; i++) {
      pressFromFocus('j');
      visited.push(nav.activeId);
    }
    expect(visited).toEqual(order);

    // …and the same path backwards, so the fix is not a forward-only accident.
    for (let i = 1; i < order.length; i++) pressFromFocus('k');
    expect(nav.activeId).toBe(order[0]);
  });

  it('859 follow-up: rows that lay out AFTER the first measure are walked with no re-render between', async () => {
    // THE DEFECT MEASURED LIVE AGAINST THE RUNNING STACK, post-#534. On a record load the plain HTML
    // of the transcript lays out synchronously, so the `hostUpdated` measure ran and committed
    // exactly those rows, while the `jf-reasoning-block` / `jf-tool-call-card` rows had not upgraded
    // yet, measured `rect.height === 0` and were skipped by the collapsed-trace rule. Nothing then
    // re-ran the measurement — their growth moves the scroller's scrollHeight but NOT the scroller's
    // own box, so the ResizeObserver is blind to it, and a finished load is followed by no render —
    // so a reader who pressed J walked a two-item list over a seven-row run. The browser probe read
    // `landmarks.length === 2` against 7 stamped rows with non-zero heights, and a direct `measure()`
    // immediately returned all 7.
    //
    // The presses below are deliberately NOT awaited: `jumpTo` requests an update, and letting that
    // render land would re-measure for a reason other than the one under test. Synchronous presses
    // are also the reader's real case — the run feed is idle, so nothing else is going to render.
    //
    // The late rows here are ordinary activity rows given a zero box for the first measure, not the
    // literal `jf-*` elements: happy-dom cannot upgrade-and-lay-out anything, and its ShadowRoot
    // cannot resolve `activeElement` on a shadow host (the limitation the S-2 case below records), so
    // walking the REAL `jumpTo` over component rows is not expressible here. What the controller sees
    // is identical either way — a row that measured zero and later has a box.
    const el = await mount({
      turns: [
        turn({ id: 'L1' }),
        turn({
          id: 'L2',
          kind: 'agent',
          question: 'keep going',
          activity: [
            { kind: 'text', id: 's1', text: 'reading the log' },
            { kind: 'note', id: 's2', label: 'Progress', text: 'two files left' },
          ],
        }),
      ],
      run: null,
    });
    const conv = scrollerOf(el);
    if (conv === null) throw new Error('no .scroller rendered — the transcript arm was not taken');
    let contentHeight = 0;
    conv.getBoundingClientRect = () =>
      ({ top: 0, left: 0, right: 800, bottom: 200, width: 800, height: 200, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 200 });
    Object.defineProperty(conv, 'scrollHeight', { configurable: true, get: () => contentHeight });

    const boxes = new Map<string, { top: number; height: number }>();
    for (const node of conv.querySelectorAll('[data-item-id]')) {
      const id = node.getAttribute('data-item-id') ?? '';
      boxes.set(id, { top: 0, height: 0 });
      (node as HTMLElement).getBoundingClientRect = () => {
        const b = boxes.get(id) ?? { top: 0, height: 0 };
        return { top: b.top, left: 0, right: 800, bottom: b.top + b.height, width: 800, height: b.height, x: 0, y: b.top, toJSON: () => ({}) } as DOMRect;
      };
    }
    /** Stack the rows top→bottom; anything not yet laid out has the zero box an un-upgraded row has. */
    const layOutRows = (isLaidOut: (id: string) => boolean): void => {
      let y = 0;
      for (const id of [...boxes.keys()]) {
        const height = isLaidOut(id) ? 60 : 0;
        boxes.set(id, { top: y, height });
        y += height;
      }
      contentHeight = y;
    };

    // First measure: only the plain-HTML rows have a box. The component rows measure zero, exactly as
    // they do before Lit upgrades them.
    const late = new Set(['s1', 's2']);
    layOutRows((id) => !late.has(id));
    const nav = navOf(el);
    for (let i = 0; i < 4 && nav.landmarks.length === 0; i++) {
      el.requestUpdate();
      await el.updateComplete;
      await new Promise((r) => setTimeout(r, 0));
    }
    expect(nav.landmarks.map((l) => l.id)).toEqual(['L1:q', 'L1:a', 'L2:q']);

    // …and now they lay out. The column grows; nothing renders and nothing resizes the scroller.
    layOutRows(() => true);

    const order = ['L1:q', 'L1:a', 'L2:q', 's1', 's2'];
    const visited: string[] = [];
    for (let i = 0; i < order.length; i++) {
      press('j');
      visited.push(nav.activeId);
    }
    // The reading window sits at the top of the column, so the first press advances from the DERIVED
    // focus (the first row) rather than from nothing; from there each press steps exactly one landmark
    // and the walk clamps on the last. A stale three-row list clamps on `L2:q` and never reaches
    // either late row, at any number of presses.
    expect(visited).toEqual(['L1:a', 'L2:q', 's1', 's2', 's2']);

    // …and the same walk backwards, so the freshened list is ordered rather than merely longer.
    for (let i = 1; i < order.length; i++) press('k');
    expect(nav.activeId).toBe(order[0]);
  });

  it('859 follow-up: J walks a transcript that FITS the viewport, where scrollHeight never moves', async () => {
    // THE REGIME THE DEFECT WAS ACTUALLY MEASURED IN. The live probe on the failing record read
    // `scrollHeight === clientHeight === 825`: the transcript fits, so by spec the scroll height is
    // pinned to the client height and does not move by a pixel when the rows lay out. Neither the
    // scroller-resize signal (a flex track with a fixed box) nor the content-height signal can see
    // that. The content WRAPPER's box is what grows, so the authority observes it — and this case
    // fails outright if that observation is dropped.
    //
    // happy-dom defines ResizeObserver but never fires it, so the callback is captured and fired by
    // hand; everything else here is the real component.
    const savedRO = (globalThis as Record<string, unknown>).ResizeObserver;
    const roInstances: Array<{ cb: () => void; els: Set<Element> }> = [];
    class CapturingResizeObserver {
      private readonly rec: { cb: () => void; els: Set<Element> };
      constructor(cb: () => void) {
        this.rec = { cb, els: new Set() };
        roInstances.push(this.rec);
      }
      observe(el: Element): void {
        this.rec.els.add(el);
      }
      disconnect(): void {
        this.rec.els.clear();
      }
      unobserve(el: Element): void {
        this.rec.els.delete(el);
      }
    }
    // Faithful on the point that matters: an element nobody observes delivers nothing, so dropping
    // the wrapper observation makes the WALK below fail, not merely a wiring assertion.
    const fireResizeFor = (el: Element): void => {
      for (const i of roInstances) if (i.els.has(el)) i.cb();
    };
    (globalThis as Record<string, unknown>).ResizeObserver = CapturingResizeObserver;
    try {
      const el = await mount({
        turns: [
          turn({ id: 'F1' }),
          turn({
            id: 'F2',
            kind: 'agent',
            question: 'keep going',
            activity: [
              { kind: 'text', id: 'f-s1', text: 'reading the log' },
              { kind: 'note', id: 'f-s2', label: 'Progress', text: 'two files left' },
            ],
          }),
        ],
        run: null,
      });
      const conv = scrollerOf(el);
      if (conv === null) throw new Error('no .scroller rendered — the transcript arm was not taken');
      const wrapper = el.shadowRoot?.querySelector('.transcript') as HTMLElement | null;
      if (wrapper === null) throw new Error('no .transcript wrapper rendered');

      // The scroller FITS its content and stays that way: both heights are pinned to 825 for the
      // whole case, so no growth signal can come from either of them.
      conv.getBoundingClientRect = () =>
        ({ top: 0, left: 0, right: 800, bottom: 825, width: 800, height: 825, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
      Object.defineProperty(conv, 'clientHeight', { configurable: true, value: 825 });
      Object.defineProperty(conv, 'scrollHeight', { configurable: true, value: 825 });

      let laidOut = false;
      const late = new Set(['f-s1', 'f-s2']);
      let y = 0;
      for (const node of conv.querySelectorAll('[data-item-id]')) {
        const id = node.getAttribute('data-item-id') ?? '';
        const top = y;
        y += 60;
        (node as HTMLElement).getBoundingClientRect = () => {
          const height = late.has(id) && !laidOut ? 0 : 60;
          return { top, left: 0, right: 800, bottom: top + height, width: 800, height, x: 0, y: top, toJSON: () => ({}) } as DOMRect;
        };
      }

      const nav = navOf(el);
      for (let i = 0; i < 4 && nav.landmarks.length === 0; i++) {
        el.requestUpdate();
        await el.updateComplete;
        await new Promise((r) => setTimeout(r, 0));
      }
      expect(nav.landmarks.map((l) => l.id)).toEqual(['F1:q', 'F1:a', 'F2:q']);
      expect(
        roInstances.flatMap((i) => [...i.els]),
        'the wrapper is observed, not only the scroller',
      ).toContain(wrapper);

      // Drain every OTHER measure source first — a queued render or a coalescer's trailing frame
      // would re-measure for a reason that is not the one under test, and this case passed under a
      // dropped-observation mutant until that window was closed.
      await el.updateComplete;
      await new Promise((r) => setTimeout(r, 0));
      await new Promise((r) => setTimeout(r, 0));

      // The rows lay out. Nothing renders; the content height is byte-for-byte what it was.
      laidOut = true;
      expect(conv.scrollHeight).toBe(conv.clientHeight);
      expect(nav.landmarks.map((l) => l.id), 'no height signal exists to see this').toEqual(['F1:q', 'F1:a', 'F2:q']);

      // …and the wrapper's box grows, which is the signal that does exist. Asserted with no await,
      // so nothing but this delivery can be what refreshed the list.
      fireResizeFor(wrapper);
      expect(nav.landmarks.map((l) => l.id)).toEqual(['F1:q', 'F1:a', 'F2:q', 'f-s1', 'f-s2']);

      const order = ['F1:q', 'F1:a', 'F2:q', 'f-s1', 'f-s2'];
      const visited: string[] = [];
      for (let i = 0; i < order.length; i++) {
        press('j');
        visited.push(nav.activeId);
      }
      // The column is not scrollable, so FOCUS derives to the topmost landmark and the first press
      // advances from it; the walk then steps one landmark per press and clamps on the last.
      expect(visited).toEqual(['F1:a', 'F2:q', 'f-s1', 'f-s2', 'f-s2']);
    } finally {
      (globalThis as Record<string, unknown>).ResizeObserver = savedRO;
    }
  });

  it('S-2 (859 §A §1.8): J walks reasoning and tools in TRUE run order', async () => {
    // 857's named side benefit, bought with one attribute: an inline reasoning row carries a
    // `data-item-id`, so it becomes a landmark for free — and the walk is the run's real chronology
    // rather than "the tools, then whatever the stack above them was".
    const el = await mount({
      turns: [
        turn({
          id: 'r1',
          kind: 'agent',
          question: 'index the vendor folder',
          activity: [
            { kind: 'reasoning', id: 'c1:think:0', text: 'search first', durationMs: 900, streaming: false },
            { kind: 'tool', id: 'c1', call: { callId: 'c1', toolName: 'core_search', arguments: '{}', risk: 'LOW', status: 'completed' } },
            { kind: 'reasoning', id: 'c2:think:0', text: 'now read it', durationMs: 400, streaming: false },
            { kind: 'tool', id: 'c2', call: { callId: 'c2', toolName: 'core_read', arguments: '{}', risk: 'LOW', status: 'completed' } },
          ],
        }),
      ],
      run: null,
    });
    await layOut(el);
    const nav = navOf(el);
    const order = ['r1:q', 'c1:think:0', 'c1', 'c2:think:0', 'c2'];
    // The landmark list IS the walk order (`navigation.ts` collects `[data-item-id]` in DOM order),
    // so this is the claim: a thought is reachable, and it is reachable BEFORE the step it produced.
    expect(nav.landmarks.map((l) => l.id)).toEqual(order);

    // The real jumpTo cannot be walked here: it moves DOM focus onto the landmark, and every row in
    // this fixture is a shadow HOST, which happy-dom's `ShadowRoot.activeElement` cannot resolve.
    // The forward-walk property itself is pinned on non-host landmarks by the mixed-transcript case
    // above; what is new here is the ORDER, asserted directly, plus that j resolves into this list.
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j');
    expect(jumpTo).toHaveBeenLastCalledWith(order[0]);
    press('k');
    expect(jumpTo).toHaveBeenLastCalledWith(order[order.length - 1]);
  });

  it('clamps at both ends — no wrap', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const last = nav.landmarks[nav.landmarks.length - 1]!.id;
    const first = nav.landmarks[0]!.id;

    for (let i = 0; i < nav.landmarks.length + 2; i++) press('j');
    expect(nav.activeId).toBe(last);
    for (let i = 0; i < nav.landmarks.length + 2; i++) press('k');
    expect(nav.activeId).toBe(first);
  });

  it('moves real DOM focus to the step it jumps to (the whole accessibility payload)', async () => {
    const el = await transcript();
    const nav = navOf(el);
    press('j');
    const focused = el.shadowRoot?.activeElement as HTMLElement | null;
    expect(focused?.getAttribute('data-item-id')).toBe(nav.activeId);
  });

  it('works at a narrow width — the deliberate divergence from the retiree’s wideZone gate', async () => {
    const el = await transcript();
    el.getBoundingClientRect = () =>
      ({ top: 0, left: 0, right: 380, bottom: 600, width: 380, height: 600, x: 0, y: 0, toJSON: () => ({}) }) as DOMRect;
    await el.updateComplete;
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j');
    // The retiree gates this navigation on a wide viewport because its MINIMAP needs a gutter; this
    // window has no gutter, so gating would leave a narrow reader with no keyboard nav at all.
    expect(jumpTo).toHaveBeenCalledTimes(1);
  });

  it('removes the window listener on disconnect (no leak after the region is gone)', async () => {
    const el = await transcript();
    const nav = navOf(el);
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    el.remove();
    press('j');
    expect(jumpTo).not.toHaveBeenCalled();
  });

  it('no-ops with nothing measured rather than throwing (the record-notice-only transcript)', async () => {
    const el = await mount({ turns: [], recordNotice: true });
    expect(scrollerOf(el)).not.toBeNull(); // the transcript arm IS rendered, so active() is true
    expect(navOf(el).landmarks).toHaveLength(0);
    expect(() => press('j')).not.toThrow();
  });
});

describe('857 PR-A — the guards', () => {
  async function transcript(): Promise<{ el: Mounted; nav: NavigationController }> {
    const el = await mount({ turns: [turn({ id: 'g1' }), turn({ id: 'g2' })] });
    await layOut(el);
    return { el, nav: navOf(el) };
  }

  it('ignores a j/k an inner handler already claimed (the advisory-drawer collision)', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // Faithful to `components/advisory/AdvisoryInboxDrawer.ts:379-386`: a row-scoped bare-`j`
    // handler that calls preventDefault() but NOT stopPropagation(), on a `role="button"` element
    // the typing guard has no reason to stop. The drawer is mounted app-wide (`chrome/Shell.ts:2384`),
    // so its keys reach this window's listener by bubbling.
    const row = document.createElement('div');
    row.setAttribute('role', 'button');
    row.tabIndex = 0;
    row.addEventListener('keydown', (e) => {
      if ((e as KeyboardEvent).key === 'j' || (e as KeyboardEvent).key === 'k') e.preventDefault();
    });
    document.body.appendChild(row);

    row.dispatchEvent(new KeyboardEvent('keydown', { key: 'j', bubbles: true, cancelable: true }));
    expect(jumpTo).not.toHaveBeenCalled();

    // The same press with nothing claiming it still navigates — so the assertion above is the guard,
    // not a dead listener.
    press('j');
    expect(jumpTo).toHaveBeenCalledTimes(1);
  });

  it('never hijacks typing — input, textarea, select and contentEditable all block it', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    for (const tag of ['input', 'textarea', 'select'] as const) {
      const field = document.createElement(tag);
      document.body.appendChild(field);
      field.focus();
      expect(document.activeElement).toBe(field);
      press('j');
      expect(jumpTo).not.toHaveBeenCalled();
      field.remove();
    }
    const editable = document.createElement('div');
    editable.tabIndex = 0;
    Object.defineProperty(editable, 'isContentEditable', { configurable: true, value: true });
    document.body.appendChild(editable);
    editable.focus();
    press('j');
    expect(jumpTo).not.toHaveBeenCalled();
    editable.remove();
  });

  it('never hijacks typing — descends nested shadow roots to the truly-focused editable', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    // The composer's textarea lives two shadow roots down; a bare `document.activeElement` check
    // stops at the host and reports a non-editable custom element.
    const innerTextarea = { tagName: 'TEXTAREA', isContentEditable: false, shadowRoot: null };
    const host = { shadowRoot: { activeElement: innerTextarea }, tagName: 'JF-SV3-COMPOSER' };
    Object.defineProperty(document, 'activeElement', { configurable: true, get: () => host });
    try {
      press('j');
      expect(jumpTo).not.toHaveBeenCalled();
    } finally {
      delete (document as unknown as Record<string, unknown>).activeElement;
    }
  });

  it('does not consume j/k once the transcript arm is gone, even with landmarks still measured', async () => {
    // The landmark list survives its arm on purpose: `teardown()` releases the observer, listeners,
    // pin and viewport but keeps `landmarks`/`fractions`/`trackPx`. So a stale non-empty list is the
    // normal state after a transcript→locked transition, and a handler that gated only on
    // `landmarks.length` would call `preventDefault()` on a key it then cannot act on — `jumpTo`
    // bails because the locked arm renders no `.scroller`. The key must reach whatever else wants it.
    const { el, nav } = await transcript();
    expect(nav.landmarks.length).toBeGreaterThan(0);

    el.historyLocked = true;
    await el.updateComplete;
    expect(scrollerOf(el)).toBeNull(); // the locked arm replaced the transcript
    expect(nav.landmarks.length, 'the list is stale-but-kept — the premise of this case').toBeGreaterThan(0);

    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const raised = new KeyboardEvent('keydown', { key: 'j', bubbles: true, cancelable: true });
    window.dispatchEvent(raised);
    expect(jumpTo).not.toHaveBeenCalled();
    expect(raised.defaultPrevented, 'the key was swallowed by a transcript that is not on screen').toBe(false);
  });

  it('ignores modified chords — Ctrl/⌘/Alt/Shift + j belong to the browser and to the palette', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j', { ctrlKey: true });
    press('j', { metaKey: true });
    press('j', { altKey: true });
    press('j', { shiftKey: true });
    expect(jumpTo).not.toHaveBeenCalled();
  });

  /**
   * Tempdoc 864 Layer 2(b) — IME composition and auto-repeat, through the ONE shared helper. Both
   * are asserted beside a press that still navigates, so a guard that simply swallowed every key
   * would fail the second half.
   */
  it('864: ignores an IME-composing press and an auto-repeat press', async () => {
    const { nav } = await transcript();
    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    press('j', { isComposing: true });
    expect(jumpTo, 'a keystroke feeding an IME candidate window is not a shortcut').not.toHaveBeenCalled();
    press('j', { repeat: true });
    expect(jumpTo, 'a held key is one intent, not N').not.toHaveBeenCalled();
    press('j');
    expect(jumpTo).toHaveBeenCalledTimes(1);
  });

  /**
   * Tempdoc 864 Layer 2(d) — THE L10 defect, reproduced against the REAL palette (2026-08-25 live
   * session: palette open, focus on a control inside the popup, `j` scrolled the transcript
   * underneath it from 624 to 0). Focus is deliberately parked on a NON-editable element, because
   * that is the whole point: every pre-existing guard passes there — the press is unclaimed, it
   * carries no modifier, and a `role="option"` row is not a typing target — so only a guard that
   * asks "does a modal own the keyboard?" can stop it. The same press after `hide()` navigates, so
   * this is the modal guard and not a dead listener.
   */
  it('864 L10: stands down while the command palette is open, wherever focus sits', async () => {
    const { nav } = await transcript();
    // The app's one palette reads its providers while open; those reads answer empty here.
    const reads = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => Response.json({}));
    const palette = document.createElement('jf-command-palette') as CommandPalette;
    document.body.appendChild(palette);
    palette.show();
    await palette.updateComplete;

    const row = palette.shadowRoot?.querySelector<HTMLElement>('[role="option"]');
    if (!row) throw new Error('no palette row rendered — the case cannot park focus inside the popup');
    row.tabIndex = -1;
    row.focus();
    expect(isTypingTarget(deepActiveElement()), 'the pre-existing typing guard does NOT cover this').toBe(
      false,
    );

    const jumpTo = vi.spyOn(nav, 'jumpTo').mockImplementation(() => {});
    const underAModal = new KeyboardEvent('keydown', { key: 'j', bubbles: true, cancelable: true });
    window.dispatchEvent(underAModal);
    expect(jumpTo, 'j reached the transcript under an open palette').not.toHaveBeenCalled();
    expect(underAModal.defaultPrevented, 'the key belongs to the modal, unswallowed').toBe(false);

    palette.hide();
    press('j');
    expect(jumpTo, 'the guard released with the modal').toHaveBeenCalledTimes(1);
    palette.remove();
    reads.mockRestore();
  });
  // Tempdoc 864 review F2 (the indexing overlay as a second modal) retired with that overlay in
  // the frame status slice: indexing progress moved into the non-modal status panel. The modality
  // guard itself stays pinned by the palette case above.
});

describe('857 PR-A — active() is host state, and false in every non-transcript arm', () => {
  const armed = (el: Mounted): boolean =>
    (el as unknown as { transcriptArmRendered: boolean }).transcriptArmRendered;

  it('is true only where the transcript renders — the hero, search and locked arms are all false', async () => {
    const hero = await mount({ state: 'docked' });
    expect(armed(hero)).toBe(false);

    const withTurns = await mount({ turns: [turn({ id: 'x' })] });
    expect(armed(withTurns)).toBe(true);

    // The LOCK wins over the transcript (`render()` takes the locked arm first), and that arm has no
    // `.scroller` at all. An `active()` that read only "there are turns" would stay true here, the
    // controller would never tear down, and it would hold listeners on a detached node.
    const locked = await mount({ turns: [turn({ id: 'x' })], historyLocked: true });
    expect(armed(locked)).toBe(false);
    expect(scrollerOf(locked)).toBeNull();
  });

  it('is answerable before any measurement — it never reads the landmark list', async () => {
    // The deadlock this closes: `landmarks` populate only inside `measure()`, which runs only when
    // `active()` is already true, so an `active()` derived from `landmarks.length` can never become
    // true. Here the arm is armed while the landmark list is still empty.
    const el = await mount({ turns: [turn({ id: 'pre' })] });
    expect(navOf(el).landmarks).toHaveLength(0);
    expect(armed(el)).toBe(true);
  });
});
