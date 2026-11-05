// SPDX-License-Identifier: Apache-2.0
/**
 * <jf-elicit-host> — Tempdoc 543 §25.β3 chrome surface.
 *
 * Renders pending elicitation requests as modal dialogs. Each request
 * embeds a <jf-form> driven by the request's JsonSchema + UISchema +
 * initialData. Submit / cancel resolves the elicit() promise via
 * resolveElicit / cancelElicit; the modal closes immediately after.
 *
 * Sits at the chrome root (mounted from Shell). At most one modal
 * shows at a time; concurrent elicit() calls queue and surface FIFO.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { keyed } from 'lit/directives/keyed.js';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';
import { ModalController } from '../primitives/modalController.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import {
  listPendingElicits,
  resolveElicit,
  cancelElicit,
  type ElicitRequest,
} from '../substrates/elicit/index.js';
import '../components/Form.js';

/**
 * 543-fwd — elicit-form fix. A schema-only elicit request (no uischema) left
 * <jf-form> with an `undefined` uischema, so `createChildRenderer` matched no
 * renderer and the form rendered ZERO inputs (every macro save / parameterize
 * prompt). The renderer registry itself is fine (TextControl matches a plain
 * string; VerticalLayout renders its elements; jf-form works in SettingsLitView)
 * — it just needs a uischema. Generate a default VerticalLayout (one Control per
 * top-level property) when the request supplies none. Cheap + flat; matches the
 * shape SettingsLitView feeds jf-form.
 */
function defaultUiSchemaFor(schema: JsonSchema | undefined): UISchemaElement {
  const properties =
    schema && typeof schema === 'object' && schema.properties
      ? (schema.properties as Record<string, { 'x-ui'?: { multi?: boolean } }>)
      : {};
  return {
    type: 'VerticalLayout',
    elements: Object.keys(properties).map((key) => {
      // 543-fwd #2 — a property may opt into a multiline control with an `x-ui`
      // hint; map it to the JSON Forms `options.multi` the renderer reads.
      const multi = Boolean(properties[key]?.['x-ui']?.multi);
      return {
        type: 'Control',
        scope: `#/properties/${key}`,
        ...(multi ? { options: { multi: true } } : {}),
      };
    }),
  } as unknown as UISchemaElement;
}

export class ElicitHost extends JfElement {
  static properties = {
    pending: { state: true },
    formData: { state: true },
  };

  declare pending: ReadonlyArray<ElicitRequest>;
  declare formData: unknown;

  private requestListener: ((e: Event) => void) | null = null;

  constructor() {
    super();
    this.pending = [];
    this.formData = undefined;
  }

  connectedCallback(): void {
    super.connectedCallback();
    if (this.requestListener) return;
    this.requestListener = () => this.refresh();
    document.addEventListener('jf-elicit-request', this.requestListener);
    this.refresh();
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    // A dialog taken out of the page leaves the top layer but stays open: end its presentation so a
    // remount (navigating back) opens the still-queued prompt modally again. Nothing is answered.
    this.modal.close();
    if (this.requestListener) {
      document.removeEventListener('jf-elicit-request', this.requestListener);
      this.requestListener = null;
    }
  }

  private refresh(): void {
    const newPending = listPendingElicits();
    const topId = this.pending[0]?.id;
    const newTopId = newPending[0]?.id;
    const wasEmpty = this.pending.length === 0;
    this.pending = newPending;
    // Capture the pre-open focus HERE (before render), not in updated(): the
    // elicit form's own field autofocuses during render, so capturing in
    // updated() would grab the input, not the invoker. residue #5.
    if (wasEmpty && newPending.length > 0) {
      // Capture focus + lock background scroll at the empty→pending transition (before the
      // form autofocuses) via the modal controller (574 Move 4 / §22.G; open() won't re-enter).
      this.modal.captureFocus();
    }
    // Reset form data when the top-of-queue request changes.
    if (newTopId !== topId) {
      this.formData = newPending[0]?.initialData;
    }
  }

