// @vitest-environment happy-dom

/**
 * Search v3's FOCUS AUTHORITY (tempdoc 864 Layer 1) — the window owns where the caret is.
 *
 * The defect these cases exist for is not a keybinding: nothing on this surface ever focused its own
 * primary input, so `<body>` kept focus under a glass box that looks primed, and the reader's typing
 * went wherever focus happened to be parked — including a row button whose bare `Space` swaps the
 * conversation (§2.7b). Every case here is written so that it FAILS on the pre-fix window:
 *
 *  - the entry-path cases move focus somewhere else FIRST (the row button, the new-session control,
 *    an input outside the window), so a pass cannot come from focus merely never having left;
 *  - the dead-zone cases press the padding and the glass — the two places §2.9(b) measured as
 *    click-dead — and then press a real control, so "focus everything" would fail the third;
 *  - the ring case asserts the ring is still the platform's `:focus-visible`, so a fix that painted
 *    a focused ring for programmatic focus would fail.
 *
 * The authorities are the real ones (the conversation store, the per-tab pointer), stubbed only at
 * their single exit, `fetch` — the same shape `SearchV3View.record.test.ts` uses.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { Sv3Composer } from './Sv3Composer.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';
// Tempdoc 859 — the app-global focus ring the composer's local reset has to take the field back FROM,
// and the base class that carries it into every shadow root.
import { ambientStyles } from '../../primitives/ambientStyles.js';
import { JfElement } from '../../primitives/JfElement.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };
type Updatable = HTMLElement & { updateComplete: Promise<unknown> };

let fetchMock: ReturnType<typeof vi.fn>;
let conversations: Array<Record<string, unknown>>;

const LAST_VIEWED_KEY = 'justsearch.lastViewedConversation.v1';

function row(id: string, question: string): Record<string, unknown> {
  return {
    sessionId: id,
    createdAtMs: 1,
    lastActiveAtMs: 2,
    messageCount: 2,
    firstUserMessage: question,
    shapeId: 'core.rag-ask',
  };
}

function stubFetch(): void {
  fetchMock.mockImplementation(async (url: unknown) => {
    const href = String(url);
    if (href.includes('/api/chat/runs/live')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ runs: [] }) };
    }
    if (href.includes('/api/chat/conversations')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ sessions: conversations }) };
    }
    if (href.includes('/api/thread/')) {
      return { ok: true, status: 200, json: () => Promise.resolve({ conversationId: '', events: [] }) };
    }
    return { ok: true, status: 200, json: () => Promise.resolve({ results: [] }) };
  });
}

/**
 * The observed state in which the composer is genuinely usable. A model id is fed as well as the
 * online verdict, because the footer's model LABEL only renders when there is one — and that label is
 * the selectable text the dead-zone press must leave alone.
 */
function aiOnline(): void {
  __feedForTest({
    inference: {
      mode: 'online',
      available: true,
      activeModelId: 'Qwen_Qwen3.5-9B.Q4_K_M.gguf',
    } as never,
    status: { worker: { core: { indexedDocuments: 42 } } } as unknown as StatusSnapshot,
  });
  __feedContactForTest();
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  conversations = [];
  fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  stubFetch();
  aiOnline();
});

