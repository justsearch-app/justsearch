// @vitest-environment happy-dom

/**
 * The Search v3 command palette and the two empty states (tempdoc 822 slice 4).
 *
 * Three things here are contracts rather than appearance, and each is asserted as a MECHANISM:
 * the chord is scoped to the window host (so the shipped shell's own Ctrl+K keeps working outside
 * it), focus cannot leave an open palette and returns to whatever opened it, and the two spec
 * fills are mutually exclusive by selector rather than by declaration order.
 */
import { describe, it, expect, afterEach, vi } from 'vitest';
// Side-effect imports, kept separate from the type-only ones below: a class named ONLY in a type
// position is elided from the emitted bundle, and with it the `customElements.define` that makes
// `document.createElement` return anything but an un-upgraded element.
import './SearchV3View.js';
import './Sv3Palette.js';
import './Sv3Empty.js';
import './Sv3Sidebar.js';
import type { Sv3Sidebar } from './Sv3Sidebar.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Palette } from './Sv3Palette.js';
import type { Sv3Empty } from './Sv3Empty.js';
import { SV3_PALETTE_RUN } from './Sv3Palette.js';
import { SV3_PALETTE_REQUEST } from './Sv3Topbar.js';
import { COMMANDS, COMMAND_GROUPS, SIDEBAR_EMPTY, SV3_COMMAND_SEARCH } from './fixtures.js';
import { COMPONENT_TAGS } from '../../renderers/component-vocabulary.generated.js';
import { modalOwnsFocus, __resetModalityForTest } from '../../primitives/modality.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

async function region(el: Mounted, tag: string): Promise<Mounted> {
  const found = el.shadowRoot?.querySelector(tag) as Mounted | null;
  if (!found) throw new Error(`no <${tag}> in the window`);
  await found.updateComplete;
  return found;
}

const paletteOf = (el: Mounted): Sv3Palette & Mounted =>
  el.shadowRoot?.querySelector('jf-sv3-palette') as Sv3Palette & Mounted;

/** Open through the real affordance, so the invoker the palette records is a real element. */
async function openViaTopbar(el: Mounted): Promise<{
  palette: Sv3Palette & Mounted;
  trigger: HTMLButtonElement;
}> {
  const topbar = await region(el, 'jf-sv3-topbar');
  const trigger = topbar.shadowRoot?.querySelector<HTMLButtonElement>(
    '[data-testid="sv3-topbar-palette"]',
  );
  if (!trigger) throw new Error('no palette trigger in the topbar');
  trigger.click();
  const palette = paletteOf(el);
  await palette.updateComplete;
  return { palette, trigger };
}

/**
 * Focus is observed through the element's own `focus` event rather than through a shadow root's
 * `activeElement`, which the test DOM cannot resolve across two nested roots.
 */
function watchFocus(el: HTMLElement): () => boolean {
  let hit = false;
  el.addEventListener('focus', () => {
    hit = true;
  });
  return () => hit;
}

const chord = (): KeyboardEvent =>
  new KeyboardEvent('keydown', { key: 'k', ctrlKey: true, bubbles: true, composed: true });

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  vi.restoreAllMocks();
  // 864 Layer 2(d) — modality depth is module state; a case that leaves the palette open would
  // otherwise leak "a modal is open" into every later case in the run.
  __resetModalityForTest();
});

