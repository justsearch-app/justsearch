// SPDX-License-Identifier: Apache-2.0
/**
 * The composer's hero → docked morph (tempdoc 822 slice 3).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The design spec's choreography, which is three ideas rather than one animation:
 *
 *  1. **Only named elements move.** `::view-transition-old/new(root)` are killed, so the page does
 *     not cross-fade; the window around the composer stays visually stationary.
 *  2. **The content swap happens in the middle third.** The two composer forms have different
 *     internal layouts, so their images hold opacity for the first 35%, cross between 35% and 65%,
 *     and hold for the last 35% — the eye tracks the moving box and never sees a cut.
 *  3. **Departing sub-elements leave before the container settles** — the headline exits in 130ms
 *     with an accelerating ease and a 6px upward drift while the composer group takes 180ms.
 *
 * CONTAINMENT OBLIGATION. `::view-transition-*` pseudo-elements are document-level: they cannot be
 * declared inside a shadow root, and a stylesheet in `document.adoptedStyleSheets` outlives whatever
 * added it. This window is a hidden dev surface that must not change how the SHIPPED app animates,
 * so containment is enforced twice over:
 *
 *   - **Lifetime** — the sheet is ref-counted onto `document.adoptedStyleSheets` while a Search v3
 *     window is connected and removed the moment the last one disconnects (asserted by unit test).
 *   - **Scope** — every rule is gated on `:root[data-sv3-composer-morph='true']`, an attribute that
 *     exists only for the duration of one composer morph. Without the gate, killing the root
 *     transition would silently disable the shipped shell's own surface cross-fade
 *     (`chrome/viewTransition.ts`) for as long as this window stayed mounted.
 *
 * The durations and easings below are LITERALS, not `var(--duration-sv3-morph)`, because a
 * `::view-transition-*` pseudo resolves custom properties against the document root — where the
 * window's host-scoped token sheet deliberately declares nothing. `sv3-tokens.test.ts` pins each
 * literal against the token of the same name so the two cannot drift apart.
 */
import { adoptTransitionPromises, surfaceTransitionsEnabled } from '../../chrome/viewTransition.js';

/** Set on `<html>` for the duration of one morph; every rule in the sheet is gated on it. */
export const SV3_MORPH_ROOT_ATTR = 'data-sv3-composer-morph';
/** Set on the composer element for the duration of one morph; its sheet keys the names off it. */
export const SV3_MORPH_ELEMENT_ATTR = 'morphing';
export const SV3_COMPOSER_VT_NAME = 'sv3-composer';
export const SV3_HERO_HEADLINE_VT_NAME = 'sv3-hero-headline';

/** The container's move — the one duration reserved for the signature morph. */
export const SV3_MORPH_DURATION_MS = 180;
export const SV3_MORPH_EASING = 'cubic-bezier(0.4, 0, 0.2, 1)';
/** The departing sub-element, faster than the container and accelerating out. */
export const SV3_HEADLINE_EXIT_MS = 130;
export const SV3_HEADLINE_EXIT_EASING = 'cubic-bezier(0.4, 0, 1, 1)';

const GATE = `:root[${SV3_MORPH_ROOT_ATTR}='true']`;

export const SV3_MORPH_SHEET_TEXT = `
${GATE}::view-transition-old(root),
${GATE}::view-transition-new(root) {
  animation: none;
}

${GATE}::view-transition-group(${SV3_COMPOSER_VT_NAME}) {
  animation-duration: ${SV3_MORPH_DURATION_MS}ms;
  animation-timing-function: ${SV3_MORPH_EASING};
}

${GATE}::view-transition-image-pair(${SV3_COMPOSER_VT_NAME}) {
  isolation: isolate;
}

${GATE}::view-transition-old(${SV3_COMPOSER_VT_NAME}) {
  animation: sv3-composer-old ${SV3_MORPH_DURATION_MS}ms linear both;
  mix-blend-mode: normal;
}

${GATE}::view-transition-new(${SV3_COMPOSER_VT_NAME}) {
  animation: sv3-composer-new ${SV3_MORPH_DURATION_MS}ms linear both;
  mix-blend-mode: normal;
}

@keyframes sv3-composer-old {
  0%,
  35% {
    opacity: 1;
  }
  65%,
  100% {
    opacity: 0;
  }
}

@keyframes sv3-composer-new {
  0%,
  35% {
    opacity: 0;
  }
  65%,
  100% {
    opacity: 1;
  }
}

${GATE}::view-transition-group(${SV3_HERO_HEADLINE_VT_NAME}) {
  animation-duration: ${SV3_HEADLINE_EXIT_MS}ms;
}

${GATE}::view-transition-old(${SV3_HERO_HEADLINE_VT_NAME}) {
  animation: sv3-hero-headline-exit ${SV3_HEADLINE_EXIT_MS}ms ${SV3_HEADLINE_EXIT_EASING} both;
  mix-blend-mode: normal;
}

@keyframes sv3-hero-headline-exit {
  from {
    opacity: 1;
    transform: translateY(0);
  }
  to {
    opacity: 0;
    transform: translateY(-6px);
  }
}

/* The JS gate below already swaps instantly under reduced motion; this is the second half of the
   spec's own redundancy rule, so a morph that somehow reaches the API still does not animate. */
@media (prefers-reduced-motion: reduce) {
  ${GATE}::view-transition-group(${SV3_COMPOSER_VT_NAME}),
  ${GATE}::view-transition-old(${SV3_COMPOSER_VT_NAME}),
  ${GATE}::view-transition-new(${SV3_COMPOSER_VT_NAME}),
  ${GATE}::view-transition-group(${SV3_HERO_HEADLINE_VT_NAME}),
  ${GATE}::view-transition-old(${SV3_HERO_HEADLINE_VT_NAME}) {
    animation: none;
  }
}
`;

