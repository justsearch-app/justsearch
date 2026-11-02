// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type TemplateResult } from 'lit';
import { icon } from './Icon.js';
import { failedChipCopy } from '../state/folderStatus.js';
import { unavailableBecause } from '../state/availability.js';
import './Button.js';

/** Additive projection: existing theme fields and intents remain supported. */
export interface FolderRow {
  readonly expanded?: boolean;
  readonly pathHash?: string;
  readonly displayPath?: string;
  readonly fullPath?: string;
  readonly pathTitle?: string;
  readonly status?: string;
  readonly statusText?: string;
  readonly metaText?: string;
  readonly walkError?: string;
  readonly failed?: number;
  readonly failedLastKnown?: boolean;
  readonly removalBusy?: boolean;
  readonly filesFound?: string;
  readonly collection?: string;
  readonly wordsVerdict?: string;
  readonly showProgress?: boolean;
  readonly processingPercent?: number | null;
  readonly indexedAgo?: string;
  readonly verifiedAgo?: string;
  readonly canReveal?: boolean;
  readonly revealUnavailable?: string;
  readonly needsVerification?: boolean;
}

export type FolderRowIntent = 'remove' | 'show-failed' | 'reveal' | 'reindex';
interface RowActions {
  readonly expanded: boolean;
  readonly enabled: boolean;
  toggle(): void;
  invoke(intent: FolderRowIntent): void;
}

export const folderRowStyles = css`
  .cards { display: flex; flex-direction: column; }
  .card { border-block-start: 1px solid var(--border-subtle); min-width: 0; padding: .65rem 0; }
  .card:last-child { border-block-end: 1px solid var(--border-subtle); }
  .folder-summary { display: grid; grid-template-columns: minmax(10rem, 1fr) minmax(8rem, auto) auto;
    gap: 1rem; align-items: center; }
  .folder-toggle { display: flex; align-items: center; gap: .75rem; min-width: 0;
    padding: 0; border: 0; background: transparent; color: var(--text-primary);
    text-align: start; font: inherit; cursor: pointer; }
  .folder-toggle:focus-visible { outline: 2px solid var(--accent-tint); outline-offset: 3px; }
  .card-icon, .chevron { flex: none; color: var(--text-secondary); }
  .chevron { display: inline-flex; }
  .folder-toggle[aria-expanded='true'] .chevron { transform: rotate(90deg); }
  .card-info { min-width: 0; }
  .card-path { font-size: 14px; }
  .folder-name, .folder-path { display: block; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
  .folder-path { font-size: var(--font-size-xs); color: var(--text-secondary); margin-top: .2rem; }
  .folder-state { display: flex; flex-wrap: wrap; align-items: center; gap: .45rem;
    font-size: var(--font-size-xs); max-width: 24rem; color: var(--text-secondary); overflow-wrap: anywhere; }
  .files-found { color: var(--text-secondary); font-size: var(--font-size-xs); text-align: end; }
  .folder-progress { width: 7rem; height: 3px; border: 0; accent-color: var(--accent-tint); }
  .folder-progress::-webkit-progress-bar { background: var(--border-subtle); border-radius: 2px; }
  .folder-progress::-webkit-progress-value { background: var(--accent-tint); border-radius: 2px; }
  .folder-details { margin: .8rem 0 .35rem 3.8rem; display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) auto; gap: .4rem 1rem; }
  .folder-details[hidden] { display: none; }
  .folder-details dl { margin: 0; font-size: var(--font-size-xs); }
  .folder-details dl > div { display: flex; flex-wrap: wrap; gap: .5rem; margin-bottom: .5rem; }
  .folder-details dt { color: var(--text-secondary); }
  .folder-details dd { margin: 0; overflow-wrap: anywhere; }
  .row-actions { display: flex; flex-direction: column; align-items: start; }
  .row-actions jf-button::part(control) { font-size: 14px; }
  .status-icon { display: inline-flex; }
  .status-icon.indexed { color: var(--text-success); }
  .status-icon.error { color: var(--text-danger); }
  .status-icon.unverified { color: var(--text-warning); }
  .remove-action { --jf-button-color: var(--text-danger); }
  @media (max-width: 800px) {
    .folder-summary { grid-template-columns: minmax(0, 1fr) auto; gap: .5rem; }
    .folder-state { grid-column: 1 / -1; padding-inline-start: 3.8rem; grid-row: 2; max-width: none; }
    .folder-details { grid-template-columns: minmax(0, 1fr); }
  }
  @media (forced-colors: active) {
    .card { border-color: CanvasText; }
    .folder-progress { forced-color-adjust: auto; }
    .folder-toggle:focus-visible { outline-color: Highlight; }
  }
`;

