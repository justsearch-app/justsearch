// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 478 §4.B — Single typed UserStateDocument with migration ladder.
 *
 * V1.5 alpha shipped two ad-hoc per-domain stores (`userConfigState`
 * + `themeState`), each with its own localStorage key, schema-version
 * gate, malformed-JSON handling, and listener pattern. 478 §4.B
 * theorized that all user-mutable state belongs in ONE document
 * with a typed migration ladder; sub-views are projections.
 *
 * This module ships the substrate. Existing modules
 * (userConfigState.ts, themeState.ts) become PROJECTIONS that
 * delegate to this document. Their public API is preserved
 * unchanged, so no consumer code needs to change. Migration is
 * automatic on first boot — old keys are read and merged into
 * the new document; old keys remain untouched (revertible).
 *
 * Schema discipline:
 *   - One `version: N` literal field per shape
 *   - Migration ladder: `migrate1to2(v1) -> V2`
 *   - Validation at construction: malformed JSON → fall back to defaults
 *   - Forward-compat: unknown fields preserved opaquely (V2+ behavior)
 *
 * V1.5.1 ships schemaVersion 1 with two sub-views:
 *   - userConfig (matches RendererUserConfig from V1.5 alpha)
 *   - activeThemeId (matches themeState's persisted value)
 *
 * Future sub-views land here without spawning new ad-hoc stores:
 *   - installedPlugins (478 §4.H lifecycle states)
 *   - rendererPreferences
 *   - density, etc.
 *
 * The migration handles the V1.5 alpha → V1.5.1 transition:
 * reads `justsearch.userConfig` + `justsearch.activeTheme` from
 * localStorage, merges into `justsearch.userState.v1`, leaves the
 * old keys in place (so a revert reads the old data).
 */

import { normalizeSurfacePreferences } from '../renderers/userConfig.js';
import type { RendererUserConfig } from '../renderers/userConfig.js';
// Allowlisted in eslint.config.js — see 511-followup-B. This file is
// state/projection mirroring a wire enum and pre-dates the
// `<jf-operation>` / `<jf-resource>` substrate boundary.
import type { Audience } from '../../api/types/registry.js';
import type { DesignTokenTree } from '../themes/designTokenTree.js';
import type { PresentationDeclaration } from '../themes/presentationDeclaration.js';
import { createLogger } from '../../utils/logger.js';
// Tempdoc 548 §1 / R1b — the document is now a signal (single source of
// truth). Projections memoize by value (kills F10); see subscribeProjection.
import { signal, computed } from '@lit-labs/signals';

const stateLog = createLogger('UserState');

/**
 * Storage key for the consolidated document.
 *
 * Tempdoc 508 §11.3 / §13.3 — V2 introduces Profiles. The on-disk
 * storage uses the v2 key. First-boot migration reads the v1 key
 * and the legacy keys and constructs a V2 doc with everything
 * lifted into a "default" profile.
 */
const DOCUMENT_STORAGE_KEY = 'justsearch.userState.v2';
/** Pre-V2 key — read on first boot for migration, then preserved (revertible). */
const DOCUMENT_STORAGE_KEY_V1 = 'justsearch.userState.v1';
/**
 * Highest schema version this build understands. A stored document with
 * a higher version was written by a newer build; ensureInitialized
 * preserves it rather than overwriting (F9 — see docs/tempdocs/547).
 */
const CURRENT_SCHEMA_VERSION = 2;

/** Built-in default profile id; cannot be deleted. */
export const DEFAULT_PROFILE_ID = 'default';

/**
 * Slice 490 Group B5 — bound the {@code acknowledgedAdvisories} array so long-running
 * sessions don't grow it without limit. Acks past this count are FIFO-evicted (oldest
 * dropped first). 500 covers months of typical advisory volume per the v1 advisory
 * class (operation-completed). Future advisory classes with higher volume may need a
 * per-class structured cap; v1 keeps the global flat list for simplicity.
 */
export const ACKNOWLEDGED_ADVISORIES_CAP = 500;

/** Legacy keys read on first boot (V1.5 alpha → V1.5.1 migration). */
const LEGACY_USER_CONFIG_KEY = 'justsearch.userConfig';
const LEGACY_ACTIVE_THEME_KEY = 'justsearch.activeTheme';

/**
 * Slice 486 G36 — Pinned search entry. User pins a search query
 * via the SearchSurface pin button; the entry persists in this
 * document's `pinnedSearches` slice and is readable by the
 * `pinnedSearchState` projection. De-duped by `query.trim()` at
 * mutation time; ordering is creation-order (newest-last).
 */
export interface SearchPin {
  /** Stable id (crypto.randomUUID()). Used as the unpin key. */
  readonly id: string;
  /** The query string the user pinned. Stored verbatim (untrimmed). */
  readonly query: string;
  /** Pin creation timestamp (Date.now() ms). */
  readonly pinnedAt: number;
  /**
   * Slice 486 G36-widening (run-history) — short ring buffer of recent successful runs
   * for this pin. Capped at 5 entries (FIFO drop). Defaults to
   * `[]` for newly-created pins and for pre-G37 stored pins
   * that don't carry the field. The pinnedSearchState projection
   * appends entries via `recordRun(query, totalHits)`.
   */
  readonly runs: readonly SearchPinRun[];
  /**
   * Slice 486 G36-widening (filter-snapshot) — optional snapshot of the active filter at
   * pin time. Restored when the user clicks the chip. Absent
   * (undefined) when the pin was created with no active filter
   * AND for pre-G34 stored pins. Treated identically to
   * `{}` at runtime.
   */
  readonly filterSpec?: SearchFilterSpec;
}

/**
 * Slice 486 G36-widening (filter-snapshot) — pinned-search filter snapshot. Re-exported
 * here so the UserStateDocument type is the single source of
 * truth for persisted shapes. The runtime filter held by
 * `searchFiltersState` is the same shape (re-exported as
 * `SearchFilterSpec`).
 *
 * Both bounds optional; both undefined = no filter (treated
 * as absent at restore + request-build time).
 */
export interface SearchFilterSpec {
  /** Lower bound on `modified_at`, ms epoch (inclusive). */
  readonly modifiedFromMs?: number;
  /** Upper bound on `modified_at`, ms epoch (inclusive). */
  readonly modifiedToMs?: number;
}

/**
 * Slice 486 G36-widening (run-history) — single run-history entry for a pinned search.
 * Two fields only: `ranAt` (wall-clock ms) and `totalHits` (the
 * `totalHits` value returned by /api/knowledge/search). No
 * latency / error fields in V1.
 */
export interface SearchPinRun {
  /** Run timestamp (Date.now() ms). */
  readonly ranAt: number;
  /** totalHits from the search response. */
  readonly totalHits: number;
}

/**
 * Slice 501 — Saved view (bookmark). A canonical justsearch:// URL
 * that the user explicitly saved. Restoring = parseUrl(url) → dispatch.
 */
export interface SavedView {
  readonly id: string;
  readonly label: string;
  readonly url: string;
  readonly surfaceId: string;
  readonly savedAt: number;
}

/**
 * Tempdoc 508 §11.3 / §13.3 — Profile bundles the per-mode user
 * state: layout, theme, pinned searches, keybinding overrides,
 * saved views, viewer audience, and userConfig overrides. Switching
 * profiles atomically rebinds all of these. Cross-profile slices
 * (advisory acks, recent commands, plugin settings) live at the
 * document root.
 *
 * The `default` profile is built-in and cannot be deleted. New
 * profiles are created via {@link createProfile}.
 */
export interface Profile {
  readonly id: string;
  readonly label: string;
  readonly userConfig: RendererUserConfig;
  readonly activeThemeId: string | null;
  readonly pinnedSearches: readonly SearchPin[];
  readonly keybindingOverrides?: readonly { key: string; commandId: string; source: 'user' }[];
  /**
   * Slice 501 — Saved views (bookmarks). Each entry is a canonical
   * justsearch:// URL + metadata. Per-profile (different profile =
   * different bookmarks). Optional for back-compat; undefined treated
   * as empty by the savedViewState projection.
   */
  readonly savedViews?: readonly SavedView[];
  /**
   * Tempdoc 511-followup Track A — Viewer audience tier. Drives the
   * audience gate on `<jf-operation>` / `<jf-resource>` (per-tier
   * filtering of operations marked OPERATOR / DEVELOPER). Defaults
   * to 'USER' so unmodified deployments behave identically to the
   * V1.5 baseline. Optional for back-compat — undefined treated as
   * 'USER' by the projection.
   */
  readonly viewerAudience?: Audience;
  /**
   * 569 §19 Seam 5 — the id of the active applied Presentation Declaration (per-profile, like
   * {@link activeThemeId}, with which it stays consistent: re-applying the presentation re-derives the
   * theme). Persisted so boot re-applies the user's last declaration instead of always CORE_DECLARED.
   * Optional for back-compat — undefined means "the built-in default".
   */
  readonly activePresentationId?: string | null;
}

/**
 * Legacy V1 schema, kept for migration. Validated only on first-
 * boot or rollback scenarios — current writers always produce V2.
 */
interface UserStateV1Legacy {
  readonly version: 1;
  readonly userConfig: RendererUserConfig;
  readonly activeThemeId: string | null;
  readonly pinnedSearches: readonly SearchPin[];
  readonly acknowledgedAdvisories?: readonly string[];
  readonly pluginSettings?: Readonly<Record<string, Record<string, unknown>>>;
  readonly recentCommandIds?: readonly string[];
  readonly keybindingOverrides?: readonly { key: string; commandId: string; source: 'user' }[];
  // Slice 501 — savedViews lift into the default profile on V1→V2 migration.
  readonly savedViews?: readonly SavedView[];
  // Tempdoc 511-followup Track A — viewerAudience lifts into the default profile.
  readonly viewerAudience?: Audience;
}

/**
 * V2 — the current schema. Storage on disk uses {@link UserStateV2Storage};
 * consumers receive {@link UserStateV1} which is the V2 view with the
 * active profile's per-profile slices flattened to the root for
 * back-compat with V1 consumers (5 existing state modules don't
 * change as a result of this refactor).
 */
export interface WalkthroughProgress {
  readonly activeStepIndex: number;
  readonly completedStepIds: readonly string[];
  readonly dismissed: boolean;
}

export interface UserStateV2Storage {
  readonly version: 2;
  readonly activeProfileId: string;
  readonly profiles: Readonly<Record<string, Profile>>;
  /**
   * Slice 490 §4.D — Advisory inbox read-state. Cross-profile (an
   * advisory acked in one mode should stay acked in others).
   */
  readonly acknowledgedAdvisories?: readonly string[];
  /**
   * Tempdoc 507 §6 Phase 4 — per-plugin settings. Cross-profile
   * (plugin's own settings are independent of user-mode).
   */
  readonly pluginSettings?: Readonly<Record<string, Record<string, unknown>>>;
  /**
   * Tempdoc 508 §3.3 — recently-invoked command ids. Cross-profile
   * (recency is one user-signal across modes).
   */
  readonly recentCommandIds?: readonly string[];
  /**
   * Tempdoc 521 §16.4 — per-walkthrough progress. Cross-profile
   * (completing the welcome walkthrough in default mode means it's
   * done in operator mode too). Key = walkthrough id. Absent = the
   * walkthrough has not been started.
   */
  readonly walkthroughState?: Readonly<Record<string, WalkthroughProgress>>;
  /**
   * Tempdoc 567 / 560 §26 — user-created custom themes (seed/role declarations,
   * a `DesignTokenTree` each). Cross-profile ("my saved themes" persist across
   * modes). Merged into the theme catalog at boot; the unit of save/apply/delete.
   */
  readonly customThemes?: readonly DesignTokenTree[];
  /**
   * 569 Move 1 — user-created Presentation Declarations (theme + body + layout). The
   * persisted, multi-origin artifact; cross-profile, the unit of save/apply/delete.
   */
  readonly customPresentations?: readonly PresentationDeclaration[];
  /** 569 §19 Seam 6 — append-only presentation apply history (cross-profile); newest last. */
  readonly presentationHistory?: readonly PresentationHistoryEntry[];
  /**
   * Tempdoc 567 §9.4 — the glass/solid surface mode. Cross-profile (a global rendering preference,
   * not per-mode). FE-only (no backend `ui` setting); applied by the one appearance writer at boot.
   */
  readonly surfaceMode?: 'glass' | 'solid';
  /** Tempdoc 874 — the Search v3 chat-column width preset. Cross-profile, FE-only (no backend setting). */
  readonly chatWidth?: 'narrow' | 'default' | 'wide';
}

/**
 * Consumer-facing view: V2 with the active profile's slices
 * flattened to the root. Existing consumers (userConfigState,
 * themeState, pinnedSearchState, etc.) read these flat fields
 * unchanged. New profile-aware code can read `activeProfileId` and
 * `profiles` directly.
 *
 * The interface keeps the `UserStateV1` name for source-compat
 * with imports; the `version: 2` value distinguishes V2 docs from
 * V1 legacy docs.
 */
export interface UserStateV1 {
  readonly version: 2;
  readonly activeProfileId: string;
  readonly profiles: Readonly<Record<string, Profile>>;
  // Per-profile slices flattened (mirror profiles[activeProfileId]):
  readonly userConfig: RendererUserConfig;
  readonly activeThemeId: string | null;
  readonly pinnedSearches: readonly SearchPin[];
  readonly keybindingOverrides?: readonly { key: string; commandId: string; source: 'user' }[];
  readonly savedViews?: readonly SavedView[];
  readonly viewerAudience?: Audience;
  readonly activePresentationId?: string | null; // 569 §19 Seam 5 (per-profile)
  // Cross-profile slices:
  readonly acknowledgedAdvisories?: readonly string[];
  readonly pluginSettings?: Readonly<Record<string, Record<string, unknown>>>;
  readonly recentCommandIds?: readonly string[];
  readonly walkthroughState?: Readonly<Record<string, WalkthroughProgress>>;
  readonly customThemes?: readonly DesignTokenTree[];
  readonly customPresentations?: readonly PresentationDeclaration[];
  /** 569 §19 Seam 6 — append-only apply history (cross-profile), newest last; powers revert. */
  readonly presentationHistory?: readonly PresentationHistoryEntry[];
  /** Tempdoc 567 §9.4 — glass/solid surface mode (cross-profile, FE-only). */
  readonly surfaceMode?: 'glass' | 'solid';
  /** Tempdoc 874 — Search v3 chat-column width preset (cross-profile, FE-only). */
  readonly chatWidth?: 'narrow' | 'default' | 'wide';
}

/** 569 §19 Seam 6 — one entry in the presentation apply history. */
export interface PresentationHistoryEntry {
  readonly presentationId: string;
  readonly appliedAt: number;
}

/** Built-in default profile — used as the V1 → V2 migration target. */
const DEFAULT_PROFILE: Profile = {
  id: DEFAULT_PROFILE_ID,
  label: 'Default',
  userConfig: { version: 1 },
  activeThemeId: null,
  pinnedSearches: [],
};

/** Default (empty) document — used when no localStorage data exists. */
const DEFAULT_DOCUMENT: UserStateV1 = {
  version: 2,
  activeProfileId: DEFAULT_PROFILE_ID,
  profiles: { [DEFAULT_PROFILE_ID]: DEFAULT_PROFILE },
  userConfig: DEFAULT_PROFILE.userConfig,
  activeThemeId: null,
  pinnedSearches: [],
  acknowledgedAdvisories: [],
  savedViews: [],
  viewerAudience: 'USER',
};

/** Hoist the active profile's slices onto the V1 view. */
/**
 * Project a profile's per-profile slices onto the flat view shape.
 * SINGLE SOURCE OF TRUTH for the per-profile field set: both
 * viewFromStorage (build the view from storage) and setActiveProfileId
 * (rebind on switch) derive the active profile's flat slices from here,
 * so the two sites cannot drift. Adding a new per-profile field updates
 * both rebind paths at once. (Their divergence — setActiveProfileId
 * omitting fields viewFromStorage included — was the F11 bug; this is the
 * F12 structural guard.)
 *
 * Absent optionals are projected as explicit `undefined` so a caller that
 * spreads over a prior state (setActiveProfileId spreads `...doc`) clears
 * the previous profile's value instead of inheriting it. For consumers
 * and storageFromView, explicit-`undefined` is equivalent to absent.
 */
// Exported for the F11-recurrence structural guard (tempdoc 548 R1c): a test
// asserts this projector emits every per-profile slice of `Profile`, so adding
// a slice without rebinding it here fails CI rather than silently recurring F11.
export function flatSlicesFromProfile(profile: Profile): {
  userConfig: RendererUserConfig;
  activeThemeId: string | null;
  pinnedSearches: readonly SearchPin[];
  keybindingOverrides: Profile['keybindingOverrides'];
  savedViews: Profile['savedViews'];
  viewerAudience: Profile['viewerAudience'];
  activePresentationId: Profile['activePresentationId'];
} {
  return {
    userConfig: profile.userConfig,
    activeThemeId: profile.activeThemeId,
    pinnedSearches: profile.pinnedSearches,
    keybindingOverrides: profile.keybindingOverrides,
    savedViews: profile.savedViews,
    viewerAudience: profile.viewerAudience,
    activePresentationId: profile.activePresentationId,
  };
}

function viewFromStorage(storage: UserStateV2Storage): UserStateV1 {
  const profile = storage.profiles[storage.activeProfileId] ?? DEFAULT_PROFILE;
  return {
    version: 2,
    activeProfileId: storage.activeProfileId,
    profiles: storage.profiles,
    ...flatSlicesFromProfile(profile),
    ...(storage.acknowledgedAdvisories !== undefined
      ? { acknowledgedAdvisories: storage.acknowledgedAdvisories }
      : {}),
    ...(storage.pluginSettings !== undefined
      ? { pluginSettings: storage.pluginSettings }
      : {}),
    ...(storage.recentCommandIds !== undefined
      ? { recentCommandIds: storage.recentCommandIds }
      : {}),
    ...(storage.walkthroughState !== undefined
      ? { walkthroughState: storage.walkthroughState }
      : {}),
    ...(storage.customThemes !== undefined
      ? { customThemes: storage.customThemes }
      : {}),
    ...(storage.customPresentations !== undefined
      ? { customPresentations: storage.customPresentations }
      : {}),
    ...(storage.presentationHistory !== undefined
      ? { presentationHistory: storage.presentationHistory }
      : {}),
    ...(storage.surfaceMode !== undefined ? { surfaceMode: storage.surfaceMode } : {}),
    ...(storage.chatWidth !== undefined ? { chatWidth: storage.chatWidth } : {}),
  };
}

/** Re-key the active profile from the view's flat slices. */
function storageFromView(view: UserStateV1): UserStateV2Storage {
  const existing = view.profiles[view.activeProfileId];
  const activeProfile: Profile = {
    id: view.activeProfileId,
    label: existing?.label ?? view.activeProfileId,
    userConfig: view.userConfig,
    activeThemeId: view.activeThemeId,
    pinnedSearches: view.pinnedSearches,
    ...(view.keybindingOverrides !== undefined
      ? { keybindingOverrides: view.keybindingOverrides }
      : {}),
    ...(view.savedViews !== undefined
      ? { savedViews: view.savedViews }
      : {}),
    ...(view.viewerAudience !== undefined
      ? { viewerAudience: view.viewerAudience }
      : {}),
    ...(view.activePresentationId !== undefined
      ? { activePresentationId: view.activePresentationId }
      : {}),
  };
  return {
    version: 2,
    activeProfileId: view.activeProfileId,
    profiles: { ...view.profiles, [view.activeProfileId]: activeProfile },
    ...(view.acknowledgedAdvisories !== undefined
      ? { acknowledgedAdvisories: view.acknowledgedAdvisories }
      : {}),
    ...(view.pluginSettings !== undefined ? { pluginSettings: view.pluginSettings } : {}),
    ...(view.recentCommandIds !== undefined ? { recentCommandIds: view.recentCommandIds } : {}),
    ...(view.walkthroughState !== undefined
      ? { walkthroughState: view.walkthroughState }
      : {}),
    ...(view.customThemes !== undefined ? { customThemes: view.customThemes } : {}),
    ...(view.customPresentations !== undefined
      ? { customPresentations: view.customPresentations }
      : {}),
    ...(view.presentationHistory !== undefined
      ? { presentationHistory: view.presentationHistory }
      : {}),
    ...(view.surfaceMode !== undefined ? { surfaceMode: view.surfaceMode } : {}),
    ...(view.chatWidth !== undefined ? { chatWidth: view.chatWidth } : {}),
  };
}

type Listener = (doc: UserStateV1) => void;
type Selector<T> = (doc: UserStateV1) => T;

// Tempdoc 548 §1 / R1b — the document is a signal: the single source of
// truth. Tempdoc 548 §4.4 (profile-as-a-swappable-signal): the *authority* is
// `storageSig` (the persisted `UserStateV2Storage`); the flat consumer view
// (`docSig`) is a COMPUTED projection of it via `viewFromStorage`. Each
// profile's per-profile slices therefore have exactly one home —
// `storage.profiles[activeProfileId]` — and switching the active profile is a
// pure pointer change (`setActiveProfileId`) that rebinds every slice
// structurally through the projector. There is no second place the active
// slices live, so F11/F12 rebind-drift is *unrepresentable* (tier-2) rather
// than kept-in-sync by a hand-written `flatSlicesFromProfile` spread on the
// switch path. Intra-profile mutations still flow through `mutateDocument`,
// whose `storageFromView` re-keys the (unchanged) active profile from the flat
// view it just produced.
const storageSig = signal<UserStateV2Storage>(storageFromView(DEFAULT_DOCUMENT));
const docSig = computed<UserStateV1>(() => viewFromStorage(storageSig.get()));
const listeners = new Set<Listener>();

/**
 * Set the in-memory document from a flat view, re-keying the active profile
 * into the storage authority. The single internal write path other than
 * `mutateDocument` (used by initialization + test resets). `docSig` recomputes
 * from `storageSig` on the next read.
 */
function setDocumentFromView(view: UserStateV1): void {
  storageSig.set(storageFromView(view));
}

/**
 * Structural (value) equality for projection results — the F10 fix. The old
 * referential `next !== previous` check re-fired whenever a selector returned
 * a fresh object/array (e.g. `doc.list ?? []`), even when the value was
 * unchanged. Projections are small (scalars, short arrays, flat objects), so
 * a recursive structural compare is cheap and correct here.
 */
function projectionEquals(a: unknown, b: unknown): boolean {
  if (Object.is(a, b)) return true;
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') {
    return false;
  }
  const aArr = Array.isArray(a);
  if (aArr !== Array.isArray(b)) return false;
  const aKeys = Object.keys(a as object);
  const bKeys = Object.keys(b as object);
  if (aKeys.length !== bKeys.length) return false;
  for (const k of aKeys) {
    if (!Object.prototype.hasOwnProperty.call(b, k)) return false;
    if (!projectionEquals((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k])) {
      return false;
    }
  }
  return true;
}
let initialized = false;
/** Last error thrown by saveDocument, or null if the last write succeeded (F10). */
let lastPersistError: unknown = null;

