// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest';
import { setUiMode, __resetUiModeForTest } from '../../state/uiModeState.js';
import './DocumentPane.js';
import type { DocumentPane } from './DocumentPane.js';
import {
  citationHeader,
  CITATION_SPAN_UNUSABLE,
  type CitationHeader,
} from '../chat/evidenceProjection.js';

function make(): DocumentPane {
  const el = document.createElement('jf-document-pane') as DocumentPane;
  document.body.appendChild(el);
  return el;
}

function stubFetchOnce(response: {
  ok?: boolean;
  status?: number;
  content?: string;
  textProvenance?: string | null;
  visualExtractionEvidence?: Record<string, unknown> | null;
}): void {
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => ({
      ok: response.ok ?? true,
      status: response.status ?? 200,
      json: async () => ({
        content: response.content ?? '',
        textProvenance: response.textProvenance ?? null,
        visualExtractionEvidence: response.visualExtractionEvidence ?? null,
      }),
    })),
  );
}

/**
 * Tempdoc 849 §3 — a fetch stub that behaves like `/api/preview` instead of ignoring the request:
 * it honours `offsetChars`/`maxChars`, echoes the offset it served, and sets `truncated` when more
 * of the document follows. Without this the windowed fetch could not be tested at all — a stub that
 * returns the whole document regardless makes every offset look correct.
 */
function stubDocument(doc: string): { calls: URL[] } {
  const calls: URL[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string) => {
      const url = new URL(input, 'http://localhost');
      calls.push(url);
      const offset = Number(url.searchParams.get('offsetChars') ?? 0);
      const max = Number(url.searchParams.get('maxChars') ?? 5000);
      const content = doc.slice(offset, offset + max);
      return {
        ok: true,
        status: 200,
        json: async () => ({
          content,
          offsetChars: offset,
          maxChars: max,
          truncated: offset + max < doc.length,
          textProvenance: null,
          visualExtractionEvidence: null,
        }),
      };
    }),
  );
  return { calls };
}

async function flush(el: DocumentPane): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, 0));
  await el.updateComplete;
}

