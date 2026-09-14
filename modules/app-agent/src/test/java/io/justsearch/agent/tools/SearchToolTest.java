package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import io.justsearch.app.api.knowledge.PipelineConfig;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// Windows-specific path-form assertions (see BrowseToolTest) — runs on the
// windows-native lane (tempdoc 668 option B).
@Tag("windows")
class SearchToolTest {

  private AtomicReference<KnowledgeSearchRequest> capturedRequest;
  private KnowledgeSearchResponse stubbedResponse;
  private SearchTool tool;

  @BeforeEach
  void setUp() {
    capturedRequest = new AtomicReference<>();
    stubbedResponse = emptyResponse();
    tool =
        new SearchTool(
            (req, context) -> {
              capturedRequest.set(req);
              return stubbedResponse;
            });
  }

  @Test
  void executeWithValidQuery() {
    stubbedResponse = responseWithHits(1);

    OperationResult result = tool.execute("{\"query\": \"test documents\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertNotNull(capturedRequest.get());
    assertEquals("test documents", capturedRequest.get().query());
    assertTrue(result.message().contains("[1]"));
    assertTrue(result.message().contains("Found 1 results"));
  }

  @Test
  void executeMissingQueryReturnsFailure() {
    OperationResult result = tool.execute("{}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("required"), result.message());
  }

  @Test
  void executeEmptyQueryReturnsFailure() {
    OperationResult result = tool.execute("{\"query\": \"\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("required"), result.message());
  }

  @Test
  void executeWithLimitAndMode() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"find me\", \"limit\": 5, \"mode\": \"hybrid\"}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(5, req.limit());
    // 256-G2: mode is translated to pipeline, mode field is null
    assertNull(req.mode());
    assertNotNull(req.pipeline());
    assertTrue(req.pipeline().sparseEnabled());
    assertTrue(req.pipeline().denseEnabled());
  }

  @Test
  void executeDefaultModeFromConfig() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"test query\"}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    // 256-G2: mode is translated to pipeline; null mode → HYBRID preset
    assertNull(req.mode());
    assertNotNull(req.pipeline());
    assertTrue(req.pipeline().sparseEnabled(), "HYBRID preset has sparse enabled");
    assertTrue(req.pipeline().denseEnabled(), "HYBRID preset has dense enabled");
  }

  @Test
  void executeLimitCappedAtMax() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"find me\", \"limit\": 100}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(20, req.limit(), "Limit should be capped at MAX_LIMIT=20");
  }

  @Test
  void executeWithPathPrefix() {
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"invoices\", \"path_prefix\": \"/docs/finance\"}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertNotNull(req.filters());
    assertEquals("/docs/finance", req.filters().pathPrefix());
  }

