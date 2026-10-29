// SPDX-License-Identifier: Apache-2.0
/**
 * Search mode in the Search v3 window (WP1.1): the window's half of a plain search, and the chosen
 * files an "Ask about these files" is limited to.
 *
 * NO SEARCH CLIENT. Every request goes through the SHARED store (`state/searchState.ts`) — the
 * debounced quick pass, the refined pass on settle or Enter, and the generation guard that drops a
 * superseded response — and every filter is the shared one (`state/searchFiltersState.ts`, the
 * store's scope chips). The default window reads the same stores, so a filter set in either window
 * restricts both, and is shown in both.
 *
 * What lives HERE is what the store cannot know: whether this window is showing its result list,
 * which rows the reader has chosen, the files a prepared Ask is limited to, and which answered turn
 * was limited to which files. None of it is persisted — a search is not a turn, and the store keeps
 * no scope with a turn (`ConversationEngine.persistedAssistant` writes neither `docIds` nor
 * `fileCount`), so a reloaded turn has unknown inputs and cannot safely be replayed.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { localizeResourceKey } from '../../../i18n/resourceCatalog.js';
import {
  getScopeChips,
  invalidateSearchRestrictions,
  setQuery,
  submitSearch,
  subscribeScopeChips,
  subscribeSearch,
  type SearchHit,
  type SearchScopeChip,
  type SearchState,
} from '../../state/searchState.js';
import {
  getFacetSelections,
  getFilters,
  restrictionWire,
  subscribeFacetSelections,
  subscribeFilters,
  type SearchFilterSpec,
} from '../../state/searchFiltersState.js';
import { restrictionLabels, type RestrictionKind } from './sv3-filter-input.js';
import { FACET_LABELS } from '../../components/searchResults/facetChips.js';
import { clearSelection, getSelection, setSelection, DEFAULT_CAPABILITIES_BY_KIND } from '../../state/selectionState.js';
import type { Availability } from '../../state/availability.js';
import { forgetContainsPath, type ForgetScope } from '../../../api/domains/forget.js';

/** The files one Ask is limited to — document PATHS, which is what the backend's filter matches. */
export interface Sv3AskScope {
  readonly paths: readonly string[];
}

/** A hit, as far as choosing it needs: its row id and its document's path. */
export interface Sv3ChoosableHit {
  readonly id: string;
  readonly path: string;
}

const basename = (path: string): string => path.split(/[\\/]/).pop() || path;

/**
 * The chosen rows as a scope: their documents, each once, in list order. `null` — never an empty
 * set — for a choice that names no document, because an empty `docIds` is the backend's word for
 * "the whole index" and a reader who chose files must never be answered from all of them.
 */
export function sv3ScopeFromHits(
  hits: readonly Sv3ChoosableHit[],
  ids: Iterable<string>,
): Sv3AskScope | null {
  const chosen = new Set(ids);
  const paths: string[] = [];
  for (const hit of hits) {
    if (!chosen.has(hit.id) || hit.path === '' || paths.includes(hit.path)) continue;
    paths.push(hit.path);
  }
  return paths.length === 0 ? null : { paths };
}

/** A single document as a scope, refusing a hit with no document path. */
export function sv3ScopeFromPath(path: string): Sv3AskScope | null {
  return path.trim() === '' ? null : { paths: [path] };
}

/**
 * The choice, minus the rows the current list no longer holds. The SAME set when nothing left, so a
 * re-render with an unchanged list does not look like a change.
 */
export function sv3PruneSelection(
  selected: ReadonlySet<string>,
  hits: readonly Sv3ChoosableHit[],
): ReadonlySet<string> {
  const present = new Set(hits.map((hit) => hit.id));
  if ([...selected].every((id) => present.has(id))) return selected;
  return new Set([...selected].filter((id) => present.has(id)));
}

/** How many documents a choice of rows names — several hits from one document count once. */
function sv3ChosenDocumentCount(
  hits: readonly Sv3ChoosableHit[],
  selected: ReadonlySet<string>,
): number {
  return sv3ScopeFromHits(hits, selected)?.paths.length ?? 0;
}

export const sv3AskAboutLabel = (count: number): string =>
  count === 1 ? 'Ask about this file' : `Ask about these ${count} files`;

/** The scope in a reader's words: the file's name, or a count and the first few names. */
export function sv3ScopeLabel(scope: Sv3AskScope): string {
  const names = scope.paths.map(basename);
  if (names.length === 1) return names[0] ?? '';
  const shown = names.slice(0, 3).join(', ');
  return `${names.length} files: ${shown}${names.length > 3 ? ', …' : ''}`;
}

