/**
 * Zod input/output schemas for the JustSearch production MCP server tools.
 * Adapted from scripts/dev/justsearch-dev-mcp/schemas.mjs with dev-runner
 * specifics (runId, timeoutMs, maxBytes) removed.
 */

import * as z from 'zod/v4';

// --- Shared ---

export const ToolErrorSchema = z
  .object({
    code: z.string().optional(),
    message: z.string(),
    stack: z.string().optional(),
  })
  .passthrough();

// --- Typed result schemas (MCP spec 2025-06-18: clients SHOULD validate outputSchema) ---

export const SearchHitSchema = z
  .object({
    score: z.number(),
    filename: z.string().optional(),
    path: z.string().optional(),
    file_kind: z.string().optional(),
    size_bytes: z.number(),
    language: z.string().optional(),
    content_preview: z.string(),
    meta_source: z.string().optional(),
    meta_author: z.string().optional(),
    meta_category: z.string().optional(),
    matchedFields: z.array(z.string()).optional(),
    provenance: z.any().optional(),
    excerpts: z.array(z.object({
      text: z.string(),
      approx_line: z.number().optional(),
    })).optional(),
  })
  .passthrough();

export const ChunkSchema = z
  .object({
    parent_doc_id: z.string().optional(),
    chunk_index: z.number().optional(),
    score: z.number().optional(),
    text: z.string().optional(),
  })
  .passthrough();

export const QualitySchema = z
  .object({
    coverage: z.number().optional(),
    best_score: z.number().optional(),
    retrieval_mode: z.string().optional(),
    chunks_considered: z.number().optional(),
    chunks_included: z.number().optional(),
    truncated: z.boolean().optional(),
  })
  .passthrough();

export const FacetsSchema = z.record(z.string(), z.record(z.string(), z.number())).optional();

// --- Search ---

export const SearchInputSchema = z
  .object({
    query: z.string().min(1).describe('Search query text'),
    limit: z.number().int().positive().max(50).default(10).describe('Max results to return'),
    mode: z
      .enum(['hybrid', 'text', 'vector'])
      .default('hybrid')
      .describe('Search mode: hybrid (BM25+vector), text (BM25 only), or vector (semantic only)'),
    querySyntax: z
      .enum(['SIMPLE', 'LUCENE'])
      .default('SIMPLE')
      .describe('Query syntax: SIMPLE for natural language, LUCENE for advanced syntax'),
    filters: z
      .object({
        path_prefix: z.string().optional().describe('Filter to files under this path'),
        mime: z.array(z.string()).optional().describe("Filter by MIME type (e.g. ['application/pdf'])"),
        file_kind: z
          .array(z.string())
          .optional()
          .describe("Filter by file kind: 'pdf', 'markdown', 'code', 'text', 'office', 'image', 'archive', 'binary', 'unknown'"),
        language: z.array(z.string()).optional().describe('Filter by detected language code'),
        entity_persons: z.array(z.string()).optional().describe('Filter to documents mentioning these people'),
        entity_organizations: z.array(z.string()).optional().describe('Filter to documents mentioning these organizations'),
        entity_locations: z.array(z.string()).optional().describe('Filter to documents mentioning these locations'),
        meta_source: z.array(z.string()).optional().describe("Filter by source publication (e.g. ['the verge', 'techcrunch']). Values are case-insensitive."),
        meta_author: z.array(z.string()).optional().describe("Filter by author name (e.g. ['stan choe']). Values are case-insensitive."),
        meta_category: z.array(z.string()).optional().describe("Filter by category (e.g. ['technology', 'business', 'sports']). Values are case-insensitive."),
        meta_published_after: z.string().optional().describe('Only include documents published after this date (ISO 8601, e.g. 2023-10-01)'),
        meta_published_before: z.string().optional().describe('Only include documents published before this date (ISO 8601, e.g. 2023-10-31)'),
      })
      .passthrough()
      .optional()
      .describe('Structured filters to narrow results (hard filter — excludes non-matching documents). IMPORTANT: When the query mentions a specific source, author, or category, use these filters instead of putting the name in the query text. Example: {meta_source: ["cbssports.com"], meta_category: ["sports"]}. Metadata filters match document frontmatter fields and are case-insensitive. Advanced: you can also pass doc_ids (array of paths) to scope search to specific documents.'),
    boostFilters: z
      .object({
        entity_persons: z.array(z.string()).optional().describe('Boost documents mentioning these people'),
        entity_organizations: z.array(z.string()).optional().describe('Boost documents mentioning these organizations'),
        entity_locations: z.array(z.string()).optional().describe('Boost documents mentioning these locations'),
        meta_source: z.array(z.string()).optional().describe("Boost documents from these sources (e.g. ['the verge'])"),
        meta_author: z.array(z.string()).optional().describe("Boost documents by these authors (e.g. ['stan choe'])"),
        meta_category: z.array(z.string()).optional().describe("Boost documents in these categories (e.g. ['technology'])"),
      })
      .strict()
      .optional()
      .describe('Soft-boost filters — promote matching documents higher in results WITHOUT excluding non-matching ones. Use this instead of filters when you want to prefer certain sources/authors/categories without risking zero results. Example: {meta_source: ["the verge"]} ranks Verge articles higher but still returns other sources.'),
    cursor: z
      .string()
      .optional()
      .describe('Opaque pagination cursor from a previous search response (nextCursor field)'),
    verbose: z
      .boolean()
      .default(false)
      .describe('If true, return full content_preview instead of 200-char truncation'),
    includeProvenance: z
      .boolean()
      .default(false)
      .describe('If true, include per-hit provenance (ranking breakdown by retrieval leg, fusion, chunk merge, branch fusion)'),
    includeExcerpts: z
      .boolean()
      .default(false)
      .describe('If true, include query-biased passage excerpts per result (top-3 most relevant passages with sentence-boundary snapping). Much more useful than content_preview for understanding why a document matched.'),
    facets: z
      .union([
        z.literal(true),
        z.object({
          fields: z.array(z.string()).describe('Field names to facet on (e.g. ["meta_source", "meta_category", "language"])'),
          size: z.number().int().positive().max(50).default(10).describe('Max values per facet field'),
        }).strict(),
      ])
      .optional()
      .describe('Request facet breakdowns. Pass true for default facets (meta_source, meta_category, meta_author top-5), or {fields: [...], size: N} for specific fields. Facets show available filter values with document counts.'),
  })
  .strict();

