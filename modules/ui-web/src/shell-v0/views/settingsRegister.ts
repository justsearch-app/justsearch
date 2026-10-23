// SPDX-License-Identifier: Apache-2.0
/**
 * settingsRegister — tempdoc 855 §9.3: the ONE declared settings register.
 *
 * An ordered list of `{group → category → entry}`. From this single declaration project:
 * (1) `<jf-settings-nav>` (groups, category rows, accordion sub-anchors), (2) the active
 * category's content in `SettingsSurface` (native entries dispatch to one of its existing
 * `render*()` methods by `key`; member entries mount a catalog surface by id), (3) deep-link
 * targets (a member category's id IS the catalog surface id the existing member→host redirect
 * already resolves — see `router/memberTabIntent.ts`), (4) — later — settings search (855 §6
 * Phase 4). Nav and content both read this register; neither hand-maintains its own copy of the
 * tree (the projection-vs-fork discipline, ADR-0031/0033).
 *
 * Audience gating: a `gate()` predicate is attached to the SAME entries the corresponding
 * `SettingsSurface.render*()` method already self-gates on (Plugin permissions hidden for USER,
 * Workspace Profiles shown only for DEVELOPER — `SettingsSurface.ts` `renderPluginPermissions` /
 * `renderWorkspaceProfilesDeveloper`). Both sides call the identical `getViewerAudience()` read —
 * this is the same fact consumed twice (nav-visibility + content-dispatch), not a second
 * declaration of the rule that could drift.
 *
 * i18n: native group/category/section labels resolve through `settings.group.*` /
 * `settings.category.*` / `settings.section.*` keys in the existing `registry-surface` message
 * catalog (`modules/app-api/src/main/resources/messages/registry-surface.en.properties`) via
 * `localizeResourceKey` — the same backend-served catalog `present()` already boots
 * (`bootSurfaceCatalog`), so no new namespace/controller wiring is needed. Member category labels
 * resolve through `present({kind:'surface', id})` (the existing surface-label authority) instead —
 * no `EntityRef` kind exists for a bare settings category, so native labels use the resource-key
 * convention directly (855 §11.5).
 *
 * Phase 4 (855 §6 / §9.3 item 4): `searchRegister()` is the register's SECOND projection — a
 * query over the exact same tree `<jf-settings-nav>` already renders, resolved through the exact
 * same label paths (`categoryLabel()` below — extracted from `<jf-settings-nav>` so nav and search
 * share one label-resolution function instead of forking a second copy). It matches group labels
 * too: a group-label hit surfaces every category in that group (there is no single "activate a
 * group" target, so the group match fans out to its categories). Section-level audience gating
 * reuses each entry's own `gate()` — the SAME live `getViewerAudience()` read the nav's accordion
 * already filters sections with, so a USER querying a DEVELOPER-only section's label gets no hit,
 * with no second gating rule to drift.
 */
import { getViewerAudience } from '../state/viewerAudienceState.js';
import { localizeResourceKey } from '../../i18n/resourceCatalog.js';
import { present } from '../display/present.js';

export type SettingsCategoryKind = 'native' | 'member';

/** One sub-anchor within a native category page — dispatches to a `SettingsSurface.render*()`. */
export interface SettingsSectionEntry {
  /** Stable key: the scroll-spy anchor id AND the `SettingsSurface` section-renderer dispatch key. */
  readonly key: string;
  /** `registry-surface` catalog key resolving this sub-anchor's label. */
  readonly labelKey: string;
  /** Optional audience gate — mirrors the section renderer's own self-gate (see file header). */
  readonly gate?: () => boolean;
}

export interface SettingsCategory {
  /** Stable id: the nav row id. For a MEMBER category this is also the catalog surface id (so the
   *  existing member→host deep-link redirect selects it directly — no id translation needed). */
  readonly id: string;
  readonly kind: SettingsCategoryKind;
  /** `registry-surface` catalog key resolving this category's nav-row label (native only). */
  readonly labelKey?: string;
  /** Native categories: ordered sub-anchor sections. */
  readonly sections?: readonly SettingsSectionEntry[];
  /** Member categories: the catalog surface id this category mounts (equals `id`). */
  readonly memberSurfaceId?: string;
}

