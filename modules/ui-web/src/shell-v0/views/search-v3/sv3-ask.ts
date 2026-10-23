// SPDX-License-Identifier: Apache-2.0
/**
 * sv3-ask — the ONE ask-issuance site of the Search v3 window (tempdoc 822 Phase F1).
 *
 * Derived from a third-party design system (MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * Every answer this window produces is dispatched from {@link sv3Ask} and nowhere else, and
 * `sv3-ask.forbid.test.ts` fails the build if a second `consumeShapeStream` caller appears in
 * `search-v3/`. That is the point: the shipped window's L9 defect is not that its 423 handling is
 * wrong, it is that ONE of its send paths dispatches without any — an asymmetry only a second
 * issuance site can have. With one site the refusal is structural: there is no second path to forget.
 *
 * Shared authorities consumed, not re-authored (the charter's "from-scratch components, shared
 * authorities"; the PATTERN was mined from the since-retired search-v2 window's ask client — never
 * imported, because a dev window importing another dev window's module is the coupling this arc
 * exists to avoid):
 *  - `buildRequestBody('core.rag-ask', …)` — the per-shape POST body.
 *  - `consumeShapeStream` + `dispatchShapeEventToHandlers` — the ONE SSE consumer, so the
 *    connection-budget accounting in `streams.ts` stays correct.
 *  - the GENERATED `CoreRagAskHandlers` interface — a new shape event is a compile-time fact.
 *  - `claimsToCitations` (Phase F4) — the ONE claim→mark resolver, so this window's inline `[n]`
 *    marks are the same resolution the shipped surfaces render.
 *  - `friendlyStreamError` (Phase F7) — the ONE stream-error vocabulary.
 *
 * Phase F7 also opened the two channels this shape declares and F1 left unread: `reasoning_chunk`
 * (the model's thinking) and `rag.rewrite` (the standalone question retrieval ran on). Both are
 * handed to the caller rather than interpreted here — the reasoning payload goes to the shared
 * `ReasoningController` verbatim.
 *
 * Registered in `governance/execution-surfaces.v1.json` (`sv3-ask-client`) as an opaque carrier of
 * the `RetrievalCitation` evidence record: it accumulates the backend's citation payloads and hands
 * them to its caller unchanged, deriving nothing from a citation's fields.
 *
 * FOUR TERMINALS, EXACTLY ONE OF THEM, ALWAYS. `sv3Ask` never throws: completion, refusal (the
 * session lock's 423), a halt the reader asked for, and failure are four distinct sink calls, so the
 * caller's turn can always reach a terminal state and can always say WHICH one it reached. A halt is
 * not a failure and must not be worded as one — the reader stopped the answer on purpose.
 */

import {
  consumeShapeStream,
  dispatchShapeEventToHandlers,
  type RagMetaPayload,
} from '../../../api/streams.js';
import type { CoreRagAskHandlers } from '../../../api/generated/shape-handlers/core-rag-ask.js';
import { buildRequestBody } from '../unifiedChatRequest.js';
import { authorizedFetch } from '../../api/authorizedFetch.js';
// The ONE stream-error vocabulary (slice 497; inventory E9). A technical code becomes the same
// sentence here as it does in the shipped window; an abort never reaches it (see the catch below).
import { friendlyStreamError } from '../../utils/streamError.js';
import type {
  CitationMatch,
  Claim,
  RetrievalCitation,
} from '../../components/chat/citationTypes.js';
// The ONE claim→mark resolver (565 §15.B). The window resolves nothing itself: it hands the
// backend's claims + sources to the shared authority and stores what comes back.
import { claimsToCitations } from '../../components/chat/citationResolve.js';
// Tempdoc 847 S2 / F-12 — the ONE `claimMatches` envelope reader, so this window's producer gate IS
// the shipped window's, not a second copy of it (§2.5: defence in depth through one authority).
//
// F-12 widened this from the gate to the WHOLE envelope. S2 imported `readScorer` and applied the
// producer verdict through the shared authority, but kept reading every other field off the raw
// payload here — so the live arm and the reloaded arm were still two readers of one shape, and only
// the gate was shared. `claimsFromRecord` + `matchesFromRecord` are that shape's readers; the live
// handler below now calls exactly them, so a field the record arm normalizes (a legacy `chunkIndex`
// position, an absent `parentDocId`) cannot be normalized on one path and dropped on the other.
import {
  claimsFromRecord,
  matchesFromRecord,
  readScorer,
  coverageFactsFromRecord,
} from '../../components/chat/recordEvidence.js';
import type { Sv3ContextUsage, Sv3TurnEvidence } from './sv3-sessions.js';
// Tempdoc 610 §E.4 — the ONE reading of the terminal's own occupancy report, next to the meter it
// feeds rather than inlined in the handler table below.
import { readSv3ContextUsage } from './sv3-context.js';

