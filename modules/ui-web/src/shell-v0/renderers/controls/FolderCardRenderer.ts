// SPDX-License-Identifier: Apache-2.0
/**
 * FolderCardRenderer — first-party `x-ui-renderer` hint renderer (569 §14 / the Library rollout).
 *
 * Renders the Library's indexed-folder cards (the 2nd real surface inverted after Settings) at the
 * bespoke quality LibrarySurface hand-authored — icon · status · path · meta · Remove — through the
 * projection engine, so the real Library is a projection of a declaration with no visual downgrade.
 *
 * CONTENT is projected from the pre-resolved card data (the surface owns async path resolution +
 * formatting); the one INTERACTION (Remove) is emitted as a named INTENT event `jf-folder-card-remove`
 * the surface handles (confirm dialog + the gated `core.remove-watched-root` operation) — the renderer
 * stays a pure projection, the operation+confirm stay the surface's (the §7 boundary).
 *
 * Schema fragment (hint on the array property):
 *   folders: { type:'array', 'x-ui-renderer':'folder-card',
 *     items:{ properties:{ pathHash, displayPath, status, metaText, walkError } } }
 *
 * Side-effect registers `'folder-card'` → `'jf-folder-card'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';
import { icon } from '../../components/Icon.js';
import '../../components/Button.js';
// Tempdoc 914 D3 — the chip's copy comes from the folder-status seam, so this renderer and
// LibrarySurface's hand-authored card cannot word the last-known qualifier differently: a carried
// count is marked visibly AND in the accessible name (see `failedChipCopy`).
import { failedChipCopy } from '../../state/folderStatus.js';
// Tempdoc 914 D3 — the chip's look, shared with LibrarySurface's hand-authored card.
import { failedChipStyles } from '../../components/failedChipPresentation.js';

interface FolderCard {
  readonly pathHash?: string;
  readonly displayPath?: string;
  readonly status?: string;
  readonly metaText?: string;
  readonly walkError?: string;
  /** Tempdoc 599 §16/B1 — failed-job count; >0 renders a clickable chip that opens the drill-down. */
  readonly failed?: number;
  /** Tempdoc 914 D3 — `failed` is a count carried across a window in which this root has no settled
   *  answer, not this poll's own number. The chip marks it visibly AND in the accessible name
   *  (`failedChipCopy`), which is what review finding S2-2 required. */
  readonly failedLastKnown?: boolean;
  readonly removalBusy?: boolean;
}

