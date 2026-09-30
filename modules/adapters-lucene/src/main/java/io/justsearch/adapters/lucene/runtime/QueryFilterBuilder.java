/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.indexing.SchemaFields;
import java.util.List;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.IndexOrDocValuesQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.util.BytesRef;

/**
 * Builds Lucene filter queries from {@link RuntimeSearchFilters}.
 *
 * <p>This class is responsible for constructing filter queries that apply structured filters
 * (mime, language, file kind, path prefix, modified date range) and chunk exclusion logic.
 *
 * <p>All methods are pure query-building operations with no state dependencies.
 */
public final class QueryFilterBuilder {

  /** Default boost weight for soft-boost filters (363). Calibrated by weight sweep (w=20). */
  public static final float DEFAULT_BOOST_WEIGHT = 20.0f;

  /**
   * Tempdoc 811 D-1 — the empty filter set. A {@code null} {@link RuntimeSearchFilters} means "the
   * caller supplied no filters", which is the DEFAULT scope — never "no scope at all". Every entry
   * point substitutes this record for {@code null} so the null-filters path runs the exact same
   * code as an explicitly-empty filter set, and the tempdoc-585-D4b default agent-history exclusion
   * applies either way. Before this, both builders returned BEFORE {@link #addCollectionScope},
   * so any null-filters call site searched indexed agent transcripts.
   */
  private static final RuntimeSearchFilters NO_FILTERS =
      LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder().build();

  private QueryFilterBuilder() {
    // Utility class - no instantiation
  }

