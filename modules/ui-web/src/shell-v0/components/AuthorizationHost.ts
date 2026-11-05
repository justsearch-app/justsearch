// SPDX-License-Identifier: Apache-2.0
/**
 * `<jf-authorization-host>` — the one FE ceremony surface for non-user actions that need a
 * human decision (tempdoc 550 C3 + G9), rendered on the TRUSTED CHANNEL (569 Move 4).
 *
 * The ceremony renders in a native `<dialog>`. Security-bearing prompts (high-risk
 * TYPED_CONFIRM authorize + capability grants) open via `showModal()`, which places the
 * dialog in the browser TOP LAYER — above every stacking context — so a user-authored
 * skin's overlay (`position:fixed; z-index:99999`) CANNOT occlude, cover, or spoof the
 * trusted ceremony. The skin has no field for this element (it lives in the host shadow,
 * outside the authorable surface) and cannot paint over it: anti-spoofing is structural,
 * not visual. Low/medium-risk confirmations of the person's own command stay non-blocking
 * (`show()`, 565 §3.D).
 *
 * DE3 — requests from agents and other apps (`prompt.arrival === 'needs-you'`: MCP, the agent in
 * Search) open no dialog on arrival. They wait here, listed in the status panel's Needs you, which
 * reviews and answers them ({@link AuthorizationHost.answerStatusRequest}) through the same single
 * decision as the dialog; a typed one opens the security dialog only from its Review.
 *
 * It presents two prompt kinds through one consistent chrome:
 *
 *   - **authorize** (550 C3, invoke-first): when a dispatch hits the backend's 428 trust gate,
 *     the {@link authorizationBroker} hands this host the backend-issued
 *     {@link AuthorizationPrompt} and it renders an approve/deny ceremony (TYPED_CONFIRM →
 *     type-the-op-id; INLINE → one click), resolving the human decision the dispatcher awaits.
 *
 *   - **capability** (tempdoc 550 G9): a plugin capability request (`jf-consent-request`,
 *     from the consent substrate's {@link requestCapability}) renders allow-once /
 *     allow-always / deny in the SAME ceremony chrome. The grant **model stays federated** —
 *     the consent substrate still owns the durable-grant semantics, localStorage persistence,
 *     and resolution via {@link resolveConsentRequest}. This host only unifies the *surface*.
 *
 * Mount one instance high in the chrome. On teardown it unregisters and fails closed — a gated
 * action is denied, and a pending capability request is left pending, if no host is mounted.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { ModalController } from '../primitives/modalController.js';
import { ref, createRef, type Ref } from 'lit/directives/ref.js';
import { keyed } from 'lit/directives/keyed.js';
import {
  setAuthorizationPresenter,
  setAuthorizationCanceller,
  ARMING_DELAY_MS,
  type AuthorizationPrompt,
  type AuthorizationDecision,
} from '../operations/authorizationBroker.js';
import {
  listPendingConsentRequests,
  resolveConsentRequest,
  type ConsentRequest,
  type ConsentDecision,
} from '../substrates/consent/index.js';

/** A one-shot backend-gated authorization prompt awaiting an approve/deny decision. */
interface AuthorizeItem {
  readonly kind: 'authorize';
  /** Replaced by the fresh live details read when the person reviews it. */
  prompt: AuthorizationPrompt;
  readonly resolve: (decision: AuthorizationDecision) => void;
  /** Needs-you requests: when the panel may accept an answer (arrival, each review opening, changed details). */
  readyAt?: number;
}

/** What the status panel shows and may do for one waiting request. */
export interface StatusRequestState {
  readonly busy: boolean;
  /** Its live details are being read: it cannot be allowed until they are. */
  readonly checking: boolean;
  readonly error?: string;
  /** `performance.now()` time after which the panel accepts an answer. */
  readonly readyAt: number;
  /** An "always allow" choice the backend would keep (never HIGH, never the in-app agent's route). */
  readonly durable: boolean;
}

/** A plugin capability-consent request awaiting an allow-once / allow-always / deny decision. */
interface CapabilityItem {
  readonly kind: 'capability';
  readonly request: ConsentRequest;
}

type CeremonyItem = AuthorizeItem | CapabilityItem;

/** How long an Allow waits for a request's live details before it is refused as unavailable. */
const FRESH_DETAILS_TIMEOUT_MS = 5_000;
const DETAILS_UNAVAILABLE = 'Its current details are unavailable, so it can’t be allowed right now. Try again, or deny it.';
const DETAILS_CHANGED = 'Its details changed, so it was not allowed. Check them and answer again.';

/** The live details read for an agent's request, as `refresh` reports them. */
type FreshDetails = Awaited<ReturnType<NonNullable<AuthorizationPrompt['refresh']>>>;

