// SPDX-License-Identifier: Apache-2.0
/**
 * XUiRendererControl — Tempdoc 543 §13.3.1 (Form primitive).
 *
 * The kernel-side Form primitive consumes a JSON Schema with an
 * optional `x-ui-renderer` extension keyword. When set, the named
 * hint dispatches to a registered renderer for that hint value
 * (e.g., `'corpus-picker'` → `<jf-corpus-picker>`, `'agent-id-picker'`
 * → `<jf-agent-id-picker>`).
 *
 * This file ships the kernel-controlled dispatch: a single tester
 * that wins at a rank above all specialized controls and routes
 * to a hint-keyed Renderer Registry. The registry maps
 * `hintName → custom-element-tag`; first-party + plugin renderers
 * register entries via `registerXUiRenderer()`.
 *
 * Per §13.3.1 layer placement: this is the substrate surface.
 * Individual renderers (`<jf-corpus-picker>`, etc.) are
 * feature-module / plugin code. The primitive lives in
 * shell-v0/renderers because it composes with the existing
 * JsonForms-shaped renderer registry — adding a new registry would
 * duplicate dispatch infrastructure.
 *
 * KCS bridge per §19: future `useForm()` capability module.
 */

import { html, css, type TemplateResult } from 'lit';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { JsonFormsRendererBase } from '../JsonFormsRendererBase.js';
import type { RendererTester } from '../rendererTypes.js';
import { registerRenderer } from '../dispatch.js';
import { ensureXUiRenderer, hasLazyHintLoader } from './lazyHintLoaders.js';
import { getXUiRendererTag, subscribeXUiRenderers } from './xUiRendererRegistry.js';
// 569 Phase 0 — the hint registry now lives in the dependency-LEAF `xUiRendererRegistry` (so the
// renderers can self-register without closing the control→lazyHintLoaders→renderer→control cycle).
// Re-exported here so existing `from './XUiRendererControl.js'` importers keep working unchanged.
export {
  registerXUiRenderer,
  unregisterXUiRenderer,
  getXUiRendererTag,
  listXUiRenderers,
  subscribeXUiRenderers,
  __resetXUiRendererRegistryForTest,
} from './xUiRendererRegistry.js';

/**
 * Rank for the x-ui-renderer dispatcher. Higher than every specialized
 * control (date, time, enum) so that an `x-ui-renderer` hint always
 * wins over format-based matching when both apply.
 */
const RANK_X_UI_RENDERER = 100;

/**
 * The x-ui-renderer dispatcher control. When a JSON Schema fragment
 * carries `x-ui-renderer: '<hint>'` and the hint is registered, this
 * control instantiates the registered custom element and forwards
 * the standard RendererProps.
 *
 * When the hint is unknown, the dispatcher falls back to a visible
 * warning so the failure is observable rather than silent.
 */
export class XUiRendererControl extends JsonFormsRendererBase {
  static styles = css`
    :host {
      display: block;
      margin-block-end: var(--justsearch-shell-form-control-spacing, 0.75rem);
    }
    .x-ui-renderer-missing {
      padding: 0.5rem;
      border: 1px dashed var(--justsearch-shell-form-error-color, #c00);
      border-radius: 0.25rem;
      font-size: var(--font-size-sm);
      color: var(--justsearch-shell-form-error-color, #c00);
    }
  `;

  // Keep the actual renderer mounted when only its projected data changes.
  private childRenderer: HTMLElement | null = null;
  // 569 Phase 0 — re-render when the hint registry changes (a lazy renderer just registered),
  // so a miss that triggered `ensureXUiRenderer` resolves on the next paint.
  private _registryUnsub: (() => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    this._registryUnsub = subscribeXUiRenderers(() => this.requestUpdate());
  }

  override disconnectedCallback(): void {
    this._registryUnsub?.();
    this._registryUnsub = null;
    super.disconnectedCallback();
  }

  override render(): TemplateResult {
    if (!this.visible) {
      return html``;
    }
    const hint = (this.schema as { 'x-ui-renderer'?: string })['x-ui-renderer'];
    if (typeof hint !== 'string' || hint.length === 0) {
      return html`<div class="x-ui-renderer-missing">
        x-ui-renderer dispatcher mounted without a hint
      </div>`;
    }
    const tag = getXUiRendererTag(hint);
    if (tag === undefined) {
      // 569 Phase 0 — a lazy-loadable hint hasn't been imported yet: kick off the dynamic import
      // (de-duped + idempotent) and render a quiet placeholder; the registry-change subscription
      // re-renders us once it self-registers. A genuinely unknown hint shows the loud diagnostic.
      if (hasLazyHintLoader(hint)) {
        void ensureXUiRenderer(hint);
        return html`<div class="x-ui-renderer-loading" data-testid="x-ui-renderer-loading"></div>`;
      }
      return html`<div
        class="x-ui-renderer-missing"
        data-testid="x-ui-renderer-missing"
      >
        No renderer registered for hint <code>${hint}</code>
      </div>`;
    }
    return html`
      <div data-x-ui-renderer-hint=${hint}>
        ${this.childRenderer = createElementWithProps(tag, {
          schema: this.schema,
          uischema: this.uischema,
          path: this.path,
          data: this.data,
          errors: this.errors,
          enabled: this.enabled,
          visible: this.visible,
          onChange: this.onChange,
          userConfig: this.userConfig,
        }, this.childRenderer)}
      </div>
    `;
  }
}

/**
 * Instantiate a custom element by tag and assign the standard
 * renderer props. Returns the live element for use in a Lit template.
 *
 * We do NOT use Lit's static-html `unsafeStatic` here because the
 * tag comes from a runtime registry (untrusted in principle — a
 * plugin's manifest could declare it). Direct construction + property
 * assignment is safer.
 */
function createElementWithProps(
  tag: string,
  props: Record<string, unknown>,
  previous: HTMLElement | null,
): HTMLElement {
  const el = (previous?.localName === tag ? previous : document.createElement(tag)) as HTMLElement &
    Record<string, unknown>;
  for (const [k, v] of Object.entries(props)) {
    el[k] = v;
  }
  return el;
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-x-ui-renderer-control')
) {
  customElements.define('jf-x-ui-renderer-control', XUiRendererControl);
}

/**
 * Tester — matches any Control whose schema carries an
 * `x-ui-renderer` extension keyword. Wins at high rank so it always
 * preempts type/format-based dispatch when a hint is present.
 *
 * Returns -1 (no match) when the schema lacks the keyword OR when
 * the uischema element isn't a Control. Returns RANK_X_UI_RENDERER
 * when the hint is present (regardless of whether a renderer is
 * registered — the dispatcher's `render()` shows a visible "no
 * renderer" diagnostic, which is more useful than silently falling
 * back to a text input).
 */
export const xUiRendererTester: RendererTester = (
  uischema: UISchemaElement,
  schema: JsonSchema,
): number => {
  if (uischema.type !== 'Control') return -1;
  const hint = (schema as { 'x-ui-renderer'?: unknown })['x-ui-renderer'];
  if (typeof hint !== 'string' || hint.length === 0) return -1;
  return RANK_X_UI_RENDERER;
};

// Self-register into the dispatch store (cycle-free; tempdoc 530 UI-cycle gate).
registerRenderer(xUiRendererTester, 'jf-x-ui-renderer-control');