let sheet: CSSStyleSheet | null = null;
let mounted = 0;

function morphSheet(): CSSStyleSheet {
  if (sheet === null) {
    sheet = new CSSStyleSheet();
    sheet.replaceSync(SV3_MORPH_SHEET_TEXT);
  }
  return sheet;
}

/** Ref-counted: several windows may be connected at once, and only the last one out clears up. */
export function adoptSv3MorphSheet(): void {
  mounted += 1;
  const own = morphSheet();
  if (!document.adoptedStyleSheets.includes(own)) {
    document.adoptedStyleSheets = [...document.adoptedStyleSheets, own];
  }
}

export function releaseSv3MorphSheet(): void {
  mounted = Math.max(0, mounted - 1);
  if (mounted > 0 || sheet === null) return;
  const own = sheet;
  document.adoptedStyleSheets = document.adoptedStyleSheets.filter((s) => s !== own);
}

/** Whether the document currently carries the morph sheet — the containment obligation, observable. */
export function sv3MorphSheetAdopted(): boolean {
  return sheet !== null && document.adoptedStyleSheets.includes(sheet);
}

interface ViewTransition {
  readonly finished: Promise<unknown>;
  readonly ready?: Promise<unknown>;
}
interface ViewTransitionDocument {
  startViewTransition?: (cb: () => Promise<void> | void) => ViewTransition;
}

/**
 * Run `apply` inside a scoped view transition, or run it plainly when transitions are unavailable or
 * the user prefers reduced motion (`surfaceTransitionsEnabled` is the shell's existing gate for
 * exactly that pair of conditions). `apply` runs exactly once either way — a transition that throws
 * or is cancelled must still leave the state change committed.
 */
export async function runSv3ComposerMorph(
  target: HTMLElement,
  apply: () => Promise<void>,
): Promise<void> {
  let applied = false;
  const applyOnce = async (): Promise<void> => {
    if (applied) return;
    applied = true;
    await apply();
  };
  if (!surfaceTransitionsEnabled()) {
    await applyOnce();
    return;
  }
  const root = document.documentElement;
  target.setAttribute(SV3_MORPH_ELEMENT_ATTR, '');
  root.setAttribute(SV3_MORPH_ROOT_ATTR, 'true');
  try {
    let finished: Promise<unknown> | undefined;
    try {
      const transition = (document as ViewTransitionDocument).startViewTransition?.(() =>
        applyOnce(),
      );
      // Tempdoc 859 — `finished` is awaited below, but `ready` and `updateCallbackDone` were dropped,
      // and `ready` is the face the API rejects with `TimeoutError` when the DOM update outruns its
      // budget. Adopting the whole handle is what keeps a SKIPPED morph out of the unhandled-rejection
      // channel; the local `await finished?.catch(...)` stays because this function also has to WAIT.
      adoptTransitionPromises(transition);
      finished = transition?.finished;
    } catch {
      // A browser that refuses to start one (a transition already running, a hidden document)
      // must not cost the state change; the plain apply below still commits it.
    }
    await finished?.catch(() => undefined);
    await applyOnce();
  } finally {
    // Unconditional: the flag gates rules that would otherwise govern the SHIPPED app's own
    // transitions, so no path — resolved, rejected, refused, or thrown — may leave it set.
    root.removeAttribute(SV3_MORPH_ROOT_ATTR);
    target.removeAttribute(SV3_MORPH_ELEMENT_ATTR);
  }
}
