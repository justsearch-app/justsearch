// SPDX-License-Identifier: Apache-2.0
/**
 * OptionButtonGroupRenderer — first-party `x-ui-renderer` hint renderer (569 Fix 1).
 *
 * Renders an enum as the bespoke `.option-btn` grid (the same look SettingsSurface hand-authored),
 * so a DECLARED body matches hand-authored quality — this is what lets a Presentation Declaration
 * be the DEFAULT render of a real surface region with no visual downgrade (the real inversion).
 *
 * Schema fragment that triggers it (the hint lives on the property schema, read by the rank-100
 * x-ui-renderer dispatcher):
 *   { type:'string', enum:['simple','advanced'], 'x-ui-renderer':'option-button-group',
 *     'x-enum-labels': {simple:'Simple', advanced:'Advanced'},
 *     'x-enum-descriptions': {simple:'Standard view', advanced:'Full controls'},
 *     'x-enum-swatches': {simple:{fill:'#111'}, advanced:{fill:'#222'}} }
 *
 * Tempdoc 855 fix-round F1 — `x-enum-swatches` mirrors the `x-enum-labels` convention (a
 * value→spec record on the property schema) so the DECLARED schema path can render the same
 * swatch-tile trio the plain-props path renders (previously the schema branch hardcoded
 * `swatches = {}`, making the trio dead code on the default declared boot). A schema is DATA, not
 * code, so the spec is the serializable {@link SwatchSpec} — never a `TemplateResult` — and BOTH
 * the plain-props `option.swatch` field and this schema extension consume it through the one
 * `renderSwatchFill()` below: one swatch vocabulary, not two.
 *
 * Each option is a native <button> in a role=radiogroup → keyboard-operable (the 559 operability
 * contract; measured axe verifies it on the rendered page).
 * Tempdoc 855 §15.2/§17 R2 — the WAI-ARIA radiogroup keyboard model (ArrowLeft/Up = previous+select,
 * ArrowRight/Down = next+select, Home/End = first/last+select, wrapping, roving tabindex with focus
 * following selection) lives HERE, once — neither of this codebase's two prior radiogroup precedents
 * (this renderer, `AutonomyDial`) implemented it before (both were click-only — operable via
 * native `<button>` but not the real keyboard pattern).
 *
 * Plain-props path (855 §17 R2) — an ordinary Lit template can drive this element WITHOUT the
 * JsonForms schema/onChange plumbing: set `.options=${[{value,label,description?,icon?}, …]}` and
 * `.value=${current}`; it emits `CustomEvent('change', {detail:{value}})` instead of calling
 * `onChange`. The JsonForms (schema-driven) path is unchanged and takes priority when `schema.enum`
 * is present — a consumer uses one path or the other, never both. The optional per-option `icon`
 * preserves the hand-authored SettingsSurface pickers' leading icon (Simple/Detailed,
 * System/Dark/Light, …) — omitting it would be a visual downgrade the composing surface's
 * `option-btn` idiom never had.
 *
 * Side-effect registers `'option-button-group'` → `'jf-option-button-group'` at module load.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import { registerXUiRenderer } from './XUiRendererControl.js';
import { icon, type IconName } from '../../components/Icon.js';

interface EnumOptionSchema {
  readonly enum?: readonly unknown[];
  readonly title?: string;
  readonly 'x-enum-labels'?: Record<string, string>;
  readonly 'x-enum-descriptions'?: Record<string, string>;
  /** Tempdoc 855 fix-round F1 — the declared-path swatch vocabulary; see the module doc. */
  readonly 'x-enum-swatches'?: Record<string, SwatchSpec>;
}

/**
 * Tempdoc 855 fix-round F1 — the ONE serializable swatch vocabulary, consumed by both the
 * plain-props `OptionButtonGroupOption.swatch` field and the declared-path `x-enum-swatches`
 * schema extension. `fill` is a flat color/gradient CSS value; `split` is the two-tone diagonal
 * idiom (e.g. the Appearance "System" tile — half dark, half light). Serializable (no
 * `TemplateResult`) because a JSON-Schema declaration is DATA, not code.
 */
