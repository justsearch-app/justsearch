// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-task-list> — Tempdoc 543 §32 R-E1, redesigned by tempdoc 813 §5.
 *
 * The default view is ONE compact aggregate card — phase label, a progress affordance with a
 * FAITHFUL denominator (or none), the counts line, and a coarse indicative estimate when one is
 * honest. The per-file rows the panel used to lead with are now an opt-in disclosure ("Details"):
 * at ~1 s/file a live per-file list is noise, and it was the panel's whole reason to grow tall
 * enough to occlude the rail's bottom controls.
 *
 * 813 §20 puts TWO layers behind that one card: the surface states the CAPABILITY tier (what works
 * now, how far the whole enrichment blend has come), and the disclosure lists the MACHINE stages
 * that add up to it. Every number in the disclosure is a narrower scope of the same projection —
 * `enrichingStages` is literally the row set the surface percent sums — so the detail tier can
 * never contradict the headline (§3b).
 *
 * Two transports, two subjects, no overlap (813 §3b / §13):
 *  - the AGGREGATE numbers come from the ONE indexing-progress projection over the `/api/status`
 *    poll snapshot (`selectIndexingProgress`) — this component derives no count of its own;
 *  - the per-file ROW list stays the `core.indexing-jobs` SSE mirror (`tasks` substrate), with its
 *    existing bounded projection (queued collapsed to a count) and its own stall honesty.
 *
 * Because the aggregate reads the poll and not the row feed, the panel renders during `enriching`
 * even when the SSE task list is EMPTY — closing §1d's absence-as-claim defect, where "the panel
 * disappeared" was the only completion signal the product ever gave and it fired while the
 * semantic layers were still catching up.
 *
 * Floats lower-LEFT (the OverlayHost `bottom-left` slot, whose reserved band keeps it clear of the
 * rail; 559 Authority I owns the placement).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import {
  listTasks,
  cancelTask,
  clearFinishedTasks,
  subscribeTasks,
  isTerminalStatus,
  type Task,
  type TaskStatus,
} from '../substrates/tasks/index.js';
import { countByKey, capWithOverflow } from '../projections/boundedProjection.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import { subscribeFeedStalled } from '../substrates/tasks/indexingJobsBridge.js';
import { subscribeAiState } from '../state/aiStateStore.js';
import {
  selectIndexingProgress,
  indexingTaskStatus,
  type EnrichingStageRow,
  type IndexingProgress,
} from '../state/indexingProgress.js';
import { humanizeSeconds } from '../state/startupEstimate.js';
import { ENRICHMENT_BLOCKED_BODY, ENRICHMENT_BODY } from '../state/enrichmentCoverage.js';
import { indexingQueueCounts } from '../state/indexingQueueCounts.js';
import { formatBytes, formatCount } from '../display/format.js';

/**
 * Bounded projection (tempdoc 550 Thesis III). Inside the disclosure the rail shows a per-status
 * summary, then lists individual rows for the actionable/recent tasks up to a total cap — but the
 * `queued` bulk (the F-1 flood source: a backlog of waiting indexing jobs) is collapsed to a COUNT
 * only, never N individual pills. Running / failed / succeeded / cancelled are naturally few (agent
 * ops + failures) and stay individually visible for feedback, capped with a "+N more" overflow.
 */
const MAX_DETAIL_ROWS = 8;
const COUNT_ORDER: readonly TaskStatus[] = [
  'running',
  'queued',
  'failed',
  'succeeded',
  'cancelled',
];

/**
 * 813 §5 / §14.3 — how long the terminal "Ready" state stays up before the panel dismisses itself.
 * The STATE is the completion signal (decision §14.3 deliberately chose state over a toast, since
 * the toast stack is still defective), so it must be visible long enough to be read, then get out
 * of the way. Only shown when the panel was already up and ACTIVE — an idle session never flashes
 * a completion it did not witness.
 */
export const READY_DISMISS_MS = 6000;

