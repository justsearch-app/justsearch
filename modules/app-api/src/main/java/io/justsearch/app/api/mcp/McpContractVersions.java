/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.mcp;

/**
 * Single source of truth for the versions of JustSearch's MCP surface (tempdoc 654).
 *
 * <p>Two orthogonal versions live here so that every surface that reports them — the MCP
 * {@code initialize} response ({@code McpProtocolHandler}) and the runtime manifest's
 * {@link io.justsearch.app.api.runtime.RuntimeContract} — reads the same constants by
 * construction, rather than each carrying its own literal (a fork that would silently drift).
 *
 * <ul>
 *   <li>{@link #PROTOCOL_VERSION} — the Model Context Protocol spec version this server speaks,
 *       negotiated in {@code initialize}. A dated MCP version string; bumped only when the spec
 *       makes a backward-incompatible change (MCP's own rule). Not a JustSearch choice.
 *   <li>{@link #TOOL_SURFACE_VERSION} — JustSearch's OWN version for its curated tool surface,
 *       which the MCP protocol version says nothing about. MCP has no shipped tool-surface
 *       versioning yet (SEP-986 / SEP-1575 point at per-tool SemVer), so JustSearch versions it
 *       here, SemVer-shaped, pre-aligned to that direction. Reported as {@code
 *       serverInfo._meta["io.justsearch/toolSurfaceVersion"]} and as the runtime contract's
 *       {@code mcpToolSurfaceVersion} constituent. Starts pre-1.0 per the under-promise stance.
 *       NOT {@code serverInfo.version} — that slot is the server implementation's (build) version;
 *       reporting the tool surface there showed {@code 0.5.0} on a {@code 0.2.0} build (tempdoc 804
 *       §B9, round-10 F12).
 * </ul>
 */
public final class McpContractVersions {

  /** MCP spec version negotiated in {@code initialize}. Dated per the MCP versioning rule. */
  public static final String PROTOCOL_VERSION = "2025-11-25";

