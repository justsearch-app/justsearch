// SPDX-License-Identifier: Apache-2.0
/**
 * What Search's details pane does beyond holding the open document: the tabs, what goes back to the
 * keyboard, the source controls, and each conversation's memory (design system §7). The window owns
 * the pane's fields and `openPane`, the one writer of the open document; this decides around them.
 */
import { filenameOf, acquisitionChannel, sameCitationHeader, type CitationHeader } from '../../components/chat/evidenceProjection.js';
import type { AnswerEvidenceSource } from '../../components/chat/citationTypes.js';
import type { DocumentCitationAnchor } from '../../components/documentPane/DocumentPane.js';
import type { DetailsTab } from '../../components/detailsPane/DetailsPane.js';
import { pluginDetailsTabs } from '../../components/detailsPane/DetailsPluginTab.js';
import { emitEphemeralToast } from '../../components/advisory/ephemeralToast.js';
import { onInspectorTabChange } from '../../commands/InspectorTabRegistry.js';
import { getDetailsMemory, rememberDetails } from '../../state/detailsMemory.js';
import {
  getExcludedSources,
  setExcludedSources,
  sourceExcludeKey,
  subscribeExcludedSources,
  toggleExcludedSource,
} from '../../state/excludedSources.js';
import type { SelectedItem } from '../../state/inspectorState.js';
import { setSelectedSource, sourceKey } from '../../state/selectedSource.js';
import { deepActiveElement } from '../../utils/keyboardHandler.js';
import { SV3_SOURCE_INDEX_ABSENT, sv3CitationAnchor, sv3CitationHeader, sv3MatchedSentence } from './sv3-citation-anchor.js';
import {
  SV3_PANE_CONTEXT_TAB,
  SV3_PANE_DOCUMENT_TAB,
  sv3SourceSelection,
  sv3SourceTab,
  sv3SourceTabs,
  sv3TabSourceIndex,
} from './sv3-details.js';
import { activeTurns, sessionById, type Sv3SessionList, type Sv3Turn } from './sv3-sessions.js';
import type { Sv3CitationOpen } from './Sv3Main.js';
import type { Sv3Pane, Sv3PaneSourceControl } from './Sv3Pane.js';

/** The pane's fields, which live on the window because they drive its render. */
interface Sv3PaneFields {
  paneTab: string | null;
  paneDocPath: string | null;
  paneSource: { readonly turnId: string; readonly sourceIndex: number; readonly sourceUnavailable?: true } | null;
  paneCitation: DocumentCitationAnchor | null;
  paneCitationHeader: CitationHeader | null;
}

interface DetailsHost {
  sessions(): Sv3SessionList;
  pane(): Sv3PaneFields;
  /** A document handed in by the shared inspector belongs to its caller, not to the conversation. */
  sharedInspector(): boolean;
  openPane(detail: Sv3CitationOpen): void;
  preparePaneBox(): void;
  dismissPane(): void;
  owns(node: Node | null): boolean;
  paneElement(): Sv3Pane | null;
  focusComposer(): void;
  whenUpdated(): Promise<unknown>;
  requestUpdate(): void;
}

export class Sv3DetailsDomain {
  /** The citation or result that opened the pane; it gets the keyboard back on close. */
  private opener: HTMLElement | null = null;
  private lastClicked: HTMLElement | null = null;
  private unsubscribes: Array<() => void> = [];
  /** The conversation {@link syncConversation} last saw. */
  private conversation: string | null = null;
  private restorePending = false;
  private excludedSeen: readonly string[] | undefined = undefined;
  private selected: SelectedItem | null = null;

  constructor(private readonly host: DetailsHost) {}

  connect(): void {
    this.unsubscribes = [
      onInspectorTabChange(() => this.onPluginTabsChange()),
      subscribeExcludedSources(() => this.host.requestUpdate()),
    ];
  }

  disconnect(): void {
    this.unsubscribes.forEach(unsubscribe => unsubscribe());
    this.unsubscribes = [];
  }

