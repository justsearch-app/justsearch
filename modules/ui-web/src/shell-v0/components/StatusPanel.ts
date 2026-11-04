// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { JfElement } from '../primitives/JfElement.js';
import type { FrameStatus, StatusAction, StatusFact, StatusSection } from '../state/frameStatus.js';
import type { StatusBarItem } from '../commands/StatusBarRegistry.js';
import { formatRelativeIso } from '../../utils/relativeTime.js';
import { glyphChar, statusGlyph } from '../utils/statusTone.js';
// 559 Authority VI — plugin items keep one line and overflow by priority into "…";
// a lone item wider than the row wraps inside it instead, and an element wider than the row is
// contained by it (L2).
import { OverflowController } from '../primitives/adaptiveBar.js';
import './ProvenanceChip.js';
import './IndexingControls.js';
import './StatusBadge.js';

const SECTION_NAMES: Record<StatusSection, string> = {
  needs: 'Needs you', problems: 'Problems right now', running: 'Running', earlier: 'Earlier',
};

/** ref-09 header line: the search state, then what each non-empty section holds. */
function summary(model: FrameStatus): string {
  const n = (section: StatusSection) => model.sections[section].length;
  const counts = [n('needs') ? `${n('needs')} need you` : '', n('problems') ? `${n('problems')} ${n('problems') === 1 ? 'problem' : 'problems'}` : '',
    n('running') ? `${n('running')} running` : ''].filter(Boolean);
  const state = ['needs', 'problems', 'working'].includes(model.pill.kind) ? null : model.pill.label;
  return [state, ...counts].filter(Boolean).join(' · ') || model.pill.label;
}

/**
 * The row's icon. History shows how an item ended (a failure is never drawn as a check); the words
 * in the title and detail carry the same meaning, so the glyph is never the only signal.
 */
function rowGlyph(row: StatusFact): string {
  if (row.section !== 'earlier' && row.tone !== 'failure') return ({ needs: '!', problems: '×', running: '◌' } as const)[row.section];
  // The one status → glyph authority (565 §17); an item without a decisive outcome draws a plain dot.
  return glyphChar(statusGlyph(row.tone === 'neutral' ? 'info' : row.tone)) || '•';
}

