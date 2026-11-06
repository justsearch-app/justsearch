/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import io.justsearch.ipc.RetrieveContextRequest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 821 §3-C2 — the collection scope on the RAG path. Before this, {@code
 * RetrieveContextRequest} had no collection field at all, so an ASK could not be scoped the way a
 * search could: {@code buildRagFilters} never set {@code collection} and the Worker always applied
 * the DEFAULT scope.
 *
 * <p>Two things are pinned here. First that an explicit scope reaches the Lucene filter as a
 * POSITIVE include. Second — the routing decision — that {@code collection} counts towards {@code
 * hasFilters} but NOT towards {@code hasDocLevelFilters}, so a collection-only request keeps the
 * chunk-search path instead of being diverted into {@code executeRetrieval}'s parent pre-filter
 * (which would change {@code chunks_found} and the empty-response shape).
 */
@DisplayName("RagContextOps — collection scope on the RAG path (821 §3-C2)")
final class RagContextOpsCollectionScopeTest {

  private static RetrieveContextRequest.Builder base() {
    return RetrieveContextRequest.newBuilder().setQuestion("what did the agent do?").setTopK(5);
  }

  @Nested
  @DisplayName("An explicit scope")
  class ExplicitScope {

    @Test
    @DisplayName("reaches RuntimeSearchFilters and the built chunk filter as a positive include")
    void explicitCollectionReachesTheChunkFilter() {
      var filters = RagContextOps.buildRagFilters(base().addCollection("agent-history").build());

      assertNotNull(filters, "a collection-only request must produce a filter record");
      assertEquals(List.of("agent-history"), filters.collection());

      String q = QueryFilterBuilder.buildChunkFilterQuery(filters).toString();
      assertTrue(
        q.contains("collection:agent-history") || q.contains("collection:(agent-history)"),
        "scope must reach the query: " + q);
      assertFalse(
          (q.contains("-collection:agent-history") || q.contains("-collection:(agent-history)")),
          "an explicit agent-history scope must NOT become the default exclusion: " + q);
    }

    @Test
    @DisplayName("survives alongside document-level filters")
    void explicitCollectionSurvivesWithDocLevelFilters() {
      var filters =
          RagContextOps.buildRagFilters(
              base().addCollection("agent-history").addEntityPersons("ada").build());

      assertEquals(List.of("agent-history"), filters.collection());
      // The parent pre-filter leg copies every component (811 D-2 wither) — assert it, since this
      // is the leg that resolves the doc universe the chunk search is then scoped to.
      String parentQuery =
          QueryFilterBuilder.buildFilterQueryOnly(RagContextOps.withIncludeChunks(filters, false))
              .toString();
      assertTrue(
          parentQuery.contains("collection:agent-history")
              || parentQuery.contains("collection:(agent-history)"),
          "scope must also bind on the parent pre-filter leg: " + parentQuery);
    }
  }

  @Nested
  @DisplayName("Routing (the hasFilters / hasDocLevelFilters decision)")
  class Routing {

    @Test
    @DisplayName("a collection-only request is NOT a document-level filter set")
    void collectionOnlyIsNotDocLevel() {
      var filters = RagContextOps.buildRagFilters(base().addCollection("agent-history").build());

      assertFalse(
          RagContextOps.hasDocLevelFilters(filters),
          "collection binds on the chunk branch (811 item 3); routing it through parent resolution"
              + " would change chunks_found and the empty-response shape");
    }

    @Test
    @DisplayName("adding collection does not change doc-level routing for other filters")
    void docLevelRoutingUnchangedByCollection() {
      var withoutCollection = RagContextOps.buildRagFilters(base().addEntityPersons("ada").build());
      var withCollection =
          RagContextOps.buildRagFilters(
              base().addEntityPersons("ada").addCollection("agent-history").build());

      assertTrue(RagContextOps.hasDocLevelFilters(withoutCollection));
      assertTrue(
          RagContextOps.hasDocLevelFilters(withCollection),
          "an entity filter still routes through parent resolution, with or without a scope");
    }
  }

  @Nested
  @DisplayName("Behaviour neutrality when collection is absent")
  class AbsentCollection {

    @Test
    @DisplayName("an unfiltered request still yields null filters (the pre-821 path)")
    void unfilteredRequestStillYieldsNull() {
      assertNull(
          RagContextOps.buildRagFilters(base().build()),
          "no filters at all must stay the null path, which QueryFilterBuilder treats as the"
              + " DEFAULT scope (811 D-1)");
    }

    @Test
    @DisplayName("null filters still bind the default agent-history exclusion")
    void nullFiltersStillExcludeAgentHistory() {
      String q = QueryFilterBuilder.buildChunkFilterQuery(null).toString();
      assertTrue(
          q.contains("-collection:agent-history"),
          "the 811 D-1 default exclusion must still bind: " + q);
    }

    @Test
    @DisplayName("a filtered-but-unscoped request keeps the default exclusion, not an include")
    void filteredButUnscopedKeepsDefaultExclusion() {
      var filters = RagContextOps.buildRagFilters(base().addFileKind("markdown").build());

      assertEquals(
          List.of(), filters.collection(), "an absent scope is empty, which IS the default scope");
      String q = QueryFilterBuilder.buildChunkFilterQuery(filters).toString();
      assertTrue(
          q.contains("-collection:agent-history"),
          "an unscoped request must still exclude agent-history: " + q);
      assertTrue(q.contains("markdown"), "the caller's own filter is unaffected: " + q);
    }
  }
}
