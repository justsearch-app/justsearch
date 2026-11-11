// SPDX-License-Identifier: Apache-2.0
/**
 * Store registry — slice 489 §5 / §6 source-of-truth wiring.
 *
 * Maps the abstract {@code storeId} declared in `SurfaceStateSchema.bindings`
 * to a concrete FE store's serialize/restore API. The registry is the only
 * indirection layer between the router and the existing pub-sub stores —
 * the router never imports store modules directly. Adding a new store-as-
 * surface-state-source is one registration, not a router refactor.
 *
 * Each store implementation provides:
 *   - `serialize()` — returns a plain bag matching the schema field types
 *   - `restore(snapshot)` — applies the bag (silently coerces by type)
 *   - `subscribe(callback)` — pub-sub for the URL projector to listen on
 *
 * Per slice 489 §6: state is the source of truth, URL is the projection.
 * The router calls `restore` on URL arrival and `subscribe` to drive
 * `replaceState` on store changes.
 */

import type { StateSnapshot } from './types.js';

export interface StoreAdapter {
  /** Stable abstract identifier the schema's `storeId` resolves to. */
  readonly storeId: string;

  /** Read current store value as a flat bag matching the surface schema. */
  serialize(): StateSnapshot;

  /**
   * Apply a snapshot — replace the store's current state with the values in
   * the bag. Implementations silently coerce types (URL args arrive as
   * strings; the store coerces against its typed shape). Missing keys
   * preserve current defaults. `opening` is true when the app pushes the address after this
   * restore (an in-app navigation, not a traversal or an address the browser already holds), so
   * the address it pushes is what the store holds afterwards.
   */
  restore(snapshot: StateSnapshot, opening?: boolean): void;

  /**
   * Subscribe to store changes. Listener fires whenever the addressable
   * state may have changed. Returns an unsubscribe handle.
   */
  subscribe(listener: (s: StateSnapshot) => void): () => void;

  /**
   * Tempdoc 864 PR C — on a history TRAVERSAL (Back / Forward), is an absent argument a value?
   *
   * The default is no, and that is right for a refinement: a search address without `?query=`
   * means the projector had nothing to write, not that the reader wants the box emptied, so
   * {@link NavigationHandler} skips absent-valued bindings and the store keeps what it has.
   *
   * It is wrong for a store whose whole content IS the address. Search v3's hero — no conversation
   * claimed — projects as the bare surface address, so the entry a new session pushes carries no
   * `conversationId`. Under the default skip, going FORWARD onto that entry would leave the window
   * showing the conversation the reader had just left, with a URL saying otherwise.
   *
   * Gated on traversal deliberately: a programmatic `activateSurface(id, {})` also arrives with no
   * state, and treating that as "close the open conversation" would make a re-navigation to this
   * surface destructive. A traversal is the one arrival where the browser has already moved and the
   * address is therefore the authority.
   */
  readonly clearsOnTraversal?: boolean;
}

const registry = new Map<string, StoreAdapter>();

/**
 * Register a store adapter. Idempotent — re-registering under the same
 * `storeId` replaces the prior registration (intended for hot-reload + tests).
 */
export function registerStore(adapter: StoreAdapter): void {
  registry.set(adapter.storeId, adapter);
}

/** Look up a registered adapter by abstract storeId. Returns undefined if absent. */
export function getStore(storeId: string): StoreAdapter | undefined {
  return registry.get(storeId);
}

/** Test-only: clear all registrations. */
export function __resetStoreRegistryForTest(): void {
  registry.clear();
}

/** Returns the registered storeIds in insertion order. */
export function registeredStoreIds(): string[] {
  return Array.from(registry.keys());
}
