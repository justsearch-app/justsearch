// SPDX-License-Identifier: Apache-2.0
/**
 * Search domain API - Knowledge search
 */

import { request } from '../http';
import { parseWireContract } from '../schemas';
// Tempdoc 564 Phase 1: the raw wire type AND its runtime validator are now a single
// generated projection of the Java record (record → JSON Schema → {TS, Zod}). The hand
// `KnowledgeSearchResponse` interface and the fail-open `.loose()` post-map validation are
// retired in favour of validating the raw response against the faithful generated schema.
import {
  knowledgeSearchResponseSchema,
  type KnowledgeSearchResponse,
  type Filters,
} from '../generated/schema-types/knowledge-search-response';

// Re-export the generated raw wire type so existing consumers (tests, callers) keep a
// stable import path while the type itself is now the single generated projection.
export type { KnowledgeSearchResponse } from '../generated/schema-types/knowledge-search-response';

// Tempdoc 683: the hand IndexCapabilitiesSchema is deleted; the capabilities slice type
// projects from the generated wire authority instead (projection, not fork).
type IndexCapabilities = NonNullable<KnowledgeSearchResponse['indexCapabilities']>;

// ============================================
// Types
// ============================================

// 380: All fields normalized to camelCase. Lucene field names (snake_case in the
// fields map) are renamed at the mapper boundary in mapKnowledgeSearchResponse.
export interface SearchHit {
  docId: string;
  score: number;
  title?: string | undefined;
  path?: string | undefined;
  meta?: string | undefined;
  mime?: string | undefined;
  mimeBase?: string | undefined;
  fileKind?: string | undefined;
  language?: string | undefined;
  modifiedAt?: string | undefined;
  sizeBytes?: string | undefined;
  contentPreview?: string | undefined;
  collection?: string | undefined;
  highlights?: Record<string, string[]> | undefined;
  matchedFields?: string[] | undefined;
  matchSpans?: MatchSpan[] | undefined;
  excerptRegions?: ExcerptRegion[] | undefined;
  // 362: Metadata fields
  metaSource?: string | undefined;
  metaAuthor?: string | undefined;
  metaCategory?: string | undefined;
  metaPublishedAt?: string | undefined;
  // Tempdoc 549 Phase E2: per-hit provenance retired — per-hit ranking is the trace slice.
}

export interface MatchSpan {
  field: string;
  startChar: number;
  endChar: number;
  term?: string | undefined;
}

export interface ExcerptRegion {
  text: string;
  startChar: number;
  endChar: number;
  approxLine: number;
  matchSpans: MatchSpan[];
}

// The request restrictions and appliedFilters echo share the generated Java filter record.
export type SearchFilters = { [K in keyof Filters]?: Filters[K] | undefined };


type FacetField = { field: string; size?: number | undefined };
export type SearchFacets = { include: boolean; fields: FacetField[]; maxDocsScanned?: number | undefined };

/** Component activation for fine-grained pipeline control (256: Phase A). */
export type PipelineConfig = {
  sparseEnabled?: boolean | undefined;
  denseEnabled?: boolean | undefined;
  spladeEnabled?: boolean | undefined;
  fusionAlgorithm?: 'rrf' | 'cc' | 'none' | undefined;
  lambdamartEnabled?: boolean | undefined;
  crossEncoderEnabled?: boolean | undefined;
  crossEncoderWindow?: number | undefined;
  expansionEnabled?: boolean | undefined;
  freshnessEnabled?: boolean | undefined;
};

export type SearchOptions = {
  limit?: number | undefined;
  mode?: 'text' | 'vector' | 'hybrid' | undefined;
  querySyntax?: 'simple' | 'lucene' | 'search' | undefined;
  sort?: 'relevance' | 'modified_desc' | 'modified_asc' | 'size_desc' | 'size_asc' | 'path_asc' | 'path_desc' | undefined;
  cursor?: string | undefined;
  filters?: SearchFilters | undefined;
  facets?: SearchFacets | undefined;
  projection?: string[] | undefined;
  includeExcerpts?: boolean | undefined;
  pipeline?: PipelineConfig | null | undefined;
};

export type SearchFacetsResponse = Record<string, Record<string, number>>;

/** Variant breakdown for a disambiguated entity canonical form. */
export interface EntityVariantBreakdown {
  canonicalForm: string;
  totalCount: number;
  variants: Record<string, number>;
}

export interface SearchResponse {
  hits: SearchHit[];
  totalHits: number;
  // Tempdoc 597: the TRUE matched-document count (searcher.count over the chunk-excluded query),
  // distinct from totalHits (the bounded fused-candidate-union). The headline reads "Top N of M
  // matches" off this so it can't contradict the facet chips (which count the same population).
  matchCount: number;
  queryTimeMs: number;
  nextCursor?: string | undefined;
  facets?: SearchFacetsResponse | undefined;
  facetsTruncated?: boolean | undefined;
  // Tempdoc 549 U4 (Slice 6): flat correction* fields removed; read introspection.correction.
  entityFacetVariants?: Record<string, EntityVariantBreakdown[]> | undefined;
  indexCapabilities?: IndexCapabilities | null | undefined;
  // Tempdoc 549 Phase E3: pipelineExecution retired — timing/component status on searchTrace.
  // Tempdoc 549 Phase E4: introspection retired — the unified searchTrace is the single source.
}

