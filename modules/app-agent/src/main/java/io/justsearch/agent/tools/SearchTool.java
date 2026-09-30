/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.core.context.EngineContext;

import tools.jackson.databind.JsonNode;
import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.core.util.ContextBudget;
import io.justsearch.app.api.knowledge.KnowledgeSearchHitIdentity;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequestFiltersBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.app.api.knowledge.PipelineConfig;
import io.justsearch.configuration.resolved.ConfigStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Read-only tool for querying the knowledge index. Auto-approved (no user gate).
 *
 * <p>Returns a compact text summary of search results for LLM consumption, including titles, paths,
 * scores, and excerpt snippets.
 */
/**
 * Read-only knowledge-index search tool. It is its own
 * {@link io.justsearch.agent.api.registry.OperationHandler}: the substrate dispatches
 * {@code execute(String): OperationResult} directly against this class.
 */
public final class SearchTool implements OperationHandler {
  // Three-layer truncation for search results delivered to the LLM (see tempdocs 208, 213):
  // Layer 1 (formatResults): sizes the WHOLE emitted string under the Layer-2 cap by
  //   construction — summary reserved first, then every hit's identity block, then excerpts with
  //   whatever is left, spread top-down so a dropped hit is always a tail hit and is announced.
  //   — 800-char per-region cap preserved as safety net for very long individual regions.
  // Layer 2 (AgentContextCompressor.truncate): hard cut at ToolResultCarrier.layerTwoCapChars.
  // Layer 3 (AgentLoopService.compressToolMessagesForContext): strips Excerpt: lines from
  //   older tool messages to free context for subsequent iterations.
  // The k=3 default was set by tempdoc 213, superseding limit-5 from tempdoc 208 compression work.
  private static final int DEFAULT_LIMIT =
      Math.max(1, Math.min(20, resolveSearchDefaultLimit()));
  private static final int MAX_LIMIT = 20;

