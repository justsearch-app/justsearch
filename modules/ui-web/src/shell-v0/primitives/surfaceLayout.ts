// SPDX-License-Identifier: Apache-2.0
/**
 * SurfaceLayout — tempdoc 559 Authority I (spatial / surface-shell layout).
 *
 * THE one region+rhythm authority for intra-surface placement. Today the 11
 * `jf-*-surface` elements each re-implement the identical `:host{flex column;
 * height:100%}` + `.header`/`.body` block (the "two authorities for one concept"
 * defect, one layer in) — diverging in padding so vertical rhythm drifts. This
 * primitive owns the contract once; surfaces compose it and keep only bespoke
 * rules, so the rhythm cannot drift (collapse, tier-1).
 *
 * Two variants (the only two the surfaces actually use):
 *  - `surfaceLayoutStyles` (default): the body scrolls inside a fixed header
 *    (Search / Health / Browse / Library / Logs / Agent / TokenEditor). Activity keeps these
 *    regions but scrolls as a whole, header included, so its feed keeps the window at 200% text.
 *  - `surfaceScrollLayoutStyles`: the whole host scrolls under a sticky header
 *    (Brain / Settings / Help).
 *
 * Region rhythm comes from the `--density-*` tokens (tokens.css) — the single
 * spacing scale — never per-surface `rem` literals. Landmarks are NOT assigned
 * here: per 559 Authority II they project from surface `placement` at the shell
 * mount (the stage is the one `main`), not from intra-surface regions.
 *
 * 559 Authority VI (Adaptivity, slack/fill) — the surface also owns the *fill*
 * policy, declared once on the host (`data-fill` attribute), not hand-rolled per
 * surface:
 *  - `data-fill="reading"`: BOTH header and body sit in a centered
 *    `--surface-content-max-width` column (aligned) with intentional side
 *    whitespace, so a short content block on a wide viewport reads as deliberate,
 *    not a 40–60% loose empty band that stretches full-bleed.
 *  - default / `data-fill="full"`: full-bleed (tables, logs, chat transcripts).
 * This is the constraint-is-slack case of "layout is a total function over space."
 * The policy is AVAILABLE but currently has NO adopter — full-bleed is the default
 * everywhere. A measured live A/B (559 Appendix A, "the reading-column tradeoff")
 * found centering only a partial win, so adoption awaits a narrower-measure /
 * row-regroup decision. A surface opts in with `setAttribute('data-fill','reading')`.
 *
 * Usage:  static styles = [surfaceLayoutStyles, css`/* surface-specific *​/`];
 * (a later surface-specific rule still wins by cascade where it must override.)
 * Declare the fill policy on the host (e.g. `connectedCallback` →
 * `this.setAttribute('data-fill','reading')`), so header and body align.
 */
import { css } from 'lit';

/** Shared region contract used by BOTH variants. */
const regionBase = css`
  :host {
    box-sizing: border-box;
    color: var(--text-primary);
    font-family: var(--font-native);
  }
  /* 559 Authority VI slack/fill — the declared reading-column policy. When the
     host declares data-fill="reading", BOTH header and body collapse to a
     centered max-width column (aligned) instead of stretching full-bleed and
     reading as a loose empty band. Full-bleed surfaces omit data-fill (or set
     "full"). Applies in both layout variants (shared regionBase). */
  :host([data-fill='reading']) .header,
  :host([data-fill='reading']) .body {
    width: 100%;
    max-width: var(--surface-content-max-width);
    margin-inline: auto;
  }
  .header {
    flex-shrink: 0;
    padding: var(--density-header-pad-y) var(--density-header-pad-x);
    border-bottom: 1px solid var(--border-subtle);
  }
  .header h1 {
    margin: 0;
    font-size: var(--font-size-headline);
    font-weight: 600;
  }
  .header .sub {
    margin-top: 0.25rem;
    font-size: var(--font-size-xs);
    color: var(--text-secondary);
  }
  .footer {
    flex-shrink: 0;
    padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
    border-top: 1px solid var(--border-subtle);
  }
`;

/** Default: fixed header, the `.body` region scrolls. */
export const surfaceLayoutStyles = css`
  ${regionBase}
  :host {
    display: flex;
    flex-direction: column;
    height: 100%;
  }
  .body {
    flex: 1;
    overflow-y: auto;
    padding: var(--density-inner-pad-y) var(--density-inner-pad-x);
  }
  /* A short window: a fixed header (with its filters and notices) can fill it and leave the body
     below the window, as at 600x400 with 200% text. There the header scrolls with the body, and the
     surface is the one scroller. Scoped by :host so it outranks a surface's own .body rule. */
  @media (max-height: 30rem) {
    :host {
      overflow-y: auto;
    }
    :host .body {
      flex: none;
      overflow-y: visible;
    }
  }
`;

/** Variant: the whole host scrolls under a sticky header. */
export const surfaceScrollLayoutStyles = css`
  ${regionBase}
  :host {
    display: block;
    height: 100%;
    overflow-y: auto;
  }
  .header {
    position: sticky;
    top: 0;
    background: var(--surface-0);
    z-index: 1;
  }
`;