  static styles = css`
    /* 543-fwd UPDATE 10 P3 — native <dialog> (showModal): focus management, Escape,
       ::backdrop, aria-modal for free; the host is a passthrough. */
    :host {
      display: contents;
    }
    dialog {
      padding: 1.25rem 1.5rem;
      min-width: 24rem;
      max-width: 36rem;
      max-height: 80vh;
      overflow: auto;
      border: 1px solid var(--border-default);
      border-radius: 0.5rem;
      background: var(--surface-1);
      color: var(--text-primary);
      box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    h2 {
      margin: 0 0 0.5rem;
      font-size: var(--font-size-md);
      font-weight: 600;
    }
    .description {
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
      margin-bottom: 1rem;
    }
    .form-host {
      margin-bottom: 1rem;
    }
    .actions {
      display: flex;
      gap: 0.5rem;
      justify-content: flex-end;
    }
    /* 574 B (remediation) — cancel = jf-button (secondary), submit = jf-button tone="success"
       (the one solid-CTA tone authority); the bespoke button/.submit fork is deleted. */
  `;

  /** The modality contract (574 Move 4) — scroll-lock + focus-restore via the one authority.
      enter() is called in refresh() at the empty→pending transition (focus captured before the
      form autofocuses); exit() restores it on close. */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  /** Drive the native <dialog> from the queue — open whenever a request is pending (full contract by
      construction; focus was captured early in refresh() via captureFocus, before the form autofocuses). */
  updated(): void {
    if (this.pending.length > 0) this.modal.open();
    else this.modal.close();
  }

  private handleFormChange(e: CustomEvent): void {
    this.formData = e.detail.data;
  }

  private handleSubmit(req: ElicitRequest): void {
    resolveElicit(req.id, this.formData ?? null);
    this.refresh();
  }

  private handleCancel(req: ElicitRequest): void {
    cancelElicit(req.id);
    this.refresh();
  }

  render(): TemplateResult {
    const req = this.pending[0];
    const showCancel = req ? req.cancelLabel !== null : false;
    return html`
      <dialog
        @cancel=${(e: Event) => {
          // A non-cancellable (required) prompt must stay modal: block the native
          // Escape-close. preventDefault on the `cancel` event is the only reliable
          // mechanism (keydown.preventDefault does NOT stop the dialog closing).
          if (!req || !showCancel) e.preventDefault();
        }}
        @keydown=${(e: KeyboardEvent) => {
          // Escape resolves a CANCELLABLE prompt — done here (not via the close event,
          // which some environments don't fire). Non-cancellable: do nothing; @cancel
          // blocks it. Backdrop click mirrors this.
          if (e.key === 'Escape' && req && showCancel) this.handleCancel(req);
        }}
        @click=${(e: MouseEvent) => {
          if (e.target === e.currentTarget && req && showCancel) this.handleCancel(req);
        }}
      >
        ${req
          // Each request gets its own controls: jf-button forwards onActivate only when it renders, so a
          // reused Submit would keep answering the previous (already answered) request.
          ? keyed(req.id, html`<div data-elicit-id=${req.id}>
              <h2>${req.title}</h2>
              ${req.description
                ? html`<div class="description">${req.description}</div>`
                : nothing}
              <div class="form-host">
                <jf-form
                  .schema=${req.schema}
                  .uischema=${(req.uischema ??
                    defaultUiSchemaFor(req.schema as JsonSchema)) as never}
                  .data=${this.formData}
                  @form-change=${this.handleFormChange.bind(this)}
                ></jf-form>
              </div>
              <div class="actions">
                ${showCancel
                  ? html`<jf-button
                      label=${req.cancelLabel ?? 'Cancel'}
                      .onActivate=${() => this.handleCancel(req)}
                    >
                      ${req.cancelLabel ?? 'Cancel'}
                    </jf-button>`
                  : nothing}
                <jf-button
                  class="submit"
                  tone="success"
                  label=${req.submitLabel ?? 'Submit'}
                  .onActivate=${() => this.handleSubmit(req)}
                >
                  ${req.submitLabel ?? 'Submit'}
                </jf-button>
              </div>
            </div>`)
          : nothing}
      </dialog>
    `;
  }
}

if (!customElements.get('jf-elicit-host')) {
  customElements.define('jf-elicit-host', ElicitHost);
}
