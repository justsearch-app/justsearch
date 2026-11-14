// SPDX-License-Identifier: Apache-2.0
import { html, css, nothing, type TemplateResult, type PropertyValues } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { JfElement } from '../primitives/JfElement.js';
import type { FrameStatus, StatusAction, StatusFact, StatusReview, StatusReviewLine, StatusSection } from '../state/frameStatus.js';
import { ARMING_DELAY_MS } from '../operations/authorizationBroker.js';
import { modalOwnsFocus } from '../primitives/modality.js';
import { isTypingTarget, shouldIgnoreKeyEvent } from '../utils/keyboardHandler.js';
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

const LINE_ICONS: Record<StatusReviewLine['icon'], string> = { target: '▸', risk: '⚠', undo: '↺', info: 'ⓘ' };

/** The answers a list row or a review gives in place: each one is a decision control. */
const DECISIONS: ReadonlySet<StatusAction['kind']> = new Set(['authorization-allow', 'authorization-deny', 'proposal', 'reject-proposal']);
const decisionId = (action: StatusAction): string => `${action.kind}:${action.target}`;

/** The words a node shows: its text, through open shadow roots and the nodes assigned to its slots. */
function shownText(node: Node): string {
  if (node.nodeType === Node.TEXT_NODE) return node.textContent ?? '';
  if (!(node instanceof Element) || ['style', 'script', 'template'].includes(node.localName)) return '';
  const children = node instanceof HTMLSlotElement ? node.assignedNodes({ flatten: true })
    : [...(node.shadowRoot ?? node).childNodes];
  return children.map(shownText).join(' ');
}