/** What a Retry or an Edit of a turn will search, said before it is pressed. */
export function sv3ResendScopeNote(scope: Sv3AskScope | null | undefined, filters: SearchFilterSpec = {}): string {
  if (scope === undefined) return 'Original Ask inputs are unknown. Prepare a new Ask and review its files.';
  if (scope === null) {
    const labels = restrictionLabels(filters).map(f => f.label);
    return labels.length ? `${localizeResourceKey('search.filters.original')} ${labels.join('; ')}` : 'Searches all your files';
  }
  return scope.paths.length === 1 ? 'Uses the same file' : `Uses the same ${scope.paths.length} files`;
}

/** The rows on screen answered an earlier query than the one the store now holds. */
export const sv3ResultsStale = (snapshot: { readonly query: string }, answered: string): boolean =>
  snapshot.query.trim() !== answered.trim();

/** One restriction on the list, as the filter row shows it and its remove control names it. */
export type Sv3ActiveFilter =
  | { readonly key: string; readonly kind: 'scope'; readonly index: number; readonly label: string }
  | { readonly key: RestrictionKind; readonly kind: RestrictionKind; readonly label: string }
  | {
      readonly key: string;
      readonly kind: 'facet';
      readonly field: string;
      readonly value: string;
      readonly label: string;
    };

/**
 * EVERY restriction the shared stores put on this list, so none of them restricts it invisibly —
 * including ones set in the default window (a pinned file) or by a plugin (the date range, which no
 * control in either window sets).
 */
function sv3ActiveFilters(
  chips: readonly SearchScopeChip[],
  dates: SearchFilterSpec,
  facets: Readonly<Record<string, readonly string[]>>,
): readonly Sv3ActiveFilter[] {
  const out: Sv3ActiveFilter[] = chips.map((chip, index) => ({
    key: `scope-${index}`,
    kind: 'scope' as const,
    index,
    label: `Only ${chip.label}`,
  }));
  for (const { kind, label } of restrictionLabels(dates)) out.push({ key: kind, kind, label });
  for (const [field, values] of Object.entries(facets)) {
    for (const value of values) {
      out.push({
        key: `facet-${field}-${value}`,
        kind: 'facet',
        field,
        value,
        label: `${FACET_LABELS[field] ?? field}: ${value}`,
      });
    }
  }
  return out;
}

type Sv3ResultsStatus = 'idle' | 'loading' | 'ready' | 'empty' | 'unreachable';

/** Everything the result list renders, derived once per window render. */
export interface Sv3ResultsView {
  readonly status: Sv3ResultsStatus;
  readonly query: string;
  /** The store snapshot the shared card renders — only ever the CURRENT query's. */
  readonly snapshot: SearchState | null;
  readonly failure: string;
  readonly selectedIds: ReadonlySet<string>;
  readonly chosenCount: number;
  readonly facetSelections: Record<string, string[]>;
  readonly filters: readonly Sv3ActiveFilter[];
  /** Whether an Ask about the chosen files can run, with the existing reason when it cannot. */
  readonly askAvailability: Availability;
}

export const SV3_RESULTS_IDLE: Sv3ResultsView = {
  status: 'idle',
  query: '',
  snapshot: null,
  failure: '',
  selectedIds: new Set(),
  chosenCount: 0,
  facetSelections: {},
  filters: [],
  askAvailability: { kind: 'available' },
};

/**
 * The window's search state. A Lit controller so its subscriptions follow the window's connection,
 * and so the window file gains wiring rather than a second copy of this bookkeeping.
 */
export class Sv3SearchController implements ReactiveController {
  /** The store's latest emission. */
  snapshot: SearchState | null = null;
  /** The result list is what the window is showing (the results "place"). */
  showing = false;
  /** Rows the reader has chosen, by hit id. */
  selected: ReadonlySet<string> = new Set();
  /** The files the next Ask from the result list is limited to, fixed when it was prepared. */
  pendingScope: Sv3AskScope | null = null;
  /** The query the rows on screen answered — the stale guard's other half. */
  private answered = '';
  /**
   * The query as this window last knew it: what it wrote itself, or what it last saw. The store
   * holding another one means the default window (or an address) moved the SHARED query, which
   * this window has not followed yet — `sharedMove` holds the query it moved away from.
   */
  private known = '';
  private sharedMove: { readonly previous: string } | null = null;
  private lastResults: readonly SearchHit[] | null = null;
  private chips: readonly SearchScopeChip[] = getScopeChips();
  private dates: SearchFilterSpec = getFilters();
  private facets: Record<string, string[]> = getFacetSelections();
  /** Which answered turn was limited to which files. Window-local, so a reload forgets it honestly. */
  private readonly turnFilters = new Map<string, SearchFilterSpec>();
  private readonly turnScopes = new Map<string, Sv3AskScope | null>();
  private unsubscribers: Array<() => void> = [];

