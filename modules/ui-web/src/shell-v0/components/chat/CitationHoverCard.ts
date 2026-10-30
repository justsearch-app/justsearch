// SPDX-License-Identifier: Apache-2.0
/**
 * CitationHoverCard — floating preview card for citation superscripts (tempdoc 508).
 *
 * Appears on hover over a citation superscript [n] in StreamingTextBlock.
 * Shows excerpt text, document name, and grounding score.
 * Positioned relative to the trigger element via absolute positioning.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { displayPassageExcerpt } from './passageExcerptPresentation.js';
// Tempdoc 565 §15.A — the ONE grounding-tier authority (was a forked `scoreLabel` here).
import { groundingLabel } from './evidenceProjection.js';

export interface CitationHoverData {
  excerpt: string;
  parentDocId: string;
  score: number;
  headingText?: string;
}

export class CitationHoverCard extends JfElement {
  static properties = {
    data: { attribute: false },
    visible: { type: Boolean, reflect: true },
    x: { type: Number },
    y: { type: Number },
  };

  declare data: CitationHoverData | null;
  declare visible: boolean;
  declare x: number;
  declare y: number;

  constructor() {
    super();
    this.data = null;
    this.visible = false;
    this.x = 0;
    this.y = 0;
  }

  static styles = css`
    :host {
      position: fixed;
      z-index: var(--z-modal);
      pointer-events: none;
      opacity: 0;
      transition: opacity var(--duration-fast) var(--ease-standard);
    }
    :host([visible]) {
      opacity: 1;
      pointer-events: auto;
    }
    .card {
      max-width: 320px;
      padding: 0.5rem 0.75rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      line-height: 1.4;
    }
    .doc-name {
      font-size: var(--font-size-xs);
      color: var(--text-tint);
      margin-bottom: 0.25rem;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }
    .excerpt {
      color: var(--text-secondary);
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
    }
    .score {
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      margin-top: 0.25rem;
    }
  `;

  override render(): TemplateResult | typeof nothing {
    if (!this.data) return nothing;
    const docName = this.data.parentDocId.split('/').pop() ?? this.data.parentDocId;
    const scoreLabel = groundingLabel(this.data.score);
    return html`
      <div class="card" style="transform: translate(${this.x}px, ${this.y}px)">
        <div class="doc-name">${this.data.headingText || docName}</div>
        ${this.data.excerpt
          ? html`<div class="excerpt">${displayPassageExcerpt(this.data.excerpt)}</div>`
          : nothing}
        <div class="score">${scoreLabel} match · ${Math.round(this.data.score * 100)}%</div>
      </div>
    `;
  }

  show(data: CitationHoverData, rect: DOMRect): void {
    this.data = data;
    this.x = rect.left;
    this.y = rect.bottom + 4;
    this.visible = true;
  }

  hide(): void {
    this.visible = false;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-citation-hover-card')) {
  customElements.define('jf-citation-hover-card', CitationHoverCard);
}
