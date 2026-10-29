/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * Search request for the Knowledge Server-backed HTTP API (e.g., POST /api/knowledge/search).
 *
 * <p>Stability: stable (API contract)
 */
public record KnowledgeSearchRequest(
    String query,
    Integer limit,
    String mode,
    String sort,
    String cursor,
    List<String> projection,
    Filters filters,
    Filters boostFilters,
    Facets facets,
    String querySyntax,
    Boolean includeExcerpts,
    Boolean debug,
    PipelineConfig pipeline) {

  public KnowledgeSearchRequest {
    projection = projection == null ? List.of() : List.copyOf(projection);
    querySyntax = querySyntax == null || querySyntax.isBlank() ? null : querySyntax.trim();
  }

  /**
   * Filter set. Also serialized on the way OUT, as the {@code appliedFilters} echo on {@link
   * KnowledgeSearchResponse} (366 §1b) — hence {@code NON_EMPTY}, so the echo shows the filters that
   * were sent rather than fifteen empty arrays. Serialization-only; request parsing is manual
   * (KnowledgeSearchController reads the body map) and unaffected.
   */
  @RecordBuilder
  @JsonInclude(JsonInclude.Include.NON_EMPTY)
  public static record Filters(
      List<String> mime,
      List<String> language,
      List<String> fileKind,
      List<String> mimeBase,
      String pathPrefix,
      Boolean includeChunks,
      TimeRangeMs modifiedAt,
      List<String> entityPersons,
      List<String> entityOrganizations,
      List<String> entityLocations,
      List<String> metaSource,
      List<String> metaAuthor,
      List<String> metaCategory,
      TimeRangeMs metaPublishedAt,
      List<String> docIds,
      // Tempdoc 585 §D Phase 4 (D4b) — scope search to Lucene collection tag(s) (e.g. "agent-history").
      List<String> collection,
      ByteRange sizeBytes) {
    public Filters(
        List<String> mime, List<String> language, List<String> fileKind, List<String> mimeBase,
        String pathPrefix, Boolean includeChunks, TimeRangeMs modifiedAt,
        List<String> entityPersons, List<String> entityOrganizations, List<String> entityLocations,
        List<String> metaSource, List<String> metaAuthor, List<String> metaCategory,
        TimeRangeMs metaPublishedAt, List<String> docIds, List<String> collection) {
      this(mime, language, fileKind, mimeBase, pathPrefix, includeChunks, modifiedAt,
          entityPersons, entityOrganizations, entityLocations, metaSource, metaAuthor, metaCategory,
          metaPublishedAt, docIds, collection, null);
    }

    public Filters {
      mime = mime == null ? List.of() : List.copyOf(mime);
      language = language == null ? List.of() : List.copyOf(language);
      fileKind = fileKind == null ? List.of() : List.copyOf(fileKind);
      mimeBase = mimeBase == null ? List.of() : List.copyOf(mimeBase);
      entityPersons = entityPersons == null ? List.of() : List.copyOf(entityPersons);
      entityOrganizations = entityOrganizations == null ? List.of() : List.copyOf(entityOrganizations);
      entityLocations = entityLocations == null ? List.of() : List.copyOf(entityLocations);
      metaSource = metaSource == null ? List.of() : List.copyOf(metaSource);
      metaAuthor = metaAuthor == null ? List.of() : List.copyOf(metaAuthor);
      metaCategory = metaCategory == null ? List.of() : List.copyOf(metaCategory);
      docIds = docIds == null ? List.of() : List.copyOf(docIds);
      collection = collection == null ? List.of() : List.copyOf(collection);
    }
  }

  public static record TimeRangeMs(Long fromMs, Long toMs) {}

  /** Inclusive source-file byte bounds. Null is unset; zero is a real bound. */
  public static record ByteRange(Long fromBytes, Long toBytes) {}

  public static record Facets(Boolean include, Integer maxDocsScanned, List<FieldSpec> fields) {
    public Facets {
      fields = fields == null ? List.of() : List.copyOf(fields);
    }
  }

  public static record FieldSpec(String field, Integer size) {}
}
