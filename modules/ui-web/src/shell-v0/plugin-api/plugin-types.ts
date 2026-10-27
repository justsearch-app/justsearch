// SPDX-License-Identifier: Apache-2.0
/**
 * Shell V0 Plugin API — V1 contract types.
 *
 * Per slice 3a.1 §"Slice 3a.1" Phase 7: Stage 3a ships a plugin
 * skeleton (no loader, no signing). Plugins are statically compiled
 * into the bundle for V1; the loader + signing surfaces ship in V1.5+
 * (see archive/source-tempdocs/421-extensibility.md).
 *
 * The contract here is the **shape** plugins must conform to. The
 * registry keeps a map from plugin id → manifest; the host
 * iterates the registry to dispatch plugin-defined surfaces.
 */

// §4.1/§4.3 anti-drift: the trust/audience vocabulary has ONE authority — the Java enums,
// projected to this GENERATED file (do not hand-author a parallel mirror).
import type { Audience, TrustTier } from '../../api/generated/registry-enums.generated.js';

/**
 * The plugin contract version this V1.1 host accepts. Plugins
 * declare their target version in `manifest.contractVersion`; the
 * registry validates compatibility at install time.
 *
 * Comparison rule (slice 3a.1.6): exact major + ≥minor. Host 1.1
 * accepts plugin 1.0 (with deprecation warning) or 1.1; rejects
 * 0.x / 2.x. Plugins authored against 1.0 work as register-only
 * (no `unregister`); the registry's `uninstall` path treats absent
 * `unregister` as a no-op teardown.
 */
export const PLUGIN_CONTRACT_VERSION = '1.1';

/**
 * V1.1 plugin manifest. Slice 3a.1.6 added the V1.1 fields
 * (`contractVersion`, `tagNamespace`, optional `unregister`) to lock
 * plugin lifecycle before plugin authors couple to V1's
 * register-only surface. See foundation-correctness audit Concern 3.
 *
 *  - id: stable identifier; used by the registry as the key.
 *  - version: semver; the host can decline to load if range mismatches.
 *  - displayName: human-facing label.
 *  - capabilities: feature-detect-driven advertisements; see below.
 *  - register(host): one-shot setup hook; the plugin registers its
 *    custom elements and surface-port handlers via the host.
 *  - contractVersion (V1.1): plugin contract version this plugin
 *    targets. Validated at install time against
 *    `PLUGIN_CONTRACT_VERSION`.
 *  - tagNamespace (V1.1): custom-element tag prefix this plugin
 *    uses. Must equal `id` exactly. Validated at install time.
 *  - unregister (V1.1, optional): teardown hook called by
 *    `uninstall(id)`. Plugins authored at V1 (no `unregister`)
 *    work unchanged; `uninstall` treats absent hook as a no-op.
 */
export interface PluginManifest {
  id: string;
  version: string;
  displayName: string;
  capabilities: PluginCapabilities;
  register: PluginRegisterFn;

  // V1.1 additions (slice 3a.1.6):
  contractVersion: string;
  tagNamespace: string;
  unregister?: PluginUnregisterFn;

  /**
   * §25.ζ#5 — lifecycle hooks distinct from `register` / `unregister`.
   * - `register` / `unregister`: one-time module-load and tear-down.
   * - `activate` / `deactivate`: every time the plugin transitions
   *   between active and inactive states (e.g., enabling/disabling a
   *   profile that scopes this plugin). First-party always-on plugins
   *   typically omit these — `register` covers their needs.
   * Mirrors ContributionManifest.lifecycle (§13.2.3) so the future
   * unified-manifest migration is field-compatible.
   */
  activate?: () => void | Promise<void>;
  deactivate?: () => void | Promise<void>;

  /**
   * Per-Category contract version map (slice 3a-1-8 Phase 6, optional).
   *
   * V1 plugins declare {@link contractVersion} (single string for the plugin
   * Category). Plugins that consume wire-Category types additionally declare
   * `contractVersions: { wire: "0.1" }`; the host's
   * `PluginRegistry.installFromHandshake` validates each declared Category
   * against the handshake's `serverCapabilities.contractVersions` map.
   *
   * Forward-compat: V1.1 plugins declaring only the legacy
   * {@link contractVersion} string continue to work (legacy single-Category
   * declaration treated as plugin-Category only). V2+ plugins declaring
   * `contractVersions` get multi-Category validation.
   */
  contractVersions?: Record<string, string>;

  /**
   * Slice 471 / 449 phase 12 — i18n translations for the plugin's
   * declared `labelKey` / `descriptionKey` strings (and any custom keys
   * the plugin's surfaces / operations consume). Keyed by locale code
   * (e.g., `'en'`, `'de'`); each value is a `{ key: translation }`
   * map merged into the existing resource catalog at install time
   * via `registerCatalogEntries`.
   *
   * Existing catalog keys are NOT overwritten — server-fetched
   * translations win over plugin-supplied ones (plugins can ship
   * defaults; the host catalog is authoritative for shared keys).
   *
   * V1.5 alpha shape: simple flat record. V1.5.1 polish may add
   * pluralization / interpolation support (tempdoc 742: the earlier
   * Lingui dependency was removed entirely; any future support here
   * is catalog-native, not Lingui-based).
   */
  translations?: Record<string, Record<string, string>>;

  /**
   * Tempdoc 507 §6 Phase 4 — JSON Schema fragment describing the
   * plugin's user-configurable settings. The Settings surface renders
   * this via `<jf-form>`. Config is persisted in UserStateDocument
   * under a per-plugin namespace (`pluginSettings.<manifest.id>`).
   */
  settingsSchema?: Record<string, unknown>;
}

// ---------------------------------------------------------------------------
// §4.1 unified declaration model — the FE projection of the ONE declaration
// the backend app-agent-api carries.
// ---------------------------------------------------------------------------

/**
 * Trust tier + audience — re-exported from the GENERATED single authority
 * (`registry-enums.generated.ts`, emitted from the Java enums by
 * RegistryEnumsTsGenerationTest). NOT hand-authored: a value added to the backend
 * `TrustTier`/`Audience` enum regenerates these and a stale copy fails the build
 * (tempdoc 560 §4.1/§4.3 anti-drift — one lattice/vocabulary spanning both processes).
 */
export type SubstrateTrustTier = TrustTier;
export type SubstrateAudience = Audience;

/** Provenance axis — mirror of the backend `Provenance` record (trust + origin + version). */
export interface SubstrateProvenance {
  trustTier: SubstrateTrustTier;
  vendor: string;
  version: string;
}

/** Consumer-hook axis — mirror of the backend `ConsumerHook.Realized` (a live consumer that receives this declaration). */
export interface SubstrateConsumerHook {
  consumerId: string;
  audience: SubstrateAudience;
}