/**
 * The Indexing phase's ONE fact row (813 §19 W4): the file backlog, its BYTE weight when the
 * projection has a faithful one (813 Slice B), and the coarse estimate when one is honest — merged
 * into a single line rather than stacked, because the panel's height is what occludes the rail's
 * bottom controls.
 *
 * The weight is the answer to "why is '12 files remaining' taking so long" on a mixed corpus — a
 * single 2 GB video and a 2 KB note are both "1 file". Every segment past the first is APPENDED,
 * never substituted, and each is withdrawn ENTIRELY (no placeholder, no "0 B") whenever the
 * projection says it has no honest basis for it — so an absent estimate is an absent segment.
 */
export function indexingCountsLine(p: IndexingProgress): string {
  const files = `${formatCount(p.jobsPending)} ${p.jobsPending === 1 ? 'file' : 'files'} remaining`;
  const weighed = p.pendingBytes === null ? files : `${files} · ${formatBytes(p.pendingBytes)}`;
  return p.etaSeconds === null ? weighed : `${weighed} · ~${humanizeSeconds(p.etaSeconds)} left`;
}

/**
 * The accessible/hover form of {@link indexingCountsLine} — the same facts plus the estimate's
 * INDICATIVE qualifier, which 813 §19 (W4) moved off the visible line to keep the row to one line
 * without dropping the honesty it carries. `null` when there is no estimate, so the surface attaches
 * no label at all rather than one that repeats the visible text.
 */
export function indexingCountsLabel(p: IndexingProgress): string | null {
  return p.etaSeconds === null ? null : `${indexingCountsLine(p)} at the current rate`;
}

/**
 * The Enriching phase's fact row (813 §20): the coverage percent when the projection has a faithful
 * one, the shared caveat, and the enrichment estimate when the projection has an honest basis for
 * one — built with the same segment discipline as {@link indexingCountsLine}, including the same
 * humanizer, so the two phases speak one dialect.
 *
 * The percent here is the CAPABILITY-tier number (how far the whole blend has come). The per-stage
 * breakdown that adds up to it lives in the disclosure — §20's two layers.
 */
export function enrichingCountsLine(p: IndexingProgress): string {
  const base =
    p.enrichingPercent === null ? ENRICHMENT_BODY : `${p.enrichingPercent}% · ${ENRICHMENT_BODY}`;
  return p.enrichingEtaSeconds === null
    ? base
    : `${base} · ~${humanizeSeconds(p.enrichingEtaSeconds)} left`;
}

/** {@link enrichingCountsLine} plus the INDICATIVE qualifier (§19 W4's accessible-label form). */
export function enrichingCountsLabel(p: IndexingProgress): string | null {
  return p.enrichingEtaSeconds === null ? null : `${enrichingCountsLine(p)} at the current rate`;
}

/**
 * 813 §20 — the USER words for the machine stages the disclosure lists. Local literals on purpose
 * (§19 W1): this is the only surface that renders per-stage enrichment detail, and a shared constant
 * without a second consumer is a fork waiting to happen. The wire/machine names (`splade`, `ner`)
 * stay on the projection's stage ids; they are already shown as capability chips elsewhere
 * (`display/facts.ts`), where the subject is "is this engine present?", not "how far along is it?".
 */
const STAGE_LABELS: Record<EnrichingStageRow['id'], string> = {
  embedding: 'Semantic vectors',
  splade: 'Keyword expansion',
  ner: 'Entity recognition',
  chunkVectors: 'Passage vectors',
};

/** The empty projection this component starts from, before the first `/api/status` poll lands. */
const NO_PROGRESS = selectIndexingProgress(null, false, 0, []);

export class TaskList extends JfElement {
  static properties = {
    tasks: { state: true },
    feedStalled: { state: true },
    progress: { state: true },
    queueCounts: { state: true },
    filesOpen: { state: true },
    showingReady: { state: true },
  };

  declare tasks: readonly Task[];
  /** 595 §4.4 — the live jobs feed (SSE) went quiet while work is in-flight. */
  declare feedStalled: boolean;
  /** 813 §3b — the ONE derivation authority for the aggregate numbers. */
  declare progress: IndexingProgress;
  /** 813 §5 — the per-file rows are opt-in; collapsed is the default view. */
  declare filesOpen: boolean;
  /** 813 §5 — the brief terminal state, shown only after a witnessed active phase. */
  declare showingReady: boolean;

