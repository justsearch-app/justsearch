// SPDX-License-Identifier: Apache-2.0
/**
 * HostApiImpl — Tempdoc 507 §3.1 implementation.
 *
 * Builds a PluginHostApi instance that delegates to existing framework
 * internals. Trust-tier attenuation (§3.4) is applied at construction
 * time via the factory function `createHostApi()`.
 */

import type {
  PluginHostApi,
  PluginTrustTier,
  SurfacePortHandler,
  NotificationOptions,
  CatalogEntry,
  Unsubscribe,
} from './plugin-types.js';

// 548 §4.2 Increment A — host.ai is now its own capability module (first step of
// dissolving the createHostApi God-Object into per-capability modules behind the
// unchanged PluginHostApi facade).
import { createPluginAI } from './capabilities/ai.js';
import { createPlatformApi } from './capabilities/platform.js';
import { createDataApi } from './capabilities/data.js';
import { createSelectionApi } from './capabilities/selection.js';
import { createRegistrationApi } from './capabilities/registration.js';
import { createUiApi } from './capabilities/ui.js';
import { authorizedFetch } from '../api/authorizedFetch.js';
import {
  subscribeInspector,
  getInspectorState,
} from '../state/inspectorState.js';
import {
  listSurfaces as catalogListSurfaces,
  onSurfaceCatalogChange,
} from '../../api/registry/SurfaceCatalogClient.js';
import {
  listResources as catalogListResources,
} from '../../api/registry/ResourceCatalogClient.js';
import {
  listOperations as catalogListOperations,
} from '../../api/registry/OperationCatalogClient.js';
import {
  getPluginSetting,
  setPluginSetting,
  subscribePluginSetting,
} from '../state/UserStateDocument.js';
import {
  getSearchState,
  setSearchApiBase,
  setQuery,
  submitSearch,
  subscribeSearch,
  hitToSelectedItem,
} from '../state/searchState.js';
import {
  getPinnedSearches,
  subscribePinnedSearches,
  pinSearch,
  unpinSearch,
  isPinned,
  recordRun,
} from '../state/pinnedSearchState.js';
import {
  getFilters,
  subscribeFilters,
  setFilterRange,
  hasActiveFilter,
} from '../state/searchFiltersState.js';
import { createThemeApi } from './capabilities/theme.js';
import {
  getUserConfig,
  subscribeUserConfig,
  setSurfaceVisibility,
  setSurfaceOrder,
  clearAllLayoutOverrides,
  setActiveLayoutId,
} from '../state/userConfigState.js';
import { formatRelative } from '../utils/relativeTime.js';

// ---------------------------------------------------------------------------
// Dependencies injected at construction (framework-internal singletons)
// ---------------------------------------------------------------------------

export interface HostApiDeps {
  apiBase: string;
  registerSurfacePort: (pluginId: string, id: string, handler: SurfacePortHandler) => void;
  navigate?: (target: string, state?: Record<string, unknown>) => void;
  navigateBack?: () => void;
  navigateForward?: () => void;
  showNotification?: (message: string, options?: NotificationOptions) => void;
  registerCommand?: (id: string, label: string, handler: () => void, labelKey?: string) => void;
  registerKeybinding?: (key: string, handler: () => void) => void;
}

// ---------------------------------------------------------------------------
// Factory (tempdoc 507 §3.4)
// ---------------------------------------------------------------------------

