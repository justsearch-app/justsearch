// SPDX-License-Identifier: Apache-2.0
/**
 * ToolCallCard — Lit block rendering one tool-call lifecycle stage.
 *
 * Originally extracted from AgentSurface in slice 491 G2; AgentSurface was
 * subsequently decomposed into AgentView + AgentSessionController in slice 495.
 * The parent view is now AgentView.
 *
 * Props:
 *   - `.toolCall: ToolCall` — the call to render
 *   - `.expanded: boolean` — collapsed/expanded toggle (reflected attribute)
 *   - `.evidencePaths: ReadonlySet<string> | null` — tempdoc 867: the run's evidence-path set, for a
 *     search card's level-2 body to say which of its own hits the run actually drew on. `null` means
 *     "the mount site did not wire this" (an older caller, or a test), distinct from an empty set
 *     ("wired, and nothing is in evidence yet") — see the accessory text below.
 *
 * Tempdoc 867 (L1+L2 slice) — ONE disclosure per card. The header row is the ENTIRE click/keyboard
 * target (no separate tiny expand button); RISK is no longer a header text badge — the glyph alone
 * carries status, and risk renders as its own row ONLY for MEDIUM/HIGH (border tint + the focusable
 * "why" disclosure are unaffected). A search card's body is no longer a nested `<jf-results-card>`
 * mount: it is the card's own level-2 body, plus a footer and an "Open in Search" pill.
 *
 * Tempdoc 871 §3b (owner batch, 2026-08-26) — three changes to that shape:
 *   - The header reads as the MODEL'S act: the VERB form (`composeToolLabel().verbLabel` — the one
 *     565 §12.3.B authority, so both windows get it) followed by the query in quotes, not the raw
 *     catalog name ("Searched “taxes”", not "Search Index taxes").
 *   - Level 2 is the FULL ranked list the model saw, capped at {@link SEARCH_ROW_CAP}, with the rows
 *     the run actually drew on marked (tint + filled dot + a `used` tag) — REVERSING 867's used-only
 *     summary, which hid the ranking the reader is trying to judge. Rows are two lines (title, then
 *     dim path + locator) so a path never ellipsizes against a locator on one jammed line.
 *   - Visible copy is "used", not "in evidence" (owner-settled; the internal register keeps the
 *     grounding authority's `inEvidence`/`evidenceCount` names — this is a copy decision, not a
 *     second axis).
 *
 * Tempdoc 550 C3: the per-card Approve/Reject buttons were removed. A tool call that needs
 * a human decision now routes through the ONE unified ceremony host
 * (`<jf-authorization-host>`, driven by `AgentSessionController` via the authorization
 * broker) — the same approve/deny surface used for gated effect/emission dispatches. The
 * card just shows "awaiting approval" while the ceremony is open.
 *
 * Invariants:
 *   - `pending` status shows an "awaiting approval" hint (no inline buttons).
 *   - Risk row (MEDIUM/HIGH only) tints the card border and explains itself on demand.
 *   - Executing state is carried by the status glyph alone (no visible status word — the header's
 *     accessible name carries it for AT).
 *   - Output panel only renders for status=completed with non-empty output (non-search tools only).
 *   - Collapsed state hides args/output/search body/actions (toggle via the header).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
// Tempdoc 565 §17 — the ONE run-step node primitive; its `running` glyph is the unified alive-indicator.
import './RunNode.js';
import type { ToolCall } from '../../controllers/AgentSessionController.js';
import type { StepPresentation } from '../../views/runStepPresentation.js';
// 543-fwd #2 — derive a plain-text "why" line from risk + the current dial level.
import { becauseLine, getAutonomyLevel } from '../../substrates/autonomy/index.js';
import { agentSearchCardProjection, type AgentSearchCardProjection } from './toolSearchCard.js';
// Tempdoc 577 §2.14 Root III (#18) — the ONE tool-output text-provenance authority (non-search only —
// tempdoc 867 removed the search-specific "evidence-lineage" header alongside the raw JSON dump).
import { toolOutputLineage, lineageFrameLabel } from './toolOutputLineage.js';
// Tempdoc 577 Ext I — presentedToolStatus folds the result outcome into the presented status word,
// read into the header's accessible name (867 removed the visible, inline-coloured status word — the
// glyph carries status now, so `statusAccent` is no longer read here; the ONE status → tone →
// accent-token authority (`statusTone.ts`) still colours the nested `<jf-run-node>` glyph directly).
import { presentedToolStatus } from '../../utils/statusTone.js';
import { composeToolLabel } from '../../display/toolLabeling.js';

export type { ToolCall, ToolRisk, ToolCallStatus } from '../../controllers/AgentSessionController.js';

/** Tempdoc 867 — a stable empty-set identity, so a search body's join never allocates for "nothing". */
const EMPTY_EVIDENCE_PATHS: ReadonlySet<string> = new Set();

