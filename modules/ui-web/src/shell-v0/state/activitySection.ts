// SPDX-License-Identifier: Apache-2.0
/**
 * Arriving at a section of Activity. A pointer from elsewhere (the status panel, Ctrl+K) asks for a
 * section and then navigates to Activity; the navigation, once applied, addresses the request to the
 * window it lands on (FC6). Only an Activity inside that window takes it: another Activity that is
 * connected but not where the navigation went (a preview that was dismissed, the other window of a
 * split) is not the consumer and leaves it alone. The request is held until that Activity takes it,
 * because navigating there is asynchronous and the Activity may mount after the address is known.
 */
export type ActivitySection = 'background-runs' | 'effect-journal';

let requested: ActivitySection | null = null;
/** The window the navigation that followed the request lands on; null until it is applied. */
let addressed: (() => Element | null) | null = null;
const listeners = new Set<() => void>();

function notify(): void {
  for (const listener of listeners) listener();
}

/** `node` is `window` or inside it, across shadow roots. */
function within(node: Node, window: Element): boolean {
  for (let at: Node | null = node; at; at = at.parentNode ?? (at instanceof ShadowRoot ? at.host : null)) {
    if (at === window) return true;
  }
  return false;
}

function isFor(consumer: Element): boolean {
  const window = requested !== null ? addressed?.() : null;
  return !!window && within(consumer, window);
}

/** Ask for a section of Activity; the navigation that follows says which Activity it is for. */
export function requestActivitySection(section: ActivitySection): void {
  requested = section;
  addressed = null;
  notify();
}

/** The navigation to Activity was applied: a pending request is for the Activity in `window`. */
export function addressActivitySection(window: () => Element | null): void {
  if (requested === null) return;
  addressed = window;
  notify();
}

/** The section pending for `consumer`, left in place for it to reveal. */
export function peekActivitySection(consumer: Element): ActivitySection | null {
  return isFor(consumer) ? requested : null;
}

/** The section pending for `consumer`, cleared by the read; null for any other consumer. */
export function takeActivitySection(consumer: Element): ActivitySection | null {
  if (!isFor(consumer)) return null;
  const section = requested;
  requested = null;
  addressed = null;
  return section;
}

export function subscribeActivitySection(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