/**
 * Read the persisted document from localStorage, applying the
 * V1.5 alpha → V1.5.1 migration if needed. Idempotent. Called
 * automatically by getDocument() / subscribeDocument(); callers
 * never invoke directly.
 */
function ensureInitialized(): void {
  if (initialized) return;
  initialized = true;
  if (typeof localStorage === 'undefined') return;
  // §11.3 / §13.3 — read order:
  //   1. v2 key (current)
  //   2. v1 key (legacy — migrated forward, both keys preserved)
  //   3. V1.5 alpha legacy keys (userConfig / activeTheme)
  const readKey = (k: string): string | null => {
    try {
      return localStorage.getItem(k);
    } catch {
      return null;
    }
  };
  const v2Raw = readKey(DOCUMENT_STORAGE_KEY);
  if (v2Raw !== null) {
    const parsed = parseDocument(v2Raw);
    if (parsed !== null) {
      const migrated = migrateSurfacePreferences(parsed);
      setDocumentFromView(migrated);
      if (migrated !== parsed) saveDocument(migrated);
      return;
    }
    // F9: parseDocument returned null. Distinguish a document written by
    // a NEWER build (valid JSON, version > CURRENT_SCHEMA_VERSION) from a
    // genuinely malformed blob. A newer-versioned document must NOT be
    // clobbered: run with defaults this session and leave it on disk so
    // the newer build reads it back intact on next launch. (Residual: a
    // later user mutation in this session still persists over it via
    // saveDocument — but boot no longer destroys it unprompted, which is
    // the silent-loss path F9 identifies.)
    const storedVersion = peekSchemaVersion(v2Raw);
    if (storedVersion !== null && storedVersion > CURRENT_SCHEMA_VERSION) {
      stateLog.warn(
        'Stored user-state document has a newer schema version; ' +
          'running with defaults and preserving it on disk.',
        { storedVersion, knownVersion: CURRENT_SCHEMA_VERSION },
      );
      setDocumentFromView(DEFAULT_DOCUMENT);
      return;
    }
    // Malformed v2 (corrupt / unparseable) — fall through to legacy
    // recovery paths.
  }
  const v1Raw = readKey(DOCUMENT_STORAGE_KEY_V1);
  if (v1Raw !== null) {
    const parsed = parseDocument(v1Raw);
    if (parsed !== null) {
      const migrated = migrateSurfacePreferences(parsed);
      setDocumentFromView(migrated);
      // Persist as v2 so future boots take the fast path.
      // V1 key is preserved (revertible) per the legacy migration
      // convention.
      saveDocument(migrated);
      return;
    }
  }
  // No consolidated document — try V1.5 alpha → V1.5.1 → V2 migration.
  const migrated = migrateFromLegacy();
  setDocumentFromView(migrateSurfacePreferences(viewFromStorage(migrateV1ToV2(migrated))));
  saveDocument(docSig.get());
}