describe('the palette is a window-scoped overlay, never a document one', () => {
  it('is the last node in the window, so it stacks over the hero composer on the same rung', async () => {
    const el = await mount();
    const children = [...(el.shadowRoot?.children ?? [])];
    expect(children[children.length - 1]?.tagName.toLowerCase()).toBe('jf-sv3-palette');
    // Both sit at --z-overlay; DOM order is the tie-break, so it is pinned rather than incidental —
    // and there is exactly ONE, or a second instance would take the tie-break instead.
    expect(el.shadowRoot?.querySelectorAll('jf-sv3-palette')).toHaveLength(1);
    expect(children.some((c) => c.classList.contains('column'))).toBe(true);
  });

  it('renders nothing at all while closed', async () => {
    const el = await mount();
    const palette = paletteOf(el);
    expect(palette.open).toBe(false);
    expect(palette.shadowRoot?.querySelector('[data-testid="sv3-palette-popup"]')).toBeNull();
    // ...which is also why it cannot become a second scroller in a window that has exactly one.
    expect(palette.shadowRoot?.querySelectorAll('.sv3-scroller')).toHaveLength(0);
  });

  it('opens from the topbar with the spec anatomy: input, list, separator, footer', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    expect(palette.open).toBe(true);
    const root = palette.shadowRoot;
    expect(root?.querySelector('[data-testid="sv3-palette-input"]')).toBeTruthy();
    expect(root?.querySelector('[data-testid="sv3-palette-panel"]')).toBeTruthy();
    expect(root?.querySelector('[data-testid="sv3-palette-footer"]')).toBeTruthy();
    expect(root?.querySelectorAll('[data-testid="sv3-palette-item"]')).toHaveLength(COMMANDS.length);
    expect(root?.querySelectorAll('[data-testid="sv3-palette-group-label"]')).toHaveLength(
      COMMAND_GROUPS.length,
    );
    // Two groups, so exactly ONE separator — a trailing rule would underline the last group.
    expect(root?.querySelectorAll('[data-testid="sv3-palette-separator"]')).toHaveLength(
      COMMAND_GROUPS.length - 1,
    );
  });
});

/**
 * The scoping contract. The shipped shell binds the same chord globally, on a CAPTURE listener
 * attached to `window` at boot, and this window must not interfere with it — so it registers nothing
 * global at all: the listener lives on the host, which only events routed through this window reach.
 */
