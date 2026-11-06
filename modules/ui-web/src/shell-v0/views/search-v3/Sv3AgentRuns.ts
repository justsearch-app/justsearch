// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-agent-runs — the agent runs this instance still holds, at the end of Search's list: resume
 * one, replay a finished one, export its transcript, or load a shared one. It reads the ONE shared
 * `AgentSessionController`, whose replay shows in this window's main column, so choosing a row leaves
 * the reader where the result appears.
 *
 * A closed section asks the backend nothing. A failed read says so, keeps the rows it had, and offers
 * Try again; an empty list only ever means the backend said it was empty.
 *
 * Side-effect registers <jf-sv3-agent-runs>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { getAgentSessionController, subscribeAgentSession } from '../../state/agentSessionStore.js';
import {
  interruptedRunNotice,
  interruptedRunPresentation,
  sessionLabel,
  type SessionListItem,
} from '../../controllers/AgentSessionController.js';
// Tempdoc 577 Move 1 — per-run directives (resume included) dispatch ONLY through the control seam.
import { dispatchRunControl } from '../../controllers/runControlIntent.js';
import { runListNotice } from '../../controllers/runListLoad.js';
import { formatRelativeIso } from '../../../utils/relativeTime.js';
import { icon } from '../../components/Icon.js';

const RUNS_LABEL = 'Agent runs';

