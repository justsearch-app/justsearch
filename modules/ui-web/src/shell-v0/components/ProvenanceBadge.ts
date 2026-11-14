// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 471 — ProvenanceBadge.
 *
 * Persistent UI element shown whenever any non-core override is
 * active in the runtime userConfig (surfaceOverride for V1.5; the
 * activeLayoutId / activeThemeId fields surface here too once
 * siblings 472 / 474 ship).
 *
 * Tempdoc 559 Authority I: a plain element docked in the OverlayHost
 * top-right slot (the slot owns placement). The earlier HTML Popover
 * top-layer escape + the CH-1 anchor hack are retired.
 *
 * V1.5 shape: presence-only chip showing the override count + a
 * brief summary, with one-click "revert all" action. Per-override
 * revert UI lands when the Settings → Appearance picker UX ships
 * (out of slice 471 scope).
 */

import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Control.js';
import {
  subscribeUserConfig,
  clearAllSurfaceOverrides,
  clearAllLayoutOverrides,
} from '../state/userConfigState.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';

export class ProvenanceBadge extends JfElement {
  static properties = {
    config: { state: true },
  };

  declare config: RendererUserConfig;

  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.config = { version: 1 };
  }

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeUserConfig((cfg) => {
      this.config = cfg;
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  static styles = css`
    /* 559 Authority V: the revert is a real keyboard-operable control (jf-control);
       this styles its button part as the warning chip. Placement (559 Authority I)
       is owned by the OverlayHost top-right slot. */
    jf-control::part(control) {
      padding: 0.25rem 0.6rem;
      border-radius: 9999px;
      background: var(--accent-warning-16);
      color: var(--text-warning);
      border: 1px solid var(--accent-warning-45);
      font-size: var(--font-size-xs);
      font-family: var(--font-native);
      user-select: none;
      pointer-events: auto;
      /* Tempdoc 567 §9.4 — respect solid surface mode (--glass-blur-scale:0 zeroes the blur). */
      backdrop-filter: blur(calc(8px * var(--glass-blur-scale)));
      gap: 0.4rem;
    }
    jf-control::part(control):hover {
      background: var(--accent-warning-30);
    }
    .marker {
      display: inline-block;
      width: 0.4rem;
      height: 0.4rem;
      border-radius: 50%;
      background: currentColor;
    }
  `;

  private surfaceOverrideCount(): number {
    return Object.keys(this.config.surfaceOverride ?? {}).length;
  }

  private layoutOverrideCount(): number {
    let n = 0;
    if (this.config.surfaceVisibility) {
      // Each explicit visibility entry counts; both true and false
      // are user-authored decisions that diverge from "default
      // visible per catalog."
      n += Object.keys(this.config.surfaceVisibility).length;
    }
    if (this.config.surfaceOrder && this.config.surfaceOrder.length > 0) {
      n += 1;
    }
    if (this.config.activeLayoutId) {
      n += 1;
    }
    return n;
  }

  private overrideCount(): number {
    return this.surfaceOverrideCount() + this.layoutOverrideCount();
  }

  private summary(): string {
    const surfaceOverrides = this.config.surfaceOverride ?? {};
    const surfaceIds = Object.keys(surfaceOverrides);
    const layoutCount = this.layoutOverrideCount();

    if (surfaceIds.length === 1 && layoutCount === 0) {
      const coreId = surfaceIds[0]!;
      return `${coreId} → ${surfaceOverrides[coreId]}`;
    }
    const parts: string[] = [];
    if (surfaceIds.length > 0) {
      parts.push(
        `${surfaceIds.length} surface override${surfaceIds.length === 1 ? '' : 's'}`,
      );
    }
    if (layoutCount > 0) {
      parts.push(
        `${layoutCount} layout override${layoutCount === 1 ? '' : 's'}`,
      );
    }
    return parts.join(' + ') || '';
  }

  private handleClick(): void {
    // V1.5 minimum: click reverts all overrides (surface + layout) at
    // once. Per-tier revert UI lands with the Settings → Appearance
    // picker (out of 471/472 substrate scope).
    clearAllSurfaceOverrides();
    clearAllLayoutOverrides();
  }

  override render(): TemplateResult | typeof nothing {
    if (this.overrideCount() === 0) return nothing;
    const summary = this.summary();
    const tooltip = `non-core: ${summary} (activate to revert)`;
    // 559 Authority V: the revert is a real <jf-control> (focusable, Enter/Space-
    // operable, accessibly named "Revert non-core overrides") — not a mouse-only
    // <div @click>. Authority I: placement owned by the OverlayHost top-right slot.
    return html`<jf-control
      label="Revert non-core overrides"
      title=${tooltip}
      .onActivate=${() => this.handleClick()}
    >
      <span class="marker"></span>
      <span>non-core: ${summary}</span>
    </jf-control>`;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-provenance-badge')
) {
  customElements.define('jf-provenance-badge', ProvenanceBadge);
}