/** Click the native button inside a `<jf-button class="icon">`'s composed `<jf-control>`. */
async function clickIconButton(host: Element): Promise<void> {
  const jfButton = host.shadowRoot?.querySelector('jf-button.icon');
  await (jfButton as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const ctrl = jfButton!.shadowRoot!.querySelector('jf-control')!;
  await (ctrl as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (ctrl.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

const MD_FIXTURE = ['# Title', '', 'Paragraph text here.', '', '- item a', '- item b'].join('\n');

const CITATION_FIXTURE = {
  parentDocId: 'notes/thread.md',
  chunkIndex: 3,
  chunkTotal: 9,
  startChar: 10,
  endChar: 40,
  // Deliberately high, and deliberately never rendered: slice-3 review HIGH-1 removed the retrieval
  // band because this is the RAW Lucene hit score, not a value on the grounding tier scale.
  score: 0.9,
  excerpt: 'Paragraph text here.',
  startLine: 2,
  endLine: 2,
  headingText: 'Title',
  headingLevel: 1,
  // PARTIAL, not dropped: the default fixture must be a state that KEEPS its grounding, so the
  // header-content tests below assert the full header rather than the MEDIUM-3 suppressed one.
  contextInclusion: 'partial' as const,
};

/**
 * Tempdoc 849 §7 — the citation header, as the WINDOW would hand it over. Built through the real
 * `citationHeader` projector rather than as a hand-written literal, so a test cannot assert a header
 * shape the product can never produce.
 */
function headerFor(
  overrides: Partial<Parameters<typeof citationHeader>[0]> = {},
): CitationHeader | null {
  return citationHeader({
    citation: CITATION_FIXTURE,
    grounding: {
      cited: true,
      groundedSentences: 2,
      similarity: 0.51,
      tier: 'medium' as never,
      state: 'cited',
    },
    question: 'How does indexing reach the head?',
    spanUnusable: false,
    ...overrides,
  });
}

/**
 * Tempdoc 849 slice 3 §7 — the CITATION header (distinct from the text-extraction provenance line,
 * which §7's first instruction says must keep its name and its behaviour).
 */
describe('DocumentPane — 849 citation header', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  async function open(header: CitationHeader | null): Promise<DocumentPane> {
    stubFetchOnce({ content: MD_FIXTURE, textProvenance: 'tika' });
    const el = make();
    el.citationHeader = header;
    el.docPath = 'notes/thread.md';
    await flush(el);
    return el;
  }

  const headerText = (el: DocumentPane): string =>
    (el.shadowRoot?.querySelector('[data-testid="citation-header"]')?.textContent ?? '').replace(
      /\s+/g,
      ' ',
    );

  it('says which turn cited the document, where the passage sits, and what happened to it', async () => {
    const el = await open(headerFor());
    const text = headerText(el);
    expect(text).toContain('How does indexing reach the head?');
    expect(text).toContain('Passage 4 of 9');
    expect(text).toContain('Grounds 2 sentences');
    expect(text).toContain('Partly sent to the model');
    expect(
      el.shadowRoot?.querySelector('.citation-inclusion')?.getAttribute('data-inclusion'),
    ).toBe('partial');
  });

  it('labels its ONE score by what it MEASURES, and renders no retrieval band', async () => {
    // Slice-3 review HIGH-1: the retrieval score is the raw Lucene hit score and the tier
    // thresholds are anchored to the cross-encoder scale, so banding it produced a mode-constant —
    // always "weak" for RRF-fused hybrid, always "strong" for unbounded BM25. It is gone.
    const el = await open(headerFor());
    const text = headerText(el);
    // The band that remains comes from the CLAIM similarity (0.51 → moderate). The fixture's
    // retrieval score is 0.9, so a header that had reached for it would read "strong" here.
    expect(text).toContain('Claim match moderate');
    expect(text).not.toContain('Retrieval match');
    expect(text).not.toContain('strong');
    // §7 rule 2 — no bare percentages anywhere in the header.
    expect(text).not.toMatch(/\d+%/);
  });

  it('a DROPPED passage shows the badge ALONE, with no grounding claim beside it', async () => {
    // Slice-3 review MEDIUM-3, on the pane side. A source the prompt had no room for can still be
    // scored by the matcher (which re-fetches chunk text by identity), so this fixture — dropped
    // AND cited — is the real shape, and it used to render both statements at once.
    const el = await open(
      headerFor({ citation: { ...CITATION_FIXTURE, contextInclusion: 'dropped' } }),
    );
    const text = headerText(el);
    expect(text).toContain('Retrieved · never sent to the model');
    expect(text).not.toContain('Grounds 2 sentences');
    expect(text).not.toContain('Claim match');
    // Not a blanket blanking of the header: what the producer DID observe is still said.
    expect(text).toContain('Passage 4 of 9');
  });

  it('renders a SHORTER header when the producer said less — never a padded one', async () => {
    // An uncited, pre-849 source: no inclusion state, no claim match. Everything that remains true
    // is still said, and nothing is padded in to fill the row.
    const el = await open(
      headerFor({
        citation: {
          parentDocId: 'notes/thread.md',
          chunkIndex: 3,
          chunkTotal: 9,
          startChar: 10,
          endChar: 40,
          score: 0.9,
          excerpt: '',
          startLine: 2,
          endLine: 2,
          headingText: '',
          headingLevel: 0,
        },
        grounding: null,
      }),
    );
    const text = headerText(el);
    expect(text).toContain('Passage 4 of 9');
    expect(text).not.toContain('sent to the model');
    expect(text).not.toContain('Claim match');
  });

  it('renders no header at all for the line-addressed mount sites', async () => {
    const el = await open(null);
    expect(el.shadowRoot?.querySelector('[data-testid="citation-header"]')).toBeNull();
    // …and the TEXT-EXTRACTION provenance line is untouched by any of this (§7's name-collision
    // instruction): it still renders on its own, as it did before the header existed.
    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('Text source');
  });

  it('849 S10 — a degenerate span is explained instead of opening in silence', async () => {
    const el = await open(headerFor({ citation: null, grounding: null, spanUnusable: true }));
    const notice = el.shadowRoot?.querySelector('.span-notice');
    expect(notice?.textContent?.trim()).toBe(CITATION_SPAN_UNUSABLE);
    // The message the slice-2 review asked for: the reader is told the citation had no usable
    // position, rather than being left to wonder why nothing is highlighted.
    expect(notice?.textContent).toContain('did not record a usable position');
  });

  it('849 S10 — a citation WITH a usable span shows no such notice', async () => {
    // The discriminator. Without it the previous test would pass against a pane that showed the
    // notice unconditionally.
    const el = await open(headerFor());
    expect(el.shadowRoot?.querySelector('.span-notice')).toBeNull();
  });
});

describe('DocumentPane — empty state', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('renders the no-document empty state when docPath is null', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain('No document selected');
    expect(el.shadowRoot?.querySelector('.header')).toBeNull();
  });
});

describe('DocumentPane — a document the index no longer has (WP1.1 S1.5)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('says the file is no longer in the index', async () => {
    stubFetchOnce({ ok: false, status: 404 });
    const el = make();
    el.docPath = 'Contracts/Gone.pdf';
    await flush(el);

    const alert = el.shadowRoot?.querySelector('jf-error-alert');
    expect(alert?.textContent).toMatch(/no longer in (your|the) index/i);
  });

  it('shows any other failure as a failure, not as a missing file', async () => {
    stubFetchOnce({ ok: false, status: 503 });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);
    const alert = el.shadowRoot?.querySelector('jf-error-alert');
    expect(alert).not.toBeNull();
    expect(alert?.textContent?.trim()).not.toBe('');
    expect(alert?.textContent).not.toMatch(/no longer in (your|the) index/i);
  });
});