describe('Ctrl+K is scoped to the window, and the shipped binding is left alone', () => {
  it('opens the palette for a chord raised inside the window', async () => {
    const el = await mount();
    const composer = await region(el, 'jf-sv3-composer');
    const field = composer.shadowRoot?.querySelector('textarea');
    field?.dispatchEvent(chord());
    const palette = paletteOf(el);
    await palette.updateComplete;
    expect(palette.open).toBe(true);
    // ...and toggles shut on the same chord, which is what the spec's trigger does.
    field?.dispatchEvent(chord());
    await palette.updateComplete;
    expect(palette.open).toBe(false);
  });

  it('ignores a chord raised outside the window entirely', async () => {
    const el = await mount();
    const outside = document.createElement('input');
    document.body.appendChild(outside);
    outside.dispatchEvent(chord());
    document.body.dispatchEvent(chord());
    const palette = paletteOf(el);
    await palette.updateComplete;
    expect(palette.open).toBe(false);
  });

  it('registers no global CHORD dispatcher, so the shipped Ctrl+K is untouched', async () => {
    // Tempdoc 857 PR-A narrowed this case to the contract it names in the file header — "the chord is
    // scoped to the window host (so the shipped shell's own Ctrl+K keeps working outside it)".
    //
    // It used to assert the PROXY for that: zero global keydown listeners anywhere in the window. That
    // proxy stopped matching its subject when the run spine's J/K navigation was ported here: J/K is
    // deliberately window-scoped (a reader must be able to step the transcript while focus sits in the
    // sidebar, and this window's other host-capture listener is on the wrong element to reach the
    // transcript's landmark index), and it ignores every modified chord, so the shipped dispatcher is
    // as untouched as it ever was. Asserted as a mechanism rather than as a count, plus the two things
    // the old count could not see: WHICH listener, and whether it leaks.
    const onDocument = vi.spyOn(document, 'addEventListener');
    const onWindow = vi.spyOn(window, 'addEventListener');
    const el = await mount();
    expect(
      onDocument.mock.calls.filter(([type]) => type === 'keydown'),
      'the window surface attached a document keydown listener',
    ).toHaveLength(0);
    // Exactly one, and it is the transcript region's — a second would mean an undeclared global
    // dispatcher had appeared.
    const keyOnWindow = onWindow.mock.calls.filter(([type]) => type === 'keydown');
    expect(keyOnWindow, 'more global keydown listeners than the transcript’s J/K navigation').toHaveLength(1);

    // The mechanism: a chord dispatched at `window` reaches that listener and is left entirely alone —
    // not consumed, not prevented — so the shipped capture-phase dispatcher's event is unaltered.
    const raised = chord();
    window.dispatchEvent(raised);
    expect(raised.defaultPrevented, 'the window surface consumed the shipped chord').toBe(false);
    const palette = paletteOf(el);
    await palette.updateComplete;
    expect(palette.open).toBe(false);

    // …and it is removed on teardown, symmetrically. The old "zero removals" assertion could only say
    // that nothing global was detached; this says the one listener that exists does not outlive the
    // element that owns it.
    const offWindow = vi.spyOn(window, 'removeEventListener');
    const offDocument = vi.spyOn(document, 'removeEventListener');
    el.remove();
    expect(offWindow.mock.calls.filter(([type]) => type === 'keydown')).toHaveLength(1);
    expect(offDocument.mock.calls.filter(([type]) => type === 'keydown')).toHaveLength(0);
  });

  it('stops the chord at the window boundary rather than letting it reach a second consumer', async () => {
    // Honest limit, recorded so nobody re-derives it: the shipped dispatcher listens in the CAPTURE
    // phase on `window` and is attached at boot, so it necessarily runs BEFORE any listener this
    // lazily-loaded surface can add. Everything downstream of the host — bubble-phase consumers and
    // the window's own field — is what this window can and does stop.
    const el = await mount();
    const seen: string[] = [];
    const bubble = (): void => void seen.push('bubble');
    window.addEventListener('keydown', bubble);
    try {
      const composer = await region(el, 'jf-sv3-composer');
      composer.shadowRoot?.querySelector('textarea')?.dispatchEvent(chord());
      expect(seen).toEqual([]);
    } finally {
      window.removeEventListener('keydown', bubble);
    }
  });

  it('leaves an unmounted window deaf, so a stale surface cannot answer the chord', async () => {
    const el = await mount();
    const palette = paletteOf(el);
    el.remove();
    document.body.appendChild(el);
    el.remove();
    // Re-attaching once must not double-register: two listeners would toggle twice and no-op.
    document.body.appendChild(el);
    await el.updateComplete;
    const composer = await region(el, 'jf-sv3-composer');
    composer.shadowRoot?.querySelector('textarea')?.dispatchEvent(chord());
    await palette.updateComplete;
    expect(palette.open).toBe(true);
  });
});