/**
 * Tempdoc 871 §3b — level 2 renders the model's ranked list, capped here; anything past the cap is
 * counted in the footer, never rendered as a row. The cap survives the owner's reversal of 867's
 * used-only summary (raised 5 → 6): the card is still a reading-density projection, and the ranking
 * detail past the cap lives on the surface the reader can act on ("Open in Search", 871 §7).
 */
const SEARCH_ROW_CAP = 6;

/** `1 result` / `2 results` — the ONE pluralization for a hit count on this card. */
function countLabel(n: number, noun: string): string {
  return `${n} ${noun}${n === 1 ? '' : 's'}`;
}

export class ToolCallCard extends JfElement {
  static properties = {
    toolCall: { attribute: false },
    stepPresentation: { attribute: false },
    evidencePaths: { attribute: false },
    expanded: { type: Boolean, reflect: true },
    riskWhyOpen: { state: true },
  };

  declare toolCall: ToolCall | null;
  /** Tempdoc 565 §17 — the ONE run-step presentation descriptor (glyph + tone), passed by the mount
   * site (UnifiedChatView.renderToolActivity) so the card shows the SAME glyph the spine/trace do. */
  declare stepPresentation: StepPresentation | null;
  /**
   * Tempdoc 867 — the run's evidence-path set, for a search card's level-2 join. `null` when the
   * mount site never wired this property (renders the accessory's " · M used" segment absent — an
   * honest "we don't know", not a claimed zero — while the body still renders the full ranked list,
   * unmarked, rather than fail closed on the whole card).
   */
  declare evidencePaths: ReadonlySet<string> | null;
  declare expanded: boolean;
  /** Tempdoc 577 §2.12 Move 4 — the risk-tier explanation disclosure is OPEN (keyboard-reachable;
   * the explanation is no longer hover-`title`-only — §2.11 #11). */
  declare riskWhyOpen: boolean;

  /** Tempdoc 565 §3.C — once the user toggles, stop auto-collapsing on status change. */
  private userToggled = false;

  constructor() {
    super();
    this.toolCall = null;
    this.stepPresentation = null;
    this.evidencePaths = null;
    this.expanded = true;
    this.riskWhyOpen = false;
  }

  /**
   * Tempdoc 565 §3.C / 867 — keep the run compact: a card whose call is still in flight (pending /
   * proposed / executing / approved) auto-expands and settles back to collapsed once it completes
   * successfully. A FAILED terminal call stays expanded (577 Ext I — collapsing it would hide the one
   * signal that distinguishes it from a success in a compacted run). A user toggle pins the choice —
   * the system never overrides it again for this card's lifetime.
   */
  override updated(changed: Map<string, unknown>): void {
    if (this.userToggled || !changed.has('toolCall')) return;
    const status = this.toolCall?.status;
    const active = status === 'pending' || status === 'proposed' || status === 'executing' || status === 'approved';
    const failed = status === 'completed' && this.toolCall?.success === false;
    const shouldExpand = active || failed;
    if (this.expanded !== shouldExpand) this.expanded = shouldExpand;
  }

