// @vitest-environment happy-dom

/**
 * The Search v3 sidebar's MECHANICS (tempdoc 822 Phase F5) — the window-level half of
 * `sv3-boundaries.ts`, whose arithmetic is decided without a DOM in its own file.
 *
 * What is asserted here is the wiring the arithmetic cannot see: that the clamp is fed the box
 * measured AT DRAG TIME rather than a constant, that a chosen width is remembered and a reset
 * forgets it, that collapsing changes the rendered rail without touching the remembered width, that
 * the collapsed row still tells the truth about a run blocked on the reader, and that a rename
 * commits, cancels and reverts by the design spec's rule.
 *
 * happy-dom lays nothing out, so `getBoundingClientRect` is stubbed per case — which is what makes
 * "the ceiling comes from the shared box" testable at all: two different boxes must produce two
 * different ceilings.
 */
import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest';
import './SearchV3View.js';
import { submitInSession as localConversation, SV3_SESSIONS_EMPTY as EMPTY_CONVERSATIONS } from './sv3-sessions.js';
import type { SearchV3View } from './SearchV3View.js';
import { Sv3SessionRow } from './Sv3SessionRow.js';
import { Sv3Sidebar } from './Sv3Sidebar.js';
import { resetSearchState } from '../../state/searchState.js';
import {
  __feedContactForTest,
  __feedForTest,
  __resetAiStateForTest,
} from '../../state/aiStateStore.js';
import type { StatusSnapshot } from '../../utils/statusPoll.js';
import { startNewSession, submitInSession, SV3_SESSIONS_EMPTY } from './sv3-sessions.js';
import {
  sv3BoundaryStorageKeys,
  SV3_SIDEBAR_DEFAULT_PX,
  SV3_SIDEBAR_MIN_PX,
} from './sv3-boundaries.js';
import {
  loadConversations,
  __resetConversationListForTest,
} from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';

type Mounted = SearchV3View & { updateComplete: Promise<unknown> };

/** The window measured as a box of `width` px — the spec's `wrapper`, which happy-dom cannot lay out. */
function widen(el: HTMLElement, width: number): void {
  el.getBoundingClientRect = () => ({ width, height: 900, x: 0, y: 0, top: 0, left: 0, right: width, bottom: 900, toJSON: () => ({}) }) as DOMRect;
}

async function mount(boxWidth = 1568, existing = true): Promise<Mounted> {
  const el = document.createElement('jf-sv3-window') as Mounted;
  if (existing) el.sessions = { ...localConversation(EMPTY_CONVERSATIONS, 'Pending local conversation', 1, 'ask', 'geometry-conversation'), activeId: null };
  widen(el, boxWidth);
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const q = <T extends HTMLElement>(el: Mounted, id: string): T =>
  el.shadowRoot?.querySelector<T>(`[data-testid="${id}"]`) as T;

const sidebar = (el: Mounted): Sv3Sidebar => q<Sv3Sidebar>(el, 'sv3-sidebar');

const rows = (el: Mounted): Sv3SessionRow[] => [
  ...(sidebar(el).shadowRoot?.querySelectorAll<Sv3SessionRow>('[data-testid="sv3-sidebar-row"]') ??
    []),
];

const widthPx = (el: Mounted): number =>
  Number.parseInt(el.style.getPropertyValue('--sidebar-width'), 10);

/** One drag of the grip, in the three events a pointer really sends. */
async function drag(el: Mounted, deltaX: number): Promise<void> {
  const grip = q(el, 'sv3-sidebar-grip');
  grip.setPointerCapture = (): void => undefined;
  grip.dispatchEvent(
    new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 300 }),
  );
  grip.dispatchEvent(new MouseEvent('pointermove', { bubbles: true, clientX: 300 + deltaX }));
  grip.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
  await el.updateComplete;
}

