// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-sources-pane> — Tempdoc 565 §3.A (the grounded-answer authority's evidence surface).
 *
 * The right-drawer that shows the LATEST agent answer's grounding sources — each a verifiable LOCAL
 * passage. Reads the ONE shared `AgentSessionController` (the same `answerSources` the done event
 * carried) and renders clickable cards (filename · line · excerpt). Clicking dispatches the existing
 * `citation-select` event → `Shell.onCitationSelect` (pushes the passage's line range onto the shared
 * inspectorState `selected`) → UnifiedChatView's own inspectorState subscription, which opens the doc
 * in the reading pane (`<jf-document-pane>`, Search Thread S6 — retired InspectorPane's imperative
 * `highlightCitation` call) at the highlighted line — the differentiator web-grounded tools cannot do. Mirrors
 * the retrospective drawer's mount pattern (slot="right-drawer" + an open-store).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { TransientController } from '../primitives/transientController.js';
import {
  getAgentSessionController,
  subscribeAgentSession,
} from '../state/agentSessionStore.js';
import { isSourcesOpen, setSourcesOpen, subscribeSources } from '../state/sourcesDrawer.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import {
  getSelectedSource,
  setSelectedSource,
  subscribeSelectedSource,
  sourceKey,
} from '../state/selectedSource.js';
// Tempdoc 868 §B.3 — the hide/restore control names the CHANNEL the assistant got this document by,
// and that word is the acquisition axis'. Imported rather than inflected here: "retrieval" over a
// document the agent opened by name is the same false provenance claim the badge stopped making.
import { acquisitionChannel, filenameOf } from './chat/evidenceProjection.js';
import { activateOnKey } from '../utils/keyboardHandler.js';
// Tempdoc 610 §J.3 — the shared hidden-source store + the active conversation id, so the rail offers the
// same hide/restore control as the inline chips and dims consistently.
import {
  getExcludedSources,
  toggleExcludedSource,
  subscribeExcludedSources,
  sourceExcludeKey,
} from '../state/excludedSources.js';
import {
  getConversationListState,
  subscribeConversationList,
} from '../state/conversationListStore.js';
import type { AgentSource } from '../controllers/AgentSessionController.js';
import type { CitationSelectDetail } from './chat/citationTypes.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

