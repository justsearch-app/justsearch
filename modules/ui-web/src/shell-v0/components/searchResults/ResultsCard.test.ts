// @vitest-environment happy-dom

/**
 * ResultsCard.test.ts — Search Thread S1: the ONE search-result card
 * (`<jf-results-card>`, S1 decision 4) owns the presentation both the
 * standalone SearchSurface and UnifiedChatView's retrieve tier used to render
 * themselves. New coverage this file locks in:
 *
 *  (a) count coherence — the shown>matched headline never contradicts the
 *      rendered rows, and the matched-count noun is singular at 1.
 *  (b) the multi-select click model (plain/shift-range/ctrl-toggle, anchor
 *      tracking) as EVENT assertions — `card-selection`/`card-open` — since
 *      selectionState publishing moved to the hosts (ports
 *      SearchSurface.multiSelect.test.ts's scenarios).
 *  (c) facet chips: projection, dismissable selections, and the
 *      `card-facet-toggle` event on click (ports SearchSurface.facets.test.ts).
 *  (d) the terminal "refined ✓" stamp (the two-stage search's missing third
 *      act): appears once a refining/quick pass settles with results, and
 *      auto-hides after 4s.
 *  (e) the Ask AI `jf-control`: absent when `askAvailability` is null,
 *      rendered (not a plain button) when unavailable, and emits
 *      `card-ask-ai` on activation when available.
 *  (f) the quick-pass badge.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './ResultsCard.js';
import type {
  ResultsCard,
  CardHit,
  CardSelectionDetail,
  CardSnapshot,
  SearchProvenance,
} from './ResultsCard.js';
import type { Availability } from '../../state/availability.js';

function hit(id: string, extra: Partial<CardHit> = {}): CardHit {
  return { id, title: `Title ${id}`, path: `/docs/${id}.md`, ...extra };
}

const BASE: CardSnapshot = {
  query: 'pipeline',
  results: [],
  matchCount: 0,
  totalHits: 0,
  facetsTruncated: false,
  isSearching: false,
  processingTimeMs: null,
  error: null,
};

interface MountOpts {
  selectedIds?: ReadonlySet<string>;
  askAvailability?: Availability | null;
  facetSelections?: Record<string, string[]>;
  variant?: 'live' | 'snapshot' | 'excerpt';
  provenance?: SearchProvenance | null;
}

async function mount(snapshot: CardSnapshot, opts: MountOpts = {}): Promise<ResultsCard> {
  const el = document.createElement('jf-results-card') as ResultsCard;
  el.snapshot = snapshot;
  if (opts.selectedIds) el.selectedIds = opts.selectedIds;
  if (opts.askAvailability !== undefined) el.askAvailability = opts.askAvailability;
  if (opts.facetSelections) el.facetSelections = opts.facetSelections;
  if (opts.variant) el.variant = opts.variant;
  if (opts.provenance !== undefined) el.provenance = opts.provenance;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const PROVENANCE: SearchProvenance = {
  actor: 'user',
  query: 'pipeline',
  mode: 'HYBRID',
  matchCount: 5,
  resultCount: 5,
  executedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
};

function meta(el: ResultsCard): Element {
  const m = el.shadowRoot?.querySelector('[data-testid="card-meta"]');
  if (!m) throw new Error('card-meta not rendered');
  return m;
}

function row(el: ResultsCard, id: string): HTMLElement {
  const r = el.shadowRoot?.querySelector(`[data-addressable-id="${id}"]`);
  if (!r) throw new Error(`row ${id} not rendered`);
  return r as HTMLElement;
}

function clickRow(el: ResultsCard, id: string, init: MouseEventInit = {}): void {
  row(el, id).dispatchEvent(
    new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, ...init }),
  );
}

function collectSelections(el: ResultsCard): CardSelectionDetail[] {
  const log: CardSelectionDetail[] = [];
  el.addEventListener('card-selection', (e) => log.push((e as CustomEvent<CardSelectionDetail>).detail));
  return log;
}

beforeEach(() => {
  document.body.innerHTML = '';
});

describe('ResultsCard — count coherence (Search Thread S1)', () => {
  it('names both counts instead of contradicting the rendered rows when shown > matched', async () => {
    const el = await mount({
      ...BASE,
      matchCount: 1,
      totalHits: 4,
      results: [hit('a'), hit('b'), hit('c'), hit('d')],
    });
    expect(meta(el).textContent).toContain('4 results · 1 matched exactly');
    expect(meta(el).textContent).not.toContain('1 matches');
  });

  it('collapses to the singular "1 match" when matchCount=1 and exactly one row is shown', async () => {
    const el = await mount({ ...BASE, matchCount: 1, totalHits: 1, results: [hit('a')] });
    expect(meta(el).textContent).toContain('1 match');
    expect(meta(el).textContent).not.toContain('1 matches');
  });
});

describe('ResultsCard — multi-select event model (ports SearchSurface.multiSelect.test.ts, tempdoc 508-followup §γ4)', () => {
  const HITS = [hit('a'), hit('b'), hit('c'), hit('d')];
  const snapshotWith = (results: CardHit[]): CardSnapshot => ({
    ...BASE,
    matchCount: results.length,
    totalHits: results.length,
    results,
  });

  it('plain click emits card-selection {ids:[id]} and card-open', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);
    const opens: string[] = [];
    el.addEventListener('card-open', (e) => opens.push((e as CustomEvent<{ id: string }>).detail.id));

    clickRow(el, 'b');

    expect(selections).toEqual([{ ids: ['b'], primaryId: 'b', primaryIndex: 1 }]);
    expect(opens).toEqual(['b']);
  });

  it('a subsequent plain click emits a replaced single-id selection', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);

    clickRow(el, 'a');
    clickRow(el, 'c');

    expect(selections.at(-1)).toEqual({ ids: ['c'], primaryId: 'c', primaryIndex: 2 });
  });

  it('shift-click emits the anchor..clicked range', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);

    clickRow(el, 'a'); // anchor on a (index 0)
    clickRow(el, 'c', { shiftKey: true }); // range a..c

    const last = selections.at(-1)!;
    expect([...last.ids].sort()).toEqual(['a', 'b', 'c']);
  });

  it('shift-click range is direction-agnostic', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);

    clickRow(el, 'd'); // anchor on d
    clickRow(el, 'b', { shiftKey: true }); // range b..d

    expect([...selections.at(-1)!.ids].sort()).toEqual(['b', 'c', 'd']);
  });

  it('ctrl-click toggles membership on then off', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);
    // The card reads its OWN `.selectedIds` property to decide ctrl-toggle membership —
    // simulate the real host round-trip (host re-renders with the emitted ids).
    el.addEventListener('card-selection', (e) => {
      el.selectedIds = new Set((e as CustomEvent<CardSelectionDetail>).detail.ids);
    });

    clickRow(el, 'a');
    await el.updateComplete;
    clickRow(el, 'c', { ctrlKey: true });
    await el.updateComplete;
    expect([...selections.at(-1)!.ids].sort()).toEqual(['a', 'c']);

    clickRow(el, 'c', { ctrlKey: true });
    await el.updateComplete;
    expect(selections.at(-1)!.ids).toEqual(['a']);
  });

  it('meta-click behaves like ctrl-click', async () => {
    const el = await mount(snapshotWith(HITS));
    const selections = collectSelections(el);
    el.addEventListener('card-selection', (e) => {
      el.selectedIds = new Set((e as CustomEvent<CardSelectionDetail>).detail.ids);
    });

    clickRow(el, 'a');
    await el.updateComplete;
    clickRow(el, 'd', { metaKey: true });
    await el.updateComplete;

    expect([...selections.at(-1)!.ids].sort()).toEqual(['a', 'd']);
  });
});

describe('ResultsCard — selection-mode="explicit": opening a hit never discards the choice (WP1.1 S1.5)', () => {
  const HITS = [hit('a'), hit('b'), hit('c'), hit('d')];
  const snapshotWith = (results: CardHit[]): CardSnapshot => ({
    ...BASE,
    matchCount: results.length,
    totalHits: results.length,
    results,
  });

  async function mountExplicit(): Promise<{ el: ResultsCard; selections: CardSelectionDetail[]; opens: string[] }> {
    const el = await mount(snapshotWith(HITS));
    el.selectionMode = 'explicit';
    await el.updateComplete;
    const selections = collectSelections(el);
    // The host round-trip, as in the default-mode cases above.
    el.addEventListener('card-selection', (e) => {
      el.selectedIds = new Set((e as CustomEvent<CardSelectionDetail>).detail.ids);
    });
    const opens: string[] = [];
    el.addEventListener('card-open', (e) => opens.push((e as CustomEvent<{ id: string }>).detail.id));
    return { el, selections, opens };
  }

  afterEach(() => {
    for (const menu of [...document.querySelectorAll('jf-context-menu')]) menu.remove();
  });

  it('a plain click opens and leaves the selection alone', async () => {
    const { el, selections, opens } = await mountExplicit();
    clickRow(el, 'a', { ctrlKey: true });
    clickRow(el, 'b', { ctrlKey: true });
    await el.updateComplete;
    clickRow(el, 'c');
    expect(opens).toEqual(['c']);
    expect(selections.at(-1)?.ids).toEqual(['a', 'b']);
    expect(selections).toHaveLength(2);
  });

  it('Ctrl and Shift choose without opening, and Shift ranges from the row read last', async () => {
    const { el, selections, opens } = await mountExplicit();
    clickRow(el, 'b');
    clickRow(el, 'd', { shiftKey: true });
    expect(opens).toEqual(['b']);
    expect([...selections.at(-1)!.ids].sort()).toEqual(['b', 'c', 'd']);
  });

  it('the row menu opens without choosing, and offers choosing to the keyboard', async () => {
    const { el, selections, opens } = await mountExplicit();
    const menuEntry = async (id: string, label: string): Promise<HTMLButtonElement> => {
      row(el, id).querySelector<HTMLButtonElement>('[data-testid="row-actions"]')?.click();
      await new Promise((r) => setTimeout(r, 0));
      const menu = document.querySelector('jf-context-menu') as (HTMLElement & { updateComplete: Promise<unknown> }) | null;
      await menu?.updateComplete;
      const item = [...(menu?.shadowRoot?.querySelectorAll<HTMLButtonElement>('button.item') ?? [])].find((b) =>
        (b.textContent ?? '').includes(label),
      );
      if (!item) throw new Error(`no menu entry ${label}`);
      return item;
    };
    (await menuEntry('a', 'Add to chosen files')).click();
    await new Promise((r) => setTimeout(r, 0));
    await el.updateComplete;
    expect(selections.at(-1)?.ids).toEqual(['a']);
    expect(opens).toEqual([]);

    (await menuEntry('b', 'Open document')).click();
    await new Promise((r) => setTimeout(r, 0));
    expect(opens).toEqual(['b']);
    expect(selections).toHaveLength(1);

    (await menuEntry('a', 'Remove from chosen files')).click();
    await new Promise((r) => setTimeout(r, 0));
    expect(selections.at(-1)?.ids).toEqual([]);
  });
});

describe('ResultsCard — facet chips (ports SearchSurface.facets.test.ts, tempdoc 577 Phase 6 Move E)', () => {
  const FACET_SNAPSHOT: CardSnapshot = {
    ...BASE,
    matchCount: 25,
    totalHits: 25,
    results: [hit('a')],
    facets: { file_kind: { markdown: 20, code: 8 }, language: { en: 25 } },
  };

  it('projects chips from the emitted facet counts, grouped and labeled', async () => {
    const el = await mount(FACET_SNAPSHOT);
    const rowEl = el.shadowRoot?.querySelector('[data-testid="facet-row"]');
    expect(rowEl).not.toBeNull();
    const chips = Array.from(rowEl!.querySelectorAll('.facet-chip')).map((c) => ({
      text: c.textContent ?? '',
      count: c.querySelector('.facet-count')?.textContent ?? null,
    }));
    expect(chips.some((c) => c.text.startsWith('markdown') && c.count === '20')).toBe(true);
    expect(chips.some((c) => c.text.startsWith('code') && c.count === '8')).toBe(true);
    expect(chips.some((c) => c.text.startsWith('en') && c.count === '25')).toBe(true);
    const groups = Array.from(rowEl!.querySelectorAll('.facet-group-label')).map((g) => g.textContent);
    expect(groups).toContain('Type');
    expect(groups).toContain('Language');
  });

  it('renders nothing when the response carried no facets and nothing is selected', async () => {
    const el = await mount({ ...BASE, results: [hit('a')], facets: null });
    expect(el.shadowRoot?.querySelector('[data-testid="facet-row"]')).toBeNull();
  });

  it('a selected value absent from the current counts still renders (dismissable)', async () => {
    const el = await mount(FACET_SNAPSHOT, { facetSelections: { file_kind: ['pdf'] } });
    const chips = Array.from(el.shadowRoot?.querySelectorAll('.facet-chip') ?? []).map((c) =>
      c.textContent?.trim(),
    );
    expect(chips.some((t) => t?.startsWith('pdf'))).toBe(true);
  });

  it('a chip click emits card-facet-toggle with the field/value', async () => {
    const el = await mount(FACET_SNAPSHOT);
    const events: Array<{ field: string; value: string }> = [];
    el.addEventListener('card-facet-toggle', (e) =>
      events.push((e as CustomEvent<{ field: string; value: string }>).detail),
    );
    const chip = Array.from(el.shadowRoot?.querySelectorAll('.facet-chip') ?? []).find((c) =>
      c.textContent?.includes('markdown'),
    ) as HTMLButtonElement;

    chip.click();

    expect(events).toEqual([{ field: 'file_kind', value: 'markdown' }]);
  });
});

describe('ResultsCard — the terminal "refined ✓" stamp (Search Thread S1)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('appears once a refining pass settles with results, and auto-hides after 4s', async () => {
    vi.useFakeTimers();
    const results = [hit('a'), hit('b')];
    const el = await mount({ ...BASE, matchCount: 2, totalHits: 2, results, isRefining: true });

    expect(meta(el).querySelector('[data-testid="meta-refining"]')).not.toBeNull();
    expect(meta(el).querySelector('[data-testid="meta-refined"]')).toBeNull();

    el.snapshot = { ...BASE, matchCount: 2, totalHits: 2, results, isRefining: false };
    await el.updateComplete;
    expect(meta(el).querySelector('[data-testid="meta-refined"]')).not.toBeNull();

    vi.advanceTimersByTime(4000);
    await el.updateComplete;
    expect(meta(el).querySelector('[data-testid="meta-refined"]')).toBeNull();
  });

  it('does not arm from a quiescent (never-refining) settle', async () => {
    const el = await mount({ ...BASE, matchCount: 1, totalHits: 1, results: [hit('a')] });
    expect(meta(el).querySelector('[data-testid="meta-refined"]')).toBeNull();
  });
});

describe('ResultsCard — Ask AI (jf-control availability)', () => {
  const snapshot: CardSnapshot = { ...BASE, matchCount: 1, totalHits: 1, results: [hit('a')] };

  it('hides the Ask AI control when askAvailability is null', async () => {
    const el = await mount(snapshot, { askAvailability: null });
    expect(el.shadowRoot?.querySelector('.ask-ai-btn')).toBeNull();
  });

  it('renders the jf-control (not a plain enabled button) when unavailable', async () => {
    const el = await mount(snapshot, {
      askAvailability: { kind: 'unavailable', reason: 'AI is offline' },
    });
    const control = el.shadowRoot?.querySelector('.ask-ai-btn');
    expect(control).not.toBeNull();
    expect(control!.tagName.toLowerCase()).toBe('jf-control');
  });

  it('activating an available control emits card-ask-ai with the current query and shiftKey:false', async () => {
    const el = await mount(
      { ...snapshot, query: 'pipeline' },
      { askAvailability: { kind: 'available' } },
    );
    const events: Array<{ query: string; shiftKey: boolean }> = [];
    el.addEventListener('card-ask-ai', (e) =>
      events.push((e as CustomEvent<{ query: string; shiftKey: boolean }>).detail),
    );

    const control = el.shadowRoot?.querySelector('.ask-ai-btn') as
      | (HTMLElement & { updateComplete: Promise<boolean> })
      | null;
    expect(control).not.toBeNull();
    await control!.updateComplete;
    const btn = control!.shadowRoot?.querySelector('button') as HTMLButtonElement;
    btn.click();

    expect(events).toEqual([{ query: 'pipeline', shiftKey: false }]);
  });

  // Search Thread Round-2 R2 — jf-control's onActivate carries no event, so the modifier is captured
  // via a CAPTURE-phase click listener on the composed jf-control (fires before its own bubble-phase
  // click → activate()), the same shift-detection idiom handleRowClick uses for row clicks.
  it('a shift-held activation emits card-ask-ai with shiftKey:true', async () => {
    const el = await mount(
      { ...snapshot, query: 'pipeline' },
      { askAvailability: { kind: 'available' } },
    );
    const events: Array<{ query: string; shiftKey: boolean }> = [];
    el.addEventListener('card-ask-ai', (e) =>
      events.push((e as CustomEvent<{ query: string; shiftKey: boolean }>).detail),
    );

    const control = el.shadowRoot?.querySelector('.ask-ai-btn') as
      | (HTMLElement & { updateComplete: Promise<boolean> })
      | null;
    expect(control).not.toBeNull();
    await control!.updateComplete;
    const btn = control!.shadowRoot?.querySelector('button') as HTMLButtonElement;
    btn.dispatchEvent(
      new MouseEvent('click', { bubbles: true, cancelable: true, composed: true, shiftKey: true }),
    );

    expect(events).toEqual([{ query: 'pipeline', shiftKey: true }]);
  });
});

describe('ResultsCard — quick badge', () => {
  it('shows meta-quick during the quick pass', async () => {
    const el = await mount({
      ...BASE,
      matchCount: 1,
      totalHits: 1,
      results: [hit('a')],
      passStage: 'quick',
    });
    expect(el.shadowRoot?.querySelector('[data-testid="meta-quick"]')).not.toBeNull();
  });
});

describe('ResultsCard — variant="snapshot" (Search Thread S4-final)', () => {
  const SNAPSHOT_HITS = [hit('a'), hit('b'), hit('c'), hit('d')];

  it('renders the provenance header instead of the live meta line', async () => {
    const el = await mount(
      { ...BASE, results: SNAPSHOT_HITS, matchCount: 5, totalHits: 5 },
      { variant: 'snapshot', provenance: PROVENANCE },
    );
    const header = el.shadowRoot?.querySelector('[data-testid="card-provenance"]');
    expect(header).not.toBeNull();
    expect(header!.textContent).toContain('You');
    expect(header!.textContent).toContain('searched');
    expect(header!.textContent).toContain('"pipeline"');
    // Tempdoc 738 (C2) — Simple mode (default) shows the plain retrieval-mode label.
    expect(header!.textContent).toContain('meaning + words');
    expect(el.shadowRoot?.querySelector('[data-testid="card-meta"]')).toBeNull();
  });

  it('collapses to the top 3 rows with a "Show all N" expander that toggles to every row', async () => {
    const el = await mount(
      { ...BASE, results: SNAPSHOT_HITS, matchCount: 4, totalHits: 4 },
      { variant: 'snapshot', provenance: PROVENANCE },
    );
    expect(el.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]').length).toBe(3);
    const expander = el.shadowRoot?.querySelector('[data-testid="snapshot-expander"]') as HTMLButtonElement;
    expect(expander).not.toBeNull();
    expect(expander.textContent).toContain('Show all 4');

    expander.click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]').length).toBe(4);
    expect(expander.textContent).toContain('Show less');
  });

  it('has no copy actions / facets / Ask AI (a frozen record)', async () => {
    const el = await mount(
      { ...BASE, results: SNAPSHOT_HITS, matchCount: 4, totalHits: 4, facets: { file_kind: { markdown: 4 } } },
      { variant: 'snapshot', provenance: PROVENANCE, askAvailability: { kind: 'available' } },
    );
    expect(el.shadowRoot?.querySelector('[data-testid="copy-actions"]')).toBeNull();
    expect(el.shadowRoot?.querySelector('[data-testid="facet-row"]')).toBeNull();
    expect(el.shadowRoot?.querySelector('.ask-ai-btn')).toBeNull();
  });

  it('rows are openable (card-open) but not selectable (no card-selection, no context menu)', async () => {
    const el = await mount(
      { ...BASE, results: SNAPSHOT_HITS, matchCount: 4, totalHits: 4 },
      { variant: 'snapshot', provenance: PROVENANCE },
    );
    const opens: string[] = [];
    const selections = collectSelections(el);
    el.addEventListener('card-open', (e) => opens.push((e as CustomEvent<{ id: string }>).detail.id));

    clickRow(el, 'b');

    expect(opens).toEqual(['b']);
    expect(selections).toEqual([]);
    expect(el.shadowRoot?.querySelector('[data-testid="row-actions"]')).toBeNull();
  });

  it('the "Search again" affordance dispatches a bubbling card-fork with the frozen query', async () => {
    const el = await mount(
      { ...BASE, results: SNAPSHOT_HITS, matchCount: 4, totalHits: 4 },
      { variant: 'snapshot', provenance: PROVENANCE },
    );
    const forks: Array<{ query: string }> = [];
    document.body.addEventListener('card-fork', (e) => forks.push((e as CustomEvent<{ query: string }>).detail));

    (el.shadowRoot?.querySelector('[data-testid="card-fork-btn"]') as HTMLButtonElement).click();

    expect(forks).toEqual([{ query: 'pipeline' }]);
  });

  it('renders the honest empty note (not fabricated rows) when no hits are stored', async () => {
    const el = await mount(
      { ...BASE, results: [], matchCount: 4, totalHits: 4 },
      { variant: 'snapshot', provenance: PROVENANCE },
    );
    expect(el.shadowRoot?.querySelector('[data-testid="snapshot-empty-note"]')?.textContent).toContain(
      'results not stored',
    );
    expect(el.shadowRoot?.querySelector('[data-testid="search-result-row"]')).toBeNull();
  });
});

describe('ResultsCard — variant="excerpt" (Search Thread S4-final)', () => {
  it('renders one collapsed line summarizing the query + result count', async () => {
    const el = await mount(
      { ...BASE, results: [hit('a'), hit('b')], matchCount: 2, totalHits: 2 },
      { variant: 'excerpt', provenance: { ...PROVENANCE, resultCount: 2 } },
    );
    const btn = el.shadowRoot?.querySelector('[data-testid="card-excerpt"]');
    expect(btn).not.toBeNull();
    expect(btn!.textContent).toContain('pipeline');
    expect(btn!.textContent).toContain('2 result');
    expect(el.shadowRoot?.querySelector('[data-testid="card-snapshot"]')).toBeNull();
  });

  it('expands in place to the full snapshot rendering on click', async () => {
    const el = await mount(
      { ...BASE, results: [hit('a'), hit('b')], matchCount: 2, totalHits: 2 },
      { variant: 'excerpt', provenance: { ...PROVENANCE, resultCount: 2 } },
    );
    (el.shadowRoot?.querySelector('[data-testid="card-excerpt"]') as HTMLButtonElement).click();
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('[data-testid="card-snapshot"]')).not.toBeNull();
    expect(el.shadowRoot?.querySelectorAll('[data-testid="search-result-row"]').length).toBe(2);
    const collapse = el.shadowRoot?.querySelector('[data-testid="excerpt-collapse"]') as HTMLButtonElement;
    expect(collapse).not.toBeNull();

    collapse.click();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('[data-testid="card-excerpt"]')).not.toBeNull();
  });

  it('a restored thread event (no persisted hits) expands to the honesty note, not fabricated rows', async () => {
    const el = await mount(
      { ...BASE, results: [], matchCount: 3, totalHits: 3 },
      { variant: 'excerpt', provenance: { ...PROVENANCE, resultCount: 3 } },
    );
    (el.shadowRoot?.querySelector('[data-testid="card-excerpt"]') as HTMLButtonElement).click();
    await el.updateComplete;

    expect(el.shadowRoot?.querySelector('[data-testid="snapshot-empty-note"]')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('[data-testid="card-fork-btn"]')).not.toBeNull();
  });
});

/**
 * Tempdoc 811 (C-1a) — the corpus marker on the row. `docs/reference/search-ui-behavior.md`
 * (§Result Row Anatomy 9, §Built-in Searchable Help Content) documents a teal "Help" pill for
 * `collection === 'justsearch-help'`; this card is the ONE render path, so the same pill also
 * covers the retrieve tier and the agent tool-search excerpt.
 */
