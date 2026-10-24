// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-honesty — the Search v3 window's honesty derivations (tempdoc 822 Phase F7).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * The three facts this window must not get wrong, each derived ONCE here and rendered nowhere else:
 * whether the conversation store is readable at all, what an answer actually stood on, and how many
 * documents there are to stand on. Everything is PURE — a snapshot and a turn arrive as arguments —
 * so each can be tested without a DOM, and so no render site can re-derive one of them differently.
 *
 * Nothing here authors a VERDICT of its own: the frame and its wording come from the shared
 * `components/chat/evidenceProjection.ts` authority (tempdoc 559 Authority IV / 577 Move 3), the same
 * one the shipped window reads. A ranking-trace explanation instead names its subject and input;
 * it makes no claim about grounding in document content.
 */
import type { AiState } from '../../state/aiStateStore.js';
// The ONE answer-frame authority. The window derives no grounding verdict and words none: it hands
// over (shape × sources × coverage) and renders what comes back (`views/UnifiedChatView.ts:4974`
// does the same through its own `frameFor`).
import {
  answerFrame,
  filenameOf,
  answerFrameLabel,
  groundingCoverage,
  coverageHonesty,
  groundingDegraded,
  sourcesAreChunkPrecise,
  type AnswerFrame,
} from '../../components/chat/evidenceProjection.js';
import { SV3_ASK_SHAPE_ID, SV3_MODEL_SHAPE_ID } from './sv3-ask.js';
import { CITATIONS_LABEL, SOURCES_LABEL } from './fixtures.js';
import type { Sv3Turn, Sv3TurnEvidence } from './sv3-sessions.js';

/* ── The one remedy channel ──────────────────────────────────────────────────────────────────── */

/**
 * Raised by whichever region is showing a remedy the reader took — the locked transcript's "Unlock in
 * Security", the empty corpus's "Add folders in Library". ONE event and ONE handler in the window,
 * because two regions reaching the app's navigation authority themselves would be two places this
 * window can decide where the reader goes (596 §11.4's remedy nav, kept to a single exit).
 */
export const SV3_REMEDY = 'sv3-remedy';

export interface Sv3RemedyDetail {
  /** The surface that OWNS the fix — never one hop short of it. */
  readonly target: string;
}

/* ── The lock (inventory E5) ─────────────────────────────────────────────────────────────────── */

/**
 * Is the conversation store locked, as of this observed-state snapshot?
 *
 * TRI-STATE ON PURPOSE (tempdoc 734, the mechanism at `views/UnifiedChatView.ts:1038-1048`): the
 * poll reports `locked` or `unlocked`, and anything else — a snapshot with no status yet, a field an
 * older backend omits — is NOT "unlocked", it is *not said*. Collapsing the unknown into `false`
 * would unlock the view on the first status frame that happened to omit the field. Before 734 the
 * flag was written once from the initial 423, so a lock taken elsewhere (an idle auto-lock, another
 * tab) left the transcript readable forever; reading it from every snapshot is what closes that, at
 * the poll's own ~10s bound.
 */
export function deriveSv3HistoryLocked(previous: boolean, snapshot: AiState | null): boolean {
  const state = snapshot?.status?.conversationProtection?.state;
  if (state === 'locked') return true;
  if (state === 'unlocked') return false;
  return previous;
}

/* ── The corpus (inventory E10) ──────────────────────────────────────────────────────────────── */

/**
 * How many documents the next question can be answered from — or that the window does not yet know.
 *
 * THREE states, where the shipped landing has two (`views/UnifiedChatView.ts:3021-3039` treats a
 * `null` count exactly like a real `0` and offers the remedy either way). A count that has not been
 * reported yet is not a corpus of zero: telling a reader to add folders when the window simply has
 * no settled poll would be the same over-claim in the other direction from the one tempdoc 811 C-4
 * fixed. `unknown` therefore says nothing at all.
 */
export type Sv3Corpus =
  | { readonly kind: 'unknown' }
  | { readonly kind: 'documents'; readonly count: number }
  | { readonly kind: 'empty' };

export const SV3_CORPUS_UNKNOWN: Sv3Corpus = { kind: 'unknown' };