/** Keyed rows preserve disclosures, focus and scroll through live source updates. */
export class StatusPanel extends JfElement {
  static override properties = {
    model: { attribute: false }, plugins: { attribute: false }, apiBase: { attribute: 'api-base' },
    expanded: { state: true }, pluginsOpen: { state: true }, reviewing: { state: true }, always: { state: true }, gone: { state: true }, noticeGone: { state: true },
  };
  /** DE3 — the Needs-you row whose review is open inside the panel (ref-10), or null for the list. */
  declare reviewing: string | null;
  /** A clicked desktop notification's item has left the panel: answered, expired or cleared. */
  declare noticeGone: boolean;
  /** The review's "always allow" choice; reset for every review. */
  declare always: boolean;
  /** The reviewed request left (answered elsewhere, withdrawn or expired) while it was open. */
  declare gone: boolean;
  /** When the current review opened; its answers arm {@link ARMING_DELAY_MS} later. */
  private reviewOpenedAt = 0;
  /** The review this panel answered: its leaving is the answer taking effect, not news. */
  private answered: string | null = null;
  /** The pointer press a decision click must complete; it has to start after the review armed. */
  private press: { at: number; button: Element | null } | null = null;
  /**
   * C12 — where each decision control stands (which request it answers, and its box) and since when.
   * A control that appeared, moved (a row above it left or arrived) or now answers another request
   * re-arms: only a press that starts {@link ARMING_DELAY_MS} after that counts.
   */
  private readonly placed = new WeakMap<Element, { where: string; at: number }>();
  /** The decision control's press: a pointer press or an unrepeated Enter/Space, after it armed. */
  private controlPress: { at: number; control: Element | null } | null = null;
  private keyPress: { key: string; control: Element } | null = null;
  declare model: FrameStatus | null;
  declare plugins: readonly StatusBarItem[];
  declare apiBase: string;
  declare expanded: ReadonlySet<string>;
  declare pluginsOpen: boolean;
  constructor() {
    super(); this.model = null; this.plugins = []; this.apiBase = ''; this.expanded = new Set(); this.pluginsOpen = false;
    this.reviewing = null; this.always = false; this.gone = false; this.noticeGone = false;
  }
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
    h2 { margin: 0; font-size: var(--font-size-lg); font-variation-settings: "opsz" 32; font-weight: var(--jf-weight-medium); }
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
    .actions button.primary { color: var(--accent-on-tint); background: var(--accent-tint); border-radius: var(--frame-control-radius);
      padding-inline: var(--jf-space-3); }
    .error { color: var(--text-danger); font-size: var(--font-size-xs); }
    /* ref-10 — the review of one request, inside the panel. */
    .review { padding: var(--jf-space-4); display: grid; gap: var(--jf-space-3); }
    .review h2:focus { outline: none; }
    .review h2:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
    .back { justify-self: start; color: var(--text-secondary); }
    .who { display: flex; align-items: center; gap: var(--jf-space-3); }
    .who p { margin: 0; }
    .avatar { flex: none; display: grid; place-items: center; inline-size: var(--frame-control-size); block-size: var(--frame-control-size);
      border-radius: var(--frame-control-radius); background: var(--surface-2); color: var(--text-tint); }
    .lines { list-style: none; margin: 0; padding: 0; }
    .lines li { display: flex; gap: var(--jf-space-3); padding-block: var(--jf-space-2); border-top: var(--frame-hairline) solid var(--border-subtle); }
    .lines li:last-child { border-bottom: var(--frame-hairline) solid var(--border-subtle); }
    .lines .path { font-family: ui-monospace, monospace; white-space: pre-wrap; }
    .line-icon { flex: none; inline-size: var(--jf-space-4); text-align: center; color: var(--text-secondary); }
    .reason blockquote { margin: var(--jf-space-1) 0; padding: var(--jf-space-3); border: var(--frame-hairline) solid var(--border-subtle);
      border-radius: var(--frame-control-radius); background: var(--surface-2); white-space: pre-wrap; }
    .timeout { display: flex; align-items: center; gap: var(--jf-space-2); margin: 0; padding: var(--jf-space-3);
      border: var(--frame-hairline) solid var(--accent-warning); border-radius: var(--frame-control-radius); background: var(--accent-warning-weak); }
    .timeout > span:nth-child(2) { flex: 1; }
    .timeout progress { inline-size: 25%; margin: 0; accent-color: var(--accent-warning); }
    .note { color: var(--text-secondary); font-size: var(--font-size-xs); margin: 0; }
    .decide { display: grid; grid-template-columns: 1fr 1fr; gap: var(--jf-space-3); }
    .decide button { display: flex; justify-content: space-between; align-items: center; gap: var(--jf-space-2);
      padding: var(--jf-space-3); border: var(--frame-hairline) solid var(--border-emphasized); border-radius: var(--frame-control-radius); }
    .decide button.allow { background: var(--accent-tint); color: var(--accent-on-tint); border-color: transparent; }
    .decide button:hover { text-decoration: none; }
    .decide button[aria-disabled="true"] { opacity: 0.6; cursor: not-allowed; }
    kbd { font: inherit; font-size: var(--font-size-xs); padding-inline: var(--jf-space-1); border: var(--frame-hairline) solid currentColor;
      border-radius: var(--frame-control-radius); }
    .keys { display: flex; flex-wrap: wrap; gap: var(--jf-space-3); color: var(--text-secondary); font-size: var(--font-size-xs); margin: 0; }
    @media (forced-colors: active) {
      :host::before { background: Canvas; border-color: CanvasText; }
      .panel { background: Canvas; color: CanvasText; border-color: CanvasText; box-shadow: none; }
      button { color: ButtonText; } .detail, .deadline, time, h3, .state-icon[data-tone] { color: CanvasText; }
      .actions button, .footer button, .unread { color: LinkText; }
      button:focus-visible { outline-color: Highlight; }
      .actions button.primary, .decide button { color: ButtonText; background: ButtonFace; border: var(--frame-hairline) solid ButtonText; }
      .decide button.allow { color: ButtonFace; background: ButtonText; forced-color-adjust: none; }
      .timeout, .reason blockquote, .avatar { background: Canvas; color: CanvasText; border-color: CanvasText; }
      .error, .note, .keys, .line-icon { color: CanvasText; }
    }
  `;
  private readonly pluginOutputs = new WeakMap<StatusBarItem, HTMLElement | string>();
  private focusSummary = false;
  private focusedRow: { id: string | null; section: string | null } | null = null;
  protected override willUpdate(changed: PropertyValues): void {
    if (changed.has('model') && this.reviewing && !this.reviewed()) {
      // Answered elsewhere, withdrawn or expired while open: it cannot be answered here any more.
      this.gone = this.answered !== this.reviewing; this.reviewing = null; this.focusSummary = true;
    }
    if (!changed.has('model') || !this.focusedRow) return;
    const row = Object.values(this.model?.sections ?? {}).flat().find(r => r.id === this.focusedRow!.id);
    this.focusSummary = !row || SECTION_NAMES[row.section] !== this.focusedRow.section;
  }
  protected override updated(): void {
    this.placeControls();
    for (const body of this.renderRoot.querySelectorAll<HTMLElement>('.plugin-body')) this.titlePlugin(body);
    if (this.focusSummary) {
      this.focusSummary = false;
      this.shadowRoot?.querySelector<HTMLElement>('[role="dialog"]')?.focus({ preventScroll: true });
    }
  }
  private static where(control: Element): string {
    const box = control.getBoundingClientRect();
    return `${control.getAttribute('data-decision')}@${[box.left, box.top, box.width, box.height].map(Math.round).join(',')}`;
  }
  /** Note every decision control that appeared, moved or changed request since it was last seen. */
  placeControls(): void {
    const now = performance.now();
    for (const control of this.renderRoot.querySelectorAll('[data-decision]')) {
      const where = StatusPanel.where(control);
      if (this.placed.get(control)?.where !== where) this.placed.set(control, { where, at: now });
    }
  }
  private controlArmed(control: Element, at = performance.now()): boolean {
    const placed = this.placed.get(control);
    return !!placed && at - placed.at >= ARMING_DELAY_MS;
  }
  private static decision(e: Event): HTMLElement | null {
    return e.composedPath().find((node): node is HTMLElement => node instanceof HTMLElement && node.matches('[data-decision]')) ?? null;
  }
  /**
   * One deliberate interaction answers one request, as in the security dialog: a pointer click on a
   * decision control completes a press that started on that same control after it armed; the keyboard
   * answers on the release of an armed, unrepeated Enter or Space (the native Enter-on-keydown, a held
   * key's auto-repeat and a release whose press began elsewhere answer nothing). Other activations
   * (assistive technology's default action) wait for the control to arm.
   */
  private readonly onDecisionPointerDown = (e: PointerEvent): void => {
    this.placeControls();
    this.controlPress = { at: performance.now(), control: StatusPanel.decision(e) };
  };
  private readonly onDecisionClick = (e: MouseEvent): void => {
    const control = StatusPanel.decision(e);
    if (!control) return;
    this.placeControls();
    const press = this.controlPress;
    if (e.detail > 0) this.controlPress = null;
    if (this.controlArmed(control) && (e.detail === 0 || (press?.control === control && this.controlArmed(control, press.at)))) return;
    e.preventDefault();
    e.stopPropagation();
  };
  private readonly onDecisionKey = (e: KeyboardEvent): void => {
    const control = StatusPanel.decision(e);
    if (!control || (e.key !== 'Enter' && e.key !== ' ')) return;
    e.preventDefault();
    const pending = this.keyPress;
    this.keyPress = null;
    if (e.type === 'keydown') {
      this.placeControls();
      if (!e.repeat && this.controlArmed(control)) this.keyPress = { key: e.key, control };
    } else if (pending?.key === e.key && pending.control === control && this.controlArmed(control)) control.click();
  };
  /**
   * L6 — a clipped plugin item keeps its full words as its tooltip: what it shows, read through its own
   * shadow root and slots (rendered later than this panel, so read again when pointed at), or else its
   * declared label.
   */
  private titlePlugin(body: HTMLElement): void {
    const item = this.plugins.find(row => row.id === body.parentElement?.dataset.pluginItem);
    const output = item && this.pluginOutputs.get(item);
    if (!item || output === undefined) return;
    const words = (typeof output === 'string' ? output : shownText(output)).replace(/\s+/g, ' ').trim();
    body.title = words || item.accessibleLabel || '';
  }
  private pluginOutput(item: StatusBarItem): HTMLElement | string {
    if (!this.pluginOutputs.has(item)) this.pluginOutputs.set(item, item.render());
    return this.pluginOutputs.get(item)!;
  }
  private action(action: StatusAction, row?: StatusFact): void {
    if (action.kind === 'review' && row?.review) { this.openReview(row); return; }
    this.dispatchEvent(new CustomEvent<StatusAction>('status-action', { detail: action, bubbles: true, composed: true }));
  }
  private reviewed(): StatusFact | undefined {
    return this.model?.sections.needs.find(row => row.id === this.reviewing && row.review);
  }
  /** Open a row's review in place of the list; the activation that opened it cannot answer it. */
  openReview(row: StatusFact): void {
    this.reviewing = row.id; this.always = false; this.gone = false;
    this.reviewOpenedAt = performance.now(); this.press = null;
    if (row.review?.open) this.action(row.review.open);
    void this.updateComplete.then(() => this.shadowRoot?.querySelector<HTMLElement>('.review h2')?.focus());
  }
  /** Bring one row into view and put focus on it (a clicked desktop notification). */
  revealRow(id: string): void {
    void this.updateComplete.then(() => {
      const row = this.shadowRoot?.querySelector<HTMLElement>(`article[data-fact="${CSS.escape(id)}"]`);
      row?.scrollIntoView({ block: 'nearest' });
      row?.querySelector<HTMLElement>('button')?.focus();
    });
  }
  /** Back to the list; the request keeps waiting. True when a review was open (Escape uses this). */
  back(): boolean {
    const id = this.reviewing;
    if (!id) return false;
    this.reviewing = null;
    void this.updateComplete.then(() => this.shadowRoot?.querySelector<HTMLElement>(`article[data-fact="${CSS.escape(id)}"] button`)?.focus());
    return true;
  }
  private armed(review: StatusReview, at = performance.now()): boolean {
    return at - Math.max(review.readyAt, this.reviewOpenedAt) >= ARMING_DELAY_MS;
  }
  /** Answer the open review once: never before it arms, while busy, or (Allow) when it cannot be allowed. */
  private answer(review: StatusReview, allow: boolean): void {
    if (!this.armed(review) || review.busy || review.typed || (allow && review.checking)) return;
    this.answered = this.reviewing;
    this.action(allow ? this.always && review.allowAlways ? review.allowAlways : review.allow : review.deny);
  }
  private readonly onReviewPointerDown = (e: PointerEvent): void => {
    this.press = { at: performance.now(), button: e.composedPath().find((n): n is Element => n instanceof Element && n.matches('.decide button, label.always')) ?? null };
  };
  /** One deliberate pointer click per answer: the press must start on the same control after arming. */
  private deliberate(e: MouseEvent, review: StatusReview): boolean {
    const control = e.currentTarget as Element;
    const press = this.press;
    if (e.detail > 0) this.press = null;
    return this.armed(review) && (e.detail === 0 || (press?.button === control && this.armed(review, press.at)));
  }
  /**
   * C8 — keys 1 (allow) and 2 (deny) answer the open review only from inside the panel, never from a
   * text field, during IME composition, on key repeat, with a modifier, or while another modal owns
   * the keyboard.
   */
  private readonly onReviewKey = (e: KeyboardEvent): void => {
    const review = this.reviewed()?.review;
    if (!review || (e.key !== '1' && e.key !== '2') || e.defaultPrevented || shouldIgnoreKeyEvent(e) || e.keyCode === 229
      || e.ctrlKey || e.metaKey || e.altKey || modalOwnsFocus()) return;
    const origin = e.composedPath()[0];
    if (origin instanceof Element && isTypingTarget(origin)) return;
    e.preventDefault();
    this.answer(review, e.key === '1');
  };
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
      ${row.error ? html`<p class="error" role="alert">${row.error}</p>` : nothing}
      ${row.indeterminate && !row.stale ? html`<progress aria-label=${row.title}></progress>` : nothing}
      ${row.percent != null && !row.stale ? html`<progress aria-label=${row.title} max="100" value=${row.percent}></progress>` : nothing}
      ${row.indexing && this.model ? html`<jf-indexing-controls .progress=${this.model.progress}
        .allowPause=${row.section === 'running'} api-base=${this.apiBase}></jf-indexing-controls>` : nothing}
      <div class="actions">
        ${(row.actions ?? []).map(action => html`<button type="button" class=${action.kind === 'authorization-allow' ? 'primary' : nothing}
          data-decision=${DECISIONS.has(action.kind) ? decisionId(action) : nothing} @click=${() => this.action(action, row)}>${action.label}</button>`)}
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
        @pointerenter=${(e: Event) => this.titlePlugin(e.currentTarget as HTMLElement)}>${output}</span><jf-provenance-chip .provenance=${item.provenance}></jf-provenance-chip></span>`; })}
      ${hidden > 0 || this.pluginsOpen && this.overflow.visibleCount < this.plugins.length ? html`<button type="button" aria-expanded=${this.pluginsOpen}
        @click=${() => { this.pluginsOpen = !this.pluginsOpen; }}>${this.pluginsOpen ? 'Fewer' : `… ${hidden} more`}</button>` : nothing}</div>`;
  }
  /** ref-10 — who asked, the question, consequences, its own words, the deadline, and the answers. */
  private renderReview(row: StatusFact, review: StatusReview): TemplateResult {
    const disabled = (allow: boolean) => review.busy || (allow && review.checking);
    const decide = (allow: boolean) => (e: MouseEvent) => { if (this.deliberate(e, review)) this.answer(review, allow); };
    return html`<div class="review" data-testid="status-review" data-fact=${row.id} @keydown=${this.onReviewKey}
      @pointerdown=${{ handleEvent: this.onReviewPointerDown, capture: true }}>
      <button type="button" class="back" @click=${() => this.back()}><span aria-hidden="true">‹</span> What's going on</button>
      <div class="who"><span class="avatar" aria-hidden="true">◇</span><div><p class="who-name">${review.who}</p>
        <p class="detail">${review.whoDetail}</p></div></div>
      <h2 tabindex="-1">${review.question}</h2>
      ${review.lines.length ? html`<ul class="lines" aria-label="What it does">${review.lines.map(line => html`<li data-line=${line.icon}>
        <span class="line-icon" aria-hidden="true">${LINE_ICONS[line.icon]}</span><span class=${line.path ? 'path' : nothing}>${line.text}</span></li>`)}</ul>` : nothing}
      ${review.reason ? html`<div class="reason" data-reason><h3>Its reason</h3><blockquote>${review.reason}</blockquote>
        <p class="detail">Written by ${review.reasonBy ?? review.who}, not checked by JustSearch</p></div>` : nothing}
      <p class="timeout" data-deadline><span aria-hidden="true">◷</span><span>${review.timeout}</span>${review.remaining == null ? nothing
        : html`<progress max="100" value=${Math.round(review.remaining * 100)} aria-label="Time left to answer"></progress>`}</p>
      ${review.allowAlways ? html`<label class="always"><input type="checkbox" .checked=${this.always}
          @click=${(e: MouseEvent) => { if (!this.deliberate(e, review)) e.preventDefault(); }}
          @change=${(e: Event) => { this.always = (e.target as HTMLInputElement).checked; }}> ${review.allowAlways.label}</label>`
        : html`<p class="note"><span aria-hidden="true">ⓘ</span> ${review.askedEveryTime}</p>`}
      ${review.error ? html`<p class="error" role="alert">${review.error}</p>` : nothing}
      ${review.typed ? html`<div class="actions"><button type="button" class="primary"
          @click=${() => this.action({ kind: 'authorization', target: review.allow.target, label: 'Review' })}>Continue in the security check</button></div>`
        : html`<div class="decide">
        <button type="button" class="allow" data-answer="allow" data-decision=${decisionId(review.allow)} aria-keyshortcuts="1" aria-disabled=${disabled(true) ? 'true' : 'false'}
          @click=${decide(true)}>${review.allow.label}<kbd aria-hidden="true">1</kbd></button>
        <button type="button" class="deny" data-answer="deny" data-decision=${decisionId(review.deny)} aria-keyshortcuts="2" aria-disabled=${disabled(false) ? 'true' : 'false'}
          @click=${decide(false)}>${review.deny.label}<kbd aria-hidden="true">2</kbd></button></div>
      <p class="keys" aria-hidden="true"><span><kbd>1</kbd> allow</span><span><kbd>2</kbd> deny</span><span><kbd>Esc</kbd> back, still waiting</span></p>`}
    </div>`;
  }
  private renderNoticeGone(): TemplateResult | typeof nothing {
    return this.noticeGone ? html`<p class="detail" role="status" data-testid="status-notice-gone">What that notification was about is no longer here.</p>` : nothing;
  }
  // B5 — the header and footer rows are plain containers: page banner/contentinfo landmarks belong
  // to the app frame, never to a popover.
  override render(): TemplateResult {
    const model = this.model;
    const reviewed = this.reviewed();
    if (reviewed?.review) {
      return html`<div class="panel" data-testid="status-panel" role="dialog" aria-label="What's going on" tabindex="-1"
        @pointerdown=${{ handleEvent: this.onDecisionPointerDown, capture: true }} @click=${{ handleEvent: this.onDecisionClick, capture: true }}
        @keydown=${{ handleEvent: this.onDecisionKey, capture: true }} @keyup=${{ handleEvent: this.onDecisionKey, capture: true }}
        @scroll=${() => this.placeControls()}>
        ${this.renderNoticeGone()}${this.renderReview(reviewed, reviewed.review)}</div>`;
    }
    return html`<div class="panel" data-testid="status-panel" role="dialog" aria-label="What's going on" tabindex="-1"
      @pointerdown=${{ handleEvent: this.onDecisionPointerDown, capture: true }} @click=${{ handleEvent: this.onDecisionClick, capture: true }}
      @keydown=${{ handleEvent: this.onDecisionKey, capture: true }} @keyup=${{ handleEvent: this.onDecisionKey, capture: true }}
      @scroll=${() => this.placeControls()} @focusin=${(event: FocusEvent) => {
        const row = event.composedPath().find(node => node instanceof HTMLElement && node.matches('article')) as HTMLElement | undefined;
        this.focusedRow = row ? { id: row.getAttribute('data-fact'), section: row.closest('section')?.getAttribute('aria-label') ?? null } : null;
      }} @focusout=${(event: FocusEvent) => {
        if (!(event.relatedTarget instanceof Node) || !this.shadowRoot?.contains(event.relatedTarget)) this.focusedRow = null;
      }}>
      <div class="header"><div><h2>What's going on</h2><p class="detail">${model ? summary(model) : 'Checking search status'}</p>
        ${this.gone ? html`<p class="detail" role="status">The request you were reviewing is no longer waiting.</p>` : nothing}
        ${this.renderNoticeGone()}</div>
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
      <div class="footer"><button type="button" @click=${() => this.action({ kind: 'all-activity', target: 'core.activity-surface', label: 'All activity' })}>All activity</button>
        <button type="button" @click=${() => this.action({ kind: 'navigate', target: 'core.health-surface', label: 'Health' })}>Health</button></div>
    </div>`;
  }
}
if (!customElements.get('jf-status-panel')) customElements.define('jf-status-panel', StatusPanel);