/**
 * The shared cross-cutting axes that <b>every</b> declaration carries — the FE
 * mirror of the backend {@code Declaration → Provenanced → ConsumerDeclaring}
 * hierarchy (tempdoc 560 §4.1). The backend base in
 * {@code io.justsearch.agent.api.registry} is the authority; this is the FE's
 * structural position for the same axes, so a FE manifest and a backend
 * declaration describe the same thing in the same shape (per-kind payloads stay
 * distinct — the FE manifest's executable {@code register} hook, the backend
 * Operation's policy, etc. are NOT lifted here).
 */
export interface SubstrateDeclaration {
  id: string;
  presentation: { label: string; description?: string };
  provenance: SubstrateProvenance;
  audience: SubstrateAudience;
  consumers: SubstrateConsumerHook[];
}

/**
 * A Plugin declaration — the FE projection of the backend {@code Plugin} Manifest
 * tier onto the one declaration model. Same axes, none of the FE runtime payload
 * ({@link PluginManifest.register} etc.). Produced by {@code pluginDeclaration()}.
 */
export type PluginDeclaration = SubstrateDeclaration;

/**
 * Capabilities a plugin advertises to the host. Each axis is optional;
 * absence means the plugin does not contribute that axis. The host
 * iterates the registry, skipping plugins that don't advertise the
 * axis it's currently dispatching.
 *
 * V1 axes:
 *  - customElementTags: list of tags the plugin registers via
 *    `customElements.define` during `register()`. The host uses this
 *    list to surface plugin-defined elements in the dispatcher.
 *  - surfacePorts: list of surface-port ids the plugin contributes
 *    (e.g., 'health.condition', 'workspace.editor'). Surface ports
 *    are the V0 extension axis — a host slot that asks "which plugin
 *    renders this view?".
 */
export interface PluginCapabilities {
  customElementTags?: string[];
  surfacePorts?: string[];
  /**
   * Slice 449 phase 12 — Surface Manifest entries the plugin
   * contributes. Each entry is a `core.<id>`-shaped catalog row that
   * the host's SurfaceCatalog merges with `CoreSurfaceCatalog` at
   * boot. Audience floor enforcement (per slice 449 §0 D2):
   *  - TrustTier CORE / TRUSTED_PLUGIN -> Audience.USER floor
   *  - TrustTier UNTRUSTED_PLUGIN -> Audience.OPERATOR floor
   * The host applies the floor at registration time; declared
   * entries with a lower-than-floor audience are silently
   * promoted (not rejected — registration shouldn't fail on a
   * misconfigured audience).
   */
  surfaces?: PluginSurfaceContribution[];
}

/**
 * Plugin-contributed Surface Manifest entry. Mirrors the host-side
 * `Surface` record shape but uses string ids (the host turns these
 * into `SurfaceRef` value-types and validates with `SurfaceAreaValidator`
 * before merging into the live catalog).
 */
export interface PluginSurfaceContribution {
  /** Catalog id, e.g., `acme-tools.dashboard-surface`. */
  id: string;
  /** Lit custom-element tag name the plugin registers. */
  mountTag: string;
  /** I18n keys for label + description. Plugin owns the catalog. */
  labelKey: string;
  descriptionKey: string;
  /** Declared audience; the host promotes to floor when needed. */
  audience: 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';
  /** Where in the chrome the surface mounts. */
  placement:
    | 'COMMAND'
    | 'RAIL'
    | 'STAGE'
    | 'HUD'
    | 'STATUS'
    | 'DRAWER'
    | 'MODAL'
    | 'DEEPLINK'
    | 'HEADLESS_AGENT_TOOL';
  /**
   * Tempdoc 571 — the surface's altitude (home + core-vs-plugin eligibility). A plugin
   * MAY declare PRODUCT / DIAGNOSTIC / TOOL; `TRUST` is CORE-only and is clamped to
   * PRODUCT for non-CORE provenance at merge time (`mergePluginSurfaceContributions`),
   * the runtime completion of the `surface-altitude` gate's `TRUST ⟹ CORE` foreclosure.
   * Absent ⇒ PRODUCT (the benign default).
   */
  altitude?: 'PRODUCT' | 'DIAGNOSTIC' | 'TRUST' | 'TOOL';
  /** Operations / Resources / Prompts / DiagnosticChannels / ConversationShapes consumed. */
  consumes?: {
    resources?: string[];
    operations?: string[];
    prompts?: string[];
    diagnosticChannels?: string[];
    // Tempdoc 560 §28.G — a `jf-chat-shape-mount` surface declares the shape it hosts here; the
    // chrome derives the mount's `shape-id` from `consumes.conversationShapes[0]`.
    conversationShapes?: string[];
  };
  /**
   * Tempdoc 521 §22 Phase D — declarative split pairing. When this
   * surface is mounted as the primary pane of `core.split` (or any
   * layout with `stage.exclusive === false`) AND the user has not
   * picked an explicit secondary surface, Shell's
   * `resolveSecondarySurface` uses this `secondary` id as the curated
   * default. Plugins ship their preferred pair without touching host
   * code; cycles are tolerated (each side names the other) — the
   * code reads only the primary's pairing, never recurses.
   *
   * Absent ⇒ no curated pair (falls through to "first non-primary
   * rail surface").
   */
  splitPairing?: {
    readonly secondary: string;
  };
}

/**
 * The plugin's setup hook. Called once at host startup.
 *
 * V1.5.1 (slice 477 / 478 §4.I): the function may RETURN a typed
 * `PluginContribution` declaration, which the registry applies
 * atomically (custom elements registered, surface ports merged,
 * translations registered all-or-nothing). This is the preferred
 * shape — it gives the host a typed view of every contribution
 * the plugin makes, which enables:
 *   - Atomic install (validation runs over the full contribution
 *     before any state mutation)
 *   - Symbolic uninstall (the registry inverts the contribution
 *     record without per-plugin tracking arrays)
 *   - V1.5.2's customElements-endowment-cut for UNTRUSTED tier
 *     (plugins declare classes in the contribution record;
 *     the host calls customElements.define on their behalf
 *     with namespace validation BEFORE define runs)
 *
 * V1.5 alpha (legacy): the function may return `void` and call
 * `host.registerSurfacePort(...)` or `customElements.define(...)`
 * imperatively. The registry preserves this path for back-compat;
 * existing plugins work unchanged.
 *
 * Errors thrown from `register` are caught + recorded; the plugin
 * is recorded with an error and dispatch skips it.
 */
export type PluginRegisterFn = (
  host: PluginHostApi,
) => PluginContribution | void;

