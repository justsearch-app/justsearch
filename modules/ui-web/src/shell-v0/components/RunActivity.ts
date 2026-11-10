// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-run-activity> — what agent runs did and are doing, in Activity (design system §1: "History →
 * System › Activity"). Background runs: the cross-conversation list (`/api/presence`) and the
 * run-in-background launcher. This run: what the agent did in the run open right now
 * (`/api/action-ledger` by correlation id). Both read the ONE shared `AgentSessionController`, so this
 * is a consumer, not a second authority. Machine-wide history is the ledger beside it in Activity.
 *
 * A failed read keeps the rows it had and says so; empty only ever means the backend said empty.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import './StatusBadge.js';
import { getAgentSessionController, subscribeAgentSession } from '../state/agentSessionStore.js';
import { runListNotice, type RunListLoad } from '../controllers/runListLoad.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { activateOnKey } from '../utils/keyboardHandler.js';
import { renderEventRow, eventRowStyles } from './eventRow.js';
import type { PresenceRun } from '../controllers/AgentSessionController.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

const SEARCH_WINDOW = 'core.search-v3-surface';

/**
 * Tempdoc 565 §33 — the REAL lifecycle vocabulary the backend emits: `PresenceRun.state` =
 * `LifecycleState.name()`. The two transient running states merge into one group.
 */
const STATUS: Record<string, { bucket: string; rank: number; label: string }> = {
  READY_FOR_LLM: { bucket: 'running', rank: 0, label: 'Running' },
  AFTER_TOOL_RESULT: { bucket: 'running', rank: 0, label: 'Running' },
  WAITING_APPROVAL: { bucket: 'needs-approval', rank: 1, label: 'Needs approval' },
  // Tempdoc 577 Move 2 — the run parked at the budget gate (held, non-terminal).
  WAITING_BUDGET: { bucket: 'needs-budget', rank: 1, label: 'Awaiting budget' },
  DONE: { bucket: 'done', rank: 2, label: 'Done' },
  ERROR: { bucket: 'failed', rank: 3, label: 'Failed' },
};

function humanize(state: string): string {
  return (state || 'Unknown').toLowerCase().split(/[\s_]+/).map(word => (word ? word[0]!.toUpperCase() + word.slice(1) : word)).join(' ');
}

function classify(state: string): { bucket: string; rank: number; label: string } {
  return STATUS[state] ?? { bucket: (state || 'unknown').toLowerCase().replace(/[\s_]+/g, '-'), rank: 9, label: humanize(state) };
}

