// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 491 §9.D Phase E (C0) — typed view-factory brand for ConversationShape mounts.
 *
 * Mirrors the slice 478 §4.A SurfaceFactory pattern (`api/registry/SurfaceCatalogClient.ts`)
 * verbatim, with the shape ref as the type parameter:
 *
 * 1. **Type-level brand** — the `__viewBrand: symbol` field prevents accidental
 *    constructions like `{mount: () => el}` from satisfying the ViewFactory type.
 *    Consumers can READ the symbol off a real factory but the type system rejects
 *    objects without the field.
 *
 * 2. **Runtime brand verification** — `mountView()` checks WeakSet membership.
 *    The WeakSet is module-private; a consumer with only a factory reference
 *    cannot insert into it. So stealing `__viewBrand` from a real factory and
 *    forging `{__viewBrand: stolen, mount: () => evilEl}` passes the type check
 *    (Symbol matches) but FAILS the runtime verification (forged factory not in WeakSet).
 *
 * The factory's mount() returns an HTMLElement that the chrome can append to a
 * shape-mount slot. Per §9.E A2: SurfaceFactory's brand pattern transplants here
 * verbatim; HMR survival is a single-test residual check during C0 dev work.
 */

import { canonicalComponentTag } from '../renderers/componentIdentity.js';
import type { ConversationShapeRef } from '../../api/types/conversation-shape.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';

/**
 * Mount options for a ViewFactory. `apiBase` is the historical option;
 * `host_` was added by the 507/508 merge follow-up so chat-shape inner
 * views (FreeChatView, AgentView, etc.) receive the same PluginHostApi
 * the outer `<jf-chat-shape-mount>` wrapper has. Without this, host-
 * aware code paths inside those views fall back to direct imports
 * because the inner element never sees `host_`.
 */
export interface ViewMountOpts {
  readonly apiBase?: string;
  readonly host_?: PluginHostApi;
}

const VIEW_DISPATCH_BRAND: unique symbol = Symbol('jf.view-factory.dispatch-brand');

/**
 * Opaque dispatch token for a typed view mount. The
 * {@link viewFactoryRegistry} is the only mint-site; consumers cannot
 * construct a ViewFactory because they cannot reach the module-private
 * WeakSet of valid factories.
 */
export interface ViewFactory<S extends ConversationShapeRef> {
  /** Type-level brand — see file doc comment. */
  readonly __viewBrand: typeof VIEW_DISPATCH_BRAND;
  /** The shape this factory mounts. */
  readonly shapeRef: S;
  /**
   * Mount the view as a fresh HTMLElement. Throws if the registered custom
   * element cannot be constructed.
   */
  mount(opts?: ViewMountOpts): HTMLElement;
}

/** Module-private WeakSet of catalog-minted factories. */
const VALID_VIEW_FACTORIES: WeakSet<ViewFactory<ConversationShapeRef>> = new WeakSet();

/**
 * Mint a typed view factory for a shape. The returned factory is added to the
 * module-private WeakSet; only this function can produce factories that pass
 * {@link mountView}'s runtime verification.
 */
export function stampViewFactory<S extends ConversationShapeRef>(
  shapeRef: S,
  mountTag: string,
): ViewFactory<S> {
  const factory: ViewFactory<S> = {
    __viewBrand: VIEW_DISPATCH_BRAND,
    shapeRef,
    mount(opts?: ViewMountOpts): HTMLElement {
      const ctor = customElements.get(canonicalComponentTag(mountTag));
      if (!ctor) {
        throw new Error(
          `ViewFactory.mount: custom element '${mountTag}' is not registered for shape '${shapeRef}'. ` +
            `Ensure the typed view's module is imported (side-effect register) before mount.`,
        );
      }
      const el = new ctor() as HTMLElement;
      if (opts?.apiBase !== undefined) {
        el.setAttribute('api-base', opts.apiBase);
      }
      // 507/508-merge T2.4 — forward host_ to the inner view. Views
      // that declare host_ as a Lit reactive property (FreeChatView,
      // AgentView, et al.) receive the PluginHostApi instance the
      // outer wrapper was given.
      if (opts?.host_ !== undefined) {
        (el as unknown as { host_?: PluginHostApi }).host_ = opts.host_;
      }
      return el;
    },
  };
  VALID_VIEW_FACTORIES.add(factory);
  return factory;
}

/**
 * Verify the factory's brand + WeakSet membership before calling mount().
 *
 * Throws `Error` if the factory is forged (Symbol matches but not in WeakSet)
 * or if the type-level brand is missing.
 */
export function mountView<S extends ConversationShapeRef>(
  factory: ViewFactory<S>,
  opts?: ViewMountOpts,
): HTMLElement {
  if (factory.__viewBrand !== VIEW_DISPATCH_BRAND) {
    throw new Error(
      'mountView: factory __viewBrand symbol mismatch — only stampViewFactory mints valid factories',
    );
  }
  if (!VALID_VIEW_FACTORIES.has(factory)) {
    throw new Error(
      'mountView: factory not in the catalog WeakSet — likely forged or mutated post-mint',
    );
  }
  return factory.mount(opts);
}

// ============================================================
// Test-only helpers
// ============================================================

/** Test helper — peek the module-private brand symbol. */
export function __getViewBrandForTest(): symbol {
  return VIEW_DISPATCH_BRAND;
}

/** Test helper — check WeakSet membership without exposing the set itself. */
export function __isInValidViewFactoriesForTest(
  factory: ViewFactory<ConversationShapeRef>,
): boolean {
  return VALID_VIEW_FACTORIES.has(factory);
}