/**
 * 478 §4.I — typed plugin contribution record. When `register()`
 * returns one of these, the registry applies it atomically:
 * validates the entire shape, then commits all sub-contributions
 * in one transaction.
 *
 * Each field is optional — plugins contribute only what they need.
 * V1.5.1 alpha applies the record after `register` returns; V1.5.2
 * may move the apply step BEFORE register so register's body can
 * access the registered customElements/translations via host APIs.
 */
export interface PluginContribution {
  /**
   * Custom-element classes the plugin wants registered. The host
   * calls `customElements.define(<plugin-id>-<tagSuffix>, klass)`
   * after validating the tag suffix is well-formed (matches the
   * HTML custom-element-name regex with the plugin's namespace
   * prefix prepended).
   *
   * Plugins MAY also call customElements.define imperatively at
   * factory-evaluate time (V1.5 alpha pattern). The two paths
   * compose; declared elements are validated by the host,
   * imperatively-registered elements bypass that validation but
   * are subject to the per-tier customElements proxy (UNTRUSTED
   * tier).
   */
  customElements?: ReadonlyArray<{
    /** Suffix appended to the plugin id with a hyphen. E.g.,
     *  for plugin 'acme', tagSuffix 'panel' → registered as 'acme-panel'. */
    readonly tagSuffix: string;
    readonly klass: typeof HTMLElement;
  }>;
  /**
   * Surface-port handlers — same as
   * `host.registerSurfacePort(portId, handler)` but declared.
   */
  surfacePorts?: ReadonlyArray<{
    readonly portId: string;
    readonly handler: SurfacePortHandler;
  }>;
  /**
   * 471 i18n translations — same as `manifest.translations` but
   * scoped to register-time. The contribution record's
   * translations win over `manifest.translations` if both are
   * present (the contribution is the runtime authoritative shape).
   */
  translations?: Record<string, Record<string, string>>;
  /**
   * Slice 447-followup-live-wiring §X.12.8 Item 1.2 — Surface
   * contributions. Each entry's contribution is mirrored into the
   * live `SurfaceCatalog` via `mergePluginSurfaceContributions` at
   * install time and removed via `removePluginSurfaceContributions`
   * at uninstall time. The host stamps `pluginId` + `trustTier` +
   * `effectiveAudience` from the manifest's signing chain (V1
   * compiled-in plugins are TRUSTED_PLUGIN per
   * `PluginRegistry.surfaceContributions`).
   *
   * V1.5.1 polish (this slice) — closes the gap §X.12.8 surfaced
   * where the merge function was defined but never called from any
   * production install path.
   */
  surfaceContributions?: ReadonlyArray<{
    readonly contribution: {
      readonly id: string;
      readonly mountTag: string;
      readonly labelKey: string;
      readonly descriptionKey: string;
      readonly audience: 'USER' | 'AGENT' | 'OPERATOR' | 'DEVELOPER';
      readonly placement:
        | 'COMMAND'
        | 'RAIL'
        | 'STAGE'
        | 'HUD'
        | 'STATUS'
        | 'DRAWER'
        | 'MODAL'
        | 'DEEPLINK'
        | 'HEADLESS_AGENT_TOOL';
      readonly consumes?: {
        readonly operations?: ReadonlyArray<string>;
        readonly resources?: ReadonlyArray<string>;
        readonly prompts?: ReadonlyArray<string>;
        readonly diagnosticChannels?: ReadonlyArray<string>;
        // Tempdoc 560 §28.G — the hosted ConversationShape(s) for a `jf-chat-shape-mount` surface.
        readonly conversationShapes?: ReadonlyArray<string>;
      };
      // Tempdoc 521 §22 Phase D — declarative split pairing.
      readonly splitPairing?: { readonly secondary: string };
    };
  }>;
  /**
   * Tempdoc 560 §17/§28 (4d) — ConversationShape contributions. Completes the FE manifest's
   * declaration symmetry with the backend {@code Installation}, which already accepts all six
   * contribution kinds (560 §17): a plugin may DECLARE the conversation shapes it owns.
   *
   * <p>NOTE — declaration-only for now. The shape *runner* (a plugin-facing
   * {@code registerViewFactory} path + a conversation-substrate emitter that mounts a declared shape
   * via {@code <jf-chat-shape-mount>}) is not yet built — 560 §18 names it out of scope — so a declared
   * shape is not yet rendered live. This is the declaration half (the FE side of "the contribution
   * model spans all six kinds"); the runner is the known pending consumer (logged in observations.md).
   * The shape {@code id} must be {@code vendor.<x>.<y>}.
   */
  conversationShapes?: ReadonlyArray<{
    readonly contribution: {
      readonly id: string;
      /** The custom-element tag a future view-factory registration would mount for this shape. */
      readonly viewTag?: string;
    };
  }>;
  /**
   * Slice 447-followup-live-wiring §X.12.8 Item 1.2 — Resource
   * contributions (slice 447-impl-C primitive). Mirrors
   * `mergePluginResourceContributions` (last-writer-wins on
   * collision per the merge function's contract). Host stamps
   * provenance from the plugin's signing chain.
   */
  resourceContributions?: ReadonlyArray<{
    readonly contribution: import('../../api/types/registry.js').Resource extends infer R
      ? R extends { provenance: unknown }
        ? Omit<R, 'provenance'>
        : R
      : never;
    readonly version?: string;
  }>;
  /**
   * Slice 447-followup-live-wiring §X.12.8 Item 1.2 — recovery
   * overlays (slice 447 §X.11.5 Phase 6). Each overlay overrides
   * the recommended Operation for a given (conditionId, subject)
   * pair; trust-tier governance per `RecoveryOverlayClient` rejects
   * UNTRUSTED contributions targeting CORE Conditions and accepts
   * own-namespace contributions unconditionally.
   */
  recoveryOverlays?: ReadonlyArray<{
    readonly conditionId: string;
    readonly subject: string;
    readonly operationRef: string;
  }>;
  /**
   * Tempdoc 508 §4 — status bar item contributions. Each entry is
   * registered with StatusBarRegistry; StatusDeck reads from the
   * registry and renders items in priority order before the spacer.
   */
  statusBarItems?: ReadonlyArray<{
    readonly id: string;
    readonly position: 'left' | 'right';
    readonly priority: number;
    readonly render: () => HTMLElement | string;
  }>;
  /**
   * Tempdoc 508 §4 — inspector tab contributions. Originally: append tabs to the (now-retired)
   * InspectorPane's Preview/Context/Answer/Ask tab list. Search Thread S6 retired InspectorPane in
   * favor of the no-tabs `<jf-document-pane>` reading surface — this contribution point (and
   * `commands/InspectorTabRegistry.ts`, which still registers/lists entries declared here) currently
   * has no visual consumer; a plugin-declared tab registers but nothing renders it.
   */
  inspectorTabs?: ReadonlyArray<{
    readonly id: string;
    readonly label: string;
    readonly icon?: string;
    readonly priority: number;
    readonly render: (context: { selectedItem: unknown }) => HTMLElement | string;
  }>;
  /**
   * Tempdoc 508 §4 — context-menu action contributions. Filtered by
   * context name when openContextMenu() is called.
   */
  contextActions?: ReadonlyArray<{
    readonly id: string;
    readonly context: string;
    readonly label: string;
    readonly icon?: string;
    readonly priority: number;
    readonly when?: string;
    readonly handler: (payload: unknown) => void | Promise<void>;
    readonly enabled?: (payload: unknown) => boolean;
  }>;
  /**
   * Tempdoc 508 §11.6 / §13.6 — empty-state contribution. Each
   * entry renders inside the consumer's empty-state container when
   * the consumer's view has no content to show. Filtered by
   * `context` and `when` against the live ShellContext. Raycast-
   * style fallback commands (Search web, Ask AI, etc.) ship as
   * core contributions to the `palette-no-results` context.
   */
  emptyStateContributions?: ReadonlyArray<{
    readonly id: string;
    readonly context: string;
    readonly priority: number;
    readonly when?: string;
    readonly render: (input: {
      readonly context: string;
      readonly query?: string;
      readonly surface?: string;
    }) => HTMLElement | string;
  }>;
  /**
   * Tempdoc 521 §16.4 — walkthrough contributions. Each entry registers
   * a guided sequence with {@link WalkthroughRegistry}; install /
   * uninstall is mirrored by the PluginRegistry. Step ids are
   * namespaced by the plugin id (`<pluginId>.<step.id>`) to keep
   * progress records uncollidable across plugins.
   */
  walkthroughs?: ReadonlyArray<{
    readonly id: string;
    readonly title: string;
    readonly description?: string;
    readonly priority: number;
    readonly when?: string;
    readonly steps: ReadonlyArray<{
      readonly id: string;
      readonly title: string;
      readonly body: string;
      readonly completionEvent?:
        | `onCommand:${string}`
        | `onSettingChanged:${string}`
        | `extensionInstalled:${string}`;
    }>;
  }>;
  /**
   * Tempdoc 499 F6 — resolution alias contributions. Each entry
   * registers an alias (e.g., "dashboard" → "vendor.acme.dashboard-surface")
   * so the resolution layer redirects on exact match. Merged into the
   * CatalogResolver's alias map on install; removed on uninstall.
   */
  resolutionAliases?: ReadonlyArray<{
    readonly from: string;
    readonly to: string;
  }>;
  /**
   * Tempdoc 499 F6 — resolution synonym contributions. Each entry
   * registers a token synonym (e.g., "metrics" → "dashboard") for
   * the resolution layer's token-based synonym pre-filter.
   */
  resolutionSynonyms?: ReadonlyArray<{
    readonly from: string;
    readonly to: string;
  }>;