/** Normalize active and inactive profiles together, preserving explicit successor preferences. */
function migrateSurfacePreferences(doc: UserStateV1): UserStateV1 {
  let changed = false;
  const profiles = Object.fromEntries(Object.entries(doc.profiles).map(([id, profile]) => {
    const userConfig = normalizeSurfacePreferences(profile.userConfig);
    if (userConfig === profile.userConfig) return [id, profile];
    changed = true;
    return [id, { ...profile, userConfig }];
  }));
  return changed ? { ...doc, profiles, userConfig: profiles[doc.activeProfileId]!.userConfig } : doc;
}

function parseDocument(raw: string): UserStateV1 | null {
  let candidate: unknown;
  try {
    candidate = JSON.parse(raw);
  } catch {
    return null;
  }
  if (
    candidate === null ||
    typeof candidate !== 'object' ||
    typeof (candidate as { version?: unknown }).version !== 'number'
  ) {
    return null;
  }
  const v = (candidate as { version: number }).version;
  if (v === 2) {
    const storage = validateV2(candidate);
    return storage ? viewFromStorage(storage) : null;
  }
  if (v === 1) {
    const legacy = validateV1Legacy(candidate);
    return legacy ? viewFromStorage(migrateV1ToV2(legacy)) : null;
  }
  // Unknown schema version. Return null (no side effects here);
  // ensureInitialized inspects the raw version and PRESERVES a future
  // (version > CURRENT_SCHEMA_VERSION) document rather than overwriting
  // it, so a downgrade doesn't lose the newer build's data (F9).
  return null;
}

