// SPDX-License-Identifier: Apache-2.0
/** Shell viewport breakpoint authority, shared across subscribers. */
const WIDE_QUERY = '(min-width: 64rem)';

type Listener = (wide: boolean) => void;
const listeners = new Set<Listener>();
let mql: MediaQueryList | null = null;
let initialized = false;

/** Current viewport breakpoint (wide when matchMedia is unavailable). */
export function isWideLayout(): boolean {
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    return window.matchMedia(WIDE_QUERY).matches;
  }
  return true;
}

function notify(): void {
  const wide = isWideLayout();
  for (const l of listeners) l(wide);
}

function ensure(): void {
  if (initialized) return;
  initialized = true;
  if (typeof window !== 'undefined' && typeof window.matchMedia === 'function') {
    mql = window.matchMedia(WIDE_QUERY);
    mql.addEventListener('change', () => notify());
  }
}

/** Subscribe to wide-breakpoint changes. Fires once immediately with the current value. */
export function subscribeWide(listener: Listener): () => void {
  ensure();
  listeners.add(listener);
  listener(isWideLayout());
  return () => {
    listeners.delete(listener);
  };
}