/** Two sessions, the second of them claimed — handed in as data, the way F3's live check does. */
function twoSessions(el: Mounted): void {
  const first = submitInSession(
    SV3_SESSIONS_EMPTY,
    'northfield lease',
    Date.parse('2026-08-13T10:00:00Z'),
    'ask',
    'uc-first',
  );
  el.sessions = submitInSession(
    startNewSession(first),
    'renewal option',
    Date.parse('2026-08-13T10:05:00Z'),
    'ask',
    'uc-second',
  );
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
  localStorage.clear();
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
  document.querySelectorAll('jf-sv3-window').forEach((el) => el.remove());
  resetSearchState();
  __resetAiStateForTest();
  vi.unstubAllGlobals();
  localStorage.clear();
});

describe('the sidebar boundary drags, within the spec clamps', () => {
  it('opens at the spec default', async () => {
    const el = await mount();
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX);
  });

  it('follows the pointer between the floor and the ceiling', async () => {
    const el = await mount();
    await drag(el, 80);
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX + 80);
  });

  it('cannot starve the main pane: the ceiling is the box minus 640', async () => {
    const el = await mount(1568);
    await drag(el, 5000);
    expect(widthPx(el)).toBe(1568 - 640);
  });

  it('takes the ceiling from the box measured AT DRAG TIME', async () => {
    // The probe the constant-ceiling bug would survive: the same gesture in a narrower window must
    // stop earlier. A clamp reading a stored or start-up number would give 928 both times.
    const el = await mount(1568);
    await drag(el, 5000);
    expect(widthPx(el)).toBe(928);
    widen(el, 1100);
    await drag(el, 5000);
    expect(widthPx(el)).toBe(1100 - 640);
  });

  it('stands the transitions down for the duration of the gesture', async () => {
    // An eased width lags a pointer that is setting it directly (the spec's own reason for zeroing
    // its transition durations at pointer-down).
    const el = await mount();
    const grip = q(el, 'sv3-sidebar-grip');
    grip.setPointerCapture = (): void => undefined;
    grip.dispatchEvent(
      new MouseEvent('pointerdown', { bubbles: true, cancelable: true, button: 0, clientX: 300 }),
    );
    await el.updateComplete;
    expect(el.hasAttribute('resizing')).toBe(true);
    grip.dispatchEvent(new MouseEvent('pointerup', { bubbles: true }));
    await el.updateComplete;
    expect(el.hasAttribute('resizing')).toBe(false);
  });

  it('refuses to drag a collapsed rail', async () => {
    const el = await mount();
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    await drag(el, 300);
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX);
  });

  it('does not clamp to the floor just because the window has not been laid out', async () => {
    // A box measured at 0 (pre-layout, or a headless host) is UNKNOWN, not tiny; letting it decide
    // would collapse a remembered width to the floor as a side effect of being unmeasurable.
    localStorage.setItem(sv3BoundaryStorageKeys.sidebarWidth, '400');
    const el = document.createElement('jf-sv3-window') as Mounted;
    document.body.appendChild(el);
    await el.updateComplete;
    expect(widthPx(el)).toBe(400);
  });

  it('cannot go under the spec floor', async () => {
    const el = await mount();
    await drag(el, -5000);
    expect(widthPx(el)).toBe(SV3_SIDEBAR_MIN_PX);
  });

  it('remembers the chosen width, and a fresh window opens at it', async () => {
    const el = await mount();
    await drag(el, 60);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBe('316');
    el.remove();
    const reopened = await mount();
    expect(widthPx(reopened)).toBe(316);
  });

  it('clamps a remembered width into a window that got narrower', async () => {
    localStorage.setItem(sv3BoundaryStorageKeys.sidebarWidth, '900');
    const el = await mount(1000);
    expect(widthPx(el)).toBe(360);
  });

  it('double-click returns the boundary to automatic AND forgets the choice (818 L13)', async () => {
    const el = await mount();
    await drag(el, 200);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).not.toBeNull();
    q(el, 'sv3-sidebar-grip').dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await el.updateComplete;
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBeNull();
  });
});