/**
 * 811 C-4 — `searchableDocumentCount` is the default-search-scope population and is the number the
 * reader's next question will actually run against; `documentCount` is the fallback for a backend
 * that does not report it. A reported `0` is real and gets the remedy.
 */
export function projectSv3Corpus(snapshot: AiState | null): Sv3Corpus {
  const index = snapshot?.lastSettledIndex;
  if (index == null) return SV3_CORPUS_UNKNOWN;
  const count = index.searchableDocumentCount ?? index.documentCount;
  if (typeof count !== 'number') return SV3_CORPUS_UNKNOWN;
  return count > 0 ? { kind: 'documents', count } : { kind: 'empty' };
}

/* ── The answer frame (inventory C1) ─────────────────────────────────────────────────────────── */

/**
 * The quiet receipt tail: how long it took, and which model produced it. Ported from the shipped
 * window's `formatReceiptTail` (`views/UnifiedChatView.ts:5003-5017`) — the same thresholds, so a
 * duration does not read one way in one window and another way in the next.
 *
 * NEVER FABRICATED: each part is omitted when the window was not told it, which is why both
 * arguments are nullable rather than defaulted. A negative duration (a clock that moved backwards) is
 * dropped for the same reason.
 */
export function sv3ReceiptTail(durationMs: number | null, modelLabel: string | null): string {
  const parts: string[] = [];
  if (durationMs !== null && durationMs >= 0) {
    parts.push(durationMs >= 1000 ? `${(durationMs / 1000).toFixed(1)} s` : `${durationMs} ms`);
  }
  if (modelLabel !== null && modelLabel !== '') parts.push(modelLabel);
  return parts.join(' · ');
}

/* ── Cut short (tempdoc 859 §D §2.6) ─────────────────────────────────────────────────────────── */

/**
 * The two TRUNCATING dispositions — a run that stopped before finishing its work — each with the
 * line that says WHAT stopped it.
 *
 * `BUDGET_EDGE_FINALIZE`: the budget ran out and the model was given one last call to synthesise
 * whatever it had. `MAX_ITERATIONS`: the loop hit its step ceiling.
 *
 * ONE STRING PER DISPOSITION, not one for both (859 D live-defect D5). A single "Cut short at the
 * budget limit" line served both, so a `MAX_ITERATIONS` run — which the live audit watched end with
 * 59% of its budget UNSPENT — told the reader that tokens had stopped it. That is a specific false
 * statement about why the answer is partial, made at the exact moment the reader is deciding what to
 * do next: raising the budget would have changed nothing. The two limits have different remedies, so
 * they get different sentences.
 *
 * The other three (`COMPLETED`, `ERRORED`, `CANCELLED`) are not truncations: the first finished, and
 * the other two already have their own honest surfaces (the error text, the halt receipt).
 */
const SV3_TRUNCATION_NOTICES: ReadonlyMap<string, string> = new Map([
  [
    'BUDGET_EDGE_FINALIZE',
    'Cut short at the budget limit — this answer is based on what the run had gathered by then',
  ],
  [
    'MAX_ITERATIONS',
    'Cut short at the step limit — this answer is based on what the run had gathered by then',
  ],
]);

/**
 * Tempdoc 878 §D.1 — the step ceiling's ANSWERLESS arm.
 *
 * Until 878 the ceiling emitted an empty answer with no LLM call at all, so one sentence covered it:
 * "the run used all its steps before reaching an answer". The ceiling now makes the same no-tools
 * finalize call the budget wall has always made, so that sentence became false whenever the call
 * returned text — and the map's line above became false whenever it did not. Both outcomes are real
 * (a compact model can still decline, 859 §7), so the disposition alone no longer determines the
 * sentence and the second input is required rather than convenient.
 */
const SV3_CUT_SHORT_STEPS_ANSWERLESS =
  'Cut short at the step limit — the run used all its steps before reaching an answer';

/**
 * The compact badge on a DELEGATE turn's receipt, beside its outcome.
 *
 * <p>It lives on {@link ../sv3-run.sv3RunReceiptLabel}, not on {@link sv3ReceiptTail}: the tail is
 * built by {@link projectSv3AnswerFrame}, which refuses anything but an `ask` turn — so an ask turn
 * can never carry a disposition and a delegate turn never reaches the tail. Threading the parameter
 * through both would have added a branch nothing could reach.
 */
