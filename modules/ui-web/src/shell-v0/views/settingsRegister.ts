// SPDX-License-Identifier: Apache-2.0
/** The one Settings register: navigation, content, search and incoming coordinates. */
import { getShowDeveloperTools } from '../state/developerToolsState.js';
import { getViewerAudience } from '../state/viewerAudienceState.js';
import { getUserConfig } from '../state/userConfigState.js';
import { getOperation } from '../../api/registry/OperationCatalogClient.js';
import { operationVisibleTo } from '../aggregate-substrate/queryPrimitives.js';
import { localizeResourceKey } from '../../i18n/resourceCatalog.js';
import { Resolve, type ControlElement, type UISchemaElement } from '@jsonforms/core';
import { activeBodyFor } from '../state/presentationRuntime.js';
import { SETTINGS_INTERFACE_REGION } from '../themes/builtinPresentations.js';
import { listLayouts } from '../layout/LayoutManifest.js';
import { listAvailableThemes } from '../themes/themesCatalog.js';
import { SETTINGS_CONTROL_LABELS } from './settingsControlLabels.js';
import { present } from '../display/present.js';


type SettingsCategoryKind = 'native' | 'member';

/** One sub-anchor within a native category page — dispatches to a `SettingsSurface.render*()`. */
export interface SettingsSectionEntry {
  /** Stable key: the scroll-spy anchor id AND the `SettingsSurface` section-renderer dispatch key. */
  readonly key: string;
  /** Searchable words from the same labels the controls render. */
  readonly controlWords?: () => readonly string[];
  readonly keywords?: readonly string[];
  readonly destination?: 'indexing-hours' | 'reading-memory';
  /** `registry-surface` catalog key resolving this sub-anchor's label. */
  readonly labelKey: string;
  /** Catalog member rendered alongside native entries in this section. */
  readonly memberSurfaceId?: string;
  /** Platform capability (`host_.platform.capabilities`) the row's controls need. Without it the row
   *  is not part of Settings on this platform: never listed, rendered or explained. */
  readonly requires?: string;
  /** Who may see the row: the view tier, or the operation policy behind its only control. */
  readonly audience?: (viewer: SettingsViewer) => boolean;
  /** A developer tool: shown only while Show developer tools is on. */
  readonly developerTool?: true;
  /** The row's only control changes the backend's saved settings. While they are session-only the
   *  row has nothing to offer, so it is not part of Settings, like a row this platform lacks. */
  readonly savedSettings?: true;
}

/** What Settings knows about its viewer beyond the shared audience and developer-tools stores. */
export interface SettingsViewer {
  readonly capabilities: ReadonlySet<string>;
  /** Settings recovery holds indexing until Reset to defaults is confirmed (the USER exception). */
  readonly settingsRecovery: boolean;
  /** The backend keeps settings for this session only (`settingsMode: in_memory`). */
  readonly settingsReadOnly: boolean;
}

const NO_VIEWER: SettingsViewer = { capabilities: new Set(), settingsRecovery: false, settingsReadOnly: false };

/** Shown, or why not: an audience or developer-tools restriction (explained to arrivals), or
 *  absent on this platform or in this settings mode. Navigation, search, content and arrivals read only this. */
export type SettingsEntryState = 'shown' | 'audience' | 'tools' | 'absent';

export function entryState(entry: SettingsSectionEntry, viewer: SettingsViewer = NO_VIEWER): SettingsEntryState {
  if (entry.requires && !viewer.capabilities.has(entry.requires)) return 'absent';
  if (entry.savedSettings && viewer.settingsReadOnly) return 'absent';
  if (entry.audience && !entry.audience(viewer)) return 'audience';
  if (entry.developerTool && !getShowDeveloperTools()) return 'tools';
  return 'shown';
}

/** A focused row that an audience or developer-tools change removes hands focus to Show developer
 *  tools, which explains it. Reset to defaults disappears when settings recovery ends and keeps its
 *  own rescue (the Settings heading). */