  /** Shared mutation/proof scope; callers must first certify every accepted survivor identity. */
  static Query excludingAcceptedSurvivors(Query scope, List<String> fileIds,
      List<String> projectionIds) {
    var query = new BooleanQuery.Builder().add(scope, BooleanClause.Occur.FILTER);
    var ids = new java.util.ArrayList<String>(fileIds);
    ids.addAll(projectionIds);
    if (!ids.isEmpty()) {
      query.add(new TermInSetQuery(SchemaFields.DOC_ID,
          ids.stream().map(BytesRef::new).toList()), BooleanClause.Occur.MUST_NOT);
    }
    if (!fileIds.isEmpty()) {
      var chunks = new BooleanQuery.Builder()
          .add(new TermInSetQuery(SchemaFields.PARENT_DOC_ID,
              fileIds.stream().map(BytesRef::new).toList()), BooleanClause.Occur.FILTER)
          .add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.FILTER)
          .add(new PrefixQuery(new Term(SchemaFields.DOC_ID,
              io.justsearch.indexing.chunking.ChunkIds.CHUNK_PREFIX)), BooleanClause.Occur.FILTER);
      query.add(chunks.build(), BooleanClause.Occur.MUST_NOT);
    }
    return query.build();
  }

  /**
   * Normalizes a path prefix for platform-consistent path filtering.
   *
   * <p>Converts forward slashes to platform separators, lowercases on Windows,
   * and ensures trailing separator.
   *
   * @param path the path prefix to normalize (may be null)
   * @return normalized path, or null if input is null
   */
  public static String normalizePathPrefix(String path) {
    if (path == null) return null;
    String normalized = path.replace('/', java.io.File.separatorChar);
    boolean isWindows = io.justsearch.configuration.PlatformPaths.isWindows();
    if (isWindows) {
      normalized = normalized.toLowerCase(java.util.Locale.ROOT);
    }
    if (!normalized.endsWith(java.io.File.separator)) {
      normalized = normalized + java.io.File.separator;
    }
    return normalized;
  }

  /**
   * Adds an OR filter for a list of term values to the query builder.
   *
   * <p>Builds a disjunction (OR) of TermQueries for each non-blank value in the list
   * and adds it as a FILTER clause to the builder.
   *
   * @param qb the query builder to add the filter to
   * @param values the list of values to match (may be null or empty)
   * @param fieldName the Lucene field name to match against
   * @return true if a filter was added, false if values was null/empty or all values were blank
   */
  private static boolean addTermOrFilter(
      BooleanQuery.Builder qb, List<String> values, String fieldName) {
    if (values == null || values.isEmpty()) {
      return false;
    }
    BooleanQuery.Builder or = new BooleanQuery.Builder();
    for (String v : values) {
      if (v == null || v.isBlank()) continue;
      or.add(new TermQuery(new Term(fieldName, v)), BooleanClause.Occur.SHOULD);
    }
    BooleanQuery q = or.build();
    if (q.clauses().isEmpty()) {
      return false;
    }
    qb.add(q, BooleanClause.Occur.FILTER);
    return true;
  }

  /**
   * Tempdoc 585 §D Phase 4 (D4b) — apply the collection scope. An EXPLICIT scope (non-empty
   * {@code collections}) includes ONLY those collections (the "Agent history" scope passes
   * {@code ["agent-history"]}); the DEFAULT (empty) excludes the reserved {@code agent-history}
   * collection with a {@code MUST_NOT}, so indexed run transcripts never pollute normal document
   * search. The {@code MUST_NOT} only matches docs that carry the agent-history tag — untagged docs
   * (no collection field) pass through. Always contributes a clause, so returns true.
   */
  private static boolean addCollectionScope(BooleanQuery.Builder qb, List<String> collections) {
    if (collections != null && !collections.isEmpty()) {
      // An explicit scope is a POSITIVE include filter.
      return addTermOrFilter(qb, collections, SchemaFields.COLLECTION);
    }
    // The default exclusion is a MUST_NOT — NOT a positive filter: like the chunk exclusion it needs
    // a MatchAllDocs anchor when no positive filter is present (a pure-negative query matches none),
    // so it returns false (does not set hasFilters).
    qb.add(
        new TermQuery(new Term(SchemaFields.COLLECTION, SchemaFields.AGENT_HISTORY_COLLECTION)),
        BooleanClause.Occur.MUST_NOT);
    return false;
  }

  /**
   * Adds an IndexOrDocValuesQuery range filter for a long field.
   * Uses LongPoint (BKD-tree) when driving iteration, DocValues for verification.
   */
  private static boolean addLongRangeFilter(
      BooleanQuery.Builder qb, Long fromMs, Long toMs, String field) {
    long from = fromMs == null ? 0L : fromMs;
    long to = toMs == null ? 0L : toMs;
    if (from <= 0 && to <= 0) return false;
    long min = from > 0 ? from : Long.MIN_VALUE;
    long max = to > 0 ? to : Long.MAX_VALUE;
    if (min > max) { long tmp = min; min = max; max = tmp; }
    qb.add(new IndexOrDocValuesQuery(
        LongPoint.newRangeQuery(field, min, max),
        NumericDocValuesField.newSlowRangeQuery(field, min, max)),
        BooleanClause.Occur.FILTER);
    return true;
  }

  /**
   * Applies runtime filters to a content query.
   *
   * <p>Combines the content query with structured filters (mime, language, file kind, etc.)
   * and applies default chunk exclusion unless explicitly requested.
   *
   * @param contentQuery the base content query (must not be null)
   * @param raw optional structured filters (null == the default scope, see {@link #NO_FILTERS})
   * @return combined query with filters applied, or null if contentQuery is null
   */
  public static Query applyRuntimeFilters(Query contentQuery, RuntimeSearchFilters raw) {
    if (contentQuery == null) {
      return null;
    }
    RuntimeSearchFilters filters = raw == null ? NO_FILTERS : raw;

    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    qb.add(contentQuery, BooleanClause.Occur.MUST);

    // Always exclude chunks by default unless explicitly requested.
    // This prevents opaque chunk doc IDs (chunk:<uuid>) from leaking into normal search results.
    boolean includeChunks = filters.includeChunks();
    if (!includeChunks) {
      qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    }

    // Term-based filters: mime, file_kind, mime_base, language
    addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    // Tempdoc 585 §D Phase 4 (D4b) — collection scope: an explicit scope includes only those
    // collections; the default excludes the reserved agent-history collection so transcripts don't
    // pollute normal document search.
    addCollectionScope(qb, filters.collection());

    // Entity filters (NER-extracted values)
    addTermOrFilter(qb, filters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW);
    addTermOrFilter(qb, filters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW);
    addTermOrFilter(qb, filters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW);

    // Metadata filters (frontmatter-extracted, lowercased at index+query time)
    addTermOrFilter(qb, filters.metaSource(), SchemaFields.META_SOURCE);
    addTermOrFilter(qb, filters.metaAuthor(), SchemaFields.META_AUTHOR);
    addTermOrFilter(qb, filters.metaCategory(), SchemaFields.META_CATEGORY);

    // path prefix filter
    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      String normalized = normalizePathPrefix(filters.pathPrefix());
      qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.FILTER);
    }

    // doc_ids exact match filter (scoped search to specific documents)
    addTermOrFilter(qb, filters.docIds(), SchemaFields.PATH);

    // modified_at range filter (IndexOrDocValuesQuery for optimal performance)
    addLongRangeFilter(qb, filters.modifiedFromMs(), filters.modifiedToMs(), SchemaFields.MODIFIED_AT);

    // meta_published_at range filter
    addLongRangeFilter(qb, filters.metaPublishedFromMs(), filters.metaPublishedToMs(),
        SchemaFields.META_PUBLISHED_AT);

    return qb.build();
  }

  /**
   * Builds a filter-only query (no content query) from structured filters.
   *
   * <p>Used by VECTOR and HYBRID search modes to apply user filters and chunk exclusion.
   *
   * @param raw optional structured filters (null == the default scope, see {@link #NO_FILTERS})
   * @return filter query, or null if no filtering is needed
   */
  public static Query buildFilterQueryOnly(RuntimeSearchFilters raw) {
    RuntimeSearchFilters filters = raw == null ? NO_FILTERS : raw;
    boolean includeChunks = filters.includeChunks();

    // Build filter using same logic as applyRuntimeFilters but without content query
    BooleanQuery.Builder qb = new BooleanQuery.Builder();

    // Always exclude chunks unless explicitly requested
    if (!includeChunks) {
      qb.add(new TermQuery(new Term(SchemaFields.IS_CHUNK, "true")), BooleanClause.Occur.MUST_NOT);
    }

    // Term-based filters: mime, file_kind, mime_base, language
    boolean hasFilters = false;
    hasFilters |= addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    hasFilters |= addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    hasFilters |= addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    hasFilters |= addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    // Tempdoc 585 §D Phase 4 (D4b) — collection scope (default-excludes the reserved agent-history).
    hasFilters |= addCollectionScope(qb, filters.collection());

    // Entity filters (NER-extracted values)
    hasFilters |= addTermOrFilter(qb, filters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW);
    hasFilters |=
        addTermOrFilter(qb, filters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW);
    hasFilters |= addTermOrFilter(qb, filters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW);

    // Metadata filters (frontmatter-extracted, lowercased at index+query time)
    hasFilters |= addTermOrFilter(qb, filters.metaSource(), SchemaFields.META_SOURCE);
    hasFilters |= addTermOrFilter(qb, filters.metaAuthor(), SchemaFields.META_AUTHOR);
    hasFilters |= addTermOrFilter(qb, filters.metaCategory(), SchemaFields.META_CATEGORY);

    // path prefix filter
    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      String normalized = normalizePathPrefix(filters.pathPrefix());
      qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.FILTER);
      hasFilters = true;
    }

    // doc_ids exact match filter (scoped search to specific documents)
    hasFilters |= addTermOrFilter(qb, filters.docIds(), SchemaFields.PATH);

    // modified_at range filter (IndexOrDocValuesQuery for optimal performance)
    hasFilters |= addLongRangeFilter(qb, filters.modifiedFromMs(), filters.modifiedToMs(),
        SchemaFields.MODIFIED_AT);

    // meta_published_at range filter
    hasFilters |= addLongRangeFilter(qb, filters.metaPublishedFromMs(),
        filters.metaPublishedToMs(), SchemaFields.META_PUBLISHED_AT);

    // No positive include-filters: qb may hold only MUST_NOT exclusions (the chunk exclusion and/or
    // the tempdoc-585-D4b default agent-history exclusion). A pure-negative BooleanQuery matches
    // nothing, so anchor it with MatchAllDocs. If qb is genuinely empty (no exclusions either —
    // includeChunks AND no collection exclusion), no filtering is needed.
    if (!hasFilters) {
      if (qb.build().clauses().isEmpty()) {
        return null;
      }
      qb.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    }

    return qb.build();
  }

  /**
   * Builds a filter query for chunk search from structured filters.
   *
   * <p>Applies the filters whose fields chunk documents actually carry: mime, fileKind, mimeBase,
   * language, and the two PATH-keyed scopes (pathPrefix, doc_ids). A chunk's {@code PATH} holds its
   * PARENT's absolute path — {@code ChunkDocumentWriter} writes {@code PATH = parentDocId} and
   * {@code IndexingDocumentOps} writes the parent's {@code DOC_ID = PATH = absolutePath} — so the
   * same {@link PrefixQuery}/TermInSet the whole-doc legs use is valid verbatim on chunks. (An
   * earlier revision skipped both on the premise that chunk PATH "stores parentDocId, not the file
   * path"; the two are the same string, and skipping them let the chunk branch retrieve candidates
   * from OUTSIDE the requested scope — inflating the fused candidate union that {@code totalHits}
   * reports and, at high enough fused rank, leaking an out-of-scope document into {@code results}.)
   *
   * <p>Since tempdoc 811 item 3 the collection scope applies here too: {@code ChunkDocumentWriter}
   * now writes the PARENT's {@code collection} onto every chunk document, so the default
   * agent-history exclusion (and an explicit collection scope) bind on the chunk branch exactly as
   * they do on the whole-doc legs. Without it, agent-history CHUNKS entered the fused candidate
   * union whenever no pathPrefix/doc_ids filter was set — the same leak class #371 closed for the
   * two PATH-keyed scopes. ({@code IndexCountOps#queryRootCoverageCounts} relies on the same
   * chunk-PATH-is-parent-path fact for per-root coverage counting — tempdoc 813.)
   *
   * <p>Still skipped, because chunk documents genuinely do not carry these fields: IS_CHUNK
   * exclusion (chunks are the target), modifiedAt / metaPublishedAt ranges, entity filters, and
   * metadata filters.
   *
   * @param raw optional structured filters (null == the default scope, see {@link #NO_FILTERS})
   * @return filter query for chunk search, or null if no applicable filters exist
   */
  public static Query buildChunkFilterQuery(RuntimeSearchFilters raw) {
    RuntimeSearchFilters filters = raw == null ? NO_FILTERS : raw;

    BooleanQuery.Builder qb = new BooleanQuery.Builder();
    boolean hasClause = false;
    hasClause |= addTermOrFilter(qb, filters.mime(), SchemaFields.MIME);
    hasClause |= addTermOrFilter(qb, filters.fileKind(), SchemaFields.FILE_KIND);
    hasClause |= addTermOrFilter(qb, filters.mimeBase(), SchemaFields.MIME_BASE);
    hasClause |= addTermOrFilter(qb, filters.language(), SchemaFields.LANGUAGE);

    // Tempdoc 811 item 3 — the collection scope on the chunk branch. STALE-INDEX DISPOSITION
    // (owner precedent, tempdoc 798, 2026-07-30: no current users, no data repair; dev indices are
    // rebuilt with --clean): chunk documents written BEFORE this change carry no `collection`
    // field, and the default exclusion is a MUST_NOT that only matches docs which DO carry the
    // agent-history tag. Pre-existing agent-history chunks therefore remain un-excluded until the
    // index is rebuilt. This is accepted and deliberately not migrated — a re-index is the fix.
    hasClause |= addCollectionScope(qb, filters.collection());

    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      String normalized = normalizePathPrefix(filters.pathPrefix());
      qb.add(new PrefixQuery(new Term(SchemaFields.PATH, normalized)), BooleanClause.Occur.FILTER);
      hasClause = true;
    }
    hasClause |= addTermOrFilter(qb, filters.docIds(), SchemaFields.PATH);

    // Mirrors buildFilterQueryOnly: with no positive include-filter the builder holds only the
    // default agent-history MUST_NOT, and a pure-negative BooleanQuery matches nothing — anchor it.
    if (!hasClause) {
      if (qb.build().clauses().isEmpty()) {
        return null;
      }
      qb.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
    }
    return qb.build();
  }

  // ---- Soft-boost filters (363: query understanding) ----

  /**
   * Adds an OR-of-terms as a SHOULD boost clause (not a hard filter). Matching documents get a
   * fixed score bump equal to {@code weight}. Non-matching documents are NOT excluded. Uses {@link
   * ConstantScoreQuery} to avoid IDF variation across keyword fields.
   */
  private static boolean addTermOrBoost(
      BooleanQuery.Builder qb, List<String> values, String fieldName, float weight) {
    if (values == null || values.isEmpty()) {
      return false;
    }
    BooleanQuery.Builder or = new BooleanQuery.Builder();
    for (String v : values) {
      if (v == null || v.isBlank()) continue;
      or.add(new TermQuery(new Term(fieldName, v)), BooleanClause.Occur.SHOULD);
    }
    BooleanQuery q = or.build();
    if (q.clauses().isEmpty()) {
      return false;
    }
    qb.add(new BoostQuery(new ConstantScoreQuery(q), weight), BooleanClause.Occur.SHOULD);
    return true;
  }

  /**
   * Applies soft-boost filters to an existing query builder.
   *
   * <p>Unlike {@link #applyRuntimeFilters}, boost filters use {@link BooleanClause.Occur#SHOULD}
   * instead of {@link BooleanClause.Occur#FILTER}. Matching documents score higher but non-matching
   * documents are NOT excluded from results. This preserves recall while improving precision.
   *
   * @param qb the query builder to add boost clauses to (must already have a MUST content query)
   * @param boostFilters the structured boost filter values (may be null)
   * @param weight the boost weight (added to the content score for matching documents)
   */
  public static void applyBoostFilters(
      BooleanQuery.Builder qb, RuntimeSearchFilters boostFilters, float weight) {
    if (boostFilters == null) {
      return;
    }
    addTermOrBoost(qb, boostFilters.entityPersons(), SchemaFields.ENTITY_PERSONS_RAW, weight);
    addTermOrBoost(
        qb, boostFilters.entityOrganizations(), SchemaFields.ENTITY_ORGANIZATIONS_RAW, weight);
    addTermOrBoost(qb, boostFilters.entityLocations(), SchemaFields.ENTITY_LOCATIONS_RAW, weight);
    addTermOrBoost(qb, boostFilters.metaSource(), SchemaFields.META_SOURCE, weight);
    addTermOrBoost(qb, boostFilters.metaAuthor(), SchemaFields.META_AUTHOR, weight);
    addTermOrBoost(qb, boostFilters.metaCategory(), SchemaFields.META_CATEGORY, weight);

    // Date range boost (363): documents within the extracted date range get a score bump.
    addLongRangeBoost(
        qb,
        boostFilters.metaPublishedFromMs(),
        boostFilters.metaPublishedToMs(),
        SchemaFields.META_PUBLISHED_AT,
        weight);
  }

  /**
   * Adds a date range as a SHOULD boost clause. Documents within the range get a fixed score bump.
   * Uses the same IndexOrDocValuesQuery pattern as {@link #addLongRangeFilter} but wrapped in
   * BoostQuery + ConstantScoreQuery to avoid score variation.
   */
  private static boolean addLongRangeBoost(
      BooleanQuery.Builder qb, Long fromMs, Long toMs, String field, float weight) {
    long from = fromMs == null ? 0L : fromMs;
    long to = toMs == null ? 0L : toMs;
    if (from <= 0 && to <= 0) return false;
    long min = from > 0 ? from : Long.MIN_VALUE;
    long max = to > 0 ? to : Long.MAX_VALUE;
    if (min > max) {
      long tmp = min;
      min = max;
      max = tmp;
    }
    Query rangeQuery =
        new IndexOrDocValuesQuery(
            LongPoint.newRangeQuery(field, min, max),
            NumericDocValuesField.newSlowRangeQuery(field, min, max));
    qb.add(new BoostQuery(new ConstantScoreQuery(rangeQuery), weight), BooleanClause.Occur.SHOULD);
    return true;
  }
}