export class Sv3AgentRuns extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
    expanded: { state: true },
    loadError: { state: true },
  };

  declare apiBase: string;
  declare private expanded: boolean;
  /** Tempdoc 585 §D Phase 2 (D3) — why a loaded shared-replay file was refused. */
  declare private loadError: string | null;

  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.apiBase = '';
    this.expanded = false;
    this.loadError = null;
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.watch(false);
  }

  /** Only an open section follows the shared controller. */
  private watch(on: boolean): void {
    this.unsubscribe?.();
    this.unsubscribe = on ? subscribeAgentSession(() => this.requestUpdate()) : null;
  }

  private ctrl() {
    return getAgentSessionController(this.apiBase);
  }

  private toggle(): void {
    this.expanded = !this.expanded;
    this.watch(this.expanded);
    if (this.expanded) void this.ctrl().loadSessions();
  }

  // Tempdoc 577 Move 1 — resume carries the session's declared `resumable`, so the predicate and the
  // dispatch read the same lifecycle fact (a Resume on an evicted session is unrepresentable here).
  private resume(run: SessionListItem): void {
    void dispatchRunControl(this.ctrl(), { kind: 'resume', sessionId: run.sessionId, resumable: run.resumable === true });
  }

  /** Tempdoc 585 §D Phase 1 (C1) — read-only inspection, not a per-run directive. */
  private replay(run: SessionListItem): void {
    void this.ctrl().loadReplay(run.sessionId);
  }

  /** Tempdoc 585 §D Phase 2 (D3) — a finished run's transcript as a shareable JSON artifact. */
  private export(run: SessionListItem): void {
    const link = document.createElement('a');
    link.href = `${this.apiBase}/api/chat/sessions/${encodeURIComponent(run.sessionId)}/transcript`;
    link.download = `agent-session-${run.sessionId}.json`;
    document.body.appendChild(link);
    link.click();
    link.remove();
  }

  /** Tempdoc 585 §D Phase 2 (D3) — open a shared transcript file as a replay. */
  private async loadShared(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    try {
      const json: unknown = JSON.parse(await file.text());
      this.loadError = this.ctrl().loadReplayFromExport(json) ? null : 'That file has no replayable events.';
    } catch {
      this.loadError = 'Could not read that file as a run transcript.';
    } finally {
      input.value = ''; // Allow re-selecting the same file.
    }
  }

  private row(run: SessionListItem): TemplateResult {
    const resumable = run.resumable === true;
    const notice = interruptedRunNotice(run);
    return html`<li class="run" data-testid="sv3-agent-run">
      <div class="title" title=${sessionLabel(run)}>${sessionLabel(run)}</div>
      <div class="meta">
        ${run.startedAtEpochMs ? formatRelativeIso(new Date(run.startedAtEpochMs).toISOString()) : ''}
        ${run.iterationsUsed != null ? html`· ${run.iterationsUsed} iter` : nothing}
        ${!resumable && interruptedRunPresentation(run) !== 'FORK_ONLY' ? '· Finished' : nothing}
      </div>
      ${notice === null ? nothing : html`<div class="interrupted" data-testid="session-interrupted">${notice}</div>`}
      <div class="actions">
        ${resumable
          ? html`<button type="button" @click=${() => this.resume(run)}>Resume</button>`
          : html`<button type="button" @click=${() => this.replay(run)}>Replay</button>
              <button type="button" title="Download this run as a shareable transcript" @click=${() => this.export(run)}>Export</button>`}
      </div>
    </li>`;
  }

  private body(): TemplateResult {
    const ctrl = this.ctrl();
    const notice = runListNotice(ctrl.sessionsLoad, 'agent runs');
    return html`<div class="body" id="sv3-agent-runs-body">
      ${notice === null ? nothing : html`<p class="notice" role="status" data-testid="sv3-agent-runs-failed">
        ${notice} <button type="button" @click=${() => void ctrl.loadSessions()}>Try again</button></p>`}
      ${ctrl.sessions.length > 0
        ? html`<ul class="runs">${ctrl.sessions.map(run => this.row(run))}</ul>`
        : notice === null ? html`<p class="empty" data-testid="sv3-agent-runs-empty">Agent runs appear here once you start one.</p>` : nothing}
      <label class="shared">Load a shared replay
        <input type="file" accept="application/json,.json" @change=${(event: Event) => void this.loadShared(event)} /></label>
      ${this.loadError ? html`<p class="notice" role="alert">${this.loadError}</p>` : nothing}
    </div>`;
  }

  override render(): TemplateResult {
    return html`
      <button type="button" class="toggle" aria-expanded=${this.expanded ? 'true' : 'false'}
        aria-controls="sv3-agent-runs-body" data-testid="sv3-agent-runs-toggle" @click=${this.toggle}>
        ${icon({ name: this.expanded ? 'chevron-down' : 'chevron-right', size: 12 })}<span>${RUNS_LABEL}</span>
      </button>
      ${this.expanded ? this.body() : nothing}
    `;
  }

  static styles = [
    sv3Shared,
    css`
      :host {
        display: block;
        padding-inline: var(--sidebar-content-inset);
        color: var(--foreground);
        font-size: var(--font-size-sv3-xs);
      }
      button {
        font: inherit;
        color: inherit;
        cursor: pointer;
      }
      .toggle {
        display: flex;
        align-items: center;
        gap: var(--space-1);
        inline-size: 100%;
        padding: var(--space-2) var(--sidebar-row-content-inset);
        border: 0;
        background: transparent;
        color: var(--secondary-label);
      }
      .toggle:focus-visible,
      .actions button:focus-visible,
      .notice button:focus-visible {
        outline: 2px solid var(--ring);
        outline-offset: 2px;
      }
      .body {
        max-block-size: calc(var(--space-9) * 6);
        overflow-y: auto;
        padding-inline: var(--sidebar-row-content-inset);
      }
      .runs {
        margin: 0;
        padding: 0;
        list-style: none;
      }
      .run {
        padding-block: var(--space-2);
        border-block-start: 1px solid var(--border);
      }
      .title {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
      .meta,
      .empty,
      .shared {
        color: var(--secondary-label);
      }
      .interrupted,
      .notice {
        color: var(--warning-foreground);
      }
      .actions {
        display: flex;
        flex-wrap: wrap;
        gap: var(--space-1);
        margin-block-start: var(--space-1);
      }
      .actions button,
      .notice button {
        padding: 0 var(--space-2);
        border: 1px solid var(--border);
        border-radius: var(--radius-md);
        background: transparent;
      }
      .notice,
      .empty {
        margin: var(--space-1) 0;
      }
      .shared {
        display: block;
        margin-block-start: var(--space-2);
        overflow-wrap: anywhere;
      }
      .shared input {
        display: block;
        max-inline-size: 100%;
        margin-block-start: var(--space-1);
      }
    `,
  ];
}

customElements.define('jf-sv3-agent-runs', Sv3AgentRuns);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-agent-runs': Sv3AgentRuns;
  }
}
