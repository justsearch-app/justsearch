// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type PropertyValues, type TemplateResult } from 'lit';
import { repeat } from 'lit/directives/repeat.js';
import { z } from 'zod';
import { JfElement } from '../primitives/JfElement.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import { getOperationClient, OperationError } from '../operations/OperationClient.js';
import { requestAuthorization } from '../operations/authorizationBroker.js';
import './Button.js';

const inventorySchema = z.object({ imports: z.array(z.object({
  operationKey: z.string().min(1), interruptionRevision: z.number().int().nonnegative().safe(),
  reason: z.string(), folders: z.array(z.object({ path: z.string() })), resumeAvailable: z.boolean(),
})) });
type InterruptedImport = z.infer<typeof inventorySchema>['imports'][number];

function reasonText(reason: string): string {
  switch (reason) {
    case 'RECOVERY_AUTHORIZATION_REFUSED': return 'Approval is needed to resume this import.';
    case 'RECOVERY_SCOPE_REFUSED': return 'Access to the original folders could not be confirmed.';
    case 'RECOVERY_BINDING_INVALID': return 'The saved import details could not be verified.';
    case 'RECOVERY_GENERATION_MISMATCH': return 'The index changed before this import finished.';
    case 'RECOVERY_ORIGIN_UNAVAILABLE': return 'The app that started this import is unavailable.';
    case 'RECOVERY_WRITE_UNAVAILABLE':
    case 'RECOVERY_ORIGIN_WRITE_UNAVAILABLE': return 'Import progress could not be saved.';
    case 'INGEST_UNIT_STATE_UNAVAILABLE': return 'The saved import progress is unavailable.';
    case 'INGEST_RECOVERY_ATTEMPTS_EXHAUSTED': return 'This import stopped after repeated recovery attempts.';
    case 'ENGINE_LIMIT':
    case 'CONTEXT_LIMIT': return 'There was no room to continue this import.';
    case 'cancelled': return 'This import was cancelled.';
    default: return 'This import stopped before it finished';
  }
}

/** Retained import inventory; resuming always enters the shared operation approval flow. */
export class InterruptedImports extends JfElement {
  static properties = {
    host_: { attribute: false }, apiBase: { type: String, attribute: 'api-base' },
    imports: { state: true }, errors: { state: true },
  };
  declare host_: PluginHostApi | undefined;
  declare apiBase: string;
  private declare imports: InterruptedImport[];
  private declare errors: ReadonlyMap<string, string>;
  private readonly resuming = new Set<string>();
  private request: AbortController | null = null;
  private readSerial = 0;
  private contextSerial = 0;
  private currentHost: PluginHostApi | undefined;
  private currentBase = '';

  constructor() {
    super();
    this.apiBase = '';
    this.imports = [];
    this.errors = new Map();
  }

