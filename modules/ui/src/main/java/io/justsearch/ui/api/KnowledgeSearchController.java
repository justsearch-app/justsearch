/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.justsearch.core.context.EngineContext;

import io.javalin.http.Context;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.api.knowledge.KnowledgeClientException;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.IngestCollectionPolicy;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.services.feedback.FeatureSnapshot;
import io.justsearch.app.services.feedback.FeatureSnapshots;
import io.justsearch.app.services.feedback.NdjsonAppendStore;
import io.justsearch.app.services.feedback.ResultDisposition;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.app.api.knowledge.KnowledgeStatus;
import io.justsearch.app.api.knowledge.KnowledgeStatusView;
import io.justsearch.app.api.knowledge.KnowledgeStatusViewBuilder;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.observability.HeadApiTags.ApiRequestTags;
import io.justsearch.app.services.observability.HttpMethod;
import io.justsearch.app.services.observability.HttpStatusClass;
import io.justsearch.app.services.worker.KnowledgeHttpApiAdapter;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.app.services.indexing.ExcludeGlobs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for Knowledge Server search endpoints.
 *
 * <p>Provides HTTP API endpoints that delegate to the Knowledge Server via gRPC.
 * This enables the UI to search content indexed by the background worker process.
 */
public class KnowledgeSearchController {
  private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchController.class);

  // Query redaction for privacy-safe logging
  private record SensitiveQuery(String value) {}
  private static String redact(Object x) {
    return (x instanceof SensitiveQuery) ? "[REDACTED]" : String.valueOf(x);
  }
  private final KnowledgeServerBootstrap knowledgeServer;
  private final Telemetry telemetry;
  private final HeadApiMetricCatalog apiCatalog;
  private final KnowledgeHttpApiAdapter adapter;
  private volatile io.justsearch.app.services.lifecycle.WorkerCapability workerCapability;
  // Tempdoc 580 §17 P1 — lazily-built per-query feature-snapshot store (the trace-feature capture).
  private volatile NdjsonAppendStore<FeatureSnapshot> featureSnapshots;
  // Tempdoc 580 §17 P3 — lazily-built disposition store (the search-interaction contributor sink).
  private volatile NdjsonAppendStore<ResultDisposition> dispositions;
  // Tempdoc 778 — the AUTHORED feedback-store cipher, injected post-construction (default passthrough).
  private volatile io.justsearch.agent.api.encryption.StoreCipher feedbackCipher =
      io.justsearch.agent.api.encryption.StoreCipher.disabled();
  // Tempdoc 778 — the default-on local capture flag; when absent (test path) capture stays on.
  private volatile io.justsearch.app.services.feedback.FeedbackCaptureSettings feedbackCaptureSettings;

  // L160: cache the last successful KnowledgeStatusView so the sparse fallback
  // during migration/Worker-restart serves useful data instead of {state, ready}.
  private record CachedStatus(KnowledgeStatusView view, long atMs) {}

  private volatile CachedStatus cachedStatus;

  /** Exposes the adapter for facet snapshot access (366 Phase 6 answer normalization). */
  public KnowledgeHttpApiAdapter getAdapter() {
    return adapter;
  }

  public void setWorkerCapability(io.justsearch.app.services.lifecycle.WorkerCapability cap) {
    this.workerCapability = cap;
  }

  /**
   * Tempdoc 778 — inject the AUTHORED feedback-store cipher (a projection of {@code
   * StoreCatalog.FEEDBACK}) so the disposition + feature-snapshot streams seal at rest with the same
   * key the agent contributor + label rebuild use. Called post-construction where {@code HeadAssembly}
   * is available; before it (or on the test path) the default disabled cipher is passthrough.
   */
  public void setFeedbackCipher(io.justsearch.agent.api.encryption.StoreCipher cipher) {
    if (cipher != null) {
      this.feedbackCipher = cipher;
    }
  }

  /** Tempdoc 778 — inject the default-on local capture flag governing disposition writes. */
  public void setFeedbackCaptureSettings(
      io.justsearch.app.services.feedback.FeedbackCaptureSettings settings) {
    this.feedbackCaptureSettings = settings;
  }

  private boolean captureEnabled() {
    var s = feedbackCaptureSettings;
    return s == null || s.isEnabled();
  }

  private boolean isWorkerReady() {
    var cap = workerCapability;
    return cap != null ? cap.available() : knowledgeServer.isReady();
  }

  private String workerStateName() {
    var cap = workerCapability;
    return cap != null ? cap.health().name() : knowledgeServer.workerCapability().health().name();
  }

  /**
   * Tempdoc 580 §17 P1 — persist this query's per-hit ranking features under {@code interactionId}
   * so a later result-disposition can join to "what we ranked" (the SearchTrace is otherwise
   * ephemeral, 580 §17.10). Best-effort: feedback capture must never break search.
   */
  private void captureFeatureSnapshot(
      String interactionId, String query, KnowledgeSearchResponse response) {
    try {
      featureSnapshotStore()
          .append(
          FeatureSnapshots.capture(
              interactionId, query, java.time.Instant.now().toEpochMilli(), response));
    } catch (Exception e) {
      log.debug("feature snapshot capture failed (non-fatal): {}", e.toString());
    }
  }

  /**
   * Tempdoc 580 §17 P3 — the search-interaction contributor endpoint. The FE posts {@code
   * {interactionId, docId, kind}} when a user opens / dwells-on / refines-without-opening a result;
   * the disposition lands in the ONE canonical stream and joins its {@link FeatureSnapshot} by
   * {@code interactionId} (the §17.4 join, working on the HTTP path). Best-effort: feedback never
   * fails the FE (always 204).
   *
   * <p>Tempdoc 778 — an optional {@code contributor} field distinguishes the surface: absent/{@code
   * "search"} is the default {@code SEARCH_INTERACTION} (unchanged); {@code "chat-citation"} records a
   * {@code USER_CITATION_CLICK} — a USER clicking a citation in a chat answer, distinct from the
   * LLM's own {@code AGENT_CITATION} harvest. Both land in the same canonical stream.
   */
  public void handleDisposition(Context ctx) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) ctx.bodyAsClass(Map.class);
      String interactionId = body.get("interactionId") instanceof String s ? s : null;
      String docId = body.get("docId") instanceof String s ? s : null;
      String kindStr = body.get("kind") instanceof String s ? s : null;
      if (interactionId == null || docId == null || kindStr == null) {
        ctx.status(400).json(Map.of("error", "interactionId, docId, kind required"));
        return;
      }
      // Tempdoc 778 — respect the default-on local capture flag: off ⇒ accept (204) but persist
      // nothing. Loopback-only either way; the flag lets the user stop even local capture.
      if (!captureEnabled()) {
        ctx.status(204);
        return;
      }
      ResultDisposition.Kind kind;
      try {
        kind = ResultDisposition.Kind.valueOf(kindStr.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException e) {
        ctx.status(400).json(Map.of("error", "unknown disposition kind: " + kindStr));
        return;
      }
      String contributorStr = body.get("contributor") instanceof String s ? s : null;
      ResultDisposition.Contributor contributor = contributorFor(contributorStr);
      var stableDocId =
          FeatureSnapshots.resolveStableDocId(
              featureSnapshotStore().readAll(), interactionId, docId);
      if (stableDocId.isEmpty()) {
        log.debug(
            "omitting feedback row without stable document UID: interactionId={} docId={}",
            interactionId,
            docId);
        ctx.status(204);
        return;
      }
      dispositionStore()
          .append(
              new ResultDisposition(
                  interactionId, stableDocId.get(), kind, contributor,
                  java.time.Instant.now().toEpochMilli()));
      ctx.status(204);
    } catch (Exception e) {
      log.debug("disposition capture failed (non-fatal): {}", e.toString());
      ctx.status(204); // never fail the FE on feedback
    }
  }

  /**
   * Tempdoc 778 — map the optional wire {@code contributor} to the enum. {@code "chat-citation"} →
   * {@code USER_CITATION_CLICK}; anything else (incl. absent / {@code "search"}) → the default
   * {@code SEARCH_INTERACTION}, preserving the pre-778 search-only behaviour.
   */
  private static ResultDisposition.Contributor contributorFor(String contributor) {
    return "chat-citation".equals(contributor)
        ? ResultDisposition.Contributor.USER_CITATION_CLICK
        : ResultDisposition.Contributor.SEARCH_INTERACTION;
  }

  private NdjsonAppendStore<ResultDisposition> dispositionStore() {
    NdjsonAppendStore<ResultDisposition> s = dispositions;
    if (s == null) {
      synchronized (this) {
        s = dispositions;
        if (s == null) {
          s =
              new NdjsonAppendStore<>(
                  PlatformPaths.resolveDataDir()
                      .resolve("feedback")
                      .resolve("result-dispositions.ndjson"),
                  ResultDisposition.class,
                  feedbackCipher);
          dispositions = s;
        }
      }
    }
    return s;
  }

  private NdjsonAppendStore<FeatureSnapshot> featureSnapshotStore() {
    NdjsonAppendStore<FeatureSnapshot> s = featureSnapshots;
    if (s == null) {
      synchronized (this) {
        s = featureSnapshots;
        if (s == null) {
          s =
              new NdjsonAppendStore<>(
                  PlatformPaths.resolveDataDir()
                      .resolve("feedback")
                      .resolve("feature-snapshots.ndjson"),
                  FeatureSnapshot.class,
                  feedbackCipher);
          featureSnapshots = s;
        }
      }
    }
    return s;
  }

  public KnowledgeSearchController(KnowledgeServerBootstrap knowledgeServer) {
    this(knowledgeServer, null);
  }

  public KnowledgeSearchController(KnowledgeServerBootstrap knowledgeServer, Telemetry telemetry) {
    this(knowledgeServer, telemetry, OnlineAiService.unavailable());
  }

  public KnowledgeSearchController(
      KnowledgeServerBootstrap knowledgeServer, Telemetry telemetry, OnlineAiService onlineAi) {
    this(knowledgeServer, telemetry, onlineAi, null);
  }

  public KnowledgeSearchController(
      KnowledgeServerBootstrap knowledgeServer,
      Telemetry telemetry,
      OnlineAiService onlineAi,
      RerankerService lambdaMartReranker) {
    this(knowledgeServer, telemetry, onlineAi, lambdaMartReranker, null);
  }

  public KnowledgeSearchController(
      KnowledgeServerBootstrap knowledgeServer,
      Telemetry telemetry,
      OnlineAiService onlineAi,
      RerankerService lambdaMartReranker,
      HeadApiMetricCatalog apiCatalog) {
    this.knowledgeServer = knowledgeServer;
    this.telemetry = telemetry;
    this.apiCatalog = apiCatalog;
    this.adapter = new KnowledgeHttpApiAdapter(knowledgeServer, onlineAi, lambdaMartReranker);
  }

  /**
   * Handles search requests.
   *
   * POST /api/knowledge/search
   * Body: { "query": "search text", "limit": 10 }
   */
  public void handleSearch(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    long startNs = System.nanoTime();
    try {
      // Tempdoc 502: inline state check removed — POST /api/knowledge/search is
      // behind the WorkerCapability gate (before-handler returns 503 if not READY).

      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) ctx.bodyAsClass(Map.class);
      String query = (String) body.get("query");
      Integer limit = body.get("limit") instanceof Number n ? n.intValue() : 10;
      Object modeRaw = body.get("mode");
      String modeText = modeRaw instanceof String s ? s : null;
      Object sortRaw = body.get("sort");
      String sortText = sortRaw instanceof String s ? s : null;
      Object cursorRaw = body.get("cursor");
      String cursorText = cursorRaw instanceof String s ? s : null;
      Object querySyntaxRaw = body.get("querySyntax");
      if (querySyntaxRaw == null) {
        querySyntaxRaw = body.get("query_syntax");
      }
      String querySyntaxText = querySyntaxRaw instanceof String s ? s : null;

      if (query == null || query.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Query is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }

      // Foreground responsiveness: record UI activity for the Head's idle checks (VDU pacing).
      // This is intentionally best-effort; it must never make search fail.
      try {
        knowledgeServer.recordUserActivity();
      } catch (Exception ignored) {
        // best-effort
      }

      log.info("Knowledge search: query='{}', limit={}", redact(new SensitiveQuery(query)), limit);

      // Optional: projection
      List<String> projection = List.of();
      Object projectionRaw = body.get("projection");
      if (projectionRaw instanceof List<?> list) {
        List<String> p = new ArrayList<>();
        for (Object v : list) {
          if (v instanceof String s && !s.isBlank()) {
            p.add(s);
          }
        }
        projection = List.copyOf(p);
      }
      // Feedback capture needs the internal stable UID even when a caller requests a narrow field
      // projection. Inject it only into the Worker request, then remove it from the HTTP result
      // unless the caller explicitly requested it. The public projection contract stays unchanged.
      List<String> injectedFeedbackFields = injectedFeedbackFields(projection);
      List<String> workerProjection = withFeedbackUid(projection);

      // Optional: filters
      KnowledgeSearchRequest.Filters filters = null;
      Object filtersRaw = body.get("filters");
      if (filtersRaw instanceof Map<?, ?> filtersMap) {
        filters = parseFilters(filtersMap);
      }

      // Optional: soft-boost filters (363)
      KnowledgeSearchRequest.Filters boostFilters = null;
      Object boostFiltersRaw = body.get("boostFilters");
      if (boostFiltersRaw instanceof Map<?, ?> boostMap) {
        List<String> bMetaSource = extractStringListAny(boostMap, "metaSource", "meta_source");
        List<String> bMetaAuthor = extractStringListAny(boostMap, "metaAuthor", "meta_author");
        List<String> bMetaCategory = extractStringListAny(boostMap, "metaCategory", "meta_category");
        List<String> bEntityPersons = extractStringListAny(boostMap, "entityPersons", "entity_persons");
        List<String> bEntityOrganizations = extractStringListAny(boostMap, "entityOrganizations", "entity_organizations");
        List<String> bEntityLocations = extractStringListAny(boostMap, "entityLocations", "entity_locations");
        boostFilters = KnowledgeSearchRequestFiltersBuilder.builder()
            .entityPersons(bEntityPersons)
            .entityOrganizations(bEntityOrganizations)
            .entityLocations(bEntityLocations)
            .metaSource(bMetaSource)
            .metaAuthor(bMetaAuthor)
            .metaCategory(bMetaCategory)
            .build();
      }

      // Optional: facets
      KnowledgeSearchRequest.Facets facets = null;
      Object facetsRaw = body.get("facets");
      if (facetsRaw instanceof Map<?, ?> facetsMap) {
        Boolean include = (facetsMap.get("include") instanceof Boolean b) ? b : null;
        Integer maxDocsScanned = (facetsMap.get("maxDocsScanned") instanceof Number n) ? n.intValue() : null;
        List<KnowledgeSearchRequest.FieldSpec> fields = List.of();
        Object fieldsRaw = facetsMap.get("fields");
        if (fieldsRaw instanceof List<?> list) {
          List<KnowledgeSearchRequest.FieldSpec> f = new ArrayList<>();
          for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) continue;
            Object fieldNameRaw = m.get("field");
            String fieldName = fieldNameRaw instanceof String s ? s : null;
            if (fieldName == null || fieldName.isBlank()) continue;
            Integer size = (m.get("size") instanceof Number n) ? n.intValue() : null;
            f.add(new KnowledgeSearchRequest.FieldSpec(fieldName, size));
          }
          fields = List.copyOf(f);
        }
        facets = new KnowledgeSearchRequest.Facets(include, maxDocsScanned, fields);
      }

      // Optional: includeExcerpts
      Boolean includeExcerpts = (body.get("includeExcerpts") instanceof Boolean b) ? b : null;
      // Optional: request the numeric per-hit detail tier (HitStage.detail) — tempdoc 549 Phase D2
      // maps this `debug` flag to include_detail. The structural per-hit trace is always-on.
      Boolean debug = (body.get("debug") instanceof Boolean b) ? b : null;

      // Optional: pipeline config (256: Phase A)
      PipelineConfig pipelineConfig = null;
      Object pipelineRaw = body.get("pipeline");
      if (pipelineRaw instanceof Map<?, ?> pm) {
        pipelineConfig =
            new PipelineConfig(
                Boolean.TRUE.equals(pm.get("sparseEnabled")),
                Boolean.TRUE.equals(pm.get("denseEnabled")),
                Boolean.TRUE.equals(pm.get("spladeEnabled")),
                pm.get("fusionAlgorithm") instanceof String s ? s : "none",
                Boolean.TRUE.equals(pm.get("lambdamartEnabled")),
                Boolean.TRUE.equals(pm.get("crossEncoderEnabled")),
                pm.get("crossEncoderWindow") instanceof Number n ? n.intValue() : 0,
                Boolean.TRUE.equals(pm.get("expansionEnabled")),
                Boolean.TRUE.equals(pm.get("freshnessEnabled")));
      }

      KnowledgeSearchRequest req =
          new KnowledgeSearchRequest(
              query, limit, modeText, sortText, cursorText, workerProjection, filters, boostFilters,
              facets, querySyntaxText, includeExcerpts, debug, pipelineConfig);
      KnowledgeSearchResponse response = adapter.search(req, engineContext);

      // Tempdoc 580 §17 (Track C P1) — stable per-query join key. The FE echoes it back with a
      // result-disposition (P3) so "what came of a result" joins to the persisted ranking features
      // (the §17.4 join). Additive field; the FE parse is additive-tolerant (searchState.ts cast).
      String interactionId = java.util.UUID.randomUUID().toString();
      captureFeatureSnapshot(interactionId, query, response);

      // Preserve legacy JSON shape (optional fields omitted when empty).
      Map<String, Object> out = new HashMap<>();
      out.put("interactionId", interactionId);
      out.put("totalHits", response.totalHits());
      // Tempdoc 597: the true matched-document count — the FE headline binds to this.
      out.put("matchCount", response.matchCount());
      out.put("tookMs", response.tookMs());
      out.put(
          "results",
          withoutInternalFields(response.results(), injectedFeedbackFields));
      if (response.nextCursor() != null && !response.nextCursor().isBlank()) {
        out.put("nextCursor", response.nextCursor());
      }
      if (response.facets() != null && !response.facets().isEmpty()) {
        out.put("facets", response.facets());
        out.put("facetsTruncated", Boolean.TRUE.equals(response.facetsTruncated()));
      }
      // Tempdoc 549 U4 (Slice 6): the 15 flat query-trace fields were removed from the response.
      // The canonical `introspection` trace (+ headStages) carries all of this; it is emitted
      // below via out.put("introspection", ...). Consumers read the trace, not flat fields.
      if (response.entityFacetVariants() != null
          && !response.entityFacetVariants().isEmpty()) {
        out.put("entityFacetVariants", response.entityFacetVariants());
      }
      if (response.indexCapabilities() != null) {
        out.put("indexCapabilities", response.indexCapabilities());
      }
      // Tempdoc 549 Phase E3: pipelineExecution retired — timing/component status on searchTrace.
      if (response.queryUnderstanding() != null) {
        out.put("queryUnderstanding", response.queryUnderstanding());
      }
      if (response.filterNormalization() != null) {
        out.put("filterNormalization", response.filterNormalization());
      }
      // Tempdoc 549 Phase E4: introspection retired — the unified trace is the single source.
      if (response.searchTrace() != null) {
        out.put("searchTrace", response.searchTrace());
      }
      // Tempdoc 366 §1b: the filter echo, present only when the request carried filters.
      if (response.appliedFilters() != null) {
        out.put("appliedFilters", response.appliedFilters());
      }
      ctx.json(out);

    } catch (KnowledgeClientException e) {
      int http = ApiErrorHandler.mapClientStatusToHttp(e.status());
      if (e.isInvalidCursor()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.CURSOR_INVALID, "Invalid cursor", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      ApiErrorCode code = ApiErrorHandler.resolve(e);
      ctx.status(http).json(ApiErrorHandler.toResponse(code, e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge search failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } finally {
      recordRequestMetricsBestEffort(ctx, startNs);
    }
  }

  /**
   * The internal fields feedback capture needs on every hit: the stable parent identity and, since
   * tempdoc 931 §C.6, the content revision that identity was ranked at. Neither is part of the
   * public projection contract, so both are injected into the Worker request and removed again from
   * the HTTP result unless the caller asked for them by name.
   */
  static final List<String> FEEDBACK_CAPTURE_FIELDS = List.of("doc_uid", "content_sha256");

  static List<KnowledgeSearchResponse.Hit> withoutInternalFields(
      List<KnowledgeSearchResponse.Hit> hits, List<String> injectedFields) {
    if (injectedFields.isEmpty()) {
      return hits;
    }
    return hits.stream()
        .map(
            hit -> {
              if (injectedFields.stream().noneMatch(hit.fields()::containsKey)) {
                return hit;
              }
              Map<String, String> fields = new HashMap<>(hit.fields());
              injectedFields.forEach(fields::remove);
              return new KnowledgeSearchResponse.Hit(
                  hit.id(),
                  hit.score(),
                  fields,
                  hit.matchedFields(),
                  hit.matchSpans(),
                  hit.excerptRegions(),
                  hit.trace());
            })
        .toList();
  }

  /** The subset of {@link #FEEDBACK_CAPTURE_FIELDS} this request has to inject and strip again. */
  static List<String> injectedFeedbackFields(List<String> requestedProjection) {
    if (requestedProjection.isEmpty()) {
      // An empty projection already returns every stored field; nothing was injected, so nothing
      // may be stripped.
      return List.of();
    }
    return FEEDBACK_CAPTURE_FIELDS.stream().filter(f -> !requestedProjection.contains(f)).toList();
  }

  static List<String> withFeedbackUid(List<String> requestedProjection) {
    List<String> missing = injectedFeedbackFields(requestedProjection);
    if (missing.isEmpty()) {
      return requestedProjection;
    }
    List<String> withCaptureFields = new ArrayList<>(requestedProjection);
    withCaptureFields.addAll(missing);
    return List.copyOf(withCaptureFields);
  }

  private void recordRequestMetricsBestEffort(Context ctx, long startNs) {
    if (apiCatalog == null || ctx == null) {
      return;
    }
    try {
      int status = ctx.res() == null ? 0 : ctx.res().getStatus();
      if (status <= 0) {
        return;
      }
      long durMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
      apiCatalog.requestMs.record(
          Math.max(0, durMs),
          new ApiRequestTags(
              "/api/knowledge/search",
              HttpMethod.POST,
              Integer.toString(status),
              HttpStatusClass.forStatus(status)));
    } catch (Exception ignored) {
      // best-effort
    }
  }



  // Lane F review B1: isInvalidCursor moved onto KnowledgeClientException. It was typed on the
  // transport's exception, so it became unreachable at item A6 and an expired cursor silently
  // stopped being the 4xx the pagination contract promises. The predicate is unchanged.

  private static List<String> extractStringList(Object raw) {
    if (!(raw instanceof List<?> list)) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (Object v : list) {
      if (v instanceof String s && !s.isBlank()) {
        out.add(s);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Parse the request {@code filters} block into a {@link KnowledgeSearchRequest.Filters}. Extracted
   * from the inline handler so the wiring is unit-testable (tempdoc 629 §Open issues #1/#2): the
   * {@code collection} scope tag was previously dropped here, silently disabling "search your agent
   * history" — a gap no test could catch while the parsing was inline. Accepts camelCase + snake_case
   * for the entity/meta keys (MCP compat); {@code collection} uses its record-component wire name.
   */
  static KnowledgeSearchRequest.Filters parseFilters(Map<?, ?> filtersMap) {
    List<String> mime = extractStringList(filtersMap.get("mime"));
    List<String> language = extractStringList(filtersMap.get("language"));
    List<String> fileKind = extractStringList(filtersMap.get("fileKind"));
    List<String> mimeBase = extractStringList(filtersMap.get("mimeBase"));
    String pathPrefix = (filtersMap.get("pathPrefix") instanceof String s && !s.isBlank()) ? s : null;
    Boolean includeChunks = (filtersMap.get("includeChunks") instanceof Boolean b) ? b : null;

    KnowledgeSearchRequest.TimeRangeMs modifiedAt = null;
    Object modifiedAtRaw = filtersMap.get("modifiedAt");
    if (modifiedAtRaw instanceof Map<?, ?> m) {
      Long fromMs = (m.get("fromMs") instanceof Number n) ? n.longValue() : null;
      Long toMs = (m.get("toMs") instanceof Number n) ? n.longValue() : null;
      if ((fromMs != null && fromMs > 0) || (toMs != null && toMs > 0)) {
        modifiedAt = new KnowledgeSearchRequest.TimeRangeMs(fromMs, toMs);
      }
    }
    // Parse entity and metadata filters (accept both camelCase and snake_case for MCP compat)
    List<String> entityPersons = extractStringListAny(filtersMap, "entityPersons", "entity_persons");
    List<String> entityOrganizations =
        extractStringListAny(filtersMap, "entityOrganizations", "entity_organizations");
    List<String> entityLocations =
        extractStringListAny(filtersMap, "entityLocations", "entity_locations");
    List<String> metaSource = extractStringListAny(filtersMap, "metaSource", "meta_source");
    List<String> metaAuthor = extractStringListAny(filtersMap, "metaAuthor", "meta_author");
    List<String> metaCategory = extractStringListAny(filtersMap, "metaCategory", "meta_category");
    KnowledgeSearchRequest.TimeRangeMs metaPublishedAt = parseMetaPublishedAt(filtersMap);
    List<String> docIds = extractStringListAny(filtersMap, "docIds", "doc_ids");
    // Tempdoc 585 §D Phase 4 (D4b) — the search-scope tag (e.g. "agent-history"). Dropping it here
    // made the worker's default branch always fire MUST_NOT collection:agent-history, so "search your
    // agent history" returned nothing (629 §Open issues #1). Wire key = the record component `collection`.
    List<String> collection = extractStringList(filtersMap.get("collection"));
    return KnowledgeSearchRequestFiltersBuilder.builder()
        .mime(mime)
        .language(language)
        .fileKind(fileKind)
        .mimeBase(mimeBase)
        .pathPrefix(pathPrefix)
        .includeChunks(includeChunks)
        .modifiedAt(modifiedAt)
        .entityPersons(entityPersons)
        .entityOrganizations(entityOrganizations)
        .entityLocations(entityLocations)
        .metaSource(metaSource)
        .metaAuthor(metaAuthor)
        .metaCategory(metaCategory)
        .metaPublishedAt(metaPublishedAt)
        .docIds(docIds)
        .collection(collection)
        .build();
  }

  /** Extracts a string list trying camelCase key first, then snake_case fallback. */
  private static List<String> extractStringListAny(Map<?, ?> map, String camelKey, String snakeKey) {
    Object raw = map.get(camelKey);
    if (raw == null) raw = map.get(snakeKey);
    List<String> result = extractStringList(raw);
    return result.isEmpty() ? null : result;
  }

  /** Parses meta_published_at from filter map (accepts both camelCase and snake_case date keys). */
  private static KnowledgeSearchRequest.TimeRangeMs parseMetaPublishedAt(Map<?, ?> map) {
    // Try structured object first (camelCase from direct API calls)
    Object metaPubRaw = map.get("metaPublishedAt");
    if (metaPubRaw instanceof Map<?, ?> mp) {
      Long from = (mp.get("fromMs") instanceof Number n) ? n.longValue() : null;
      Long to = (mp.get("toMs") instanceof Number n) ? n.longValue() : null;
      if ((from != null && from > 0) || (to != null && to > 0)) {
        return new KnowledgeSearchRequest.TimeRangeMs(from, to);
      }
    }
    // Try ISO date strings (snake_case from MCP)
    Object afterRaw = map.get("meta_published_after");
    Object beforeRaw = map.get("meta_published_before");
    String after = afterRaw instanceof String s ? s : "";
    String before = beforeRaw instanceof String s ? s : "";
    if (!after.isEmpty() || !before.isEmpty()) {
      long from = after.isEmpty() ? 0 : parseIso8601ToEpochMs(after);
      long to = before.isEmpty() ? 0 : parseIso8601ToEpochMs(before);
      if (from > 0 || to > 0) {
        return new KnowledgeSearchRequest.TimeRangeMs(from > 0 ? from : null, to > 0 ? to : null);
      }
    }
    return null;
  }

  private static long parseIso8601ToEpochMs(String iso) {
    try {
      return java.time.Instant.parse(iso + (iso.contains("T") ? "" : "T00:00:00Z")).toEpochMilli();
    } catch (Exception e) {
      try {
        return java.time.LocalDate.parse(iso).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli();
      } catch (Exception e2) {
        return 0;
      }
    }
  }

  /**
   * Handles status requests.
   *
   * GET /api/knowledge/status
   */
  public void handleStatus(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    try {
      if (isWorkerReady()) {
        try {
          KnowledgeStatus indexStatus = adapter.status(engineContext);
          KnowledgeStatusView view = KnowledgeStatusView.from(indexStatus);
          cachedStatus = new CachedStatus(view, System.currentTimeMillis());
          ctx.json(view);
        } catch (Exception e) {
          log.warn("Failed to get index status", e);
          ctx.json(serveStaleOrSparse(workerStateName(), true));
        }
      } else {
        ctx.json(serveStaleOrSparse(workerStateName(), false));
      }

    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge status check failed", e);
      ApiErrorCode code = ApiErrorHandler.resolve(e);
      Map<String, Object> errorResponse =
          new HashMap<>(
              ApiErrorHandler.toResponse(code, e, telemetry, ApiErrorHandler.routeOf(ctx)));
      errorResponse.put("state", "ERROR");
      ctx.status(500).json(errorResponse);
    }
  }

  private static final long STALE_CACHE_MAX_MS = 120_000;

  /**
   * Returns a stale-annotated copy of the last successful status view. If no cache exists
   * or the cache is older than {@link #STALE_CACHE_MAX_MS}, returns a minimal
   * {@code KnowledgeStatusView} with only {@code state}, {@code ready}, and the stale markers
   * populated — consistent JSON shape regardless of cache state.
   */
  private KnowledgeStatusView serveStaleOrSparse(String currentState, boolean currentReady) {
    CachedStatus snapshot = cachedStatus;
    if (snapshot != null) {
      long staleMs = System.currentTimeMillis() - snapshot.atMs();
      if (staleMs <= STALE_CACHE_MAX_MS) {
        return KnowledgeStatusViewBuilder.builder(snapshot.view())
            .state(currentState)
            .ready(currentReady)
            .healthy(false)
            .indexState("UNKNOWN")
            .statusStale(true)
            .statusStaleMs(staleMs)
            .build();
      }
    }
    return KnowledgeStatusViewBuilder.builder()
        .state(currentState)
        .ready(currentReady)
        .healthy(false)
        .indexState("UNKNOWN")
        .statusStale(true)
        .statusStaleMs(snapshot != null ? System.currentTimeMillis() - snapshot.atMs() : 0L)
        .build();
  }

  /**
   * Handles ingest requests.
   *
   * POST /api/knowledge/ingest
   * Body: { "paths": ["/path/to/file1", "/path/to/file2"], "collection": "notes" (optional) }
   */
  public void handleIngest(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) ctx.bodyAsClass(Map.class);
      @SuppressWarnings("unchecked")
      List<String> paths = (List<String>) body.get("paths");

      if (paths == null || paths.isEmpty()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Paths array is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }

      // Tempdoc 811 (C-2a) — optional caller-supplied collection. Validated on the SERVER (the MCP
      // tool schema is a convenience, not a guard): a present-but-non-string value, a blank string,
      // or a reserved app-internal name is a 400.
      Object rawCollection = body.get("collection");
      if (rawCollection != null && !(rawCollection instanceof String)) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "collection must be a string", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      String requestedCollection;
      try {
        requestedCollection = IngestCollectionPolicy.normalizeRequested((String) rawCollection);
      } catch (IllegalArgumentException e) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, e.getMessage(), telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      List<IngestCollectionPolicy.RootBinding> rootBindings = watchedRootBindings(engineContext);

      log.info("Knowledge ingest request: {} roots", paths.size());

      // Tempdoc 418 Phase B — Worker owns the directory walk. For each requested path:
      //  - directory: dispatch to ScanRoot RPC; Worker walks + admits via WorkerIngestionAuthority.
      //  - regular file: keep the legacy submitBatch single-file path (no walk needed).
      // ExcludeGlobs is no longer applied Head-side; the equivalent is handled by
      // WorkerIngestionAuthority.shouldSkip plus the per-request exclude_globs supplied here.
      // Tempdoc 883 decision 4 slice 2: the RESOLVED list (settings.json 300, env 400, -D 500), not
      // the sysprop the settings promotion used to mirror it into. globalOrNull because an ingest
      // request must not 500 on a store that is not up yet; no excludes is the safe answer.
      io.justsearch.configuration.resolved.ConfigStore excludeStore =
          io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      List<String> excludeGlobs =
          ExcludeGlobs.fromRawJsonArray(
                  excludeStore == null ? "" : excludeStore.get().ui().excludePatterns())
              .patterns();
      // Tempdoc 811 (C-2a): single files are grouped by resolved collection so one request can mix
      // in-root (inherited tag) and out-of-root (mcp-ingest) paths without forcing one label on all.
      Map<String, List<Path>> singleFilesByCollection = new java.util.LinkedHashMap<>();
      long totalAdmitted = 0L;
      List<String> terminalReasons = new ArrayList<>();
      // Per docs/reference/api-contract-map.md: directory inputs get a scanId for live progress
      // SSE. Tempdoc 812 D2 — this is the WORKER-allocated id carried back on the scan's progress
      // stream (`KnowledgeIngestResponse.scanId`), the same value `GET /api/scans/{scanId}/progress`
      // subscribes on and the same value the job rows / scan-rollup audit record carry. It used to
      // be a locally-minted UUID that matched nothing: every subscribe against it resolved to
      // UNKNOWN_SCAN_OR_RETENTION_EXPIRED.
      String scanId = null;

      for (String p : paths) {
          Path input = Path.of(p).toAbsolutePath().normalize();
          if (!Files.exists(input)) {
              continue;
          }
          // Tempdoc 811 (C-2a): every ad-hoc ingest now carries an addressable collection. Explicit
          // request value wins; a path under a watched root inherits that root's collection; anything
          // else is out-of-root and lands in `mcp-ingest`. Pre-811 documents indexed through this
          // endpoint carry NO collection field and stay that way — there is no backfill (798
          // precedent: no released users, no migration); they acquire a tag on re-index.
          String collection = IngestCollectionPolicy.resolve(requestedCollection, input, rootBindings);
          if (Files.isDirectory(input)) {
              var scanResp = adapter.scanRoot(input.toString(), collection, excludeGlobs, engineContext);
              if (scanId == null && scanResp.scanId() != null && !scanResp.scanId().isEmpty()) {
                  scanId = scanResp.scanId();
              }
              totalAdmitted += scanResp.accepted();
              if (scanResp.error() != null && !scanResp.error().isEmpty()) {
                  terminalReasons.add(input + ":" + scanResp.error());
              }
          } else if (Files.isRegularFile(input) && Files.isReadable(input)) {
              singleFilesByCollection
                  .computeIfAbsent(collection == null ? "" : collection, k -> new ArrayList<>())
                  .add(input);
          }
      }
      for (Map.Entry<String, List<Path>> group : singleFilesByCollection.entrySet()) {
          String collection = group.getKey().isEmpty() ? null : group.getKey();
          var ingestResp = adapter.ingest(group.getValue(), collection, engineContext);
          totalAdmitted += ingestResp.accepted();
          if (ingestResp.error() != null && !ingestResp.error().isEmpty()) {
              terminalReasons.add("files:" + ingestResp.error());
          }
      }

      log.info("Worker-side scan accepted {} files across {} roots", totalAdmitted, paths.size());

      // B-H.4 defect K — KnowledgeIngestResponse.accepted is int. At desktop scale the cast is a
      // no-op; the explicit conversion documents the contract and converts overflow into an
      // ArithmeticException caught by the outer Exception handler (→ 500) instead of silent
      // truncation in the JSON serializer.
      Map<String, Object> resp = new java.util.LinkedHashMap<>();
      resp.put("accepted", Math.toIntExact(totalAdmitted));
      resp.put("error", String.join("; ", terminalReasons));
      if (scanId != null) {
          resp.put("scanId", scanId);
      }
      ctx.json(resp);

    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge ingest failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Tempdoc 811 (C-2a) — the watched-root containment authority for ingest tagging. Reads the same
   * registry {@code GET /api/indexing/roots} serves ({@code KnowledgeClient} implements {@code
   * IndexingService}, delegating to {@code RootLifecycleOps}'s watched-root state), so an in-root
   * ad-hoc ingest inherits exactly the collection the root's own scan writes. Best-effort: when the
   * Worker is not connected, an empty binding list makes every path resolve out-of-root, which is
   * the safe direction (a real tag rather than the pre-811 {@code null}).
   */
  private List<IngestCollectionPolicy.RootBinding> watchedRootBindings(EngineContext engineContext) {
    try {
      return knowledgeServer.client().getWatchedRoots(engineContext).stream()
          .filter(r -> r != null && r.path() != null)
          .map(r -> new IngestCollectionPolicy.RootBinding(r.path(), r.collection()))
          .toList();
    } catch (Exception e) {
      log.debug("watched-root lookup for ingest tagging failed: {}", e.toString());
      return List.of();
    }
  }

  /**
   * Handles suggest/autocomplete requests.
   *
   * <p>GET /api/knowledge/suggest?query=prefix&amp;limit=5
   */
  public void handleSuggest(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    try {
      if (!isWorkerReady()) {
        Map<String, Object> resp = new java.util.LinkedHashMap<>(
            ApiErrorHandler.toResponse(ApiErrorCode.SERVICE_UNAVAILABLE, "Knowledge Server not ready", telemetry, ApiErrorHandler.routeOf(ctx)));
        resp.put("state", workerStateName());
        ctx.status(503).json(resp);
        return;
      }

      String query = ctx.queryParam("query");
      if (query == null || query.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "Query parameter is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }

      String limitParam = ctx.queryParam("limit");
      int limit = 5;
      if (limitParam != null) {
        try {
          limit = Math.max(1, Math.min(20, Integer.parseInt(limitParam)));
        } catch (NumberFormatException ignored) {
          // use default
        }
      }

      log.info("Knowledge suggest: query='{}', limit={}", redact(new SensitiveQuery(query)), limit);

      try {
        knowledgeServer.recordUserActivity();
      } catch (Exception ignored) {
        // best-effort
      }

      List<String> suggestions = adapter.suggest(query, limit, engineContext);
      ctx.json(Map.of("suggestions", suggestions));

    } catch (KnowledgeClientException e) {
      int http = ApiErrorHandler.mapClientStatusToHttp(e.status());
      ApiErrorCode code = ApiErrorHandler.resolve(e);
      ctx.status(http).json(ApiErrorHandler.toResponse(code, e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge suggest failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  // ========== Folder Browse ==========

  /**
   * Lists child folders under a parent path.
   *
   * <p>POST /api/knowledge/folders
   * Body: { "parentPath": "D:\\Documents\\", "maxFolders": 200 }
   */
  public void handleListFolders(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) ctx.bodyAsClass(Map.class);
      String parentPath = body.get("parentPath") instanceof String s ? s : null;
      if (parentPath == null || parentPath.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "parentPath is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      Integer maxFolders = body.get("maxFolders") instanceof Number n ? n.intValue() : null;

      try {
        knowledgeServer.recordUserActivity();
      } catch (Exception ignored) {
        // best-effort
      }

      log.info("Knowledge listFolders: parentPath='{}', maxFolders={}",
          redact(new SensitiveQuery(parentPath)), maxFolders);

      FolderBrowseRequest req = new FolderBrowseRequest(parentPath, maxFolders);
      FolderBrowseResponse response = adapter.listFolders(req, engineContext);
      ctx.json(response);

    } catch (KnowledgeClientException e) {
      int http = ApiErrorHandler.mapClientStatusToHttp(e.status());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge listFolders failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }

  /**
   * Lists files directly within a folder.
   *
   * <p>POST /api/knowledge/folder-files
   * Body: { "folderPath": "D:\\Documents\\Reports\\", "limit": 100 }
   */
  public void handleListFolderFiles(Context ctx) {
    var engineContext = RequestEngineContext.get(ctx);
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> body = (Map<String, Object>) ctx.bodyAsClass(Map.class);
      String folderPath = body.get("folderPath") instanceof String s ? s : null;
      if (folderPath == null || folderPath.isBlank()) {
        ctx.status(400).json(ApiErrorHandler.toResponse(ApiErrorCode.INVALID_REQUEST, "folderPath is required", telemetry, ApiErrorHandler.routeOf(ctx)));
        return;
      }
      Integer limit = body.get("limit") instanceof Number n ? n.intValue() : null;

      @SuppressWarnings("unchecked")
      List<String> projection = body.get("projection") instanceof List<?> list
          ? list.stream().filter(String.class::isInstance).map(String.class::cast).toList()
          : List.of();

      try {
        knowledgeServer.recordUserActivity();
      } catch (Exception ignored) {
        // best-effort
      }

      log.info("Knowledge listFolderFiles: folderPath='{}', limit={}",
          redact(new SensitiveQuery(folderPath)), limit);

      FolderFilesRequest req = new FolderFilesRequest(folderPath, limit, projection);
      FolderFilesResponse response = adapter.listFolderFiles(req, engineContext);
      ctx.json(response);

    } catch (KnowledgeClientException e) {
      int http = ApiErrorHandler.mapClientStatusToHttp(e.status());
      ctx.status(http).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    } catch (Exception e) {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Knowledge listFolderFiles failed", e);
      ctx.status(500).json(ApiErrorHandler.toResponse(e, telemetry, ApiErrorHandler.routeOf(ctx)));
    }
  }
}
