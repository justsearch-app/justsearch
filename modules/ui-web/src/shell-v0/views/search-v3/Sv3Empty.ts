// SPDX-License-Identifier: Apache-2.0
/**
 * jf-sv3-empty — the Search v3 window's zero state (tempdoc 822 slice 4).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * ONE element for every empty region, because the design spec has one: the sidebar with no threads and the
 * content surface with no results are the same pattern at two sizes, so they must not drift into two.
 *
 * The media is the spec's fanned three-card stack — a bordered tile with two `aria-hidden` copies
 * behind it, rotated ±10° and scaled to 84% off their bottom corners. It is memorable and costs no
 * illustration asset, which is the whole reason the spec chose it. The two copies are decoration and
 * are hidden from assistive tech; the front tile carries whatever glyph the caller slots in.
 *
 * Side-effect registers <jf-sv3-empty>.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Shared } from './sv3-shared-styles.js';

export class Sv3Empty extends JfElement {
  static styles = [
    sv3Shared,
    css`
      :host {
        display: flex;
        flex: 1 1 auto;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: var(--space-6);
        min-width: 0;
        padding: var(--space-6);
        color: var(--foreground);
        font-family: var(--font-sans);
        text-align: center;
        text-wrap: balance;
      }
      /* The spec's one breakpoint on this component: a roomier region gets a roomier state. */
      @media (min-width: 48rem) {
        :host([roomy]) {
          padding: var(--space-12);
        }
      }

      /* 24rem is a measure, not a box: the header is centred and never grows past a readable line. */
      .header {
        display: flex;
        flex-direction: column;
        align-items: center;
        max-inline-size: 24rem;
        text-align: center;
      }

      .media {
        position: relative;
        margin-bottom: var(--space-6);
      }
      .tile {
        position: relative;
        display: flex;
        flex-shrink: 0;
        align-items: center;
        justify-content: center;
        inline-size: var(--space-9);
        block-size: var(--space-9);
        border: 1px solid var(--border);
        border-radius: var(--radius-md);
        background: var(--card);
        color: var(--foreground);
        font-size: var(--font-size-sv3-sm);
        box-shadow: var(--empty-tile-shadow);
      }
      /* The 1px inset ring the spec puts on every card-like tile: a hairline BELOW the edge in light,
         ABOVE it in dark. Both live in the token sheet, so the inversion is not a component rule. */
      .tile::before {
        content: '';
        pointer-events: none;
        position: absolute;
        inset: 0;
        border-radius: var(--radius-md);
        box-shadow: var(--empty-tile-edge);
      }
      /* Individual transform properties rather than the shorthand: the spec composes translate,
         rotate and scale as three independent utilities, and a shorthand would order them by hand. */
      .tile.ghost {
        pointer-events: none;
        position: absolute;
        bottom: 1px;
        scale: 0.84;
        box-shadow: none;
      }
      .tile.ghost::before {
        box-shadow: none;
      }
      .tile.ghost-start {
        transform-origin: bottom left;
        translate: -2px 0;
        rotate: -10deg;
      }
      .tile.ghost-end {
        transform-origin: bottom right;
        translate: 2px 0;
        rotate: 10deg;
      }

      .title {
        font-size: var(--font-size-sv3-xl);
        font-variation-settings: "opsz" 32;
        font-weight: 600;
      }
      /* Only ever the gap AFTER a title — a description standing alone owns its own position. */
      .title + .description {
        margin-top: var(--space-1);
      }
      .description {
        color: var(--muted-foreground);
        font-size: var(--font-size-sv3-sm);
      }

      .content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: var(--space-4);
        width: 100%;
        min-width: 0;
        max-inline-size: 24rem;
        font-size: var(--font-size-sv3-sm);
        text-wrap: balance;
      }
      /* An empty content slot must not spend the host's 24px gap on nothing. */
      .content:not(:has(*)) {
        display: none;
      }
    `,
  ];

  static properties = {
    heading: { type: String },
    description: { type: String },
    glyph: { type: String },
    roomy: { type: Boolean, reflect: true },
  };

  declare heading: string;
  declare description: string;
  declare glyph: string;
  declare roomy: boolean;

  constructor() {
    super();
    this.heading = '';
    this.description = '';
    this.glyph = '';
    this.roomy = false;
  }

  render(): TemplateResult {
    return html`
      <div class="header" data-testid="sv3-empty-header">
        <div class="media" data-testid="sv3-empty-media">
          <div class="tile ghost ghost-start" aria-hidden="true"></div>
          <div class="tile ghost ghost-end" aria-hidden="true"></div>
          <div class="tile" data-testid="sv3-empty-tile">${this.glyph}</div>
        </div>
        ${this.heading === ''
          ? nothing
          : html`<div class="title" data-testid="sv3-empty-title">${this.heading}</div>`}
        ${this.description === ''
          ? nothing
          : html`<div class="description" data-testid="sv3-empty-description">
              ${this.description}
            </div>`}
      </div>
      <div class="content"><slot></slot></div>
    `;
  }
}

customElements.define('jf-sv3-empty', Sv3Empty);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-empty': Sv3Empty;
  }
}
