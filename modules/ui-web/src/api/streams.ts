// SPDX-License-Identifier: Apache-2.0
/**
 * Streaming helpers for SSE endpoints.
 *
 * This replaces the legacy `apiClientStreams.js` with a typed implementation.
 * Defines a typed StreamEventV1 discriminated union for consistent stream handling.
 */

import { getSessionToken, resolveSessionTokenFromTauri, SESSION_TOKEN_HEADER } from './http';
import { parseSseBuffer, parseSseBufferJson } from './sse';
import { bumpChannelClosed, bumpChannelOpened } from '../shell-v0/state/liveChannelBudget.js';
// Tempdoc 941 — the app's ONE client-originated message channel (559 Authority III). Imported for
// the same reason `liveChannelBudget` is: the seam is a plain function over a document event, with
// no chrome dependency, so the stream can report through it without acquiring a view dependency.
import { emitEphemeralToast } from '../shell-v0/components/advisory/ephemeralToast.js';

// ==================== Stream Event Types ====================


/** Structured citation metadata emitted by RAG endpoints for click-to-verify UI. */
/**
 * A single sentence-to-source citation match from post-hoc embedding similarity.
 *
 * Tempdoc 822 §3b — `sourceIndex` is the source's POSITION in the turn's `rag.citations` array
 * (renamed from `chunkIndex`, which is the chunk's ordinal inside its parent document and never
 * travels on a match).
 */
export interface CitationMatch {
  sentenceIndex: number;
  sentenceText: string;
  sourceIndex: number;
  similarity: number;
  parentDocId: string;
  /**
   * Tempdoc 836 §4 — which TEXT this score is about: `SUPPLIED` (the caller's literal passage) or
   * `CHUNK_LOOKUP` (text re-fetched by parentDocId + chunkIndex). Per match, because the choice is
   * per source. Optional: a record persisted before the field existed carries none.
   */
  textSource?: string;
}

/**
 * Tempdoc 836 S2S3-A.1 — how much of ONE source's text was actually examined.
 *
 * Admission control preserves SENTENCE coverage by cutting WINDOWS, so `sentencesScored ===
 * sentencesTotal` can hold while most of a source's text was never looked at. `windowsConsidered >
 * 0 && windowsScored === 0` is the discriminator that keeps "the budget never examined this source"
 * apart from "this source supports nothing" — the same empty match list otherwise.
 */
export interface SourceCoverage {
  sourceIndex: number;
  windowsConsidered: number;
  windowsScored: number;
}

/** Payload for the citation_matches SSE event (sent after done). */
export interface CitationMatchesPayload {
  matches: CitationMatch[];
  sentencesTotal: number;
  sentencesMatched: number;
  tookMs: number;
  /**
   * Tempdoc 836 §4 — which PRODUCER wrote the similarities: `CROSS_ENCODER` | `EMBEDDING_COSINE` |
   * `NONE`. The two scales are measurably incomparable, so a threshold calibrated on one must not
   * read the other. Optional: absent on records persisted before the field existed.
   */
  scorer?: string;
  /**
   * Tempdoc 836 §3.6 — sentences actually SCORED (the backend's `BreakIterator` count is the
   * authority for the denominator; the frontend regex counter is a fallback, not a second one).
   */
  sentencesScored?: number;
  /** Tempdoc 836 S2S3-A.1 — the per-source TEXT-coverage axis. */
  sourceCoverage?: SourceCoverage[];
}

export interface ContextCitation {
  parentDocId: string;
  chunkIndex: number;
  chunkTotal: number;
  startChar: number;
  endChar: number;
  score?: number;
  excerpt?: string;
  // F8 Tier 2: In-document navigation
  startLine?: number;
  endLine?: number;
  headingText?: string;
  headingLevel?: number;
}

/**
 * F8 Tier 3: RAG retrieval metadata for transparency.
 * Emitted via 'rag.meta' event to show users how context was retrieved.
 * Renamed from the legacy `rag_meta` event name in slice 491 C3 (namespaced shape events).
 */
