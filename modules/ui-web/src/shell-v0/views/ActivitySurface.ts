// SPDX-License-Identifier: Apache-2.0
/**
 * ActivitySurface — Lit-side Activity surface (slice 486 F15-narrow).
 *
 * Self-mounting Surface backed by the existing
 * `core.operation-history` EVENT_STREAM Resource (slice 444b
 * substrate). The Resource ships with a specialty renderer
 * (`<jf-table>` via `kind="operation-history"` per
 * `renderers/resourceRegistryDefaults.ts`) so this surface is
 * a thin chrome wrapper: a header + the existing
 * `<jf-resource-view>` substrate consumer.
 *
 * Side-effect registers `<jf-activity-surface>` for the chrome
 * dispatcher.
 *
 * Shows the latest operation-history snapshot from retained operations rows,
 * followed by live updates. Resource-declared operation keys merge replay overlap.
 * Unkeyed observations remain independent; filtering and export are not provided.
 *
 * Backend declaration: `CoreSurfaceCatalog.ACTIVITY_SURFACE_ID`
 * (`core.activity-surface`) → mountTag `jf-activity-surface`,
 * Audience.USER, Placement.DEEPLINK, consumes
 * `core.operation-history`.
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceLayoutStyles } from '../primitives/surfaceLayout.js';
import '../components/ResourceView.js';
// Tempdoc 550 thesis II: mount the unified action-ledger read-view (the Outcome face — one
// chronological, originator-attributed, LIVE stream over operations + navigations + gates + FE
// effects). Side-effect import registers <jf-action-ledger>.
import '../components/ActionLedgerView.js';

export class ActivitySurface extends JfElement {
  static properties = {
    apiBase: { type: String, attribute: 'api-base' },
  } as const;

  declare apiBase: string;

  constructor() {
    super();
    this.apiBase = '';
  }

  // Tempdoc 559 Authority I: the :host / .header / .body region contract is
  // owned by surfaceLayoutStyles (the one layout authority); only bespoke rules
  // live here.
  static styles = [
    surfaceLayoutStyles,
    css`
    jf-resource-view {
      display: block;
      width: 100%;
    }
    jf-action-ledger {
      display: block;
      width: 100%;
    }
    .section-label {
      margin: 0.75rem 0 0.25rem 0;
      font-size: var(--font-size-xs);
      font-weight: 600;
      letter-spacing: 0.03em;
      text-transform: uppercase;
      color: var(--text-secondary);
    }
    .section-label:first-child {
      margin-top: 0;
    }
  `,
  ];

  override render(): TemplateResult {
    return html`
      <div class="header">
        <!-- Tempdoc 571 §11 / 578: shell topbar owns the page <h1>; <h2> here so this TRUST surface
             is embeddable as a host member (System) without emitting a second <h1>. -->
        <h2>Activity</h2>
        <div class="sub">
          What the system did — a structured audit of operations (button
          clicks, agent calls, scheduled runs) with outcomes and timing.
          Recent activity and retained operation history, updated live.
          For raw diagnostic output, see Logs.
        </div>
      </div>
      <div class="body">
        <!-- Tempdoc 550 thesis II (Outcome read-view): the unified, LIVE action-ledger —
             one chronological, originator-attributed stream over operations + navigations +
             trust-gate firings + FE-local effects. This is the 550 Outcome face. -->
        <div class="section-label">Unified activity (live)</div>
        <jf-action-ledger
          api-base=${this.apiBase}
          data-testid="activity-surface-action-ledger"
        ></jf-action-ledger>

        <!-- Tempdoc 511 Phase 6: <jf-resource> aggregate component
             dispatches via the (Resource, list-item) canonical
             strategy, which wraps <jf-resource-view> with audience
             gating + wire-metadata data attributes. Retained as the
             structured operations table (core.operation-history). -->
        <div class="section-label">Operations (structured)</div>
        <jf-resource
          context="list-item"
          resource-id="core.operation-history"
          api-base=${this.apiBase}
          data-testid="activity-surface-resource-view"
        ></jf-resource>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-activity-surface')) {
  customElements.define('jf-activity-surface', ActivitySurface);
}
