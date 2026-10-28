// SPDX-License-Identifier: Apache-2.0
/**
 * Renderer dispatch + registry store for the Shell V0 Lit JSON Forms
 * binding.
 *
 * Extracted from `registry.ts` to break the renderer import cycle
 * (tempdoc 530 UI-cycle gate). `ArrayControl`, `ObjectControl`, and
 * `layoutDispatch` need `dispatchRenderer` to instantiate child
 * renderers; importing it from `registry.ts` (which in turn imports
 * those very modules for their testers) formed a cycle. This leaf holds
 * the registry store + dispatch and imports only leaf types
 * (`rendererTypes`, `userConfig`) and `@jsonforms/core`.
 *
 * Registration model: each control/layout module SELF-REGISTERS its
 * `(tester, tag)` via `registerRenderer` at module load (a side effect
 * next to its `customElements.define`), mirroring the sibling
 * `resourceRegistryDefaults` / `registerXUiRenderer` patterns. So
 * dispatch works whenever the relevant renderer module is imported —
 * not only when the `registry.ts` aggregator is loaded. (A prior
 * "registry.ts populates the store" model left dispatch empty for any
 * import path that didn't pull in the aggregator, e.g. the per-renderer
 * render tests.)
 */

import { canonicalComponentTag } from './componentIdentity.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import type { RendererEntry, RendererTester } from './rendererTypes.js';
import type { RendererUserConfig } from './userConfig.js';

/**
 * The renderer registry, populated by per-renderer self-registration
 * (see `registerRenderer`). Reads before a given renderer's module has
 * loaded won't see it, but dispatch happens at render time — after the
 * imported renderer modules have run their registration side effects.
 */
const _registry: RendererEntry[] = [];

/**
 * Register a renderer's `(tester, tag)`. Idempotent by tag: a repeated
 * registration (re-import / HMR) replaces the prior entry rather than
 * duplicating it, so the registry length stays stable.
 */
export function registerRenderer(tester: RendererTester, tag: string): void {
  const existing = _registry.findIndex((e) => e.tag === tag);
  if (existing >= 0) {
    _registry[existing] = { tester, tag };
  } else {
    _registry.push({ tester, tag });
  }
}

/** The current renderer registry. */
export function getRendererRegistry(): RendererEntry[] {
  return _registry;
}

/**
 * Dispatch: find the registered renderer for the given uischema +
 * schema. Returns the custom-element tag, or null when no renderer
 * matches.
 *
 * Resolution order (slice 3a.1.7):
 *  1. If `userConfig.rendererOverride` declares a tag for this
 *     dispatch point (keyed by `renderer:<scope-or-empty>`),
 *     return that tag directly. The dispatcher does not validate
 *     that the declared tag is registered — the host is
 *     responsible for honoring user choices and surfacing
 *     "renderer not available" UX if the tag doesn't exist.
 *  2. Otherwise, fall through to rank-based dispatch: highest
 *     tester rank wins; ties broken by registration order.
 *
 * Used by Object and Array controls to instantiate child renderers
 * for nested schema properties / array items.
 */
export function dispatchRenderer(
  uischema: UISchemaElement,
  schema: JsonSchema,
  userConfig?: RendererUserConfig,
): string | null {
  const override = lookupRendererOverride(uischema, userConfig);
  if (override) {
    return canonicalComponentTag(override);
  }
  let bestRank = -1;
  let bestTag: string | null = null;
  for (const entry of getRendererRegistry()) {
    const rank = entry.tester(uischema, schema);
    if (rank > bestRank) {
      bestRank = rank;
      bestTag = entry.tag;
    }
  }
  return bestRank >= 0 && bestTag !== null ? canonicalComponentTag(bestTag) : null;
}

/**
 * Resolve a userConfig render override for the given uischema. Keys
 * tried in order:
 *   1. `renderer:<scope>` if the uischema has a `scope` property
 *      (Control elements typically do).
 *   2. `renderer:` (empty key) for surface-wide overrides.
 *
 * Returns the override tag, or null if no override matches.
 */
function lookupRendererOverride(
  uischema: UISchemaElement,
  userConfig: RendererUserConfig | undefined,
): string | null {
  if (!userConfig?.rendererOverride) {
    return null;
  }
  const scope = (uischema as { scope?: string }).scope;
  if (scope) {
    const scopedKey = `renderer:${scope}`;
    if (userConfig.rendererOverride[scopedKey]) {
      return userConfig.rendererOverride[scopedKey];
    }
  }
  if (userConfig.rendererOverride['renderer:']) {
    return userConfig.rendererOverride['renderer:'];
  }
  return null;
}
