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
import { confirmAsync } from '../components/ConfirmDialog.js';
import type { JfButton } from '../components/Button.js';

interface MemoryItem {
  readonly id: string;
  readonly kind: string;
  readonly content: string;
  readonly actor: string;
  readonly createdAt: string;
  readonly sourceConversationId?: string;
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
    announcement: { state: true },
  };

  declare apiBase: string;
  declare memories: MemoryItem[];
  declare rememberDraft: string;
  declare busy: boolean;
  declare locked: boolean;
  declare mutationError: string;
  declare loadError: string;
  declare loading: boolean;
  declare announcement: string;

  private unresolvedForget?: { id: string; index: number };
  private loadVersion = 0;
  private visitVersion = 0;

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
    this.announcement = '';
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
  static override transientState = { busy: false, mutationError: '', loading: false, loadError: '', announcement: '' };

  protected override settleTransients(): void {
    this.unresolvedForget = undefined;
    ++this.loadVersion;
    ++this.visitVersion;
    super.settleTransients();
  }

  private base(): string {
    return this.apiBase || '';
  }

  private async loadMemories(): Promise<boolean> {
    const version = ++this.loadVersion;
    this.loading = true;
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory`);
      if (version !== this.loadVersion) return false;
      if (!r.ok) {
        this.loadError = 'Memories are unavailable. Try opening Memory again after the stored memories can be read.';
        return false;
      }
      const j = (await r.json()) as { memories?: MemoryItem[]; locked?: boolean };
      if (version !== this.loadVersion) return false;
      // The API always emits a boolean lock state. Missing or malformed metadata cannot establish
      // presence or absence, even when the list itself looks valid.
      if (typeof j.locked !== 'boolean' || !Array.isArray(j.memories) || j.memories.some(m =>
        !m || typeof m.id !== 'string' || typeof m.content !== 'string')) {
        throw new Error('Invalid memory list');
      }
      this.memories = j.memories;
      this.locked = j.locked;
      this.loadError = '';
      return !this.locked;
    } catch {
      if (version === this.loadVersion) {
        this.loadError = 'Memories are unavailable. Could not read the stored memories.';
      }
      return false;
    } finally {
      if (version === this.loadVersion) this.loading = false;
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
    const visit = this.visitVersion;
    this.busy = true;
    this.mutationError = '';
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ content, kind: 'fact' }),
      });
      if (visit !== this.visitVersion || !this.isConnected) return;
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
      if (visit === this.visitVersion && this.isConnected) {
        this.mutationError = 'Could not reach the backend — nothing was saved.';
      }
    } finally {
      if (visit === this.visitVersion) this.busy = false;
    }
  }

  private async confirmForget(memory: MemoryItem): Promise<void> {
    if (this.busy || this.locked || this.loading || this.loadError) return;
    const visit = this.visitVersion;
    const index = this.memories.findIndex(m => m.id === memory.id);
    let confirmed = false;
    this.busy = true;
    this.announcement = '';
    try {
      confirmed = await confirmAsync({
        title: 'Forget this memory?',
        message: `Forget “${memory.content}”? The assistant will no longer remember this item. This cannot be undone.`,
        confirmLabel: 'Forget',
        cancelLabel: 'Cancel',
      });
      if (confirmed && visit === this.visitVersion && this.isConnected) {
        // The store/API has no conditional forget. Re-read immediately before deleting so consent
        // cannot knowingly apply to a replacement at the same id. A read/delete race remains.
        const readable = await this.loadMemories();
        if (visit !== this.visitVersion || !this.isConnected) return;
        if (!readable) {
          if (this.locked && !this.loadError) this.noteMutationFailure(423, 'forgotten');
          else this.mutationError = 'Could not check this memory. Nothing was forgotten. Reload memories and try again.';
          return;
        }
        const current = this.memories.find(m => m.id === memory.id);
        if (!current) {
          this.mutationError = '';
          this.announcement = 'This item is no longer in memory. Nothing was forgotten by this request.';
          return;
        }
        if ((['content', 'kind', 'actor', 'createdAt', 'sourceConversationId'] as const)
          .some(field => current[field] !== memory[field])) {
          this.mutationError = 'This memory changed while confirmation was open. Review the current item and choose Forget again.';
          return;
        }
        await this.forget(memory.id);
      }
    } finally {
      if (visit === this.visitVersion) {
        this.busy = false;
        if (confirmed && this.isConnected) await this.focusMemory(index, visit);
      }
    }
  }

  private async focusMemory(index: number, visit: number): Promise<void> {
    await this.updateComplete;
    if (visit !== this.visitVersion || !this.isConnected) return;
    const buttons = this.shadowRoot!.querySelectorAll<JfButton>('.list jf-button');
    const input = this.shadowRoot!.querySelector<HTMLInputElement>('input.draft');
    const next = buttons[Math.min(Math.max(index, 0), buttons.length - 1)]
      ?? (input?.disabled ? this.shadowRoot!.querySelector<JfButton>('jf-button[label="Reload memories"]') : null);
    if (next) {
      await next.updateComplete;
      const control = next.shadowRoot?.querySelector('jf-control');
      if (control) await (control as JfElement).updateComplete;
      if (visit === this.visitVersion && this.isConnected) next.focus();
    } else if (input && !input.disabled) input.focus();
  }

  private reconcileForget(): void {
    if (!this.unresolvedForget) return;
    this.mutationError = '';
    this.announcement = this.memories.some(m => m.id === this.unresolvedForget!.id)
      ? 'The forget response was lost. The store confirms this item is still in memory. You can try Forget again.'
      : 'The forget response was lost. The store confirmed this item is absent.';
    this.unresolvedForget = undefined;
  }

  private async reloadMemories(): Promise<void> {
    if (this.busy || this.loading) return;
    const visit = this.visitVersion;
    const index = this.unresolvedForget?.index ?? 0;
    if (!this.unresolvedForget) this.mutationError = '';
    this.announcement = '';
    const readable = await this.loadMemories();
    if (visit !== this.visitVersion || !this.isConnected) return;
    if (readable) this.reconcileForget();
    await this.focusMemory(index, visit);
  }

  private async forget(id: string): Promise<void> {
    const visit = this.visitVersion;
    this.mutationError = '';
    this.announcement = '';
    let lost = false;
    let failed = false;
    try {
      const r = await authorizedFetch(`${this.base()}/api/memory/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }, { retry: false });
      if (visit !== this.visitVersion || !this.isConnected) return;
      failed = !r.ok;
      if (failed) this.noteMutationFailure(r.status, 'forgotten');
    } catch {
      if (visit !== this.visitVersion || !this.isConnected) return;
      lost = true;
      this.unresolvedForget = { id, index: this.memories.findIndex(m => m.id === id) };
      this.mutationError = 'The forget response was lost. The outcome is unknown; checking the stored memories…';
    }
    // A dropped response can follow a durable write. Only a fresh, readable list resolves it.
    const readable = await this.loadMemories();
    if (visit !== this.visitVersion || !this.isConnected) return;
    if (lost) {
      if (!readable) {
        this.mutationError = 'The forget response was lost. The outcome is unknown. Reload memories after the store is available or unlocked to check.';
        return;
      }
      this.reconcileForget();
    } else if (readable && !failed && !this.memories.some(m => m.id === id)) {
      this.announcement = 'Forgotten.';
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
        <p class="announcement" role="status" aria-live="polite">${this.announcement}</p>

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
                        label=${`Forget: ${m.content}`}
                        .availability=${this.busy ? unavailableBecause('A memory change is in progress', true) : undefined}
                        @keydown=${(e: KeyboardEvent) => {
                          if (e.repeat && (e.key === 'Enter' || e.key === ' ')) {
                            e.preventDefault();
                            e.stopPropagation();
                          }
                        }}
                        .onActivate=${() => this.confirmForget(m)}
                      >
                        Forget
                      </jf-button>
                    </li>`,
                  )}
                </ul>`}
          ${this.mutationError
            ? html`<p class="mutation-error" role="alert">${this.mutationError}</p>`
            : nothing}
          <jf-button
            label="Reload memories"
            ?disabled=${this.busy || this.loading}
            .onActivate=${() => this.reloadMemories()}
          >Reload memories</jf-button>
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
        flex-wrap: wrap;
      }
      .kind {
        font-size: var(--font-size-xs);
        text-transform: uppercase;
        letter-spacing: 0.05em;
        opacity: 0.7;
      }
      .content {
        flex: 1;
        min-width: 8rem;
        overflow-wrap: anywhere;
      }
      .announcement {
        margin: 0;
      }
      .announcement:empty {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip-path: inset(50%);
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
        flex-wrap: wrap;
      }
      .draft {
        flex: 1;
        min-width: 0;
        padding: 0.35rem 0.5rem;
      }
      /* 574 B1 — the action buttons are jf-button atoms now; the base button{} fork is gone. */
    `,
  ];
}

customElements.define('jf-memory-surface', MemorySurface);
