/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.core.context.EngineContext;
import io.justsearch.app.api.operations.OperationStoreException;
import io.justsearch.app.api.registry.OperationInvocationResponse;

import io.justsearch.agent.api.registry.ConfirmationRequiredException;
import io.justsearch.agent.api.registry.InvocationProvenance;
import io.justsearch.agent.api.registry.Operation;
import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.ui.api.ApiErrorHandler;
import io.justsearch.ui.api.KnowledgeSearchController;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Layer 2: Curated MCP tool surface.
 *
 * <p>Five eval-informed tools with hand-written descriptions, position-bias ordering,
 * direct service-layer dispatch, and response-level progressive disclosure hints.
 * Adapted from the eval-informed TypeScript MCP server (a tool-interface-design eval, tempdoc 366).
 *
 * <p>Separated from {@link McpProtocolHandler} (Layer 1: transport) per tempdoc 500's
 * three-layer architecture.
 */
public final class McpToolSurface {

  private static final Logger log = LoggerFactory.getLogger(McpToolSurface.class);
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final long RETRIEVE_TIMEOUT_MS = 15_000;

  // --- Tool descriptions (100-300 words, adapted from old TS server) ---

  private static final String ANSWER_DESC =
      "Get evidence from your indexed documents to answer a question. This is the primary tool "
          + "for question-answering — it retrieves relevant passages from multiple documents in "
          + "one call, assembled with source attribution, ready to use as evidence for your answer. "
          + "Much more efficient than searching and reading documents individually. "
          // Tempdoc 770 review: §F.5 removed the per-call facet round-trip, so this tool no longer
          // returns facets on either tier — the pre-770 sentence promising them (already flagged
          // by tempdoc 733:61) would send an agent looking for a field that is not delivered, and
          // `filters` is still accepted, so a fabricated value silently over-narrows retrieval.
          + "This tool does not return facets. To discover valid filter values, call "
          + "justsearch_search — it returns the top sources, categories, authors and entities for "
          + "the documents matching a query. Pass known values as filters to scope retrieval: "
          + "filters: {meta_source: [\"the verge\"], entity_persons: [\"Elon Musk\"]}. "
          + "For questions comparing what different sources report, call this tool once per source "
          + "with meta_source filters to get source-specific evidence, then synthesize. "
          + "Use justsearch_search only when you need to explore or discover what is in the index.";

  private static final String SEARCH_DESC =
      "Find and explore documents in the JustSearch index. Use this to discover what documents "
          + "exist, browse by source/category/author, or find specific files. Returns file paths, "
          + "relevance scores, and content previews. For answering questions, prefer "
          + "justsearch_answer — it retrieves assembled passages from multiple documents in one call. "
          + "Supports hybrid (default), text (BM25 keyword), and vector (semantic) search modes. "
          // Tempdoc 770 review: `queryUnderstanding` is emitted by the REST search controller
          // (KnowledgeSearchController), NOT by this tool — neither the text block nor
          // McpEvidenceProjection#searchEvidence carries it, so the pre-770 "check the
          // queryUnderstanding field" pointer named a field the MCP caller never receives. The
          // boost itself is real but conditional (KnowledgeSearchEngine: QU runs only when the
          // local model is loaded and no explicit filters were supplied), so it is stated as such.
          + "When the local AI model is loaded and no explicit filters are supplied, the system "
          + "may also detect sources, authors, and entities in your query and soft-boost matching "
          + "documents. "
          // Tempdoc 770 review: the requested facet set is 6 fields (callSearch's
          // defaultFacetFields), not the 3 the pre-770 wording named — and justsearch_answer's
          // description now redirects here for entity values, so the two must agree.
          + "When the matching documents carry them, the response also returns top facet values "
          + "(sources, categories, authors, and person/organization/location entities) to use as "
          + "filters. "
          // Tempdoc 821 §L.3, worded cause-neutrally: facetsTruncated's causes are not fixed —
          // today the maxDocsScanned cap, and 821's facets-engine work extends it to a mid-scan
          // failure too — so this clause names the effect (an incomplete scan), not a specific
          // cause. On a broad query the per-value counts (and even which values appear at all)
          // can be a lower bound rather than exact.
          + "Facet counts may be partial: when the response's facetsTruncated flag is true, the "
          + "scan did not cover every matching document, so treat the returned values and counts "
          + "as a lower-bound sample rather than an exhaustive list. "
          + "Set query_syntax: \"lucene\" for exact-phrase (\"...\") and boolean (AND/OR/NOT) "
          + "queries; the default is plain-text search. "
          + "Set detail: true to also receive per-hit ranking provenance (stage participation and "
          + "fusion-leg scores); it is omitted by default.";

  private static final String BROWSE_DESC =
      "Browse the indexed folder structure. Lists subfolders with file counts and sizes. "
          + "When a folder has no subfolders, automatically lists individual files instead. "
          + "Set list_files:true to explicitly list files in a folder. "
          + "Call with no parent_path to see top-level indexed roots.";

  private static final String INGEST_DESC =
      "Index files or directories into JustSearch. Provide absolute paths to files or folders. "
          + "Returns the number of accepted items. Folders are expanded recursively. "
          + "Use justsearch_status to check indexing progress after ingestion.";

  private static final String STATUS_DESC =
      "Get the current status of the JustSearch knowledge index. Returns document count, "
          + "queue depth, readiness state, health, and enrichment coverage "
          + "(reported as embeddingCoverage, spladeCoverage, and nerCompleted N (M pending)). "
          + "After ingesting documents, poll this to check if enrichment (embeddings, NER, SPLADE) "
          + "is complete before using entity filters or semantic search.";

  // Tempdoc 655: the single-sourced, COMPARATIVE tool-selection guidance — the one string that
  // states when to prefer the index over reading files directly. Consumed by both the connect-time
  // instructions() surface (MCP `initialize`) and the user-invoked prompt path (getStatusContext),
  // so the two cannot fork (654 "projection, not fork"). Kept minimal and honest (small-model
  // description bloat hurts adoption per ADR-0015 / tempdoc 366; and file tools genuinely are fine
  // for small/exact cases — no absolute "beats grep" claim).
  private static final String TOOL_SELECTION_GUIDANCE =
      "JustSearch maintains a local search index over the user's documents. Prefer justsearch_answer"
          + " — which retrieves and assembles cited passages from across many documents in a single"
          + " call — over reading files one-by-one when the corpus is large, when the question is"
          + " conceptual or semantic (no exact keyword or filename to match on), when it spans"
          + " multiple documents, or when you want to conserve context. For a small set of files or"
          + " an exact string / filename lookup, ordinary file tools are equally good. Use"
          + " justsearch_search to explore what is in the index, and justsearch_status for the live"
          + " index size and readiness. Both tools accept response_format; \"concise\" trims the"
          + " human-readable text block (not the structured response) and keeps every line that"
          + " reports what was elided.";

