// SPDX-License-Identifier: Apache-2.0
/**
 * Fixture data and fixed copy for the Search v3 shell (tempdoc 822 slice 1; narrowed in A1 and A2).
 *
 * The window was fixture-first on purpose: the shell's geometry had to clear the bar before any
 * backend was wired. Phase A1 moved the CONTENT SURFACE onto the shared search store and Phase A2
 * moved the SIDEBAR onto real window-local sessions (`sv3-sessions.ts`), so the fixture sessions are
 * gone. What remains is fixed copy and the row's status vocabulary — the composer's placeholder
 * controls went with Phase F10, which replaced them with a real one.
 */
import { reasonFor } from '../../state/readinessNotice.js';

/**
 * The row's status, as the 3-colour budget spends it: colour is reserved for act-now,
 * in-motion and broken. Every other row is `resting` and spends none of it.
 */
export type Sv3RowStatus = 'resting' | 'in-motion' | 'act-now' | 'broken';

export const WINDOW_TITLE = 'Search v3';

export const COMPOSER_PLACEHOLDER = 'Ask or search…';

/**
 * The composer's two forms. HERO is the empty window — nothing has been asked yet, so the composer
 * is the centred subject of the main region and carries its headline. DOCKED is the working window —
 * results own the region and the composer recedes into the bottom band. They are one component in
 * two states, not two components, because the morph between them animates a single moving box.
 */
export type Sv3ComposerState = 'hero' | 'docked';

/** The window opens empty, so the morph is reachable from the default fixture. */
export const COMPOSER_STATE_DEFAULT: Sv3ComposerState = 'hero';

export const HERO_HEADLINE = 'What are you looking for?';

/*
 * The slice-3 scope-control PLACEHOLDERS ("All sources" / "Any time") are gone (Phase F10). They
 * were search-scope furniture standing in for controls the §4b standing directive defers
 * indefinitely, they did nothing when clicked, and the row they occupied is now the composer's
 * effort control — a real one. Deleting them rather than leaving them beside it is the same call
 * F9 made on the two inert topbar placeholders: chrome that cannot act is chrome that lies about
 * what the window can do.
 */

/* ── Slice 4: the palette and the empty states ─────────────────────────────────────────────── */

/**
 * The `fixtures` dev handle that used to switch the sidebar between a fixture list and its zero state
 * is gone with the fixtures (Phase A2): both regions now reach their empty state the real way — the
 * sidebar before the window's first search, the content surface on an empty result set.
 */
export interface Sv3EmptyCopy {
  readonly title: string;
  readonly description: string;
  /** The same outcome while filters restrict the list — the remedy is then theirs to remove. */
  readonly filteredDescription?: string;
}

export const SIDEBAR_EMPTY: Sv3EmptyCopy = {
  title: 'No searches yet',
  description: 'Ask something and the thread will be kept here.',
};

export const MAIN_EMPTY: Sv3EmptyCopy = {
  title: 'No documents matched',
  description: 'Try fewer or different words.',
  filteredDescription: 'Try fewer words, or remove a filter above.',
};

/** WP1.1 — a hit with no document path can be neither opened nor asked about; said, not dropped. */
export const SV3_RESULT_NO_DOCUMENT = 'This result has no file to open or ask about.';

/**
 * A failed search establishes no match count. This includes a backend rejection as well as
 * a transport failure; the store supplies the specific explanation and recovery guidance.
 */
export const MAIN_UNREACHABLE: Sv3EmptyCopy = {
  title: 'Search could not be completed',
  description: 'No search results are available for this attempt.',
};

/**
 * The conversation's canonical record could not be read (tempdoc 822 Phase F6; inventory D2 /
 * tempdoc 727 F-8). It is a THIRD state, distinct from both of the two above: {@link MAIN_EMPTY} says
 * the corpus held nothing, {@link MAIN_UNREACHABLE} says a search failed, and this
 * says the window is showing a conversation it could not fully load. 727 F-8's finding was that the
 * shipped window's empty-on-failure fallback was *completely silent* — a reader stared at a thread
 * with no hint anything was missing — so the notice states the shortfall AND that what is on screen
 * is still real. Its connection detail comes from the existing `readinessNotice` wording.
 */
export const RECORD_UNREACHABLE: Sv3EmptyCopy = {
  title: "Couldn't load this conversation's full record — showing what's here.",
  description: reasonFor('binding.unreachable').wording,
};

/* ── Phase F6: the window's identity to the shared draft + hint authorities ─────────────────── */

