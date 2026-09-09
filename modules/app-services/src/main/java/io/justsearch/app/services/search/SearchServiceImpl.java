/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.search;

import io.justsearch.app.api.SearchRequest;
import io.justsearch.app.api.SearchResponse;
import io.justsearch.core.dto.Cursor;
import io.justsearch.core.dto.Query;
import io.justsearch.core.dto.Result;
import io.justsearch.core.search.SearchPort;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Wraps a {@link SearchPort} to translate between the wire-level {@link SearchRequest} /
 * {@link SearchResponse} DTOs and the core {@link Query} / {@link Result} types.
 *
 * <p>§31 supplier-aware: the SearchPort is supplied lazily so the Worker-backed port can be
 * substituted in after async Worker connect without rebuilding the held ServiceGraph. The
 * supplier resolves at each {@link #search} call; it should never return null (use a
 * {@code NoopSearchPort} instead).
 */
public final class SearchServiceImpl implements io.justsearch.app.api.SearchService {

  private final Supplier<SearchPort> searchPortSupplier;

  public SearchServiceImpl(Supplier<SearchPort> searchPortSupplier) {
    this.searchPortSupplier = Objects.requireNonNull(searchPortSupplier, "searchPortSupplier");
  }

  /** Execute the request and return the wire-level response. */
  @Override
  public SearchResponse search(SearchRequest request, io.justsearch.core.context.EngineContext context) {
    Objects.requireNonNull(request, "request");
    Query query = toCoreQuery(request);
    Result result = searchPortSupplier.get().search(query, Objects.requireNonNull(context, "context"));
    return toApiResponse(result);
  }

  static Query toCoreQuery(SearchRequest req) {
    Query.Filters filters = null;
    if (req.filters() != null) {
      Query.TimeRange tr = null;
      if (req.filters().timeRange() != null) {
        tr =
            new Query.TimeRange(
                req.filters().timeRange().fromMs(), req.filters().timeRange().toMs());
      }
      filters = new Query.Filters(req.filters().mime(), req.filters().language(), tr);
    }

    List<Query.Clause> clauses = null;
    if (req.clauses() != null) {
      clauses =
          req.clauses().stream()
              .map(c -> new Query.Clause(c.type(), c.field(), c.value(), c.tokens()))
              .toList();
    }

    Cursor cursor = null;
    if (req.cursor() != null) {
      SearchRequest.Cursor c = req.cursor();
      cursor = new Cursor(c.mode(), c.token(), c.expiresAtEpochMs(), c.extras());
    }

    Map<String, Object> context =
        req.context() == null ? Map.of() : req.context().translatorMeta();
    return new Query(
        req.limit(), req.offset(), req.highlight(), filters, req.sort(), clauses, cursor, context);
  }

  static SearchResponse toApiResponse(Result res) {
    List<SearchResponse.Hit> hits = null;
    if (res.hits() != null) {
      hits =
          res.hits().stream()
              .map(h -> new SearchResponse.Hit(h.doc_id(), h.score(), h.highlights()))
              .toList();
    }
    Map<String, Map<String, Integer>> facets = res.facets();
    SearchResponse.Cursor cursor = null;
    if (res.cursor() != null) {
      Cursor coreCursor = res.cursor();
      cursor =
          new SearchResponse.Cursor(
              coreCursor.mode(),
              coreCursor.token(),
              coreCursor.expiresAtEpochMs(),
              coreCursor.extras());
    }
    return new SearchResponse(hits, facets, cursor, res.metadata());
  }
}
