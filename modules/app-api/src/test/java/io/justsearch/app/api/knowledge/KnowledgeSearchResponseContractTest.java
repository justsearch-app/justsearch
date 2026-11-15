package io.justsearch.app.api.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards against silent field omissions in the controller's manual HashMap serialization.
 *
 * <p>When a field is added to {@link KnowledgeSearchResponse}, this test fails — reminding the
 * developer to also add the field to {@code KnowledgeSearchController.handleSearch()}'s manual
 * {@code HashMap} mapping. Update the expected set below after confirming the controller maps the
 * new field.
 */
class KnowledgeSearchResponseContractTest {

  /**
   * The set of component names that KnowledgeSearchController.handleSearch() maps into its
   * response HashMap. When you add a field to KnowledgeSearchResponse, add it here AND in the
   * controller's if/put block.
   */
  private static final Set<String> CONTROLLER_MAPPED_FIELDS =
      Set.of(
          "totalHits",
          // Tempdoc 597: the true matched-document count (mapped in KnowledgeSearchController).
          "matchCount",
          "helpMatches",
          "tookMs",
          "results",
          "nextCursor",
          "facets",
          "facetsTruncated",
          // Tempdoc 549 U4 (Slice 6): the 15 flat query-trace fields were removed; the canonical
          // `introspection` trace is the single source the controller emits.
          "entityFacetVariants",
          "indexCapabilities",
          // Tempdoc 549 Phase E3: pipelineExecution retired — per-stage timing/status on searchTrace.
          "queryUnderstanding",
          "filterNormalization",
          // Tempdoc 549 Phase E4: introspection retired — the unified trace is the single source.
          "searchTrace",
          // Tempdoc 366 §1b: the filter echo (mapped in KnowledgeSearchController when non-null).
          "appliedFilters");

  @Test
  void recordComponentsMatchControllerMapping() {
    Set<String> recordComponents =
        Stream.of(KnowledgeSearchResponse.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(Collectors.toSet());

    assertEquals(
        recordComponents,
        CONTROLLER_MAPPED_FIELDS,
        "KnowledgeSearchResponse has components not listed in CONTROLLER_MAPPED_FIELDS "
            + "(or vice versa). When adding a field to the record, also add it to "
            + "KnowledgeSearchController.handleSearch()'s HashMap mapping AND update "
            + "this set.");
  }
}