  static styles = css`
    :host { display: block; }
    section { margin: 1rem 0; border-top: 1px solid var(--border-subtle); padding-top: 1rem; }
    h3 { margin: 0; color: var(--text-secondary); font-size: var(--font-size-sm); font-weight: 500; }
    ul { list-style: none; padding: 0; margin: 0; }
    li { display: flex; flex-wrap: wrap; align-items: center; gap: 0.5rem 1rem;
      padding: 0.75rem 0; border-bottom: 1px solid var(--border-subtle); }
    .details { flex: 1 1 16rem; min-width: 0; }
    .path { white-space: pre-wrap; overflow-wrap: anywhere; }
    p { margin: 0.25rem 0 0; font-size: var(--font-size-sm); color: var(--text-secondary); }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    window.addEventListener('focus', this.onFocus);
    void this.refresh();
  }

  protected override updated(changed: PropertyValues): void {
    if ((changed.has('host_') || changed.has('apiBase'))
      && (this.currentHost !== this.host_ || this.currentBase !== this.apiBase)) {
      this.contextSerial++;
      this.imports = [];
      this.errors = new Map();
      this.resuming.clear();
      void this.refresh();
    }
  }

  override disconnectedCallback(): void {
    window.removeEventListener('focus', this.onFocus);
    this.readSerial++;
    this.request?.abort();
    this.request = null;
    super.disconnectedCallback();
  }

  private readonly onFocus = (): void => { void this.refresh(); };

  private async refresh(): Promise<void> {
    if (!this.isConnected) return;
    this.currentHost = this.host_;
    this.currentBase = this.apiBase;
    const serial = ++this.readSerial;
    this.request?.abort();
    const controller = new AbortController();
    this.request = controller;
    // Bound a hung inventory without creating another repeating poller.
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
      if (!this.host_) throw new Error('Import inventory unavailable');
      const read = async () => {
        const response = await this.host_!.data.fetch('/api/indexing/imports/interrupted', { signal: controller.signal });
        if (!response.ok) throw new Error('Import inventory unavailable');
        return inventorySchema.parse(await response.json());
      };
      const aborted = new Promise<never>((_resolve, reject) => {
        controller.signal.addEventListener('abort', () => reject(controller.signal.reason), { once: true });
      });
      const inventory = await Promise.race([read(), aborted]);
      if (serial !== this.readSerial || !this.isConnected || controller.signal.aborted) return;
      this.imports = inventory.imports;
      this.errors = new Map([...this.errors].filter(([key]) => inventory.imports.some(row => row.operationKey === key)));
    } catch {
      // An unavailable inventory must never claim that there are no interrupted imports.
      if (serial === this.readSerial && this.isConnected) this.imports = [];
    } finally {
      clearTimeout(timeout);
      if (serial === this.readSerial) this.request = null;
    }
  }

  private async resume(row: InterruptedImport): Promise<void> {
    const key = row.operationKey;
    if (this.resuming.has(key) || !row.resumeAvailable || !this.imports.includes(row)) return;
    const context = this.contextSerial;
    this.resuming.add(key);
    this.errors = new Map([...this.errors].filter(([other]) => other !== key));
    this.requestUpdate();
    try {
      await getOperationClient(this.apiBase).invokeWithConsent('core.resume-import', {
        args: { operationKey: key, interruptionRevision: row.interruptionRevision },
      }, { requestConsent: requestAuthorization });
    } catch (error) {
      // A response to an older revision must not attach an error to a replacement row.
      if (context === this.contextSerial && this.isConnected
        && this.imports.some(current => current.operationKey === key && current.interruptionRevision === row.interruptionRevision)) {
        const stale = error instanceof OperationError && (error.httpStatus === 409 || error.errorCode === 'IMPORT_RESUME_STALE');
        const refused = error instanceof OperationError && error.errorClass === 'CONFIRMATION_REQUIRED';
        this.errors = new Map(this.errors).set(key, stale ? 'This import changed. Review it and try again.'
          : refused ? 'Resume was not approved. You can try again.' : 'Could not resume this import. Try again.');
      }
    } finally {
      if (context === this.contextSerial) {
        await this.refresh();
        this.resuming.delete(key);
        this.requestUpdate();
      }
    }
  }

  override render(): TemplateResult | typeof nothing {
    if (!this.imports.length) return nothing;
    return html`<section aria-labelledby="interrupted-heading">
      <h3 id="interrupted-heading">Interrupted imports</h3>
      <ul>${repeat(this.imports, row => row.operationKey, row => html`<li>
        <div class="details">
          ${row.folders.map(folder => html`<div class="path">${folder.path}</div>`)}
          <p>${reasonText(row.reason)}</p>
          ${this.errors.has(row.operationKey) ? html`<p role="alert">${this.errors.get(row.operationKey)}</p>` : nothing}
        </div>
        ${row.resumeAvailable ? html`<jf-button variant="ghost" .disabled=${this.resuming.has(row.operationKey)}
          aria-busy=${this.resuming.has(row.operationKey)} .onActivate=${() => this.resume(row)}>
          ${this.resuming.has(row.operationKey) ? 'Resuming…' : 'Resume'}</jf-button>` : nothing}
      </li>`)}</ul>
    </section>`;
  }
}

if (!customElements.get('jf-interrupted-imports')) customElements.define('jf-interrupted-imports', InterruptedImports);