export const SV3_CUT_SHORT_BADGE = 'cut short';

/**
 * The full line on the settled turn — what the badge means, in the reader's terms, FOR THE LIMIT
 * THAT ACTUALLY FIRED. `null` for anything that was not a truncation, so a render site branches on
 * the value rather than re-deriving the set.
 *
 * `hasAnswer` (878 §D.1) is what distinguishes the step ceiling's two outcomes: it now attempts a
 * synthesis, and either sentence would be a false statement in the other case. It is read only for
 * `MAX_ITERATIONS` — the budget wall reaches its notice only by producing text, so its line is the
 * same either way — and defaults to `true`, which is the arm that says LESS about what the run
 * failed to do.
 */
export function sv3CutShortNotice(
  disposition: string | null | undefined,
  hasAnswer: boolean = true,
): string | null {
  if (disposition == null) return null;
  if (disposition === 'MAX_ITERATIONS' && !hasAnswer) return SV3_CUT_SHORT_STEPS_ANSWERLESS;
  return SV3_TRUNCATION_NOTICES.get(disposition) ?? null;
}

/** The budget arm of {@link sv3CutShortNotice}, exported so a test can name it without a literal. */
export const SV3_CUT_SHORT_BUDGET_NOTICE = SV3_TRUNCATION_NOTICES.get('BUDGET_EDGE_FINALIZE')!;

/** The step-ceiling arm — the one a single shared string used to mis-attribute to the budget. */
export const SV3_CUT_SHORT_STEPS_NOTICE = SV3_TRUNCATION_NOTICES.get('MAX_ITERATIONS')!;

/** The step ceiling's answerless arm (878 §D.1), exported for the same reason as its sibling. */
export const SV3_CUT_SHORT_STEPS_NO_ANSWER_NOTICE = SV3_CUT_SHORT_STEPS_ANSWERLESS;

/**
 * Whether the run behind this answer was truncated.
 *
 * <p>WHY THIS IS NOT DERIVED FROM THE ANSWER'S TEXT: 859 §7 watched a cut-short run produce a
 * confidently formatted, content-free non-answer that disclosed nothing. The backend writes the
 * disposition AFTER and INDEPENDENTLY of the finalize text, so a model cannot talk its way out of
 * this badge — which is the entire point. An unknown or absent disposition discloses NOTHING; it is
 * never read as "completed".
 */
export function sv3WasCutShort(disposition: string | null | undefined): boolean {
  return sv3CutShortNotice(disposition) !== null;
}

/**
 * The terminal disposition of a run the READER stopped (`TerminalDisposition.CANCELLED`, carried onto
 * the persisted assistant message by `AgentInteractionMapper`'s `done` case). Named here because this
 * module already owns the disposition vocabulary — a second literal at a read site is how the record
 * and the live tail come to disagree about the same string.
 */
export const SV3_DISPOSITION_CANCELLED = 'CANCELLED';

/**
 * Whether the run behind this turn was stopped by the reader.
 *
 * <p>Live audit 2026-08-25 (D3) — this exists because the RECORD had no way to say so. The live tail
 * reads "stopped by you" ({@link ../sv3-run.sv3RunOutcome} returns `halted` on the halt request), but
 * the record's projection derived its status from the error entry alone, so the same cancelled run
 * came back from a reload reading "failed": one run, two stories, and the one the record told blamed
 * the product for the reader's own decision. The disposition was already ON the record — it was just
 * not consulted — so the fix is a read, not a new field.
 */
export function sv3WasHalted(disposition: string | null | undefined): boolean {
  return disposition === SV3_DISPOSITION_CANCELLED;
}

/**
 * The typed code the cancel path stamps on its error entry ({@code AgentErrorCode.CANCELLED},
 * carried onto the record's ERROR event by `AgentInteractionMapper`'s `error` case as
 * `attributes.errorCode`).
 *
 * <p>Owner decision 2026-08-26 (answerless cancel) — {@link sv3WasHalted} reads a disposition that a
 * cancelled run does not always have. A run stopped BEFORE it produced an answer emits `AgentError`
 * and never `AgentDone` (`AgentStepRunner.executeIteration`), so no assistant row is written, no
 * disposition reaches the record, and the status derivation fell through to the error entry and
 * called the reader's own Stop a failure. The code is TYPED and was already on the record — matching
 * the error's prose ("Session cancelled") would have been a proxy for a fact the wire states.
 */