describe('the boundary is operable without a pointer', () => {
  const key = (el: Mounted, k: string): void => {
    q(el, 'sv3-sidebar-grip').dispatchEvent(
      new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true }),
    );
  };

  it('is a native button that names its gestures', async () => {
    const el = await mount();
    const grip = q(el, 'sv3-sidebar-grip');
    expect(grip.tagName).toBe('BUTTON');
    expect(grip.getAttribute('aria-label')).toContain('arrow keys resize');
  });

  it('nudges by one step per arrow, and remembers it', async () => {
    const el = await mount();
    key(el, 'ArrowRight');
    await el.updateComplete;
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX + 24);
    key(el, 'ArrowLeft');
    key(el, 'ArrowLeft');
    await el.updateComplete;
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX - 24);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBe('232');
  });

  it('nudges under the same clamp as the drag', async () => {
    const el = await mount(1000);
    for (let i = 0; i < 20; i += 1) key(el, 'ArrowRight');
    await el.updateComplete;
    expect(widthPx(el)).toBe(360);
  });

  it('Home returns to automatic', async () => {
    const el = await mount();
    key(el, 'ArrowRight');
    key(el, 'Home');
    await el.updateComplete;
    expect(widthPx(el)).toBe(SV3_SIDEBAR_DEFAULT_PX);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarWidth)).toBeNull();
  });
});

