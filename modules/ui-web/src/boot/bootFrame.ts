// SPDX-License-Identifier: Apache-2.0
import { subscribeAppearanceChanges } from '../shell-v0/state/themeState.js';
import { subscribeProfileSwitch } from '../shell-v0/state/UserStateDocument.js';

/** A paint cache, never an appearance authority. No store is read by the early HTML script. */
const FRAME_STORAGE_KEY = 'justsearch.bootFrame.v1';
const colors = {
  background: '--surface-0', raised: '--surface-1', text: '--text-primary',
  muted: '--text-secondary', border: '--border-subtle', accent: '--accent-tint',
} as const;

/** Resolved computed values include palette, presentation and high contrast. Storage is optional. */
function readFrameColors(): string | null {
  try {
    const computed = getComputedStyle(document.documentElement);
    const snapshot: Record<string, string> = {
      mode: document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark',
    };
    for (const [field, token] of Object.entries(colors)) {
      const value = computed.getPropertyValue(token).trim();
      if (!value || !CSS.supports('color', value)) return null;
      snapshot[field] = value;
    }
    return JSON.stringify(snapshot);
  } catch { return null; }
}

/**
 * The single later-change seam: subscribe to the appearance writer (including palette/presentation
 * injection and OS changes) and profile switches. Batch synchronous writes after their final token projection. Install
 * after all boot restores, so a failed/partial restore never saves defaults over the previous look.
 */
export function installBootFramePersistence(restored: boolean): () => void {
  let previous = readFrameColors();
  const save = (value: string | null) => {
    try { if (value) localStorage.setItem(FRAME_STORAGE_KEY, value); }
    catch { /* A denied/quota-limited paint cache must never prevent boot or appearance changes. */ }
  };
  if (restored) save(previous);
  let queued = false;
  let active = true;
  const changed = () => {
    if (queued) return;
    queued = true;
    queueMicrotask(() => {
      queued = false;
      if (!active) return;
      const next = readFrameColors();
      // Boot may still add fonts or lock scrolling for onboarding. Neither is an appearance edit,
      // and neither may replace the cache after a failed restore (or another window's edit).
      if (next !== previous) { previous = next; save(next); }
    });
  };
  const unsubscribeAppearance = subscribeAppearanceChanges(changed);
  const unsubscribeProfile = subscribeProfileSwitch(changed);
  return () => { active = false; unsubscribeAppearance(); unsubscribeProfile(); };
}