  static styles = css`
    :host {
      display: block;
      width: 100%;
    }
    .tool-card {
      max-width: 90%;
      padding: 0.625rem 0.75rem;
      background: var(--surface-secondary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.5rem;
      font-size: var(--font-size-sm);
      width: 100%;
    }
    .tool-card.high-risk {
      border-color: var(--accent-danger-45);
    }
    .tool-card.medium-risk {
      border-color: var(--accent-warning-45);
    }
    /* Tempdoc 867 — ONE disclosure: the whole header row is the click/keyboard toggle. */
    .tool-card-header {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      cursor: pointer;
      border-radius: 0.25rem;
      outline: none;
    }
    .tool-card-header:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: 2px;
    }
    .tool-card-lead {
      display: flex;
      align-items: center;
      min-width: 0;
    }
    /* Tempdoc 871 §3b — the VERB ("Searched"), bold, then the query. The old nowrap+ellipsis clipped
       the query mid-word on a narrow card; the owner's shape wraps instead (the header stays ONE
       logical line — it simply reflows), so the reader always sees the whole query. */
    .tool-name {
      font-weight: 600;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    /* Tempdoc 565 §12.3.B — the tool's target (query / filename), subdued after the label. */
    .tool-target {
      color: var(--text-secondary);
      font-weight: 400;
      margin-left: 0.4ch;
    }
    /* Tempdoc 867 — the muted right accessory (e.g. "3 results · 2 used"). */
    .tool-card-accessory {
      margin-left: auto;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      white-space: nowrap;
      flex: none;
    }
    .expand-chevron {
      flex: none;
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      transition: transform var(--duration-normal) var(--ease-standard);
    }
    @media (prefers-reduced-motion: reduce) {
      .expand-chevron { transition: none; }
    }
    /* Collapsed points along the reading direction (▶); expanded rotates it down (▼). The base
       glyph is ▶, so the EXPANDED state carries the rotation — live-caught 2026-08-26: the old
       rule (collapsed −90°) belonged to the retired ▼ base glyph and rendered collapsed as ▲. */
    :host([expanded]) .expand-chevron {
      transform: rotate(90deg);
    }
    /* Tempdoc 867 — the risk row: MEDIUM/HIGH only, below the header, unaffected by expand/collapse
       (it was always visible before too — only its position moved out of the header). */
    .risk-row {
      margin-top: 0.375rem;
    }
    .risk-word {
      cursor: help;
      text-decoration: underline dotted;
      text-underline-offset: 0.15em;
      background: none;
      border: none;
      padding: 0;
      margin: 0;
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }
    .risk-why {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-style: italic;
      margin: 0.25rem 0 0;
    }
    /* Tempdoc 565 §17 — the run-step glyph (the SAME <jf-run-node> the spine/trace show), sized inline. */
    .tool-status-glyph {
      display: inline-flex;
      width: 0.85rem;
      height: 0.85rem;
      flex: none;
      margin-right: 0.35rem;
    }
    /* Tempdoc 867 — the collapsible body, animated with the grid-template-rows 0fr/1fr technique so
       height animates without a measured pixel value. */
    .tool-card-body {
      display: grid;
      grid-template-rows: 1fr;
      transition: grid-template-rows var(--duration-normal) var(--ease-standard);
    }
    :host(:not([expanded])) .tool-card-body {
      grid-template-rows: 0fr;
    }
    @media (prefers-reduced-motion: reduce) {
      .tool-card-body { transition: none; }
    }
    .tool-card-body-inner {
      overflow: hidden;
      min-height: 0;
    }
    .tool-args {
      font-family: monospace;
      font-size: var(--font-size-xs);
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 0.25rem;
      max-height: 8rem;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      margin-top: 0.5rem;
    }
    .tool-output {
      font-family: monospace;
      font-size: var(--font-size-xs);
      padding: 0.5rem;
      background: var(--surface-tertiary);
      border-radius: 0.25rem;
      max-height: 12rem;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      margin-top: 0.5rem;
    }
    /* Tempdoc 577 §2.14 Root III (#18) — corpus-quoted output is framed as the user's documents
       quoted back (a left rule + a "Quoted from your documents" header), so citation/instruction-
       shaped text inside it reads as the documents' words, not the agent's own claim. */
    .tool-output.lineage-corpus-quoted {
      border-left: 3px solid var(--border-strong);
      background: var(--surface-2);
    }
    .lineage-frame-label {
      display: block;
      font-family: var(--font-display);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-bottom: 0.35rem;
      font-style: italic;
    }
    /* Tempdoc 878 §D.4 — the model saw less of this output than you are reading. Sits UNDER the
       output, because it is a qualification of what is above it rather than a heading for it. */
    .tool-output-model-note {
      font-family: var(--font-display);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.25rem;
    }
    /* Tempdoc 867 — the search card's level-2 evidence body. */
    .tool-search-body {
      margin-top: 0.5rem;
    }
    .search-scope {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-bottom: 0.375rem;
    }
    .search-rows {
      list-style: none;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 0.125rem;
    }
    /* Tempdoc 871 §3b — a two-LINE row. The single-line shape jammed title, path and locator into
       one flex line, so the path ellipsized mid-way while the locator floated right; the path now
       owns its own line under the title and only truncates when it genuinely cannot fit. */
    .search-row-open {
      display: flex;
      align-items: flex-start;
      gap: 0.4rem;
      width: 100%;
      text-align: left;
      background: none;
      border: none;
      padding: 0.25rem 0.3rem;
      border-radius: 0.25rem;
      font: inherit;
      font-size: var(--font-size-xs);
      /* The default row is a hit the run did NOT draw on: present, ranked, dimmed. */
      color: var(--text-secondary);
      cursor: pointer;
      min-width: 0;
    }
    /* A row the run actually drew on: tinted ground + full-strength text (the filled dot and the
       "used" tag carry the same fact for readers who cannot see the tint). */
    .search-row-open.used {
      background: var(--accent-tint-08);
      color: var(--text-primary);
    }
    .search-row-open:hover,
    .search-row-open:focus-visible {
      background: var(--surface-tertiary);
    }
    .search-row-open:focus-visible {
      outline: 2px solid var(--accent);
      outline-offset: -2px;
    }
    .search-row-dot {
      flex: none;
      width: 0.35rem;
      height: 0.35rem;
      margin-top: 0.45em;
      border: 1px solid currentColor;
      border-radius: 50%;
      background: transparent;
    }
    .search-row-open.used .search-row-dot {
      background: currentColor;
    }
    /* A used row's second line is NOT dimmed, and that is measured, not aesthetic: the tint lightens
       the ground just enough that --text-secondary lands at 4.31:1 on the dark window (Sv3Main's
       contrast oracle). The dimming axis is used-vs-unused — inside a used row the hierarchy is
       carried by weight and the locator's italic instead. */
    .search-row-open.used .search-row-path,
    .search-row-open.used .search-row-locator {
      color: inherit;
    }
    .search-row-lines {
      display: flex;
      flex-direction: column;
      gap: 0.0625rem;
      flex: 1 1 auto;
      min-width: 0;
    }
    .search-row-line {
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
      min-width: 0;
    }
    .search-row-filename {
      font-weight: 500;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      min-width: 0;
    }
    /* The small "used" tag — the accessible twin of the tint, in the muted accent. */
    .search-row-used-tag {
      flex: none;
      font-size: var(--font-size-xs);
      line-height: 1.4;
      padding: 0 0.4em;
      border-radius: 999px;
      background: var(--accent-tint-16);
      color: var(--text-tint);
    }
    .search-row-path {
      color: var(--text-secondary);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
      min-width: 0;
    }
    .search-row-locator {
      color: var(--text-secondary);
      font-style: italic;
      flex: none;
    }
    .search-more {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-top: 0.375rem;
    }
    .search-open-in-search {
      margin-top: 0.5rem;
      padding: 0.25rem 0.75rem;
      border-radius: 999px;
      border: 1px solid var(--border-subtle);
      background: var(--surface-tertiary);
      color: var(--text-primary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .search-open-in-search:hover {
      background: var(--surface-2);
    }
    .tool-rich-content {
      margin-top: 0.5rem;
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .tool-image {
      min-width: 48px;
      max-width: 100%;
      max-height: 16rem;
      border-radius: 0.25rem;
      align-self: flex-start;
      /* Tiny images (e.g. an MCP icon) stay visible when upscaled to the min size. */
      image-rendering: pixelated;
    }
    .tool-resource a {
      font-size: var(--font-size-xs);
      color: var(--accent);
      word-break: break-all;
    }
    .rejected-reason {
      color: var(--text-warning);
      font-size: var(--font-size-xs);
      margin-top: 0.5rem;
    }
    /* 543-fwd #2 — deterministic "because" line explaining the gate decision. */
    .because {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      margin-top: 0.5rem;
      font-style: italic;
    }
    /* dot-pulse animation moved to <jf-pulse-dots> shared component */
  `;