/** The one shape this window's ask route dispatches. */
export const SV3_ASK_SHAPE_ID = 'core.rag-ask';

/**
 * How much work the next question asks for (tempdoc 822 Phase F10; the §4b ADAPTATION RATIFIED —
 * the spec's per-session provider picker maps to an effort control, because there is ONE local
 * model and therefore no provider to pick).
 *
 * Every rung is a REAL request parameter the shared dispatch path already reads. Verified at the
 * source rather than assumed, because a control that changes nothing is the exact failure this
 * window exists to avoid:
 *  - `enableThinking` — `ConversationEngine.java:781-786` turns it into `SamplingParams`, and
 *    `OnlineModeOps.java:611-614` forwards it to llama-server as
 *    `chat_template_kwargs={"enable_thinking": …}`. It is the switch behind the reasoning block
 *    Phase F7 wired (inventory C9).
 *  - `maxTokens` — `ConversationEngine.java:772-778`; absent means the engine's own
 *    `DEFAULT_MAX_TOKENS = 1024` (`:65`).
 *  - `topK` — `RAGContext.extractTopK`; an explicit per-request value still always wins, verbatim.
 *    What changed with tempdoc 883 decision 3 is the DEFAULT: it is no longer the flat
 *    `DEFAULT_TOP_K = 5` but `max(1, min(configured, inputBudget / DEFAULT_CHUNK_TOKENS))` — how
 *    many corpus-sized passages this turn's context budget can actually hold, bounded above by the
 *    configured `justsearch.rag.top_k`. So a small window asks for fewer passages instead of asking
 *    for five it cannot hold; only the request's own number escapes that bound.
 *
 * There is deliberately NO reasoning-effort or posture parameter in the table: the backend has
 * none (`buildRequestBody` declares question/prompt/docIds/schema/sessionId/selection and nothing
 * else), and inventing a name the server ignores would be the dead control in disguise.
 *
 * ONE honest side effect, stated where the mapping is made: `parseSamplingParams` returns sampling
 * params ONLY when `enableThinking` is present, and the object it builds pins temperature 0.8 /
 * top_p 0.95 with it (`ConversationEngine.java:780-786`). So the two rungs that name thinking also
 * pin those two, and `standard` — which sends no parameter at all — is the only rung that leaves
 * every sampling decision to the backend. That is why `standard` is the default and sends `{}`
 * rather than a spelled-out copy of the defaults: a rung that restates the backend's numbers would
 * silently fork them the first time they change.
 */
export type Sv3Effort = 'quick' | 'standard' | 'thorough';

export interface Sv3EffortOption {
  readonly id: Sv3Effort;
  /** The trigger's label when this rung is chosen — the spec's trigger IS the current value. */
  readonly label: string;
  /** One line, in the menu, saying exactly what the rung changes (the spec's item description). */
  readonly description: string;
  /** The rung the window starts on; the spec badges it in the menu. */
  readonly isDefault: boolean;
}

/** The menu's group label (one per descriptor). */
export const SV3_EFFORT_MENU_LABEL = 'Effort';