// The raw wire type `KnowledgeSearchResponse` is now imported from the generated
// `../generated/schema-types/knowledge-search-response` (the single record → schema → TS
// projection); the previous hand-authored interface lived here and is retired.

// ============================================
// Pure Mappers (testable without network)
// ============================================

/** Map raw excerpt regions from the backend to typed ExcerptRegion[]. */
function mapExcerptRegions(raw: unknown): ExcerptRegion[] | undefined {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  const regions: ExcerptRegion[] = [];
  for (const r of raw) {
    if (r == null || typeof r !== 'object') continue;
    const obj = r as any;
    const text = typeof obj.text === 'string' ? obj.text : '';
    const startChar = Number.isFinite(Number(obj.startChar)) ? Math.max(0, Math.floor(Number(obj.startChar))) : 0;
    const endChar = Number.isFinite(Number(obj.endChar)) ? Math.max(startChar, Math.floor(Number(obj.endChar))) : startChar;
    const approxLine = Number.isFinite(Number(obj.approxLine)) ? Math.max(1, Math.floor(Number(obj.approxLine))) : 1;
    const spans: MatchSpan[] = [];
    if (Array.isArray(obj.matchSpans)) {
      for (const s of obj.matchSpans) {
        if (s == null || typeof s !== 'object') continue;
        const field = typeof s.field === 'string' ? s.field.trim() : '';
        const sc = Number.isFinite(Number(s.startChar)) ? Math.max(0, Math.floor(Number(s.startChar))) : NaN;
        const ec = Number.isFinite(Number(s.endChar)) ? Math.max(0, Math.floor(Number(s.endChar))) : NaN;
        if (!Number.isFinite(sc) || !Number.isFinite(ec) || ec <= sc) continue;
        const term = typeof s.term === 'string' ? s.term : undefined;
        spans.push(term != null ? { field, startChar: sc, endChar: ec, term } : { field, startChar: sc, endChar: ec });
      }
    }
    if (!text) continue;
    regions.push({ text, startChar, endChar, approxLine, matchSpans: spans });
  }
  return regions.length > 0 ? regions : undefined;
}

/**
 * Normalize the raw entity-facet-variants map (generated-optional fields) into the
 * UI-ergonomic shape with non-optional fields. The wire always populates these, but the
 * generated schema lacks required markers, so coalesce defensively.
 */
function normalizeEntityFacetVariants(
  raw: KnowledgeSearchResponse['entityFacetVariants']
): Record<string, EntityVariantBreakdown[]> | undefined {
  if (raw == null) return undefined;
  const out: Record<string, EntityVariantBreakdown[]> = {};
  for (const [key, variants] of Object.entries(raw)) {
    out[key] = (variants ?? []).map((v) => ({
      canonicalForm: v.canonicalForm ?? '',
      totalCount: typeof v.totalCount === 'number' ? v.totalCount : 0,
      variants: v.variants ?? {},
    }));
  }
  return out;
}

/**
 * Maps a raw KnowledgeSearchResponse to a UI-friendly SearchResponse.
 * Pure function - no side effects, fully testable.
 */