describe('DocumentPane — load + render (markdown)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('fetches /api/preview for the docPath and renders the fixture as blocks in Rendered mode by default', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toContain(
      '/api/preview?docId=' + encodeURIComponent('notes/thread.md'),
    );
    expect(el.mode).toBe('rendered');
    const blocks = el.shadowRoot?.querySelectorAll('.block');
    expect(blocks?.length).toBe(3); // heading, paragraph, list
    expect(el.shadowRoot?.querySelector('.block')?.getAttribute('data-line-start')).toBe('0');
  });

  it('toggles to Source mode and back to Rendered', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const [renderedBtn, sourceBtn] = Array.from(
      el.shadowRoot?.querySelectorAll('.toggle-btn') ?? [],
    ) as HTMLButtonElement[];
    expect(renderedBtn!.getAttribute('aria-checked')).toBe('true');

    sourceBtn!.click();
    await el.updateComplete;
    expect(el.mode).toBe('source');
    expect(sourceBtn!.getAttribute('aria-checked')).toBe('true');
    expect(el.shadowRoot?.querySelector('pre.source')).not.toBeNull();
    expect(el.shadowRoot?.querySelectorAll('pre.source span[data-line]').length).toBe(
      MD_FIXTURE.split('\n').length,
    );

    renderedBtn!.click();
    await el.updateComplete;
    expect(el.mode).toBe('rendered');
  });

  it('a non-markdown docPath defaults to Source mode with Rendered disabled', async () => {
    stubFetchOnce({ content: 'plain text content' });
    const el = make();
    el.docPath = 'notes/readme.txt';
    await flush(el);

    expect(el.mode).toBe('source');
    const renderedBtn = el.shadowRoot?.querySelector('.toggle-btn') as HTMLButtonElement;
    // Soft block (aria-disabled, not native `disabled`) — 596 face 1.1: a native-disabled control
    // suppresses its own `title`, so the reason must stay reachable via focus/hover instead.
    expect(renderedBtn.getAttribute('aria-disabled')).toBe('true');
    expect(renderedBtn.title).toContain('only available for Markdown');
    renderedBtn.click();
    await el.updateComplete;
    expect(el.mode).toBe('source'); // soft-blocked click is a no-op, not a mode switch
  });
});

describe('DocumentPane — highlightRange / chunkRange', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('applies hl-strong to the covering block and scrolls it to block:center', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const scrollSpy = vi.fn();
    Element.prototype.scrollIntoView = scrollSpy;

    el.highlightRange = { startLine: 2, endLine: 2 }; // the paragraph block
    await flush(el);

    const strong = el.shadowRoot?.querySelector('.block.hl-strong');
    expect(strong).not.toBeNull();
    expect(strong?.getAttribute('data-line-start')).toBe('2');
    expect(scrollSpy).toHaveBeenCalledWith({ block: 'center' });
  });

  it('tints the rest of a wider chunkRange with hl-weak, leaving the exact range hl-strong', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    el.highlightRange = { startLine: 2, endLine: 2 };
    el.chunkRange = { startLine: 0, endLine: 5 };
    await flush(el);

    const strongBlocks = el.shadowRoot?.querySelectorAll('.block.hl-strong');
    const weakBlocks = el.shadowRoot?.querySelectorAll('.block.hl-weak');
    expect(strongBlocks?.length).toBe(1);
    expect(weakBlocks?.length).toBe(2); // heading + list, both inside the chunk but not the exact range
  });

  it('applies hl-strong to the covering line span in Source mode', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/readme.txt'; // forces Source mode
    el.highlightRange = { startLine: 2, endLine: 3 };
    await flush(el);

    const spans = el.shadowRoot?.querySelectorAll('pre.source span.hl-strong');
    expect(spans?.length).toBe(2);
    expect(spans?.[0]?.getAttribute('data-line')).toBe('2');
    expect(spans?.[1]?.getAttribute('data-line')).toBe('3');
  });
});

// Search Thread Round-2 R1b — the highlight lands strong then decays to the quiet hl-weak tint +
// edge marker; chunkRange never gets the strong phase; reduced motion skips the loud phase.
describe('DocumentPane — highlight decay (Search Thread Round-2 R1b)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function stubReducedMotion(reduce: boolean): void {
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: reduce,
      media: '(prefers-reduced-motion: reduce)',
      addEventListener: () => {},
      removeEventListener: () => {},
    } as unknown as MediaQueryList);
  }

  it('lands hl-strong, then decays to hl-weak after ~1.5s', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.block.hl-weak')).toBeNull();

    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();
    const settled = el.shadowRoot?.querySelector('.block.hl-weak');
    expect(settled).not.toBeNull();
    expect(settled?.getAttribute('data-line-start')).toBe('2');
  });

  it('honors prefers-reduced-motion: lands quiet immediately, never rendering hl-strong', async () => {
    stubReducedMotion(true);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();
    expect(el.shadowRoot?.querySelector('.block.hl-weak')).not.toBeNull();
  });

  it('a NEW highlightRange re-arms the strong phase even after a prior range decayed', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    el.highlightRange = { startLine: 2, endLine: 2 };
    await el.updateComplete;
    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.block.hl-strong')).toBeNull();

    el.highlightRange = { startLine: 0, endLine: 0 }; // the heading block
    await el.updateComplete;
    const strong = el.shadowRoot?.querySelector('.block.hl-strong');
    expect(strong).not.toBeNull();
    expect(strong?.getAttribute('data-line-start')).toBe('0');
  });

  it('the chunkRange tier never gets the strong phase, before or after decay', async () => {
    stubReducedMotion(false);
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    el.highlightRange = { startLine: 2, endLine: 2 };
    el.chunkRange = { startLine: 0, endLine: 5 };
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    // Before decay: chunk siblings are hl-weak, the exact range is hl-strong.
    expect(el.shadowRoot?.querySelectorAll('.block.hl-weak').length).toBe(2);
    expect(el.shadowRoot?.querySelectorAll('.block.hl-strong').length).toBe(1);

    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    // After decay: the exact range folds into the same hl-weak tier — never hl-strong.
    expect(el.shadowRoot?.querySelectorAll('.block.hl-strong').length).toBe(0);
    expect(el.shadowRoot?.querySelectorAll('.block.hl-weak').length).toBe(3);
  });
});