export class AuthorizationHost extends JfElement {
  static properties = {
    active: { attribute: false },
    typed: { attribute: false },
    allowAlways: { attribute: false },
  } as const;

  /** The item currently being presented, or null when idle. One at a time (FIFO). */
  declare active: CeremonyItem | null;
  declare typed: string;
  /** Tempdoc 550 thesis IV: the "always allow this action" checkbox state for the active prompt. */
  declare allowAlways: boolean;

  private queue: CeremonyItem[] = [];
  /** DE3 — agent requests waiting in Needs you; they open no dialog until a typed one is reviewed. */
  private waiting: AuthorizeItem[] = [];
  private readonly decided = new Set<string>();
  private consentListener: ((e: Event) => void) | null = null;
  /** 569 Move 4 — the native <dialog> on the trusted channel (Top Layer when modal). */
  private readonly dialogRef: Ref<HTMLDialogElement> = createRef();
  /** When the active prompt was presented; its inputs arm {@link ARMING_DELAY_MS} later. */
  private presentedAt = 0;
  /** The pointer press on this prompt that a pointer click may complete; null when none started here. */
  private press: { at: number; button: Element | null } | null = null;
  /** The armed, unrepeated Enter/Space press on a control that its release completes. */
  private keyPress: { key: string; button: Element } | null = null;
  /** Keys pressed in the typed confirmation after the prompt armed: only their auto-repeat may type. */
  private readonly typingKeys = new Set<string>();
  /**
   * The open prompt's dialog was closed without a decision (Escape, `dialog.close()`): it stays pending
   * and listed in the status panel, and only Review (or the next prompt) presents a ceremony again.
   */
  private dismissed = false;
  private readonly rejecting = new Set<CeremonyItem>();
  /** Agent requests whose live details are being read; none of them can be allowed meanwhile. */
  private readonly checking = new Set<AuthorizeItem>();
  /** Why the last answer to a request did not go through, shown with it until it is answered. */
  private readonly answerErrors = new WeakMap<CeremonyItem, string>();

  constructor() {
    super();
    this.active = null;
    this.typed = '';
    this.allowAlways = false;
  }