export interface RagMetaPayload {
  /** Retrieval mode used: BM25, HYBRID, FULLTEXT_FALLBACK */
  retrieval_mode?: string;
  /** Reason code explaining the mode choice */
  retrieval_mode_reason?: string;
  /** True if context was truncated due to budget */
  context_truncated?: boolean;
  /** Number of chunks used */
  chunks_used?: number;
  /** Total chunks found by search */
  chunks_found?: number;
  /** Slice 493: QualitySignals from ContextResult */
  best_chunk_score?: number;
  score_gap?: number;
  retrieval_coverage?: number;
  chunks_considered?: number;
}

interface RagMetaEvent {
  type: 'rag.meta';
  data: RagMetaPayload;
}

export interface AiUsage {
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  [key: string]: unknown;
}

/**
 * Meta event containing request metadata (e.g., truncation info, RAG status).
 *
 * RAG semantics (Phase 1.5 fix):
 * - `usedRag`: true if chunk-based RAG was used, false if fallback to full docs
 * - `chunksUsed`: number of chunks included in context (0 = fallback)
 * - `chunksFound`: total chunks matching the query (may be > chunksUsed due to limits)
 */
interface StreamMetaEvent {
  type: 'meta';
  data: {
    truncated?: boolean;
    /** True if chunk-based RAG was used, false if fallback to full documents. */
    usedRag?: boolean;
    /** Number of chunks included in context (0 means full-doc fallback). */
    chunksUsed?: number;
    /** Total chunks found by search (may be > chunksUsed due to limits). */
    chunksFound?: number;
    /** Structured chunk citations included in the assembled context (empty for fallback). */
    citations?: ContextCitation[];
    contextLength?: number;
    [key: string]: unknown;
  };
}

/** Progress event for multi-phase operations. */
interface StreamProgressEvent {
  type: 'progress';
  data: {
    phase?: string;
    message?: string;
    [key: string]: unknown;
  };
}


// ==================== Endpoint-Specific Done Payloads ====================

/**
 * Done payload for /api/summarize/stream (single document).
// Tempdoc 491 §9.D Phase E C3 (2026-05-14): SummarizeDonePayload and
// AskDonePayload interfaces were referenced only by the deleted endpoint
// wrappers (`summarizeDocumentStream`, `askQuestionStream`). The new typed
// views (SummarizeView, AskView) consume their shapes through the generic
// consumeShapeStream + the generated *Handlers interfaces. Removing these
// interfaces avoids tsc's "declared but never used" warning + the
// substrate's "typed payloads belong in the codegen layer" pattern.

// ==================== Handler Types ====================

/** Generic stream handlers with typed done payload. */
type StreamHandlers<TDone = Record<string, unknown>> = {
  onChunk?: (text: string) => void;
  onMeta?: (data: StreamMetaEvent['data']) => void;
  onProgress?: (data: StreamProgressEvent['data']) => void;
  onDone?: (data: TDone) => void;
  onError?: (err: Error & { code?: string; errorClass?: string; retryable?: boolean }) => void;
  /** F8 Tier 3: Handler for RAG metadata (retrieval mode transparency). */
  onRagMeta?: (data: RagMetaEvent['data']) => void;
  /** Post-hoc citation matching results (sent after done event). */
  onCitationMatches?: (data: CitationMatchesPayload) => void;
};


// ==================== SSE Parsing ====================

// SSE parsing is now handled by the spec-correct parser in ./sse.ts
// which supports CRLF/LF/CR line endings, proper multi-line data: handling,
// comment lines, and boundary tolerance.

// ==================== Core Stream Request ====================