afterEach(() => {
  for (const el of [...document.querySelectorAll('jf-sv3-window')]) el.remove();
  for (const el of [...document.querySelectorAll('input.outsider')]) el.remove();
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

async function settle(el: Mounted): Promise<void> {
  for (let turn = 0; turn < 8; turn += 1) await new Promise<void>((r) => setTimeout(r, 0));
  await el.updateComplete;
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  await settle(el);
  return el;
}

async function region(el: Mounted, tag: string): Promise<Updatable> {
  const found = el.shadowRoot?.querySelector(tag) as Updatable | null;
  if (found === null) throw new Error(`no <${tag}> in the window`);
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

/** Park focus where the pre-fix window left it, so a pass cannot come from focus never moving. */
function park(): HTMLInputElement {
  const outsider = document.createElement('input');
  outsider.className = 'outsider';
  document.body.appendChild(outsider);
  outsider.focus();
  return outsider;
}

function blurEverything(): void {
  (deepActiveElement() as HTMLElement | null)?.blur();
}

/**
 * A component's OWN stylesheet — the last entry in `static styles`, after the shared sheets it
 * adopts (the `sv3-tokens.test.ts` convention). Reading the whole array would let a declaration made
 * in the shared token sheet satisfy a claim about this component's own rules.
 */
const ownStyleTextOf = (ctor: { styles?: unknown }): string => {
  const sheets = ctor.styles as ReadonlyArray<{ cssText: string }>;
  return sheets[sheets.length - 1]?.cssText ?? '';
};

const composerSheet = (): string => ownStyleTextOf(Sv3Composer);

/** `[selector, declarationBlock]` per rule, selectors whitespace-normalised (the #539 idiom). */
const rulesOf = (cssText: string): Array<[string, string]> => {
  const stripped = cssText.replace(/\/\*[\s\S]*?\*\//g, '');
  return [...stripped.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(
    (m) =>
      [(m[1] as string).replace(/\s+/g, ' ').trim(), (m[2] as string).trim()] as [string, string],
  );
};

async function rowButtons(el: Mounted): Promise<HTMLButtonElement[]> {
  const sidebar = await region(el, 'jf-sv3-sidebar');
  const rows = [...(sidebar.shadowRoot?.querySelectorAll('jf-sv3-session-row') ?? [])] as Updatable[];
  await Promise.all(rows.map((r) => r.updateComplete));
  return rows.flatMap((r) => {
    const button = r.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-session-row-button"]',
    );
    return button === null || button === undefined ? [] : [button];
  });
}

describe('tempdoc 864 Layer 1(a) — every entry lands the reader in the composer', () => {
  it('a fresh window focuses its own field instead of leaving <body> focused', async () => {
    const el = await mount();
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('a restored record lands in the composer, not on nothing', async () => {
    // The per-tab pointer is the record-load entry (tempdoc 609 Phase 3): the window claims the
    // conversation during connect and the reader arrives at a docked, ready composer.
    sessionStorage.setItem(LAST_VIEWED_KEY, 'conv-restored');
    conversations = [row('conv-restored', 'why did the renewal fail?')];
    const el = await mount();
    // Docked proves the RECORD path ran, not merely that a hero mounted.
    expect(el.composerState).toBe('docked');
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('claiming a conversation takes focus OFF the row button and puts it in the composer', async () => {
    conversations = [row('conv-a', 'first question'), row('conv-b', 'second question')];
    const el = await mount();
    const [first] = await rowButtons(el);
    if (first === undefined) throw new Error('no session rows');
    // A real click focuses the control it lands on — the state §2.7b turns into a conversation swap
    // on the reader's next `Space`.
    first.focus();
    expect(deepActiveElement()).toBe(first);
    first.click();
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('starting a new session moves focus off the new-session control and into the composer', async () => {
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const newButton = sidebar.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-sidebar-new"]',
    );
    if (!newButton) throw new Error('no new-session control');
    newButton.focus();
    expect(deepActiveElement()).toBe(newButton);
    newButton.click();
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('does NOT steal focus from a reader who is already typing somewhere else', async () => {
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const outsider = park();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(
      new CustomEvent('sv3-session-new', { bubbles: true, composed: true }),
    );
    await settle(el);
    expect(deepActiveElement()).toBe(outsider);
  });

  it('yields to a rename in progress across an unmount and re-entry', async () => {
    // The window is RETAINED across a surface switch and `renamingId` survives it, so re-entry is an
    // entry path that can land on a half-finished rename — with focus wherever the unmount left it.
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(
      new CustomEvent('sv3-session-rename', {
        detail: { id: 'conv-a', phase: 'start', title: null },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    el.remove();
    (deepActiveElement() as HTMLElement | null)?.blur();
    // A TASK boundary between the unmount and the re-entry, or this case would pass for the wrong
    // reason: PR E reads a same-task disconnect→connect as a re-parent and skips the focus move
    // outright, so a synchronous re-append satisfies the assertion without the rename refusal ever
    // being consulted.
    await new Promise<void>((r) => setTimeout(r, 0));
    document.body.appendChild(el);
    await settle(el);
    expect(deepActiveElement()).not.toBe(await fieldOf(el));
  });
});

/**
 * Tempdoc 864 PR E — the residual PR A's review found and deliberately left: `connectedCallback` is
 * the entry signal, and the split-view toggle routes a NON-entry through it. The stage re-templates
 * when the split opens (`Shell.ts` `render()` — the surface moves from the single-pane slot into
 * `.split > .pane`), so the retained element is removed and re-inserted with no entry having
 * happened, and the caret jumped into the composer on a layout gesture made for something else.
 *
 * Both halves are asserted, because the guard is only right if the entry it is NOT meant to catch
 * still fires.
 */
describe('tempdoc 864 PR E — a re-parent is not an entry', () => {
  it('a split-view re-parent leaves focus on the control the reader pressed', async () => {
    const el = await mount();
    // The toggle is chrome OUTSIDE this window, so it keeps focus across the re-template — and it is
    // a BUTTON, not a typing target, so the entry path's own typing guard cannot be what saves it.
    // That is the point of the fixture: on the pre-PR-E window this assertion fails.
    const toggle = document.createElement('button');
    toggle.className = 'outsider-toggle';
    document.body.appendChild(toggle);
    toggle.focus();
    // What the stage does to the surface: same node, new parent, ONE synchronous update.
    const pane = document.createElement('div');
    pane.className = 'pane';
    el.remove();
    pane.appendChild(el);
    document.body.appendChild(pane);
    await settle(el);
    expect(deepActiveElement()).toBe(toggle);
    expect(deepActiveElement()).not.toBe(await fieldOf(el));
    pane.remove();
    toggle.remove();
  });

  it('a genuine re-entry to the retained window still lands in the composer', async () => {
    const el = await mount();
    el.remove();
    blurEverything();
    // The task boundary is the whole discriminator: a re-entry needs a user event, which is a later
    // task by construction, while Lit's re-parent finishes inside one synchronous update.
    await new Promise<void>((r) => setTimeout(r, 0));
    document.body.appendChild(el);
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });
});

describe('tempdoc 864 Layer 1(b) — the whole glass box is the field', () => {
  it('a press on the field padding focuses the textarea', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    expect(deepActiveElement()).not.toBe(field);
    const padding = composer.shadowRoot?.querySelector('.field');
    if (!padding) throw new Error('no .field');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    padding.dispatchEvent(press);
    expect(deepActiveElement()).toBe(field);
    // The press's own default IS the focus move this replaces, so it must not also run.
    expect(press.defaultPrevented).toBe(true);
  });

  it('a press on the glass — the ring the box is framed by — focuses the textarea', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const glass = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-shell"]');
    if (!glass) throw new Error('no .glass');
    glass.dispatchEvent(new Event('pointerdown', { bubbles: true, composed: true, cancelable: true }));
    expect(deepActiveElement()).toBe(field);
  });

  it('a press on a footer control is left alone — the control keeps its own press', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const control = composer.shadowRoot?.querySelector<HTMLButtonElement>(
      '[data-testid="sv3-composer-tier"]',
    );
    if (!control) throw new Error('no mode control');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    control.dispatchEvent(press);
    expect(deepActiveElement()).not.toBe(field);
    expect(press.defaultPrevented).toBe(false);
  });

  it('a press on a jf-control in the band is left alone — its host padding retargets no further', async () => {
    // The band holds plain `<button>`s today and `jf-control` tomorrow (it is the product's one
    // operability primitive). A press on the CONTROL'S OWN padding surfaces the host in the composed
    // path, and an upward walk from a host finds no `<button>` — so without the host in the bail set
    // the press would be eaten and the control would silently stop working.
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const glass = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-shell"]');
    if (!glass) throw new Error('no .glass');
    const control = document.createElement('jf-control');
    glass.appendChild(control);
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    control.dispatchEvent(press);
    expect(deepActiveElement()).not.toBe(field);
    expect(press.defaultPrevented).toBe(false);
  });

  it('a press on the model label is left alone, so its text stays selectable', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = await fieldOf(el);
    blurEverything();
    const label = composer.shadowRoot?.querySelector('[data-testid="sv3-composer-model"]');
    if (!label) throw new Error('no model label — the fixture must report a model');
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    label.dispatchEvent(press);
    expect(press.defaultPrevented).toBe(false);
    expect(deepActiveElement()).not.toBe(field);
  });

  it('a press on the textarea itself is left alone, so caret placement and selection still work', async () => {
    const el = await mount();
    const field = await fieldOf(el);
    const press = new Event('pointerdown', { bubbles: true, composed: true, cancelable: true });
    field.dispatchEvent(press);
    expect(press.defaultPrevented).toBe(false);
  });
});

describe('tempdoc 864 Layer 1(c2), PR B — jf-focus-composer reaches sv3', () => {
  it('the command path (shell.focus-composer dispatches jf-focus-composer, chrome/Shell.ts) lands deep focus in the composer field', async () => {
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const [first] = await rowButtons(el);
    if (first === undefined) throw new Error('no session rows');
    // Park focus on the armed row button — exactly §2.7b's hazard state, and the case the shortcut
    // exists to recover from.
    first.focus();
    expect(deepActiveElement()).toBe(first);
    window.dispatchEvent(new CustomEvent('jf-focus-composer'));
    await settle(el);
    expect(deepActiveElement()).toBe(await fieldOf(el));
  });

  it('honors the refusal set — a reader typing elsewhere keeps their focus, no steal', async () => {
    const el = await mount();
    const outsider = park();
    window.dispatchEvent(new CustomEvent('jf-focus-composer'));
    await settle(el);
    expect(deepActiveElement()).toBe(outsider);
  });

  it('honors the refusal set — arriving mid-rename (renamingId survives a remount) does not steal focus', async () => {
    // Focus after the remount is <body> (not a typing target), so this isolates the renamingId
    // refusal specifically — the typing guard alone would not have blocked it here.
    conversations = [row('conv-a', 'first question')];
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    sidebar.dispatchEvent(
      new CustomEvent('sv3-session-rename', {
        detail: { id: 'conv-a', phase: 'start', title: null },
        bubbles: true,
        composed: true,
      }),
    );
    await settle(el);
    el.remove();
    (deepActiveElement() as HTMLElement | null)?.blur();
    document.body.appendChild(el);
    await settle(el);
    window.dispatchEvent(new CustomEvent('jf-focus-composer'));
    await settle(el);
    expect(deepActiveElement()).not.toBe(await fieldOf(el));
  });

  it('unsubscribes on disconnect — the event does nothing once the window is torn down (no leak)', async () => {
    const el = await mount();
    const outsider = park();
    el.remove();
    await settle(el);
    window.dispatchEvent(new CustomEvent('jf-focus-composer'));
    await settle(el);
    expect(deepActiveElement()).toBe(outsider);
  });
});

describe('tempdoc 864 — the focus ring stays the platform’s own', () => {
  it('keys the ring on :focus-visible only, so programmatic focus paints no ring the reader did not ask for', () => {
    // The ring rules moved onto the wrapper in PR E (`.glass:has(…)::after`), for the reachability
    // reason recorded there; what they may NOT do is stop being the platform's own pseudo-class.
    const ringRules = rulesOf(composerSheet()).filter(
      ([selector]) => selector.includes('::after') && selector.includes(':has(textarea'),
    );
    expect(ringRules.length).toBeGreaterThan(0);
    for (const [selector, block] of ringRules) {
      const paintsFocus = /outline|border-color: var\(--ring\)/.test(block);
      if (!paintsFocus) continue;
      expect(selector, `${selector} paints focus without the platform pseudo-class`).toContain(
        ':focus-visible',
      );
    }
  });
});

/**
 * Tempdoc 859 (live audit 2026-08-25) — the composer painted TWO rings on a keyboard focus: the
 * wrapper's own teal `--ring` frame, and an amber 2px/offset-2px outline on the `<textarea>` itself,
 * from the app-global `:focus-visible` rule in `primitives/ambientStyles.ts`. That rule is a
 * CLASS-B ambient facet adopted into every shadow root by `JfElement`, so it reaches inside this
 * component — and at (0,1,0) it out-specifies the bare-type `textarea { outline: none }` reset. Two
 * competing outlines is the exact thing `Sv3Composer`'s own contract says it does not do: "the field
 * itself stays unstyled and every state is read off the wrapper … one ring rather than two".
 *
 * The fix (870 item 2) restates the reset ON the pseudo-class — (0,1,1), and later in the cascade —
 * so it takes the field back HERE without touching the ambient rule, which every other focusable
 * surface in the app still needs. These cases pin both halves: this composer paints one ring, and
 * the ambient rule is still there for everything else.
 *
 * The measured half is the live audit. happy-dom applies no stylesheets and computes no cascade, so
 * `getComputedStyle(textarea).outlineStyle` reads the same '' whether the reset is present or
 * deleted — an assertion on it would pass on the two-ring window too. What IS decidable here is the
 * rendered structure plus the cascade inputs, so that is what is asserted, on a really-focused field.
 */
describe('859: the focused composer paints ONE ring, and the ambient rule survives for everyone else', () => {
  const specificityOfFocusVisible = (selector: string): [number, number, number] => {
    // Enough for these two selectors: pseudo-classes count as classes, type selectors as types.
    const classes = (selector.match(/:[a-z-]+(?:\([^)]*\))?/g) ?? []).length;
    const types = (selector.match(/(^|\s|>|\+|~)[a-z][a-z0-9-]*/gi) ?? []).length;
    return [0, classes, types];
  };

  /** Every non-`none` `outline` this declaration block sets. */
  const outlinesPaintedBy = (block: string): string[] =>
    [...block.matchAll(/outline:\s*([^;]+)/g)]
      .map((m) => (m[1] as string).trim())
      .filter((value) => value !== 'none');

  it('the ambient ring really is in scope inside the composer (the precondition of the bug)', () => {
    // Anti-vacuity, and the reason the local reset has to exist at all: `JfElement.finalizeStyles`
    // prepends the ambient sheet to EVERY component's styles, so the rule is inside this shadow root
    // and applies to the shadow `<textarea>`. If the composer ever stopped composing that base, the
    // reset below would become dead code rather than a fix.
    expect(Sv3Composer.prototype instanceof JfElement).toBe(true);
    // …and the ambient rule is UNTOUCHED. The composer may not close its own double ring by deleting
    // the app-global one — every other focusable surface in the app is the reason that rule exists.
    expect(ambientStyles.cssText.replace(/\s+/g, ' ')).toContain(
      ':focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: 2px; }',
    );
  });

  it('the composer’s own reset out-specifies the ambient rule AND comes later', () => {
    const reset = rulesOf(composerSheet()).find(([s]) => s === 'textarea:focus-visible');
    expect(
      reset,
      'the local reset is gone — the field would paint the ambient ring again',
    ).toBeDefined();
    expect(reset?.[1]).toContain('outline: none');
    // Specificity: (0,1,1) beats the ambient (0,1,0)…
    expect(specificityOfFocusVisible('textarea:focus-visible')).toEqual([0, 1, 1]);
    expect(specificityOfFocusVisible(':focus-visible')).toEqual([0, 1, 0]);
    // …and it does NOT rely on order alone: the bare-type reset that lost this fight is still in the
    // sheet, later than the ambient rule, and it lost anyway — which is the whole reason a
    // pseudo-class restatement was needed. So the specificity is the load-bearing half.
    expect(rulesOf(composerSheet()).find(([s]) => s === 'textarea')?.[1]).toContain('outline: none');
    expect(specificityOfFocusVisible('textarea')).toEqual([0, 0, 1]);
  });

  it('a really-focused field carries exactly one ring authority, and it is the wrapper’s', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector('textarea') as HTMLTextAreaElement;
    expect(field, 'the composer rendered no field to focus').not.toBeNull();
    field.focus();
    await settle(el);
    expect(deepActiveElement(), 'the fixture never actually focused the field').toBe(field);

    // ONE ring, on the rendered element that is actually focused: the only rules in this component
    // that PAINT a focus mark reachable from a focused field are the wrapper's frame; every rule
    // keyed on the field itself only ever removes an outline. A second painter here is the two-ring
    // regression, whatever colour it used.
    const fieldPainters = rulesOf(composerSheet()).filter(
      ([selector, block]) =>
        /^textarea(:|\s|$)/.test(selector) && outlinesPaintedBy(block).length > 0,
    );
    expect(fieldPainters.map(([selector]) => selector)).toEqual([]);
    // …and the wrapper still carries one, so this is not "no indication".
    expect(
      rulesOf(composerSheet()).filter(
        ([selector, block]) =>
          selector.includes(':focus-visible') && /border-color:\s*var\(--ring\)/.test(block),
      ),
    ).toHaveLength(1);
    el.remove();
  });
});

/**
 * Tempdoc 864 Layer 1(d) — THE RESTING-STATE AFFORDANCE, as style text.
 *
 * happy-dom runs no cascade and resolves no `color-mix`, so there is no honest computed colour to
 * read out of a shadow tree here; the DECLARATIONS are the mechanism and they are what these pin
 * (the posture `sv3-tokens.test.ts`'s a11y-floor block already takes, with the measured proof left
 * to the live audit). Each case fails on the pre-PR-E sheet, which carried no "not focused"
 * declaration at all.
 */
describe('tempdoc 864 Layer 1(d) — the resting composer says it is not focused', () => {
  it('declares the knob at REST and spends it on the field’s own focus, not on a state flag', () => {
    const writers = rulesOf(composerSheet()).filter(([, block]) =>
      /--composer-rest\s*:/.test(block),
    );
    // Exactly two writers: the resting default and the one rule that lifts it. A third would be a
    // second authority for "is this composer the reader's" — and a `[state=…]` writer would put the
    // affordance back on a flag, which §2.9 is explicit the honest ring never was.
    expect(writers.map(([selector]) => selector)).toEqual([
      '.glass',
      '.glass:has(textarea:focus)',
    ]);
    expect(writers[0]?.[1]).toContain('--composer-rest: 1');
    expect(writers[1]?.[1]).toContain('--composer-rest: 0');
  });

  it('lifts on the real pseudo-class, and never through :host(:has(…)) — 822 F3', () => {
    // `:host(:has(…))` is the shape 822 F3 measured as a Chrome syntax error that takes its whole
    // selector list down with it, and `:host()` matches its argument in the OUTER tree, where this
    // shadow textarea does not live. Either way the rule would be unreachable — silently. Comments
    // are stripped: the prose above the re-keyed rules names the shape it replaced.
    expect(composerSheet().replace(/\/\*[\s\S]*?\*\//g, '')).not.toContain(':host(:has(');
    // Not `:focus-visible` for the de-emphasis: a reader who CLICKED into the field is typing there
    // too, and a box that stayed de-emphasised for them would be lying.
    expect(composerSheet()).toContain('.glass:has(textarea:focus) {');
  });

  it('derives every resting declaration from the one knob, in the color-mix idiom', () => {
    const derived = rulesOf(composerSheet()).filter(([, block]) =>
      /var\(--composer-rest\)/.test(block),
    );
    // The MATERIAL, and only the material. 864 Layer 1(d) shipped two derivations — the surface and
    // the frame's alpha — and the owner decision of 2026-09-07 (tempdoc 948) removed the frame half:
    // it was the one spending NON-text contrast, and it put the resting boundary under WCAG 1.4.11's
    // 3:1 (1.59:1 dark / 1.60:1 light, measured). The surface half is the whole de-emphasis now, and
    // this list is what says so — a second derivation reappearing here is that decision being undone.
    expect(derived.map(([selector]) => selector).sort()).toEqual(['.glass']);
    // The frame explicitly does NOT read the knob: full token alpha in every unfocused state.
    expect(
      rulesOf(composerSheet()).find(([s]) => s === '.glass::after')?.[1] ?? '',
    ).not.toContain('--composer-rest');
    for (const [selector, block] of derived) {
      // Whitespace-normalised: both derivations are wrapped across lines at this width.
      expect(block.replace(/\s+/g, ' '), `${selector} derives without color-mix`).toContain(
        'color-mix( in srgb,',
      );
    }
    const glass = derived.find(([selector]) => selector === '.glass')?.[1] ?? '';
    // The resting surface is DERIVED from the shipped material, not a second token for it.
    expect(glass).toContain('--composer-rest-surface: color-mix(');
    expect(glass).toContain('var(--composer-glass-surface) calc(100% - 65% * var(--composer-rest))');
    // ...and the fill still reads it through the ONE blur multiplier (859 §B D2), unbroken.
    expect(glass).toContain(
      'var(--composer-rest-surface)\n            calc(100% - (100% - var(--glass-opacity)) * var(--glass-blur-scale))',
    );
    // The no-blur fallback carries the resting state too, or the composer would re-emphasise itself
    // wherever backdrop-filter is missing.
    const fallback = rulesOf(composerSheet()).filter(
      ([selector, block]) => selector === '.glass' && block.includes('background: var('),
    );
    expect(fallback.map(([, block]) => block)).toContain(
      'background: var(--composer-rest-surface);',
    );
  });

  it('spends no TEXT contrast — the de-emphasis is material only', () => {
    // The half that makes the measured contrast audit answerable in advance: no rule that reads the
    // knob may also set ink or opacity. A resting treatment that dimmed the placeholder would put
    // --muted-foreground (zinc-500 on the light page, already near the AA floor) under it.
    // Read per DECLARATION, not per block: `.glass` legitimately carries a `backdrop-filter` that
    // reads the BLUR multiplier, and a block-level scan would either fail on it or have to exempt
    // the property outright. What is banned is a declaration whose VALUE spends this knob on one of
    // the ink-bearing properties — `color` and `opacity` are the obvious two, and `filter`,
    // `backdrop-filter` and `-webkit-text-fill-color` are the ones that would dim text without
    // naming a colour, which is how a later edit slips past a list that stopped at the obvious two.
    const INK = ['color', 'opacity', 'filter', 'backdrop-filter', '-webkit-text-fill-color'];
    for (const [selector, block] of rulesOf(composerSheet())) {
      for (const declaration of block.split(';')) {
        const at = declaration.indexOf(':');
        if (at < 0) continue;
        const property = declaration.slice(0, at).trim().toLowerCase();
        const value = declaration.slice(at + 1);
        if (!value.includes('var(--composer-rest)')) continue;
        expect(INK, `${selector} spends the resting knob on ${property}`).not.toContain(property);
      }
    }
    const placeholder = rulesOf(composerSheet()).find(([s]) => s === '.placeholder')?.[1] ?? '';
    expect(placeholder).toContain('color: var(--placeholder)');
    expect(placeholder).not.toContain('--composer-rest');
  });

  it('animates the change only where motion is welcome', () => {
    const sheet = composerSheet();
    // Both still transition, but for two reasons now: `.glass` hands off the resting SURFACE, and
    // `.glass::after` hands off `border-color` from `--composer-outline` to `--ring` at the focus arm
    // (tempdoc 948 removed the alpha fade, not the transition — the state change must not be a cut).
    for (const selector of ['.glass', '.glass::after']) {
      const block = rulesOf(sheet).find(([s]) => s === selector)?.[1] ?? '';
      expect(block, `${selector} declares no transition`).toContain(
        'var(--duration-sv3-micro) var(--ease-sv3-enter)',
      );
    }
    // ...and the reduce block stills exactly that, keeping the STATE and dropping its animation.
    const reduced = rulesOf(sheet.slice(sheet.indexOf('@media (prefers-reduced-motion: reduce)')));
    const stilled = reduced.filter(
      ([selector, block]) =>
        selector.includes('.glass::after') && block.includes('transition: none'),
    );
    expect(stilled.length, 'the reduce block does not still the resting transition').toBe(1);
    expect(stilled[0]?.[0]).toContain('.glass,');
  });

  it('does NOT fire while the composer holds focus', () => {
    // The direct statement of the case: every consumer reads the knob inside a
    // `calc(100% - N% * var(--composer-rest))` term, so at the focused value (0) each resolves to the
    // shipped, un-de-emphasised value — 100% of the material, 100% of the frame — rather than to some
    // third state, and no consumer can read the knob bare.
    for (const [selector, block] of rulesOf(composerSheet())) {
      if (!/var\(--composer-rest\)/.test(block)) continue;
      const spends = [...block.matchAll(/calc\(100% - (\d+)% \* var\(--composer-rest\)\)/g)];
      const reads = [...block.matchAll(/var\(--composer-rest\)/g)];
      expect(spends.length, `${selector} reads the knob outside a spend term`).toBe(reads.length);
      for (const spend of spends) {
        expect(
          Number(spend[1]),
          `${selector} spends the whole value, leaving nothing at rest`,
        ).toBeLessThan(100);
      }
    }
  });
});

/**
 * Tempdoc 864 Layer 3(c)(i) — THE PARKED ROW. §3.3(c) keeps tempdoc 831's guarantee (focus stays on
 * the row after a rename or a discard) and removes the hazard the other way: the row is the control
 * a bare `Space` swaps the conversation from, so while it holds focus the reader has to SEE it.
 */
describe('tempdoc 864 Layer 3(c)(i) — the focused session row is unmistakable', () => {
  const ownSheet = (): string => ownStyleTextOf(Sv3SessionRow);
  const ruleFor = (selector: string): string => {
    const styles = ownSheet();
    const at = styles.indexOf(selector);
    expect(at, `no rule for ${selector}`).toBeGreaterThan(-1);
    return styles.slice(at, styles.indexOf('}', at));
  };

  it('draws the ring AND a halo of the same token, both inset past the row’s own clip', () => {
    const focus = ruleFor('button.row:focus-visible {');
    expect(focus).toContain('outline: 2px solid var(--ring)');
    // Inset: the row sets `overflow: hidden` and sits flush in the sidebar's inset, so an outward
    // ring is trimmed — which is why the offset is negative rather than the usual +2.
    expect(focus).toContain('outline-offset: -2px');
    expect(ruleFor('button.row {')).toContain('overflow: hidden');
    // The second mark, in the composer's own halo idiom (#529): a graded mix of the SAME token.
    expect(focus).toContain('box-shadow: inset 0 0 0 var(--space-2)');
    expect(focus).toContain('color-mix(in srgb, var(--ring) 22%, transparent)');
    // One hue: the ring and the halo cannot drift into two focus colours, and neither is a literal.
    for (const literal of ['rgb(', 'hsl(', 'oklch(', '#']) expect(focus).not.toContain(literal);
    expect([...focus.matchAll(/var\(--[a-z0-9-]+\)/g)].map((m) => m[0]).sort()).toEqual([
      'var(--ring)',
      'var(--ring)',
      'var(--space-2)',
    ]);
  });

  it('is not eaten by an `all:` reset — the #539 lesson, on this row', () => {
    // `all: unset` (or a bare `outline: none` after the focus rule) silently blanks a focus ring,
    // which is how #539's composed control lost one. `button.row` resets its chrome property by
    // property instead; this pins the reason so a later "tidy-up" cannot reintroduce the shorthand.
    expect(ruleFor('button.row {')).not.toMatch(/[;{]\s*all\s*:/);
    expect(ruleFor('button.row {')).toContain('border: 0');
    const own = ownSheet();
    const after = own.slice(own.indexOf('button.row:focus-visible {'));
    expect(after).not.toMatch(/button\.row[^{]*\{[^}]*outline:\s*none/);
    // And the OUTER tree cannot reach in: a `::part` declaration from the consuming tree beats the
    // inner rule regardless of specificity, which is the exact mechanism #539 was fixed for.
    expect(own).not.toContain('::part(');
    expect(ownStyleTextOf(Sv3Sidebar)).not.toContain('::part(');
  });

  it('exposes the focusable row through no shadow part an outer sheet could blank', async () => {
    // The rules above are text; this is the DOM half — an element carrying no `part` attribute
    // cannot be reached by `::part()` at all, whatever a consuming sheet later says. The row's
    // ICONS do expose one (`jf-icon`, from the shared icon component), which is why this asserts the
    // focusable controls specifically rather than the absence of parts everywhere.
    const el = document.createElement('jf-sv3-session-row') as HTMLElement & {
      updateComplete: Promise<unknown>;
    };
    document.body.appendChild(el);
    await el.updateComplete;
    const focusable = [...(el.shadowRoot?.querySelectorAll('button') ?? [])];
    expect(focusable.length).toBeGreaterThan(0);
    for (const control of focusable) {
      expect(control.hasAttribute('part'), `${control.className} is reachable by ::part()`).toBe(
        false,
      );
    }
    // Anti-vacuity: the row really did render the button the rules above are about.
    expect(el.shadowRoot?.querySelector('button.row')).not.toBeNull();
    el.remove();
  });
});
