// SPDX-License-Identifier: Apache-2.0
/**
 * DeclaredSurface — tempdoc 569 Move 3 (the keystone): render a surface region's
 * BODY from an externally-authored typed declaration, instead of hand-authored Lit.
 *
 * v0 (Phase 1 — the engine seam). Takes a `SurfaceBodyDeclaration`
 * ({schema, uischema, heading, placement}) and renders it through the EXISTING
 * renderer pipeline (`createChildRenderer` — the same engine `<jf-form>` uses),
 * CO-PROJECTING the invariant facets the author does not touch:
 *   - the ARIA landmark role, derived from the declared `Placement` via the NESTED mapping
 *     (`nestedLandmarkRole` — 559 Authority II / 578: never a second `main` inside the stage), and
 *   - the region heading, from the declaration.
 * The body author supplies the data shape + composition (schema + uischema over the
 * renderer vocabulary); the engine derives the accessible, operable region. Zone
 * composition and bindings are later tiers (569 Move 2 / the surface-composition DSL).
 *
 * This is the proof that a real surface region is a projection of a user-authorable
 * declaration — the §9 de-risk spike made concrete.
 */
import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import { createChildRenderer } from '../renderers/layouts/layoutDispatch.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
import { nestedLandmarkRole } from '../display/landmarks.js';
import type { Placement, Altitude } from '../../api/types/surface.js';
import { setAtPath } from './Form.js';
import { OverflowController } from '../primitives/adaptiveBar.js';
// 569 §14 — register <jf-liveness-readout> (the co-projected liveness facet).
import './LivenessReadout.js';
// 594 Move 1b — the Display-value authority: a factual chip derives its value from here.
import { projectFact, isFact, type FactPresence, type FactConfidence } from '../display/facts.js';
import { subscribeAiState, type AiState } from '../state/aiStateStore.js';

/**
 * 569 §14 — a declared adaptive item: the engine clips the trailing tail of these by
 * (space × priority × pinned) via {@link OverflowController}, so the author declares the items +
 * their priority but NEVER the clip — a naked-clipped bar is unrepresentable (rung-2, 565 Auth VI).
 */
export interface DeclaredAdaptiveItem {
  readonly id: string;
  /**
   * Decorative free text (a fact-free label). Mutually exclusive with {@link fact}: a chip that
   * asserts a runtime/build fact must use `fact`, not a literal here (594 §9.3 — a wrong literal is
   * unrepresentable because the factual form carries a fact-ref, not a string).
   */
  readonly label?: string;
  /**
   * 594 Move 1b — a FACT-ref. The engine projects its value+presence via `projectFact` from the ONE
   * Display-value authority (facts.ts): the chip DERIVES its fact (dimension/accelerator/precision/
   * capability) instead of baking it. An absent capability omits the chip; an unknown one renders
   * muted; a present one renders name+value with an optional provenance `title`.
   */
  readonly fact?: string;
  readonly priority: number;
  /** `true` = never hidden when space runs out (the always-on signals). Default `false`. */
  readonly pinned?: boolean;
}

/** The authored body of a surface region — the unit a user/LLM may author. */
export interface SurfaceBodyDeclaration {
  /** JSON Schema describing the body's data shape. */
  readonly schema: JsonSchema;
  /** UI Schema (layout + controls) — the authored composition over the renderer vocabulary. */
  readonly uischema: UISchemaElement;
  /** Optional declared heading for the region. */
  readonly heading?: string;
  /** Optional catalog Placement — projects the ARIA landmark role (559 Authority II). */
  readonly placement?: Placement;
  /**
   * 569 §14 — opt into a co-projected LIVENESS readout by naming the signal. The engine derives
   * the live tri-state from the one observed-state authority; the author has no field for the
   * state, so a faked "healthy" indicator is unrepresentable (rung-2, the liveness facet).
   */
  readonly liveness?: string;
  /**
   * 569 §14 — declared adaptive items; the engine OWNS the overflow clip (OverflowController),
   * so the author declares priority but cannot naked-clip the bar (rung-2, the overflow facet).
   */
  readonly overflow?: readonly DeclaredAdaptiveItem[];
  /**
   * 594 §11.3 #4 — the host surface's 571 altitude, threaded by the mounting surface (NOT a second
   * authority — it is read from the surface catalog). On a `DIAGNOSTIC` surface an absent capability
   * renders "<name> off" (the off-state is diagnostic information); elsewhere it is omitted.
   */
  readonly altitude?: Altitude;
}

