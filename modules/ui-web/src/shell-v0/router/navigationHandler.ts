// SPDX-License-Identifier: Apache-2.0
/**
 * NavigationHandler — Intent substrate tier 3 (slice 492).
 *
 * Given a {@link ShellAddressNavigation}, bring observable reality into
 * alignment: distribute state to registered StoreAdapters per the surface's
 * stateSchema, set the active surface, push the URL, and activate the
 * projector. This is the canonical "realize a Navigation intent" site —
 * the body that previously lived as a side-effect of URLHydrator.applyState
 * and as Shell.activateSurface's ignored `state` parameter.
 *
 * Invariants (slice 492):
 *
 *   - **Determinism per address.** Two handle() calls with the same
 *     `ShellAddressNavigation` converge the observable state to the same
 *     final shape: store contents are equal; activeId equals the target;
 *     the URL hash reflects the canonical projection. Note this is
 *     weaker than CQRS-style "idempotency at the effect level":
 *     `pushState` is called once per handle() with `push: true`, so
 *     two handles produce two history entries — both pointing at the
 *     same canonical URL. Callers that want effect-level idempotency
 *     pass `push: false` (the popstate path uses this).
 *   - **Bidirectionality** (redux-first-router precedent). Every handler
 *     write round-trips to the URL via the projector. Guaranteed because
 *     activateProjection subscribes to every store the schema names.
 *   - **Transport purity** (hexagonal precedent). The handler takes a
 *     parsed ShellAddress, never a raw Location or SSE wrapper.
 *
 * Concurrency policy (slice 492 — last-writer-wins by synchronous
 * execution): handle() steps are synchronous (validate → distribute
 * → activate → push → project). JavaScript's single-threaded event
 * loop serializes them; two overlapping handle() calls run in
 * call-order, and the second's effects land on top of the first's.
 * Combined with the determinism property below, this produces the
 * correct user-intent ordering for rapid overlaps (rail-click then
 * popstate then URL paste).
 *
 * A previous draft installed an AbortController + per-step `signal.aborted`
 * checks intending "cancel-in-flight"; that machinery cannot abort
 * anything when steps are synchronous (no event-loop yield between
 * checks) and was removed in the slice-492 critical-analysis follow-up.
 * If a future step becomes async (e.g., per-surface preflight fetch),
 * an AbortController can be re-introduced WITH an `await signal.aborted`
 * checkpoint that actually does something.
 */

import { parkSearchV3Handoff, peekSearchV3Handoff, takeSearchV3Handoff, takePendingSelection, } from '../utils/compose.js';
import { coerceAndValidate } from './stateValidator.js';
import { getSurfaceStateSchema, resolveSurfaceStateSchema } from './surfaceSchemas.js';
import type { StoreAdapter } from './storeRegistry.js';
import type { ShellAddressNavigation, StateSnapshot } from './types.js';
import {
  activateProjection,
  currentAddress,
  flushPendingNavigationWrite,
  pushAddress,
} from './URLProjector.js';

// Compatibility addresses name a mode of Search, not a separate state consumer.
const COMPATIBILITY_SHAPES: Readonly<Record<string, string>> = {
  'core.ask-surface': 'core.rag-ask',
  'core.free-chat-surface': 'core.free-chat',
  'core.extract-surface': 'core.extract',
};

/** Callback the Shell exposes for setting its active surface. */
export type SetActiveSurfaceFn = (surfaceId: string) => void;

/** Callback the Shell exposes for checking whether a surfaceId is in the active rail set. */
export type IsKnownSurfaceFn = (surfaceId: string) => boolean;

/**
 * Callback the Shell exposes for looking up a target's declared placement.
 * `ShellAddress` carries no placement (types.ts), so the handler receives the
 * lookup by injection — the same shape as {@link IsKnownSurfaceFn}.
 */
export type GetPlacementFn = (surfaceId: string) => string | undefined;

/**
 * Report of what the MODAL branch did to history before opening the window.
 *
 * `pushed` is the load-bearing bit: the window's close routine can only unwind with
 * `history.back()` when this navigation actually ADDED an entry. A boot/deep-link entry (the URL
 * already IS the settings address) and a repeat navigation while the window is open both push
 * nothing, so closing must move FORWARD to the stage address instead (tempdoc 855 §11.1 D3/D4).
 */
export interface OpenModalInfo {
  readonly pushed: boolean;
}

/** Callback the Shell exposes for opening a MODAL-placement target as a window. */
export type OpenModalFn = (
  surfaceId: string,
  state: Record<string, unknown> | undefined,
  info: OpenModalInfo,
) => void;

