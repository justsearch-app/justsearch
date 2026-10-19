// SPDX-License-Identifier: Apache-2.0
/**
 * MemorySurface — `<jf-memory-surface>` (tempdoc 561 P-E; tempdoc 565 §26.D).
 *
 * The user-facing projection of the agent's learned MEMORY — the §P-E canonical record: inspect "what
 * it knows", record a durable fact, FORGET an item. This is the "what it knows" half.
 *
 * Tempdoc 565 §26.D — the ACTIVITY half (the presence inbox + the run-in-background launcher) was
 * FOLDED into the one interaction window: a conversation-linked background run renders as a `background`
 * run-segment in the thread (§26.A), and the cross-conversation inbox is a tab of the retrospective
 * drawer (`RetrospectivePanel`). So this surface no longer reads `/api/presence` — "what it did" lives
 * with the runs, not beside the durable facts ("what it knows" ≠ "what it did" — §14/§24, the ChatGPT
 * split). The durable-facts surface deliberately STAYS a separate peer (the §26.E fork-deletion keeps
 * one run-activity renderer: the unified projection).
 *
 * Self-contained: reads/writes the backend (`/api/memory`) via the forwarded `api-base`. Composes the
 * one SurfaceLayout authority (layout-purity), no own <h1> — the shell topbar owns the page heading,
 * this surface uses <h2> so it is embeddable as a host member (578 heading closure), native
 * controls (559 operability), no bare colored literals (the one theme authority).
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import '../components/Button.js';
import { icon } from '../components/Icon.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import { reasonFor } from '../state/readinessNotice.js';
import { unavailableBecause } from '../state/availability.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';

interface MemoryItem {
  readonly id: string;
  readonly kind: string;
  readonly content: string;
  readonly actor: string;
  readonly createdAt: string;
}

/**
 * Tempdoc 806 W1 (design direction D5) — the refusal, named on the control itself. Preventing the
 * interaction beats reporting its failure; the error path stays because the store's OTHER producer (the
 * agent's `core_remember`) never passes through this input.
 */
const LOCKED_INPUT_REASON = 'Unlock memory to teach it a new fact';

export class MemorySurface extends JfElement {
  static properties = {
    apiBase: { attribute: 'api-base', type: String },
    memories: { state: true },
    rememberDraft: { state: true },
    busy: { state: true },
    // Tempdoc 806 W1 — the store is encrypted + locked (`GET /api/memory` → `locked: true`). NOT the
    // same state as "no memories": the list is empty because it cannot be read, so this surface must
    // never answer it with the empty-state claim.
    locked: { state: true },
    // Tempdoc 806 W1 — the last mutation's failure, surfaced instead of discarded. Before this, both
    // mutations awaited the fetch and ignored its status, so a refused write re-rendered as success.
    mutationError: { state: true },
    loadError: { state: true },
    loading: { state: true },
  };

  declare apiBase: string;
  declare memories: MemoryItem[];
  declare rememberDraft: string;
  declare busy: boolean;
  declare locked: boolean;
  declare mutationError: string;
  declare loadError: string;
  declare loading: boolean;

  constructor() {
    super();
    this.apiBase = '';
    this.memories = [];
    this.rememberDraft = '';
    this.busy = false;
    this.locked = false;
    this.mutationError = '';
    this.loadError = '';
    this.loading = true;
  }

  connectedCallback(): void {
    super.connectedCallback();
    void this.loadMemories();
  }

  /** Tempdoc 609 — settle transient state on hide (the in-flight save flag) so a return doesn't show a
   *  stale spinner. The `rememberDraft` is an in-progress user input (recoverable) and is kept;
   *  `memories` re-loads on reconnect. Auto-invoked via JfElement.disconnectedCallback.
   *
   *  806 W1: `mutationError` settles the same way — it reports ONE attempt, so it must not outlive the
   *  navigation that ended it (a stale "nothing was saved" beside a list that has since reloaded). */
  static override transientState = { busy: false, mutationError: '', loading: false, loadError: '' };

  private base(): string {
    return this.apiBase || '';
  }