  /**
   * What gets the keyboard back when the pane closes (DP1). The focused element when there is one in
   * this window, else what was last clicked: WebKit does not focus a clicked button. A tab inside the
   * pane is not an opener, so moving between tabs keeps the citation that opened it.
   */
  rememberOpener(): void {
    const active = deepActiveElement() as HTMLElement | null;
    const candidate = active !== null && this.host.owns(active) ? active : this.lastClicked;
    if (candidate !== null && !this.inPane(candidate)) this.opener = candidate;
  }

  private inPane(node: Node): boolean {
    const pane = this.host.paneElement();
    for (let at: Node | null = node; at !== null; at = at.parentNode ?? (at as ShadowRoot).host ?? null) {
      if (at === pane) return true;
    }
    return false;
  }

  private ownsPaneFocus(): boolean {
    const active = deepActiveElement();
    return active !== null && this.inPane(active);
  }

  readonly onHostClick = (event: Event): void => {
    const target = event.composedPath().find(node => node instanceof HTMLElement
      && (node.tabIndex >= 0 || node.getAttribute('role') === 'button')) as HTMLElement | undefined;
    this.lastClicked = target ?? null;
  };

  focusPane(): void {
    void this.host.whenUpdated().then(async () => {
      const pane = this.host.paneElement();
      await pane?.updateComplete;
      pane?.focusPane();
    });
  }

  /** A pane that became a sheet dims the column the keyboard may be in; the keyboard comes along. */
  keepKeyboardInSheet(): void {
    if (this.host.pane().paneTab !== null && !this.ownsPaneFocus()) this.focusPane();
  }

  /** Called before the pane closes; the returned step gives the keyboard back once it has. */
  takeFocusReturn(): () => void {
    const { opener } = this;
    const hadFocus = this.ownsPaneFocus();
    this.opener = null;
    return () => {
      if (opener?.isConnected) opener.focus();
      else if (hadFocus) this.host.focusComposer();
    };
  }

  /**
   * Tempdoc 610 §K — the Context tab: what the last completed turn actually sent. A second press
   * closes it, as it always did. No document is needed, so this writes the tab and not `openPane`.
   */
  openContext(): void {
    const pane = this.host.pane();
    if (pane.paneTab === SV3_PANE_CONTEXT_TAB) {
      this.host.dismissPane();
      return;
    }
    this.rememberOpener();
    this.host.preparePaneBox();
    pane.paneTab = SV3_PANE_CONTEXT_TAB;
    this.remember(true);
    this.focusPane();
  }

  /** The turn whose sources the tabs stand for: the open source's, else the newest with evidence. */
  private turn(): Sv3Turn | null {
    const turns = activeTurns(this.host.sessions());
    const turnId = this.host.pane().paneSource?.turnId ?? '';
    return turns.find(turn => turn.id === turnId)
      ?? [...turns].reverse().find(turn => (turn.evidence?.sources.length ?? 0) > 0) ?? null;
  }

  tabs(): DetailsTab[] {
    const { paneTab, paneSource, paneDocPath } = this.host.pane();
    const index = paneTab === null ? null : sv3TabSourceIndex(paneTab);
    const open = paneSource !== null && paneSource.sourceIndex !== SV3_SOURCE_INDEX_ABSENT ? paneSource.sourceIndex : index;
    const sources = sv3SourceTabs(this.turn(), open);
    // A document that is not one of the answer's sources (a result, a plugin's selection) has its own tab.
    const unsourced = paneSource === null || paneSource.sourceIndex === SV3_SOURCE_INDEX_ABSENT;
    const reading: DetailsTab[] = paneDocPath !== null && (unsourced || sources.length === 0)
      ? [{ id: SV3_PANE_DOCUMENT_TAB, label: filenameOf(paneDocPath), title: paneDocPath }] : [];
    return [...sources, ...reading, { id: SV3_PANE_CONTEXT_TAB, label: 'Context', title: 'What the assistant sees' }, ...pluginDetailsTabs()];
  }

  /** What plugin tabs are told is selected: the document on show. One object per document, so a
   *  re-render of the window does not read as a new selection and redraw the plugin. */
  selection(): SelectedItem | null {
    const path = this.host.pane().paneDocPath;
    if (path === null) return null;
    if (this.selected?.path !== path) this.selected = { id: path, title: filenameOf(path), path, kind: 'search-hit' };
    return this.selected;
  }

