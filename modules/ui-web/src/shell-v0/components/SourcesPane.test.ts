// @vitest-environment happy-dom
//
// Tempdoc 565 §3.A — the sources pane + its open-store: the answer's grounding sources render as
// clickable local-passage cards that dispatch citation-select (the deep-link).

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// These projections do not exercise their incidental backend reads.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import './SourcesPane.js';
import type { SourcesPane } from './SourcesPane.js';
import {
  getAgentSessionController,
  __resetAgentSessionStore,
} from '../state/agentSessionStore.js';
import { isSourcesOpen, toggleSources, __resetSourcesDrawer } from '../state/sourcesDrawer.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../state/selectedSource.js';
import type { CitationSelectDetail } from './chat/citationTypes.js';
import {
  setExcludedSources,
  sourceExcludeKey,
  __resetExcludedSources,
} from '../state/excludedSources.js';

afterEach(() => {
  __resetAgentSessionStore();
  __resetSourcesDrawer();
  __resetSelectedSource();
  __resetExcludedSources();
});

function seedSource(): void {
  const ctrl = getAgentSessionController('http://x');
  (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
    {
      parentDocId: 'C:/docs/taxes.md',
      chunkIndex: 2,
      path: 'C:/docs/taxes.md',
      title: 'taxes.md',
      excerpt: 'the reliability budget report',
      startLine: 42,
      endLine: 48,
      headingText: 'Budget',
    },
  ];
}

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

describe('sourcesDrawer store', () => {
  it('toggles open state', () => {
    expect(isSourcesOpen()).toBe(false);
    toggleSources();
    expect(isSourcesOpen()).toBe(true);
    toggleSources();
    expect(isSourcesOpen()).toBe(false);
  });
});

describe('SourcesPane (565 §3.A)', () => {
  it('renders the answer sources and dispatches citation-select with the local-passage detail', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      {
        parentDocId: 'C:/docs/taxes.md',
        chunkIndex: 2,
        path: 'C:/docs/taxes.md',
        title: 'taxes.md',
        excerpt: 'the reliability budget report',
        startLine: 42,
        endLine: 48,
        headingText: 'Budget',
      },
    ];

    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('taxes.md');
    expect(text).toContain('line 42');

    let detail: CitationSelectDetail | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent<CitationSelectDetail>).detail;
    });
    const card = el.shadowRoot?.querySelector('[role="button"]') as HTMLElement | null;
    expect(card).not.toBeNull();
    card?.click();

    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('C:/docs/taxes.md');
    expect(detail!.startLine).toBe(42);
    el.remove();
  });

  it('610 §J.3: renders the hide/restore control on each source row + dims hidden sources', async () => {
    seedSource(); // parentDocId C:/docs/taxes.md, chunkIndex 2
    setExcludedSources([sourceExcludeKey('C:/docs/taxes.md', 2)]);
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const hideBtn = el.shadowRoot?.querySelector('.source-hide');
    expect(hideBtn).not.toBeNull();
    // Hidden state → restore glyph + the row dimmed (shared store, consistent with the inline chips).
    expect(hideBtn?.textContent?.trim()).toBe('↺');
    expect(el.shadowRoot?.querySelector('.source.hidden-source')).not.toBeNull();
    el.remove();
  });

  it('603 D-4: a DOCUMENT-LEVEL source (startLine === -1 sentinel) shows NO line locator and still deep-links to the file', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      {
        parentDocId: 'C:/docs/overview.md',
        chunkIndex: -1, // doc-level: no chunk identity
        path: 'C:/docs/overview.md',
        title: 'overview.md',
        excerpt: 'a whole-document provenance source',
        startLine: -1, // sentinel: no precise line
        endLine: -1,
        headingText: '',
      },
    ];

    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);

    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('overview.md');
    // The provenance source must NOT render a fabricated "line -1"/"line 0" locator.
    expect(text).not.toContain('line -1');
    expect(text).not.toContain('line 0');
    expect(text).not.toMatch(/line\s/i);
    expect(el.shadowRoot?.querySelector('.source-loc')).toBeNull();

    // It still deep-links to the file (opened at top — startLine -1 means no precise highlight).
    let detail: CitationSelectDetail | null = null;
    el.addEventListener('citation-select', (e) => {
      detail = (e as CustomEvent<CitationSelectDetail>).detail;
    });
    (el.shadowRoot?.querySelector('[role="button"]') as HTMLElement | null)?.click();
    expect(detail).not.toBeNull();
    expect(detail!.parentDocId).toBe('C:/docs/overview.md');
    expect(detail!.startLine).toBe(-1);
    el.remove();
  });
});