  constructor(private readonly host: ReactiveControllerHost, private readonly filtersChanged: () => void = () => {}) {
    host.addController(this);
  }

  hostConnected(): void {
    this.unsubscribers = [
      subscribeSearch((snapshot) => this.onSnapshot(snapshot)),
      subscribeScopeChips((chips) => {
        this.chips = chips;
        this.host.requestUpdate();
      }),
      subscribeFilters((spec) => {
        const changed = JSON.stringify(restrictionWire(spec)) !== JSON.stringify(restrictionWire(this.dates));
        this.dates = spec;
        if (changed) { this.selected = new Set(); invalidateSearchRestrictions(); this.filtersChanged(); }
        this.host.requestUpdate();
      }),
      subscribeFacetSelections((selections) => {
        this.facets = selections;
        this.host.requestUpdate();
      }),
    ];
    if (this.showing) this.publishSelection();
  }

  hostDisconnected(): void {
    for (const unsubscribe of this.unsubscribers) unsubscribe();
    this.unsubscribers = [];
    this.clearPublishedSelection();
  }

  private onSnapshot(snapshot: SearchState): void {
    // A new result array is a response that passed the store's generation guard, so it answers the
    // query the store holds at that moment. Anything else (a keystroke, a pass starting) leaves the
    // rows answering whatever they answered before.
    // Every emission is checked, the immediate one on (re)connect included: that is how a search
    // made in the default window while this one was away reaches it.
    if (snapshot.query !== this.known) {
      this.sharedMove ??= { previous: this.known };
      this.known = snapshot.query;
    }
    if (snapshot.results !== this.lastResults) {
      this.lastResults = snapshot.results;
      this.answered = snapshot.query;
      this.selected = sv3PruneSelection(this.selected, snapshot.results);
    }
    this.snapshot = snapshot;
    if (this.showing && getSelection().surfaceId === 'core.search-v3-surface') this.publishSelection();
    this.host.requestUpdate();
  }

  /** This window has a result list to return to (Back/Forward onto the bare address). */
  private hasList = false;

  get hasPlace(): boolean {
    return this.hasList && this.query.trim() !== '';
  }

  show(): void {
    this.showing = true;
    this.publishSelection();
    this.hasList = true;
    this.host.requestUpdate();
  }

  hide(): void {
    this.showing = false;
    this.clearPublishedSelection();
    this.host.requestUpdate();
  }

  /** A pass is running for the list this window shows — the session row's activity dot. */
  get busy(): boolean {
    return this.showing && (this.snapshot?.isSearching === true || this.snapshot?.isRefining === true);
  }

  /** The store's current query — what the result list is a list for. */
  get query(): string {
    return this.snapshot?.query ?? '';
  }

  /**
   * The shared query moved without this window — the query it moved AWAY from, once, or `null`.
   * The window decides what follows; the controller only notices.
   */
  takeSharedMove(): { readonly previous: string } | null {
    const move = this.sharedMove;
    this.sharedMove = null;
    return move;
  }

  /** A keystroke in Search mode: the staged quick-then-refined path, exactly as the default window. */
  type(text: string): void {
    this.write(text);
  }

  /** Enter in Search mode: the full pass now, superseding any staged one. */
  submit(text: string): void {
    this.write(text);
    submitSearch();
  }

  private write(text: string): void {
    this.known = text;
    if (text !== this.query) setQuery(text);
  }

  /** Re-run the current query after a filter changed — the store deliberately does not. */
  rerun(): void {
    if (this.query.trim() !== '') submitSearch();
  }

  select(ids: Iterable<string>): void {
    this.selected = sv3PruneSelection(new Set(ids), this.snapshot?.results ?? []);
    this.publishSelection();
    this.host.requestUpdate();
  }

  /** Confirmed removal changes only the live choice/prepared Ask; saved turn scopes stay intact. */
  reconcileForgotten(path: string, scope: ForgetScope): boolean {
    const forgotten = new Set((this.snapshot?.results ?? [])
      .filter(hit => forgetContainsPath(path, scope, hit.path)).map(hit => hit.id));
    this.selected = new Set([...this.selected].filter(id => !forgotten.has(id)));
    this.publishSelection();
    let clearedScope = false;
    if (this.pendingScope !== null) {
      const paths = this.pendingScope.paths.filter(candidate => !forgetContainsPath(path, scope, candidate));
      clearedScope = paths.length === 0;
      this.pendingScope = paths.length ? { paths } : null;
    }
    this.host.requestUpdate();
    this.rerun(); // The current query/filter store re-reads real results, counts and facets.
    return clearedScope;
  }

