// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 490 §4.D — Inbox drawer rendering persisted advisories with
 * read/unread state. Opens on demand (via {@link AdvisoryRailBadge}
 * dispatching `advisory-toggle-inbox`); closes on outside click or
 * Esc.
 *
 * Read state is persisted in {@link UserStateDocument} via the
 * {@link AdvisoryStore.acknowledge} entry point.
 */

import { css, html, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import '../Button.js';
import '../StatusBadge.js';
import '../FilterChip.js';
import { present } from '../../display/present.js';
import { unavailableBecause } from '../../state/availability.js';
// Tempdoc 586 P-1c — shared relative-time formatter (replaces verbose toLocaleTimeString()).
import { formatRelativeIso } from '../../../utils/relativeTime.js';
// Tempdoc 565 §17 — the ONE status → glyph authority (so the outcome icon is not a hand-rolled glyph).
import { statusGlyph, glyphChar } from '../../utils/statusTone.js';
// Tempdoc 565 §7.3 — the single right-drawer arbiter (only one right-drawer open at a time).
import { TransientController } from '../../primitives/transientController.js';
import {
  AdvisoryStore,
  type AdvisoryRecord,
  type AdvisorySnapshot,
} from './AdvisoryStore.js';
import {
  advisoryClassChrome,
  healthAdvisoryReasonBody,
  type AdvisoryClassChromeEntry,
} from './AdvisoryClassChrome.js';
import '../DispatchSource.js';
import { transportChrome } from '../TransportChrome.js';
import { requestAuthorization } from '../../operations/authorizationBroker.js';
import type { PropertyValues } from 'lit';

interface FilterState {
  classes: string[];
  transports: string[];
  outcomes: string[];
  unreadOnly: boolean;
}

interface ChipOption {
  value: string;
  icon: string;
  label: string;
  count: number;
}

function emptyFilter(): FilterState {
  return { classes: [], transports: [], outcomes: [], unreadOnly: false };
}

function deriveChipOptions(advisories: readonly AdvisoryRecord[]): {
  classes: ChipOption[];
  transports: ChipOption[];
  outcomes: ChipOption[];
} {
  const classCounts = new Map<string, number>();
  const transportCounts = new Map<string, number>();
  const outcomeCounts = new Map<string, number>();
  for (const r of advisories) {
    classCounts.set(r.event.classId, (classCounts.get(r.event.classId) ?? 0) + 1);
    const t = r.event.provenance?.transport;
    if (t) transportCounts.set(t, (transportCounts.get(t) ?? 0) + 1);
    const o = r.event.classExtras?.outcome;
    if (typeof o === 'string') outcomeCounts.set(o, (outcomeCounts.get(o) ?? 0) + 1);
  }
  const toChips = (
    counts: Map<string, number>,
    chromeFn: (v: string) => { icon: string; label: string },
  ): ChipOption[] =>
    [...counts.entries()].map(([value, count]) => {
      const c = chromeFn(value);
      return { value, icon: c.icon, label: c.label, count };
    });
  return {
    classes: toChips(classCounts, (v) => advisoryClassChrome(v)),
    transports: toChips(transportCounts, (v) => transportChrome(v)),
    outcomes: toChips(outcomeCounts, (v) => ({
      // Tempdoc 565 §17 — the outcome icon reads the ONE status→glyph authority, not a hand-rolled
      // `'✓' : '✕'` ternary (a second status-glyph site the §17 run-step authority subsumes).
      icon: glyphChar(statusGlyph(v === 'SUCCESS' ? 'success' : 'error')),
      label: v.charAt(0) + v.slice(1).toLowerCase(),
    })),
  };
}

function matchesFilters(r: AdvisoryRecord, f: FilterState): boolean {
  if (f.unreadOnly && r.acknowledged) return false;
  if (f.classes.length > 0 && !f.classes.includes(r.event.classId)) return false;
  if (f.transports.length > 0) {
    const t = r.event.provenance?.transport;
    if (!t || !f.transports.includes(t)) return false;
  }
  if (f.outcomes.length > 0) {
    const o = r.event.classExtras?.outcome;
    if (typeof o !== 'string' || !f.outcomes.includes(o)) return false;
  }
  return true;
}

export class AdvisoryInboxDrawer extends JfElement {
  static properties = {
    store: { attribute: false },
    operationClient: { attribute: false },
    open: { type: Boolean, reflect: true },
    advisories: { state: true },
    unreadCount: { state: true },
    expandedKey: { state: true },
    filterState: { state: true },
  };

  declare store: AdvisoryStore | null;
  declare operationClient: import('../../operations/OperationClient.js').OperationClient | null;
  declare open: boolean;
  declare advisories: readonly AdvisoryRecord[];
  declare unreadCount: number;
  declare expandedKey: string | null;
  declare filterState: FilterState;

  private storeUnsubscribe: (() => void) | null = null;
  /** 574 §23.B — single-open arbitration + outside-click/Esc dismiss BY CONSTRUCTION. The rail badge that
   *  opens this drawer lives outside the host, so it is excluded from dismiss (else its click re-closes). */
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'advisory',
    managesDismiss: true,
    dismissExclude: (path) =>
      path.some((t) => (t as HTMLElement)?.tagName?.toLowerCase?.() === 'jf-advisory-rail-badge'),
    close: () => {
      this.open = false;
    },
  });

  static styles = css`
    :host {
      /* 559 Authority I: docked in the OverlayHost right-drawer slot (full-height
         right edge); the slide is the drawer's own transform. */
      width: 22rem;
      max-width: 90vw;
      transform: translateX(100%);
      transition: transform var(--duration-normal) var(--ease-decelerate);
      background: var(--surface-1);
      border-left: 1px solid var(--border-subtle);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.4);
      display: flex;
      flex-direction: column;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
    }
    /* Tempdoc 565 §7.3 — display:none when closed (the convention SourcesPane /
       RetrospectivePanel / AgentActivityPanel already follow). Without this the
       closed drawer keeps a full-height flex slot in the shared right-drawer
       column and overlaps the open pane (the logged latent bug). */
    :host(:not([open])) {
      display: none;
    }
    :host([open]) {
      transform: translateX(0);
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    h2 {
      margin: 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
    }
    .actions {
      display: flex;
      gap: 0.5rem;
    }
    button {
      padding: 0.25rem 0.5rem;
      background: transparent;
      color: var(--text-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      cursor: pointer;
      font-size: var(--font-size-xs);
      transition: color var(--duration-fast), background var(--duration-fast);
    }
    button:hover {
      background: var(--surface-secondary);
      color: var(--text-primary);
    }
    button:disabled {
      opacity: 0.5;
      cursor: default;
    }
    .list {
      flex: 1;
      overflow-y: auto;
      display: flex;
      flex-direction: column;
    }
    .empty {
      padding: 2rem 1rem;
      color: var(--text-secondary);
      text-align: center;
      font-size: var(--font-size-sm);
    }
    .item {
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      cursor: pointer;
      transition: background var(--duration-fast);
    }
    .item:hover {
      background: var(--surface-secondary);
    }
    .item.unread {
      background: var(--accent-tint-08);
      border-left: 2px solid var(--accent);
    }
    .item.unread:hover {
      background: var(--accent-tint-08);
    }
    .item-title {
      font-weight: 600;
      margin-bottom: 0.25rem;
    }
    .item-meta {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .item-diagnostics {
      margin-top: 0.375rem;
      font-size: var(--font-size-xs);
    }
    .item-diagnostics a {
      color: var(--accent);
      text-decoration: none;
    }
    .item-diagnostics a:hover {
      text-decoration: underline;
    }
    .item:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: -2px;
    }
    .item-detail {
      margin-top: 0.5rem;
      padding-top: 0.5rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .item-detail .action-btn {
      display: inline-block;
      margin-top: 0.375rem;
      padding: 0.25rem 0.625rem;
      font-size: var(--font-size-xs);
      font-weight: 600;
      border: 1px solid var(--accent);
      border-radius: 0.25rem;
      background: transparent;
      color: var(--accent);
      cursor: pointer;
    }
    .item-detail .action-btn:hover {
      background: var(--accent);
      color: var(--surface-1);
    }
    .item-detail .action-btn.success {
      border-color: var(--accent-success);
      color: var(--text-success);
    }
    .item-detail .action-btn.failed {
      border-color: var(--accent-warning);
      color: var(--text-warning);
    }
    .item-detail .extras {
      margin-top: 0.375rem;
    }
    .item-detail .extras dt {
      font-weight: 600;
      display: inline;
    }
    .item-detail .extras dd {
      display: inline;
      margin: 0;
      margin-right: 0.75rem;
    }
    .filter-bar {
      padding: 0.5rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      display: flex;
      flex-wrap: wrap;
      gap: 0.375rem;
      align-items: center;
    }
    .filter-bar:empty {
      display: none;
    }
    .chip-count {
      opacity: 0.7;
      font-size: var(--font-size-xs);
    }
    .chip-sep {
      width: 1px;
      height: 1rem;
      background: var(--border-subtle);
      margin: 0 0.125rem;
    }
    .filter-status {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      padding: 0 0.5rem 0.375rem;
    }
  `;

  constructor() {
    super();
    this.store = null;
    this.operationClient = null;
    this.open = false;
    this.advisories = [];
    this.unreadCount = 0;
    this.expandedKey = null;
    this.filterState = emptyFilter();
  }

  override willUpdate(changed: PropertyValues): void {
    if (changed.has('open')) {
      if (this.open) {
        // §7.3 / 574 §23.B — opening drives the controller: single-open + dismiss by construction.
        this.transient.open();
      } else {
        this.filterState = emptyFilter();
        this.expandedKey = null;
        this.transient.close();
      }
    }
  }

  override connectedCallback(): void {
    super.connectedCallback();
    if (this.store) {
      this.storeUnsubscribe = this.store.subscribe((s: AdvisorySnapshot) => {
        // Slice 490 substrate-completion (P2.3) — drawer renders only PERSISTED
        // and REQUIRES_ACK records. EPHEMERAL is toast-only (per RenderHint
        // contract); the inbox would be misleading. Dispatch is per-event via
        // record.sourceRenderHint — multiple advisory classes with different
        // renderHints coexist cleanly. newest-first ordering preserved.
        const visible = s.advisories.filter(
          (r) => r.sourceRenderHint !== 'EPHEMERAL',
        );
        this.advisories = [...visible].reverse();
        this.unreadCount = visible.filter((r) => !r.acknowledged).length;
      });
    }
  }

  override disconnectedCallback(): void {
    if (this.storeUnsubscribe) {
      this.storeUnsubscribe();
      this.storeUnsubscribe = null;
    }
    super.disconnectedCallback();
  }

  toggle(): void {
    this.open = !this.open;
  }

  private handleItemClick(record: AdvisoryRecord): void {
    if (this.store && !record.acknowledged) this.store.acknowledge(record.key);
    this.expandedKey = this.expandedKey === record.key ? null : record.key;
  }

  private handleItemKeydown(e: KeyboardEvent, index: number): void {
    const items = this.shadowRoot?.querySelectorAll('.item');
    if (!items) return;
    if (e.key === 'ArrowDown' || e.key === 'j') {
      e.preventDefault();
      const next = items[index + 1] as HTMLElement | undefined;
      next?.focus();
    } else if (e.key === 'ArrowUp' || e.key === 'k') {
      e.preventDefault();
      const prev = items[index - 1] as HTMLElement | undefined;
      prev?.focus();
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      const record = this.advisories[index];
      if (record) this.handleItemClick(record);
    }
  }

  private async handleInlineAction(e: Event, record: AdvisoryRecord): Promise<void> {
    e.stopPropagation();
    const action = record.event.primaryAction;
    if (!action || !this.operationClient) return;
    const btn = e.currentTarget as HTMLButtonElement;
    const originalText = btn.textContent;
    btn.textContent = 'Running…';
    try {
      const args = action.defaultArgsJson
        ? JSON.parse(action.defaultArgsJson)
        : {};
      if (record.event.primaryActionKind === 'undo' && args.executionId) {
        // Tempdoc 875 §C.7 — the reversal meets the same trust lattice its forward
        // form did, so it takes the same consent path as the invoke branch below
        // (the shared `<jf-authorization-host>` ceremony via the broker).
        await this.operationClient.undoWithConsent(action.target, args.executionId, {
          transport: 'BUTTON',
          requestConsent: requestAuthorization,
        });
      } else {
        await this.operationClient.invoke(action.target, {
          args,
          transport: 'BUTTON',
        });
      }
      btn.classList.add('success');
      btn.textContent = '✓ Done';
      if (this.store && !record.acknowledged) this.store.acknowledge(record.key);
    } catch {
      btn.classList.add('failed');
      btn.textContent = '✕ Failed';
      setTimeout(() => {
        btn.classList.remove('failed');
        btn.textContent = originalText;
      }, 2000);
    }
  }

  private handleAckAll(): void {
    if (this.store) this.store.acknowledgeAll();
  }

  override render(): TemplateResult {
    const chips = deriveChipOptions(this.advisories);
    const filtered = this.advisories.filter((r) =>
      matchesFilters(r, this.filterState),
    );
    const isFiltered =
      this.filterState.classes.length > 0 ||
      this.filterState.transports.length > 0 ||
      this.filterState.outcomes.length > 0 ||
      this.filterState.unreadOnly;
    return html`
      <header role="presentation">
        <h2>Advisories${this.unreadCount > 0 ? ` (${this.unreadCount})` : ''}</h2>
        <div class="actions">
          <jf-button
            size="sm"
            label="Mark all read"
            .availability=${this.unreadCount === 0 ? unavailableBecause('No unread advisories') : undefined}
            .onActivate=${() => this.handleAckAll()}
          >
            Mark all read
          </jf-button>
          <jf-button variant="ghost" size="icon" label="Close" title="Close" .onActivate=${() => (this.open = false)}>
            ✕
          </jf-button>
        </div>
      </header>
      ${this.renderFilterBar(chips)}
      ${isFiltered
        ? html`<div class="filter-status">
            Showing ${filtered.length} of ${this.advisories.length}
          </div>`
        : nothing}
      <div class="list">
        ${filtered.length === 0
          ? html`<div class="empty">
              ${isFiltered ? 'No advisories match filters.' : 'No advisories yet.'}
            </div>`
          : filtered.map((r, i) => this.renderItem(r, i))}
      </div>
    `;
  }

  private renderFilterBar(chips: ReturnType<typeof deriveChipOptions>): TemplateResult | typeof nothing {
    const hasClassChips = chips.classes.length > 1;
    const hasTransportChips = chips.transports.length > 1;
    const hasOutcomeChips = chips.outcomes.length > 1;
    const hasAnyChip = hasClassChips || hasTransportChips || hasOutcomeChips || this.advisories.some((r) => !r.acknowledged);
    if (!hasAnyChip) return nothing;
    return html`
      <div class="filter-bar">
        ${this.advisories.some((r) => !r.acknowledged)
          ? html`
              <jf-filter-chip
                ?active=${this.filterState.unreadOnly}
                @click=${() => this.toggleUnreadOnly()}
                >Unread only</jf-filter-chip
              >
            `
          : nothing}
        ${hasClassChips || hasTransportChips || hasOutcomeChips
          ? html`<span class="chip-sep"></span>`
          : nothing}
        ${hasClassChips
          ? chips.classes.map(
              (c) => html`
                <jf-filter-chip
                  ?active=${this.filterState.classes.includes(c.value)}
                  @click=${() => this.toggleFilter('classes', c.value)}
                >
                  ${c.icon} ${c.label}
                  <span class="chip-count">(${c.count})</span>
                </jf-filter-chip>
              `,
            )
          : nothing}
        ${hasTransportChips
          ? chips.transports.map(
              (c) => html`
                <jf-filter-chip
                  ?active=${this.filterState.transports.includes(c.value)}
                  @click=${() => this.toggleFilter('transports', c.value)}
                >
                  ${c.icon} ${c.label}
                  <span class="chip-count">(${c.count})</span>
                </jf-filter-chip>
              `,
            )
          : nothing}
        ${hasOutcomeChips
          ? chips.outcomes.map(
              (c) => html`
                <jf-filter-chip
                  ?active=${this.filterState.outcomes.includes(c.value)}
                  @click=${() => this.toggleFilter('outcomes', c.value)}
                >
                  ${c.icon} ${c.label}
                  <span class="chip-count">(${c.count})</span>
                </jf-filter-chip>
              `,
            )
          : nothing}
      </div>
    `;
  }

  private toggleFilter(dimension: 'classes' | 'transports' | 'outcomes', value: string): void {
    const current = this.filterState[dimension];
    const next = current.includes(value)
      ? current.filter((v) => v !== value)
      : [...current, value];
    this.filterState = { ...this.filterState, [dimension]: next };
  }

  private toggleUnreadOnly(): void {
    this.filterState = { ...this.filterState, unreadOnly: !this.filterState.unreadOnly };
  }

  private renderItem(record: AdvisoryRecord, index: number): TemplateResult {
    const chrome = advisoryClassChrome(record.event.classId);
    const diag = record.event.diagnosticsLink;
    const extras = record.event.classExtras ?? {};
    const title = this.deriveTitle(record.event, chrome, extras);
    const isExpanded = this.expandedKey === record.key;
    const action = record.event.primaryAction;
    const prov = record.event.provenance;
    return html`
      <div
        class="item ${record.acknowledged ? '' : 'unread'}"
        tabindex="0"
        role="button"
        aria-expanded=${isExpanded}
        @click=${() => this.handleItemClick(record)}
        @keydown=${(e: KeyboardEvent) => this.handleItemKeydown(e, index)}
      >
        <div class="item-title">
          <jf-status-badge
            tone=${chrome.toneClass === 'success' ? 'success' : 'warning'}
            style="margin-right: 0.5rem"
            >${chrome.icon}</jf-status-badge
          >
          ${title}
        </div>
        <div class="item-meta">
          ${formatTime(record.event.occurredAt)}
          ${prov
            ? html` • <jf-dispatch-source .provenance=${prov}></jf-dispatch-source>`
            : this.deriveFallbackSubtitle(record.event, extras)
              ? html` • ${this.deriveFallbackSubtitle(record.event, extras)}`
              : nothing}
        </div>
        ${isExpanded
          ? html`
              <div class="item-detail">
                ${prov
                  ? html`<jf-dispatch-source .provenance=${prov} detailed></jf-dispatch-source>`
                  : nothing}
                ${/* Tempdoc 941 — the reason-specific authored sentence wins over the generic
                      `health-events.<id>.message` that `bodyI18nKey` always names. Same
                      resolution the toast body uses (one authority in AdvisoryClassChrome). */ ''}
                ${(() => {
                  const reasonBody = healthAdvisoryReasonBody(record.event.classId, extras);
                  if (reasonBody) return html`<div>${reasonBody}</div>`;
                  return record.event.bodyI18nKey
                    ? html`<div>
                        ${present({ kind: 'resource', key: record.event.bodyI18nKey }).label}
                      </div>`
                    : nothing;
                })()}
                ${diag
                  ? html`
                      <div class="item-diagnostics">
                        ${isUrl(diag)
                          ? html`<a
                              href=${diag}
                              target="_blank"
                              rel="noopener noreferrer"
                              @click=${(e: Event) => e.stopPropagation()}
                              >View diagnostics ↗</a
                            >`
                          : html`<span>Diagnostics: ${diag}</span>`}
                      </div>
                    `
                  : nothing}
                ${action
                  ? html`
                      <button
                        class="action-btn"
                        @click=${(e: Event) => this.handleInlineAction(e, record)}
                      >
                        ${record.event.primaryActionKind === 'undo'
                          ? 'Undo'
                          : (action.target.split('.').pop() ?? 'Fix')}
                      </button>
                    `
                  : nothing}
                ${Object.keys(extras).length > 0
                  ? html`
                      <dl class="extras">
                        ${Object.entries(extras).map(
                          ([k, v]) =>
                            html`<dt>${k}:</dt>
                              <dd>${String(v)}</dd>`,
                        )}
                      </dl>
                    `
                  : nothing}
              </div>
            `
          : diag
            ? html`
                <div class="item-diagnostics">
                  ${isUrl(diag)
                    ? html`<a
                        href=${diag}
                        target="_blank"
                        rel="noopener noreferrer"
                        @click=${(e: Event) => e.stopPropagation()}
                        >View diagnostics ↗</a
                      >`
                    : html`<span>Diagnostics: ${diag}</span>`}
                </div>
              `
            : nothing}
      </div>
    `;
  }

  private deriveTitle(
    event: import('./AdvisoryStore.js').AdvisoryEvent,
    chrome: AdvisoryClassChromeEntry,
    extras: Record<string, unknown>,
  ): string {
    // Tempdoc 559 Authority III/§9 (ADV-1): humanize the raw machine ids through
    // the one display authority (present already carries `operation`/`condition`
    // kinds, 557) so the advisory feed cannot leak a dotted key like
    // `schema.reindex-required` — the same projection Health "Fixable now" uses.
    if (event.classId === 'operation.completed') {
      const opId = typeof extras.operationId === 'string' ? extras.operationId : '';
      const opLabel = opId ? present({ kind: 'operation', id: opId }).label : event.classId;
      return `${extras.outcome ?? ''} ${opLabel}`.trim();
    }
    if (event.classId === 'health.recoverable') {
      const condId = typeof extras.conditionId === 'string' ? extras.conditionId : '';
      const condLabel = condId
        ? present({ kind: 'condition', id: condId }).label
        : 'Recoverable condition';
      const subject = typeof extras.subject === 'string' && extras.subject ? extras.subject : '';
      return subject ? `${condLabel} (${subject})` : condLabel;
    }
    return chrome.label;
  }

  private deriveFallbackSubtitle(
    event: import('./AdvisoryStore.js').AdvisoryEvent,
    extras: Record<string, unknown>,
  ): string | null {
    if (event.classId === 'health.recoverable' && extras.reason) {
      // Tempdoc 613 — never surface a RAW reason token (e.g. `ReindexRequired`) at the user
      // altitude. The title is already humanized via present(); the reason is worded here through
      // the same no-raw-leak discipline so the advisory feed cannot show a machine code. Splits
      // camelCase + dotted/dashed/underscored tokens, then Title-Cases.
      return humanizeReasonToken(String(extras.reason));
    }
    return null;
  }
}

