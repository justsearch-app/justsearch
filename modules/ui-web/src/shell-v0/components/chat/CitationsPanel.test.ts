/**
 * @vitest-environment happy-dom
 *
 * Slice 493 — CitationsPanel tests (trust-tier rendering).
 */

import { beforeEach, describe, expect, it } from 'vitest';
import {
  CitationsPanel,
  type CitationMatch,
  type RetrievalCitation,
} from './CitationsPanel.js';
import './CitationsPanel.js';
import {
  getSelectedSource,
  setSelectedSource,
  sourceKey,
  __resetSelectedSource,
} from '../../state/selectedSource.js';

async function settle(el: Element): Promise<void> {
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  // 559 C-1: sources are collapsed by default; open the disclosure so the
  // body-inspecting assertions below see the cards. (The default-collapsed
  // behavior itself is covered by the dedicated test at the end.)
  (el as unknown as { sourcesExpanded: boolean }).sourcesExpanded = true;
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
}

function fakeSource(
  overrides: Partial<RetrievalCitation> = {},
): RetrievalCitation {
  return {
    parentDocId: 'doc.fake',
    chunkIndex: 0,
    chunkTotal: 1,
    startChar: 0,
    endChar: 100,
    score: 0.85,
    excerpt: 'Default excerpt text.',
    startLine: 0,
    endLine: 5,
    headingText: '',
    headingLevel: 0,
    ...overrides,
  };
}

function fakeCitation(overrides: Partial<CitationMatch> = {}): CitationMatch {
  return {
    sentenceIndex: 0,
    sentenceText: 'Default sentence text.',
    sourceIndex: 0,
    similarity: 0.85,
    parentDocId: 'doc.fake',
    ...overrides,
  };
}

/**
 * Tempdoc 849 slice 3 §5 — the RETRIEVED-vs-RECEIVED badge on the source card.
 *
 * Its own describe block because the discipline being pinned is not "the badge renders" but "the
 * badge renders EXACTLY when the producer resolved the state": the absence case is the one that
 * keeps a pre-849 conversation from being retroactively described.
 */
