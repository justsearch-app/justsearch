// SPDX-License-Identifier: Apache-2.0
/**
 * ambientStyles — tempdoc 574 Move 1/2 (the tree-wide AMBIENT facets, delivered into every
 * shadow root).
 *
 * The §15/§16 root cause: a Lit app is ~95 sealed shadow roots + one light-DOM `tokens.css` with
 * no adopted stylesheet, so presentation expressed through a shadow-non-crossing CSS mechanism
 * (pseudo-elements, selector rules, `@keyframes`) is a *false global* — defined once but applying
 * almost nowhere, which manufactures per-component forks (the scrollbar/selection/keyframes family).
 *
 * The fix has two halves by facet class (574 §16):
 *  - **Class-A** ambient facets that DO inherit (scrollbar-width/scrollbar-color, motion, z) live in
 *    `tokens.css :root` and reach every shadow root by platform inheritance — no adoption needed.
 *  - **Class-B** ambient facets with NO inherited form (this file) are adopted into every shadow root
 *    via the {@link JfElement} base (Move 1's delivery substrate), so they finally apply to shadow
 *    content. Every value is a token (tokens.css is the single source); this sheet carries STRUCTURE,
 *    never authored values.
 *
 * This is the host-owned channel 569 §2 / Move 4 names: components compose it by extending JfElement;
 * they cannot write to it. New ambient facets are added HERE (one place), never per component — the
 * `class-b-ambient` gate (Phase 5) forbids these constructs inside a component's own `static styles`.
 */
import { css } from 'lit';

export const ambientStyles = css`
  /* Scrollbar WIDTH is NOT an inherited property (only scrollbar-color is — CSS Scrollbars L1), so
     the :root \`scrollbar-width: thin\` in tokens.css reaches the light-DOM scrollers but leaves
     shadow-DOM scrollers at the default "auto" width (live-verified §15 gap). Setting ONLY the
     non-inherited width here — in this adopted sheet that reaches every shadow root — thins them too.
     scrollbar-color is NOT repeated: it IS inherited, so the single :root declaration in tokens.css
     already crosses every shadow boundary (A5 — repeating it on \`*\` was redundant + over-broad). */
  :host,
  * {
    scrollbar-width: thin;
  }

  /* Hide the native scrollbar entirely — the WEBKIT pseudo-element half (Chromium/Tauri). The
     standard/inherited half is \`scrollbar-width: none\`, which a component sets locally on the same
     element; \`::-webkit-scrollbar\` is a Class-B shadow-scoped pseudo (574 §16), so its ONE definition
     lives here and a component opts in by adding \`jf-scrollbar-none\` to the element. Used by the §21
     run-spine reading column, where the minimap IS the scroll control so the native bar is redundant. */
  .jf-scrollbar-none::-webkit-scrollbar {
    width: 0;
    height: 0;
  }

  /* Text selection — a pseudo-element, shadow-scoped: a document-sheet ::selection never reached
     shadow text (§16 S1). Adopted here, it does. Colour is the theme-aware token. */
  ::selection {
    background: var(--selection-bg);
  }

  /* Focus ring — :focus-visible is shadow-scoped, so a document rule could not reach shadow
     focusables (§16 S5, a11y-bearing). One consistent ring, token-coloured, in every root. */
  :focus-visible {
    outline: 2px solid var(--focus-ring-color);
    outline-offset: 2px;
  }
  /* Forced colors (Windows High Contrast): the ring is the system Highlight, stated here rather than
     through the token, so a theme that writes --focus-ring-color inline on the root cannot take it.
     A component rule that hides its ring behind an 'outline: 2px solid transparent' gets CanvasText
     instead (forced colors repaint the transparent outline), which is still a visible ring. */
  @media (forced-colors: active) {
    :focus-visible {
      outline-color: Highlight;
    }
  }

  /* Input placeholder — ::placeholder is shadow-scoped (§16 S3), so a document rule never reached
     shadow inputs and each one re-authored the muted colour. The ONE token-coloured placeholder, in
     every root; the \`ambient-purity\` gate bans re-authoring it in a component (574 Move 2). */
  ::placeholder {
    color: var(--text-muted);
  }

  /* Screen-reader-only utility — a multi-property clip pattern that cannot be a shared class across
     shadow roots (§16 S6). One definition every component can use. */
  .visually-hidden {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    margin: -1px;
    overflow: hidden;
    clip: rect(0 0 0 0);
    clip-path: inset(50%);
    white-space: nowrap;
    border: 0;
  }

  /* Single-line truncation utility (§19.3 truncate facet) — the overflow/ellipsis/nowrap triad that
     was re-authored per component. One shared class every root can compose (additive, no ban). */
  .jf-truncate {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  /* Shared motion keyframes (574 §16 S2) — keyframe names are tree-scoped, so each animating
     component had to redeclare them (spin ×5, …). Defined once here; a component's local copy (if
     any) still wins within its own root during migration, so adding these is non-breaking. The
     consolidation step removes the per-component copies. Rotation is universally identical; divergent
     pulse variants stay local until reconciled (AHA — do not merge animations that differ). */
  @keyframes spin {
    to {
      transform: rotate(360deg);
    }
  }
  @keyframes jf-spin {
    to {
      transform: rotate(360deg);
    }
  }
`;