  /**
   * Tempdoc 511 — Aggregate-in-context strategy contributions.
   *
   * Each entry registers (or overrides) a canonical strategy for a
   * specific `(WireAggregateKind, SurfaceContextKind)` cell.
   * Override-existing-strategy contributions require an elevated
   * capability (`override-core-aggregate-strategy`); the registry
   * itself does not enforce — `PluginRegistry.install` checks
   * `manifest.capabilities` before applying these contributions.
   *
   * The type uses `string` rather than the typed `WireAggregateKind`
   * / `SurfaceContextKind` to avoid an import cycle through the
   * plugin-api module; the merge function in `PluginRegistry`
   * narrows + validates at install time.
   */
  aggregateStrategies?: ReadonlyArray<{
    readonly aggregate: string;
    readonly context: string;
    readonly rank: number;
    /** Strategy function. Type is intentionally loose at the plugin boundary. */
    readonly strategy: (...args: unknown[]) => unknown;
  }>;
}

/**
 * The plugin's optional teardown hook (V1.1+). Called by
 * `PluginRegistry.uninstall(id)` when the plugin is removed at
 * runtime (user disable, hot-reload). Plugins clean up any
 * registry-level state they registered in `register`: surface-
 * port handlers, custom-element tag dispatch entries, open
 * connections.
 *
 * Renderers' own `disconnectedCallback` covers DOM teardown
 * automatically; this hook covers registry-level cleanup that
 * outlives the DOM (e.g., closing an EnvelopeStream the plugin
 * opened in `register`).
 *
 * `unregister` is sync; if a plugin needs async cleanup, fire
 * the work and return — the registry doesn't await.
 */
export type PluginUnregisterFn = (host: PluginHostApi) => void;

// ---------------------------------------------------------------------------
// PluginHostApi — the universal framework boundary (tempdoc 507 §3.1)
// Decomposed into sub-interfaces per tempdoc 508 §2.2.
// PluginHostApi extends all sub-interfaces for flat access (508 §2.2
// design choice: flat extension, not nested objects — keeps call sites
// simple while preserving decomposition benefits for docs, versioning,
// mocking, and compositional trust).
// ---------------------------------------------------------------------------

/** Trust tier resolved at plugin load time. */
export type PluginTrustTier = 'CORE' | 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN';

/** Unsubscribe function returned by subscription methods. */
export type Unsubscribe = () => void;

// ---------------------------------------------------------------------------
// Typed snapshot types (508 §2.2) — contract shapes at the boundary.
// Structurally identical to internal types but owned by plugin-types.
// ---------------------------------------------------------------------------

export interface SearchSnapshot {
  readonly query: string;
  readonly results: readonly SearchHitSnapshot[];
  /** The bounded fused-candidate-union (retrieval window). Diagnostic only — NOT the headline. */
  readonly totalHits: number;
  /** Tempdoc 597: the TRUE matched-document count. The headline reads "Top N of M matches" off
   *  this (M), so it can't contradict the facet chips (which count the same matched population). */
  readonly matchCount: number;
  /** Tempdoc 597 §16.2: true when the match scan hit its `maxDocsScanned` cap, so `matchCount` is a
   *  lower bound — the headline renders "M+" (the Elasticsearch `gte` / Lucene relation convention). */
  readonly facetsTruncated: boolean;
  readonly isSearching: boolean;
  readonly processingTimeMs: number | null;
  readonly error: string | null;
  /**
   * Tempdoc 549 — the unified stage-keyed search trace (the single canonical artifact).
   * Opaque at the plugin boundary; the core search-explain panel casts it to the generated
   * `SearchTrace`. The single source — `introspection` (E4) and `pipelineExecution` (E3) retired.
   */
  readonly searchTrace?: unknown;
  /** Tempdoc 577 Phase 4 — worker-side retrieval-phase ms (diagnostic altitude). */
  readonly retrievalMs?: number | null;
  /** Tempdoc 577 Phase 4 — an in-flight pass exceeded the slow-hint threshold. */
  readonly slowSearch?: boolean;
  /** Tempdoc 577 Phase 5 — which pass produced the results ('quick' = provisional). */
  readonly passStage?: 'quick' | 'refined' | null;
  /** Tempdoc 577 Phase 5 — the refined pass is in flight behind displayed quick results. */
  readonly isRefining?: boolean;
  /** Tempdoc 577 Phase 6 — the response's emitted facet counts (field → value → count). */
  readonly facets?: Readonly<Record<string, Readonly<Record<string, number>>>> | null;
}