/**
 * Best-effort read of the top-level `version` from a stored document
 * blob, WITHOUT full validation. Used by ensureInitialized to tell a
 * future-versioned document (preserve) from a malformed one (recover).
 */
function peekSchemaVersion(raw: string): number | null {
  try {
    const c = JSON.parse(raw) as { version?: unknown };
    return typeof c?.version === 'number' ? c.version : null;
  } catch {
    return null;
  }
}

function validateV1Legacy(candidate: unknown): UserStateV1Legacy | null {
  const c = candidate as Record<string, unknown>;
  const userConfig = c['userConfig'];
  if (
    userConfig === null ||
    typeof userConfig !== 'object' ||
    (userConfig as { version?: unknown }).version !== 1
  ) {
    return null;
  }
  const activeThemeId = c['activeThemeId'];
  if (activeThemeId !== null && typeof activeThemeId !== 'string') {
    return null;
  }
  // Slice 486 G36 — pinnedSearches. Per-field fallback to [] for
  // missing or malformed data; one bad pin shouldn't drop the
  // whole slice. Forward-compat: extra fields on a SearchPin are
  // preserved opaquely (V2+ behavior) by not stripping them.
  const rawPins = c['pinnedSearches'];
  const pinnedSearches: SearchPin[] = Array.isArray(rawPins)
    ? rawPins.flatMap((p): SearchPin[] => {
        if (
          p !== null &&
          typeof p === 'object' &&
          typeof (p as { id?: unknown }).id === 'string' &&
          typeof (p as { query?: unknown }).query === 'string' &&
          typeof (p as { pinnedAt?: unknown }).pinnedAt === 'number'
        ) {
          const pin = p as SearchPin & { runs?: unknown };
          // Slice 486 G36-widening (run-history) — runs: per-entry fallback. One bad run
          // doesn't drop the whole list; missing field becomes [].
          const rawRuns = (p as { runs?: unknown }).runs;
          const runs: SearchPinRun[] = Array.isArray(rawRuns)
            ? rawRuns.flatMap((r): SearchPinRun[] => {
                if (
                  r !== null &&
                  typeof r === 'object' &&
                  typeof (r as { ranAt?: unknown }).ranAt === 'number' &&
                  typeof (r as { totalHits?: unknown }).totalHits === 'number'
                ) {
                  const run = r as SearchPinRun;
                  return [{ ranAt: run.ranAt, totalHits: run.totalHits }];
                }
                return [];
              })
            : [];
          // Slice 486 G36-widening (filter-snapshot) — filterSpec: optional. Either bound
          // can be absent. A bound that's not a finite number is
          // dropped; if both are dropped, the whole filterSpec
          // is omitted (treated as "no filter").
          const rawFilter = (p as { filterSpec?: unknown }).filterSpec;
          let filterSpec: SearchFilterSpec | undefined;
          if (rawFilter !== null && typeof rawFilter === 'object') {
            const rawFrom = (rawFilter as { modifiedFromMs?: unknown })
              .modifiedFromMs;
            const rawTo = (rawFilter as { modifiedToMs?: unknown })
              .modifiedToMs;
            const fromOk =
              typeof rawFrom === 'number' && Number.isFinite(rawFrom);
            const toOk = typeof rawTo === 'number' && Number.isFinite(rawTo);
            if (fromOk || toOk) {
              const out: { modifiedFromMs?: number; modifiedToMs?: number } = {};
              if (fromOk) out.modifiedFromMs = rawFrom as number;
              if (toOk) out.modifiedToMs = rawTo as number;
              filterSpec = out;
            }
          }
          return [{
            id: pin.id,
            query: pin.query,
            pinnedAt: pin.pinnedAt,
            runs,
            ...(filterSpec !== undefined ? { filterSpec } : {}),
          }];
        }
        return [];
      })
    : [];

  // §12.2 (508 §13) — Defect fix: validateV1 previously dropped four
  // declared slices (acknowledgedAdvisories, pluginSettings,
  // recentCommandIds, keybindingOverrides). Restored here with the
  // same per-slice safe-fallback discipline used for pinnedSearches.

  const rawAcks = c['acknowledgedAdvisories'];
  const acknowledgedAdvisories: string[] | undefined = Array.isArray(rawAcks)
    ? rawAcks.filter((a): a is string => typeof a === 'string').slice(0, ACKNOWLEDGED_ADVISORIES_CAP)
    : undefined;

  const rawPluginSettings = c['pluginSettings'];
  let pluginSettings: Record<string, Record<string, unknown>> | undefined;
  if (rawPluginSettings !== null && typeof rawPluginSettings === 'object') {
    const out: Record<string, Record<string, unknown>> = {};
    for (const [pluginId, pluginValue] of Object.entries(rawPluginSettings)) {
      if (pluginValue !== null && typeof pluginValue === 'object' && !Array.isArray(pluginValue)) {
        out[pluginId] = { ...(pluginValue as Record<string, unknown>) };
      }
    }
    if (Object.keys(out).length > 0) pluginSettings = out;
  }

  const rawRecent = c['recentCommandIds'];
  const recentCommandIds: string[] | undefined = Array.isArray(rawRecent)
    ? rawRecent.filter((x): x is string => typeof x === 'string').slice(0, 10)
    : undefined;

  const rawKeybindings = c['keybindingOverrides'];
  const keybindingOverrides:
    | { key: string; commandId: string; source: 'user' }[]
    | undefined = Array.isArray(rawKeybindings)
    ? rawKeybindings.flatMap((entry): { key: string; commandId: string; source: 'user' }[] => {
        if (
          entry !== null &&
          typeof entry === 'object' &&
          typeof (entry as { key?: unknown }).key === 'string' &&
          typeof (entry as { commandId?: unknown }).commandId === 'string'
        ) {
          const e = entry as { key: string; commandId: string };
          return [{ key: e.key, commandId: e.commandId, source: 'user' }];
        }
        return [];
      })
    : undefined;

  // Slice 501 — savedViews: optional SavedView array (per-profile).
  const rawViews = c['savedViews'];
  const savedViews: SavedView[] | undefined = Array.isArray(rawViews)
    ? rawViews.flatMap((v): SavedView[] => {
        if (
          v !== null &&
          typeof v === 'object' &&
          typeof (v as { id?: unknown }).id === 'string' &&
          typeof (v as { label?: unknown }).label === 'string' &&
          typeof (v as { url?: unknown }).url === 'string' &&
          typeof (v as { surfaceId?: unknown }).surfaceId === 'string' &&
          typeof (v as { savedAt?: unknown }).savedAt === 'number'
        ) {
          const sv = v as SavedView;
          return [{ id: sv.id, label: sv.label, url: sv.url, surfaceId: sv.surfaceId, savedAt: sv.savedAt }];
        }
        return [];
      })
    : undefined;

  // Tempdoc 511-followup Track A — viewerAudience: optional. Validate
  // against the closed Audience set; default to 'USER' on absence or
  // invalid value.
  const rawAudience = c['viewerAudience'];
  const viewerAudience: Audience =
    rawAudience === 'USER' ||
    rawAudience === 'OPERATOR' ||
    rawAudience === 'AGENT' ||
    rawAudience === 'DEVELOPER'
      ? rawAudience
      : 'USER';

  return {
    version: 1,
    userConfig: userConfig as RendererUserConfig,
    activeThemeId: activeThemeId as string | null,
    pinnedSearches,
    ...(acknowledgedAdvisories !== undefined ? { acknowledgedAdvisories } : {}),
    ...(pluginSettings !== undefined ? { pluginSettings } : {}),
    ...(recentCommandIds !== undefined ? { recentCommandIds } : {}),
    ...(keybindingOverrides !== undefined ? { keybindingOverrides } : {}),
    ...(savedViews !== undefined ? { savedViews } : {}),
    viewerAudience,
  };
}