export class SourcesPane extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    // Tempdoc 565 §12.3.E — docked mode: the persistent evidence rail (always-visible, inline in the
    // three-zone conversation layout, OUTSIDE the single-drawer arbiter) rather than the toggle drawer.
    docked: { type: Boolean, reflect: true },
    // Tempdoc 814 §D3 — cap the rendered card count (the docked rail is a bounded INDEX, not a
    // scroller). 0 = unbounded, the drawer's behaviour, unchanged.
    maxVisible: { type: Number, attribute: 'max-visible' },
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
  };

  declare open: boolean;
  declare docked: boolean;
  declare maxVisible: number;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;

  private unsubs: Array<() => void> = [];

  /** 574 §23.B — single-open arbitration by construction. Reflects the `sourcesDrawer` store; the controller
   *  closes peer drawers on open and is closed by a peer opening. No outside-click dismiss (Close button / Esc). */
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'sources',
    close: () => setSourcesOpen(false),
  });

  constructor() {
    super();
    this.open = isSourcesOpen();
    this.docked = false;
    this.maxVisible = 0;
    this.apiBase = '';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.unsubs = [
      subscribeAgentSession(() => this.requestUpdate()),
      subscribeSources(() => {
        this.open = isSourcesOpen();
        this.requestUpdate();
      }),
      // Tempdoc 565 §12.3.E — re-render the highlight when the cross-surface selection changes.
      subscribeSelectedSource(() => this.requestUpdate()),
      // Tempdoc 610 §J.3 — re-render dim/toggle state when a source is hidden/restored (either view).
      subscribeExcludedSources(() => this.requestUpdate()),
      subscribeConversationList(() => this.requestUpdate()),
    ];
  }

  /** Tempdoc 610 §J.3 — toggle a retrieved source's hidden state for the active conversation. */
  private async onToggleHidden(s: AgentSource): Promise<void> {
    const sessionId = getConversationListState().activeId;
    if (!sessionId) return;
    const key = sourceExcludeKey(s.parentDocId, s.chunkIndex);
    await toggleExcludedSource(sessionId, key, !getExcludedSources().has(key));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubs.forEach((u) => u());
    this.unsubs = [];
  }

  override updated(changed: Map<string, unknown>): void {
    // 574 §23.B — the open-state (reflected from the store) drives the single-open controller.
    if (changed.has('open')) {
      if (this.open) this.transient.open();
      else this.transient.close();
    }
  }

  private sources(): AgentSource[] {
    return getAgentSessionController(this.apiBase, this.host_).answerSources;
  }

  private onSelect(s: AgentSource): void {
    // Tempdoc 565 §12.3.E — mark this source the cross-surface selection (highlights the matching inline
    // [n] mark + this card) BEFORE the existing deep-link dispatch (open the local passage in the inspector).
    setSelectedSource(sourceKey(s.parentDocId, s.startLine));
    // Tempdoc 778 — capture the USER's click on this chat citation into the ONE ResultDisposition
    // stream (contributor=chat-citation, distinct from the LLM's AGENT_CITATION harvest). Mirrors
    // searchState.ts recordOpenDisposition; fire-and-forget so feedback never disrupts the UI.
    this.recordCitationClick(s.parentDocId);
    const detail: CitationSelectDetail = {
      parentDocId: s.parentDocId,
      startLine: s.startLine,
      endLine: s.endLine,
      startChar: 0,
      endChar: 0,
      excerpt: s.excerpt,
    };
    this.dispatchEvent(
      new CustomEvent<CitationSelectDetail>('citation-select', {
        detail,
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Tempdoc 778 — record a USER citation click as a {@code USER_CITATION_CLICK} disposition (kind
   * OPENED) into the ONE canonical stream via {@code POST /api/knowledge/disposition}. The join key
   * is the active conversation id (the best identifier available at this surface); a click with no
   * joinable feature-snapshot is still captured as a raw signal (the honest limit LabelProjection
   * already surfaces). Fire-and-forget — never disrupts the UI.
   */
  private recordCitationClick(parentDocId: string): void {
    const interactionId = getConversationListState().activeId;
    if (!interactionId || !parentDocId) return;
    void authorizedFetch(`${this.apiBase || ''}/api/knowledge/disposition`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        interactionId,
        docId: parentDocId,
        kind: 'opened',
        contributor: 'chat-citation',
      }),
    }).catch(() => {});
  }

  private onKeydown = (ev: KeyboardEvent): void => {
    if (ev.key === 'Escape') setSourcesOpen(false);
  };

  static styles = css`
    :host(:not([open]):not([docked])) {
      display: none;
    }
    /* Tempdoc 565 §12.3.E — docked rail: fills its three-zone column inline (no floating drawer chrome). */
    :host([docked]) {
      display: block;
      height: 100%;
    }
    .panel {
      position: relative;
      height: 100%;
      width: 24rem;
      max-width: 90vw;
      background: var(--surface-1);
      border-left: 1px solid var(--border-default);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.35);
      color: var(--text-primary);
      display: flex;
      flex-direction: column;
    }
    :host([docked]) .panel {
      width: 100%;
      max-width: none;
      box-shadow: none;
      border-left: none;
      border-radius: 0.5rem;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      /* 814 §D3 — the bounded index clips rather than scrolls if a card runs long. */
      overflow: hidden;
    }
    .head {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
    }
    .title {
      font-weight: 600;
      font-size: var(--font-size-sm);
    }
    /* 574 critical-analysis F2 — the close is a jf-button(sm) atom; it skins itself.
       The old class-only .close skin was deleted (it leaked onto the jf-button host). */
    .scroll {
      flex: 1;
      overflow-y: auto;
      padding: 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    /* Tempdoc 814 §D3 — the DOCKED rail is the bounded index: a capped card list with no scroller of
       its own (the conversation column is the surface's ONE scroll region). The drawer copy above keeps
       its scrolling — same component, one property apart. */
    :host([docked]) .scroll {
      flex: 0 1 auto;
      overflow: visible;
    }
    /* The index's exit to the full list — the sanctioned OverlayHost drawer (the same pane, undocked). */
    .open-all {
      all: unset;
      flex-shrink: 0;
      margin-top: auto;
      box-sizing: border-box;
      cursor: pointer;
      padding: 0.5rem 1rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .open-all:hover,
    .open-all:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    .empty-state {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    .source {
      padding: 0.625rem 0.75rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      cursor: pointer;
    }
    .source:hover,
    .source:focus-visible {
      background: var(--surface-hover);
      border-color: var(--accent-command);
      outline: 2px solid transparent;
    }
    /* Tempdoc 565 §12.3.E — the cross-surface selection: this card is the source the user focused
       (via an inline [n] mark or this card), highlighted in sync with the matching answer citation. */
    .source.selected {
      background: var(--surface-hover);
      border-color: var(--accent-command);
    }
    /* Forced colors (Windows High Contrast) repaint every card edge CanvasText and erase the fill, so
       the selected card's edge is restated as Highlight, a system colour forced colors keep. */
    @media (forced-colors: active) {
      .source.selected {
        border-color: Highlight;
      }
    }
    .source-head {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .source-name {
      font-size: var(--font-size-sm);
      font-weight: 600;
      color: var(--text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* Tempdoc 610 §J.3 — the per-source hide/restore toggle + the hidden treatment. */
    .source-hide {
      all: unset;
      cursor: pointer;
      flex-shrink: 0;
      padding: 0 0.3rem;
      font-size: var(--font-size-xs);
      line-height: 1;
      color: var(--text-muted);
      border-radius: 0.2rem;
    }
    .source-hide:hover,
    .source-hide:focus-visible {
      color: var(--text-primary);
      background: var(--surface-hover);
      outline: 2px solid transparent;
    }
    .source.hidden-source {
      opacity: 0.5;
    }
    .source.hidden-source .source-name {
      text-decoration: line-through;
    }
    .source-loc {
      flex-shrink: 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      font-family: var(--font-mono);
    }
    .source-excerpt {
      margin-top: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
  `;

  override render(): TemplateResult {
    const all = this.sources();
    // Tempdoc 814 §D3 — the bounded index: the docked rail renders the top N and hands the full list
    // to the drawer. `maxVisible <= 0` (the drawer's own state) keeps the complete list.
    const sources = this.maxVisible > 0 ? all.slice(0, this.maxVisible) : all;
    return html`
      <div class="panel" role="region" aria-label="Answer sources" @keydown=${this.onKeydown}>
        <div class="head">
          ${/* 814 §D5 — the head's count is the TOTAL (the authority for the source-count fact), never
                the truncated index length. */ ''}
          <span class="title">Sources${all.length > 0 ? ` · ${all.length}` : ''}</span>
          ${this.docked
            ? nothing
            : html`<jf-button class="close" size="sm" label="Close" .onActivate=${() => setSourcesOpen(false)}>Close</jf-button>`}
        </div>
        ${all.length === 0
          ? html`<div class="empty-state">No grounded sources for the latest answer.</div>`
          : html`<div class="scroll">
              ${sources.map((s) => {
                const selected = getSelectedSource() === sourceKey(s.parentDocId, s.startLine);
                // Tempdoc 603 D-4 — a document-level source (no chunk identity) carries the -1 sentinel
                // for its line: show no precise locator (never "line -1"/"line 0"), and the open hint
                // omits the line. The card still deep-links to the file (opened at top, no false highlight).
                const hasLine = s.startLine >= 0;
                const name = s.title || filenameOf(s.path);
                const isHidden = getExcludedSources().has(
                  sourceExcludeKey(s.parentDocId, s.chunkIndex),
                );
                return html`
                  <div
                    class="source ${selected ? 'selected' : ''} ${isHidden ? 'hidden-source' : ''}"
                    role="button"
                    tabindex="0"
                    aria-current=${selected ? 'true' : 'false'}
                    title=${hasLine ? `Open ${s.path} at line ${s.startLine}` : `Open ${s.path}`}
                    @click=${() => this.onSelect(s)}
                    @keydown=${(e: KeyboardEvent) => activateOnKey(e, () => this.onSelect(s))}
                  >
                    <div class="source-head">
                      <span class="source-name">${name}</span>
                      ${hasLine
                        ? html`<span class="source-loc">line ${s.startLine}</span>`
                        : nothing}
                      <button
                        class="source-hide"
                        aria-label=${isHidden
                          ? `Restore ${name} to the assistant's ${acquisitionChannel(s)}`
                          : `Hide ${name} from the assistant's ${acquisitionChannel(s)}`}
                        title=${isHidden
                          ? `Restore to ${acquisitionChannel(s)}`
                          : `Hide from ${acquisitionChannel(s)}`}
                        @click=${(e: Event) => {
                          e.stopPropagation();
                          void this.onToggleHidden(s);
                        }}
                      >
                        ${isHidden ? '↺' : '×'}
                      </button>
                    </div>
                    ${s.excerpt ? html`<div class="source-excerpt">${s.excerpt}</div>` : nothing}
                  </div>
                `;
              })}
            </div>`}
        ${/* Tempdoc 814 §D3 — the index's one exit: the SAME pane, undocked, in Shell's OverlayHost
              right-drawer (no new overlay mechanics, no second source authority). */ ''}
        ${this.docked && all.length > 0
          ? html`<button
              class="open-all"
              aria-label=${`Open all ${all.length} answer sources`}
              @click=${() => setSourcesOpen(true)}
            >
              Open all · ${all.length}
            </button>`
          : nothing}
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-sources-pane')) {
  customElements.define('jf-sources-pane', SourcesPane);
}
