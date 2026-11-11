// SPDX-License-Identifier: Apache-2.0
/** Five fixed providers. Display identity never determines invocation. */
import { listActions, getAllActions, getAction, subscribeActions, type Action } from '../substrates/actions/index.js';
import { getShellContext, subscribeShellContext } from '../state/shellContextState.js';
import { evaluateWhen } from './whenExpression.js';
import { getActiveProfileId, getRecentCommandIds, subscribeDocument, subscribeProfileSwitch } from '../state/UserStateDocument.js';
import { commandLabel, __getAllCommandsForPalette, getCommand, invokePaletteCommand, onCommandRegistryChange, parsePaletteQuery, type Command } from './CommandRegistry.js';
import { effectiveShortcut, onKeybindingChange } from './KeybindingRegistry.js';
import { SETTINGS_REGISTER, searchRegister, shownEntries, categoryLabel, entryLabel, type SettingsSearchDestination, type SettingsViewer } from '../views/settingsRegister.js';
import { getIndexedRoots, readIndexedRoots, subscribeIndexedRoots } from '../state/indexedRoots.js';
import { getConversationListState, subscribeConversationList, loadConversations } from '../state/conversationListStore.js';
import { listSurfaces, onSurfaceCatalogChange } from '../../api/registry/SurfaceCatalogClient.js';
import { FRAME_AREAS, FRAME_FIXED_WINDOWS, frameWindows } from '../layout/frameAreas.js';
import { getUserConfig, subscribeUserConfig } from '../state/userConfigState.js';
import { present } from '../display/present.js';
import { folderName } from '../state/frameStatus.js';
import { requestLibraryRoot } from '../router/libraryRootIntent.js';
import { requestSurfaceNavigation, requestIndexingRuleNavigation, requestReadingMemoryNavigation } from '../controllers/navigateRequest.js';
import { subscribeShowDeveloperTools } from '../state/developerToolsState.js';
import { subscribeViewerAudience } from '../state/viewerAudienceState.js';
import { getAiState, subscribeAiState } from '../state/aiStateStore.js';
import { emitEphemeralToast } from '../components/advisory/ephemeralToast.js';
import { getSettingsV2 } from '../../api/domains/settings.js';

type PaletteGroup = 'Actions' | 'Settings' | 'Folders' | 'Recent conversations' | 'Areas';
type PaletteTarget =
  | { readonly kind: 'action'; readonly action: Action; readonly commandId: string }
  | { readonly kind: 'command'; readonly command: Command; readonly commandId: string }
  | { readonly kind: 'settings'; readonly destination: SettingsSearchDestination }
  | { readonly kind: 'folder'; readonly pathHash: string; readonly action: 'open' | 'remove' }
  | { readonly kind: 'conversation'; readonly conversationId: string }
  | { readonly kind: 'area'; readonly surfaceId: string }
  | { readonly kind: 'search'; readonly text: string };