// Search Thread Round-2 R5c — the reading-pane provenance header truncates through the shared
// formatDisplayPath authority (filename-preserving), not CSS end-truncation.
describe('DocumentPane — header path truncation (Search Thread Round-2 R5c)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    __resetUiModeForTest();
  });

  it('Detailed: renders a formatDisplayPath-truncated header text with the full path in title', async () => {
    setUiMode('advanced'); // Tempdoc 738 (C4) — the full/truncated path is the Detailed form.
    const longPath =
      'projects/deeply/nested/folder/structure/that/goes/on/for/a/while/before/reaching/the-actual-filename.md';
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = longPath;
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.getAttribute('title')).toBe(longPath);
    expect(pathEl?.textContent).toContain('the-actual-filename.md');
    expect(pathEl?.textContent?.trim()).not.toBe(longPath);
    expect(pathEl?.textContent).toContain('…');
  });

  it('Detailed: renders a short path verbatim (no truncation needed)', async () => {
    setUiMode('advanced');
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.textContent?.trim()).toBe('notes/thread.md');
    expect(pathEl?.getAttribute('title')).toBe('notes/thread.md');
  });

  it('Simple (default): renders a humanized folder breadcrumb, not the raw path (Tempdoc 738 C4)', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'f:\\justsearch-public\\ssot\\docs\\help\\getting-started.md';
    await flush(el);

    const pathEl = el.shadowRoot?.querySelector('.path');
    expect(pathEl?.textContent?.trim()).toBe('ssot › docs › help');
    // The full path stays reachable via the title tooltip.
    expect(pathEl?.getAttribute('title')).toBe('f:\\justsearch-public\\ssot\\docs\\help\\getting-started.md');
  });
});

describe('DocumentPane — zero-content diagnostic (tempdoc 671 parity)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('shows the provenance diagnostic even when content is empty', async () => {
    stubFetchOnce({
      content: '',
      textProvenance: 'vdu_pending',
      visualExtractionEvidence: { route: 'structured', ocrSkipReason: 'no_text_found' },
    });
    const el = make();
    el.docPath = 'scans/page.md';
    await flush(el);

    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('VDU pending');
    expect(el.shadowRoot?.querySelector('.empty')?.textContent).toContain('No preview available');
  });
});

describe('DocumentPane — VDU abstention-gate provenance (tempdoc 677)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('labels vdu_empty as VDU-ran-but-found-nothing, not the silent base-method fall-through', async () => {
    stubFetchOnce({
      content: 'OCR baseline text',
      textProvenance: 'vdu_empty',
    });
    const el = make();
    el.docPath = 'scans/blank-page.md';
    await flush(el);

    expect(el.shadowRoot?.querySelector('.preview-source')?.textContent).toContain('VDU: no text found');
  });

  it('labels vdu_rejected with the abstention-gate label and an explanatory tooltip', async () => {
    stubFetchOnce({
      content: 'OCR baseline text',
      textProvenance: 'vdu_rejected',
    });
    const el = make();
    el.docPath = 'scans/suspect-page.md';
    await flush(el);

    const sourceEl = el.shadowRoot?.querySelector('.preview-source');
    expect(sourceEl?.textContent).toContain('VDU: unreliable, not used');
    const labelSpan = sourceEl?.querySelector('span[title]');
    expect(labelSpan?.getAttribute('title')).toBe(
      'The automatic reader could not produce trustworthy text for this document, so search uses the original extraction.',
    );
  });
});

describe('DocumentPane — a11y + events', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
  });

  it('the scroll region is keyboard-focusable (tabindex=0 + role=region)', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    const region = el.shadowRoot?.querySelector('.scroll-region');
    expect(region?.getAttribute('tabindex')).toBe('0');
    expect(region?.getAttribute('role')).toBe('region');
    expect(region?.getAttribute('aria-label')).toBeTruthy();
  });

  it("the ramp's own scroll containers are keyboard-reachable too (tempdoc 853 F-05)", async () => {
    // The regression the 2026-08-19 audit measured as axe `scrollable-region-focusable` (serious,
    // WCAG 2.1.1) n=2 ON THIS SURFACE: a `<table>` (`display:block; overflow-x:auto` under the prose
    // variant) and a fenced `<pre>` (`overflow-x:auto`) both scroll horizontally, and the pane's own
    // `.scroll-region` above does NOT reach content clipped INSIDE a block. This is the wiring pin —
    // `markdownScrollRegions.test.ts` proves the pass, this proves the pane runs it.
    // Both constructs are nested one level inside a blockquote ON PURPOSE. This file already records
    // (markdownBlockMap.test.ts, the note above its wrapper assertions) that happy-dom's DOMPurify
    // strips exactly the single OUTERMOST element of a standalone fragment — a test-environment
    // artifact, not a production one — which would delete the very `<table>`/`<pre>` under test.
    // Nesting keeps the real marked -> DOMPurify -> unsafeHTML path and leaves the containers intact.
    stubFetchOnce({
      content: [
        '# Title',
        '',
        '> | a | b |',
        '> | --- | --- |',
        '> | 1 | 2 |',
        '',
        '> ```java',
        '> int x = 1;',
        '> ```',
      ].join('\n'),
    });
    const el = make();
    el.docPath = 'notes/wide.md';
    await flush(el);

    const table = el.shadowRoot?.querySelector('.blocks table');
    expect(table, 'the fixture must actually render a table').toBeTruthy();
    expect(table?.getAttribute('tabindex')).toBe('0');
    expect(table?.getAttribute('aria-label')).toBeTruthy();

    const pre = el.shadowRoot?.querySelector('.blocks pre');
    expect(pre, 'the fixture must actually render a fenced block').toBeTruthy();
    expect(pre?.getAttribute('tabindex')).toBe('0');
    expect(pre?.getAttribute('aria-label')).toBeTruthy();
  });

  it('emits pane-close when the header close action activates', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/thread.md';
    await flush(el);

    let closed = false;
    el.addEventListener('pane-close', () => {
      closed = true;
    });
    await clickIconButton(el);
    expect(closed).toBe(true);
  });
});