/** Keyed rows preserve disclosures, focus and scroll through live source updates. */
export class StatusPanel extends JfElement {
  static override properties = {
    model: { attribute: false }, plugins: { attribute: false }, apiBase: { attribute: 'api-base' },
    expanded: { state: true }, pluginsOpen: { state: true },
  };
  declare model: FrameStatus | null;
  declare plugins: readonly StatusBarItem[];
  declare apiBase: string;
  declare expanded: ReadonlySet<string>;
  declare pluginsOpen: boolean;
  constructor() { super(); this.model = null; this.plugins = []; this.apiBase = ''; this.expanded = new Set(); this.pluginsOpen = false; }
  private readonly overflow = new OverflowController(this, {
    items: () => [...this.renderRoot.querySelectorAll<HTMLElement>('.plugin')],
    container: () => this.renderRoot.querySelector<HTMLElement>('.plugins'),
    signature: () => this.plugins.map(item => item.id).join('|'),
    pinned: () => this.plugins.map(item => item.overflow === 'pinned'),
  });
  static styles = css`
    :host { display: block; color: var(--text-primary); font-size: var(--font-size-sm); }
    :host::before { content: ''; position: absolute; right: var(--frame-status-arrow-right, var(--jf-space-4));
      top: calc(-1 * var(--jf-space-1)); width: var(--jf-space-2); height: var(--jf-space-2);
      background: var(--surface-1); border-inline-start: var(--frame-hairline) solid var(--border-emphasized);
      border-top: var(--frame-hairline) solid var(--border-emphasized); transform: rotate(45deg); pointer-events: none; }
    .panel { width: min(var(--frame-status-panel-width), calc(100vw - 2 * var(--jf-space-2)));
      max-height: calc(100dvh - var(--frame-status-panel-top) - var(--jf-space-2) - var(--jf-space-4));
      overflow-y: auto; overscroll-behavior: contain; background: var(--surface-1);
      border: var(--frame-hairline) solid var(--border-emphasized); border-radius: var(--frame-panel-radius);
      box-shadow: var(--shadow-float); overflow-wrap: anywhere; }
    .header, section, .footer, .plugins { padding: var(--jf-space-4); }
    .header, section, .plugins { border-bottom: var(--frame-hairline) solid var(--border-subtle); }
    .header, .heading, .footer { display: flex; align-items: center; gap: var(--jf-space-3); }
    .header > div, .heading > span { flex: 1; min-width: 0; }
    h2 { margin: 0; font-size: var(--font-size-lg); font-weight: var(--jf-weight-medium); }
    h3 { margin: 0; font-size: var(--font-size-sm); font-weight: var(--jf-weight-medium); color: var(--text-secondary); }
    p { margin: var(--jf-space-1) 0; }
    .detail, time { color: var(--text-secondary); font-size: var(--font-size-xs); }
    .deadline { color: var(--text-warning); font-size: var(--font-size-xs); }
    article { padding-block: var(--jf-space-3); }
    .title { display: flex; align-items: baseline; gap: var(--jf-space-2); }
    .title > .text { flex: 1; min-width: 0; }
    /* A fixed icon column: every row title starts at the same inline offset. */
    .state-icon { flex: none; inline-size: var(--jf-space-4); text-align: center; color: var(--text-secondary); }
    .state-icon[data-tone="success"] { color: var(--text-success); }
    .state-icon[data-tone="failure"] { color: var(--text-danger); }
    .state-icon[data-tone="warning"] { color: var(--text-warning); }
    time { flex-shrink: 0; font-variant-numeric: tabular-nums; }
    .actions { display: flex; flex-wrap: wrap; gap: var(--jf-space-3); margin-top: var(--jf-space-2); }
    button { font: inherit; color: var(--text-primary); background: transparent; border: 0; padding: var(--jf-space-1); cursor: pointer; }
    .actions button, .footer button { color: var(--text-tint); }
    button:hover { text-decoration: underline; }
    button:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: var(--jf-space-1); }
    progress { display: block; width: 100%; height: var(--jf-space-1); accent-color: var(--accent); margin-top: var(--jf-space-2); }
    .unread { font-size: var(--font-size-xs); color: var(--text-tint); }
    .plugins { display: flex; flex-wrap: nowrap; align-items: center; gap: var(--jf-space-3); min-width: 0; }
    .plugins.open { flex-wrap: wrap; }
    .plugin { display: flex; flex-wrap: wrap; align-items: center; gap: var(--jf-space-2); max-width: 100%; min-width: 0; }
    /* L2 — a plugin's own element may be wider than the row (a fixed width, a min-width): the row
       contains it, never the panel scrolling sideways. Its words stay reachable as the tooltip. */
    .plugin-body { display: block; min-width: 0; max-inline-size: 100%; overflow: hidden; }
    .plugin-body > * { max-inline-size: 100%; box-sizing: border-box; }
    .empty { padding: var(--jf-space-4); }
    @media (forced-colors: active) {
      :host::before { background: Canvas; border-color: CanvasText; }
      .panel { background: Canvas; color: CanvasText; border-color: CanvasText; box-shadow: none; }
      button { color: ButtonText; } .detail, .deadline, time, h3, .state-icon[data-tone] { color: CanvasText; }
      .actions button, .footer button, .unread { color: LinkText; }
      button:focus-visible { outline-color: Highlight; }
    }
  `;
  private readonly pluginOutputs = new WeakMap<StatusBarItem, HTMLElement | string>();
  private focusSummary = false;
  private focusedRow: { id: string | null; section: string | null } | null = null;
  protected override willUpdate(changed: PropertyValues): void {
    if (!changed.has('model') || !this.focusedRow) return;
    const row = Object.values(this.model?.sections ?? {}).flat().find(r => r.id === this.focusedRow!.id);
    this.focusSummary = !row || SECTION_NAMES[row.section] !== this.focusedRow.section;
  }
  protected override updated(): void {
    if (this.focusSummary) {
      this.focusSummary = false;
      this.shadowRoot?.querySelector<HTMLElement>('[role="dialog"]')?.focus({ preventScroll: true });
    }
  }
  private pluginOutput(item: StatusBarItem): HTMLElement | string {
    if (!this.pluginOutputs.has(item)) this.pluginOutputs.set(item, item.render());
    return this.pluginOutputs.get(item)!;
  }
  private action(action: StatusAction): void {
    this.dispatchEvent(new CustomEvent<StatusAction>('status-action', { detail: action, bubbles: true, composed: true }));
  }
  private toggle(row: StatusFact): void {
    const next = new Set(this.expanded);
    if (next.has(row.id)) next.delete(row.id); else next.add(row.id);
    this.expanded = next;
    this.dispatchEvent(new CustomEvent('status-read', { detail: row.id, bubbles: true, composed: true }));
  }
  private row(row: StatusFact): TemplateResult {
    const expanded = this.expanded.has(row.id);
    return html`<article data-fact=${row.id}>
      <div class="title"><span class="state-icon" aria-hidden="true" data-tone=${row.tone ?? nothing}>${rowGlyph(row)}</span><span class="text">${row.title}${row.confidence == null ? nothing : html` <jf-status-badge .tone=${row.confidence < 0.5 ? 'warning' : 'neutral'} .label=${`${Math.round(row.confidence * 100)}% confidence`}></jf-status-badge>`} ${row.unread ? html`<span class="unread">Unread</span>` : nothing}</span>
        ${row.occurredAt ? html`<time datetime=${row.occurredAt}>${formatRelativeIso(row.occurredAt)}</time>` : nothing}</div>
      ${row.stale ? html`<p class="detail">Last seen · checking for an update</p>` : nothing}
      ${row.detail ? html`<p class="detail" title=${row.progressLabel ?? nothing} aria-label=${row.progressLabel ?? nothing}>${row.detail}</p>` : nothing}
      ${row.deadline ? html`<p class="deadline" data-deadline><span aria-hidden="true">◷</span> ${row.deadline}</p>` : nothing}
      ${row.indeterminate && !row.stale ? html`<progress aria-label=${row.title}></progress>` : nothing}
      ${row.percent != null && !row.stale ? html`<progress aria-label=${row.title} max="100" value=${row.percent}></progress>` : nothing}
      ${row.indexing && this.model ? html`<jf-indexing-controls .progress=${this.model.progress}
        .allowPause=${row.section === 'running'} api-base=${this.apiBase}></jf-indexing-controls>` : nothing}
      <div class="actions">
        ${(row.actions ?? []).map(action => html`<button type="button" @click=${() => this.action(action)}>${action.label}</button>`)}
        ${row.section === 'earlier' || row.details?.length ? html`<button type="button" aria-expanded=${expanded}
          @click=${() => this.toggle(row)}>${expanded ? 'Hide details' : 'Details'}</button>` : nothing}
      </div>
      ${expanded ? html`<div class="detail" data-expanded=${row.id}><slot name=${row.id}></slot>${row.details?.length ? repeat(row.details, detail => detail.id, detail => html`<p data-detail=${detail.id}>${detail.title}${detail.detail ? ` · ${detail.detail}` : ''}${detail.stale ? ' · last seen' : ''}</p>`) : row.occurredAt ?? row.title}
        ${row.diagnostics ? /^https?:\/\//i.test(row.diagnostics) ? html`<p><a href=${row.diagnostics} target="_blank" rel="noopener noreferrer">Diagnostics</a></p>` : html`<p>${row.diagnostics}</p>` : nothing}</div>` : nothing}
    </article>`;
  }
  /** Plugin row: registry order is priority order; hidden items stay one click away. */
  private renderPlugins(): TemplateResult | typeof nothing {
    if (!this.plugins.length) return nothing;
    // L2 — the first item always shows directly (wrapping inside the row, provenance beside it);
    // "… N more" collects only items that do not fit after it, never the only one.
    const shown = this.pluginsOpen ? this.plugins : this.plugins.slice(0, Math.max(1, this.overflow.visibleCount));
    const hidden = this.plugins.length - shown.length;
    return html`<div class="plugins ${this.pluginsOpen ? 'open' : ''}" role="group" aria-label="Plugin status">${repeat(shown, item => item.id,
      item => { const output = this.pluginOutput(item); return html`<span class="plugin" data-plugin-item=${item.id}><span class="plugin-body"
        title=${(typeof output === 'string' ? output : output.textContent ?? '').trim()}>${output}</span><jf-provenance-chip .provenance=${item.provenance}></jf-provenance-chip></span>`; })}
      ${hidden > 0 || this.pluginsOpen && this.overflow.visibleCount < this.plugins.length ? html`<button type="button" aria-expanded=${this.pluginsOpen}
        @click=${() => { this.pluginsOpen = !this.pluginsOpen; }}>${this.pluginsOpen ? 'Fewer' : `… ${hidden} more`}</button>` : nothing}</div>`;
  }
  // B5 — the header and footer rows are plain containers: page banner/contentinfo landmarks belong
  // to the app frame, never to a popover.
  override render(): TemplateResult {
    const model = this.model;
    return html`<div class="panel" data-testid="status-panel" role="dialog" aria-label="What's going on" tabindex="-1"
      @focusin=${(event: FocusEvent) => {
        const row = event.composedPath().find(node => node instanceof HTMLElement && node.matches('article')) as HTMLElement | undefined;
        this.focusedRow = row ? { id: row.getAttribute('data-fact'), section: row.closest('section')?.getAttribute('aria-label') ?? null } : null;
      }} @focusout=${(event: FocusEvent) => {
        if (!(event.relatedTarget instanceof Node) || !this.shadowRoot?.contains(event.relatedTarget)) this.focusedRow = null;
      }}>
      <div class="header"><div><h2>What's going on</h2><p class="detail">${model ? summary(model) : 'Checking search status'}</p></div>
        <button type="button" aria-label="Close status panel" @click=${() => this.dispatchEvent(new CustomEvent('status-close', { bubbles: true, composed: true }))}>×</button></div>
      ${model ? (['needs', 'problems', 'running', 'earlier'] as const).map(section => {
        const rows = model.sections[section];
        if (!rows.length) return nothing;
        return html`<section aria-label=${SECTION_NAMES[section]}><div class="heading"><h3>${SECTION_NAMES[section]} ${section === 'earlier'
          ? `${rows.filter(r => r.unread).length} unread` : rows.length}</h3><span></span>
          ${section === 'earlier' ? html`<button type="button" aria-disabled=${rows.some(r => r.unread) ? 'false' : 'true'}
            title=${rows.some(r => r.unread) ? nothing : 'All items are already read'}
            @click=${() => { if (rows.some(r => r.unread)) this.dispatchEvent(new CustomEvent('status-read-all', { bubbles: true, composed: true })); }}>Mark all read</button>` : nothing}</div>
          ${repeat(rows, row => row.id, row => this.row(row))}</section>`;
      }) : nothing}
      ${model && Object.values(model.sections).every(rows => !rows.length)
        ? html`<p class="empty">${model.pill.kind === 'calm' ? 'Search is up to date. Nothing needs you.' : model.pill.label}</p>` : nothing}
      ${this.renderPlugins()}
      <div class="footer"><button type="button" @click=${() => this.action({ kind: 'navigate', target: 'core.activity-surface', label: 'All activity' })}>All activity</button>
        <button type="button" @click=${() => this.action({ kind: 'navigate', target: 'core.health-surface', label: 'Health' })}>Health</button></div>
    </div>`;
  }
}
if (!customElements.get('jf-status-panel')) customElements.define('jf-status-panel', StatusPanel);
