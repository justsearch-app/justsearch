// SPDX-License-Identifier: Apache-2.0
/**
 * PresentationGallerySurface — the style-variations / skins gallery (569 §19 Phase 4).
 *
 * Renders the one presentation catalog (`listPresentations()` — built-in variations ∪ user/LLM skins) as
 * cards. Apply switches the active declaration (the one writer, which persists it + appends history);
 * Revert steps back through the apply history (Seam 6); Export serialises the active declaration to an
 * inert JSON "skin"; Import certifies + saves a pasted skin. A skin is inert data over a closed
 * vocabulary — safe like a terminal theme, expressive like a full theme, gate-certified on import.
 */
import { SETTINGS_CONTROL_LABELS } from './settingsControlLabels.js';
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { surfaceScrollLayoutStyles } from '../primitives/surfaceLayout.js';
import { icon } from '../components/Icon.js';
import '../components/Control.js';
import {
  listPresentations,
  applyPresentation,
  revertPresentation,
} from '../state/presentationState.js';
import { saveCustomPresentation } from '../themes/presentationCatalog.js';
import { describeConformanceError } from '../themes/conformanceGate.js';
import {
  getActivePresentation,
  subscribePresentation,
} from '../state/presentationRuntime.js';
import { getDocument, subscribeDocument } from '../state/UserStateDocument.js';
import { isSafeTokenValue } from '../themes/designTokenTree.js';
import type { PresentationDeclaration, PresentationOrigin } from '../themes/presentationDeclaration.js';

/**
 * Swatch fill for a declaration that declares no theme tier (CORE_DECLARED, SETTINGS_DECLARED,
 * THREE_SURFACE_SPIKE): applying one changes no accent token, so its fallback preview is neutral.
 * It must not echo the document accent: that would preview the active skin on every such card.
 */
const DEFAULT_SWATCH_TINT = 'var(--text-secondary)';

export class PresentationGallerySurface extends JfElement {
  static properties = {
    activeId: { state: true },
    importText: { state: true },
    message: { state: true },
    canRevert: { state: true },
  };

  declare activeId: string | null;
  declare importText: string;
  declare message: string;
  declare canRevert: boolean;