describe('ResultsCard — collection pill (811 C-1a)', () => {
  const pill = (el: ResultsCard, id: string): HTMLElement | null =>
    row(el, id).querySelector('[data-testid="collection-pill"]');

  it('marks a justsearch-help hit with the teal Help pill', async () => {
    const el = await mount({
      ...BASE,
      matchCount: 1,
      totalHits: 1,
      results: [hit('a', { collection: 'justsearch-help' })],
    });
    const p = pill(el, 'a');
    expect(p?.textContent?.trim()).toBe('Help');
    expect(p?.getAttribute('data-tone')).toBe('help');
    expect(p?.getAttribute('data-collection')).toBe('justsearch-help');
  });

  it('leaves the user\'s own documents unmarked (absent and `default` collection alike)', async () => {
    const el = await mount({
      ...BASE,
      matchCount: 2,
      totalHits: 2,
      results: [hit('a'), hit('b', { collection: 'default' })],
    });
    expect(pill(el, 'a')).toBeNull();
    expect(pill(el, 'b')).toBeNull();
  });

  it('marks any other named collection with the neutral pill (per-collection, not one string)', async () => {
    const el = await mount({
      ...BASE,
      matchCount: 2,
      totalHits: 2,
      results: [hit('a', { collection: 'agent-history' }), hit('b', { collection: 'mcp-ingest' })],
    });
    expect(pill(el, 'a')?.textContent?.trim()).toBe('Agent history');
    expect(pill(el, 'a')?.getAttribute('data-tone')).toBe('neutral');
    // An unregistered collection still marks — by its own name.
    expect(pill(el, 'b')?.textContent?.trim()).toBe('mcp-ingest');
    expect(pill(el, 'b')?.getAttribute('data-tone')).toBe('neutral');
  });
});
