// SPDX-License-Identifier: Apache-2.0
/**
 * transientLayerArbiter — tempdoc 574 Move 4: the generalized single-open arbiter for transient
 * overlay LAYERS (menus, popovers, tooltips).
 *
 * §16 S10: menus / popovers / the dismiss-triad (ContextMenu, BookmarksPopover, SelectionActionsMenu, Peek) each hand-rolled an
 * outside-click dismiss with NO shared arbiter, so two could be open at once. This is the ONE place
 * "at most one open per layer" lives — a component registers a close callback under a (layer, id) and
 * calls {@link closeOthersInLayer} on open, so opening one closes its peers by construction.
 */
type Closer = () => void;

const layers = new Map<string, Map<string, Closer>>();

function layerMap(layer: string): Map<string, Closer> {
  let m = layers.get(layer);
  if (!m) {
    m = new Map();
    layers.set(layer, m);
  }
  return m;
}

/** Register a transient overlay's close callback under (layer, id) (idempotent). */
export function registerTransient(layer: string, id: string, close: Closer): void {
  layerMap(layer).set(id, close);
}

/** Forget a transient overlay (e.g. on disconnect). */
export function unregisterTransient(layer: string, id: string): void {
  layers.get(layer)?.delete(id);
}

/** Close every registered overlay in `layer` except the one being opened. */
export function closeOthersInLayer(layer: string, openId: string): void {
  for (const [id, close] of layerMap(layer)) {
    if (id !== openId) {
      try {
        close();
      } catch {
        /* swallow */
      }
    }
  }
}

/** Test-only reset. */
export function __resetTransientArbiter(): void {
  layers.clear();
}