/**
 * Slice 490 Pass-8 follow-up — heuristic url-vs-i18n-key discrimination for the
 * advisory's {@code diagnosticsLink}. The backend documents this field as "URL or
 * i18n key" (mirrors {@code OperationHistoryEntry.diagnosticsLink}); the FE
 * renders as a link only for absolute URLs to avoid surfacing raw i18n keys.
 */
function isUrl(s: string): boolean {
  return /^https?:\/\//i.test(s);
}

function formatTime(iso: string): string {
  // Tempdoc 586 P-1c — friendly relative time ("3m ago") via the shared formatter rather than
  // the raw toLocaleTimeString() ("19:35:18 GMT+0200 (Central European Summer Time)"). The
  // formatter returns '' for empty/invalid input, so the raw iso is kept as the fallback.
  return formatRelativeIso(iso) || iso;
}

/**
 * Tempdoc 613 — humanize a raw machine reason token (e.g. `ReindexRequired`,
 * `embedding_not_ready`, `index.schema_mismatch`) into Title-Cased words so the advisory feed
 * never shows a raw code at the user altitude. Splits camelCase boundaries first, then dotted /
 * dashed / underscored / whitespace separators. Returns null for an empty result so the caller
 * renders no subtitle rather than an empty bullet.
 */
function humanizeReasonToken(raw: string): string | null {
  const words = raw
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[.\-_\s]+/)
    .filter((s) => s.length > 0)
    .map((s) => s.charAt(0).toUpperCase() + s.slice(1).toLowerCase());
  return words.length > 0 ? words.join(' ') : null;
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-advisory-inbox-drawer')
) {
  customElements.define('jf-advisory-inbox-drawer', AdvisoryInboxDrawer);
}