describe('SourcesPane docked rail + cross-highlight (565 §12.3.E)', () => {
  it('docked renders without the open store (always-visible persistent rail) and no Close button', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true; // NOT open — docked is independent of the toggle drawer store.
    document.body.appendChild(el);
    await settle(el);

    expect(isSourcesOpen()).toBe(false);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('taxes.md'); // rendered despite the drawer being closed
    expect(el.shadowRoot?.querySelector('.close')).toBeNull(); // no Close in the docked rail
    el.remove();
  });

  it('a card click sets the shared selection; the matching card highlights', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    document.body.appendChild(el);
    await settle(el);

    const card = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(card?.classList.contains('selected')).toBe(false);
    card?.click();
    // The click set the cross-surface selection to this source's local-passage key.
    expect(getSelectedSource()).toBe(sourceKey('C:/docs/taxes.md', 42));
    await settle(el);
    const after = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(after?.classList.contains('selected')).toBe(true);
    expect(after?.getAttribute('aria-current')).toBe('true');
    el.remove();
  });

  it('814 §D3 — maxVisible bounds the docked INDEX to the top N; the head still states the TOTAL', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [1, 2, 3, 4, 5].map((n) => ({
      parentDocId: `C:/docs/d${n}.md`,
      chunkIndex: n,
      path: `C:/docs/d${n}.md`,
      title: `d${n}.md`,
      excerpt: 'x',
      startLine: n,
      endLine: n + 2,
      headingText: '',
    }));
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    el.maxVisible = 3;
    document.body.appendChild(el);
    await settle(el);

    // Bounded: 3 of 5 cards rendered, in order.
    const cards = [...(el.shadowRoot?.querySelectorAll('.source') ?? [])];
    expect(cards.length).toBe(3);
    expect(cards[0]?.textContent).toContain('d1.md');
    expect(cards[2]?.textContent).toContain('d3.md');
    // §D5 — the head is the source-count AUTHORITY, so it states the total, never the truncation.
    expect(el.shadowRoot?.querySelector('.title')?.textContent).toBe('Sources · 5');
    el.remove();
  });

  it('814 §D3 — the docked index carries an "Open all · N" row that opens the sanctioned drawer', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    el.maxVisible = 3;
    document.body.appendChild(el);
    await settle(el);

    const openAll = el.shadowRoot?.querySelector('.open-all') as HTMLButtonElement | null;
    expect(openAll).not.toBeNull();
    expect(openAll!.tagName).toBe('BUTTON'); // keyboard-operable
    expect(openAll!.textContent?.replace(/\s+/g, ' ').trim()).toBe('Open all · 1');
    expect(isSourcesOpen()).toBe(false);
    openAll!.click();
    // The full list opens in the SAME pane, undocked, in Shell's OverlayHost right-drawer.
    expect(isSourcesOpen()).toBe(true);
    el.remove();
  });

  it('814 §D3 — the DRAWER copy is unbounded and keeps its own scroller (only the docked rail is bounded)', async () => {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [1, 2, 3, 4, 5].map((n) => ({
      parentDocId: `C:/docs/d${n}.md`,
      chunkIndex: n,
      path: `C:/docs/d${n}.md`,
      title: `d${n}.md`,
      excerpt: 'x',
      startLine: n,
      endLine: n + 2,
      headingText: '',
    }));
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true; // the drawer mount — NOT docked, no maxVisible
    document.body.appendChild(el);
    await settle(el);

    expect(el.shadowRoot?.querySelectorAll('.source').length).toBe(5);
    expect(el.shadowRoot?.querySelector('.open-all')).toBeNull(); // the drawer IS the full list
    el.remove();
  });

  it('reflects an EXTERNAL selection (e.g. an inline [n] mark) by highlighting the matching card', async () => {
    seedSource();
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.docked = true;
    document.body.appendChild(el);
    await settle(el);

    // Simulate the answer's inline mark focusing this source.
    setSelectedSource(sourceKey('C:/docs/taxes.md', 42));
    await settle(el);
    const card = el.shadowRoot?.querySelector('.source') as HTMLElement | null;
    expect(card?.classList.contains('selected')).toBe(true);
    el.remove();
  });
});