  private async loadMemories(): Promise<void> {
    this.loading = true;
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory`);
      if (!r.ok) {
        this.loadError = 'Memories are unavailable. Try opening Memory again after the stored memories can be read.';
        return;
      }
      const j = (await r.json()) as { memories?: MemoryItem[]; locked?: boolean };
      this.memories = Array.isArray(j.memories) ? j.memories : [];
      // Tempdoc 806 W1: the wire distinguishes "cannot read" from "nothing learned" — carry it, so the
      // render can. An older backend omits the field ⇒ `false` (readable), the pre-806 behaviour.
      this.locked = j.locked === true;
      this.loadError = '';
    } catch {
      this.loadError = 'Memories are unavailable. Could not reach the backend.';
    } finally {
      this.loading = false;
    }
  }

  /**
   * Tempdoc 806 W1 — the failure of a mutation, worded. A `423` is the store refusing because its key
   * is locked (the ONE CAUSE_ROWS vocabulary words it, so this surface cannot drift from the locked-chat
   * affordance); anything else is a genuine failure and says so rather than passing as success.
   */
  private noteMutationFailure(status: number, verb: string): void {
    this.mutationError =
      status === 423
        ? `${reasonFor('memory.locked').wording} — nothing was ${verb}.`
        : `Could not ${verb === 'saved' ? 'save' : 'forget'} that (server said ${status}).`;
  }

  private async remember(): Promise<void> {
    const content = this.rememberDraft.trim();
    if (!content || this.busy || this.locked || this.loading || this.loadError) return;
    this.busy = true;
    this.mutationError = '';
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content, kind: 'fact' }),
      });
      if (!r.ok) {
        // The write did NOT land: keep the draft (it is the user's unsaved text) and re-read, so the
        // list shown is the server's answer rather than an optimistic one.
        this.noteMutationFailure(r.status, 'saved');
        await this.loadMemories();
        return;
      }
      this.rememberDraft = '';
      await this.loadMemories();
    } catch {
      this.mutationError = 'Could not reach the backend — nothing was saved.';
    } finally {
      this.busy = false;
    }
  }

  private async forget(id: string): Promise<void> {
    this.mutationError = '';
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      });
      // Re-read either way: on failure the record is STILL there and the list must show it (a
      // "forgotten" item that quietly returns on unlock is the privacy defect this closes).
      if (!r.ok) this.noteMutationFailure(r.status, 'forgotten');
      await this.loadMemories();
    } catch {
      this.mutationError = 'Could not reach the backend — nothing was forgotten.';
    }
  }

  /**
   * Tempdoc 806 W1 — the locked read, rendered as what it is. This REPLACES the empty state while the
   * store is locked: "No learned memory yet." is a positive claim about what the AI knows, and the one
   * thing a locked store cannot support is a claim about its contents. Wording + remedy come from the
   * ONE CAUSE_ROWS authority (`reasonFor`), so this cannot drift from the locked-chat affordance.
   */
  private renderLocked(): TemplateResult {
    const r = reasonFor('memory.locked');
    const nav = r.remedy?.kind === 'navigate' ? r.remedy : null;
    return html`
      <div class="locked" role="status">
        <p>${icon({ name: 'shield', size: 16 })} <strong>${r.wording}</strong>.</p>
        <p class="help">Unlock to see what the AI has learned — your search index is unaffected.</p>
        ${nav
          ? html`<jf-button label=${nav.label} .onActivate=${() => requestSurfaceNavigation(nav.target)}>
              ${nav.label}
            </jf-button>`
          : nothing}
      </div>
    `;
  }

  render(): TemplateResult {
    return html`
      <div class="surface-scroll memory-surface">
        <!-- Tempdoc 571 §11 / 578: the page <h1> is owned by the shell topbar (surface title); this
             surface renders <h2> so it does not emit a second <h1> when embedded as a host member. -->
        <h2 class="surface-title-h">Memory</h2>

        <section>
          <h2>What it knows</h2>
          ${this.loading
            ? html`<p role="status">Loading memories…</p>`
            : this.loadError
              ? html`<p role="alert">${this.loadError}</p>`
              : this.locked
            ? this.renderLocked()
            : this.memories.length === 0
              ? html`<p class="empty">No learned memory yet.</p>`
              : html`<ul class="list">
                  ${this.memories.map(
                    (m) => html`<li>
                      <span class="kind">${m.kind}</span>
                      <span class="content">${m.content}</span>
                      <jf-button
                        size="sm"
                        label="Forget"
                        title="Forget this"
                        .onActivate=${() => void this.forget(m.id)}
                      >
                        Forget
                      </jf-button>
                    </li>`,
                  )}
                </ul>`}
          ${this.mutationError
            ? html`<p class="mutation-error" role="alert">${this.mutationError}</p>`
            : nothing}
          <div class="add-row">
            <input
              class="draft"
              .value=${this.rememberDraft}
              aria-label="Teach it a durable fact"
              ?disabled=${this.locked || this.loading || !!this.loadError}
              placeholder=${this.locked ? LOCKED_INPUT_REASON : 'Teach it a durable fact…'}
              @input=${(e: Event) => (this.rememberDraft = (e.target as HTMLInputElement).value)}
            />
            <jf-button
              variant="primary"
              label="Remember"
              ?disabled=${this.busy || !this.rememberDraft.trim()}
              .availability=${this.loadError || this.loading
                ? unavailableBecause(this.loadError || 'Loading memories', true)
                : this.locked ? unavailableBecause(LOCKED_INPUT_REASON, true) : undefined}
              .onActivate=${() => void this.remember()}
            >
              Remember
            </jf-button>
          </div>
        </section>
      </div>
    `;
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
      .memory-surface {
        padding: 1rem 1.25rem;
        display: flex;
        flex-direction: column;
        gap: 1.5rem;
      }
      section {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
      }
      h2 {
        font-size: var(--font-size-md);
        margin: 0;
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .list {
        list-style: none;
        margin: 0;
        padding: 0;
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
      }
      .list li {
        display: flex;
        align-items: center;
        gap: 0.5rem;
      }
      .kind {
        font-size: var(--font-size-xs);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        opacity: 0.7;
      }
      .content {
        flex: 1;
      }
      .empty {
        opacity: 0.6;
        margin: 0;
      }
      /* 806 W1 — the locked read + a refused mutation. */
      .locked {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 0.5rem;
      }
      .locked p {
        margin: 0;
      }
      .locked .help {
        opacity: 0.75;
        font-size: var(--font-size-sm);
      }
      .mutation-error {
        margin: 0;
        color: var(--text-danger);
        font-size: var(--font-size-sm);
      }
      .add-row {
        display: flex;
        gap: 0.5rem;
        margin-top: 0.25rem;
      }
      .draft {
        flex: 1;
        padding: 0.35rem 0.5rem;
      }
      /* 574 B1 — the action buttons are jf-button atoms now; the base button{} fork is gone. */
    `,
  ];
}

customElements.define('jf-memory-surface', MemorySurface);
