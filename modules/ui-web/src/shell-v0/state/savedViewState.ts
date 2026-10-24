// SPDX-License-Identifier: Apache-2.0
/**
 * savedViewState — projection over UserStateDocument's `savedViews` slice.
 *
 * Slice 501: user-authored bookmarks (saved views). Each entry is a
 * canonical justsearch:// URL. Restoring a saved view = parseUrl(url)
 * → dispatch through IntentRouter. Follows pinnedSearchState.ts pattern.
 *
 * API:
 *   - getSavedViews() — snapshot
 *   - subscribeSavedViews(listener) — projection subscription
 *   - saveView(label, url, surfaceId) — de-dupes by URL
 *   - removeView(id) — removes by id
 *   - isViewSaved(url) — true if a view with this URL exists
 *   - renameView(id, newLabel) — user-editable labels
 */
import { canonicalSurfaceUrl } from '../router/surfaceIdentity.js';
import {
  getDocument,
  subscribeProjection,
  mutateDocument,
  type SavedView,
} from './UserStateDocument.js';

export type { SavedView } from './UserStateDocument.js';

type Listener = (views: readonly SavedView[]) => void;

export function getSavedViews(): readonly SavedView[] {
  return getDocument().savedViews ?? [];
}

export function subscribeSavedViews(listener: Listener): () => void {
  return subscribeProjection((doc) => doc.savedViews ?? [], listener);
}

export function isViewSaved(url: string): boolean {
  return findSavedView(url) !== undefined;
}

/** Alias-aware identity; keep the existing record's id, label, URL and profile. */
export function findSavedView(url: string): SavedView | undefined {
  const place = canonicalSurfaceUrl(url);
  return getSavedViews().find(view => canonicalSurfaceUrl(view.url) === place);
}

export function saveView(
  label: string,
  url: string,
  surfaceId: string,
): SavedView | null {
  if (!url || !surfaceId) return null;
  let result: SavedView | null = null;
  mutateDocument((doc) => {
    const views = doc.savedViews ?? [];
    const existing = findSavedView(url);
    if (existing) {
      result = existing;
      return doc;
    }
    const view: SavedView = {
      id: makeViewId(),
      label: label || surfaceId,
      url,
      surfaceId,
      savedAt: Date.now(),
    };
    result = view;
    return { ...doc, savedViews: [...views, view] };
  });
  return result;
}

export function removeView(id: string): boolean {
  let removed = false;
  mutateDocument((doc) => {
    const views = doc.savedViews ?? [];
    const filtered = views.filter((v) => {
      if (v.id === id) {
        removed = true;
        return false;
      }
      return true;
    });
    if (!removed) return doc;
    return { ...doc, savedViews: filtered };
  });
  return removed;
}

export function renameView(id: string, newLabel: string): boolean {
  let renamed = false;
  mutateDocument((doc) => {
    const views = doc.savedViews ?? [];
    const next = views.map((v) => {
      if (v.id === id) {
        renamed = true;
        return { ...v, label: newLabel };
      }
      return v;
    });
    if (!renamed) return doc;
    return { ...doc, savedViews: next };
  });
  return renamed;
}

function makeViewId(): string {
  const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
  if (c?.randomUUID) return c.randomUUID();
  return `sv-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