interface PaletteResultAction { readonly label: string; readonly target: PaletteTarget }
export interface PaletteEntry {
  readonly id: string;
  readonly group: PaletteGroup;
  readonly category: string;
  readonly label: string;
  readonly description: string;
  readonly target: PaletteTarget;
  readonly actions: readonly PaletteResultAction[];
  readonly shortcut?: string;
  readonly icon?: string;
  readonly origin: 'action' | 'command' | 'intent' | 'settings' | 'folder' | 'conversation' | 'area';
  readonly intentText?: string;
  readonly keywords?: readonly string[];
  readonly activity?: number;
}
export interface ScoredPaletteEntry { entry: PaletteEntry; score: number; matches: number[] }
const GROUPS: readonly PaletteGroup[] = ['Actions', 'Settings', 'Folders', 'Recent conversations', 'Areas'];
/** Independent of the runtime's default locale, so equal tiers order the same everywhere. */
const NAMES = new Intl.Collator('en');
function legacyCommandIdFor(actionId: string): string {
  return /^core\.action\.(shell|op|template)\./.test(actionId) ? actionId.slice('core.action.'.length) : actionId;
}
function result(id: string, group: PaletteGroup, label: string, description: string, target: PaletteTarget,
  origin: PaletteEntry['origin'], primary = 'Open'): PaletteEntry {
  return { id, group, category: group, label, description, target, origin, actions: [{ label: primary, target }] };
}
function coreLabel(label: string, core: boolean): string {
  return core ? label.replace(/\s*\(Action\)$/i, '').replace(/\s+action$/i, '').replace(/\bBrain\b/g, 'AI Brain').replace(/AI AI Brain/g, 'AI Brain') : label;
}
function viewer(): SettingsViewer {
  return { capabilities: new Set(getShellContext().platformCapabilities.split(',').filter(Boolean)),
    settingsRecovery: getAiState().status?.operating?.indexing?.reasons.includes('settings_recovery') ?? false,
    settingsReadOnly: false };
}
function pool(query: string, settingsViewer: SettingsViewer): PaletteEntry[] {
  const entries: PaletteEntry[] = [];
  const canonical = new Set(getAllActions().map(action => legacyCommandIdFor(action.id)));
  for (const action of listActions()) {
    if (action.appliesTo?.length) continue;
    const id = legacyCommandIdFor(action.id);
    entries.push({ ...result(id, 'Actions', coreLabel(action.title, action.provenance.tier === 'CORE'), '',
      { kind: 'action', action, commandId: id }, 'action', 'Run'), shortcut: effectiveShortcut(id), icon: action.icon });
  }
  for (const command of __getAllCommandsForPalette()) {
    if (canonical.has(command.id) || !evaluateWhen(command.when, getShellContext() as unknown as Record<string, unknown>)) continue;
    entries.push({ ...result(command.id, 'Actions', coreLabel(commandLabel(command), command.source !== 'plugin'), command.category ?? '',
      { kind: 'command', command, commandId: command.id }, 'command', 'Run'), shortcut: effectiveShortcut(command.id), icon: command.icon });
  }
  const settings = query ? searchRegister(query, SETTINGS_REGISTER, settingsViewer) : SETTINGS_REGISTER.flatMap(group =>
    group.categories.flatMap(category => [
      { label: categoryLabel(category), destination: { kind: 'settings' as const, section: category.id }, category, section: undefined },
      ...shownEntries(category, settingsViewer).map(section => ({ label: section.destination === 'indexing-hours' ? 'Library › When to index'
        : section.destination === 'reading-memory' ? 'AI Brain › Reading memory' : entryLabel(section), category, section,
      destination: section.destination ? { kind: section.destination } : { kind: 'settings' as const, section: category.id, anchor: section.key } })),
    ]));
  for (const hit of settings) entries.push({ ...result(`settings-row:${hit.category.id}:${hit.section?.key ?? ''}`, 'Settings', hit.label,
    categoryLabel(hit.category), { kind: 'settings', destination: hit.destination }, 'settings'),
    keywords: [...(hit.section?.keywords ?? []), ...(hit.section?.controlWords?.() ?? [])] });
  const roots = getIndexedRoots();
  for (const root of roots.roots) {
    if (!root.pathHash) continue;
    const path = roots.paths[root.pathHash];
    const target: PaletteTarget = { kind: 'folder', pathHash: root.pathHash, action: 'open' };
    entries.push({ ...result(`folder:${root.pathHash}`, 'Folders', path ? folderName(path) : 'Folder (path unavailable)', path ?? '', target, 'folder', 'Open in Library'),
      actions: [{ label: 'Open in Library', target }, { label: 'Remove from Library', target: { ...target, action: 'remove' } }] });
  }
  for (const conversation of getConversationListState().conversations) {
    if (!conversation.title && !conversation.firstUserMessage) continue;
    entries.push({ ...result(`conversation:${conversation.id}`, 'Recent conversations', conversation.title || conversation.firstUserMessage,
      conversation.firstUserMessage, { kind: 'conversation', conversationId: conversation.id }, 'conversation'), activity: conversation.lastActiveAt });
  }
  const windows = frameWindows(listSurfaces(), getUserConfig());
  const ordered = [...FRAME_AREAS.map(area => windows.find(window => window.id === area.id)).filter(window => window !== undefined),
    ...windows.filter(window => !FRAME_AREAS.some(area => area.id === window.id) && !FRAME_FIXED_WINDOWS.has(window.id))];
  for (const window of ordered) entries.push(result(`area:${window.id}`, 'Areas', present({ kind: 'surface', id: window.id }).label,
    '', { kind: 'area', surfaceId: window.id }, 'area'));
  return entries;
}
const words = (text: string): string[] => text.toLocaleLowerCase().match(/[\p{L}\p{N}]+/gu) ?? [];
function tier(query: string, entry: PaletteEntry): number | undefined {
  const needles = words(query), label = words(entry.label);
  const match = (haystack: string[]): boolean => needles.every(needle => haystack.some(word => word.startsWith(needle)));
  if (entry.label.toLocaleLowerCase() === query.toLocaleLowerCase()) return 5;
  if (needles.every(needle => label.includes(needle))) return 4;
  if (match(label)) return 3;
  if (match([...label, ...words(entry.keywords?.join(' ') ?? '')])) return 2;
  if (match([...label, ...words(entry.keywords?.join(' ') ?? ''), ...words(entry.description)])) return 1;
  return undefined;
}
function marks(query: string, label: string): number[] {
  const needles = words(query), indices: number[] = [];
  for (const word of label.matchAll(/[\p{L}\p{N}]+/gu)) {
    const needle = needles.find(needle => word[0].toLocaleLowerCase().startsWith(needle));
    if (needle) for (let i = 0; i < needle.length; i++) indices.push(word.index + i);
  }
  return indices;
}
export function searchPaletteEntries(raw: string, settingsViewer = viewer()): ScoredPaletteEntry[] {
  const parsed = parsePaletteQuery(raw), query = parsed.text.trim();
  const entries = pool(query, settingsViewer).filter(entry => {
    if (raw.startsWith('>')) return entry.group === 'Actions';
    if (parsed.mode === 'surfaces') return entry.group === 'Areas' || entry.id.startsWith('shell.go-to-');
    if (parsed.mode === 'settings') return entry.group === 'Settings' || entry.id.startsWith('settings.')
      || (entry.target.kind === 'action' && entry.target.action.category === 'Settings')
      || (entry.target.kind === 'command' && entry.target.command.category === 'Settings');
    return true;
  });
  const recent = getRecentCommandIds();
  const recency = (entry: PaletteEntry): number => {
    const index = recent.indexOf(entry.id);
    return entry.group === 'Actions' && index >= 0 ? recent.length - index : 0;
  };
  const scored = entries.flatMap(entry => {
    const score = query ? tier(query, entry) : 0;
    return score === undefined ? [] : [{ entry, score, matches: query ? marks(query, entry.label) : [] }];
  });
  const groups = GROUPS.map(group => scored.filter(row => row.entry.group === group));
  if (query) {
    // Tier, then command recency or conversation activity, then name and identity in a fixed collation.
    for (const group of groups) group.sort((a, b) => b.score - a.score || recency(b.entry) - recency(a.entry)
      || (b.entry.activity ?? 0) - (a.entry.activity ?? 0) || NAMES.compare(a.entry.label, b.entry.label)
      || (a.entry.id < b.entry.id ? -1 : a.entry.id > b.entry.id ? 1 : 0));
    // Groups by their best tier; equal tiers keep provider order.
    groups.sort((a, b) => (b[0]?.score ?? -1) - (a[0]?.score ?? -1)
      || GROUPS.indexOf(a[0]?.entry.group ?? 'Areas') - GROUPS.indexOf(b[0]?.entry.group ?? 'Areas'));
  } else {
    groups[0]!.sort((a, b) => recency(b.entry) - recency(a.entry));
    groups[3]!.sort((a, b) => (b.entry.activity ?? 0) - (a.entry.activity ?? 0));
  }
  const results = groups.flat();
  if (query && !/^[>#@]/.test(raw)) results.push({ entry: { ...result('intent.search', 'Actions', `Search for "${query}"`, '',
    { kind: 'search', text: query }, 'intent', 'Search'), category: '', intentText: query }, score: 0, matches: [] });
  return results;
}