  /**
   * JustSearch's own curated-tool-surface version (SemVer). Pre-1.0 by the under-promise stance
   * (tempdoc 654 §D3/D5): the surface may still change while we settle it.
   *
   * <p>0.2.0 (tempdocs 655 + 658): the curated surface gained a connect-time {@code instructions}
   * field (comparative tool-selection guidance) and comparative response hints (655), plus a
   * machine-readable {@code structuredContent} retrieval-evidence payload on search/answer and an
   * opt-in {@code detail} argument (658) — material, agent-visible additions to the surface, so the
   * SemVer minor bumps. Single-sourced here, it projects by construction into MCP
   * {@code serverInfo._meta} and the runtime manifest's {@code mcpToolSurfaceVersion}.
   *
   * <p>0.3.0 (tempdoc 725, increments W1-W3): {@code justsearch_search} responses gained
   * match-anchored previews and rationale/degradation/coverage lines; {@code justsearch_answer}
   * gained a self-describing evidence-pack header; both tools gained an opt-in {@code
   * response_format} ("concise"/"detailed") argument; error results across the surface gained a
   * uniform, descriptive failure grammar pointing at {@code justsearch_status} — material,
   * agent-visible additions, so the SemVer minor bumps again.
   *
   * <p>0.3.1 (tempdoc 732 item 3 / 731 I6a): the {@code response_format} schema description and
   * the single-sourced tool-selection guidance were reworded to state the concise/detailed
   * per-call token-size tradeoff explicitly (both changes to published {@code tools/list} bytes);
   * {@code justsearch_answer}'s evidence-pack header gained a descriptive pack-selection facts
   * line ({@code chunksIncluded}/{@code chunksConsidered}/{@code retrievalCoverage}) when the
   * retrieval's quality signals are populated — agent-visible text additions to the published
   * surface, so the SemVer patch bumps.
   *
   * <p>0.4.0 (tempdoc 735 W6, tier equivalence): {@code justsearch_search} and {@code
   * justsearch_answer}'s {@code structuredContent} gained {@code hints}, {@code facets}, {@code
   * coverage}, and {@code truncated} fields — the response-level facts the text tier already
   * carried (progressive-disclosure hints, facet values, totalHits/shown/tookMs, truncation) but
   * structuredContent historically dropped. A structured-preferring client (observed default:
   * Claude Code CLI 2.1.209, which delivers structuredContent verbatim when present and drops the
   * text tier entirely) previously never saw these facts at all. Both tiers now derive from one
   * shared per-request content model ({@code McpSearchResponseContent} /
   * {@code McpAnswerResponseContent}), so they cannot silently diverge again — a new,
   * agent-visible material addition to the structured surface, so the SemVer minor bumps.
   *
   * <p>0.5.0 (tempdoc 770, tool-surface economy): measurement over 1,078 recovered v5 payloads
   * showed the per-hit ranking-provenance block ({@code trace} + {@code legScores}) is 19.9% of the
   * delivered {@code justsearch_search} payload while carrying no document content, and that
   * {@code hit.path} was byte-identical to {@code hit.id} in all 14,617 measured hits. So the
   * default {@code structuredContent} now omits {@code trace}/{@code legScores} (recoverable via
   * the existing {@code detail} argument, whose meaning widens from "the numeric sub-tier" to "the
   * whole provenance block") and emits {@code id} only when it differs from {@code path} —
   * {@code path} is the field kept, being the affordance-bearing name the agent acts on. Excerpts,
   * scores, and the query-level search trace are unchanged. Three false statements were also
   * corrected in the published {@code tools/list} descriptions (a {@code querySyntax} parameter
   * that {@code SEARCH_SCHEMA} did not accept and the validator silently ignored — now a declared
   * schema parameter threaded through to the request, so the sentence is restored and true; a
   * "first search returns facets" claim about behavior that happens on every call with facetable
   * hits; and a "concise returns substantially fewer tokens" promise that measured zero reduction
   * across 336 opt-ins because it trims only the text tier). Finally, {@code justsearch_answer} no
   * longer fires a second full hybrid search per call for a facet sidecar, so its {@code
   * structuredContent} no longer carries {@code facets}.
   *
   * <p>Removing default fields is a removal under the stability policy. It is deliberate, and the
   * decision — including that the 90-day deprecation window is overridden here by the pre-1.0
   * clause — is recorded in the Runtime Contract changelog
   * ({@code docs/reference/runtime-contract.md}), which also bumps the umbrella contract version
   * to {@code 0.2.0} because this is the first break to a constituent. The SemVer MINOR (not
   * major) is a pre-1.0 judgement, not a reachability claim: {@code trace}/{@code legScores} stay
   * reachable via {@code detail}, but {@code justsearch_answer}'s {@code facets} has no other
   * source and IS now unreachable. That deletion is justified by measured non-use — facet/hint
   * affordances produced zero behavioral adoption at haiku across three campaigns
   * ({@code 735:471-474}) — against the cost of a full extra hybrid search per call, not by
   * reachability.
   *
   * <p>0.6.0 (tempdoc 821 §3-C2, RAG collection scoping): the shared {@code filters} input schema
   * gains a {@code collection} string array, and {@code justsearch_answer} now honours it — the
   * scope previously reached only the search path, so an agent asking a question could not restrict
   * retrieval to a collection. Additive (a new optional input property; omitting it is the
   * unchanged default scope), hence a SemVer MINOR with no removal and no deprecation window. One
   * narrowing rides along and is NOT purely additive: declaring the property means the boundary
   * validator now rejects a bare-string {@code collection} instead of coercing it. On
   * {@code justsearch_answer} nothing could break (the key was neither declared nor read), but on
   * {@code justsearch_search} the undeclared key WAS functional — {@code McpToolSurface#parseFilters}
   * coerced a bare string via {@code toStringList} — so a client that discovered it out-of-band must
   * now send an array.
   *
   * <p>0.7.0: tool failures gain optional structured error code, class and retryability,
   * preserving the same known facts in text. Generic failures no longer suggest transience
   * independently of the existing API classification. Tool names and input schemas are unchanged.
   *
   * <p>0.8.0: browse/ingest gain optional operationKey transport metadata, preserved through
   * approval and excluded from handler public input. Failed attempts retain receipt identity
   * in both delivery tiers. Existing calls without the property retain their behavior.
   */
  public static final String TOOL_SURFACE_VERSION = "0.8.0";

  private McpContractVersions() {}
}
