// SPDX-License-Identifier: Apache-2.0
/**
 * CommandPalette — the app's one palette (Tempdoc 508 §3.3, shell S6).
 *
 * Opened only through the `shell.toggle-palette` action, whose one binding (Ctrl K, Cmd K on macOS)
 * lives in the keybinding registry; Search's topbar button asks the same action. One ranked list over
 * the five providers of `CommandPaletteProjection`; Tab shows the selected result's actions.
 *
 * Keyboard: the field keeps focus; arrows move the active option (`aria-activedescendant`), Enter
 * runs it, Tab shows its actions, Escape or Shift Tab returns from them with query, selection and
 * scroll kept, and Escape on the results closes. Keys that end an IME composition are the IME's.
 *
 * The palette is a native modal `<dialog>` (`showModal()`, through `ModalController`, as Settings
 * is): the top layer paints it above all chrome whatever its z-index, and the platform makes the page
 * behind it inert, so nothing beneath can take a click or a Tab meant for the field.
 */

import { html, css, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { ModalController } from '../primitives/modalController.js';
import { closeOthersInLayer } from '../state/transientLayerArbiter.js';
import { deepActiveElement, isImeComposition } from '../utils/keyboardHandler.js';
import { focusRegion } from '../chrome/frameRegions.js';
import { repeat } from 'lit/directives/repeat.js';
import {
  searchPaletteEntries,
  invokePaletteEntry,
  paletteNotes,
  paletteSourceState,
  watchPaletteEntries,
  type ScoredPaletteEntry,
  type PaletteEntry,
} from './CommandPaletteProjection.js';
import type { SettingsViewer } from '../views/settingsRegister.js';
import { listEmptyStates } from './EmptyStateRegistry.js';
import {
  setSlotPromptProvider,
  type NamedArgumentSlot,
} from './TemplateCatalog.js';

/**
 * Tempdoc 508-followup §δ1 — wizard step: one resolved (or pending)
 * slot prompt. Completed steps appear as breadcrumbs above the input.
 */
interface WizardStep {
  readonly slot: NamedArgumentSlot;
  readonly templateId: string;
  readonly resolver: (value: string | null) => void;
}

/** What identifies a result: its id and target. It carries none of the result's shown text. */
type ResultIdentity = Pick<PaletteEntry, 'id' | 'target'>;

/** One result: the same id and, for runnable results, the same captured registration. */
function sameResult(entry: PaletteEntry, selected: ResultIdentity): boolean {
  return entry.id === selected.id
    && (selected.target.kind !== 'action' || (entry.target.kind === 'action' && entry.target.action === selected.target.action))
    && (selected.target.kind !== 'command' || (entry.target.kind === 'command' && entry.target.command === selected.target.command));
}

const FIRST_BOUND = 8;
const LIST_ID = 'palette-listbox';
const ACTIONS_ID = 'palette-actions';

export class CommandPalette extends JfElement {
  static properties = {
    open: { type: Boolean, reflect: true },
    query: { state: true },
    results: { state: true },
    selectedIndex: { state: true },
    actionsFor: { state: true },
    actionIndex: { state: true },
    wizardActive: { state: true },
    wizardInput: { state: true },
    wizardCompletedLabels: { state: true },
  };

  declare open: boolean;
  declare query: string;
  declare results: ScoredPaletteEntry[];
  declare selectedIndex: number;
  /**
   * The result whose actions are shown (Tab), exactly as the providers give it now, or null while
   * the results are shown or once that result has left them. Its text (heading, items, the status
   * announcement) is read only from here, the same provider snapshot as the results, so a result
   * the providers withdraw (a conversation whose history was observed locked) leaves the actions
   * view the moment it leaves the results.
   */
  declare actionsFor: PaletteEntry | null;
  declare actionIndex: number;
  /** Tempdoc 508-followup §δ1 — palette is in slot-collection mode. */
  declare wizardActive: boolean;
  /** Tempdoc 508-followup §δ1 — current wizard input value (prefilled with default). */
  declare wizardInput: string;
  /** Tempdoc 508-followup §δ1 — completed step breadcrumb labels. */
  declare wizardCompletedLabels: ReadonlyArray<{ name: string; value: string }>;

  /** Tempdoc 508-followup §δ1 — pending wizard step (the slot we're prompting for). */
  private wizardStep: WizardStep | null = null;
  private wizardCancelled = false;
  private wizardInvocationActive = false;
  /** Which result the actions view is for: identity only, never its text. Null on the results. */
  private actionsOf: ResultIdentity | null = null;
  /** The selected result left while shown; said once, until the reader moves on. */
  private selectionLost = false;
  /** Where the results list was scrolled when Tab showed actions. */
  private resultsScroll = 0;

  constructor() {
    super();
    this.open = false;
    this.query = '';
    this.results = [];
    this.selectedIndex = 0;
    this.actionsFor = null;
    this.actionIndex = 0;
    this.wizardActive = false;
    this.wizardInput = '';
    this.wizardCompletedLabels = [];
  }

  /**
   * The palette owns the keyboard while open, so it enters the app's one modality authority: global
   * shortcuts — the status panel's among them — stand down under it, and the toggle action leaves
   * another dialog in control. Closing gives focus back to the invoker.
   */
  private readonly modal = new ModalController(this, {
    dialog: () => this.shadowRoot?.querySelector('dialog'),
  });

  /** An IME composition in the field: its Escape is the IME's, never the dialog's. */
  private composing = false;

  /**
   * One overlay at a time: opening the palette closes any open popover (the status panel included).
   * The modality is entered now, before the render that opens the dialog, so it holds from the
   * moment the palette is asked to open and the saved focus is the invoker, not the field.
   */
  private enterOverlay(): void {
    closeOthersInLayer('transient', 'command-palette');
    this.modal.captureFocus();
  }

  /**
   * Leave the overlay; focus goes back to the invoker. When the invoker is gone, the deliberate
   * fallback is the main content region (never a hidden field or the page).
   */
  private exitOverlay(): void {
    this.modal.close();
    const active = deepActiveElement();
    if (active !== null && active !== document.body && !this.shadowRoot?.contains(active)) return;
    const main = (this.getRootNode() as ParentNode).querySelector?.<HTMLElement>('[role="main"]') ?? null;
    if (focusRegion([{ kind: 'content', root: main }], 1) === null && active instanceof HTMLElement && active !== document.body) active.blur();
  }

  connectedCallback(): void {
    super.connectedCallback();
    // Tempdoc 508-followup §δ1 — register as the slot prompt provider
    // so template invocations resolve through the inline wizard
    // instead of window.prompt.
    setSlotPromptProvider((slot, templateId) => this.promptForSlot(slot, templateId), phase => {
      if (phase === 'start') {
        this.hide();
        this.wizardCancelled = false;
        this.wizardInvocationActive = true;
      } else {
        this.wizardInvocationActive = false;
        this.hide();
      }
    });
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.hide();
    this.dismissPeek();
    setSlotPromptProvider(null);
    // If a wizard was active, resolve with null (cancel) so any
    // in-flight template expansion unblocks.
    if (this.wizardStep) {
      const step = this.wizardStep;
      this.wizardStep = null;
      step.resolver(null);
    }
  }

  /**
   * Tempdoc 508-followup §δ1 — promise-returning entry point for
   * TemplateCatalog. Opens the palette in wizard mode for the slot,
   * waits for Enter (resolve with value) or Esc (resolve null).
   * Multiple-slot templates call this once per slot in declaration
   * order; completed slots accumulate in wizardCompletedLabels.
   */
  private promptForSlot(slot: NamedArgumentSlot, templateId: string): Promise<string | null> {
    if (this.wizardCancelled) return Promise.resolve(null);
    return new Promise<string | null>((resolve) => {
      this.wizardStep = { slot, templateId, resolver: resolve };
      this.wizardActive = true;
      this.wizardInput = slot.default ?? '';
      this.enterOverlay();
      this.open = true;
      this.updateComplete.then(() => {
        const input = this.shadowRoot?.querySelector('input');
        input?.focus();
        input?.select();
      });
    });
  }

  /** Tempdoc 508-followup §δ1 — confirm the current step. */
  private confirmWizardStep(): void {
    if (!this.wizardStep) return;
    const step = this.wizardStep;
    const value = this.wizardInput;
    // Add to breadcrumb so the user can see what's been answered if
    // a subsequent prompt arrives on the same palette instance.
    this.wizardCompletedLabels = [
      ...this.wizardCompletedLabels,
      { name: step.slot.name, value },
    ];
    this.wizardStep = null;
    this.wizardInput = '';
    this.wizardActive = false;
    step.resolver(value);
  }

  /** Tempdoc 508-followup §δ1 — cancel resolves with null. */
  private cancelWizard(): void {
    if (!this.wizardStep) {
      this.hide();
      return;
    }
    const step = this.wizardStep;
    this.wizardStep = null;
    this.wizardInput = '';
    this.wizardActive = false;
    this.wizardCompletedLabels = [];
    this.hide();
    step.resolver(null);
  }

  static styles = css`
    :host {
      display: contents;
    }
    /* Native <dialog> (showModal): the top layer and the platform's inert background, so no backdrop
       div and no z-index. Centred horizontally, at a fixed distance from the top. */
    dialog {
      --palette-top: min(15vh, 8rem);
      box-sizing: border-box;
      width: min(40rem, calc(100vw - 1rem));
      max-width: none;
      max-height: calc(100dvh - var(--palette-top) - 0.5rem);
      margin: var(--palette-top) auto auto;
      padding: 0;
      background: var(--surface-1);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-radius: 0.875rem;
      box-shadow: 0 24px 64px -24px rgb(0 0 0 / 0.65);
      flex-direction: column;
      overflow: hidden;
    }
    dialog[open] {
      display: flex;
    }
    dialog::backdrop {
      background: rgba(0, 0, 0, 0.5);
    }
    .search-row {
      display: flex;
      flex: none;
      align-items: center;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid var(--border-subtle);
      gap: 0.5rem;
    }
    .search-glyph { flex: none; color: var(--text-muted); }
    .search-row input {
      flex: 1;
      min-width: 0;
      background: transparent;
      border: none;
      color: var(--text-primary);
      font-size: var(--font-size-md);
      outline: none;
      font-family: inherit;
    }
    /* Transparent, not none: invisible here as before, but forced colors repaint it, so a High
       Contrast reader still sees where focus is once the panel's own styling is gone. */
    .search-row input:focus-visible {
      outline: 2px solid transparent;
      outline-offset: -2px;
    }
    kbd {
      font: inherit;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0 0.375rem;
      border: 1px solid var(--border-subtle);
      border-radius: 0.25rem;
      white-space: nowrap;
    }
    .mode-chip {
      font-size: var(--font-size-xs);
      font-weight: 600;
      padding: 0.125rem 0.375rem;
      background: var(--accent-tint);
      color: var(--accent-on-tint);
      border-radius: 0.25rem;
      white-space: nowrap;
    }
    .results {
      flex: 1 1 auto;
      min-height: 0;
      display: flex;
      flex-direction: column;
      overflow-y: auto;
      padding: 0.25rem 0.5rem 0.5rem;
    }
    /* The listbox the field controls is the scroll region (the combobox's popup), so the field's
       arrow keys scroll it through aria-activedescendant; it shrinks first, before anything around it. */
    .results > [role="listbox"] {
      flex: 0 1 auto;
      min-height: 0;
      overflow-y: auto;
    }
    .category-header {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.5rem 0.5rem 0.25rem;
    }
    .result-row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      padding: 0.5rem;
      border-radius: 0.5rem;
      cursor: pointer;
      column-gap: 0.75rem;
      row-gap: 0.125rem;
    }
    .result-row:hover { background: var(--surface-hover); }
    .result-row[data-selected] {
      background: var(--surface-hover);
      box-shadow: inset 2px 0 0 var(--accent, currentColor);
    }
    .result-label {
      font-size: var(--font-size-sm);
      color: var(--text-primary);
      overflow-wrap: anywhere;
    }
    .result-source {
      flex: 1 1 8rem;
      min-width: 0;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      overflow-wrap: anywhere;
    }
    .result-keys { display: flex; align-items: center; gap: 0.375rem; margin-inline-start: auto; font-size: var(--font-size-xs); color: var(--text-muted); }
    /* Matched word starts are bold; colour alone never carries them. */
    .result-label mark {
      background: transparent;
      color: inherit;
      font-weight: 700;
    }
    .more {
      display: block;
      margin: 0.125rem 0.5rem;
      background: none;
      border: none;
      padding: 0.25rem 0;
      color: var(--text-secondary);
      font: inherit;
      font-size: var(--font-size-xs);
      cursor: pointer;
    }
    .actions-of {
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.5rem 0.5rem 0.25rem;
      overflow-wrap: anywhere;
    }
    .note {
      margin: 0;
      font-size: var(--font-size-xs);
      color: var(--text-secondary);
      padding: 0.25rem 0.5rem;
    }
    .footer {
      display: flex;
      flex: none;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.25rem 0.75rem;
      padding: 0.5rem 1rem;
      border-top: 1px solid var(--border-subtle);
      font-size: var(--font-size-xs);
      color: var(--text-muted);
    }
    .footer .end { margin-inline-start: auto; }
    /* A short window keeps the field, the list and the active row; the key hints go first. */
    @media (max-height: 30rem) {
      dialog { --palette-top: 0.5rem; }
      .footer { display: none; }
    }
    .empty {
      padding: 1.5rem;
      text-align: center;
      color: var(--text-secondary);
      font-size: var(--font-size-sm);
    }
    .empty-headline { margin-bottom: 0.75rem; }
    .empty-fallbacks { display: flex; flex-direction: column; gap: 0.25rem; align-items: stretch; }
    .empty-fallback {
      padding: 0.5rem 0.75rem;
      border-radius: 0.25rem;
      background: var(--surface-hover);
      color: var(--text-primary);
      cursor: pointer;
      text-align: left;
    }
    .empty-fallback:hover { background: var(--surface-active); }
    .visually-hidden {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip-path: inset(50%);
      white-space: nowrap;
    }
    /* Forced colours drop fills and shadows: the active option keeps a system-colour outline, the
       field its focus outline, so selection and focus stay distinct in light and dark themes. */
    @media (forced-colors: active) {
      dialog { border-color: CanvasText; }
      .result-row[data-selected] { outline: 2px solid Highlight; outline-offset: -2px; }
      .search-row input:focus-visible { outline-color: Highlight; }
      kbd { border-color: CanvasText; }
    }
  `;

  private stopWatching?: () => void;
  private sourceResults: ScoredPaletteEntry[] = [];
  private groupLimits = new Map<string, number>();

  /** Rebuilds the shown results; a kept selection follows its result's identity, never its index. */
  private setResults(results: ScoredPaletteEntry[], keepSelection = false): void {
    const selected = keepSelection ? this.results[this.selectedIndex]?.entry : undefined;
    this.sourceResults = results;
    const counts = new Map<string, number>();
    this.results = results.filter(row => {
      const group = row.entry.category;
      const count = (counts.get(group) ?? 0) + 1;
      counts.set(group, count);
      return count <= (this.groupLimits.get(group) ?? FIRST_BOUND);
    });
    if (selected) {
      this.selectedIndex = this.results.findIndex(row => sameResult(row.entry, selected));
      if (this.selectedIndex < 0) this.selectionLost = true;
    }
    if (this.actionsOf) {
      // Once gone, the view stays gone: a same-id replacement is another result, never this one.
      const shown = this.actionsOf;
      this.actionsFor = this.actionsFor && (results.find(row => sameResult(row.entry, shown))?.entry ?? null);
      const count = this.actionsFor?.actions.length ?? 0;
      if (this.actionIndex >= count) this.actionIndex = Math.max(0, count - 1);
    }
    if (this.peekRowId !== null && (this.actionsOf !== null || !this.results.some(row => row.entry.id === this.peekRowId))) this.dismissPeek();
  }

  private showMore(group: string): void {
    this.groupLimits.set(group, (this.groupLimits.get(group) ?? FIRST_BOUND) + 24);
    this.setResults(this.sourceResults, true);
  }

  protected override updated(): void {
    // The dialog is always rendered, so it exists here; opening is idempotent.
    if (this.open) this.modal.open();
    this.shadowRoot?.querySelector<HTMLElement>('[data-selected]')?.scrollIntoView?.({ block: 'nearest' });
  }
  private settingsViewer?: SettingsViewer;

  private refreshResults = (viewer: SettingsViewer): void => {
    this.settingsViewer = viewer;
    if (!this.open || this.wizardActive) return;
    this.setResults(searchPaletteEntries(this.query, viewer), true);
  };

  show(): void {
    this.enterOverlay();
    this.open = true;
    this.query = '';
    this.leaveActions();
    this.selectionLost = false;
    this.groupLimits.clear();
    this.setResults(searchPaletteEntries(''));
    this.stopWatching?.();
    this.stopWatching = watchPaletteEntries(this.refreshResults);
    this.selectedIndex = 0;
    this.updateComplete.then(() => {
      const input = this.shadowRoot?.querySelector('input');
      input?.focus();
    });
  }

  hide(): void {
    if (this.wizardInvocationActive) this.wizardCancelled = true;
    this.stopWatching?.();
    this.stopWatching = undefined;
    this.dismissPeek();
    if (this.wizardStep) {
      const step = this.wizardStep;
      this.wizardStep = null;
      this.wizardActive = false;
      this.wizardInput = '';
      step.resolver(null);
    }
    this.composing = false;
    const wasOpen = this.open;
    this.open = false;
    if (wasOpen) this.exitOverlay();
    else this.modal.close();
    this.query = '';
    this.results = [];
    this.sourceResults = [];
    this.selectedIndex = 0;
    this.leaveActions();
    this.wizardCompletedLabels = [];
  }

  private handleInput(e: Event): void {
    const value = (e.target as HTMLInputElement).value;
    if (this.wizardActive) {
      this.wizardInput = value;
      return;
    }
    // Typing returns from a result's actions to the results it filters.
    this.leaveActions();
    this.selectionLost = false;
    this.query = value;
    this.groupLimits.clear();
    this.setResults(searchPaletteEntries(this.query, this.settingsViewer));
    this.selectedIndex = 0;
  }

  private handleKeydown(e: KeyboardEvent): void {
    if (isImeComposition(e)) return;
    // Tempdoc 508-followup §δ1 — wizard mode: Enter confirms,
    // Escape cancels; arrow keys are no-ops since there's no result list.
    if (this.wizardActive) {
      if (e.key === 'Escape') {
        e.preventDefault();
        e.stopPropagation();
        this.cancelWizard();
      } else if (e.key === 'Enter') {
        e.preventDefault();
        this.confirmWizardStep();
      } else if (e.key === 'Tab') {
        e.preventDefault();
      }
      return;
    }
    if (this.actionsOf) {
      this.handleActionsKeydown(e, this.actionsFor);
      return;
    }
    if (e.key === 'Escape') {
      // The palette's Escape: nothing beneath it (a Search pane, the page's details) sees it.
      e.preventDefault();
      e.stopPropagation();
      this.hide();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.moveDown();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.selectionLost = false;
      this.selectedIndex = Math.max(this.selectedIndex - 1, 0);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const selected = this.results[this.selectedIndex];
      if (selected) this.runEntry(selected.entry);
    } else if (e.key === 'Tab') {
      // Focus never leaves the dialog; with no result there are no actions to show.
      e.preventDefault();
      const selected = this.results[this.selectedIndex];
      if (!e.shiftKey && selected && selected.entry.actions.length > 0) this.showActions(selected.entry);
      else if (!selected) this.cycleFocus(e.shiftKey ? -1 : 1);
    }
  }

  /**
   * Keys raised on the dialog's other controls (an empty state's fallbacks): Escape closes, and Tab
   * moves among the dialog's own controls, never out to the page behind the backdrop.
   */
  private handlePanelKeydown(e: KeyboardEvent): void {
    if (isImeComposition(e) || e.composedPath()[0] instanceof HTMLInputElement) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      e.stopPropagation();
      this.hide();
    } else if (e.key === 'Tab') {
      e.preventDefault();
      this.cycleFocus(e.shiftKey ? -1 : 1);
    }
  }

  private cycleFocus(direction: 1 | -1): void {
    const stops = [...(this.shadowRoot?.querySelectorAll<HTMLElement>('input, .empty :is(button, a[href], [tabindex]:not([tabindex="-1"]))') ?? [])];
    if (stops.length < 2) return;
    const at = stops.indexOf(deepActiveElement() as HTMLElement);
    stops[(Math.max(at, 0) + direction + stops.length) % stops.length]!.focus();
  }

  /**
   * Arrow order is the rendered order. Past the last shown row of a group that holds more matches,
   * the group grows first, so every match is reachable by keyboard alone.
   */
  private moveDown(): void {
    this.selectionLost = false;
    const current = this.results[this.selectedIndex];
    const next = current ? this.sourceResults[this.sourceResults.indexOf(current) + 1] : undefined;
    if (next && !this.results.includes(next) && next.entry.category === current?.entry.category) {
      this.showMore(next.entry.category);
    }
    this.selectedIndex = Math.min(this.selectedIndex + 1, this.results.length - 1);
  }

  private handleActionsKeydown(e: KeyboardEvent, entry: PaletteEntry | null): void {
    const actions = entry?.actions ?? [];
    if (e.key === 'Escape' || (e.key === 'Tab' && e.shiftKey)) {
      e.preventDefault();
      e.stopPropagation();
      this.returnToResults();
    } else if (e.key === 'Tab') {
      e.preventDefault();
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.actionIndex = Math.min(this.actionIndex + 1, Math.max(actions.length - 1, 0));
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.actionIndex = Math.max(this.actionIndex - 1, 0);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const action = actions[this.actionIndex];
      if (entry && action) this.runEntry(entry, action.target);
    }
  }

  private showActions(entry: PaletteEntry): void {
    this.resultsScroll = this.shadowRoot?.getElementById(LIST_ID)?.scrollTop ?? 0;
    this.dismissPeek();
    this.actionsOf = { id: entry.id, target: entry.target };
    this.actionsFor = entry;
    this.actionIndex = 0;
  }

  private leaveActions(): void {
    this.actionsOf = null;
    this.actionsFor = null;
    this.actionIndex = 0;
  }

  /** Back to the results as they were: same query, the same selected result, the same scroll. */
  private returnToResults(): void {
    this.leaveActions();
    const scroll = this.resultsScroll;
    void this.updateComplete.then(() => {
      const list = this.shadowRoot?.getElementById(LIST_ID);
      if (list) list.scrollTop = scroll;
      this.shadowRoot?.querySelector<HTMLElement>('[data-selected]')?.scrollIntoView?.({ block: 'nearest' });
    });
  }

  private handleSelect(index: number): void {
    const selected = this.results[index];
    if (selected) this.runEntry(selected.entry);
  }

  /**
   * Close, then run: the chosen command (e.g. "What's going on") acts on the page, not under the
   * palette's modality. The query is read before closing clears it.
   */
  private runEntry(entry: PaletteEntry, target = entry.target): void {
    this.hide();
    void invokePaletteEntry(entry, target, this.settingsViewer).finally(() => {
      if (this.open && !this.wizardStep) this.hide();
    });
  }

  /**
   * Tempdoc 508-followup §δ2 — hover-preview dispatch. When a result
   * row is hovered with Alt held, we map the command to a surfaceId
   * (today: `shell.go-to-<id>` navigation commands) and fire a
   * `jf-peek-request` event picked up by `<jf-peek>`. Releasing Alt,
   * leaving or losing the row, closing the palette or removing it
   * fires `jf-peek-dismiss` and drops the Alt listener.
   *
   * Working assumption: Alt is the least-collidey modifier per the
   * plan's flagged decision.
   */
  private peekRowId: string | null = null;
  private peekKeyupHandler: ((e: KeyboardEvent) => void) | null = null;

  private handlePeekMouseEnter(e: MouseEvent, item: ScoredPaletteEntry): void {
    if (!e.altKey) return;
    const surfaceId = this.commandToSurfaceId(item.entry.id);
    if (!surfaceId) return;
    document.dispatchEvent(new CustomEvent('jf-peek-request', { detail: { surfaceId } }));
    this.peekRowId = item.entry.id;
    // Hook keyup so releasing Alt dismisses without requiring mouseleave.
    if (!this.peekKeyupHandler) {
      this.peekKeyupHandler = (ev: KeyboardEvent) => {
        if (ev.key === 'Alt') this.dismissPeek();
      };
      document.addEventListener('keyup', this.peekKeyupHandler);
    }
  }

  private dismissPeek(): void {
    if (this.peekKeyupHandler) {
      document.removeEventListener('keyup', this.peekKeyupHandler);
      this.peekKeyupHandler = null;
    }
    if (this.peekRowId === null) return;
    this.peekRowId = null;
    document.dispatchEvent(new CustomEvent('jf-peek-dismiss'));
  }

  /**
   * Map a command id to a surface id. V1 only handles navigation
   * commands (`shell.go-to-<id>` → `core.<id>-surface`). Future:
   * a richer registration map keyed by command id.
   */
  private commandToSurfaceId(commandId: string): string | null {
    const navPrefix = 'shell.go-to-';
    if (commandId.startsWith(navPrefix)) {
      const tail = commandId.slice(navPrefix.length);
      // Match the names registered in CommandRegistry.registerShellCommands.
      // Search Thread S5b — 'search' now resolves to the one window (the standalone Search
      // rail surface is retired; its retrieve tier folded into core.search-v3-surface).
      if (tail === 'search') return 'core.search-v3-surface';
      if (tail === 'library') return 'core.library-surface';
      if (tail === 'settings') return 'core.settings-surface';
      if (tail === 'health') return 'core.health-surface';
      if (tail === 'chat') return 'core.search-v3-surface';
      if (tail === 'browse') return 'core.browse-surface';
    }
    return null;
  }

  /** What a screen reader hears when the list changes: the count, or the empty, loading or error state. */
  private announcement(): string {
    if (this.actionsOf) {
      if (!this.actionsFor) return 'This result is no longer available. Escape goes back.';
      const count = this.actionsFor.actions.length;
      return `Actions for ${this.actionsFor.label}: ${count} ${count === 1 ? 'action' : 'actions'}. Escape goes back.`;
    }
    const sources = paletteSourceState();
    const count = this.sourceResults.length;
    const parts = [
      this.selectionLost ? 'The selected result is no longer available.' : '',
      count === 0 && this.query ? `No results for "${this.query}".` : `${count} ${count === 1 ? 'result' : 'results'}.`,
      sources.loading ? 'Still loading folders and conversations.' : '',
      sources.failed ? 'Folders could not be loaded, so results may be incomplete.' : '',
    ];
    return parts.filter(Boolean).join(' ');
  }

  /**
   * The dialog's own close request (Escape where no key handler took it, or the platform's): the same
   * order as the keys — a template's slot cancels, a result's actions go back to the results, the
   * results close. Never during an IME composition.
   */
  private onCancel(e: Event): void {
    e.preventDefault();
    if (this.composing) return;
    if (this.wizardActive) this.cancelWizard();
    else if (this.actionsOf) this.returnToResults();
    else this.hide();
  }

  /** The platform closed the dialog without asking (a close request it would not let be refused). */
  private onClose(e: Event): void {
    if (this.open && !(e.currentTarget as HTMLDialogElement).open) this.hide();
  }

  /** A click on the dialog box itself, not its content, is a click on the backdrop. */
  private onBackdrop(e: Event): void {
    if (e.target !== e.currentTarget) return;
    if (this.wizardActive) this.cancelWizard();
    else this.hide();
  }

  override render(): TemplateResult {
    return html`
      <dialog
        aria-label="Command palette"
        @cancel=${this.onCancel}
        @close=${this.onClose}
        @click=${this.onBackdrop}
        @keydown=${this.handlePanelKeydown}
        @compositionstart=${() => { this.composing = true; }}
        @compositionend=${() => { this.composing = false; }}
      >${this.open ? this.renderContent() : nothing}</dialog>
    `;
  }

  private renderContent(): TemplateResult {
    // Tempdoc 508-followup §δ1 — wizard render path. Replaces the
    // result list with a single-slot prompt UI while a template's
    // expandTemplate is awaiting input. Esc cancels (resolves null);
    // Enter advances or completes.
    if (this.wizardActive && this.wizardStep) {
      const slot = this.wizardStep.slot;
      return html`
        <div class="search-row">
          <span class="mode-chip">${this.wizardStep.templateId}</span>
          <input
            type="text"
            aria-label=${slot.name}
            placeholder=${slot.name}
            .value=${this.wizardInput}
            @input=${this.handleInput}
            @keydown=${this.handleKeydown}
          />
          <span class="result-keys"><kbd>Enter</kbd> continue <kbd>Esc</kbd> cancel</span>
        </div>
        <div class="results">
          <div class="category-header" data-wizard-slot-name=${slot.name}>
            ${slot.name}${slot.default !== undefined ? html` <span class="result-source">default: ${slot.default}</span>` : nothing}
          </div>
          ${this.wizardCompletedLabels.length > 0
            ? html`<div class="category-header">Completed</div>
              ${this.wizardCompletedLabels.map(
                (c) => html`<div class="result-row"><span class="result-label">${c.name}</span><span class="result-source">${c.value}</span></div>`,
              )}`
            : nothing}
        </div>
      `;
    }

    const prefix = this.query.charAt(0);
    const modeLabel = prefix === '>' ? 'Actions' : prefix === '#' ? 'Areas' : prefix === '@' ? 'Settings' : '';
    const actions = this.actionsOf !== null;
    const activeId = actions
      ? (this.actionsFor?.actions[this.actionIndex] ? `palette-action-${this.actionIndex}` : '')
      : (this.results[this.selectedIndex] ? `palette-opt-${this.selectedIndex}` : '');

    return html`
      <div class="search-row">
        <svg class="search-glyph" aria-hidden="true" width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="7" cy="7" r="4.5"/><path d="m10.5 10.5 3 3"/></svg>
        ${modeLabel ? html`<span class="mode-chip">${modeLabel}</span>` : nothing}
        <input
          type="text"
          role="combobox"
          aria-label="Search actions, settings, folders, conversations and areas"
          aria-expanded="true"
          aria-autocomplete="list"
          aria-controls=${actions ? ACTIONS_ID : LIST_ID}
          aria-activedescendant=${activeId || nothing}
          autocomplete="off"
          spellcheck="false"
          placeholder=${modeLabel ? `Filter ${modeLabel.toLowerCase()}…` : 'Search, or type > for actions only'}
          .value=${this.query}
          @input=${this.handleInput}
          @keydown=${this.handleKeydown}
        />
        <kbd aria-hidden="true">Esc</kbd>
      </div>
      <div class="results">
        ${actions ? this.renderActions(this.actionsFor) : this.renderResults()}
      </div>
      <div class="visually-hidden" role="status" aria-live="polite" data-testid="palette-status">${this.announcement()}</div>
      <div class="footer" aria-hidden="true">
        ${actions
          ? html`<span><kbd>↑</kbd> <kbd>↓</kbd> move</span><span><kbd>Enter</kbd> run</span><span><kbd>Esc</kbd> back</span>`
          : html`<span><kbd>↑</kbd> <kbd>↓</kbd> move</span><span><kbd>Enter</kbd> open</span><span><kbd>Tab</kbd> actions</span>
            <span class="end">Type <kbd>&gt;</kbd> for actions only</span>`}
      </div>
    `;
  }

  private renderResults(): TemplateResult {
    const positions = new Map(this.sourceResults.map((row, index) => [row, index + 1]));
    const option = (item: ScoredPaletteEntry): TemplateResult => {
      const index = this.results.indexOf(item);
      const selected = index === this.selectedIndex;
      return html`
        <div
          class="result-row"
          id=${`palette-opt-${index}`}
          role="option"
          aria-selected=${selected ? 'true' : 'false'}
          aria-posinset=${positions.get(item) ?? index + 1}
          aria-setsize=${this.sourceResults.length}
          ?data-selected=${selected}
          @click=${() => this.handleSelect(index)}
          @mouseenter=${(e: MouseEvent) => this.handlePeekMouseEnter(e, item)}
          @mouseleave=${() => this.dismissPeek()}
        >
          <span class="result-label">${this.renderLabelWithMatches(item.entry.label, item.matches)}</span>
          ${item.entry.description ? html`<span class="result-source">${item.entry.description}</span>` : nothing}
          ${item.entry.shortcut || selected
            ? html`<span class="result-keys">
                ${item.entry.shortcut ? html`<kbd>${item.entry.shortcut}</kbd>` : nothing}
                ${selected && item.entry.actions.length > 0 ? html`<span aria-hidden="true"><kbd>Tab</kbd> actions</span>` : nothing}
              </span>`
            : nothing}
        </div>
      `;
    };
    const groups = this.groupByCategory(this.results);
    return html`
      ${this.results.length === 0 && this.query.length > 0 ? this.renderEmptyState() : nothing}
      <div role="listbox" id=${LIST_ID} aria-label="Results">
        ${groups.map(([category, items], groupIndex) => category ? html`
          <div role="group" aria-labelledby=${`palette-group-${groupIndex}`}>
            <div class="category-header" role="presentation" id=${`palette-group-${groupIndex}`}>${category}</div>
            ${items.map(option)}
            ${this.sourceResults.filter(row => row.entry.category === category).length > items.length
              ? html`<button class="more" type="button" tabindex="-1" aria-hidden="true" @click=${() => this.showMore(category)}>Show more ${category.toLowerCase()}</button>`
              : nothing}
          </div>` : items.map(option))}
      </div>
      ${paletteNotes(this.query).map(note => html`<p class="note" role="note" data-testid="palette-note">${note}</p>`)}
    `;
  }

  /** The actions of the result as the providers give it now; with no result, a neutral heading. */
  private renderActions(entry: PaletteEntry | null): TemplateResult {
    const actions = entry?.actions ?? [];
    return html`
      <div class="actions-of" id="palette-actions-of">${entry ? `Actions for ${entry.label}` : 'Actions'}</div>
      ${entry ? nothing : html`<p class="note" role="note">This result is no longer available. Escape goes back.</p>`}
      <div role="listbox" id=${ACTIONS_ID} aria-labelledby="palette-actions-of">
        ${actions.map((action, index) => html`
          <div
            class="result-row"
            id=${`palette-action-${index}`}
            role="option"
            aria-selected=${index === this.actionIndex ? 'true' : 'false'}
            aria-posinset=${index + 1}
            aria-setsize=${actions.length}
            ?data-selected=${index === this.actionIndex}
            @click=${() => entry && this.runEntry(entry, action.target)}
          ><span class="result-label">${action.label}</span></div>
        `)}
      </div>
    `;
  }

  /**
   * §11.9 polish — render the command label with matched characters
   * highlighted. `matches` is a sorted array of character indices
   * produced by the scorer; we wrap each contiguous matched
   * run in a <mark> tag. Empty matches produces plain text.
   */
  private renderLabelWithMatches(label: string, matches: ReadonlyArray<number>): TemplateResult | string {
    if (!matches || matches.length === 0) return label;
    const sorted = [...matches].sort((a, b) => a - b);
    const segments: Array<{ text: string; matched: boolean }> = [];
    let cursor = 0;
    let i = 0;
    while (i < sorted.length) {
      const start = sorted[i]!;
      // Find the contiguous run.
      let end = start;
      while (i + 1 < sorted.length && sorted[i + 1] === end + 1) {
        i++;
        end = sorted[i]!;
      }
      if (start > cursor) {
        segments.push({ text: label.slice(cursor, start), matched: false });
      }
      segments.push({ text: label.slice(start, end + 1), matched: true });
      cursor = end + 1;
      i++;
    }
    if (cursor < label.length) {
      segments.push({ text: label.slice(cursor), matched: false });
    }
    return html`${segments.map((seg) =>
      seg.matched ? html`<mark>${seg.text}</mark>` : seg.text,
    )}`;
  }

  private groupByCategory(results: ScoredPaletteEntry[]): Array<[string, ScoredPaletteEntry[]]> {
    const groups = new Map<string, ScoredPaletteEntry[]>();
    for (const r of results) {
      const cat = r.entry.category ?? '';
      const list = groups.get(cat) ?? [];
      list.push(r);
      groups.set(cat, list);
    }
    return Array.from(groups.entries());
  }

  /**
   * §11.6 / §13.6 — render fallback contributions when the palette
   * has no matches for a non-empty query. Replaces the previous
   * static "No commands match" message with a structured registry
   * query.
   */
  private renderEmptyState(): TemplateResult {
    const contributions = listEmptyStates({
      context: 'palette-no-results',
      query: this.query,
    });
    if (contributions.length === 0) {
      return html`<div class="empty">No results for "${this.query}"</div>`;
    }
    return html`
      <div class="empty">
        <div class="empty-headline">No results for "${this.query}"</div>
        <div class="empty-fallbacks">
          ${repeat(contributions, (c) => c.id, (c) => html`<div class="empty-fallback" data-fallback-id=${c.id}>${c.render({
            context: 'palette-no-results',
            query: this.query,
          })}</div>`)}
        </div>
      </div>
    `;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-command-palette')) {
  customElements.define('jf-command-palette', CommandPalette);
}