export type SwatchSpec = { readonly fill: string } | { readonly split: readonly [string, string] };

/** Renders a {@link SwatchSpec} into the `.option-swatch-tile` fill — the one rendering path both
 *  the plain-props and declared-schema swatch sources go through. */
function renderSwatchFill(spec: SwatchSpec): TemplateResult {
  const background =
    'split' in spec
      ? `linear-gradient(135deg, ${spec.split[0]} 50%, ${spec.split[1]} 50%)`
      : spec.fill;
  return html`<span style="position:absolute;inset:0;background:${background}"></span>`;
}

/** Plain-props option shape (855 §17 R2 — the non-JsonForms usage path). */
export interface OptionButtonGroupOption {
  readonly value: string;
  readonly label: string;
  readonly description?: string;
  readonly icon?: IconName;
  /**
   * Tempdoc 855 §15.2 — an optional custom visual (e.g. a painted swatch tile) that replaces the
   * icon slot when present, and switches the button to the compact square "swatch" layout with a
   * check badge on the selected option. Reuses the ONE radiogroup keyboard model instead of forking
   * a second component for visually-rich option grids (e.g. the Appearance System/Dark/Light trio) —
   * a consumer supplies the fill, this renderer owns the tile chrome + selection state + keyboard.
   * Fix-round F1 — serializable {@link SwatchSpec}, not a `TemplateResult` (so the same shape also
   * works as declared-schema DATA via `x-enum-swatches`).
   */
  readonly swatch?: SwatchSpec;
}

function titleCase(v: string): string {
  return v.length === 0 ? v : v.charAt(0).toUpperCase() + v.slice(1);
}

export class OptionButtonGroupRenderer extends JsonFormsRendererBase {
  static override properties = {
    ...JsonFormsRendererBase.properties,
    options: { attribute: false },
    value: { type: String },
    groupLabel: { attribute: false },
  };

  /** Plain-props option list (855 §17 R2). Non-empty ⇒ plain-props mode. */
  declare options: readonly OptionButtonGroupOption[];
  /** Plain-props current value — used only when `options` is set. */
  declare value: string;
  /**
   * Tempdoc 855 fix-round F2 (M1) — the plain-props path's accessible name for the
   * `role="radiogroup"` element (axe/WCAG: a group of radios needs a name distinguishing it from
   * every other radiogroup on the page). The JsonForms path derives the same thing from
   * `schema.title` instead (below) — a consumer authors ONE of the two, never both, matching how
   * `options`/`schema.enum` already fork the two modes.
   */
  declare groupLabel: string;