/**
 * This window's own key into the shared `DraftPersistence` controller (tempdoc 609 §R T2.1). Its own,
 * not the shipped window's: two surfaces sharing a key would each rehydrate the other's draft.
 */
export const SV3_DRAFT_KEY = 'search-v3.composer';

/** The surface key the one-shot "Draft kept" hint is remembered against (tempdoc 609 §R T1.4). */
export const SV3_SURFACE_KEY = 'core.search-v3-surface';

/**
 * A rename the conversation store refused, worded (tempdoc 838). The row is put back, so the message
 * only has to say that the NAME did not stick — the reader can see which name it was. The locked case
 * is worded by `readinessNotice`'s ONE reason vocabulary instead, so this surface cannot drift from
 * how the rest of the product names an encrypted-and-locked store.
 */
export const SV3_RENAME_FAILED = "Couldn't save that name — the conversation kept its old one.";

/* ── Phase F1: the transcript's fixed copy ─────────────────────────────────────────────────── */

/**
 * The design spec's own wording for a response that finished with nothing in it
 * — an empty slot would leave the reader
 * unable to tell "finished with no text" from "still coming".
 */
export const TURN_EMPTY_ANSWER = '(empty response)';

/** The reader's Stop. Said as the reader's own act, never as a failure the window suffered. */
export const TURN_HALTED = 'Stopped by you.';
/**
 * Between the reader's Stop and the Engine's answer to it. Only the Engine's own end of the turn
 * says whether it stopped (and was saved as stopped) or had already finished.
 */
export const TURN_STOPPING = 'Stopping…';
/** The Stop never reached the Engine (refused, or no answer), so the answer goes on. */
export const TURN_STOP_FAILED = 'Could not stop this answer — it goes on. Press Stop to try again.';

export const TURN_FAILED = 'The answer failed.';

/* ── Phase F2: the delegated run ───────────────────────────────────────────────────────────── */

/**
 * The optimistic echo: the task left this window and the server has not acknowledged it yet. It
 * claims nothing about the run itself — a run that has not been acknowledged may not exist — and it
 * yields the moment `hasServerAcknowledgedLocalDispatch` holds (`sv3-run.ts`).
 */
export const RUN_DISPATCHING = 'Sending…';

export interface Sv3Command {
  readonly id: string;
  readonly label: string;
  /** The keyboard hint rendered at the row's trailing edge; absent means the command has none. */
  readonly shortcut?: string;
  /** The CURRENT choice — distinct from the keyboard position, which the palette owns at runtime. */
  readonly selected?: boolean;
}

/**
 * Named rather than inlined, because the window matches on it and a typo would silently do nothing.
 * WP1.1 — "Search everything" is live: it puts the composer in Search mode and searches the draft.
 */
export const SV3_COMMAND_SEARCH = 'cmd-everything';

/** The palette's export command (tempdoc 822 Phase F7; inventory A10). Named for the same reason. */
export const SV3_COMMAND_EXPORT_MARKDOWN = 'cmd-export-markdown';

export interface Sv3CommandGroup {
  readonly id: string;
  readonly label: string;
  readonly commands: readonly Sv3Command[];
}

/**
 * Nine commands over two groups, so the ONE separator between them is exercised. Exactly one command
 * is `selected` — the palette's current choice — which is what makes the two-state distinction
 * visible: the highlight starts on the first row while the selection sits elsewhere.
 */
export const COMMAND_GROUPS: readonly Sv3CommandGroup[] = [
  {
    id: 'cg-search',
    label: 'Search',
    commands: [
      { id: SV3_COMMAND_SEARCH, label: 'Search everything' },
      { id: 'cmd-scope-sources', label: 'Scope to all sources', selected: true },
      { id: 'cmd-scope-recent', label: 'Scope to the last 7 days' },
      { id: 'cmd-similar', label: 'Find documents like this one' },
      { id: 'cmd-explain', label: 'Explain why this result ranked' },
    ],
  },
  {
    id: 'cg-workspace',
    label: 'Workspace',
    commands: [
      /* Live (tempdoc 822 Phase F7, inventory A10). Export is a real capability with no resting
         chrome to spend on it, which is what the palette is FOR in the spec's economy — the
         alternative was a per-conversation overflow this window does not have. Handled in
         `SearchV3View.onPaletteRun`. */
      { id: SV3_COMMAND_EXPORT_MARKDOWN, label: 'Copy this conversation as Markdown' },
      { id: 'cmd-folders', label: 'Manage indexed folders' },
      { id: 'cmd-reindex', label: 'Reindex this folder' },
      { id: 'cmd-diagnostics', label: 'Open indexing diagnostics', shortcut: 'Ctrl D' },
    ],
  },
];