/**
 * Validate a V2 candidate (storage shape). Returns null on
 * malformed input.
 */
function validateV2(candidate: unknown): UserStateV2Storage | null {
  const c = candidate as Record<string, unknown>;
  const activeProfileId = c['activeProfileId'];
  if (typeof activeProfileId !== 'string' || activeProfileId.length === 0) return null;
  const rawProfiles = c['profiles'];
  if (rawProfiles === null || typeof rawProfiles !== 'object') return null;
  const profiles: Record<string, Profile> = {};
  for (const [id, raw] of Object.entries(rawProfiles)) {
    if (raw === null || typeof raw !== 'object') continue;
    const p = raw as Record<string, unknown>;
    const userConfig = p['userConfig'];
    if (
      userConfig === null ||
      typeof userConfig !== 'object' ||
      (userConfig as { version?: unknown }).version !== 1
    ) {
      continue;
    }
    const activeThemeId = p['activeThemeId'];
    if (activeThemeId !== null && typeof activeThemeId !== 'string') continue;
    const rawPins = p['pinnedSearches'];
    const pinnedSearches = Array.isArray(rawPins)
      ? rawPins.flatMap(parseSinglePin)
      : [];
    const label =
      typeof p['label'] === 'string' && (p['label'] as string).length > 0
        ? (p['label'] as string)
        : id;
    const rawOverrides = p['keybindingOverrides'];
    const keybindingOverrides = Array.isArray(rawOverrides)
      ? parseKeybindingOverrides(rawOverrides)
      : undefined;
    // Per-profile savedViews + viewerAudience (slice 501 / 511-followup A).
    const rawProfileViews = p['savedViews'];
    const profileSavedViews: SavedView[] | undefined = Array.isArray(rawProfileViews)
      ? rawProfileViews.flatMap(parseSavedView)
      : undefined;
    const rawProfileAudience = p['viewerAudience'];
    const profileViewerAudience: Audience | undefined =
      rawProfileAudience === 'USER' ||
      rawProfileAudience === 'OPERATOR' ||
      rawProfileAudience === 'AGENT' ||
      rawProfileAudience === 'DEVELOPER'
        ? rawProfileAudience
        : undefined;
    // 569 §19 Seam 5 — per-profile active presentation id (string | null when present).
    const rawActivePresentationId = p['activePresentationId'];
    const profileActivePresentationId =
      rawActivePresentationId === null || typeof rawActivePresentationId === 'string'
        ? rawActivePresentationId
        : undefined;
    profiles[id] = {
      id,
      label,
      userConfig: userConfig as RendererUserConfig,
      activeThemeId: activeThemeId as string | null,
      pinnedSearches,
      ...(keybindingOverrides !== undefined ? { keybindingOverrides } : {}),
      ...(profileSavedViews !== undefined ? { savedViews: profileSavedViews } : {}),
      ...(profileViewerAudience !== undefined ? { viewerAudience: profileViewerAudience } : {}),
      ...(profileActivePresentationId !== undefined
        ? { activePresentationId: profileActivePresentationId }
        : {}),
    };
  }
  if (!(activeProfileId in profiles)) {
    // Active profile missing — fall back to default. Migration
    // creates one if absent, but a malformed activeProfileId is
    // self-healed here.
    if (!(DEFAULT_PROFILE_ID in profiles)) {
      profiles[DEFAULT_PROFILE_ID] = DEFAULT_PROFILE;
    }
    return validateV2({
      ...c,
      activeProfileId: DEFAULT_PROFILE_ID,
      profiles,
    });
  }

  // Cross-profile slices.
  const rawAcks = c['acknowledgedAdvisories'];
  const acknowledgedAdvisories: string[] | undefined = Array.isArray(rawAcks)
    ? rawAcks.filter((a): a is string => typeof a === 'string').slice(0, ACKNOWLEDGED_ADVISORIES_CAP)
    : undefined;
  const rawPluginSettings = c['pluginSettings'];
  let pluginSettings: Record<string, Record<string, unknown>> | undefined;
  if (rawPluginSettings !== null && typeof rawPluginSettings === 'object') {
    const out: Record<string, Record<string, unknown>> = {};
    for (const [pluginId, pluginValue] of Object.entries(rawPluginSettings)) {
      if (pluginValue !== null && typeof pluginValue === 'object' && !Array.isArray(pluginValue)) {
        out[pluginId] = { ...(pluginValue as Record<string, unknown>) };
      }
    }
    if (Object.keys(out).length > 0) pluginSettings = out;
  }
  const rawRecent = c['recentCommandIds'];
  const recentCommandIds: string[] | undefined = Array.isArray(rawRecent)
    ? rawRecent.filter((x): x is string => typeof x === 'string').slice(0, 10)
    : undefined;

  // Tempdoc 521 §16.4 — walkthrough progress: cross-profile record of
  // each walkthrough's active step + completed step ids + dismissal.
  // Validated entry-by-entry; malformed entries silently dropped.
  const rawWalkthrough = c['walkthroughState'];
  let walkthroughState: Record<string, WalkthroughProgress> | undefined;
  if (rawWalkthrough !== null && typeof rawWalkthrough === 'object' && !Array.isArray(rawWalkthrough)) {
    const out: Record<string, WalkthroughProgress> = {};
    for (const [wid, raw] of Object.entries(rawWalkthrough)) {
      if (raw === null || typeof raw !== 'object') continue;
      const r = raw as Record<string, unknown>;
      const activeStepIndex = r['activeStepIndex'];
      const completedStepIds = r['completedStepIds'];
      const dismissed = r['dismissed'];
      if (
        typeof activeStepIndex === 'number' &&
        Number.isInteger(activeStepIndex) &&
        activeStepIndex >= 0 &&
        Array.isArray(completedStepIds) &&
        typeof dismissed === 'boolean'
      ) {
        out[wid] = {
          activeStepIndex,
          completedStepIds: completedStepIds.filter((s): s is string => typeof s === 'string'),
          dismissed,
        };
      }
    }
    if (Object.keys(out).length > 0) walkthroughState = out;
  }

  // Tempdoc 567 — custom themes: an array of DesignTokenTree declarations.
  // Light structural validation only (the full validateDesignTokenTree runs at
  // save time); malformed entries are dropped so a corrupt store can't crash boot.
  const rawCustomThemes = c['customThemes'];
  let customThemes: DesignTokenTree[] | undefined;
  if (Array.isArray(rawCustomThemes)) {
    const kept = rawCustomThemes.filter(
      (t): t is DesignTokenTree =>
        t !== null &&
        typeof t === 'object' &&
        typeof (t as { id?: unknown }).id === 'string' &&
        typeof (t as { displayName?: unknown }).displayName === 'string' &&
        (t as { tokens?: unknown }).tokens !== null &&
        typeof (t as { tokens?: unknown }).tokens === 'object',
    );
    if (kept.length > 0) customThemes = kept;
  }

  // 569 Move 1 — custom Presentation Declarations. Light structural validation only
  // (certifyPresentation runs at save time); malformed entries are dropped.
  const rawCustomPresentations = c['customPresentations'];
  let customPresentations: PresentationDeclaration[] | undefined;
  if (Array.isArray(rawCustomPresentations)) {
    const kept = rawCustomPresentations.filter(
      (p): p is PresentationDeclaration =>
        p !== null &&
        typeof p === 'object' &&
        typeof (p as { id?: unknown }).id === 'string' &&
        typeof (p as { displayName?: unknown }).displayName === 'string',
    );
    if (kept.length > 0) customPresentations = kept;
  }

  // Tempdoc 567 §9.4 — glass/solid surface mode (cross-profile); only the two valid literals survive.
  const rawSurfaceMode = c['surfaceMode'];
  const surfaceMode: 'glass' | 'solid' | undefined =
    rawSurfaceMode === 'glass' || rawSurfaceMode === 'solid' ? rawSurfaceMode : undefined;

  // Tempdoc 874 — Search v3 chat-column width (cross-profile); only the three valid literals survive.
  const rawChatWidth = c['chatWidth'];
  const chatWidth: 'narrow' | 'default' | 'wide' | undefined =
    rawChatWidth === 'narrow' || rawChatWidth === 'default' || rawChatWidth === 'wide'
      ? rawChatWidth
      : undefined;

  // 569 §19 Seam 6 — presentation apply history (cross-profile); malformed entries dropped.
  const rawHistory = c['presentationHistory'];
  let presentationHistory: PresentationHistoryEntry[] | undefined;
  if (Array.isArray(rawHistory)) {
    const kept = rawHistory.filter(
      (h): h is PresentationHistoryEntry =>
        h !== null &&
        typeof h === 'object' &&
        typeof (h as { presentationId?: unknown }).presentationId === 'string' &&
        typeof (h as { appliedAt?: unknown }).appliedAt === 'number',
    );
    if (kept.length > 0) presentationHistory = kept;
  }

  return {
    version: 2,
    activeProfileId,
    profiles,
    ...(acknowledgedAdvisories !== undefined ? { acknowledgedAdvisories } : {}),
    ...(pluginSettings !== undefined ? { pluginSettings } : {}),
    ...(customThemes !== undefined ? { customThemes } : {}),
    ...(customPresentations !== undefined ? { customPresentations } : {}),
    ...(presentationHistory !== undefined ? { presentationHistory } : {}),
    ...(recentCommandIds !== undefined ? { recentCommandIds } : {}),
    ...(walkthroughState !== undefined ? { walkthroughState } : {}),
    ...(surfaceMode !== undefined ? { surfaceMode } : {}),
    ...(chatWidth !== undefined ? { chatWidth } : {}),
  };
}