export function createHostApi(
  pluginId: string,
  tier: PluginTrustTier,
  deps: HostApiDeps,
): PluginHostApi {
  // 548 §4.2 — createHostApi is now a thin assembler: each capability is its own
  // module under ./capabilities/, and trust attenuation lives inside the module
  // that owns it (521 §2.3 composition — the per-tier variant is selected by
  // `tier`, never an `if (tier)` in a method body). The read-only delegations
  // below (navigation/discovery/settings/search/inspector/theme/layout/
  // utilities) carry no attenuation and stay inline as plain pass-throughs.
  const api: PluginHostApi = {
    // -- Identity (flat at root for convenience) --
    installedTagNamespace: pluginId,
    pluginId,
    trustTier: tier,

    registration: createRegistrationApi(tier, pluginId, deps),

    data: createDataApi(tier, deps.apiBase),

    navigation: {
      navigate: (target: string, state?: Record<string, unknown>) => deps.navigate?.(target, state),
      navigateBack: () => deps.navigateBack?.(),
      navigateForward: () => deps.navigateForward?.(),
    },

    ui: createUiApi(tier, deps),

    discovery: {
      listOperations: (_filter?: Record<string, unknown>): CatalogEntry[] => {
        return catalogListOperations().map((op) => ({
          id: op.id,
          displayName: op.presentation?.labelKey ?? op.id,
        }));
      },
      listResources: (_filter?: Record<string, unknown>): CatalogEntry[] => {
        return catalogListResources().map((r) => ({
          id: r.id,
          displayName: r.presentation?.labelKey ?? r.id,
        }));
      },
      listSurfaces: (_filter?: Record<string, unknown>): CatalogEntry[] => {
        return catalogListSurfaces().map((s) => ({
          id: s.id,
          displayName: s.id,
        }));
      },
      getSystemStatus: async (): Promise<unknown> => {
        const res = await authorizedFetch(`${deps.apiBase}/api/status`);
        return res.json();
      },
    },

    settings: {
      getSetting: (key: string): unknown => getPluginSetting(pluginId, key),
      setSetting: (key: string, value: unknown) => setPluginSetting(pluginId, key, value),
      subscribeSetting: (key: string, handler: (value: unknown) => void): Unsubscribe => subscribePluginSetting(pluginId, key, handler),
    },

    search: {
      subscribeSearch: (handler) => subscribeSearch((s) => handler(s as unknown as import('./plugin-types.js').SearchSnapshot)),
      getSearchState: () => getSearchState() as unknown as import('./plugin-types.js').SearchSnapshot,
      setQuery: (q: string) => setQuery(q),
      submitQuery: () => submitSearch(),
      setSearchApiBase: (base: string) => setSearchApiBase(base),
      subscribePinnedSearches: (handler) => subscribePinnedSearches((pins) => handler(pins as readonly import('./plugin-types.js').SearchPinSnapshot[])),
      getPinnedSearches: () => getPinnedSearches() as readonly import('./plugin-types.js').SearchPinSnapshot[],
      pinSearch: (query, filterSpec?) => pinSearch(query, filterSpec as import('../state/UserStateDocument.js').SearchFilterSpec | undefined) as import('./plugin-types.js').SearchPinSnapshot | null,
      unpinSearch: (id: string) => unpinSearch(id),
      isPinned: (query: string) => isPinned(query),
      recordSearchRun: (query: string, totalHits: number) => recordRun(query, totalHits),
      subscribeFilters: (handler) => subscribeFilters((f) => handler(f as import('./plugin-types.js').SearchFilterSnapshot)),
      getFilters: () => getFilters() as import('./plugin-types.js').SearchFilterSnapshot,
      setFilterRange: (fromMs?: number, toMs?: number) => setFilterRange(fromMs, toMs),
      hasActiveFilter: () => {
        const f = getFilters();
        return hasActiveFilter(f);
      },
      hitToSelectedItem: (hit) => {
        const item = hitToSelectedItem(hit as unknown as import('../state/searchState.js').SearchHit);
        return { id: item.id, title: item.title, path: item.path, kind: item.kind };
      },
    },

    inspector: {
      subscribeInspector: (handler) => subscribeInspector((s) => handler(s as unknown as import('./plugin-types.js').InspectorSnapshot)),
      getInspectorState: () => getInspectorState() as unknown as import('./plugin-types.js').InspectorSnapshot,
    },

    selection: createSelectionApi(tier, pluginId),

    theme: createThemeApi(tier, pluginId),

    layout: {
      subscribeUserConfig: (handler) => subscribeUserConfig((cfg) => handler(cfg as unknown as import('./plugin-types.js').UserConfigSnapshot)),
      getUserConfig: () => getUserConfig() as unknown as import('./plugin-types.js').UserConfigSnapshot,
      setSurfaceVisibility: (surfaceId: string, visible: boolean) => setSurfaceVisibility(surfaceId, visible),
      setSurfaceOrder: (order: string[]) => setSurfaceOrder(order),
      clearAllLayoutOverrides: () => clearAllLayoutOverrides(),
      setActiveLayoutId: (layoutId: string | undefined) => setActiveLayoutId(layoutId),
      onSurfaceCatalogChange: (handler: () => void): Unsubscribe => onSurfaceCatalogChange(handler),
    },

    utilities: {
      formatRelativeTime: (isoString: string): string => {
        const ms = new Date(isoString).getTime();
        return Number.isFinite(ms) ? formatRelative(ms) : isoString;
      },
    },

    platform: createPlatformApi(tier),

    // Tempdoc 508 §11.4 / §13.4 — host.ai sub-interface.
    ai: createPluginAI(tier, deps.apiBase),
  };

  return api;
}