export const COMMANDS: readonly Sv3Command[] = COMMAND_GROUPS.flatMap((g) => g.commands);

export const PALETTE_PLACEHOLDER = 'Type a command or search…';

export const PALETTE_EMPTY = 'No matching commands';

/** The spec's footer gutter: one hint per key group, navigate / act / dismiss. */
export interface Sv3PaletteHint {
  readonly keys: readonly string[];
  readonly label: string;
}

export const PALETTE_HINTS: readonly Sv3PaletteHint[] = [
  { keys: ['↑', '↓'], label: 'Navigate' },
  { keys: ['Enter'], label: 'Run' },
  { keys: ['Esc'], label: 'Close' },
];

/* ── Phase F7: the honesty pack ────────────────────────────────────────────────────────────── */

/**
 * The conversation store is locked, so the transcript CANNOT BE READ (tempdoc 629 §L4, inventory
 * E4/E5). The heading is the ONE readiness vocabulary's wording (`reasonFor('conversations.locked')`)
 * and is therefore not written here; what this adds is the reassurance the shipped window carries
 * beside it — locked must never look deleted, and the index is a different store entirely.
 */
export const HISTORY_LOCKED_HELP =
  'Unlock it to read your chat history — your search index is unaffected.';

/**
 * A send the lock refused, said next to the remedy that fixes it (tempdoc 734 round-14 F4). It is the
 * shipped sentence MINUS its "your text is back in the composer" half, because this window does not
 * put the draft back — a reassurance about something that did not happen would be the exact class of
 * untruth this pack exists to remove.
 */
export const HISTORY_LOCKED_REFUSED = 'Your last message was not sent.';

/** The transparency note (tempdoc 603 C2; inventory C8) — the reference window's own label. */
export const REWRITE_NOTE_LABEL = 'Interpreted as:';

/**
 * The zero-corpus remedy (tempdoc 811 C-4; inventory E10) — the shipped landing's own wording, which
 * names both the destination and why the reader is being sent there.
 */
export const CORPUS_ADD_FOLDERS = 'Add folders in Library to start searching';

/** Where {@link CORPUS_ADD_FOLDERS} goes. The surface that OWNS folder management, not one hop short. */
export const CORPUS_REMEDY_TARGET = 'core.library-surface';

/* ── The degradation banner (inventory E1/E2/E3) ───────────────────────────────────────────────
 *
 * The banner's HEADLINE, BODY, CAUSES and REMEDY LABEL are all the readiness authority's
 * (`state/readinessNotice.ts`) and appear nowhere in this file — this window words no state. What
 * IS written here is the disclosure's own two labels and the ids/geometry the composer needs, which
 * are chrome, not claims.
 */

/**
 * The disclosure's accessible name in each direction. Named, not inlined, because it is the ONE
 * affordance standing between a Simple reader and the worded causes: an unlabelled chevron would put
 * an honesty fact behind a control that says nothing about what it opens.
 */
export const SV3_DEGRADATION_MORE = 'Show what is reduced';
export const SV3_DEGRADATION_LESS = 'Hide what is reduced';

/**
 * The headline's id. The send's `aria-describedby` names it when the banner has taken the slot's one
 * line from the availability notice, so a refusal's explanation is reachable either way.
 */
export const SV3_DEGRADATION_HEADLINE_ID = 'sv3-degradation-headline';

/** The disclosed region's id, named by `aria-controls` only while it exists. */
export const SV3_DEGRADATION_DETAIL_ID = 'sv3-degradation-detail';

/**
 * The banner's two glyphs (severity mark, disclosure chevron) at the tail's own glyph size, so the
 * one line reads as composer chrome rather than as an alert dialog that landed in the band.
 */
export const SV3_DEGRADATION_GLYPH_SIZE = 13;

/**
 * The per-turn copy action and its confirmation (inventory A9). Since Phase F11 the control is
 * ICON-ONLY (the spec's copy button), so {@link TURN_COPY_LABEL} is its ACCESSIBLE
 * NAME rather than its text, and {@link TURN_COPY_DONE} is what the tail's live region announces.
 */
export const TURN_COPY_LABEL = 'Copy answer';
export const TURN_COPY_DONE = 'Copied';

