// SPDX-License-Identifier: Apache-2.0
/**
 * Surface-navigation request seam — tempdoc 609 §R (T1.3).
 *
 * Overlay components (the floating TaskList, the status-bar running-job chip) live outside the shell's
 * render tree and have no IntentRouter handle, so they request navigation via one document-level event the
 * Shell listens for and routes through `activateSurface`. One seam, both producers — no component reaches
 * into routing directly. Mirrors the rail's existing event-to-shell navigation pattern.
 */
import { requestIndexingRuleFocus, type IndexingRuleControl } from '../router/indexingRuleIntent.js';
import type { StateSnapshot } from '../router/types.js';

export const NAVIGATE_TO_SURFACE_EVENT = 'jf-navigate-to-surface';

export interface NavigateToSurfaceDetail {
  readonly surfaceId: string;
  readonly state?: StateSnapshot;
}

/** Ask the shell to navigate to a surface (the "return to running job" action). */
export function requestSurfaceNavigation(surfaceId: string, state?: StateSnapshot): void {
  if (typeof document === 'undefined') return;
  document.dispatchEvent(
    new CustomEvent<NavigateToSurfaceDetail>(NAVIGATE_TO_SURFACE_EVENT, {
      detail: { surfaceId, ...(state ? { state } : {}) },
    }),
  );
}

/** Publish the arrival request before navigating; no focus state enters history. */
export function requestIndexingRuleNavigation(control: IndexingRuleControl): void {
  requestIndexingRuleFocus(control);
  requestSurfaceNavigation('core.library-surface');
}