  private toggleExpanded(): void {
    this.userToggled = true;
    this.expanded = !this.expanded;
  }

  private onHeaderKeydown(e: KeyboardEvent): void {
    if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') {
      e.preventDefault();
      this.toggleExpanded();
    }
  }

  override render(): TemplateResult {
    const tc = this.toolCall;
    if (!tc) return html``;
    const isPending = tc.status === 'pending';
    const isCompleted = tc.status === 'completed';
    const isRejected = tc.status === 'rejected';
    const riskClass =
      tc.risk === 'HIGH' ? 'high-risk' : tc.risk === 'MEDIUM' ? 'medium-risk' : '';
    const showRisk = tc.risk === 'HIGH' || tc.risk === 'MEDIUM';
    // Tempdoc 871 §3b — the header is the model's ACT ("Searched"), from the ONE 565 §12.3.B label
    // authority, so the unified window and SV3 read identically. A SEARCH target is a query, so it
    // is quoted (a filename target is not — quoting a path would read as a literal, not a name).
    const { verbLabel, target } = composeToolLabel(tc.toolName, tc.arguments);
    const quotedTarget = verbLabel === 'Searched' && target ? `“${target}”` : target;
    // Tempdoc 867 — the search projection now carries the level-2 body's own data (hits joined
    // against the run's evidence set); null when there is no evidence (or, the honest old-record
    // edge case, no derivable query) so the raw-output path below renders instead of a fabrication.
    const searchProjection = isCompleted
      ? agentSearchCardProjection(tc.structuredData, tc.arguments, this.evidencePaths ?? EMPTY_EVIDENCE_PATHS)
      : null;
    const accessory = searchProjection ? this.searchAccessory(searchProjection) : '';
    const presentedStatus = presentedToolStatus(tc.status, tc.success);
    const headerLabel = `${this.expanded ? 'Collapse' : 'Expand'} ${verbLabel}${target ? ` ${target}` : ''} — ${presentedStatus}`;
    return html`
      <div class="tool-card ${riskClass}">
        <div
          class="tool-card-header"
          role="button"
          tabindex="0"
          aria-expanded=${this.expanded ? 'true' : 'false'}
          aria-label=${headerLabel}
          data-testid="tool-card-header"
          @click=${() => this.toggleExpanded()}
          @keydown=${(e: KeyboardEvent) => this.onHeaderKeydown(e)}
        >
          <span class="tool-card-lead">
            ${this.stepPresentation
              ? html`<jf-run-node
                  class="tool-status-glyph"
                  density="full"
                  .presentation=${this.stepPresentation}
                ></jf-run-node>`
              : nothing}
            <span class="tool-name"
              ><span class="tool-verb" data-testid="tool-card-verb">${verbLabel}</span
              >${target
                ? html`<span class="tool-target" data-testid="tool-card-target">${quotedTarget}</span>`
                : nothing}</span
            >
          </span>
          ${accessory
            ? html`<span class="tool-card-accessory" data-testid="tool-card-accessory">${accessory}</span>`
            : nothing}
          <span class="expand-chevron" aria-hidden="true">▶</span>
        </div>
        ${showRisk ? this.renderRiskRow(tc) : nothing}
        <div class="tool-card-body">
          <div class="tool-card-body-inner">
            ${tc.arguments && !searchProjection
              ? html`<div class="tool-args">${tc.arguments}</div>`
              : nothing}
            ${/* Tempdoc 577 §2.14 Root III (#18): frame raw tool output by its backend-stamped lineage
                — corpus-quoted text is the user's documents quoted back. A search card's evidence is
                framed by the level-2 body itself (867), not this raw-output panel. */ ''}
            ${isCompleted && tc.output && !searchProjection
              ? this.renderLineageFramedOutput(tc)
              : nothing}
            ${searchProjection ? this.renderSearchBody(searchProjection) : nothing}
            ${/* Tempdoc 878 §D.4 — the model-visibility note sits OUTSIDE the two body branches,
                because it is a fact about the tool RESULT and not about the raw-output panel. Riding
                it on the lineage frame left the search card — the bulkiest output, and so the one
                most likely to be cut — the only card that could be truncated silently. */ ''}
            ${isCompleted ? this.renderModelVisibilityNote(tc) : nothing}
            ${isCompleted ? this.renderRichContent(tc) : nothing}
            ${isRejected
              ? html`<div class="rejected-reason">
                  Rejected${tc.rejectReason ? html`: ${tc.rejectReason}` : nothing}
                </div>`
              : nothing}
            ${isPending
              ? html`
                  <div class="because" data-testid="tool-call-because">
                    ${/* Tempdoc 561 P-D1: explain the BACKEND gate verdict when present. */ ''}
                    ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}
                  </div>
                  <div class="rejected-reason" data-testid="awaiting-approval">
                    Awaiting your approval…
                  </div>
                `
              : nothing}
          </div>
        </div>
      </div>
    `;
  }

  /** Tempdoc 867/871 — "N results · M used"; the used segment omits when the run's evidence set was
   * never wired to this card (an honest "we don't know", not a claimed zero). */
  private searchAccessory(projection: AgentSearchCardProjection): string {
    const results = countLabel(projection.resultCount, 'result');
    if (this.evidencePaths === null) return results;
    return `${results} · ${projection.evidenceCount} used`;
  }

  /**
   * Tempdoc 871 §3b — the scope/filters line: the facts of the CALL, always rendered at level 2.
   *   - the folder restriction, or "all folders" when the call carried none (that IS a fact of the
   *     call's arguments — the absence of `path_prefix` means corpus-wide, not "unknown");
   *   - the RESOLVED pipeline preset, when the record carries one (867 §2a's named gap: a record
   *     persisted before that stamp simply does not say, and the segment is omitted, never guessed);
   *   - the `limit` the call explicitly asked for (omitted when it asked for none — the effective
   *     default is a config fact the record does not carry).
   * The query moved to the header (871 §3b) and the count to the accessory, so neither repeats here.
   */
  private searchScopeLine(projection: AgentSearchCardProjection): string {
    const segments = [projection.scope || 'all folders'];
    if (projection.mode) segments.push(projection.mode);
    if (projection.limit !== null) segments.push(`limit ${projection.limit}`);
    return segments.join(' · ');
  }

  /**
   * Tempdoc 867 — the risk row: MEDIUM/HIGH only, a focusable disclosure explaining the tier via the
   * SAME `becauseLine` authority, reachable by keyboard/AT (§2.11 #11 / §2.12 Move 4).
   */
  private renderRiskRow(tc: ToolCall): TemplateResult {
    return html`<div class="risk-row">
      <button
        type="button"
        class="risk-word"
        aria-expanded=${this.riskWhyOpen ? 'true' : 'false'}
        aria-label=${`Risk tier ${tc.risk}. ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}`}
        @click=${() => {
          this.riskWhyOpen = !this.riskWhyOpen;
        }}
        >${tc.risk}</button
      >
      ${this.riskWhyOpen
        ? html`<div class="risk-why" role="note">
            ${becauseLine(tc.risk, getAutonomyLevel(), tc.gateBehavior)}
          </div>`
        : nothing}
    </div>`;
  }

  /**
   * Tempdoc 871 §3b — the search tool card's level-2 body: the scope/filters line, then the model's
   * FULL ranked list (capped at {@link SEARCH_ROW_CAP}) with the rows the run drew on marked, a
   * footer counting what the cap hid, and an "Open in Search" pill (852 owns the actual navigation —
   * this only dispatches the intent).
   *
   * This REVERSES 867's used-only summary (owner, 2026-08-26): filtering to the used rows hid the
   * ranking the reader is trying to judge — whether the model's search was any good is a fact about
   * the whole list, not about its accepted subset. Every row stays clickable, used or not.
   *
   * The footer counts what is BEYOND THE CAP, and its used/not-used split is composed from the hits
   * this card actually observed, never inferred:
   *   - `K more used`   = hits past the cap whose path is in the run's evidence set;
   *   - `J more retrieved, not used` = the remaining hidden total (`resultCount` − rendered − K).
   * When the evidence set was never wired, the card does not know which hidden hits were used, so
   * the footer says `N more results` rather than claiming any of them went unused.
   *
   * The per-row open path is UNCHANGED in contract: clicking a row fires the same `card-open`
   * CustomEvent (bubbles, composed, `{id}`) the old nested `<jf-results-card>` excerpt rows fired, so
   * the mount site's existing `findAgentSearchHit(structuredData, id)` resolution keeps working.
   */
  private renderSearchBody(projection: AgentSearchCardProjection): TemplateResult {
    const rows = projection.hits.slice(0, SEARCH_ROW_CAP);
    const hiddenUsed = projection.hits.slice(SEARCH_ROW_CAP).reduce((n, h) => (h.inEvidence ? n + 1 : n), 0);
    const hiddenTotal = Math.max(0, projection.resultCount - rows.length);
    const hiddenOther = Math.max(0, hiddenTotal - hiddenUsed);
    const footerSegments =
      this.evidencePaths === null
        ? [hiddenTotal > 0 ? `${hiddenTotal} more ${hiddenTotal === 1 ? 'result' : 'results'}` : null]
        : [
            hiddenUsed > 0 ? `${hiddenUsed} more used` : null,
            hiddenOther > 0 ? `${hiddenOther} more retrieved, not used` : null,
          ];
    const footerText = footerSegments.filter((s): s is string => s !== null).join(' · ');
    const scopeText = this.searchScopeLine(projection);
    return html`<div class="tool-search-body" data-testid="tool-search-body">
      <div class="search-scope" data-testid="tool-search-scope">${scopeText}</div>
      ${rows.length > 0
        ? html`<ul class="search-rows" data-testid="tool-search-rows">
            ${rows.map(
              (hit) => html`<li>
                <button
                  type="button"
                  class="search-row-open ${hit.inEvidence ? 'used' : ''}"
                  data-testid="tool-search-row"
                  data-used=${hit.inEvidence ? 'true' : 'false'}
                  @click=${() => this.openSearchHit(hit.id)}
                >
                  <span class="search-row-dot" aria-hidden="true"></span>
                  <span class="search-row-lines">
                    <span class="search-row-line" data-testid="tool-search-row-title">
                      <span class="search-row-filename">${hit.title}</span>
                      ${hit.inEvidence
                        ? html`<span class="search-row-used-tag" data-testid="tool-search-row-used"
                            >used</span
                          >`
                        : nothing}
                    </span>
                    <span class="search-row-line" data-testid="tool-search-row-path">
                      <span class="search-row-path">${hit.path}</span>
                      ${hit.locator
                        ? html`<span class="search-row-locator">${hit.locator}</span>`
                        : nothing}
                    </span>
                  </span>
                </button>
              </li>`,
            )}
          </ul>`
        : nothing}
      ${footerText
        ? html`<div class="search-more" data-testid="tool-search-more">${footerText}</div>`
        : nothing}
      <button
        type="button"
        class="search-open-in-search"
        data-testid="tool-search-open-in-search"
        @click=${() => this.openInSearch(projection.query, projection.scope)}
      >
        Open in Search
      </button>
    </div>`;
  }

  /** Tempdoc 867 — preserves the existing `card-open` contract (bubbles + composed, `{id}`). */
  private openSearchHit(id: string): void {
    this.dispatchEvent(
      new CustomEvent('card-open', { detail: { id }, bubbles: true, composed: true }),
    );
  }

  /** Tempdoc 867 — the "Open in Search" intent. No navigation wired here; 852 owns the landing. */
  private openInSearch(query: string, scope: string): void {
    this.dispatchEvent(
      new CustomEvent('tool-card-open-search', {
        detail: { query, scope },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private renderLineageFramedOutput(tc: ToolCall): TemplateResult {
    const lineage = toolOutputLineage(tc.structuredData);
    const label = lineageFrameLabel(lineage);
    // Runtime output (the default) renders byte-identically to before — no frame, no extra nodes.
    // Only a backend-stamped corpus-quoted excerpt gets the quoting frame + header.
    const body =
      label === null
        ? html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}>${tc.output}</div>`
        : html`<div class="tool-output lineage-${lineage}" data-lineage=${lineage}><span
              class="lineage-frame-label"
              data-testid="tool-output-lineage"
              >${label}</span
            >${tc.output}</div>`;
    return body;
  }

  /**
   * Tempdoc 878 §D.4 — say when the MODEL received less of this output than the reader is seeing.
   *
   * The panel above shows what the tool returned; the agent loop appends a truncated copy to the
   * prompt. Both are honest answers to "what was the output", and until the backend labelled them
   * the card silently gave the first while the agent worked from the second — so a reader debugging
   * a wrong answer was looking at evidence the model never had.
   *
   * Renders NOTHING in two distinct cases, and the distinction is the point: the model got all of it
   * (nothing to disclose), or nobody measured (`outputCharsToModel` absent — say nothing rather than
   * imply completeness). Records written before the backend carried this field fall in the second.
   */
  private renderModelVisibilityNote(tc: ToolCall): TemplateResult | typeof nothing {
    if (tc.truncatedForModel !== true || tc.outputCharsToModel === undefined) return nothing;
    const total = (tc.output ?? '').length;
    // 'en-US' explicitly, not the host locale: the shell ships English copy, and a German host would
    // otherwise render "4.000" — which reads as four, not four thousand, in the sentence around it.
    // Same reasoning the backend applies to its own grouped numbers.
    const grouped = (n: number): string => n.toLocaleString('en-US');
    return html`<div class="tool-output-model-note" data-testid="tool-output-model-note">
      The model received the first ${grouped(tc.outputCharsToModel)} of ${grouped(total)} characters
      of this output.
    </div>`;
  }

  /**
   * Tempdoc 560 Phase 1 — render non-text result blocks (e.g. an MCP tool's image / embedded
   * resource) carried in {@code structuredData.mcpContent}. Text blocks already render via `output`;
   * here we surface images (base64 data URI) and resource links so multimodal tool results are not
   * silently dropped.
   */
  private renderRichContent(tc: ToolCall): TemplateResult | typeof nothing {
    const content = tc.structuredData?.['mcpContent'];
    if (!Array.isArray(content)) return nothing;
    const rich = (content as Array<Record<string, unknown>>).filter((b) => b['type'] !== 'text');
    if (rich.length === 0) return nothing;
    return html`<div class="tool-rich-content" data-testid="tool-rich-content">
      ${rich.map((b) => {
        const type = b['type'] as string;
        if (type === 'image' && typeof b['data'] === 'string') {
          const mime = (b['mimeType'] as string) || 'image/png';
          return html`<img
            class="tool-image"
            alt="MCP tool image result"
            src=${`data:${mime};base64,${b['data'] as string}`}
          />`;
        }
        if (type === 'resource' && typeof b['uri'] === 'string') {
          return html`<div class="tool-resource">
            <a href=${b['uri'] as string} target="_blank" rel="noopener">${b['uri']}</a>
          </div>`;
        }
        return nothing;
      })}
    </div>`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-tool-call-card')) {
  customElements.define('jf-tool-call-card', ToolCallCard);
}