describe('the open palette keeps the keyboard, and hands it back on close', () => {
  it('906: a pointer press cannot dismiss the option before its click dispatches', async () => {
    const el = await mount();
    const { palette, trigger } = await openViaTopbar(el);
    const input = palette.shadowRoot!.querySelector('input')!;
    const item = palette.shadowRoot!.querySelector<HTMLElement>(`#sv3-palette-item-${SV3_COMMAND_SEARCH}`)!;
    const label = item.querySelector<HTMLElement>('.item-label')!;
    const commands: string[] = [];
    el.addEventListener(SV3_PALETTE_RUN, (event) => {
      commands.push((event as CustomEvent<{ id: string }>).detail.id);
    });
    const refocused = watchFocus(trigger);
    expect(palette.shadowRoot?.activeElement).toBe(input);

    label.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true, composed: true, button: 0 }));
    const mouseDown = new MouseEvent('mousedown', { bubbles: true, composed: true, cancelable: true, button: 0 });
    label.dispatchEvent(mouseDown);
    // happy-dom does not perform native mousedown focus transfer. Reproduce Chromium's
    // observed default: a nonfocusable option blurs the field with relatedTarget=null.
    if (!mouseDown.defaultPrevented) {
      input.dispatchEvent(new FocusEvent('focusout', { bubbles: true, composed: true, relatedTarget: null }));
    }
    await palette.updateComplete;
    expect(palette.open, 'pointer focus transfer dismissed the option before click').toBe(true);
    expect(palette.shadowRoot?.activeElement).toBe(input);
    expect(commands).toEqual([]);
    label.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, composed: true, button: 0 }));
    label.dispatchEvent(new MouseEvent('click', { bubbles: true, composed: true, button: 0 }));
    await palette.updateComplete;
    expect(commands).toEqual([SV3_COMMAND_SEARCH]);
    expect(palette.open).toBe(false);
    expect(refocused()).toBe(true);
  });

  it('focuses the field on open and returns focus to the invoker on Escape', async () => {
    const el = await mount();
    const { palette, trigger } = await openViaTopbar(el);
    const input = palette.shadowRoot?.querySelector('input');
    expect(palette.shadowRoot?.activeElement).toBe(input);

    const refocused = watchFocus(trigger);
    input?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await palette.updateComplete;
    expect(palette.open).toBe(false);
    // Focus must land somewhere deliberate: back on the control that opened the palette.
    expect(refocused(), 'focus did not return to the invoker').toBe(true);
  });

  it('closes rather than stranding itself when something outside takes the keyboard', async () => {
    // The shipped shell binds the SAME Ctrl+K and its palette steals the field
    // (`KeybindingRegistry.ts:178`). Once focus is out of the window, this palette's own Escape
    // never fires again — it would sit open, visible, and reachable only by pointer (F-series fit
    // audit, DEFECT-8). Focus departure is therefore its third exit.
    const el = await mount();
    const { palette, trigger } = await openViaTopbar(el);
    expect(palette.open).toBe(true);

    // Something in the shipped shell (outside this window's tree) now holds the keyboard.
    const thief = document.createElement('input');
    document.body.appendChild(thief);
    const reclaimed = watchFocus(trigger);
    palette.shadowRoot
      ?.querySelector('input')
      ?.dispatchEvent(new FocusEvent('focusout', { bubbles: true, composed: true, relatedTarget: thief }));
    await palette.updateComplete;

    expect(palette.open, 'the palette was left open and keyboard-unreachable').toBe(false);
    // And it does NOT yank the caret back out of whatever legitimately took it — the fight that
    // makes the double-palette worse than a duplicate.
    expect(reclaimed(), 'the palette pulled focus back off the new owner').toBe(false);
    thief.remove();
  });

  it('stays open while focus moves WITHIN the window (the palette is inside it)', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    // Focus crossing a shadow boundary inside the window retargets to the window host itself.
    palette.shadowRoot
      ?.querySelector('input')
      ?.dispatchEvent(new FocusEvent('focusout', { bubbles: true, composed: true, relatedTarget: el }));
    await palette.updateComplete;
    expect(palette.open).toBe(true);
  });

  it('closes on a backdrop click and still restores the invoker', async () => {
    const el = await mount();
    const { palette, trigger } = await openViaTopbar(el);
    const refocused = watchFocus(trigger);
    palette.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-palette-backdrop"]')?.click();
    await palette.updateComplete;
    expect(palette.open).toBe(false);
    expect(refocused(), 'focus did not return to the invoker').toBe(true);
  });

  it('traps Tab inside the popup instead of walking out into the shell behind the backdrop', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const input = palette.shadowRoot?.querySelector('input');
    const forward = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    input?.dispatchEvent(forward);
    expect(forward.defaultPrevented, 'Tab escaped the palette').toBe(true);
    expect(palette.shadowRoot?.activeElement).toBe(input);

    const back = new KeyboardEvent('keydown', {
      key: 'Tab',
      shiftKey: true,
      bubbles: true,
      cancelable: true,
    });
    input?.dispatchEvent(back);
    expect(back.defaultPrevented, 'Shift+Tab escaped the palette').toBe(true);
    expect(palette.shadowRoot?.activeElement).toBe(input);
  });

  /**
   * Tempdoc 864 §2.9(e) — the trap's actual hole, and the reason `Tab` escaped onto `<body>` in the
   * 2026-08-25 live session. The rows are `role="option"` and deliberately not tab stops, so focus
   * resting on one (or anywhere else inside the popup that is not a stop) took the early return and
   * handed the press back to the platform. Containment must not depend on focus already being on a
   * stop.
   */
  it('864: contains Tab even when focus is on a row rather than on a stop', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const input = palette.shadowRoot?.querySelector('input');
    const row = palette.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-palette-item"]');
    if (!row) throw new Error('no palette row rendered');
    row.tabIndex = -1;
    row.focus();
    expect(palette.shadowRoot?.activeElement, 'the case needs focus off the stop').toBe(row);

    const forward = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    row.dispatchEvent(forward);
    expect(forward.defaultPrevented, 'Tab walked out of the popup').toBe(true);
    expect(palette.shadowRoot?.activeElement, 'focus was not pulled back to a stop').toBe(input);
  });

  /**
   * Tempdoc 864 Layer 2(d) — the palette is a modal the platform does not know about (window-scoped,
   * not a native `<dialog>`), so it must enter the app's ONE modality authority itself. That entry is
   * what every global key handler reads to stand down; `Sv3Main.navigation.test.ts` asserts the
   * consuming half (`j` under an open palette).
   */
  it('864: owns modality while open, and releases it on both exits', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    expect(modalOwnsFocus(), 'an open palette does not claim the keyboard').toBe(true);
    palette.hide();
    expect(modalOwnsFocus(), 'hide() left modality entered').toBe(false);

    await palette.show(null);
    expect(modalOwnsFocus()).toBe(true);
    palette.dismiss();
    expect(modalOwnsFocus(), 'dismiss() left modality entered').toBe(false);
  });

  /**
   * Tempdoc 864 review F1 — the THIRD exit, and the one with no caller to remember it: a palette torn
   * down while still open (surface swap, window unmount) never runs `hide()` or `dismiss()`.
   * `ModalityController.hostDisconnected` is what releases the count there, and post-864 a leaked
   * count is not a stuck scroll-lock any more — it is EVERY global key dead for the rest of the
   * session, silently. Both sibling controllers pin their own teardown release
   * (`transientController.test.ts` "hostDisconnected unregisters"); this is modality's.
   */
  it('864 F1: a palette torn down while OPEN releases modality (no leaked count)', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    expect(modalOwnsFocus(), 'the case needs an open palette to tear down').toBe(true);

    palette.remove();
    await el.updateComplete;
    expect(modalOwnsFocus(), 'a torn-down palette leaked its modality — every global key is now dead').toBe(
      false,
    );
  });
});