export const SV3_ERROR_CODE_CANCELLED = 'CANCELLED';

/**
 * The durable progress phase the backend writes when a cancel arrives ({@code
 * AgentEvent.AgentProgress.PHASE_STOP_REQUESTED}, listed in `AgentInteractionMapper`'s
 * `DURABLE_PROGRESS_PHASES`).
 *
 * <p>Owner decision 2026-08-26 (late cancel) — it exists for the case neither of the two above can
 * cover: a stop that lands as the run reaches its terminal changes nothing about the run, so the
 * disposition stays `COMPLETED` and no error entry is written. The reader's act is real and the
 * record has to hold it, or a refresh re-words their Stop as "finished".
 */
export const SV3_PHASE_STOP_REQUESTED = 'stop_requested';

/**
 * Whether a record ITEM says the reader stopped this run — the ONE reader of the two facts above, so
 * a site that wants the answer asks here instead of matching a literal.
 *
 * <p>Takes the item's kind and attributes rather than a pre-extracted field, because the two facts
 * live on different kinds and a caller that had to know which one to look at would be re-deriving
 * this rule at the read site.
 */
export function sv3RecordItemStopsRun(
  kind: string,
  attributes: Readonly<Record<string, unknown>>,
): boolean {
  if (kind === 'error') return attributes.errorCode === SV3_ERROR_CODE_CANCELLED;
  if (kind === 'progress') return attributes.phase === SV3_PHASE_STOP_REQUESTED;
  return false;
}

/**
 * The answer's honest frame line: what it is based on, how long it took, which model wrote it.
 *
 * 810 §T-B singles this out as owner-valued credit worth preserving — *"Based on your documents —
 * per-sentence grounding not verified · 45.7 s · Qwen_Qwen3.5-9B"* — and the whole line is derived,
 * never composed from a template with a hopeful value in it:
 *
 *  - the LABEL is the shared authority's, taken from the shape's declared grounding class refined by
 *    what this run actually produced. It is `null` for a fully-grounded answer, because the inline
 *    marks already say so and a banner repeating them would be the twice-rendered fact 814 §D5 bans.
 *  - the TAIL is the turn's own measured duration and, when it is still needed, the model recorded AT
 *    ITS TERMINAL — never the model that happens to be loaded when the transcript is re-read. Since
 *    Phase F11 the model's ordinary home is the composer, so the stamp is named here only when it
 *    differs from what the composer is currently saying ({@link sv3TailModelLabel}) — and only for a
 *    reader in Detailed mode (inventory E3). The DURATION is not gated: "how long it took" is plain,
 *    and the grounding verdict is an honesty fact, so neither is a disclosure decision.
 *
 * The LABEL arrives as one string and is rendered as two ({@link splitSv3FrameLabel}): the verdict
 * rests, the elaboration extends (L14). Both halves are the authority's own substrings.
 *
 * A turn the backend never sent retrieval evidence for gets NO label: `evidence === null` is "never
 * told", and framing it as "searched your documents and found nothing to cite" would be inventing a
 * search that may not have happened. Its tail still renders — the duration is measured, not reported.
 */
export interface Sv3AnswerFrame {
  /** `"Partly grounded"` — the authority's verdict half, which RESTS. `null` needs no line at all. */
  readonly verdict: string | null;
  /** `"some statements are not backed by your documents"` — the elaboration half, or `''`. */
  readonly elaboration: string;
  /** `"45.7 s"` (+ `" · <model>"` only when the model differs), or `''` when nothing was known. */
  readonly tail: string;
}

/** The separator the shared authority words every two-part frame label with (U+2014, spaced). */
const FRAME_LABEL_SEPARATOR = ' — ';

/**
 * The authority's label is `"<verdict> — <elaboration>"` for every frame it words
 * (`components/chat/evidenceProjection.ts:171-188`). This RE-WORDS NOTHING: both halves are the
 * authority's own substrings, and the whole string survives verbatim wherever the reader is given
 * the full line (the tail's accessible name and its `title`).
 *
 * Fail-safe direction matters: a label the authority ever words WITHOUT the em dash rests ENTIRELY,
 * because the failure the window can afford is more text, never less.
 */