/**
 * Tempdoc 859 §D §2.3 — ONE description set per rung, naming BOTH quantities the rung governs.
 *
 * The rung means two different things depending on where the send goes: how long an ASK's answer is
 * ({@link sv3EffortParams}), and how much room a DELEGATED run gets (`AgentBudgetPolicy`). The
 * obvious design is to switch the copy by tier — and it is unshippable, because `Sv3Composer`
 * dispatches `delegate` on Ctrl/Cmd+Enter REGARDLESS of the tier control (852 kept the accelerator
 * deliberately). A reader in ask mode, looking at ask copy, can delegate: the menu would have just
 * told them the rung means answer length at the moment it is about to mean run budget. Copy and act
 * disagreeing is the one state this control may not have.
 *
 * So each line names both, and is true in both modes. This is a deliberate divergence from a
 * literal reading of 859 §4 ("say which meaning is active"): with the chord in place, neither
 * meaning is reliably the active one at read time, so naming both is the honest form of that
 * requirement rather than a weaker version of it.
 */
export const SV3_EFFORT_OPTIONS: readonly Sv3EffortOption[] = [
  {
    id: 'quick',
    label: 'Quick',
    // Never worded as a limiter: every rung RAISES a delegated run's old allowance (859 §D §2.2).
    // Quick is the smallest raise, and the leash is between rungs, not against today's behaviour.
    description: 'Short answer; a couple of steps when delegated.',
    isDefault: false,
  },
  {
    id: 'standard',
    label: 'Standard',
    description: 'Leaves the settings to the model; room for a handful of steps.',
    isDefault: true,
  },
  {
    id: 'thorough',
    label: 'Thorough',
    description: 'Thinks first and answers at length; room to work through many steps.',
    isDefault: false,
  },
];

export const SV3_EFFORT_DEFAULT: Sv3Effort = 'standard';

export const isSv3Effort = (value: unknown): value is Sv3Effort =>
  SV3_EFFORT_OPTIONS.some((option) => option.id === value);

export const sv3EffortLabel = (effort: Sv3Effort): string =>
  SV3_EFFORT_OPTIONS.find((option) => option.id === effort)?.label ?? '';

/**
 * The rung → request parameters mapping, and the ONLY place it is made. Each field below is one
 * sentence of the option's description, so a copy change that stops being true has to happen here,
 * next to the parameter that made it true.
 */
export function sv3EffortParams(effort: Sv3Effort): Readonly<Record<string, unknown>> {
  switch (effort) {
    case 'quick':
      return { enableThinking: false, maxTokens: 512 };
    case 'thorough':
      return { enableThinking: true, maxTokens: 3072, topK: 12 };
    case 'standard':
    default:
      return {};
  }
}

export interface Sv3AskRequest {
  readonly apiBase: string;
  /** Present only for Structured; the existing dispatch and terminal rules are shared. */
  readonly schema?: string;
  readonly question: string;
  /** The window's session id — stamped on every dispatch so the backend records the turn. */
  readonly conversationId: string;
  /** The composer's effort rung for THIS send; omitted is the default rung, not "no parameters". */
  readonly effort?: Sv3Effort;
  /**
   * WP1.1 — the document PATHS retrieval is limited to (what the backend's `docIds` filter
   * matches). Omitted asks the whole index; the window never sends an empty list for a choice.
   */
  readonly docIds?: readonly string[];
  /**
   * The conversation so far, as `{role, content}` messages, for a FOLLOW-UP (S2.0) — the window's
   * effective context (`sv3-context.ts` sv3FollowUpContext). The ask shape is ephemeral, so this is
   * all the model and the follow-up rewrite know of the earlier turns. Omitted or empty sends none.
   */
  readonly context?: readonly { readonly role: string; readonly content: string }[];
  /** This dispatch's own id, which the reader's Stop names to the Engine ({@link sv3StopAsk}). */
  readonly turnId?: string;
  readonly signal?: AbortSignal;
}

/** The `errorCode` the Engine ends a turn with when the reader's Stop ended it. */
const SV3_STOPPED_CODE = 'CANCELLED';

