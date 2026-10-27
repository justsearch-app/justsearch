// SPDX-License-Identifier: Apache-2.0
/**
 * CorePlugin — Tempdoc 507 §3.3 — registers all core surfaces through
 * PluginRegistry with tier = CORE.
 *
 * This is the structural proof that the plugin contract is sufficient
 * for the app's own UI. Core surfaces use native import() — no SES
 * Compartment — but participate in the same lifecycle (register /
 * unregister / hot-reload) as third-party plugins.
 *
 * D5 (single core manifest): all core surfaces ship as one CORE-trust
 * plugin. Users swap individual surfaces via surfaceOverride, not by
 * uninstalling individual core plugins.
 */

import {
  PLUGIN_CONTRACT_VERSION,
  type PluginManifest,
  type PluginContribution,
  type PluginSurfaceContribution,
} from './plugin-types.js';

const CORE_SURFACES: PluginSurfaceContribution[] = [
  {
    id: 'core.library-surface',
    mountTag: 'jf-library-surface',
    labelKey: 'registry-surface.library-surface.label',
    descriptionKey: 'registry-surface.library-surface.description',
    audience: 'USER',
    placement: 'RAIL',
    consumes: {
      operations: ['core.reindex', 'core.add-watched-root', 'core.remove-watched-root', 'core.preview-excludes', 'core.apply-excludes'],
      resources: ['core.indexed-roots'],
    },
    splitPairing: { secondary: 'core.search-v3-surface' },
  },
  {
    id: 'core.help-surface',
    mountTag: 'jf-help-surface',
    labelKey: 'registry-surface.help-surface.label',
    descriptionKey: 'registry-surface.help-surface.description',
    audience: 'USER',
    // Tempdoc 578 §5.6 Phase 4 — Help is reference content, not a rail workspace surface. DEEPLINK here
    // matches the Java catalog (this FE re-declaration would otherwise override the wire's placement);
    // Help is reached via the rail's dedicated "?" affordance + URL/command-palette.
    placement: 'DEEPLINK',
  },
  // 569 §19 Phase 4 — the style-variations / skins gallery.
  {
    id: 'core.presentation-gallery-surface',
    mountTag: 'jf-presentation-gallery-surface',
    labelKey: 'registry-surface.presentation-gallery-surface.label',
    descriptionKey: 'registry-surface.presentation-gallery-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
  },
  // 569 §19 Phase 6 — the visual presentation editor.
  {
    id: 'core.presentation-editor-surface',
    mountTag: 'jf-presentation-editor-surface',
    labelKey: 'registry-surface.presentation-editor-surface.label',
    descriptionKey: 'registry-surface.presentation-editor-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
  },
  // Tempdoc 930 §19.3 F9(d): `core.governance-surface` is retired — the governance dashboard (its
  // view, controller, route and generator) is removed; the discipline-gate kernel itself stays.
  // Tempdoc 583 §D.3b — the API explorer: read-only projection of GET /api/meta/routes (the
  // self-describing route manifest). DEEPLINK dev/operator tool, off-rail.
  {
    id: 'core.api-explorer-surface',
    mountTag: 'jf-api-explorer-view',
    labelKey: 'registry-surface.api-explorer-surface.label',
    descriptionKey: 'registry-surface.api-explorer-surface.description',
    audience: 'DEVELOPER',
    placement: 'DEEPLINK',
  },
  // WP1.5a — the main Search window, keeping its established identity and saved state.
  {
    id: 'core.search-v3-surface',
    mountTag: 'jf-sv3-window',
    labelKey: 'registry-surface.search-v3-surface.label',
    descriptionKey: 'registry-surface.search-v3-surface.description',
    audience: 'USER',
    placement: 'RAIL',
    splitPairing: { secondary: 'core.library-surface' },
  },
  {
    id: 'core.brain-surface',
    mountTag: 'jf-brain-surface',
    labelKey: 'registry-surface.brain-surface.label',
    descriptionKey: 'registry-surface.brain-surface.description',
    audience: 'USER',
    placement: 'RAIL',
  },
  {
    // Tempdoc 855 §9.2 — Settings opens as a centered window over the stage (<jf-settings-window>),
    // not as a rail surface. It keeps a fixed bottom rail affordance; the placement flip must stay in
    // step with CoreSurfaceCatalog.java (the check-surface-composition LEG-2 parity gate).
    id: 'core.settings-surface',
    mountTag: 'jf-settings-surface',
    labelKey: 'registry-surface.settings-surface.label',
    descriptionKey: 'registry-surface.settings-surface.description',
    audience: 'USER',
    placement: 'MODAL',
  },
  {
    // Tempdoc 629 (remaining-work) — unified Security & Privacy surface (encryption control + at-rest
    // status), moved out of Settings.
    // Tempdoc 855 §5 item 1 / §9.3 — absorbed into the settings window as a member category (Privacy &
    // Trust). Placement flip must stay in step with CoreSurfaceCatalog.java (check-surface-composition
    // LEG-2 parity gate); deep-links keep working via the member→host alias redirect (§9.4).
    id: 'core.security-surface',
    mountTag: 'jf-security-surface',
    labelKey: 'registry-surface.security-surface.label',
    descriptionKey: 'registry-surface.security-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
  },
  {
    // Tempdoc 561 P-E — learned-memory (inspect / forget): the durable "what it knows" facts half.
    // Tempdoc 565 §26.D — the ACTIVITY half (presence inbox + run-in-background) folded into the one
    // window (the retrospective drawer's Inbox tab + inline `background` run-segments); this surface
    // is now facts-only ("what it knows" ≠ "what it did").
    id: 'core.memory-surface',
    mountTag: 'jf-memory-surface',
    labelKey: 'registry-surface.memory-surface.label',
    descriptionKey: 'registry-surface.memory-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
  },
  {
    id: 'core.browse-surface',
    mountTag: 'jf-browse-surface',
    labelKey: 'registry-surface.browse-surface.label',
    descriptionKey: 'registry-surface.browse-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
  },
  {
    id: 'core.health-surface',
    mountTag: 'jf-health-surface',
    labelKey: 'registry-surface.health-surface.label',
    descriptionKey: 'registry-surface.health-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
    // Tempdoc 571 — altitude (DIAGNOSTIC) flows from the single authority (the wire / Java catalog) and
    // is preserved through mergePluginSurfaceContributions; not re-declared here (no second authority).
    consumes: {
      operations: ['core.reindex', 'core.recover-component', 'core.clear-failed-jobs', 'core.export-diagnostics', 'core.bulk-reindex'],
    },
  },
  // Tempdoc 575 §17 Face B (the System Self-View / "Now") was a RAIL surface here; RETIRED by tempdoc
  // 578 Workstream A — its live-strip is folded into Health (<jf-system-self-view variant="strip">) and
  // a deep-link to core.system-self-view aliases to the System hub (RETIRED_SURFACE_ALIASES).
  // Search Thread S5b (the great retirement) — the standalone `core.search-surface` RAIL surface
  // (jf-search-surface) was here; RETIRED — its retrieve tier folded into `core.unified-chat-surface`
  // above (Search Thread S1-S5a). A deep-link to core.search-surface aliases to the one window
  // (RETIRED_SURFACE_ALIASES, same treatment as core.agent-surface / core.system-self-view).
  {
    id: 'core.logs-surface',
    mountTag: 'jf-log-surface',
    labelKey: 'registry-surface.logs-surface.label',
    descriptionKey: 'registry-surface.logs-surface.description',
    audience: 'OPERATOR',
    placement: 'DEEPLINK',
    // Tempdoc 571 — altitude (DIAGNOSTIC) flows from the wire authority; preserved through the merge.
  },
  // Tempdoc 560 §25/§26 — the core token-editor surface (`core.token-editor-surface` /
  // `jf-token-editor-surface`) is retired. The ONE token editor now ships as the bundled first-party
  // `token-editor` plugin (src/shell-v0/plugins/token-editor/), installed at boot in main.jsx.
  {
    id: 'core.command-palette',
    mountTag: 'jf-command-palette',
    // No registry-surface catalog entry yet → resolves via deriveTitleFromSurfaceId
    // ("Command Palette"). See observations.md (add authored label/description).
    labelKey: 'registry-surface.command-palette.label',
    descriptionKey: 'registry-surface.command-palette.description',
    audience: 'USER',
    placement: 'COMMAND',
  },
  {
    id: 'core.activity-surface',
    mountTag: 'jf-activity-surface',
    labelKey: 'registry-surface.activity-surface.label',
    descriptionKey: 'registry-surface.activity-surface.description',
    audience: 'USER',
    placement: 'DEEPLINK',
    // Tempdoc 571 — altitude (TRUST) flows from the wire authority; preserved through the merge.
  },
];

export const CORE_PLUGIN_ID = 'core';

/**
 * Tempdoc 521 §16.4 — the "Welcome to JustSearch" walkthrough was the
 * WalkthroughRegistry's first consumer. As of 548 §4.3(d) it is no longer
 * declared here imperatively: it ships as its own ContributionManifest
 * (`core.core-welcome`, installed at boot from Shell.ts), proving the
 * ContributionManifest is the canonical declaration root for a real
 * first-party feature. See `substrates/manifest/canonicalManifest.ts`.
 */

export function createCorePluginManifest(): PluginManifest {
  return {
    id: CORE_PLUGIN_ID,
    version: '1.0.0',
    displayName: 'JustSearch Core',
    contractVersion: PLUGIN_CONTRACT_VERSION,
    tagNamespace: CORE_PLUGIN_ID,
    capabilities: {
      surfaces: CORE_SURFACES,
    },
    register: (): PluginContribution => ({
      surfaceContributions: CORE_SURFACES.map((s) => ({ contribution: s })),
    }),
    unregister: () => {
      // Core plugin unregister is a lifecycle proof — if called, it means
      // PluginRegistry.uninstall('core') was invoked. Surface catalog
      // cleanup is handled by the registry's uninstall path.
    },
  };
}