  readonly onPaneTab = (event: Event): void => {
    const id = (event as CustomEvent<{ id: string }>).detail?.id;
    const pane = this.host.pane();
    if (!id || id === pane.paneTab) return;
    const sourceIndex = sv3TabSourceIndex(id);
    if (sourceIndex !== null) { this.selectSource(this.turn(), sourceIndex); return; }
    // The document stays mounted under the other tabs, so coming back keeps the reader's place.
    pane.paneTab = id;
    this.remember(true);
  };

  /** Move the pane to a source of `turn`, the way following its citation would. */
  private selectSource(turn: Sv3Turn | null, sourceIndex: number): void {
    const selection = sv3SourceSelection(turn, sourceIndex);
    if (turn === null || selection === null) return;
    setSelectedSource(sourceKey(selection.parentDocId, selection.startLine));
    this.host.openPane({ docPath: selection.parentDocId, turnId: turn.id, sourceIndex,
      anchor: sv3CitationAnchor(selection, sv3MatchedSentence(turn, sourceIndex)) });
  }

  private sourceRecord(): AnswerEvidenceSource | null {
    const open = this.host.pane().paneSource;
    if (open === null || open.sourceIndex === SV3_SOURCE_INDEX_ABSENT) return null;
    return this.turn()?.evidence?.sources[open.sourceIndex] ?? null;
  }

  /** The hide/restore words for the source on show. Only a conversation's source can be hidden. */
  sourceControl(): Sv3PaneSourceControl | null {
    const source = this.sourceRecord();
    if (source === null || this.host.sessions().activeId === null) return null;
    const hidden = getExcludedSources().has(sourceExcludeKey(source.parentDocId, source.chunkIndex));
    const name = filenameOf(source.parentDocId);
    return { hidden, label: hidden ? `Restore ${name} to the assistant's ${acquisitionChannel(source)}`
      : `Hide ${name} from the assistant's ${acquisitionChannel(source)}` };
  }

  async toggleHidden(): Promise<void> {
    const source = this.sourceRecord();
    const sessionId = this.host.sessions().activeId;
    if (source === null || sessionId === null) return;
    const key = sourceExcludeKey(source.parentDocId, source.chunkIndex);
    const hide = !getExcludedSources().has(key);
    if (!(await toggleExcludedSource(sessionId, key, hide))) {
      emitEphemeralToast({ message: `Could not ${hide ? 'hide' : 'restore'} ${filenameOf(source.parentDocId)}. Try again.`, severity: 'warning' });
    }
  }

  remember(open: boolean): void {
    const id = this.host.sessions().activeId;
    const { paneTab, paneDocPath, paneSource } = this.host.pane();
    if (id === null || paneTab === null || this.host.sharedInspector()) return;
    rememberDetails(id, { open, tab: paneTab, docPath: paneDocPath ?? '',
      turnId: paneSource?.turnId ?? '', sourceIndex: paneSource?.sourceIndex ?? SV3_SOURCE_INDEX_ABSENT });
  }

