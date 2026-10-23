// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-interaction-retrospective-panel> — Tempdoc 561 (surface tier).
 *
 * The agent's retrospective views — Sessions / Timeline / History — folded INTO the one interaction
 * window as a right-drawer panel (mirroring jf-agent-activity-panel / jf-advisory-inbox-drawer). It
 * reads the ONE shared `AgentSessionController` (agentSessionStore) — the same governed projections
 * the inline agent run uses (/api/chat/sessions; the action-ledger workspace activity, 561 P-B2;
 * /api/action-ledger by correlationId, P-B1) — so it is a consumer, not a second authority. Replaces
 * the retrospective tabs of the now-retired standalone `core.agent-surface`.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import './StatusBadge.js';
import { TransientController } from '../primitives/transientController.js';
import {
  getAgentSessionController,
  subscribeAgentSession,
} from '../state/agentSessionStore.js';
import {
  isRetrospectiveOpen,
  setRetrospectiveOpen,
  subscribeRetrospective,
  takeRequestedTab,
  type RetrospectiveTab,
} from '../state/retrospectiveDrawer.js';
import {
  interruptedRunNotice,
  interruptedRunPresentation,
  sessionLabel,
  type SessionListItem,
} from '../controllers/AgentSessionController.js';
// Tempdoc 577 Move 1 — per-run directives (resume included) dispatch ONLY through the control seam.
import { dispatchRunControl } from '../controllers/runControlIntent.js';
import { formatRelativeIso } from '../../utils/relativeTime.js';
import { activateOnKey } from '../utils/keyboardHandler.js';
// tempdoc 558 §S1 — the ONE shared ledger-row projection (identical to the Activity surface), so the
// History/Timeline rows and the Activity rows present the same record the same way by construction. This
// also subsumes the former per-tab status-tone + display-label wiring (now inside the shared row).
import { renderEventRow, eventRowStyles } from './eventRow.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

// Tempdoc 565 §26.D — the Inbox tab folds the Memory surface's presence/activity half into the one
// window's retrospective drawer (the durable-facts half stays the peer `core.memory-surface`).
// Tempdoc 814 (finding 7) — the tab identities now live in the drawer store (a cross-component
// pointer must be able to name a tab without importing this panel); the internal alias is kept so
// the id vocabulary reads the same at every use site here.
type RetroTab = RetrospectiveTab;
const TAB_KEYS: Record<string, RetroTab> = {
  '1': 'sessions',
  '2': 'timeline',
  '3': 'history',
  '4': 'inbox',
};

/**
 * Tempdoc 814 (finding 7) — each tab named by the QUESTION it answers, with the SCOPE of the data
 * that answers it, verified at the controller's fetch site (AgentSessionController):
 *
 *  - `sessions`  → `GET /api/chat/sessions?limit=N` — the roster of agent runs this instance holds.
 *  - `timeline`  → `GET /api/action-ledger` (unfiltered) folded with the FE-local Effect Journal
 *                  (`ActionLedgerClient.unifiedActivity`) — machine-wide, every originator/kind.
 *  - `history`   → `GET /api/action-ledger?originator=agent&correlationId=<sessionId>`, kept to
 *                  `kind === 'operation'` — the ACTIVE RUN's own operations. NOT `/api/thread` and
 *                  not conversation-scoped: the filter key is the agent session id, which a fork
 *                  resets, so "This run" is the honest name (design's assumption corrected here).
 *  - `inbox`     → `GET /api/presence` — background runs, across conversations.
 *
 * "Timeline"/"History" were near-synonyms answering different questions at different scopes; the
 * labels and the descriptors under each tab's content are what make the difference legible.
 */
const TAB_LABEL: Record<RetroTab, string> = {
  sessions: 'Sessions',
  timeline: 'System activity',
  history: 'This run',
  inbox: 'Background runs',
};
const TAB_SCOPE: Record<RetroTab, string> = {
  sessions: 'Every agent run this instance still holds — resume or replay one.',
  timeline: 'Everything that happened in this workspace, newest first.',
  history: 'What the agent did in the run open right now.',
  inbox: 'Runs working in the background, across every conversation.',
};