/**
 * The spec's two-state distinction, rendered: SELECTED is the current choice and HIGHLIGHTED is
 * where the keyboard is. They are different rows in the default fixture, which is the only way the
 * distinction is observable at all.
 */
describe('selection and highlight are two different states', () => {
  const itemsOf = (palette: Sv3Palette): HTMLElement[] => [
    ...(palette.shadowRoot?.querySelectorAll<HTMLElement>('[data-testid="sv3-palette-item"]') ?? []),
  ];

  it('starts with the highlight on the first row and the selection on the fixture choice', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const items = itemsOf(palette);
    const highlighted = items.filter((i) => i.hasAttribute('data-highlighted'));
    const selected = items.filter((i) => i.hasAttribute('data-selected'));
    expect(highlighted).toHaveLength(1);
    expect(selected).toHaveLength(1);
    expect(highlighted[0]).toBe(items[0]);
    expect(highlighted[0]).not.toBe(selected[0]);
    // Selection is the ARIA-visible one; the highlight travels as the active descendant instead.
    expect(selected[0]?.getAttribute('aria-selected')).toBe('true');
    expect(palette.shadowRoot?.querySelector('input')?.getAttribute('aria-activedescendant')).toBe(
      items[0]?.id,
    );
  });

  it('moves the highlight with the arrow keys and wraps at both ends', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const input = palette.shadowRoot?.querySelector('input');
    const press = async (key: string): Promise<void> => {
      input?.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true }));
      await palette.updateComplete;
    };
    await press('ArrowDown');
    expect(itemsOf(palette).findIndex((i) => i.hasAttribute('data-highlighted'))).toBe(1);
    await press('ArrowUp');
    await press('ArrowUp');
    // Wrapped past the top onto the last row — the list is a ring, not a dead end.
    expect(itemsOf(palette).findIndex((i) => i.hasAttribute('data-highlighted'))).toBe(
      COMMANDS.length - 1,
    );
    await press('Home');
    expect(itemsOf(palette).findIndex((i) => i.hasAttribute('data-highlighted'))).toBe(0);
    await press('End');
    expect(itemsOf(palette).findIndex((i) => i.hasAttribute('data-highlighted'))).toBe(
      COMMANDS.length - 1,
    );
  });

  it('runs the highlighted command on Enter, which becomes the new selection', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const runs: string[] = [];
    el.addEventListener('sv3-palette-run', (e) =>
      runs.push((e as CustomEvent<{ id: string }>).detail.id),
    );
    const input = palette.shadowRoot?.querySelector('input');
    input?.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await palette.updateComplete;
    expect(runs).toEqual([COMMANDS[0]?.id]);
    expect(palette.open).toBe(false);

    const reopened = await openViaTopbar(el);
    const selected = itemsOf(reopened.palette).filter((i) => i.hasAttribute('data-selected'));
    expect(selected).toHaveLength(1);
    expect(selected[0]?.id).toBe(`sv3-palette-item-${COMMANDS[0]?.id}`);
  });

  it('filters on the query and shows the spec empty row when nothing matches', async () => {
    const el = await mount();
    const { palette } = await openViaTopbar(el);
    const input = palette.shadowRoot?.querySelector('input');
    input!.value = 'reindex';
    input!.dispatchEvent(new Event('input'));
    await palette.updateComplete;
    expect(itemsOf(palette)).toHaveLength(1);
    // A group with no surviving command drops its label too, rather than heading an empty run.
    expect(palette.shadowRoot?.querySelectorAll('[data-testid="sv3-palette-group-label"]'))
      .toHaveLength(1);
    expect(palette.shadowRoot?.querySelectorAll('[data-testid="sv3-palette-separator"]'))
      .toHaveLength(0);

    input!.value = 'zzzz';
    input!.dispatchEvent(new Event('input'));
    await palette.updateComplete;
    expect(itemsOf(palette)).toHaveLength(0);
    expect(palette.shadowRoot?.querySelector('[data-testid="sv3-palette-empty"]')).toBeTruthy();
  });

  it('reopens on a clean query, so a stale filter never greets the next invocation', async () => {
    const el = await mount();
    const first = await openViaTopbar(el);
    const input = first.palette.shadowRoot?.querySelector('input');
    input!.value = 'reindex';
    input!.dispatchEvent(new Event('input'));
    await first.palette.updateComplete;
    first.palette.hide();
    await first.palette.updateComplete;

    const again = await openViaTopbar(el);
    expect(again.palette.shadowRoot?.querySelector('input')?.value).toBe('');
    expect(itemsOf(again.palette)).toHaveLength(COMMANDS.length);
  });
});

