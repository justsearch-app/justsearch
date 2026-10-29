/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.ipc.FacetFieldSpec;
import io.justsearch.ipc.FacetSpec;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchRequest;
import io.justsearch.ipc.TimeRangeMs;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 556 (F-C4.2): pure marshalling of app-api {@link KnowledgeSearchRequest} sub-records into
 * proto {@link SearchRequest} pieces. Extracted verbatim from {@code KnowledgeHttpApiAdapter.doSearch}
 * so the adapter stays a thin orchestrator. Behaviour-preserving: each method mutates the supplied
 * builder identically to the inline code it replaced.
 */
final class SearchRequestMapper {

  private static final Logger log = LoggerFactory.getLogger(SearchRequestMapper.class);

  private SearchRequestMapper() {}

  /**
   * Marshals the hard {@code filters} into proto and sets them on {@code b}. Caller guarantees
   * {@code filters != null}.
   */
  static void applyFilters(SearchRequest.Builder b, KnowledgeSearchRequest.Filters filters) {
    boolean strict = b.getQuerySyntax() == io.justsearch.ipc.SearchQuerySyntax.SEARCH_QUERY_SYNTAX_SEARCH;
    SearchFilters.Builder fb = SearchFilters.newBuilder();
    for (String v : filters.mime()) {
      if (v != null && !v.isBlank()) fb.addMime(v);
    }
    for (String v : filters.language()) {
      if (v != null && !v.isBlank()) fb.addLanguage(v);
    }
    for (String v : filters.fileKind()) {
      if (v != null && !v.isBlank()) fb.addFileKind(v);
    }
    for (String v : filters.mimeBase()) {
      if (v != null && !v.isBlank()) fb.addMimeBase(v);
    }
    // Tempdoc 585 §D Phase 4 (D4b) — collection scope tag(s).
    for (String v : filters.collection()) {
      if (v != null && !v.isBlank()) fb.addCollection(v);
    }
    if (filters.pathPrefix() != null && !filters.pathPrefix().isBlank()) {
      fb.setPathPrefix(filters.pathPrefix());
    }
    if (filters.includeChunks() != null) {
      fb.setIncludeChunks(filters.includeChunks());
    }
    KnowledgeSearchRequest.TimeRangeMs modifiedAt = filters.modifiedAt();
    if (modifiedAt != null) {
      long fromMs = modifiedAt.fromMs() == null ? 0L : modifiedAt.fromMs();
      long toMs = modifiedAt.toMs() == null ? 0L : modifiedAt.toMs();
      if (fromMs > 0 || toMs > 0) {
        fb.setModifiedAt(TimeRangeMs.newBuilder().setFromMs(fromMs).setToMs(toMs).build());
      }
    }
    if (filters.sizeBytes() != null) {
      var size = io.justsearch.ipc.ByteRange.newBuilder();
      if (filters.sizeBytes().fromBytes() != null) size.setFromBytes(filters.sizeBytes().fromBytes());
      if (filters.sizeBytes().toBytes() != null) size.setToBytes(filters.sizeBytes().toBytes());
      fb.setSizeBytes(size);
    }
    // Entity filters
    for (String v : filters.entityPersons()) {
      if (v != null && !v.isBlank()) fb.addEntityPersons(v);
    }
    for (String v : filters.entityOrganizations()) {
      if (v != null && !v.isBlank()) fb.addEntityOrganizations(v);
    }
    for (String v : filters.entityLocations()) {
      if (v != null && !v.isBlank()) fb.addEntityLocations(v);
    }
    // 366: lowercase to match index-time normalization in IndexingDocumentOps
    for (String v : filters.metaSource()) {
      if (v != null && !v.isBlank()) fb.addMetaSource(strict ? v : v.toLowerCase(Locale.ROOT));
    }
    for (String v : filters.metaAuthor()) {
      if (v != null && !v.isBlank()) fb.addMetaAuthor(strict ? v : v.toLowerCase(Locale.ROOT));
    }
    for (String v : filters.metaCategory()) {
      if (v != null && !v.isBlank()) fb.addMetaCategory(strict ? v : v.toLowerCase(Locale.ROOT));
    }
    KnowledgeSearchRequest.TimeRangeMs metaPub = filters.metaPublishedAt();
    if (metaPub != null) {
      long mpFrom = metaPub.fromMs() == null ? 0L : metaPub.fromMs();
      long mpTo = metaPub.toMs() == null ? 0L : metaPub.toMs();
      if (mpFrom > 0 || mpTo > 0) {
        fb.setMetaPublishedAt(TimeRangeMs.newBuilder().setFromMs(mpFrom).setToMs(mpTo).build());
      }
    }
    // 366 Phase 6: PATH field is lowercased on Windows at index time — match that here.
    boolean isWindows = io.justsearch.configuration.PlatformPaths.isWindows();
    for (String v : filters.docIds()) {
      if (v != null && !v.isBlank()) {
        String normalized = strict ? v : v.replace('/', java.io.File.separatorChar);
        if (!strict && isWindows) normalized = normalized.toLowerCase(Locale.ROOT);
        fb.addDocIds(normalized);
      }
    }
    b.setFilters(fb.build());
  }