export class RetrospectivePanel extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    apiBase: { type: String, attribute: 'api-base' },
    host_: { attribute: false },
    activeTab: { state: true },
    inboxDraft: { state: true },
    loadError: { state: true },
  };

  declare open: boolean;
  declare apiBase: string;
  declare host_: PluginHostApi | undefined;
  declare activeTab: RetroTab;
  // Tempdoc 565 §26.D — the run-in-background composer draft (the launcher folded from the Memory surface).
  declare inboxDraft: string;
  // Tempdoc 585 §D Phase 2 (D3) — a transient message when a loaded shared-replay file is not a
  // valid transcript (null when there is no error).
  declare loadError: string | null;

  private unsubs: Array<() => void> = [];

  /** 574 §23.B — single-open arbitration by construction. This panel reflects the `retrospectiveDrawer`
   *  store; the controller closes peer drawers on open and is itself closed when a peer opens. No
   *  outside-click dismiss (the panel closes via its Close button / Esc handler). */
  private readonly transient = new TransientController(this, {
    layer: 'right-drawer',
    id: 'retrospective',
    close: () => setRetrospectiveOpen(false),
  });

  constructor() {
    super();
    this.open = isRetrospectiveOpen();
    this.apiBase = '';
    this.activeTab = 'sessions';
    this.inboxDraft = '';
    this.loadError = null;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.unsubs = [
      subscribeAgentSession(() => this.requestUpdate()),
      subscribeRetrospective(() => {
        const next = isRetrospectiveOpen();
        const opening = next && !this.open;
        // Tempdoc 814 (finding 7) — a pointer that opened the drawer AT a tab (the thread's
        // background-run reference) selects it here; consuming the request clears it, so a later
        // plain open lands on whatever tab the user last chose.
        const requested = takeRequestedTab();
        if (requested) this.activeTab = requested;
        this.open = next;
        if (next && (opening || requested)) void this.loadActive();
        this.requestUpdate();
      }),
    ];
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubs.forEach((u) => u());
    this.unsubs = [];
  }

  override updated(changed: Map<string, unknown>): void {
    // 574 §23.B — the open-state (reflected from the store) drives the single-open controller.
    if (changed.has('open')) {
      if (this.open) this.transient.open();
      else this.transient.close();
    }
  }

  private ctrl() {
    return getAgentSessionController(this.apiBase, this.host_);
  }

  private setTab(tab: RetroTab): void {
    this.activeTab = tab;
    void this.loadActive();
  }

  private async loadActive(): Promise<void> {
    const c = this.ctrl();
    // Tempdoc 814 (finding 7) — the Background-runs COUNT rides on the tab itself, so it is visible
    // from every tab and its data loads on every tab. An unloaded badge would render "0" while runs
    // exist: a false zero, which is the opposite of the legible zero the badge exists to give.
    const presence = c.loadPresence();
    if (this.activeTab === 'sessions') await c.loadSessions();
    else if (this.activeTab === 'timeline') await c.loadTimeline();
    else if (this.activeTab === 'history') await c.loadHistory();
    await presence;
  }

  private onKeydown = (ev: KeyboardEvent): void => {
    if (ev.key === 'Escape') {
      setRetrospectiveOpen(false);
      return;
    }
    if (ev.altKey && TAB_KEYS[ev.key]) {
      ev.preventDefault();
      this.setTab(TAB_KEYS[ev.key]!);
    }
  };

  // Tempdoc 577 Move 1 — resume is a per-run directive: it flows through the ONE control seam,
  // carrying the session's declared `resumable` so the predicate and the dispatch read the same
  // lifecycle fact (the Resume-on-an-evicted-session 500 class is unrepresentable at this seam).
  private handleResume(s: SessionListItem): void {
    void dispatchRunControl(this.ctrl(), {
      kind: 'resume',
      sessionId: s.sessionId,
      resumable: s.resumable === true,
    });
    setRetrospectiveOpen(false);
  }

  /**
   * Tempdoc 585 §D Phase 1 (C1) — replay a FINISHED run. Read-only inspection (NOT a per-run
   * directive), so it calls the controller's replay primitive directly rather than the
   * dispatchRunControl steering seam. The shared controller enters replayMode + loads the run's
   * persisted events; the main view renders them with a scrubber.
   */
  private handleReplay(s: SessionListItem): void {
    this.handleReplaySession(s.sessionId);
  }

  /** Tempdoc 585 §D Phase 1 (C1/D1) — load a finished run into the shared controller's replay mode. */
  private handleReplaySession(sessionId: string): void {
    void this.ctrl().loadReplay(sessionId);
    setRetrospectiveOpen(false);
  }

  /**
   * Tempdoc 585 §D Phase 2 (D3) — download a finished run's transcript as a shareable JSON artifact
   * (the backend `GET /api/chat/sessions/{id}/transcript`, Content-Disposition: attachment). A
   * portable record another instance can re-open as a replay via {@link handleLoadSharedReplay}.
   */
  private handleExport(s: SessionListItem): void {
    const url = `${this.apiBase}/api/chat/sessions/${encodeURIComponent(s.sessionId)}/transcript`;
    const a = document.createElement('a');
    a.href = url;
    a.download = `agent-session-${s.sessionId}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  /**
   * Tempdoc 585 §D Phase 2 (D3) — load a shared/exported transcript file as a replay: parse the JSON
   * and drive the C1 replay inspector via {@link AgentSessionController#loadReplayFromExport}. Closes
   * the drawer on success so the replayed run is visible; surfaces an inline error otherwise.
   */
  private async handleLoadSharedReplay(e: Event): Promise<void> {
    const input = e.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    try {
      const json = JSON.parse(await file.text());
      if (this.ctrl().loadReplayFromExport(json)) {
        this.loadError = null;
        setRetrospectiveOpen(false); // reveal the replayed run in the inspector
      } else {
        this.loadError = 'That file has no replayable events.';
      }
    } catch {
      this.loadError = 'Could not read that file as a run transcript.';
    } finally {
      input.value = ''; // allow re-selecting the same file
      this.requestUpdate();
    }
  }

  // tempdoc 558 §S1 — the ledger-row styles are single-sourced in eventRow.ts (shared with the Activity
  // surface); this panel adds its own chrome on top.
  static styles = [
    eventRowStyles,
    css`
    :host(:not([open])) {
      display: none;
    }
    .panel {
      position: relative;
      height: 100%;
      width: 24rem;
      max-width: 90vw;
      background: var(--surface-1);
      border-left: 1px solid var(--border-default);
      box-shadow: -4px 0 16px rgba(0, 0, 0, 0.35);
      color: var(--text-primary);
      display: flex;
      flex-direction: column;
    }
    .head {
      flex-shrink: 0;
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-default);
    }
    .title {
      font-weight: 600;
      font-size: var(--font-size-sm);
    }
    /* 574 critical-analysis F2 — the close is a jf-button(sm) atom; it skins itself.
       The old class-only .close skin was deleted (it leaked onto the jf-button host). */
    .tabs {
      display: inline-flex;
      gap: 0.125rem;
      padding: 0.375rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
    }
    .tabs button {
      padding: 0.25rem 0.625rem;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-weight: 500;
      border-radius: 0.25rem;
      cursor: pointer;
    }
    .tabs button.active {
      background: var(--accent-command-16);
      color: var(--text-command);
    }
    /* Tempdoc 814 (finding 7) — the Background-runs count badge: an empty inbox reads as a legible
       zero instead of a tab you have to open to learn it holds nothing. */
    .tab-count {
      display: inline-block;
      margin-left: 0.3rem;
      padding: 0 0.3rem;
      border-radius: 0.6rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
      font-variant-numeric: tabular-nums;
    }
    .tabs button.active .tab-count {
      background: var(--surface-1);
    }
    /* Tempdoc 814 (finding 7) — the per-tab scope descriptor: which record answers this tab. */
    .scope-note {
      margin: 0 0 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .scroll {
      flex: 1;
      overflow-y: auto;
      padding: 1rem 1rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .empty-state {
      flex: 1;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 2rem;
      text-align: center;
      color: var(--text-secondary);
    }
    /* Tempdoc 565 §26.D — the run-in-background composer in the Inbox tab. */
    .inbox-compose {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
    .inbox-draft {
      flex: 1;
      font: inherit;
      color: var(--text-primary);
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      border-radius: 0.3rem;
      padding: 0.35rem 0.5rem;
    }
    .session-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.625rem 0.75rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
    }
    /* Tempdoc 577 §2.9 (Sessions overlap fix): the text column shrinks (min-width:0 enables the
     * title's ellipsis inside flex) and the trailing action never overlaps it. */
    .session-row > div {
      min-width: 0;
      flex: 1;
    }
    .session-row > button {
      flex-shrink: 0;
    }
    /* Tempdoc 565 §29 Tier-4 — the inbox status groups (a dispatcher's kanban). */
    .inbox-group {
      display: flex;
      flex-direction: column;
      gap: 0.375rem;
      margin-bottom: 0.625rem;
    }
    .inbox-group-header {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.04ch;
    }
    .session-row.clickable {
      cursor: pointer;
    }
    .session-row.clickable:hover {
      background: var(--surface-hover);
    }
    .session-title {
      font-size: var(--font-size-sm);
      font-weight: 500;
      color: var(--text-primary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .mono {
      font-family: var(--font-mono);
      font-size: var(--font-size-sm);
    }
    .meta {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    .resume {
      background: transparent;
      border: 1px solid var(--border-default);
      border-radius: 0.25rem;
      color: inherit;
      font: inherit;
      font-size: var(--font-size-xs);
      padding: 0.15rem 0.5rem;
      cursor: pointer;
    }
    /* Tempdoc 585 §D Phase 2 (D3) — per-row Replay + Export actions. */
    .row-actions {
      display: flex;
      gap: 0.375rem;
      align-items: center;
    }
    /* Tempdoc 585 §D Phase 2 (D3) — load a shared transcript file as a replay. */
    .shared-replay {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
      padding: 0.5rem 0.25rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .load-error {
      color: var(--text-warning);
    }
    .interrupted {
      display: block;
      font-size: var(--font-size-xs);
      color: var(--text-warning);
      margin-top: 0.125rem;
    }
  `,
  ];

  override render(): TemplateResult {
    return html`
      <div
        class="panel"
        role="region"
        aria-label="Agent retrospective"
        @keydown=${this.onKeydown}
      >
        <div class="head">
          ${/* Tempdoc 814 (finding 7) — the drawer titled "History" over a tab named "History" was
                half of the collision this rename resolves. It is titled by what OPENS it (the
                header's "Activity" control), and the tabs now each name their own question. */ ''}
          <span class="title">Activity</span>
          <jf-button class="close" size="sm" label="Close" .onActivate=${() => setRetrospectiveOpen(false)}>Close</jf-button>
        </div>
        <div class="tabs" role="tablist">
          ${(['sessions', 'timeline', 'history', 'inbox'] as RetroTab[]).map(
            (id) => html`
              <button
                role="tab"
                data-tab=${id}
                aria-selected=${this.activeTab === id ? 'true' : 'false'}
                class=${this.activeTab === id ? 'active' : ''}
                title=${TAB_SCOPE[id]}
                @click=${() => this.setTab(id)}
              >
                ${TAB_LABEL[id]}${id === 'inbox'
                  ? html`<span class="tab-count" data-testid="background-runs-count"
                      >${this.ctrl().presence.length}</span
                    >`
                  : nothing}
              </button>
            `,
          )}
        </div>
        ${this.activeTab === 'sessions' ? this.renderSessions() : nothing}
        ${this.activeTab === 'timeline' ? this.renderTimeline() : nothing}
        ${this.activeTab === 'history' ? this.renderHistory() : nothing}
        ${this.activeTab === 'inbox' ? this.renderInbox() : nothing}
      </div>
    `;
  }

  /**
   * Tempdoc 565 §26.D — the Inbox tab: the cross-conversation background-run list (`/api/presence`) +
   * the run-in-background launcher (carrying the active conversationId so the run also joins this
   * conversation's thread as a `background` segment). This is the activity half folded out of the
   * standalone Memory surface; the durable-facts half stays the peer `core.memory-surface`.
   */
  private renderInbox(): TemplateResult {
    const runs = this.ctrl().presence;
    const submit = (): void => {
      const prompt = this.inboxDraft.trim();
      if (!prompt) return;
      void this.ctrl().runBackgroundTask(prompt);
      this.inboxDraft = '';
    };
    // Tempdoc 565 §33 — group the inbox by run STATUS (a dispatcher's kanban) using the REAL lifecycle
    // vocabulary the backend emits: `PresenceRun.state` = `LifecycleState.name()` ∈
    // {READY_FOR_LLM, WAITING_APPROVAL, AFTER_TOOL_RESULT, DONE, ERROR}. Each maps to a friendly
    // { bucket, rank, label } — the two transient running states merge into one "Running" group; an
    // unknown state falls last under a humanized header. Pure reorg of the same `/api/presence` data.
    const STATUS: Record<string, { bucket: string; rank: number; label: string }> = {
      READY_FOR_LLM: { bucket: 'running', rank: 0, label: 'Running' },
      AFTER_TOOL_RESULT: { bucket: 'running', rank: 0, label: 'Running' },
      WAITING_APPROVAL: { bucket: 'needs-approval', rank: 1, label: 'Needs approval' },
      // Tempdoc 577 Move 2 — the run parked at the budget gate (held, non-terminal).
      WAITING_BUDGET: { bucket: 'needs-budget', rank: 1, label: 'Awaiting budget' },
      DONE: { bucket: 'done', rank: 2, label: 'Done' },
      ERROR: { bucket: 'failed', rank: 3, label: 'Failed' },
    };
    const humanize = (s: string): string =>
      (s || 'Unknown')
        .toLowerCase()
        .split(/[\s_]+/)
        .map((w) => (w ? w[0]!.toUpperCase() + w.slice(1) : w))
        .join(' ');
    const classify = (state: string): { bucket: string; rank: number; label: string } =>
      STATUS[state] ?? {
        bucket: (state || 'unknown').toLowerCase().replace(/[\s_]+/g, '-'),
        rank: 9,
        label: humanize(state),
      };
    const groups = new Map<
      string,
      { rank: number; label: string; runs: Array<(typeof runs)[number]> }
    >();
    for (const p of runs) {
      const c = classify(p.state);
      const g = groups.get(c.bucket);
      if (g) g.runs.push(p);
      else groups.set(c.bucket, { rank: c.rank, label: c.label, runs: [p] });
    }
    const orderedBuckets = [...groups.entries()].sort(
      (a, b) => a[1].rank - b[1].rank || a[0].localeCompare(b[0]),
    );
    return html`
      <div class="scroll">
        ${this.renderScope('inbox')}
        ${runs.length === 0
          ? html`<div class="empty-state">
              Runs you launch in the background appear here — none are running or finished yet.
            </div>`
          : orderedBuckets.map(
              ([key, group]) => html`
                <div class="inbox-group" data-status=${key}>
                  <div class="inbox-group-header">${group.label} · ${group.runs.length}</div>
                  ${group.runs.map((p) => {
                    // Tempdoc 585 §D Phase 1 (D1): a finished (DONE/ERROR) background run is
                    // click-through to the C1 replay inspector; a still-running one is not.
                    const terminal = p.state === 'DONE' || p.state === 'ERROR';
                    const body = html`
                      <div>
                        <div style="font-size: var(--font-size-sm)">
                          Background run — ${p.toolCalls} tool${p.toolCalls === 1 ? '' : 's'},
                          ${p.iterations} iteration${p.iterations === 1 ? '' : 's'}
                        </div>
                        <div class="meta">
                          <jf-status-badge status=${p.state}>${classify(p.state).label}</jf-status-badge>
                        </div>
                      </div>
                    `;
                    return terminal
                      ? html`
                          <div
                            class="session-row clickable"
                            role="button"
                            tabindex="0"
                            title="Replay this background run"
                            @click=${() => this.handleReplaySession(p.sessionId)}
                            @keydown=${(e: KeyboardEvent) =>
                              activateOnKey(e, () => this.handleReplaySession(p.sessionId))}
                          >
                            ${body}<button class="resume">Replay</button>
                          </div>
                        `
                      : html`<div class="session-row">${body}</div>`;
                  })}
                </div>
              `,
            )}
        <div class="inbox-compose">
          <input
            class="inbox-draft"
            aria-label="Run a task in the background"
            placeholder="Run a task in the background…"
            .value=${this.inboxDraft}
            @input=${(e: Event) => (this.inboxDraft = (e.target as HTMLInputElement).value)}
            @keydown=${(e: KeyboardEvent) => {
              if (e.key === 'Enter') submit();
            }}
          />
          <button ?disabled=${!this.inboxDraft.trim()} @click=${submit}>Run in background</button>
        </div>
      </div>
    `;
  }

  private renderSessions(): TemplateResult {
    const sessions = this.ctrl().sessions;
    return html`
      <div class="scroll">
        ${this.renderScope('sessions')}
        <div class="shared-replay">
          <label for="share-replay-input">Load a shared replay:</label>
          <input
            id="share-replay-input"
            type="file"
            accept="application/json,.json"
            @change=${(e: Event) => void this.handleLoadSharedReplay(e)}
          />
          ${this.loadError
            ? html`<span class="load-error" role="alert">${this.loadError}</span>`
            : nothing}
        </div>
        ${sessions.length === 0
          ? html`<div class="empty-state">
              Agent runs appear here once you start one — resume or replay them from this list.
            </div>`
          : sessions.map((s) => {
              // Tempdoc 577 Move 1 — the Resume affordance is a projection of the session's
              // declared lifecycle: a non-resumable (finished/evicted) session renders a plain
              // row with a "Finished" label, never a dead button (the live-audit 500 class).
              const resumable = s.resumable === true;
              return resumable
                ? html`
                    <div
                      class="session-row clickable"
                      role="button"
                      tabindex="0"
                      @click=${() => this.handleResume(s)}
                      @keydown=${(e: KeyboardEvent) =>
                        activateOnKey(e, () => this.handleResume(s))}
                      title="Resume — ${sessionLabel(s)}"
                    >
                      <div>
                        <div class="session-title">${sessionLabel(s)}</div>
                        <div class="meta">
                          ${s.startedAtEpochMs
                            ? formatRelativeIso(new Date(s.startedAtEpochMs).toISOString())
                            : ''}
                          ${s.iterationsUsed != null ? html`· ${s.iterationsUsed} iter` : nothing}
                        </div>
                        ${this.renderInterruption(s)}
                      </div>
                      <button class="resume">Resume</button>
                    </div>
                  `
                : html`
                    <div
                      class="session-row clickable"
                      role="button"
                      tabindex="0"
                      @click=${() => this.handleReplay(s)}
                      @keydown=${(e: KeyboardEvent) =>
                        activateOnKey(e, () => this.handleReplay(s))}
                      title="Replay — ${sessionLabel(s)}"
                    >
                      <div>
                        <div class="session-title">${sessionLabel(s)}</div>
                        <div class="meta">
                          ${s.startedAtEpochMs
                            ? formatRelativeIso(new Date(s.startedAtEpochMs).toISOString())
                            : ''}
                          ${s.iterationsUsed != null ? html`· ${s.iterationsUsed} iter` : nothing}
                          ${interruptedRunPresentation(s) === 'FORK_ONLY' ? nothing : '· Finished'}
                        </div>
                        ${this.renderInterruption(s)}
                      </div>
                      <div class="row-actions">
                        <button class="resume">Replay</button>
                        <button
                          class="resume export"
                          @click=${(e: Event) => {
                            e.stopPropagation();
                            this.handleExport(s);
                          }}
                          title="Download this run as a shareable transcript"
                        >
                          Export
                        </button>
                      </div>
                    </div>
                  `;
            })}
      </div>
    `;
  }

  /**
   * Tempdoc 834 §5.2 — the interruption line, when there is one. Four presentations, derived by the
   * shared classifier from the exact triple the wire carries; a row that was simply finished, or
   * that was never interrupted, says nothing extra. The FORK_ONLY copy names the fork because a
   * budget/context gate lived in memory and no checkpoint recorded it — a Resume there would be the
   * dead-button class, so the row keeps its Replay/Export actions and the copy says why.
   */
  private renderInterruption(s: SessionListItem): TemplateResult | typeof nothing {
    const notice = interruptedRunNotice(s);
    return notice === null
      ? nothing
      : html`<span class="interrupted" data-testid="session-interrupted">${notice}</span>`;
  }

  private renderTimeline(): TemplateResult {
    const rows = [...this.ctrl().timeline].reverse();
    // tempdoc 558 §S1 — workspace activity is the same UnifiedActionEntry the Activity surface renders;
    // project it through the ONE shared row (who · label · outcome · source · time).
    return html`
      <div class="scroll">
        ${this.renderScope('timeline')}
        ${rows.length === 0
          ? html`<div class="empty-state">
              Actions taken anywhere in this workspace — yours or the agent's — appear here as they
              happen.
            </div>`
          : rows.map((e) => renderEventRow(e, { apiBase: this.apiBase }))}
      </div>
    `;
  }

  private renderHistory(): TemplateResult {
    const history = this.ctrl().history;
    // tempdoc 558 §S1 — History is this session's agent operations, the SAME record the Activity
    // surface shows; render it through the ONE shared row instead of a re-derived HistoryEntry markup.
    return html`
      <div class="scroll">
        ${this.renderScope('history')}
        ${history.length === 0
          ? html`<div class="empty-state">
              Each tool the agent uses in the current run is listed here — the run has used none yet.
            </div>`
          : history.map((e) => renderEventRow(e, { apiBase: this.apiBase }))}
      </div>
    `;
  }

  /**
   * Tempdoc 814 (finding 7) — the one-line scope descriptor under a tab's content header. Renamed
   * tabs answer different questions; the descriptor states WHICH RECORD answers this one, so
   * "System activity" and "This run" cannot be read as two copies of the same list.
   */
  private renderScope(tab: RetroTab): TemplateResult {
    return html`<p class="scope-note" data-testid="scope-${tab}">${TAB_SCOPE[tab]}</p>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-interaction-retrospective-panel')) {
  customElements.define('jf-interaction-retrospective-panel', RetrospectivePanel);
}
