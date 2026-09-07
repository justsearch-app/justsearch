package io.justsearch.ui.api.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.javalin.http.Context;
import io.justsearch.agent.api.registry.ConfirmStrategy;
import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.GateBehavior;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.RiskTier;
import io.justsearch.agent.api.registry.SourceTier;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.SearchTrace.HitStage;
import io.justsearch.app.api.knowledge.SearchTrace.StageId;
import io.justsearch.app.api.knowledge.SearchTrace.StageStatus;
import io.justsearch.app.api.knowledge.SearchTrace.TraceStage;
import io.justsearch.app.api.mcp.McpContractVersions;
import io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry;
import io.justsearch.app.services.intent.PendingAuthorizationStore;
import io.justsearch.agent.tools.AgentToolsOperationCatalog;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class McpProtocolHandlerTest {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-05-16T12:00:00Z"), ZoneId.of("UTC"));

  private McpProtocolHandler handler;
  private OperationDispatcher dispatcher;

  @BeforeEach
  void setUp() {
    dispatcher = mock(OperationDispatcher.class);
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);
    handler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);
  }

  @Test
  @SuppressWarnings("unchecked")
  void malformedToolCallsSerializeMatchingValidationFacts() {
    for (String body : List.of(
        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":null}",
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{}}")) {
      Context ctx = mock(Context.class);
      when(ctx.body()).thenReturn(body);
      when(ctx.contentType(anyString())).thenReturn(ctx);
      ArgumentCaptor<String> response = ArgumentCaptor.forClass(String.class);
      when(ctx.result(response.capture())).thenReturn(ctx);

      handler.handlePost(ctx);

      Map<String, Object> wire = MAPPER.readValue(response.getValue(), Map.class);
      Map<String, Object> result = (Map<String, Object>) wire.get("result");
      assertEquals(Boolean.TRUE, result.get("isError"));
      Map<String, Object> facts = (Map<String, Object>) result.get("structuredContent");
      assertEquals("INVALID_REQUEST", facts.get("errorCode"));
      assertEquals("VALIDATION", facts.get("errorClass"));
      assertEquals(Boolean.FALSE, facts.get("retryable"));
      List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
      String text = (String) content.getFirst().get("text");
      assertTrue(text.startsWith((String) facts.get("error")));
      assertTrue(text.contains("Error code: INVALID_REQUEST"));
      assertTrue(text.contains("Error class: VALIDATION"));
      assertTrue(text.contains("Retryable: false"));
    }
  }

  @Test
  void initialize_returnsCapabilities() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    assertEquals("2.0", response.get("jsonrpc"));
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    // Tempdoc 654: both versions are single-sourced from McpContractVersions — assert against the
    // constants (not literals) so the manifest's RuntimeContract and this response can't desync.
    assertEquals(McpContractVersions.PROTOCOL_VERSION, result.get("protocolVersion"));
    @SuppressWarnings("unchecked")
    Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
    // Tempdoc 804 §B9 (round-10 F12): serverInfo.version is the SERVER IMPLEMENTATION's version —
    // this build — computed from the one build-version source, never a literal and never the
    // tool-surface version (which reported 0.5.0 on a 0.2.0 build). The curated tool-surface
    // version keeps its own namespaced `_meta` slot, so nothing became unreachable.
    assertEquals(
        McpProtocolHandler.buildVersion(),
        serverInfo.get("version"),
        "serverInfo.version binds to the build version source, not a literal");
    @SuppressWarnings("unchecked")
    Map<String, Object> serverMeta = (Map<String, Object>) serverInfo.get("_meta");
    assertEquals(
        McpContractVersions.TOOL_SURFACE_VERSION,
        serverMeta.get("io.justsearch/toolSurfaceVersion"),
        "the curated tool-surface version stays reported, under its own name");
    @SuppressWarnings("unchecked")
    Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
    assertNotNull(caps.get("tools"));
    assertNotNull(caps.get("resources"));
    assertNotNull(caps.get("prompts"));
    // Tempdoc 655 fix: neither the tool list nor the resource list can change at runtime (both
    // are fixed at compile time) — locks in the corrected, honest capability declaration so a
    // future accidental revert to the over-declared `true` is caught.
    @SuppressWarnings("unchecked")
    Map<String, Object> toolsCap = (Map<String, Object>) caps.get("tools");
    assertEquals(Boolean.FALSE, toolsCap.get("listChanged"));
    @SuppressWarnings("unchecked")
    Map<String, Object> resourcesCap = (Map<String, Object>) caps.get("resources");
    assertEquals(Boolean.FALSE, resourcesCap.get("listChanged"));
    assertEquals(Boolean.TRUE, resourcesCap.get("subscribe"));

    // Tempdoc 655 (agent-legibility layer): initialize now carries the MCP `instructions` steering
    // field — the one server-level surface an autonomous agent reads at tool-selection time. It must
    // be present, non-blank, and COMPARATIVE (state when to prefer the index), not a bare feature list.
    Object instructions = result.get("instructions");
    assertNotNull(instructions, "initialize must return the MCP `instructions` steering field");
    String instr = (String) instructions;
    assertFalse(instr.isBlank(), "instructions must not be blank");
    assertTrue(
        instr.contains("justsearch_answer"),
        "instructions must steer toward the primary retrieval tool");
    assertTrue(
        instr.toLowerCase().contains("prefer"),
        "instructions must be comparative (when to prefer the index), not a bare feature list");
  }

  /**
   * Tempdoc 804 §B9 (round-10 F12) — the CONTRACT: {@code serverInfo.version} tracks the build
   * version source, so it cannot report a version this build does not have. Drives the real source
   * ({@code -Djustsearch.app.version}, what the packaged shell passes) rather than asserting the
   * helper against itself.
   */
  @Test
  void initialize_serverInfoVersion_tracksTheBuildVersionSource() throws Exception {
    String previous = System.getProperty("justsearch.app.version");
    System.setProperty("justsearch.app.version", "9.9.9-contract-probe");
    try {
      Context ctx = mock(Context.class);
      when(ctx.header("Mcp-Session-Id")).thenReturn(null);
      when(ctx.body())
          .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
      ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
      when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
      when(ctx.contentType(anyString())).thenReturn(ctx);

      handler.handlePost(ctx);

      @SuppressWarnings("unchecked")
      Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) response.get("result");
      @SuppressWarnings("unchecked")
      Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
      assertEquals(
          "9.9.9-contract-probe",
          serverInfo.get("version"),
          "serverInfo.version must be the build version, not the tool-surface version or a literal");
      assertFalse(
          McpContractVersions.TOOL_SURFACE_VERSION.equals(serverInfo.get("version")),
          "the tool-surface version must no longer masquerade as the server version");
    } finally {
      if (previous == null) {
        System.clearProperty("justsearch.app.version");
      } else {
        System.setProperty("justsearch.app.version", previous);
      }
    }
  }

  @Test
  void instructionsAndPromptPath_shareSingleSourcedGuidance() throws Exception {
    // Tempdoc 655: the connect-time instructions() surface and the user-invoked prompt path
    // (getStatusContext) must read ONE guidance string — 654 "projection, not fork". A distinctive
    // phrase that lives only in TOOL_SELECTION_GUIDANCE must appear in both.
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);
    String marker = "ordinary file tools are equally good";
    assertTrue(
        surface.instructions().contains(marker),
        "instructions() must carry the single-sourced comparative guidance");
    String promptJson =
        MAPPER.writeValueAsString(surface.getPrompt("search_files", Map.of("topic", "x")));
    assertTrue(
        promptJson.contains(marker),
        "the user-invoked prompt path must read the SAME single-sourced guidance (no fork)");
  }

  @Test
  void instructions_stayUnderClientTruncationBudget() {
    // Tempdoc 732 item 3(b): TOOL_SELECTION_GUIDANCE gained one sentence naming the
    // response_format token-size tradeoff. Some MCP clients truncate the connect-time
    // `instructions` field around 2KB, so this pins the byte length under that budget rather than
    // relying on eyeballing the string during review.
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK);
    int byteLength = surface.instructions().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    assertTrue(
        byteLength < 2048,
        "instructions() must stay under the ~2KB client truncation budget, was " + byteLength
            + " bytes");
  }

  @Test
  void comparativeAnswerHint_countsDistinctDocuments_notChunks() {
    // Regression (review finding F1): the hint must key on DISTINCT cited documents, not chunksFound
    // — multiple chunks from ONE document must NOT produce a "spanning multiple documents" claim.
    assertEquals(
        "",
        McpToolSurface.comparativeAnswerHint(List.of(cite("docA"), cite("docA"), cite("docA"))),
        "multiple chunks from a single document must not claim 'multiple documents'");

    // The fallback-dump path (chunksFound>1 but nothing assembled) yields empty citations → no hint.
    assertEquals("", McpToolSurface.comparativeAnswerHint(List.of()));
    assertEquals("", McpToolSurface.comparativeAnswerHint(null));

    // Genuinely multi-document assembly → fires with the honest distinct-document count.
    String hint =
        McpToolSurface.comparativeAnswerHint(List.of(cite("docA"), cite("docB"), cite("docC")));
    assertTrue(hint.contains("3 documents"), "distinct-document count must drive the hint: " + hint);
    assertTrue(hint.contains("single retrieval call"));

    // Blank parentDocId is not counted as a document (only docA is real → 1 distinct → no claim).
    assertEquals("", McpToolSurface.comparativeAnswerHint(List.of(cite(""), cite("docA"))));
  }

  private static DocumentService.ContextCitation cite(String parentDocId) {
    return new DocumentService.ContextCitation(
        parentDocId, 0, 1, 0, 0, 1.0f, "excerpt", 0, 0, "", 0,
        DocumentService.ContextInclusion.ABSENT);
  }

  @Test
  void toolsList_returnsCuratedFiveTools() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");

    // Tempdoc 501 Phase 15 added justsearch_runtime_manifest as the sixth tool.
    assertEquals(6, tools.size());
    assertEquals("justsearch_answer", tools.get(0).get("name"));
    assertEquals("justsearch_search", tools.get(1).get("name"));
    assertEquals("justsearch_browse", tools.get(2).get("name"));
    assertEquals("justsearch_ingest", tools.get(3).get("name"));
    assertEquals("justsearch_status", tools.get(4).get("name"));
    assertEquals("justsearch_runtime_manifest", tools.get(5).get("name"));

    String answerDesc = (String) tools.get(0).get("description");
    assertTrue(answerDesc.contains("primary tool for question-answering"));

    // Tempdoc 658: the opt-in `detail` argument is part of the published search-tool contract.
    @SuppressWarnings("unchecked")
    Map<String, Object> searchInputSchema =
        (Map<String, Object>) tools.get(1).get("inputSchema");
    @SuppressWarnings("unchecked")
    Map<String, Object> searchProps =
        (Map<String, Object>) searchInputSchema.get("properties");
    assertTrue(searchProps.containsKey("detail"), "search tool advertises the detail arg");
    // Tempdoc 770: querySyntax is a declared parameter now, not a description-only claim.
    assertTrue(searchProps.containsKey("query_syntax"), "search tool advertises the query_syntax arg");
    @SuppressWarnings("unchecked")
    Map<String, Object> querySyntaxProp = (Map<String, Object>) searchProps.get("query_syntax");
    assertEquals(
        List.of("simple", "lucene", "advanced"),
        querySyntaxProp.get("enum"),
        "query_syntax mirrors SearchPipelinePresets#parseQuerySyntaxOrDefault's accepted values");

    // Tempdoc 725: tools/list must serialize with a byte-stable key order across JVM restarts —
    // the MCP draft spec SHOULDs deterministic ordering for client-side cache hits. Jackson
    // deserializes JSON objects into LinkedHashMap, so the parsed key order here mirrors exactly
    // what was serialized; asserting it matches the documented source-literal order catches a
    // regression back to JDK Map.of (whose 2+-entry iteration order is salted per JVM run and
    // would only reveal itself as flakiness across separate process launches, not within one
    // test run).
    assertEquals(
        List.of("query", "limit", "mode", "filters", "query_syntax", "detail", "response_format"),
        List.copyOf(searchProps.keySet()),
        "search inputSchema properties must serialize in declared source order");

    // Tempdoc 811 (C-2a): the ingest tool advertises the optional `collection` tag. Ordered
    // (not Map.of) for the same byte-stability reason as the search/answer schemas above.
    @SuppressWarnings("unchecked")
    Map<String, Object> ingestInputSchema =
        (Map<String, Object>) tools.get(3).get("inputSchema");
    @SuppressWarnings("unchecked")
    Map<String, Object> ingestProps =
        (Map<String, Object>) ingestInputSchema.get("properties");
    assertEquals(
        List.of("paths", "collection"),
        List.copyOf(ingestProps.keySet()),
        "ingest inputSchema properties must serialize in declared source order");
    assertEquals(
        List.of("paths"),
        ingestInputSchema.get("required"),
        "collection stays optional — omitting it inherits the root collection or mcp-ingest");
    @SuppressWarnings("unchecked")
    Map<String, Object> collectionProp = (Map<String, Object>) ingestProps.get("collection");
    assertEquals("string", collectionProp.get("type"));
    assertTrue(
        ((String) collectionProp.get("description")).contains("mcp-ingest"),
        "the schema must name the out-of-root default so an agent knows what it gets");

    // Tempdoc 725 W2c: the opt-in `response_format` argument is part of the published
    // answer-tool contract too (sibling of the search-tool assertion above).
    @SuppressWarnings("unchecked")
    Map<String, Object> answerInputSchema =
        (Map<String, Object>) tools.get(0).get("inputSchema");
    @SuppressWarnings("unchecked")
    Map<String, Object> answerProps =
        (Map<String, Object>) answerInputSchema.get("properties");
    assertEquals(
        List.of("query", "top_k", "filters", "response_format"),
        List.copyOf(answerProps.keySet()),
        "answer inputSchema properties must serialize in declared source order");
    @SuppressWarnings("unchecked")
    Map<String, Object> answerResponseFormat =
        (Map<String, Object>) answerProps.get("response_format");
    assertEquals(
        List.of("concise", "detailed"), answerResponseFormat.get("enum"),
        "answer response_format is a concise/detailed enum");
    // Tempdoc 732 item 3 / 770 Part 2: the response_format schema description states the
    // concise/detailed tradeoff TRUTHFULLY — concise trims the text tier only, so a
    // structured-preferring client sees no size difference (measured: zero reduction across 336
    // opt-ins). The shared RESPONSE_FORMAT_SCHEMA constant, so pinning it once here covers both
    // tools (search's copy is asserted identical below).
    assertEquals(
        "Verbosity of the human-readable text block only; it does not change the structured"
            + " response, so a client that reads structuredContent (the common case) sees no"
            + " size difference. \"detailed\" (default) includes preview snippets and full"
            + " evidence passages. \"concise\" drops the per-hit preview line from"
            + " justsearch_search text and caps justsearch_answer text at the 3 highest-ranked"
            + " passages; the coverage, match, and header lines are kept in both modes.",
        answerResponseFormat.get("description"),
        "response_format schema description must state the text-tier-only tradeoff");
    // Tempdoc 655's single-sourced RESPONSE_FORMAT_SCHEMA is shared by both tools (655 "projection,
    // not fork") — search's copy must carry the identical description, not a drifted duplicate.
    @SuppressWarnings("unchecked")
    Map<String, Object> searchResponseFormat =
        (Map<String, Object>) searchProps.get("response_format");
    assertEquals(
        answerResponseFormat.get("description"),
        searchResponseFormat.get("description"),
        "search and answer must share the identical response_format description (single-sourced)");

    @SuppressWarnings("unchecked")
    Map<String, Object> searchFilters = (Map<String, Object>) searchProps.get("filters");
    @SuppressWarnings("unchecked")
    Map<String, Object> searchFilterProps = (Map<String, Object>) searchFilters.get("properties");
    assertEquals(
        List.of(
            "path_prefix",
            "meta_source",
            "meta_author",
            "meta_category",
            "entity_persons",
            "entity_organizations",
            "entity_locations",
            // Tempdoc 821 §3-C2 — the collection scope, declared last so the pre-821 order is
            // untouched (clients that cached the earlier key order see an append, not a reshuffle).
            "collection"),
        List.copyOf(searchFilterProps.keySet()),
        "filters schema properties must serialize in declared source order");

    @SuppressWarnings("unchecked")
    Map<String, Object> browseInputSchema =
        (Map<String, Object>) tools.get(2).get("inputSchema");
    @SuppressWarnings("unchecked")
    Map<String, Object> browseProps = (Map<String, Object>) browseInputSchema.get("properties");
    assertEquals(
        List.of("parent_path", "list_files"),
        List.copyOf(browseProps.keySet()),
        "browse inputSchema properties must serialize in declared source order");

    @SuppressWarnings("unchecked")
    Map<String, Object> ingestAnnotations =
        (Map<String, Object>) tools.get(3).get("annotations");
    assertEquals(
        List.of("readOnlyHint", "idempotentHint"),
        List.copyOf(ingestAnnotations.keySet()),
        "ingest tool annotations must serialize in declared source order");
  }

  @Test
  void resourcesList_returnsDeterministicKeyOrder() throws Exception {
    // Tempdoc 732 issue 8: resource() used a 4-entry Map.of, the same JDK-salted-iteration-order
    // defect already fixed for tool()/schema()/propStringArray()/propEnum() via orderedMap(...).
    // Mirrors the tools/list order assertion above for the resources/list response.
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"resources/list\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> resources = (List<Map<String, Object>>) result.get("resources");

    assertFalse(resources.isEmpty());
    assertEquals(
        List.of("uri", "name", "description", "mimeType"),
        List.copyOf(resources.get(0).keySet()),
        "resource entries must serialize in declared source order");
  }

  @Test
  void toolsCall_search_attachesStructuredEvidence() throws Exception {
    // Tempdoc 658: end-to-end wiring — the search tool projects the canonical SearchTrace onto the
    // structuredContent channel (the McpEvidenceProjection mapping itself is unit-tested in
    // McpEvidenceProjectionTest; this asserts callSearch attaches it and it survives serialization).
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            null,
            null,
            List.of(new TraceStage(StageId.SPARSE_RETRIEVAL, StageStatus.EXECUTED, null, 5L, null, null)));
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "Troubleshooting", "path", "help/troubleshooting.md"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new HitStage(StageId.SPARSE_RETRIEVAL, 1, 3.3f, null)));
    KnowledgeSearchResponse canned =
        new KnowledgeSearchResponse(
            1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, trace, null);

    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(canned);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    var localHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"justsearch_search\",\"arguments\":{\"query\":\"troubleshoot\"}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    localHandler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertEquals(Boolean.FALSE, result.get("isError"));
    @SuppressWarnings("unchecked")
    Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
    assertNotNull(structured, "search tool response carries structuredContent evidence");
    @SuppressWarnings("unchecked")
    Map<String, Object> searchTrace = (Map<String, Object>) structured.get("searchTrace");
    assertEquals("HYBRID", searchTrace.get("effectiveMode"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
    assertEquals(1, results.size());
    assertEquals("doc-1", results.get(0).get("id"));
    // Tempdoc 770: per-hit ranking provenance is a detail-gated tier; this call did not set
    // detail, so the block is absent. detail:true restore is covered end-to-end by
    // toolsCall_search_detailTrueRestoresPerHitProvenance below.
    assertNull(results.get(0).get("trace"), "per-hit ranking trace is gated behind detail:true");
  }

  // =========================================================================
  // Tempdoc 770 review fixes — the detail restore path and the querySyntax parameter, both
  // exercised through the REAL tools/call entry point (arg parsing + boundary validation +
  // dispatch), not by calling the projection directly. The minor-not-major contract argument
  // rests on "trace/legScores stay recoverable via detail", so that must be tested at the layer
  // that has to work.
  // =========================================================================

  /** Canned single-hit response with a populated per-hit trace, for the detail-tier tests. */
  private static KnowledgeSearchResponse cannedSearchResponse() {
    SearchTrace trace =
        new SearchTrace(
            SearchTrace.SCHEMA_VERSION,
            "HYBRID",
            "multi_leg",
            null,
            null,
            List.of(new TraceStage(StageId.SPARSE_RETRIEVAL, StageStatus.EXECUTED, null, 5L, null, null)));
    KnowledgeSearchResponse.Hit hit =
        new KnowledgeSearchResponse.Hit(
            "doc-1",
            0.9d,
            Map.of("title", "Troubleshooting", "path", "help/troubleshooting.md"),
            List.of(),
            List.of(),
            List.of(),
            List.of(new HitStage(StageId.SPARSE_RETRIEVAL, 1, 3.3f, Map.of("bm25", 3.3f))));
    return new KnowledgeSearchResponse(
        1L, 1L, 5L, List.of(hit), null, null, null, null, null, null, null, trace, null);
  }

  /** A handler wired to a mock adapter that always returns {@code canned}. */
  private McpProtocolHandler handlerOver(KnowledgeHttpApiAdapter adapter) {
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    return new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> structuredOf(String raw) throws Exception {
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertEquals(Boolean.FALSE, result.get("isError"), "tool call must succeed: " + raw);
    return (Map<String, Object>) result.get("structuredContent");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> firstHitOf(Map<String, Object> structured) {
    return ((List<Map<String, Object>>) structured.get("results")).get(0);
  }

  @Test
  void toolsCall_search_detailTrueRestoresPerHitProvenance() throws Exception {
    // Tempdoc 770 §B: the whole minor-not-major justification for removing trace/legScores from
    // the default response is that `detail: true` restores them. Asserted here through the real
    // entry point — JSON arg parsing, schema validation, callSearch's Boolean unwrap, and the
    // projection call site — because every other detail=true test calls the projection directly.
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(cannedSearchResponse());
    McpProtocolHandler h = handlerOver(adapter);

    Map<String, Object> withDetail =
        firstHitOf(structuredOf(callTool(h, 30, "justsearch_search", "{\"query\":\"x\",\"detail\":true}")));
    assertNotNull(withDetail.get("trace"), "detail:true must restore the per-hit trace");
    assertNotNull(withDetail.get("legScores"), "detail:true must restore the per-hit legScores");

    Map<String, Object> explicitFalse =
        firstHitOf(structuredOf(callTool(h, 31, "justsearch_search", "{\"query\":\"x\",\"detail\":false}")));
    assertNull(explicitFalse.get("trace"), "detail:false must omit the per-hit trace");
    assertNull(explicitFalse.get("legScores"), "detail:false must omit the per-hit legScores");

    Map<String, Object> absent =
        firstHitOf(structuredOf(callTool(h, 32, "justsearch_search", "{\"query\":\"x\"}")));
    assertNull(absent.get("trace"), "absent detail defaults to omitting the per-hit trace");
    assertNull(absent.get("legScores"), "absent detail defaults to omitting the per-hit legScores");

    // The ungated facts are identical across tiers — the gate elides, it does not reshape.
    assertEquals(withDetail.get("path"), absent.get("path"));
    assertEquals(withDetail.get("score"), absent.get("score"));
  }

  @Test
  void toolsCall_search_nonBooleanDetail_rejectedAtBoundary() throws Exception {
    // Tempdoc 770 review question B: SEARCH_SCHEMA declares detail as {"type":"boolean"} and
    // validateArgsOrNull runs before dispatch, so a string/number must be a clean boundary error
    // — NOT silently coerced to false (which would be a costly silent trap: the agent asks for
    // provenance, gets none, and is told nothing).
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(cannedSearchResponse());
    McpProtocolHandler h = handlerOver(adapter);

    for (String badDetail : List.of("\"true\"", "1")) {
      String raw = callTool(h, 33, "justsearch_search", "{\"query\":\"x\",\"detail\":" + badDetail + "}");
      @SuppressWarnings("unchecked")
      Map<String, Object> response = MAPPER.readValue(raw, Map.class);
      @SuppressWarnings("unchecked")
      Map<String, Object> result = (Map<String, Object>) response.get("result");
      assertEquals(
          Boolean.TRUE,
          result.get("isError"),
          "detail=" + badDetail + " must be rejected at the boundary, not coerced: " + raw);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
      assertTrue(
          ((String) content.get(0).get("text")).contains("Invalid arguments"),
          "must be the boundary-validation error: " + raw);
    }
  }

  @Test
  void toolsCall_search_querySyntaxReachesTheRequest() throws Exception {
    // Tempdoc 770 §E.2 (corrected): querySyntax is a live engine capability
    // (SearchPipelinePresets#parseQuerySyntaxOrDefault). It is now a declared schema parameter,
    // so the restored SEARCH_DESC sentence is true only if the value actually reaches the
    // request — callSearch passed a hard-coded null before this fix.
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(cannedSearchResponse());
    McpProtocolHandler h = handlerOver(adapter);

    callTool(h, 34, "justsearch_search", "{\"query\":\"\\\"exact phrase\\\"\",\"query_syntax\":\"lucene\"}");
    ArgumentCaptor<KnowledgeSearchRequest> req =
        ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
    verify(adapter).search(req.capture());
    assertEquals("lucene", req.getValue().querySyntax(), "querySyntax must reach the request");

    // Omitted → null, so the engine applies its SIMPLE default.
    reset(adapter);
    when(adapter.search(any())).thenReturn(cannedSearchResponse());
    callTool(h, 35, "justsearch_search", "{\"query\":\"plain\"}");
    ArgumentCaptor<KnowledgeSearchRequest> defaulted =
        ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
    verify(adapter).search(defaulted.capture());
    assertNull(defaulted.getValue().querySyntax(), "omitted querySyntax leaves the engine default");
  }

  @Test
  void toolsCall_search_undeclaredQuerySyntaxValue_rejectedAtBoundary() throws Exception {
    // The schema declares an enum, so a value the engine would silently fold to SIMPLE is a clean
    // error instead — the exact failure mode (silently ignored querySyntax) this lane is fixing.
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    when(adapter.search(any())).thenReturn(cannedSearchResponse());
    String raw =
        callTool(
            handlerOver(adapter), 36, "justsearch_search",
            "{\"query\":\"x\",\"query_syntax\":\"regex\"}");
    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertEquals(Boolean.TRUE, result.get("isError"), "undeclared enum value must be rejected: " + raw);
  }

  @Test
  void toolsCall_unknownTool_returnsError() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"nonexistent\",\"arguments\":{}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
  }

  @Test
  void toolsCall_statusWithoutKnowledge_returnsUnavailable() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"justsearch_status\",\"arguments\":{}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
  }

  @Test
  void promptsList_returnsThreeTemplates() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\",\"params\":{}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> prompts = (List<Map<String, Object>>) result.get("prompts");

    assertEquals(3, prompts.size());
    assertEquals("search_files", prompts.get(0).get("name"));
    assertEquals("answer_question", prompts.get(1).get("name"));
    assertEquals("index_folder", prompts.get(2).get("name"));
  }

  @Test
  void promptsGet_expandsSearchTemplate() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"prompts/get\","
                + "\"params\":{\"name\":\"search_files\","
                + "\"arguments\":{\"topic\":\"climate change\"}}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
    assertEquals(2, messages.size());
    assertEquals("assistant", messages.get(0).get("role"));
    assertEquals("user", messages.get(1).get("role"));
  }

  @Test
  void ping_returnsEmptyResult() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body()).thenReturn("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    assertNotNull(response.get("result"));
  }

  // =========================================================================
  // Tempdoc 655: gated confirmation now creates a real pending record instead of a fabricated
  // (and never-honored) "_confirmationToken" hint, and all 6 tools validate arguments at the
  // MCP boundary before dispatch.
  // =========================================================================

  private String callTool(McpProtocolHandler h, int id, String toolName, String argumentsJson)
      throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn("s1");
    when(ctx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":"
                + id
                + ",\"method\":\"tools/call\",\"params\":{\"name\":\""
                + toolName
                + "\",\"arguments\":"
                + argumentsJson
                + "}}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);
    h.handlePost(ctx);
    return resultCaptor.getValue();
  }

  @Test
  void toolsCall_gatedIngest_createsPendingAndReturnsTruthfulMessage() throws Exception {
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    assertEquals(0, pendingStore.size());
    String raw =
        callTool(gatedHandler, 10, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");

    // The old, never-honored retry hint must be gone.
    assertFalse(text.contains("_confirmationToken"), "must not advertise the dead retry path");
    // The new message is truthful: approval lives in the app; no retry needed.
    assertTrue(text.contains("JustSearch app"), "must point at the app as the approval surface");
    assertTrue(
        text.contains("do not need to retry"), "must not tell the agent to retry this call");

    // A real pending record was created — the SAME mechanism the browser UI's gate path uses.
    assertEquals(1, pendingStore.size());
  }

  @Test
  void initialize_capturesClientInfo_surfacesAsRequestedByOnGatedPending() throws Exception {
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore =
        new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    // initialize with clientInfo — capture the minted session id off the response header setter.
    Context initCtx = mock(Context.class);
    when(initCtx.header("Mcp-Session-Id")).thenReturn(null);
    when(initCtx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                + "{\"clientInfo\":{\"name\":\"Claude Code\",\"version\":\"1.0\"}}}");
    ArgumentCaptor<String> sessionIdCaptor = ArgumentCaptor.forClass(String.class);
    when(initCtx.header(eq("Mcp-Session-Id"), sessionIdCaptor.capture()))
        .thenReturn(initCtx);
    when(initCtx.result(anyString())).thenReturn(initCtx);
    when(initCtx.contentType(anyString())).thenReturn(initCtx);
    gatedHandler.handlePost(initCtx);
    String sessionId = sessionIdCaptor.getValue();
    assertNotNull(sessionId, "initialize must mint and return a session id");

    // tools/call reusing that session — the gate fires, creating a pending record.
    java.util.concurrent.atomic.AtomicReference<String> pendingId =
        new java.util.concurrent.atomic.AtomicReference<>();
    pendingChanges.subscribeTyped(event -> pendingId.set(event.pendingId()));
    Context callCtx = mock(Context.class);
    when(callCtx.header("Mcp-Session-Id")).thenReturn(sessionId);
    when(callCtx.body())
        .thenReturn(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                + "\"justsearch_ingest\",\"arguments\":{\"paths\":[\"C:/tmp/notes\"]}}}");
    when(callCtx.result(anyString())).thenReturn(callCtx);
    when(callCtx.contentType(anyString())).thenReturn(callCtx);
    gatedHandler.handlePost(callCtx);

    assertEquals(1, pendingStore.size());
    assertNotNull(pendingId.get(), "the advisory subscription must have observed the broadcast");
    var pending = pendingStore.peek(pendingId.get());
    assertTrue(pending.isPresent());
    assertEquals(
        "Claude Code",
        pending.get().requestedBy(),
        "the MCP client's self-reported clientInfo.name must flow through to the pending record");
  }

  @Test
  void toolsCall_noClientInfo_pendingRequestedByIsNull() throws Exception {
    // A client that omits clientInfo (or never calls initialize with a session at all) must not
    // fabricate a requester name — requestedBy stays null, not "" or "unknown".
    OperationDispatcher gatedDispatcher = mock(OperationDispatcher.class);
    when(gatedDispatcher.dispatch(any(), any(), any()))
        .thenThrow(
            new ConfirmationRequiredException(
                AgentToolsOperationCatalog.INGEST_FILES,
                GateBehavior.TYPED_CONFIRM,
                ConfirmStrategy.typedForId(AgentToolsOperationCatalog.INGEST_FILES),
                SourceTier.UNTRUSTED));
    PendingAuthorizationStore pendingStore =
        new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            gatedDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var gatedHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    // No prior initialize for this session id — matches this file's existing callTool() helper,
    // which always uses a bare "s1" session id never registered via initialize.
    java.util.concurrent.atomic.AtomicReference<String> pendingId =
        new java.util.concurrent.atomic.AtomicReference<>();
    pendingChanges.subscribeTyped(event -> pendingId.set(event.pendingId()));
    callTool(gatedHandler, 20, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    assertEquals(1, pendingStore.size());
    assertNotNull(pendingId.get());
    var pending = pendingStore.peek(pendingId.get());
    assertTrue(pending.isPresent());
    assertNull(
        pending.get().requestedBy(),
        "requestedBy must stay null (not a fabricated placeholder) when the session has no"
            + " captured clientInfo");
  }

  @Test
  void toolsCall_dispatchSucceeds_createsNoPendingRecord() throws Exception {
    // Simulates the already-working durable-grant path: the trust lattice was satisfied
    // (by an existing allow-always grant) before McpToolSurface ever sees a gate exception, so
    // dispatch just succeeds. Proves the new pending-authorization wiring only activates on an
    // actual gate firing, not on every ingest call.
    OperationDispatcher successDispatcher = mock(OperationDispatcher.class);
    when(successDispatcher.dispatch(any(), any(), any()))
        .thenReturn(OperationResult.success("Indexed 1 item", Map.of()));
    PendingAuthorizationStore pendingStore = new PendingAuthorizationStore(FIXED_CLOCK, java.time.Duration.ofMinutes(5));
    PendingAuthorizationChangeRegistry pendingChanges = new PendingAuthorizationChangeRegistry();
    var surface =
        new McpToolSurface(
            List.of(new AgentToolsOperationCatalog()),
            successDispatcher,
            () -> null,
            () -> null,
            FIXED_CLOCK,
            () -> null,
            pendingStore,
            pendingChanges);
    var successHandler = new McpProtocolHandler(surface, List.of(), FIXED_CLOCK);

    String raw =
        callTool(successHandler, 11, "justsearch_ingest", "{\"paths\":[\"C:/tmp/notes\"]}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertFalse((Boolean) result.get("isError"));
    assertEquals(0, pendingStore.size());
  }

  @Test
  void toolsCall_malformedSearchArgs_rejectedAtBoundaryBeforeDispatch() throws Exception {
    // justsearch_search declares "query" as required + type "string" — a numeric query must be
    // rejected by the new boundary validation, not reach the (null, in this test) knowledge
    // lookup and fail with an unrelated "Knowledge server not available" message.
    String raw = callTool(handler, 12, "justsearch_search", "{\"query\":123}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");
    assertTrue(text.contains("Invalid arguments"), "must be the boundary-validation error: " + text);
  }

  @Test
  void toolsCall_validatorCannotRun_failsClosedWithoutDispatch() {
    // Tempdoc 949: the boundary validator's own failure (schema/args serialization throws, or
    // the validator throws) must NOT be read as "validated OK". Pre-fix, validateArgsOrNull
    // caught the exception and returned null — the caller's success value — so an unvalidated
    // argument map reached dispatch and the unchecked casts the validation exists to guard.
    // A self-referencing map makes MAPPER.writeValueAsString throw inside the validator's try,
    // which is the only way to exercise that catch from a real callTool entry.
    KnowledgeHttpApiAdapter adapter = mock(KnowledgeHttpApiAdapter.class);
    KnowledgeSearchController ctrl = mock(KnowledgeSearchController.class);
    when(ctrl.getAdapter()).thenReturn(adapter);
    var surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            dispatcher,
            () -> ctrl,
            () -> null,
            FIXED_CLOCK);
    var cyclic = new java.util.HashMap<String, Object>();
    cyclic.put("query", "x");
    cyclic.put("self", cyclic);

    Map<String, Object> result = surface.callTool("justsearch_search", cyclic, "s1", null);

    assertEquals(Boolean.TRUE, result.get("isError"), "must fail closed: " + result);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");
    assertTrue(
        text.contains("validation could not run") && text.contains("not dispatched"),
        "must be the validator-unavailable error, not a downstream failure: " + text);
    assertTrue(text.contains("INTERNAL_ERROR"), "typed as a substrate error: " + text);
    verify(adapter, never()).search(any());
    verifyNoInteractions(dispatcher);
  }

  @Test
  void toolsCall_malformedNestedFilter_rejectedAtBoundaryBeforeDispatch() throws Exception {
    // Tempdoc 655 fix pass: `filters` was previously an opaque "object" in the schema, so a
    // malformed NESTED field (path_prefix as a number instead of a string) fell through the
    // boundary validation and reached McpToolSurface#parseFilters's unchecked cast. Declaring
    // filters' nested shape closes that gap — this must now be a clean boundary-validation error,
    // not an unrelated failure surfaced from deeper in the call.
    String raw =
        callTool(
            handler, 13, "justsearch_search", "{\"query\":\"x\",\"filters\":{\"path_prefix\":123}}");

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(raw, Map.class);
    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) response.get("result");
    assertTrue((Boolean) result.get("isError"));
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    String text = (String) content.get(0).get("text");
    assertTrue(text.contains("Invalid arguments"), "must be the boundary-validation error: " + text);
  }

  // Live-verified defect (2026-07): a real client speaking the shipped MCPB stdio bridge over
  // /mcp sent `{"jsonrpc":"2.0","method":"notifications/initialized"}` (the mandatory
  // post-initialize lifecycle notification) and got back HTTP 200 with a JSON-RPC error body
  // (`{"id":null,"error":{"code":-32601,"message":"Method not found: notifications/initialized"}}`).
  // Two distinct spec violations: (1) the method wasn't recognized at all — the pre-fix switch
  // only matched the never-sent bare string "initialized"; (2) the server replied to a
  // Notification at all, which JSON-RPC 2.0 §4.1 forbids regardless of whether the method is
  // known. A spec-correct client that (correctly) expects no reply desyncs its read loop and
  // hangs — reproduced by scripts/sandbox/mcp-typed-confirm.ps1.

  @Test
  void notificationsInitialized_noErrorBody_noResponsePayload() throws Exception {
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
    when(ctx.status(anyInt())).thenReturn(ctx);

    handler.handlePost(ctx);

    // Pre-fix: this method wasn't recognized (only bare "initialized" was), so it fell into the
    // switch's default branch and called writeError -> ctx.result(...) with a -32601 body. A
    // Notification must never get a JSON-RPC reply body of any kind, error or otherwise.
    verify(ctx, never()).result(anyString());
    verify(ctx).status(202);
  }

  @Test
  void unrecognizedNotification_stillNoResponsePayload() throws Exception {
    // A notification (no "id" member) with a method the server doesn't specifically recognize.
    // Pre-fix: any unrecognized *method name* -> -32601 error body, with no branch that first
    // checked "is this a notification" before deciding to reply. JSON-RPC 2.0 §4.1's "MUST NOT
    // reply to a Notification" applies independently of whether the method is known.
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/some_future_thing\"}");
    when(ctx.status(anyInt())).thenReturn(ctx);

    handler.handlePost(ctx);

    verify(ctx, never()).result(anyString());
    verify(ctx).status(202);
  }

  @Test
  void unknownRequestWithId_stillReturnsMethodNotFound() throws Exception {
    // Regression guard for the fix above: an unknown method that IS a request (carries an "id"
    // member) must keep getting a real JSON-RPC error reply — the notification short-circuit must
    // key off "id" member presence, not off any-unrecognized-method, or this would go silent too.
    Context ctx = mock(Context.class);
    when(ctx.header("Mcp-Session-Id")).thenReturn(null);
    when(ctx.body())
        .thenReturn("{\"jsonrpc\":\"2.0\",\"id\":99,\"method\":\"totally/unknown\"}");
    ArgumentCaptor<String> resultCaptor = ArgumentCaptor.forClass(String.class);
    when(ctx.result(resultCaptor.capture())).thenReturn(ctx);
    when(ctx.contentType(anyString())).thenReturn(ctx);

    handler.handlePost(ctx);

    @SuppressWarnings("unchecked")
    Map<String, Object> response = MAPPER.readValue(resultCaptor.getValue(), Map.class);
    assertEquals(99, ((Number) response.get("id")).intValue());
    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) response.get("error");
    assertNotNull(error, "an unknown REQUEST (has id) must still get a JSON-RPC error, unlike a notification");
    assertEquals(-32601, ((Number) error.get("code")).intValue());
  }
}