export function rescuedBySwitch(entry: SettingsSectionEntry, viewer?: SettingsViewer): boolean {
  const state = entryState(entry, viewer);
  return (state === 'audience' || state === 'tools') && entry.key !== 'reset-settings';
}

/** The entries of a category this viewer sees, in register order. */
export function shownEntries(category: SettingsCategory, viewer?: SettingsViewer): SettingsSectionEntry[] {
  return (category.sections ?? []).filter(entry => entryState(entry, viewer) === 'shown');
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
/** Reset to defaults is offered by its operation's audience policy; settings recovery offers it as
 *  OPERATOR (the USER exception), as its renderer does. */
const resetSettingsOffered = (viewer: SettingsViewer): boolean => {
  const reset = getOperation('core.reset-settings');
  return !!reset && operationVisibleTo(reset, viewer.settingsRecovery ? 'OPERATOR' : getViewerAudience());
};
/** Desktop, update and updater rows need the native application. */
const NATIVE = 'native-notifications';

/** Seven pages, in navigation order. Entry keys remain compatible with old addresses. */
export const SETTINGS_REGISTER: readonly SettingsGroup[] = [
  {
    id: 'general', labelKey: 'settings.category.general', categories: [{
      id: 'general', kind: 'native', labelKey: 'settings.category.general', sections: [
        { key: 'desktop', keywords: ['shortcut', 'hotkey'], controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['desktop']), labelKey: 'settings.section.desktop', requires: NATIVE },
        { key: 'notifications', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['notifications']), labelKey: 'settings.section.notifications' },
        { key: 'app-updates', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['app-updates']), labelKey: 'settings.section.app-updates', requires: NATIVE },
        { key: 'keyboard', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['keyboard']), labelKey: 'settings.section.keyboard' },
        { key: 'indexing', destination: 'indexing-hours', keywords: ['battery', 'schedule'], controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['indexing']), labelKey: 'settings.section.indexing' },
        { key: 'context-window', destination: 'reading-memory', keywords: ['context window', 'tokens'], controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['context-window']), labelKey: 'settings.section.context-window' },
      ],
    }],
  },
  {
    id: 'appearance', labelKey: 'settings.category.appearance', categories: [{
      id: 'appearance', kind: 'native', labelKey: 'settings.category.appearance', sections: [
        { key: 'interface', controlWords: interfaceControlWords, labelKey: 'settings.section.interface' },
        { key: 'theme', controlWords: () => [...Object.values(SETTINGS_CONTROL_LABELS['theme']), ...listAvailableThemes().map(t => t.displayName)], labelKey: 'settings.section.theme' },
        { key: 'core.presentation-gallery-surface', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['core.presentation-gallery-surface']), labelKey: 'registry-surface.core.presentation-gallery-surface.label', memberSurfaceId: 'core.presentation-gallery-surface' },
        { key: 'core.presentation-editor-surface', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['core.presentation-editor-surface']), labelKey: 'registry-surface.core.presentation-editor-surface.label', memberSurfaceId: 'core.presentation-editor-surface' },
        { key: 'token-editor', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['token-editor']), labelKey: 'settings.section.token-editor' },
        { key: 'layout', controlWords: () => listLayouts().map(l => l.displayName), labelKey: 'settings.section.layout' },
        { key: 'rail', controlWords: () => {
          const config = getUserConfig();
          return [SETTINGS_CONTROL_LABELS.rail.up, SETTINGS_CONTROL_LABELS.rail.down,
            ...(config.surfaceVisibility !== undefined || config.surfaceOrder !== undefined ? [SETTINGS_CONTROL_LABELS.rail.reset] : [])];
        }, labelKey: 'settings.section.rail' },
        { key: 'chat-width', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['chat-width']), labelKey: 'settings.section.chat-width' },
        { key: 'accessibility', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['accessibility']), labelKey: 'settings.section.accessibility' },
      ],
    }],
  },
  {
    id: 'security-privacy', labelKey: 'settings.category.security-privacy', categories: [{
      id: 'security-privacy', kind: 'native', labelKey: 'settings.category.security-privacy', sections: [
        { key: 'core.security-surface', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['core.security-surface']), labelKey: 'registry-surface.core.security-surface.label', memberSurfaceId: 'core.security-surface' },
        { key: 'durable-grants', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['durable-grants']), labelKey: 'settings.section.durable-grants' },
        { key: 'plugin-permissions', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['plugin-permissions']), labelKey: 'settings.section.plugin-permissions', audience: operatorOrAbove },
        { key: 'agent-autonomy', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['agent-autonomy']), labelKey: 'settings.section.agent-autonomy' },
        { key: 'feedback-capture', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['feedback-capture']), labelKey: 'settings.section.feedback-capture' },
      ],
    }],
  },
  {
    id: 'data-reset', labelKey: 'settings.category.data-reset', danger: true, categories: [{
      id: 'data-reset', kind: 'native', labelKey: 'settings.category.data-reset', sections: [
        { key: 'reset-settings', controlWords: () => [present({ kind: 'operation', id: 'core.reset-settings' }).label], labelKey: 'settings.section.reset-settings', audience: resetSettingsOffered, savedSettings: true },
        { key: 'data', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['data']), labelKey: 'settings.section.data' },
      ],
    }],
  },
  {
    id: 'plugins', labelKey: 'settings.category.plugins', categories: [{
      id: 'plugins', kind: 'native', labelKey: 'settings.category.plugins', sections: [
        { key: 'plugins', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['plugins']), labelKey: 'settings.section.plugins' },
        { key: 'delivered-contributions', labelKey: 'settings.section.delivered-contributions' },
      ],
    }],
  },
  {
    id: 'advanced', labelKey: 'settings.category.advanced', categories: [{
      id: 'advanced', kind: 'native', labelKey: 'settings.category.advanced', sections: [
        { key: 'show-developer-tools', labelKey: 'settings.section.show-developer-tools' },
        { key: 'view-tier', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['view-tier']), labelKey: 'settings.section.view-tier', developerTool: true },
        { key: 'workspace-profiles', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['workspace-profiles']), labelKey: 'settings.section.workspace-profiles', audience: developerOnly, developerTool: true },
      ],
    }],
  },
  {
    id: 'about', labelKey: 'settings.category.about', categories: [{
      id: 'about', kind: 'native', labelKey: 'settings.category.about', sections: [
        { key: 'version', labelKey: 'settings.section.version' },
        { key: 'update-information', controlWords: () => Object.values(SETTINGS_CONTROL_LABELS['update-information']), labelKey: 'settings.section.update-information' },
      ],
    }],
  },
];