describe('the icon rail', () => {
  it('collapses and expands, and remembers which', async () => {
    const el = await mount();
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    expect(el.hasAttribute('sidebar-collapsed')).toBe(true);
    expect(localStorage.getItem(sv3BoundaryStorageKeys.sidebarCollapsed)).toBe('1');
    el.remove();
    const reopened = await mount();
    expect(reopened.hasAttribute('sidebar-collapsed')).toBe(true);
  });

  it('restores the width the reader chose when it expands', async () => {
    const el = await mount();
    await drag(el, 100);
    expect(widthPx(el)).toBe(356);
    const toggle = (): void => {
      q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    };
    toggle();
    await el.updateComplete;
    // Collapsing renders the icon rail WITHOUT overwriting the chosen width — which is what makes
    // the restore free rather than a second thing to keep in step.
    expect(widthPx(el)).toBe(356);
    toggle();
    await el.updateComplete;
    expect(el.hasAttribute('sidebar-collapsed')).toBe(false);
    expect(widthPx(el)).toBe(356);
  });

  it('a click on the grip expands a collapsed rail, and does nothing to an open one', async () => {
    const el = await mount();
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    q(el, 'sv3-sidebar-grip').click();
    await el.updateComplete;
    expect(el.hasAttribute('sidebar-collapsed')).toBe(false);
    q(el, 'sv3-sidebar-grip').click();
    await el.updateComplete;
    expect(el.hasAttribute('sidebar-collapsed')).toBe(false);
  });

  it('puts every row into its compact form', async () => {
    const el = await mount();
    twoSessions(el);
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect(rows(el).every((row) => row.compact)).toBe(false);
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect(rows(el)).toHaveLength(2);
    expect(rows(el).every((row) => row.compact)).toBe(true);
  });

  it('keeps a compact row NAMED even though its title is not drawn', async () => {
    const el = await mount();
    twoSessions(el);
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    await sidebar(el).updateComplete;
    const row = rows(el)[0] as Sv3SessionRow;
    await row.updateComplete;
    const button = row.shadowRoot?.querySelector('[data-testid="sv3-session-row-button"]');
    expect(button?.getAttribute('aria-label')).toBe('renewal option');
    // The spec shows a right-side tooltip on a collapsed row; this window
    // has no tooltip primitive (slice 4 deferred it), so the native one carries the same text.
    expect(button?.getAttribute('title')).toBe('renewal option');
  });

  it('hides the title, the timestamp and the pin — and NEVER the act-now dot', async () => {
    const el = await mount();
    const sheets = Sv3SessionRow.styles as ReadonlyArray<{ cssText: string }>;
    const css = sheets.map((sheet) => sheet.cssText).join('\n');
    // The compact rules name exactly the three things that yield.
    expect(css).toMatch(/:host\(\[compact\]\)\s*\.row-label,\s*:host\(\[compact\]\)\s*\.meta,\s*:host\(\[compact\]\)\s*\.actions\s*\{\s*display:\s*none/);
    // And the status slot is repositioned, not hidden: no rule may take the dot away.
    expect(css).not.toMatch(/:host\(\[compact\]\)[^{]*\.dot[^{]*\{[^}]*display:\s*none/);
    expect(css).not.toMatch(/:host\(\[compact\]\)[^{]*\.status-slot[^{]*\{[^}]*display:\s*none/);
    // The hover/focus SWAP must not fire while compact either — a collapsed act-now row whose dot
    // disappeared under the pointer would be exactly the fact the never-yields rule protects.
    const swapRules = css.match(/[^}]*\.slot-content\s*\{\s*position:\s*absolute[^}]*}/g) ?? [];
    expect(swapRules.length).toBeGreaterThanOrEqual(3);
    expect(swapRules.every((rule) => rule.includes(':not([compact])'))).toBe(true);
    el.remove();
  });

  it('renders the act-now dot on a collapsed row', async () => {
    const el = await mount();
    twoSessions(el);
    q(sidebar(el) as unknown as Mounted, 'sv3-sidebar-collapse').click();
    await el.updateComplete;
    await sidebar(el).updateComplete;
    const row = rows(el).find((r) => r.status !== 'resting') ?? (rows(el)[0] as Sv3SessionRow);
    row.status = 'act-now';
    row.compact = true;
    await row.updateComplete;
    expect(row.shadowRoot?.querySelector('[data-testid="sv3-session-row-status"]')).not.toBeNull();
    expect(row.shadowRoot?.querySelector('[data-testid="sv3-session-row-meta"]')).toBeNull();
  });
});

describe('renaming a conversation from its row', () => {
  const openRename = async (el: Mounted): Promise<HTMLInputElement> => {
    const row = rows(el)[0] as Sv3SessionRow;
    row.shadowRoot
      ?.querySelector('[data-testid="sv3-session-row-button"]')
      ?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    await sidebar(el).updateComplete;
    const editing = rows(el)[0] as Sv3SessionRow;
    await editing.updateComplete;
    return editing.shadowRoot?.querySelector<HTMLInputElement>(
      '[data-testid="sv3-session-row-rename-input"]',
    ) as HTMLInputElement;
  };

  const ready = async (el: Mounted): Promise<void> => {
    twoSessions(el);
    await el.updateComplete;
    await sidebar(el).updateComplete;
    for (const row of rows(el)) await row.updateComplete;
  };

  it('opens an inline edit on double-click, seeded with the current title', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    expect(input).not.toBeNull();
    expect(input.value).toBe('renewal option');
  });

  it('commits on Enter', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = '  Lease renewal  ';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('Lease renewal');
    expect((rows(el)[0] as Sv3SessionRow).renaming).toBe(false);
  });

  it('cancels on Escape, keeping the old title', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = 'discarded';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('renewal option');
  });

  it('does not resurrect a cancelled edit through the blur that follows it', async () => {
    // Escape removes the input, and removing a focused input fires `blur`. Without the latch that
    // blur would commit exactly the text the reader just discarded (per the design spec).
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = 'discarded';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, composed: true, cancelable: true }));
    input.dispatchEvent(new FocusEvent('blur', { bubbles: false }));
    await el.updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('renewal option');
  });

  it('reverts an empty title rather than leaving the row nameless', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = '   ';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('renewal option');
  });

  it('commits on blur, once — a key that already settled it does not commit twice', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = 'From blur';
    input.dispatchEvent(new FocusEvent('blur', { bubbles: false }));
    await el.updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('From blur');
  });

  it('is reachable from the keyboard alone (F2)', async () => {
    const el = await mount();
    await ready(el);
    (rows(el)[0] as Sv3SessionRow).shadowRoot
      ?.querySelector('[data-testid="sv3-session-row-button"]')
      ?.dispatchEvent(new KeyboardEvent('keydown', { key: 'F2', bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    await sidebar(el).updateComplete;
    const row = rows(el)[0] as Sv3SessionRow;
    await row.updateComplete;
    expect(row.renaming).toBe(true);
  });

  it('a modified double-click is not a rename', async () => {
    const el = await mount();
    await ready(el);
    (rows(el)[0] as Sv3SessionRow).shadowRoot
      ?.querySelector('[data-testid="sv3-session-row-button"]')
      ?.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, composed: true, shiftKey: true }));
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect((rows(el)[0] as Sv3SessionRow).renaming).toBe(false);
  });

  it('drops an edit in flight when another conversation is claimed', async () => {
    const el = await mount();
    await ready(el);
    await openRename(el);
    (rows(el)[1] as Sv3SessionRow).click();
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect(rows(el).some((row) => row.renaming)).toBe(false);
    // And the title is untouched: navigating away is not a commit.
    expect(el.sessions.sessions[0]?.title).toBe('renewal option');
  });

  it('shows a DELEGATE conversation on a cold load, and withholds its rename', async () => {
    // Tempdoc 859 slice C PR-2, end to end through the real path: the list endpoint's synthesized
    // row → the store → the merge → the projection → the rendered row. Before the two-store join
    // this conversation had a complete durable record and no row anywhere, so the sidebar could not
    // reach it; and the row it gets must not offer the one action its record cannot support.
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation((url: string) =>
        Promise.resolve({
          ok: true,
          status: 200,
          json: () =>
            Promise.resolve(
              String(url).includes('/api/chat/conversations')
                ? {
                    sessions: [
                      {
                        sessionId: 'conv-delegate',
                        shapeId: 'core.agent-run',
                        createdAtMs: Date.parse('2026-08-13T09:00:00Z'),
                        lastActiveAtMs: Date.parse('2026-08-13T09:30:00Z'),
                        firstUserMessage: 'summarise the renewal folder',
                        storeBacked: false,
                      },
                    ],
                  }
                : { results: [] },
            ),
        }),
      ),
    );
    const el = await mount(1568, false);
    await loadConversations();
    await el.updateComplete;
    await sidebar(el).updateComplete;
    for (const row of rows(el)) await row.updateComplete;

    expect(el.sessions.sessions.map((s) => s.id)).toEqual(['conv-delegate']);
    const row = rows(el)[0] as Sv3SessionRow;
    expect(row.label).toBe('summarise the renewal folder');
    expect(row.storeBacked).toBe(false);
    expect(row.shadowRoot?.querySelector('[data-testid="sv3-session-row-rename"]')).toBeNull();
    // The discard IS offered: deleting the conversation deletes its runs, which is its whole record.
    expect(row.shadowRoot?.querySelector('[data-testid="sv3-session-row-remove"]')).not.toBeNull();
  });

  it('keeps the chosen title when the conversation takes another turn', async () => {
    const el = await mount();
    await ready(el);
    const input = await openRename(el);
    input.value = 'Lease renewal';
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, composed: true, cancelable: true }));
    await el.updateComplete;
    el.sessions = submitInSession(el.sessions, 'and the break clause?', Date.now(), 'ask', 'uc-unused');
    await el.updateComplete;
    await sidebar(el).updateComplete;
    expect(el.sessions.sessions[0]?.title).toBe('Lease renewal');
    expect(el.sessions.sessions[0]?.turns).toHaveLength(2);
  });
});