  constructor() {
    super();
    this.options = [];
    this.value = '';
    this.groupLabel = '';
  }

  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .option-group {
      display: flex;
      gap: 0.5rem;
    }
    .option-btn {
      flex: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      padding: 0.75rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.375rem;
      background: transparent;
      color: var(--text-primary);
      cursor: pointer;
      font: inherit;
    }
    .option-btn:hover:not(:disabled) {
      background: var(--surface-hover);
    }
    .option-btn.selected {
      border-color: var(--accent-tint);
      background: var(--accent-tint-08);
      color: var(--text-tint);
    }
    /* Forced colors (Windows High Contrast) erase the selected card's tint and edge, so it takes the
       selection pair ('forced-color-adjust: none' keeps the browser's Canvas backplate from landing
       behind its text). A selected swatch keeps its outline, which forced colors still paint. */
    @media (forced-colors: active) {
      .option-btn.selected:not(.option-btn-swatch) {
        forced-color-adjust: none;
        background: Highlight;
        border-color: Highlight;
        color: HighlightText;
      }
      .option-btn.selected:not(.option-btn-swatch) * {
        color: HighlightText;
      }
    }
    .option-btn:disabled {
      opacity: 0.5;
      cursor: not-allowed;
    }
    .option-label {
      font-size: var(--font-size-sm);
      font-weight: 500;
      margin-top: 0.25rem;
    }
    .option-desc {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      margin-top: 0.125rem;
    }
    /* Tempdoc 855 §15.2 — the swatch variant: compact fixed-width square tiles (Discord's
       "Default Themes" idiom), not the flex:1 label+desc card the default layout uses. */
    .option-group.swatch-group {
      gap: 0.75rem;
    }
    .option-btn.option-btn-swatch {
      flex: 0 0 auto;
      width: 4rem;
      padding: 0;
      border: none;
      gap: 0.375rem;
    }
    .option-swatch-tile {
      width: 2.75rem;
      height: 2.75rem;
      border-radius: 0.625rem;
      border: 1px solid var(--border-subtle);
      position: relative;
      overflow: hidden;
    }
    .option-btn.option-btn-swatch.selected .option-swatch-tile {
      outline: 2px solid var(--accent-tint);
      outline-offset: 2px;
    }
    .option-swatch-check {
      position: absolute;
      bottom: -0.25rem;
      right: -0.25rem;
      display: flex;
      border-radius: 50%;
      background: var(--surface-primary);
      color: var(--text-tint);
    }
    .option-btn.option-btn-swatch .option-label {
      margin-top: 0;
      font-weight: 500;
    }
    /* Tempdoc 855 fix-round F2 (M3) — a swatch tile never renders .option-desc (the description
       moves to the title attribute + composed aria-label instead, see render()); this rule is
       belt-and-suspenders so the fixed 4rem tile never reserves description space even if a future
       edit re-adds the span here. */
    .option-btn.option-btn-swatch .option-desc {
      display: none;
    }
  `;

  override render(): TemplateResult {
    if (!this.visible) return html``;
    // 855 §17 R2 — a non-empty `options` list is the plain-props path; the JsonForms `schema.enum`
    // path is used otherwise. A consumer picks one or the other, never both.
    const plainMode = this.options.length > 0;
    let values: readonly string[];
    let labels: Record<string, string>;
    let descs: Record<string, string>;
    let icons: Record<string, IconName>;
    let swatches: Record<string, SwatchSpec>;
    let current: string;
    if (plainMode) {
      values = this.options.map((o) => o.value);
      labels = Object.fromEntries(this.options.map((o) => [o.value, o.label]));
      descs = Object.fromEntries(
        this.options
          .filter((o): o is OptionButtonGroupOption & { description: string } => o.description !== undefined)
          .map((o) => [o.value, o.description]),
      );
      icons = Object.fromEntries(
        this.options
          .filter((o): o is OptionButtonGroupOption & { icon: IconName } => o.icon !== undefined)
          .map((o) => [o.value, o.icon]),
      );
      swatches = Object.fromEntries(
        this.options
          .filter((o): o is OptionButtonGroupOption & { swatch: SwatchSpec } => o.swatch !== undefined)
          .map((o) => [o.value, o.swatch]),
      );
      current = this.value;
    } else {
      const schema = this.schema as EnumOptionSchema;
      values = (schema.enum ?? []) as readonly string[];
      labels = schema['x-enum-labels'] ?? {};
      descs = schema['x-enum-descriptions'] ?? {};
      icons = {};
      // Fix-round F1 — was hardcoded `{}`, making the declared theme picker's swatch trio dead
      // code on the default declared path (production boot applies CORE_DECLARED). Threaded
      // exactly like `x-enum-labels` above.
      swatches = schema['x-enum-swatches'] ?? {};
      current = typeof this.data === 'string' ? this.data : '';
    }
    // Tempdoc 855 fix-round F2 (M1) — the radiogroup's accessible name: plain-props reads the
    // `groupLabel` prop, the JsonForms path reads the standard `schema.title` field (the same
    // field EnterActionPickerRenderer already threads for its own control's name).
    const groupAriaLabel = plainMode ? this.groupLabel || undefined : ((this.schema as EnumOptionSchema).title ?? undefined);
    const hasSwatches = Object.keys(swatches).length > 0;
    // Roving tabindex (WAI-ARIA radiogroup pattern): the selected option is the one tab stop; if
    // nothing matches `current`, the first option holds it so the group stays reachable.
    const currentIndex = values.indexOf(current);
    const rovingIndex = currentIndex >= 0 ? currentIndex : 0;

    const select = (v: string): void => {
      if (!this.enabled) return;
      if (plainMode) {
        this.value = v;
        this.dispatchEvent(
          new CustomEvent('change', { detail: { value: v }, bubbles: true, composed: true }),
        );
      } else {
        this.onChange(v, this.path);
      }
    };

    // ArrowLeft/Up = previous+select, ArrowRight/Down = next+select (wrapping), Home/End =
    // first/last+select; focus follows the newly-selected button (855 §17 R2).
    const onKeydown = (e: KeyboardEvent, index: number): void => {
      if (!this.enabled || values.length === 0) return;
      let target: number;
      switch (e.key) {
        case 'ArrowLeft':
        case 'ArrowUp':
          target = (index - 1 + values.length) % values.length;
          break;
        case 'ArrowRight':
        case 'ArrowDown':
          target = (index + 1) % values.length;
          break;
        case 'Home':
          target = 0;
          break;
        case 'End':
          target = values.length - 1;
          break;
        default:
          return;
      }
      e.preventDefault();
      const nextValue = values[target];
      if (nextValue === undefined) return;
      select(nextValue);
      const buttons = this.renderRoot.querySelectorAll<HTMLButtonElement>('.option-btn');
      buttons[target]?.focus();
    };

    return html`
      <div
        class="option-group ${hasSwatches ? 'swatch-group' : ''}"
        role="radiogroup"
        aria-label=${groupAriaLabel ?? nothing}
      >
        ${values.map((v, i) => {
          const selected = current === v;
          const swatch = swatches[v];
          const label = labels[v] ?? titleCase(v);
          const desc = descs[v];
          // Tempdoc 855 fix-round F2 (M3) — a swatch tile's description is demoted from visible
          // text (which squashed the fixed-width 64px tile) to the house `title`-attribute idiom,
          // mirroring `renderThemeTile()`'s "name: description" aria-label composition. Non-swatch
          // (grid) options keep the description visible, unchanged.
          const swatchAriaLabel = swatch && desc ? `${label}: ${desc}` : undefined;
          return html`
            <button
              type="button"
              class="option-btn ${swatch ? 'option-btn-swatch' : ''} ${selected ? 'selected' : ''}"
              role="radio"
              aria-checked=${selected ? 'true' : 'false'}
              aria-label=${swatchAriaLabel ?? nothing}
              title=${swatch && desc ? desc : nothing}
              tabindex=${i === rovingIndex ? '0' : '-1'}
              ?disabled=${!this.enabled}
              @click=${() => select(v)}
              @keydown=${(e: KeyboardEvent) => onKeydown(e, i)}
            >
              ${swatch
                ? html`
                    <span class="option-swatch-tile">
                      ${renderSwatchFill(swatch)}
                      ${selected
                        ? html`<span class="option-swatch-check" aria-hidden="true"
                            >${icon({ name: 'check-circle-2', size: 14 })}</span
                          >`
                        : nothing}
                    </span>
                  `
                : icons[v]
                  ? icon({ name: icons[v], size: 18 })
                  : nothing}
              <span class="option-label">${label}</span>
              ${!swatch && desc ? html`<span class="option-desc">${desc}</span>` : nothing}
            </button>
          `;
        })}
      </div>
    `;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-option-button-group')
) {
  customElements.define('jf-option-button-group', OptionButtonGroupRenderer);
}

registerXUiRenderer('option-button-group', 'jf-option-button-group');