function parseSinglePin(p: unknown): SearchPin[] {
  if (
    p === null ||
    typeof p !== 'object' ||
    typeof (p as { id?: unknown }).id !== 'string' ||
    typeof (p as { query?: unknown }).query !== 'string' ||
    typeof (p as { pinnedAt?: unknown }).pinnedAt !== 'number'
  ) {
    return [];
  }
  const pin = p as SearchPin & { runs?: unknown };
  const rawRuns = (p as { runs?: unknown }).runs;
  const runs: SearchPinRun[] = Array.isArray(rawRuns)
    ? rawRuns.flatMap((r): SearchPinRun[] => {
        if (
          r !== null &&
          typeof r === 'object' &&
          typeof (r as { ranAt?: unknown }).ranAt === 'number' &&
          typeof (r as { totalHits?: unknown }).totalHits === 'number'
        ) {
          const run = r as SearchPinRun;
          return [{ ranAt: run.ranAt, totalHits: run.totalHits }];
        }
        return [];
      })
    : [];
  const rawFilter = (p as { filterSpec?: unknown }).filterSpec;
  let filterSpec: SearchFilterSpec | undefined;
  if (rawFilter !== null && typeof rawFilter === 'object') {
    const rawFrom = (rawFilter as { modifiedFromMs?: unknown }).modifiedFromMs;
    const rawTo = (rawFilter as { modifiedToMs?: unknown }).modifiedToMs;
    const fromOk = typeof rawFrom === 'number' && Number.isFinite(rawFrom);
    const toOk = typeof rawTo === 'number' && Number.isFinite(rawTo);
    if (fromOk || toOk) {
      const out: { modifiedFromMs?: number; modifiedToMs?: number } = {};
      if (fromOk) out.modifiedFromMs = rawFrom as number;
      if (toOk) out.modifiedToMs = rawTo as number;
      filterSpec = out;
    }
  }
  return [{
    id: pin.id,
    query: pin.query,
    pinnedAt: pin.pinnedAt,
    runs,
    ...(filterSpec !== undefined ? { filterSpec } : {}),
  }];
}

function parseKeybindingOverrides(
  raw: unknown[],
): { key: string; commandId: string; source: 'user' }[] {
  return raw.flatMap((entry): { key: string; commandId: string; source: 'user' }[] => {
    if (
      entry !== null &&
      typeof entry === 'object' &&
      typeof (entry as { key?: unknown }).key === 'string' &&
      typeof (entry as { commandId?: unknown }).commandId === 'string'
    ) {
      const e = entry as { key: string; commandId: string };
      return [{ key: e.key, commandId: e.commandId, source: 'user' }];
    }
    return [];
  });
}

// Slice 501 — per-entry SavedView parser. One bad entry doesn't drop
// the whole list. Mirrors parseSinglePin's discipline.
function parseSavedView(v: unknown): SavedView[] {
  if (
    v !== null &&
    typeof v === 'object' &&
    typeof (v as { id?: unknown }).id === 'string' &&
    typeof (v as { label?: unknown }).label === 'string' &&
    typeof (v as { url?: unknown }).url === 'string' &&
    typeof (v as { surfaceId?: unknown }).surfaceId === 'string' &&
    typeof (v as { savedAt?: unknown }).savedAt === 'number'
  ) {
    const sv = v as SavedView;
    return [{ id: sv.id, label: sv.label, url: sv.url, surfaceId: sv.surfaceId, savedAt: sv.savedAt }];
  }
  return [];
}

/**
 * Migrate a V1 legacy doc into V2 storage. Lifts all slices into
 * the built-in "default" profile.
 */
function migrateV1ToV2(legacy: UserStateV1Legacy): UserStateV2Storage {
  const defaultProfile: Profile = {
    id: DEFAULT_PROFILE_ID,
    label: 'Default',
    userConfig: legacy.userConfig,
    activeThemeId: legacy.activeThemeId,
    pinnedSearches: legacy.pinnedSearches,
    ...(legacy.keybindingOverrides !== undefined
      ? { keybindingOverrides: legacy.keybindingOverrides }
      : {}),
    ...(legacy.savedViews !== undefined
      ? { savedViews: legacy.savedViews }
      : {}),
    ...(legacy.viewerAudience !== undefined
      ? { viewerAudience: legacy.viewerAudience }
      : {}),
  };
  return {
    version: 2,
    activeProfileId: DEFAULT_PROFILE_ID,
    profiles: { [DEFAULT_PROFILE_ID]: defaultProfile },
    ...(legacy.acknowledgedAdvisories !== undefined
      ? { acknowledgedAdvisories: legacy.acknowledgedAdvisories }
      : {}),
    ...(legacy.pluginSettings !== undefined
      ? { pluginSettings: legacy.pluginSettings }
      : {}),
    ...(legacy.recentCommandIds !== undefined
      ? { recentCommandIds: legacy.recentCommandIds }
      : {}),
  };
}

/**
 * V1.5 alpha → V1.5.1 migration. Reads the two legacy keys and
 * constructs a UserStateV1Legacy document. Errors fall back to
 * defaults for the affected slice; the other slice may still
 * migrate cleanly. Caller migrates further to V2.
 *
 * The legacy keys are NOT deleted — V1.5.1 leaves them in place
 * so a revert keeps reading them. V1.5.2 cleanup deletes the
 * legacy keys after a deprecation window.
 */
function migrateFromLegacy(): UserStateV1Legacy {
  let userConfig: RendererUserConfig = { version: 1 };
  let activeThemeId: string | null = null;
  try {
    const rawCfg = localStorage.getItem(LEGACY_USER_CONFIG_KEY);
    if (rawCfg !== null) {
      const parsed = JSON.parse(rawCfg);
      if (
        parsed !== null &&
        typeof parsed === 'object' &&
        (parsed as { version?: unknown }).version === 1
      ) {
        userConfig = parsed as RendererUserConfig;
      }
    }
  } catch {
    // legacy key missing or malformed — keep default
  }
  try {
    const rawTheme = localStorage.getItem(LEGACY_ACTIVE_THEME_KEY);
    if (rawTheme !== null && rawTheme.length > 0) {
      activeThemeId = rawTheme;
    }
  } catch {
    // ignore
  }
  return { version: 1, userConfig, activeThemeId, pinnedSearches: [] };
}

