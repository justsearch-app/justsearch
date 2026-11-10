// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-agent-emitter-demo> — Tempdoc 543 §28.W8.
 *
 * Synthetic agent emitter. Exercises the entire agent pipeline end-to-
 * end WITHOUT requiring a backend AI route:
 *
 *   - Emits Effects tagged `originator: 'agent'` so they surface
 *     under "AI" in the audit log + are reachable by
 *     undoLastEffectByOriginator('agent') / undoAllByOriginator
 *   - Some Effects auto-apply (low-risk: navigate, toast)
 *   - Some are PROPOSED via proposeEffect (high-risk: invoke-operation,
 *     open-modal) and surface in the PendingEffect queue for user
 *     accept/reject
 *   - DataEffect emissions populate the EvaluationContext data cache
 *     via setLatestDataResult, so when-clauses can branch on them
 *
 * This is the production consumer for the DataEffect arm (§25.β5),
 * PendingEffect (§21.E), and originator-grouped undo (§28.W4) that
 * §29 honestly said was "substrate exists; no live consumer." Now
 * there's a live consumer — gated under DEVELOPER audience so it
 * doesn't surface in user-facing UX.
 *
 * The synthetic emissions don't replace a real AI emitter (which is
 * gated on V1.5.2 + backend AI plumbing). They DO exercise every
 * substrate path the real emitter will take, so when V1.5.2 lands the
 * code paths are battle-tested.
 */

import { formatTime } from '../display/format.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './StatusBadge.js';
import { applyEffect } from '../substrates/actions/index.js';
import {
  proposeEffect,
  type PendingId,
} from '../substrates/pending-effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import { recordEffect } from '../substrates/effects/index.js';

type DemoOutcome =
  | { kind: 'auto-applied'; effectKind: string }
  | { kind: 'proposed'; pendingId: PendingId; effectKind: string }
  | { kind: 'data-recorded'; resultKey: string };