/**
 * Tempdoc 849 §3-§4 — the pane as an EVIDENCE READER: anchored on the citation's own character
 * offsets, windowed around them, and silent when it cannot confirm them.
 *
 * Every assertion here is on RENDERED text or on the element that was actually scrolled to, never on
 * a computed number and never on the absence of a class: "no `.hl-strong`" would pass with the
 * reader sitting at the top of the document, which is the defect D-2 describes rather than a proof
 * against it.
 */
describe('DocumentPane — char-anchored citations (tempdoc 849 §3)', () => {
  // Six distinct lines, so an off-by-one lands on text the assertion can name. Each line is 12
  // characters plus its newline.
  const LINES = ['line zero...', 'line one....', 'line two....', 'line three..', 'line four...', 'line five...'];
  const DOC = LINES.join('\n');
  const charOf = (line: string): number => DOC.indexOf(line);

  /** A capture-the-target scroll spy: WHAT was scrolled to is the assertion, not that something was. */
  function spyScroll(): { target: () => Element | null } {
    let scrolled: Element | null = null;
    Element.prototype.scrollIntoView = function scrollIntoView(this: Element): void {
      scrolled = this;
    };
    return { target: () => scrolled };
  }

  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
  });

  it('derives the highlighted line from startChar — the reader counts the line the citation names', async () => {
    // THE OFF-BY-ONE DISCRIMINATOR (D1). The producer computes `startLine` 1-based; this pane's
    // ranges are 0-based; nothing subtracted one. 'line three..' is at 0-based index 3 and 1-based
    // line 4, so a surviving off-by-one renders 'line four...' — which is why the assertion is the
    // TEXT of the tinted line and not the number that produced it.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt'; // Source mode: one span per line, so the tint is per line
    el.citation = {
      startChar: charOf('line three..'),
      endChar: charOf('line three..') + 'line three..'.length,
      excerpt: 'line three..',
      sentenceText: null,
    };
    await flush(el);

    const tinted = el.shadowRoot?.querySelectorAll('pre.source span.hl-weak');
    expect(tinted?.length).toBe(1);
    expect(tinted?.[0]?.textContent?.trim()).toBe('line three..');
    expect(tinted?.[0]?.getAttribute('data-line')).toBe('3');
  });

  it('marks the claim-matched sentence strong INSIDE the tinted chunk', async () => {
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: charOf('line two....'),
      endChar: charOf('line five...') + 'line five...'.length,
      excerpt: 'line two....',
      sentenceText: 'line four...',
    };
    await flush(el);

    const strong = el.shadowRoot?.querySelectorAll('pre.source span.hl-strong');
    expect(strong?.length).toBe(1);
    expect(strong?.[0]?.textContent?.trim()).toBe('line four...');
    // …and the rest of the retrieved chunk is the weaker tier: two, three, five.
    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(3);
  });

  it('with NO claim match, scrolls the tinted chunk into view (not the top of the document)', async () => {
    // Review D-2: `rag.citations` arrives at retrieval time and `rag.citation_matches` only after
    // the answer streams, so this is what EVERY mid-stream citation click lands in. The assertion is
    // the scroll TARGET; asserting "no .hl-strong" would pass with the evidence off-screen.
    stubDocument(DOC);
    const scroll = spyScroll();
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: charOf('line four...'),
      endChar: charOf('line four...') + 'line four...'.length,
      excerpt: 'line four...',
      sentenceText: null,
    };
    await flush(el);
    await flush(el);

    const target = scroll.target();
    expect(target).not.toBeNull();
    expect(target?.classList.contains('hl-weak')).toBe(true);
    expect(target?.textContent?.trim()).toBe('line four...');
  });

  it('a late claim match upgrades the OPEN pane to strong, once, without re-fetching', async () => {
    stubDocument(DOC);
    const scroll = spyScroll();
    const el = make();
    el.docPath = 'notes/readme.txt';
    const chunk = {
      startChar: charOf('line two....'),
      endChar: charOf('line five...') + 'line five...'.length,
      excerpt: 'line two....',
    };
    el.citation = { ...chunk, sentenceText: null };
    await flush(el);
    await flush(el);
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')).toBeNull();
    const fetchesBefore = vi.mocked(fetch).mock.calls.length;

    // `rag.citation_matches` lands: the same span, now with the sentence it grounded.
    el.citation = { ...chunk, sentenceText: 'line three..' };
    await flush(el);
    await flush(el);

    const strong = el.shadowRoot?.querySelector('pre.source span.hl-strong');
    expect(strong?.textContent?.trim()).toBe('line three..');
    expect(scroll.target()).toBe(strong);
    // The loaded window already contained the span, so the upgrade is not a reload.
    expect(vi.mocked(fetch).mock.calls.length).toBe(fetchesBefore);
  });

  it('reaches a citation past the old 5,000-character head window', async () => {
    // D2: the fetch was hard-capped at `offsetChars=0&maxChars=5000`, so a passage at ~char 40,000
    // was not in `content` at all and `scrollToHighlight` silently did nothing.
    const filler = Array.from({ length: 400 }, (_, i) => `filler line ${i}`.padEnd(99, '.')).join('\n');
    const passage = 'the renewal failed because the lock was still held';
    const doc = `${filler}\n${passage}\n${filler}`;
    const at = doc.indexOf(passage);
    expect(at).toBeGreaterThan(5000);

    const stub = stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { startChar: at, endChar: at + passage.length, excerpt: passage, sentenceText: null };
    await flush(el);

    const requested = stub.calls[0]!;
    expect(Number(requested.searchParams.get('offsetChars'))).toBeGreaterThan(0);
    const tinted = el.shadowRoot?.querySelector('pre.source span.hl-weak');
    expect(tinted?.textContent?.trim()).toBe(passage);
    // …and the reader says it is looking at a window, from the `truncated` flag it used to drop.
    expect(el.shadowRoot?.querySelector('.window-note')?.textContent).toContain('around the cited passage');
  });

  it('confirms the witness across a whitespace difference (the excerpt was re-wrapped)', async () => {
    // The excerpt is quoted from the chunker's text and the pane renders the extractor's; whitespace
    // differs far more often than words do, so a whitespace-strict witness would suppress honestly
    // anchored highlights.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: charOf('line two....'),
      endChar: charOf('line three..') + 'line three..'.length,
      excerpt: 'line two....   line three..',
      sentenceText: null,
    };
    await flush(el);

    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(2);
    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
  });

  it('SUPPRESSES the highlight when the excerpt is not at the anchored offsets, and says why', async () => {
    // §3 R1.4 / review D-11: the document changed since it was indexed. A caveated highlight would
    // be read as a highlight — the tint is the strongest signal on the surface and the caveat the
    // weakest — so nothing is tinted at all.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: charOf('line three..'),
      endChar: charOf('line three..') + 'line three..'.length,
      excerpt: 'a paragraph that is no longer at these offsets',
      sentenceText: 'line three..',
    };
    await flush(el);

    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-strong').length).toBe(0);
    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(0);
    expect(el.shadowRoot?.querySelector('.anchor-notice')?.textContent).toContain(
      'could not be confirmed',
    );
    // The document itself is still shown — the pane withholds the claim, not the reading.
    expect(el.shadowRoot?.querySelector('pre.source')?.textContent).toContain('line three..');
  });

  it('says so rather than guessing when the LOADED WINDOW does not reach the anchor', async () => {
    // Distinct from the shrunk case below: here the document genuinely continues past what could be
    // fetched (the endpoint caps a slice at 200K), so the limit is this reader's and the notice must
    // not describe it as a change in the document. A cited span longer than the cap is what reaches
    // this branch.
    const doc = 'y'.repeat(260000);
    stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { startChar: 0, endChar: 240000, excerpt: 'y'.repeat(60), sentenceText: null };
    await flush(el);

    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(0);
    const notice = el.shadowRoot?.querySelector('.anchor-notice')?.textContent ?? '';
    expect(notice).toContain('outside the part of this document that could be loaded');
    expect(notice).not.toContain('shorter than');
  });

  it('anchors across a length-changing lowercase (U+0130) without desyncing the offset map', async () => {
    // `'İ'.toLowerCase()` is TWO code units ('i' + U+0307) — the only length-changing lowercase in
    // U+0000-U+2FFFF. Pushing it as one entry desynced the normalized text from its origin map, so
    // the sentence lookup read past the end of the map and returned `end: NaN`: the strong highlight
    // silently vanished or landed on the wrong line. This fails OPEN, which is why it needs a pin.
    const lines = ['plain first line', 'İSTANBUL notes here', 'the matched sentence lives here', 'trailing line'];
    const doc = lines.join('\n');
    const chunkStart = doc.indexOf('İSTANBUL');
    const sentence = 'the matched sentence lives here';

    stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: chunkStart,
      endChar: doc.indexOf(sentence) + sentence.length,
      // A real excerpt is a word-clamped PREFIX of the chunk's own text, so it must be one here too
      // — the witness runs before the sentence lookup, and a fabricated quote would suppress the
      // highlight for a reason that has nothing to do with what this test is about.
      excerpt: 'İSTANBUL notes here\nthe matched sentence lives here',
      sentenceText: sentence,
    };
    await flush(el);

    const strong = el.shadowRoot?.querySelector('pre.source span.hl-strong');
    expect(strong?.textContent?.trim()).toBe(sentence);
    expect(strong?.getAttribute('data-line')).toBe('2');
  });

  it('a witness too SHORT to be evidence confirms rather than suppressing', async () => {
    // `slice(0, 48)` is a maximum, not a guarantee: a 12-character excerpt ("Introduction") matches
    // in a wholly rewritten document, so suppressing-or-confirming on it certifies nothing either
    // way. Such a citation carries no usable witness, which is the same standing as an empty one —
    // said out loud instead of dressed up as verification.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: charOf('line three..'),
      endChar: charOf('line three..') + 'line three..'.length,
      excerpt: 'other text', // < WITNESS_MIN_CHARS, and absent from the document
      sentenceText: null,
    };
    await flush(el);

    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
    expect(el.shadowRoot?.querySelector('pre.source span.hl-weak')?.textContent?.trim()).toBe(
      'line three..',
    );
  });

  it('suppresses when the witness sits far INSIDE the span (text was inserted before it)', async () => {
    // The excerpt is a word-clamped PREFIX of the chunk's content, so in an unchanged document it
    // starts at the span's first character. Confirming it anywhere in the span accepts a document
    // where a paragraph was inserted before the passage: the witness is still in range and the tint
    // has silently moved by the length of the insertion.
    const inserted = 'an inserted paragraph that was not here when this document was indexed. '.repeat(8);
    const passage = 'the renewal failed because the lock was still held by the prior run';
    const doc = `intro line\n${inserted}\n${passage}\ntrailing`;
    const spanStart = doc.indexOf('an inserted');

    stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: spanStart,
      endChar: doc.indexOf(passage) + passage.length,
      excerpt: passage, // the recorded quote — now far from where the offsets say it starts
      sentenceText: null,
    };
    await flush(el);

    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(0);
    expect(el.shadowRoot?.querySelector('.anchor-notice')?.textContent).toContain(
      'could not be confirmed',
    );
  });

  it('names a SHRUNK document as changed, not as an under-loaded window', async () => {
    // The endpoint said there is no more to fetch, and the text still ends before the cited offsets:
    // the document is shorter than when it was indexed. Reporting that as "outside the part that
    // could be loaded" would blame this reader for a change in the document.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: DOC.length - 5,
      endChar: DOC.length + 400,
      excerpt: 'a passage that used to live at the end of this document',
      sentenceText: null,
    };
    await flush(el);

    const notice = el.shadowRoot?.querySelector('.anchor-notice')?.textContent ?? '';
    expect(notice).toContain('shorter than the cited passage');
    expect(notice).not.toContain('could not be loaded');
  });

  it('an offset past the end of the document reads as shrunk, not as a blank window', async () => {
    // The worker clamps an out-of-range `offsetChars` silently and returns an empty slice, so the
    // pane must not present an empty document as if the citation were merely off-screen.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { startChar: 90000, endChar: 90040, excerpt: 'gone from this document', sentenceText: null };
    await flush(el);

    expect(el.shadowRoot?.querySelector('.anchor-notice')?.textContent).toContain(
      'shorter than the cited passage',
    );
  });

  it('does not contradict itself: no window note beside a suppression notice', async () => {
    const filler = Array.from({ length: 400 }, (_, i) => `filler line ${i}`.padEnd(99, '.')).join('\n');
    const passage = 'the renewal failed because the lock was still held';
    const doc = `${filler}\n${passage}\n${filler}`;
    const at = doc.indexOf(passage);

    stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = {
      startChar: at,
      endChar: at + passage.length,
      excerpt: 'a passage that is no longer at these recorded offsets',
      sentenceText: null,
    };
    await flush(el);

    expect(el.shadowRoot?.querySelector('.anchor-notice')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.window-note')).toBeNull();
  });

  it('a degenerate span says nothing at all — no highlight, and no notice blaming the document', async () => {
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { startChar: 20, endChar: 20, excerpt: 'a passage of some length here', sentenceText: null };
    await flush(el);

    expect(el.shadowRoot?.querySelectorAll('pre.source span.hl-weak').length).toBe(0);
    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
  });

  it('does not fire a second identical fetch for an anchor the in-flight request already covers', async () => {
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    const chunk = {
      startChar: charOf('line two....'),
      endChar: charOf('line five...') + 'line five...'.length,
      excerpt: 'line two.... line three.. line four...',
    };
    el.citation = { ...chunk, sentenceText: null };
    // The upgrade lands BEFORE the first fetch resolves — `previewWindow` is still null here.
    el.citation = { ...chunk, sentenceText: 'line four...' };
    await flush(el);
    await flush(el);

    expect(vi.mocked(fetch).mock.calls.length).toBe(1);
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')?.textContent?.trim()).toBe(
      'line four...',
    );
  });

  it('anchors in a document with NO line breaks before the citation', async () => {
    // The window is cut at an arbitrary character and the leading half-line is dropped so line 0 is
    // a real line — but on text whose first break comes AFTER the cited span (an extracted PDF is
    // routinely one very long line), trimming to it would cut away the evidence and then report it
    // as unreachable. The trim is skipped in exactly that case.
    const passage = 'the renewal failed because the lock was still held';
    const doc = `${'z'.repeat(9000)}${passage}${'z'.repeat(1000)}\nand a second line`;
    const at = doc.indexOf(passage);
    // The fixture must actually exercise the trim: the window's first line break has to fall INSIDE
    // the fetched slice and AFTER the citation, which is the case the guard is about.
    expect(doc.indexOf('\n')).toBeGreaterThan(at);

    stubDocument(doc);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { startChar: at, endChar: at + passage.length, excerpt: passage, sentenceText: null };
    await flush(el);

    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
    expect(el.shadowRoot?.querySelector('pre.source span.hl-weak')?.textContent).toContain(passage);
  });

  it('treats an UNBOUND citation property as no citation (Lit leaves it undefined, not null)', async () => {
    // Three of the four mount sites are line-addressed and never bind `.citation`; a host that binds
    // it from a state field it has not written yet passes `undefined`. An `=== null` read would take
    // those down the anchored path with nothing to anchor on — which threw before this was pinned.
    stubDocument(DOC);
    const el = make();
    el.citation = undefined as unknown as null;
    el.docPath = 'notes/readme.txt';
    el.highlightRange = { startLine: 2, endLine: 2 };
    await flush(el);

    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')?.textContent?.trim()).toBe(
      'line two....',
    );
    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
  });

  it('leaves the line-addressed consumers alone: no citation, no window note, head window', async () => {
    // The shipped Shell and search-v2 still write `highlightRange`; the char anchor is additive.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.highlightRange = { startLine: 1, endLine: 1 };
    await flush(el);

    expect(vi.mocked(fetch).mock.calls[0]?.[0]).toContain('offsetChars=0&maxChars=5000');
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')?.textContent?.trim()).toBe(
      'line one....',
    );
    expect(el.shadowRoot?.querySelector('.window-note')).toBeNull();
    expect(el.shadowRoot?.querySelector('.anchor-notice')).toBeNull();
  });
});

