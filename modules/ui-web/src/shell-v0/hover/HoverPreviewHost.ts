// SPDX-License-Identifier: Apache-2.0
/**
 * HoverPreviewHost — Tempdoc 543 §12.3 #5 (Slice 8).
 *
 * Kernel-rendered popover host for the (aggregate, hover-preview)
 * substrate. "Plugins request, kernel renders" — strategies
 * contribute the popover body via the aggregate-substrate; this
 * host owns the popover lifecycle: trigger detection, debounce,
 * positioning, dismissal, focus restoration. Plugins never paint
 * hover chrome themselves.
 *
 * Activation contract: any element annotated with
 *   data-hover-aggregate-kind="Operation"
 *   data-hover-aggregate-id="<operation-id>"
 * triggers a hover preview. The host fetches the Operation from the
 * Operation catalog, calls `renderAggregateMulti('Operation',
 * 'hover-preview', op, {triggerEl}, host)` (Slice 6's multi dispatch
 * with 'merge' policy), and stacks the resulting bodies into a
 * positioned popover.
 *
 * Mount: one instance attaches to document at chrome boot. This
 * satisfies surfaceContextKinds.ts header's "must have one production
 * surface mounting" discipline for the new `'hover-preview'`
 * SurfaceContextKind.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import {
  renderAggregateMulti,
  type StrategyHost,
} from '../aggregate-substrate/aggregateRegistry.js';
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import type { Operation } from '../../api/types/registry.js';

const HOVER_DELAY_MS = 350;
const HIDE_DELAY_MS = 150;

interface ActiveHover {
  triggerEl: HTMLElement;
  aggregateKind: 'Operation';
  aggregateId: string;
  data: Operation;
  rect: DOMRect;
}

export class HoverPreviewHost extends JfElement {
  static properties = {
    active: { state: true },
  };

  declare active: ActiveHover | null;

  private showTimer: number | null = null;
  private hideTimer: number | null = null;
  private mouseEnterListener: ((e: MouseEvent) => void) | null = null;
  private mouseLeaveListener: ((e: MouseEvent) => void) | null = null;
  private scrollListener: (() => void) | null = null;

  constructor() {
    super();
    this.active = null;
  }

  static styles = css`
    :host {
      position: fixed;
      pointer-events: none;
      inset: 0;
      z-index: var(--z-overlay-modal);
    }
    .popover {
      position: absolute;
      pointer-events: auto;
      background: var(--surface-2);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 8px;
      padding: 0.5rem 0.625rem;
      font-size: var(--font-size-sm);
      box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
      max-width: 24rem;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    // Guard against double-registration on rapid mount/unmount cycles
    // (StrictMode / Lit re-renders) per worktree reviewer follow-up B1.
    if (this.mouseEnterListener) return;
    this.mouseEnterListener = (e: MouseEvent) => this.handleMouseEnter(e);
    this.mouseLeaveListener = (e: MouseEvent) => this.handleMouseLeave(e);
    this.scrollListener = () => this.dismiss();
    document.addEventListener('mouseenter', this.mouseEnterListener, true);
    document.addEventListener('mouseleave', this.mouseLeaveListener, true);
    window.addEventListener('scroll', this.scrollListener, true);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.mouseEnterListener) {
      document.removeEventListener(
        'mouseenter',
        this.mouseEnterListener,
        true,
      );
      this.mouseEnterListener = null;
    }
    if (this.mouseLeaveListener) {
      document.removeEventListener(
        'mouseleave',
        this.mouseLeaveListener,
        true,
      );
      this.mouseLeaveListener = null;
    }
    if (this.scrollListener) {
      window.removeEventListener('scroll', this.scrollListener, true);
      this.scrollListener = null;
    }
    this.clearTimers();
  }

  private clearTimers(): void {
    if (this.showTimer !== null) {
      window.clearTimeout(this.showTimer);
      this.showTimer = null;
    }
    if (this.hideTimer !== null) {
      window.clearTimeout(this.hideTimer);
      this.hideTimer = null;
    }
  }

  private handleMouseEnter(e: MouseEvent): void {
    const target = e.target;
    if (!(target instanceof HTMLElement)) return;
    const el = target.closest<HTMLElement>('[data-hover-aggregate-kind]');
    if (!el) return;
    const kind = el.getAttribute('data-hover-aggregate-kind');
    const id = el.getAttribute('data-hover-aggregate-id');
    if (kind !== 'Operation' || !id) return;
    this.clearTimers();
    this.showTimer = window.setTimeout(() => {
      this.activate(el, id);
    }, HOVER_DELAY_MS);
  }

  private handleMouseLeave(e: MouseEvent): void {
    const target = e.target;
    if (!(target instanceof HTMLElement)) return;
    const el = target.closest<HTMLElement>('[data-hover-aggregate-kind]');
    if (!el) return;
    if (this.showTimer !== null) {
      window.clearTimeout(this.showTimer);
      this.showTimer = null;
    }
    if (this.active && this.active.triggerEl === el) {
      this.hideTimer = window.setTimeout(
        () => this.dismiss(),
        HIDE_DELAY_MS,
      );
    }
  }

  private activate(triggerEl: HTMLElement, aggregateId: string): void {
    const op = getOperation(aggregateId);
    if (!op) return;
    this.active = {
      triggerEl,
      aggregateKind: 'Operation',
      aggregateId,
      data: op,
      rect: triggerEl.getBoundingClientRect(),
    };
  }

  private dismiss(): void {
    this.active = null;
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.active) return nothing;
    const a = this.active;
    const top = a.rect.bottom + 6;
    const left = Math.max(
      8,
      Math.min(window.innerWidth - 392, a.rect.left),
    );
    // Tempdoc 543 §13.3.2 — hover-preview uses the 'merge' policy
    // (flipped at bootstrap). All contributors stack in rank-desc
    // order; the popover composes them as sections.
    const host: StrategyHost = { apiBase: '' };
    const bodies = renderAggregateMulti(
      'Operation',
      'hover-preview',
      a.data,
      { triggerEl: a.triggerEl },
      host,
    );
    if (bodies.length === 0) return nothing;
    return html`
      <div
        class="popover"
        data-testid="hover-preview-popover"
        data-aggregate-kind=${a.aggregateKind}
        data-aggregate-id=${a.aggregateId}
        data-section-count=${bodies.length}
        .style=${`top: ${top}px; left: ${left}px`}
        @mouseenter=${() => {
          if (this.hideTimer !== null) {
            window.clearTimeout(this.hideTimer);
            this.hideTimer = null;
          }
        }}
        @mouseleave=${() => this.dismiss()}
      >
        ${bodies.map((b) => html`${b}`)}
      </div>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-hover-preview-host')
) {
  customElements.define('jf-hover-preview-host', HoverPreviewHost);
}