  /**
   * The conversation on show changed, or its record arrived. Each conversation gets its own hidden
   * sources and comes back to what its pane last showed. Remembered turns are looked up in the record
   * of the conversation that is active NOW, so a read that lands after a switch restores nothing into
   * the wrong conversation; a turn no longer there restores nothing at all (C4).
   */
  syncConversation(): void {
    const sessions = this.host.sessions();
    const id = sessions.activeId;
    if (id !== this.conversation) {
      this.conversation = id;
      this.restorePending = id !== null;
      this.excludedSeen = undefined;
      setExcludedSources([], id);
    }
    if (id === null) return;
    const excluded = sessionById(sessions, id)?.history?.excludedSourceIds;
    if (excluded !== undefined && excluded !== this.excludedSeen) {
      this.excludedSeen = excluded;
      setExcludedSources(excluded, id);
    }
    if (!this.restorePending) return;
    const memory = getDetailsMemory(id);
    const pane = this.host.pane();
    if (memory === null || !memory.open || pane.paneTab !== null) { this.restorePending = false; return; }
    const turns = activeTurns(sessions);
    if (turns.length === 0) return;
    this.restorePending = false;
    const turn = turns.find(candidate => candidate.id === memory.turnId) ?? null;
    if (memory.sourceIndex !== SV3_SOURCE_INDEX_ABSENT) this.selectSource(turn, memory.sourceIndex);
    else if (memory.docPath !== '') this.host.openPane({ docPath: memory.docPath, anchor: null, turnId: '', sourceIndex: SV3_SOURCE_INDEX_ABSENT });
    if (memory.tab === SV3_PANE_CONTEXT_TAB || pluginDetailsTabs().some(tab => tab.id === memory.tab)) {
      this.host.preparePaneBox();
      pane.paneTab = memory.tab;
      this.remember(true);
    }
  }

  /** A plugin tab that left takes the pane back to what it was reading rather than to a blank tab. */
  private onPluginTabsChange(): void {
    const pane = this.host.pane();
    const tab = pane.paneTab;
    if (tab?.startsWith('plugin:') && !pluginDetailsTabs().some(candidate => candidate.id === tab)) {
      const source = pane.paneSource;
      pane.paneTab = pane.paneDocPath === null ? SV3_PANE_CONTEXT_TAB
        : source !== null && source.sourceIndex !== SV3_SOURCE_INDEX_ABSENT ? sv3SourceTab(source.sourceIndex) : SV3_PANE_DOCUMENT_TAB;
    }
    this.host.requestUpdate();
  }

  /**
   * Tempdoc 849 §7 — the citation header for the turn/source the pane is showing. One helper, two
   * callers, for the same reason `sv3CitationAnchor` has two: the header is resolved when the
   * citation is followed and AGAIN when a late claim match changes what may be said about it, and
   * the two answers must be produced by the same join.
   */
  headerFor(turnId: string, sourceIndex: number, anchor: DocumentCitationAnchor | null): CitationHeader | null {
    const turn = activeTurns(this.host.sessions()).find((candidate) => candidate.id === turnId) ?? null;
    return sv3CitationHeader(turn, sourceIndex, anchor);
  }

  /**
   * Tempdoc 849 §4 — the late claim match. A citation followed while the answer is still streaming
   * has a retrieved chunk and no matched sentence, so the pane opens tinting the passage; when
   * `rag.citation_matches` lands, the open pane gains its sentence and lands the strong emphasis.
   *
   * Only the OPEN pane's own source is upgraded, and only from `null` — a match for a different
   * source does not touch it, and a pane that already has its sentence is not re-anchored by a later
   * projection of the same turn. The reader's `armedHighlightKey` guard does the rest: the new,
   * distinct range arms the strong phase exactly once.
   */
  upgradeOpenPaneAnchor(): void {
    const pane = this.host.pane();
    const source = pane.paneSource;
    if (source === null || source.sourceIndex === SV3_SOURCE_INDEX_ABSENT) return;
    const anchor = pane.paneCitation;

    // The ANCHOR upgrade, which needs an anchor to upgrade and a sentence it does not yet have.
    if (anchor !== null && anchor.sentenceText === null) {
      const turn = activeTurns(this.host.sessions()).find((c) => c.id === source.turnId) ?? null;
      const sentence = sv3MatchedSentence(turn, source.sourceIndex);
      if (sentence !== null) pane.paneCitation = { ...anchor, sentenceText: sentence };
    }

    // The HEADER refresh, which needs neither (review LOW-5). A citation with no usable position is
    // still a citation whose source can be grounded, and the header is the only thing that can say so.
    const next = this.headerFor(source.turnId, source.sourceIndex, pane.paneCitation);
    // Written only on a real change: this runs on every `sessions` update, i.e. every streamed
    // chunk, and a fresh object each time would re-render the reader continuously for a header
    // whose words never moved.
    if (!sameCitationHeader(pane.paneCitationHeader, next)) pane.paneCitationHeader = next;
  }
}