/** Exported for testing. Callers should use endpoint-specific wrappers instead. */
export async function streamRequest<TDone = Record<string, unknown>>(
  url: string,
  payload: unknown,
  handlers: StreamHandlers<TDone> | undefined,
  signal?: AbortSignal
): Promise<void> {
  const { onChunk, onMeta, onProgress, onDone, onError, onRagMeta, onCitationMatches } = handlers || {};

  // Global demo_error simulation - works even with real backend (for evidence capture testing)
  if (typeof window !== 'undefined') {
    const params = new URLSearchParams(window.location.search);
    const demoError = params.get('demo_error') ?? params.get('demoError');
    if (demoError) {
      await new Promise((r) => setTimeout(r, 500)); // Brief delay for realism
      // Per tempdoc 431 §B14: the prior duplicated `errorMessages` + `errorClasses` maps
      // here were removed alongside the FE catalog deletion. Demo mode now emits a generic
      // error wrapping the requested code; the FE renders via the boot-fetched catalog
      // (`i18n/errorCatalog.ts`) using `errors.<code>` lookup. If demo mode needs richer
      // simulation again, route it through the backend's `/api/error-catalog` instead of
      // re-introducing a duplicated FE table.
      const error = new Error(`Demo error: ${demoError}`) as Error & {
        code?: string; errorClass?: string; retryable?: boolean; i18nKey?: string;
      };
      error.code = demoError;
      error.errorClass = 'PERMANENT';
      error.retryable = false;
      error.i18nKey = `errors.${demoError}`;
      onError?.(error);
      return;
    }
  }

  // Demo mode helper (baseUrl === "demo" produces URLs like "demo/api/...")
  // Also triggers when demo_ai=true is set (forces demo mock even with real backend)
  const forceDemoAi =
    typeof window !== 'undefined' &&
    (new URLSearchParams(window.location.search).get('demo_ai') ?? '') === 'true';
  if (url.startsWith('demo/') || forceDemoAi) {
    // Demo-only deterministic overrides (used by UI harness and e2e tests).
    // - demo_truncated=1|true → meta.truncated=true (shows "Partial" + "Truncated" UI)
    // - demo_rag=fallback|false|0 → meta.usedRag=false (shows fallback UI)
    const demoOverrides = (() => {
      try {
        if (typeof window === 'undefined') {
          return {
            truncated: false,
            usedRag: true,
            configuredContextTokens: 4096,
            llmContextTokens: 4096,
            citationMisaligned: false,
            streamDelayMs: 30,
            demoError: null,
          };
        }
        const params = new URLSearchParams(window.location.search);
        const truncatedRaw = params.get('demo_truncated') ?? params.get('demoTruncated');
        const truncated = truncatedRaw === '1' || truncatedRaw === 'true' || truncatedRaw === 'yes';
        const ragRaw = (params.get('demo_rag') ?? params.get('demoRag') ?? '').toLowerCase();
        const usedRag =
          ragRaw === 'fallback' || ragRaw === 'false' || ragRaw === '0' ? false : true;
        const misalignedRaw = params.get('demo_citation_misaligned') ?? params.get('demoCitationMisaligned');
        const citationMisaligned = misalignedRaw === '1' || misalignedRaw === 'true' || misalignedRaw === 'yes';
        const parsePosInt = (raw: string | null): number | null => {
          const n = raw == null ? NaN : Number(raw);
          return Number.isFinite(n) && n > 0 ? Math.floor(n) : null;
        };
        const parseNonNegInt = (raw: string | null): number | null => {
          const n = raw == null ? NaN : Number(raw);
          return Number.isFinite(n) && n >= 0 ? Math.floor(n) : null;
        };
        const configuredContextTokens =
          parsePosInt(params.get('demo_context_tokens') ?? params.get('demoContextTokens')) ?? 4096;
        const llmContextTokens =
          parsePosInt(params.get('demo_model_context_tokens') ?? params.get('demoModelContextTokens')) ??
          configuredContextTokens;
        const streamDelayMsRaw =
          parseNonNegInt(params.get('demo_stream_delay_ms') ?? params.get('demoStreamDelayMs')) ?? 30;
        const streamDelayMs = Math.min(500, streamDelayMsRaw);
        const usageTotalTokens =
          parsePosInt(params.get('demo_usage_total_tokens') ?? params.get('demoUsageTotalTokens')) ?? 152;
        // Error simulation for testing error states in evidence capture
        const demoError = params.get('demo_error') ?? params.get('demoError') ?? null;
        return { truncated, usedRag, configuredContextTokens, llmContextTokens, citationMisaligned, streamDelayMs, usageTotalTokens, demoError };
      } catch {
        return {
          truncated: false,
          usedRag: true,
          configuredContextTokens: 4096,
          llmContextTokens: 4096,
          citationMisaligned: false,
          streamDelayMs: 30,
          usageTotalTokens: 152,
          demoError: null,
        };
      }
    })();

    const maybeDocIds = (payload as any)?.docIds;
    const docId =
      Array.isArray(maybeDocIds) && maybeDocIds.length > 0
        ? String(maybeDocIds[0])
        : 'demo';

    // Mirror the demo preview content structure (so offsets are stable in demo mode).
    const previewPrefixChars = (() => {
      try {
        if (typeof window === 'undefined') return 0;
        const params = new URLSearchParams(window.location.search);
        const raw = params.get('demo_preview_prefix_chars') ?? params.get('demoPreviewPrefixChars');
        const n = raw == null ? NaN : Number(raw);
        return Number.isFinite(n) && n > 0 ? Math.min(100_000, Math.floor(n)) : 0;
      } catch {
        return 0;
      }
    })();

    const prefix = previewPrefixChars > 0 ? '·'.repeat(previewPrefixChars) + '\n' : '';

    const demoPreview = prefix + [
      `Demo preview for docId=${docId}`,
      '',
      'This is deterministic preview content used for UI harness screenshots.',
      'In a real run, /api/preview would return extracted text from the Worker (Lucene/Tika/VDU pipeline).',
      '',
      'Key UI behaviors validated in demo mode:',
      '- Inspector layout + scrolling',
      '- Highlighting',
      '- Summarize streaming (mock SSE)',
      '',
      'Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.',
    ].join('\n');

    const excerptNeedle = 'deterministic preview content';
    const idx = Math.max(0, demoPreview.indexOf(excerptNeedle));
    const misalignDelta = demoOverrides.citationMisaligned ? 3 : 0;
    const citations: ContextCitation[] = [
      {
        parentDocId: docId,
        chunkIndex: 0,
        chunkTotal: 1,
        startChar: idx + misalignDelta,
        endChar: idx + misalignDelta + excerptNeedle.length,
        score: 1,
        excerpt: excerptNeedle,
      },
    ];

    const usedRag = demoOverrides.usedRag;
    const truncated = demoOverrides.truncated;
    const citationsOut = usedRag ? citations : [];
    const chunksUsed = usedRag ? citationsOut.length : 0;
    const chunksFound = usedRag ? (truncated ? chunksUsed + 5 : chunksUsed) : 0;
    const configuredContextTokens = demoOverrides.configuredContextTokens;
    const llmContextTokens = demoOverrides.llmContextTokens;
    const usageTotalTokens = demoOverrides.usageTotalTokens ?? 0;
    const usage: AiUsage = { promptTokens: 24, completionTokens: usageTotalTokens - 24, totalTokens: usageTotalTokens };

    const mockText =
      'This is a simulated AI summary for **Demo Mode**.\n\n' +
      'In a real environment, this would be streamed from a local Llama model running on your GPU.\n\n' +
      'Key features demonstrated here:\n- Streaming typography\n- Markdown rendering\n- Error handling';
    const chunks = mockText.split(/(?=[ \n])/);

    let cancelled = false;
    if (signal) {
      signal.addEventListener(
        'abort',
        () => {
          cancelled = true;
        },
        { once: true }
      );
    }

    // Error simulation for testing error UI states (used by evidence capture)
    if (demoOverrides.demoError) {
      await new Promise((r) => setTimeout(r, 500)); // Brief delay for realism
      // Per tempdoc 431 §B14: duplicated `errorMessages` + `errorClasses` removed.
      // See note in the matching block above (`if (demoError)`) for rationale.
      const error = new Error(`Demo error: ${demoOverrides.demoError}`) as Error & {
        code?: string; errorClass?: string; retryable?: boolean; i18nKey?: string;
      };
      error.code = demoOverrides.demoError;
      error.errorClass = 'PERMANENT';
      error.retryable = false;
      error.i18nKey = `errors.${demoOverrides.demoError}`;
      onError?.(error);
      return;
    }

    try {
      onMeta?.({
        truncated,
        usedRag,
        chunksUsed,
        chunksFound,
        citations: citationsOut,
        contextLength: demoPreview.length,
      });
      for (const chunk of chunks) {
        if (cancelled) return;
        await new Promise((r) => setTimeout(r, demoOverrides.streamDelayMs ?? 30));
        onChunk?.(chunk);
      }
      if (!cancelled) {
        onDone?.(
          {
            summary: mockText,
            fileCount: Array.isArray(maybeDocIds) ? maybeDocIds.length : undefined,
            usedRag,
            chunksUsed,
            chunksFound,
            citations: citationsOut,
            usage,
            llmContextTokens,
            configuredContextTokens,
          } as TDone
        );
      }
    } catch (err: unknown) {
      if (err instanceof Error) {
        onError?.(err);
      } else {
        onError?.(new Error(String(err)));
      }
    }
    return;
  }

  const controller = new AbortController();
  if (signal) {
    signal.addEventListener('abort', () => controller.abort(), { once: true });
  }
  const mergedSignal = controller.signal;

  try {
    // Ensure session token is resolved before proceeding (A2: token hardening).
    // This blocks the stream POST until we know whether a token exists,
    // preventing race conditions where the request fires before token resolution completes.
    await resolveSessionTokenFromTauri();

    // Build headers with Content-Type and optional session token
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const sessionToken = getSessionToken();
    if (sessionToken) {
      headers[SESSION_TOKEN_HEADER] = sessionToken;
    }

    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: mergedSignal,
    });

    if (!response.ok || !response.body) {
      const text = await response.text().catch(() => '');
      throw new Error(text || `Failed to start stream (${response.status})`);
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let receivedTerminal = false;

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = parseSseBufferJson(buffer, (event, parsed) => {
        if (event === 'chunk') {
          onChunk?.(typeof parsed.text === 'string' ? parsed.text : '');
        } else if (event === 'meta') {
          onMeta?.(parsed as StreamMetaEvent['data']);
        } else if (event === 'progress') {
          onProgress?.(parsed as StreamProgressEvent['data']);
        } else if (event === 'done') {
          if (!receivedTerminal) {
            receivedTerminal = true;
            onDone?.(parsed as TDone);
          }
        } else if (event === 'error') {
          if (!receivedTerminal) {
            receivedTerminal = true;
            const error = new Error(
              typeof parsed.error === 'string' ? parsed.error : 'Request failed'
            ) as Error & { code?: string; errorClass?: string; retryable?: boolean; i18nKey?: string };
            if (typeof parsed.errorCode === 'string') {
              error.code = parsed.errorCode;
            }
            if (typeof parsed.errorClass === 'string') {
              error.errorClass = parsed.errorClass;
            }
            if (typeof parsed.retryable === 'boolean') {
              error.retryable = parsed.retryable;
            }
            // Tempdoc 431 Option A (D.2.d): all error surfaces (REST, summary SSE, agent SSE)
            // emit `i18nKey` on the wire. The FE no longer derives `"errors." + code` for
            // any error event.
            if (typeof parsed.i18nKey === 'string') {
              error.i18nKey = parsed.i18nKey;
            }
            onError?.(error);
          }
        } else if (event === 'rag.meta') {
          // F8 Tier 3: RAG retrieval metadata for transparency. Renamed from the legacy
          // `rag_meta` event name in slice 491 C3 to match the namespaced shape-event
          // vocabulary (`<shape-namespace>.<event>`).
          onRagMeta?.(parsed as RagMetaEvent['data']);
        } else if (event === 'rag.citation_matches') {
          // Post-hoc citation matching results. Renamed from the legacy `citation_matches`
          // event name in slice 491 C3 for the same namespacing reason.
          onCitationMatches?.(parsed as unknown as CitationMatchesPayload);
        }
      });
    }

    // Detect silent stream close: if the stream ended without a terminal SSE event
    // (done/error), the connection was likely dropped — fire onError so callers
    // (e.g., useAppAI) can clear loading state instead of showing a stuck spinner.
    if (!receivedTerminal) {
      const error = new Error('Stream ended without completion') as Error & { code?: string };
      error.code = 'STREAM_INCOMPLETE';
      onError?.(error);
    }
  } catch (err: unknown) {
    if (mergedSignal.aborted) {
      const error = new Error('Request cancelled') as Error & { code?: string };
      error.code = 'CANCELLED';
      onError?.(error);
      return;
    }
    if (err instanceof Error) {
      onError?.(err);
    } else {
      onError?.(new Error(String(err)));
    }
  }
}