/**
 * The tail's disclosure, in the two words it may use (tempdoc 822 Phase F11). Sentence case at the
 * tail's own 12px — the window declares ONE disclosure affordance and this is its wording, replacing
 * the imported panel's `▸ N SOURCES` dialect (the fit audit's axis 3 + axis 5, for this region).
 *
 * TWO words because they are two claims: a retrieval set really was reported, versus only
 * per-sentence citation-matches were, which is not a retrieval the window may name.
 */
export const SOURCES_LABEL = 'Sources';
export const CITATIONS_LABEL = 'Citations';

/** How long the copy confirmation stands before the label returns. */
export const TURN_COPY_FEEDBACK_MS = 1600;

/* ── The effective-context set (tempdoc 610, ported by 852 S2) ─────────────────────────────────
 *
 * The reference window's own wording wherever it had some, because these five acts change what the
 * MODEL sees and a second phrasing of "the assistant no longer sees this" in a second window is a
 * second contract. What is re-worded is only what the unit changed: this window's transcript is
 * made of TURNS, so a per-message act is named for the turn it acts on.
 */

/** The ⋯ trigger's accessible name — icon-only, so this IS the name and not a tooltip. */
export const CONTEXT_MENU_LABEL = 'Context actions for this turn';

export const CONTEXT_MENU_RESET = 'Reset context to here';
export const CONTEXT_MENU_COMPACT = 'Compact up to here';
export const CONTEXT_MENU_EXCLUDE = 'Exclude from context';
export const CONTEXT_MENU_INCLUDE = 'Include in context';

/** The divider above the floor turn, in its two forms — a plain rewind, and one carrying a summary. */
export const CONTEXT_FLOOR_RESET =
  'Context reset — the assistant no longer sees the turns above this line';
export const CONTEXT_FLOOR_COMPACTED =
  'Context compacted — the turns above this line are summarized for the assistant';

/**
 * The divider's control row names itself as a GROUP. `role="separator"` sits on the hairline above
 * it and nowhere else: that role is children-presentational, so putting it on the row would prune
 * Restore — the only way back from a floor — from assistive tech entirely.
 */
export const CONTEXT_FLOOR_GROUP_LABEL = 'Effective context';

export const CONTEXT_FLOOR_RESTORE = 'Restore';
export const CONTEXT_FLOOR_RESTORE_LABEL = 'Restore the full context for this conversation';
export const CONTEXT_SUMMARY_SHOW = 'Show summary';
export const CONTEXT_SUMMARY_HIDE = 'Hide summary';
export const CONTEXT_SUMMARY_EDIT = 'Edit';
export const CONTEXT_SUMMARY_EDIT_LABEL = 'Edit the context summary';
export const CONTEXT_SUMMARY_INPUT_LABEL = 'Context summary';
export const CONTEXT_SUMMARY_SAVE = 'Save';
export const CONTEXT_SUMMARY_CANCEL = 'Cancel';

/** The bar under the transcript: the meter's trigger, and the aggregate of what is hidden. */
export const CONTEXT_METER_LABEL = 'Context';
export const CONTEXT_METER_BAR_LABEL = 'Context window used';
export const CONTEXT_INCLUDE_ALL = 'Include all';
export const CONTEXT_INCLUDE_ALL_LABEL = 'Include every hidden message back into the context';

/** "N turns hidden from context" — a count the reader can act on, so it says which number it is. */
export const contextHiddenLabel = (turns: number): string =>
  `${turns} turn${turns === 1 ? '' : 's'} hidden from context`;

/**
 * Hidden messages no turn on screen names (S2.7) — counted as MESSAGES, because there is no turn to
 * count them by, and a turn count that silently left them out would read as "nothing else is hidden".
 */
export const contextUnseenLabel = (messages: number): string =>
  `${messages} hidden message${messages === 1 ? '' : 's'} not shown here`;

/** The floor sits on a message this window does not draw as a turn, so no divider can carry it (S2.8). */
export const CONTEXT_FLOOR_UNSEEN = 'Context starts at a message not shown here';
export const CONTEXT_FLOOR_UNSEEN_SUMMARIZED =
  'Context starts at a message not shown here — earlier turns are summarized for the assistant';

/** The occupancy readout, and what pressing it opens. */
export const contextMeterLabel = (pct: number, occupancy: number, window: number): string =>
  `${CONTEXT_METER_LABEL} ${pct}% · ${occupancy} / ${window} tokens`;

export const contextMeterTrigger = (pct: number): string =>
  `Context ${pct}% used — show what the assistant sees`;

/** The estimated split, named as an estimate everywhere it appears (tempdoc 610 §I.2). */
export const contextSplitLabel = (system: number, conversation: number, retrieved: number): string =>
  `split (estimated): system ~${system}, conversation ~${conversation}, documents ~${retrieved}`;

