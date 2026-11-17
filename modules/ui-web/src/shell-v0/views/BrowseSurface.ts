// SPDX-License-Identifier: Apache-2.0
/** Search-backed folder browser. Counts describe the root's disk observation, not loaded children. */
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { z } from 'zod';
import { getShowDeveloperTools, subscribeShowDeveloperTools } from '../state/developerToolsState.js';
import { JfElement } from '../primitives/JfElement.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';
import { recordRootCounts, rootCount, subscribeIndexedRoots } from '../state/indexedRoots.js';
import { selectIndexingProgress } from '../state/indexingProgress.js';
import { unavailableBecause } from '../state/availability.js';
import { buttonBaseStyles } from '../components/Button.js';
import '../components/ErrorAlert.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import { icon } from '../components/Icon.js';
import { openContextMenu, type ContextMenuAction } from '../components/ContextMenu.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { openLocalFile } from '../plugin-api/capabilities/platform.js';
import { compose } from '../utils/compose.js';
import { fileKindPresentation } from '../display/fileKind.js';
import { formatBytes, formatCount, formatFileCount, formatShortDate } from '../display/format.js';
import { folderBrowseResponseSchema } from '../../api/generated/schema-types/folder-browse-response.js';
import { folderFilesResponseSchema } from '../../api/generated/schema-types/folder-files-response.js';
import { parseWireContract } from '../../api/schemas.js';

const rootsSchema = z.object({ roots: z.array(z.object({ path: z.string(), fileCount: z.number().optional(), fileCountCapped: z.boolean().optional() }).loose()) });
interface BrowseFolder { path: string; name: string; fileCount?: number; fileCountCapped?: boolean }
interface BrowseFile { path: string; name: string; size?: number; fileKind?: string; mimeBase?: string; modifiedAt?: number }
type TreeRow = { id: string; path: string; parent?: string; level: number; position: number; size: number; folder?: BrowseFolder; file?: BrowseFile };
const lastSeg = (path: string): string => path.replace(/[\\/]+$/, '').split(/[\\/]/).pop() || path;
const samePath = (a: string, b: string): boolean => a.replace(/(.)[\\/]+$/, '$1') === b.replace(/(.)[\\/]+$/, '$1');
/** The last `count` segments of a path, as written (fewer when the path has fewer). */
const tailSegs = (path: string, count: number): string => {
  const trimmed = path.replace(/(.)[\\/]+$/, '$1');
  let at = trimmed.length;
  for (let i = 0; i < count && at > 0; i++) at = Math.max(trimmed.lastIndexOf('/', at - 1), trimmed.lastIndexOf('\\', at - 1));
  return trimmed.slice(at + 1);
};
/** A folder's name, or as much of its path's end as tells it apart from the other paths named beside it. */
const distinctName = (path: string, beside: string[]): string => {
  const others = beside.filter(other => !samePath(other, path));
  for (let count = 1; count <= path.split(/[\\/]/).length; count++) {
    const tail = tailSegs(path, count);
    if (tail && !others.some(other => tailSegs(other, count) === tail)) return tail;
  }
  return path;
};
/** Folders, then files, each by name, the order the Engine already gives folders (case-insensitive). */
const NAMES = new Intl.Collator(undefined, { sensitivity: 'base', numeric: true });
const byName = <T extends { name: string; path: string }>(rows: T[]): T[] =>
  [...rows].sort((a, b) => NAMES.compare(a.name, b.name) || (a.path < b.path ? -1 : a.path > b.path ? 1 : 0));