export interface SearchHitSnapshot {
  readonly docId: string;
  readonly score: number;
  readonly fields: Readonly<Record<string, string>>;
  /** Tempdoc 577 Phase 7 (Move B) — typed identity (`kind` below) + the worker's best passages. */
  readonly mimeBase?: string;
  readonly excerptRegions?: ReadonlyArray<{ readonly text?: string; readonly approxLine?: number }>;
  readonly highlights?: Readonly<Record<string, readonly string[]>>;
  readonly id: string;
  readonly title: string;
  readonly path: string;
  readonly snippet?: string;
  readonly kind?: string;
  /**
   * Tempdoc 549 — per-hit slice of the unified stage vocabulary (the sole per-hit
   * ranking-provenance source; the leg-keyed `provenance` was retired in Phase E2).
   * Opaque at the plugin boundary; the core "Why this result?" disclosure casts it to
   * the generated `HitStage[]`. Path-sparse — only the stages that touched this hit.
   */
  readonly trace?: unknown;
}

export interface SearchPinSnapshot {
  readonly id: string;
  readonly query: string;
  readonly pinnedAt: number;
  readonly runs: readonly SearchPinRunSnapshot[];
  readonly filterSpec?: SearchFilterSnapshot;
}

export interface SearchPinRunSnapshot {
  readonly ranAt: number;
  readonly totalHits: number;
}

export interface SearchFilterSnapshot {
  readonly modifiedFromMs?: number;
  readonly modifiedToMs?: number;
}

export interface InspectorSnapshot {
  readonly isOpen: boolean;
  readonly selected: InspectorItem | null;
  readonly activeTab: string;
  readonly ai: { readonly loading: boolean; readonly text: string; readonly error: string | null };
}

export interface UserConfigSnapshot {
  readonly version: number;
  readonly surfaceOverride?: Readonly<Record<string, string>>;
  readonly surfaceVisibility?: Readonly<Record<string, boolean>>;
  readonly surfaceOrder?: readonly string[];
  readonly activeLayoutId?: string;
  readonly density?: string;
  readonly themeVariant?: Readonly<Record<string, string>>;
  readonly rendererOverride?: Readonly<Record<string, string>>;
}

/**
 * Result of an operation invocation. Mirrors OperationClient's response
 * shape without coupling plugins to the internal client class.
 */
export interface OperationResult {
  success: boolean;
  message?: string;
  executionId?: string;
  structuredData?: unknown;
  errorCode?: string;
  errorDetails?: string;
  retryable?: boolean;
}

/** Options for host.showNotification(). */
export interface NotificationOptions {
  severity?: 'info' | 'warning' | 'error' | 'success';
  durationMs?: number;
  actionLabel?: string;
  onAction?: () => void;
}

/** Options for host.showConfirmDialog(). */
export interface ConfirmDialogOptions {
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
  /**
   * Tempdoc 508-followup §ε2 — typed-confirm for destructive actions.
   * When set, the confirm button is disabled until the user types
   * this string exactly. Surfaces use this for irreversible operations
   * like "force rebuild index" or "delete profile".
   */
  typedConfirmWord?: string;
}

/** Options for host.fetch(). */
export interface HostFetchInit {
  method?: string;
  headers?: Record<string, string>;
  body?: string | object;
  signal?: AbortSignal;
}

/** Catalog entry shape exposed to plugins (no internal types leaked). */
export interface CatalogEntry {
  id: string;
  displayName?: string;
  [key: string]: unknown;
}

/** Item for host.showInspector(). */
export interface InspectorItem {
  id: string;
  title: string;
  path?: string;
  kind?: string;
  snippet?: string;
  /** Optional 0-based, inclusive passage start, as in the shared inspector selection.
   * Supply both bounds as finite integers with 0 <= start <= end; otherwise neither is used. */
  highlightStartLine?: number;
  /** Optional 0-based, inclusive passage end; used only with a valid highlightStartLine. */
  highlightEndLine?: number;
  [key: string]: unknown;
}

// ---------------------------------------------------------------------------
// Sub-interfaces (508 §2.2) — each is a self-contained capability domain.
// PluginHostApi extends all of them for flat access.
// ---------------------------------------------------------------------------

export interface PluginIdentity {
  readonly installedTagNamespace: string;
  readonly pluginId: string;
  readonly trustTier: PluginTrustTier;
}

export interface PluginRegistration {
  registerSurfacePort: (id: string, handler: SurfacePortHandler) => void;
  /**
   * Register an invocable command. `label` is the raw display string; pass an
   * optional `labelKey` to have the host localize the label through its one
   * display projector (tempdoc 557 P2). When `labelKey` is given it wins over
   * `label` at render time, so a plugin can pass any non-empty `label` as a
   * dev-time fallback.
   */
  registerCommand: (id: string, label: string, handler: () => void, labelKey?: string) => void;
  registerKeybinding: (key: string, handler: () => void) => void;
}

export interface PluginDataAccess {
  fetch: (path: string, init?: HostFetchInit) => Promise<Response>;
  /**
   * Invoke an Operation. Tempdoc 550 C3: the optional `consented` opt signals the user
   * approved this op (e.g. a HIGH-risk op the surface confirmed). When set, the host routes
   * through `OperationClient.invokeWithConsent`: on the backend's 428 trust gate it approves
   * the backend-issued PendingAuthorization by id and re-invokes with the minted capsule —
   * the surface never mints for an arbitrary op (closes the Tier-0 hole, WA-5). Omitted /
   * false for AUTO-gated ops (LOW/MEDIUM at TRUSTED tier). Keeps the host API the single FE
   * invoke seam with the capsule backend-verified (distributed authority).
   */
  invokeOperation: (
    id: string,
    params?: Record<string, unknown>,
    opts?: { consented?: boolean },
  ) => Promise<OperationResult>;
  subscribeResource: (id: string, handler: (data: unknown) => void) => Unsubscribe;
  subscribeHealth: (handler: (status: unknown) => void) => Unsubscribe;
}