function saveDocument(doc: UserStateV1): boolean {
  if (typeof localStorage === 'undefined') return false;
  try {
    // Serialize the V2 storage shape (no duplication of root flat
    // fields — they're derived from profiles on read).
    const storage = storageFromView(doc);
    localStorage.setItem(DOCUMENT_STORAGE_KEY, JSON.stringify(storage));
    lastPersistError = null;
    return true;
  } catch (err) {
    // F10: do NOT swallow silently. A failed write (quota exceeded,
    // serialization error) means the in-memory mutation is lost on the
    // next reload; record + log it so the failure is observable rather
    // than presenting as success. The caller still keeps the in-memory
    // update — we don't revert — but we no longer hide that the write
    // failed.
    lastPersistError = err;
    stateLog.error('Failed to persist user-state document', {
      error: err instanceof Error ? err.message : String(err),
    });
    return false;
  }
}

function notify(): void {
  for (const listener of listeners) {
    try {
      listener(docSig.get());
    } catch {
      // swallow listener errors — match the existing posture in
      // userConfigState / themeState
    }
  }
}

// ============================================================
// Public API
// ============================================================

/**
 * Get the current document snapshot. Forward-compat for V2's
 * additional sub-views; V1.5.1 callers typically use the
 * projection helpers below instead.
 */
export function getDocument(): UserStateV1 {
  ensureInitialized();
  return docSig.get();
}

/**
 * Subscribe to document changes. Listener fires once with the
 * current snapshot on subscribe, then on every mutation.
 */
export function subscribeDocument(listener: Listener): () => void {
  ensureInitialized();
  listeners.add(listener);
  try {
    listener(docSig.get());
  } catch {
    // ignore
  }
  return () => {
    listeners.delete(listener);
  };
}

/**
 * Subscribe to a projected slice. The listener fires only when
 * the selected value changes (referential equality), reducing
 * spurious re-renders. Use this in Lit elements that depend on
 * just userConfig.surfaceOrder (not the full document).
 */
export function subscribeProjection<T>(
  selector: Selector<T>,
  listener: (value: T) => void,
): () => void {
  ensureInitialized();
  let previous: T | typeof SENTINEL = SENTINEL;
  const wrapped: Listener = (doc) => {
    const next = selector(doc);
    if (!projectionEquals(next, previous)) {
      previous = next;
      try {
        listener(next);
      } catch {
        // ignore
      }
    }
  };
  listeners.add(wrapped);
  // Fire initial.
  wrapped(docSig.get());
  return () => {
    listeners.delete(wrapped);
  };
}

const SENTINEL = Symbol('userstate-projection-sentinel');

/**
 * Mutate the document via a producer function. The producer
 * receives the current snapshot and returns the next snapshot.
 * Subscribers fire after persistence.
 *
 * Producer must return a NEW object reference; mutating in place
 * doesn't fire listeners (listener equality check would short-
 * circuit). The TS type enforces this via `readonly` fields.
 */
export function mutateDocument(
  producer: (current: UserStateV1) => UserStateV1,
): void {
  ensureInitialized();
  const produced = producer(docSig.get());
  if (produced === docSig.get()) return;
  // §11.3 / §13.3 — the producer mutates the V1-shape view. Re-key the active
  // profile from the flat slices into the storage authority; `docSig`
  // recomputes the internally-consistent view (profiles[activeProfileId] ===
  // flat slices) on the next read.
  storageSig.set(storageFromView(produced));
  saveDocument(docSig.get());
  notify();
}

// ============================================================
// Test helpers
// ============================================================

export function __resetUserStateForTest(): void {
  setDocumentFromView(DEFAULT_DOCUMENT);
  listeners.clear();
  initialized = false;
  lastPersistError = null;
  if (typeof localStorage !== 'undefined') {
    try {
      localStorage.removeItem(DOCUMENT_STORAGE_KEY);
      localStorage.removeItem(LEGACY_USER_CONFIG_KEY);
      localStorage.removeItem(LEGACY_ACTIVE_THEME_KEY);
    } catch {
      // ignore
    }
  }
}

/**
 * Test helper: drop the in-memory document + listeners + initialized
 * flag without touching localStorage. Used to simulate a "page reload"
 * for persistence-round-trip tests. The next document read will re-run
 * ensureInitialized() which re-parses the stored localStorage body.
 * Tempdoc 511-followup-2 Track CC.
 */
export function __resetInMemoryStateForTest(): void {
  setDocumentFromView(DEFAULT_DOCUMENT);
  listeners.clear();
  initialized = false;
  lastPersistError = null;
}

/**
 * Test/diagnostic accessor for the last persistence error (F10). Returns
 * the error thrown by the most recent failed `saveDocument`, or null if
 * the last write succeeded. Lets callers + tests observe a silent quota /
 * serialization failure instead of it presenting as success.
 */
export function __getLastPersistError(): unknown {
  return lastPersistError;
}

/**
 * Test helper: seed legacy keys and force re-initialization.
 * Used by migration tests.
 */
export function __seedLegacyForTest(opts: {
  userConfig?: RendererUserConfig | null;
  activeThemeId?: string | null;
}): void {
  if (typeof localStorage === 'undefined') return;
  if (opts.userConfig !== undefined) {
    if (opts.userConfig === null) {
      localStorage.removeItem(LEGACY_USER_CONFIG_KEY);
    } else {
      localStorage.setItem(LEGACY_USER_CONFIG_KEY, JSON.stringify(opts.userConfig));
    }
  }
  if (opts.activeThemeId !== undefined) {
    if (opts.activeThemeId === null) {
      localStorage.removeItem(LEGACY_ACTIVE_THEME_KEY);
    } else {
      localStorage.setItem(LEGACY_ACTIVE_THEME_KEY, opts.activeThemeId);
    }
  }
  // Reset internal state so next read re-runs ensureInitialized.
  setDocumentFromView(DEFAULT_DOCUMENT);
  initialized = false;
  listeners.clear();
  // Clear consolidated key so migration runs.
  localStorage.removeItem(DOCUMENT_STORAGE_KEY);
}

export const __DOCUMENT_STORAGE_KEY = DOCUMENT_STORAGE_KEY;
export const __DOCUMENT_STORAGE_KEY_V1 = DOCUMENT_STORAGE_KEY_V1;
export const __LEGACY_USER_CONFIG_KEY = LEGACY_USER_CONFIG_KEY;
export const __LEGACY_ACTIVE_THEME_KEY = LEGACY_ACTIVE_THEME_KEY;

// ---------------------------------------------------------------------------
// Tempdoc 507 §6 Phase 4 — plugin settings projection
// ---------------------------------------------------------------------------

export function getPluginSetting(pluginId: string, key: string): unknown {
  const doc = getDocument();
  return doc.pluginSettings?.[pluginId]?.[key];
}

export function setPluginSetting(pluginId: string, key: string, value: unknown): void {
  const doc = getDocument();
  const current = doc.pluginSettings ?? {};
  const pluginCurrent = current[pluginId] ?? {};
  mutateDocument((d) => ({
    ...d,
    pluginSettings: {
      ...current,
      [pluginId]: { ...pluginCurrent, [key]: value },
    },
  }));
}

export function subscribePluginSetting(
  pluginId: string,
  key: string,
  handler: (value: unknown) => void,
): () => void {
  return subscribeProjection(
    (doc) => doc.pluginSettings?.[pluginId]?.[key],
    handler,
  );
}

export function getPluginSettings(pluginId: string): Record<string, unknown> {
  const doc = getDocument();
  return { ...(doc.pluginSettings?.[pluginId] ?? {}) };
}

// ---------------------------------------------------------------------------
// Tempdoc 508 §3.3 — recent commands projection
// ---------------------------------------------------------------------------

const RECENT_COMMANDS_CAP = 10;

export function getRecentCommandIds(): readonly string[] {
  return getDocument().recentCommandIds ?? [];
}

export function recordRecentCommandId(id: string): void {
  const current = getDocument().recentCommandIds ?? [];
  const next = [id, ...current.filter((x) => x !== id)].slice(0, RECENT_COMMANDS_CAP);
  mutateDocument((doc) => ({ ...doc, recentCommandIds: next }));
}

export function subscribeRecentCommandIds(handler: (ids: readonly string[]) => void): () => void {
  return subscribeProjection((doc) => doc.recentCommandIds ?? [], handler);
}

// ---------------------------------------------------------------------------
// Tempdoc 521 §16.4 — walkthrough progress projection
// ---------------------------------------------------------------------------

export function getWalkthroughProgress(id: string): WalkthroughProgress | undefined {
  return getDocument().walkthroughState?.[id];
}

export function getAllWalkthroughProgress(): Readonly<Record<string, WalkthroughProgress>> {
  return getDocument().walkthroughState ?? {};
}

function updateWalkthroughProgress(
  id: string,
  patch: (prev: WalkthroughProgress | undefined) => WalkthroughProgress,
): void {
  mutateDocument((doc) => {
    const state = doc.walkthroughState ?? {};
    const next = { ...state, [id]: patch(state[id]) };
    return { ...doc, walkthroughState: next };
  });
}

