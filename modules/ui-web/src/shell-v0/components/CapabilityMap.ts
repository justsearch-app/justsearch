// SPDX-License-Identifier: Apache-2.0
/**
 * CapabilityMap (`jf-capability-map`) — tempdoc 596 §16.5 "what can I do right now".
 *
 * A read-only projection of EVERY capability affordance's availability into one list — the single
 * system-state explainer the chat/search degradation banner is one slice of. It does NOT own any new
 * state: each row is `projectAvailability(affordance, aiState)` (the same authority the live controls
 * read), so the map can never disagree with the affordance it describes. Each blocked/degraded row that
 * carries a remedy offers it inline (the affordance-level "+ how to fix"), navigating via the shell's
 * `navigate-with-context` seam — the same one `jf-control` uses.
 *
 * Realized as an embeddable COMPONENT (mounted in the Health surface, the system-state home) rather than
 * a standalone route surface: same feature, no new route/catalog/i18n structure (596 plan §3b, the
 * "extend, don't invent" cut — the capability map is a system-state READ, and Health is where that lives).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Control.js';
import {
  AVAILABLE,
  projectAvailability,
  unavailableBecause,
  type Availability,
  type CapabilityAffordance,
} from '../state/availability.js';
import type { AiState } from '../state/aiStateStore.js';
import { reasonFor, type NoticeRemedy } from '../state/readinessNotice.js';

/** The capability affordances, in the order a user reasons about them, with human labels. */
const AFFORDANCES: ReadonlyArray<{ affordance: CapabilityAffordance; label: string }> = [
  { affordance: 'documents', label: 'Ask AI about your documents' },
  { affordance: 'extract', label: 'Extract structured data' },
  { affordance: 'agent', label: 'Run agent tasks' },
];

interface Row {
  readonly label: string;
  readonly availability: Availability;
}

export class CapabilityMap extends JfElement {
  static properties = {
    aiState: { attribute: false },
  };

  declare aiState: AiState | null;

  constructor() {
    super();
    this.aiState = null;
  }

  static styles = css`
    :host {
      display: block;
    }
    h3 {
      margin: 0 0 0.5rem;
      font-size: var(--font-size-sm);
      font-weight: 600;
      color: var(--text-primary);
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.4rem;
    }
    li {
      display: flex;
      align-items: baseline;
      flex-wrap: wrap;
      gap: 0.5rem;
      padding: 0.4rem 0.5rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.4rem;
      background: var(--surface-2);
      font-size: var(--font-size-sm);
    }
    .cap-name {
      flex: 1 1 10rem;
      color: var(--text-primary);
    }
    .cap-status {
      flex: 1 1 auto;
      color: var(--text-secondary);
    }
    .cap-status[data-tone='ok'] {
      color: var(--text-success);
    }
    .cap-status[data-tone='warn'] {
      color: var(--text-warning);
    }
    .cap-remedy {
      flex: 0 0 auto;
    }
  `;

  private rows(): Row[] {
    // Keyword search needs no AI MODEL — but it does need the backend that answers the query.
    //
    // Tempdoc 807 §E.4: this row used to be a hardcoded `{ kind: 'available' }` that consulted
    // nothing, so with the backend gone the map asserted "Search (keyword) — Available" beside rows
    // reading "the connection to the search backend was lost" (round 14 named it the most misleading
    // instance of the retained-snapshot class). Gated on the SAME `snapshotLive` contact fact
    // `projectAvailability` consults, worded from the SAME `binding.unreachable` row the verdict and
    // the disconnection banner word themselves from — one authority, no re-authored string.
    //
    // Positive evidence only: a null store (the pre-first-poll boot window) is UNKNOWN, not lost —
    // mirroring `projectAvailability`, whose transient boot arm answers before its liveness gate.
    const rows: Row[] = [
      {
        label: 'Search (keyword)',
        availability:
          this.aiState?.snapshotLive === false
            ? unavailableBecause(reasonFor('binding.unreachable').wording)
            : this.aiState?.verdict?.reasons.includes('index.ownership_unproven')
              ? unavailableBecause(reasonFor('index.ownership_unproven').wording)
              : !this.aiState?.status || !this.aiState.indexingStatusLive ? unavailableBecause('Search status unconfirmed', true) : AVAILABLE,
      },
    ];
    for (const a of AFFORDANCES) {
      rows.push({ label: a.label, availability: projectAvailability(a.affordance, this.aiState) });
    }
    return rows;
  }

  /** The status phrase + a tone for colour. `ok` = usable, `warn` = usable-with-caveat, default = blocked. */
  private status(a: Availability): { text: string; tone: 'ok' | 'warn' | 'off'; remedy: NoticeRemedy | null } {
    switch (a.kind) {
      case 'available':
        return { text: 'Available', tone: 'ok', remedy: null };
      case 'degraded':
        return { text: `Available — ${a.caveat}`, tone: 'warn', remedy: a.remedy ?? null };
      case 'unavailable':
        return { text: a.reason, tone: a.transient ? 'warn' : 'off', remedy: a.remedy ?? null };
      case 'blocked':
        return { text: 'Unavailable', tone: 'off', remedy: null };
    }
  }

  private dispatchRemedy(remedy: NoticeRemedy): void {
    // Mirror jf-control's remedy dispatch: navigate to where the fix lives (an operation remedy points at
    // Health, where its consent/risk ceremony is), via the shell's one navigate-with-context seam.
    const target = remedy.kind === 'navigate' ? remedy.target : 'core.health-surface';
    this.dispatchEvent(
      new CustomEvent('navigate-with-context', {
        detail: { target, state: {} },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private renderRow(row: Row): TemplateResult {
    const s = this.status(row.availability);
    const remedyLabel = s.remedy ? (s.remedy.kind === 'navigate' ? s.remedy.label : 'Open Health') : null;
    return html`<li>
      <span class="cap-name">${row.label}</span>
      <span class="cap-status" data-tone=${s.tone}>${s.text}</span>
      ${s.remedy && remedyLabel
        ? html`<jf-control
            class="cap-remedy"
            .label=${remedyLabel}
            .onActivate=${() => s.remedy && this.dispatchRemedy(s.remedy)}
            >${remedyLabel}</jf-control
          >`
        : nothing}
    </li>`;
  }

  override render(): TemplateResult {
    return html`<h3>What you can do right now</h3>
      <ul aria-label="Capability availability">
        ${this.rows().map((r) => this.renderRow(r))}
      </ul>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-capability-map')) {
  customElements.define('jf-capability-map', CapabilityMap);
}