/** Emitted after every child onChange; mirrors `<jf-form>`'s `form-change`. */
export interface SurfaceChangeEventDetail {
  data: unknown;
  path: string;
  value: unknown;
}

export class DeclaredSurface extends JfElement {
  static override properties = {
    declaration: { attribute: false },
    data: { attribute: false },
    enabled: { type: Boolean },
    userConfig: { attribute: false },
    aiState: { state: true },
  } as const;

  declare declaration: SurfaceBodyDeclaration;
  declare data: Record<string, unknown>;
  declare enabled: boolean;
  /** Slice 3a.1.7 — threaded through to child dispatch + renderers. */
  declare userConfig: RendererUserConfig | undefined;
  /** 594 Move 1b — the observed-state snapshot a factual chip projects from (one authority). */
  declare aiState: AiState | null;
  private unsubAiState: (() => void) | null = null;

  // 569 §14 — the engine OWNS the overflow clip of a declared adaptive strip (no author CSS clip).
  // Measures the rendered items + tells render how many fit; the trailing tail collapses to "+N".
  private readonly overflowCtl = new OverflowController(this, {
    items: () =>
      Array.from(this.renderRoot.querySelectorAll('.adaptive-strip .item')) as HTMLElement[],
    container: () => this.renderRoot.querySelector('.adaptive-strip'),
    // 594 §10.2/§14 G3 — the signature includes the live RENDERED set (absent factual chips are
    // omitted), so a capability flip changes it and the strip re-measures immediately rather than
    // only on a resize. (Declaration-only ids would not change when a live capability toggles.)
    signature: () => this.projectedStrip().map((i) => i.id).join('|'),
    reserve: 32,
    pinned: () =>
      Array.from(this.renderRoot.querySelectorAll('.adaptive-strip .item')).map(
        (el) => el.getAttribute('data-pinned') === 'true',
      ),
  });

  static override styles = css`
    /* A body-region projector, not a layout surface — pass-through per 559 Authority I
       (it renders a <section>; the host generates no box). */
    :host {
      display: contents;
    }
    h3 {
      margin: 0 0 0.75rem;
      font-size: var(--font-size-xs);
      text-transform: uppercase;
      letter-spacing: 0.06em;
      color: var(--text-secondary);
    }
    /* 569 §14 — the co-projected facets render above the body. */
    jf-liveness-readout {
      margin: 0 0 0.6rem;
    }
    .adaptive-strip {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      margin: 0 0 0.6rem;
      min-inline-size: 0;
    }
    .adaptive-strip .item {
      flex: none;
      padding: 0.1rem 0.5rem;
      border-radius: 0.25rem;
      background: var(--surface-2);
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      white-space: nowrap;
    }
    /* The ENGINE hides the overflowing tail — there is no author field for this clip. */
    .adaptive-strip .item[hidden] {
      display: none;
    }
    .adaptive-strip .more {
      flex: none;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      opacity: 0.8;
    }
    /* 594 §11.3 #3 — an UNKNOWN fact (not polled / reconnecting) renders muted, never a fabricated
       or stale-confident value; distinct from ABSENT (the chip is omitted entirely). */
    .adaptive-strip .item[data-presence='unknown'] {
      opacity: 0.65;
    }
    /* 594 §11.3 #4 — a diagnostic "<name> off" chip reads as a deliberately-disabled capability:
       muted, not asserting. (Only rendered on a DIAGNOSTIC-altitude surface; ambient surfaces omit.) */
    .adaptive-strip .item[data-presence='absent'] {
      opacity: 0.55;
      font-style: italic;
    }
    /* 594 §11.3 #2 — a low/unknown-CONFIDENCE present fact (e.g. a GPU detected only via nvidia-smi)
       carries a dotted-underline uncertainty cue (paired with the trailing "?"), so it does not read
       as flatly asserted as a high-confidence one. The value is known; only the SUREness is low. */
    .adaptive-strip .item[data-confidence='low'],
    .adaptive-strip .item[data-confidence='unknown'] {
      text-decoration: underline dotted;
      text-underline-offset: 0.2em;
    }
  `;

