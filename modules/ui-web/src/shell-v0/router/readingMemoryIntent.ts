// SPDX-License-Identifier: Apache-2.0
/** Arrival-only focus, separate from the Brain tab stored in the navigation address. */
let pending = false;
const listeners = new Set<() => void>();

export function requestReadingMemoryFocus(): void {
  if (!listeners.size) pending = true;
  else for (const listener of listeners) listener();
}

export function takeReadingMemoryFocus(): boolean {
  const requested = pending;
  pending = false;
  return requested;
}

export function subscribeReadingMemoryFocus(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