export interface Sv3AskSink {
  /** Streaming text, delta by delta. The accumulated answer lives in the caller's turn. */
  onDelta(text: string): void;
  /**
   * The retrieval evidence the backend minted for this answer, as a WHOLE snapshot — never a delta
   * and never a count. Phase F1 kept only the count because the window had no resolver behind it;
   * Phase F4 has the shared ones, so the turn stores what the answer actually stood on and every
   * number on screen is read off that one record (tempdoc 822 Phase F4).
   *
   * Called on each contributing event rather than once at the terminal: retrieval happens BEFORE the
   * text finishes, so a halted turn keeps the sources that really arrived.
   */
  onEvidence(evidence: Sv3TurnEvidence): void;
  /**
   * The model's own thinking, chunk by chunk (`reasoning_chunk`, a declared event of this shape —
   * `api/generated/shape-handlers/core-rag-ask.ts`). Handed over as the RAW payload because the
   * SHARED `ReasoningController` is the one thing that parses it, accumulates it and times it
   * (inventory C9); a second parse here would be a second reasoning model.
   */
  onReasoning(payload: unknown): void;
  /**
   * A follow-up was decontextualized before retrieval ran (`rag.rewrite`; tempdoc 603 C2, inventory
   * C8). What is handed over is what retrieval ACTUALLY searched for, which is the whole point of
   * showing it back.
   */
  onRewrite(standalone: string): void;
  /**
   * The turn reached its terminal, carrying whatever the `done` payload said about the PROMPT it
   * spent (tempdoc 610 §E.4 — `promptTokens` + the estimated `contextBreakdown`), or `null` when it
   * reported none. Handed over rather than stored here: the occupancy belongs to the conversation,
   * and this client owns no conversation.
   */
  onDone(usage: Sv3ContextUsage | null): void;
  /** The session lock refused this send (HTTP 423) — the ONLY 423 consumer in this window. */
  onRefused(): void;
  /**
   * The Engine ended the turn because the reader pressed Stop, and saved it as stopped. Whatever
   * streamed so far is kept; the turn is halted, not failed.
   */
  onHalted(): void;
  /**
   * The window let the stream go (it left the conversation, or went away) and was told nothing more:
   * the Engine goes on and saves the turn as it ends, so what this window holds of it is not what is
   * saved.
   */
  onLeft(): void;
  onFailed(message: string): void;
}

/**
 * The per-sentence grounding model both citation events contribute to. Mined from the since-retired
 * search-v2 window's ask client (the same reason F1 mined the dispatch: a dev window importing
 * another dev window's module is the coupling this arc exists to avoid); the RESOLVER it feeds is
 * shared.
 */
interface ClaimAcc {
  text: string;
  verifiedScore: number | null;
  lexicalScore: number;
  /**
   * Tempdoc 822 §3b — the refs are split by producer for the same reason the scores are: deltas
   * arrive FIRST, so one merged set made `refs[0]` the streaming guess on every doubly-matched
   * sentence, and the resolver took `refs[0]`. Only the verified set may resolve a mark.
   */
  verifiedRefs: Set<number>;
  lexicalRefs: Set<number>;
}

/**
 * Tempdoc 822 §3d — which EVENT scored this sentence: `rag.citation_delta` (the streaming lexical
 * matcher) or `rag.citation_matches` (the authoritative post-hoc one). That distinction really is
 * the handler's own, and needs no wire field.
 *
 * Tempdoc 847 §1.5 — it is NOT the whole provenance, and reading it as such was this window's gate
 * hole. 836 introduced a second distinction INSIDE `rag.citation_matches` — which SCORER wrote the
 * similarity (cross-encoder vs the embedding-cosine fallback) — that the handler cannot know and the
 * payload states outright in its `scorer` field. So the handler answers "which event", the envelope
 * answers "which producer", and only both together admit a score as verified.
 */
type ScoreProvenance = 'verified' | 'lexical';