export class BrowseSurface extends JfElement {
  static properties = {
    developer: { state: true },
    apiBase: { attribute: 'api-base', type: String }, host_: { attribute: false }, roots: { state: true },
    folderChildren: { state: true }, fileChildren: { state: true }, expanded: { state: true }, inflight: { state: true },
    filter: { state: true }, refreshing: { state: true }, error: { state: true }, provisional: { state: true },
    aiState: { state: true }, folderErrors: { state: true }, partial: { state: true }, activeRowId: { state: true },
  };
  declare developer: boolean;
  private developerUnsub: (() => void) | undefined;
  declare apiBase: string;
  declare host_: PluginHostApi;
  declare roots: BrowseFolder[];
  declare folderChildren: Record<string, BrowseFolder[]>;
  declare fileChildren: Record<string, BrowseFile[]>;
  declare expanded: Record<string, boolean>;
  declare inflight: Record<string, boolean>;
  declare filter: string;
  declare refreshing: boolean;
  declare error: string | null;
  declare provisional: boolean;
  declare aiState: AiState | null;
  declare folderErrors: Record<string, boolean>;
  declare partial: Record<string, boolean>;
  declare activeRowId: string;
  private aiUnsub: (() => void) | null = null;
  private countsUnsub: (() => void) | null = null;
  private generation = 0;
  private recoveryParent: Record<string, string | undefined> = {};
  private recoverFocus = false;