/** The themed renderer and the quarantine fallback render this exact template. */
export function renderFolderRow(f: FolderRow, actions: RowActions): TemplateResult {
  const disabled = !actions.enabled || !!f.removalBusy;
  const failed = (f.failed ?? 0) > 0 ? failedChipCopy(f.failed ?? 0, f.failedLastKnown === true) : null;
  const glyph = f.status === 'indexed' ? 'check-circle-2' : f.status === 'error' ? 'alert-circle'
    : f.status === 'unavailable' ? 'x-circle' : f.status === 'unverified' ? 'alert-triangle' : 'clock';
  return html`<div class="card" role="listitem" aria-busy=${f.removalBusy ? 'true' : 'false'}>
    <div class="folder-summary">
      <button type="button" class="folder-toggle" aria-expanded=${String(actions.expanded)}
        aria-controls=${`folder-details-${f.pathHash ?? ''}`} @click=${actions.toggle}>
        <span class="chevron">${icon({ name: 'chevron-right', size: 14 })}</span>
        <span class="card-icon">${icon({ name: 'folder', size: 18 })}</span>
        <span class="card-info card-path"><span class="folder-name" data-testid="library-folder-name"
          title=${f.pathTitle ?? f.fullPath ?? f.displayPath ?? ''}>${f.displayPath ?? 'Folder (path unavailable)'}</span>
          <span class="folder-path" title=${f.fullPath ?? ''}>${f.fullPath ?? 'Path unavailable'}</span></span>
      </button>
      <div class="folder-state"><span class="status-icon ${f.status ?? 'pending'}">${icon({ name: glyph, size: 15 })}</span>
        <span class="card-meta">${f.removalBusy ? 'Removing folder…' : f.statusText ?? f.metaText ?? ''}</span>
        ${f.showProgress && f.processingPercent != null ? html`<progress class="folder-progress" max="100" value=${f.processingPercent}
          aria-label="Meaning processing progress">${f.processingPercent}%</progress>` : nothing}
        ${failed ? html`<jf-button class="failed-chip" variant="ghost" size="sm" label=${failed.label}
          title=${failed.title || nothing} data-last-known=${f.failedLastKnown ? 'true' : 'false'}
          .disabled=${disabled} .onActivate=${() => { if (!disabled) actions.invoke('show-failed'); }}
          >${icon({ name: 'alert-circle', size: 12 })} ${failed.text}</jf-button>` : nothing}
        ${f.needsVerification ? html`<jf-button class="reindex-chip" variant="ghost" size="sm" label="Reindex to verify"
          .disabled=${disabled} .onActivate=${() => { if (!disabled) actions.invoke('reindex'); }}>Reindex to verify</jf-button>` : nothing}
      </div>
      <span class="files-found">${f.filesFound ?? ''}</span>
    </div>
    ${actions.expanded ? html`<div class="folder-details" id=${`folder-details-${f.pathHash ?? ''}`}>
      <dl><div><dt>Collection</dt><dd>${f.collection ?? 'default'}</dd></div>
        <div><dt>Searchable by words</dt><dd>${f.wordsVerdict ?? 'Unconfirmed'}</dd></div>
        <div><dt>Meaning processing progress</dt><dd>${f.processingPercent != null ? `${f.processingPercent}%` : 'Unconfirmed'}</dd></div></dl>
      <dl><div><dt>Indexed</dt><dd>${f.indexedAgo || 'Not yet confirmed'}</dd></div>
        <div><dt>Verified</dt><dd>${f.verifiedAgo || 'Not yet confirmed'}</dd></div>
        <div><dt>Unreadable files</dt><dd>${failed ? html`<jf-button variant="ghost" size="sm" label=${failed.label}
          .disabled=${disabled} .onActivate=${() => { if (!disabled) actions.invoke('show-failed'); }}
          >${failed.text}</jf-button>` : 'None reported'}</dd></div></dl>
      <div class="row-actions">${f.canReveal ? html`<jf-button variant="ghost" label="Open in Explorer"
        title=${f.revealUnavailable || nothing} .disabled=${disabled}
        .availability=${f.revealUnavailable ? unavailableBecause(f.revealUnavailable) : undefined}
        .onActivate=${() => { if (!disabled && !f.revealUnavailable) actions.invoke('reveal'); }}
        >${icon({ name: 'folder', size: 14 })} Open in Explorer</jf-button>` : nothing}
        <jf-button class="remove-action" variant="ghost" label=${f.removalBusy ? 'Removing…' : 'Remove from library'}
          .disabled=${disabled} .onActivate=${() => { if (!disabled) actions.invoke('remove'); }}
          >${icon({ name: 'trash-2', size: 14 })} ${f.removalBusy ? 'Removing…' : 'Remove from library'}</jf-button>
      </div>
    </div>` : nothing}
  </div>`;
}