export function mapKnowledgeSearchResponse(raw: KnowledgeSearchResponse): SearchResponse {
  const hits: SearchHit[] = (raw.results || []).map((res) => {
    const fields = (res?.fields || {}) as Record<string, unknown>;
    // 380: Access typed record fields directly — no `as any` casts needed.
    const matchedFields =
      Array.isArray(res?.matchedFields) ? res.matchedFields.filter((v): v is string => typeof v === 'string') : undefined;
    const matchSpansRaw = Array.isArray(res?.matchSpans) ? res.matchSpans : null;
    const matchSpans =
      matchSpansRaw && matchSpansRaw.length > 0
        ? matchSpansRaw
            .map((v) => {
              if (v == null || typeof v !== 'object') return null;
              const field = typeof v.field === 'string' ? v.field.trim() : '';
              const startChar = Number.isFinite(Number(v.startChar)) ? Number(v.startChar) : NaN;
              const endChar = Number.isFinite(Number(v.endChar)) ? Number(v.endChar) : NaN;
              const term = typeof v.term === 'string' ? v.term : undefined;
              if (!field) return null;
              if (!Number.isFinite(startChar) || !Number.isFinite(endChar)) return null;
              if (endChar <= startChar) return null;
              const base = {
                field,
                startChar: Math.max(0, Math.floor(startChar)),
                endChar: Math.max(0, Math.floor(endChar)),
              };
              return term != null ? ({ ...base, term } satisfies MatchSpan) : (base satisfies MatchSpan);
            })
            .filter((v): v is MatchSpan => v != null)
        : undefined;
    // 380: Lucene field names (snake_case) are renamed to camelCase here.
    // `res.id` is optional on the generated raw type (the wire always sends it, but the
    // schema lacks a required marker); coalesce to keep docId a non-optional string.
    const docId = typeof fields?.doc_id === 'string' ? fields.doc_id : (res.id ?? '');
    const path = typeof fields?.path === 'string' ? fields.path : docId;
    const filename =
      typeof fields?.filename === 'string'
        ? fields.filename
        : (() => {
            const parts = String(path).split(/[/\\]/);
            return parts[parts.length - 1] || String(path);
          })();
    const title = typeof fields?.title === 'string' ? fields.title : filename || docId;
    const mime = typeof fields?.mime === 'string' ? fields.mime : undefined;
    const mimeBase = typeof fields?.mime_base === 'string' ? fields.mime_base : undefined;
    const fileKind = typeof fields?.file_kind === 'string' ? fields.file_kind : undefined;
    const language = typeof fields?.language === 'string' ? fields.language : undefined;
    const modifiedAt = typeof fields?.modified_at === 'string' ? fields.modified_at : undefined;
    const sizeBytes = typeof fields?.size_bytes === 'string' ? fields.size_bytes : undefined;
    const contentPreview = typeof fields?.content_preview === 'string' ? fields.content_preview : undefined;
    const collection = typeof fields?.collection === 'string' ? fields.collection : undefined;
    // 362: Metadata fields
    const metaSource = typeof fields?.meta_source === 'string' ? fields.meta_source : undefined;
    const metaAuthor = typeof fields?.meta_author === 'string' ? fields.meta_author : undefined;
    const metaCategory = typeof fields?.meta_category === 'string' ? fields.meta_category : undefined;
    const metaPublishedAt = typeof fields?.meta_published_at === 'string' ? fields.meta_published_at : undefined;
    return {
      docId,
      score: typeof res?.score === 'number' ? res.score : 0,
      title,
      path,
      mime,
      mimeBase,
      fileKind,
      language,
      modifiedAt,
      sizeBytes,
      contentPreview,
      collection,
      meta: [mime, language].filter(Boolean).join(' . ') || mime,
      matchedFields: matchedFields && matchedFields.length > 0 ? matchedFields : undefined,
      matchSpans: matchSpans && matchSpans.length > 0 ? matchSpans : undefined,
      excerptRegions: mapExcerptRegions(res?.excerptRegions),
      metaSource,
      metaAuthor,
      metaCategory,
      metaPublishedAt,
    };
  });

  return {
    hits,
    totalHits: typeof raw.totalHits === 'number' ? raw.totalHits : hits.length,
    // Tempdoc 597: fall back to totalHits then hit count if an older worker omits matchCount.
    matchCount:
      typeof raw.matchCount === 'number'
        ? raw.matchCount
        : typeof raw.totalHits === 'number'
          ? raw.totalHits
          : hits.length,
    queryTimeMs: typeof raw.tookMs === 'number' ? raw.tookMs : 0,
    nextCursor: typeof raw.nextCursor === 'string' && raw.nextCursor.trim() ? raw.nextCursor : undefined,
    facets: raw.facets ?? undefined,
    facetsTruncated: raw.facetsTruncated === true,
    entityFacetVariants: normalizeEntityFacetVariants(raw.entityFacetVariants),
    indexCapabilities: raw.indexCapabilities ?? undefined,
  };
}

// ============================================
// API Functions
// ============================================

/**
 * Performs a knowledge search.
 */
export async function search(
  baseUrl: string,
  query: string,
  signal?: AbortSignal,
  options: SearchOptions = {}
): Promise<SearchResponse> {
  const body: Record<string, unknown> = { query, limit: options.limit ?? 50 };
  if (options.mode) body.mode = options.mode;
  if (options.querySyntax) body.querySyntax = options.querySyntax;
  if (options.sort) body.sort = options.sort;
  if (options.cursor) body.cursor = options.cursor;
  if (options.filters) body.filters = options.filters;
  if (options.facets) body.facets = options.facets;
  if (options.projection) body.projection = options.projection;
  if (options.includeExcerpts) body.includeExcerpts = true;
  if (options.pipeline) body.pipeline = options.pipeline;

  const rawUnknown = await request<unknown>(baseUrl, '/api/knowledge/search', {
    method: 'POST',
    body,
    signal,
  });

  // Tempdoc 564 Phase 1: validate the RAW wire response against the faithful generated
  // schema at the parse boundary (not the fail-open `.loose()` post-map check). This is
  // the contract gate — a mismatch logs `[WireContract]` loudly.
  const raw = parseWireContract(
    knowledgeSearchResponseSchema,
    rawUnknown,
    'POST /api/knowledge/search'
  );

  return mapKnowledgeSearchResponse(raw);
}