export interface PluginNavigation {
  navigate: (target: string, state?: Record<string, unknown>) => void;
  navigateBack: () => void;
  navigateForward: () => void;
}

export interface PluginUIControls {
  showNotification: (message: string, options?: NotificationOptions) => void;
  showConfirmDialog: (message: string, options?: ConfirmDialogOptions) => Promise<boolean>;
  copyToClipboard: (text: string) => Promise<void>;
  showInspector: (item: InspectorItem) => void;
  /**
   * Tempdoc 508-followup §ε1 — scroll a surface's mounted element to a
   * target. Best-effort: surfaces that don't expose a scroll container
   * with the expected selector or scrollIntoView semantics will no-op.
   * The target shape is intentionally open: `{ line }` is shape-
   * specific (transcript surfaces interpret it as a message index),
   * `{ top }` / `{ bottom }` map to scrollTop=0 / scrollHeight.
   */
  scrollSurfaceTo: (surfaceId: string, target: ScrollSurfaceTarget) => void;
}

/**
 * Tempdoc 508-followup §ε1 — target descriptor for `host.ui.scrollSurfaceTo`.
 * Open shape so future targets (anchor id, ratio, smooth) can land
 * additively without breaking older plugin code.
 */
export interface ScrollSurfaceTarget {
  readonly line?: number;
  readonly top?: boolean;
  readonly bottom?: boolean;
}

export interface PluginDiscovery {
  listOperations: (filter?: Record<string, unknown>) => CatalogEntry[];
  listResources: (filter?: Record<string, unknown>) => CatalogEntry[];
  listSurfaces: (filter?: Record<string, unknown>) => CatalogEntry[];
  getSystemStatus: () => Promise<unknown>;
}

export interface PluginSettings {
  getSetting: (key: string) => unknown;
  setSetting: (key: string, value: unknown) => void;
  subscribeSetting: (key: string, handler: (value: unknown) => void) => Unsubscribe;
}

export interface PluginSearchState {
  subscribeSearch: (handler: (state: SearchSnapshot) => void) => Unsubscribe;
  getSearchState: () => SearchSnapshot;
  setQuery: (q: string) => void;
  /** Tempdoc 577 Phase 5 — explicit submit: run the full refined pass immediately. */
  submitQuery: () => void;
  setSearchApiBase: (base: string) => void;
  subscribePinnedSearches: (handler: (pins: readonly SearchPinSnapshot[]) => void) => Unsubscribe;
  getPinnedSearches: () => readonly SearchPinSnapshot[];
  pinSearch: (query: string, filterSpec?: SearchFilterSnapshot) => SearchPinSnapshot | null;
  unpinSearch: (id: string) => boolean;
  isPinned: (query: string) => boolean;
  recordSearchRun: (query: string, totalHits: number) => void;
  subscribeFilters: (handler: (filters: SearchFilterSnapshot) => void) => Unsubscribe;
  getFilters: () => SearchFilterSnapshot;
  setFilterRange: (fromMs?: number, toMs?: number) => void;
  hasActiveFilter: () => boolean;
  hitToSelectedItem: (hit: SearchHitSnapshot) => InspectorItem;
}

export interface PluginInspectorState {
  subscribeInspector: (handler: (state: InspectorSnapshot) => void) => Unsubscribe;
  getInspectorState: () => InspectorSnapshot;
}

/**
 * Tempdoc 508-followup §γ2 — boundary view of a single selected item.
 * Plugins see `kind` as a string (not the internal discriminated union)
 * and `capabilities` as a string[] (not the internal `ReadonlySet`).
 * The stringly-typed shape decouples plugin code from the host's
 * SelectionItem enum so additive kinds don't break plugin code.
 */
export interface SelectionItemSnapshot {
  readonly kind: string;
  readonly capabilities: ReadonlyArray<string>;
  /** Best-effort label — title for hits, path for nodes, label for plugin items. */
  readonly label: string;
  /** Stable id when one exists (`hitId`, `itemId`, `path` fallback). */
  readonly id: string;
  /** Filesystem path when one is meaningful (`search-hit` / `browse-node`). */
  readonly path?: string;
  /** Plugin-defined payload echoed back from `setSelection`. */
  readonly payload?: unknown;
}

/**
 * Tempdoc 508-followup §γ2 — full selection snapshot. Mirrors the
 * internal {@code SelectionDescriptor} with boundary-safe item shapes.
 */
export interface SelectionSnapshot {
  readonly items: ReadonlyArray<SelectionItemSnapshot>;
  readonly primaryIndex: number;
  readonly surfaceId: string | null;
}

/**
 * Tempdoc 508-followup §γ2 — input shape for plugin-driven selection
 * mutations. Plugins describe a `plugin-item` selection via this shape;
 * the host wraps it in the internal SelectionItem union.
 */
export interface SelectionDescriptorInput {
  readonly items: ReadonlyArray<{
    readonly kind: 'plugin-item';
    readonly itemId: string;
    readonly label: string;
    readonly capabilities?: ReadonlyArray<string>;
    readonly payload?: unknown;
  }>;
  readonly primaryIndex?: number;
  readonly surfaceId?: string | null;
}

/**
 * Tempdoc 508-followup §γ2 — `host.selection` sub-interface.
 *
 * UNTRUSTED tier: read-only (no `setSelection` / `clearSelection`).
 * TRUSTED+ / CORE tier: read + write. The optional methods are
 * structurally omitted for UNTRUSTED instances so plugin code that
 * type-checks against the interface gets a compile-time signal that
 * writes aren't available at that tier.
 */
export interface PluginSelectionActionEntry {
  readonly id: string;
  readonly operation: string;
  readonly label: string;
  readonly trustGate?: 'AUTO' | 'INLINE_CONFIRM' | 'TYPED_CONFIRM' | 'DENY';
}

export interface PluginComposeIntent {
  // Tempdoc 526 §16 F5 — operation narrowed to {summarize, ask}.
  readonly operation: 'core.summarize' | 'core.ask';
  readonly userPrompt?: string;
  readonly source?: string;
}

export interface PluginSelection {
  current: () => SelectionSnapshot | null;
  subscribe: (handler: (snapshot: SelectionSnapshot | null) => void) => Unsubscribe;
  /**
   * Tempdoc 526 §5 — list registry actions applicable to the current selection
   * (or a hypothetical one if {@code probe} is provided). Available at every
   * trust tier; the registry is read-only from plugins regardless.
   */
  actions: (probe?: SelectionSnapshot | null) => ReadonlyArray<PluginSelectionActionEntry>;
  /**
   * Tempdoc 526 §5 — typed dispatch. UNTRUSTED tier omits this method
   * structurally (read-only contract).
   */
  compose?: (intent: PluginComposeIntent) => boolean;
  setSelection?: (descriptor: SelectionDescriptorInput) => void;
  clearSelection?: () => void;
}