export class RunActivity extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
    draft: { state: true },
  };

  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  /** Tempdoc 565 §26.D — the run-in-background launcher's text. */
  declare private draft: string;

  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.draft = '';
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeAgentSession(() => this.requestUpdate());
    void this.ctrl().loadPresence();
    void this.ctrl().loadHistory();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  private ctrl() {
    return getAgentSessionController(this.apiBase, this.host_);
  }

  /** Put the reader on Background runs; Activity calls this when a pointer brought them here. */
  revealBackgroundRuns(): void {
    const section = this.renderRoot.querySelector<HTMLElement>('[data-testid="activity-background-runs"]');
    section?.scrollIntoView?.({ block: 'start' });
    section?.focus();
  }

  /** Tempdoc 585 §D Phase 1 (D1) — a finished run opens in the replay inspector, which is in Search. */
  private replay(sessionId: string): void {
    void this.ctrl().loadReplay(sessionId);
    requestSurfaceNavigation(SEARCH_WINDOW);
  }

  private submit(): void {
    const prompt = this.draft.trim();
    if (!prompt) return;
    void this.ctrl().runBackgroundTask(prompt);
    this.draft = '';
  }

  private notice(load: RunListLoad, noun: string, retry: () => void): TemplateResult | typeof nothing {
    const words = runListNotice(load, noun);
    return words === null ? nothing : html`<p class="notice" role="status" data-testid="run-activity-failed">
      ${words} <jf-button size="sm" variant="ghost" label="Try again" .onActivate=${retry}>Try again</jf-button></p>`;
  }

  private backgroundRow(run: PresenceRun): TemplateResult {
    // Tempdoc 585 §D Phase 1 (D1): a finished run is click-through to replay; a running one is not.
    const terminal = run.state === 'DONE' || run.state === 'ERROR';
    const body = html`<div>
      <div class="run-title">Background run — ${run.toolCalls} tool${run.toolCalls === 1 ? '' : 's'},
        ${run.iterations} iteration${run.iterations === 1 ? '' : 's'}</div>
      <div class="meta"><jf-status-badge status=${run.state}>${classify(run.state).label}</jf-status-badge></div>
    </div>`;
    return terminal
      ? html`<div class="run clickable" role="button" tabindex="0" title="Replay this background run"
          @click=${() => this.replay(run.sessionId)}
          @keydown=${(event: KeyboardEvent) => activateOnKey(event, () => this.replay(run.sessionId))}
          >${body}<span class="replay">Replay</span></div>`
      : html`<div class="run">${body}</div>`;
  }

  private backgroundRuns(): TemplateResult {
    const ctrl = this.ctrl();
    const runs = ctrl.presence;
    const groups = new Map<string, { rank: number; label: string; runs: PresenceRun[] }>();
    for (const run of runs) {
      const status = classify(run.state);
      const group = groups.get(status.bucket);
      if (group) group.runs.push(run);
      else groups.set(status.bucket, { rank: status.rank, label: status.label, runs: [run] });
    }
    const ordered = [...groups.entries()].sort((a, b) => a[1].rank - b[1].rank || a[0].localeCompare(b[0]));
    return html`<section aria-label="Background runs" data-testid="activity-background-runs" tabindex="-1">
      <div class="section-label">Background runs
        <span class="count" data-testid="background-runs-count">${ctrl.presenceLoad.loadedAt === null ? '…' : runs.length}</span></div>
      <p class="scope-note">Runs working in the background, across every conversation.</p>
      ${this.notice(ctrl.presenceLoad, 'background runs', () => void ctrl.loadPresence())}
      ${runs.length === 0 && !ctrl.presenceLoad.failed
        ? html`<p class="empty">Runs you launch in the background appear here — none are running or finished yet.</p>`
        : ordered.map(([key, group]) => html`<div class="group" data-status=${key}>
            <div class="group-header">${group.label} · ${group.runs.length}</div>
            ${group.runs.map(run => this.backgroundRow(run))}
          </div>`)}
      <div class="compose">
        <input aria-label="Run a task in the background" placeholder="Run a task in the background…" .value=${this.draft}
          @input=${(event: Event) => { this.draft = (event.target as HTMLInputElement).value; }}
          @keydown=${(event: KeyboardEvent) => { if (event.key === 'Enter') this.submit(); }} />
        <jf-button size="sm" label="Run in background" .disabled=${!this.draft.trim()} .onActivate=${() => this.submit()}>Run in background</jf-button>
      </div>
    </section>`;
  }

  private thisRun(): TemplateResult {
    const ctrl = this.ctrl();
    const history = ctrl.history;
    return html`<section aria-label="This run" data-testid="activity-this-run">
      <div class="section-label">This run</div>
      <p class="scope-note">What the agent did in the run open right now.</p>
      ${this.notice(ctrl.historyLoad, 'this run', () => void ctrl.loadHistory())}
      ${history.length === 0 && !ctrl.historyLoad.failed
        ? html`<p class="empty">Each tool the agent uses in the current run is listed here — the run has used none yet.</p>`
        : history.map(entry => renderEventRow(entry, { apiBase: this.apiBase }))}
    </section>`;
  }

  override render(): TemplateResult {
    return html`${this.backgroundRuns()}${this.thisRun()}`;
  }

  static styles = [
    eventRowStyles,
    css`
      :host { display: block; }
      section:focus { outline: none; }
      section:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); outline-offset: var(--frame-focus-width); }
      .section-label {
        margin: 0.75rem 0 0.25rem 0;
        font-size: var(--font-size-xs);
        font-weight: 600;
        letter-spacing: 0.03em;
        text-transform: uppercase;
        color: var(--text-secondary);
      }
      .count {
        margin-inline-start: var(--jf-space-1);
        font-variant-numeric: tabular-nums;
      }
      .scope-note, .empty {
        margin: 0 0 var(--jf-space-2);
        font-size: var(--font-size-sm);
        color: var(--text-secondary);
      }
      .notice {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: var(--jf-space-2);
        margin: 0 0 var(--jf-space-2);
        font-size: var(--font-size-sm);
        color: var(--text-warning);
      }
      .group { margin-block-end: var(--jf-space-3); }
      .group-header {
        margin-block-end: var(--jf-space-1);
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .run {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: var(--jf-space-2);
        padding: var(--jf-space-2) var(--jf-space-3);
        border: var(--frame-hairline) solid var(--border-subtle);
        border-radius: var(--frame-control-radius);
        margin-block-end: var(--jf-space-1);
      }
      .run > div { min-inline-size: 0; flex: 1; }
      .run.clickable { cursor: pointer; }
      .run.clickable:hover { background: var(--surface-hover); }
      .run.clickable:focus-visible { outline: var(--frame-focus-width) solid var(--focus-ring-color); }
      .run-title { font-size: var(--font-size-sm); overflow-wrap: anywhere; }
      .meta { margin-block-start: var(--jf-space-1); font-size: var(--font-size-xs); color: var(--text-secondary); }
      .replay { flex: none; font-size: var(--font-size-xs); color: var(--text-secondary); }
      .compose { display: flex; flex-wrap: wrap; gap: var(--jf-space-2); margin-block-start: var(--jf-space-2); }
      .compose input {
        flex: 1 1 12rem;
        min-inline-size: 0;
        font: inherit;
        color: var(--text-primary);
        background: var(--surface-2);
        border: var(--frame-hairline) solid var(--border-subtle);
        border-radius: var(--frame-control-radius);
        padding: var(--jf-space-1) var(--jf-space-2);
      }
    `,
  ];
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-run-activity')) {
  customElements.define('jf-run-activity', RunActivity);
}

declare global {
  interface HTMLElementTagNameMap {
    'jf-run-activity': RunActivity;
  }
}
