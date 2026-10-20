// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * Tempdoc 849 slice 3 — `jf-sv3-pane`, the Search v3 window's citation-inspection REGION.
 *
 * The element had no test file at all until this slice, which is why it also carries the
 * `execution-surfaces` register guard for the pane row: a `test:<Name>` guard needs a file to
 * resolve against, and a dangling guard reports green while asserting nothing.
 *
 * What is pinned here is the region's ONE law: it forwards, and it derives nothing. Everything a
 * document needs to be read belongs to the shared `jf-document-pane`, and every fact about the
 * citation is joined in `SearchV3View` from the turn's own evidence record. A label re-authored on
 * the way through this element — a re-worded badge, a re-banded score, a defaulted inclusion state —
 * would be exactly the fork the register exists to prevent, and nothing else in the suite is
 * positioned to see it.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

// Incidental requests are outside these render/event scenarios. Keep their
// unavailable-backend behavior explicit, without contacting a real server.
const originalFetch = globalThis.fetch;
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Test backend unavailable')));
});
afterEach(() => { globalThis.fetch = originalFetch; });
import { Sv3Pane, SV3_PANE_CLOSE } from './Sv3Pane.js';
import { citationHeader, type CitationHeader } from '../../components/chat/evidenceProjection.js';
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';

type Mounted = Sv3Pane & { updateComplete: Promise<unknown> };

const ANCHOR: DocumentCitationAnchor = {
  startChar: 120,
  endChar: 240,
  excerpt: 'the lock held',
  sentenceText: null,
};

function header(spanUnusable = false): CitationHeader {
  const built = citationHeader({
    citation: {
      parentDocId: 'f:/docs/note-0.md',
      chunkIndex: 0,
      chunkTotal: 2,
      startChar: 120,
      endChar: 240,
      score: 0.8,
      excerpt: 'the lock held',
      startLine: 12,
      endLine: 18,
      headingText: 'Notes',
      headingLevel: 2,
      contextInclusion: 'dropped',
    },
    grounding: null,
    question: 'why did the renewal fail?',
    spanUnusable,
  });
  if (built === null) throw new Error('the fixture must produce a header');
  return built;
}

async function mount(): Promise<Mounted> {
  const el = document.createElement('jf-sv3-pane') as Mounted;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const reader = (el: Mounted): (HTMLElement & { citationHeader: CitationHeader | null }) | null =>
  el.shadowRoot?.querySelector('jf-document-pane') ?? null;

beforeEach(() => {
  document.body.innerHTML = '';
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
});

describe('jf-sv3-pane — the region forwards and derives nothing', () => {
  it('renders nothing at all until a document is set', async () => {
    const el = await mount();
    // Not an empty frame: the pane is a region of the window GRID, so an unoccupied one must
    // contribute no box, no edge and no backdrop.
    expect(el.shadowRoot?.querySelector('.surface')).toBeNull();
    expect(reader(el)).toBeNull();
  });

  it('hands the shared reader the citation header BY IDENTITY, not by a copy', async () => {
    const el = await mount();
    const h = header();
    el.docPath = 'f:/docs/note-0.md';
    el.citation = ANCHOR;
    el.citationHeader = h;
    await el.updateComplete;

    // Identity, deliberately. A structural compare would pass against a region that rebuilt the
    // header — which is the one thing this element must never do, because a rebuild is where a
    // second wording gets minted.
    expect(reader(el)?.citationHeader).toBe(h);
    expect((reader(el) as unknown as { citation: DocumentCitationAnchor }).citation).toBe(ANCHOR);
  });

  it('forwards an ABSENT header as absent — it invents no default for a pre-849 citation', async () => {
    const el = await mount();
    el.docPath = 'f:/docs/note-0.md';
    el.citation = ANCHOR;
    await el.updateComplete;
    expect(reader(el)?.citationHeader).toBeNull();
  });

  it('849 S10 — an unusable span reaches the reader as a header, with no anchor', async () => {
    // The pair that makes the degenerate span legible: no anchor to highlight, but a header that
    // says the citation named no usable position. The region must carry BOTH halves — dropping the
    // header here would put the pane back where slice 2 found it, opening in silence.
    const el = await mount();
    el.docPath = 'f:/docs/note-0.md';
    el.citation = null;
    el.citationHeader = header(true);
    await el.updateComplete;

    expect((reader(el) as unknown as { citation: unknown }).citation).toBeNull();
    expect(reader(el)?.citationHeader?.spanUnusable).toBe(true);
  });

  it('re-raises the shared reader\u2019s close as the WINDOW\u2019s own event', async () => {
    const el = await mount();
    el.docPath = 'f:/docs/note-0.md';
    await el.updateComplete;

    const escaped: Event[] = [];
    const own: Event[] = [];
    document.body.addEventListener('pane-close', (e) => escaped.push(e));
    el.addEventListener(SV3_PANE_CLOSE, (e) => own.push(e));

    reader(el)?.dispatchEvent(new CustomEvent('pane-close', { bubbles: true, composed: true }));

    // The shared component's `composed` event is stopped here; what leaves is this window's own, so
    // the Shell's unguarded host listeners never see a v3 region closing.
    expect(own).toHaveLength(1);
    expect(escaped).toHaveLength(0);
  });
});
