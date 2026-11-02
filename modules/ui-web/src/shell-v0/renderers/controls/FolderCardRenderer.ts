// SPDX-License-Identifier: Apache-2.0
import { html, css, type TemplateResult } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './xUiRendererRegistry.js';
import { renderFolderRow, folderRowStyles, type FolderRow, type FolderRowIntent } from '../../components/folderRow.js';
import { failedChipStyles } from '../../components/failedChipPresentation.js';

/** Default Library projection. Operations remain owned by the host surface. */
export class FolderCardRenderer extends JsonFormsRendererBase {
  private expanded = new Set<string>();
  static styles = [folderRowStyles, failedChipStyles, css`
    :host { display: block; min-width: 0; }
    .empty { padding: 1rem; color: var(--text-secondary); font-size: var(--font-size-sm); }
  `];

  override render(): TemplateResult {
    if (!this.visible) return html``;
    const folders = Array.isArray(this.data) ? this.data as FolderRow[] : [];
    if (!folders.length) return html`<div class="empty">No folders indexed yet.</div>`;
    return html`<div class="cards" role="list">${repeat(folders, f => f.pathHash, f => renderFolderRow(f, {
      expanded: f.expanded ?? this.expanded.has(f.pathHash ?? ''), enabled: this.enabled,
      toggle: () => {
        const hash = f.pathHash ?? '';
        if (f.expanded !== undefined) {
          this.dispatchEvent(new CustomEvent('jf-folder-card-toggle', {
            detail: { pathHash: hash }, bubbles: true, composed: true,
          }));
          return;
        }
        if (this.expanded.has(hash)) this.expanded.delete(hash); else this.expanded.add(hash);
        this.requestUpdate();
      },
      invoke: intent => this.emitIntent(intent, f.pathHash ?? ''),
    }))}</div>`;
  }

  private emitIntent(intent: FolderRowIntent, pathHash: string): void {
    if (!pathHash) return;
    this.dispatchEvent(new CustomEvent(`jf-folder-card-${intent}`, {
      detail: { pathHash }, bubbles: true, composed: true,
    }));
  }
}
customElements.define('jf-folder-card', FolderCardRenderer);
registerXUiRenderer('folder-card', 'jf-folder-card');