// ==================== Endpoint Wrappers ====================
//
// Tempdoc 491 §9.D Phase E C3 (2026-05-14): the per-endpoint wrappers
// `summarizeDocumentStream` and `askQuestionStream` were deleted. Both shipped
// as typed envelopes with zero in-repo production callers (verified by grep
// during the §9.E A5 audit; 2026-05-12 entry 445 had already flagged this as
// "structurally migrated but functionally dead"). The new typed views
// (`AskView`, `SummarizeView`) consume their respective shapes through the
// generic `consumeShapeStream` + `dispatchShapeEventToHandlers` helpers
// declared below (the prior §C2 addition); the per-endpoint wrappers are no
// longer the right shape for the substrate-shape-rule's "view consumes its
// shape directly" pattern.
//
// `summarizeDocumentBatchStream` was deleted earlier (Phase C5, 2026-05-12)
// for the same reason. The legacy /api/summarize/batch/stream endpoint is
// gone; the substrate's POST /api/chat/batch-summarize shape
// (BatchSummarizeShape) is hosted by future typed views consuming it via
// `consumeShapeStream`.

// ==================== Slice 491 §9.D Phase E (C2) — consumeShapeStream ====================

/**
 * Slice 491 §9.D Phase E (C2) — generic shape-stream consumer.
 *
 * POSTs `body` to `url`, parses the SSE response, and dispatches each
 * event to `onEvent(eventName, parsedPayload)`. The typed view wraps this with
 * its own dispatcher that calls the right method on its generated
 * `*Handlers` interface via {@link dispatchShapeEventToHandlers} (the event-
 * name → method-name conversion matches the codegen in
 * `scripts/codegen/gen-shape-handlers.mjs`).
 *
 * Why a separate function from `streamRequest`:
 * - `streamRequest` is hardcoded to summarize/ask events (onChunk / onMeta /
 *   onProgress / onRagMeta / onCitationMatches / onDone / onError) plus
 *   significant demo-mode machinery (~300 LOC). C3 deletes the orphaned
 *   wrappers (`summarizeDocumentStream`, `askQuestionStream`) and re-routes
 *   the typed views through `consumeShapeStream`.
 * - This is shape-agnostic; the typed view decides what to do with each event.
 *
 * Resolves on stream EOF. Throws if an `error` event arrived (with `code` +
 * `errorClass` if present) or on non-2xx HTTP.
 */
