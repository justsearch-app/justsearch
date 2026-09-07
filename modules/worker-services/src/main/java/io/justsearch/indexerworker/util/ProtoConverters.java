/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder;
import io.justsearch.ipc.SearchFilters;
import io.justsearch.ipc.SearchSort;
import java.util.List;

/**
 * Utility methods for converting gRPC proto messages to runtime types.
 *
 * <p>Extracted from WorkerSearchService for reusability and testability.
 */
public final class ProtoConverters {

  private ProtoConverters() {
    // Utility class - no instantiation
  }

  /**
   * Converts a gRPC SearchSort to RuntimeSearchSort.
   *
   * @param sort the gRPC sort (may be null)
   * @return RuntimeSearchSort, defaults to RELEVANCE if null
   */
  public static RuntimeSearchSort toRuntimeSort(SearchSort sort) {
    if (sort == null) {
      return RuntimeSearchSort.RELEVANCE;
    }
    return switch (sort) {
      case SEARCH_SORT_MODIFIED_DESC -> RuntimeSearchSort.MODIFIED_DESC;
      case SEARCH_SORT_MODIFIED_ASC -> RuntimeSearchSort.MODIFIED_ASC;
      case SEARCH_SORT_SIZE_DESC -> RuntimeSearchSort.SIZE_DESC;
      case SEARCH_SORT_SIZE_ASC -> RuntimeSearchSort.SIZE_ASC;
      case SEARCH_SORT_PATH_ASC -> RuntimeSearchSort.PATH_ASC;
      case SEARCH_SORT_PATH_DESC -> RuntimeSearchSort.PATH_DESC;
      default -> RuntimeSearchSort.RELEVANCE;
    };
  }

  /**
   * Converts a gRPC SearchFilters to RuntimeSearchFilters.
   *
   * @param filtersMsg the gRPC filters (may be null)
   * @return RuntimeSearchFilters, or null if input is null
   */
  public static RuntimeSearchFilters toRuntimeFilters(SearchFilters filtersMsg) {
    if (filtersMsg == null) {
      return null;
    }

    List<String> mime = filtersMsg.getMimeList().isEmpty() ? null : filtersMsg.getMimeList();
    List<String> language =
        filtersMsg.getLanguageList().isEmpty() ? null : filtersMsg.getLanguageList();
    List<String> fileKind =
        filtersMsg.getFileKindList().isEmpty() ? null : filtersMsg.getFileKindList();
    List<String> mimeBase =
        filtersMsg.getMimeBaseList().isEmpty() ? null : filtersMsg.getMimeBaseList();
    String pathPrefix = filtersMsg.getPathPrefix();
    Long from =
        filtersMsg.hasModifiedAt() && filtersMsg.getModifiedAt().getFromMs() > 0
            ? filtersMsg.getModifiedAt().getFromMs()
            : null;
    Long to =
        filtersMsg.hasModifiedAt() && filtersMsg.getModifiedAt().getToMs() > 0
            ? filtersMsg.getModifiedAt().getToMs()
            : null;
    boolean includeChunks = filtersMsg.getIncludeChunks();
    List<String> entityPersons =
        filtersMsg.getEntityPersonsList().isEmpty()
            ? null
            : filtersMsg.getEntityPersonsList();
    List<String> entityOrganizations =
        filtersMsg.getEntityOrganizationsList().isEmpty()
            ? null
            : filtersMsg.getEntityOrganizationsList();
    List<String> entityLocations =
        filtersMsg.getEntityLocationsList().isEmpty()
            ? null
            : filtersMsg.getEntityLocationsList();

    // Metadata filters (lowercased for case-insensitive matching)
    List<String> metaSource = lowerList(filtersMsg.getMetaSourceList());
    List<String> metaAuthor = lowerList(filtersMsg.getMetaAuthorList());
    List<String> metaCategory = lowerList(filtersMsg.getMetaCategoryList());
    Long metaPubFrom =
        filtersMsg.hasMetaPublishedAt() && filtersMsg.getMetaPublishedAt().getFromMs() > 0
            ? filtersMsg.getMetaPublishedAt().getFromMs()
            : null;
    Long metaPubTo =
        filtersMsg.hasMetaPublishedAt() && filtersMsg.getMetaPublishedAt().getToMs() > 0
            ? filtersMsg.getMetaPublishedAt().getToMs()
            : null;

    List<String> docIds =
        filtersMsg.getDocIdsList().isEmpty() ? null : filtersMsg.getDocIdsList();

    // Tempdoc 585 §D Phase 4 (D4b) — collection scope tag(s).
    List<String> collection =
        filtersMsg.getCollectionList().isEmpty() ? null : filtersMsg.getCollectionList();

    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .mime(mime)
        .language(language)
        .fileKind(fileKind)
        .mimeBase(mimeBase)
        .pathPrefix(pathPrefix)
        .modifiedFromMs(from)
        .modifiedToMs(to)
        .includeChunks(includeChunks)
        .entityPersons(entityPersons)
        .entityOrganizations(entityOrganizations)
        .entityLocations(entityLocations)
        .metaSource(metaSource)
        .metaAuthor(metaAuthor)
        .metaCategory(metaCategory)
        .metaPublishedFromMs(metaPubFrom)
        .metaPublishedToMs(metaPubTo)
        .docIds(docIds)
        .collection(collection)
        .build();
  }

  /** Lowercases all non-blank strings in the list; returns null if empty. */
  private static List<String> lowerList(List<String> values) {
    if (values == null || values.isEmpty()) return null;
    List<String> result = new java.util.ArrayList<>(values.size());
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        result.add(v.toLowerCase(java.util.Locale.ROOT));
      }
    }
    return result.isEmpty() ? null : result;
  }
}