/** One element applied twice — the sidebar with no threads, the surface with none. */
describe('the empty states are one pattern in two regions', () => {
  it('renders the sidebar variant before this window has searched anything', async () => {
    // Phase A2: the sidebar's zero state is reached the real way — a window with no sessions yet —
    // rather than through the retired `fixtures="empty"` dev handle.
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const empty = sidebar.shadowRoot?.querySelector('[data-testid="sv3-sidebar-empty"]') as
      | (Sv3Empty & Mounted)
      | null;
    expect(empty).toBeTruthy();
    await empty!.updateComplete;
    expect(empty!.shadowRoot?.querySelector('[data-testid="sv3-empty-title"]')?.textContent?.trim())
      .toBe(SIDEBAR_EMPTY.title);
    expect(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-row"]')).toHaveLength(0);
  });

  // The content surface's twin of this case moved to `SearchV3View.search.test.ts` in Phase A1: its
  // zero state is now a real empty RESULT SET, not an emptied fixture list, so it is asserted where
  // a response can be given to it.

  it('builds the media as a fanned three-card stack, two of them hidden from assistive tech', async () => {
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const empty = sidebar.shadowRoot?.querySelector('jf-sv3-empty') as Sv3Empty & Mounted;
    await empty.updateComplete;
    const tiles = [...(empty.shadowRoot?.querySelectorAll('.tile') ?? [])];
    expect(tiles).toHaveLength(3);
    expect(tiles.filter((t) => t.getAttribute('aria-hidden') === 'true')).toHaveLength(2);
    // The front tile is the only one in the accessibility tree, and the only one behind the glyph.
    expect(empty.shadowRoot?.querySelector('[data-testid="sv3-empty-tile"]')?.getAttribute('aria-hidden'))
      .toBeNull();
  });

  it('yields to the rows the moment the panel has a session to show', async () => {
    // The empty state is a zero state, not a background: one group is enough to retire it. The
    // panel is driven directly here because what is under test is the panel's own either/or.
    const sidebar = document.createElement('jf-sv3-sidebar') as Sv3Sidebar & Mounted;
    document.body.appendChild(sidebar);
    sidebar.groups = [
      {
        id: 'sv3-shelf-recent',
        label: 'Recent',
        rows: [
          {
            id: 'sv3-session-1',
            label: 'northfield lease',
            status: 'resting',
            meta: 'now',
            active: true,
            pinned: false,
            unread: false,
            live: false,
            storeBacked: true,
          },
        ],
      },
    ];
    await sidebar.updateComplete;
    expect(sidebar.shadowRoot?.querySelector('jf-sv3-empty')).toBeNull();
    expect(sidebar.shadowRoot?.querySelectorAll('[data-testid="sv3-sidebar-row"]')).toHaveLength(1);

    // The content surface docked without a search still claims nothing — no zero-results verdict.
    const el = await mount();
    const main = await region(el, 'jf-sv3-main');
    await el.setComposerState('docked');
    await main.updateComplete;
    expect(main.shadowRoot?.querySelector('jf-sv3-empty')).toBeNull();
  });

  it('keeps the empty state out of the heading outline the shell already owns', async () => {
    // The shell's single-<h1> closure is a real gate; a zero state must not mint a second one.
    const el = await mount();
    const sidebar = await region(el, 'jf-sv3-sidebar');
    const empty = sidebar.shadowRoot?.querySelector('jf-sv3-empty') as Sv3Empty & Mounted;
    await empty.updateComplete;
    expect(empty.shadowRoot?.querySelector('h1, h2, h3')).toBeNull();
  });
});

describe('the slice-4 elements join the closed component vocabulary', () => {
  it('names the palette and the empty state', () => {
    expect(COMPONENT_TAGS).toContain('jf-sv3-palette');
    expect(COMPONENT_TAGS).toContain('jf-sv3-empty');
  });

  it('keeps the exported event names equal to the literals the templates bind', () => {
    // A Lit template cannot interpolate an event NAME, so each wire exists twice: once as the
    // exported constant a listener imports, once as the literal in the template. Renaming one
    // silently unhooks the affordance — nothing else in the suite would notice.
    // (The other half — that the templates really bind these wires — is the behaviour above: the
    // topbar click opens the palette, and Enter is heard on the host under the literal name.)
    expect(SV3_PALETTE_REQUEST).toBe('sv3-palette-request');
    expect(SV3_PALETTE_RUN).toBe('sv3-palette-run');
  });
});
