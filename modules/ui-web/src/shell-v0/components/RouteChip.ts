// SPDX-License-Identifier: Apache-2.0
/**
 * RouteChip (`jf-route-chip`) — Search Thread tempdoc D3: the visible per-turn ROUTE indicator.
 *
 * Every input already feeds instant search (the floor); Enter either commits a search
 * (`'search'`) or sends to the AI (`'ask'`). This chip is the one place that route is made
 * VISIBLE on the composer row, so Enter is never a surprise: it shows what Enter will do right
 * now, and — when not pinned — clicking it dispatches `route-toggle` so the host can flip the
 * route the same way Ctrl+Enter does.
 *
 * Composition note (574 atom-fork discipline): `jf-filter-chip` is the house interactive-chip
 * atom, but its API is presence/active-only (a plain `active` boolean + a consumer-bound
 * `@click`) — it has no `availability`/`onActivate` surface, so it cannot express "this side is
 * unavailable, and the reason must be reachable" (tempdoc 596). `jf-control` already owns exactly
 * that authority (typed `Availability`, a WCAG-1.4.13 reachable reason, a non-silent blocked
 * activation), so this chip COMPOSES `jf-control` and skins its `part="control"` as a pill via
 * `::part()` — the same pattern `ProvenanceBadge`/`jf-button` use — rather than hand-rolling a
 * second toggle-chip primitive or forcing availability semantics into `jf-filter-chip`.
 *
 * When AI is unavailable the route is PINNED to `'search'` (Hard Invariant: never a silent
 * no-op). `pinned` forces the displayed route to `'search'` and forwards `askAvailability` to the
 * composed `jf-control`, so the reason is reachable via the same hover/focus tooltip — and
 * `jf-control`'s own activation gate (596 `effective()`/`activate()`) means an attempt to activate
 * an `unavailable` side surfaces the reason toast instead of ever emitting `route-toggle`.
 */
import { html, css, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Control.js';
import { unavailableBecause, type Availability } from '../state/availability.js';
import { describeRoute, type TurnRoute } from '../state/routeHeuristic.js';

/** Hint shown (via native `title`) on the operable, non-pinned chip. */
const OTHER_WAY_HINT = 'Ctrl+Enter sends the other way';

export class RouteChip extends JfElement {
  static properties = {
    route: { type: String },
    askAvailability: { attribute: false },
    pinned: { type: Boolean, reflect: true },
  };

  /** What Enter would do absent pinning. */
  declare route: TurnRoute;
  /** Why `'ask'` is unavailable, when `pinned` is true (596 typed availability). */
  declare askAvailability: Availability | null;
  /** True when the AI route is unavailable — Enter is forced to `'search'` regardless of `route`. */
  declare pinned: boolean;

  constructor() {
    super();
    this.route = 'search';
    this.askAvailability = null;
    this.pinned = false;
  }

  static styles = css`
    :host {
      display: inline-flex;
    }
    /* Skin the composed jf-control as a pill (the FilterChip shape) via its exposed part — no
       raw .chip{}/.badge{}/.pill{} class of our own (atom-fork-ratchet). */
    jf-control::part(control) {
      display: inline-flex;
      align-items: center;
      gap: 0.3rem;
      padding: 0.125rem 0.5rem;
      border-radius: 9999px;
      border: 1px solid var(--border-subtle);
      background: var(--surface-2);
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
      font-weight: 500;
      transition: background var(--duration-fast) var(--ease-standard),
        border-color var(--duration-fast) var(--ease-standard),
        color var(--duration-fast) var(--ease-standard);
    }
    jf-control::part(control):hover {
      border-color: var(--text-secondary);
      color: var(--text-primary);
    }
    :host([pinned]) jf-control::part(control) {
      background: var(--surface-1);
    }
    .glyph {
      opacity: 0.75;
    }
  `;

  private handleToggle(): void {
    this.dispatchEvent(new CustomEvent('route-toggle', { bubbles: true, composed: true }));
  }

  override render(): TemplateResult {
    const effectiveRoute: TurnRoute = this.pinned ? 'search' : this.route;
    const label = describeRoute(effectiveRoute);
    const ariaLabel =
      effectiveRoute === 'search' ? 'Enter will search your files' : 'Enter will ask the AI';
    // Pinned → forward the typed reason so it's reachable on jf-control's own tooltip; jf-control's
    // activation gate then blocks `onActivate` from ever firing for an unavailable kind (596 activate()).
    const availability: Availability | undefined = this.pinned
      ? (this.askAvailability ?? unavailableBecause('Ask is unavailable'))
      : undefined;
    const title = this.pinned ? nothing : OTHER_WAY_HINT;

    return html`<jf-control
      label=${ariaLabel}
      title=${title}
      .availability=${availability}
      .onActivate=${() => this.handleToggle()}
      ><span class="glyph">↵</span> ${label}</jf-control
    >`;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-route-chip')) {
  customElements.define('jf-route-chip', RouteChip);
}