export function markWalkthroughStepComplete(walkthroughId: string, stepId: string): void {
  updateWalkthroughProgress(walkthroughId, (prev) => {
    const completedStepIds = prev?.completedStepIds ?? [];
    const completed = completedStepIds.includes(stepId)
      ? completedStepIds
      : [...completedStepIds, stepId];
    return {
      activeStepIndex: prev?.activeStepIndex ?? 0,
      completedStepIds: completed,
      dismissed: prev?.dismissed ?? false,
    };
  });
}

export function setWalkthroughActiveStep(walkthroughId: string, index: number): void {
  if (!Number.isInteger(index) || index < 0) return;
  updateWalkthroughProgress(walkthroughId, (prev) => ({
    activeStepIndex: index,
    completedStepIds: prev?.completedStepIds ?? [],
    dismissed: prev?.dismissed ?? false,
  }));
}

export function dismissWalkthrough(walkthroughId: string): void {
  updateWalkthroughProgress(walkthroughId, (prev) => ({
    activeStepIndex: prev?.activeStepIndex ?? 0,
    completedStepIds: prev?.completedStepIds ?? [],
    dismissed: true,
  }));
}

export function subscribeWalkthroughProgress(
  handler: (state: Readonly<Record<string, WalkthroughProgress>>) => void,
): () => void {
  return subscribeProjection((doc) => doc.walkthroughState ?? {}, handler);
}

// ---------------------------------------------------------------------------
// Tempdoc 521 §16.4 deeper — `onSettingChanged(key, handler)` channel.
// Walkthrough steps gated on `onSettingChanged:<key>` rely on this to
// advance when the named document slice changes.
//
// §22 Phase C — the V1 closed allowlist (activeThemeId / activeProfileId
// / activeLayoutId / viewerAudience) was identity-branching and a
// §11.11 invariant violation. The implementation is now a dotted-path
// resolver: `onSettingChanged('userConfig.activeLayoutId', ...)` walks
// the document via the path segments and fires when the value
// transitions. Plugins can subscribe to their own slice via
// `onSettingChanged('pluginSettings.acme.configured', ...)` without
// touching this code. Malformed / unresolvable paths fire never
// (parity with §13.1's silent-malformed policy).
// ---------------------------------------------------------------------------

/** Read a dotted-path slice out of an opaque document. Returns
 *  `undefined` if any segment is missing. */
function readDocumentPath(doc: unknown, key: string): unknown {
  if (!key || typeof key !== 'string') return undefined;
  const segments = key.split('.');
  let cursor: unknown = doc;
  for (const seg of segments) {
    if (cursor === null || cursor === undefined) return undefined;
    if (typeof cursor !== 'object') return undefined;
    cursor = (cursor as Record<string, unknown>)[seg];
  }
  return cursor;
}

/**
 * Subscribe to changes of a named setting key. The handler fires only
 * when the resolved value transitions from one settled state to
 * another (initial subscribe does NOT fire). Returns an unsubscribe
 * function.
 *
 * Key format: dotted path into the document (e.g.,
 * `activeThemeId`, `userConfig.activeLayoutId`,
 * `pluginSettings.acme.configured`). Unresolvable paths silently
 * never fire.
 */
export function onSettingChanged(
  key: string,
  handler: () => void,
): () => void {
  if (!key) return () => {};
  ensureInitialized();
  let previous: unknown = readDocumentPath(docSig.get(), key);
  const wrapped: Listener = (doc) => {
    const next = readDocumentPath(doc, key);
    if (!projectionEquals(next, previous)) {
      previous = next;
      try {
        handler();
      } catch {
        // ignore
      }
    }
  };
  listeners.add(wrapped);
  return () => listeners.delete(wrapped);
}

// ---------------------------------------------------------------------------
// Tempdoc 508 §11.3 / §13.3 — Profile management
// ---------------------------------------------------------------------------

/** The currently-active profile id. */
export function getActiveProfileId(): string {
  return getDocument().activeProfileId;
}

/** All known profiles, in insertion order. */
export function listProfiles(): readonly Profile[] {
  return Object.values(getDocument().profiles);
}

/** Look up a profile by id. */
export function getProfile(id: string): Profile | undefined {
  return getDocument().profiles[id];
}

/**
 * Switch the active profile atomically. Listeners fire once with
 * the new view (layout, theme, pinned searches, keybindings all
 * rebind in a single mutation).
 */
export function setActiveProfileId(id: string): void {
  ensureInitialized();
  const storage = storageSig.get();
  if (storage.activeProfileId === id) return;
  if (!storage.profiles[id]) {
    throw new Error(`UserStateDocument: profile '${id}' does not exist`);
  }
  // 548 §4.4 — profile-as-a-swappable-signal. The switch is a pure pointer
  // change on the storage authority; the flat per-profile slices are a
  // `computed` projection (`docSig`) of `(storage, activeProfileId)`, so
  // flipping the pointer rebinds layout/theme/pins/keybindings/savedViews/
  // viewerAudience structurally on the next read. This deliberately bypasses
  // `mutateDocument`: its `storageFromView` re-keys the active profile *from
  // the flat view*, which on a switch would write the OUTGOING profile's
  // slices into the incoming profile — the F11 bug. With the slices held only
  // in `storage.profiles[id]`, that drift is unrepresentable.
  storageSig.set({ ...storage, activeProfileId: id });
  saveDocument(docSig.get());
  notify();
}

/**
 * Create a new profile. If `basedOn` is supplied, the new profile
 * copies the basis's slices; otherwise it starts from the built-in
 * defaults. Throws if the id already exists.
 */
export function createProfile(
  id: string,
  label: string,
  basedOn?: string,
): void {
  mutateDocument((doc) => {
    if (doc.profiles[id]) {
      throw new Error(`UserStateDocument: profile '${id}' already exists`);
    }
    // §11.3 — when basedOn isn't supplied, copy from the currently-
    // active profile (most natural for "fork my current setup as a
    // new mode"). Explicit basedOn overrides this.
    const basisId = basedOn ?? doc.activeProfileId;
    const basis = doc.profiles[basisId] ?? DEFAULT_PROFILE;
    const newProfile: Profile = {
      id,
      label,
      userConfig: basis.userConfig,
      activeThemeId: basis.activeThemeId,
      pinnedSearches: basis.pinnedSearches,
      ...(basis.keybindingOverrides !== undefined
        ? { keybindingOverrides: basis.keybindingOverrides }
        : {}),
    };
    return {
      ...doc,
      profiles: { ...doc.profiles, [id]: newProfile },
    };
  });
}

/**
 * Rename a profile. The id is immutable; only the human-facing
 * label changes.
 */
export function renameProfile(id: string, label: string): void {
  mutateDocument((doc) => {
    const profile = doc.profiles[id];
    if (!profile) throw new Error(`UserStateDocument: profile '${id}' does not exist`);
    return {
      ...doc,
      profiles: { ...doc.profiles, [id]: { ...profile, label } },
    };
  });
}

/**
 * Delete a profile. Cannot delete the active profile or the
 * built-in 'default' profile. Switch active first if needed.
 */
export function deleteProfile(id: string): void {
  mutateDocument((doc) => {
    if (id === DEFAULT_PROFILE_ID) {
      throw new Error("UserStateDocument: cannot delete the 'default' profile");
    }
    if (id === doc.activeProfileId) {
      throw new Error(
        `UserStateDocument: cannot delete the active profile '${id}'; switch active first`,
      );
    }
    if (!doc.profiles[id]) return doc;
    const next = { ...doc.profiles };
    delete next[id];
    return { ...doc, profiles: next };
  });
}

export function subscribeActiveProfileId(
  handler: (id: string) => void,
): () => void {
  return subscribeProjection((doc) => doc.activeProfileId, handler);
}

/**
 * Tempdoc 508-followup §β4 — invalidation API. Fires only when the
 * active profile id CHANGES; the initial fire that {@link
 * subscribeProjection} delivers on subscribe is suppressed. Use this
 * to drop in-memory state derived from the previous profile (e.g.,
 * keybinding overrides, ephemeral search-surface state) so the user
 * sees a clean rebind on every switch.
 *
 * Consumers wired at boot today: KeybindingRegistry,
 * searchFiltersState, searchState, inspectorState.
 */
export function subscribeProfileSwitch(
  handler: (newProfileId: string) => void,
): () => void {
  let primed = false;
  return subscribeProjection(
    (doc) => doc.activeProfileId,
    (id) => {
      if (!primed) {
        primed = true;
        return;
      }
      handler(id);
    },
  );
}