export interface ShapeStreamOptions {
  /**
   * Tempdoc 834 §1.6 — the RUN-stream resume cursor, sent as `?sinceSeq=<seq>`.
   *
   * Deliberately not `?since=`: that parameter carries a `ResumeTokenCodec` token on the envelope
   * stream family, and reusing the name for a raw integer would be a silent grammar fork. A run
   * stream refuses a `?since=` outright rather than reading it as 0. Omitted (or 0) replays
   * everything, which is the guaranteed path — the backend's window rule rejects only a cursor
   * ahead of the stream or behind the retained window.
   */
  readonly sinceSeq?: number;
}

/**
 * Tempdoc 834 §1.6 — the typed body of a run stream's 404. `reason` distinguishes "this run is over,
 * read the record" from "never heard of it", and `recordHint` names where the record lives. A 200
 * with an empty stream is what this exists to avoid.
 */
export interface RunNotFoundDetail {
  readonly runId: string;
  readonly reason: 'unknown' | 'retired';
  readonly recordHint: string;
}

/** Tempdoc 834 §2 — emitted when the requested cursor predates the retained replay window. */
export interface ReplayTruncatedPayload {
  readonly sinceSeq: number;
  readonly oldestRetainedSeq: number;
}

/** The SSE event name carrying {@link ReplayTruncatedPayload}. */
export const REPLAY_TRUNCATED_EVENT = 'replay_truncated';