describe('CitationsPanel — 849 inclusion badge', () => {
  async function panelText(source: RetrievalCitation): Promise<string> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [source];
    el.citations = [fakeCitation({ parentDocId: source.parentDocId, sourceIndex: 0 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    el.remove();
    return text;
  }

  it('names a DROPPED passage as retrieved but never sent to the model', async () => {
    const text = await panelText(fakeSource({ contextInclusion: 'dropped' }));
    expect(text).toContain('Retrieved · never sent to the model');
  });

  it('a DROPPED passage does not ALSO claim it grounded a sentence', async () => {
    // Slice-3 review MEDIUM-3. The pair is reachable: `RAGContext.java:429` hands the matcher every
    // kept citation regardless of the cut, and the matcher scores against chunk text it re-fetches
    // by identity — not against what the model was shown. So this card's fixture (a dropped passage
    // WITH a citation match) is the real production shape, and it used to render both
    // "never sent to the model" and "Grounds 1 sentence".
    const text = await panelText(fakeSource({ contextInclusion: 'dropped' }));
    expect(text).toContain('Retrieved · never sent to the model');
    // The badge stands alone. Its producer observed the actual cut; the grounding label is a
    // similarity against text the model never saw, and the two cannot both be informative.
    expect(text).not.toContain('Grounds 1 sentence');
  });

  it('names the partial and included states in the same vocabulary — and they KEEP their grounding', async () => {
    // The discriminator for the suppression above: it must be scoped to `dropped`, not a blanket
    // removal of the grounding badge from every card that carries an inclusion state.
    const partial = await panelText(fakeSource({ contextInclusion: 'partial' }));
    expect(partial).toContain('Partly sent to the model');
    expect(partial).toContain('Grounds 1 sentence');
    const included = await panelText(fakeSource({ contextInclusion: 'included' }));
    expect(included).toContain('Sent to the model');
    expect(included).toContain('Grounds 1 sentence');
  });

  it('renders NOTHING for a citation that said nothing about inclusion', async () => {
    // Every conversation persisted before 849 lands here. The card must be silent — not "included",
    // and not a placeholder caveat, which would put a hedge on the entire history.
    const text = await panelText(fakeSource());
    expect(text).not.toContain('sent to the model');
    expect(text).not.toContain('Sent to the model');
    // Non-vacuity: the card DID render (so "no badge" is the badge's absence, not an empty panel).
    expect(text).toContain('Grounds 1 sentence');
  });

  it('an unrecognised state is absence, not a guess', async () => {
    const text = await panelText(
      fakeSource({ contextInclusion: 'mostly' as never }),
    );
    expect(text).not.toContain('sent to the model');
    expect(text).not.toContain('mostly');
  });
});

describe('CitationsPanel', () => {
  it('renders nothing when both arrays are empty', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [];
    el.sources = [];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').trim();
    expect(text).toBe('');
    el.remove();
  });

  // Tempdoc 603 C1 — sources are graded by GROUNDING (faithfulness from the citation-matches), NOT the
  // BM25 retrieval score. A cited source ranks under "Grounds the answer"; a retrieved-but-uncited source
  // is demoted to the collapsed "retrieved · not cited" group (never "high confidence").
  it('grades a CITED source by grounding (Grounds the answer), joining by ARRAY POSITION not the doc-ordinal', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.architecture',
        chunkIndex: 2, // the source's DOC-ORDINAL (display only) — deliberately != its array position (0)
        score: 0.92, // BM25 — must NOT surface as a trust %
        excerpt: 'The system uses a three-process model.',
      }),
    ];
    // 603 PART X.B — the match's sourceIndex is the source's POSITION in this list (0), not the doc-ordinal (2).
    // Joining by doc-ordinal (the §1 bug) would find nothing here and wrongly read "not cited".
    el.citations = [
      fakeCitation({ parentDocId: 'doc.architecture', sourceIndex: 0, similarity: 0.8 }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 source');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('Grounds 1 sentence');
    expect(text).not.toContain('92%'); // the BM25 retrieval number is gone (559 §5)
    expect(text).toContain('three-process model');
    el.remove();
  });

  it('demotes a retrieved-but-UNCITED source to "retrieved · not cited", never high confidence', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      // cited: grounds the answer
      fakeSource({ parentDocId: 'a.md', chunkIndex: 0, excerpt: 'Grounded passage' }),
      // retrieved (high BM25) but never cited → demoted
      fakeSource({ parentDocId: 'b.md', chunkIndex: 1, score: 0.99, excerpt: 'Unused passage' }),
    ];
    el.citations = [fakeCitation({ parentDocId: 'a.md', sourceIndex: 0, similarity: 0.85 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Grounds the answer');
    expect(text).toContain('a.md');
    // the uncited high-BM25 source is in the collapsed "not cited" group, NOT shown by default
    // (868 §B.3 dropped the group's acquisition verb — it groups a set that can mix opened and
    // retrieved sources; the COUNT is what the toggle is for and is still pinned here).
    expect(text).toContain('1 not cited');
    expect(text).not.toContain('Unused passage');
    el.remove();
  });

  it('no citation-matches → neutral flat list (no trust grade, no BM25 %)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource({ parentDocId: 'a.md', score: 0.92, excerpt: 'Retrieved text' })];
    el.citations = []; // matcher didn't run
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    // 868 §B.3 — the flat header is acquisition-neutral now, so it is pinned on the ELEMENT rather
    // than as a substring: "1 source" alone would also match a stray mention elsewhere in the panel,
    // and this header's whole job is to be the count and nothing more.
    expect(
      (el.shadowRoot?.querySelector('.panel-header')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    ).toBe('1 source');
    expect(text).toContain('Retrieved text');
    expect(text).not.toContain('Grounds');
    expect(text).not.toContain('Grounds the answer');
    expect(text).not.toContain('92%');
    el.remove();
  });

  it('shows heading breadcrumb when present', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({
        parentDocId: 'doc.md',
        score: 0.8,
        headingText: 'Getting Started',
      }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('Getting Started');
    el.remove();
  });

  it('renders citation-match fallback with confidence bars', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [
      fakeCitation({ similarity: 0.75, sentenceText: 'Matched claim.' }),
    ];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('1 citation');
    expect(text).toContain('75%');
    expect(text).toContain('Matched claim');
    el.remove();
  });

  // The score clamp lives in the citation-match fallback (the one place a % is still shown — the
  // per-sentence faithfulness similarity). Source cards no longer show a % (603 C1).
  it('clamps citation-match scores above 1.0 to 100%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: 2.09 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('100%');
    expect(text).not.toContain('209%');
    const fill = el.shadowRoot?.querySelector('.fill') as HTMLElement | null;
    expect(fill?.style.width).toBe('100%');
    el.remove();
  });

  it('clamps negative citation-match scores to 0%', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.citations = [fakeCitation({ similarity: -0.5 })];
    document.body.appendChild(el);
    await settle(el);
    const text = (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
    expect(text).toContain('0%');
    el.remove();
  });

  it('fires citation-select exactly once per click', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource()];
    document.body.appendChild(el);
    await settle(el);
    let count = 0;
    el.addEventListener('citation-select', () => count++);
    const btn = el.shadowRoot?.querySelector('button.source') as HTMLElement;
    btn?.click();
    expect(count).toBe(1);
    el.remove();
  });

  it('collapses sources by default and discloses on toggle (559 C-1)', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource(), fakeSource()];
    // 603 C1 / PART X.B — the collapsible disclosure is the GROUNDED (tiered) path; supply a match per
    // source POSITION (0 and 1) so both are cited and render through renderTieredSources (the flat
    // no-matches path has no disclosure). Grounding joins by array position, so one match per index.
    el.citations = [fakeCitation({ sourceIndex: 0 }), fakeCitation({ sourceIndex: 1 })];
    document.body.appendChild(el);
    // Raw settle (no auto-expand) — assert the default-collapsed state.
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const header = el.shadowRoot?.querySelector('button.panel-header');
    expect(header?.getAttribute('aria-expanded')).toBe('false');
    expect(el.shadowRoot?.querySelector('button.source')).toBeNull();
    expect((header?.textContent ?? '').replace(/\s+/g, ' ')).toContain('2 sources');
    // Toggling discloses the cards.
    (header as HTMLElement).click();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(header?.getAttribute('aria-expanded')).toBe('true');
    expect(el.shadowRoot?.querySelectorAll('button.source').length).toBe(2);
    el.remove();
  });

  /* ── Tempdoc 822 Phase F11: the additive `externalDisclosure`, and the shipped default it must
        not move. The property lets ONE consumer (the Search v3 window) own the disclosure itself;
        the containment rule is that every other consumer renders byte-identically to before, so the
        default path is asserted here on ALL THREE header paths rather than on the one that changed. */

  /** The three shapes the panel's `render` dispatches over — one per header path. */
  const paths = [
    {
      what: 'tiered sources (the disclosure path)',
      apply: (el: CitationsPanel) => {
        el.sources = [fakeSource(), fakeSource()];
        el.citations = [fakeCitation({ sourceIndex: 0 }), fakeCitation({ sourceIndex: 1 })];
      },
      header: 'button.panel-header',
      body: 'button.source',
    },
    {
      what: 'flat sources (no matches, so no trust grade)',
      apply: (el: CitationsPanel) => {
        el.sources = [fakeSource(), fakeSource()];
        el.citations = [];
      },
      header: 'div.panel-header',
      body: 'button.source',
    },
    {
      what: 'citation-matches only (no retrieval sources)',
      apply: (el: CitationsPanel) => {
        el.sources = [];
        el.citations = [fakeCitation()];
      },
      header: 'div.panel-header',
      body: '.citation',
    },
  ] as const;

  const mount = async (
    apply: (el: CitationsPanel) => void,
    external: boolean,
  ): Promise<CitationsPanel> => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    apply(el);
    if (external) el.externalDisclosure = true;
    document.body.appendChild(el);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    return el;
  };

  for (const path of paths) {
    it(`leaves the SHIPPED default untouched — ${path.what}`, async () => {
      const el = await mount(path.apply, false);
      // The header is still the panel's own, and the two always-open bodies are still open: a
      // default that quietly started gating itself would close UnifiedChatView and SummarizeView.
      expect(el.externalDisclosure).toBe(false);
      expect(el.shadowRoot?.querySelector(path.header)).not.toBeNull();
      const openByDefault = path.header === 'div.panel-header';
      expect(el.shadowRoot?.querySelector(path.body) !== null).toBe(openByDefault);
      el.remove();
    });

    it(`renders BODY ONLY when the host owns the disclosure — ${path.what}`, async () => {
      const el = await mount(path.apply, true);
      // No header at all, on any of the three paths...
      expect(el.shadowRoot?.querySelector('.panel-header')).toBeNull();
      // ...and no body either, until the host says the disclosure is open — a suppressed toggle
      // must not leave a body permanently expanded.
      expect(el.shadowRoot?.querySelector(path.body)).toBeNull();
      el.sourcesExpanded = true;
      await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
      expect(el.shadowRoot?.querySelector('.panel-header')).toBeNull();
      expect(el.shadowRoot?.querySelector(path.body)).not.toBeNull();
      el.remove();
    });
  }
});