export function splitSv3FrameLabel(label: string): { verdict: string; elaboration: string } {
  const at = label.indexOf(FRAME_LABEL_SEPARATOR);
  if (at < 0) return { verdict: label, elaboration: '' };
  return {
    verdict: label.slice(0, at),
    elaboration: label.slice(at + FRAME_LABEL_SEPARATOR.length),
  };
}

/**
 * Which model to name IN THE TAIL, given the one this turn was stamped with at its terminal and the
 * one the composer is currently naming (tempdoc 822 Phase F11).
 *
 * The model moved into the composer, and a composer names the CURRENT model — so for an old answer
 * written by a different one, the composer would be mislabelling it. That is the exact defect the
 * per-turn stamp exists to avoid (see the note above), so the stamp REAPPEARS in the tail precisely
 * when, and only when, it would otherwise be contradicted:
 *
 *  - same model  → `null`: the composer already says it, and saying it twice is the duplicated fact.
 *  - different   → the STAMPED one: this answer was not written by what the composer names.
 *  - current unknown → the stamped one: "not said" is not "the same".
 *
 * And ahead of all three (inventory E3): a model id is a TECHNICAL fact, so Simple mode never names
 * one. `detailed` has no default — every caller states which mode it is rendering for, because a
 * disclosure decision made by omission is the one that silently stops being made.
 */
export function sv3TailModelLabel(
  stamped: string | null,
  current: string | null,
  detailed: boolean,
): string | null {
  if (!detailed) return null;
  if (stamped === null || stamped === '') return null;
  return stamped === current ? null : stamped;
}

/**
 * Tempdoc 869 C2b — THE window's answer frame, computed once and consumed twice.
 *
 * It was computed here already, inside {@link projectSv3AnswerFrame}, and thrown away after the tail
 * LABEL was worded — so the transcript's `<jf-markdown-block>` sites, which need the frame itself,
 * had no way to reach it and rendered at the renderer's default `'grounded'`. That is a fork by
 * construction: the tail line could say "Based on your documents — per-sentence grounding not
 * verified" while the block beneath it framed itself as grounded and let the model's own `[n]`
 * literals pose as verified refs. One computation, two projections, no second authority.
 *
 * `null` means "never told": `evidence === null` is a turn the backend sent no retrieval evidence
 * for, and framing it would be inventing a search that may not have happened. Evidence is
 * accumulated at the terminal, so a non-null one is settled — which is what the `settled` argument
 * below asserts.
 */
export function sv3AnswerFrame(turn: Sv3Turn): AnswerFrame | null {
  if (turn.kind === 'model') {
    return answerFrame(SV3_MODEL_SHAPE_ID, 0, groundingCoverage([], ''));
  }
  if (turn.status === 'no-evidence') return null;
  const evidence = turn.evidence;
  if (evidence === null) return null;
  return answerFrame(
    SV3_ASK_SHAPE_ID,
    evidence.sources.length,
    // Tempdoc 822 §3b — the coverage counts the RESOLVED MARKS, not the raw match list: a claim the
    // resolver dropped (no verified ref, or one addressing no source) renders no mark, so counting
    // it would claim a verification the reader cannot see. The frame degrades because the evidence
    // degraded — the same read the shipped window makes.
    groundingCoverage(evidence.marks, turn.answer, coverageHonesty(evidence), evidence.sentencesTotal),
    sourcesAreChunkPrecise(evidence.sources),
    // Settled: the evidence record exists only once the terminal landed, so the matcher has finished
    // and a zero-cite answer can no longer excuse itself as "marks pending".
    true,
  );
}

/** The request selection owns Explain's identity. Older records can identify their subject
 * citation (which deliberately contains no document passage), but never their editable wording.
 */
export function sv3ExplainsRankingTrace(turn: Sv3Turn | null): boolean {
  if (turn?.shapeId !== 'core.summarize') return false;
  if (turn.selectionKind !== undefined) return turn.selectionKind === 'search-trace';
  return turn.evidence?.sources.some(source => source.chunkIndex === -1
    && source.startChar === 0 && source.endChar === 0 && source.excerpt === '') ?? false;
}

