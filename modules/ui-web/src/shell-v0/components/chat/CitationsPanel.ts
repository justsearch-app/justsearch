// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C3) — `<jf-citations-panel>` Lit block.
 *
 * Renders RAG citations from the `rag.citation_matches` SSE event payload
 * (see `core.rag-ask` shape's eventSchema). Each citation card shows the
 * matched sentence, the parent document ref, similarity score, and (when
 * present) excerpt text.
 *
 * Consumed by AskView (C3). The view passes the parsed payload through; this
 * Search v3 also observes current index membership without changing the evidence.
 *
 * Per §9.F Q1 (hybrid) + slice 486 G140: location-metadata + click-to-verify
 * affordances land in a follow-up polish phase. V1 ships the minimum
 * structural rendering (sentence + score + parent doc).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { displayPassageExcerpt } from './passageExcerptPresentation.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
import '../Control.js';
// Tempdoc 565 §29 Tier-3 — open the cited LOCAL file (the uniquely-local citation affordance).
import { openLocalFile } from '../../plugin-api/capabilities/platform.js';
// Tempdoc 822 citation-mark presentation §5.4 — the FAR SIDE of the inline mark's selection. The
// store's whole justification is relating two surfaces; until now it rendered on one (F1), so in the
// v3 window — where this panel IS the rail card's counterpart — selecting a citation lit the mark and
// left every source card identical. The SAME four imports `MarkdownBlock.ts` uses, and the same
// `sourceKey` authority: a second key function here would silently never agree with the marks.
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../../state/selectedSource.js';
import {
  toEvidenceItem,
  evidenceScore,
  tierGroup,
  tierClass,
  filenameOf,
  sourceGrounding,
  sourceGroundingLabel,
  inclusionBadge,
  suppressGroundingFor,
  type EvidenceScore,
  type SourceGrounding,
  type InclusionBadge,
} from './evidenceProjection.js';
import type {
  Acquisition,
  AnswerEvidenceSource,
  CitationMatch,
  CitationSelectDetail,
  SourceCoverage,
} from './citationTypes.js';

// The pure data shapes moved to `citationTypes.ts` (cycle break,
// tempdoc 530 UI-cycle gate). Re-exported here so existing importers of
// `./CitationsPanel.js` keep working unchanged.
export type {
  AnswerEvidenceSource,
  CitationMatch,
  RetrievalCitation,
  CitationSelectDetail,
} from './citationTypes.js';

export class CitationsPanel extends JfElement {
  static properties = {
    citations: { type: Array, attribute: false },
    sources: { type: Array, attribute: false },
    sourceCoverage: { type: Array, attribute: false },
    groupByCoverage: { type: Boolean, attribute: false },
    groundingIncomplete: { type: Boolean, attribute: false },
    retrievalMode: { type: String, attribute: false },
    showWeak: { state: true },
    sourcesExpanded: { type: Boolean, attribute: false },
    externalDisclosure: { type: Boolean, attribute: false },
    resultBeingExplained: { type: Boolean, attribute: false },
    observeAvailability: { type: Boolean, attribute: false },
    presenceScope: { type: String, attribute: false },
    apiBase: { type: String, attribute: false },
    unavailable: { state: true },
  };

  declare citations: CitationMatch[];
  declare sources: AnswerEvidenceSource[];
  /**
   * Tempdoc 836 S2S3-A.3 — the per-source examination facts, so a source the verification budget
   * never looked at is not filed under the "not cited" group. Empty (the default) keeps the
   * established two-state behaviour for every consumer whose run reports no coverage.
   */
  declare sourceCoverage: SourceCoverage[];
  /** Examination facts can select grouping without matches, including with keyword retrieval.
   * Delegate hosts disable this to keep their established flat/tiered choice (WP4.2a D1).
   */
  declare groupByCoverage: boolean;
  /**
   * Tempdoc 865 §7.3 — the run reported that its grounding pass did not complete, so an empty
   * {@link citations} list is a MISSING verdict, not a negative one. `false` (the default) is the
   * established behaviour for every consumer whose run says nothing about its pass.
   */
  declare groundingIncomplete: boolean;
  declare retrievalMode: string;
  declare showWeak: boolean;
  // Tempdoc 559 Authority IV (C-1): sources are disclosed on demand, not
  // expanded by default — a short answer stays short (the ReasoningBlock
  // disclosure pattern). The header is the toggle; the body renders only when open.
  //
  // Promoted from `state: true` to a public property by tempdoc 822 Phase F11 so an
  // `externalDisclosure` host can drive it. Default and reactivity are unchanged.
  declare sourcesExpanded: boolean;
  /**
   * The HOST owns the disclosure, so this panel renders BODY ONLY (tempdoc 822 Phase F11).
   *
   * Default `false` — every shipped consumer (`views/UnifiedChatView.ts`,
   * `views/SummarizeView.ts`) is byte-identical to before. Search v3 sets it because its answer tail
   * is ONE 24px row: a component-owned header on its own line makes that two rows, and the panel's
   * uppercase `▸ N SOURCES` is a second disclosure dialect for an act the window already declares.
   * When true, ALL THREE header paths are suppressed and ALL THREE bodies are gated on
   * {@link sourcesExpanded} — a host that hides the toggle must not leave a body permanently open.
   */
  declare externalDisclosure: boolean;
  /** A ranking trace names its subject; it supplies no document passage or grounding verdict. */
  declare resultBeingExplained: boolean;
  /** Search v3 opts into current index membership; evidence records are never modified. */
  declare observeAvailability: boolean;
  declare presenceScope: string;
  declare apiBase: string;
  declare private unavailable: Set<string>;
  private presenceRequest: AbortController | null = null;

  private selectedSourceUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.resultBeingExplained = false;
    this.observeAvailability = false;
    this.presenceScope = '';
    this.apiBase = '';
    this.unavailable = new Set();
    this.citations = [];
    this.sources = [];
    this.sourceCoverage = [];
    this.groupByCoverage = true;
    this.groundingIncomplete = false;
    this.retrievalMode = '';
    this.showWeak = false;
    this.sourcesExpanded = false;
    this.externalDisclosure = false;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 565 §12.3.E / 822 §5.4 — re-render the card highlight when the cross-surface selection
    // changes (an inline `[n]` mark or another card was focused). Lifecycle mirrored EXACTLY from
    // `MarkdownBlock.ts` (its counterpart on the near side): store the unsubscribe fn here, call and
    // null it in `disconnectedCallback` — a leaked subscription would keep a detached panel alive.
    this.selectedSourceUnsub = subscribeSelectedSource(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.selectedSourceUnsub?.();
    this.selectedSourceUnsub = null;
    this.presenceRequest?.abort();
    this.presenceRequest = null;
    if (this.unavailable.size > 0) this.unavailable = new Set();
  }

  protected override onReveal(): void {
    void this.checkAvailability();
  }

  protected override updated(changed: Map<string, unknown>): void {
    if (changed.has('sources') || changed.has('observeAvailability') || changed.has('presenceScope') || changed.has('apiBase')) {
      void this.checkAvailability();
    }
  }

  private async checkAvailability(): Promise<void> {
    this.presenceRequest?.abort();
    const request = new AbortController();
    this.presenceRequest = request;
    this.unavailable = new Set();
    if (!this.observeAvailability || !this.isConnected) return;
    const sources = this.sources;
    const scope = this.presenceScope;
    const apiBase = this.apiBase;
    const ids = [...new Set(sources.map(source => source.parentDocId).filter(Boolean))];
    for (let offset = 0; offset < ids.length; offset += 64) {
      if (request.signal.aborted || this.presenceRequest !== request
          || this.sources !== sources || this.presenceScope !== scope || this.apiBase !== apiBase) return;
      const batch = ids.slice(offset, offset + 64);
      try {
        const response = await authorizedFetch(`${apiBase}/api/documents/presence`, {
          method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ docIds: batch }), signal: request.signal,
        }, { retry: false });
        if (!response.ok) continue;
        const data: unknown = await response.json();
        if (request.signal.aborted || this.presenceRequest !== request || !this.isConnected
            || this.sources !== sources || this.presenceScope !== scope || this.apiBase !== apiBase || !this.observeAvailability) return;
        const states = data !== null && typeof data === 'object' && 'states' in data ? data.states : null;
        if (states === null || typeof states !== 'object' || Array.isArray(states)) continue;
        this.unavailable = new Set([...this.unavailable, ...batch.filter(id =>
          Object.hasOwn(states, id) && (states as Record<string, unknown>)[id] === 'absent')]);
      } catch {
        // A failed/slow Engine is unknown. Other batches keep their own independent result.
      }
    }
  }

  static styles = css`
    :host {
      display: block;
      margin: 0.5rem 0;
    }
    /* Tempdoc 870 item 7 — the panel's type scale, normalised. Eight declared roles resolved to two
       sizes with three uppercase sites and hand-authored letter-spacing between them; the roles are
       two now — META/LABELS at --font-size-xs, CONTENT at --font-size-sm — and nothing shouts.
       Sentence case is also the app-wide direction and Search v3's own stated law (Sv3Main.ts:236):
       "v3 uses UPPERCASE nowhere", which this shared panel was breaking in the one window that says
       so. The strings already read as sentences ("Grounds the answer"), so nothing had to be
       re-worded — only the transform that was shouting them removed. */
    .panel-header {
      font-size: var(--font-size-xs);
      font-weight: 500;
      color: var(--text-secondary);
      padding: 0.25rem 0 0.5rem;
      /* 559 C-1: disclosure toggle — reset native button chrome, keep the look. */
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      background: none;
      border: none;
      font-family: inherit;
      cursor: pointer;
    }
    .panel-header:hover {
      color: var(--text-primary);
    }
    .disclosure-chevron {
      display: inline-block;
      transition: transform var(--duration-fast) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .disclosure-chevron { transition: none; }
    }
    .disclosure-chevron.open {
      transform: rotate(90deg);
    }
    .citations {
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    .citation,
    .source {
      padding: 0.5rem 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    button.source {
      cursor: pointer;
      transition: background var(--duration-fast), border-color var(--duration-fast);
      text-align: left;
      width: 100%;
    }
    /* Tempdoc 565 §29 Tier-3 — the cited-file card wraps the source button + an open-file affordance. */
    .source-card {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .source-open {
      align-self: flex-start;
      cursor: pointer;
      background: none;
      border: none;
      padding: 0.1rem 0.25rem;
      color: var(--text-command);
      font-size: var(--font-size-xs);
      text-align: left;
    }
    .source-open:hover {
      text-decoration: underline;
    }
    .source:hover {
      background: var(--surface-3);
      /* 822 §5.4 — the panel's one accent spend, recovered: a hover edge is not an act-now signal,
         so the window that wants it neutral re-points it. Default unchanged. */
      border-color: var(--cp-hover-edge, var(--accent-tint));
    }
    /* Tempdoc 822 §5.4 — the selected source card. Containment means every --cp-* name defaults to
       TODAY'S VALUE, not to nothing: this selector's (0,2,0) outranks the base '.citation, .source'
       at (0,1,0), so a 'transparent' default would blank a selected card's fill and edge in the
       shipped windows (UnifiedChatView, SummarizeView) where 'data-selected' is reachable. Defaulting to
       the base rule's own '--surface-2' / '--border-subtle' makes an un-tokenized selected card
       byte-identical to an unselected one, and the v3 window opts in from Sv3Main.ts. These rules
       sit AFTER .source:hover on purpose: they share its (0,2,0) specificity, so source order is what
       decides the border of a card that is both hovered and selected — and the SELECTED edge is the
       one that should win. The design spec’s precedence rule: a row that is both takes the
       HIGHER wash and never shows two competing fills, which is why [data-selected]:hover restates
       background alone at (0,3,0) rather than layering a second fill under the hover. */
    .source[data-selected] {
      background: var(--cp-selected-region, var(--surface-2));
      border-color: var(--cp-selected-edge, var(--border-subtle));
    }
    .source[data-selected]:hover {
      background: var(--cp-selected, var(--surface-3));
      /* The hover EDGE has to be restated here, and its absence was a shipped regression rather than
         a v3 nicety: '.source:hover' and '.source[data-selected]' are both (0,2,0), so the selected
         rule — later in source — took the border of every card the pointer was over. Before this
         slice 'data-selected' was unreachable and every hovered card showed the hover edge; the
         moment the store was wired up, the card you had just CLICKED became the one card in the
         panel with no hover feedback, in UnifiedChatView, SummarizeView and v3 alike. Restating it keeps
         the source order's meaning intact — when a card is both, it still takes the HIGHER wash —
         while letting the pointer's own signal survive the selection it just made. */
      /* Its own name, falling through to the plain hover edge: a consumer whose selected edge
         is STRONGER than its hover edge (v3 spends 34 % on selection and 14 % on hover) would
         otherwise WEAKEN the edge on hover, reading as "less selected" the moment the pointer
         arrives. Shipped consumers set neither name and fall through to the accent edge exactly as
         before. */
      border-color: var(--cp-selected-hover-edge, var(--cp-hover-edge, var(--accent-tint)));
    }
    /* Tempdoc 870 item 6a — the reveal EASES now. 'display' is a discrete property, so the
       none → block swap this rule pair has always made could never animate: the preview appeared
       whole, one frame after the pointer arrived, and the card grew under it. The layout half still
       has to be 'display' (a preview left in the flow at opacity 0 would keep the card tall at rest
       and take pointer events), so the fade is bought with the 'allow-discrete' transition behavior:
       'display' is held at its OLD value for the transition's duration while 'opacity' runs, and
       '@starting-style' gives the entering box an opacity to come FROM (without it the element's
       first style after 'display: block' is already the final one and nothing interpolates). */
    .source .preview {
      display: none;
      opacity: 0;
      margin-top: 0.4rem;
      padding-top: 0.4rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      line-height: 1.5;
      transition:
        opacity var(--duration-fast) var(--ease-standard),
        display var(--duration-fast) var(--ease-standard) allow-discrete;
    }
    .source:hover .preview,
    .source:focus-within .preview {
      display: block;
      opacity: 1;
    }
    @starting-style {
      .source:hover .preview,
      .source:focus-within .preview {
        opacity: 0;
      }
    }
    /* The panel's existing reduced-motion posture (the disclosure chevron above): drop the animation,
       keep the affordance — the preview still appears, it just does not fade in. */
    @media (prefers-reduced-motion: reduce) {
      .source .preview {
        transition: none;
      }
    }
    .preview .detail {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.2rem;
    }
    .citation .header {
      display: flex;
      gap: 0.5rem;
      align-items: center;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.25rem;
    }
    .doc-ref {
      font-family: ui-monospace, monospace;
    }
    .score {
      font-weight: 500;
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
    }
    .confidence-bar {
      display: inline-block;
      width: 2.5rem;
      height: 0.375rem;
      border-radius: 0.1875rem;
      background: var(--surface-tertiary);
      overflow: hidden;
    }
    .confidence-bar-fill {
      height: 100%;
      border-radius: 0.1875rem;
      transition: width var(--duration-normal);
    }
    .score.high { color: var(--text-tint); }
    .score.medium { color: var(--text-secondary); }
    .score.low { color: var(--text-warning); }
    /* Tempdoc 603 C1 — the per-source GROUNDING (faithfulness) badge. Cited → tier colour; uncited → muted
       (never the alarming warning colour — "retrieved, not cited" is neutral, not a fault). */
    .grounding { font-size: var(--font-size-xs); font-weight: 500; white-space: normal; }
    .grounding.high { color: var(--text-tint); }
    .grounding.medium { color: var(--text-secondary); }
    .grounding.uncited { color: var(--text-secondary); font-style: italic; }
    /* Tempdoc 849 §5/§7 — the RETRIEVED-vs-RECEIVED badge, beside the grounding badge because they
       are siblings: two budget cuts, one stage apart (§5.5). Quiet by default — "sent to the model"
       is the unremarkable case and must not compete with the grounding tier for attention. DROPPED
       takes the warning ink and nothing stronger: a passage the prompt had no room for is a fact
       about the budget, not a fault, and the danger ink would read as an error the reader must fix. */
    .inclusion {
      font-size: var(--font-size-xs);
      font-weight: 500;
      white-space: normal;
      color: var(--text-secondary);
    }
    .inclusion.dropped { color: var(--text-warning); }
    /* 559 Authority IV — the declared metric label (the "%" is no longer bare). */
    .score-metric {
      margin-left: 0.3rem;
      /* A unit suffix on the figure beside it — the meta role WITHOUT emphasis (870 item 7). */
      font-weight: 400;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .confidence-bar {
      width: 3rem;
      height: 4px;
      border-radius: 2px;
      background: var(--border-subtle);
      overflow: hidden;
    }
    .confidence-bar .fill {
      height: 100%;
      border-radius: 2px;
      transition: width var(--duration-normal);
    }
    .confidence-bar .fill.high { background: var(--accent-tint); }
    .confidence-bar .fill.medium { background: var(--text-secondary); }
    .confidence-bar .fill.low { background: var(--accent-warning); }
    /* The CONTENT role: --font-size-sm off the '.citation, .source' rule above, at the transcript's
       own rhythm rather than the tighter 1.4 this panel used to set itself (870 item 7). Its sibling
       '.excerpt' went with the same edit — no template in this file ever applied that class, so it
       was a fourth type role that could not be seen. */
    .sentence {
      line-height: 1.5;
    }
    .heading-breadcrumb {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .source-availability {
      display: block;
      overflow-wrap: anywhere;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    @media (forced-colors: active) {
      .source-availability { color: CanvasText; }
    }
    .tier-header {
      font-size: var(--font-size-xs);
      /* A group heading — the meta role WITH emphasis (870 item 7), which is the one weight
         distinction this panel makes now. */
      font-weight: 500;
      color: var(--text-secondary);
      padding: 0.5rem 0 0.25rem;
    }
    .doc-group-label {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: ui-monospace, monospace;
      padding: 0.25rem 0 0.15rem;
    }
    jf-control.weak-toggle { display: block; }
    jf-control.weak-toggle::part(control) {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.25rem 0;
    }
    jf-control.weak-toggle::part(control):hover { color: var(--text-primary); }
  `;

  /** Current observation only; never derived from the saved evidence. */
  isUnavailable(docId: string): boolean {
    return this.unavailable.has(docId);
  }

  private onSourceClick(source: AnswerEvidenceSource): void {
    // Tempdoc 822 §5.4 — mark this source the cross-surface selection BEFORE the existing deep-link
    // dispatch, exactly as its sibling `components/SourcesPane.ts:130` already does. That is the line
    // this panel was missing; adding it is what makes the binding two-ended, which is the only thing
    // that justifies the state existing at all. Nothing below changes.
    setSelectedSource(sourceKey(source.parentDocId, source.startLine));
    const detail: CitationSelectDetail = {
      parentDocId: source.parentDocId,
      startLine: source.startLine,
      endLine: source.endLine,
      startChar: source.startChar,
      endChar: source.endChar,
      // Tempdoc 526 §14.5 T2 — excerpt rides through for G21 kind-flip.
      excerpt: source.excerpt ?? '',
    };
    this.dispatchEvent(
      new CustomEvent('citation-select', {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /** 559 Authority IV — the score renders as a LABELED metric, not a bare "%". */
  private renderScore(score: EvidenceScore): TemplateResult {
    return html`<div class="confidence-bar">
        <div class="fill ${tierClass(score.tier)}" style="width:${score.pct}%"></div>
      </div>
      <span class="score ${tierClass(score.tier)}" aria-label="${score.label}: ${score.pct}%">
        ${score.pct}%<span class="score-metric">${score.label}</span>
      </span>`;
  }

  /**
   * Tempdoc 603 C1 — the per-source TRUST badge is GROUNDING (faithfulness), not the BM25 retrieval score:
   * "Grounds N sentences" for a cited source, the honest "Retrieved · not cited" for a retrieved-but-unused
   * one (muted, never "high confidence"). When `grounding` is null (no citation-matches available — the
   * matcher didn't run / keyword-only) the card shows NO trust claim at all (the §22/U2 degraded fallback).
   *
   * <p>Tempdoc 868 §B.3 — "Opened · not cited" over a source the agent read by name rather than
   * retrieved. The badge takes both halves from {@link sourceGroundingLabel} and re-states neither:
   * the panel writing its own provenance word is exactly how one surface comes to disagree with the
   * reading pane about the same document.
   */
  private renderGrounding(g: SourceGrounding, acquisition: Acquisition): TemplateResult {
    // Tempdoc 865 §7.3 — the tier is nullable now, and only a CITED source is guaranteed one, which
    // is the only branch that reads it. The null test narrows in code rather than by an assertion:
    // an assertion would be a claim, and the muted uncited style is the right fallback anyway.
    const cls = g.cited && g.tier !== null ? `grounding ${tierClass(g.tier)}` : 'grounding uncited';
    // Tempdoc 868 §B.3 — the label's opening word comes from the source's acquisition, resolved by
    // the projection. The aria-label and the visible text are the SAME call for the same reason they
    // always were: a screen reader hearing "Retrieved" over a document the agent opened by name is
    // the identical false claim, delivered where nobody will notice it.
    const label = sourceGroundingLabel(g, { acquisition });
    return html`<span class="${cls}" aria-label=${label}>${label}</span>`;
  }

  /**
   * Tempdoc 849 §5 — whether the passage this card names actually reached the model. The badge is
   * rendered from the {@link InclusionBadge} authority and NEVER from a state test here: the panel
   * saying "dropped" in its own words while the reading pane says something else is the drift the
   * one label authority exists to prevent.
   *
   * <p>ABSENCE RENDERS NOTHING. Every conversation persisted before 849 carries no inclusion state,
   * and the correct thing to show for it is empty space — not "included", and not a placeholder
   * saying we do not know, which would put a caveat on every historical source.
   */
  private renderInclusion(badge: InclusionBadge | null): TemplateResult | typeof nothing {
    if (badge === null) return nothing;
    return html`<span class="inclusion ${badge.state}" title=${badge.detail} aria-label=${badge.label}
      >${badge.label}</span
    >`;
  }

  private renderSourceCard(s: AnswerEvidenceSource, grounding?: SourceGrounding | null): TemplateResult {
    // 559 Authority IV — render the citation card as a typed projection of the
    // evidence record, not ad-hoc field reads.
    const item = toEvidenceItem({ ...s, excerpt: displayPassageExcerpt(s.excerpt ?? '') });
    const excerpt = item.excerpt;
    // Tempdoc 565 §29 Tier-3 — the cited file's basename, for the open-file affordance below the card.
    const docName = this.resultBeingExplained
      ? filenameOf(s.parentDocId)
      : s.parentDocId.split('/').pop() ?? s.parentDocId;
    // Tempdoc 822 §5.4 — the SAME two fields `MarkdownBlock.makeMarker` keys its marks on
    // (`parentDocId` + `startLine`, through the one `sourceKey` authority), so the card and the
    // inline `[n]` resolve to one identity and the two surfaces cannot silently disagree.
    const selected = getSelectedSource() === sourceKey(s.parentDocId, s.startLine);
    // `data-selected` is a STYLING hook and nothing more — it is invisible to assistive tech, so a
    // card marked with it alone repeats on the far side the exact "state was visual-only" defect
    // (F6) that this slice fixed on the mark. `aria-current` is what announces it, matching
    // `MarkdownBlock.applyCitationHighlight`. REMOVED when unselected rather than set to "false":
    // some screen readers announce the false value as a present-but-off property, which would be
    // noise on every other card in the list.
    //
    // `data-cite-key` publishes the SAME identity the inline mark carries in its own dataset. The
    // panel renders every retrieved source while marks exist only for the ones a claim referenced,
    // so a positional (`.first`) correspondence between the two surfaces is not one — the harness
    // step that selects a card and then looks for its mark has to match on the key, or it can pick a
    // retrieved-but-uncited card and wait forever for a mark that was never rendered.
    return html`
      <div class="source-card">
        <button
          class="source"
          data-cite-key=${sourceKey(s.parentDocId, s.startLine)}
          ?data-selected=${selected}
          aria-current=${selected ? 'true' : nothing}
          aria-description=${this.observeAvailability && this.unavailable.has(s.parentDocId) ? 'No longer available' : nothing}
          @click=${() => this.onSourceClick(s)}
        >
          ${this.observeAvailability ? html`<span class="source-availability" role="status" aria-live="polite"
            >${this.unavailable.has(s.parentDocId) ? 'No longer available' : nothing}</span>` : nothing}
          ${this.resultBeingExplained
            ? html`<div class="sentence">Result being explained: ${docName}</div>`
            : html`<div class="header">
            ${grounding && !suppressGroundingFor(item.inclusion)
              ? this.renderGrounding(grounding, item.acquisition)
              : nothing}
            ${this.renderInclusion(inclusionBadge(item.inclusion, item))}
            ${item.headingText
              ? html`<span class="heading-breadcrumb">${item.headingText}</span>`
              : nothing}
          </div>
          ${excerpt
            ? html`<div class="sentence">${excerpt}</div>`
            : nothing}
          <div class="preview">
            ${excerpt
              ? html`<div>${excerpt}</div>`
              : html`<div>(no excerpt)</div>`}
            ${item.headingText
              ? html`<div class="detail">Section: ${item.headingText}</div>`
              : nothing}
          </div>`}
        </button>
        <button
          class="source-open"
          data-open-file
          title="Open ${docName}"
          aria-label="Open file ${docName}"
          @click=${() => void openLocalFile(s.parentDocId).catch(() => { /* Already reported. */ })}
        >
          Open ${docName}
        </button>
      </div>
    `;
  }

  override render(): TemplateResult {
    const hasSources = this.sources && this.sources.length > 0;
    const hasCitations = this.citations && this.citations.length > 0;
    if (!hasSources && !hasCitations) return html``;

    if (hasSources) {
      // 603 §22/U2 — without citation-matches (matcher didn't run / keyword-only) there is no faithfulness
      // signal, so render sources NEUTRALLY (flat, no trust grade) rather than grading by BM25 or marking
      // every source "not cited". With matches, group by grounding (renderTieredSources).
      //
      // Tempdoc 865 §7.3 — with ONE exception, which is the whole point of the flag: an empty match
      // list that the producer has EXPLAINED is no longer the silent case. Neutral flat cards say
      // nothing about why there is no grounding, which is fine when nothing is known and wrong when
      // the run reported that its grounding pass did not complete — that is a fact the reader can act
      // on. The `!hasCitations` proxy stays the rule for every run that says nothing.
      // Ask's reported examination gaps are facts even for keyword retrieval. This override is
      // Ask-only: a Delegate's structurally unexaminable source must not change its neutral panel.
      const coverageGroups = this.groupByCoverage && this.sourceCoverage.some((c) => c.windowsScored === 0);
      return !coverageGroups && (this.retrievalMode === 'FULLTEXT_FALLBACK' ||
        (!hasCitations && !this.groundingIncomplete))
        ? this.renderFlatSources()
        : this.renderTieredSources();
    }

    // Fallback: citation-match-only rendering (no retrieval-time sources)
    return html`
      ${this.externalDisclosure
        ? nothing
        : html`<div class="panel-header">
            ${this.citations.length}
            ${this.citations.length === 1 ? 'citation' : 'citations'}
          </div>`}
      ${this.bodyOpen
        ? html`<div class="citations">
            ${this.citations.map(
              (c) => html`
                <div class="citation">
                  <div class="header">${this.renderScore(evidenceScore(c.similarity))}</div>
                  <div class="sentence">${c.sentenceText}</div>
                </div>
              `,
            )}
          </div>`
        : nothing}
    `;
  }

  /**
   * Whether the BODY renders. Only an `externalDisclosure` host can close the two always-open
   * paths — with the panel's own header present, the header IS the toggle and the body follows it,
   * exactly as before.
   */
  private get bodyOpen(): boolean {
    return !this.externalDisclosure || this.sourcesExpanded;
  }

  /**
   * Flat, NEUTRAL source list — no trust grade. Used for FULLTEXT_FALLBACK and (603 §22/U2) when no
   * citation-matches are available (the faithfulness matcher didn't run), so we never assert grounding we
   * don't have nor fall back to the misleading BM25 "confidence". `renderSourceCard(s, null)` shows no badge.
   *
   * <p>Tempdoc 868 §B.3 — the count is ACQUISITION-NEUTRAL. It used to read "N sources retrieved",
   * which was true of every source until the read tool existed and is now a claim about a SET that
   * can mix retrieved hits with opened-by-name documents. A per-source axis cannot be summed into one
   * verb, and the honest summary is the one that says less: the cards below carry the provenance,
   * each for itself. Neutral also keeps this header's promise — "no trust grade" — literally true.
   */
  private renderFlatSources(): TemplateResult {
    return html`
      ${this.externalDisclosure
        ? nothing
        : html`<div class="panel-header">
            ${this.sources.length}
            ${this.sources.length === 1 ? 'source' : 'sources'}
          </div>`}
      ${this.bodyOpen
        ? html`<div class="citations">
            ${this.sources.map((s) => this.renderSourceCard(s, null))}
          </div>`
        : nothing}
    `;
  }

  private renderTieredSources(): TemplateResult {
    // Tempdoc 603 C1 — group sources by their GROUNDING (faithfulness) tier, joined from the answer's
    // per-sentence citation-matches (sourceGrounding), NOT the BM25 retrieval score. So a source that
    // actually grounds the answer ranks high and an uncited one is demoted into the collapsed
    // "not cited" slot (868 §B.3 dropped that slot's acquisition verb — it groups a SET, and the set
    // can mix opened and retrieved) — the panel agrees with the inline citations + the banner
    // (the §1 mis-calibration). The tier still comes from the ONE evidenceTier authority (groundingSemantics).
    // 603 PART X.B — grounding joins by the source's ARRAY POSITION in this.sources (the established
    // convention the inline marks use), NOT a doc-ordinal compare. Compute once per (index, source) and
    // carry the result to the card render so grouping and the badge agree (and we don't re-join twice).
    // Tempdoc 836 S2S3-A.3 — the source's own examination facts join by the same ARRAY POSITION
    // everything else in the citation system indexes by.
    const coverageAt = (i: number) =>
      this.sourceCoverage.find((c) => c.sourceIndex === i) ?? null;
    const gOf = new Map<AnswerEvidenceSource, SourceGrounding>(
      this.sources.map((s, i) => [
        s,
        sourceGrounding(i, this.citations, s.parentDocId, coverageAt(i), this.groundingIncomplete),
      ]),
    );
    const groups: Record<'high' | 'supporting' | 'weak', AnswerEvidenceSource[]> = {
      high: [],
      supporting: [],
      weak: [],
    };
    // Tempdoc 836 S2S3-A.3 — an UNEXAMINED source leaves the uncited group entirely. Filing it
    // under "not cited" would state an evidence verdict about text no scorer read;
    // it is a budget fact, so it gets its own slot and never a tier.
    const unexamined: AnswerEvidenceSource[] = [];
    // Tempdoc 865 §7.3 — and a source no matcher judged leaves it for the same reason one step
    // earlier: "not cited" is a verdict, and there was no pass to deliver one. It carries no tier
    // either (there is no similarity to mint one from), so it must be routed out BEFORE `tierGroup`.
    const groundingIncomplete: AnswerEvidenceSource[] = [];
    for (const s of this.sources) {
      const g = gOf.get(s)!;
      if (g.state === 'grounding-incomplete' || g.tier === null) {
        groundingIncomplete.push(s);
        continue;
      }
      if (g.state === 'unexamined') {
        unexamined.push(s);
        continue;
      }
      groups[tierGroup(g.tier)].push(s);
    }
    const { high, supporting, weak } = groups;

    const groupByDoc = (items: AnswerEvidenceSource[]) => {
      const groups = new Map<string, AnswerEvidenceSource[]>();
      for (const s of items) {
        const key = s.parentDocId;
        const list = groups.get(key) ?? [];
        list.push(s);
        groups.set(key, list);
      }
      return groups;
    };

    const renderGroup = (items: AnswerEvidenceSource[]) => {
      const groups = groupByDoc(items);
      return html`${Array.from(groups.entries()).map(
        ([docId, sources]) => html`
          <div class="doc-group-label">${filenameOf(docId)}</div>
          ${sources.map((s) => this.renderSourceCard(s, gOf.get(s)))}
        `,
      )}`;
    };

    return html`
      ${this.externalDisclosure
        ? nothing
        : html`<button
            class="panel-header"
            aria-expanded=${this.sourcesExpanded ? 'true' : 'false'}
            aria-controls="citations-body"
            @click=${() => (this.sourcesExpanded = !this.sourcesExpanded)}
          >
            <span class="disclosure-chevron ${this.sourcesExpanded ? 'open' : ''}">▸</span>
            ${this.sources.length}
            ${this.sources.length === 1 ? 'source' : 'sources'}
          </button>`}
      ${this.sourcesExpanded
        ? html`<div class="citations" id="citations-body">
        ${high.length > 0
          ? html`
              <div class="tier-header">Grounds the answer</div>
              ${renderGroup(high)}
            `
          : nothing}
        ${supporting.length > 0
          ? html`
              <div class="tier-header">Supporting</div>
              ${renderGroup(supporting)}
            `
          : nothing}
        ${weak.length > 0
          ? html`
              <!-- Tempdoc 868 §B.3 — acquisition-neutral, for the reason renderFlatSources states:
                   this slot is a SET, and it can hold an opened document beside a retrieved one.
                   "not cited" is the fact the whole group actually shares (it is what put them here,
                   via the uncited tier), so dropping the verb costs the reader nothing and stops the
                   group header from asserting a retrieval over documents nothing ranked. The count
                   stays — it is what the toggle is for. -->
              <jf-control
                class="weak-toggle"
                label=${this.showWeak
                  ? 'Hide not cited'
                  : `Show ${weak.length} not cited`}
                .onActivate=${() => (this.showWeak = !this.showWeak)}
              >
                ${this.showWeak
                  ? 'Hide'
                  : `${weak.length} not cited`}
              </jf-control>
              ${this.showWeak ? renderGroup(weak) : nothing}
            `
          : nothing}
        ${unexamined.length > 0
          ? html`
              <div class="tier-header">
                Not examined — the verification budget did not reach
                ${unexamined.length === 1 ? 'this source' : `these ${unexamined.length} sources`}
              </div>
              ${renderGroup(unexamined)}
            `
          : nothing}
        ${groundingIncomplete.length > 0
          ? html`
              <div class="tier-header" data-testid="grounding-incomplete-header">
                Not scored — the grounding check did not complete for
                ${groundingIncomplete.length === 1
                  ? 'this source'
                  : `these ${groundingIncomplete.length} sources`}
              </div>
              ${renderGroup(groundingIncomplete)}
            `
          : nothing}
      </div>`
        : nothing}
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-citations-panel')
) {
  customElements.define('jf-citations-panel', CitationsPanel);
}