function mergeClaim(
  acc: Map<number, ClaimAcc>,
  sentenceIndex: number,
  sentenceText: string,
  /**
   * `null` on the verified side means the sentence WAS matched but by a producer whose scale the
   * grounding thresholds are not calibrated for (847 §2.5). The claim still exists — it is what
   * arrived — it simply carries no verified score and resolves no ref, so it mints no mark.
   */
  score: number | null,
  provenance: ScoreProvenance,
  sourceIndex: number | null,
): void {
  const existing = acc.get(sentenceIndex);
  if (existing) {
    // The two scores are maxed WITHIN a scale, never across one (822 §3d: no monotone mapping
    // between a coverage ratio and a relevance probability exists to max over), and the refs land
    // on the matching side for the same reason (822 §3b).
    if (provenance === 'verified') {
      // 847 S5 — the verified side's text WINS. The two sides segment differently (the draft cuts
      // an incomplete markdown buffer as prose; the final cuts parsed block nodes), so at the same
      // `sentenceIndex` they are usually different sentences. Keeping the draft's text would hand
      // the renderer a key that no longer names the sentence that earned the score — a mark placed
      // on one sentence by another's evidence, and a live render that disagrees with the reload.
      if (sentenceText) existing.text = sentenceText;
      if (score !== null) existing.verifiedScore = Math.max(existing.verifiedScore ?? 0, score);
      if (sourceIndex !== null) existing.verifiedRefs.add(sourceIndex);
    } else {
      existing.lexicalScore = Math.max(existing.lexicalScore, score ?? 0);
      if (sourceIndex !== null) existing.lexicalRefs.add(sourceIndex);
    }
    return;
  }
  const verifiedRefs = new Set<number>();
  const lexicalRefs = new Set<number>();
  if (sourceIndex !== null) {
    (provenance === 'verified' ? verifiedRefs : lexicalRefs).add(sourceIndex);
  }
  acc.set(sentenceIndex, {
    text: sentenceText,
    verifiedScore: provenance === 'verified' ? score : null,
    lexicalScore: provenance === 'lexical' ? (score ?? 0) : 0,
    verifiedRefs,
    lexicalRefs,
  });
}

/**
 * Tempdoc 847 §2.5 — the producer is STAMPED onto every claim, so `claimsToCitations`'s own gate
 * (`citationResolve.ts:45`) sees the same fact this file did. Absent (`null`) is left off the claim
 * entirely rather than written as an empty string: an envelope that predates the field is a
 * different statement from one that named a producer, and `isVerifiedProducer` distinguishes them.
 */
function claimsOf(acc: Map<number, ClaimAcc>, scorer: string | null): Claim[] {
  return [...acc.entries()]
    .map(([sentenceIndex, v]) => ({
      sentenceIndex,
      sentenceText: v.text,
      ...(scorer !== null ? { scorer } : {}),
      verifiedScore: v.verifiedScore,
      lexicalScore: v.lexicalScore,
      verifiedRefs: [...v.verifiedRefs],
      lexicalRefs: [...v.lexicalRefs],
    }))
    .sort((a, b) => a.sentenceIndex - b.sentenceIndex);
}

/**
 * Dispatch one grounded ask and drive `sink` from the stream.
 *
 * `docIds` is empty by construction: this window has no committed document set to scope an answer to
 * (the retired search-v2 window's frozen-snapshot law has no counterpart here yet), and an empty
 * array is the backend's documented open-retrieval fallback (`views/unifiedChatRequest.ts:80-84`) rather than a scope this
 * FE invented.
 */