export interface SettingsGroup {
  readonly id: string;
  readonly labelKey: string;
  /** Isolated/destructive group (855 §4 — "Data", red, last, own group). */
  readonly danger?: boolean;
  readonly categories: readonly SettingsCategory[];
}

const operatorOrAbove = (): boolean => getViewerAudience() !== 'USER';
const developerOnly = (): boolean => getViewerAudience() === 'DEVELOPER';

/**
 * The declared tree (855 §4 / Phase 1 straw naming, D4 — bikeshed later, not a blocker). Category
 * ids double as CSS/test hooks and (for member categories) catalog surface ids; keep them stable.
 */
export const SETTINGS_REGISTER: readonly SettingsGroup[] = [
  {
    id: 'general',
    labelKey: 'settings.group.general',
    categories: [
      {
        id: 'appearance',
        kind: 'native',
        labelKey: 'settings.category.appearance',
        sections: [
          { key: 'interface', labelKey: 'settings.section.interface' },
          { key: 'theme', labelKey: 'settings.section.theme' },
          { key: 'accessibility', labelKey: 'settings.section.accessibility' },
          // Tempdoc 874 — the Search v3 chat-column width preset (FE-only, user-state document).
          { key: 'chat-width', labelKey: 'settings.section.chat-width' },
          // Tempdoc 855 §5 item 2 / §9.6 item 5 — Token Editor's rail placement is demoted
          // (RAIL → DEEPLINK); this sub-anchor is its replacement launch affordance, a LINK to
          // the plugin surface per ADR-0035 (settings never embeds plugin UI).
          { key: 'token-editor', labelKey: 'settings.section.token-editor' },
        ],
      },
      {
        id: 'layout',
        kind: 'native',
        labelKey: 'settings.category.layout',
        sections: [
          { key: 'layout', labelKey: 'settings.section.layout' },
          { key: 'rail', labelKey: 'settings.section.rail' },
          { key: 'keyboard', labelKey: 'settings.section.keyboard' },
        ],
      },
      {
        id: 'core.presentation-gallery-surface',
        kind: 'member',
        memberSurfaceId: 'core.presentation-gallery-surface',
      },
      {
        id: 'core.presentation-editor-surface',
        kind: 'member',
        memberSurfaceId: 'core.presentation-editor-surface',
      },
    ],
  },
  {
    id: 'ai',
    labelKey: 'settings.group.ai',
    categories: [
      {
        id: 'agent',
        kind: 'native',
        labelKey: 'settings.category.agent',
        sections: [
          { key: 'agent-autonomy', labelKey: 'settings.section.agent-autonomy' },
          // Tempdoc 883 D-A.7 — `contextLength` never had a control. It has one now, but a
          // DIAGNOSTIC one: the window is a derived resource (ADR-0047), so the section is a
          // read-only readout of what the engine was actually launched with, plus an escape hatch.
          { key: 'context-window', labelKey: 'settings.section.context-window' },
        ],
      },
    ],
  },
  {
    id: 'privacy-trust',
    labelKey: 'settings.group.privacy-trust',
    categories: [
      {
        // Tempdoc 855 §5 item 1 / §9.3 — absorbed member category: core.security-surface (encryption,
        // backups, auto-lock) mounts here directly instead of a native pointer page linking out to it
        // (629's interim placement, now superseded — the settings window is the real home).
        id: 'core.security-surface',
        kind: 'member',
        memberSurfaceId: 'core.security-surface',
      },
      {
        id: 'plugins',
        kind: 'native',
        labelKey: 'settings.category.plugins',
        sections: [
          { key: 'plugins', labelKey: 'settings.section.plugins' },
          {
            key: 'plugin-permissions',
            labelKey: 'settings.section.plugin-permissions',
            gate: operatorOrAbove,
          },
          { key: 'durable-grants', labelKey: 'settings.section.durable-grants' },
          { key: 'delivered-contributions', labelKey: 'settings.section.delivered-contributions' },
        ],
      },
    ],
  },
  {
    id: 'app',
    labelKey: 'settings.group.app',
    categories: [
      {
        id: 'indexing', kind: 'native', labelKey: 'settings.category.indexing',
        sections: [{ key: 'indexing', labelKey: 'settings.section.indexing' }],
      },
      {
        id: 'desktop',
        kind: 'native',
        labelKey: 'settings.category.desktop',
        sections: [
          { key: 'desktop', labelKey: 'settings.section.desktop' },
          { key: 'app-updates', labelKey: 'settings.section.app-updates' },
          { key: 'feedback-capture', labelKey: 'settings.section.feedback-capture' },
        ],
      },
      {
        id: 'developer',
        kind: 'native',
        labelKey: 'settings.category.developer',
        sections: [
          { key: 'view-tier', labelKey: 'settings.section.view-tier' },
          {
            key: 'workspace-profiles',
            labelKey: 'settings.section.workspace-profiles',
            gate: developerOnly,
          },
        ],
      },
    ],
  },
  {
    id: 'data',
    labelKey: 'settings.group.data',
    danger: true,
    categories: [
      {
        id: 'data',
        kind: 'native',
        labelKey: 'settings.category.data',
        sections: [{ key: 'data', labelKey: 'settings.section.data' }],
      },
    ],
  },
];