/* ═══════════════════════════════════════════════════════════════════════════════════════════════
 * Tempdoc 822 citation-mark presentation §5.4 (F1) — THE FAR SIDE OF THE SELECTION.
 *
 * `MarkdownBlock.ts` promises the selected mark is "highlighted in sync with the rail card". In the
 * Search v3 window the counterpart surface is THIS panel, and it carried zero references to
 * `selectedSource`: selecting a citation lit the inline mark and left its source card identical to
 * every other card. The store's whole justification is relating two surfaces; it rendered on one.
 *
 * The key is computed through the ONE `sourceKey` authority over the SAME two fields
 * `MarkdownBlock.makeMarker` uses — a second key function here would silently never agree.
 * ═══════════════════════════════════════════════════════════════════════════════════════════════ */
describe('CitationsPanel 822 §5.4 — the source card is the selection’s far side', () => {
  beforeEach(() => {
    __resetSelectedSource();
  });

  const A = { parentDocId: 'doc.alpha', startLine: 11 };
  const B = { parentDocId: 'doc.beta', startLine: 42 };

  /** Two cards from two DIFFERENT docs, disclosed, through the flat (no-grade) path. */
  async function mountTwo(): Promise<CitationsPanel> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({ ...A, excerpt: 'Alpha excerpt.' }),
      fakeSource({ ...B, excerpt: 'Beta excerpt.' }),
    ];
    el.citations = [];
    document.body.appendChild(el);
    await settle(el);
    return el;
  }

  const cards = (el: CitationsPanel): HTMLElement[] =>
    Array.from(el.shadowRoot?.querySelectorAll('button.source') ?? []) as HTMLElement[];

  /** The nth card, or a failure — an absent card must never read as a passing assertion. */
  const cardAt = (el: CitationsPanel, i: number): HTMLElement => {
    const c = cards(el)[i];
    if (!c) throw new Error(`no source card at index ${i}`);
    return c;
  };

  /* ── Reading the cascade out of the sheet itself ────────────────────────────────────────────
     happy-dom ABANDONS any declaration whose value carries a var() FALLBACK, and every rule this
     section governs is written that way — so a computed-style comparison of a selected card against
     an unselected one is passed by ANY fallback, right or wrong (probed: pointing the selected
     card's fill at the destructive role instead of the base surface stayed green). It cannot see
     `:hover` either. The
     invariants therefore have to be read off the stylesheet as STRUCTURE, related to each other,
     rather than pinned as literal strings on one side only — a literal pins the sheet against a
     copy of itself in the test, whereas relating the two rules breaks if EITHER side drifts. */

  const CSS = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
  /** Comments stripped, so a WHY-comment between two declarations cannot hide one from the parse. */
  const CSS_BARE = CSS.replace(/\/\*[\s\S]*?\*\//g, '');

  /** The declaration block of one rule, by its selector (regex source), or a failure. */
  const ruleBody = (selector: string): string => {
    const found = new RegExp(`(?:^|[\\s}])${selector}\\s*\\{([^}]*)\\}`).exec(CSS_BARE);
    if (!found?.[1]) throw new Error(`no rule for ${selector}`);
    return found[1];
  };

  /** Every `var(--…)` name a property's value mentions, outermost first. */
  const tokensOf = (body: string, property: string): string[] => {
    const decl = new RegExp(`(?:^|;)\\s*${property}\\s*:([^;]+);`).exec(body)?.[1];
    if (decl === undefined) throw new Error(`no '${property}' declaration in: ${body.trim()}`);
    const names = [...decl.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1] as string);
    if (names.length === 0) throw new Error(`'${property}' names no token: ${decl}`);
    return names;
  };

  /** The opt-in knob (outermost var) and the value it falls back to (innermost var). */
  const knobOf = (body: string, property: string): string => tokensOf(body, property)[0] as string;
  const fallbackOf = (body: string, property: string): string => {
    const names = tokensOf(body, property);
    return names[names.length - 1] as string;
  };

  it('marks ONLY the card whose source key is selected', async () => {
    const el = await mountTwo();
    expect(cards(el)).toHaveLength(2);
    expect(cards(el).filter((c) => c.hasAttribute('data-selected'))).toHaveLength(0);
    // Each card publishes the identity its inline mark carries in `dataset.citeKey`, from the ONE
    // `sourceKey` authority. Positional correspondence between the two surfaces is not one — the
    // panel renders every retrieved source, marks exist only for cited ones — so anything relating
    // a card to a mark (the `sv3-citation-selected` harness step) has to match on this.
    expect(cards(el).map((c) => c.dataset.citeKey)).toEqual([
      sourceKey(A.parentDocId, A.startLine),
      sourceKey(B.parentDocId, B.startLine),
    ]);

    setSelectedSource(sourceKey(B.parentDocId, B.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    const marked = cards(el).map((c) => c.hasAttribute('data-selected'));
    expect(marked).toEqual([false, true]);

    // The key is the (parentDocId, startLine) PAIR: the same doc at another line is a different
    // source, so a card must not light for its neighbour's passage.
    setSelectedSource(sourceKey(B.parentDocId, B.startLine + 1));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cards(el).filter((c) => c.hasAttribute('data-selected'))).toHaveLength(0);
    el.remove();
  });

  it('clicking a card publishes that source as the cross-surface selection', async () => {
    const el = await mountTwo();
    expect(getSelectedSource()).toBeNull();

    let dispatched = 0;
    el.addEventListener('citation-select', () => {
      dispatched += 1;
    });
    cardAt(el, 0).click();

    // The store now holds the clicked card's key...
    expect(getSelectedSource()).toBe(sourceKey(A.parentDocId, A.startLine));
    // ...and the existing deep-link dispatch still fires: the selection is an ADDED line, not a
    // replacement for what the card already did.
    expect(dispatched).toBe(1);

    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);
    el.remove();
  });

  it('unsubscribes from the store on disconnect (no leaked listener)', async () => {
    const el = await mountTwo();
    setSelectedSource(sourceKey(A.parentDocId, A.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);

    el.remove();
    setSelectedSource(null);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    // A live subscription would have re-rendered the detached panel and dropped the attribute.
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(true);

    // NON-VACUITY: a detached Lit element still renders when asked, so the stale attribute above
    // means "no notification arrived", not "updates stopped working". Ask directly and it clears.
    el.requestUpdate();
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cardAt(el, 0).hasAttribute('data-selected')).toBe(false);
  });

  it('announces the selected card to assistive tech, and only that card', async () => {
    // `data-selected` is a styling hook; it is invisible to a screen reader. Marking the card with
    // it alone reproduced, on the far side, the exact "state was visual-only" defect (F6) the slice
    // had just fixed on the inline mark. The idiom is `MarkdownBlock.ts`'s: present-and-true when
    // selected, REMOVED otherwise — never "false", which some readers announce as a present-but-off
    // property (noise on every other card in the list).
    const el = await mountTwo();
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(0);

    setSelectedSource(sourceKey(B.parentDocId, B.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;

    expect(cardAt(el, 1).getAttribute('aria-current')).toBe('true');
    expect(cardAt(el, 0).hasAttribute('aria-current')).toBe(false);
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(1);

    setSelectedSource(null);
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    expect(cards(el).filter((c) => c.hasAttribute('aria-current'))).toHaveLength(0);
    el.remove();
  });

  it('renders a selected card IDENTICALLY to an unselected one with no --cp-* tokens set (the shipped-containment proof)', async () => {
    const el = await mountTwo();
    // Sentinel values for the two tokens the BASE `.citation, .source` rule declares, so the
    // equality below is a comparison of real colours rather than of two empty strings.
    el.style.setProperty('--surface-2', 'rgb(1, 2, 3)');
    el.style.setProperty('--border-subtle', 'rgb(4, 5, 6)');
    setSelectedSource(sourceKey(A.parentDocId, A.startLine));
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const selected = cardAt(el, 0);
    const unselected = cardAt(el, 1);
    expect(selected.hasAttribute('data-selected')).toBe(true);
    expect(unselected.hasAttribute('data-selected')).toBe(false);

    // `.source[data-selected]` is (0,2,0) and OUTRANKS the base `.citation, .source` at (0,1,0), so
    // its fallbacks are not decoration — they are what a selected card resolves to in every consumer
    // that does not opt in (UnifiedChatView, SummarizeView, where `MarkdownBlock` marks and `SourcesPane`
    // both write the store, so `data-selected` IS reachable). Defaulting them to the base rule's own
    // `--surface-2` / `--border-subtle` is what makes "shipped is unaffected" true: the two cards
    // must be indistinguishable. A `transparent` default would blank the selected card instead.
    //
    // THE DISCRIMINATING HALF. The computed comparison below cannot see this: happy-dom abandons the
    // nested-fallback declarations outright, so the selected card falls through to the base rule no
    // matter WHAT the fallback names, and a wrong one stayed green. Relating the two rules' token
    // names is what actually proves the claim in the title — and it breaks from either direction,
    // whether the selected rule's fallback drifts or the base rule is repainted out from under it.
    const base = ruleBody('\\.citation,\\s*\\.source');
    const selectedRule = ruleBody('\\.source\\[data-selected\\]');
    expect(fallbackOf(selectedRule, 'background')).toBe(fallbackOf(base, 'background'));
    expect(fallbackOf(selectedRule, 'border-color')).toBe(fallbackOf(base, 'border'));
    // …and each one is opt-in through a `--cp-*` knob, which is what lets v3 wash the card at all.
    expect(knobOf(selectedRule, 'background')).toMatch(/^--cp-/);
    expect(knobOf(selectedRule, 'border-color')).toMatch(/^--cp-/);

    const sel = getComputedStyle(selected);
    const unsel = getComputedStyle(unselected);
    expect(unsel.backgroundColor).toBe('rgb(1, 2, 3)');
    expect(unsel.borderTopColor).toBe('rgb(4, 5, 6)');
    expect(sel.backgroundColor).toBe(unsel.backgroundColor);
    expect(sel.borderTopColor).toBe(unsel.borderTopColor);
    expect(sel.borderLeftColor).toBe(unsel.borderLeftColor);
    el.remove();
  });

  it('freezes the selected card’s un-overridden fill and edge at the base rule’s own tokens', () => {
    // Source-level, deliberately, and for the reason `MarkdownBlock.test.ts:791` records: happy-dom
    // ABANDONS a declaration whose value contains a nested var() fallback, which both of these are —
    // so the sentinel half of this proof (set `--cp-selected-region`, watch only the selected card
    // take it) is not expressible here and belongs to the live browser check. What IS checkable, and
    // is the actual invariant, is that the two fallbacks name the SAME tokens the base rule paints
    // with: that is why the computed equality above holds, and it would break the moment either
    // default drifted back toward `transparent`.
    const cssText = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
    expect(cssText).toContain('background: var(--cp-selected-region, var(--surface-2));');
    expect(cssText).toContain('border-color: var(--cp-selected-edge, var(--border-subtle));');
    // …and those ARE the base rule's values, read off the base rule itself rather than assumed.
    const base = /\.citation,\s*\.source\s*\{([^}]*)\}/.exec(cssText)![1]!;
    expect(base).toContain('background: var(--surface-2);');
    expect(base).toContain('border: 1px solid var(--border-subtle);');
  });

  it('leaves the hover edge on its shipped default (--cp-hover-edge is opt-in only)', () => {
    const cssText = (CitationsPanel.styles as unknown as { cssText: string }).cssText;
    // The accent is still the FALLBACK, so the shipped hover border is byte-identical; only a
    // window that declares `--cp-hover-edge` takes it off the accent (822 §5.4).
    expect(cssText).toContain('border-color: var(--cp-hover-edge, var(--text-secondary))');
    // The spec’s precedence rule, and it is about the WASH: `.source[data-selected]` sits after `.source:hover`
    // at the same (0,2,0), so a card that is both takes the selected fill; and the (0,3,0)
    // `[data-selected]:hover` after it raises that to the higher rung rather than layering a second
    // one under it. Flip this order and a hovered selected card would paint the plain hover fill.
    expect(cssText.indexOf('.source:hover')).toBeLessThan(cssText.indexOf('.source[data-selected]'));
    expect(cssText.indexOf('.source[data-selected] ')).toBeLessThan(
      cssText.indexOf('.source[data-selected]:hover'),
    );
  });

  it('keeps hover feedback on a card that is ALREADY selected', () => {
    // A shipped regression the containment claim denied. `.source:hover` and `.source[data-selected]`
    // are both (0,2,0) and the selected rule is later, so it took the border of any card the pointer
    // was over. While `data-selected` was unreachable that never showed; the moment this slice wired
    // the store up, the card the reader had just CLICKED became the one card in the panel with no
    // pointer feedback — in SummarizeView and UnifiedChatView alike. So the more specific
    // (0,3,0) rule has to restate the hover edge, not the background alone.
    const hovered = ruleBody('\\.source\\[data-selected\\]:hover');
    const resting = ruleBody('\\.source\\[data-selected\\]');
    // A selected+hovered card's border differs from a selected+unhovered one's: different knob…
    expect(knobOf(hovered, 'border-color')).not.toBe(knobOf(resting, 'border-color'));
    // …and, UNBRIDGED, the same ultimate colour `.source:hover` paints: a shipped consumer that
    // sets no --cp-* name sees hover mean one thing on every card, selected or not.
    expect(fallbackOf(hovered, 'border-color')).toBe(
      fallbackOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    // The plain hover knob stays IN the chain, so an unbridged consumer still gets exactly the edge
    // '.source:hover' paints — that is what shipped parity rests on.
    expect(tokensOf(hovered, 'border-color')).toContain(
      knobOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    // But its OWN knob comes first, deliberately: a consumer whose selected edge is STRONGER than
    // its hover edge (v3 spends 34% on selection and 14% on hover) would otherwise WEAKEN the edge
    // the moment the pointer arrived — reading as "less selected" exactly when the reader is acting
    // on it.
    expect(knobOf(hovered, 'border-color')).not.toBe(
      knobOf(ruleBody('\\.source:hover'), 'border-color'),
    );
    expect(fallbackOf(hovered, 'border-color')).not.toBe(fallbackOf(resting, 'border-color'));
  });
});

/**
 * Tempdoc 865 §7.3 — the panel's side of "a matcher that never ran mints no verdict".
 *
 * The panel never spoke the false verdict itself: `render` routes to the ungraded flat list whenever
 * no match survives (603 §22/U2's `!hasCitations` branch), so a timed-out run showed neutral cards.
 * That is not a wrong statement, but it is the WRONG SILENCE — it is byte-identical to a keyword-only
 * run, and it withholds a fact the reader can act on. With the pass-level flag present these sources
 * get their own heading and a badge that names the MATCHER; with it absent, every consumer keeps the
 * flat list exactly as before.
 */
describe('CitationsPanel — the grounding pass did not complete (865 PR-0)', () => {
  async function panel(
    configure: (el: CitationsPanel) => void,
  ): Promise<{ text: string; el: CitationsPanel }> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({ parentDocId: 'a.md', startLine: 1, excerpt: 'first passage' }),
      fakeSource({ parentDocId: 'b.md', startLine: 9, excerpt: 'second passage' }),
    ];
    el.citations = [];
    configure(el);
    document.body.appendChild(el);
    await settle(el);
    return { text: (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' '), el };
  }

  it('gives the sources their own heading, and never the "not cited" group', async () => {
    const { text, el } = await panel((p) => {
      p.groundingIncomplete = true;
    });
    expect(text).toContain('Not scored — the grounding check did not complete for these 2 sources');
    expect(text).toContain('Retrieved · grounding check did not complete');
    // The two verdicts that must NOT appear: the per-source one, and the collapsed group that files
    // a source under it.
    expect(text).not.toContain('Retrieved · not cited');
    // 868 §B.3 — the group's own words are now just "not cited", so this refusal covers BOTH the
    // per-source verdict and the group that files a source under it, in one assertion.
    expect(text).not.toContain('not cited');
    // Nor the BUDGET wording — a scorer failure is not a budget outcome (the "not `unexamined`" rule).
    expect(text).not.toContain('not examined');
    expect(
      el.shadowRoot?.querySelector('[data-testid="grounding-incomplete-header"]'),
    ).not.toBeNull();
    el.remove();
  });

  it('says "this source" when there is exactly one', async () => {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [fakeSource({ parentDocId: 'a.md', startLine: 1 })];
    el.citations = [];
    el.groundingIncomplete = true;
    document.body.appendChild(el);
    await settle(el);
    expect((el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ')).toContain(
      'Not scored — the grounding check did not complete for this source',
    );
    el.remove();
  });

  it('ABSENT flag keeps the flat, ungraded list byte-for-byte', async () => {
    // The precedent's rule, at the panel: a consumer that says nothing about its pass — the ask
    // plane, every pre-865 record — must be unchanged. Both the default and an explicit `false`.
    const unset = await panel(() => {});
    expect(unset.text).not.toContain('grounding check did not complete');
    expect(unset.text).not.toContain('Retrieved · not cited');
    // 868 §B.3 — the flat header, pinned on the element (see the neutral-flat-list case above).
    expect(
      (unset.el.shadowRoot?.querySelector('.panel-header')?.textContent ?? '')
        .replace(/\s+/g, ' ')
        .trim(),
    ).toBe('2 sources');
    unset.el.remove();

    const explicit = await panel((p) => {
      p.groundingIncomplete = false;
    });
    expect(explicit.text).toBe(unset.text);
    explicit.el.remove();
  });

  it('FULLTEXT_FALLBACK still wins — a keyword run is graded by nothing at all', async () => {
    const { text, el } = await panel((p) => {
      p.groundingIncomplete = true;
      p.retrievalMode = 'FULLTEXT_FALLBACK';
    });
    expect(text).not.toContain('grounding check did not complete');
    expect(
      (el.shadowRoot?.querySelector('.panel-header')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    ).toBe('2 sources');
    el.remove();
  });

  it('a matcher that RAN still demotes its uncited sources — the honest verdict is untouched', async () => {
    // The control. One cite lands on source 1; source 0 was judged and grounded nothing, so it
    // belongs in the collapsed "not cited" group and keeps that wording (868 §B.3 dropped only the
    // acquisition verb from it; the demotion and the count are the behaviour under test).
    const { text, el } = await panel((p) => {
      p.citations = [fakeCitation({ parentDocId: 'b.md', sourceIndex: 1 })];
    });
    expect(text).toContain('1 not cited');
    expect(text).toContain('Grounds 1 sentence');
    expect(text).not.toContain('grounding check did not complete');
    el.remove();
  });
});

/**
 * Tempdoc 870 items 6a + 7 — the owner's 2026-08-26 visual pass on this shared panel: the preview
 * reveal eases instead of snapping, and the type scale collapses to two roles with no shouting.
 *
 * happy-dom runs no cascade and lays nothing out, so the DECLARATIONS are the mechanism here and
 * they are what these pin — the same posture the sv3 sheets' own token tests take.
 */
describe('CitationsPanel — 870 motion and type scale', () => {
  const styleText = (): string =>
    [CitationsPanel.styles]
      .flat(Infinity)
      .map((s) => String((s as { cssText?: string }).cssText ?? s))
      .join('\n');

  /** One rule's declaration body, so a match from a NEIGHBOURING rule cannot satisfy an assertion. */
  const ruleBody = (selector: string): string => {
    const m = new RegExp(`${selector}\\s*\\{([^}]*)\\}`).exec(styleText());
    if (m === null) throw new Error(`CitationsPanel.styles has no ${selector} rule`);
    return m[1] as string;
  };

  it('item 6a: the hover preview FADES — `display` alone could never animate', () => {
    const preview = ruleBody('\\.source \\.preview');
    // The layout half stays `display` (opacity 0 in the flow would keep the card tall at rest), so
    // the fade needs all three parts: an opacity to run, `allow-discrete` to hold `display` while it
    // runs, and a `@starting-style` opacity for the entering box to come FROM. Any one missing and
    // the reveal snaps again exactly as it did before.
    expect(preview).toMatch(/opacity:\s*0/);
    expect(preview).toMatch(/transition:/);
    expect(preview).toContain('allow-discrete');
    const css = styleText();
    expect(css).toContain('@starting-style');
    expect(css).toMatch(/@starting-style\s*\{[\s\S]*?opacity:\s*0/);
    // Reduced motion drops the animation, not the affordance — the panel's existing posture.
    const reduced = css.slice(css.lastIndexOf('@media (prefers-reduced-motion: reduce)'));
    expect(reduced).toContain('.source .preview');
    expect(reduced).toMatch(/transition:\s*none/);
  });

  it('item 7: nothing in this panel shouts — no uppercase, no hand-authored tracking', () => {
    // Search v3's own stated law (Sv3Main.ts: "v3 uses UPPERCASE nowhere"), which this shared panel
    // was breaking in the one window that says so. The strings already read as sentences, so this is
    // a transform removal and not a re-wording. Comments stripped: the prose names what it retired.
    const declarations = styleText().replace(/\/\*[\s\S]*?\*\//g, '');
    expect(declarations).not.toContain('text-transform');
    expect(declarations).not.toContain('letter-spacing');
  });

  it('item 7: two type roles — meta at xs, content at sm', () => {
    const declarations = styleText().replace(/\/\*[\s\S]*?\*\//g, '');
    const sizes = new Set(
      [...declarations.matchAll(/font-size:\s*var\((--font-size-[\w-]+)\)/g)].map((m) => m[1]),
    );
    expect([...sizes].sort()).toEqual(['--font-size-sm', '--font-size-xs']);
    // The three roles the item named, each on the side of the split it belongs to.
    expect(ruleBody('\\.panel-header')).toMatch(/font-size:\s*var\(--font-size-xs\)/);
    expect(ruleBody('\\.tier-header')).toMatch(/font-size:\s*var\(--font-size-xs\)/);
    expect(ruleBody('\\.score-metric')).toMatch(/font-size:\s*var\(--font-size-xs\)/);
    // ONE weight treatment: 500 where a label carries emphasis, normal where it does not.
    expect(ruleBody('\\.panel-header')).toMatch(/font-weight:\s*500/);
    expect(ruleBody('\\.tier-header')).toMatch(/font-weight:\s*500/);
    expect(ruleBody('\\.score-metric')).toMatch(/font-weight:\s*400/);
    // Content rides the transcript's rhythm rather than the tighter 1.4 this panel used to set.
    expect(ruleBody('\\.sentence')).toMatch(/line-height:\s*1\.5/);
    expect(ruleBody('\\.source \\.preview')).toMatch(/line-height:\s*1\.5/);
    // The dead `.excerpt` role is gone — no template in this file ever applied that class, which is
    // why it could drift into a fourth size unnoticed.
    expect(declarations).not.toContain('.excerpt');
  });
});

/**
 * Tempdoc 868 §B.3 — the source card's excerpt BUDGET, once an excerpt can be a whole page.
 *
 * The read tool hands the model a whole page and the source carries all of it, deliberately: the
 * citation matcher verifies an opened source against its own literal text (§B.1), so the wire value
 * must stay uncapped. The card is the opposite kind of thing — a fixed-size promise that lets the
 * reader scan several sources at once — and rendering a full page turned one card into the whole
 * panel (observed live on `278-decision-log.md`, when a page was the retired flat 3,000 characters).
 *
 * The page size is now WINDOW-DERIVED (tempdoc 883 decision 3): `ReadDocumentTool.readPageChars` is
 * the smaller of `ContextBudget.readDocumentPageChars()` (half the input budget, capped at 4096
 * tokens) and the Layer-2 cut minus the 600-char page headroom — the latter arm being the one that
 * binds, which is how a page measures 7,592 chars at a 32,768-token window and 1,704 at 4,096 —
 * and the `READ_PAGE_CHARS` literal is gone. So these tests deliberately
 * assert against the CARD budget, which is a constant, and never against a page size, which is not.
 * The fixtures below are sized to sit either side of that card budget for the same reason.
 *
 * So the clamp is a DISPLAY clamp, in the view-model projection, over every excerpt. These pin both
 * halves: it bites the long one, and the record behind it is untouched.
 */
describe('CitationsPanel — excerpt display budget (868 §B.3)', () => {
  // A page-shaped fixture: real words, so the boundary walk has somewhere to land, and comfortably
  // over the card budget. Its length is a FIXTURE choice, not a claim about any window's page size
  // (which varies — see the note above); the assertions below only require "well over 320".
  const OPENED_TEXT = 'opened page text '.repeat(177).trim();
  // ~300 chars — a retrieved excerpt at the top of what its producer emits (RagContextOps clamps to
  // 240; this is deliberately above that and still under the card budget).
  const RETRIEVED_TEXT = 'passage '.repeat(38).trim();

  async function cards(): Promise<{ el: CitationsPanel; sentences: string[] }> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = [
      fakeSource({ parentDocId: 'long.md', startLine: 1, excerpt: OPENED_TEXT, acquisition: 'opened' }),
      fakeSource({ parentDocId: 'short.md', startLine: 5, excerpt: RETRIEVED_TEXT }),
    ];
    el.citations = [];
    document.body.appendChild(el);
    await settle(el);
    const sentences = Array.from(el.shadowRoot?.querySelectorAll('.sentence') ?? []).map((n) =>
      (n.textContent ?? '').trim(),
    );
    return { el, sentences };
  }

  it('the premise: the two fixtures sit on either side of the budget', () => {
    // Stated rather than assumed — a fixture that drifted under the ceiling would make the clamp
    // test below pass while testing nothing. The bound is a FIXTURE floor (comfortably over the
    // 320-char card budget), not the read tool's page size: that one is window-derived and has no
    // fixed number to pin (883 decision 3).
    expect(OPENED_TEXT.length).toBeGreaterThan(2900);
    expect(RETRIEVED_TEXT.length).toBeGreaterThan(280);
    expect(RETRIEVED_TEXT.length).toBeLessThan(320);
  });

  it('an opened page is elided to the card budget, at a word boundary, with an ellipsis', async () => {
    const { el, sentences } = await cards();
    const opened = sentences[0]!;
    expect(opened.length).toBeLessThanOrEqual(330);
    expect(opened.endsWith('…')).toBe(true);
    // A word boundary, not a mid-word cut — asserted structurally rather than by naming the word the
    // cut happens to land on: the body is a PREFIX of the page, and the character it stopped before
    // is whitespace. Both halves are needed. A prefix check alone passes a mid-word cut, and a
    // "ends with a letter" check passes text that was never the page's.
    const body = opened.slice(0, -1);
    expect(OPENED_TEXT.startsWith(body)).toBe(true);
    expect(OPENED_TEXT.charAt(body.length)).toBe(' ');
    el.remove();
  });

  it('a retrieved excerpt under the budget renders byte-for-byte — the clamp is a ceiling, not a format', async () => {
    const { el, sentences } = await cards();
    expect(sentences[1]).toBe(RETRIEVED_TEXT);
    expect(sentences[1]).not.toContain('…');
    el.remove();
  });

  it('the SOURCE keeps its full text — display shortened it, nothing else did', async () => {
    // The load-bearing half. The matcher verifies an opened source against this literal text and the
    // pane's witness check reads it too; a clamp that reached the record would quietly weaken both.
    const { el } = await cards();
    expect(el.sources[0]!.excerpt).toBe(OPENED_TEXT);
    expect(el.sources[0]!.excerpt.length).toBeGreaterThan(2900);
    el.remove();
  });
});

/**
 * Tempdoc 868 §B.3 — the panel's AGGREGATE copy, once a run can mix acquisitions.
 *
 * The per-source badge got the honest word first ("Opened · not cited"), which left the group
 * headers as the last place claiming a retrieval for documents nothing ranked: "N sources retrieved"
 * over a set, and "Show N retrieved (not cited)" over a slot two sources reached by different routes.
 * A per-source axis cannot be summed into one verb — so the headers say less, and the cards inside
 * them keep saying it each for itself. These pin both halves of that split.
 */
describe('CitationsPanel — acquisition-neutral group copy (868 §B.3)', () => {
  const MIXED: RetrievalCitation[] = [
    fakeSource({ parentDocId: 'a.md', startLine: 1, excerpt: 'grounded passage' }),
    fakeSource({ parentDocId: 'b.md', startLine: 5, excerpt: 'unused hit' }),
    fakeSource({
      parentDocId: 'c.md',
      startLine: 9,
      excerpt: 'unused page',
      acquisition: 'opened',
    }),
  ];

  function normalized(el: CitationsPanel): string {
    return (el.shadowRoot?.textContent ?? '').replace(/\s+/g, ' ');
  }

  async function panelOf(sources: RetrievalCitation[]): Promise<CitationsPanel> {
    const el = document.createElement('jf-citations-panel') as CitationsPanel;
    el.sources = sources;
    // One cite, on source 0 — so the matcher RAN (the tiered path) and sources 1 and 2 land in the
    // collapsed group together: one retrieved, one opened.
    el.citations = [fakeCitation({ parentDocId: 'a.md', sourceIndex: 0 })];
    document.body.appendChild(el);
    await settle(el);
    return el;
  }

  it('says nothing about retrieval in ANY group copy when the set is mixed', async () => {
    const el = await panelOf(MIXED.map((s) => ({ ...s })));
    const text = normalized(el);
    // The collapsed state is the one where every visible word is group copy — the per-source badges
    // are behind the toggle, so a stray "retrieved" here could only have come from a header.
    expect(text.toLowerCase()).not.toContain('retrieved');
    // ...and it still tells the reader the two things the copy is FOR: the total and the slot count.
    expect(
      (el.shadowRoot?.querySelector('.panel-header')?.textContent ?? '').replace(/\s+/g, ' ').trim(),
    ).toContain('3 sources');
    expect(text).toContain('2 not cited');
    el.remove();
  });

  it('the neutral group still holds sources that describe themselves — each by its own route', async () => {
    // The other half. Neutral group copy would be a REGRESSION if the provenance vanished with it;
    // it moved down to the cards, where it is a per-source fact rather than a claim about a set.
    const el = await panelOf(MIXED.map((s) => ({ ...s })));
    (el as unknown as { showWeak: boolean }).showWeak = true;
    await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
    const text = normalized(el);
    expect(text).toContain('Opened · not cited');
    expect(text).toContain('Retrieved · not cited');
    el.remove();
  });

  it('a wholly-retrieved run reads the same — the copy is neutral, not conditional', async () => {
    // Neutrality is unconditional: the headers do not switch wording when a run happens to be all
    // search hits. A conditional header would be a second acquisition authority, deciding for a SET
    // what `acquisitionWord` decides per source.
    const el = await panelOf(
      MIXED.map((s) => {
        const { acquisition: _dropped, ...retrieved } = s;
        return retrieved;
      }),
    );
    const text = normalized(el);
    expect(text.toLowerCase()).not.toContain('retrieved');
    expect(text).toContain('2 not cited');
    el.remove();
  });
});