  private static int resolveSearchDefaultLimit() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().agent().searchDefaultLimit() : 3;
  }

  /**
   * Resolves the default search mode from config. Returns null (meaning text/BM25) when unset,
   * or a mode string like "hybrid" or "vector" when configured via
   * JUSTSEARCH_AGENT_SEARCH_DEFAULT_MODE.
   */
  private static String resolveSearchDefaultMode() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs != null) {
      String mode = cs.get().agent().searchDefaultMode();
      if (mode != null && !mode.isBlank()) {
        return mode;
      }
    }
    return null;
  }

  /**
   * Translates a mode string to a PipelineConfig preset (256: Phase G2). Keeps the mode parameter
   * in the tool schema for backward compatibility with trained LLMs.
   */
  static PipelineConfig modeToPreset(String mode) {
    if (mode == null || mode.isBlank()) return PipelineConfig.HYBRID;
    return switch (mode.toLowerCase(java.util.Locale.ROOT)) {
      case "text" -> PipelineConfig.TEXT;
      case "vector" -> PipelineConfig.VECTOR;
      case "hybrid" -> PipelineConfig.HYBRID;
      default -> PipelineConfig.HYBRID;
    };
  }

  /**
   * Tempdoc 867 — the RESOLVED mode/preset this call actually ran with, after config-default
   * resolution ({@link #modeToPreset}'s own defaulting rules, restated here rather than reverse-
   * derived from the {@link PipelineConfig} it produced, so the stamp cannot drift from the
   * decision). A fine-grained {@code pipeline} object has no single named mode, so it stamps
   * {@code "custom"} rather than guessing one of the three presets for it.
   */
  private static String resolveEffectiveSearchMode(JsonNode args) {
    if (args.has("pipeline") && args.get("pipeline").isObject()) {
      return "custom";
    }
    String modeStr = ToolArgs.stringArg(args, "mode");
    if (modeStr == null) {
      modeStr = resolveSearchDefaultMode();
    }
    if (modeStr == null || modeStr.isBlank()) {
      return "hybrid";
    }
    return switch (modeStr.toLowerCase(java.util.Locale.ROOT)) {
      case "text" -> "text";
      case "vector" -> "vector";
      case "hybrid" -> "hybrid";
      default -> "hybrid";
    };
  }

  /** Parses a JSON pipeline argument into a PipelineConfig (256: Phase H1). */
  static PipelineConfig parsePipelineArg(JsonNode node) {
    return new PipelineConfig(
        ToolArgs.boolArg(node, "sparseEnabled"),
        ToolArgs.boolArg(node, "denseEnabled"),
        ToolArgs.boolArg(node, "spladeEnabled"),
        ToolArgs.stringArg(node, "fusionAlgorithm", "none"),
        ToolArgs.boolArg(node, "lambdamartEnabled"),
        ToolArgs.boolArg(node, "crossEncoderEnabled"),
        ToolArgs.intArg(node, "crossEncoderWindow", 0, Integer.MIN_VALUE, Integer.MAX_VALUE),
        ToolArgs.boolArg(node, "expansionEnabled"),
        ToolArgs.boolArg(node, "freshnessEnabled"));
  }

  /**
   * Tempdoc 561 P-A5 — bound a rendered excerpt/preview to {@code maxLen} chars at a WORD boundary,
   * never a raw mid-word cut. Mirrors the producer-owned-boundary fix made for
   * {@code ContextCitation} in {@code RagContextOps#clampExcerptToWordBoundary}; kept local (a
   * separate module + a separate, agent-context budget — per AHA, not over-DRYed across modules).
   * Walks back to the last whitespace within a 40-char lookback so a single long token still
   * truncates; appends an ellipsis when truncated.
   */
  private static String clampToWordBoundary(String text, int maxLen) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxLen || maxLen <= 0) {
      return text;
    }
    int scan = maxLen;
    while (scan > maxLen - 40 && scan > 0 && !Character.isWhitespace(text.charAt(scan))) {
      scan--;
    }
    int cut = (scan > 0 && Character.isWhitespace(text.charAt(scan))) ? scan : maxLen;
    return text.substring(0, cut).stripTrailing() + "...";
  }

  private final SearchCallback searchCallback;
  private final AgentToolPaths.RootsView rootsView;

  /**
   * The live per-call budget (tempdoc 883 decision 3). The result-set cap used to come from a
   * {@code static final} frozen at class-init; it is now a fraction of the window the running
   * server actually has, read per call.
   */
  private final Supplier<ContextBudget> budget;

  public SearchTool(SearchCallback searchCallback) {
    this(searchCallback, (java.util.function.Function<EngineContext, List<BrowseTool.RootInfo>>) null);
  }

  public SearchTool(
      SearchCallback searchCallback, java.util.function.Function<EngineContext, List<BrowseTool.RootInfo>> rootsSupplier) {
    this(searchCallback, AgentToolPaths.RootsView.of(rootsSupplier));
  }

  /** Tempdoc 877 §2.4 — the shared roots view {@code AgentToolFactory.assemble} builds once. */
  public SearchTool(SearchCallback searchCallback, AgentToolPaths.RootsView rootsView) {
    this(searchCallback, rootsView, null);
  }

  /**
   * Tempdoc 883 decision 3 — the composition-root constructor: roots view plus the live context
   * budget. A null budget supplier falls back to the no-server budget, which is what an
   * inference-less caller (a test, a boot before activation) actually has.
   */
  public SearchTool(
      SearchCallback searchCallback,
      AgentToolPaths.RootsView rootsView,
      Supplier<ContextBudget> budget) {
    this.searchCallback = searchCallback;
    this.rootsView = rootsView == null ? AgentToolPaths.RootsView.of(null) : rootsView;
    this.budget =
        budget == null ? () -> io.justsearch.agent.AgentContextBudgets.forCall(null) : budget;
  }

  @Override
  public OperationResult execute(String argumentsJson, EngineContext engineContext) {
    // Tempdoc 877 §2.1 — the argument keys, and who authors each one. Model-visible: `query`,
    // `limit`, `path_prefix` — exactly what AgentToolsOperationCatalog.searchIndex() declares, which
    // is the only schema the model is shown (AgentOperationEmitter projects op.intf().inputs()).
    // Server-injected: `docIds`, merged in by AgentToolDispatcher.scopeToolCall. Honoured but
    // deliberately undeclared per 868 §B.4: `mode` (the shape the agent.searchDefaultMode config
    // default flows through) and `pipeline` (fine-grained retrieval levers — no production caller
    // supplies it today; only SearchToolTest does).
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);

      // Extract query (required)
      String query = ToolArgs.stringArg(args, "query");
      if (query == null || query.isBlank()) {
        return OperationResult.failure("Search query is required");
      }

      // Sanitize file-path queries: LLM sometimes sends a file path as query text
      // (e.g., "docs/reference/config/env-vars.md"), which causes Lucene parse errors.
      // Convert path separators to spaces and strip file extensions for keyword matching.
      query = sanitizeFilePathQuery(query);

      // Extract optional parameters
      int limit = ToolArgs.intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
      // 256-H1: pipeline parameter overrides mode when both are provided.
      PipelineConfig pipeline;
      if (args.has("pipeline") && args.get("pipeline").isObject()) {
        pipeline = parsePipelineArg(args.get("pipeline"));
      } else {
        String modeStr = ToolArgs.stringArg(args, "mode");
        pipeline = modeToPreset(modeStr == null ? resolveSearchDefaultMode() : modeStr);
      }
      String pathPrefix = ToolArgs.stringArg(args, "path_prefix");
      String effectiveMode = resolveEffectiveSearchMode(args);

      // Resolve relative path_prefix against indexed roots, then validate
      if (pathPrefix != null && !pathPrefix.isBlank()) {
        if (!AgentToolPaths.looksAbsolute(pathPrefix)) {
          String resolved = rootsView.resolveRelative(pathPrefix, engineContext);
          if (resolved != null) {
            pathPrefix = resolved;
          }
        }
        String rejection = rootsView.validate(pathPrefix, "path_prefix", engineContext);
        if (rejection != null) {
          return OperationResult.failure(rejection);
        }
      }

      // Tempdoc S7 — an optional docIds scope (FE "scope chips"), merged into these arguments
      // server-side by AgentToolDispatcher.scopeToolCall when the run carries one. Deliberately
      // NOT declared on AgentToolsOperationCatalog.searchIndex() — the LLM never chooses this key,
      // it rides along silently so every search call in a scoped run stays confined to those paths.
      List<String> docIds = extractDocIds(args);

      // Build search request
      KnowledgeSearchRequest.Filters filters = null;
      if ((pathPrefix != null && !pathPrefix.isBlank()) || !docIds.isEmpty()) {
        var filtersBuilder = KnowledgeSearchRequestFiltersBuilder.builder();
        if (pathPrefix != null && !pathPrefix.isBlank()) {
          filtersBuilder.pathPrefix(pathPrefix);
        }
        if (!docIds.isEmpty()) {
          filtersBuilder.docIds(docIds);
        }
        filters = filtersBuilder.build();
      }

      var request =
          new KnowledgeSearchRequest(
              query, limit, null, null, null, null, filters, null, null, null, true, null, pipeline);

      // Execute search. Tempdoc 877 §2.8 — under the shared fetch budget, so an unresponsive Worker
      // cannot hold the agent loop thread forever (it could, before this).
      KnowledgeSearchResponse response =
          io.justsearch.agent.AgentTimeouts.call(
              "core_search_index", () -> searchCallback.search(request, engineContext));
      if (response == null) {
        return OperationResult.failure("Search returned no response");
      }

      // Format results for LLM consumption
      String formatted = formatResults(response);
      if (response.results().isEmpty() && pathPrefix != null && !AgentToolPaths.looksAbsolute(pathPrefix)) {
        formatted += unresolvedPathPrefixHint(pathPrefix);
      }
      // Tempdoc 561 #6: carry STRUCTURED evidence alongside the LLM-facing text so the tool card can
      // render real evidence cards (filename · location · excerpt) instead of a raw monospace dump.
      // NOTE: deliberately NO relevance score — hit.score() is the uncalibrated RANKING score, which
      // 559 §5 / §18 C-6 say must not be surfaced as a "% relevance" (that would fabricate calibration).
      return OperationResult.success(formatted, buildSearchEvidence(query, effectiveMode, response));

    } catch (Exception e) {
      return AgentToolErrors.classify("core_search_index", "Search error", e);
    }
  }

  /**
   * Tempdoc S7 — the optional {@code docIds} scope (FE "scope chips"), silently accepted alongside
   * the LLM-facing {@code query}/{@code limit}/{@code path_prefix} args but not declared on {@code
   * AgentToolsOperationCatalog.searchIndex()}'s {@code Interface} (tempdoc 877 §2.1 — the one schema
   * the model is shown): {@code AgentToolDispatcher.scopeToolCall} merges it into the arguments JSON
   * server-side for every search call in a scoped run, so the LLM never chooses this key itself.
   */
  private static List<String> extractDocIds(JsonNode args) {
    if (!args.has("docIds") || !args.get("docIds").isArray()) {
      return List.of();
    }
    List<String> out = new ArrayList<>();
    for (JsonNode n : args.get("docIds")) {
      String s = n.asText(null);
      if (s != null && !s.isBlank()) {
        out.add(s);
      }
    }
    return List.copyOf(out);
  }

  /**
   * Tempdoc 561 #6: project the search hits into structured evidence the tool card renders (title /
   * path / excerpt / line) — the producer-owned excerpt is already word-boundary snapped. The
   * rendered {@code searchResults} carry no score (uncalibrated ranking; 559 §5).
   *
   * <p>Tempdoc 580 §17 P4 (Fix B): also emit a SEPARATE {@code feedbackFeatures} list — the per-leg
   * retrieval scores keyed by {@code parentDocId} — for the agent-citation feedback loop. This is a
   * FEEDBACK channel, NOT rendered by the tool card, so it does not surface uncalibrated scores to the
   * UI (the 559 §5 line is about <em>display</em>). app-services captures it into a {@code FeatureSnapshot}
   * from the {@code tool_exec_completed} event so agent CITED/SHOWN dispositions become joinable labels.
   *
   * <p>Tempdoc S7: also carries the executed {@code query} text and the {@code resultCount} (the
   * number of hits in THIS response — mirrors the FE's own {@code resultCount} convention, e.g.
   * {@code UnifiedChatView.ts}'s user-issued-search provenance), additive top-level keys alongside
   * {@code searchResults}/{@code feedbackFeatures} — existing consumers that only read {@code
   * searchResults} (e.g. {@code searchEvidence.ts}) are unaffected.
   *
   * <p>Tempdoc 867 — also carries {@code searchMode}, the RESOLVED mode/preset this call actually ran
   * with ({@link #resolveEffectiveSearchMode}), so the tool card's scope line can say how the search
   * ran without re-deriving it from the raw arguments. Absent on records persisted before this field
   * — the FE renders nothing rather than guess a mode for an old record.
   *
   * <p>Returns {@code {"query": ..., "resultCount": ..., "searchMode": ..., "searchResults": [...],
   * "feedbackFeatures": [...]}}.
   */
  private Map<String, Object> buildSearchEvidence(
      String query, String searchMode, KnowledgeSearchResponse response) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<Map<String, Object>> feedback = new ArrayList<>();
    int rank = 0;
    for (KnowledgeSearchResponse.Hit hit : response.results()) {
      rank++;
      Map<String, Object> hitFeedback = feedbackFeatures(hit, rank);
      if (hitFeedback != null) {
        feedback.add(hitFeedback);
      }
      var fields = hit.fields();
      var item = new LinkedHashMap<String, Object>();
      item.put("title", fields.getOrDefault("title", fields.getOrDefault("filename", "")));
      item.put("path", fields.getOrDefault("path", ""));
      String excerpt;
      int line = 0;
      if (!hit.excerptRegions().isEmpty()) {
        var region = hit.excerptRegions().get(0);
        excerpt = clampToWordBoundary(region.text().strip().replace("\r", "").replace("\n", " "), 320);
        line = region.approxLine();
      } else {
        excerpt = clampToWordBoundary(
            fields.getOrDefault("content_preview", "").strip().replace("\r", "").replace("\n", " "),
            320);
      }
      item.put("excerpt", excerpt);
      item.put("line", line);
      // Tempdoc 565 §3.A — carry the chunk identity + passage span so the answer's grounding is a
      // verifiable, clickable LOCAL-passage citation: parentDocId+chunkIndex let the answer↔source
      // matcher key the source, and path + start/end line let the FE deep-link to the exact lines.
      String parentDocId = fields.getOrDefault("parent_doc_id", "");
      if (!parentDocId.isEmpty()) {
        item.put("parentDocId", parentDocId);
        item.put("chunkIndex", parseIntOr(fields.get("chunk_index"), 0));
        item.put("startLine", parseIntOr(fields.get("chunk_start_line"), line));
        item.put("endLine", parseIntOr(fields.get("chunk_end_line"), line));
        String heading = fields.getOrDefault("chunk_heading_text", "");
        if (!heading.isEmpty()) {
          item.put("headingText", heading);
        }
      }
      out.add(Map.copyOf(item));
    }
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("query", query);
    evidence.put("resultCount", out.size());
    evidence.put("searchMode", searchMode);
    evidence.put(OperationResult.SEARCH_RESULTS_KEY, List.copyOf(out));
    evidence.put(OperationResult.FEEDBACK_FEATURES_KEY, List.copyOf(feedback));
    return Map.copyOf(evidence);
  }

  /**
   * Tempdoc 580 §17 P4 (Fix B) — the per-leg retrieval features for one hit. {@code docId} retains
   * the path-oriented id that agent citations reference; {@code docUid} is the stable parent
   * identity used by the persisted feedback join. Missing or inconsistent UID evidence produces no
   * feedback row. The rendered {@code searchResults} remain unchanged.
   */
  private static Map<String, Object> feedbackFeatures(KnowledgeSearchResponse.Hit hit, int rank) {
    String docId = KnowledgeSearchHitIdentity.sourceDocId(hit);
    String docUid = KnowledgeSearchHitIdentity.stableParentDocUid(hit);
    if (docId == null || docUid == null) {
      return null;
    }
    SearchTrace.LegScores legs = SearchTrace.legScores(hit.trace(), (float) hit.score());
    var f = new LinkedHashMap<String, Object>();
    f.put("docId", docId);
    f.put("docUid", docUid);
    // Tempdoc 931 §C.6 — the parent content revision this hit was ranked at, so an agent-captured
    // label ages the same way a UI-captured one does. Omitted (not null-valued) when the hit
    // carries none, matching the map's other absent-means-unknown entries.
    String contentRevision = KnowledgeSearchHitIdentity.contentRevision(hit);
    if (contentRevision != null) {
      f.put("contentRevision", contentRevision);
    }
    f.put("rank", rank);
    f.put("sparse", legs.sparse());
    f.put("dense", legs.dense());
    f.put("splade", legs.splade());
    f.put("fused", legs.fused());
    return Map.copyOf(f);
  }

  /** Parse a stored string field to int, returning {@code fallback} when absent or malformed. */
  private static int parseIntOr(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  /**
   * Tempdoc 877 §2.2 — the rendered result set, sized to fit the Layer-2 cap BY CONSTRUCTION.
   *
   * <p>The previous budget divided the cap by {@code hits.size()} and then charged each hit only
   * {@code excerpt.length()} — never the {@code [n] title (score)} header, the {@code Path:} line,
   * the carrier line's own framing, nor the trailing summary. At {@code limit:20} that reliably
   * overshot {@code AgentContextCompressor}'s hard cut, and the tail of the list — the later,
   * lower-ranked hits and the "Found N results" summary itself — died inside Layer 2 without a
   * trace. Here the summary is reserved first, and every hit is charged the FULL length of every
   * line it writes, so the returned string is {@code <= layerTwoCapChars(budget)} rather than
   * approximately so.
   *
   * <p><b>Identity is never the part that gets cut.</b> A first version of this budget let a hit
   * whose {@code [n] title} + {@code Path:} block alone overran its slice emit NOTHING — and since a
   * dropped hit forfeited no budget, the slack rolled forward and LOWER-ranked hits rendered while
   * the top of the ranking vanished, with a gap in the {@code [n]} numbering and a summary still
   * claiming all N. A head-cut is strictly worse than the tail-cut it replaced. So the identity
   * blocks are priced FIRST: however many whole hits fit are emitted from the top, in rank order,
   * each guaranteed its header and path with the excerpt budget free to fall to zero, and whatever
   * did not fit is STATED by {@link #omittedNotice} rather than silently missing.
   */
  private String formatResults(KnowledgeSearchResponse response) {
    return formatResults(
        response, io.justsearch.agent.ToolResultCarrier.layerTwoCapChars(budget.get()));
  }

  /**
   * {@link #formatResults(KnowledgeSearchResponse)} with the cap as a parameter, so the
   * constrained-budget path is directly testable without standing up a window.
   */
  static String formatResults(KnowledgeSearchResponse response, int capChars) {
    List<KnowledgeSearchResponse.Hit> hits = response.results();
    if (hits.isEmpty()) {
      return "No results found (took " + response.tookMs() + "ms).";
    }

    String summary = summaryLine(response);
    int budget = Math.max(0, capChars - summary.length());

    // Price the identity blocks up front; prefix[i] is what the first i hits cost in headers alone.
    var identity = new ArrayList<String>(hits.size());
    int[] prefix = new int[hits.size() + 1];
    for (int i = 0; i < hits.size(); i++) {
      identity.add(identityBlock(hits.get(i), i));
      prefix[i + 1] = prefix[i] + identity.get(i).length();
    }

    // The largest prefix of the ranking whose identity blocks — plus the notice naming what is left
    // out — fit the budget. Walking DOWN from the full list keeps the emitted set top-anchored.
    int shown = hits.size();
    while (shown > 0 && prefix[shown] + omittedNotice(hits.size() - shown).length() > budget) {
      shown--;
    }
    String notice = omittedNotice(hits.size() - shown);
    if (prefix[shown] + notice.length() > budget) {
      notice = ""; // shown == 0 and not even the notice fits; the summary alone still must.
    }

    var sb = new StringBuilder();
    for (int i = 0; i < shown; i++) {
      // Reserve the identity of every hit still to come, plus the notice, before this hit may spend
      // anything on excerpts. That reservation is what makes the slice always cover this hit's own
      // identity block, so no emitted hit can lose its header, and the total stays under the cap.
      int reserved = (prefix[shown] - prefix[i + 1]) + notice.length();
      int slice = Math.max(0, budget - sb.length() - reserved);
      appendHit(sb, hits.get(i), identity.get(i), slice);
    }
    sb.append(notice);
    sb.append(summary);
    return sb.toString();
  }

  /** The {@code [n] title (score)} + {@code Path:} lines: what identifies a hit at all. */
  private static String identityBlock(KnowledgeSearchResponse.Hit hit, int index) {
    var fields = hit.fields();
    String title = fields.getOrDefault("title", fields.getOrDefault("filename", "(untitled)"));
    String path = fields.getOrDefault("path", "");
    var block = new StringBuilder();
    block.append(String.format("[%d] %s (score: %.2f)%n", index + 1, title, hit.score()));
    if (!path.isEmpty()) {
      block.append(String.format("    Path: %s%n", path));
    }
    return block.toString();
  }

  /** What the model is told about hits the budget could not carry; empty when none were dropped. */
  private static String omittedNotice(int omitted) {
    return omitted <= 0
        ? ""
        : String.format("... %d further results omitted (context budget)%n", omitted);
  }

  /**
   * One hit's block: its identity lines ALWAYS, then as much carrier text as {@code budget} leaves.
   * The caller guarantees {@code budget >= identity.length()}, so the excerpt allowance may fall to
   * zero but the header and path never do.
   */
  private static void appendHit(
      StringBuilder out, KnowledgeSearchResponse.Hit hit, String identity, int budget) {
    var fields = hit.fields();
    var block = new StringBuilder(identity);
    int remaining = Math.max(0, budget - block.length());

    // Include excerpt regions up to the per-result budget (backend computes up to 3 regions).
    // The 800-char per-region cap is a secondary guard for large-k queries where the per-result
    // budget itself would otherwise allow a single very large region to dominate.
    if (!hit.excerptRegions().isEmpty()) {
      for (var region : hit.excerptRegions()) {
        String excerpt = region.text().strip();
        if (excerpt.isEmpty()) {
          continue;
        }
        excerpt = excerpt.replace("\"", "'").replace("\n", " ").replace("\r", "");
        int room =
            textRoom(remaining, io.justsearch.agent.ToolResultCarrier.excerptLine("").length());
        if (room <= 0) {
          break;
        }
        // Tempdoc 561 P-A5: when bounding to the agent's per-result budget, snap to a word
        // boundary rather than a raw mid-word substring (the producer-owned-boundary principle
        // applied to the agent tool output — the same clip class fixed for ContextCitation).
        String line =
            io.justsearch.agent.ToolResultCarrier.excerptLine(clampToWordBoundary(excerpt, room));
        if (line.length() > remaining) {
          break;
        }
        block.append(line);
        remaining -= line.length();
      }
    } else {
      // Vector search fallback: use content_preview when no excerpt regions
      String preview = fields.getOrDefault("content_preview", "");
      if (!preview.isBlank()) {
        preview = preview.strip().replace("\"", "'").replace("\n", " ").replace("\r", "");
        int room =
            textRoom(remaining, io.justsearch.agent.ToolResultCarrier.previewLine("").length());
        if (room > 0) {
          // Tempdoc 865 §7.5 — the SAME authority the excerpt branch writes through. This branch is
          // the one the inclusion receipt used to be blind to: a dense-only hit never produces an
          // `Excerpt:` line, so a reader keyed on that spelling saw its message as textless.
          String line =
              io.justsearch.agent.ToolResultCarrier.previewLine(clampToWordBoundary(preview, room));
          if (line.length() <= remaining) {
            block.append(line);
          }
        }
      }
    }
    out.append(block);
  }

  /**
   * How much TEXT fits in {@code remaining} once the carrier framing and a possible ellipsis (which
   * {@link #clampToWordBoundary} appends when it truncates) are paid for, capped at 800.
   */
  private static int textRoom(int remaining, int framing) {
    return Math.min(remaining - framing - 3, 800);
  }

  /** The trailing summary, reserved out of the budget before any hit is rendered. */
  private static String summaryLine(KnowledgeSearchResponse response) {
    var sb = new StringBuilder();
    sb.append(
        String.format("%nFound %d results (took %dms).", response.totalHits(), response.tookMs()));
    // Tempdoc 549 Phase E4: read the correction from the unified trace's CORRECTION stage
    // (status=EXECUTED, detail=corrected query). SearchIntrospection was retired.
    String correctedQuery = correctedQueryFromTrace(response.searchTrace());
    if (correctedQuery != null && !correctedQuery.isBlank()) {
      sb.append(String.format(" (corrected to: \"%s\")", correctedQuery));
    }
    return sb.toString();
  }

  /** The corrected query from the unified trace's CORRECTION stage, or null when not applied. */
  private static String correctedQueryFromTrace(
      SearchTrace trace) {
    if (trace == null || trace.stages() == null) {
      return null;
    }
    for (var st : trace.stages()) {
      if (st.id() == SearchTrace.StageId.CORRECTION
          && st.status() == SearchTrace.StageStatus.EXECUTED) {
        return st.detail();
      }
    }
    return null;
  }

  /**
   * Detects file-path-like queries and converts them to keyword-friendly form. Queries containing
   * path separators (/ or \) have extensions stripped and separators replaced with spaces. This
   * prevents Lucene parse errors from slashes and improves keyword matching for path-based queries.
   */
  static String sanitizeFilePathQuery(String query) {
    if (!query.contains("/") && !query.contains("\\")) {
      return query;
    }
    // Strip common file extensions
    String cleaned =
        query.replaceAll("\\.(md|txt|json|yaml|yml|java|xml|html|proto|pdf|csv|toml)$", "");
    // Replace path separators with spaces
    cleaned = cleaned.replace('/', ' ').replace('\\', ' ');
    // Collapse multiple spaces
    cleaned = cleaned.replaceAll("\\s+", " ").trim();
    return cleaned.isEmpty() ? query : cleaned;
  }

  /**
   * Tempdoc 877 §2.7 — the hint that fires when {@code path_prefix} stayed relative after root
   * resolution. It used to say "Use an absolute path from browse_folders", which is advice browse
   * cannot follow: browse emits ROOT-RELATIVE paths (a measured 227 §A.6 decision), so the model was
   * being sent to look for something no tool produces.
   *
   * <p>This branch is reached ONLY when the roots are unknown or empty — with roots known, an
   * unresolvable relative prefix is rejected by {@code RootsView.validate} before the search runs,
   * and that rejection already lists the indexed roots. So the one thing this can usefully say is
   * where the root names come from.
   */
  private static String unresolvedPathPrefixHint(String pathPrefix) {
    return " HINT: The path_prefix \""
        + pathPrefix
        + "\" matched no indexed root folder. Use core_browse_folders to see the root folders, and"
        + " pass a path from its results straight back.";
  }

  /** Callback for executing search queries against the knowledge index. */
  @FunctionalInterface
  public interface SearchCallback {
    KnowledgeSearchResponse search(KnowledgeSearchRequest request, EngineContext engineContext);
  }
}