  private clearPublishedSelection(): void {
    if (getSelection().surfaceId === 'core.search-v3-surface') clearSelection();
  }

  private publishSelection(): void {
    const hits = (this.snapshot?.results ?? []).filter(hit => this.selected.has(hit.id));
    if (!this.showing || hits.length === 0 || this.snapshot?.query !== this.answered || this.restrictionsStale) {
      this.clearPublishedSelection();
      return;
    }
    const items = hits.length > 1
      ? [{ kind: 'result-set' as const, items: [...new Set(hits.map(hit => hit.path))].map(id => ({ id, kind: 'doc' as const })), query: this.query, capabilities: DEFAULT_CAPABILITIES_BY_KIND['result-set'] }]
      : hits.map(hit => ({ kind: 'search-hit' as const, hitId: hit.id, title: hit.title, path: hit.path, capabilities: DEFAULT_CAPABILITIES_BY_KIND['search-hit'] }));
    setSelection({ items, primaryIndex: 0, surfaceId: 'core.search-v3-surface' });
  }

  /** Fix the chosen rows as the next Ask's files. `null` when they name no document. */
  scopeFromSelection(): Sv3AskScope | null {
    return this.restrictionsStale ? null : sv3ScopeFromHits(this.snapshot?.results ?? [], this.selected);
  }

  prepareFromSelection(): Sv3AskScope | null {
    return this.prepare(this.scopeFromSelection());
  }

  prepare(scope: Sv3AskScope | null): Sv3AskScope | null {
    if (scope === null) return null;
    this.pendingScope = scope;
    this.host.requestUpdate();
    return scope;
  }

  clearScope(): void {
    this.pendingScope = null;
    this.host.requestUpdate();
  }

  /** The prepared scope, consumed: it belongs to ONE Ask. */
  takeScope(): Sv3AskScope | null {
    const scope = this.pendingScope;
    this.pendingScope = null;
    return scope;
  }

  recordTurnScope(turnId: string, scope: Sv3AskScope | null, filters: SearchFilterSpec = {}): void {
    this.turnFilters.set(turnId, filters);
    this.turnScopes.set(turnId, scope);
  }

  filtersOfTurn(turnId: string): SearchFilterSpec { return this.turnFilters.get(turnId) ?? {}; }

  private get restrictionsStale(): boolean {
    return (this.snapshot?.restrictionsKey ?? '{}') !== JSON.stringify(restrictionWire(this.dates));
  }

  scopeOfTurn(turnId: string): Sv3AskScope | null {
    return this.turnScopes.get(turnId) ?? null;
  }

  hasTurnScope(turnId: string): boolean { return this.turnScopes.has(turnId); }

  get filtersByTurn(): ReadonlyMap<string, SearchFilterSpec> { return this.turnFilters; }

  get scopesByTurn(): ReadonlyMap<string, Sv3AskScope | null> { return this.turnScopes; }

  /** New session: the result list, its choice and its prepared Ask are all left behind. */
  reset(): void {
    this.showing = false;
    this.hasList = false;
    this.selected = new Set();
    this.clearPublishedSelection();
    this.pendingScope = null;
    this.host.requestUpdate();
  }

  view(askAvailability: Availability): Sv3ResultsView {
    const snapshot = this.snapshot;
    if (!this.showing || snapshot === null || snapshot.query.trim() === '') return SV3_RESULTS_IDLE;
    const base = {
      query: snapshot.query.trim(),
      selectedIds: this.selected,
      chosenCount: this.restrictionsStale ? 0 : sv3ChosenDocumentCount(snapshot.results, this.selected),
      facetSelections: this.facets,
      filters: sv3ActiveFilters(this.chips, this.dates, this.facets),
      askAvailability,
    };
    // In flight beats every earlier conclusion; a failure beats rows that answered something else;
    // and rows that answered an earlier query are never shown as this one's.
    if (snapshot.isSearching) return { ...base, status: 'loading', snapshot: null, failure: '' };
    if (typeof snapshot.error === 'string' && snapshot.error !== '') {
      return { ...base, status: 'unreachable', snapshot: null, failure: snapshot.error };
    }
    if (sv3ResultsStale(snapshot, this.answered) || this.restrictionsStale) {
      return { ...base, status: 'loading', snapshot: null, failure: '' };
    }
    return {
      ...base,
      status: snapshot.results.length === 0 ? 'empty' : 'ready',
      snapshot,
      failure: '',
    };
  }
}