/**
 * Tempdoc 868 §B.3 — the rail's hide/restore control names the CHANNEL, and the channel is the
 * acquisition axis.
 *
 * "Hide {name} from the assistant's retrieval" is a provenance claim in a place easy to miss: it is
 * an aria-label, so the reader who hears it is the one least able to check it against the card. Over
 * a document the agent opened by name, nothing retrieved anything — the same false claim the badge
 * stopped making, one control over.
 */
describe('SourcesPane — the hide control names the acquisition channel (868 §B.3)', () => {
  function seedWith(acquisition?: string): void {
    const ctrl = getAgentSessionController('http://x');
    (ctrl as unknown as { answerSources: unknown[] }).answerSources = [
      {
        parentDocId: 'C:/docs/handbook.md',
        chunkIndex: 2,
        path: 'C:/docs/handbook.md',
        title: 'handbook.md',
        excerpt: 'a passage',
        startLine: 42,
        endLine: 48,
        headingText: '',
        ...(acquisition === undefined ? {} : { acquisition }),
      },
    ];
  }

  async function hideButton(acquisition?: string, hidden = false): Promise<{
    el: SourcesPane;
    btn: Element | null;
  }> {
    seedWith(acquisition);
    if (hidden) setExcludedSources([sourceExcludeKey('C:/docs/handbook.md', 2)]);
    const el = document.createElement('jf-sources-pane') as SourcesPane;
    el.apiBase = 'http://x';
    el.open = true;
    document.body.appendChild(el);
    await settle(el);
    return { el, btn: el.shadowRoot?.querySelector('.source-hide') ?? null };
  }

  it('an OPENED source is hidden from the assistant\'s READING, never its retrieval', async () => {
    const { el, btn } = await hideButton('opened');
    expect(btn?.getAttribute('aria-label')).toBe("Hide handbook.md from the assistant's reading");
    expect(btn?.getAttribute('title')).toBe('Hide from reading');
    // The refusal, on both surfaces of the control — an aria-label that still said "retrieval" would
    // be the defect surviving exactly where it is hardest to notice.
    expect(btn?.getAttribute('aria-label')).not.toContain('retrieval');
    expect(btn?.getAttribute('title')).not.toContain('retrieval');
    el.remove();
  });

  it('the RESTORE wording follows the same channel', async () => {
    const { el, btn } = await hideButton('opened', true);
    expect(btn?.getAttribute('aria-label')).toBe("Restore handbook.md to the assistant's reading");
    expect(btn?.getAttribute('title')).toBe('Restore to reading');
    el.remove();
  });

  it('a retrieved source keeps the established wording, byte-for-byte', async () => {
    // The control. 610 §J.3's words are unchanged for the case that has always existed — this slice
    // adds a second channel, it does not rename the first.
    const { el, btn } = await hideButton('retrieved');
    expect(btn?.getAttribute('aria-label')).toBe("Hide handbook.md from the assistant's retrieval");
    expect(btn?.getAttribute('title')).toBe('Hide from retrieval');
    el.remove();
  });

  it('a source that reports no acquisition reads as retrieved — the same default the badge uses', async () => {
    const { el, btn } = await hideButton(undefined);
    expect(btn?.getAttribute('aria-label')).toBe("Hide handbook.md from the assistant's retrieval");
    el.remove();
  });
});