export class FolderCardRenderer extends JsonFormsRendererBase {
  static styles = [
    failedChipStyles,
    css`
    :host {
      display: block;
    }
    .cards {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .card {
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: var(--surface-2);
    }
    .card-icon {
      flex: none;
      color: var(--text-secondary);
    }
    .card-info {
      flex: 1;
      min-inline-size: 0;
    }
    .card-path {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
    }
    .card-path span {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .card-meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .status-icon.indexed {
      color: var(--text-tint);
    }
    .status-icon.error {
      color: var(--text-danger);
    }
    .status-icon.pending {
      color: var(--text-secondary);
    }
    .status-icon.unavailable {
      color: var(--text-secondary);
    }
    .walk-error {
      color: var(--text-danger);
    }
    .empty {
      padding: 1rem;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
  `,
  ];

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const folders = Array.isArray(this.data) ? (this.data as FolderCard[]) : [];
    if (folders.length === 0) {
      return html`<div class="empty">No folders indexed yet.</div>`;
    }
    return html`<div class="cards" role="list">
      ${folders.map((f) => this.renderCard(f))}
    </div>`;
  }

  private statusIcon(status: string | undefined): TemplateResult {
    // Tempdoc 599 §16/A1 — 'unavailable' (folder path gone) is a muted x-circle, not the red error glyph.
    // Tempdoc 626 §Axis-C — 'unverified' (indexed but deletions couldn't be verified) is a muted
    // caution glyph, NOT the green ✓: the folder is searchable but its delete correspondence is unknown.
    const cls =
      status === 'indexed'
        ? 'indexed'
        : status === 'error'
          ? 'error'
          : status === 'unavailable'
            ? 'unavailable'
            : status === 'unverified'
              ? 'unverified'
              : 'pending';
    const name =
      status === 'indexed'
        ? 'check-circle-2'
        : status === 'error'
          ? 'alert-circle'
          : status === 'unavailable'
            ? 'x-circle'
            : status === 'unverified'
              ? 'alert-triangle'
              : 'clock';
    return html`<span class="status-icon ${cls}">${icon({ name, size: 16 })}</span>`;
  }

  private renderCard(f: FolderCard): TemplateResult {
    return html`<div class="card" role="listitem" aria-busy=${f.removalBusy ? 'true' : 'false'}>
      <span class="card-icon">${icon({ name: 'folder', size: 24 })}</span>
      <div class="card-info">
        <div class="card-path">
          ${this.statusIcon(f.status)}<span title=${f.displayPath ?? ''}
            >${f.displayPath ?? '(resolving…)'}</span
          >
        </div>
        <div class="card-meta">
          ${f.metaText ?? ''}${f.walkError
            ? html` · <span class="walk-error">${f.walkError}</span>`
            : nothing}${(f.failed ?? 0) > 0
            ? this.renderFailedChip(f)
            : nothing}
        </div>
      </div>
      <jf-button
        variant="danger"
        label=${f.removalBusy ? 'Removing…' : 'Remove'}
        .disabled=${!this.enabled || !!f.removalBusy}
        .onActivate=${() => { if (!f.removalBusy) this.emitRemove(f.pathHash ?? ''); }}
      >
        ${icon({ name: 'trash-2', size: 14 })} ${f.removalBusy ? 'Removing…' : 'Remove'}
      </jf-button>
    </div>`;
  }

  /**
   * Tempdoc 599 §16/B1 + 914 D3 (review S2-2) — the clickable "N failed" chip. A carried count is
   * marked VISIBLY (shared `failedChipCopy` text + the muted-italic `[data-last-known]` rule); the
   * `label` starts with that same visible text, which is what WCAG 2.5.3 requires.
   */
  private renderFailedChip(f: FolderCard): TemplateResult {
    const lastKnown = f.failedLastKnown === true;
    const copy = failedChipCopy(f.failed ?? 0, lastKnown);
    return html`<jf-button
      class="failed-chip"
      data-last-known=${lastKnown ? 'true' : 'false'}
      variant="ghost"
      size="sm"
      label=${copy.label}
      title=${copy.title || nothing}
      .disabled=${!!f.removalBusy}
      .onActivate=${() => { if (!f.removalBusy) this.emitShowFailed(f.pathHash ?? ''); }}
      >${icon({ name: 'alert-circle', size: 12 })} ${copy.text}</jf-button
    >`;
  }

  /**
   * Emit the Remove INTENT — the surface owns the confirm dialog + the gated remove operation
   * (the renderer is a pure projection; the privileged action stays team/surface-owned, §7).
   */
  private emitRemove(pathHash: string): void {
    if (!pathHash) return;
    this.dispatchEvent(
      new CustomEvent<{ pathHash: string }>('jf-folder-card-remove', {
        detail: { pathHash },
        bubbles: true,
        composed: true,
      }),
    );
  }

  /**
   * Emit the show-failed INTENT (tempdoc 599 §16/B1) — the surface opens the per-folder failed-files
   * drawer; the renderer stays a pure projection (mirrors {@link #emitRemove}).
   */
  private emitShowFailed(pathHash: string): void {
    if (!pathHash) return;
    this.dispatchEvent(
      new CustomEvent<{ pathHash: string }>('jf-folder-card-show-failed', {
        detail: { pathHash },
        bubbles: true,
        composed: true,
      }),
    );
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-folder-card')) {
  customElements.define('jf-folder-card', FolderCardRenderer);
}

registerXUiRenderer('folder-card', 'jf-folder-card');