/** Unknown-surface recovery handler. */
export type OnUnknownSurfaceFn = (surfaceId: string) => void;

export interface NavigationHandlerConfig {
  setActiveSurface: SetActiveSurfaceFn;
  isKnownSurface: IsKnownSurfaceFn;
  /** Optional — absent lookup means every target takes the normal (stage-mounting) path. */
  getPlacement?: GetPlacementFn;
  /** Optional — absent opener means a MODAL target still pushes its URL but shows nothing. */
  openModal?: OpenModalFn;
  /** Optional — defaults to a console.warn. */
  onUnknownSurface?: OnUnknownSurfaceFn;
}

export interface NavigationHandleOptions {
  /** Internal provenance: history/journal restoration never replays a prepared draft. */
  taskOrigin?: 'fresh' | 'restore';
  /**
   * When true (default) the handler pushes a new history entry via
   * `pushAddress`. When false (used by popstate-driven activations), the
   * URL was already updated by the browser and no push is needed.
   */
  push?: boolean;
}

export interface NavigationHandler {
  /** Realize a Navigation intent. */
  handle(addr: ShellAddressNavigation, options?: NavigationHandleOptions): Promise<void>;
}

export function createNavigationHandler(config: NavigationHandlerConfig): NavigationHandler {
  const onUnknown =
    config.onUnknownSurface ??
    ((surfaceId) => {
      console.error(
        `[NavigationHandler] BUG: received unresolved surfaceId "${surfaceId}". ` +
          'The IntentRouter should have caught this during resolution (tempdoc 499 §4.7).',
      );
    });

  return {
    async handle(
      addr: ShellAddressNavigation,
      options?: NavigationHandleOptions,
    ): Promise<void> {
      if (!config.isKnownSurface(addr.target)) {
        onUnknown(addr.target);
        return;
      }

      // Lower before schema hydration: the legacy Ask store is not read by Search v3.
      // Retain the raw task so the existing hand-off validation can refuse invalid scope.
      const compatibilityShape = Object.hasOwn(COMPATIBILITY_SHAPES, addr.target) ? COMPATIBILITY_SHAPES[addr.target] : undefined;
      if (compatibilityShape) {
        addr = { ...addr, target: 'core.search-v3-surface',
          state: { forceShapeId: compatibilityShape, ...addr.state } };
      }

      // 0. MODAL placement (tempdoc 855 §11.1) — the target opens as a window
      //    OVER the current stage surface instead of replacing it. Two skips
      //    below are load-bearing, not stylistic:
      //
      //      • setActiveSurface — Shell.render()'s catalog-wide surface fallback
      //        is NOT placement-filtered, so a changed activeId would ALSO mount
      //        the surface standalone into the Stage.
      //      • activateProjection — it tears the CURRENT surface's URL projection
      //        down BEFORE its own schema check (URLProjector), so calling it for
      //        a schema-less MODAL target would silently kill the underlying
      //        stage surface's live URL sync. The projector's single-slot design
      //        assumes one URL-owning surface; MODAL targets stay outside it.
      //
      //    applyState still runs (forward-safe: a no-op while the target has no
      //    schema) and pushAddress still runs when it would ADD an entry, so the
      //    window's URL is bookmarkable and Back is meaningful. Whether an entry
      //    was added is reported to the opener as `info.pushed` — the Shell needs
      //    it because `history.back()` is only a valid close when this navigation
      //    is what put the settings address on the stack (855 §11.1 D3/D4).
      // Tempdoc 864 PR C — absent arguments clear stores only for actual traversal.
      // A native fresh hash push also emits popstate and has already moved history,
      // but it must not clear B after its incoming task is refused by capacity.
      const traversal = options?.push === false && options.taskOrigin !== 'fresh';

      // Review finding F4 — settle the OUTGOING surface's debt first. A claim made within the
      // projector's 75ms window and followed straight away by a surface change would otherwise be
      // cancelled by the teardown inside activateProjection, losing the history entry the reader
      // earned. Only a navigational write is flushed (a no-op for every other surface), and never
      // on a traversal: the browser has already moved there, so a push would truncate the forward
      // tail — the same hazard the hero branch of SearchV3View.followStoreConversation avoids.
      if (!traversal) flushPendingNavigationWrite();

      if (config.getPlacement?.(addr.target) === 'MODAL') {
        if (peekSearchV3Handoff()?.refused) takeSearchV3Handoff();
        applyState(addr.target, addr.state, traversal);
        // Tempdoc 855 §11.1 D3 — never stack a SECOND entry for the address we are already on.
        // Re-navigating to Settings (rail affordance pressed twice, a command-palette repeat) would
        // otherwise push a duplicate entry, and the window's `history.back()` would land back on the
        // settings address — i.e. Escape would appear to re-open the window.
        const alreadyHere = currentAddress()?.target === addr.target;
        const pushed = options?.push !== false && !alreadyHere;
        if (pushed) {
          pushAddress({
            kind: 'navigate',
            target: addr.target,
            state: addr.state,
          });
        }
        config.openModal?.(addr.target, addr.state, { pushed });
        return;
      }

      // A hand-off is consumed only after safe intake. Its verb's task outranks a conversation id.
      const incomingState = addr.state;
      if (addr.target === 'core.search-v3-surface' &&
          ['query', 'docIds', 'forceShapeId', 'mode', 'affordance', 'selection'].some(key => Object.hasOwn(addr.state, key))) {
        parkSearchV3Handoff(addr.state, options?.taskOrigin ?? (traversal ? 'restore' : 'fresh'));
        addr = { ...addr, state: {} };
      } else {
        takeSearchV3Handoff();
        if (addr.target !== 'core.unified-chat-surface') {
          takePendingSelection(); }
      }

      // 1. Distribute state to registered StoreAdapters per the schema.
      //    Pre-distribution: validate the snapshot against the surface's
      //    schema; group by adapter; restore each.
      applyState(addr.target, addr.state, traversal);

      // 2. Mount the surface (Shell-level activeId).
      config.setActiveSurface(addr.target);

      // 3. Push the URL (gated by push option for popstate-driven activations).
      //
      // Slice 489 T1/N2 — ordering invariant: pushAddress (history.pushState)
      // MUST run BEFORE activateProjection's initial write (which fires a
      // synchronous history.replaceState). Reversed, the initial projection
      // write would overwrite the previous history entry with the new URL,
      // collapsing the back-button stack ("rail click then Back" would skip
      // the prior surface entirely). The same invariant applied when this
      // body lived in Shell.activateSurface pre-slice-492; it lives here now
      // because Navigation realization moved to the handler.
      const push = options?.push !== false;
      if (push) {
        const pending = addr.target === 'core.search-v3-surface' ? peekSearchV3Handoff() : null;
        pushAddress({
          kind: 'navigate',
          target: addr.target,
          state: pending?.origin === 'fresh' ? incomingState : addr.state,
        });
      }

      // 4. Activate the projector so subsequent store changes propagate to the URL.
      activateProjection(addr.target);
    },
  };
}