describe('DocumentPane — anchored highlight decay (tempdoc 849 §4)', () => {
  const LINES = ['line zero...', 'line one....', 'line two....', 'line three..', 'line four...', 'line five...'];
  const DOC = LINES.join('\n');
  const chunk = {
    startChar: DOC.indexOf('line two....'),
    endChar: DOC.indexOf('line five...') + 'line five...'.length,
    excerpt: 'line two....',
  };

  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    vi.spyOn(window, 'matchMedia').mockReturnValue({
      matches: false,
      media: '(prefers-reduced-motion: reduce)',
      addEventListener: () => {},
      removeEventListener: () => {},
    } as unknown as MediaQueryList);
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('re-arms the strong phase for a NEW anchored sentence after the previous one decayed', async () => {
    // The decay is armed from the DERIVED range, not from the `highlightRange` property the
    // pre-849 decay tests above exercise, so the anchored path needs its own pin: an upgrade landing
    // on an already-decayed pane must return to the strong phase rather than stay quiet.
    stubDocument(DOC);
    const el = make();
    el.docPath = 'notes/readme.txt';
    el.citation = { ...chunk, sentenceText: 'line three..' };
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')?.textContent?.trim()).toBe(
      'line three..',
    );

    await vi.advanceTimersByTimeAsync(1500);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')).toBeNull();

    el.citation = { ...chunk, sentenceText: 'line four...' };
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('pre.source span.hl-strong')?.textContent?.trim()).toBe(
      'line four...',
    );
  });
});

