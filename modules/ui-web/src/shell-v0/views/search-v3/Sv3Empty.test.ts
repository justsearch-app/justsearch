// @vitest-environment happy-dom

/**
 * The Search v3 window's two empty states (tempdoc 822 slice 4). Moved out of the retired Search
 * palette's file unchanged when that palette left the window (S6 SP7); the app's one palette is
 * `commands/CommandPalette.ts`.
 */
import { describe, it, expect, afterEach, vi, beforeEach } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
// Side-effect imports, kept separate from the type-only ones below: a class named ONLY in a type
// position is elided from the emitted bundle, and with it the `customElements.define` that makes
// `document.createElement` return anything but an un-upgraded element.
import './SearchV3View.js';
import './Sv3Empty.js';
import './Sv3Sidebar.js';
import type { Sv3Sidebar } from './Sv3Sidebar.js';
import type { SearchV3View } from './SearchV3View.js';
import type { Sv3Empty } from './Sv3Empty.js';
import { SIDEBAR_EMPTY } from './fixtures.js';
import { COMPONENT_TAGS } from '../../renderers/component-vocabulary.generated.js';
import { __resetModalityForTest } from '../../primitives/modality.js';

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

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  vi.restoreAllMocks();
  __resetModalityForTest();
});

/** One element applied twice — the sidebar with no threads, the surface with none. */
describe('the empty states are one pattern in two regions', () => {
  it('renders the isolated sidebar zero state', async () => {
    // R10/D6 omit the empty column from Search. Its reusable zero state remains a component check.
    const sidebar = document.createElement('jf-sv3-sidebar') as Sv3Sidebar; document.body.append(sidebar); await sidebar.updateComplete;
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
    const sidebar = document.createElement('jf-sv3-sidebar') as Sv3Sidebar; document.body.append(sidebar); await sidebar.updateComplete;
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
    const sidebar = document.createElement('jf-sv3-sidebar') as Sv3Sidebar; document.body.append(sidebar); await sidebar.updateComplete;
    const empty = sidebar.shadowRoot?.querySelector('jf-sv3-empty') as Sv3Empty & Mounted;
    await empty.updateComplete;
    expect(empty.shadowRoot?.querySelector('h1, h2, h3')).toBeNull();
  });
});

describe('the slice-4 elements join the closed component vocabulary', () => {
  it('names the empty state, and no longer the retired Search palette', () => {
    expect(COMPONENT_TAGS).toContain('jf-sv3-empty');
    expect(COMPONENT_TAGS).not.toContain('jf-sv3-palette');
  });
});
