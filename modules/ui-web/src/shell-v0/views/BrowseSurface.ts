// SPDX-License-Identifier: Apache-2.0
/**
 * BrowseSurface — Lit-side Browse rail surface (slice 455 phase 9).
 *
 * Self-mounting Surface: tree view of watched roots → folders → files,
 * lazy-fetched via POST /api/knowledge/folders + POST
 * /api/knowledge/folder-files. Search filter, expand/collapse, refresh.
 *
 * Visual deltas vs React BrowseView (accepted):
 *  - No virtualized list (React uses `virtua`); plain recursive render
 *    works up to ~5000 rows. Larger trees would benefit from a Lit
 *    virtualization helper — phase 10 follow-up.
 *  - No keyboard navigation (Tab/Arrow/Enter/Space) in V1.
 *  - No context menu — file row click triggers default-action
 *    (open or reveal via Tauri); hover reveals an inline action button.
 *  - No selection multi-row + Inspector wiring.
 *  - No collapsing-folder animation.
 *
 * Side-effect registers `<jf-browse-surface>` for the chrome dispatcher.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import { unavailableBecause } from '../state/availability.js';
import '../components/Button.js';
import '../components/ErrorAlert.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import { repeat } from 'lit/directives/repeat.js';
import { icon } from '../components/Icon.js';
import { openContextMenu, type ContextMenuAction } from '../components/ContextMenu.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { openLocalFile } from '../plugin-api/capabilities/platform.js';
import { compose } from '../utils/compose.js';

interface WatchedRoot {
  path: string;
  collection?: string;
  fileCount?: number;
  lastIndexed?: string;
}

interface BrowseFolder {
  path: string;
  name: string;
  childFolderCount?: number;
  childFileCount?: number;
}

interface BrowseFile {
  path: string;
  name: string;
  size?: number;
  fileKind?: string;
  modifiedAt?: number;
}

// Internal Node type was elided when we moved to direct render paths;
// kept its constituent shapes inline at usage sites.

const ROOT_KEY = '__BROWSE_ROOTS__';

function lastSeg(p: string): string {
  const trimmed = p.replace(/[\\/]+$/, '');
  const last = trimmed.split(/[\\/]/).pop();
  return last || p;
}

function fileIconName(kind: string | undefined): 'folder' | 'hard-drive' {
  void kind;
  return 'hard-drive';
}

export class BrowseSurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    host_: { attribute: false },
    roots: { state: true },
    folderChildren: { state: true },
    fileChildren: { state: true },
    expanded: { state: true },
    inflight: { state: true },
    filter: { state: true },
    refreshing: { state: true },
    error: { state: true },
    provisional: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: WatchedRoot[];
  declare folderChildren: Record<string, BrowseFolder[]>;
  declare fileChildren: Record<string, BrowseFile[]>;
  declare expanded: Record<string, boolean>;
  declare inflight: Record<string, boolean>;
  declare filter: string;
  declare refreshing: boolean;
  declare error: string | null;
  /** 595 §4.3 — backend mid-transition; an empty roots list is "unknown", not "none". */
  declare provisional: boolean;

  private aiUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.host_ = undefined as unknown as PluginHostApi;
    this.roots = [];
    this.folderChildren = {};
    this.fileChildren = {};
    this.expanded = {};
    this.inflight = {};
    this.filter = '';
    this.refreshing = false;
    this.error = null;
    this.provisional = false;
  }

  static styles = [
    surfaceLayoutStyles,
    css`
    .header {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.75rem;
      padding: 0.75rem 1.5rem;
    }
    .header h2 {
      margin: 0;
      font-size: var(--font-size-md);
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 0.4rem;
    }
    .search-bar {
      flex-shrink: 0;
      padding: 0 1.5rem 0.625rem 1.5rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .filter-wrap {
      position: relative;
      width: 100%;
      display: flex;
      align-items: center;
    }
    .filter-input {
      flex: 1;
      width: 100%;
      padding: 0.4rem 0.625rem 0.4rem 2rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    .filter-wrap .search-icon {
      position: absolute;
      left: 0.6rem;
      color: var(--text-secondary);
      pointer-events: none;
    }
    .filter-helper {
      margin-top: 0.375rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .row .count-badge {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-left: auto;
      padding-right: 0.25rem;
    }
    /* 574 B1 — the two icon action buttons are jf-button(ghost,icon) atoms now;
       the base button{}/.icon-btn fork is deleted. */
    /* Fills the SurfaceLayout \`.body\` region — flex + overflow come from
       surfaceLayoutStyles; keep only the bespoke edge-to-edge inset so the
       full-width tree rows own their own horizontal padding. */
    .body {
      padding: 0.5rem 0;
    }
    .row {
      display: flex;
      align-items: center;
      gap: 0.375rem;
      padding: 0.25rem 1.5rem;
      cursor: pointer;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      user-select: none;
    }
    .row:hover {
      background: var(--surface-hover);
    }
    .row .toggle {
      width: 1rem;
      flex-shrink: 0;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: var(--text-secondary);
    }
    .row .name {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .row .meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-left: 0.5rem;
      flex-shrink: 0;
    }
    .row.file {
      color: var(--text-secondary);
    }
    .row .actions {
      display: none;
      gap: 0.25rem;
      margin-left: auto;
    }
    .row:hover .actions {
      display: inline-flex;
    }
    .row .icon {
      flex-shrink: 0;
      color: var(--text-tint);
    }
    .row.file .icon {
      color: var(--text-secondary);
    }
    .empty-state {
      padding: 2rem 1.5rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .spin {
      animation: spin 1s linear infinite;
      color: var(--text-secondary);
    }
  `,
  ];

  override connectedCallback(): void {
    super.connectedCallback();
    void this.loadRoots();
    // 595 §4.3 — observe the one Stability axis so an empty roots list during a
    // transition renders as "Rebuilding…", not "No watched folders".
    this.aiUnsub = subscribeAiState((s) => {
      this.provisional = s.stability.kind === 'provisional';
    });
  }

  /** Tempdoc 609 — settle transient state on hide (per-folder in-flight loads, refresh flag, fetch
   *  error) so a return doesn't show a stale spinner or locked folder. Expanded tree, filter, and
   *  cached children are recoverable and untouched. */
  protected override settleTransients(): void {
    this.inflight = {};
    this.refreshing = false;
    this.error = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.aiUnsub?.();
    this.aiUnsub = null;
  }

  private doFetch(path: string, init?: RequestInit): Promise<Response> {
    return this.host_.data.fetch(path, {
      method: init?.method,
      headers: init?.headers as Record<string, string> | undefined,
      body: init?.body as string | undefined,
    });
  }

  private async loadRoots(): Promise<void> {
    this.refreshing = true;
    this.error = null;
    try {
      const res = await this.doFetch('/api/indexing/roots?counts=true');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = (await res.json()) as { roots?: WatchedRoot[] };
      this.roots = data.roots ?? [];
      this.expanded = { ...this.expanded, [ROOT_KEY]: true };
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      this.refreshing = false;
    }
  }

  private async loadFolder(path: string): Promise<void> {
    if (this.folderChildren[path] !== undefined && this.fileChildren[path] !== undefined) {
      return;
    }
    if (this.inflight[path]) return;
    this.inflight = { ...this.inflight, [path]: true };
    try {
      const [foldersRes, filesRes] = await Promise.all([
        this.doFetch('/api/knowledge/folders', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ parentPath: path, maxFolders: 500 }),
        }),
        this.doFetch('/api/knowledge/folder-files', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            folderPath: path,
            limit: 1000,
            projection: ['path', 'filename', 'file_kind', 'size_bytes', 'modified_at'],
          }),
        }),
      ]);
      if (foldersRes.ok) {
        const fdata = (await foldersRes.json()) as { folders?: BrowseFolder[] };
        this.folderChildren = {
          ...this.folderChildren,
          [path]: (fdata.folders ?? []).map((f) => ({ ...f, name: lastSeg(f.path) })),
        };
      }
      if (filesRes.ok) {
        const fdata = (await filesRes.json()) as { files?: Array<{ docId: string; fields: Record<string, string> }> };
        const files: BrowseFile[] = (fdata.files ?? []).map((row) => ({
          path: row.fields.path ?? row.docId,
          name: row.fields.filename ?? lastSeg(row.fields.path ?? row.docId),
          fileKind: row.fields.file_kind,
          size: row.fields.size_bytes ? Number(row.fields.size_bytes) : undefined,
          modifiedAt: row.fields.modified_at ? Number(row.fields.modified_at) : undefined,
        }));
        this.fileChildren = { ...this.fileChildren, [path]: files };
      }
    } catch (err) {
      this.error = err instanceof Error ? err.message : String(err);
    } finally {
      const next = { ...this.inflight };
      delete next[path];
      this.inflight = next;
    }
  }

  private async toggle(path: string): Promise<void> {
    const isOpen = this.expanded[path] === true;
    if (isOpen) {
      this.expanded = { ...this.expanded, [path]: false };
      return;
    }
    this.expanded = { ...this.expanded, [path]: true };
    await this.loadFolder(path);
  }

  private async openFile(path: string): Promise<void> {
    try {
      await openLocalFile(path);
    } catch {
      // The platform helper has shown the refusal and Show in folder action.
    }
  }

  private matchesFilter(name: string): boolean {
    if (!this.filter.trim()) return true;
    return name.toLowerCase().includes(this.filter.toLowerCase());
  }

  private renderFolderRow(
    path: string,
    name: string,
    level: number,
    fileCountHint?: number,
  ): TemplateResult {
    const isOpen = this.expanded[path] === true;
    const loading = this.inflight[path] === true;
    // Prefer cached fileChildren count once loaded, otherwise the hint provided
    // by the parent (root-list or folder-list response). Negative or undefined
    // hides the badge.
    const cached = this.fileChildren[path]?.length;
    const count = cached ?? fileCountHint;
    return html`
      <div
        class="row"
        style="padding-left: ${1.5 + level * 1}rem"
        @click=${() => void this.toggle(path)}
        role="treeitem"
        aria-expanded=${String(isOpen)}
      >
        <span class="toggle"
          >${loading
            ? html`<span class="spin">${icon({ name: 'refresh-cw', size: 12 })}</span>`
            : isOpen
              ? html`${icon({ name: 'chevron-down', size: 12 })}`
              : '▸'}</span
        >
        <span class="icon">${icon({ name: 'folder', size: 14 })}</span>
        <span class="name">${name}</span>
        ${count !== undefined && count > 0
          ? html`<span class="count-badge"
              >${icon({ name: 'file-text', size: 10 })}
              ${count.toLocaleString()}</span
            >`
          : nothing}
      </div>
    `;
  }

  private async handleFileContextMenu(e: MouseEvent, file: BrowseFile): Promise<void> {
    e.preventDefault();
    const actions: ContextMenuAction[] = [
      {
        id: 'open',
        label: 'Open',
        icon: 'check-circle-2',
        shortcut: 'Enter',
        category: 'file',
        enabled: true,
      },
      {
        id: 'reveal',
        label: 'Reveal in folder',
        icon: 'folder',
        category: 'file',
        enabled: this.host_.platform.capabilities.has('reveal-in-explorer'),
      },
      {
        id: 'copy-path',
        label: 'Copy path',
        category: 'file',
        enabled: true,
      },
      // Slice 496 §3.A: context-emitting actions that navigate to chat
      // surfaces with the file's docId pre-filled via StateSnapshot.
      {
        id: 'summarize',
        label: 'Summarize',
        icon: 'file-text',
        category: 'ai',
        enabled: true,
      },
      {
        id: 'ask-about',
        label: 'Ask about this',
        icon: 'database',
        category: 'ai',
        enabled: true,
      },
    ];
    const result = await openContextMenu({
      actions,
      anchor: { x: e.clientX, y: e.clientY },
    });
    if (result === 'open') void this.openFile(file.path);
    else if (result === 'copy-path') {
      void this.host_.ui.copyToClipboard(file.path);
    } else if (result === 'summarize') {
      compose({
        operation: 'core.summarize',
        source: 'BUTTON',
        docIds: [file.path],
        docName: file.name,
      });
    } else if (result === 'ask-about') {
      compose({
        operation: 'core.ask',
        source: 'BUTTON',
        userPrompt: `Tell me about the document at ${file.name}`,
        docIds: [file.path],
        docName: file.name,
      });
    }
    else if (result === 'reveal') {
      void this.host_.platform.revealInExplorer(file.path).catch(() => { /* Already reported. */ });
    }
  }

  private renderFileRow(file: BrowseFile, level: number): TemplateResult {
    const sizeText =
      file.size && file.size > 0 ? `${(file.size / 1024).toFixed(1)} KB` : '';
    return html`
      <div
        class="row file"
        style="padding-left: ${1.5 + level * 1}rem"
        @click=${() => void this.openFile(file.path)}
        @contextmenu=${(e: MouseEvent) => void this.handleFileContextMenu(e, file)}
        title=${file.path}
        role="treeitem"
      >
        <span class="toggle"></span>
        <span class="icon">${icon({ name: fileIconName(file.fileKind), size: 14 })}</span>
        <span class="name">${file.name}</span>
        ${sizeText ? html`<span class="meta">${sizeText}</span>` : nothing}
        <span class="actions">
          <jf-button
            variant="ghost"
            size="icon"
            label="Open file"
            @click=${(e: Event) => e.stopPropagation()}
            .onActivate=${() => void this.openFile(file.path)}
          >
            ${icon({ name: 'check-circle-2', size: 12 })}
          </jf-button>
        </span>
      </div>
    `;
  }

  private renderFolderChildren(path: string, level: number): TemplateResult {
    const folders = this.folderChildren[path] ?? [];
    const files = this.fileChildren[path] ?? [];
    if (folders.length === 0 && files.length === 0 && !this.inflight[path]) {
      return html`<div
        class="empty-state"
        style="padding-left: ${1.5 + level * 1}rem; padding-top: 0.25rem; padding-bottom: 0.25rem; text-align: left; font-size: var(--font-size-xs)"
      >
        empty
      </div>`;
    }
    return html`
      ${folders.filter((f) => this.matchesFilter(f.name)).map(
        (f) => html`
          ${this.renderFolderRow(f.path, f.name, level)}
          ${this.expanded[f.path] === true
            ? this.renderFolderChildren(f.path, level + 1)
            : nothing}
        `,
      )}
      ${files.filter((f) => this.matchesFilter(f.name)).map(
        (file) => this.renderFileRow(file, level),
      )}
    `;
  }

  private renderTree(): TemplateResult {
    if (this.refreshing && this.roots.length === 0) {
      return html`<div class="empty-state">Loading watched roots…</div>`;
    }
    if (this.roots.length === 0) {
      // 595 §4.3 — a transient/unavailable backend is NOT "no folders configured".
      if (this.provisional) {
        return html`<div class="empty-state">
          Rebuilding index… your watched folders will reappear when it finishes.
        </div>`;
      }
      return html`<div class="empty-state">
        No watched folders. Add one in the Library surface.
      </div>`;
    }
    return html`
      ${repeat(
        this.roots,
        (r) => r.path,
        (r) => html`
          ${this.renderFolderRow(r.path, lastSeg(r.path) || r.path, 0, r.fileCount)}
          ${this.expanded[r.path] === true ? this.renderFolderChildren(r.path, 1) : nothing}
        `,
      )}
    `;
  }

  override render(): TemplateResult {
    return html`
      <div class="header">
        <h2>${icon({ name: 'folder-tree', size: 16 })} Browse</h2>
        <jf-button
          variant="ghost"
          size="icon"
          label="Refresh"
          .availability=${this.refreshing ? unavailableBecause('Refreshing…', true) : undefined}
          .onActivate=${async () => {
            this.folderChildren = {};
            this.fileChildren = {};
            await this.loadRoots();
            for (const r of this.roots) {
              if (this.expanded[r.path]) await this.loadFolder(r.path);
            }
          }}
        >
          ${icon({ name: 'refresh-cw', size: 14, spin: this.refreshing })}
        </jf-button>
      </div>
      <div class="search-bar">
        <div class="filter-wrap">
          <span class="search-icon">${icon({ name: 'search', size: 12 })}</span>
          <input
            class="filter-input"
            type="text"
            placeholder="Filter files…"
            .value=${this.filter}
            @input=${(e: Event) => (this.filter = (e.target as HTMLInputElement).value)}
          />
        </div>
        <div class="filter-helper">Explore indexed files by folder structure.</div>
      </div>
      ${this.error
        ? html`<jf-error-alert tone="error" style="margin: 0.5rem 1.5rem"
            >${this.error}</jf-error-alert
          >`
        : nothing}
      <div class="body" role="tree" aria-label="Browse">${this.renderTree()}</div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-browse-surface')) {
  customElements.define('jf-browse-surface', BrowseSurface);
}