  constructor() {
    super();
    this.declaration = {
      schema: {},
      uischema: { type: 'VerticalLayout', elements: [] } as UISchemaElement,
    };
    this.data = {};
    this.enabled = true;
    this.userConfig = undefined;
    this.aiState = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    // 594 Move 1b — subscribe to the ONE observed-state authority only if this declaration has a
    // factual chip (most declarations don't). Fires immediately with the current snapshot.
    if ((this.declaration.overflow ?? []).some((i) => i.fact)) {
      this.unsubAiState = subscribeAiState((s) => {
        this.aiState = s;
      });
    }
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubAiState?.();
    this.unsubAiState = null;
  }

  override render(): TemplateResult {
    const decl = this.declaration;
    const child = createChildRenderer(
      decl.uischema,
      decl.schema,
      '',
      this.data,
      this.enabled,
      this.handleChildChange,
      this.userConfig,
    );
    // Co-projected facet (569 Move 3): the landmark role is DERIVED, never authored. This engine
    // always renders NESTED inside the shell STAGE (already role="main"), so it uses the nested
    // mapping — STAGE→"region", never a second "main" (axe landmark-no-duplicate-main, tempdoc 578).
    const derivedRole = decl.placement ? nestedLandmarkRole(decl.placement) : null;
    // A "region" is only a landmark when named; without a heading it carries no accessible name, so
    // drop the role entirely rather than emit a nameless region.
    const role = derivedRole === 'region' && !decl.heading ? null : derivedRole;
    const body = child
      ? html`${child}`
      : html`<div>
          DeclaredSurface: no renderer for the root uischema element (type:
          ${(decl.uischema as { type?: string }).type ?? 'undefined'}).
        </div>`;
    return html`
      <section
        role=${role ?? nothing}
        aria-label=${role && decl.heading ? decl.heading : nothing}
      >
        ${decl.heading ? html`<h3>${decl.heading}</h3>` : nothing}
        ${decl.liveness
          ? html`<jf-liveness-readout metric-id=${decl.liveness}></jf-liveness-readout>`
          : nothing}
        ${decl.overflow && decl.overflow.length > 0
          ? this.renderAdaptiveStrip()
          : nothing}
        ${body}
      </section>
    `;
  }

  /**
   * 594 Move 1b — resolve the declared overflow items into the RENDERED chip set, in priority order
   * (highest first). A decorative item passes through its `label`; a factual item (`fact`) is
   * projected via the ONE Display-value authority (`projectFact`): an ABSENT capability is omitted
   * entirely (so e.g. "GPU" never shows on a CPU host), an UNKNOWN one renders muted, a PRESENT one
   * renders name+value with an optional provenance `title`. This is the single source the render AND
   * the overflow `signature()` read, so a capability flip changes the set and re-measures.
   */
  private projectedStrip(): ReadonlyArray<{
    id: string;
    text: string;
    presence: FactPresence | 'decorative';
    provenance?: string;
    confidence?: FactConfidence;
    pinned: boolean;
    priority: number;
  }> {
    const out: Array<{
      id: string;
      text: string;
      presence: FactPresence | 'decorative';
      provenance?: string;
      pinned: boolean;
      priority: number;
    }> = [];
    const diagnostic = this.declaration.altitude === 'DIAGNOSTIC';
    for (const it of this.declaration.overflow ?? []) {
      const pinned = it.pinned === true;
      if (it.fact && isFact(it.fact)) {
        const f = projectFact(it.fact, this.aiState);
        if (f.presence === 'absent') {
          // 594 §11.3 #4 — on a DIAGNOSTIC surface the off-state is information ("<name> off");
          // on an ambient surface the chip is omitted (no clutter, no lie).
          if (!diagnostic) continue;
          out.push({ id: it.id, text: `${f.name} off`, presence: 'absent', pinned, priority: it.priority });
          continue;
        }
        // 594 §11.3 #2 — a low/unknown-confidence reading renders with a trailing "?" + a muted
        // cue (below), so it does not assert as flatly as a high-confidence one.
        const uncertain = f.confidence === 'low' || f.confidence === 'unknown';
        const base = f.value ? `${f.name} ${f.value}` : f.name;
        out.push({
          id: it.id,
          text: uncertain ? `${base}?` : base,
          presence: f.presence,
          ...(f.provenance ? { provenance: f.provenance } : {}),
          ...(f.confidence ? { confidence: f.confidence } : {}),
          pinned,
          priority: it.priority,
        });
      } else {
        out.push({ id: it.id, text: it.label ?? '', presence: 'decorative', pinned, priority: it.priority });
      }
    }
    // 594 §11.3 #5 — a FACT chip outranks a decorative one in the overflow order, so a real
    // capability is never the chip hidden behind "+N"; ties break by the authored priority.
    return out.sort(
      (a, b) =>
        (a.presence === 'decorative' ? 1 : 0) - (b.presence === 'decorative' ? 1 : 0) ||
        b.priority - a.priority,
    );
  }