export function projectSv3AnswerFrame(
  turn: Sv3Turn,
  currentModelLabel: string | null,
  detailed: boolean,
): Sv3AnswerFrame | null {
  // Model turns always name their provenance, including while streaming or after a failure.
  // Only a completed Ask gets a grounding frame; its other outcomes have their own notes.
  if (turn.kind !== 'model' && (turn.kind !== 'ask' || turn.status !== 'complete')) return null;
  const tail = sv3ReceiptTail(
    turn.durationMs,
    sv3TailModelLabel(turn.modelLabel, currentModelLabel, detailed),
  );
  if (sv3ExplainsRankingTrace(turn)) {
    const subject = turn.evidence?.sources[0];
    return {
      verdict: subject
        ? `Explains the ranking of ${filenameOf(subject.parentDocId)} from its ranking trace`
        : 'Explains the ranking trace',
      elaboration: '', tail,
    };
  }
  // Older source-free summaries did not record their input kind. Do not turn that missing
  // provenance into a claim that documents were searched, or infer it from editable prose.
  if (turn.shapeId === 'core.summarize' && turn.selectionKind === undefined
      && turn.evidence !== null && turn.evidence.sources.length === 0) {
    return tail === '' ? null : { verdict: null, elaboration: '', tail };
  }
  const frame = sv3AnswerFrame(turn);
  if (frame === null) {
    // `evidence === null` — "never told", so there is no verdict to word; the tail still renders.
    return tail === '' ? null : { verdict: null, elaboration: '', tail };
  }
  const sourceCount = turn.evidence?.sources.length ?? 0;
  const label = answerFrameLabel(frame, groundingDegraded(turn.kind === 'model' ? SV3_MODEL_SHAPE_ID : SV3_ASK_SHAPE_ID, sourceCount));
  if (label === null && tail === '') return null;
  if (label === null) return { verdict: null, elaboration: '', tail };
  return { ...splitSv3FrameLabel(label), tail };
}

/* ── The tail's sources disclosure (tempdoc 822 Phase F11) ───────────────────────────────────── */

/** What the tail's disclosure may be called for this turn, or `null` when there is nothing to open. */
export type Sv3SourcesTrigger = typeof SOURCES_LABEL | typeof CITATIONS_LABEL;

/**
 * The window's ONE disclosure affordance for a turn's evidence, and the word it may use.
 *
 * The word is not decorative: with a retrieval set the panel holds SOURCES, but with only
 * per-sentence matches it holds citation-matches and nothing was reported as retrieved — calling
 * that "Sources" would claim a retrieval the window was never told about. The trigger's presence
 * mirrors `CitationsPanel.render`'s own both-empty short-circuit, so a trigger can never open onto
 * an empty panel.
 */
export function sv3SourcesTrigger(evidence: Sv3TurnEvidence | null): Sv3SourcesTrigger | null {
  if (evidence === null) return null;
  if (evidence.sources.length > 0) return SOURCES_LABEL;
  return evidence.matches.length > 0 ? CITATIONS_LABEL : null;
}

/**
 * Whether the disclosure carries its COUNT on the resting surface (`5 Sources`) or only in its
 * accessible name (`Sources`, `aria-label="Sources: 5"`).
 *
 * `false` is the owner's literal direction (tempdoc 822 F11 §4 choice 1(i)). It is a named constant
 * rather than an inlined `false` because the alternative — the quiet count, which keeps a resting
 * honesty fact L14 placed deliberately — must stay a ONE-LINE flip, and both branches are tested.
 */
export const SV3_SOURCES_COUNT_IN_TRIGGER = false;

/** How many the disclosure would be speaking for — sources when there are any, else the matches. */
export function sv3SourcesTriggerCount(evidence: Sv3TurnEvidence | null): number {
  if (evidence === null) return 0;
  return evidence.sources.length > 0 ? evidence.sources.length : evidence.matches.length;
}

/** The disclosure's VISIBLE label, under the constant above. Its accessible name always has the count. */
export function sv3SourcesTriggerLabel(
  trigger: Sv3SourcesTrigger,
  count: number,
  countInTrigger: boolean = SV3_SOURCES_COUNT_IN_TRIGGER,
): string {
  return countInTrigger ? `${count} ${trigger}` : trigger;
}