/**
 * Apply a state snapshot to the surface's registered stores. Bindings that
 * reference an unregistered storeId are silently dropped (per
 * surfaceSchemas.resolveSurfaceStateSchema posture).
 *
 * Validates + coerces the state through the surface's declared schema before
 * distribution. On validation failure: warn + skip restore (stores keep
 * defaults; surface still activates — graceful degradation).
 *
 * Body lifted verbatim (modulo signature) from the deleted
 * URLHydrator.applyState. Behavior is identical to ensure refresh-restore
 * semantics round-trip unchanged.
 */
function applyState(surfaceId: string, state: StateSnapshot, traversal = false): void {
  const schema = getSurfaceStateSchema(surfaceId);
  if (!schema) return;
  const validation = coerceAndValidate(state, schema.schema);
  if (!validation.ok) {
    console.warn(
      `[NavigationHandler] state validation failed for surfaceId="${surfaceId}":`,
      validation.errors,
    );
    return;
  }
  const validated = validation.value;
  const resolved = resolveSurfaceStateSchema(schema);
  const byStore = new Map<StoreAdapter, StateSnapshot>();
  for (const field of resolved.fields) {
    const value = validated[field.schemaPath.replace(/^\//, '')];
    if (value === undefined) {
      // Absent is a silence for every adapter but one (StoreAdapter.clearsOnTraversal): on a
      // traversal that adapter is still handed its — empty — bag, so it can read the absence as
      // "nothing is claimed" instead of keeping state the address contradicts.
      if (!traversal || field.adapter.clearsOnTraversal !== true) continue;
      if (!byStore.has(field.adapter)) byStore.set(field.adapter, {});
      continue;
    }
    const bag = byStore.get(field.adapter) ?? {};
    bag[field.storeKey] = value;
    byStore.set(field.adapter, bag);
  }
  for (const [adapter, bag] of byStore.entries()) {
    try {
      adapter.restore(bag);
    } catch (err) {
      console.warn(
        `[NavigationHandler] restore failed for storeId="${adapter.storeId}":`,
        err,
      );
    }
  }
}