  @Test
  void executeWithDocIds() {
    // Tempdoc S7 — docIds is not declared on the catalog Interface (AgentToolDispatcher
    // merges it in server-side), but SearchTool must still honor it when present in the parsed
    // arguments, threading it into KnowledgeSearchRequest.Filters.docIds (QueryFilterBuilder
    // matches it against SchemaFields.PATH).
    stubbedResponse = emptyResponse();

    tool.execute(
        "{\"query\": \"invoices\", \"docIds\": [\"/docs/a.md\", \"/docs/b.md\"]}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertNotNull(req.filters());
    assertEquals(List.of("/docs/a.md", "/docs/b.md"), req.filters().docIds());
  }

  @Test
  void executeWithoutDocIdsOrPathPrefix_filtersIsNull() {
    // Absent case: no docIds, no path_prefix — filters stays null (unscoped), the pre-S7 behavior.
    stubbedResponse = emptyResponse();

    tool.execute("{\"query\": \"invoices\"}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertNull(req.filters());
  }

  @Test
  void executeWithDocIdsAndPathPrefix_bothFiltersApply() {
    stubbedResponse = emptyResponse();

    tool.execute(
        "{\"query\": \"invoices\", \"path_prefix\": \"/docs/finance\","
            + " \"docIds\": [\"/docs/finance/a.md\"]}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertNotNull(req.filters());
    assertEquals("/docs/finance", req.filters().pathPrefix());
    assertEquals(List.of("/docs/finance/a.md"), req.filters().docIds());
  }

  @Test
  void executeCallbackError() {
    tool =
        new SearchTool(
            (req, context) -> {
              throw new RuntimeException("Connection refused");
            });

    OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("Connection refused"), result.message());
  }

  @Test
  void executeFormatsMultipleResults() {
    stubbedResponse = responseWithHits(3);

    OperationResult result = tool.execute("{\"query\": \"reports\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("[1]"));
    assertTrue(result.message().contains("[2]"));
    assertTrue(result.message().contains("[3]"));
    assertTrue(result.message().contains("Found 3 results"));
    assertTrue(result.message().contains("Path:"));
  }

  @Test
  void executeNoResults() {
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"nonexistent\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("No results found"), result.message());
  }

  @Test
  void executeNullArgumentsReturnsFailure() {
    OperationResult result = tool.execute(null, EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("No arguments"), result.message());
  }

  @Test
  void executeMalformedJsonReturnsFailure() {
    OperationResult result = tool.execute("not json {{{", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("error") || result.message().contains("Search error"),
        result.message());
  }

  @Test
  void executeNullResponseReturnsFailure() {
    tool = new SearchTool((req, context) -> null);

    OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertFalse(result.success());
    assertTrue(result.message().contains("no response"), result.message());
  }

  @Test
  void executeFormatsExcerptsWithSpecialChars() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of("title", "Test Doc", "path", "/test.pdf"))
                    .excerptRegions(List.of(
                        new KnowledgeSearchResponse.ExcerptRegion(
                            "He said \"hello\" and\nnewline here", 0, 30, 1, List.of())))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    // Quotes should be replaced with apostrophes, newlines with spaces
    assertFalse(result.message().contains("\"hello\""), "Quotes should be sanitized");
    assertTrue(result.message().contains("'hello'"), "Quotes should become apostrophes");
    assertFalse(result.message().contains("and\nnewline"), "Newlines within excerpt should be replaced");
  }

  @Test
  void emitsStructuredSearchEvidenceWithoutAFabricatedScore() {
    // Tempdoc 561 #6: the tool result carries structured evidence (title/path/excerpt/line) for the
    // tool card — and deliberately NO score (the ranking score is uncalibrated; 559 §5 / §18 C-6).
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of("title", "Tax Notes", "path", "/docs/taxes.md"))
                    .excerptRegions(List.of(
                        new KnowledgeSearchResponse.ExcerptRegion(
                            "deductible limits for the year", 0, 30, 42, List.of())))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"taxes\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());

    Object raw = result.structuredData().get("searchResults");
    assertInstanceOf(List.class, raw, "structuredData carries a searchResults list");
    List<?> items = (List<?>) raw;
    assertEquals(1, items.size());
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) items.get(0);
    assertEquals("Tax Notes", item.get("title"));
    assertEquals("/docs/taxes.md", item.get("path"));
    assertEquals(42, item.get("line"));
    assertTrue(((String) item.get("excerpt")).contains("deductible"), "excerpt carried");
    // Honesty floor: no relevance score is emitted (would fabricate calibration from a ranking score).
    assertFalse(item.containsKey("score"), "no uncalibrated relevance score is surfaced");
  }

  @Test
  void feedbackEvidenceCarriesStableUidWithoutChangingRenderedEvidence() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .results(
                List.of(
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("C:/docs/taxes.md")
                        .score(0.9)
                        .fields(
                            Map.of(
                                "title", "Tax Notes",
                                "path", "C:/docs/taxes.md",
                                "doc_uid", "stable-tax-uid"))
                        .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"taxes\"}", EngineContextTestFixtures.AGENT_LOOP);

    List<?> feedback =
        assertInstanceOf(
            List.class, result.structuredData().get(OperationResult.FEEDBACK_FEATURES_KEY));
    assertEquals(1, feedback.size());
    Map<?, ?> feedbackHit = assertInstanceOf(Map.class, feedback.getFirst());
    assertEquals("C:/docs/taxes.md", feedbackHit.get("docId"));
    assertEquals("stable-tax-uid", feedbackHit.get("docUid"));

    List<?> rendered =
        assertInstanceOf(
            List.class, result.structuredData().get(OperationResult.SEARCH_RESULTS_KEY));
    Map<?, ?> renderedHit = assertInstanceOf(Map.class, rendered.getFirst());
    assertFalse(renderedHit.containsKey("docUid"));
    assertFalse(renderedHit.containsKey("doc_uid"));
  }

  @Test
  void feedbackEvidenceOmitsHitWithoutUidInsteadOfFallingBackToPath() {
    stubbedResponse = responseWithHits(1);

    OperationResult result = tool.execute("{\"query\": \"documents\"}", EngineContextTestFixtures.AGENT_LOOP);

    List<?> feedback =
        assertInstanceOf(
            List.class, result.structuredData().get(OperationResult.FEEDBACK_FEATURES_KEY));
    assertTrue(feedback.isEmpty());
  }

  @Test
  void structuredDataCarriesQueryAndResultCount() {
    // Tempdoc S7 — additive structuredData keys alongside searchResults/feedbackFeatures: the
    // executed query text and the number of hits in THIS response.
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(5) // deliberately different from results().size() to distinguish the two
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of("title", "Tax Notes", "path", "/docs/taxes.md"))
                    .build(),
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-2").score(0.7)
                    .fields(Map.of("title", "More Taxes", "path", "/docs/taxes2.md"))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"taxes\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());

    assertEquals("taxes", result.structuredData().get("query"));
    assertEquals(2, result.structuredData().get("resultCount"), "resultCount is this response's hit count");
  }

  @Test
  void structuredDataCarriesTheExecutedSanitizedQuery() {
    // sanitizeFilePathQuery rewrites a file-path-shaped query before it's sent; structuredData's
    // "query" should reflect the ACTUALLY EXECUTED text, not the LLM's raw input.
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"docs/reference/config.md\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());

    assertEquals("docs reference config", result.structuredData().get("query"));
  }

  @Test
  void structuredDataCarriesTheResolvedSearchMode() {
    // Tempdoc 867 — the RESOLVED mode after config-default resolution (modeToPreset's own
    // defaulting), not a re-derivation: no "mode" arg and no configured default resolves to
    // "hybrid" (modeToPreset(null) == HYBRID), and an explicit mode is stamped verbatim (lowercase).
    stubbedResponse = emptyResponse();

    OperationResult noModeArg = tool.execute("{\"query\": \"taxes\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(noModeArg.success(), noModeArg.message());
    assertEquals("hybrid", noModeArg.structuredData().get("searchMode"));

    OperationResult vectorMode = tool.execute("{\"query\": \"taxes\", \"mode\": \"vector\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(vectorMode.success(), vectorMode.message());
    assertEquals("vector", vectorMode.structuredData().get("searchMode"));

    OperationResult textMode = tool.execute("{\"query\": \"taxes\", \"mode\": \"TEXT\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(textMode.success(), textMode.message());
    assertEquals("text", textMode.structuredData().get("searchMode"));
  }

  @Test
  void structuredDataStampsCustomForAFineGrainedPipeline() {
    // A fine-grained `pipeline` object overrides `mode` and has no single named preset — stamped
    // "custom" rather than guessing one of the three named modes for it.
    stubbedResponse = emptyResponse();

    OperationResult result =
        tool.execute(
            "{\"query\": \"taxes\", \"mode\": \"vector\","
                + " \"pipeline\": {\"sparseEnabled\": true, \"denseEnabled\": false}}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertEquals("custom", result.structuredData().get("searchMode"));
  }

  @Test
  void executeShowsQueryCorrection() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9).fields(Map.of("title", "Result")).build()))
            // Tempdoc 549 Phase E4: correction is carried by the unified trace's CORRECTION stage
            // (status=EXECUTED, detail=corrected query). SearchIntrospection was retired.
            .searchTrace(
                new io.justsearch.app.api.knowledge.SearchTrace(
                    1, null, null, null, null,
                    List.of(
                        new io.justsearch.app.api.knowledge.SearchTrace.TraceStage(
                            io.justsearch.app.api.knowledge.SearchTrace.StageId.CORRECTION,
                            io.justsearch.app.api.knowledge.SearchTrace.StageStatus.EXECUTED,
                            null, null, "corrected query", null))))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("corrected to"), result.message());
    assertTrue(result.message().contains("corrected query"), result.message());
  }

  // Tempdoc 877 §2.1: `schemaIsValidJson` moved to AgentToolCatalogContractTest (app-services),
  // which parses the catalog Interface of every agent tool — the only schema the model is shown.

  // ---------------------------------------------------------------------------
  // File-path query sanitization
  // ---------------------------------------------------------------------------

  @Test
  void sanitizeFilePathQuery_convertsPathToKeywords() {
    assertEquals(
        "docs reference configuration environment-variables",
        SearchTool.sanitizeFilePathQuery("docs/reference/configuration/environment-variables.md"));
  }

  @Test
  void sanitizeFilePathQuery_preservesNonPathQuery() {
    assertEquals("inference model", SearchTool.sanitizeFilePathQuery("inference model"));
  }

  @Test
  void sanitizeFilePathQuery_handlesBackslashes() {
    assertEquals(
        "docs explanation 01-system-overview",
        SearchTool.sanitizeFilePathQuery("docs\\explanation\\01-system-overview.md"));
  }

  @Test
  void sanitizeFilePathQuery_passesQueryUsedInSearch() {
    stubbedResponse = emptyResponse();

    tool.execute(
        "{\"query\": \"docs/reference/configuration/environment-variables.md\"}", EngineContextTestFixtures.AGENT_LOOP);

    var req = capturedRequest.get();
    assertNotNull(req);
    assertEquals(
        "docs reference configuration environment-variables",
        req.query(),
        "File-path query should be sanitized to keywords");
  }

  @Test
  void relativePathPrefix_emptyResults_showsHint() {
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"test\", \"path_prefix\": \"docs/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("HINT"), "Should contain HINT: " + result.message());
    assertTrue(
        result.message().contains("docs/how-to"),
        "Should echo the path_prefix: " + result.message());
    // Tempdoc 877 §2.7 — this tool has no roots supplier, so the hint cannot name root names and
    // points at the tool that can. It used to say "use an absolute path from browse_folders", which
    // is advice browse cannot follow: browse emits ROOT-RELATIVE paths (a measured 227 §A.6
    // decision), so the old wording sent the model looking for something no tool produces.
    assertTrue(
        result.message().contains("core_browse_folders"),
        "Should point at the tool that lists the roots: " + result.message());
  }

  @Test
  void relativePathPrefix_withRootsKnown_isRejectedAndNamesTheRoots() {
    // Tempdoc 877 §2.7 — the empty-result HINT above is reachable only when the roots are unknown.
    // With roots known, an unresolvable relative path_prefix never reaches the index at all:
    // RootsView.validate rejects it first, and THAT is the message that has to name the roots the
    // model can recover with. Pinned here so the two branches cannot be confused for one another.
    SearchTool.SearchCallback search = (req, context) -> emptyResponse();
    var toolWithRoots =
        new SearchTool(
            search,
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\data\\docs", "docs"),
                    new BrowseTool.RootInfo("D:\\data\\notes", "notes")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"nowhere/at/all\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(result.success(), result.message());
    assertTrue(
        result.message().contains("nowhere/at/all"),
        "Should echo the offending prefix: " + result.message());
    assertTrue(
        result.message().contains("D:\\data\\docs") && result.message().contains("D:\\data\\notes"),
        "Should name the indexed roots: " + result.message());
  }

  @Test
  void relativePathPrefix_thatMatchesARootName_resolvesInsteadOfBeingRejected() {
    // The other half of the same convention: a path starting with a root NAME — exactly the form
    // core_browse_folders emits — resolves to the absolute path and is NOT rejected.
    var captured = new AtomicReference<KnowledgeSearchRequest>();
    SearchTool.SearchCallback search =
        (req, context) -> {
          captured.set(req);
          return emptyResponse();
        };
    var toolWithRoots =
        new SearchTool(search, context -> List.of(new BrowseTool.RootInfo("D:\\data\\docs", "docs")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"docs/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertEquals("D:\\data\\docs\\how-to", captured.get().filters().pathPrefix());
  }

  @Test
  void absolutePathPrefix_emptyResults_noHint() {
    stubbedResponse = emptyResponse();

    OperationResult result =
        tool.execute("{\"query\": \"test\", \"path_prefix\": \"D:\\\\Documents\\\\stuff\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), result.message());
    assertTrue(
        result.message().contains("No results found"),
        "Should report no results: " + result.message());
    assertFalse(result.message().contains("HINT"), "Should NOT contain HINT: " + result.message());
  }

  // ---------------------------------------------------------------------------
  // Path prefix validation against roots
  // ---------------------------------------------------------------------------

  @Test
  void pathPrefix_relativePathResolved_whenRootMatches() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            (req, context) -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\docs", "docs"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"docs/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), "Relative path matching root should resolve: " + result.message());
    assertNotNull(capturedRequest.get(), "Search should have been executed");
    assertEquals(
        "D:\\docs\\how-to",
        capturedRequest.get().filters().pathPrefix(),
        "Relative path should resolve to absolute");
  }

  @Test
  void pathPrefix_relativePathNoMatch_rejected() {
    var toolWithRoots =
        new SearchTool(
            (req, context) -> stubbedResponse,
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"unknown/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(result.success(), "Relative path not matching any root should be rejected: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
  }

  @Test
  void pathPrefix_unixSlashRejected_whenRootsAvailable() {
    var toolWithRoots =
        new SearchTool(
            (req, context) -> stubbedResponse,
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        toolWithRoots.execute("{\"query\": \"test\", \"path_prefix\": \"/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(result.success(), "Unix-style /path should be rejected on Windows: " + result.message());
    assertTrue(result.message().contains("not an absolute path"), result.message());
  }

  @Test
  void pathPrefix_validRootedPath_accepted() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            (req, context) -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            context ->
                List.of(
                    new BrowseTool.RootInfo("D:\\Documents", "Documents"),
                    new BrowseTool.RootInfo("D:\\Projects", "Projects")));

    OperationResult result =
        toolWithRoots.execute(
            "{\"query\": \"test\", \"path_prefix\": \"D:\\\\Documents\\\\how-to\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertTrue(result.success(), "Valid rooted path should be accepted: " + result.message());
    assertNotNull(capturedRequest.get(), "Search should have been executed");
  }

  @Test
  void pathPrefix_absoluteButOutOfRoots_rejected() {
    var toolWithRoots =
        new SearchTool(
            (req, context) -> stubbedResponse,
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result =
        toolWithRoots.execute(
            "{\"query\": \"test\", \"path_prefix\": \"C:\\\\other\\\\path\"}", EngineContextTestFixtures.AGENT_LOOP);

    assertFalse(result.success(), "Out-of-root path should be rejected: " + result.message());
    assertTrue(result.message().contains("not under any indexed root"), result.message());
    assertTrue(result.message().contains("D:\\Documents"), result.message());
  }

  @Test
  void pathPrefix_nullOrEmpty_acceptedWithRoots() {
    stubbedResponse = emptyResponse();
    var toolWithRoots =
        new SearchTool(
            (req, context) -> {
              capturedRequest.set(req);
              return stubbedResponse;
            },
            context -> List.of(new BrowseTool.RootInfo("D:\\Documents", "Documents")));

    OperationResult result = toolWithRoots.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "No path_prefix should be accepted: " + result.message());
    assertNotNull(capturedRequest.get());
  }

  @Test
  void pathPrefix_noRootsSupplier_fallsBackToHeuristic() {
    // Tool without roots supplier should still work (original behavior)
    stubbedResponse = emptyResponse();

    OperationResult result = tool.execute("{\"query\": \"test\", \"path_prefix\": \"/how-to\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), "Without roots, all paths should be allowed: " + result.message());
  }

  @Test
  void executeFormatsContentPreviewFallback_whenNoExcerpts() {
    stubbedResponse =
        KnowledgeSearchResponseBuilder.builder()
            .totalHits(1)
            .tookMs(5)
            .results(List.of(
                KnowledgeSearchResponseHitBuilder.builder()
                    .id("doc-1").score(0.9)
                    .fields(Map.of(
                        "title", "Vector Result",
                        "path", "/test.md",
                        "content_preview",
                            "This is a long preview of the document content for vector search"))
                    .build()))
            .build();

    OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("Preview:"), "Should show content_preview fallback");
    assertTrue(result.message().contains("long preview"), result.message());
  }

  @Test
  void executeEnforcesPerResultCharBudget() {
    // Tempdoc 877 §2.2 — the budget is taken from ToolResultCarrier.layerTwoCapChars(budget), the ONE
    // reader of agent.maxToolResultChars, which since tempdoc 883 is a fraction of the LIVE window
    // resolved per call. This test used to set justsearch.agent.max_tool_result_chars at runtime and
    // rely on SearchTool's own per-call re-read of the config — the second reader §2.2 deletes. The
    // excerpt is sized past the 800-char per-region guard so truncation is certain at any cap.
    {
      String longText = "A".repeat(1200);
      stubbedResponse =
          KnowledgeSearchResponseBuilder.builder()
              .totalHits(3)
              .tookMs(12)
              .results(List.of(
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d1").score(0.9).fields(Map.of("title", "Doc 1", "path", "/d1.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build(),
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d2").score(0.8).fields(Map.of("title", "Doc 2", "path", "/d2.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build(),
                  KnowledgeSearchResponseHitBuilder.builder()
                      .id("d3").score(0.7).fields(Map.of("title", "Doc 3", "path", "/d3.pdf"))
                      .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                      .build()))
              .build();

      OperationResult result = tool.execute("{\"query\": \"test\"}", EngineContextTestFixtures.AGENT_LOOP);

      assertTrue(result.success(), result.message());
      String output = result.message();
      // All 3 results present (not starved by earlier results)
      assertTrue(output.contains("[1]"));
      assertTrue(output.contains("[2]"));
      assertTrue(output.contains("[3]"));
      // Excerpts are truncated by the per-result budget
      assertTrue(output.contains("..."), "Excerpts should be truncated by budget");
      // The full excerpt should NOT appear for any single result
      assertFalse(output.contains(longText), "Full excerpt should be truncated by per-result budget");
      // Tempdoc 877 §2.2 — and the WHOLE emitted string fits the Layer-2 cap by construction, which
      // is the half the old per-result-only budget never checked: headers, Path: lines, carrier
      // framing and the trailing summary were all uncounted, so the tail died inside Layer 2.
      assertTrue(
          output.length() <= io.justsearch.agent.ToolResultCarrier.layerTwoCapChars(
              io.justsearch.agent.AgentContextBudgets.forCall(null)),
          "emitted "
              + output.length()
              + " chars, over the Layer-2 cap of "
              + io.justsearch.agent.ToolResultCarrier.layerTwoCapChars(
              io.justsearch.agent.AgentContextBudgets.forCall(null)));
      assertTrue(output.contains("Found 3 results"), "the summary must survive: " + output);
    }
  }

  @Test
  void formatResultsUnderASmallCapKeepsEveryHitsIdentity() {
    // Recovers the small-cap coverage origin/main had as `executeEnforcesPerResultCharBudget` with
    // `justsearch.agent.max_tool_result_chars=600`. Setting that property at test time would make
    // the assertion depend on process-global config rather than on the renderer, so the cap is a
    // PARAMETER of the renderer instead and the constrained path is exercised directly.
    KnowledgeSearchResponse response = threeHitsWithLongExcerpts();

    String output = SearchTool.formatResults(response, 600);

    assertTrue(output.length() <= 600, "over the 600-char cap: " + output.length());
    assertTrue(output.contains("[1]"), output);
    assertTrue(output.contains("[2]"), output);
    assertTrue(output.contains("[3]"), output);
    assertTrue(output.contains("Path: /d1.pdf"), output);
    assertTrue(output.contains("..."), "excerpts must be clipped at this cap: " + output);
    assertTrue(output.contains("Found 3 results"), output);
  }

  @Test
  void formatResultsUnderATightCapDropsTheTailAndSaysSo() {
    // The head-cut regression, at the altitude where it is cheapest to see. A budget that cannot
    // carry all three identity blocks must render the TOP hits and STATE the rest — not silently
    // skip the hits whose header overran their slice and spend the freed budget on lower-ranked
    // ones, which is what a per-hit-slice budget with a "skip if the header doesn't fit" arm does.
    KnowledgeSearchResponse response = threeHitsWithLongExcerpts();

    String output = SearchTool.formatResults(response, 140);

    assertTrue(output.length() <= 140, "over the 140-char cap: " + output.length());
    assertTrue(output.contains("[1] Doc 1"), "the top hit must survive a tight cap: " + output);
    assertFalse(output.contains("[3]"), "the TAIL is what a tight cap drops: " + output);
    assertTrue(
        output.contains("further results omitted (context budget)"),
        "a dropped hit must be stated, not silently missing: " + output);
    assertTrue(output.contains("Found 3 results"), output);
  }

  private static KnowledgeSearchResponse threeHitsWithLongExcerpts() {
    String longText = "A".repeat(1200);
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(3)
        .tookMs(12)
        .results(List.of(
            KnowledgeSearchResponseHitBuilder.builder()
                .id("d1").score(0.9).fields(Map.of("title", "Doc 1", "path", "/d1.pdf"))
                .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                .build(),
            KnowledgeSearchResponseHitBuilder.builder()
                .id("d2").score(0.8).fields(Map.of("title", "Doc 2", "path", "/d2.pdf"))
                .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                .build(),
            KnowledgeSearchResponseHitBuilder.builder()
                .id("d3").score(0.7).fields(Map.of("title", "Doc 3", "path", "/d3.pdf"))
                .excerptRegions(List.of(new KnowledgeSearchResponse.ExcerptRegion(longText, 0, 500, 1, List.of())))
                .build()))
        .build();
  }

  // ---------------------------------------------------------------------------
  // modeToPreset() unit tests (256: Phase G2)
  // ---------------------------------------------------------------------------

  @Test
  void modeToPreset_text() {
    assertEquals(PipelineConfig.TEXT, SearchTool.modeToPreset("text"));
  }

  @Test
  void modeToPreset_vector() {
    assertEquals(PipelineConfig.VECTOR, SearchTool.modeToPreset("vector"));
  }

  @Test
  void modeToPreset_hybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("hybrid"));
  }

  @Test
  void modeToPreset_null_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset(null));
  }

  @Test
  void modeToPreset_blank_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset(""));
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("   "));
  }

  @Test
  void modeToPreset_unknown_defaultsToHybrid() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("unknown"));
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("splade"));
  }

  @Test
  void modeToPreset_caseInsensitive() {
    assertEquals(PipelineConfig.HYBRID, SearchTool.modeToPreset("HYBRID"));
    assertEquals(PipelineConfig.TEXT, SearchTool.modeToPreset("Text"));
    assertEquals(PipelineConfig.VECTOR, SearchTool.modeToPreset("VECTOR"));
  }

  // ---------------------------------------------------------------------------
  // parsePipelineArg() unit tests (256: Phase H1)
  // ---------------------------------------------------------------------------

  private static final tools.jackson.databind.ObjectMapper OM =
      new tools.jackson.databind.ObjectMapper();

  @Test
  void parsePipelineArg_emptyObject_allDefaults() throws Exception {
    var node = OM.readTree("{}");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertFalse(cfg.sparseEnabled());
    assertFalse(cfg.denseEnabled());
    assertFalse(cfg.spladeEnabled());
    assertEquals("none", cfg.fusionAlgorithm());
    assertFalse(cfg.lambdamartEnabled());
    assertFalse(cfg.crossEncoderEnabled());
    assertEquals(0, cfg.crossEncoderWindow());
    assertFalse(cfg.expansionEnabled());
  }

  @Test
  void parsePipelineArg_fullObject() throws Exception {
    var node = OM.readTree("""
        {"sparseEnabled":true,"denseEnabled":true,"spladeEnabled":true,
         "fusionAlgorithm":"rrf","lambdamartEnabled":true,
         "crossEncoderEnabled":true,"crossEncoderWindow":10,
         "expansionEnabled":true}""");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertTrue(cfg.sparseEnabled());
    assertTrue(cfg.denseEnabled());
    assertTrue(cfg.spladeEnabled());
    assertEquals("rrf", cfg.fusionAlgorithm());
    assertTrue(cfg.lambdamartEnabled());
    assertTrue(cfg.crossEncoderEnabled());
    assertEquals(10, cfg.crossEncoderWindow());
    assertTrue(cfg.expansionEnabled());
  }

  @Test
  void parsePipelineArg_partialObject_onlySparseEnabled() throws Exception {
    var node = OM.readTree("{\"sparseEnabled\":true}");
    PipelineConfig cfg = SearchTool.parsePipelineArg(node);
    assertTrue(cfg.sparseEnabled());
    assertFalse(cfg.denseEnabled());
    assertFalse(cfg.lambdamartEnabled());
    assertFalse(cfg.expansionEnabled());
    assertEquals("none", cfg.fusionAlgorithm());
  }

  // ===== Helpers =====

  private static KnowledgeSearchResponse emptyResponse() {
    return KnowledgeSearchResponseBuilder.builder().tookMs(5).build();
  }

  private static KnowledgeSearchResponse responseWithHits(int count) {
    List<KnowledgeSearchResponse.Hit> hits =
        java.util.stream.IntStream.rangeClosed(1, count)
            .mapToObj(
                i ->
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("doc-" + i)
                        .score(1.0 - (i * 0.1))
                        .fields(Map.of("title", "Document " + i, "path", "/docs/doc-" + i + ".pdf"))
                        .matchedFields(List.of("content"))
                        .excerptRegions(List.of(
                            new KnowledgeSearchResponse.ExcerptRegion(
                                "Matching excerpt for document " + i, 0, 30, 1, List.of())))
                        .build())
            .toList();
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(count)
        .tookMs(12)
        .results(hits)
        .build();
  }
}
