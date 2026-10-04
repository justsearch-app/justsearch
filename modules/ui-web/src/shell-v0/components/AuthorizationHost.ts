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
 * not visual. Low/medium-risk approvals stay non-blocking (`show()`, 565 §3.D) so an agent
 * run stays interactive; promoting them to the top layer non-modally (Popover API) is the
 * further refinement.
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
import {
  setAuthorizationPresenter,
  setAuthorizationCanceller,
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
  readonly prompt: AuthorizationPrompt;
  readonly resolve: (decision: AuthorizationDecision) => void;
}

/** A plugin capability-consent request awaiting an allow-once / allow-always / deny decision. */
interface CapabilityItem {
  readonly kind: 'capability';
  readonly request: ConsentRequest;
}

type CeremonyItem = AuthorizeItem | CapabilityItem;

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
  private consentListener: ((e: Event) => void) | null = null;
  /** 569 Move 4 — the native <dialog> on the trusted channel (Top Layer when modal). */
  private readonly dialogRef: Ref<HTMLDialogElement> = createRef();

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
      border-radius: 0.5rem;
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
    setAuthorizationCanceller((runId) => this.cancelForRun(runId));
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
    this.queue = [];
    this.active = null;
  }

  /** Broker entry point: enqueue an authorize prompt and return a promise of the decision. */
  present(prompt: AuthorizationPrompt): Promise<AuthorizationDecision> {
    return new Promise<AuthorizationDecision>((resolve) => {
      this.queue.push({ kind: 'authorize', prompt, resolve });
      this.pumpQueue();
    });
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
    const owns = (item: CeremonyItem): boolean =>
      item.kind === 'authorize' && item.prompt.owningRunId === runId;
    // The drain is a SUPERSEDE, not a human deny: resolve with `superseded` so the awaiting
    // dispatcher skips a backend reject POST to the now-concluded run (the legible notice is
    // surfaced once by the run-conclusion caller, not per ceremony).
    const supersede = (item: CeremonyItem): void => {
      if (item.kind === 'authorize') {
        item.resolve({ approved: false, allowAlways: false, superseded: true });
      }
    };
    let denied = 0;
    this.queue = this.queue.filter((item) => {
      if (!owns(item)) return true;
      supersede(item);
      denied++;
      return false;
    });
    if (this.active && owns(this.active)) {
      supersede(this.active);
      this.active = null;
      denied++;
    }
    if (denied > 0) {
      this.typed = '';
      this.allowAlways = false;
      this.requestUpdate();
      this.pumpQueue();
    }
    return denied;
  }

  private pumpQueue(): void {
    if (this.active || this.queue.length === 0) return;
    this.active = this.queue.shift()!;
    this.typed = '';
    this.allowAlways = false;
    this.requestUpdate();
  }

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

  private decide(approved: boolean): void {
    const current = this.active;
    const honoured =
      current?.kind === 'authorize' ? AuthorizationHost.honoursAllowAlways(current.prompt) : true;
    const allowAlways = approved && honoured && this.allowAlways;
    this.active = null;
    this.typed = '';
    this.allowAlways = false;
    if (current?.kind === 'authorize') current.resolve({ approved, allowAlways });
    this.requestUpdate();
    this.pumpQueue();
  }

  private decideCapability(decision: ConsentDecision): void {
    const current = this.active;
    this.active = null;
    this.typed = '';
    if (current?.kind === 'capability') resolveConsentRequest(current.request.id, decision);
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
    if (this.active) this.modal.open({ nonBlocking: this.isLightweight() });
    else this.modal.close();
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
    return html`
      <dialog
        ${ref(this.dialogRef)}
        class="ceremony ${this.isLightweight() ? 'lightweight' : ''}"
        data-testid=${testid}
        @cancel=${(e: Event) => e.preventDefault()}
      >
        ${body}
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
      <div class="actions">
        <jf-button class="deny" variant="danger" data-testid="authorization-deny" label="Deny" .onActivate=${() => this.decide(false)}>
          Deny
        </jf-button>
        <jf-button
          class="approve"
          variant="primary"
          data-testid="authorization-approve"
          label="Approve"
          ?disabled=${!matches}
          .onActivate=${() => this.decide(true)}
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
            .onActivate=${() => this.decideCapability('deny')}
          >
            Deny
          </jf-button>
          <jf-button
            data-testid="capability-allow-once"
            label="Allow once"
            .onActivate=${() => this.decideCapability('allow-once')}
          >
            Allow once
          </jf-button>
          <jf-button
            class="approve"
            variant="primary"
            data-testid="capability-allow-always"
            label="Allow always"
            .onActivate=${() => this.decideCapability('allow-always')}
          >
            Allow always
          </button>
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-authorization-host')) {
  customElements.define('jf-authorization-host', AuthorizationHost);
}