/** The SSE event name carrying a run's identity triple (`runId`, `shapeId`, `conversationId`). */
export const RUN_STARTED_EVENT = 'run_started';

/**
 * Tempdoc 941 — report a per-event handler throw without letting it kill the stream.
 *
 * The throw must not abort the stream: one broken consumer (a citations handler, a reasoning
 * handler) must not cost the reader the rest of an answer that is still arriving. But it was
 * previously only `console.warn`ed, which means the APP never learned anything — and the whole
 * point of 847 F-12 is that the user-visible result of a throwing evidence handler is
 * indistinguishable from a backend that sent nothing. A console line is not a channel: nobody has
 * devtools open, and the turn renders as though the missing part was never sent.
 *
 * So it goes out through the app's ONE client-originated message channel
 * ({@link emitEphemeralToast} — 559 Authority III), the same seam every other client-side notice
 * uses. The wording is about the consequence the reader can actually observe (part of the response
 * is missing), not about the exception: `handlerError` stays on the console line for the person
 * who can act on it.
 *
 * Deduped per stream per EVENT NAME, because the failure mode is systematic, not incidental: a
 * `chunk` handler that throws once throws on every token, and one bug must not become several
 * hundred toasts. First occurrence per event name is the signal; the rest are the same fact.
 */
function reportHandlerFailure(
  eventName: string,
  handlerError: unknown,
  alreadyReported: Set<string>,
): void {
  console.warn(`[stream] handler for "${eventName}" threw`, handlerError);
  if (alreadyReported.has(eventName)) return;
  alreadyReported.add(eventName);
  emitEphemeralToast({
    message:
      'Part of this response could not be displayed — the rest of it is still arriving. ' +
      'See the browser console for the failure detail.',
    severity: 'warning',
  });
}