  /**
   * Marshals the soft {@code boost} filters into proto and sets them on {@code b}. Also injects the
   * deterministic {@code temporalExtraction} range when QU did not set one (#9). Caller guarantees
   * {@code boost != null}.
   */
  static void applyBoostFilters(
      SearchRequest.Builder b,
      KnowledgeSearchRequest.Filters boost,
      TemporalQueryExtractor.TemporalExtraction temporalExtraction) {
    SearchFilters.Builder bb = SearchFilters.newBuilder();
    for (String v : boost.entityPersons()) {
      if (v != null && !v.isBlank()) bb.addEntityPersons(v);
    }
    for (String v : boost.entityOrganizations()) {
      if (v != null && !v.isBlank()) bb.addEntityOrganizations(v);
    }
    for (String v : boost.entityLocations()) {
      if (v != null && !v.isBlank()) bb.addEntityLocations(v);
    }
    // 366: lowercase to match index-time normalization
    for (String v : boost.metaSource()) {
      if (v != null && !v.isBlank()) bb.addMetaSource(v.toLowerCase(Locale.ROOT));
    }
    for (String v : boost.metaAuthor()) {
      if (v != null && !v.isBlank()) bb.addMetaAuthor(v.toLowerCase(Locale.ROOT));
    }
    for (String v : boost.metaCategory()) {
      if (v != null && !v.isBlank()) bb.addMetaCategory(v.toLowerCase(Locale.ROOT));
    }
    KnowledgeSearchRequest.TimeRangeMs boostPub = boost.metaPublishedAt();
    if (boostPub != null) {
      long bpFrom = boostPub.fromMs() == null ? 0L : boostPub.fromMs();
      long bpTo = boostPub.toMs() == null ? 0L : boostPub.toMs();
      if (bpFrom > 0 || bpTo > 0) {
        bb.setMetaPublishedAt(TimeRangeMs.newBuilder().setFromMs(bpFrom).setToMs(bpTo).build());
      }
    }
    // 385: Inject extracted temporal range if QU did not set one (#9).
    if (temporalExtraction.hasDates() && boostPub != null
        && (boostPub.fromMs() != null || boostPub.toMs() != null)) {
      log.debug("385: QU set temporal range, skipping deterministic extraction (QU takes precedence)");
    }
    if (temporalExtraction.hasDates()
        && (boostPub == null || (boostPub.fromMs() == null && boostPub.toMs() == null))) {
      KnowledgeSearchRequest.TimeRangeMs tr = temporalExtraction.suggestedRange();
      if (tr != null) {
        long tFrom = tr.fromMs() == null ? 0L : tr.fromMs();
        long tTo = tr.toMs() == null ? 0L : tr.toMs();
        if (tFrom > 0 || tTo > 0) {
          bb.setMetaPublishedAt(
              TimeRangeMs.newBuilder().setFromMs(tFrom).setToMs(tTo).build());
        }
      }
    }
    b.setBoostFilters(bb.build());
  }

  /**
   * 385: When no explicit/QU boost was set and the query has dates, set a temporal-only boost
   * derived from the extracted range. No-op when no dates.
   */
  static void applyTemporalOnlyBoost(
      SearchRequest.Builder b, TemporalQueryExtractor.TemporalExtraction temporalExtraction) {
    if (!temporalExtraction.hasDates()) return;
    KnowledgeSearchRequest.TimeRangeMs tr = temporalExtraction.suggestedRange();
    if (tr == null) return;
    long tFrom = tr.fromMs() == null ? 0L : tr.fromMs();
    long tTo = tr.toMs() == null ? 0L : tr.toMs();
    if (tFrom > 0 || tTo > 0) {
      b.setBoostFilters(
          SearchFilters.newBuilder()
              .setMetaPublishedAt(
                  TimeRangeMs.newBuilder().setFromMs(tFrom).setToMs(tTo).build())
              .build());
    }
  }

  /** Marshals facet spec into proto. Caller guarantees {@code facets != null}. */
  static void applyFacets(SearchRequest.Builder b, KnowledgeSearchRequest.Facets facets) {
    FacetSpec.Builder facetSpec = FacetSpec.newBuilder();
    if (facets.include() != null) {
      facetSpec.setInclude(facets.include());
    }
    if (facets.maxDocsScanned() != null && facets.maxDocsScanned() > 0) {
      facetSpec.setMaxDocsScanned(facets.maxDocsScanned());
    }
    for (KnowledgeSearchRequest.FieldSpec fs : facets.fields()) {
      if (fs == null) continue;
      String field = fs.field();
      if (field == null || field.isBlank()) continue;
      int size = fs.size() == null ? 10 : Math.max(1, fs.size());
      facetSpec.addFields(FacetFieldSpec.newBuilder().setField(field).setSize(size).build());
    }
    b.setFacets(facetSpec.build());
  }
}