/** The inspector's own section names and speaker labels. */
export const CONTEXT_INSPECTOR_SUMMARY = 'Summary of earlier turns';
export const CONTEXT_INSPECTOR_CONVERSATION = 'Conversation';
export const CONTEXT_INSPECTOR_DOCUMENTS = 'Documents';
export const CONTEXT_TURN_USER = 'You';
export const CONTEXT_TURN_ASSISTANT = 'Assistant';

/**
 * What a refused write says. Each names the ACT that failed rather than "something went wrong",
 * because the reader's next move differs per act. None of them words the model's availability: this
 * window has ONE vocabulary for that (`state/readinessNotice.ts`, spoken by the composer's own
 * notice), and a second phrasing here would be a second sense of the same state (inventory E8).
 */
export const CONTEXT_FLOOR_FAILED = 'The context could not be reset';
export const CONTEXT_RESTORE_FAILED = 'The context could not be restored';
export const CONTEXT_COMPACT_FAILED = 'The turns above this one could not be summarized';
export const CONTEXT_SUMMARY_FAILED = 'The summary could not be saved';
export const CONTEXT_EXCLUDE_FAILED = 'That turn could not be hidden from the context';
export const CONTEXT_INCLUDE_FAILED = 'That turn could not be put back into the context';
export const CONTEXT_INCLUDE_ALL_FAILED =
  'Some hidden messages could not be put back into the context';

/* ── Branch, edit / retry and the version pager (slice 513 + 610 Phase A/B, ported by 852 S3) ───
 *
 * The reference window's wording verbatim wherever it had some, for the reason S2 kept the context
 * wording: these acts create a real, navigable fork of a conversation, and a second name for
 * "branch to a new thread" in a second window would be a second concept to the reader.
 */

export const BRANCH_MENU_RETRY = 'Retry from here';
export const BRANCH_MENU_BRANCH = 'Branch to new thread';

/** Edit renders INLINE on the question — the reference's §13.1 split — so this is its own name. */
export const BRANCH_EDIT_LABEL = 'Edit this question';
export const BRANCH_EDIT_INPUT_LABEL = 'Rewrite this question';
export const BRANCH_EDIT_SEND = 'Send';
export const BRANCH_EDIT_CANCEL = 'Cancel';
/**
 * A stream started while the editor was open. The rewrite is KEPT and Send says why it cannot go —
 * a fork of a transcript with a prompt in flight is not a fork of it, the same gate the ⋯ entries
 * take. Silently doing nothing here would be "a control that fails when pressed".
 */
export const BRANCH_EDIT_WAIT = 'Wait for the answer in flight before re-asking this question';

/**
 * The pager names itself as a GROUP and each control as a direction, because "1 / 3" alone is a
 * position with nothing to say what it positions.
 */
export const VERSION_PAGER_LABEL = 'Versions of this turn';
export const VERSION_PREVIOUS = 'Previous version';
export const VERSION_NEXT = 'Next version';
/** Why an END of the pager does not move — carried as `jf-control`'s reason, not left as silence. */
export const VERSION_AT_FIRST = 'This is the first version of this turn';
export const VERSION_AT_LAST = 'This is the last version of this turn';
export const versionPagerCount = (index: number, total: number): string => `${index + 1} / ${total}`;

/**
 * A refused branch, in the same grammar as the context refusals above: name the act, and say what
 * the conversation is now — an unchanged conversation is the reassurance the reader needs most,
 * because the act they pressed was about to replace what is on screen.
 */
export const BRANCH_FAILED = 'That thread could not be branched — this conversation is unchanged';
/** A Delegate turn's Retry/Edit, refused because the one shared agent controller is running (S2.6). */
export const BRANCH_RERUN_BUSY =
  'Another run is still going — stop it or let it finish, then run this again';

/** Delete's two new words: the cascade prompt, and the refusal when the store declined. */
export const DELETE_FAILED = 'That conversation could not be deleted';

/**
 * The citation pane's own words (tempdoc 822 Phase F8). The region is named for WHAT IT HOLDS — a
 * cited document — rather than "Document", because the pane's scope guard is that it never holds
 * anything else, and the landmark a screen reader announces should say so.
 */
export const PANE_LABEL = {
  region: 'Cited document',
  dismiss: 'Close the cited document',
  /** The grip names all three of its gestures; only pointing is discoverable on its own. */
  grip: 'Resize the cited document — arrow keys resize, Home returns to automatic, double-click resets',
} as const;

