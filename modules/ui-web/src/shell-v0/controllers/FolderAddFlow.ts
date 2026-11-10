// SPDX-License-Identifier: Apache-2.0
/** Library's folder-add flow, shared with Getting started. The server remains the path authority. */
import { html, nothing } from 'lit';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { AVAILABLE, unavailableBecause } from '../state/availability.js';
import { isReservedCollection } from '../state/otherSources.js';
import { icon } from '../components/Icon.js';
import type { JfButton } from '../components/Button.js';
import { z } from 'zod';
import '../components/Button.js';

const previewSchema = z.object({ exists: z.boolean(), isDir: z.boolean(), fileCount: z.number().optional(),
  capped: z.boolean().optional(), alreadyWatched: z.boolean().optional(),
  overlap: z.object({ path: z.string(), relationship: z.enum(['inside', 'containing']) }).nullish() });
export interface FolderPreview {
  readonly path: string; readonly exists: boolean; readonly isDir: boolean; readonly fileCount: number;
  readonly capped: boolean; readonly alreadyWatched: boolean;
  readonly overlap?: { readonly path: string; readonly relationship: 'inside' | 'containing' };
}
export interface FolderAddState {
  pendingPath: string; pendingCollection: string; pendingPreview: FolderPreview | null;
  showManualInput: boolean; addBusy: boolean; error: string | null; actionMessage: string | null;
}
export function emptyFolderAddState(): FolderAddState {
  return { pendingPath: '', pendingCollection: '', pendingPreview: null, showManualInput: false,
    addBusy: false, error: null, actionMessage: null };
}
interface FolderAddHost {
  readonly state: FolderAddState;
  api(): PluginHostApi;
  changed(): void;
  open(): Promise<void>;
  focus(target: 'path' | 'button'): Promise<void>;
  /** A Library surface can reject a picker result from its previous mount. Search retains pending work. */
  current(): () => boolean;
  added(): Promise<void>;
}
function overlapExplanation(preview: FolderPreview): string | null {
  const overlap = preview.overlap;
  if (!overlap) return null;
  return overlap.relationship === 'inside'
    ? `This folder is inside the watched folder “${overlap.path}” and is already covered by it. Choose a separate folder.`
    : `This folder contains the watched folder “${overlap.path}”. Choose a separate folder.`;
}
export class FolderAddFlow {
  private pickerBusy = false;
  private previewTimer: ReturnType<typeof setTimeout> | null = null;
  private previewSeq = 0;
  constructor(private readonly host: FolderAddHost) {}
  async open(): Promise<void> {
    const state = this.host.state;
    if (this.pickerBusy || state.addBusy) return;
    const current = this.host.current();
    state.error = null; this.host.changed();
    this.pickerBusy = true;
    try {
      if (this.host.api().platform.capabilities.has('folder-picker')) {
        const picked = await this.host.api().platform.pickFolder();
        if (!current()) return;
        if (!picked) { await this.host.focus('button'); return; }
        this.setPath(picked);
      }
      state.showManualInput = true;
      this.host.changed();
      await this.host.open();
      if (current()) await this.host.focus('path');
    } catch (err) {
      state.error = `Folder picker could not open. ${err instanceof Error ? err.message : String(err)} Retry Add a folder.`;
      this.host.changed(); await this.host.focus('button');
    } finally { this.pickerBusy = false; }
  }
  async cancel(): Promise<void> {
    if (this.host.state.addBusy) return;
    this.host.state.showManualInput = false; this.host.changed(); await this.host.focus('button');
  }
  setPath(value: string): void {
    const state = this.host.state;
    state.pendingPath = value; state.pendingPreview = null; state.error = null;
    if (this.previewTimer !== null) clearTimeout(this.previewTimer);
    ++this.previewSeq; // invalidate an older response even before the debounce fires
    const path = value.trim();
    if (path) this.previewTimer = setTimeout(() => { this.previewTimer = null; void this.fetchPreview(path); }, 350);
    this.host.changed();
  }
  async fetchPreview(path: string): Promise<void> {
    const seq = ++this.previewSeq;
    try {
      const res = await this.host.api().data.fetch('/api/indexing-roots/preview', { method: 'POST',
        headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path }) });
      if (!res.ok) return;
      const body = previewSchema.parse(await res.json());
      if (seq !== this.previewSeq) return;
      this.host.state.pendingPreview = { path, exists: body.exists, isDir: body.isDir,
        fileCount: body.fileCount ?? -1, capped: body.capped ?? false, alreadyWatched: body.alreadyWatched ?? false,
        ...(body.overlap ? { overlap: body.overlap } : {}) };
      this.host.changed();
    } catch { /* Advisory only. The operation validates again at submission. */ }
  }
  availability() {
    const state = this.host.state;
    if (state.addBusy) return unavailableBecause('Adding folder…', true);
    const path = state.pendingPath.trim();
    if (!path) return unavailableBecause('Enter a folder path', true);
    const collection = state.pendingCollection.trim();
    if (collection && isReservedCollection(collection)) return unavailableBecause(`"${collection}" is reserved for JustSearch's own documents`);
    const p = state.pendingPreview;
    if (!p || p.path !== path) return AVAILABLE;
    if (!p.exists) return unavailableBecause('Path not found');
    if (!p.isDir) return unavailableBecause('Not a folder');
    if (p.alreadyWatched) return unavailableBecause('Already watched');
    const overlap = overlapExplanation(p);
    return overlap ? unavailableBecause(overlap) : AVAILABLE;
  }
  async submit(suggestedPath?: string): Promise<void> {
    const state = this.host.state;
    if (state.addBusy || this.pickerBusy) return;
    if (suggestedPath !== undefined) {
      this.setPath(suggestedPath); state.showManualInput = true;
    }
    const path = state.pendingPath.trim();
    if (!path || this.availability().kind !== 'available') return;
    const current = this.host.current();
    state.addBusy = true; state.error = null; state.actionMessage = 'Adding folder…'; this.host.changed();
    try {
      const collection = state.pendingCollection.trim();
      const result = await this.host.api().data.invokeOperation('core.add-watched-root', collection ? { path, collection } : { path });
      if (!current()) return;
      if (!result.success) throw new Error(result.message || 'Add refused');
      state.pendingPath = ''; state.pendingCollection = ''; state.pendingPreview = null; state.showManualInput = false;
      state.actionMessage = 'Folder added. Indexing activity is shown below.';
      await this.host.added();
      state.addBusy = false; this.host.changed();
      if (current()) await this.host.focus('button');
    } catch (err) {
      if (!current()) return;
      state.actionMessage = null; state.addBusy = false;
      state.error = `Folder was not added. ${err instanceof Error ? err.message : String(err)} Correct the path or retry Add.`;
      this.host.changed(); await this.host.focus('path');
    } finally { if (current()) { state.addBusy = false; this.host.changed(); } }
  }
  renderForm() {
    const state = this.host.state;
    if (!state.showManualInput) return nothing;
    return html`<div class="add-row">
      <input type="text" aria-label="Folder path" ?disabled=${state.addBusy} placeholder="Folder path"
        .value=${state.pendingPath} @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') void this.submit(); }}
        @input=${(e: Event) => this.setPath((e.target as HTMLInputElement).value)} />
      <input class="add-collection" type="text" aria-label="Collection (optional)" ?disabled=${state.addBusy}
        data-testid="library-add-collection" placeholder="Collection (optional)" .value=${state.pendingCollection}
        @keydown=${(e: KeyboardEvent) => { if (e.key === 'Enter') void this.submit(); }}
        @input=${(e: Event) => { state.pendingCollection = (e.target as HTMLInputElement).value; this.host.changed(); }} />
      <jf-button variant="primary" label="Add" .availability=${this.availability()} .onActivate=${() => this.submit()}>Add</jf-button>
      <jf-button label="Cancel" .disabled=${state.addBusy} .onActivate=${() => this.cancel()}>Cancel</jf-button>
    </div>${this.renderPreview()}`;
  }
  private renderPreview() {
    const state = this.host.state; const p = state.pendingPreview;
    if (!state.pendingPath.trim() || !p || p.path !== state.pendingPath.trim()) return nothing;
    const reason = !p.exists ? 'Path not found' : !p.isDir ? 'Not a folder' : p.alreadyWatched ? 'Already watched' : overlapExplanation(p);
    if (reason) return html`<div class="add-preview warn" role="status">${icon({ name: 'alert-circle', size: 14 })} ${reason}</div>`;
    const files = p.fileCount >= 0 ? ` · ${p.fileCount.toLocaleString()}${p.capped ? '+' : ''} ${p.fileCount === 1 && !p.capped ? 'file' : 'files'}` : '';
    return html`<div class="add-preview ok" role="status">${icon({ name: 'check-circle-2', size: 14 })} Folder found${files}</div>`;
  }
  dispose(): void {
    if (this.previewTimer !== null) clearTimeout(this.previewTimer);
    ++this.previewSeq;
  }
}
/** Shared focus helper keeps a control's inner focus implementation authoritative. */
export async function focusFolderAdd(root: ParentNode, target: 'path' | 'button'): Promise<void> {
  if (target === 'path') root.querySelector<HTMLInputElement>('[aria-label="Folder path"]')?.focus();
  else {
    const button = root.querySelector<JfButton>('[data-add-folder]');
    await button?.updateComplete; button?.focus();
  }
}