/**
 * The Conversations provider searches only the roster the app already loads. While it is in scope
 * and holds conversations, say where older ones are, so a missing match never reads as "gone".
 */
export function paletteNotes(raw: string): string[] {
  if (/^[>#@]/.test(raw) || getConversationListState().conversations.length === 0) return [];
  return ['Recent conversations searches only the recent list. Older conversations are outside this list; find them in Search\'s conversation list.'];
}

/** Revalidate identity and eligibility, including the captured registration, before activation. */
export async function invokePaletteEntry(entry: PaletteEntry, target = entry.target, settingsViewer = viewer()): Promise<boolean> {
  const profileId = getActiveProfileId();
  const current = pool('', settingsViewer).find(row => row.id === entry.id);
  if (target.kind !== 'search' && !current) return false;
  if (target.kind === 'action' || target.kind === 'command') {
    const registration = target.kind === 'action' ? target.action : target.command;
    if (target.kind === 'action' ? getAction(registration.id) !== registration : getCommand(registration.id) !== registration) return false;
    return (await invokePaletteCommand(registration, target.commandId)).ok;
  }
  switch (target.kind) {
    case 'settings':
      if (target.destination.kind === 'indexing-hours') requestIndexingRuleNavigation('hours');
      else if (target.destination.kind === 'reading-memory') requestReadingMemoryNavigation();
      else if (target.destination.kind === 'settings') requestSurfaceNavigation('core.settings-surface', { section: target.destination.section, ...(target.destination.anchor ? { anchor: target.destination.anchor } : {}) });
      break;
    case 'folder': {
      const observation = await readIndexedRoots();
      if (profileId !== getActiveProfileId()) return false;
      if (!observation?.roots.some(root => root.pathHash === target.pathHash)) {
        emitEphemeralToast({ message: observation ? 'This folder is no longer in your library.' : 'Folder availability is unconfirmed. Retry when Library can refresh.' });
        return false;
      }
      requestLibraryRoot(target);
      requestSurfaceNavigation('core.library-surface');
      break;
    }
    // One navigation; Search's address path selects it with its own departure checks once connected.
    case 'conversation': requestSurfaceNavigation('core.search-v3-surface', { conversationId: target.conversationId }); break;
    case 'area': requestSurfaceNavigation(target.surfaceId); break;
    case 'search': requestSurfaceNavigation('core.search-v3-surface', { query: target.text }); break;
  }
  return true;
}

/** Watches the existing authorities only while the overlay is open. */
export function watchPaletteEntries(update: (settingsViewer: SettingsViewer) => void): () => void {
  let closed = false, epoch = 0, settingsReadOnly = true;
  const refresh = (): void => { if (!closed) update({ ...viewer(), settingsReadOnly }); };
  const read = async (): Promise<void> => {
    const request = ++epoch;
    settingsReadOnly = true;
    refresh();
    void readIndexedRoots();
    void loadConversations();
    try {
      const settings = await getSettingsV2('');
      if (closed || request !== epoch) return;
      settingsReadOnly = settings.settingsMode === 'in_memory';
      refresh();
    } catch { /* Unknown saved-settings availability stays excluded. */ }
  };
  const stops = [subscribeActions(refresh), onCommandRegistryChange(refresh), onKeybindingChange(refresh),
    subscribeShellContext(refresh), subscribeDocument(refresh), subscribeIndexedRoots(refresh), subscribeConversationList(refresh),
    onSurfaceCatalogChange(refresh), subscribeUserConfig(refresh), subscribeShowDeveloperTools(refresh), subscribeViewerAudience(refresh),
    subscribeAiState(refresh), subscribeProfileSwitch(() => { void read(); })];
  void read();
  return () => { closed = true; epoch++; for (const stop of stops) stop(); };
}