  static styles = css`
    :host {
      display: contents;
    }
    /* 569 Move 4 — the trusted ceremony is a native <dialog>; showModal() lifts it into
       the browser Top Layer, unreachable by any skin's z-index/overlay. Token refs mean it
       still THEMES, but the ELEMENT is outside the skinnable layer. */
    dialog.ceremony {
      background: var(--surface-1);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: var(--frame-panel-radius);
      padding: 1.25rem;
      min-width: 22rem;
      max-width: 32rem;
      box-sizing: border-box;
      max-height: calc(100dvh - 2rem);
      overflow: auto;
      font-size: var(--font-size-md);
    }
    dialog.ceremony::backdrop {
      background: rgba(0, 0, 0, 0.45);
    }
    /* ref-12 — the protected security check: teal border and its own header. */
    dialog.ceremony:modal {
      border-color: var(--accent-tint);
    }
    .security {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      gap: var(--jf-space-2);
      margin: 0 0 var(--jf-space-3);
      font-size: var(--font-size-sm);
    }
    .security strong {
      color: var(--text-tint);
      font-weight: var(--jf-weight-medium);
    }
    .security span {
      color: var(--text-secondary);
    }
    .actions .spacer {
      flex: 1;
    }
    @media (forced-colors: active) {
      dialog.ceremony:modal {
        border-color: Highlight;
      }
      .security strong,
      .security span {
        color: CanvasText;
      }
    }
    /* 565 §3.D — low/medium-risk approvals are non-blocking (show(), not modal): a bottom
       card with a transparent backdrop so the run stays visible + interactive. */
    dialog.ceremony.lightweight {
      margin: auto auto 5.5rem auto;
      min-width: 20rem;
      border-color: var(--accent-command);
      box-shadow: 0 6px 20px rgba(0, 0, 0, 0.35);
    }
    dialog.ceremony.lightweight::backdrop {
      background: transparent;
    }
    h3 {
      margin: 0 0 0.5rem;
      font-size: var(--font-size-md);
    }
    code {
      font-family: ui-monospace, monospace;
      color: var(--text-tint);
    }
    /* Frozen targets are complete identities; wrap long path segments without truncation. */
    .description code {
      overflow-wrap: anywhere;
      white-space: pre-wrap;
    }
    .rationale,
    .description {
      color: var(--text-secondary);
      margin: 0.25rem 0;
    }
    input[type='text'],
    input:not([type]) {
      box-sizing: border-box;
      width: 100%;
      margin: 0.5rem 0;
      padding: 0.4rem 0.5rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      background: var(--surface-2);
      color: inherit;
      font-family: ui-monospace, monospace;
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
      margin-top: 0.75rem;
      flex-wrap: wrap;
    }
    /* 574 B (remediation) — the decision buttons are jf-button atoms: deny → variant="danger",
       approve/allow-always → variant="primary", allow-once → secondary. The bespoke
       button/.approve/.deny fork is deleted (the affirmative/destructive emphasis is now the
       single button authority's, not this surface's hand-picked outline). */
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    setAuthorizationPresenter((prompt) => this.present(prompt));
    // Tempdoc 605 — register the run-scoped ceremony drain so a concluding run can fail-closed
    // its own still-open authorize ceremonies (Move 2), freeing the host for the next run.
    setAuthorizationCanceller((id, scope) => scope === 'pending'
      ? this.cancelMatching(prompt => prompt.pendingId === id)
      : this.cancelForRun(id));
    // Tempdoc 550 G9: this host also presents capability-consent requests so there is one
    // ceremony surface. The consent substrate keeps its federated grant model.
    if (!this.consentListener) {
      this.consentListener = (e: Event) => {
        const request = (e as CustomEvent<ConsentRequest>).detail;
        if (request) this.enqueueCapability(request);
      };
      if (typeof document !== 'undefined') {
        document.addEventListener('jf-consent-request', this.consentListener);
      }
      // Drain any requests that were pending before this host mounted.
      for (const request of listPendingConsentRequests()) this.enqueueCapability(request);
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    setAuthorizationPresenter(null);
    setAuthorizationCanceller(null); // Tempdoc 605 — unregister the run-scoped drain on teardown.
    if (this.consentListener && typeof document !== 'undefined') {
      document.removeEventListener('jf-consent-request', this.consentListener);
    }
    this.consentListener = null;
    // On teardown:
    //   - AUTHORIZE prompts fail CLOSED (resolve false) so an awaiting backend dispatcher doesn't
    //     hang — a one-shot gate decision the host can't present must default to denial.
    //   - CAPABILITY requests are LEFT PENDING (no-op). A remounted host re-drains them.
    if (this.active) this.failClosed(this.active);
    this.queue.forEach((item) => this.failClosed(item));
    this.waiting.forEach((item) => this.failClosed(item));
    this.queue = [];
    this.waiting = [];
    this.active = null;
  }

  /** Broker entry point: enqueue an authorize prompt and return a promise of the decision. */
  present(prompt: AuthorizationPrompt): Promise<AuthorizationDecision> {
    // A broadcast and the originating 428 may describe the same gate. Only its first owner
    // receives a decision that can execute; every later route is superseded.
    if (this.decided.has(prompt.pendingId) || this.statusRequests().some(item => item.kind === 'authorize' && item.prompt.pendingId === prompt.pendingId)) {
      return Promise.resolve({ approved: false, allowAlways: false, superseded: true });
    }
    return new Promise<AuthorizationDecision>((resolve) => {
      if (prompt.arrival === 'needs-you') {
        // DE3 — no dialog on arrival: the request waits in Needs you, answered from the panel.
        this.waiting.push({ kind: 'authorize', prompt, resolve, readyAt: performance.now() });
        this.requestUpdate();
        return;
      }
      this.queue.push({ kind: 'authorize', prompt, resolve });
      this.pumpQueue();
    });
  }

  /** Read view of the existing ceremony queue, owned and drained by this host. */
  statusRequests(): readonly CeremonyItem[] {
    return [...(this.active ? [this.active] : []), ...this.queue, ...this.waiting];
  }

  /** The panel's view of one waiting request, or null when it is not waiting here. */
  statusRequestState(id: string): StatusRequestState | null {
    const item = this.statusRequests().find((row): row is AuthorizeItem => row.kind === 'authorize' && row.prompt.pendingId === id);
    if (!item) return null;
    const error = this.answerErrors.get(item);
    return { busy: this.rejecting.has(item), checking: this.checking.has(item), ...(error ? { error } : {}),
      readyAt: item.readyAt ?? 0, durable: AuthorizationHost.honoursAllowAlways(item.prompt) };
  }

  /**
   * The panel opened the review of a waiting request: re-arm it (the activation that opened the review
   * cannot answer it) and read its live details again. Gone → withdrawn. Unreadable details are said;
   * nothing is sent for them: the request stays answerable, and only a fresh read lets it be allowed.
   */
  async openStatusReview(id: string): Promise<void> {
    const item = this.waiting.find(row => row.prompt.pendingId === id);
    if (!item) return;
    item.readyAt = performance.now();
    this.requestUpdate();
    if (item.prompt.refresh && !this.checking.has(item)) await this.readFresh(item);
  }

  /**
   * DE3 — read an agent request's live details again and show them. `fresh`: they match what was
   * shown; `changed`: they did not (now shown, and the request re-arms); `unavailable`: they could not
   * be read in time; `gone`: the request no longer waits (withdrawn); `stale`: it was answered meanwhile.
   */
  private async readFresh(item: AuthorizeItem): Promise<'fresh' | 'changed' | 'unavailable' | 'gone' | 'stale'> {
    const refresh = item.prompt.refresh;
    if (!refresh) {
      this.answerErrors.set(item, DETAILS_UNAVAILABLE);
      this.requestUpdate();
      return 'unavailable';
    }
    this.checking.add(item);
    this.requestUpdate();
    let fresh: FreshDetails | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      fresh = await Promise.race([refresh(), new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error('Live details timed out')), FRESH_DETAILS_TIMEOUT_MS);
      })]);
    } catch {
      fresh = undefined;
    } finally {
      clearTimeout(timer);
      this.checking.delete(item);
      this.requestUpdate();
    }
    const id = item.prompt.pendingId;
    if (!this.holds(item) || this.decided.has(id)) return 'stale';
    if (fresh === null) { this.cancelMatching(prompt => prompt.pendingId === id); return 'gone'; }
    if (fresh === undefined) { this.answerErrors.set(item, DETAILS_UNAVAILABLE); return 'unavailable'; }
    const defined = Object.entries(fresh).filter(([, value]) => value !== undefined);
    const changed = defined.some(([key, value]) => item.prompt[key as keyof AuthorizationPrompt] !== value);
    item.prompt = { ...item.prompt, ...Object.fromEntries(defined) };
    this.answerErrors.delete(item);
    if (changed) {
      // Different details under the person's pointer: nothing done before they show can answer them.
      item.readyAt = performance.now();
      if (this.active === item) this.arm();
    }
    return changed ? 'changed' : 'fresh';
  }

  /**
   * Answer a waiting request from the status panel (Allow this time / Deny, keys 1/2). The same single
   * decision as the dialog: one execution, refused before the request armed, after its deadline, or
   * when it was answered or withdrawn elsewhere. Typed requests are only answered in the security dialog.
   */
  async answerStatusRequest(id: string, approved: boolean, allowAlways = false): Promise<void> {
    const item = this.waiting.find(row => row.prompt.pendingId === id);
    if (!item || performance.now() - (item.readyAt ?? 0) < ARMING_DELAY_MS) return;
    if (approved && item.prompt.gateBehavior === 'TYPED_CONFIRM') return;
    await this.settle(item, approved, approved && allowAlways && AuthorizationHost.honoursAllowAlways(item.prompt));
  }

  /**
   * The status panel's Review action for a request answered in the dialog: a queued prompt, a typed
   * agent request waiting in Needs you, or a plugin permission. It replaces the open one (which returns
   * undecided to the queue head, or to Needs you for an agent's), and the dialog reopens with focus.
   */
  reviewStatusRequest(id: string): void {
    const matches = (item: CeremonyItem): boolean => item.kind === 'authorize'
      ? item.prompt.pendingId === id : String(item.request.id) === id;
    if (!this.active || !matches(this.active)) {
      const index = this.queue.findIndex(matches);
      const parked = index < 0 ? this.waiting.findIndex(matches) : -1;
      // Only a typed agent request opens the security dialog; the panel reviews the others itself.
      if (index < 0 && (parked < 0 || this.waiting[parked]!.prompt.gateBehavior !== 'TYPED_CONFIRM')) return;
      const next = index >= 0 ? this.queue.splice(index, 1)[0]! : this.waiting.splice(parked, 1)[0]!;
      if (this.active) this.park(this.active);
      // An open native dialog cannot switch between show() and showModal(): end the previous
      // prompt's presentation so the selected one opens in its own modality (typed and capability
      // ceremonies modal in the top layer, lightweight non-modal), as the queue does.
      this.modal.close();
      this.show(next);
    } else {
      // Reviewing re-presents the open prompt (reopening it if it was closed without a decision):
      // the activation that asked for it cannot answer it.
      this.dismissed = false;
      this.arm();
    }
    this.requestUpdate();
    void this.updateComplete.then(() => {
      const dialog = this.dialogRef.value;
      if (!dialog?.open || !this.active || !matches(this.active)) return;
      // Typed ceremonies land in their confirmation field; others on Deny, never on Approve.
      const target = dialog.querySelector<HTMLElement>('input[type="text"]')
        ?? dialog.querySelector<HTMLElement>('jf-button.deny');
      target?.focus();
    });
  }

  /** A displaced prompt returns undecided: an agent request to Needs you, any other to the queue head. */
  private park(item: CeremonyItem): void {
    if (item.kind === 'authorize' && item.prompt.arrival === 'needs-you') {
      item.readyAt = performance.now();
      this.waiting.unshift(item);
    } else this.queue.unshift(item);
  }

  private enqueueCapability(request: ConsentRequest): void {
    // Avoid double-enqueue if both the live event and the initial drain see the same request.
    const already =
      (this.active?.kind === 'capability' && this.active.request.id === request.id) ||
      this.queue.some((i) => i.kind === 'capability' && i.request.id === request.id);
    if (already) return;
    this.queue.push({ kind: 'capability', request });
    this.pumpQueue();
  }

  private failClosed(item: CeremonyItem): void {
    // Only the one-shot authorize prompt fails closed. A capability request is left pending.
    // `failedClosed` (tempdoc 807 item 4) marks this as a teardown, not a human deny, so the
    // awaiting dispatcher can tell the user the approval never completed rather than ending in
    // silence — the ceremony vanished from under the user, which looks exactly like a dismissal.
    if (item.kind === 'authorize') {
      item.resolve({ approved: false, allowAlways: false, failedClosed: true });
    }
  }

  /**
   * Tempdoc 605 — fail-closed-deny every still-open AUTHORIZE ceremony owned by `runId` (the
   * concluding run), returning the count denied. Capability-consent items, and authorize items
   * owned by another run or run-less (`owningRunId` undefined, e.g. a Shell effect dispatch), are
   * left untouched. After draining, re-pumps so a next-run ceremony that was blocked behind a
   * drained predecessor can finally surface (the M1 fix).
   */
  cancelForRun(runId: string): number {
    if (!runId) return 0;
    return this.cancelMatching(prompt => prompt.owningRunId === runId);
  }

  private cancelMatching(matches: (prompt: AuthorizationPrompt) => boolean): number {
    const owns = (item: CeremonyItem): boolean =>
      item.kind === 'authorize' && matches(item.prompt);
    // The drain is a SUPERSEDE, not a human deny: resolve with `superseded` so the awaiting
    // dispatcher skips a backend reject POST to the now-concluded run (the legible notice is
    // surfaced once by the run-conclusion caller, not per ceremony).
    const supersede = (item: CeremonyItem): void => {
      if (item.kind === 'authorize') {
        item.resolve({ approved: false, allowAlways: false, superseded: true });
      }
    };
    let denied = 0;
    const keep = (item: CeremonyItem): boolean => {
      if (!owns(item)) return true;
      supersede(item);
      denied++;
      return false;
    };
    this.queue = this.queue.filter(keep);
    this.waiting = this.waiting.filter(keep);
    if (this.active && owns(this.active)) {
      supersede(this.active);
      this.show(null);
      this.modal.close();
      denied++;
    }
    if (denied > 0) {
      this.requestUpdate();
      this.pumpQueue();
    }
    return denied;
  }

  private pumpQueue(): void {
    if (this.active || this.queue.length === 0) return;
    // End the previous ceremony before rendering the next: an already-open native dialog cannot
    // switch between show() and showModal(), and each queued prompt owns its own modality.
    this.modal.close();
    this.show(this.queue.shift()!);
    this.requestUpdate();
  }

  /** Make `item` the presented prompt (or none) with fresh state; nothing done before it can answer it. */
  private show(item: CeremonyItem | null): void {
    if (item) this.answerErrors.delete(item);
    this.active = item;
    this.typed = '';
    this.allowAlways = false;
    this.dismissed = false;
    this.arm();
  }

  private arm(): void {
    this.presentedAt = performance.now();
    this.press = null;
    this.keyPress = null;
    this.typingKeys.clear();
  }

  private armed(at = performance.now()): boolean {
    return at - this.presentedAt >= ARMING_DELAY_MS;
  }

  /** The clickable control an event belongs to: a decision button or the Always-allow checkbox (with its label). */
  private static control(e: Event): Element | null {
    return e.composedPath().find((node): node is Element =>
      node instanceof Element && node.matches('.actions > jf-button, label.allow-always')) ?? null;
  }

  private static typedField(e: Event): boolean {
    const origin = e.composedPath()[0];
    return origin instanceof HTMLInputElement && origin.type === 'text';
  }

  /**
   * One deliberate interaction answers or changes one prompt. Pointer clicks on a decision button or
   * the Always-allow checkbox must complete a press that started on the same control after the prompt
   * armed; keyboard activation happens on the release of an armed press that never auto-repeated (the
   * native Enter-on-keydown, Space-on-release of an earlier press and repeat clicks are refused). Other
   * activations (assistive technology's default action) only wait for arming. The typed confirmation
   * takes keys pressed after arming (and their repeats) and other edits (paste, drop) after arming.
   */
  private readonly onPointerDown = (e: PointerEvent): void => {
    this.press = { at: performance.now(), button: AuthorizationHost.control(e) };
  };

  private readonly onClick = (e: MouseEvent): void => {
    const control = AuthorizationHost.control(e);
    if (!control) return;
    const press = this.press;
    // A label click is followed by the click it forwards to its checkbox: both complete the one press.
    if (e.detail > 0 && !(control.matches('label') && !(e.composedPath()[0] instanceof HTMLInputElement))) this.press = null;
    const deliberate = this.armed() && (e.detail === 0
      || (press !== null && press.button === control && this.armed(press.at)));
    if (deliberate) return;
    e.preventDefault();
    e.stopPropagation();
  };

  private readonly onKey = (e: KeyboardEvent): void => {
    if (AuthorizationHost.typedField(e)) {
      if (e.type === 'keyup') { this.typingKeys.delete(e.key); return; }
      if (e.key === 'Tab') return;
      if (!e.repeat && this.armed()) this.typingKeys.add(e.key);
      if (!this.typingKeys.has(e.key)) e.preventDefault();
      return;
    }
    const control = AuthorizationHost.control(e);
    // A checkbox toggles on Space only; Enter does nothing there.
    if (!control || !(e.key === ' ' || (e.key === 'Enter' && !control.matches('label')))) return;
    e.preventDefault();
    const pending = this.keyPress;
    this.keyPress = null;
    if (e.type === 'keydown') {
      if (!e.repeat && this.armed()) this.keyPress = { key: e.key, button: control };
    } else if (pending?.key === e.key && pending.button === control && this.armed()) {
      const origin = e.composedPath()[0];
      if (origin instanceof HTMLButtonElement || origin instanceof HTMLInputElement) origin.click();
    }
  };

  private readonly onBeforeInput = (e: InputEvent): void => {
    if (AuthorizationHost.typedField(e) && !this.armed()) e.preventDefault();
  };

  /**
   * The platform closed the dialog without a decision (Escape, `dialog.close()`): release modality and
   * scroll lock as a decision does. The prompt stays pending; Review presents it again. A close the
   * host started for the next prompt arrives after that prompt reopened the dialog, and is ignored.
   */
  private readonly onClose = (): void => {
    if (this.dialogRef.value?.open) return;
    this.dismissed = true;
    this.modal.close();
    // A typed agent request closed without a decision goes back to Needs you and frees the dialog.
    const active = this.active;
    if (active?.kind === 'authorize' && active.prompt.arrival === 'needs-you') {
      this.active = null;
      this.park(active);
      this.requestUpdate();
      this.pumpQueue();
    }
  };

  /**
   * Tempdoc 875 C.2 — whether the backend would actually keep an "always allow" promise for this
   * prompt. `DurableGrantStore.isAllowed` refuses `RiskTier.HIGH` before consulting any grant set, so
   * a durable grant on a HIGH-risk operation can never satisfy its gate. Offering the checkbox there
   * would be a control that lies: the user checks it, a grant is minted, and the next invocation
   * prompts anyway. The control is hidden instead — and `decide` re-applies the rule so the decision
   * can never carry `allowAlways: true` even if the flag were set by some other path.
   *
   * <p>The second clause is the same rule from the other side: a caller whose approval route cannot
   * carry `allowAlways` at all declares `allowAlwaysSupported: false`, so the checkbox is not
   * offered rather than rendered and silently dropped.
   */
  private static honoursAllowAlways(prompt: AuthorizationPrompt): boolean {
    return prompt.riskTier !== 'HIGH' && prompt.allowAlwaysSupported !== false;
  }

  private async decide(approved: boolean, expectedId: string): Promise<void> {
    const current = this.active;
    if (current?.kind !== 'authorize' || current.prompt.pendingId !== expectedId) return;
    if (approved && current.prompt.gateBehavior === 'TYPED_CONFIRM' && this.typed !== current.prompt.operationId) return;
    const allowAlways = approved && AuthorizationHost.honoursAllowAlways(current.prompt) && this.allowAlways;
    await this.settle(current, approved, allowAlways);
  }

  /** Whether the item is still presented, queued or waiting here (not answered or withdrawn). */
  private holds(item: CeremonyItem): boolean {
    return this.active === item || this.queue.includes(item) || (item.kind === 'authorize' && this.waiting.includes(item));
  }

  /**
   * The one decision path for the dialog and the status panel (C12): an expired request is withdrawn;
   * an agent's request is allowed only on live details read for this press that match what was shown
   * (DE3); a denial cancels the backend request first and stays answerable when that fails (DE8); the
   * id is then decided exactly once and leaves every list before its caller hears the answer.
   */
  private async settle(item: AuthorizeItem, approved: boolean, allowAlways: boolean): Promise<void> {
    const id = item.prompt.pendingId;
    const expired = (): boolean => !!item.prompt.expiresAt && Date.parse(item.prompt.expiresAt) <= Date.now();
    if (this.decided.has(id) || this.rejecting.has(item) || !this.holds(item) || (approved && this.checking.has(item))) return;
    if (expired()) { this.cancelMatching(prompt => prompt.pendingId === id); return; }
    if (approved && item.prompt.arrival === 'needs-you') {
      const read = await this.readFresh(item);
      if (read === 'changed') this.answerErrors.set(item, DETAILS_CHANGED);
      this.requestUpdate();
      if (read !== 'fresh' || this.decided.has(id) || this.rejecting.has(item) || !this.holds(item)) return;
      if (expired()) { this.cancelMatching(prompt => prompt.pendingId === id); return; }
    }
    if (!approved && item.prompt.reject) {
      this.rejecting.add(item);
      this.answerErrors.delete(item);
      this.requestUpdate();
      try {
        await item.prompt.reject();
      } catch {
        if (this.holds(item)) this.answerErrors.set(item, 'The request could not be cancelled. Try again.');
        return;
      } finally {
        this.rejecting.delete(item);
        this.requestUpdate();
      }
      if (!this.holds(item) || this.decided.has(id)) {
        this.cancelMatching(prompt => prompt.pendingId === id);
        return;
      }
    }
    this.decided.add(id);
    if (this.decided.size > 500) this.decided.delete(this.decided.values().next().value!);
    if (this.active === item) this.show(null);
    this.queue = this.queue.filter(row => row !== item);
    this.waiting = this.waiting.filter(row => row !== item);
    item.resolve({ approved, allowAlways });
    this.requestUpdate();
    this.pumpQueue();
  }

  private decideCapability(decision: ConsentDecision, expectedId: ConsentRequest['id']): void {
    const current = this.active;
    if (current?.kind !== 'capability' || current.request.id !== expectedId) return;
    this.show(null);
    resolveConsentRequest(current.request.id, decision);
    this.requestUpdate();
    this.pumpQueue();
  }

  /** Is the active prompt a non-blocking low/medium-risk authorize (565 §3.D)? */
  /** The FULL modal contract (574 §22.G) — native <dialog> + scroll-lock + focus-restore, atomic by
      construction. Per-ceremony `nonBlocking` (the lightweight low/medium authorize, 565 §3.D) skips the
      lock + uses show(); exit() is idempotent so close() is uniform across both paths. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.dialogRef.value,
  });

  private isLightweight(): boolean {
    return (
      this.active?.kind === 'authorize' &&
      this.active.prompt.gateBehavior !== 'TYPED_CONFIRM'
    );
  }

  override updated(): void {
    // 569 Move 4 — open the dialog on the trusted channel. Security-bearing ceremonies
    // (high-risk typed-confirm + capability) open MODAL → the browser Top Layer, which no
    // skin overlay can occlude. Low/medium authorize opens non-modal (565 §3.D non-blocking).
    this.dispatchEvent(new CustomEvent('status-requests-changed', { bubbles: true, composed: true }));
    if (!this.active) this.modal.close();
    else if (!this.dismissed) this.modal.open({ nonBlocking: this.isLightweight() });
  }

  override render(): TemplateResult {
    const testid = !this.active
      ? nothing
      : this.active.kind === 'capability'
        ? 'capability-ceremony'
        : 'authorization-ceremony';
    const body = !this.active
      ? nothing
      : this.active.kind === 'capability'
        ? this.renderCapabilityBody(this.active.request)
        : this.renderAuthorizeBody(this.active.prompt);
    // Each prompt gets its own controls: jf-button forwards onActivate without re-rendering, so a
    // reused button would carry the previous prompt's exact-target closure into the next one.
    const identity = !this.active ? '' : this.active.kind === 'capability'
      ? `capability:${String(this.active.request.id)}` : `authorize:${this.active.prompt.pendingId}`;
    return html`
      <dialog
        ${ref(this.dialogRef)}
        class="ceremony ${this.isLightweight() ? 'lightweight' : ''}"
        data-testid=${testid}
        @cancel=${(e: Event) => e.preventDefault()}
        @close=${this.onClose}
        @pointerdown=${{ handleEvent: this.onPointerDown, capture: true }}
        @click=${{ handleEvent: this.onClick, capture: true }}
        @keydown=${{ handleEvent: this.onKey, capture: true }}
        @keyup=${{ handleEvent: this.onKey, capture: true }}
        @beforeinput=${{ handleEvent: this.onBeforeInput, capture: true }}
      >
        ${this.active && !this.isLightweight() ? html`<p class="security" data-testid="security-check-header">
          <strong><span aria-hidden="true">⛨</span> JustSearch security check</strong><span>can't be restyled by skins or plugins</span></p>` : nothing}
        ${keyed(identity, body)}
      </dialog>
    `;
  }

  private renderAuthorizeBody(prompt: AuthorizationPrompt): TemplateResult {
    const { operationId, gateBehavior } = prompt;
    const typedGate = gateBehavior === 'TYPED_CONFIRM';
    const matches = !typedGate || this.typed === operationId;
    return html`
      <h3>Authorize action</h3>
      <p>An action requires your approval: <code>${operationId}</code></p>
      ${prompt.requestedBy
        ? html`<p class="rationale" data-testid="authorization-requested-by">Requested by: ${prompt.requestedBy}</p>`
        : ''}
      ${prompt.purpose
        ? html`<p class="rationale" data-testid="authorization-purpose">${prompt.purpose}</p>`
        : ''}
      ${prompt.riskTier
        ? html`<p class="rationale" data-testid="authorization-risk">Risk: ${prompt.riskTier}</p>`
        : ''}
      ${prompt.argsSummary
        ? html`<p class="description" data-testid="authorization-args"><code>${prompt.argsSummary}</code></p>`
        : ''}
      ${prompt.undoSupported === false
        ? html`<p class="rationale" data-testid="authorization-irreversible">⚠ This action cannot be undone.</p>`
        : ''}
      ${typedGate
        ? html`<p id="authorization-typed-label">Type <code>${operationId}</code> to confirm:</p>
            <input
              type="text"
              data-testid="authorization-typed-input"
              aria-labelledby="authorization-typed-label"
              .value=${this.typed}
              @input=${(e: Event) => {
                this.typed = (e.target as HTMLInputElement).value;
              }}
            />`
        : ''}
      ${AuthorizationHost.honoursAllowAlways(prompt)
        ? html`<label class="allow-always" data-testid="authorization-allow-always">
            <input
              type="checkbox"
              .checked=${this.allowAlways}
              @change=${(e: Event) => {
                this.allowAlways = (e.target as HTMLInputElement).checked;
              }}
            />
            Always allow this action (don't ask again)
          </label>`
        : ''}
      ${this.active && this.answerErrors.get(this.active) ? html`<p role="alert">${this.answerErrors.get(this.active)}</p>` : ''}
      <div class="actions">
        <jf-button ?disabled=${!!this.active && this.rejecting.has(this.active)} class="deny" variant="danger" data-testid="authorization-deny" label="Deny" .onActivate=${() => this.decide(false, prompt.pendingId)}>
          Deny
        </jf-button>
        <jf-button
          class="approve"
          variant="primary"
          data-testid="authorization-approve"
          label="Approve"
          ?disabled=${!matches || (!!this.active && (this.rejecting.has(this.active) || (this.active.kind === 'authorize' && this.checking.has(this.active))))}
          .onActivate=${() => this.decide(true, prompt.pendingId)}
        >
          Approve
        </jf-button>
      </div>
    `;
  }

  private renderCapabilityBody(request: ConsentRequest): TemplateResult {
    return html`
      <div data-consent-id=${request.id}>
        <h3>${request.contributorId} requests permission</h3>
        <p><code>${request.capability}</code></p>
        ${request.description
          ? html`<p class="description">${request.description}</p>`
          : nothing}
        ${request.rationale ? html`<p class="rationale">${request.rationale}</p>` : nothing}
        <div class="actions">
          <jf-button
            class="deny"
            variant="danger"
            data-testid="capability-deny"
            label="Deny"
            .onActivate=${() => this.decideCapability('deny', request.id)}
          >
            Deny
          </jf-button>
          <span class="spacer"></span>
          <jf-button
            data-testid="capability-allow-always"
            label="Allow always"
            .onActivate=${() => this.decideCapability('allow-always', request.id)}
          >
            Allow always
          </jf-button>
          <!-- DE4 — the narrower grant is the primary: "Allow once". -->
          <jf-button
            class="approve"
            variant="primary"
            data-testid="capability-allow-once"
            label="Allow once"
            .onActivate=${() => this.decideCapability('allow-once', request.id)}
          >
            Allow once
          </jf-button>
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-authorization-host')) {
  customElements.define('jf-authorization-host', AuthorizationHost);
}
