// SPDX-License-Identifier: Apache-2.0
/**
 * Arriving at a section of Activity. The request is held until Activity is on screen to take it,
 * because navigating there is asynchronous and a request made before it mounts must not be lost.
 */
export type ActivitySection = 'background-runs' | 'effect-journal';

let requested: ActivitySection | null = null;
const listeners = new Set<() => void>();

export function requestActivitySection(section: ActivitySection): void {
  requested = section;
  for (const listener of listeners) listener();
}

/** The pending section, left in place for the Activity that will reveal it. */
export function peekActivitySection(): ActivitySection | null {
  return requested;
}

/** The pending section, cleared by the read. */
export function takeActivitySection(): ActivitySection | null {
  const section = requested;
  requested = null;
  return section;
}

export function subscribeActivitySection(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
