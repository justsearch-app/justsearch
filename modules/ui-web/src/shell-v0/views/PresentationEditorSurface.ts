// SPDX-License-Identifier: Apache-2.0
/**
 * PresentationEditorSurface — the visual presentation authoring editor (569 §19 Phase 6).
 *
 * The meta-surface: a surface for AUTHORING surfaces. It edits a full {@link PresentationDeclaration}
 * and projects it through the SAME pipeline every other origin uses (certify → save → apply). Four
 * affordances, each grounded in earlier phases:
 *
 *   - a PALETTE derived from the renderer registry ∩ the authorable-hint catalog, so it can only
 *     offer LEGAL, registered elements (the closed-vocabulary guarantee, surfaced at compose time);
 *   - a live SPLIT-PANE PREVIEW = a real `<jf-declared-surface>` rendering the in-progress body region
 *     (degrade-never-fail: an invalid working draft shows the linter, never a crash);
 *   - the STRUCTURED VERDICT as an inline linter (Phase 1 `ConformanceError[]` via
 *     `describeConformanceError`) — exact, rule-anchored, not a generic "invalid";
 *   - FORK THIS SURFACE — copy the active declaration into the editor — and a GENERATE-from-prompt
 *     control (Phase 5, the on-device model through the host AI), plus the raw-JSON escape hatch.
 *
 * This is the move that was previously buried in Settings; Settings now stays focused on settings.
 */
import { SETTINGS_CONTROL_LABELS } from './settingsControlLabels.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { DraftPersistence } from '../controllers/draftPersistence.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { icon } from '../components/Icon.js';
import '../components/Control.js';
import '../components/DeclaredSurface.js';
import type { SurfaceBodyDeclaration } from '../components/DeclaredSurface.js';
import { applyPresentation, listPresentations } from '../state/presentationState.js';
import { saveCustomPresentation } from '../themes/presentationCatalog.js';
import { certifyPresentation, describeConformanceError } from '../themes/conformanceGate.js';
import { getActivePresentation, subscribePresentation } from '../state/presentationRuntime.js';
import { authorPresentationFromPrompt } from '../themes/authorPresentation.js';
import { createLocalConstrainedCompletion } from '../themes/localCompletion.js';
import type { PresentationDeclaration } from '../themes/presentationDeclaration.js';
import {
  listAuthorableHints,
  starterNodeForHint,
  freshKeyForHint,
} from '../renderers/hintSchemaCatalog.js';
import {
  listXUiRenderers,
  subscribeXUiRenderers,
} from '../renderers/controls/xUiRendererRegistry.js';
import { ensureXUiRenderer, listLazyHints } from '../renderers/controls/lazyHintLoaders.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

const STARTER_DECLARATION = `{
  "schemaVersion": 1,
  "id": "user.my-surface",
  "displayName": "My surface",
  "theme": { "tokens": { "accent-tint": "oklch(65% 0.15 280)" } },
  "body": {
    "core.settings.interface": {
      "heading": "My region",
      "schema": { "type": "object", "properties": {} },
      "uischema": { "type": "VerticalLayout", "elements": [] }
    }
  }
}`;

export class PresentationEditorSurface extends JfElement {
  static properties = {
    host_: { attribute: false },
    declText: { state: true },
    promptText: { state: true },
    busy: { state: true },
    message: { state: true },
    paletteHints: { state: true },
  };

  declare host_: PluginHostApi;
  declare declText: string;
  declare promptText: string;
  declare busy: boolean;
  // Tempdoc 609 §R (T2.1) — reload-durable editor draft (flush on hide, rehydrate on a fresh mount).
  readonly draftPersist = new DraftPersistence(
    this,
    'presentation-editor.declText',
    () => this.declText,
    (v) => {
      this.declText = v;
    },
  );
  declare message: string | null;
  declare paletteHints: readonly string[];