describe('DocumentPane — pane-visible-range (debounced scroll)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    vi.restoreAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('emits the first/last visible source line after the scroll debounce settles', async () => {
    stubFetchOnce({ content: MD_FIXTURE });
    const el = make();
    el.docPath = 'notes/readme.txt'; // Source mode: one span per line, simplest geometry to mock
    await vi.advanceTimersByTimeAsync(0);
    await el.updateComplete;

    const region = el.shadowRoot!.querySelector('.scroll-region') as HTMLElement;
    vi.spyOn(region, 'getBoundingClientRect').mockReturnValue({
      top: 0,
      bottom: 100,
      left: 0,
      right: 0,
      width: 0,
      height: 100,
      x: 0,
      y: 0,
      toJSON() {
        return this;
      },
    } as DOMRect);

    const spans = Array.from(region.querySelectorAll('span[data-line]')) as HTMLElement[];
    spans.forEach((span, i) => {
      const top = i * 20;
      vi.spyOn(span, 'getBoundingClientRect').mockReturnValue({
        top,
        bottom: top + 20,
        left: 0,
        right: 0,
        width: 0,
        height: 20,
        x: 0,
        y: top,
        toJSON() {
          return this;
        },
      } as DOMRect);
    });

    let detail: { firstLine: number; lastLine: number } | null = null;
    el.addEventListener('pane-visible-range', (e) => {
      detail = (e as CustomEvent).detail;
    });

    region.dispatchEvent(new Event('scroll'));
    expect(detail).toBeNull(); // debounced — not yet fired

    await vi.advanceTimersByTimeAsync(200);
    expect(detail).not.toBeNull();
    // Only lines whose [top,bottom) overlaps the container's [0,100) window are "visible".
    expect(detail!.firstLine).toBe(0);
    expect(detail!.lastLine).toBeGreaterThanOrEqual(4);
  });
});
