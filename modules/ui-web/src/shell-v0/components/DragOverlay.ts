// SPDX-License-Identifier: Apache-2.0
/**
 * DragOverlay — Lit visual overlay for drag-drop file indexing
 * (slice 459).
 *
 * Pure presentational. Pair with `dragDetect.ts` (utils) which
 * provides the global window-level drag/drop event handling.
 *
 * Side-effect registers `<jf-drag-overlay>`.
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { icon } from './Icon.js';

export class DragOverlay extends JfElement {
  static properties = {
    active: { type: Boolean, reflect: true },
    dragKind: { type: String, attribute: 'drag-kind' },
  };

  declare active: boolean;
  declare dragKind: 'folder' | 'file' | 'unknown' | null;

  constructor() {
    super();
    this.active = false;
    this.dragKind = null;
  }

  static styles = css`
    :host {
      display: contents;
    }
    .overlay {
      /* 559 Authority I: absolute within the OverlayHost center slot (fixed;inset:0). */
      position: absolute;
      inset: 0;
      pointer-events: none;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(0, 0, 0, 0.45);
      /* Tempdoc 567 §9.4 — respect solid surface mode (--glass-blur-scale:0 zeroes the blur). */
      backdrop-filter: blur(calc(8px * var(--glass-blur-scale)));
      opacity: 0;
      transition: opacity var(--duration-fast) var(--ease-standard);
    }
    :host([active]) .overlay {
      opacity: 1;
    }
    .panel {
      text-align: center;
      color: var(--text-primary);
    }
    .icon-ring {
      width: 5rem;
      height: 5rem;
      margin: 0 auto 1rem auto;
      border-radius: 14px;
      border: 2px dashed var(--text-secondary);
      display: flex;
      align-items: center;
      justify-content: center;
      color: var(--text-secondary);
    }
    h3 {
      margin: 0 0 0.25rem 0;
      font-size: var(--font-size-lg);
      font-variation-settings: "opsz" 32;
      font-weight: 600;
    }
    .sub {
      margin: 0;
      font-size: var(--font-size-sm);
      color: var(--text-secondary);
    }
  `;

  override render(): TemplateResult | typeof nothing {
    if (!this.active) return nothing;
    const headline =
      this.dragKind === 'folder'
        ? 'Drop folder to index'
        : this.dragKind === 'file'
          ? 'Drop files here'
          : 'Drop to add to index';
    return html`
      <div class="overlay">
        <div class="panel">
          <div class="icon-ring">${icon({ name: 'folder-plus', size: 36 })}</div>
          <h3>${headline}</h3>
          <p class="sub">Files will be added to your search index</p>
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-drag-overlay')) {
  customElements.define('jf-drag-overlay', DragOverlay);
}