export interface SettingsCoordinate {
  readonly section: string;
  readonly anchor?: string;
}

/** Historical category defaults. Entry destinations are derived from the register below. */
const CATEGORY_ALIASES: Readonly<Record<string, string>> = {
  appearance: 'interface', layout: 'layout', notifications: 'notifications',
  agent: 'agent-autonomy', indexing: 'indexing', desktop: 'desktop',
  developer: 'show-developer-tools', data: 'data', plugins: 'plugins',
  'privacy-trust': 'core.security-surface',
};

/** Explicit entries outrank category defaults, including entries moved to another page.
 * Undefined means an unknown coordinate: callers keep the current page (or General on first use).
 * Audience restrictions are handled by the page; resolving never changes a preference. */
export function resolveSettingsCoordinate(id?: string, anchor?: string): SettingsCoordinate | undefined {
  const categories = allCategories();
  const entry = (key: string): SettingsCoordinate | undefined => {
    const category = categories.find(c => c.sections?.some(s => s.key === key));
    return category ? { section: category.id, anchor: key } : undefined;
  };
  if (anchor) {
    const exact = entry(anchor);
    if (exact) return exact;
  }
  if (!id) return undefined;
  // Canonical pages have no implicit anchor. Historical appearance/plugins addresses retain
  // their original first row, which is also the canonical page's first row.
  if (Object.hasOwn(CATEGORY_ALIASES, id)) return entry(CATEGORY_ALIASES[id]!);
  if (findCategory(id)) return { section: id };
  return entry(id);
}