export async function consumeShapeStream(
  url: string,
  body: unknown,
  onEvent: (event: string, payload: unknown) => void,
  signal?: AbortSignal,
  options?: ShapeStreamOptions,
): Promise<void> {
  let token = getSessionToken();
  if (!token) {
    try {
      token = await resolveSessionTokenFromTauri();
    } catch {
      // best-effort — non-Tauri contexts get null token (allowed in dev)
    }
  }
  const headers: Record<string, string> = { 'content-type': 'application/json' };
  if (token) headers[SESSION_TOKEN_HEADER] = token;

  const target =
    options?.sinceSeq && options.sinceSeq > 0
      ? `${url}${url.includes('?') ? '&' : '?'}sinceSeq=${encodeURIComponent(String(options.sinceSeq))}`
      : url;

  const response = await fetch(target, {
    method: 'POST',
    headers,
    body: JSON.stringify(body ?? {}),
    signal,
  });

  if (!response.ok || !response.body) {
    const text = await response.text().catch(() => '');
    const err = new Error(
      `consumeShapeStream: HTTP ${response.status} from ${target} — ${text.slice(0, 200)}`,
    ) as Error & { status?: number; runNotFound?: RunNotFoundDetail };
    err.status = response.status;
    // Tempdoc 834 §1.6 — a run stream answers an unknown/retired runId with a TYPED 404 naming
    // where the record lives. Surfacing it as a generic HTTP error would throw away the only thing
    // that tells the caller whether to show "this run is over, read the transcript" or a real error.
    if (response.status === 404) {
      try {
        const parsed = JSON.parse(text) as Partial<RunNotFoundDetail>;
        if (parsed && typeof parsed.runId === 'string' && typeof parsed.reason === 'string') {
          err.runNotFound = {
            runId: parsed.runId,
            reason: parsed.reason === 'retired' ? 'retired' : 'unknown',
            recordHint: typeof parsed.recordHint === 'string' ? parsed.recordHint : '',
          };
        }
      } catch {
        // Not the typed body — leave the generic error alone.
      }
    }
    throw err;
  }

  const reader = response.body.getReader();
  // Tempdoc 662 — the one shared chat/agent/summarize generation entry point; bump the runtime
  // peak signal for the duration the response body stream is held open.
  bumpChannelOpened();
  const decoder = new TextDecoder();
  let buffer = '';
  let errorFromEvent: (Error & { code?: string; errorClass?: string }) | null = null;
  let receivedTerminal = false;
  /** Event names whose handler failure this stream has already reported (see the dedup note). */
  const reportedHandlerEvents = new Set<string>();

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = parseSseBuffer(buffer, (ev) => {
        let payload: unknown = ev.data;
        if (ev.data && ev.data.length > 0) {
          try {
            payload = JSON.parse(ev.data);
          } catch {
            // Non-JSON payloads pass through as raw string.
          }
        }
        if (ev.event === 'done' || ev.event === 'error') {
          receivedTerminal = true;
        }
        // Tempdoc 834 §2 — `replay_truncated` says "your cursor predates what is still retained;
        // here is the window that does exist". It is NOT terminal and NOT an error: the stream
        // continues from oldestRetainedSeq. It reaches the caller through the generic dispatch
        // below, alongside `run_started`, rather than being intercepted here.
        if (ev.event === 'error') {
          const p = payload as Record<string, unknown> | null;
          const message =
            (p?.error as string) ?? (p?.message as string) ?? 'shape stream error';
          const err = new Error(message) as Error & {
            code?: string;
            errorClass?: string;
          };
          if (p?.errorCode) err.code = p.errorCode as string;
          if (p?.errorClass) err.errorClass = p.errorClass as string;
          errorFromEvent = err;
        }
        try {
          onEvent(ev.event, payload);
        } catch (handlerError) {
          reportHandlerFailure(ev.event, handlerError, reportedHandlerEvents);
        }
      });
    }
  } finally {
    reader.releaseLock();
    bumpChannelClosed();
  }

  if (errorFromEvent) throw errorFromEvent;

  if (!receivedTerminal) {
    const err = new Error('Stream ended without terminal event') as Error & { code?: string };
    err.code = 'STREAM_INCOMPLETE';
    throw err;
  }
}

/**
 * Slice 491 §9.D Phase E (C2) — dispatch SSE events to typed handler methods.
 *
 * Converts an event name to its handler-method name using the same algorithm
 * as the codegen (chunk → onChunk; tool_call_proposed → onToolCallProposed;
 * navigate.url_extracted → onNavigateUrlExtracted), then invokes the method
 * on `handlers` if it exists. Unmapped events are silently skipped (matches
 * the codegen's optional-method posture).
 */
export function dispatchShapeEventToHandlers(
  handlers: Record<string, unknown>,
  eventName: string,
  payload: unknown,
): void {
  const methodName =
    'on' +
    eventName
      .split(/[._]/)
      .filter((s) => s.length > 0)
      .map((s) => s.charAt(0).toUpperCase() + s.slice(1))
      .join('');
  const fn = handlers[methodName];
  if (typeof fn === 'function') {
    // Bind `this` to the handlers object so class-instance handlers see their
    // own state. Inline-object handlers are unaffected (they don't use `this`).
    (fn as (p: unknown) => void).call(handlers, payload);
  }
}
