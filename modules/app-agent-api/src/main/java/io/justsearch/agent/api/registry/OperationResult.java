/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of an Operation invocation.
 *
 * <p>Per tempdoc 429 §E.3: the {@code executionId} field threads the batch identifier
 * through the dispatch path so {@link OperationHandler#undo(String)} can later look up
 * the batch. {@link io.justsearch.agent.tools.FileOperationExecutor} (existing 1.1.d
 * code) generates {@code batchId} via {@code UUID.randomUUID().toString()} at execute
 * time; the new substrate threads that same UUID through {@code OperationResult.executionId}
 * so the existing log format stays readable.
 *
 * <p>{@code structuredData} is an optional handler-specific payload (e.g., search results,
 * browse listings); the agent loop's text-projection logic consumes {@code message} for
 * LLM-visible output and may inspect {@code structuredData} for richer surfaces.
 *
 * <p>Slice 3a-2-c Phase B: typed error metadata. {@code errorCode}, {@code errorDetails},
 * and {@code retryable} restore the typed-error semantics that 7 migrated Operations lost
 * by going through the substrate. Legacy HTTP handlers carry typed
 * {@link io.justsearch.app.api.ApiErrorCode} values (POLICY_ONLINE_AI_DISABLED,
 * INSUFFICIENT_VRAM, PACK_IMPORT_RUNNING, etc.); FE consumers branch on these for
 * banners, retry hints, and PERMANENT/TRANSIENT classification. The fields are populated
 * from typed exceptions (ModeTransitionException, AiInstallException,
 * UserPolicyWriteException) at handler-result-construction time; success paths leave
 * them empty.
 */
public record OperationResult(
    boolean success,
    String message,
    Optional<String> executionId,
    Map<String, Object> structuredData,
    Optional<String> errorCode,
    Map<String, Object> errorDetails,
    Optional<Boolean> retryable) {

  public OperationResult {
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(executionId, "executionId");
    Objects.requireNonNull(errorCode, "errorCode");
    Objects.requireNonNull(retryable, "retryable");
    structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
    errorDetails = errorDetails == null ? Map.of() : Map.copyOf(errorDetails);
  }

  /** Shared token grammar for durable receipts and expected pre-effect refusals. */
  public static boolean isDurableOutcomeCode(String code) {
    return code != null && code.matches("[A-Za-z][A-Za-z0-9_]{0,95}");
  }

  /** Success without undo support. */
  public static OperationResult success(String message) {
    return new OperationResult(
        true, message, Optional.empty(), Map.of(), Optional.empty(), Map.of(), Optional.empty());
  }

  /** Success with an undo-eligible execution id (e.g., FileOperations batchId). */
  public static OperationResult success(String message, String executionId) {
    return new OperationResult(
        true,
        message,
        Optional.of(executionId),
        Map.of(),
        Optional.empty(),
        Map.of(),
        Optional.empty());
  }

  /** Success with structured payload (e.g., search results). */
  public static OperationResult success(String message, Map<String, Object> structuredData) {
    return new OperationResult(
        true,
        message,
        Optional.empty(),
        structuredData,
        Optional.empty(),
        Map.of(),
        Optional.empty());
  }

  /**
   * Tempdoc 577 §2.14 Root III (#18) — return a copy carrying the text-provenance lineage in
   * {@code structuredData.lineage}, so the FE can frame the tool output by where its bytes came from
   * (corpus-quoted vs runtime). Idempotent merge over the existing structuredData; other fields
   * unchanged. Applied once at the dispatch seam — the single authoritative stamp.
   */
  public OperationResult withLineage(OutputLineage lineage) {
    Map<String, Object> merged = new java.util.HashMap<>(structuredData);
    merged.put(LINEAGE_KEY, lineage.wireToken());
    return new OperationResult(
        success, message, executionId, merged, errorCode, errorDetails, retryable);
  }

  /**
   * Tempdoc 865 §7.1 — return a copy carrying, under {@code structuredData.grounding}, the grounding
   * sources THIS tool call newly established. Idempotent merge over the existing structuredData;
   * other fields unchanged. Applied once at the dispatch seam — the single authoritative stamp,
   * exactly as {@link #withLineage} is.
   *
   * <p>This is the CARRIER decision, and it is the reason evidence now survives a run that never
   * reaches a grounded terminal. {@code tool_exec_completed} already projects onto both planes — the
   * wire ({@code AgentEventPayloads.toolCompletedPayload}) and the persisted record ({@code
   * AgentInteractionMapper}'s {@code tool_exec_completed} case) — so a delta is durable the moment
   * its call completes, with no new event kind, no descriptor, and no timeline row.
   *
   * <p>The sources are projected to their WIRE shape here rather than handed to the serializer as
   * records, because {@code structuredData} is declared free-form ({@code AgentRunShape}: {@code
   * EventField.object("structuredData", "")}) and therefore carries no descriptor to pin this key
   * against. Writing the nine keys explicitly is what makes the shape identical on the live and the
   * reloaded path, and pinnable by a conformance test in place of the descriptor.
   *
   * <p>Tempdoc 865 §7.5 + 868 §B.3 — the nine are {@code AgentSource}'s IDENTITY components
   * (including {@code acquisition}), and the record's two INCLUSION components are deliberately not
   * among them. Inclusion is resolved against the final prompt at the terminal; a tool call has no
   * final prompt to be a fact about, so a delta that carried the key would be claiming one.
   * Acquisition is the opposite case: it is known exactly when the source is minted.
   *
   * @param sources the delta — never the running total, so a long run does not re-send its whole
   *     evidence set every step. Callers omit the stamp entirely when the delta is empty.
   */
  public OperationResult withGrounding(
      java.util.List<io.justsearch.agent.api.AgentEvent.AgentSource> sources) {
    java.util.List<Map<String, Object>> wire = new java.util.ArrayList<>(sources.size());
    for (io.justsearch.agent.api.AgentEvent.AgentSource s : sources) {
      var item = new java.util.LinkedHashMap<String, Object>();
      item.put("parentDocId", s.parentDocId());
      item.put("chunkIndex", s.chunkIndex());
      item.put("path", s.path());
      item.put("title", s.title());
      item.put("excerpt", s.excerpt());
      item.put("startLine", s.startLine());
      item.put("endLine", s.endLine());
      item.put("headingText", s.headingText());
      // Tempdoc 868 §B.3 — acquisition IS an identity component (fixed at the mint), so unlike the
      // two inclusion keys it belongs in the delta: a reloaded run must not silently re-describe an
      // opened document as retrieved.
      item.put("acquisition", s.acquisition());
      wire.add(Map.copyOf(item));
    }
    Map<String, Object> merged = new java.util.HashMap<>(structuredData);
    merged.put(GROUNDING_KEY, java.util.List.copyOf(wire));
    return new OperationResult(
        success, message, executionId, merged, errorCode, errorDetails, retryable);
  }

  /**
   * Tempdoc 865 §7.1 — the {@code structuredData} key {@link #withGrounding} stamps.
   *
   * <p>Exported so the stamp and the JAVA readers (the conformance test, the terminal-equivalence
   * tests) name one constant instead of repeating a literal. It unifies only the Java side: the TS
   * readers on both planes necessarily hold their own literal, because the key crosses a wire. What
   * keeps those honest is not this constant but the equality the conformance test pins — the key and
   * its eight fields, asserted here in the descriptor's place, since {@code structuredData} is
   * declared free-form and no schema gate can see it.
   */
  public static final String GROUNDING_KEY = "grounding";

  /**
   * Tempdoc 877 §2.3 — the other four {@code structuredData} keys that cross a producer/consumer
   * seam, given the same treatment {@link #GROUNDING_KEY} got in 865: one constant the Java sides
   * import, plus a per-key conformance test pinning the constant's VALUE to the literal its
   * consumer holds.
   *
   * <p>A constant cannot unify a key that crosses a WIRE — the TypeScript readers necessarily spell
   * it themselves. What the constants buy is the Java half (a rename now breaks the compile instead
   * of silently producing a key nobody reads), and what the conformance tests buy is the other half
   * (a rename that compiles still fails the build, naming the TS file that would have gone blind).
   *
   * <p>Not centralised, deliberately: {@code query}, {@code resultCount} and {@code searchMode}.
   * Each has exactly one Java producer and no Java consumer, so a constant for them would name a
   * fact that is already single. They join this set if a second producer ever appears.
   */
  public static final String SEARCH_RESULTS_KEY = "searchResults";

  /**
   * The read tool's producer key (868 §B.3). Deliberately NOT {@link #SEARCH_RESULTS_KEY}: the two
   * keys are what decide a grounding source's {@code acquisition}, so a read tool emitting the
   * search key would mint sources indistinguishable from search hits.
   */
  public static final String READ_RESULTS_KEY = "readResults";

  /** The agent-citation feedback channel (580 §17 P4 Fix B). Never rendered by the tool card. */
  public static final String FEEDBACK_FEATURES_KEY = "feedbackFeatures";

  /**
   * Tempdoc 878 §D.5 — the {@code structuredData} key {@link #withLineage} stamps, and the ONLY
   * CLASSIFICATION stamp on this record.
   *
   * <p>That distinction is what {@code RunChannelPolicy} reads it for. A tool result is EVIDENCE
   * when it carries bulk — search hits, read pages, a grounding delta — and NARRATIVE when it is a
   * plain success or failure message. The discriminator used to be "is {@code structuredData}
   * present at all", which stopped separating the two the moment 577 began stamping a lineage onto
   * every successful result: a bare "ok" now carries a key.
   *
   * <p>The rule the reader applies is the inverse of listing the bulk keys: <b>a classification
   * stamp is not bulk.</b> Listing bulk keys rots silently the first time a producer adds one;
   * excluding the stamps rots only when a new STAMP is added, and that failure is visible (a
   * narrative frame classified as evidence — today's state, so it cannot get worse). {@link
   * #GROUNDING_KEY} is deliberately NOT in that exclusion: a grounding delta carries the excerpts,
   * so it is content, not classification.
   */
  public static final String LINEAGE_KEY = "lineage";

  /** Failure with reason. No executionId attached (failed invocations cannot be undone). */
  public static OperationResult failure(String message) {
    return new OperationResult(
        false, message, Optional.empty(), Map.of(), Optional.empty(), Map.of(), Optional.empty());
  }

  /**
   * Failure with typed error metadata (slice 3a-2-c Phase B).
   *
   * @param message human-readable error message
   * @param errorCode canonical error token consumers can branch on (e.g.,
   *     "POLICY_ONLINE_AI_DISABLED", "INSUFFICIENT_VRAM"); typically maps to a value
   *     in {@code io.justsearch.app.api.ApiErrorCode}
   * @param errorDetails optional structured details (e.g., {@code mode}, {@code causes}
   *     chain); empty map if not applicable
   * @param retryable whether the caller should retry; null if unknown / not classified
   */
  public static OperationResult failure(
      String message,
      String errorCode,
      Map<String, Object> errorDetails,
      Boolean retryable) {
    return new OperationResult(
        false,
        message,
        Optional.empty(),
        Map.of(),
        Optional.ofNullable(errorCode),
        errorDetails == null ? Map.of() : errorDetails,
        Optional.ofNullable(retryable));
  }
}
