// SPDX-License-Identifier: Apache-2.0
/**
 * The frame's places (shell slice 2), shared by the top bar and Settings › Layout so the two can
 * never list them differently.
 */

/** The switcher's areas in their fixed order; Ctrl+1/2/3 follow it. */
export const FRAME_AREAS: ReadonlyArray<{ readonly id: string; readonly shortcut: string }> = [
  { id: 'core.search-v3-surface', shortcut: 'Ctrl+1' },
  { id: 'core.library-surface', shortcut: 'Ctrl+2' },
  { id: 'core.brain-surface', shortcut: 'Ctrl+3' },
];

/** Windows with a fixed row of their own (System in the More menu): never an area, never listed. */
export const FRAME_FIXED_WINDOWS: ReadonlySet<string> = new Set(['core.system-surface', 'core.settings-surface']);
