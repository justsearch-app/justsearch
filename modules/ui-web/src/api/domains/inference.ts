// SPDX-License-Identifier: Apache-2.0
/**
 * Inference domain API - AI inference, summarization, preview, and LLM models
 */

import { request, createApiError } from '../http';
import { parseWireContract } from '../schemas';
// Tempdoc 564 Phase B (4b): AiRuntimeStatusResponse is the single generated wire-contract projection
// (record → JSON Schema → {TS, Zod}); the hand type + fail-open `.loose()` Zod are retired.
import {
  aiRuntimeStatusResponseSchema,
  type AiRuntimeStatusResponse,
} from '../generated/schema-types/ai-runtime-status-response';

export type { AiRuntimeStatusResponse } from '../generated/schema-types/ai-runtime-status-response';

// ============================================
// Types
// ============================================

// Tempdoc 491 Phase C1: SummarizeRequest + SummarizeResponse removed alongside the sync
// `summarize()` helper. The streaming variant in `../streams.ts` uses an inline payload
// shape and the `SummarizeDonePayload` from `streams.ts` (substrate-default done shape).

// GPU capabilities types + getGpuCapabilities were dead (zero call sites) — removed (564).

interface PreviewResponse {
  docId?: string;
  requestedDocId?: string;
  normalizedFromChunk?: boolean;
  offsetChars?: number;
  maxChars?: number;
  content: string;
  truncated?: boolean;
  nextOffsetChars?: number;
  mime?: string | null;
  title?: string | null;
  path?: string | null;
  source?: string | null;
  // VDU provenance fields (added for D_vdu_provenance)
  /**
   * Text provenance: 'tika', 'ocr', 'vdu', 'vdu_pending', 'vdu_processing', 'vdu_failed',
   * 'vdu_empty' (tempdoc 677: VDU ran and found no text), or 'vdu_rejected' (tempdoc 677
   * abstention gate: VDU output was untrustworthy or the call was skipped on an illegible
   * input — baseline content is retained)
   */
  textProvenance?: TextProvenance | null;
  /** VDU status: 'PENDING', 'PROCESSING', 'COMPLETED', 'COMPLETED_EMPTY', 'REJECTED', 'FAILED', or 'NOT_NEEDED' */
  vduStatus?: VduStatus | null;
  /** Whether VDU processing has completed for this document */
  vduProcessed?: boolean;
  /** Number of pages processed by VDU (for PDFs/images) */
  vduPageCount?: number | null;
  /** VDU enrichment JSON (summary, doc_type, entities) if available */
  vduEnrichment?: string | null;
  /** Compact OCR/VDU routing evidence for preview source explanation */
  visualExtractionEvidence?: VisualExtractionEvidence | null;
}

/** Text provenance values indicating how the preview content was extracted */
type TextProvenance =
  | 'tika'
  | 'ocr'
  | 'vdu'
  | 'vdu_pending'
  | 'vdu_processing'
  | 'vdu_failed'
  | 'vdu_empty'
  | 'vdu_rejected';

/** VDU processing status values */
type VduStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'COMPLETED_EMPTY'
  | 'REJECTED'
  | 'FAILED'
  | 'NOT_NEEDED';

interface VisualExtractionEvidence {
  schemaVersion?: number;
  pageCount?: number;
  textCharCount?: number;
  textQualityScore?: number;
  charsPerPage?: number;
  alphanumericRatio?: number;
  ocrLanguage?: string;
  ocrMeanConfidence?: number;
  ocrLowConfidenceWordCount?: number;
  ocrWordCount?: number;
  pagesWithTextLayer?: number;
  pagesMissingReadableText?: number;
  mixedPdf?: boolean;
  structuredElementCounts?: {
    tables?: number;
    headings?: number;
    lists?: number;
  };
  imagePageCount?: number;
  layoutComplexity?: string;
  contentTruncated?: boolean;
  ocrFallbackRoute?: string;
  ocrSkipReason?: string;
  route?: string;
}

// ============================================
// API Functions
// ============================================

// Tempdoc 491 Phase C1 (2026-05-12): the sync `summarize()` helper that POSTed to
// /api/summarize was removed alongside the legacy endpoint. Callers route through the
// streaming `summarizeDocumentStream()` in `../streams.ts` (POST /api/chat/summarize)
// — which is the substrate-driven SummarizeShape. No remaining FE callers were found
// at the time of removal.


/**
 * Gets AI runtime status (activation state, installed variants, active runtime) with dev-mode validation.
 */
export async function getAiRuntimeStatus(baseUrl: string, signal?: AbortSignal): Promise<AiRuntimeStatusResponse> {
  const data = await request<unknown>(baseUrl, '/api/ai/runtime/status', { method: 'GET', signal });
  return parseWireContract(aiRuntimeStatusResponseSchema, data, 'GET /api/ai/runtime/status');
}

/**
 * Activates an AI runtime variant.
 */
export async function activateAiRuntime(
  baseUrl: string,
  variantId: string,
  signal?: AbortSignal
): Promise<AiRuntimeStatusResponse> {
  return request<AiRuntimeStatusResponse>(baseUrl, '/api/ai/runtime/activate', {
    method: 'POST',
    body: { variantId },
    signal,
  });
}

/**
 * Deactivates the current AI runtime.
 */
export async function deactivateAiRuntime(baseUrl: string, signal?: AbortSignal): Promise<AiRuntimeStatusResponse> {
  return request<AiRuntimeStatusResponse>(baseUrl, '/api/ai/runtime/deactivate', {
    method: 'POST',
    body: {},
    signal,
  });
}

/**
 * Previews a document (paged extracted text).
 */
export async function previewDocument(
  baseUrl: string,
  docId: string,
  offsetChars = 0,
  maxChars = 8000,
  signal?: AbortSignal
): Promise<PreviewResponse> {
  if (!docId) {
    throw createApiError("docId required", "NO_DOC_ID");
  }

  // Demo mode: return deterministic content without hitting the backend.
  // This keeps the Inspector preview usable in Playwright screenshots captured with ?demo=true.
  if (String(baseUrl).trim().toLowerCase() === 'demo') {
    const prefixChars = (() => {
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

    const prefix = prefixChars > 0 ? '·'.repeat(prefixChars) + '\n' : '';

    const all = prefix + [
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

    const start = Math.max(0, Number(offsetChars) || 0);
    const end = Math.min(all.length, start + Math.max(0, Number(maxChars) || 0));
    const slice = all.slice(start, end);

    return {
      docId,
      requestedDocId: docId,
      offsetChars: start,
      maxChars,
      content: slice,
      truncated: end < all.length,
      nextOffsetChars: end,
      mime: 'text/plain',
      title: null,
      path: null,
      source: 'demo',
      textProvenance: 'tika',
      vduStatus: 'NOT_NEEDED',
      vduProcessed: true,
      vduPageCount: null,
      vduEnrichment: null,
    };
  }

  const enc = encodeURIComponent(docId);
  const path = `/api/preview?docId=${enc}&offsetChars=${offsetChars}&maxChars=${maxChars}`;
  return request<PreviewResponse>(baseUrl, path, { signal, retries: 1 });
}

/**
 * Restarts the Worker process (for runtime reload or recovery).
 */
export async function restartWorker(baseUrl: string, signal?: AbortSignal): Promise<void> {
  await request(baseUrl, '/api/worker/restart', {
    method: 'POST',
    body: {},
    signal,
    retries: 1,
  });
}