  constructor() {
    super(); this.developer = false; this.apiBase = ''; this.host_ = undefined as unknown as PluginHostApi;
    this.roots = []; this.folderChildren = {}; this.fileChildren = {}; this.expanded = {}; this.inflight = {};
    this.filter = ''; this.refreshing = false; this.error = null; this.provisional = false; this.aiState = null;
    this.folderErrors = {}; this.partial = {}; this.activeRowId = '';
  }
  static styles = [surfaceLayoutStyles, buttonBaseStyles, css`
    /* The page column: each region spans the window, its content sits on the centred column, on the
       Library page's inline edge (the host's --library-page-edge) like the tab row above it. */
    .header { display: flex; align-items: center; justify-content: space-between; gap: .75rem; padding: .75rem calc(var(--surface-content-gutter) + var(--library-page-edge, 2.5rem)); }
    .search-bar { padding: 0 calc(var(--surface-content-gutter) + var(--library-page-edge, 2.5rem)) .625rem; }
    .filter-input { box-sizing: border-box; width: 100%; padding: .4rem .625rem; background: var(--surface-1);
      border: 1px solid var(--border-subtle); border-radius: 8px; color: var(--text-primary); font: inherit; font-size: var(--font-size-sm); }
    .body { padding: .875rem calc(var(--surface-content-gutter) + var(--library-page-edge, 2.5rem)); }
    .row, .column-heads { display: grid; grid-template-columns: minmax(0, 1fr) 5.625rem 8.125rem 4.75rem;
      align-items: center; box-sizing: border-box; min-height: 2.125rem; padding: 0 .5rem; gap: .25rem;
      border-bottom: 1px solid var(--border-subtle); font-size: var(--font-size-sm); color: var(--text-primary); }
    .column-heads { position: sticky; top: 0; z-index: 1; background: var(--surface-0); min-height: 1.875rem; font-size: var(--font-size-xs); font-weight: 500; color: var(--text-secondary); }
    /* The stuck heads stop at the body's top padding; this band, the padding's height, covers it so no
       row scrolls into view above them. */
    .column-heads::before { content: ''; position: absolute; inset-inline: 0; bottom: 100%; height: .875rem; background: inherit; }
    .row { cursor: pointer; user-select: none; }
    .row:hover, .row:focus-within { background: var(--surface-2); border-radius: 8px; border-bottom-color: transparent; }
    .row:focus-visible, .filter-input:focus-visible { outline: 2px solid var(--focus-ring-color); outline-offset: -2px; }
    .row-name { display: flex; align-items: center; gap: .625rem; min-width: 0; padding-inline-start: 0; }
    .name { white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
    .folder .name { font-weight: 500; }
    .toggle { width: 1rem; flex: none; display: inline-flex; }
    .icon { display: inline-flex; color: var(--text-secondary); flex: none; }
    .meta, .notice, .empty-state { font-size: var(--font-size-xs); color: var(--text-secondary); }
    .actions { display: flex; justify-content: flex-end; gap: .125rem; visibility: hidden; }
    .row:hover .actions, .row:focus-within .actions { visibility: visible; }
    .actions .jf-btn { width: 1.625rem; height: 1.625rem; min-height: 0; padding: 0; background: transparent; border: 0; }
    .level-1 .row-name { padding-inline-start: 1.75rem; }
    .level-2 .row-name { padding-inline-start: 3.5rem; }
    .level-3 .row-name { padding-inline-start: 5.25rem; }
    .level-4 .row-name { padding-inline-start: 7rem; }
    .level-5 .row-name { padding-inline-start: 8.75rem; }
    .level-6 .row-name { padding-inline-start: 10.5rem; }
    .level-7 .row-name { padding-inline-start: 12.25rem; }
    .level-8 .row-name { padding-inline-start: 14rem; }
    .notice, .empty-state { padding: .5rem; }
    .notice:empty { padding: 0; }
    @media (max-width: 640px) {
      .row, .column-heads { grid-template-columns: minmax(0, 1fr) 4.5rem 6rem 3.75rem; }
      /* Narrow: still the Library page's edge (1rem here), as the tab row above. */
      .body, .header, .search-bar { padding-inline: calc(var(--surface-content-gutter) + var(--library-page-edge, .75rem)); }
      .row-name { gap: .25rem; }
    }
    @media (max-height: 30rem) { .column-heads { position: static; } .column-heads::before { content: none; } }
    @media (forced-colors: active) { .row, .column-heads { border-color: CanvasText; } }
  `];
  override connectedCallback(): void {
    super.connectedCallback(); this.developer = getShowDeveloperTools();
    // The filter is a developer tool: it is cleared when they turn off, so no hidden filter applies.
    this.developerUnsub = subscribeShowDeveloperTools(show => { this.developer = show; if (!show) this.filter = ''; });
    if (!this.developer) this.filter = '';
    void this.loadRoots();
    this.aiUnsub = subscribeAiState(state => { this.provisional = state.stability.kind === 'provisional'; this.aiState = state; });
    // Library and Browse show one count per folder: the newest disk count either of them read.
    this.countsUnsub = subscribeIndexedRoots(() => {
      if (this.roots.some(root => { const count = rootCount(undefined, root.path); return count && (count.fileCount !== root.fileCount || count.fileCountCapped !== root.fileCountCapped); })) {
        this.roots = this.roots.map(root => ({ ...root, ...rootCount(undefined, root.path) }));
      }
    });
  }
  protected override settleTransients(): void { ++this.generation; this.inflight = {}; this.refreshing = false; this.error = null; }
  override disconnectedCallback(): void {
    ++this.generation; this.developerUnsub?.(); this.aiUnsub?.(); this.aiUnsub = null; this.countsUnsub?.(); this.countsUnsub = null;
    super.disconnectedCallback();
  }
  private doFetch(path: string, body?: unknown): Promise<Response> {
    return this.host_.data.fetch(path, body === undefined ? {} : {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
  }
  private async loadRoots(): Promise<void> {
    const generation = this.generation;
    this.refreshing = true; this.error = null;
    try {
      const res = await this.doFetch('/api/indexing/roots?counts=true');
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const data = parseWireContract(rootsSchema, await res.json(), '/api/indexing/roots');
      if (generation !== this.generation) return;
      recordRootCounts(data.roots);
      this.roots = byName(data.roots.map(root => ({ ...root, ...rootCount(undefined, root.path), name: lastSeg(root.path) })));
    } catch { if (generation === this.generation) this.error = "Couldn't load folders";
    } finally { if (generation === this.generation) this.refreshing = false; }
  }
  private async loadFolder(path: string): Promise<void> {
    if (this.inflight[path]) return;
    const generation = this.generation;
    this.inflight = { ...this.inflight, [path]: true };
    this.folderErrors = { ...this.folderErrors, [path]: false };
    try {
      const [foldersRes, filesRes] = await Promise.all([
        this.doFetch('/api/knowledge/folders', { parentPath: path, maxFolders: 500 }),
        this.doFetch('/api/knowledge/folder-files', { folderPath: path, limit: 1000, projection: ['path', 'filename', 'file_kind', 'mime_base', 'size_bytes', 'modified_at'] }),
      ]);
      if (!foldersRes.ok || !filesRes.ok) throw new Error(`HTTP ${!foldersRes.ok ? foldersRes.status : filesRes.status}`);
      const folders = parseWireContract(folderBrowseResponseSchema, await foldersRes.json(), '/api/knowledge/folders');
      const files = parseWireContract(folderFilesResponseSchema, await filesRes.json(), '/api/knowledge/folder-files');
      if (generation !== this.generation) return;
      this.folderChildren = { ...this.folderChildren, [path]: byName((folders.folders ?? []).filter(f => f.path).map(f => ({
        path: f.path!, name: f.name || lastSeg(f.path!), fileCount: f.fileCount,
      }))) };
      const numeric = (value: string | undefined): number | undefined => value !== undefined && value.trim() !== '' && Number.isFinite(Number(value)) ? Number(value) : undefined;
      this.fileChildren = { ...this.fileChildren, [path]: byName((files.files ?? []).filter(row => row.fields?.path || row.docId).map(row => ({
        path: row.fields?.path ?? row.docId!, name: row.fields?.filename ?? lastSeg(row.fields?.path ?? row.docId!),
        fileKind: row.fields?.file_kind, mimeBase: row.fields?.mime_base, size: numeric(row.fields?.size_bytes), modifiedAt: numeric(row.fields?.modified_at),
      }))) };
      this.partial = { ...this.partial, [path]: folders.truncated === true || (files.totalCount ?? 0) > (files.files?.length ?? 0) };
    } catch {
      if (generation === this.generation) this.folderErrors = { ...this.folderErrors, [path]: true };
    } finally { if (generation === this.generation) { const next = { ...this.inflight }; delete next[path]; this.inflight = next; } }
  }
  private async toggle(row: TreeRow): Promise<void> {
    this.expanded = { ...this.expanded, [row.id]: !this.expanded[row.id] };
    if (this.expanded[row.id]) await this.loadFolder(row.path);
  }
  private async refresh(): Promise<void> {
    const generation = ++this.generation; this.inflight = {}; this.folderErrors = {};
    await this.loadRoots();
    // Expand by occurrence, but fetch each physical folder only once per refresh.
    const loaded = new Set<string>();
    for (let index = 0; generation === this.generation && index < this.rows().length; index++) {
      const row = this.rows()[index]!;
      if (row.folder && this.expanded[row.id] && !loaded.has(row.path)) {
        loaded.add(row.path); await this.loadFolder(row.path);
      }
    }
  }
  /** The filter in effect: none while developer tools are off. */
  private get activeFilter(): string { return this.developer ? this.filter.trim() : ''; }
  private matchesFilter(name: string): boolean { return name.toLocaleLowerCase().includes(this.activeFilter.toLocaleLowerCase()); }
  /** Every occurrence of a watched folder, nested ones included, shows its one shared disk count. */
  private folderCount(folder: BrowseFolder): string {
    const root = this.roots.find(item => samePath(item.path, folder.path)) ?? folder;
    return formatFileCount(root.fileCount, root.fileCountCapped);
  }
  private rows(): TreeRow[] {
    const rows: TreeRow[] = [];
    const addFolders = (folders: BrowseFolder[], ancestors: string[] = [], files: BrowseFile[] = []) => {
      const parent = ancestors.length ? JSON.stringify(ancestors) : undefined;
      const level = ancestors.length + 1;
      const matchingFiles = files.filter(file => this.matchesFilter(file.name));
      // Ancestors stay present so a filter never strands a visible descendant.
      const visibleFolders = folders.filter(folder => this.matchesFilter(folder.name) || this.expanded[JSON.stringify([...ancestors, folder.path])]);
      const size = visibleFolders.length + matchingFiles.length;
      visibleFolders.forEach((folder, index) => {
        const chain = [...ancestors, folder.path], id = JSON.stringify(chain);
        rows.push({ id, path: folder.path, parent, level, position: index + 1, size, folder });
        if (this.expanded[id]) addFolders(this.folderChildren[folder.path] ?? [], chain, this.fileChildren[folder.path] ?? []);
      });
      matchingFiles.forEach((file, index) => rows.push({ id: JSON.stringify([...ancestors, file.path]), path: file.path, parent, level, position: visibleFolders.length + index + 1, size, file }));
    };
    addFolders(this.roots); return rows;
  }
  protected override willUpdate(_changed: PropertyValues): void {
    const rows = this.rows();
    const active = this.shadowRoot?.activeElement as HTMLElement | null;
    this.recoverFocus = !!active?.closest('[role="treeitem"]');
    if (!rows.some(row => row.id === this.activeRowId)) {
      let parent = this.recoveryParent[this.activeRowId];
      while (parent && !rows.some(row => row.id === parent)) parent = this.recoveryParent[parent];
      this.activeRowId = parent ?? rows[0]?.id ?? '';
    }
    this.recoveryParent = Object.fromEntries(rows.map(row => [row.id, row.parent]));
  }
  protected override updated(): void {
    if (this.recoverFocus && !this.shadowRoot?.activeElement?.closest('[role="treeitem"]')) this.focusRow(this.activeRowId);
  }
  private focusRow(id: string): void {
    this.activeRowId = id;
    const row = [...(this.shadowRoot?.querySelectorAll<HTMLElement>('[role="treeitem"]') ?? [])].find(row => row.dataset.rowId === id);
    if (row) row.focus(); else (this.shadowRoot?.querySelector<HTMLElement>('.filter-input, .empty-state'))?.focus();
  }
  private async onKey(event: KeyboardEvent, row: TreeRow): Promise<void> {
    if (event.target !== event.currentTarget) return;
    const rows = this.rows(), index = rows.findIndex(item => item.id === row.id);
    switch (event.key) {
      case 'ArrowDown': event.preventDefault(); this.focusRow(rows[Math.min(index + 1, rows.length - 1)]!.id); break;
      case 'ArrowUp': event.preventDefault(); this.focusRow(rows[Math.max(0, index - 1)]!.id); break;
      case 'Home': event.preventDefault(); this.focusRow(rows[0]!.id); break;
      case 'End': event.preventDefault(); this.focusRow(rows[rows.length - 1]!.id); break;
      case 'ArrowRight':
        event.preventDefault(); if (row.folder) {
          if (!this.expanded[row.id]) await this.toggle(row);
          else if (rows[index + 1]?.parent === row.id) this.focusRow(rows[index + 1]!.id);
        } break;
      case 'ArrowLeft': event.preventDefault(); if (row.folder && this.expanded[row.id]) await this.toggle(row); else if (row.parent) this.focusRow(row.parent); break;
      case 'Enter': event.preventDefault(); if (row.file) await this.openFile(row.path); else await this.toggle(row); break;
      case 'ContextMenu': case 'F10':
        if (event.key === 'ContextMenu' || event.shiftKey) {
          if (row.file) { event.preventDefault(); const rect = (event.currentTarget as HTMLElement).getBoundingClientRect(); await this.fileMenu(row.file, rect.left, rect.bottom); }
        } break;
    }
  }
  private async openFile(path: string): Promise<void> { try { await openLocalFile(path); } catch { /* The platform helper presents the refusal. */ } }
  private async fileMenu(file: BrowseFile, x: number, y: number): Promise<void> {
    const actions: ContextMenuAction[] = [
      { id: 'open', label: 'Open file', icon: 'external-link', shortcut: 'Enter', category: 'file', enabled: true },
      ...(this.host_.platform.capabilities.has('reveal-in-explorer') ? [{ id: 'reveal', label: 'Show in folder', icon: 'folder' as const, category: 'file' as const, enabled: true }] : []),
      { id: 'copy-path', label: 'Copy path', category: 'file', enabled: true },
      { id: 'summarize', label: 'Summarize', icon: 'file-text', category: 'ai', enabled: true },
      { id: 'ask-about', label: 'Ask about this', icon: 'database', category: 'ai', enabled: true },
    ];
    const result = await openContextMenu({ actions, anchor: { x, y } });
    if (result === 'open') await this.openFile(file.path);
    else if (result === 'reveal') await this.host_.platform.revealInExplorer(file.path).catch(() => { /* Already reported. */ });
    else if (result === 'copy-path') await this.host_.ui.copyToClipboard(file.path);
    else if (result === 'summarize') compose({ operation: 'core.summarize', source: 'BUTTON', docIds: [file.path], docName: file.name });
    else if (result === 'ask-about') compose({ operation: 'core.ask', source: 'BUTTON', userPrompt: `Tell me about the document at ${file.name}`, docIds: [file.path], docName: file.name });
  }
  private renderRow(row: TreeRow): TemplateResult {
    const folder = row.folder, file = row.file, focused = this.activeRowId === row.id;
    const size = file?.size;
    const date = file?.modifiedAt !== undefined ? new Date(file.modifiedAt) : null;
    return html`<div class="row ${folder ? 'folder' : 'file'} level-${Math.min(8, row.level - 1)}" role="treeitem"
      data-row-id=${row.id} data-path=${row.path} title=${row.path} tabindex=${focused ? '0' : '-1'} aria-level=${row.level}
      aria-posinset=${row.position} aria-setsize=${row.size} aria-expanded=${folder ? String(!!this.expanded[row.id]) : nothing}
      @focus=${() => { this.activeRowId = row.id; }} @keydown=${(event: KeyboardEvent) => void this.onKey(event, row)}
      @click=${() => { this.focusRow(row.id); if (folder) void this.toggle(row); else void this.openFile(row.path); }}
      @contextmenu=${(event: MouseEvent) => { if (file) { event.preventDefault(); this.focusRow(row.id); void this.fileMenu(file, event.clientX, event.clientY); } }}>
      <span class="row-name"><span class="toggle">${folder ? icon({ name: this.expanded[row.id] ? 'chevron-down' : 'chevron-right', size: 12 }) : nothing}</span>
        <span class="icon" data-kind=${file ? fileKindPresentation(file.fileKind, file.mimeBase, file.path).kind : 'folder'}>${icon({ name: folder ? 'folder' : fileKindPresentation(file?.fileKind, file?.mimeBase, file?.path).icon, size: 16 })}</span>
        <span class="name">${folder?.name ?? file?.name}</span></span>
      <span class="meta">${folder ? this.folderCount(folder)
        : size !== undefined && size >= 0 ? formatBytes(size) : ''}</span>
      <span class="meta">${date && Number.isFinite(date.getTime()) ? formatShortDate(date) : ''}</span>
      <span class="actions" @click=${(event: Event) => event.stopPropagation()} @keydown=${(event: KeyboardEvent) => event.stopPropagation()}>
        ${file ? html`<button type="button" class="jf-btn" aria-label="Open file" tabindex=${focused ? '0' : '-1'}
          @click=${() => void this.openFile(row.path)}>${icon({ name: 'external-link', size: 14 })}</button>
        ${this.host_.platform.capabilities.has('reveal-in-explorer') ? html`<button type="button" class="jf-btn" aria-label="Show in folder" tabindex=${focused ? '0' : '-1'}
          @click=${() => void this.host_.platform.revealInExplorer(row.path).catch(() => { /* Already reported. */ })}>${icon({ name: 'folder', size: 14 })}</button>` : nothing}` : nothing}
      </span>
    </div>`;
  }
  /** One status line per open folder; an open folder with nothing to say adds no band above the tree. */
  private renderNotices(): TemplateResult {
    const open = this.rows().filter(row => row.folder && this.expanded[row.id]);
    // Failure lines name their folder; two failed folders of one name show enough of the path to tell apart.
    const failed = open.filter(({ path }) => this.folderErrors[path] && !this.inflight[path]).map(({ path }) => path);
    return html`${open.map(({ path }) => {
      const folders = this.folderChildren[path], files = this.fileChildren[path];
      const count = (folders?.length ?? 0) + (files?.length ?? 0);
      const empty = folders !== undefined && files !== undefined && (this.activeFilter && ![...folders, ...files].some(item => this.matchesFilter(item.name))
        ? `${lastSeg(path)}: No matching files in the loaded entries.` : count === 0 ? `${lastSeg(path)}: No searchable files in this folder.` : '');
      const partial = folders !== undefined && files !== undefined && this.partial[path] ? `${lastSeg(path)}: Showing first ${formatCount(count)} entries.` : '';
      // The live region stays so a later message is announced; with nothing to say it takes no room.
      return html`<div class="notice" role="status">${this.inflight[path] ? `Loading ${lastSeg(path)}…`
        : this.folderErrors[path] ? (name => html`${name}: Couldn't load this folder <jf-button variant="ghost" label=${`Retry ${name}`}
          .onActivate=${() => void this.loadFolder(path)}>Retry ${name}</jf-button>`)(distinctName(path, failed))
        : [partial, empty].filter(Boolean).join(' ') || nothing}</div>`;
    })}`;
  }
  override render(): TemplateResult {
    const rows = this.rows();
    const state = this.aiState;
    const activity = selectIndexingProgress(state?.status ?? null, state?.indexingStatusLive ?? false, state?.episodeMaxPendingJobs ?? 0, state?.enrichSettleSamples ?? []).activity;
    // Only an observed rebuild is called one; another transition (an outage, a restart) is not.
    const rebuilding = state?.stability.kind === 'provisional' && (state.stability.cause === 'rebuilding' || state.stability.cause === 'generation-switch');
    return html`${this.developer ? html`<div class="header"><jf-button variant="ghost" size="icon" label="Refresh"
      .availability=${this.refreshing ? unavailableBecause('Refreshing…', true) : undefined} .onActivate=${() => void this.refresh()}>
      ${icon({ name: 'refresh-cw', size: 14, spin: this.refreshing })}</jf-button></div>
      <div class="search-bar"><input class="filter-input" type="search" aria-label="Filter files" placeholder="Filter files…"
        .value=${this.filter} @input=${(event: Event) => { this.filter = (event.target as HTMLInputElement).value; }}></div>` : nothing}
      <div class="body">
        <div class="column-heads" aria-hidden="true"><span>Name</span><span>Size</span><span>Changed</span><span></span></div>
        ${this.error ? html`<jf-error-alert class="browse-error">${this.error}<jf-button label="Retry loading folders" .onActivate=${() => void this.refresh()}>Retry loading folders</jf-button></jf-error-alert>` : nothing}
        ${this.renderNotices()}
        ${rows.length ? html`<div role="tree" aria-label="Browse searchable files">${repeat(rows, row => row.id, row => this.renderRow(row))}</div>`
          : html`<div class="empty-state" tabindex="-1" role="status">${this.refreshing ? 'Loading folders…' : this.error ? ''
            : state && !activity.confirmed ? `${activity.summary}. Waiting for a fresh observation of folders.`
            : this.provisional && rebuilding ? 'Reading your files again… your watched folders will reappear when it finishes.'
            : this.provisional ? 'Waiting for a fresh observation of folders.'
            : this.activeFilter ? 'No matching folders in the loaded entries.' : 'No folders yet. Add a folder in Library.'}</div>`}
      </div>`;
  }
}
if (!customElements.get('jf-browse-surface')) customElements.define('jf-browse-surface', BrowseSurface);
