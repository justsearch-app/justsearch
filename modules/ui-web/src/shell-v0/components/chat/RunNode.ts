// SPDX-License-Identifier: Apache-2.0
/**
 * RunNode — Tempdoc 565 §17: the ONE run-step node primitive. §19: density-adaptive.
 *
 * A presentational glyph node that renders a {@link StepPresentation} (the §17 run-step presentation
 * projection) — a decisive-state char (✓ done · ⊘ denied · ✕ error · ! warn) or a CSS shape (a filled
 * dot for routine `none`, a hollow ring for `pending`, a pulsing dot for `running`/`live`), coloured by
 * the §3.B status tone. Every run-step surface (the spine node, the in-body trace row, the tool-card)
 * composes THIS primitive instead of hand-authoring a status dot, so the run-step presentation is
 * unforkable by construction (§17).
 *
 * §19 — the representation is a PROJECTION of the node's measured box (the FILL half of 559's
 * Adaptivity authority, {@link DensityController}). §17 unified the run-step's SEMANTICS but drew the
 * SAME representation (the glyph char) at every scale, so at the spine-gutter scale (~5–13px) the char
 * was illegible mush. Now the node renders the legible representation for its size: below
 * {@link GLYPH_LEGIBLE_PX} the DECISIVE glyph char COLLAPSES to a tone dot (the tone already carries
 * success/danger) — a glyph too small to read is never drawn. The shape-glyphs (none/pending/running)
 * are %-sized and keep their shape at every scale. The surface DECLARES its density ceiling via the
 * `density` attribute (the spine gutter `minimal`, the trace `compact`, the card `full`); the measured
 * size CAPS it (legibility wins). The element is decorative (`aria-hidden`); the accessible name lives
 * on the operable wrapper (the spine jump-button / the trace row). It fills its parent box, so the
 * wrapper owns sizing + prominence + the active ring (the §13 grading).
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { toneAccent, glyphChar } from '../../utils/statusTone.js';
import type { StepPresentation } from '../../views/runStepPresentation.js';
import { DensityController, minDensity, type RunNodeDensity } from '../../primitives/adaptiveDensity.js';

export class RunNode extends JfElement {
  static properties = {
    presentation: { attribute: false },
    density: { type: String },
    outline: { type: Boolean, reflect: true },
  };

  declare presentation: StepPresentation | null;
  /** §19 — the surface's declared density ceiling/intent (the measured box caps it). */
  declare density: RunNodeDensity;
  /**
   * Tempdoc 814 §D4 — draw the tone SHAPES as rings instead of filled discs. The tone is unchanged
   * (statusTone stays the one colour authority); fill-vs-outline is the non-colour cue that separates a
   * spine texture marker from the two filled meanings already spoken for on this screen (the
   * grounded-status dot and a spine LANDMARK). Filled is reserved for landmarks.
   */
  declare outline: boolean;

  /** §19 — projects the legible representation for the measured host box (the FILL-half controller). */
  private readonly densityCtl = new DensityController(this, {
    declared: () => this.density,
  });

  constructor() {
    super();
    this.presentation = null;
    this.density = 'full';
    this.outline = false;
  }

  static styles = css`
    :host {
      display: inline-flex;
      width: 100%;
      height: 100%;
    }
    .g {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      font-size: var(--font-size-xs);
      font-weight: 700;
      line-height: 1;
    }
    /* Routine/info: a plain filled dot (no over-decoration — backend INFO = no glyph, 561 #5). */
    .g-none::before {
      content: '';
      width: 64%;
      height: 64%;
      border-radius: 50%;
      background: currentColor;
    }
    /* §19 — a DECISIVE glyph (done/denied/error/warn) collapsed at minimal density: a filled tone dot,
       the legible representation when the box is too small for the char. The tone carries the state. */
    .g-dot::before {
      content: '';
      width: 64%;
      height: 64%;
      border-radius: 50%;
      background: currentColor;
    }
    /* A gate awaiting decision: a hollow ring. */
    .g-pending::before {
      content: '';
      width: 64%;
      height: 64%;
      border-radius: 50%;
      border: 1.5px solid currentColor;
      box-sizing: border-box;
    }
    /* Running: an alive, pulsing filled dot. */
    .g-running::before {
      content: '';
      width: 64%;
      height: 64%;
      border-radius: 50%;
      background: currentColor;
      animation: run-node-pulse 1.1s ease-in-out infinite;
    }
    @keyframes run-node-pulse {
      0%,
      100% {
        opacity: 1;
        transform: scale(1);
      }
      50% {
        opacity: 0.45;
        transform: scale(0.7);
      }
    }
    /* Tempdoc 814 §D4 — the OUTLINE treatment: the same tone, drawn as a ring. Applied to the FILLED
       shapes only (the pending glyph is already a ring); the decisive glyph chars are unaffected. */
    :host([outline]) .g-none::before,
    :host([outline]) .g-dot::before,
    :host([outline]) .g-running::before {
      background: transparent;
      border: 1.5px solid currentColor;
      box-sizing: border-box;
    }
    /* 559 reduced-motion guard — the alive dot settles to static. */
    @media (prefers-reduced-motion: reduce) {
      .g-running::before {
        animation: none;
      }
    }
  `;

  render(): TemplateResult | typeof nothing {
    const p = this.presentation;
    if (!p) return nothing;
    // §19 — the legible representation for the measured box. Below GLYPH_LEGIBLE_PX a decisive char
    // collapses to a tone dot (.g-dot); the shape-glyphs keep their %-sized shape at any scale.
    // §19.I-R1 — cap by the declared ceiling synchronously: the controller only knows the measured
    // density after its first rAF, so a `minimal`-declared gutter node must not flash a char for one
    // frame before the recompute lands. `minDensity(declared, measured)` holds the ceiling from frame 0.
    const minimal = minDensity(this.density, this.densityCtl.density) === 'minimal';
    const char = glyphChar(p.glyph);
    const klass = minimal && char ? 'g-dot' : `g-${p.glyph}`;
    const content = minimal || !char ? nothing : char;
    return html`<span
      class="g ${klass} ${p.live ? 'live' : ''}"
      .style=${`color: ${toneAccent(p.tone)}`}
      aria-hidden="true"
      >${content}</span
    >`;
  }
}

customElements.get('jf-run-node') || customElements.define('jf-run-node', RunNode);
