// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 490 §4.D — Toast host: ephemeral overlay rendering newly-arrived
 * advisories briefly before they vanish into the inbox.
 *
 * V1 renders every new (unacknowledged-on-arrival) advisory as a toast
 * regardless of {@code emissionPolicy.renderHint}. Future iterations can
 * gate by RenderHint == EPHEMERAL once that policy field is consumed
 * upstream of the FE.
 *
 * Toasts auto-dismiss after {@link TOAST_DURATION_MS}. Clicking the toast
 * marks the advisory acknowledged (inbox immediately reflects the read
 * state via UserStateDocument's projection).
 */

import { css, html, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import {
  AdvisoryStore,
  type AdvisoryRecord,
  type AdvisorySnapshot,
} from './AdvisoryStore.js';
import { advisoryClassChrome, healthAdvisoryReasonBody } from './AdvisoryClassChrome.js';
import { capWithOverflow } from '../../projections/boundedProjection.js';
import { icon } from '../Icon.js';
import '../Button.js';
import { type NoticeTone, type NoticeLive } from '../SystemNotice.js';
// Tempdoc 613 §14 — the local toast's tone/politeness/dwell are a projection of its declared severity,
// not render-site literals (the former `severityToTone` is absorbed by this one authority).
import { presentationForSeverity } from '../../state/messageClasses.js';
import '../SystemNotice.js';
import type { OperationClient } from '../../operations/OperationClient.js';
import { requestAuthorization } from '../../operations/authorizationBroker.js';
import '../DispatchSource.js';
import { isWindowFocused } from '../../../utils/windowFocus.js';
import { sendDesktopNotification } from '../../../utils/notify.js';
// Tempdoc 941 F5 — the toast now goes through the SAME authorities the inbox drawer already used:
// present() for machine ids (557 §2.A / 559 ADV-1) and formatRelativeIso for time (586 P-1c). The
// overlay was the one advisory surface still rendering raw ids and a raw locale timestamp.
import { present } from '../../display/present.js';
import { formatRelativeIso } from '../../../utils/relativeTime.js';
import { getUiMode, subscribeUiMode, type UiMode } from '../../state/uiModeState.js';
import { interpolateMessage } from '../../../i18n/resourceCatalog.js';

const TOAST_DURATION_MS = 5000;

/**
 * Dwell for a {@code REQUIRES_ACK} toast. Longer than {@link TOAST_DURATION_MS} — the advisory is
 * important enough to be worth reading before it goes — but BOUNDED, because a toast is an overlay:
 * sandbox round 8 observed two never-expiring REQUIRES_ACK toasts still covering the Library
 * header's control row ~6 minutes and several surface navigations later, one of them hiding the very
 * `Add Folder` button the empty state told the user to press.
 *
 * Auto-hiding the toast does NOT acknowledge, dismiss or drop the record: `dismiss()` only clears
 * the store for `origin === 'local'` records, and the inbox drawer renders every non-EPHEMERAL
 * record (see AdvisoryInboxDrawer's subscribe filter). The advisory keeps its durable home in the
 * inbox and its unread mark on the rail badge — the overlay was a redundant second channel that
 * contributed only occlusion.
 *
 * 3x the base dwell, derived from the one existing duration rather than introducing an unrelated
 * magic number.
 */
const ACK_TOAST_DURATION_MS = TOAST_DURATION_MS * 3;

/**
 * How many toasts render at once. The OverlayHost `.top-right` slot is an uncapped, unscrolled
 * fixed flex column, so an unbounded `visible` array stacks N toasts downward over whatever the
 * surface puts near the top — round 7 observed toasts sitting over the chat header's New chat /
 * Export controls for 20+ minutes. Bounding is a property of the PROJECTION, not the data
 * (tempdoc 550 thesis III(b)): capped toasts stay in `visible` (their timers, acknowledgement and
 * inbox state are untouched) and are simply summarized by a `+N earlier` row.
 *
 * Orthogonal to the per-toast timeout: this bounds how many toasts stack at once, the timeout
 * bounds how long each one stays. Round 7 was unbounded growth; round 8 was unbounded persistence
 * (see {@link ACK_TOAST_DURATION_MS}). Both needed their own bound.
 */
const MAX_VISIBLE_TOASTS = 3;

interface VisibleToast {
  readonly record: AdvisoryRecord;
  /**
   * Null only for a sticky local ERROR toast (which lives nowhere else — dismissing it destroys
   * it). Every store-backed toast, {@code REQUIRES_ACK} included, carries a timeout:
   * {@link ACK_TOAST_DURATION_MS} for {@code REQUIRES_ACK}, {@link TOAST_DURATION_MS} otherwise.
   */
  timeoutId: ReturnType<typeof setTimeout> | null;
}

export class AdvisoryToastHost extends JfElement {
  static properties = {
    store: { attribute: false },
    operationClient: { attribute: false },
    visible: { state: true },
    uiMode: { state: true },
  };

  declare store: AdvisoryStore | null;
  declare operationClient: OperationClient | null;
  declare visible: VisibleToast[];
  /** 941 F5 — drives the Detailed-only id disclosure; kept in sync with the app-wide authority. */
  declare uiMode: UiMode;

  private storeUnsubscribe: (() => void) | null = null;
  private uiModeUnsubscribe: (() => void) | null = null;
  private seenKeys = new Set<string>();
  // Tempdoc 602 R4 — keys currently animating out, so the supersede prune in
  // onSnapshot does not double-dismiss a toast already being removed.
  private exiting = new Set<string>();
// Slice 490 substrate-completion (P2.3) — renderHint is now per-event
// (record.sourceRenderHint) rather than per-store. The toast host reads the
// hint at pushToast() time from the individual record; no store-level field
// needs to be tracked here. Removed the v1 `currentRenderHint` shadow field.
  /**
   * Slice 490 follow-up — flips on the first LIFECYCLE snapshot frame the store
   * delivers, regardless of payload size. Before that flag, no advisory is treated
   * as toast-worthy (the snapshot's contents are historical replay). After, every
   * advisory whose key is new vs `seenKeys` fires a toast.
   *
   * <p>Replaces the prior `seenKeys.size === 0` heuristic, which mis-classified the
   * first UPDATE as "snapshot seed" when the first snapshot was empty.
   */
  private hasSeenFirstSnapshot = false;

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost top-right slot. */
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      pointer-events: none;
    }
    /* 559 notice-presentation: the toast wrapper owns float/animation/interaction;
       the inner <jf-system-notice> owns the notice shell (bg/border/tone/padding). */
    .toast {
      pointer-events: auto;
      min-width: 18rem;
      max-width: 24rem;
      border-radius: 0.5rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
      font-size: var(--font-size-sm);
      line-height: 1.35;
      cursor: pointer;
      animation: jf-toast-in 180ms ease-out;
    }
    .title-row {
      display: flex;
      align-items: flex-start;
      gap: 0.5rem;
    }
    .title {
      font-weight: 600;
      margin-bottom: 0.25rem;
      flex: 1 1 auto;
      min-width: 0;
    }
    .dismiss {
      flex: 0 0 auto;
      margin: -0.25rem -0.25rem 0 0;
    }
    /* The bounded-projection summary for the toasts held back by MAX_VISIBLE_TOASTS — the same
       "+N more" affordance shape the rail's task list uses (TaskList.ts). */
    .more {
      pointer-events: auto;
      align-self: flex-end;
      padding: 0.15rem 0.5rem;
      border-radius: 0.25rem;
      background: var(--surface-2);
      border: 1px solid var(--border-subtle);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    /* 941 F5 — the advisory's authored sentence, between the headline and the meta line. */
    .body {
      margin-bottom: 0.25rem;
      overflow-wrap: anywhere;
    }
    .meta {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    /* 941 F5 — the internal ids, Detailed mode only. Monospace + secondary tone so they read as
       diagnostics rather than as copy. */
    .detail-ids {
      margin-top: 0.25rem;
      color: var(--text-secondary);
      font-family: var(--font-mono, monospace);
      font-size: var(--font-size-xs);
      overflow-wrap: anywhere;
    }
    .action-row {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
    .action-btn {
      padding: 0.25rem 0.625rem;
      font-size: var(--font-size-xs);
      font-weight: 600;
      border: 1px solid var(--accent);
      border-radius: 0.25rem;
      background: transparent;
      color: var(--accent);
      cursor: pointer;
      pointer-events: auto;
    }
    .action-btn:hover {
      background: var(--accent);
      color: var(--surface-2);
    }
    .action-btn.running {
      opacity: 0.6;
      pointer-events: none;
    }
    .action-btn.success {
      border-color: var(--accent-success);
      color: var(--text-success);
      transition: border-color var(--duration-normal), color var(--duration-normal);
    }
    .action-btn.failed {
      border-color: var(--accent-warning);
      color: var(--text-warning);
      transition: border-color var(--duration-normal), color var(--duration-normal);
    }
    .toast.exiting {
      animation: jf-toast-out 180ms ease-in forwards;
    }
    @keyframes jf-toast-in {
      from {
        opacity: 0;
        transform: translateY(-4px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }
    @keyframes jf-toast-out {
      from {
        opacity: 1;
        transform: translateY(0);
      }
      to {
        opacity: 0;
        transform: translateY(-8px);
      }
    }
    /* a11y — honor prefers-reduced-motion: no slide/fade. dismiss() has a 250ms
       setTimeout fallback, so the lost animationend is safe. */
    @media (prefers-reduced-motion: reduce) {
      .toast,
      .toast.exiting {
        animation: none;
      }
    }
  `;

  constructor() {
    super();
    this.store = null;
    this.operationClient = null;
    this.visible = [];
    this.uiMode = getUiMode();
  }

  private keydownListener: ((e: KeyboardEvent) => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    if (this.store) {
      this.storeUnsubscribe = this.store.subscribe((s) => this.onSnapshot(s));
    }
    this.keydownListener = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
        const undoToast = this.visible.find(
          (t) => t.record.event.primaryActionKind === 'undo',
        );
        if (undoToast) {
          e.preventDefault();
          const btn = this.shadowRoot?.querySelector(
            `[data-key="${CSS.escape(undoToast.record.key)}"] .action-btn`,
          ) as HTMLButtonElement | null;
          if (btn) btn.click();
        }
      }
    };
    document.addEventListener('keydown', this.keydownListener);
    // 941 F5 — subscribe (it fires synchronously on subscribe, so this also re-seeds after a
    // detach/attach) so toggling Detailed reveals the ids on a toast that is already on screen.
    this.uiModeUnsubscribe = subscribeUiMode((m) => {
      this.uiMode = m;
    });
  }

  override disconnectedCallback(): void {
    if (this.storeUnsubscribe) {
      this.storeUnsubscribe();
      this.storeUnsubscribe = null;
    }
    if (this.uiModeUnsubscribe) {
      this.uiModeUnsubscribe();
      this.uiModeUnsubscribe = null;
    }
    if (this.keydownListener) {
      document.removeEventListener('keydown', this.keydownListener);
      this.keydownListener = null;
    }
    for (const t of this.visible) {
      if (t.timeoutId !== null) clearTimeout(t.timeoutId);
    }
    this.visible = [];
    super.disconnectedCallback();
  }

  private onSnapshot(s: AdvisorySnapshot): void {
    // Tempdoc 602 R4 — supersede reconcile: a superseding emit drops the prior
    // same-classId local record from the store, so any toast for a departed
    // local record is animated out here. Without this the replaced toast lingers
    // until its own timeout and briefly stacks beside its successor.
    const liveLocalKeys = new Set(
      s.advisories.filter((r) => r.origin === 'local').map((r) => r.key),
    );
    for (const t of this.visible) {
      if (t.record.origin === 'local' && !liveLocalKeys.has(t.record.key)) {
        this.dismiss(t.record.key);
      }
    }
    // 559 Authority III — local-origin ephemeral records (emitEphemeralToast) are
    // ALWAYS live (a client action just happened), so they bypass the frame-kind
    // replay gate below. Toast each unseen local record immediately.
    for (const r of s.advisories) {
      if (r.origin !== 'local') continue;
      if (this.seenKeys.has(r.key)) continue;
      this.seenKeys.add(r.key);
      this.pushToast(r);
    }
    // Slice 490 follow-up — gate (stream-origin) toast emission on the typed `lastFrameKind`
    // discriminator, not the seenKeys-empty heuristic. Three regimes:
    //   1. lastFrameKind === 'initial' (no frame yet): seed nothing. No toast.
    //   2. lastFrameKind === 'snapshot' (we just received a LIFECYCLE snapshot):
    //      seed seenKeys silently — historical advisories are not toast-worthy.
    //      Flip hasSeenFirstSnapshot so subsequent snapshots can re-seed.
    //   3. lastFrameKind === 'update' (a new advisory arrived): toast each key
    //      we haven't seen + that isn't already acknowledged.
    // The earlier heuristic mis-classified the FIRST update as a "snapshot seed"
    // whenever the first LIFECYCLE snapshot was empty.
    if (s.lastFrameKind === 'initial') return;
    if (s.lastFrameKind === 'snapshot') {
      for (const r of s.advisories) this.seenKeys.add(r.key);
      this.hasSeenFirstSnapshot = true;
      return;
    }
    // lastFrameKind === 'update'
    if (!this.hasSeenFirstSnapshot) {
      // An UPDATE arrived before any LIFECYCLE snapshot — treat the current
      // advisories as the implicit snapshot baseline. Defensive against
      // reconnect-without-snapshot edge cases.
      for (const r of s.advisories) this.seenKeys.add(r.key);
      this.hasSeenFirstSnapshot = true;
      return;
    }
    for (const r of s.advisories) {
      if (this.seenKeys.has(r.key) || r.acknowledged) continue;
      this.seenKeys.add(r.key);
      this.pushToast(r);
      // Tempdoc 655 long-term design pass — the OS-notification escalation lives HERE, at the one
      // render-layer place a genuinely new stream record is already distinguished from reconnect
      // replay, not inside any individual feature. Gated on REQUIRES_ACK (reserved for exactly
      // this "don't let the user miss it" shape) so any future REQUIRES_ACK-classed advisory gets
      // the same treatment for free, without per-feature wiring.
      if (r.sourceRenderHint === 'REQUIRES_ACK') {
        void this.maybeNotifyDesktop(r);
      }
    }
  }

  private async maybeNotifyDesktop(record: AdvisoryRecord): Promise<void> {
    if (await isWindowFocused()) return;
    const chrome = advisoryClassChrome(record.event.classId);
    const operationId = record.event.classExtras?.operationId;
    const body = typeof operationId === 'string' ? operationId : undefined;
    await sendDesktopNotification(chrome.label, body);
  }

  private pushToast(record: AdvisoryRecord): void {
    // A REQUIRES_ACK toast dwells longer (ACK_TOAST_DURATION_MS) but still auto-hides; it is not a
    // permanent overlay, because the record's durable channels (inbox drawer + rail badge unread)
    // outlive the toast. The dispatch is per-event via record.sourceRenderHint — multiple advisory
    // classes with different renderHints coexist cleanly.
    const durationMs =
      record.toast?.durationMs ??
      (record.sourceRenderHint === 'REQUIRES_ACK'
        ? ACK_TOAST_DURATION_MS
        : TOAST_DURATION_MS);
    // Tempdoc 613 §14 — a local ERROR toast is sticky (no auto-dismiss): an error must not silently
    // auto-vanish. Derived from the declared severity, the same way REQUIRES_ACK persists a stream toast.
    const sticky =
      record.origin === 'local' &&
      presentationForSeverity(record.event.severity).sticky;
    const timeoutId = sticky
      ? null
      : setTimeout(() => this.dismiss(record.key), durationMs);
    this.visible = [...this.visible, { record, timeoutId }];
  }

  private dismiss(key: string): void {
    const target = this.visible.find((t) => t.record.key === key);
    // Tempdoc 602 R4 — idempotent: a toast already animating out (e.g. the
    // onSnapshot supersede prune raced a timeout) must not be processed twice.
    if (!target || this.exiting.has(key)) return;
    this.exiting.add(key);
    if (target.timeoutId !== null) clearTimeout(target.timeoutId);
    // 559 Authority III — local-origin records live in the store's ephemeral
    // list; clear them there so they leave the snapshot (they never persist).
    if (target.record.origin === 'local') this.store?.dropEphemeral(key);
    const remove = () => {
      this.visible = this.visible.filter((t) => t.record.key !== key);
      this.exiting.delete(key);
    };
    const el = this.shadowRoot?.querySelector(
      `[data-key="${CSS.escape(key)}"]`,
    ) as HTMLElement | null;
    if (el && typeof el.getAnimations === 'function') {
      el.classList.add('exiting');
      el.addEventListener('animationend', remove, { once: true });
      setTimeout(remove, 250);
    } else {
      remove();
    }
  }

  private handleClick(record: AdvisoryRecord): void {
    // 559 Authority III — local records aren't persisted; dismiss drops them.
    // Stream records acknowledge (reflected in the inbox read-state projection).
    if (record.origin !== 'local' && this.store) this.store.acknowledge(record.key);
    this.dismiss(record.key);
  }

  private async handleAction(e: Event, record: AdvisoryRecord): Promise<void> {
    e.stopPropagation();
    // 559 Authority III — local ephemeral records carry a plain callback action
    // (e.g. nav-toast "Go back"), not an operation invocation.
    if (record.origin === 'local') {
      record.toast?.onAction?.();
      this.dismiss(record.key);
      return;
    }
    const action = record.event.primaryAction;
    if (!action || !this.operationClient) return;
    const btn = e.currentTarget as HTMLButtonElement;
    btn.classList.add('running');
    btn.textContent = 'Running…';
    try {
      const args = action.defaultArgsJson
        ? JSON.parse(action.defaultArgsJson)
        : {};
      if (record.event.primaryActionKind === 'undo' && args.executionId) {
        // Tempdoc 875 §C.7 — same lattice as the forward form, so the same consent
        // path as the invoke branch below (shared ceremony host via the broker).
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
      btn.classList.remove('running');
      btn.classList.add('success');
      btn.textContent = '✓ Done';
      if (this.store) this.store.acknowledge(record.key);
      setTimeout(() => this.dismiss(record.key), 1000);
    } catch {
      btn.classList.remove('running');
      btn.classList.add('failed');
      btn.textContent = '✕ Failed';
    }
  }

  override render(): TemplateResult | typeof nothing {
    if (this.visible.length === 0) return nothing;
    // `visible` is append-ordered (oldest first) and the slot stacks downward, so cap the REVERSED
    // list — a burst must never push the just-arrived toast out of view — then restore render order.
    const capped = capWithOverflow([...this.visible].reverse(), MAX_VISIBLE_TOASTS);
    const overflow = capped.overflow;
    const stack = [...capped.shown].reverse();
    return html`${overflow > 0
      ? html`<div class="more" data-testid="toast-more">
          +${overflow} earlier ${overflow === 1 ? 'notification' : 'notifications'}
        </div>`
      : nothing}
    ${stack.map((t) => {
      // 559 Authority III — local-origin records render their literal message +
      // severity tone + plain callback action (no advisory class chrome / meta).
      const isLocal = t.record.origin === 'local';
      // Local records carry their own message/tone; don't resolve class chrome
      // for them (avoids a spurious "unknown classId" warning for core.ephemeral).
      const chrome = isLocal
        ? { icon: '', label: '', toneClass: '' }
        : advisoryClassChrome(t.record.event.classId);
      const extras = t.record.event.classExtras ?? {};
      const title = isLocal
        ? (t.record.toast?.message ?? '')
        : toastTitle(t.record.event.classId, chrome.label, extras);
      // Tempdoc 941 F5 — the advisory's own authored sentence. The inbox drawer has always rendered
      // `bodyI18nKey`; the toast rendered only the class label, a timestamp and a button, so the
      // overlay a first-time user actually sees was the one channel that never said what happened
      // or what to do about it.
      //
      // The catalog sentence is a TEMPLATE (`health-events.<id>.message` may carry `{name}`
      // placeholders), and 941 F4 is what an unsubstituted one looks like on screen. The
      // advisory's classExtras are exactly its parameters — HealthRecoveryProjector.projectLifecycle
      // copies the emitter's attribute map into extras, and projectCondition puts subject/reason/
      // severity there — so the same interpolation authority the activity row uses applies here.
      // Declining (null) drops the body rather than showing a brace: no advisory carries a
      // parameterized message today, so this closes the path before it can be walked.
      //
      // Tempdoc 941 — `bodyI18nKey` is always the GENERIC `health-events.<id>.message`. When the
      // condition carries a reason with its own authored sentence, that sentence wins: it is the
      // more specific true thing about this occurrence. Interpolated through the same authority,
      // since a per-reason message may carry placeholders exactly like the generic one.
      const reasonBody = isLocal ? null : healthAdvisoryReasonBody(t.record.event.classId, extras);
      const body = isLocal || !(reasonBody || t.record.event.bodyI18nKey)
        ? ''
        : (interpolateMessage(
            reasonBody ??
              present({ kind: 'resource', key: t.record.event.bodyI18nKey as string }).label,
            extras,
          ) ?? '');
      // The internal ids are kept, not deleted — moved out of the headline and behind the app-wide
      // Detailed disclosure (uiModeState), so a diagnosing user can still correlate the toast with
      // a condition id or an operation id without a first-time user being shown either.
      const detailIds = this.uiMode === 'advanced' && !isLocal
        ? [
            typeof extras['conditionId'] === 'string' ? extras['conditionId'] : '',
            t.record.event.primaryAction?.target ?? '',
          ].filter(Boolean).join(' · ')
        : '';
      // 559 notice-presentation — the severity tone is a NoticeTone routed to the
      // shared <jf-system-notice>, not a per-host CSS class.
      // Tempdoc 613 §14 — a LOCAL toast's tone AND announcement politeness (live) are one projection of
      // its declared severity (error/warning announce assertively). Stream records keep their chrome tone
      // and the renderHint-driven politeness.
      const localPresentation = isLocal
        ? presentationForSeverity(t.record.event.severity)
        : null;
      const tone: NoticeTone = localPresentation
        ? localPresentation.tone
        : t.record.event.classId === 'operation.completed'
          ? extras.outcome === 'SUCCESS'
            ? 'success'
            : 'warning'
          : toneClassToNotice(chrome.toneClass);
      const live: NoticeLive = localPresentation
        ? localPresentation.live
        : t.record.sourceRenderHint === 'REQUIRES_ACK'
          ? 'alert'
          : 'status';
      const action = t.record.event.primaryAction;
      // Tempdoc 941 F5 — `action.target.split('.').pop()` is where the round-18 screenshot's
      // `rebuild-index` came from: the last dotted segment of an operation id, printed as a button
      // label. present({kind:'operation'}) resolves the catalog's authored label ("Force Rebuild"),
      // and humanizes the id when the catalog has not booted — never the raw segment.
      const actionLabel = isLocal
        ? t.record.toast?.actionLabel
        : action
          ? t.record.event.primaryActionKind === 'undo'
            ? 'Undo'
            : present({ kind: 'operation', id: action.target }).label || 'Fix'
          : undefined;
      return html`
          <div
            class="toast"
            data-key=${t.record.key}
            @click=${() => this.handleClick(t.record)}
          >
            <jf-system-notice tone=${tone} live=${live}>
              <div class="title-row">
                <div class="title">${isLocal ? nothing : chrome.icon} ${title}</div>
                <!-- Until now the ONLY way to dismiss a toast was clicking anywhere on it — an
                     undiscoverable affordance, and the reason round 7 saw toasts sit over the header
                     for 20+ minutes. The whole-toast click stays; this just makes it visible. The
                     wrapper stops the click before the toast div's own handler so the record is not
                     acknowledged twice (same guard shape as handleAction's stopPropagation). -->
                <span
                  class="dismiss"
                  @click=${(e: Event) => e.stopPropagation()}
                >
                  <jf-button
                    variant="ghost"
                    size="icon"
                    label="Dismiss notification"
                    .onActivate=${() => this.handleClick(t.record)}
                  >
                    ${icon({ name: 'x', size: 14 })}
                  </jf-button>
                </span>
              </div>
              ${body ? html`<div class="body" data-testid="toast-body">${body}</div>` : nothing}
              ${isLocal || (!formatTime(t.record.event.occurredAt) && !t.record.event.provenance)
                ? nothing
                : html`<div class="meta">
                    ${formatTime(t.record.event.occurredAt)}
                    ${t.record.event.provenance
                      ? html` • <jf-dispatch-source .provenance=${t.record.event.provenance}></jf-dispatch-source>`
                      : nothing}
                  </div>`}
              ${detailIds
                ? html`<div class="detail-ids" data-testid="toast-detail-ids">${detailIds}</div>`
                : nothing}
              ${actionLabel
                ? html`
                    <div class="action-row">
                      <button
                        class="action-btn"
                        @click=${(e: Event) => this.handleAction(e, t.record)}
                      >
                        ${actionLabel}
                      </button>
                    </div>
                  `
                : nothing}
            </jf-system-notice>
          </div>
        `;
    })}`;
  }
}

/** Map an advisory-class chrome toneClass ('success'/'failure'/…) to a NoticeTone. */
function toneClassToNotice(tc: string): NoticeTone {
  switch (tc) {
    case 'success':
      return 'success';
    case 'error':
      return 'error';
    case 'failure':
    case 'warning':
      return 'warning';
    default:
      return 'neutral';
  }
}

/**
 * Tempdoc 941 F5 — friendly relative time ("3 minutes ago"), the same formatter the inbox drawer
 * moved to in 586 P-1c. `toLocaleTimeString()` rendered "19:35:18 GMT+0200 (Central European
 * Summer Time)" into a 24rem overlay: a wall-clock reading of a machine's timezone tells a user
 * nothing about an event they are looking at right now.
 *
 * Returns '' — not the raw ISO — for empty/unparseable input, so the caller can drop the meta line
 * entirely rather than fall back to the other machine-facing spelling of the same value.
 */
function formatTime(iso: string): string {
  return formatRelativeIso(iso);
}

/**
 * Tempdoc 941 F5 — the toast's headline for a recoverable-health advisory.
 *
 * It used to be the class chrome's fixed label, "Recoverable condition": true of every advisory in
 * the class and therefore about nothing. Sandbox round 18 hit it by pressing Add Folder during an
 * index rebuild — the toast named neither the index nor the rebuild, and its only concrete word was
 * `rebuild-index`, an operation id leaking out of the action button. This routes the conditionId
 * through present({kind:'condition'}), the same projection the inbox drawer's deriveTitle uses.
 */
function toastTitle(
  classId: string,
  chromeLabel: string,
  extras: Record<string, unknown>,
): string {
  if (classId === 'operation.completed') {
    const opId = typeof extras['operationId'] === 'string' ? extras['operationId'] : '';
    return opId ? present({ kind: 'operation', id: opId }).label : classId;
  }
  if (classId === 'health.recoverable') {
    const condId = typeof extras['conditionId'] === 'string' ? extras['conditionId'] : '';
    if (condId) return present({ kind: 'condition', id: condId }).label;
  }
  return chromeLabel;
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-advisory-toast-host')
) {
  customElements.define('jf-advisory-toast-host', AdvisoryToastHost);
}
