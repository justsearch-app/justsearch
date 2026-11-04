// SPDX-License-Identifier: Apache-2.0
/**
 * LayoutManifest — Tempdoc 507 §6 Phase 5 / D4.
 *
 * A Layout is a JSON-only declaration (not code-bearing). It specifies
 * which surfaces appear in which zones, in what order, and with what
 * visibility. The kernel validates the declaration against this schema
 * and arranges zones accordingly.
 *
 * D4: Layout is data, Chrome is code. Layouts are safe for UNTRUSTED
 * plugins to contribute because they execute no code.
 */

export interface LayoutZoneConfig {
  /** Surface ids to mount in this zone (order-preserving). */
  readonly surfaces?: readonly string[];
  /** Whether the zone is visible. */
  readonly visible?: boolean;
  /** Exclusive mode: only one surface visible at a time (stage behavior). */
  readonly exclusive?: boolean;
  /**
   * Tempdoc 521 §16.7 deeper — split-stage axis. Consumed by `Stage`
   * only when `exclusive === false`; ignored otherwise. `horizontal`
   * mounts the two surfaces side-by-side (left/right); `vertical`
   * stacks them top/bottom. Defaults to `horizontal` when unset on a
   * non-exclusive stage.
   */
  readonly splitAxis?: 'horizontal' | 'vertical';
}

export interface LayoutManifest {
  /** Stable layout id (e.g., 'core.default', 'focus-mode'). */
  readonly id: string;
  /** Human-readable display name. */
  readonly displayName: string;
  /** Version string. */
  readonly version: string;
  /** Optional description. */
  readonly description?: string;
  /** Zone configurations. */
  readonly zones: Readonly<Record<string, LayoutZoneConfig>>;
}

// ---------------------------------------------------------------------------
// Built-in layouts
// ---------------------------------------------------------------------------

export const DEFAULT_LAYOUT: LayoutManifest = {
  id: 'core.default',
  displayName: 'Default',
  version: '1.0',
  description: 'Standard layout: the area switcher above one window.',
  zones: {
    rail: { visible: true },
    stage: { exclusive: true },
    statusBar: { visible: true },
  },
};

export const FOCUS_LAYOUT: LayoutManifest = {
  id: 'core.focus',
  displayName: 'Focus',
  version: '1.0',
  description: 'Minimal layout — one window, no area switcher, no plugin status.',
  zones: {
    // Tempdoc 508-followup §β3 — focus mode hides BOTH rail and status
    // deck, making the layout switch visibly distinct from default.
    rail: { visible: false },
    stage: { exclusive: true },
    statusBar: { visible: false },
  },
};

// Tempdoc 521 §16.7 — `core.zen` is the second alternative built-in layout
// beyond `core.focus`. Mechanically it mirrors focus (rail + statusBar hidden,
// stage exclusive), but it ships as a distinct catalog entry so the kernel's
// support for multiple alternative layouts is exercised by a built-in pair,
// not just by the focus/default pair. The substrate gives layouts no semantic
// behavior beyond the zones map, so `zen` and `focus` differing only in id +
// displayName is intentional: the kernel-side substrate is what's under test
// here, not the UX distinction between the two layouts.
export const ZEN_LAYOUT: LayoutManifest = {
  id: 'core.zen',
  displayName: 'Zen',
  version: '1.0',
  description: 'Distraction-free — one window only, no area switcher, no plugin status.',
  zones: {
    rail: { visible: false },
    stage: { exclusive: true },
    statusBar: { visible: false },
  },
};

/**
 * Tempdoc 521 §16.7 deeper — `core.split` activates the multi-surface
 * Stage path (two surfaces side-by-side). The primary surface follows
 * the rail selection as in `core.default`; the secondary surface is
 * persisted at `userConfig.secondaryActiveSurface` and changed via the
 * right-pane picker affordance Shell renders on top of the second pane.
 */
export const SPLIT_LAYOUT: LayoutManifest = {
  id: 'core.split',
  displayName: 'Split',
  version: '1.0',
  description: 'Two windows side by side.',
  zones: {
    rail: { visible: true },
    stage: { exclusive: false, splitAxis: 'horizontal' },
    statusBar: { visible: true },
  },
};

// ---------------------------------------------------------------------------
// LayoutCatalog — in-memory catalog with core + plugin contributions
// ---------------------------------------------------------------------------

const catalogEntries = new Map<string, LayoutManifest>();
const changeListeners = new Set<() => void>();

function notifyChange(): void {
  for (const l of changeListeners) {
    try { l(); } catch { /* swallow */ }
  }
}

export function initLayoutCatalog(): void {
  catalogEntries.set(DEFAULT_LAYOUT.id, DEFAULT_LAYOUT);
  catalogEntries.set(FOCUS_LAYOUT.id, FOCUS_LAYOUT);
  catalogEntries.set(ZEN_LAYOUT.id, ZEN_LAYOUT);
  catalogEntries.set(SPLIT_LAYOUT.id, SPLIT_LAYOUT);
}

export function getLayout(id: string): LayoutManifest | undefined {
  return catalogEntries.get(id);
}

export function listLayouts(): LayoutManifest[] {
  return Array.from(catalogEntries.values());
}

export function registerLayout(layout: LayoutManifest): void {
  catalogEntries.set(layout.id, layout);
  notifyChange();
}

export function unregisterLayout(id: string): boolean {
  const removed = catalogEntries.delete(id);
  if (removed) notifyChange();
  return removed;
}

export function onLayoutCatalogChange(listener: () => void): () => void {
  changeListeners.add(listener);
  return () => changeListeners.delete(listener);
}

export function __resetLayoutCatalogForTest(): void {
  catalogEntries.clear();
  changeListeners.clear();
}