/** Flatten the register into its categories, in declared order. */
export function allCategories(
  register: readonly SettingsGroup[] = SETTINGS_REGISTER,
): SettingsCategory[] {
  return register.flatMap((g) => g.categories);
}

/** Look up a category by id (native category id or member surface id) across the whole register. */
export function findCategory(
  id: string,
  register: readonly SettingsGroup[] = SETTINGS_REGISTER,
): SettingsCategory | undefined {
  return allCategories(register).find((c) => c.id === id);
}

/** The default active category — the first entry of the first group. */
export function firstCategoryId(register: readonly SettingsGroup[] = SETTINGS_REGISTER): string {
  return register[0]?.categories[0]?.id ?? '';
}

/** The one category-label authority (855 §9.5 origin, moved here for §6 Phase 4 so
 *  `<jf-settings-nav>`'s row labels and `searchRegister()`'s hit labels can never drift apart). */
export function categoryLabel(category: SettingsCategory): string {
  if (category.kind === 'member' && category.memberSurfaceId) {
    return present({ kind: 'surface', id: category.memberSurfaceId }).label;
  }
  return category.labelKey ? localizeResourceKey(category.labelKey) : category.id;
}

/** One `searchRegister()` hit: a category match (`section` absent) or a sub-anchor match. */
export interface SettingsSearchResult {
  readonly group: SettingsGroup;
  readonly category: SettingsCategory;
  readonly section?: SettingsSectionEntry;
}

function includesCaseInsensitive(label: string, needle: string): boolean {
  return label.toLowerCase().includes(needle);
}

/**
 * 855 §6 Phase 4 / §9.3 item 4 — the register's second projection: a query over the SAME resolved
 * labels the nav renders (native via `localizeResourceKey`, member categories via `present()`),
 * plus group labels. Case-insensitive substring match only (~30 entries total — no fuzzy scoring,
 * 855 Phase-4 brief). An empty/whitespace-only query returns no results (the caller's signal to
 * show the grouped nav instead of a flat result list).
 */
export function searchRegister(
  query: string,
  register: readonly SettingsGroup[] = SETTINGS_REGISTER,
): SettingsSearchResult[] {
  const needle = query.trim().toLowerCase();
  if (!needle) return [];
  const results: SettingsSearchResult[] = [];
  const seen = new Set<string>();
  const push = (result: SettingsSearchResult): void => {
    const dedupeKey = `${result.category.id}::${result.section?.key ?? ''}`;
    if (seen.has(dedupeKey)) return;
    seen.add(dedupeKey);
    results.push(result);
  };
  for (const group of register) {
    const groupHit = includesCaseInsensitive(localizeResourceKey(group.labelKey), needle);
    for (const category of group.categories) {
      if (groupHit || includesCaseInsensitive(categoryLabel(category), needle)) {
        push({ group, category });
      }
      for (const section of category.sections ?? []) {
        if (section.gate && !section.gate()) continue;
        if (includesCaseInsensitive(localizeResourceKey(section.labelKey), needle)) {
          push({ group, category, section });
        }
      }
    }
  }
  return results;
}