/**
 * Tempdoc 560 §23 — a live design token exposed to plugins via {@link PluginThemeState.getTokens}.
 * Structurally the `theme/tokenIntrospection.ts` `TokenInfo` (kept independent so the plugin contract
 * surface does not import host theme internals).
 */
export interface PluginTokenInfo {
  readonly name: string;
  readonly currentValue: string;
  readonly category: string;
  readonly widgetType: 'color' | 'number' | 'angle' | 'duration' | 'text';
}

export interface PluginThemeState {
  // -- Reads (every tier) --
  subscribeActiveTheme: (handler: (id: string | null) => void) => Unsubscribe;
  getActiveThemeId: () => string | null;
  /**
   * Tempdoc 560 §23 — read the live design tokens (name, current computed value, widget category).
   * Read-only; available at every trust tier (the registry/theme is read-only from plugins).
   */
  getTokens: () => ReadonlyArray<PluginTokenInfo>;
  /**
   * Tempdoc 567 — list available themes (built-in + user custom) for the editor/picker.
   * `isCustom` marks a user-created theme (deletable via {@link deleteTheme}). Read-only; every tier.
   */
  listThemes: () => ReadonlyArray<{ id: string; displayName: string; isCustom: boolean }>;
  /**
   * Tempdoc 567 §8 #2 — serialize a saved custom theme to a JSON string for export/share (copy to
   * clipboard / save to file). Returns null for a non-custom or unknown id. Read-only; every tier.
   */
  exportTheme: (id: string) => string | null;

  // -- Writes (TRUSTED_PLUGIN / CORE only; tempdoc 560 §24) --
  // UNTRUSTED structurally omits these (the per-tier `createThemeApi` returns the read-only subset),
  // mirroring PluginSelection.compose/setSelection: an untrusted plugin must not change the palette
  // or restyle the app — a presentation-authority write (§4.4). Guard at the callsite:
  // `host.theme.previewTokens?.(...)`.
  /** Change the active palette. Omitted for UNTRUSTED. */
  selectTheme?: (id: string | null) => Promise<void>;
  /**
   * Tempdoc 560 §23/§24 — value-only live preview. Each `[tokenName, value]` sets `--tokenName: value`
   * inside a host-generated, per-plugin `@layer user-theme` `<style>`; the HOST validates every name
   * against the known-token allowlist AND sanitizes every value (rejecting brace/angle-bracket
   * breakout characters), generating the CSS itself — the plugin never supplies raw CSS (no second
   * presentation authority, §4.4). An empty map clears the preview. Throws on an unknown token or an
   * unsafe value. Omitted for UNTRUSTED.
   */
  previewTokens?: (changes: ReadonlyMap<string, string>, mode?: 'light' | 'dark') => void;
  /**
   * Tempdoc 567 — persist a user theme as a first-class declaration (the unit of save/apply). The
   * host assembles a `DesignTokenTree` from the `{name→value}` seed map, validates it (allowlisted
   * names, sanitized values), and stores it (replace-by-id) so it appears in `listThemes()` and can
   * be applied via `selectTheme(id)`. Persist-only. Throws on an invalid theme. Omitted for UNTRUSTED.
   */
  saveTheme?: (theme: {
    id: string;
    displayName: string;
    description?: string;
    tokens: Record<string, string>;
    /**
     * Tempdoc 567 §8 / A3 — optional per-mode (light/dark) overrides for the mode-variant primitive
     * channels (`p-*`); mode-invariant seeds (`h-*`) stay in {@link tokens}.
     */
    tokensByMode?: { light?: Record<string, string>; dark?: Record<string, string> };
  }) => void;
  /**
   * Tempdoc 567 §8 (deferred → built) — import a JSON theme (the counterpart to {@link exportTheme}).
   * Parses + validates the string and persists it through the SAME producer gate as {@link saveTheme}
   * (seeds + role-foregrounds only), so an imported theme cannot author a derived token. Returns the
   * saved `{ id, displayName }`. Throws on a JSON parse error, an invalid tree, or a built-in id
   * collision. Omitted for UNTRUSTED.
   */
  importTheme?: (json: string) => { id: string; displayName: string };
  /**
   * Tempdoc 567 §8 (deferred → built) — rename a custom theme's human label. Changes `displayName`
   * only; the id stays stable (no dangling `activeThemeId`). Throws if `id` is not a custom theme or
   * `displayName` is blank. Omitted for UNTRUSTED.
   */
  renameTheme?: (id: string, displayName: string) => void;
  /** Tempdoc 567 — delete a user custom theme by id. Omitted for UNTRUSTED. */
  deleteTheme?: (id: string) => void;
}

export interface PluginLayoutState {
  subscribeUserConfig: (handler: (config: UserConfigSnapshot) => void) => Unsubscribe;
  getUserConfig: () => UserConfigSnapshot;
  setSurfaceVisibility: (surfaceId: string, visible: boolean) => void;
  setSurfaceOrder: (order: string[]) => void;
  clearAllLayoutOverrides: () => void;
  setActiveLayoutId: (layoutId: string | undefined) => void;
  onSurfaceCatalogChange: (handler: () => void) => Unsubscribe;
}

export interface PluginPlatform {
  readonly capabilities: ReadonlySet<string>;
  pickFile: (options?: { multiple?: boolean; filters?: Array<{ name: string; extensions: string[] }> }) => Promise<string[] | null>;
  pickFolder: () => Promise<string | null>;
  revealInExplorer: (path: string) => Promise<void>;
  openExternal: (url: string) => Promise<void>;
}

export interface PluginUtilities {
  formatRelativeTime: (isoString: string) => string;
}

/**
 * The framework boundary — every capability a surface (core or plugin)
 * needs to interact with JustSearch's framework.
 *
 * Tempdoc 508 §2.2: decomposed into sub-interfaces for documentation,
 * versioning, partial mocking, and compositional trust. Access is
 * NESTED — consumers do `host.data.fetch()`,
 * `host.search.subscribeSearch()`, etc. (NOT `host.fetch()`). Each
 * sub-interface is independently versionable; partial mocks can
 * satisfy a single sub-interface without implementing the others.
 *
 * Identity fields are flat at the root for convenience (frequently
 * accessed, no risk of versioning churn).
 */