export function entryLabel(entry: SettingsSectionEntry): string {
  return entry.memberSurfaceId
    ? present({ kind: 'surface', id: entry.memberSurfaceId }).label
    : localizeResourceKey(entry.labelKey);
}

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

/** The declaration renderer and search resolve the same control schemas and enum labels. */
function interfaceControlWords(): readonly string[] {
  const body = activeBodyFor(SETTINGS_INTERFACE_REGION);
  if (!body) return Object.values(SETTINGS_CONTROL_LABELS.interface);
  const words: string[] = [];
  const visit = (element: UISchemaElement): void => {
    if (element.type === 'Control') {
      const control = element as ControlElement;
      const schema = Resolve.schema(body.schema, control.scope, body.schema);
      if (typeof control.label === 'string') words.push(control.label);
      if (schema?.title) words.push(schema.title);
      const labels = (schema as { 'x-enum-labels'?: Record<string, string> } | undefined)?.['x-enum-labels'];
      for (const value of schema?.enum ?? []) words.push(labels?.[String(value)] ?? String(value));
    }
    for (const child of (element as UISchemaElement & { elements?: UISchemaElement[] }).elements ?? []) visit(child);
  };
  visit(body.uischema);
  return words;
}

/** A destination a future palette provider can use without mounting Settings. */
export type SettingsSearchDestination =
  | { readonly kind: 'settings'; readonly section: string; readonly anchor?: string }
  | { readonly kind: 'indexing-hours' | 'reading-memory' };

/** One search hit: a category or an individual registered row and its final destination. */
export interface SettingsSearchResult {
  readonly group: SettingsGroup;
  readonly category: SettingsCategory;
  readonly section?: SettingsSectionEntry;
  readonly destination: SettingsSearchDestination;
  readonly label: string;
}

/** Unicode words, including numbers: punctuation separates words, never matches inside one. */
function words(text: string): string[] {
  return text.toLocaleLowerCase().match(/[\p{L}\p{N}]+/gu) ?? [];
}

/** All query words must match; a row label outranks keywords, then its controls. Register order
 * breaks ties. Category/group matches retain their existing category-only destinations. */
export function searchRegister(
  query: string,
  register: readonly SettingsGroup[] = SETTINGS_REGISTER,
  viewer?: SettingsViewer,
): SettingsSearchResult[] {
  const queryWords = words(query);
  if (!queryWords.length) return [];
  const results: Array<{ result: SettingsSearchResult; rank: number }> = [];
  const rank = (labels: readonly string[], keywords: readonly string[] = [], controls: readonly string[] = []): number | undefined => {
    const sources = [labels, keywords, controls].map(source => source.flatMap(words));
    const hits = queryWords.map(word => sources.findIndex(source => source.some(candidate => candidate.startsWith(word))));
    return hits.includes(-1) ? undefined : Math.max(...hits);
  };
  const push = (group: SettingsGroup, category: SettingsCategory, score: number | undefined, section?: SettingsSectionEntry): void => {
    if (score === undefined) return;
    const destination: SettingsSearchDestination = section?.destination
      ? { kind: section.destination }
      : { kind: 'settings', section: category.id, ...(section ? { anchor: section.key } : {}) };
    const label = section?.destination === 'indexing-hours'
      ? 'Library › When to index'
      : section?.destination === 'reading-memory'
        ? 'AI Brain › Reading memory'
        : section ? entryLabel(section) : categoryLabel(category);
    results.push({ rank: score, result: { group, category, section, destination, label } });
  };
  for (const group of register) {
    for (const category of group.categories) {
      push(group, category, rank([localizeResourceKey(group.labelKey), categoryLabel(category)]));
      for (const section of shownEntries(category, viewer)) {
        push(group, category, rank([entryLabel(section)], section.keywords, [
          ...(section.controlWords?.() ?? []),
          ...(section.destination === 'indexing-hours' ? ['Library', 'When to index']
            : section.destination === 'reading-memory' ? ['AI Brain', 'Reading memory'] : []),
        ]), section);
      }
    }
  }
  return results.sort((a, b) => a.rank - b.rank).map(({ result }) => result);
}