  /**
   * 594 §17.4 — the chip's accessible label, in WORDS. The visual cues (the trailing "?" for low
   * confidence, the `data-confidence` dotted underline, the `title` provenance) are invisible to
   * assistive tech, so the chip carries an `aria-label` that states presence/confidence/provenance
   * as text (the same defect class 596 flagged on `title`-on-disabled). Chip-local on purpose — NOT
   * a shared trust-render primitive (§17.3: no second trust-bearing adopter yet).
   */
  private chipAria(it: {
    text: string;
    presence: FactPresence | 'decorative';
    confidence?: FactConfidence;
    provenance?: string;
  }): string {
    const base = it.text.replace(/\?$/, ''); // the bare "?" is ambiguous to AT — drop it, say it in words
    const parts = [base];
    if (it.presence === 'unknown') parts.push('status unknown');
    if (it.provenance) parts.push(it.provenance.replace(/ · /g, ', ')); // provenance carries source + confidence
    else if (it.confidence === 'low') parts.push('low confidence');
    else if (it.confidence === 'unknown') parts.push('confidence unknown');
    return parts.join(', ');
  }

  /**
   * 569 §14 — the co-projected OVERFLOW facet. Renders the projected (594 Move 1b) chip set; the
   * engine hides the trailing tail beyond {@link OverflowController}'s measured `visibleCount` and
   * surfaces a "+N" indicator. The author declares items + priority but has NO field for the clip,
   * so a naked-clipped bar is unrepresentable (rung-2).
   */
  private renderAdaptiveStrip(): TemplateResult {
    const ordered = this.projectedStrip();
    const vis = this.overflowCtl.visibleCount;
    const hidden = Number.isFinite(vis) ? Math.max(0, ordered.length - vis) : 0;
    return html`<div class="adaptive-strip" role="list" aria-label="status">
      ${ordered.map(
        (it, i) =>
          html`<span
            class="item"
            role="listitem"
            ?hidden=${i >= vis}
            data-pinned=${it.pinned ? 'true' : 'false'}
            data-presence=${it.presence}
            data-confidence=${it.confidence ?? nothing}
            title=${it.provenance ?? nothing}
            aria-label=${this.chipAria(it)}
            >${it.text}</span
          >`,
      )}
      ${hidden > 0
        ? html`<span class="more" data-testid="overflow-more">+${hidden}</span>`
        : nothing}
    </div>`;
  }

  private readonly handleChildChange = (value: unknown, path: string): void => {
    const nextData = setAtPath(this.data, path, value);
    this.data = nextData;
    this.dispatchEvent(
      new CustomEvent<SurfaceChangeEventDetail>('surface-change', {
        detail: { data: nextData, path, value },
        bubbles: true,
        composed: true,
      }),
    );
  };
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-declared-surface')
) {
  customElements.define('jf-declared-surface', DeclaredSurface);
}