export interface PluginHostApi extends PluginIdentity {
  readonly registration: PluginRegistration;
  readonly data: PluginDataAccess;
  readonly navigation: PluginNavigation;
  readonly ui: PluginUIControls;
  readonly discovery: PluginDiscovery;
  readonly settings: PluginSettings;
  readonly search: PluginSearchState;
  readonly inspector: PluginInspectorState;
  /**
   * Tempdoc 508-followup §γ2 — selection sub-interface. Read-only for
   * UNTRUSTED tier (no `setSelection` / `clearSelection`); read+write
   * for TRUSTED+/CORE.
   */
  readonly selection: PluginSelection;
  readonly theme: PluginThemeState;
  readonly layout: PluginLayoutState;
  readonly platform: PluginPlatform;
  readonly utilities: PluginUtilities;
  /**
   * Tempdoc 508 §11.4 / §13.4 — AI / LLM access. Stateless one-shot
   * for EPHEMERAL shapes, session-bound for PERSISTENT shapes.
   * Templates are first-class artifacts via the TemplateCatalog.
   */
  readonly ai: PluginAI;
}

/**
 * Tempdoc 508 §11.4 / §13.4 — `host.ai` sub-interface.
 *
 * Two entry points reflect the backend's shape-dependent state model
 * (ConversationEngine treats EPHEMERAL shapes as stateless and
 * PERSISTENT shapes as session-bound). Plugins choose the right
 * call shape per use case; built-in chat surfaces use openSession.
 */
export interface PluginAI {
  /**
   * Stateless one-shot invocation. The body's wire shape is shape-
   * specific (matches the backend's ConversationEngine contract for
   * the given shapeId). Returns the final concatenated assistant
   * text after the stream closes. NOTE: rate-limiting for UNTRUSTED
   * plugins is NOT currently enforced by the host (createPluginAI only
   * denies openSession for UNTRUSTED; invokeShape/streamShape are
   * un-throttled) — do not rely on it. See docs/tempdocs/547 F5.
   */
  invokeShape(shapeId: string, body: AIShapeBody, signal?: AbortSignal): Promise<AIResponse>;
  /**
   * Stateless streaming. Yields each SSE event as it arrives.
   * Caller is responsible for collecting tokens / errors / etc.
   *
   * Tempdoc 521 §16.1 phase A: optional `signal` lets a surface tear
   * the stream down on Stop / disconnect without orphaning the
   * underlying fetch. Additive parameter — host.ai contract stays at 1.0.
   */
  streamShape(shapeId: string, body: AIShapeBody, signal?: AbortSignal): AsyncIterable<AIChunk>;
  /**
   * Session-bound conversation. PERSISTENT shapes only. UNTRUSTED
   * tier rejected at construction. Returns a handle that can be
   * `send()`-ed multiple times; history is server-side.
   */
  openSession(shapeId: string, sessionId?: string): AISession;
  /**
   * Tempdoc 508-followup §ε1 — fetch the full transcript for a session.
   * Backs `GET /api/chat/sessions/{id}/transcript`. Useful for surfaces
   * that resume a chat history or render a printable transcript.
   */
  getSessionTranscript(sessionId: string): Promise<AITranscriptSnapshot>;
  /**
   * Tempdoc 508-followup §ε1 — fetch session metadata (shape, title,
   * createdAt, lastUpdatedAt). Backs `GET /api/chat/sessions/{id}`.
   */
  getSessionMetadata(sessionId: string): Promise<AISessionMetadata>;
}

/**
 * Tempdoc 508-followup §ε1 — transcript snapshot returned by
 * `getSessionTranscript`. The wire shape from `GET /api/chat/sessions/{id}/transcript`
 * is plugin-opaque; only the canonical message-list field is typed here.
 */
export interface AITranscriptSnapshot {
  readonly messages: ReadonlyArray<AITranscriptMessage>;
  readonly sessionId: string;
}

export interface AITranscriptMessage {
  readonly role: 'user' | 'assistant' | 'system' | 'tool';
  readonly content: string;
  readonly timestamp?: string;
}

/**
 * Tempdoc 508-followup §ε1 — session metadata returned by
 * `getSessionMetadata`. Optional fields cover backend extensions
 * without forcing plugin breakage.
 */
export interface AISessionMetadata {
  readonly sessionId: string;
  readonly shapeId?: string;
  readonly title?: string;
  readonly createdAt?: string;
  readonly lastUpdatedAt?: string;
}

/** Wire body for an AI invocation. Shape-specific keys. */
export type AIShapeBody = Record<string, unknown>;

/** Aggregate response from `invokeShape`. */
export interface AIResponse {
  /** Concatenated assistant text (best-effort across event types). */
  readonly text: string;
  /** Original SSE event sequence for callers that need richer parsing. */
  readonly events: ReadonlyArray<AIChunk>;
}

/**
 * Tempdoc 508 §13 critical-analysis A6 — discriminated error
 * envelope for `host.ai` failures. Surfaced on chunks where
 * `name === 'error'`. Plugins switch on `kind` to distinguish
 * recoverable from fatal failures.
 */
export type AIErrorKind =
  | 'http-error'        // backend returned non-2xx; `status` carries the code
  | 'parse-error'       // SSE frame couldn't be parsed
  | 'session-closed'    // session.close() was called before this send()
  | 'transport-error'   // fetch threw (offline, DNS, etc.)
  | 'denied';           // trust tier rejected the call

export interface AIErrorPayload {
  readonly kind: AIErrorKind;
  readonly detail: string;
  readonly status?: number;
}

/**
 * One SSE event from the stream. `name` is the event name from the
 * SSE frame (e.g., 'token', 'tool-call', 'done'); `payload` is the
 * decoded JSON body or the raw string for non-JSON frames.
 *
 * §13 A6: when `name === 'error'`, payload is shaped as
 * {@link AIErrorPayload} with a discriminant `kind`.
 */
export interface AIChunk {
  readonly name: string;
  readonly payload: unknown;
}

/** Long-lived session handle. */
export interface AISession {
  readonly id: string;
  /**
   * Send a user turn; yield assistant chunks until the turn completes.
   * Tempdoc 521 §16.1 phase A: optional `signal` for tear-down on
   * Stop / disconnect; additive (host.ai contract stays at 1.0).
   */
  send(message: AIShapeBody, signal?: AbortSignal): AsyncIterable<AIChunk>;
  /** Close the session client-side. Server retains the transcript. */
  close(): void;
}

/** Handler invoked by the host to render a surface port. */
export type SurfacePortHandler = (
  context: SurfacePortContext,
) => HTMLElement | null;

/** Context passed to a surface-port handler. */
export interface SurfacePortContext {
  /** The surface port id (e.g., 'health.condition'). */
  portId: string;
  /** Optional payload the host passes (e.g., the HealthEvent for a
   *  health.condition port). Plugin-defined; host doesn't validate. */
  payload?: unknown;
}