export async function sv3Ask(req: Sv3AskRequest, sink: Sv3AskSink): Promise<void> {
  const body = buildRequestBody(req.schema === undefined ? SV3_ASK_SHAPE_ID : 'core.extract', req.question, req.conversationId, req.schema ?? '', [
    ...(req.docIds ?? []),
  ]);
  // The turn is recorded against THIS window's session, exactly as the shipped window stamps it.
  body.conversationId = req.conversationId;
  if (req.schema !== undefined && (req.docIds?.length ?? 0) > 0) {
    body.selection = { kind: 'result-set', items: [...new Set(req.docIds)].map(id => ({ id, kind: 'search-hit' })) };
  }
  // The effort rung's parameters, added AFTER the shared builder rather than inside it: the builder
  // is the shipped window's authority over the per-shape body and this window may not widen it. The
  // fields land beside `conversationId`, which arrives the same way and for the same reason.
  Object.assign(body, sv3EffortParams(req.effort ?? SV3_EFFORT_DEFAULT));
  // The follow-up's conversation, read by `ExternalContextInjector` (into the prompt) and
  // `QueryRewriteInjector` (to rewrite the question). Same late-add as the fields above.
  if (req.context !== undefined && req.context.length > 0) {
    body.context = req.context.map((m) => ({ role: m.role, content: m.content }));
  }
  if (req.turnId !== undefined) body.turnId = req.turnId;

  let sources: readonly RetrievalCitation[] = [];
  let matches: readonly CitationMatch[] = [];
  let retrievalMode = '';
  let retrievalModeReason = '';
  let retrievalModeCause = '';
  /** The producer the LAST `rag.citation_matches` envelope named; `null` until one says (847 §2.5). */
  let scorer: string | null = null;
  let coverage: ReturnType<typeof coverageFactsFromRecord> = {};
  const claims = new Map<number, ClaimAcc>();
  /**
   * The evidence record is rebuilt WHOLE on every contributing event and handed over as one value,
   * so its parts can never be written separately and drift: the marks are always the resolution of
   * exactly the claims and sources beside them.
   */
  const publish = (): void =>
    sink.onEvidence({
      sources,
      matches,
      marks: claimsToCitations(claimsOf(claims, scorer), sources),
      retrievalMode,
      retrievalModeReason,
      retrievalModeCause,
      ...coverage,
    });

  /**
   * What the terminal said about the prompt it spent. Captured from the `done` EVENT — the stream's
   * own last frame — rather than assumed at the call below, which fires for a stream that ended
   * without one too.
   */
  let usage: Sv3ContextUsage | null = null;

  const handlers: CoreRagAskHandlers = {
    onDone(payload: unknown) {
      usage = readSv3ContextUsage(payload);
      if (req.schema !== undefined) {
        const final = (payload as { finalResponse?: unknown } | null)?.finalResponse;
        if (typeof final === 'string') sink.onDelta(final);
      }
    },
    onReasoningChunk(payload: unknown) {
      sink.onReasoning(payload);
    },
    onRagRewrite(payload: unknown) {
      const p = payload as { standalone?: unknown } | null;
      if (typeof p?.standalone !== 'string') return;
      sink.onRewrite(p.standalone);
    },
    onChunk(payload: unknown) {
      const p = payload as { text?: unknown } | string | null;
      const delta = typeof p === 'string' ? p : typeof p?.text === 'string' ? p.text : '';
      if (delta === '' || req.schema !== undefined) return;
      sink.onDelta(delta);
    },
    onRagMeta(payload: unknown) {
      const p = payload as RagMetaPayload | null;
      if (!p || typeof p.retrieval_mode !== 'string') return;
      retrievalMode = p.retrieval_mode;
      retrievalModeReason = p.retrieval_mode_reason ?? '';
      retrievalModeCause = p.retrieval_mode_cause ?? '';
      publish();
    },
    onRagCitations(payload: unknown) {
      const p = payload as { citations?: RetrievalCitation[] } | null;
      if (!p || !Array.isArray(p.citations)) return;
      sources = p.citations;
      publish();
    },
    onRagCitationDelta(payload: unknown) {
      const p = payload as {
        sentenceIndex?: number;
        sentenceText?: string;
        citations?: Array<{ sourceIndex?: number; score?: number }>;
      } | null;
      if (!p || typeof p.sentenceText !== 'string' || !Array.isArray(p.citations)) return;
      const best = Math.max(0, ...p.citations.map((c) => (typeof c.score === 'number' ? c.score : 0)));
      const ref = p.citations[0]?.sourceIndex;
      mergeClaim(
        claims,
        p.sentenceIndex ?? 0,
        p.sentenceText,
        best,
        'lexical',
        typeof ref === 'number' ? ref : null,
      );
      publish();
    },
    onRagCitationMatches(payload: unknown) {
      const p = payload as { matches?: CitationMatch[] } | null;
      if (!p || !Array.isArray(p.matches)) return;
      // Tempdoc 847 §2.5 — the PRODUCER gate, read off the envelope through the shared authority
      // (836 §4). Without it this window merged every match as verified, painting embedding-cosine
      // numbers — whose supported and unsupported bands interleave at a 0.0049 margin (836 §9.7) —
      // with cross-encoder-calibrated grounding tiers.
      scorer = readScorer(p);
      coverage = coverageFactsFromRecord(p);
      // The panel answers to the same verdict as the marks: an unadmitted producer's matches carry
      // no per-source tier, so the sources list and the answer text say the same thing (847 §2.3).
      matches = matchesFromRecord(p);
      // Tempdoc 847 F-12 — the verified half of this turn's claims is what the SHARED envelope
      // reader says it is, so a live answer and the same answer after a reload are built from one
      // parse of one payload. The result is MERGED rather than assigned because the live arm has a
      // second contributor the record arm does not: the lexical `rag.citation_delta` claims already
      // in `claims`, which live on their own side of the accumulator (§3b/§3d).
      //
      // A claim the reader admitted no ref for still merges — with `null` — because the sentence WAS
      // matched and must exist as a claim; it simply resolves no mark (see `mergeClaim`).
      for (const claim of claimsFromRecord(p)) {
        const refs: readonly (number | null)[] =
          claim.verifiedRefs.length > 0 ? claim.verifiedRefs : [null];
        for (const ref of refs) {
          mergeClaim(
            claims,
            claim.sentenceIndex,
            claim.sentenceText,
            claim.verifiedScore,
            'verified',
            ref,
          );
        }
      }
      publish();
    },
  };

  try {
    await consumeShapeStream(
      `${req.apiBase}/api/chat/dispatch`,
      body,
      (event, payload) =>
        dispatchShapeEventToHandlers(handlers as Record<string, unknown>, event, payload),
      req.signal,
    );
  } catch (err) {
    // The signal is consulted BEFORE the error is classified: an abort surfaces as whatever the
    // platform's fetch rejects with (`DOMException: AbortError` in a browser, a plain error in some
    // test environments), so keying on the error's shape would report the reader's own Stop as a
    // backend failure on some runtimes and not others.
    if (req.signal?.aborted === true) {
      sink.onLeft();
      return;
    }
    // The Engine's own end of a turn the reader stopped: it is saved as stopped (`CANCELLED`).
    if ((err as Error & { code?: string }).code === SV3_STOPPED_CODE) {
      sink.onHalted();
      return;
    }
    // The ONE refusal path. `consumeShapeStream` stamps the HTTP status on the thrown error, so a
    // lock taken between this window's render and its submit is a typed outcome here, not a generic
    // failure the caller has to sniff for.
    if ((err as Error & { status?: number }).status === 423) {
      sink.onRefused();
      return;
    }
    // The shared mapping, reached only AFTER the abort and the refusal have been ruled out above —
    // which is exactly the shipped window's ordering (`views/UnifiedChatView.ts:6019-6024`) and the
    // reason a reader's own Stop is never worded as a failure (inventory E9).
    sink.onFailed(friendlyStreamError(err));
    return;
  }

  sink.onDone(usage);
}

/**
 * The reader's Stop, told to the Engine (S2.3). Aborting the stream alone only leaves: the Engine
 * keeps answering and saves a turn the reader never saw finish. Told, it ends the turn and saves it
 * as stopped, with its question, its "Interpreted as" note, its thinking, its sources and what had
 * streamed. A Stop that overtakes its dispatch stops the turn when it starts. A closed tab sends
 * nothing, and a turn whose answer is already saved is left as it is.
 *
 * The stream is NOT let go here: the turn's own stream says how it ended — stopped (and saved so)
 * or finished first — and carries every word the Engine sent before the Stop landed, which is what
 * it saves. Resolves whether the Engine took the Stop; false when it was refused or never answered,
 * and the answer goes on.
 */
export async function sv3StopAsk(
  apiBase: string,
  conversationId: string,
  turnId: string,
): Promise<boolean> {
  try {
    const response = await authorizedFetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(conversationId)}/stop`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ turnId }),
      },
    );
    return response.ok;
  } catch {
    return false;
  }
}