  private final List<OperationCatalog> operationCatalogs;
  private final OperationDispatcher dispatcher;
  private final java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup;
  private final java.util.function.Supplier<HeadAssembly> appFacadeLookup;
  private final Clock clock;
  // Tempdoc 501 Phase 15: optional publisher lookup for the runtime_manifest tool.
  // Null when the test-only Builder path constructed the surface without one.
  private final java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
      manifestPublisherLookup;
  // Tempdoc 655: the SAME store/broadcast the REST gate path (OperationsController) uses, so a
  // gate fired here is visible to the one approve endpoint and the one live shell signal —
  // nullable for legacy/test wiring, in which case the gate still fails closed, just without a
  // surfaced pending record (matches OperationsController's own null-tolerant pattern).
  private final io.justsearch.app.services.intent.PendingAuthorizationStore
      pendingAuthorizationStore;
  private final io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
      pendingAuthorizationChanges;
  private final List<ToolDefinition> toolDefinitions;
  private final Map<String, ToolLifecycle> lifecycleCatalog;
  // Tempdoc 655: boundary schema validation, applied uniformly to all 6 tools regardless of
  // which backend path (direct in-process call vs. Operation dispatch) ultimately serves them —
  // stateless/cache-only, so a private instance per surface is fine.
  private final io.justsearch.app.services.registry.executor.OperationInputSchemaValidator
      inputValidator =
          new io.justsearch.app.services.registry.executor.OperationInputSchemaValidator();

  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock) {
    this(operationCatalogs, dispatcher, knowledgeLookup, appFacadeLookup, clock, () -> null);
  }

  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock,
      java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
          manifestPublisherLookup) {
    this(
        operationCatalogs,
        dispatcher,
        knowledgeLookup,
        appFacadeLookup,
        clock,
        manifestPublisherLookup,
        null,
        null);
  }

  /** Canonical constructor (tempdoc 655): also wires the pending-authorization mechanism. */
  public McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock,
      java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
          manifestPublisherLookup,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore,
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
          pendingAuthorizationChanges) {
    this(
        operationCatalogs,
        dispatcher,
        knowledgeLookup,
        appFacadeLookup,
        clock,
        manifestPublisherLookup,
        pendingAuthorizationStore,
        pendingAuthorizationChanges,
        PRODUCTION_TOOL_DEFINITIONS,
        PRODUCTION_TOOL_LIFECYCLE);
  }

  /** Test seam for lifecycle projection and closed-world catalog validation. */
  McpToolSurface(List<ToolDefinition> toolDefinitions, List<ToolLifecycle> lifecycleEntries) {
    this(
        List.of(),
        null,
        () -> null,
        () -> null,
        Clock.systemUTC(),
        () -> null,
        null,
        null,
        toolDefinitions,
        lifecycleEntries);
  }

  private McpToolSurface(
      List<OperationCatalog> operationCatalogs,
      OperationDispatcher dispatcher,
      java.util.function.Supplier<KnowledgeSearchController> knowledgeLookup,
      java.util.function.Supplier<HeadAssembly> appFacadeLookup,
      Clock clock,
      java.util.function.Supplier<io.justsearch.ui.runtime.RuntimeManifestPublisher>
          manifestPublisherLookup,
      io.justsearch.app.services.intent.PendingAuthorizationStore pendingAuthorizationStore,
      io.justsearch.app.observability.operations.PendingAuthorizationChangeRegistry
          pendingAuthorizationChanges,
      List<ToolDefinition> toolDefinitions,
      List<ToolLifecycle> lifecycleEntries) {
    this.operationCatalogs = List.copyOf(operationCatalogs);
    this.dispatcher = dispatcher;
    this.knowledgeLookup = knowledgeLookup;
    this.appFacadeLookup = appFacadeLookup;
    this.clock = clock;
    this.manifestPublisherLookup =
        manifestPublisherLookup != null ? manifestPublisherLookup : () -> null;
    this.pendingAuthorizationStore = pendingAuthorizationStore;
    this.pendingAuthorizationChanges = pendingAuthorizationChanges;
    this.toolDefinitions = List.copyOf(Objects.requireNonNull(toolDefinitions));
    this.lifecycleCatalog = validateLifecycleCatalog(this.toolDefinitions, lifecycleEntries);
  }

  // =========================================================================
  // tools/list — 7 curated tools, position-bias ordered
  // =========================================================================

  public Map<String, Object> listTools() {
    return Map.of(
        "tools", toolDefinitions.stream().map(this::projectToolDefinition).toList());
  }

  /** Experimental MCP extensions advertised by the transport during initialize. */
  Map<String, Object> experimentalCapabilities() {
    return Map.of(
        "io.justsearch/tool-lifecycle", Map.of("version", TOOL_LIFECYCLE_EXTENSION_VERSION));
  }

  private static final String RUNTIME_MANIFEST_DESC =
      "Returns the redacted runtime manifest (tempdoc 501 §12.4): JSON document carrying the "
          + "current backend's identity (instanceId, pid, dataDir), lifecycle projection, "
          + "head/worker state, and AI runtime state. Same body served at "
          + "GET /api/runtime/manifest and GET /.well-known/justsearch/manifest.json — this tool "
          + "is the MCP-native surface for identity-aware caching and cross-restart detection.";

  // --- Tool schemas (tempdoc 655): named so listTools()'s visible schema and the boundary
  // validator (validateDirectDispatchArgs) provably validate against the SAME object for the 4
  // tools with no backing Operation — not two independently-authored literals that can drift. ---

  // Tempdoc 655 fix pass: the nested shape of `filters`, declared (not left as an opaque
  // "object") so a malformed nested field (e.g. path_prefix sent as a number) is caught by the
  // boundary validator instead of reaching McpToolSurface#parseFilters's unchecked casts.
  private static final Map<String, Object> FILTERS_SCHEMA = buildFiltersSchema();

  private static Map<String, Object> buildFiltersSchema() {
    var s =
        schema(
            orderedMap(
                "path_prefix", prop("string", "Restrict results to paths under this prefix"),
                "meta_source", propStringArray("Filter by source"),
                "meta_author", propStringArray("Filter by author"),
                "meta_category", propStringArray("Filter by category"),
                "entity_persons", propStringArray("Filter by person entity"),
                "entity_organizations", propStringArray("Filter by organization entity"),
                "entity_locations", propStringArray("Filter by location entity"),
                // Tempdoc 821 §3-C2: the search-scope tag, now honoured on the ANSWER path too.
                // Declared lean (F-016: schema complexity degrades small-model tool use) — one
                // string array, one sentence, same style as the entity keys above.
                "collection", propStringArray("Restrict to these collections (omit for default)")),
            List.of());
    // Keep the natural-language guidance the old opaque "object" prop carried (ADR-0015:
    // descriptions are load-bearing for small-model tool use) alongside the now-declared shape.
    s.put(
        "description",
        "Hard filters: {meta_source: [...], entity_persons: [...], path_prefix: \"...\", ...}");
    return s;
  }

  // Tempdoc 725 W2c: opt-in response density tier, shared by justsearch_answer and
  // justsearch_search. "detailed" (default) is the current full-fidelity shape; "concise" trims
  // passage/preview volume for token economics while keeping the header/coverage/degradation
  // lines that carry the elided-ness facts.
  private static final Map<String, Object> RESPONSE_FORMAT_SCHEMA =
      propEnum(
          List.of("concise", "detailed"),
          "Verbosity of the human-readable text block only; it does not change the structured"
              + " response, so a client that reads structuredContent (the common case) sees no"
              + " size difference. \"detailed\" (default) includes preview snippets and full"
              + " evidence passages. \"concise\" drops the per-hit preview line from"
              + " justsearch_search text and caps justsearch_answer text at the 3 highest-ranked"
              + " passages; the coverage, match, and header lines are kept in both modes.");

  private static final Map<String, Object> ANSWER_SCHEMA =
      schema(
          orderedMap(
              "query", prop("string", "The question to answer"),
              "top_k", prop("integer", "Number of passages to retrieve (default 5, max 20)"),
              "filters", FILTERS_SCHEMA,
              "response_format", RESPONSE_FORMAT_SCHEMA),
          List.of("query"));

  private static final Map<String, Object> SEARCH_SCHEMA =
      schema(
          orderedMap(
              "query", prop("string", "Search text"),
              "limit", prop("integer", "Max results (default 10, max 50)"),
              "mode", prop("string", "Search mode: hybrid (default), text, or vector"),
              "filters", FILTERS_SCHEMA,
              // Tempdoc 770: `detail` gates the whole per-hit ranking-provenance block. The
              // query-level search trace, excerpts, and scores are always returned in
              // structuredContent; per-hit trace/legScores (and the numeric detail sub-map inside
              // them) ship only when detail=true.
              "query_syntax",
                  propEnum(
                      List.of("simple", "lucene", "advanced"),
                      "Query syntax. \"simple\" (default) treats the query as plain text."
                          + " \"lucene\" (alias \"advanced\") enables Lucene query syntax —"
                          + " exact phrases (\"...\"), boolean operators (AND/OR/NOT), field"
                          + " qualifiers and grouping. Applies to the keyword leg; combine with"
                          + " mode: \"text\" for a pure keyword query."),
              "detail",
                  prop(
                      "boolean",
                      "Include the per-hit ranking-provenance tier (stage participation,"
                          + " fusion-leg scores, and the numeric detail sub-map). Omitted by"
                          + " default; excerpts, scores and the query-level search trace are"
                          + " returned either way."),
              "response_format", RESPONSE_FORMAT_SCHEMA),
          List.of("query"));

  // Transport metadata is removed before validating the Operation-owned public input schema.
  private static final Map<String, Object> OPERATION_KEY_SCHEMA =
      prop("string", "Optional UUIDv7 operation key. Reuse with identical input to obtain the"
          + " recorded receipt; changed input conflicts. Omit for a server-minted key.");

  private static final Map<String, Object> STATUS_SCHEMA = schema(Map.of(), List.of());

  private static final Map<String, Object> RUNTIME_MANIFEST_SCHEMA = schema(Map.of(), List.of());

  private static final Map<String, Object> OUTCOME_SCHEMA = schema(
      Map.of("operationKey", prop("string", "The original UUIDv7 operation key, never an executionId")),
      List.of("operationKey"));

  private static final String TOOL_LIFECYCLE_EXTENSION_VERSION = "1.0";

  private static final List<ToolDefinition> PRODUCTION_TOOL_DEFINITIONS =
      List.of(
          new ToolDefinition(
              "justsearch_answer", ANSWER_DESC, ANSWER_SCHEMA, Map.of("readOnlyHint", true)),
          new ToolDefinition(
              "justsearch_search", SEARCH_DESC, SEARCH_SCHEMA, Map.of("readOnlyHint", true)),
          new ToolDefinition(
              "justsearch_browse",
              BROWSE_DESC,
              schema(
                  orderedMap(
                      "parent_path",
                          prop("string", "Folder path to browse (empty for top-level roots)"),
                      "list_files", prop("boolean", "List individual files instead of subfolders"),
                      "operationKey", OPERATION_KEY_SCHEMA),
                  List.of()),
              Map.of("readOnlyHint", true)),
          new ToolDefinition(
              "justsearch_ingest",
              INGEST_DESC,
              schema(
                  orderedMap(
                      "paths", propStringArray("Absolute file or folder paths to index"),
                      // Tempdoc 811 (C-2a) — optional collection tag. Server-side validation in
                      // IngestTool is the guard; this schema only advertises the argument.
                      "collection",
                          prop(
                              "string",
                              "Optional collection tag for the indexed documents. Omit to inherit"
                                  + " the containing indexed root's collection, or 'mcp-ingest'"
                                  + " for paths outside every indexed root. The app-internal"
                                  + " collections 'justsearch-help' and 'agent-history' are"
                                  + " rejected."),
                      "operationKey", OPERATION_KEY_SCHEMA),
                  List.of("paths")),
              orderedMap("readOnlyHint", false, "idempotentHint", true)),
          new ToolDefinition(
              "justsearch_status", STATUS_DESC, STATUS_SCHEMA, Map.of("readOnlyHint", true)),
          new ToolDefinition(
              "justsearch_runtime_manifest",
              RUNTIME_MANIFEST_DESC,
              RUNTIME_MANIFEST_SCHEMA,
              Map.of("readOnlyHint", true)),
          new ToolDefinition(
              "justsearch_operation_outcome",
              "Read the recorded outcome for an operation key after a lost response or restart. "
                  + "Returns accepted, running, complete, failed, unknown or expired with the retained "
                  + "history boundary and available unit counts. Unknown means no acceptance record "
                  + "and no effect; expired means the retained history cannot answer. This read never "
                  + "starts or retries work. Use the original UUIDv7 operationKey, not executionId. "
                  + "Timestamps are UTC epoch milliseconds. The answer matches "
                  + "GET /api/operation-history/{operationKey} and contains metadata only.",
              OUTCOME_SCHEMA,
              Map.of("readOnlyHint", true)));

  /** No production tool is deprecated; fake lifecycle rows are injected only by focused tests. */
  private static final List<ToolLifecycle> PRODUCTION_TOOL_LIFECYCLE = List.of();

  // =========================================================================
  // tools/call — route to service layer
  // =========================================================================

  @SuppressWarnings("unchecked")
  public Map<String, Object> callTool(String name, Map<String, Object> arguments, String sessionId, EngineContext engineContext) {
    return callTool(name, arguments, sessionId, null, engineContext);
  }

  /**
   * Tempdoc 655: {@code requestedBy} is the calling MCP client's self-reported name (captured
   * from the {@code initialize} handshake's {@code clientInfo}, resolved by {@link
   * McpProtocolHandler} from its session map) — display-only, threaded through to a pending
   * authorization if this call ends up gated, so the approval ceremony can show who's asking.
   * Never used for any trust decision.
   */
  @SuppressWarnings("unchecked")
  public Map<String, Object> callTool(
      String name, Map<String, Object> arguments, String sessionId, String requestedBy, EngineContext engineContext) {
    // Tempdoc 655: validate every tool's arguments against its declared schema at the MCP
    // boundary, before dispatch — independent of which backend path (direct in-process call vs.
    // Operation dispatch) ultimately serves the tool. Previously only browse/ingest (the two
    // Operation-backed tools) got real validation via the executor's own pipeline downstream;
    // the other 4 skipped it entirely, so a wrong-typed argument surfaced as an unhandled cast
    // exception rather than a clean schema error.
    Map<String, Object> schemaForDirectDispatch =
        switch (name) {
          case "justsearch_answer" -> ANSWER_SCHEMA;
          case "justsearch_search" -> SEARCH_SCHEMA;
          case "justsearch_status" -> STATUS_SCHEMA;
          case "justsearch_runtime_manifest" -> RUNTIME_MANIFEST_SCHEMA;
          case "justsearch_operation_outcome" -> OUTCOME_SCHEMA;
          default -> null;
        };
    if (schemaForDirectDispatch != null) {
      Map<String, Object> invalid = validateArgsOrNull(name, schemaForDirectDispatch, arguments);
      if (invalid != null) {
        return invalid;
      }
    }
    return switch (name) {
      case "justsearch_answer" -> callAnswer(arguments, engineContext);
      case "justsearch_search" -> callSearch(arguments, engineContext);
      case "justsearch_browse" ->
          callOperation("core.browse-folders", arguments, requestedBy, engineContext);
      case "justsearch_ingest" ->
          callOperation("core.ingest-files", arguments, requestedBy, engineContext);
      case "justsearch_status" -> callStatus(engineContext);
      case "justsearch_runtime_manifest" -> callRuntimeManifest();
      case "justsearch_operation_outcome" -> callOperationOutcome(arguments);
      default -> unknownToolWithSuggestions(name);
    };
  }

  /**
   * Tempdoc 655: validate {@code arguments} against {@code schema} using the same {@link
   * io.justsearch.app.services.registry.executor.OperationInputSchemaValidator} the Operations
   * pipeline uses. Returns a clean MCP tool error (never {@code null} on failure) instead of
   * letting a malformed argument reach a raw unchecked cast further down; returns {@code null}
   * ONLY when the args were actually validated and passed.
   *
   * <p>Fails closed (tempdoc 949): if the validator itself cannot run — serialization of the
   * schema/args maps throws, or the validator throws — the call is NOT dispatched. The previous
   * behaviour returned {@code null} here, which the caller reads as "validated OK", so an
   * unvalidated argument map reached dispatch and the unchecked casts this validation exists to
   * guard. A substrate bug is still a reason to refuse the call, not to skip the guard; the
   * {@link #callOperation} path already behaves this way (a throw there escapes before dispatch).
   */
  private Map<String, Object> validateArgsOrNull(
      String cacheKey, Map<String, Object> schema, Map<String, Object> arguments) {
    try {
      String schemaJson = MAPPER.writeValueAsString(schema);
      String argsJson = MAPPER.writeValueAsString(arguments == null ? Map.of() : arguments);
      return inputValidator
          .validate(cacheKey, schemaJson, argsJson)
          .<Map<String, Object>>map(
              result -> errorContent("Invalid arguments for " + cacheKey + ": " + result.message(),
                  ApiErrorCode.INVALID_REQUEST))
          .orElse(null);
    } catch (Exception e) {
      log.warn("MCP boundary validation failed to run for {}: {}", cacheKey, e.getMessage());
      return errorContent(
          "Argument validation could not run for "
              + cacheKey
              + "; the call was not dispatched. Retry, and report this if it persists.",
          ApiErrorCode.INTERNAL_ERROR);
    }
  }

  private static final List<String> KNOWN_TOOLS =
      PRODUCTION_TOOL_DEFINITIONS.stream().map(ToolDefinition::name).toList();

  // =========================================================================
  // RuntimeManifest: redacted JSON snapshot of the producer-published manifest
  // (tempdoc 501 Phase 15). Returns the same shape served at
  // GET /api/runtime/manifest — sessionToken stripped.
  // =========================================================================

  private Map<String, Object> callOperationOutcome(Map<String, Object> arguments) {
    HeadAssembly facade = appFacadeLookup.get();
    if (facade == null) return errorContent("Operation history is unavailable", ApiErrorCode.SERVICE_UNAVAILABLE);
    try {
      var outcome = facade.operationOutcome((String) arguments.get("operationKey"));
      return Map.of("content", List.of(Map.of("type", "text", "text", MAPPER.writeValueAsString(outcome))),
          "structuredContent", outcome);
    } catch (OperationStoreException e) {
      var failure = OperationInvocationResponse.fromStoreFailure(e);
      return errorContent(Map.of("error", failure.message(), "errorCode", failure.errorCode(),
          "errorClass", failure.errorClass(), "retryable", failure.retryable()));
    } catch (Exception e) {
      log.warn("MCP operation outcome query failed", e);
      return toolFailureContent("Operation outcome query", e);
    }
  }

  private Map<String, Object> callRuntimeManifest() {
    io.justsearch.ui.runtime.RuntimeManifestPublisher publisher = manifestPublisherLookup.get();
    if (publisher == null) {
      return errorContent("Runtime manifest publisher not wired");
    }
    io.justsearch.app.api.runtime.RuntimeManifest current = publisher.current();
    if (current == null) {
      return errorContent("Runtime manifest not yet published");
    }
    io.justsearch.app.api.runtime.RuntimeManifest publicView = current.publicProjection();
    try {
      String json =
          new ObjectMapper()
              .writerWithDefaultPrettyPrinter()
              .writeValueAsString(publicView);
      Map<String, Object> content = new LinkedHashMap<>();
      content.put("content", List.of(Map.of("type", "text", "text", json)));
      content.put("structuredContent", publicView);
      return content;
    } catch (Exception e) {
      log.warn("MCP runtime manifest serialization failed", e);
      return toolFailureContent("Runtime manifest", e);
    }
  }

  private Map<String, Object> unknownToolWithSuggestions(String name) {
    var alts = io.justsearch.agent.api.registry.CatalogMatcher.defaultMatcher()
        .findAlternatives(name, KNOWN_TOOLS, s -> s, 3);
    if (alts.isEmpty()) return errorContent("Unknown tool: " + name);
    var suggestions = alts.stream().map(a -> a.refId()).toList();
    var msg = "Unknown tool: " + name
        + ". Did you mean: " + String.join(", ", suggestions) + "?";
    var content = new LinkedHashMap<String, Object>(errorContent(msg));
    content.put("suggestions", suggestions);
    return content;
  }

  // =========================================================================
  // Answer: direct in-process via DocumentService.retrieveContext()
  // =========================================================================

  @SuppressWarnings("unchecked")
  private Map<String, Object> callAnswer(Map<String, Object> args, EngineContext engineContext) {
    HeadAssembly facade = appFacadeLookup.get();
    if (facade == null || facade.workers().documents() == null) {
      return errorContent(KNOWLEDGE_SERVER_UNAVAILABLE_MESSAGE, ApiErrorCode.SERVICE_UNAVAILABLE);
    }
    try {
      String query = (String) args.getOrDefault("query", "");
      int topK = ((Number) args.getOrDefault("top_k", 5)).intValue();
      Map<String, Object> rawFilters = (Map<String, Object>) args.get("filters");
      boolean concise = "concise".equals(args.get("response_format"));

      RetrieveContextParams params =
          new RetrieveContextParams(
              query,
              Set.of(),
              Math.min(topK, 20),
              4096,
              toStringList(rawFilters, "entity_persons"),
              toStringList(rawFilters, "entity_organizations"),
              toStringList(rawFilters, "entity_locations"),
              RetrieveContextParams.TimeRange.UNSET,
              false,
              rawFilters != null ? (String) rawFilters.getOrDefault("path_prefix", "") : "",
              List.of(),
              true,
              // Tempdoc 725 W2b: LABELED is the only format the Worker's ContextBudgeter actually
              // renders — RagContextOps never reads contextFormat off the wire, and ContextBudgeter
              // has no XML/PLAIN branch at all (it unconditionally emits "[n] label\n" +
              // content). Requesting XML here was a dead orphan (tempdoc 725 orphan #5): the param
              // was serialized onto the gRPC request correctly, but nothing downstream consumed it,
              // so every caller has always received LABELED regardless of what it asked for.
              // Requesting the format that is actually delivered keeps this call site honest.
              RetrieveContextParams.ContextFormat.LABELED,
              toStringList(rawFilters, "meta_source"),
              toStringList(rawFilters, "meta_author"),
              toStringList(rawFilters, "meta_category"),
              RetrieveContextParams.TimeRange.UNSET,
              false,
              List.of(),
              // Tempdoc 821 §3-C2 — the collection scope reaches the Lucene filter from here.
              toStringList(rawFilters, "collection"));

      DocumentService.ContextResult result =
          facade
              .workers()
              .documents()
              .retrieveContext(params, engineContext)
              .toCompletableFuture()
              .get(RETRIEVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

      // Tempdoc 735 W6: every response fact — header counts, hints, facets — computed ONCE by the
      // content-model builder, then consumed by both the text renderer and the structured
      // renderer, so the two tiers cannot silently diverge (735 G3).
      // Tempdoc 789 Phase 2: framing flags resolved once per call, defaulting to OFF when the
      // config store is not initialized — an unconfigured process delivers exactly the pre-789 text.
      McpDeliveryFraming.Settings framing = McpDeliveryFraming.resolveSettings();
      McpAnswerResponseContent content = buildAnswerContent(result, query, framing, engineContext);
      String text = renderAnswerText(result, content, concise, query);

      // Tempdoc 658/735: the citation provenance + quality signals, PLUS the tier-equivalence
      // fields (hints/facets/coverage/truncated), ride the structuredContent channel.
      return Map.of(
          "content",
          List.of(Map.of("type", "text", "text", text)),
          "structuredContent",
          McpEvidenceProjection.answerEvidence(result, content),
          "isError",
          false);
    } catch (Exception e) {
      log.warn("MCP answer failed", e);
      return toolFailureContent("Answer", e);
    }
  }

  // Tempdoc 735 W6: exact pre-735 wording of each tool's own enrichment-hint sentence — kept
  // distinct per tool rather than unified, since unifying the wording would be a text change this
  // increment does not make (see McpAnswerResponseContent's class-level note).
  private static final String ANSWER_ENRICHMENT_MESSAGE =
      "Enrichment in progress — semantic search and entity filters may be limited until complete."
          + " Check justsearch_status.";
  private static final String SEARCH_ENRICHMENT_MESSAGE =
      "Enrichment in progress — semantic search and entity filters may be limited. Check"
          + " justsearch_status.";

  /**
   * Tempdoc 735 W6: computes every {@code justsearch_answer} response fact ONCE — passage/document
   * counts, the comparative/enrichment/zero-result hints, and the facet sidecar — so the text
   * renderer ({@link #renderAnswerText}) and the structured renderer ({@link
   * McpEvidenceProjection#answerEvidence(io.justsearch.app.api.DocumentService.ContextResult,
   * McpAnswerResponseContent)}) derive from the same instance instead of two independent
   * computations (the pre-735 shape: the facet sidecar and enrichment hint were appended directly
   * to the text StringBuilder, invisible to structuredContent).
   */
  McpAnswerResponseContent buildAnswerContent(
      DocumentService.ContextResult result, String query, McpDeliveryFraming.Settings framing, EngineContext engineContext) {
    // Tempdoc 725 W2a: N/M come from the in-hand result: N is the citation count (== chunksUsed;
    // both are derived from the same worker-reported chunk list —
    // RemoteDocumentService.mapRetrieveContextResponse), which equals the number of rendered
    // "[n] label" sections in result.context() for both the chunk-RAG and virtual-chunk-fallback
    // paths.
    //
    // Tempdoc 725 review fix: RemoteDocumentService.retrieveContextFallback (FULLTEXT_FALLBACK
    // path, gRPC-failure catch) returns citations=List.of() with a non-blank context and
    // populated sections()/docsUsed() — citations is a chunk-RAG-only concept the full-document
    // fallback never populates. Deriving N/M from citations().size() there always reads 0/0
    // above real evidence. When citations is empty but context is non-blank, derive counts from
    // ContextSection (sourceLabel/content/truncated/sectionIndex/chunkIndex) instead.
    long passages;
    long distinctDocs;
    if (result.citations().isEmpty() && !result.context().isBlank()) {
      if (!result.sections().isEmpty()) {
        passages = result.sections().size();
        distinctDocs =
            result.sections().stream()
                .map(DocumentService.ContextSection::sourceLabel)
                .filter(label -> !label.isBlank())
                .distinct()
                .count();
        if (distinctDocs == 0) {
          distinctDocs = Math.max(result.docsUsed(), 1);
        }
      } else {
        // Sections absent too (not observed from retrieveContextFallback today, but defend
        // against a future producer that populates context without sections): fall back to
        // docsUsed, assuming one passage per document.
        distinctDocs = Math.max(result.docsUsed(), 1);
        passages = distinctDocs;
      }
    } else {
      passages = result.citations().size();
      distinctDocs =
          result.citations().stream()
              .map(DocumentService.ContextCitation::parentDocId)
              .filter(id -> !id.isBlank())
              .distinct()
              .count();
    }

    // Tempdoc 655: comparative response hint, keyed on DISTINCT cited documents — see
    // comparativeAnswerHint for why chunksFound cannot back a "multiple documents" claim.
    String comparativeHint = comparativeAnswerHint(result.citations());
    String enrichmentHintText = enrichmentHint(ANSWER_ENRICHMENT_MESSAGE, engineContext);
    String zeroResultHint =
        result.chunksFound() == 0
            ? "No results. Try different terms or check justsearch_status."
            : null;

    List<String> hints = new ArrayList<>();
    if (!comparativeHint.isEmpty()) hints.add(comparativeHint);
    if (enrichmentHintText != null) hints.add(enrichmentHintText);
    if (zeroResultHint != null) hints.add(zeroResultHint);

    // Tempdoc 789 Phase 2 (F2): the evidence-not-answer header — the one framing the charter applies
    // to justsearch_answer.
    String evidenceHeader =
        framing.evidenceNotAnswerEnabled()
            ? McpDeliveryFraming.answerEvidenceHeader(passages, distinctDocs)
            : null;

    return new McpAnswerResponseContent(
        passages,
        distinctDocs,
        result.contextTruncated(),
        comparativeHint,
        enrichmentHintText,
        zeroResultHint,
        hints,
        evidenceHeader);
  }

  /**
   * Tempdoc 735 W6: the {@code justsearch_answer} text renderer — reproduces the pre-0.4.0
   * StringBuilder rendering byte-for-byte (golden-pinned by {@code McpTierEquivalenceGoldenTest}),
   * now reading every fact from {@code content} (and {@code result} for the fields the content
   * model does not carry, e.g. {@code retrievalMode} and the {@code QualitySignals} block) instead
   * of recomputing them.
   */
  static String renderAnswerText(
      DocumentService.ContextResult result,
      McpAnswerResponseContent content,
      boolean concise,
      String query) {
    var sb = new StringBuilder();

    // Tempdoc 789 Phase 2 (F2): the evidence-not-answer header, above the pre-existing "Evidence
    // pack" line. Null (and so absent, byte-for-byte) unless the framing is enabled.
    if (content.evidenceHeader() != null) {
      sb.append(content.evidenceHeader()).append("\n\n");
    }

    // Tempdoc 725 W2a: a self-describing header, stated once, ahead of the passages — this pack
    // is retrieved evidence, not a synthesized answer.
    sb.append("Evidence pack: ")
        .append(content.passages())
        .append(" passages from ")
        .append(content.distinctDocs())
        .append(" documents (retrieval mode: ")
        .append(result.retrievalMode())
        .append("). No synthesized answer is included.");
    if (content.contextTruncated()) {
      sb.append(" Context was truncated to fit limits.");
    }
    // Tempdoc 731 I6a: a descriptive pack-selection fact — no thresholds, no judgment words —
    // stated only when retrieveContext actually populated quality signals. chunksConsidered() >
    // 0 is the presence test: the call site's 9-arg ContextResult constructor defaults quality
    // to QualitySignals.EMPTY (all-zero, e.g. the FULLTEXT_FALLBACK path, which never computes
    // real signals at all), and the chunk-RAG path's own chunksConsidered is the raw candidate
    // hit count before budgeting — zero only when literally no candidates were found. Either way
    // chunksConsidered == 0 means there is nothing honest to report, never a "0 of 0" placeholder.
    DocumentService.QualitySignals qualitySignals = result.quality();
    if (qualitySignals.chunksConsidered() > 0) {
      sb.append(" Pack selection: ")
          .append(qualitySignals.chunksIncluded())
          .append(" of ")
          .append(qualitySignals.chunksConsidered())
          .append(" candidate passages (retrieval coverage ")
          .append(String.format("%.2f", qualitySignals.retrievalCoverage()))
          .append(").");
    }
    sb.append("\n\n");

    if (result.context() != null && !result.context().isBlank()) {
      sb.append(
          concise
              ? buildConciseAnswerText(result)
              : McpSearchResultFormatter.stripControlChars(result.context()));
    } else {
      sb.append("No relevant passages found for: ").append(query);
    }

    // One human-readable quality line, sourced directly from `result` (the same values the
    // structured `quality` payload projects; tempdoc 658 orphan teardown / 735 single-source).
    sb.append("\n\n--- Quality ---\n");
    sb.append("Sources found: ")
        .append(result.chunksFound())
        .append(", coverage ")
        .append(String.format("%.2f", (double) result.quality().retrievalCoverage()))
        .append(", mode ")
        .append(result.retrievalMode())
        .append("\n");
    if (content.contextTruncated()) sb.append("Note: context was truncated to fit token budget.\n");

    // Tempdoc 655: comparative response hint (placed in the RESPONSE, re-read each turn, per
    // tempdoc 366's finding that descriptions are forgotten by turn 5).
    if (!content.comparativeHint().isEmpty()) {
      sb.append(content.comparativeHint()).append("\n");
    }

    // Enrichment hint
    if (content.enrichmentHint() != null) {
      sb.append("\nHint: ").append(content.enrichmentHint()).append("\n");
    }

    // Zero-result hint
    if (content.zeroResultHint() != null) {
      sb.append("\nHint: ").append(content.zeroResultHint());
    }

    return sb.toString();
  }

  /**
   * Tempdoc 725 W2c: concise-mode passage rendering — caps at the 3 highest-rank sections
   * (sections are already rank-ordered by the Worker's chunk assembly) and trims each to ~600
   * chars, appending the W1 truncation-remedy suffix when a passage is cut. Falls back to a
   * single trimmed window of the assembled context when the result carries no structured
   * sections (defensive: every production retrieval path populates them alongside a non-blank
   * context, but a section-less result should still render something in concise mode).
   */
  private static String buildConciseAnswerText(DocumentService.ContextResult result) {
    List<DocumentService.ContextSection> sections = result.sections();
    if (sections.isEmpty()) {
      McpSearchResultFormatter.Window window =
          McpSearchResultFormatter.windowStartingAt(result.context(), 0, 600);
      String text = McpSearchResultFormatter.stripControlChars(window.text());
      return window.truncated() ? text + McpSearchResultFormatter.TRUNCATION_REMEDY : text;
    }
    var sb = new StringBuilder();
    int shown = Math.min(3, sections.size());
    for (int i = 0; i < shown; i++) {
      DocumentService.ContextSection section = sections.get(i);
      if (i > 0) sb.append(DocumentService.SECTION_SEPARATOR);
      McpSearchResultFormatter.Window window =
          McpSearchResultFormatter.windowStartingAt(section.content(), 0, 600);
      // Same "[n] label\n" header the assembled context carries (ContextBudgeter.sectionHeader,
      // tempdoc 822 §3a) — concise re-renders the sections detailed mode passes through verbatim,
      // so the two densities must not disagree about a section's ordinal.
      sb.append('[')
          .append(section.sectionIndex() + 1)
          .append("] ")
          .append(section.sourceLabel())
          .append('\n')
          .append(McpSearchResultFormatter.stripControlChars(window.text()));
      if (window.truncated()) sb.append(McpSearchResultFormatter.TRUNCATION_REMEDY);
    }
    return sb.toString();
  }

  /**
   * Tempdoc 655: the comparative answer hint, keyed on DISTINCT cited documents — the only honest
   * basis for a "spanning multiple documents" claim. {@code chunksFound} cannot back it: it counts
   * chunks (many per document, and includes found-but-unused chunks — e.g. the virtual-chunk fallback
   * reports every sub-chunk across every document), and it stays &gt; 1 even on a bare full-text dump
   * where no chunk assembly happened. Citations carry {@code parentDocId} and are empty unless real
   * chunk assembly occurred, so counting distinct parentDocId is correct and self-suppressing on the
   * fallback path. ({@code docsUsed} is unusable here — the rich-params retrieve path reports it as 0
   * regardless; logged to observations.) Package-private + pure so it is unit-tested directly, without
   * the live retrieve mock chain.
   */
  static String comparativeAnswerHint(List<DocumentService.ContextCitation> citations) {
    if (citations == null) return "";
    long distinctDocs =
        citations.stream().map(c -> c.parentDocId()).filter(id -> !id.isBlank()).distinct().count();
    if (distinctDocs > 1) {
      return "Assembled evidence from "
          + distinctDocs
          + " documents in a single retrieval call — for a question spanning multiple documents this"
          + " is fewer steps than locating and reading each file.";
    }
    return "";
  }

  // =========================================================================
  // Search: direct via KnowledgeHttpApiAdapter
  // =========================================================================

  @SuppressWarnings("unchecked")
  private Map<String, Object> callSearch(Map<String, Object> args, EngineContext engineContext) {
    KnowledgeSearchController ctrl = knowledgeLookup.get();
    if (ctrl == null) return errorContent(KNOWLEDGE_SERVER_UNAVAILABLE_MESSAGE, ApiErrorCode.SERVICE_UNAVAILABLE);
    try {
      KnowledgeHttpApiAdapter adapter = ctrl.getAdapter();
      String query = (String) args.getOrDefault("query", "");
      int limit = ((Number) args.getOrDefault("limit", 10)).intValue();
      String mode = (String) args.getOrDefault("mode", "hybrid");
      // Tempdoc 658/770: the opt-in `detail` arg maps to the request `debug` flag (→
      // include_detail), which gates the per-hit numeric detail sub-map upstream in the engine; it
      // ALSO gates the whole per-hit ranking-provenance block (trace/legScores) at the projection.
      Boolean detail = (args.get("detail") instanceof Boolean b) ? b : null;
      // Tempdoc 770 §D: the engine's query-syntax lever (SearchPipelinePresets#parseQuerySyntax-
      // OrDefault), reachable from the agent surface now that the schema declares it.
      String querySyntax = (args.get("query_syntax") instanceof String s) ? s : null;
      // Tempdoc 725 W2c: concise mode omits the Preview line only — rank/title/score, Path, and
      // Matched/Match-basis lines (plus the summary/degradation/coverage lines below the loop) all
      // carry facts, not bulk, so they stay in both response densities.
      boolean concise = "concise".equals(args.get("response_format"));

      KnowledgeSearchRequest.Filters filters =
          parseFilters((Map<String, Object>) args.get("filters"));

      var defaultFacetFields =
          List.of(
              new KnowledgeSearchRequest.FieldSpec("meta_source", 5),
              new KnowledgeSearchRequest.FieldSpec("meta_category", 5),
              new KnowledgeSearchRequest.FieldSpec("meta_author", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_persons_raw", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_organizations_raw", 5),
              new KnowledgeSearchRequest.FieldSpec("entity_locations_raw", 5));
      var facets = new KnowledgeSearchRequest.Facets(true, null, defaultFacetFields);

      // Tempdoc 725 W1: request excerpt windows so the preview can anchor on the actual match
      // instead of a blind head-of-field truncation (see buildHitPreview below).
      KnowledgeSearchRequest req =
          new KnowledgeSearchRequest(
              query, Math.min(limit, 50), mode, null, null, null, filters, null, facets,
              querySyntax, Boolean.TRUE, detail, null);
      KnowledgeSearchResponse resp = adapter.search(req, engineContext);

      // Tempdoc 775 §E/§C: the delivery governor degrades the WHOLE assembled tool result (the
      // human-readable text block + the structuredContent channel + envelope) deterministically at
      // the 770 §E.3 client truncation cliff — which operates on the whole result, not the
      // structured tier alone (a fat text block can push the wire payload over the cliff while
      // structuredContent stays under any structured-only budget). The view re-renders both tiers
      // from the surviving results at each degradation step, so text and structured never diverge:
      // provenance stripped first (per-hit trace/legScores, projected only under `detail`), then
      // whole tail results dropped lowest-ranked-first, never truncating a result or span mid-way.
      // Tempdoc 735 W6: within a single render every response fact is computed ONCE by the
      // content-model builder and consumed by both renderers, so the two tiers cannot diverge.
      boolean includeDetail = Boolean.TRUE.equals(detail);
      // Tempdoc 789 Phase 2: framing flags resolved once per call (OFF when the config store is not
      // initialized). Resolved OUTSIDE the governor's view lambda so every degradation step renders
      // under the same framing decision. The index doc count backing F3's coverage clause is read
      // once, and only when F3 is enabled — an unconfigured or F3-off process makes no extra call.
      McpDeliveryFraming.Settings framing = McpDeliveryFraming.resolveSettings();
      long indexedDocs = framing.calibratedAbsenceEnabled() ? indexedDocCount(engineContext) : -1L;
      // Tempdoc 771 item (b): carriage settings resolved once per call, outside the governor's view
      // lambda for the same reason the framing flags are — every degradation step renders under one
      // carriage decision, so the governor's re-renders cannot disagree about delivered content.
      McpEntityCarriage.Settings carriage = McpEntityCarriage.resolveSettings();
      McpDeliveryGovernor.ResultView view =
          (keep, includeProvenance) -> {
            KnowledgeSearchResponse sub =
                keep >= resp.results().size() ? resp : truncateResults(resp, keep);
            McpSearchResponseContent c = buildSearchContent(sub, args, framing, indexedDocs, carriage, engineContext);
            String t = renderSearchText(sub, c, concise);
            Map<String, Object> structured =
                McpEvidenceProjection.searchEvidence(sub, c, includeProvenance);
            return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", t)),
                "structuredContent",
                structured,
                "isError",
                false);
          };
      return McpDeliveryGovernor.govern(
          resp.results().size(), includeDetail, resolveDeliveryBudgetBytes(), MAPPER, view);
    } catch (Exception e) {
      // The AGENT-facing message (below) keeps the query — the agent sent it. This SERVER log does
      // not: a rejected LUCENE-syntax search surfaces a Lucene ParseException whose message quotes
      // the query verbatim, and the Engine log is bundled into the diagnostics export. Full detail
      // stays available at TRACE, matching SearchExecutor:160-168's deliberate split.
      log.warn("MCP search failed: {}: {}", e.getClass().getSimpleName(), withoutQuotedQuery(e.getMessage()));
      log.trace("MCP search failure detail", e);
      return toolFailureContent("Search", e);
    }
  }

  /**
   * Strips the quoted user query out of an error message before it reaches a server-side log.
   *
   * <p>Lucene's {@code ParseException} renders as {@code Cannot parse '<query>': Encountered ...},
   * which the Worker propagates as the {@code INVALID_ARGUMENT} description. Everything between the
   * first and last quote is replaced (over-redacting is the safe direction) and the result is
   * length-capped; the diagnostic shape survives. The agent's own copy is untouched.
   */
  static String withoutQuotedQuery(String message) {
    if (message == null || message.isBlank()) {
      return "(no message)";
    }
    int first = message.indexOf('\'');
    int last = message.lastIndexOf('\'');
    String out =
        (first >= 0 && last > first)
            ? message.substring(0, first + 1) + "[REDACTED]" + message.substring(last)
            : message;
    return out.length() > 200 ? out.substring(0, 200) + "..." : out;
  }

  /**
   * Tempdoc 775 §E: the delivery governor's serialized-JSON budget in bytes, read from the same
   * config machinery other search deliverables use ({@code search.mcp_delivery.budget_bytes},
   * default 45,000 — a margin under the lowest characterized 770 §E.3 truncation cliff at 46,617;
   * {@code 0} disables the governor). Resolved from the global {@link
   * io.justsearch.configuration.resolved.ConfigStore} snapshot, falling back to the default when the
   * store is not yet initialized (test/early-boot paths) so the governor is always safe to call.
   */
  private static int resolveDeliveryBudgetBytes() {
    io.justsearch.configuration.resolved.ConfigStore store =
        io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    if (store == null) {
      return io.justsearch.configuration.resolved.ResolvedConfig.Search.DEFAULT_MCP_DELIVERY_BUDGET_BYTES;
    }
    return store.get().search().mcpDeliveryBudgetBytes();
  }

  /**
   * Tempdoc 775 §E: a copy of {@code resp} carrying only its top {@code keep} results, used by the
   * delivery governor to re-render both tiers when it drops whole tail results. All other fields
   * (totalHits/matchCount/trace/facets/...) are preserved verbatim, so {@code coverage.shown} and
   * the text truncation line honestly report the reduced delivered count against the unchanged
   * total.
   */
  private static KnowledgeSearchResponse truncateResults(KnowledgeSearchResponse resp, int keep) {
    List<KnowledgeSearchResponse.Hit> sub =
        new ArrayList<>(resp.results().subList(0, Math.max(0, Math.min(keep, resp.results().size()))));
    return new KnowledgeSearchResponse(
        resp.totalHits(),
        resp.matchCount(),
        resp.tookMs(),
        sub,
        resp.nextCursor(),
        resp.facets(),
        resp.facetsTruncated(),
        resp.entityFacetVariants(),
        resp.indexCapabilities(),
        resp.queryUnderstanding(),
        resp.filterNormalization(),
        resp.searchTrace(),
        resp.appliedFilters());
  }

  /**
   * Tempdoc 735 W6: computes every {@code justsearch_search} response fact ONCE — per-hit
   * matched-terms/matched-fields/preview, response-level hints, and the raw facets map — so the
   * text renderer ({@link #renderSearchText}) and the structured renderer ({@link
   * McpEvidenceProjection#searchEvidence(KnowledgeSearchResponse, McpSearchResponseContent)})
   * derive from the same instance instead of two independent {@code filterInformative} calls (the
   * pre-735 duplication).
   */
  McpSearchResponseContent buildSearchContent(
      KnowledgeSearchResponse resp,
      Map<String, Object> args,
      McpDeliveryFraming.Settings framing,
      long indexedDocs,
      McpEntityCarriage.Settings carriage, EngineContext engineContext) {
    // Tempdoc 789 Phase 2 (F1): the entity vocabulary comes from the facet snapshot this response
    // already carries — no new query path, no query-time NER (charter: prefer existing fields).
    // Empty (so F1 emits nothing) when the framing is off or the response carries no entity facets.
    Map<String, Long> entityVocabulary =
        framing.continuationEnabled()
            ? McpDeliveryFraming.entityVocabulary(resp.facets())
            : Map.of();
    String query = (String) args.getOrDefault("query", "");
    int continuationsEmitted = 0;

    List<McpSearchResponseContent.HitContent> hits = new ArrayList<>();
    if (resp.results() != null) {
      int rank = 1;
      for (var hit : resp.results()) {
        String title = hit.fields().getOrDefault("title", "");
        String path = hit.fields().getOrDefault("path", "");
        // Tempdoc 725 W1: informative-term-anchored preview + matched-term extraction, computed
        // once regardless of response_format — the text renderer decides whether to show the
        // preview line; the facts themselves are always available for structuredContent.
        String preview = buildHitPreview(hit);
        List<KnowledgeSearchResponse.MatchSpan> informative =
            McpSearchResultFormatter.filterInformative(hit.matchSpans());
        List<String> matchedTerms =
            informative.isEmpty()
                ? List.of()
                : McpSearchResultFormatter.informativeTerms(informative);
        List<String> matchedFields =
            hit.matchedFields() == null ? List.of() : hit.matchedFields();
        // Tempdoc 771 item (b): the entity-carriage line, computed BEFORE the F1 continuation so
        // the two compose — carriage puts the document's buried entity names into delivered text,
        // and F1 may then mark one of them as a possible intermediate fact. Without carriage F1 can
        // only ever mark entities the excerpt window happened to include, which on long documents is
        // the 45%-of-successful-retrievals case 771 §E measured.
        String entityCarriage =
            carriage.enabled()
                ? McpEntityCarriage.line(
                    McpEntityCarriage.deliveredText(hit, preview),
                    hit.fields(),
                    carriage.maxChars())
                : null;
        // Tempdoc 789 Phase 2 (F1): the continuation line, computed against the text this hit
        // actually delivers (its preview, plus any carriage line) so the line never names an entity
        // the agent was not shown.
        String continuation = null;
        if (!entityVocabulary.isEmpty()
            && continuationsEmitted < McpDeliveryFraming.MAX_CONTINUATION_LINES) {
          String deliveredText =
              entityCarriage == null ? preview : preview + "\n" + entityCarriage;
          continuation =
              McpDeliveryFraming.continuationLine(deliveredText, query, entityVocabulary);
          if (continuation != null) {
            continuationsEmitted++;
          }
        }
        hits.add(
            new McpSearchResponseContent.HitContent(
                rank++,
                title,
                path,
                hit.score(),
                preview,
                matchedTerms,
                matchedFields,
                continuation,
                entityCarriage));
      }
    }

    int shownCount = resp.results() == null ? 0 : resp.results().size();
    boolean truncated = resp.totalHits() > shownCount;
    // Facets-truncation MCP relay (tempdoc 821 §L.3): resp.facetsTruncated() is a nullable Boolean
    // upstream (KnowledgeSearchResponse) — collapse null/false to false, same as `truncated` above.
    boolean facetsTruncated = Boolean.TRUE.equals(resp.facetsTruncated());

    // Hints
    var hints = new ArrayList<String>();
    if (resp.totalHits() == 0) {
      hints.add(
          "No results found. Try broader terms, or use justsearch_status to check what's"
              + " indexed.");
    } else if (resp.totalHits() > 100 && args.get("filters") == null) {
      hints.add("Many results. Use the returned facet values as filters to narrow down.");
    }
    // Tempdoc 655: comparative response hint — searched the whole index in one call, which beats
    // listing-and-reading files for a topical/semantic query. Factual, only on a productive search.
    if (resp.totalHits() > 0) {
      hints.add(
          "Searched the index in one call. For conceptual or cross-document questions,"
              + " justsearch_answer returns assembled cited passages directly.");
    }
    String enrichmentHintText = enrichmentHint(SEARCH_ENRICHMENT_MESSAGE, engineContext);
    if (enrichmentHintText != null) {
      hints.add(enrichmentHintText);
    }

    // Tempdoc 789 Phase 2 (F2/F3): the two response-level framings. Both null when their flag is
    // off, so the rendered text is byte-identical to pre-789 by default.
    String evidenceHeader =
        framing.evidenceNotAnswerEnabled()
            ? McpDeliveryFraming.searchEvidenceHeader(
                resp.totalHits(),
                McpDeliveryFraming.responseMatchedTerms(
                    hits, McpSearchResultFormatter.MAX_INFORMATIVE_TERMS))
            : null;
    String absenceNote =
        framing.calibratedAbsenceEnabled()
            ? McpDeliveryFraming.absenceNote(
                new McpDeliveryFraming.AbsenceSignals(
                    resp.totalHits(),
                    McpDeliveryFraming.topDeliveredScore(hits),
                    McpDeliveryFraming.normalizedFusionScale(resp.searchTrace()),
                    McpDeliveryFraming.deliveredBodyBytes(hits),
                    indexedDocs),
                query,
                framing)
            : null;

    return new McpSearchResponseContent(
        resp.totalHits(),
        resp.tookMs(),
        shownCount,
        truncated,
        hits,
        resp.facets(),
        facetsTruncated,
        hints,
        evidenceHeader,
        absenceNote);
  }

  /**
   * Tempdoc 789 Phase 2 (F3): the indexed-document count backing the calibrated-absence coverage
   * clause, read from the same {@code KnowledgeStatus} surface {@code justsearch_status} already
   * exposes ({@code docCount}). Returns {@code -1} when unavailable, which makes the framing omit
   * the coverage clause rather than guess a number. Called at most once per search, and only when
   * the F3 framing is enabled.
   */
  private long indexedDocCount(EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) {
        return -1L;
      }
      return ctrl.getAdapter().status(engineContext).docCount();
    } catch (Exception e) {
      log.debug("MCP framing: index doc count unavailable", e);
      return -1L;
    }
  }

  /**
   * Tempdoc 735 W6: the {@code justsearch_search} text renderer — reproduces the pre-0.4.0
   * StringBuilder rendering byte-for-byte (golden-pinned by {@code McpTierEquivalenceGoldenTest}),
   * now reading every fact from {@code content} instead of {@code resp} directly (degradation is
   * the one exception: it stays sourced straight from {@code resp.searchTrace()} since it was
   * already tier-equivalent before this increment — see {@link McpSearchResponseContent}'s
   * class-level note).
   */
  static String renderSearchText(
      KnowledgeSearchResponse resp, McpSearchResponseContent content, boolean concise) {
    var sb = new StringBuilder();

    // Tempdoc 789 Phase 2 (F2): the evidence-not-answer header leads the delivery, so the framing
    // is read before any excerpt. Null (and so absent, byte-for-byte) unless the framing is enabled.
    if (content.evidenceHeader() != null) {
      sb.append(content.evidenceHeader()).append("\n\n");
    }

    for (var h : content.hits()) {
      sb.append("[")
          .append(h.rank())
          .append("] ")
          .append(!h.title().isBlank() ? h.title() : h.path())
          .append(" (score: ")
          .append(String.format("%.2f", h.score()))
          .append(")\n");
      if (!h.path().isBlank()) sb.append("    Path: ").append(h.path()).append("\n");

      // Tempdoc 725 W1: informative-term-anchored preview + a DESCRIPTIVE (never imperative)
      // match-basis line, so an agent can tell WHY a hit matched without opening the file.
      if (!concise) {
        if (!h.preview().isBlank()) {
          sb.append("    Preview: ").append(h.preview()).append("\n");
        }
      }
      // Tempdoc 771 item (b): the entity-carriage line, directly under the excerpt whose gaps it
      // fills. Rendered at BOTH densities — unlike the Preview and F1 lines it makes no claim about
      // the excerpt (its wording is about the document), and it is the highest-value content per
      // byte in a delivery whose whole failure mode is a name the agent was never handed.
      if (h.entityCarriage() != null) {
        sb.append("    ").append(h.entityCarriage()).append("\n");
      }
      if (!h.semanticFallback()) {
        StringBuilder quoted = new StringBuilder();
        for (int i = 0; i < h.matchedTerms().size(); i++) {
          if (i > 0) quoted.append(", ");
          quoted.append('"').append(h.matchedTerms().get(i)).append('"');
        }
        sb.append("    Matched: ").append(quoted);
        if (!h.matchedFields().isEmpty()) {
          sb.append(" in ").append(String.join(", ", h.matchedFields()));
        }
        sb.append("\n");
      } else {
        sb.append("    Match basis: semantic similarity (no distinctive term overlap)\n");
      }
      // Tempdoc 789 Phase 2 (F1): the per-hit continuation line, directly under the excerpt whose
      // entity it names. Null unless the framing is enabled and this hit qualifies.
      //
      // Gated on !concise for the same reason the Preview line is: the sentence says "this excerpt
      // names X", and concise mode does not render the excerpt in the TEXT tier — the claim would
      // point at text the agent was never shown. The structured tier is unaffected (it carries
      // per-hit excerpts unconditionally, so the continuation stays true there and is projected
      // regardless of density).
      if (!concise && h.continuation() != null) {
        sb.append("    ").append(h.continuation()).append("\n");
      }
      sb.append("\n");
    }

    sb.append("Found ").append(content.totalHits()).append(" results");
    if (content.tookMs() > 0) sb.append(" (took ").append(content.tookMs()).append("ms)");
    if (content.truncated()) {
      sb.append("; showing ").append(content.shownCount()).append(".");
    } else {
      sb.append(".");
    }
    appendDegradationNote(sb, resp.searchTrace());

    // Tempdoc 789 Phase 2 (F3): the calibrated-absence block, immediately under the result count it
    // qualifies. Null unless the framing is enabled and the delivery is empty or thin.
    if (content.absenceNote() != null) {
      sb.append("\n\n").append(content.absenceNote());
    }

    // Facets
    if (content.facets() != null && !content.facets().isEmpty()) {
      sb.append("\n\nFacets (use as filter values");
      // Facets-truncation MCP relay (tempdoc 821 §L.3): the flag that never reached this tier
      // before — the scan did not cover every match, so counts are a lower bound and some values
      // may be missing entirely. Cause-neutral wording (the causes are not fixed — today the
      // maxDocsScanned cap, and 821's facets-engine work extends it to a mid-scan failure too)
      // — the claim is the effect, not a specific cause. Surfaced in the text tier too, not just
      // structuredContent, so a text-only MCP client sees it (McpEvidenceProjection carries the
      // structured counterpart).
      if (content.facetsTruncated()) {
        sb.append("; counts are partial — the scan did not cover every match");
      }
      sb.append("):\n");
      for (var entry : content.facets().entrySet()) {
        String facetName = entry.getKey().replace("_raw", "");
        sb.append("  ").append(facetName).append(": ");
        if (entry.getValue() instanceof Map<?, ?> facetMap) {
          var topEntries =
              facetMap.entrySet().stream()
                  .limit(5)
                  .map(e -> e.getKey() + " (" + e.getValue() + ")")
                  .toList();
          sb.append(String.join(", ", topEntries));
        }
        sb.append("\n");
      }
    }

    // Hints
    if (!content.hints().isEmpty()) {
      sb.append("\nHints:\n");
      for (String hint : content.hints()) sb.append("- ").append(hint).append("\n");
    }

    return sb.toString();
  }

  /**
   * Tempdoc 725 W1/W2 preview-selection order: (a) an excerpt region windowed to cover the most
   * informative-term occurrences (ties broken toward the LATER occurrence — a later occurrence
   * tends to sit in body content rather than a title echo near the head of the text), (b) a
   * content_preview window chosen the same way over the field's informative-term occurrences, (c)
   * the pre-existing head-of-field fallback. Cases (a)/(b) append {@link
   * McpSearchResultFormatter#TRUNCATION_REMEDY} when the rendered text is a cut of a larger source;
   * case (c) keeps its existing "..." suffix unchanged.
   *
   * <p>Windowing uses {@link McpSearchResultFormatter#informativeOccurrences}, not {@link
   * McpSearchResultFormatter#filterInformative}: the latter dedups by term (right for the
   * "Matched:" line) and would collapse two occurrences of the same term — e.g. a title-echo
   * occurrence and a payload occurrence — down to whichever comes first, which is the defect this
   * increment fixes (tempdoc 725 W2, live-validated on doc cavby8).
   */
  private static String buildHitPreview(KnowledgeSearchResponse.Hit hit) {
    List<KnowledgeSearchResponse.ExcerptRegion> regions = hit.excerptRegions();
    if (regions != null && !regions.isEmpty()) {
      KnowledgeSearchResponse.ExcerptRegion region = McpSearchResultFormatter.selectBestRegion(regions);
      List<KnowledgeSearchResponse.MatchSpan> regionOccurrences =
          McpSearchResultFormatter.informativeOccurrences(region.matchSpans());
      McpSearchResultFormatter.Window window =
          regionOccurrences.isEmpty()
              ? McpSearchResultFormatter.windowStartingAt(
                  region.text(), 0, McpSearchResultFormatter.REGION_WINDOW_CHARS)
              : McpSearchResultFormatter.bestWindow(
                  region.text(), regionOccurrences, McpSearchResultFormatter.REGION_WINDOW_CHARS);
      String text = McpSearchResultFormatter.stripControlChars(window.text());
      return window.truncated() ? text + McpSearchResultFormatter.TRUNCATION_REMEDY : text;
    }

    List<KnowledgeSearchResponse.MatchSpan> previewFieldSpans = new ArrayList<>();
    if (hit.matchSpans() != null) {
      for (KnowledgeSearchResponse.MatchSpan span : hit.matchSpans()) {
        if ("content_preview".equals(span.field())) previewFieldSpans.add(span);
      }
    }
    List<KnowledgeSearchResponse.MatchSpan> previewOccurrences =
        McpSearchResultFormatter.informativeOccurrences(previewFieldSpans);
    String fieldValue = hit.fields().getOrDefault("content_preview", "");
    if (!previewOccurrences.isEmpty()) {
      McpSearchResultFormatter.Window window =
          McpSearchResultFormatter.bestWindow(
              fieldValue, previewOccurrences, McpSearchResultFormatter.PREVIEW_WINDOW_CHARS);
      String text = McpSearchResultFormatter.stripControlChars(window.text());
      return window.truncated() ? text + McpSearchResultFormatter.TRUNCATION_REMEDY : text;
    }

    if (fieldValue.length() > 200) {
      return McpSearchResultFormatter.stripControlChars(fieldValue.substring(0, 200)) + "...";
    }
    return McpSearchResultFormatter.stripControlChars(fieldValue);
  }

  /**
   * Tempdoc 725 W1: a once-per-response, DESCRIPTIVE-only note when {@link SearchTrace.Degradation}
   * says semantic ranking was blocked or fell back — read from the canonical {@link SearchTrace}
   * (registered in {@code governance/execution-surfaces.v1.json}), never re-derived. Null-safe:
   * absent trace or degradation appends nothing.
   */
  private static void appendDegradationNote(StringBuilder sb, SearchTrace trace) {
    if (trace == null || trace.degradation() == null) return;
    SearchTrace.Degradation degradation = trace.degradation();
    if (!degradation.vectorBlocked() && !degradation.hybridFallback()) return;
    List<String> reasons = new ArrayList<>();
    if (degradation.vectorBlocked()
        && degradation.vectorBlockedReason() != null
        && !degradation.vectorBlockedReason().isBlank()) {
      reasons.add(degradation.vectorBlockedReason());
    }
    if (degradation.hybridFallback()
        && degradation.hybridFallbackReason() != null
        && !degradation.hybridFallbackReason().isBlank()) {
      reasons.add(degradation.hybridFallbackReason());
    }
    String reasonText = reasons.isEmpty() ? "reason unavailable" : String.join("; ", reasons);
    sb.append("\nNote: semantic ranking degraded (")
        .append(reasonText)
        .append("); results may be keyword-ranked only.");
  }

  // =========================================================================
  // Status: formatted text
  // =========================================================================

  private Map<String, Object> callStatus(EngineContext engineContext) {
    KnowledgeSearchController ctrl = knowledgeLookup.get();
    if (ctrl == null) return errorContent(KNOWLEDGE_SERVER_UNAVAILABLE_MESSAGE, ApiErrorCode.SERVICE_UNAVAILABLE);
    try {
      KnowledgeHttpApiAdapter adapter = ctrl.getAdapter();
      var status = adapter.status(engineContext);
      var sb = new StringBuilder();
      sb.append("state: ").append(status.state()).append("\n");
      sb.append("ready: ").append(status.ready()).append("\n");
      sb.append("documents: ").append(status.docCount()).append("\n");
      sb.append("queueDepth: ").append(status.queueDepth()).append("\n");
      sb.append("healthy: ").append(status.healthy()).append("\n");
      sb.append("indexState: ").append(status.indexState()).append("\n");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n) {
        sb.append("embeddingCoverage: ")
            .append(String.format("%.1f%%", n.doubleValue()))
            .append("\n");
      }
      if (extras.get("spladeCoveragePercent") instanceof Number n) {
        sb.append("spladeCoverage: ")
            .append(String.format("%.1f%%", n.doubleValue()))
            .append("\n");
      }
      if (extras.get("completedNerCount") instanceof Number nd) {
        sb.append("nerCompleted: ").append(nd.intValue());
        if (extras.get("pendingNerCount") instanceof Number np) {
          sb.append(" (").append(np.intValue()).append(" pending)");
        }
        sb.append("\n");
      }
      return Map.of(
          "content", List.of(Map.of("type", "text", "text", sb.toString())), "isError", false);
    } catch (Exception e) {
      log.warn("MCP status failed", e);
      return toolFailureContent("Status", e);
    }
  }

  // =========================================================================
  // Operation dispatch (browse + ingest)
  // =========================================================================

  private Map<String, Object> callOperation(
      String opIdValue, Map<String, Object> arguments, String requestedBy, EngineContext engineContext) {
    try {
      Operation op = resolveOperation(opIdValue);
      if (op == null) return errorContent("Operation not available: " + opIdValue);
      var publicArguments = new LinkedHashMap<String, Object>(arguments == null ? Map.of() : arguments);
      Object rawKey = publicArguments.remove("operationKey");
      if (arguments != null && arguments.containsKey("operationKey") && !(rawKey instanceof String)) {
        throw new OperationStoreException(OperationStoreException.Code.INVALID_OPERATION_KEY, null);
      }
      String operationKey = (String) rawKey;
      String argsJson = MAPPER.writeValueAsString(publicArguments);
      // Tempdoc 655: validate against the Operation's OWN declared schema — the real enforcement
      // schema, not a second MCP-authored literal — before dispatch, so a malformed call gets a
      // clean MCP error here instead of surfacing however the executor happens to fail later.
      Map<String, Object> invalid =
          inputValidator
              .validate(op, argsJson)
              .<Map<String, Object>>map(
                  result ->
                      errorContent("Invalid arguments for " + opIdValue + ": " + result.message(),
                          ApiErrorCode.INVALID_REQUEST))
              .orElse(null);
      if (invalid != null) {
        return invalid;
      }
      InvocationProvenance provenance =
          io.justsearch.app.services.intent.EngineProvenance.invocation(engineContext,
              io.justsearch.agent.api.registry.ExecutorTag.UI, clock.instant(), Optional.empty());
      OperationResult opResult;
      try {
        opResult = operationKey == null
            ? dispatcher.dispatch(op, argsJson, provenance, engineContext)
            : dispatcher.dispatch(op, argsJson, provenance, Optional.empty(), engineContext, operationKey);
      } catch (ConfirmationRequiredException e) {
        return handleConfirmationRequired(op, argsJson, e, requestedBy, engineContext, provenance, operationKey);
      }
      if (opResult.success()) {
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", opResult.message()));
        if (!opResult.structuredData().isEmpty()) {
          content.add(
              Map.of("type", "text", "text", MAPPER.writeValueAsString(opResult.structuredData())));
        }
        return Map.of("content", content, "isError", false);
      } else {
        var failure = new LinkedHashMap<String, Object>();
        failure.put("error", ApiErrorHandler.sanitizeMessage(opResult.message()));
        opResult.errorCode().ifPresent(code -> failure.put("errorCode", code));
        opResult.retryable().ifPresent(retryable -> failure.put("retryable", retryable));
        if (opResult.structuredData().get("operationKey") instanceof String key) {
          failure.put("operationKey", key);
        }
        if (opResult.structuredData().get("operationRecordId") instanceof Long id) {
          failure.put("operationRecordId", id);
        }
        // OperationResult carries no API error class. Preserve its optional facts without
        // guessing a classification for a handler-specific or absent code.
        return errorContent(failure);
      }
    } catch (io.justsearch.agent.api.encryption.KeyLockedException e) {
      var failure = OperationInvocationResponse.fromLockedStore();
      return errorContent(Map.of("error", failure.message(), "errorCode", failure.errorCode(),
          "errorClass", failure.errorClass(), "retryable", failure.retryable(), "locked", true));
    } catch (OperationStoreException e) {
      var failure = OperationInvocationResponse.fromStoreFailure(e);
      return errorContent(Map.of("error", failure.message(), "errorCode", failure.errorCode(),
          "errorClass", failure.errorClass(), "retryable", failure.retryable()));
    } catch (Exception e) {
      log.warn("MCP operation dispatch error for {}", opIdValue, e);
      return toolFailureContent("Operation " + opIdValue, e);
    }
  }

  /**
   * Tempdoc 655: an MCP tool call hit the trust lattice's confirmation gate. Reuses the SAME
   * mechanism the browser UI's gate path uses (via the shared {@link
   * io.justsearch.app.services.intent.PendingAuthorizationStore}) instead of the previous
   * fabricated {@code _confirmationToken} hint, which nothing in the codebase ever read — the
   * tool could never actually succeed via that path.
   *
   * <p>Design (tempdoc 655 §"Design decision made during investigation"): approval completes the
   * operation directly, server-side, when a human approves in the JustSearch app — not via a
   * later MCP retry/replay. This call returns immediately either way; it never blocks waiting for
   * human approval, sidestepping MCP hosts' inconsistent/short tool-call timeouts entirely.
   */
  private Map<String, Object> handleConfirmationRequired(
      Operation op, String argsJson, ConfirmationRequiredException e, String requestedBy, EngineContext engineContext, InvocationProvenance provenance, String operationKey) {
    String message;
    if (pendingAuthorizationStore == null) {
      // Legacy/test wiring with no store — fail closed, but say so plainly rather than
      // advertising a retry path that cannot work.
      message =
          "Operation '"
              + e.operationRef().value()
              + "' requires confirmation (gate: "
              + e.gateBehavior()
              + ") and no approval mechanism is wired in this deployment. It cannot be completed"
              + " via MCP.";
    } else {
      String pendingId =
          pendingAuthorizationStore.create(
              op.id().value(), argsJson, e.sourceTier(), op.policy().risk(), e.gateBehavior(),
              e.getMessage(), requestedBy,
              io.justsearch.agent.api.registry.TransportTag.MCP, engineContext, provenance,
              e.operationKey() == null ? operationKey : e.operationKey(), false, e.preparationNonce(), e.approvalPreview());
      if (pendingAuthorizationChanges != null) {
        // Tempdoc 655 fix pass: routing info only — no argsSummary/rationale on the broadcast
        // (see PendingAuthorizationEvent's doc comment for why). A subscriber fetches the
        // decision content itself, by id, via GET /api/authorizations/pending/{id}.
        pendingAuthorizationStore
            .peek(pendingId)
            .ifPresent(
                pending ->
                    pendingAuthorizationChanges.broadcast(
                        new io.justsearch.app.observability.operations.PendingAuthorizationEvent(
                            pending.id(),
                            pending.operationId(),
                            pending.sourceTier(),
                            pending.riskTier(),
                            pending.gateBehavior(),
                            pending.createdAt(),
                            pending.expiresAt(),
                            pending.transport())));
      }
      message =
          "Operation '"
              + e.operationRef().value()
              + "' requires your approval (gate: "
              + e.gateBehavior()
              + "). A request is now showing in the JustSearch app — once approved there, it"
              + " completes automatically. You do not need to retry this call.";
    }
    return Map.of("content", List.of(Map.of("type", "text", "text", message)), "isError", true);
  }

  // =========================================================================
  // Prompts
  // =========================================================================

  public Map<String, Object> listPrompts() {
    return Map.of(
        "prompts",
        List.of(
            Map.of(
                "name", "search_files",
                "description", "Search your local knowledge base for a topic",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "topic", "description", "What to search for", "required",
                            true))),
            Map.of(
                "name", "answer_question",
                "description", "Get an answer from your indexed documents",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "question", "description", "The question to answer", "required",
                            true))),
            Map.of(
                "name", "index_folder",
                "description", "Add a folder to your knowledge base",
                "arguments",
                    List.of(
                        Map.of(
                            "name", "path", "description", "Absolute path to the folder",
                            "required", true)))));
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> getPrompt(String name, Map<String, String> arguments, EngineContext engineContext) {
    String statusContext = getStatusContext(engineContext);
    return switch (name != null ? name : "") {
      case "search_files" ->
          promptMessages(
              statusContext,
              "Search my local files for: " + arguments.getOrDefault("topic", ""));
      case "answer_question" ->
          promptMessages(
              statusContext,
              "Using my indexed documents, answer: " + arguments.getOrDefault("question", ""));
      case "index_folder" ->
          promptMessages(
              statusContext,
              "Index this folder into my knowledge base: "
                  + arguments.getOrDefault("path", ""));
      default -> Map.of("messages", List.of());
    };
  }

  /**
   * Tempdoc 655: the connect-time steering surface, returned as MCP {@code initialize}'s
   * {@code instructions} field (populated by {@link McpProtocolHandler#handleInitialize}). Static,
   * comparative tool-selection guidance — deliberately does NOT call the worker for live status, so
   * the connect handshake carries no cross-process round-trip. Live index state is available to the
   * agent via {@code justsearch_status}; the user-invoked prompt path ({@link #getStatusContext})
   * prepends live counts to the SAME guidance string, so the two surfaces cannot fork.
   */
  public String instructions() {
    return TOOL_SELECTION_GUIDANCE;
  }

  private String getStatusContext(EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return "JustSearch index status unknown. " + TOOL_SELECTION_GUIDANCE;
      var status = ctrl.getAdapter().status(engineContext);
      var sb = new StringBuilder("JustSearch has ");
      sb.append(status.docCount()).append(" documents indexed.");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n) {
        sb.append(" Embeddings: ").append(String.format("%.0f%%", n.doubleValue())).append(".");
      }
      if (extras.get("spladeCoveragePercent") instanceof Number n) {
        sb.append(" SPLADE: ").append(String.format("%.0f%%", n.doubleValue())).append(".");
      }
      // Single-sourced comparative guidance (tempdoc 655): the feature-forward enumeration that used
      // to live here ("Use justsearch_answer for questions…") is superseded by TOOL_SELECTION_GUIDANCE
      // so the prompt path and the initialize instructions field speak with one voice.
      sb.append(" ").append(TOOL_SELECTION_GUIDANCE);
      return sb.toString();
    } catch (Exception e) {
      return "JustSearch index status unknown. " + TOOL_SELECTION_GUIDANCE;
    }
  }

  private static Map<String, Object> promptMessages(String systemContext, String userMessage) {
    return Map.of(
        "messages",
        List.of(
            Map.of("role", "assistant", "content", Map.of("type", "text", "text", systemContext)),
            Map.of("role", "user", "content", Map.of("type", "text", "text", userMessage))));
  }

  // =========================================================================
  // Resources (proposed URIs + catalog-driven)
  // =========================================================================

  public Map<String, Object> listResources(
      List<io.justsearch.agent.api.registry.ResourceCatalog> resourceCatalogs) {
    var resources = new ArrayList<Map<String, Object>>();

    // Proposed URIs per tempdoc 500
    resources.add(
        resource(
            "justsearch://index/summary",
            "Index Summary",
            "Document count, enrichment coverage, readiness state"));
    resources.add(
        resource(
            "justsearch://index/roots",
            "Indexed Roots",
            "List of indexed folder paths"));
    resources.add(
        resource(
            "justsearch://index/top-sources",
            "Top Sources",
            "Most common document sources (meta_source facet values)"));
    resources.add(
        resource(
            "justsearch://index/top-entities",
            "Top Entities",
            "Most common persons and organizations (entity facet values)"));

    // Catalog-driven resources (for subscription support)
    for (var catalog : resourceCatalogs) {
      for (var r : catalog.definitions()) {
        if (!Set.of(io.justsearch.agent.api.registry.Audience.USER,
            io.justsearch.agent.api.registry.Audience.AGENT).contains(r.audience())) continue;
        resources.add(
            resource(
                "justsearch://resource/" + r.id().value(),
                r.presentation().labelKey().value(),
                r.presentation().descriptionKey().value()));
      }
    }

    return Map.of("resources", resources);
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> readResource(String uri, EngineContext engineContext) {
    if (uri == null) return Map.of("contents", List.of());
    return switch (uri) {
      case "justsearch://index/summary" -> readIndexSummary(uri, engineContext);
      case "justsearch://index/roots" -> readIndexRoots(uri, engineContext);
      case "justsearch://index/top-sources" -> readTopFacet(uri, "meta_source", 10, engineContext);
      case "justsearch://index/top-entities" ->
          readTopEntities(uri, engineContext);
      default -> {
        if (uri.startsWith("justsearch://resource/")) {
          yield readIndexSummary(uri, engineContext);
        }
        yield Map.of("contents", List.of());
      }
    };
  }

  private Map<String, Object> readIndexSummary(String uri, EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var status = ctrl.getAdapter().status(engineContext);
      var sb = new StringBuilder();
      sb.append("documents: ").append(status.docCount()).append("\n");
      sb.append("ready: ").append(status.ready()).append("\n");
      sb.append("healthy: ").append(status.healthy()).append("\n");
      sb.append("state: ").append(status.state()).append("\n");
      Map<String, Object> extras = status.extras();
      if (extras.get("embeddingCoveragePercent") instanceof Number n)
        sb.append("embeddingCoverage: ").append(String.format("%.1f%%", n.doubleValue())).append("\n");
      if (extras.get("spladeCoveragePercent") instanceof Number n)
        sb.append("spladeCoverage: ").append(String.format("%.1f%%", n.doubleValue())).append("\n");
      return Map.of("contents",
          List.of(orderedMap("uri", uri, "mimeType", "text/plain", "text", sb.toString())));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readIndexRoots(String uri, EngineContext engineContext) {
    try {
      // Use the browse tool to list roots
      var result = callOperation("core.browse-folders", Map.of(), null, engineContext);
      @SuppressWarnings("unchecked")
      var content = (List<Map<String, Object>>) result.get("content");
      String text = content != null && !content.isEmpty() ? (String) content.get(0).get("text") : "No roots";
      return Map.of("contents",
          List.of(orderedMap("uri", uri, "mimeType", "text/plain", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readTopFacet(String uri, String field, int size, EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var req = new KnowledgeSearchRequest(
          "", 0, "hybrid", null, null, null, null, null,
          new KnowledgeSearchRequest.Facets(true, null,
              List.of(new KnowledgeSearchRequest.FieldSpec(field, size))),
          null, null, null, null);
      var resp = ctrl.getAdapter().search(req, engineContext);
      String text = resp.facets() != null ? MAPPER.writeValueAsString(resp.facets()) : "{}";
      return Map.of("contents",
          List.of(orderedMap("uri", uri, "mimeType", "application/json", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  private Map<String, Object> readTopEntities(String uri, EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return resourceError(uri, "Knowledge server not available");
      var req = new KnowledgeSearchRequest(
          "", 0, "hybrid", null, null, null, null, null,
          new KnowledgeSearchRequest.Facets(true, null, List.of(
              new KnowledgeSearchRequest.FieldSpec("entity_persons_raw", 10),
              new KnowledgeSearchRequest.FieldSpec("entity_organizations_raw", 10),
              new KnowledgeSearchRequest.FieldSpec("entity_locations_raw", 10))),
          null, null, null, null);
      var resp = ctrl.getAdapter().search(req, engineContext);
      String text = resp.facets() != null ? MAPPER.writeValueAsString(resp.facets()) : "{}";
      return Map.of("contents",
          List.of(orderedMap("uri", uri, "mimeType", "application/json", "text", text)));
    } catch (Exception e) {
      return resourceError(uri, e.getMessage());
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  /**
   * Tempdoc 735 W6: replaces {@code appendEnrichmentHint}/{@code appendEnrichmentHintToList} —
   * both tools ran the identical enrichment-coverage check but appended different literal wording
   * at different call sites; this returns the fact ({@code message} when enrichment coverage is
   * low, {@code null} otherwise) so each caller can both render its own pre-existing text
   * formatting AND surface the same fact on structuredContent's {@code hints}.
   */
  private String enrichmentHint(String message, EngineContext engineContext) {
    try {
      KnowledgeSearchController ctrl = knowledgeLookup.get();
      if (ctrl == null) return null;
      var status = ctrl.getAdapter().status(engineContext);
      Map<String, Object> extras = status.extras();
      boolean lowEmbedding = extras.get("embeddingCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      boolean lowSplade = extras.get("spladeCoveragePercent") instanceof Number n && n.doubleValue() < 100;
      return (lowEmbedding || lowSplade) ? message : null;
    } catch (Exception e) {
      return null;
    }
  }

  private Operation resolveOperation(String idValue) {
    for (OperationCatalog catalog : operationCatalogs) {
      var found = catalog.definitions().stream()
          .filter(op -> op.id().value().equals(idValue))
          .findFirst();
      if (found.isPresent()) return found.get();
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static KnowledgeSearchRequest.Filters parseFilters(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) return null;
    return new KnowledgeSearchRequest.Filters(
        null, null, null, null,
        // Tempdoc 655 fix pass: defensive instanceof check (matches toStringList's existing safe
        // pattern below) as belt-and-suspenders — the boundary schema validator should already
        // reject a non-string path_prefix before this is reached, but this handler shouldn't
        // trust that unconditionally.
        raw.get("path_prefix") instanceof String pathPrefix ? pathPrefix : null,
        null, null,
        toStringList(raw, "entity_persons"),
        toStringList(raw, "entity_organizations"),
        toStringList(raw, "entity_locations"),
        toStringList(raw, "meta_source"),
        toStringList(raw, "meta_author"),
        toStringList(raw, "meta_category"),
        null, null,
        // Tempdoc 585 §D Phase 4 (D4b) — collection scope (unused by the MCP search surface).
        toStringList(raw, "collection"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> toStringList(Map<String, Object> map, String key) {
    if (map == null) return List.of();
    Object val = map.get(key);
    if (val instanceof List<?> list) return list.stream().map(String::valueOf).toList();
    if (val instanceof String s) return List.of(s);
    return List.of();
  }

  private Map<String, Object> projectToolDefinition(ToolDefinition definition) {
    ToolLifecycle lifecycle = lifecycleCatalog.get(definition.name());
    var t = new LinkedHashMap<String, Object>();
    t.put("name", definition.name());
    t.put(
        "description",
        lifecycle == null
            ? definition.description()
            : lifecycle.descriptionPrefix() + definition.description());
    t.put("inputSchema", definition.inputSchema());
    if (!definition.annotations().isEmpty()) {
      t.put("annotations", definition.annotations());
    }
    if (lifecycle != null) {
      t.put("_meta", lifecycle.metadata());
    }
    return t;
  }

  private static Map<String, ToolLifecycle> validateLifecycleCatalog(
      List<ToolDefinition> toolDefinitions, List<ToolLifecycle> lifecycleEntries) {
    Objects.requireNonNull(lifecycleEntries, "lifecycleEntries");

    var liveToolCounts = new LinkedHashMap<String, Integer>();
    for (ToolDefinition definition : toolDefinitions) {
      liveToolCounts.merge(definition.name(), 1, Integer::sum);
    }
    liveToolCounts.forEach(
        (name, count) -> {
          if (count != 1) {
            throw new IllegalArgumentException(
                "MCP tool declaration must resolve exactly once: " + name + " resolved " + count
                    + " times");
          }
        });

    var catalog = new LinkedHashMap<String, ToolLifecycle>();
    for (ToolLifecycle lifecycle : lifecycleEntries) {
      Objects.requireNonNull(lifecycle, "lifecycle entry");
      if (catalog.putIfAbsent(lifecycle.toolName(), lifecycle) != null) {
        throw new IllegalArgumentException(
            "Duplicate MCP lifecycle catalog key: " + lifecycle.toolName());
      }
      int resolutions = liveToolCounts.getOrDefault(lifecycle.toolName(), 0);
      if (resolutions != 1) {
        throw new IllegalArgumentException(
            "MCP lifecycle catalog key must resolve exactly once: "
                + lifecycle.toolName()
                + " resolved "
                + resolutions
                + " times");
      }
    }
    return Collections.unmodifiableMap(catalog);
  }

  record ToolDefinition(
      String name,
      String description,
      Map<String, Object> inputSchema,
      Map<String, Object> annotations) {
    ToolDefinition {
      name = requireNonBlank(name, "tool name");
      description = requireNonBlank(description, "tool description");
      inputSchema = immutableOrderedMap(inputSchema, "tool inputSchema");
      annotations = immutableOrderedMap(annotations, "tool annotations");
    }
  }

  record ToolLifecycle(
      String toolName, Instant deprecatedSince, Instant sunsetAt, String replacement) {
    ToolLifecycle {
      toolName = requireNonBlank(toolName, "lifecycle tool name");
      Objects.requireNonNull(deprecatedSince, "deprecatedSince");
      replacement = requireNonBlank(replacement, "lifecycle replacement");
      if (sunsetAt != null && !sunsetAt.isAfter(deprecatedSince)) {
        throw new IllegalArgumentException("sunsetAt must be after deprecatedSince for " + toolName);
      }
    }

    private String descriptionPrefix() {
      return "Deprecated since " + deprecatedSince + "; use " + replacement + " instead. ";
    }

    private Map<String, Object> metadata() {
      var metadata = new LinkedHashMap<String, Object>();
      metadata.put("io.justsearch/deprecated", true);
      metadata.put("io.justsearch/deprecatedSince", deprecatedSince.toString());
      if (sunsetAt != null) {
        metadata.put("io.justsearch/sunsetAt", sunsetAt.toString());
      }
      metadata.put("io.justsearch/replacement", replacement);
      return Collections.unmodifiableMap(metadata);
    }
  }

  private static String requireNonBlank(String value, String fieldName) {
    Objects.requireNonNull(value, fieldName);
    if (value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }

  private static Map<String, Object> immutableOrderedMap(
      Map<String, Object> source, String fieldName) {
    return Collections.unmodifiableMap(
        new LinkedHashMap<>(Objects.requireNonNull(source, fieldName)));
  }

  private static Map<String, Object> schema(
      Map<String, Object> properties, List<String> required) {
    var s = new LinkedHashMap<String, Object>();
    s.put("type", "object");
    s.put("properties", properties);
    if (!required.isEmpty()) s.put("required", required);
    return s;
  }

  /**
   * Tempdoc 725: alternating key/value builder for the {@code inputSchema} property maps and
   * annotation maps that end up in the {@code tools/list} response. {@link Map#of} for 2+ entries
   * iterates in a per-JVM-salted order (JDK {@code ImmutableCollections.MapN}), so the exact same
   * literal can serialize its keys in a different order across server restarts — harmless for a
   * client that reads by key, but it breaks byte-identical {@code tools/list} responses, which the
   * MCP draft spec SHOULDs for client-side cache hits. {@link LinkedHashMap} fixes iteration to
   * insertion (i.e. source-literal) order instead.
   */
  private static Map<String, Object> orderedMap(Object... keysAndValues) {
    var m = new LinkedHashMap<String, Object>();
    for (int i = 0; i < keysAndValues.length; i += 2) {
      m.put((String) keysAndValues[i], keysAndValues[i + 1]);
    }
    return m;
  }

  private static Map<String, Object> prop(String type, String description) {
    return orderedMap("type", type, "description", description);
  }

  /** Tempdoc 655 fix pass: a declared array-of-string property (mirrors `paths` on ingest). */
  private static Map<String, Object> propStringArray(String description) {
    return orderedMap(
        "type", "array", "items", Map.of("type", "string"), "description", description);
  }

  /** Tempdoc 725 W2c: a declared string enum property (e.g. {@code response_format}). */
  private static Map<String, Object> propEnum(List<String> values, String description) {
    return orderedMap("type", "string", "enum", values, "description", description);
  }

  private static Map<String, Object> resource(String uri, String name, String description) {
    return orderedMap(
        "uri", uri, "name", name, "description", description, "mimeType", "application/json");
  }

  private static Map<String, Object> resourceError(String uri, String message) {
    return Map.of("contents", List.of(orderedMap(
        "uri", uri, "mimeType", "text/plain", "text", "Error: " + message)));
  }

  static Map<String, Object> errorContent(String message) {
    return errorContent(Map.of("error", ApiErrorHandler.sanitizeMessage(message)));
  }

  static Map<String, Object> errorContent(String message, ApiErrorCode code) {
    Map<String, Object> apiError = ApiErrorHandler.toResponse(code, message);
    var failure = new LinkedHashMap<String, Object>();
    for (String key : List.of("error", "errorCode", "errorClass", "retryable")) {
      failure.put(key, apiError.get(key));
    }
    return errorContent(failure);
  }

  /** Both MCP delivery tiers describe the same known facts; absent metadata stays absent. */
  private static Map<String, Object> errorContent(Map<String, Object> failure) {
    var text = new StringBuilder((String) failure.get("error"));
    if (failure.containsKey("errorCode")) {
      text.append("\nError code: ").append(failure.get("errorCode")).append('.');
    }
    if (failure.containsKey("errorClass")) {
      text.append(" Error class: ").append(failure.get("errorClass")).append('.');
    }
    if (failure.containsKey("retryable")) {
      text.append(" Retryable: ").append(failure.get("retryable")).append('.');
      if (Boolean.FALSE.equals(failure.get("retryable"))) {
        text.append(" Automatic retry is not recommended.");
      }
    }
    for (String field : List.of("operationKey", "operationRecordId")) {
      if (failure.containsKey(field)) text.append("\n").append(field).append(": ").append(failure.get(field));
    }
    return Map.of(
        "content", List.of(Map.of("type", "text", "text", text.toString())),
        "structuredContent", failure,
        "isError", true);
  }

  /** Shared condition and descriptive status pointer for the three unavailable-backend sites. */
  private static final String KNOWLEDGE_SERVER_UNAVAILABLE_MESSAGE =
      "Knowledge server is not available (worker offline or still starting)."
          + " State is reported by the justsearch_status tool.";

  /** Project the existing API classification and sanitizer, without a parallel retry policy. */
  private static Map<String, Object> toolFailureContent(String tool, Exception e) {
    var executorRefusal = ApiErrorHandler.executorRefusal(e);
    if (executorRefusal != null) throw executorRefusal;
    // Future.get/join transport the cause in wrappers with no failure policy of their own.
    // Use the same cause for wording and classification, retaining the fallback for absent
    // or non-Exception causes. Identity tracking also bounds malformed cyclic cause chains.
    Set<Exception> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    while ((e instanceof ExecutionException || e instanceof CompletionException)
        && e.getCause() instanceof Exception cause && seen.add(e)) {
      e = cause;
    }
    String detail = e.getMessage() != null ? e.getMessage() : "no additional detail";
    String message = tool
        + " failed: "
        + e.getClass().getSimpleName()
        + ": "
        + detail
        + ". Current component state is available via the justsearch_status tool.";
    return errorContent(message, ApiErrorHandler.resolve(e));
  }
}