  private _presUnsub: (() => void) | null = null;
  private _regUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.host_ = undefined as unknown as PluginHostApi;
    this.declText = STARTER_DECLARATION;
    this.promptText = '';
    this.busy = false;
    this.message = null;
    this.paletteHints = this.computePalette();
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // Re-render the live preview when the active presentation changes (e.g. after Apply).
    this._presUnsub = subscribePresentation(() => this.requestUpdate());
    // The palette intersects the LIVE registry; a lazily-registered renderer expands it.
    this._regUnsub = subscribeXUiRenderers(() => {
      this.paletteHints = this.computePalette();
    });
  }

  /** Tempdoc 609 — settle transient state on hide: the in-flight `busy` flag (a stale "Asking the
   *  on-device model…" spinner would otherwise survive navigation) + the feedback `message`. The author
   *  drafts (`declText`, `promptText`) are recoverable and deliberately KEPT. */
  static override transientState = { busy: false, message: null };

  override disconnectedCallback(): void {
    this._presUnsub?.();
    this._regUnsub?.();
    this._presUnsub = null;
    this._regUnsub = null;
    super.disconnectedCallback();
  }

  /** Authorable hints ∩ (registered ∪ lazily-loadable) — only legal, offerable elements. */
  private computePalette(): readonly string[] {
    const available = new Set<string>([...listXUiRenderers(), ...listLazyHints()]);
    return listAuthorableHints().filter((h) => available.has(h));
  }

  /** Parse the working text; null on invalid JSON (the linter reports it). */
  private parseDraft(): Record<string, unknown> | null {
    try {
      const parsed: unknown = JSON.parse(this.declText);
      return parsed !== null && typeof parsed === 'object'
        ? (parsed as Record<string, unknown>)
        : null;
    } catch {
      return null;
    }
  }

  /** The first authored body region of the draft (preview + palette-insert target), or null. */
  private firstBody(
    draft: Record<string, unknown> | null,
  ): { readonly key: string; readonly body: SurfaceBodyDeclaration } | null {
    const body = draft?.['body'];
    if (body === null || typeof body !== 'object') return null;
    const keys = Object.keys(body as Record<string, unknown>);
    if (keys.length === 0) return null;
    const key = keys[0]!;
    return { key, body: (body as Record<string, SurfaceBodyDeclaration>)[key]! };
  }

  /** Insert a starter node for `hint` into the draft's first body region (or a fresh region). */
  private async insertHint(hint: string): Promise<void> {
    // Make sure the renderer is registered so the preview can render the inserted node.
    await ensureXUiRenderer(hint);
    const starter = starterNodeForHint(hint);
    if (!starter) return;
    const draft = this.parseDraft();
    if (!draft) {
      this.message = 'Fix the JSON before inserting a component.';
      return;
    }
    // Ensure a body map + a target region.
    const bodyMap = (draft['body'] ??= {}) as Record<string, SurfaceBodyDeclaration>;
    let target = this.firstBody(draft);
    if (!target) {
      bodyMap['core.settings.interface'] = {
        heading: 'My region',
        schema: { type: 'object', properties: {} },
        uischema: { type: 'VerticalLayout', elements: [] },
      } as SurfaceBodyDeclaration;
      target = this.firstBody(draft)!;
    }
    // Mutate a structural COPY (the body fields are readonly types; we author plain JSON).
    const region = bodyMap[target.key] as unknown as {
      schema: { properties?: Record<string, unknown> };
      uischema: { elements?: unknown[] };
    };
    region.schema ??= { properties: {} } as { properties?: Record<string, unknown> };
    region.schema.properties ??= {};
    region.uischema ??= { type: 'VerticalLayout', elements: [] } as { elements?: unknown[] };
    region.uischema.elements ??= [];
    const used = new Set(Object.keys(region.schema.properties));
    const key = freshKeyForHint(hint, used);
    region.schema.properties[key] = starter.schemaProperty;
    region.uischema.elements.push(starter.uischemaControl(key));
    this.declText = JSON.stringify(draft, null, 2);
    this.message = `Inserted a "${starter.paletteLabel}" element.`;
  }

  /** Copy the active declaration into the editor (fork-this-surface). */
  private forkActive(): void {
    const activeId = getActivePresentation().id;
    const decl = activeId ? listPresentations().find((p) => p.id === activeId) : undefined;
    if (!decl) {
      this.message = 'No custom declaration is active to fork (the built-in render is current).';
      return;
    }
    this.declText = JSON.stringify(decl, null, 2);
    this.message = `Forked "${decl.displayName}" into the editor.`;
  }

  /** Certify + persist + apply the working declaration through the one pipe. */
  private applyDraft(): void {
    const draft = this.parseDraft();
    if (!draft) {
      this.message = 'Not valid JSON — see the linter below.';
      return;
    }
    const saved = saveCustomPresentation(draft);
    if (!saved.ok) {
      this.message = `Rejected by the gate: ${saved.errors.map(describeConformanceError).join('; ')}`;
      return;
    }
    const r = applyPresentation(draft);
    this.message = r.ok
      ? 'Applied ✓ (certified + saved; live preview now reflects the active render).'
      : `Applied with notes: ${r.errors.map(describeConformanceError).join('; ')}`;
  }

  /** Generate a declaration from a natural-language prompt via the on-device model (Phase 5). */
  private async generate(): Promise<void> {
    if (!this.host_?.ai) {
      this.message = 'Generation needs the host AI capability (none wired).';
      return;
    }
    this.busy = true;
    this.message = 'Asking the on-device model…';
    try {
      const result = await authorPresentationFromPrompt(
        this.promptText,
        createLocalConstrainedCompletion(this.host_),
      );
      if (!result.declaration) {
        this.message = `Model output rejected by the gate: ${result.verdict.errors
          .map(describeConformanceError)
          .join('; ')}`;
        return;
      }
      // Land it in the editor so the author can inspect + edit before applying.
      this.declText = JSON.stringify(result.declaration, null, 2);
      this.message = 'Generated ✓ (review below, then Apply).';
    } catch (e) {
      this.message = `Generation failed (is a chat model active?): ${(e as Error)?.message}`;
    } finally {
      this.busy = false;
    }
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
      .split {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0.75rem;
        align-items: start;
      }
      @media (max-width: 48rem) {
        .split {
          grid-template-columns: 1fr;
        }
      }
      .pane {
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        padding: 0.7rem;
        background: var(--surface-2);
        min-inline-size: 0;
      }
      .pane h3 {
        margin: 0 0 0.5rem;
        font-size: var(--font-size-xs);
        text-transform: uppercase;
        letter-spacing: 0.06em;
        color: var(--text-secondary);
      }
      .palette {
        display: flex;
        flex-wrap: wrap;
        gap: 0.4rem;
        margin-block-end: 0.6rem;
      }
      textarea {
        inline-size: 100%;
        min-block-size: 16rem;
        font-family: var(--font-mono);
        font-size: var(--font-size-xs);
        background: var(--surface-1);
        color: var(--text-primary);
        border: 1px solid var(--border-subtle);
        border-radius: 0.3rem;
        box-sizing: border-box;
      }
      .toolbar {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
        margin-block: 0.5rem;
      }
      .prompt-row {
        display: flex;
        gap: 0.5rem;
        margin-block-start: 0.5rem;
      }
      .prompt-row input {
        flex: 1 1 auto;
        min-inline-size: 0;
        background: var(--surface-1);
        color: var(--text-primary);
        border: 1px solid var(--border-subtle);
        border-radius: 0.3rem;
        padding: 0.3rem 0.5rem;
      }
      .linter {
        margin-block-start: 0.6rem;
        font-size: var(--font-size-xs);
      }
      .linter.ok {
        color: var(--text-success);
      }
      .linter ul {
        margin: 0.3rem 0 0;
        padding-inline-start: 1.1rem;
        color: var(--text-danger);
      }
      .msg {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        min-block-size: 1rem;
        margin-block-start: 0.5rem;
      }
      .preview-empty {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
    `,
  ];

  private renderLinter(): TemplateResult {
    const draft = this.parseDraft();
    if (this.declText.trim() === '') return html`<div class="linter">Empty draft.</div>`;
    if (!draft) {
      return html`<div class="linter">
        <strong>Invalid JSON</strong> — the draft is not parseable.
      </div>`;
    }
    const { verdict } = certifyPresentation(draft);
    if (verdict.ok) {
      return html`<div class="linter ok" role="status">✓ Certified — ready to apply.</div>`;
    }
    return html`<div class="linter" role="status">
      <strong>${verdict.errors.length} issue(s):</strong>
      <ul>
        ${verdict.errors.map((e) => html`<li>${describeConformanceError(e)}</li>`)}
      </ul>
    </div>`;
  }

  private renderPreview(): TemplateResult {
    const draft = this.parseDraft();
    const target = this.firstBody(draft);
    if (!target) {
      return html`<div class="preview-empty">
        No body region to preview yet — insert a component from the palette or fork a surface.
      </div>`;
    }
    // Degrade-never-fail: a malformed region renders DeclaredSurface's own diagnostic, never a crash.
    return html`
      <jf-declared-surface
        .declaration=${target.body}
        .data=${{} as Record<string, unknown>}
        .enabled=${true}
      ></jf-declared-surface>
    `;
  }

  override render(): TemplateResult {
    return html`
      <section class="surface-scroll" role="region" aria-label="Presentation editor">
        <h2>${icon({ name: 'palette', size: 14 })} Presentation editor</h2>
        <div class="toolbar">
          <jf-control data-testid="editor-fork" .onActivate=${() => this.forkActive()}
            >${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].fork}</jf-control
          >
          <jf-control data-testid="editor-apply" .onActivate=${() => this.applyDraft()}
            >${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].apply}</jf-control
          >
        </div>
        <div class="split">
          <div class="pane">
            <h3>Compose</h3>
            <div class="palette" data-testid="editor-palette">
              ${this.paletteHints.map(
                (hint) => html`
                  <jf-control
                    data-testid="palette-${hint}"
                    label=${starterNodeForHint(hint)?.paletteLabel ?? hint}
                    .onActivate=${() => void this.insertHint(hint)}
                    >+ ${starterNodeForHint(hint)?.paletteLabel ?? hint}</jf-control
                  >
                `,
              )}
            </div>
            <textarea
              aria-label=${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].json}
              .value=${this.declText}
              @input=${(e: Event) => {
                this.declText = (e.target as HTMLTextAreaElement).value;
              }}
            ></textarea>
            <div class="prompt-row">
              <input
                type="text"
                aria-label=${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].describe}
                placeholder=${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].prompt}
                .value=${this.promptText}
                ?disabled=${this.busy}
                @input=${(e: Event) => {
                  this.promptText = (e.target as HTMLInputElement).value;
                }}
              />
              <jf-control
                data-testid="editor-generate"
                label=${SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].generate}
                ?disabled=${this.busy || this.promptText.trim() === ''}
                .onActivate=${() => void this.generate()}
                >${this.busy ? 'Generating…' : SETTINGS_CONTROL_LABELS['core.presentation-editor-surface'].generateLocal}</jf-control
              >
            </div>
            ${this.renderLinter()}
          </div>
          <div class="pane">
            <h3>Live preview</h3>
            ${this.renderPreview()}
          </div>
        </div>
        <div class="msg" aria-live="polite">${this.message || nothing}</div>
      </section>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-presentation-editor-surface')
) {
  customElements.define('jf-presentation-editor-surface', PresentationEditorSurface);
}