  private _presUnsub: (() => void) | null = null;
  private _docUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.activeId = getActivePresentation().id;
    this.importText = '';
    this.message = '';
    this.canRevert = (getDocument().presentationHistory?.length ?? 0) >= 2;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this._presUnsub = subscribePresentation((p) => {
      this.activeId = p.id;
    });
    this._docUnsub = subscribeDocument((doc) => {
      this.canRevert = (doc.presentationHistory?.length ?? 0) >= 2;
    });
  }

  /** Tempdoc 609 §R (S1) — declarative transient reset: the feedback `message`. The `importText` paste
   *  draft is recoverable and KEPT. */
  static override transientState = { message: '' };

  override disconnectedCallback(): void {
    this._presUnsub?.();
    this._docUnsub?.();
    this._presUnsub = null;
    this._docUnsub = null;
    super.disconnectedCallback();
  }

  /**
   * The fill this card's own declaration would apply — NOT the document's current accent. `theme` is
   * optional, so a declaration without one falls back to {@link DEFAULT_SWATCH_TINT}. The value is
   * re-checked with the same `isSafeTokenValue` authority the declaration gate uses, since it lands
   * in a style attribute.
   */
  private swatchTint(decl: PresentationDeclaration): string {
    const tint = decl.theme?.tokens['accent-tint'];
    return tint !== undefined && isSafeTokenValue(tint) ? tint : DEFAULT_SWATCH_TINT;
  }

  private originLabel(origin: PresentationOrigin | undefined): string {
    switch (origin?.kind) {
      case 'team':
        return 'Built-in';
      case 'user':
        return 'Yours';
      case 'llm':
        return 'Assistant';
      case 'plugin':
        return 'Plugin';
      default:
        return '';
    }
  }

  private apply(decl: PresentationDeclaration): void {
    const r = applyPresentation(decl);
    this.message = r.ok
      ? `Applied "${decl.displayName}".`
      : `Applied "${decl.displayName}" with notes: ${r.errors.map(describeConformanceError).join('; ')}`;
  }

  private revert(): void {
    const r = revertPresentation();
    this.message = r ? 'Reverted to the previous skin.' : 'Nothing to revert to.';
  }

  private exportActive(): void {
    const active = listPresentations().find((p) => p.id === this.activeId);
    if (!active) {
      this.message = 'No active declaration to export.';
      return;
    }
    const json = JSON.stringify(active, null, 2);
    void navigator.clipboard?.writeText(json).then(
      () => {
        this.message = `Copied "${active.displayName}" as an inert JSON skin.`;
      },
      () => {
        this.message = 'Copy failed — clipboard unavailable.';
      },
    );
  }

  private importSkin(): void {
    let parsed: unknown;
    try {
      parsed = JSON.parse(this.importText);
    } catch {
      this.message = 'Import rejected: not valid JSON.';
      return;
    }
    const saved = saveCustomPresentation(parsed);
    if (!saved.ok) {
      this.message = `Import rejected by the gate: ${saved.errors.map(describeConformanceError).join('; ')}`;
      return;
    }
    this.importText = '';
    this.message = 'Imported + certified ✓ (now in the gallery below).';
  }

  static styles = [
    surfaceScrollLayoutStyles,
    css`
      h2 { font-size: var(--font-size-lg); font-variation-settings: "opsz" 32; }
      .grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(11rem, 1fr));
        gap: 0.7rem;
        margin-block: 0.75rem;
      }
      .card {
        border: 1px solid var(--border-subtle);
        border-radius: 0.5rem;
        padding: 0.7rem;
        background: var(--surface-2);
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
      }
      .card.active {
        border-color: var(--text-secondary);
      }
      /* Each card supplies its own --swatch-tint from the declaration via swatchTint(). */
      .swatch {
        background: var(--swatch-tint);
        block-size: 1.6rem;
        border-radius: 8px;
        border: 1px solid var(--border-subtle);
      }
      .card-name {
        font-weight: 600;
        color: var(--text-primary);
        font-size: var(--font-size-sm);
      }
      .origin-label {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
      }
      .toolbar {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
        margin-block: 0.5rem;
      }
      .import {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
        margin-block-start: 1rem;
      }
      textarea {
        inline-size: 100%;
        min-block-size: 4rem;
        font-family: var(--font-mono);
        font-size: var(--font-size-xs);
        background: var(--surface-1);
        color: var(--text-primary);
        border: 1px solid var(--border-subtle);
        border-radius: 8px;
      }
      .msg {
        font-size: var(--font-size-xs);
        color: var(--text-secondary);
        min-block-size: 1rem;
      }
    `,
  ];

  override render(): TemplateResult {
    const items = listPresentations();
    return html`
      <section class="surface-scroll" role="region" aria-label="Presentation gallery">
        <h2>${icon({ name: 'palette', size: 14 })} Skins &amp; variations</h2>
        <div class="toolbar">
          <jf-control
            data-testid="gallery-revert"
            ?disabled=${!this.canRevert}
            .onActivate=${() => this.revert()}
            >${SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].revert}</jf-control
          >
          <jf-control data-testid="gallery-export" .onActivate=${() => this.exportActive()}
            >${SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].export}</jf-control
          >
        </div>
        <div class="grid" data-testid="gallery-grid">
          ${items.map(
            (decl) => html`
              <div
                class="card ${decl.id === this.activeId ? 'active' : ''}"
                data-presentation-id=${decl.id}
              >
                <div
                  class="swatch"
                  data-testid="gallery-swatch"
                  .style=${`--swatch-tint: ${this.swatchTint(decl)}`}
                ></div>
                <div class="card-name">${decl.displayName}</div>
                <div class="origin-label">${this.originLabel(decl.origin)}</div>
                <jf-control
                  data-testid="gallery-apply"
                  .onActivate=${() => this.apply(decl)}
                  >${decl.id === this.activeId ? SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].applied : SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].apply}</jf-control
                >
              </div>
            `,
          )}
        </div>
        <div class="import">
          <label for="skin-import">${SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].paste}</label>
          <textarea
            id="skin-import"
            .value=${this.importText}
            @input=${(e: Event) => {
              this.importText = (e.target as HTMLTextAreaElement).value;
            }}
            placeholder=${'{"schemaVersion":1,"id":"user.my-skin","displayName":"My skin","theme":{"tokens":{"accent-tint":"oklch(65% 0.15 320)"}}}'}
          ></textarea>
          <jf-control
            data-testid="gallery-import"
            ?disabled=${this.importText.trim() === ''}
            .onActivate=${() => this.importSkin()}
            >${SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface'].import}</jf-control
          >
        </div>
        <div class="msg" aria-live="polite">${this.message || nothing}</div>
      </section>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-presentation-gallery-surface')
) {
  customElements.define('jf-presentation-gallery-surface', PresentationGallerySurface);
}
