// SPDX-License-Identifier: Apache-2.0
/**
 * CatalogResolver — FE-side intent resolution over in-memory catalog data
 * (tempdoc 499 §4.6).
 *
 * Resolves surface and operation IDs by calling {@link resolveAgainstCatalog}
 * against the live catalog client data. Both catalogs are already fetched and
 * cached in memory at boot time; resolution is synchronous.
 *
 * Stale-catalog note: if the catalog hasn't been refreshed since boot and
 * an unresolved result might be a false negative (ID exists on backend but
 * FE hasn't seen it), the caller can trigger a catalog refresh via
 * bootSurfaceRegistry/refreshOperationCatalog and retry. This module
 * keeps resolution synchronous and does not auto-refresh.
 */

import { canonicalSurfaceId } from './surfaceIdentity.js';
import { listSurfaces } from '../../api/registry/SurfaceCatalogClient.js';
import { listOperations } from '../../api/registry/OperationCatalogClient.js';
import type { AliasMap, CatalogEntry, ResolutionResult, SynonymMap } from './resolution.js';
import { resolveAgainstCatalog } from './resolution.js';
import { loadPromotedAliases } from './promotedAliases.js';

/**
 * Tempdoc 561 surface tier: back-compat aliases for retired surfaces. The standalone
 * `core.agent-surface` folded into the one interaction window (`core.unified-chat-surface`);
 * old deeplinks/bookmarks resolve into it rather than dead-ending. Merged into every
 * `resolveSurface` call so it survives boot/`setSurfaceAliases` ordering (a promoted alias for
 * the same id can still override it).
 */
const RETIRED_SURFACE_ALIASES: AliasMap = {
  'core.unified-chat-surface': { target: 'core.search-v3-surface', reason: 'renamed' },
  'core.agent-surface': { target: 'core.search-v3-surface', reason: 'renamed' },
  // Tempdoc 578 Workstream A — the standalone System Self-View ("Now") is retired; its live-strip folded
  // into Health. A deep-link redirects to the System hub, whose first member (Health) opens by default —
  // exactly where the strip now lives, so no member-tab intent is needed.
  'core.system-self-view': { target: 'core.system-surface', reason: 'renamed' },
  // Search Thread S5b (the great retirement) — the standalone Search rail surface is retired; its
  // retrieve tier folded into the one window. A deep-link/bookmark to core.search-surface resolves
  // into it rather than dead-ending.
  'core.search-surface': { target: 'core.search-v3-surface', reason: 'renamed' },
};

/**
 * Tempdoc 571 §11 / 578 — host/member composition: a member surface's home is its host, so a
 * deep-link to a member resolves to the host (which opens the member's tab). Derived from the live
 * catalog's `members` declarations — membership is the single home-authority, so no per-member alias
 * is hand-maintained (cf. {@link RETIRED_SURFACE_ALIASES}). The redirect's `originalId` carries the
 * member id, which the host's `<jf-surface-tabs>` reads to preselect the right tab.
 */
function memberHostAliases(): AliasMap {
  const out: Record<string, { target: string; reason: 'renamed' }> = {};
  for (const host of listSurfaces()) {
    for (const memberId of host.members ?? []) {
      out[memberId] = { target: host.id, reason: 'renamed' };
    }
  }
  return out;
}

let surfaceAliases: AliasMap = {};
let operationAliases: AliasMap = {};

export function refreshPromotedAliases(): void {
  const promoted = loadPromotedAliases();
  surfaceAliases = { ...surfaceAliases, ...promoted };
  operationAliases = { ...operationAliases, ...promoted };
}

const defaultSynonyms: SynonymMap = {
  reboot: 'restart',
  delete: 'remove',
  find: 'search',
  lookup: 'search',
  query: 'search',
  prefs: 'settings',
  preferences: 'settings',
  config: 'settings',
  docs: 'library',
  files: 'library',
};

export function getSurfaceAliases(): AliasMap {
  return surfaceAliases;
}

export function setSurfaceAliases(aliases: AliasMap): void {
  surfaceAliases = aliases;
}

export function setOperationAliases(aliases: AliasMap): void {
  operationAliases = aliases;
}

function humanizeId(id: string): string {
  return id.replace(/^core\./, '').replace(/[.\-]/g, ' ');
}

export function resolveSurface(rawId: string): ResolutionResult {
  const surfaces = listSurfaces();
  if (surfaces.length === 0) {
    // Catalog not yet booted — pass through to isKnownSurface fallback
    return { status: 'resolved', id: rawId };
  }
  // Tempdoc 571 §11 / 578 — member→host redirect MUST take precedence over exact-match: a member
  // (e.g. core.browse-surface) is still a real catalog entry (DEEPLINK), so resolveAgainstCatalog
  // would exact-match it to ITSELF and mount it standalone, bypassing its host. Intercept first so a
  // member deep-link lands on its host (which opens the member's tab via the carried originalId).
  const memberHost = memberHostAliases()[rawId];
  if (memberHost) {
    // Pure: just return the redirect. The member-tab hand-off (requestMemberTab) is fired at the
    // navigation-apply step (the intentRouter onRedirect hook wired in Shell), so a speculative
    // resolveSurface call cannot leave a stale tab intent (578 post-review fix #2).
    return { status: 'redirected', id: memberHost.target, originalId: rawId, reason: memberHost.reason };
  }
  const entries: CatalogEntry[] = surfaces.map(s => ({
    id: s.id,
    label: humanizeId(s.id),
  }));
  // Merge alias layers (lowest precedence first): static retired-surface back-compat, then promoted
  // aliases. (Member→host redirects are handled above, ahead of exact-match.)
  const aliases: AliasMap = Object.fromEntries(
    Object.entries({ ...RETIRED_SURFACE_ALIASES, ...surfaceAliases })
      .map(([id, alias]) => [id, { ...alias, target: canonicalSurfaceId(alias.target) }]),
  );
  return resolveAgainstCatalog(rawId, entries, aliases, 'surface', defaultSynonyms);
}

export function resolveOperation(rawId: string): ResolutionResult {
  const operations = listOperations();
  if (operations.length === 0) {
    return { status: 'resolved', id: rawId };
  }
  const entries: CatalogEntry[] = operations.map(o => ({
    id: o.id,
    label: humanizeId(o.id),
  }));
  return resolveAgainstCatalog(rawId, entries, operationAliases, 'operation', defaultSynonyms);
}