export const SearchOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      totalHits: z.number(),
      tookMs: z.number(),
      results: z.array(SearchHitSchema),
      nextCursor: z.string().optional(),
      facets: FacetsSchema,
      correctionApplied: z.boolean().optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Suggest ---

export const SuggestInputSchema = z
  .object({
    query: z.string().min(1).describe('Autocomplete query prefix'),
    limit: z.number().int().positive().max(20).default(10).describe('Max suggestions to return'),
  })
  .strict();

export const SuggestOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      suggestions: z.array(z.string()),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Ingest ---

export const IngestInputSchema = z
  .object({
    paths: z
      .array(z.string().min(1))
      .min(1)
      .describe('Absolute paths to files or directories to index'),
    collection: z.string().nullable().optional(),
    idempotencyKey: z.string().optional(),
    confirmationToken: z.string().optional(),
    preparationNonce: z.string().optional(),
  })
  .strict();

export const IngestOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      statusCode: z.number().int(),
      operationResponse: z.unknown(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      statusCode: z.number().int().nullable(),
      operationResponse: z.unknown().optional(),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Operation outcome ---

export const OperationOutcomeInputSchema = z
  .object({
    operationKey: z.string()
      .regex(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
      .describe('The original canonical UUIDv7 operation key, never an executionId'),
  })
  .strict();

// --- Preview ---

export const PreviewInputSchema = z
  .object({
    docId: z.string().min(1).describe('Document ID from a search result (the path field)'),
    maxChars: z
      .number()
      .int()
      .positive()
      .max(200_000)
      .default(20_000)
      .describe('Maximum characters to return (default 20000, max 200000)'),
    offsetChars: z
      .number()
      .int()
      .min(0)
      .default(0)
      .describe('Character offset to start from (for pagination through long documents)'),
  })
  .strict();

export const PreviewOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      content: z.string(),
      path: z.string(),
      title: z.string().optional(),
      mime: z.string().optional(),
      truncated: z.boolean(),
      nextOffsetChars: z.number().optional(),
    })
    .strict(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Answer (RAG) ---

export const AnswerInputSchema = z
  .object({
    query: z.string().min(1).describe('The question to answer using indexed documents'),
    top_k: z
      .number()
      .int()
      .positive()
      .max(20)
      .default(5)
      .describe('Number of chunks to retrieve (default 5, max 20)'),
    max_tokens: z
      .number()
      .int()
      .positive()
      .max(16384)
      .default(4096)
      .describe('Token budget for assembled context (default 4096)'),
    filters: z
      .object({
        path_prefix: z.string().optional().describe('Filter to files under this path'),
        file_kind: z
          .array(z.string())
          .optional()
          .describe("Filter by file kind: 'pdf', 'markdown', 'code', 'text', 'office'"),
        entity_persons: z
          .array(z.string())
          .optional()
          .describe('Filter to documents mentioning these people'),
        entity_organizations: z
          .array(z.string())
          .optional()
          .describe('Filter to documents mentioning these organizations'),
        entity_locations: z
          .array(z.string())
          .optional()
          .describe('Filter to documents mentioning these locations'),
        modified_after: z
          .string()
          .optional()
          .describe('Only include documents modified after this date (ISO 8601)'),
        modified_before: z
          .string()
          .optional()
          .describe('Only include documents modified before this date (ISO 8601)'),
        meta_source: z.array(z.string()).optional().describe("Filter by source publication (e.g. ['the verge']). Case-insensitive."),
        meta_author: z.array(z.string()).optional().describe("Filter by author name. Case-insensitive."),
        meta_category: z.array(z.string()).optional().describe("Filter by category (e.g. ['technology']). Case-insensitive."),
        meta_published_after: z.string().optional().describe('Only include documents published after this date (ISO 8601)'),
        meta_published_before: z.string().optional().describe('Only include documents published before this date (ISO 8601)'),
      })
      .strict()
      .optional()
      .describe('Structured filters to narrow retrieval scope. IMPORTANT: When the query mentions a specific source, author, or category, use these filters. Example: {meta_source: ["the verge"]}. Case-insensitive.'),
    doc_ids: z
      .array(z.string())
      .optional()
      .describe('Restrict retrieval to these specific document IDs'),
    auto_entity_extract: z
      .boolean()
      .default(true)
      .describe('Automatically extract entities from the question and use as filters (default true)'),
    context_format: z
      .enum(['labeled', 'xml', 'plain'])
      .default('xml')
      .describe('Context format: xml (default, best for agents), labeled (human-readable), plain (no source labels)'),
    verify_citations: z
      .object({
        answer_text: z.string().min(1).describe('Your generated answer to verify against source passages'),
        threshold: z.number().min(0).max(1).default(0.5).describe('Minimum similarity for a citation match (default 0.5)'),
      })
      .strict()
      .optional()
      .describe('After answering, pass your answer here to verify which sentences are supported by the retrieved passages. Returns per-sentence match scores.'),
  })
  .passthrough();

export const AnswerOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      context: z.string(),
      total_found: z.number(),
      chunks: z.array(ChunkSchema),
      quality: QualitySchema,
      citations: z.object({
        matches: z.array(z.any()),
        sentences_total: z.number(),
        sentences_matched: z.number(),
      }).optional(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

export const MatchCitationsInputSchema = z
  .object({
    answer_text: z
      .string()
      .min(1)
      .describe('The generated answer text to verify against source passages'),
    chunk_refs: z
      .array(
        z.object({
          parent_doc_id: z.string().describe('Document ID from retrieve_context chunk'),
          chunk_index: z.number().int().describe('Chunk index from retrieve_context chunk'),
        }),
      )
      .min(1)
      .describe('Chunk references from a previous retrieve_context call'),
    threshold: z
      .number()
      .min(0)
      .max(1)
      .default(0.5)
      .describe('Minimum similarity score for a citation match (default 0.5)'),
  })
  .strict();

export const MatchCitationsOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      matches: z.array(z.any()),
      sentences_total: z.number(),
      sentences_matched: z.number(),
    })
    .passthrough(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);

// --- Status ---

export const StatusInputSchema = z.object({}).strict();

export const StatusOutputSchema = z.union([
  z
    .object({
      ok: z.literal(true),
      state: z.string().optional(),
      ready: z.boolean().optional(),
      docCount: z.number().optional(),
      queueDepth: z.number().optional(),
      healthy: z.boolean().optional(),
      indexState: z.string().optional(),
      embeddingCoveragePercent: z.number().optional(),
      spladeCoveragePercent: z.number().optional(),
      pendingNerCount: z.number().optional(),
      completedNerCount: z.number().optional(),
    })
    .strict(),
  z
    .object({
      ok: z.literal(false),
      error: ToolErrorSchema,
    })
    .passthrough(),
]);