export class AgentEmitterDemo extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    log: { state: true },
  };

  declare open: boolean;
  declare log: ReadonlyArray<{ at: string; outcome: DemoOutcome }>;

  constructor() {
    super();
    this.open = false;
    this.log = [];
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost bottom-center slot. */
      pointer-events: none;
    }
    :host(:not([open])) {
      display: none;
    }
    .panel {
      pointer-events: auto;
      background: var(--surface-1);
      border: 1px solid var(--accent-warning);
      border-radius: 0.5rem;
      padding: 0.625rem 0.875rem;
      color: var(--text-primary);
      font-size: var(--font-size-sm);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      min-width: 28rem;
      max-width: 40rem;
    }
    header {
      display: flex;
      align-items: center;
      gap: 0.5rem;
      margin-bottom: 0.5rem;
    }
    header h3 {
      margin: 0;
      font-size: var(--font-size-sm);
      font-weight: 600;
    }
    .row {
      display: flex;
      gap: 0.375rem;
      flex-wrap: wrap;
      margin-bottom: 0.5rem;
    }
    button {
      background: transparent;
      color: inherit;
      border: 1px solid var(--border-default);
      border-radius: 0.25rem;
      padding: 0.25rem 0.625rem;
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    button:hover {
      background: var(--surface-2);
    }
    button.close {
      margin-left: auto;
    }
    .log {
      max-height: 8rem;
      overflow-y: auto;
      font-family: var(--font-mono);
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      background: var(--surface-2);
      padding: 0.25rem 0.5rem;
      border-radius: 0.25rem;
    }
    .log-entry {
      padding: 0.0625rem 0;
    }
  `;

  // §28.W8 — emit a low-risk Effect tagged 'agent' so it auto-applies
  // (navigate / toast — no destructive side effect). Recorded in the
  // journal as originator='agent' so audit log + originator-grouped
  // undo can reach it.
  private emitAutoNavigate(): void {
    const effect = { kind: 'navigate' as const, to: '#agent-emitted-demo' };
    applyEffect(effect, CORE_PROVENANCE);
    // applyEffect's recordEffect call defaults originator to 'user'.
    // Re-record with the agent tag so audit log + originator-undo see it.
    recordEffect(effect, CORE_PROVENANCE, { originator: 'agent' });
    this.appendLog({ kind: 'auto-applied', effectKind: 'navigate' });
  }

  private emitAutoToast(): void {
    const effect = {
      kind: 'toast' as const,
      message: 'Synthetic agent: low-risk toast',
      severity: 'info' as const,
    };
    applyEffect(effect, CORE_PROVENANCE);
    recordEffect(effect, CORE_PROVENANCE, { originator: 'agent' });
    this.appendLog({ kind: 'auto-applied', effectKind: 'toast' });
  }

  // §28.W8 — emit a high-risk Effect via PendingEffect so it surfaces
  // in the agent pending queue for user accept/reject. Exercises the
  // §21.E PendingEffect path with a real production consumer.
  private emitProposedOpenModal(): void {
    const id = proposeEffect(
      { kind: 'open-modal' as const, modalId: 'demo.agent-confirmation' },
      CORE_PROVENANCE,
      'agent',
      { rationale: 'Synthetic agent demo — would open a confirmation modal.' },
    );
    this.appendLog({ kind: 'proposed', pendingId: id, effectKind: 'open-modal' });
  }

  private emitProposedInvokeOperation(): void {
    const id = proposeEffect(
      {
        kind: 'invoke-operation' as const,
        operationId: 'core.demo.synthetic',
        args: { synthetic: true },
      },
      CORE_PROVENANCE,
      'agent',
      { rationale: 'Synthetic agent demo — would invoke a backend operation.' },
    );
    this.appendLog({ kind: 'proposed', pendingId: id, effectKind: 'invoke-operation' });
  }

  // §28.W8 — emit a DataEffect (§25.β5) so the data-result cache
  // populates and when-clauses can branch on the synthetic result.
  private emitDataResult(): void {
    const effect = {
      kind: 'data-result' as const,
      operationId: 'core.demo.synthetic-search',
      resultKey: 'demo.lastAgentSearch',
      result: {
        query: 'synthetic',
        hits: [
          { id: 's1', title: 'Synthetic hit 1' },
          { id: 's2', title: 'Synthetic hit 2' },
        ],
        count: 2,
      },
    };
    applyEffect(effect, CORE_PROVENANCE);
    recordEffect(effect, CORE_PROVENANCE, { originator: 'agent' });
    this.appendLog({ kind: 'data-recorded', resultKey: effect.resultKey });
  }

  private emitDataError(): void {
    const effect = {
      kind: 'data-error' as const,
      operationId: 'core.demo.synthetic-search',
      resultKey: 'demo.lastAgentSearch',
      error: 'Synthetic timeout (demo)',
    };
    applyEffect(effect, CORE_PROVENANCE);
    recordEffect(effect, CORE_PROVENANCE, { originator: 'agent' });
    this.appendLog({ kind: 'data-recorded', resultKey: effect.resultKey });
  }

  private appendLog(outcome: DemoOutcome): void {
    this.log = [
      ...this.log,
      { at: formatTime(new Date()), outcome },
    ].slice(-20);
  }

  private formatOutcome(o: DemoOutcome): string {
    if (o.kind === 'auto-applied') return `auto-applied ${o.effectKind}`;
    if (o.kind === 'proposed') return `proposed ${o.effectKind} #${o.pendingId}`;
    return `data-recorded ${o.resultKey}`;
  }

  render(): TemplateResult | typeof nothing {
    if (!this.open) return nothing;
    return html`
      <div class="panel" data-testid="agent-emitter-demo">
        <header>
          <jf-status-badge tone="warning">demo</jf-status-badge>
          <h3>Synthetic agent emitter</h3>
          <button class="close" type="button" @click=${() => (this.open = false)}>
            Close
          </button>
        </header>
        <p style="color:var(--text-secondary);margin:0 0 0.5rem;">
          Each button emits an Effect tagged <code>originator: 'agent'</code>.
          Auto-apply (low-risk) lands in the audit log immediately; proposed
          (high-risk) surfaces in the lower-right PendingEffect queue for
          accept/reject. Use the audit log's "Undo last AI action" to reverse.
        </p>
        <div class="row">
          <button type="button" @click=${() => this.emitAutoNavigate()}>
            Auto-navigate
          </button>
          <button type="button" @click=${() => this.emitAutoToast()}>
            Auto-toast
          </button>
          <button type="button" @click=${() => this.emitProposedOpenModal()}>
            Propose open-modal
          </button>
          <button type="button" @click=${() => this.emitProposedInvokeOperation()}>
            Propose invoke-operation
          </button>
          <button type="button" @click=${() => this.emitDataResult()}>
            Emit data-result
          </button>
          <button type="button" @click=${() => this.emitDataError()}>
            Emit data-error
          </button>
        </div>
        <div class="log">
          ${this.log.length === 0
            ? html`<div class="log-entry" style="color:var(--text-muted)">No emissions yet.</div>`
            : this.log
                .slice()
                .reverse()
                .map(
                  (e) => html`<div class="log-entry">${e.at} — ${this.formatOutcome(e.outcome)}</div>`,
                )}
        </div>
      </div>
    `;
  }
}

if (!customElements.get('jf-agent-emitter-demo')) {
  customElements.define('jf-agent-emitter-demo', AgentEmitterDemo);
}