  private unsub: (() => void) | null = null;
  private feedUnsub: (() => void) | null = null;
  private aiUnsub: (() => void) | null = null;
  private readyTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    super();
    this.queueCounts = null;
    this.tasks = listTasks();
    this.feedStalled = false;
    this.progress = NO_PROGRESS;
    this.filesOpen = false;
    this.showingReady = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.tasks = listTasks();
    this.unsub = subscribeTasks(() => {
      this.tasks = listTasks();
    });
    this.feedUnsub = subscribeFeedStalled((stalled) => {
      this.feedStalled = stalled;
    });
    this.aiUnsub = subscribeAiState((s) => {
      this.queueCounts = indexingQueueCounts(s.status);
      this.applyProgress(
        selectIndexingProgress(
          s.status,
          s.indexingStatusLive,
          s.episodeMaxPendingJobs,
          s.enrichSettleSamples,
        ),
      );
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsub?.();
    this.unsub = null;
    this.feedUnsub?.();
    this.feedUnsub = null;
    this.aiUnsub?.();
    this.aiUnsub = null;
    this.clearReadyTimer();
  }

  private clearReadyTimer(): void {
    if (this.readyTimer !== null) {
      clearTimeout(this.readyTimer);
      this.readyTimer = null;
    }
  }

  /**
   * Apply a fresh projection, opening the terminal window on the active→TERMINAL EDGE.
   *
   * The edge, not the level: `ready` is also the steady state of an idle, fully-searchable index,
   * and a panel that popped up to say "Ready" whenever a poll landed would be claiming a completion
   * it never observed happening. The window is armed only when this component actually saw the
   * active phase it is now reporting the end of.
   *
   * Round-15 F1: `blocked` shares the window rather than counting as active work. It IS the end of
   * the indexing run (nothing is running), so it gets the same brief terminal card — with different
   * words. Treating it as active instead would leave the overlay panel up forever on an install
   * that has no embedding model, which is the opposite failure.
   */
  private applyProgress(next: IndexingProgress): void {
    const wasActive = isActivePhase(this.progress.phase);
    this.progress = next;
    if (wasActive && isTerminalPhase(next.phase)) {
      this.clearReadyTimer();
      this.showingReady = true;
      this.readyTimer = setTimeout(() => {
        this.readyTimer = null;
        this.showingReady = false;
      }, READY_DISMISS_MS);
    } else if (isActivePhase(next.phase) && this.showingReady) {
      // Work resumed inside the window — the completion claim is no longer true, so withdraw it.
      this.clearReadyTimer();
      this.showingReady = false;
    }
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost bottom-left slot. */
      max-width: 24rem;
      pointer-events: none;
    }
    :host([data-empty]) {
      display: none;
    }
    .panel {
      pointer-events: auto;
      background: var(--surface-1);
      border: 1px solid var(--border-default);
      border-radius: 0.5rem;
      padding: 0.5rem 0.75rem;
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
    }
    .title {
      font-weight: 600;
    }
    .task {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
    }
    .task-row {
      display: flex;
      align-items: center;
      gap: 0.5rem;
    }
    .status {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.1rem 0.4rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .status.queued {
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .status.running {
      background: var(--accent-info);
      color: #fff;
    }
    .summary {
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem;
      align-items: center;
    }
    .count {
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.05em;
      padding: 0.1rem 0.4rem;
      border-radius: 9999px;
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .count.running {
      background: var(--accent-info);
      color: var(--accent-on-chat);
    }
    .count.failed {
      background: var(--accent-danger);
      color: var(--accent-on-danger);
    }
    .more {
      font-size: var(--font-size-xs);
      color: var(--text-tertiary);
      padding: 0.1rem 0;
    }
    .feed-stalled {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.1rem 0;
    }
    .status.succeeded {
      background: var(--accent-success);
      color: var(--accent-on-success);
    }
    .status.failed {
      background: var(--accent-danger);
      color: var(--accent-on-danger);
    }
    .status.cancelled {
      background: var(--surface-2);
      color: var(--text-tertiary);
    }
    .label {
      flex: 1;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    /* Tempdoc 609 §R (T1.3) — navigable label (return to the job's origin surface). */
    .label-link {
      text-align: left;
      background: transparent;
      border: none;
      color: inherit;
      font: inherit;
      padding: 0;
      cursor: pointer;
      text-decoration: underline;
      text-decoration-style: dotted;
    }
    .label-link:hover,
    .label-link:focus-visible {
      text-decoration-style: solid;
      outline: 2px solid transparent;
    }
    .bar {
      height: 4px;
      background: var(--surface-2);
      border-radius: 2px;
      overflow: hidden;
    }
    .bar > span {
      display: block;
      height: 100%;
      background: var(--accent-info);
    }
    /* 813 §5 — the INDETERMINATE track: the Indexing phase has a remaining count but no faithful
       TOTAL (pendingJobs is a backlog, not a fraction; the admitted-files denominator lives on the
       per-scan SSE, not on this snapshot), so the affordance shows activity WITHOUT implying a
       position. Deliberately static, not an animated sweep: the ui-shot captures must stay
       byte-stable, and a moving bar would encode a frame number into every screenshot. */
    .bar.indeterminate > span {
      width: 100%;
      background: repeating-linear-gradient(
        135deg,
        var(--accent-info) 0,
        var(--accent-info) 4px,
        var(--surface-2) 4px,
        var(--surface-2) 8px
      );
    }
    /* 813 §5 — the aggregate card is the default view; the row list is behind the disclosure. */
    .aggregate {
      display: flex;
      flex-direction: column;
      gap: 0.35rem;
    }
    .counts {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .disclose {
      align-self: flex-start;
      background: transparent;
      border: none;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      padding: 0.1rem 0;
      cursor: pointer;
      text-decoration: underline;
      text-decoration-style: dotted;
    }
    .disclose:hover,
    .disclose:focus-visible {
      color: var(--text-primary);
      text-decoration-style: solid;
    }
    .files {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
    }
    /* 813 §20 — a machine-stage readout row: name left, its own fraction right. Same type scale and
       tone as the counts line, so the disclosure reads as detail UNDER the card, not as a second
       card of its own. */
    .stage {
      display: flex;
      align-items: baseline;
      justify-content: space-between;
      gap: 0.75rem;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
    }
    .stage-count {
      white-space: nowrap;
    }
    /* 574 B (remediation) — cancel/clear are jf-button(sm) atoms now. */
  `;

  updated(): void {
    if (this.isEmpty()) this.setAttribute('data-empty', '');
    else this.removeAttribute('data-empty');
  }

  /**
   * The panel withdraws only when it has nothing true to say: no tasks in the SSE mirror, no active
   * indexing/enrichment on the poll snapshot, and no terminal state still being read. Note the
   * middle clause — before 813 the panel hid at zero TASKS, which made "disappeared" mean "done"
   * while enrichment was still running (§1d).
   */
  private isEmpty(): boolean {
    return (
      this.tasks.length === 0 && !(this.queueCounts &&
        (this.queueCounts.queued + this.queueCounts.processing + this.queueCounts.failed > 0)) && !isActivePhase(this.progress.phase) && !this.showingReady
    );
  }

  private renderTask(t: Task): TemplateResult {
    const status = indexingTaskStatus(t, this.progress.activity);
    const confirmed = status === t.status;
    return html`
      <div class="task" data-testid="task-${t.id}">
        <div class="task-row">
          <span class="status ${confirmed ? t.status : 'stale'}" data-testid="task-status-${t.id}"
            >${status}</span
          >
          ${/* Tempdoc 609 §R (T1.3) — when the task knows its origin surface, the label is a button that
                returns there ("return to running job"); otherwise a plain span. */ ''}
          ${t.originSurfaceId
            ? html`<button
                type="button"
                class="label label-link"
                data-testid="task-return-${t.id}"
                title=${`Return to ${t.label}`}
                @click=${() => requestSurfaceNavigation(t.originSurfaceId!)}
              >
                ${t.label}
              </button>`
            : html`<span class="label" title=${t.label}>${t.label}</span>`}
          ${t.status === 'running' && t.cancellable
            ? html`<jf-button
                size="sm"
                data-testid="task-cancel-${t.id}"
                label="Cancel"
                .onActivate=${() => cancelTask(t.id)}
              >
                Cancel
              </jf-button>`
            : nothing}
        </div>
        ${confirmed && t.status === 'running' && t.progress !== undefined
          ? html`<div class="bar">
              <span style=${`width:${Math.round(t.progress * 100)}%`}></span>
            </div>`
          : nothing}
      </div>
    `;
  }

  private declare queueCounts: ReturnType<typeof indexingQueueCounts>;

  private counts(): Map<TaskStatus, number> {
    // Projection-layer primitive (tempdoc 550 thesis III(b)); shared with the Activity timeline.
    return countByKey(this.tasks, (t) => t.status) as Map<TaskStatus, number>;
  }

  /**
   * 813 §5 — the aggregate card. Renders NOTHING on the `unknown` arm: a worker that did not report
   * has no progress to describe, and the projection's contract is that no number may be shown there
   * (the "0 == done" lie this whole redesign exists to remove).
   */
  private renderAggregate(): TemplateResult | typeof nothing {
    const p = this.progress;
    if (!p.activity.confirmed) return nothing;
    if (this.showingReady) {
      // Round-15 F1 — the terminal card has TWO bodies, and which one is true is decided by the
      // projection's own evidence, never by the absence of queued work: an index whose semantic
      // stage cannot run is finished with what it can do, not "enriched".
      const blocked = p.phase === 'blocked';
      return html`
        <div
          class="aggregate"
          data-testid="task-aggregate"
          data-phase=${blocked ? 'blocked' : 'ready'}
        >
          <div class="counts" data-testid="task-aggregate-counts">
            ${blocked
              ? `Indexed and keyword-searchable — ${ENRICHMENT_BLOCKED_BODY}.`
              : 'Everything is indexed and enriched.'}
          </div>
        </div>
      `;
    }
    if (p.phase === 'indexing') {
      // 813 §19 (W2) — determinate ONLY against an observed drain (`indexingPercent`); with no
      // high-water denominator yet the backlog's total is genuinely unknown, so the affordance falls
      // back to claiming activity without implying a position.
      const pct = p.indexingPercent;
      const label = indexingCountsLabel(p);
      return html`
        <div class="aggregate" data-testid="task-aggregate" data-phase="indexing">
          ${pct === null
            ? html`<div
                class="bar indeterminate"
                data-testid="task-aggregate-bar"
                role="progressbar"
                aria-label="Indexing progress"
              >
                <span></span>
              </div>`
            : html`<div
                class="bar"
                data-testid="task-aggregate-bar"
                role="progressbar"
                aria-label="Indexing progress"
                aria-valuemin="0"
                aria-valuemax="100"
                aria-valuenow=${pct}
              >
                <span style=${`width:${pct}%`}></span>
              </div>`}
          <div
            class="counts"
            data-testid="task-aggregate-counts"
            title=${label ?? nothing}
            aria-label=${label ?? nothing}
          >
            ${indexingCountsLine(p)}
          </div>
        </div>
      `;
    }
    if (p.phase === 'enriching' && p.activity.enrichmentDeferred) {
      return html`
        <div class="aggregate" data-testid="task-aggregate" data-phase="enriching">
          <div class="counts" data-testid="task-aggregate-counts">${
            p.enrichingPercent === null ? '' : `${p.enrichingPercent}% · `
          }${formatCount(p.enrichingPending)} enrichment steps waiting</div>
        </div>
      `;
    }
    if (p.phase === 'enriching') {
      const pct = p.enrichingPercent;
      const label = enrichingCountsLabel(p);
      return html`
        <div class="aggregate" data-testid="task-aggregate" data-phase="enriching">
          ${pct === null
            ? nothing
            : html`<div
                class="bar"
                data-testid="task-aggregate-bar"
                role="progressbar"
                aria-label="Enrichment progress"
                aria-valuemin="0"
                aria-valuemax="100"
                aria-valuenow=${pct}
              >
                <span style=${`width:${pct}%`}></span>
              </div>`}
          <div
            class="counts"
            data-testid="task-aggregate-counts"
            title=${label ?? nothing}
            aria-label=${label ?? nothing}
          >
            ${enrichingCountsLine(p)}
          </div>
        </div>
      `;
    }
    return nothing;
  }

  render(): TemplateResult {
    const counts = this.counts();
    const hasFinished = this.tasks.some((t) => isTerminalStatus(t.status));
    // Collapse the `queued` bulk to a count only (the flood cure); list the rest, capped.
    const detail = this.tasks.filter((t) => t.status !== 'queued');
    const { shown, overflow } = capWithOverflow(detail, MAX_DETAIL_ROWS);
    const speaks = this.showingReady || isActivePhase(this.progress.phase);
    // 813 §20 — what the disclosure has to show: per-file rows, per-stage enrichment detail, or the
    // denominator-less extraction backlog. Any one of them makes the affordance meaningful.
    const stageRows = this.progress.enrichingStages;
    const hasDetail =
      this.tasks.length > 0 || stageRows.length > 0 || this.progress.vduPending > 0;
    // 807 A.3 — a projection that is no longer a live observation says so; the SSE feed's own
    // 595 §4.4 stall carries the same message for the row list, so they share one line.
    const stalled = this.feedStalled || (speaks && !this.showingReady && !this.progress.live);
    return html`
      <div class="panel">
        <div class="head">
          <span class="title" data-testid="task-title">${this.headline()}</span>
          ${hasFinished
            ? html`<jf-button
                size="sm"
                data-testid="tasks-clear"
                label="Clear finished"
                .onActivate=${() => clearFinishedTasks()}
              >
                Clear finished
              </jf-button>`
            : nothing}
        </div>
        ${this.progress.activity.detail ? html`<div class="counts">${this.progress.activity.detail}</div>` : nothing}
        ${this.renderAggregate()}
        ${this.queueCounts ? html`
          <div class="counts" data-testid="task-queue-totals">
            ${this.progress.live ? '' : 'Last reported: '}${formatCount(this.queueCounts.processing)} processing · ${formatCount(this.queueCounts.failed)} failed
          </div>
          ${this.queueCounts.queued > 0 ? html`
            <div class="task-row" data-testid="task-queued-aggregate">
              <span class="status queued">queued</span>
              <span>${this.progress.live ? '' : 'Last reported: '}${formatCount(this.queueCounts.queued)} files queued</span>
            </div>` : nothing}
        ` : nothing}
        <!-- 595 §4.4: when the live feed stalls, say so — don't show stale counts
             as if they were live (the §1.3 freeze). -->
        ${stalled
          ? html`<div class="feed-stalled" data-testid="task-feed-stalled">
              ⚠ Live updates paused — reconnecting…
            </div>`
          : nothing}
        <!-- 813 §5/§20: the DETAIL tier is opt-in. A native <button> so it is keyboard-operable by
             construction (559 Authority V). It now opens on per-stage
             enrichment detail as well as per-file rows — during pure enrichment there are no task
             rows at all, and gating the button on them left the card with nothing to open exactly
             when the user most wants to know which stage is still running. -->
        ${hasDetail
          ? html`<button
              type="button"
              class="disclose"
              data-testid="tasks-disclosure"
              aria-expanded=${this.filesOpen ? 'true' : 'false'}
              @click=${() => (this.filesOpen = !this.filesOpen)}
            >
              ${this.filesOpen ? 'Hide details' : 'Details'}
            </button>`
          : nothing}
        ${this.filesOpen && hasDetail
          ? html`<div class="files" data-testid="tasks-files">
              ${!this.progress.activity.confirmed ? html`<div class="counts">Last observed indexing and enrichment details</div>` : nothing}
              <!-- 813 §20 — machine stages, in the disclosure: the very rows the surface percent
                   sums (enrichingStages), so this is a narrower SCOPE of the same number, never a
                   second derivation. -->
              ${stageRows.map((r) => renderStageRow(r))}
              ${this.progress.vduPending > 0
                ? html`<div class="stage" data-testid="task-stage-vdu">
                    <span class="stage-label"
                      >Content extraction — ${formatCount(this.progress.vduPending)} remaining</span
                    >
                  </div>`
                : nothing}
              ${this.tasks.length > 0
                ? html`
                    <div class="counts" data-testid="task-window-notice">
                      File details are a live window, showing up to 8 tasks; queue totals include files outside it.
                    </div>
                    <!-- Bounded summary: one count chip per non-empty status (550 Thesis III).
                         Absorbed into the disclosure by 813 §5 — inside, they label the row SETS
                         (which rows are listed, which are collapsed), not the panel. -->
                    <div class="summary" data-testid="task-summary">
                      ${COUNT_ORDER.filter((s) => (counts.get(s) ?? 0) > 0).map(
                        (s) => html`<span class="count ${s}" data-testid="task-count-${s}"
                          >${counts.get(s)} ${s}</span
                        >`,
                      )}
                    </div>
                    <!-- Individual rows for actionable/recent tasks; queued stays a count. -->
                    ${shown.map((t) => this.renderTask(t))}
                    ${overflow > 0
                      ? html`<div class="more" data-testid="task-more">
                          +${overflow} more
                        </div>`
                      : nothing}
                  `
                : nothing}
            </div>`
          : nothing}
      </div>
    `;
  }

  /**
   * 813 §4 — the panel's headline is the PHASE NOUN the rest of the product now uses (Indexing /
   * Enriching), so the Tasks panel, the Health cards and the degradation banner share one
   * vocabulary. Falls back to the generic "Tasks" only when the projection cannot speak and the
   * panel is up for agent operations alone.
   */
  private headline(): string {
    if (!this.progress.activity.confirmed || this.progress.activity.detail) return this.progress.activity.summary;
    // Round-15 F1 — "fully searchable" is a claim about COVERAGE, so it may only be made when the
    // projection says coverage supports it. With the semantic stage unable to run, the honest
    // headline keeps the capability lead (§19 W1) and names the limit instead of hiding it.
    if (this.showingReady) {
      return this.progress.phase === 'blocked'
        ? 'Search is ready — keyword only'
        : 'Ready — fully searchable';
    }
    if (this.progress.phase === 'indexing') return 'Indexing';
    // 813 §19 (W1) — capability first: during enrichment keyword search ALREADY works, so the
    // headline states what the user can do before what is still being built. Same clause order as
    // the shared caveat constant; "improving" over "learning" to avoid anthropomorphism.
    if (this.progress.phase === 'enriching') return 'Search is ready — still improving';
    return 'Tasks';
  }
}

/**
 * One machine stage's line inside the disclosure (813 §20): its user name and its own settled
 * fraction, with a ✓ once the stage itself is finished. Plain text, no bar and no control — the
 * disclosure is a detail READOUT, and a second progress bar per stage would compete with the one
 * capability-tier bar the card leads with.
 */
function renderStageRow(row: EnrichingStageRow): TemplateResult {
  const settled = row.total - row.pending;
  return html`
    <div class="stage" data-testid="task-stage-${row.id}">
      <span class="stage-label">${STAGE_LABELS[row.id]}</span>
      <span class="stage-count"
        >${formatCount(settled)} / ${formatCount(row.total)}${row.pending === 0 ? ' ✓' : ''}</span
      >
    </div>
  `;
}

/** The two phases that mean work is happening right now (and the panel has something to report). */
function isActivePhase(phase: IndexingProgress['phase']): boolean {
  return phase === 'indexing' || phase === 'enriching';
}

/**
 * The two phases that END a run: everything settled (`ready`) and everything that COULD settle
 * settled (`blocked`). Both open the brief terminal window; only their words differ (round-15 F1).
 */
function isTerminalPhase(phase: IndexingProgress['phase']): boolean {
  return phase === 'ready' || phase === 'blocked';
}

if (!customElements.get('jf-task-list')) {
  customElements.define('jf-task-list', TaskList);
}
