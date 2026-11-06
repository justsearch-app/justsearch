/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypesRuntimeSearchFiltersBuilder;
import io.justsearch.adapters.lucene.runtime.QueryFilterBuilder;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 811 D-2 — {@link RagContextOps#withIncludeChunks} used to rebuild the filter record
 * component-by-component and OMITTED {@code collection} and {@code docIds}, so an explicit scope
 * silently disappeared on the RAG path (re-opening the class tempdoc 629 §Open issue #1 flagged).
 */
@DisplayName("RagContextOps — filter copy preserves every component (811 D-2)")
final class RagContextOpsFilterCopyTest {

  private static LuceneRuntimeTypes.RuntimeSearchFilters scoped() {
    return LuceneRuntimeTypesRuntimeSearchFiltersBuilder.builder()
        .collection(List.of("agent-history"))
        .docIds(List.of("d:/agent/session-1.md"))
        .language(List.of("en"))
        .pathPrefix("d:/agent")
        .includeChunks(true)
        .build();
  }

  @Test
  @DisplayName("an explicit collection + docIds scope survives withIncludeChunks")
  void explicitScopeSurvivesIncludeChunksOverride() {
    var copy = RagContextOps.withIncludeChunks(scoped(), false);

    assertFalse(copy.includeChunks(), "the one component the caller asked to override");
    assertEquals(List.of("agent-history"), copy.collection(), "collection must not be dropped");
    assertEquals(
        List.of("d:/agent/session-1.md"), copy.docIds(), "docIds must not be dropped");
    assertEquals(List.of("en"), copy.language(), "unrelated components are copied verbatim");
    assertEquals("d:/agent", copy.pathPrefix());
  }

  @Test
  @DisplayName("the copied scope still reaches the built Lucene filter query")
  void copiedScopeReachesTheQuery() {
    var copy = RagContextOps.withIncludeChunks(scoped(), false);
    String q = QueryFilterBuilder.buildFilterQueryOnly(copy).toString();

    assertTrue(
        q.contains("collection:agent-history") || q.contains("collection:(agent-history)"),
        "explicit scope survives to the query: " + q);
    assertFalse(
        (q.contains("-collection:agent-history") || q.contains("-collection:(agent-history)")),
        "an explicit agent-history scope must not be turned into the default exclusion: " + q);
    assertTrue(q.contains("d:/agent/session-1.md"), "docIds survive to the query: " + q);
  }
}
