/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import io.justsearch.agent.api.AgentErrorCode;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.RunObservation;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DocumentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Holds state for a single agent session (one user interaction). */
final class AgentSession {
  private static final Logger LOG = LoggerFactory.getLogger(AgentSession.class);
  private static final ObjectMapper NORM_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private final List<Map<String, Object>> messages;

  /**
   * Tempdoc 834 §6.2 — a held approval gate now carries the DETAIL of the call it holds, not just
   * the future. The detail already existed one statement earlier in {@code AgentToolDispatcher}
   * (the {@code ToolCallPendingApproval} it emits before opening the gate); keeping it here is what
   * lets {@link AgentEvent.StateSnapshot} carry enough to ANSWER the gate after the announcing
   * frame has been evicted from the replay ring.
   */
  record PendingGate(
      AgentEvent.PendingApproval detail, long sinceEpochMs, CompletableFuture<Boolean> future) {}

  private final Map<String, PendingGate> approvalGates = new ConcurrentHashMap<>();
  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — futures keyed by callId for
   * {@code vop_*} virtual tool invocations. When the LLM calls a
   * virtual tool, the agent loop emits {@code AgentEvent.ToolCallVirtual}
   * and blocks on the corresponding future until the FE POSTs the
   * result via {@code /api/chat/agent/tool-result}. Cleared on
   * resolve to bound memory.
   */
  private final Map<String, CompletableFuture<VirtualToolResult>> virtualToolFutures =
      new ConcurrentHashMap<>();
  private final List<ExecutedToolCall> executedTools = new ArrayList<>();
  /**
   * Tempdoc 865 §7.1 — the run's grounding accumulator, and the ONE place the mint rule runs.
   *
   * <p>It used to be two locals inside {@code collectGroundingSources}, rebuilt from scratch at the
   * terminal. Terminal-time minting is why cancelling a run destroyed its evidence: the sources
   * existed only on the paths that reported. They are session state now, fed once per executed tool
   * call by {@link #recordExecution}, so what a call established is durable the moment it is
   * established — before any terminal decides whether to speak.
   *
   * <p>ONE structure, keyed by the run-wide dedup key (chunk identity, which is why it cannot live
   * per call) and insertion-ordered — so the key set, the ordered emitted list and each source's
   * carriers are the same fact rather than three that can fall out of step.
   * {@code groundingSearchHits} is the denominator the never-addressable WARN below needs.
   */
  private final LinkedHashMap<String, GroundingEntry> grounding = new LinkedHashMap<>();

  /**
   * Tempdoc 868 §B.3 — DOCUMENT-level index over {@link #grounding}: normalised document path (see
   * {@link #docKey}) → the grounding key of the FIRST source established for that document.
   *
   * <p>It exists because the two producers address a document by different keys and the dedup has to
   * span both. A search hit with chunk identity is keyed {@code parentDocId#chunkIndex}; a read is
   * keyed {@code doc#<path>}. Without this index, "search finds chunk 3 of /a.md, then the agent
   * reads /a.md" mints TWO sources for ONE document — the normal case, since the model gets the path
   * it reads FROM a search result. The invariant 865 §7.6 states (an opened document has LESS
   * relevance evidence than a retrieved one) is what decides the tie: the read adds availability,
   * not evidence, so the retrieved identity stands and the read only records its carrier.
   *
   * <p>Populated by the search arms only. The reverse order (read, then a search that finds a chunk
   * of the same document) deliberately still mints: that search is real ranking evidence plus a
   * chunk identity the read never had, and suppressing it would throw away the inline-mark capability
   * to avoid a duplicate row. The invariant is directional — it forbids "opened" claiming what
   * "retrieved" earned, not the reverse.
   */
  private final LinkedHashMap<String, String> documentGroundingKeys = new LinkedHashMap<>();

  private int groundingSearchHits;

  /**
   * Tempdoc 865 §7.5 — one established source and the tool calls whose results CARRIED it into the
   * prompt.
   *
   * <p>The carriers are a set, not the first one, and that is the whole per-final-prompt semantic: a
   * document established at iteration 2 and returned again by a search at iteration 9 has two
   * carriers, and the later one may still be in the prompt when the earlier one has been stripped.
   * Keeping only the minting call would report such a source as dropped for the rest of the run.
   */
  private record GroundingEntry(
      AgentEvent.AgentSource source, LinkedHashSet<String> carrierCallIds) {}

  /**
   * Tempdoc 865 §7.5 — the tool calls whose result text the compressor has taken out of the prompt,
   * folded from every receipt this run has seen.
   *
   * <p>ACCUMULATED, and that is sound rather than convenient: a tool message's content is only ever
   * shortened, so a carrier line that is gone never comes back. It is also necessary — a message
   * whose excerpts are stripped but whose remainder falls under the compressor's minimum length is
   * written back bearing no marker, so the ONLY pass that can witness it is the one that did it.
   *
   * <p>This does not make the state cumulative in the sense §4.6 warns about. The per-final-prompt
   * property lives in {@link GroundingEntry#carrierCallIds}: a document re-returned by a later
   * search has a new carrier whose message is intact, and {@link #inclusionFor} requires EVERY
   * carrier to have lost its text before it will say anything.
   */
  private final LinkedHashSet<String> carriersWithTextRemoved = new LinkedHashSet<>();

  /** Whether any compression pass has reported at all. Before the first one, say nothing. */
  private boolean compressionObserved;

  private volatile boolean cancelled;
  private int iterationsUsed;
  private String lastCallSignature;
  private int consecutiveIdenticalCalls;
  private int loopBlockCount;

  // Multi-agent handoff state (loop-thread-only, no synchronisation needed)
  private String activeAgentId;
  private int agentIterationsSinceHandoff;
  private final List<Map<String, Object>> handoffHistory = new ArrayList<>();
  private int totalHandoffs = 0;

  // Token budget tracking (thread-safe via AtomicInteger)
  private final AtomicInteger budgetRemaining;
  private final AtomicInteger promptTokensConsumed;
  private final AtomicInteger completionTokensConsumed;
  // Tempdoc 859 §D §2.7(c) — the provider-REPORTED size of the latest prompt (the same figure the
  // `llm_response` budget event carries), which the context-pressure trigger reads ALONGSIDE the
  // projection. It corrects a different error: the projection describes the messages as they stand
  // now, this describes the last call, so each is stale in the direction the other is not.
  // (Pre-878 it also stood in for the projection's schema-blindness; §D.6 fixed that at the source.)
  // 0 until the first LLM response reports usage.
  private final AtomicInteger lastReportedPromptTokens = new AtomicInteger(0);
  // Tempdoc 859 §D §3.2(7) — tokens granted but NOT YET NARRATED. Accumulates (two grants between
  // step boundaries produce one note for the total) and is drained by whichever site observes it
  // first; see drainPendingRaiseNarration.
  private final AtomicInteger pendingRaiseNarration = new AtomicInteger(0);

  // Tempdoc 577 §2.14 Root II (#14) — the model's context window (n_ctx), the cognitive-headroom
  // denominator. Set once at run start by AgentLoopService (the one site that resolves it from the
  // OnlineAiService); 0 when unknown ⇒ the FE shows no horizon ratio. Volatile: written on the loop
  // thread, read when emitting the budget event on the same thread (defensive against future moves).
  private volatile int contextWindow = 0;

  // Tempdoc 577 §2.14 Root I (#13) / 834 §1.3 — the run is an OBSERVED entity, not owned by the
  // socket that started it. The session-local hub this used to be was DELETED: sequencing, bounded
  // replay, fan-out and evict-on-throw all already existed in the SSE substrate, so the loop now
  // publishes through an injected RunObservation.Handle and owns no journal of its own.
  private volatile RunObservation.Handle observation = RunObservation.Handle.NONE;

  // The observer that started the run. It is delivered to DIRECTLY (it takes typed AgentEvents,
  // while the journal carries the wire projection), so its liveness is tracked here rather than by
  // the substrate: a write to a dead socket THROWS, and an initiating observer left counted would
  // mean "no watcher" never registers and a Watch run proceeds unwatched.
  private volatile Consumer<AgentEvent> initiatingObserver;

  // Tempdoc 415: session-lifecycle observability state. Loop-thread-only access; markTerminated
  // is idempotent (WARN-on-second-call) so a future regression that triggers it twice doesn't
  // corrupt the captured reason.
  private final Instant startedAt = Instant.now();
  private TerminalDisposition disposition;
  private AgentErrorCode terminationCode;
  private CancelTrigger cancelTrigger;
  private boolean terminated;

  /** Backward-compatible constructor for single-agent sessions (no initial agent ID). */
  AgentSession(List<Map<String, Object>> messages, int initialBudget) {
    this(messages, initialBudget, null);
  }

  AgentSession(List<Map<String, Object>> messages, int initialBudget, String initialAgentId) {
    this.messages = new ArrayList<>(messages);
    this.budgetRemaining = new AtomicInteger(initialBudget);
    this.promptTokensConsumed = new AtomicInteger(0);
    this.completionTokensConsumed = new AtomicInteger(0);
    this.activeAgentId = initialAgentId != null ? initialAgentId : "primary";
  }

  List<Map<String, Object>> messages() {
    return messages;
  }

  boolean isCancelled() {
    return cancelled;
  }

  /**
   * Tempdoc 561 P-D — whether this run is a BACKGROUND (non-interactive) run: no watcher is present to
   * approve gated tool calls, so the safety gate is safe-by-default (write/destructive ops are rejected
   * immediately rather than waiting for an approval that will never come). Set by the background-run
   * entry point ({@code AgentLoopService.runAgent(..., background=true)}).
   */
  private volatile boolean background;

  boolean isBackground() {
    return background;
  }

  void markBackground() {
    background = true;
  }

  /**
   * Tempdoc 561 P-D — the user's autonomy dial for this run. Volatile so a mid-run dial change
   * (POST /api/chat/agent/autonomy) takes effect on the NEXT tool-call gate. Default ASSIST (the
   * safe default: reads auto-run, writes require approval).
   */
  private volatile io.justsearch.agent.api.registry.AutonomyLevel autonomyLevel =
      io.justsearch.agent.api.registry.AutonomyLevel.DEFAULT;

  io.justsearch.agent.api.registry.AutonomyLevel autonomyLevel() {
    return autonomyLevel;
  }

  void setAutonomyLevel(io.justsearch.agent.api.registry.AutonomyLevel level) {
    if (level != null) {
      this.autonomyLevel = level;
    }
  }

  /**
   * Tempdoc S7 — the FE's scope-chip selection for this run (document paths). Mirrors {@code
   * autonomyLevel}: a per-run control input the request supplies, threaded onto the session by
   * {@code AgentLoopService.runAgent} right after construction. Empty (the default) means unscoped
   * — every SearchTool call behaves exactly as before this feature; {@code
   * AgentToolDispatcher.scopeToolCall} reads this to filter every search-tool invocation in the
   * run to just these paths, regardless of what the LLM's own tool-call arguments say.
   */
  private volatile List<String> docIdsScope = List.of();

  List<String> docIdsScope() {
    return docIdsScope;
  }

  void setDocIdsScope(List<String> docIds) {
    this.docIdsScope = docIds == null ? List.of() : List.copyOf(docIds);
  }

  /**
   * Lane F PR 0b — the run's optional sampling override (temperature / top_p / seed). Mirrors
   * {@link #docIdsScope}: a per-run control input the request supplies, threaded onto the session by
   * {@code AgentLoopService.runAgent} right after construction. It lives on the SESSION rather than
   * being read off the request at each call site because the three sites that build agent sampling
   * ({@code AgentLlmCaller.resolveAgentSampling} and the two inline {@code SamplingParams.AGENT}
   * turns in {@code AgentStepRunner}) all see the session but not all see the request — and one of
   * them honouring the override while another silently ignored it is exactly the shape that makes a
   * capture look pinned while the forced-tool turns still drift. Null = no override, which is
   * byte-identical to the behaviour before the field existed.
   */
  private volatile io.justsearch.agent.api.AgentRequest.SamplingOverride samplingOverride;

  io.justsearch.agent.api.AgentRequest.SamplingOverride samplingOverride() {
    return samplingOverride;
  }

  void setSamplingOverride(io.justsearch.agent.api.AgentRequest.SamplingOverride override) {
    this.samplingOverride = override;
  }

  /**
   * Tempdoc 565 §30 — the human's mid-run STEERING directive (the DIRECTION authority's
   * {@code interject} value). An external {@code POST /api/chat/agent/steer} queues it; the loop
   * DRAINS it (read-and-clear, exactly-once) at the next step boundary and folds the text into the
   * next LLM call as a steering note. Mirrors {@link #autonomyLevel} (the {@code set-posture}
   * directive) — both are per-run control inputs an external thread writes and the loop reads
   * between steps; an {@link AtomicReference} gives the drain its exactly-once semantics.
   */
  private final AtomicReference<String> pendingInterject = new AtomicReference<>(null);

  /** Read-and-clear the pending steering directive; {@code null} if none queued. */
  String drainInterject() {
    return pendingInterject.getAndSet(null);
  }

  /** Queue a mid-run steering directive (no-op on blank). */
  void setInterject(String text) {
    if (text != null && !text.isBlank()) {
      pendingInterject.set(text.trim());
    }
  }

  void cancel() {
    cancelled = true;
    approvalGates.values().forEach(g -> g.future().complete(false));
    // §13.5 Phase B — cancel pending virtual-tool waits with a
    // structured cancelled result so the loop unblocks promptly.
    virtualToolFutures.values().forEach(
        f -> f.complete(VirtualToolResult.failure("session cancelled")));
    virtualToolFutures.clear();
    // Review of the 2026-08-26 stop-semantics work — the BUDGET and CONTEXT parks are held decisions
    // exactly as an approval is, and they were the two the cancel did not release. The loop blocks on
    // `future.get(timeout)` at both, so a cancel during a park did nothing for the length of that
    // timeout: the window said "stopped by you" while the run stayed parked, and the budget gate's
    // undecided fallback is FINALIZE — so the run then synthesised and emitted an answer to a
    // question the reader had abandoned. STOP is the decision the reader just made, and it is the
    // decision the gate's own vocabulary already has, so this resolves them rather than inventing a
    // second way out. Both are no-ops when nothing is parked.
    resolveBudgetGate(BudgetGateDecision.STOP);
    resolveContextGate(ContextGateDecision.STOP);
  }

  int iterationsUsed() {
    return iterationsUsed;
  }

  void incrementIterations() {
    iterationsUsed++;
  }

  int toolCallsExecuted() {
    return executedTools.size();
  }

  /** Tempdoc 603 D-3 — the chunk-identity-absent sentinel: a document-level source carries no chunk
   *  ordinal and no precise line. {@code -1} (not {@code 0}, a valid first-chunk ordinal / first line)
   *  marks "provenance only" so the FE can classify it (the SOURCED frame) and suppress the precise-line
   *  deep-link/locator (the highlight keys on {@code startLine >= 0}). */
  static final int DOC_LEVEL_SENTINEL = -1;

  /**
   * Tempdoc 865 §7.1 — the MINT: what THIS tool result newly established, added to the run's
   * accumulator and returned as the delta. Called once per executed tool call by {@link
   * #recordExecution}; {@link #collectGroundingSources} drains what these calls built.
   *
   * <p>The rule is 565 §3.A + 603 D-3 verbatim — the two identity arms, the run-wide dedup, the
   * document-level sentinel, the identity-less skip — moved, not changed. See {@link
   * #collectGroundingSources} for what each arm means and why.
   *
   * <p>The delta is "documents this call added", not "documents this call returned": the dedup key
   * set spans the RUN, so a document already established by an earlier call contributes nothing here.
   * That is what makes the concatenated deltas equal the terminal list exactly once each, in order —
   * the property {@code AgentSentenceCite.sourceIndex} depends on, since it is a POSITION into that
   * list and a divergence would silently point every inline mark at the wrong document.
   *
   * <p>No success guard, deliberately. Both producer keys ride only a successful result ({@code
   * SearchTool.java:281} and {@code ReadDocumentTool}'s success arm), so a guard here would be
   * inert — while a guard that ever DID bite would break the equality above by dropping from the
   * deltas something the accumulator kept.
   *
   * <p>Tempdoc 868 §B.3 — TWO producer keys now. {@code searchResults} mints RETRIEVED sources (a
   * ranker matched them); {@code readResults} mints OPENED ones (the agent named the document and
   * read it). They are separate branches rather than one normalized list precisely because the
   * acquisition axis must be decided by WHICH producer wrote the key — a read tool that emitted
   * {@code searchResults} would mint sources indistinguishable from search hits, which is the 865
   * §7.6 violation the axis exists to prevent. The dedup spans both producers via {@link
   * #documentGroundingKeys}: a document already established by EITHER search arm is not re-minted by
   * a later read — not even when search keyed it by chunk and the read keys it by path — so "opened"
   * never upgrades an existing source and the delta-equals-terminal property holds across producers.
   */
  private List<AgentEvent.AgentSource> contributeGroundingSources(
      String toolCallId, OperationResult result) {
    Map<String, Object> data = result == null ? Map.of() : result.structuredData();
    var delta = new ArrayList<AgentEvent.AgentSource>();
    if (data.get(OperationResult.SEARCH_RESULTS_KEY) instanceof List<?> results) {
      contributeSearchSources(toolCallId, results, delta);
    }
    if (data.get(OperationResult.READ_RESULTS_KEY) instanceof List<?> reads) {
      contributeReadSources(toolCallId, reads, delta);
    }
    return List.copyOf(delta);
  }

  /** The {@code searchResults} producer: the two identity arms, unchanged from 565 §3.A / 603 D-3. */
  private void contributeSearchSources(
      String toolCallId, List<?> results, List<AgentEvent.AgentSource> delta) {
    groundingSearchHits += results.size();
    for (Object o : results) {
      if (!(o instanceof Map<?, ?> m)) {
        continue;
      }
      String path = asString(m.get("path"));
      boolean chunkPrecise = m.get("parentDocId") instanceof String pd && !pd.isBlank();
      if (chunkPrecise) {
        String parentDocId = (String) m.get("parentDocId");
        int chunkIndex = m.get("chunkIndex") instanceof Number n ? n.intValue() : 0;
        String groundingKey = parentDocId + "#" + chunkIndex;
        // Tempdoc 868 §B.3 — index the DOCUMENT this chunk belongs to, under both spellings a later
        // read could address it by: the stored `path` field and the `parentDocId` (which IS the
        // path in this index — PreviewController: "Treat docId as opaque"). Recorded before the
        // mint so it is present whether or not this particular chunk was new.
        rememberDocumentKey(path, groundingKey);
        rememberDocumentKey(parentDocId, groundingKey);
        AgentEvent.AgentSource minted =
            establish(
                groundingKey,
                toolCallId,
                () ->
                    new AgentEvent.AgentSource(
                        parentDocId,
                        chunkIndex,
                        path,
                        asString(m.get("title")),
                        asString(m.get("excerpt")),
                        asInt(m.get("startLine")),
                        asInt(m.get("endLine")),
                        asString(m.get("headingText"))));
        if (minted != null) {
          delta.add(minted);
        }
      } else if (!path.isBlank()) {
        // 603 D-3 — document-level provenance: identity is the path, chunk ordinal + lines are the
        // sentinel (no precise location). The whole document IS the source the answer drew on.
        rememberDocumentKey(path, docKey(path));
        AgentEvent.AgentSource minted =
            establish(
                docKey(path),
                toolCallId,
                () ->
                    new AgentEvent.AgentSource(
                        path,
                        DOC_LEVEL_SENTINEL,
                        path,
                        asString(m.get("title")),
                        asString(m.get("excerpt")),
                        DOC_LEVEL_SENTINEL,
                        DOC_LEVEL_SENTINEL,
                        asString(m.get("headingText"))));
        if (minted != null) {
          delta.add(minted);
        }
      }
      // else: neither chunk identity nor a path — not addressable as a source; skipped.
    }
  }

  /**
   * Tempdoc 868 §B.3 — the {@code readResults} producer. A read has only DOCUMENT-level identity: it
   * addresses a character span, not a chunk ordinal, so it takes the same {@code doc#<path>} arm as
   * a chunk-less search hit (sentinel chunk + lines, so the FE renders the SOURCED frame and
   * suppresses the precise-line deep-link). The excerpt is the page the model actually saw, which is
   * what {@code AgentCitationResolver} verifies an opened source against instead of re-fetching.
   *
   * <p>When the document is already established — by EITHER search arm, under either key shape —
   * this records the read as a CARRIER of the existing source and mints nothing. That is the
   * cross-producer half of the dedup, and the normal case rather than an edge one: the path the
   * model reads with is usually a path a search result just handed it.
   *
   * <p>A blank excerpt is skipped. A source whose literal text is blank falls back to an index
   * lookup in {@code DocumentService.matchCitationsAgainst} — so an opened source with no literal
   * would be verified against whatever the index returns for its key rather than against the page
   * the model was shown, which is exactly the re-fetch an opened source must never do.
   *
   * <p>Tempdoc 878 §D.9 corrects the reason this guard used to give. It cited {@code
   * ContextCitation}'s compact constructor "clamping the document-level {@code -1} chunk ordinal to
   * {@code 0}", which was true when written and has not been since: {@code
   * ContextCitation.CHUNK_INDEX_ABSENT} PRESERVES {@code -1} ({@code DocumentService}, the 836 §8.4
   * fix) precisely so "no chunk position" stops being readable as "the first chunk". The guard is
   * still right; only its stated cause had gone stale — the more dangerous of the two states,
   * because a reader checks the reason and not the guard.
   *
   * <p>Read hits are NOT added to {@code groundingSearchHits}: nothing searched, so counting them
   * would inflate a retrieval statistic with documents no ranker ever saw.
   */
  private void contributeReadSources(
      String toolCallId, List<?> reads, List<AgentEvent.AgentSource> delta) {
    for (Object o : reads) {
      if (!(o instanceof Map<?, ?> m)) {
        continue;
      }
      String path = asString(m.get("path"));
      String excerpt = asString(m.get("excerpt"));
      if (path.isBlank() || excerpt.isBlank()) {
        continue;
      }
      String documentKey = docKey(path);
      String establishedUnder = documentGroundingKeys.get(documentKey);
      GroundingEntry established =
          establishedUnder == null ? null : grounding.get(establishedUnder);
      if (established != null) {
        // The run learns that this read put the document's text in front of the model again — the
        // carrier fact the inclusion receipt needs — without a second source for one document.
        addCarrier(established, toolCallId);
        continue;
      }
      rememberDocumentKey(path, documentKey);
      AgentEvent.AgentSource minted =
          establish(
              documentKey,
              toolCallId,
              () ->
                  new AgentEvent.AgentSource(
                      path,
                      DOC_LEVEL_SENTINEL,
                      path,
                      asString(m.get("title")),
                      excerpt,
                      DOC_LEVEL_SENTINEL,
                      DOC_LEVEL_SENTINEL,
                      "",
                      AgentEvent.AgentSource.ACQUISITION_OPENED));
      if (minted != null) {
        delta.add(minted);
      }
    }
  }

  /**
   * Tempdoc 868 §B.3 — the run-wide identity of a DOCUMENT, for the index that lets the two
   * producers dedup against each other. One helper so both doc-level grounding keys and the index
   * cannot spell the same document differently.
   *
   * <p>Case-folded on every platform, not just Windows. The Worker lowercases paths before it looks
   * a document up ({@code WorkerSearchService.fetchDocumentSlice} → {@code
   * PathNormalizer.normalizePath}), so two spellings that differ only in case ARE one document as
   * far as every fetch is concerned; keying them apart here would let the case-variant mint a
   * duplicate source for a document the index cannot even distinguish.
   */
  private static String docKey(String path) {
    return "doc#" + path.toLowerCase(Locale.ROOT);
  }

  /** First writer wins: the document keeps the identity of the source that established it first. */
  private void rememberDocumentKey(String path, String groundingKey) {
    if (path != null && !path.isBlank()) {
      documentGroundingKeys.putIfAbsent(docKey(path), groundingKey);
    }
  }

  /**
   * Add {@code toolCallId} to the carriers of the source keyed by {@code key}, minting the source
   * first if this is the run's first sight of it.
   *
   * <p>Returns the newly minted source, or {@code null} when the key was already established — which
   * is the run-wide dedup, unchanged. The carrier is recorded EITHER WAY (tempdoc 865 §7.5): a
   * repeat hit adds no source to the delta but does deliver that source's text into the prompt
   * again, and that is precisely the fact the inclusion state has to see.
   */
  private AgentEvent.AgentSource establish(
      String key, String toolCallId, java.util.function.Supplier<AgentEvent.AgentSource> mint) {
    GroundingEntry existing = grounding.get(key);
    if (existing != null) {
      addCarrier(existing, toolCallId);
      return null;
    }
    AgentEvent.AgentSource source = mint.get();
    var entry = new GroundingEntry(source, new LinkedHashSet<>());
    addCarrier(entry, toolCallId);
    grounding.put(key, entry);
    return source;
  }

  private static void addCarrier(GroundingEntry entry, String toolCallId) {
    if (toolCallId != null && !toolCallId.isBlank()) {
      entry.carrierCallIds().add(toolCallId);
    }
  }

  /**
   * Tempdoc 865 §7.5 — record what the compressor left standing in the prompt it just produced.
   * Called at every {@code compressToolMessages} site, so the session always holds the LATEST
   * picture rather than the first one.
   */
  void recordCompression(AgentContextCompressor.CompressionReceipt receipt) {
    if (receipt == null) {
      return;
    }
    carriersWithTextRemoved.addAll(receipt.textRemoved());
    // A carrier the latest prompt shows as still holding text cannot also have lost it. Content only
    // ever shrinks, so this should never fire — but the say-less answer is cheap and the alternative
    // is a contradiction the reader would see as a confident false claim.
    carriersWithTextRemoved.removeAll(receipt.textIntact());
    compressionObserved = compressionObserved || receipt.observed();
  }

  /**
   * Tempdoc 865 §7.5 — THE INCLUSION PRODUCER: was this source's passage still in the prompt?
   *
   * <p>Modelled on {@code RAGContext.resolveInclusion}, which is the plane that already answers this
   * question — but where RAG cuts once at assembly and can therefore say {@code included} with a
   * character count it measured, the delegate plane degrades CONTINUOUSLY and can measure nothing
   * per source. So this producer states exactly one thing:
   *
   * <ul>
   *   <li><b>DROPPED</b> — every tool message that carried this source has lost its carrier lines
   *       (or has left the prompt entirely — tempdoc 878 §D.3 made that second clause true in code;
   *       until then only the compressor wrote to the ledger and a compacted-away carrier read back
   *       as ABSENT). Its passage text is not in the prompt.
   *   <li><b>ABSENT</b> — anything else. Say nothing.
   * </ul>
   *
   * <p><b>Why no {@code included}, and this is the honesty constraint, not a shortcut.</b> There are
   * THREE truncation layers and 849's vocabulary models only the third. Layer 1 is {@code
   * SearchTool.formatResults}' rendering budget (the whole emitted string sized under {@code
   * AgentContextCompressor#truncate}'s Layer-2 cap), which clips — or omits outright — a TAIL hit's carrier line while that
   * hit is still minted as a source from the untruncated {@code structuredData}. Layer 2 is {@code
   * AgentContextCompressor.truncate}'s hard per-message cut. Neither is visible here. So "this
   * message still carries hit text" cannot mean "THIS source's text reached the model", and stamping
   * {@code included} would fabricate exactly the claim 849 exists to remove.
   *
   * <p>DROPPED survives that objection because it is MONOTONE across the layers: once Layer 3 has
   * taken the text out of the carrier message, no upstream cut can put it back. It is also the only
   * state the reader acts on — {@code suppressGroundingFor} keys on {@code dropped} alone.
   *
   * <p><b>EVERY carrier, not any.</b> The quantifier is the per-final-prompt semantic: one intact
   * carrier means the text is in the prompt, whoever else lost it. A carrier the receipts have said
   * nothing about is not evidence either — {@code carriersWithTextRemoved} holds only calls with
   * positive evidence of removal, so "not in that set" covers both intact and unknown, and both must
   * silence the claim.
   */
  private DocumentService.ContextInclusion inclusionFor(GroundingEntry entry) {
    if (!compressionObserved || entry.carrierCallIds().isEmpty()) {
      return DocumentService.ContextInclusion.ABSENT;
    }
    for (String callId : entry.carrierCallIds()) {
      if (!carriersWithTextRemoved.contains(callId)) {
        return DocumentService.ContextInclusion.ABSENT;
      }
    }
    return DocumentService.ContextInclusion.dropped();
  }

  /**
   * Tempdoc 565 §3.A + 603 D-3 — the answer's grounding sources: the clickable local-passage
   * citations on {@link AgentEvent.AgentDone}. Read from the structured search evidence ({@code
   * searchResults}, from {@code SearchTool.buildSearchEvidence}), in first-seen order.
   *
   * <p><b>Tempdoc 865 §7.1 — this DRAINS an accumulator; it no longer computes one.</b> The mint
   * runs incrementally in {@link #contributeGroundingSources}, once per executed tool call, and each
   * call's delta is stamped onto its own {@code tool_exec_completed} event at the dispatch seam. So
   * "the run's evidence is what the terminal computed" is a RETIRED model: a cancelled or errored
   * run keeps everything it established even though it reaches no grounded terminal and calls this
   * method never. This method's remaining job is to report, at the {@code groundedDone} terminals,
   * the same ordered list the deltas already delivered — THREE of them since tempdoc 878 §D.1 routed
   * the iteration ceiling through the same seam.
   *
   * <p><b>Tempdoc 865 §7.5 — plus the one thing only a terminal knows.</b> Because it runs at the
   * terminals and nowhere else, this is also where each source's {@code ContextInclusion} is
   * resolved against the FINAL prompt (see {@link #inclusionFor}). The identity a source carries is
   * unchanged from its delta; the terminal adds a fact about a prompt that did not exist when the
   * source was minted. Same split as {@code DocumentService.ContextCitation}: constructed absent,
   * resolved at the cut.
   *
   * <p>The RULE — the two identity arms below — is unchanged by that move:
   *
   * <p><b>Provenance vs precision (603 D-3).</b> A grounding source's IDENTITY is the DOCUMENT it came
   * from; chunk identity ({@code parentDocId}+{@code chunkIndex}) is OPTIONAL ENRICHMENT that upgrades a
   * source to a line-precise, matcher-eligible passage. Source EXISTENCE is never gated on that
   * enrichment. So:
   * <ul>
   *   <li><b>chunk-precise</b> hit ({@code parentDocId} present) → keyed by {@code parentDocId#chunkIndex},
   *       carries its real chunk ordinal + line span (deep-links to the exact lines; the answer↔source
   *       matcher can add inline marks).
   *   <li><b>document-level</b> hit (no {@code parentDocId} — e.g. the main BM25/keyword pipeline under
   *       BLOCKED_LEGACY returns whole-doc hits whose stored fields lack the chunk-only {@code
   *       parent_doc_id}) → still a real source the answer drew on; keyed by its {@code path}, emitted
   *       with the {@link #DOC_LEVEL_SENTINEL} for chunk ordinal + lines (deep-links to the file top, no
   *       inline marks). Previously these were DROPPED, leaving the Sources pane falsely empty while the
   *       answer cited them (tempdoc 603 D-1).
   * </ul>
   * The run is genuinely ungrounded only when it did not search / a hit carried neither a {@code
   * parentDocId} nor a {@code path}.
   */
  List<AgentEvent.AgentSource> collectGroundingSources() {
    // Tempdoc 565 §3.A follow-up / 603 D-3 — observability for the now-narrow truly-uncitable case:
    // the run searched (hits exist) but NO hit carried even a path (no chunk identity AND no document
    // identity). With D-3 a path-bearing hit always yields a document-level source, so this WARN no
    // longer fires for the common BLOCKED_LEGACY whole-doc case — only for a genuinely identity-less
    // result (a malformed/stale Worker payload).
    if (groundingSearchHits > 0 && grounding.isEmpty()) {
      LOG.warn(
          "Grounding empty: {} search hit(s) but none were addressable (no parentDocId AND no path);"
              + " the answer will lack source citations — check the search payload or a stale Worker build",
          groundingSearchHits);
    }
    // Tempdoc 865 §7.5 — inclusion is resolved HERE and only here, because this method runs at the
    // two grounded terminals and nowhere else: the prompt the answer was written from is the last
    // one the compressor reported on, and a per-call delta has no prompt to be a fact about. That is
    // the same "constructed absent, resolved at the cut" split `ContextCitation` uses on the RAG
    // plane — so the delta and the terminal report the same source with the same identity, and only
    // the terminal adds what only the terminal knows.
    var out = new ArrayList<AgentEvent.AgentSource>(grounding.size());
    for (GroundingEntry entry : grounding.values()) {
      DocumentService.ContextInclusion inclusion = inclusionFor(entry);
      out.add(
          inclusion.absent()
              ? entry.source()
              : entry.source().withInclusion(inclusion.wireName(), inclusion.includedChars()));
    }
    return List.copyOf(out);
  }

  private static String asString(Object value) {
    return value instanceof String s ? s : "";
  }

  private static int asInt(Object value) {
    return value instanceof Number n ? n.intValue() : 0;
  }

  /**
   * Create an approval gate for a pending tool call. Returns a future that completes on
   * approve/reject. Tempdoc 834 §6.2 — {@code detail} is the same call description the caller just
   * emitted as {@code tool_call_pending}, retained so the state snapshot can carry the open gate.
   */
  CompletableFuture<Boolean> createApprovalGate(String callId, AgentEvent.PendingApproval detail) {
    var gate = new CompletableFuture<Boolean>();
    approvalGates.put(callId, new PendingGate(detail, System.currentTimeMillis(), gate));
    return gate;
  }

  /** Approve a pending tool call. Returns whether a gate with that callId existed (was completed). */
  boolean approve(String callId) {
    var gate = approvalGates.remove(callId);
    if (gate != null) {
      gate.future().complete(true);
      return true;
    }
    return false;
  }

  /** Reject a pending tool call. Returns whether a gate with that callId existed (was completed). */
  boolean reject(String callId) {
    var gate = approvalGates.remove(callId);
    if (gate != null) {
      gate.future().complete(false);
      return true;
    }
    return false;
  }

  /**
   * Tempdoc 834 §6.2 — the tool calls currently held at an approval gate, oldest first. Empty means
   * NONE are pending (the snapshot's key is always emitted, so absent-on-the-wire can keep meaning
   * "unknown" for legacy records).
   */
  List<AgentEvent.PendingApproval> pendingApprovals() {
    return approvalGates.values().stream()
        .sorted(java.util.Comparator.comparingLong(PendingGate::sinceEpochMs))
        .map(PendingGate::detail)
        .filter(Objects::nonNull)
        .toList();
  }

  /**
   * Tempdoc 834 §6.1/§6.2 — why this run is currently stopped, or {@code null} when it is running.
   * Derived from the four park sources in the order §11 lists them: the budget gate, the context
   * gate, a held approval gate, then the posture-graded zero-observer policy.
   */
  AgentEvent.ParkSnapshot parkSnapshot() {
    if (budgetGateHeld()) {
      return new AgentEvent.ParkSnapshot("budget", budgetGate.sinceEpochMs(), "budget gate held");
    }
    if (contextGateHeld()) {
      return new AgentEvent.ParkSnapshot(
          "context", contextGate.sinceEpochMs(), "context gate held");
    }
    var held =
        approvalGates.values().stream()
            .min(java.util.Comparator.comparingLong(PendingGate::sinceEpochMs))
            .orElse(null);
    if (held != null) {
      String callIds =
          approvalGates.keySet().stream().sorted().collect(java.util.stream.Collectors.joining(","));
      return new AgentEvent.ParkSnapshot("approval", held.sinceEpochMs(), callIds);
    }
    if (zeroObserverPolicy() == ZeroObserverPolicy.PARK) {
      // No transition to timestamp — the zero-observer park is DERIVED from an observer count, so
      // 0 honestly says "start unknown" rather than inventing now().
      return new AgentEvent.ParkSnapshot("unobserved", 0L, "no observers attached");
    }
    return null;
  }

  // --- Budget gate (tempdoc 577 §2.12 Move 2) ---

  /** The human's decision at a held budget gate. */
  enum BudgetGateDecision {
    /** Budget was raised (via the raise endpoint) — resume the loop at this boundary. */
    CONTINUE,
    /** Synthesize from what the run has (the budget-edge finalize path). */
    FINALIZE,
    /** Stop the run here (cancel semantics, trigger BUDGET). */
    STOP
  }

  // Tempdoc 834 §6.2 — the gate also records when the park began, so ParkSnapshot can say
  // "parked since", not just "parked".
  private final HeldGate<BudgetGateDecision> budgetGate = new HeldGate<>();

  /**
   * Tempdoc 577 Move 2 — park the run at the budget boundary as a HELD decision (the budget
   * analogue of an approval gate). One gate at a time by construction (the loop is single-threaded
   * between iterations). The loop blocks on the returned future; resolution arrives from the raise
   * endpoint (CONTINUE), the decision endpoint (FINALIZE/STOP), or the loop's own timeout.
   */
  CompletableFuture<BudgetGateDecision> createBudgetGate() {
    return budgetGate.arm();
  }

  /**
   * Resolve a held budget gate. Returns false when no gate is held (the run is not parked) — the
   * endpoint surfaces that as 404, mirroring approve/reject on an unknown callId.
   */
  boolean resolveBudgetGate(BudgetGateDecision decision) {
    return budgetGate.resolve(decision);
  }

  /** Whether a budget gate is currently held (the run is parked awaiting a decision). */
  boolean budgetGateHeld() {
    return budgetGate.held();
  }

  /** Clear the gate reference after the loop consumed it (timeout path). */
  void clearBudgetGate() {
    budgetGate.clear();
  }

  // --- Context gate (tempdoc 577 §2.14 Root II #14 — the COGNITIVE sibling of the budget gate) ---

  /** The human's decision at a held context-pressure gate. */
  enum ContextGateDecision {
    /** Proceed anyway — send the large prompt as-is (the user accepts the risk). */
    CONTINUE,
    /** Compact older turns to free context headroom, then resume. */
    SUMMARIZE,
    /** Stop the run here. */
    STOP
  }

  private final HeldGate<ContextGateDecision> contextGate = new HeldGate<>();
  // The context gate ASKS at most once per run: once the user decides (continue/summarize), the run
  // is not re-parked every iteration.
  //
  // Tempdoc 859 §D §2.7 — this flag's ORIGINAL rationale ("a renewed pressure spike after a
  // summarize is covered by the hard budget gate") is RETIRED, not merely reworded. It was true only
  // while the budget was one context window: the budget wall always arrived first, so a second
  // crossing was unreachable. At the effort multipliers (up to 15x) the budget no longer arrives
  // first, and a second crossing with no response would let the prompt grow past n_ctx — which would
  // void the very structural bound the Thorough rung is justified by (AgentBudgetPolicy).
  //
  // What the flag means NOW: ask once, then AUTO-COMPACT on every later crossing (AgentStepRunner).
  // The decision is re-applied, not re-asked — and the run says so each time it re-applies it.
  // Loop-thread-only, so a plain boolean suffices.
  private boolean contextGateFired = false;

  /**
   * Tempdoc 577 §2.14 Root II — park the run at the context-pressure boundary as a HELD decision (the
   * cognitive analogue of the budget gate). One gate at a time; the loop blocks on the returned
   * future until the context-decision endpoint resolves it or the loop's timeout fires.
   */
  CompletableFuture<ContextGateDecision> createContextGate() {
    var gate = contextGate.arm();
    // Not part of HeldGate: arming the CONTEXT gate additionally latches "already asked this run".
    // The budget gate has no analogue, which is why gate CREATION stayed per-gate (tempdoc 880 §B.6).
    contextGateFired = true;
    return gate;
  }

  /** Resolve a held context gate. Returns false when no gate is held (surfaced as 404). */
  boolean resolveContextGate(ContextGateDecision decision) {
    return contextGate.resolve(decision);
  }

  /** Whether a context gate is currently held (the run is parked awaiting a decision). */
  boolean contextGateHeld() {
    return contextGate.held();
  }

  /** Whether the context gate has already fired this run (park at most once). */
  boolean contextGateFired() {
    return contextGateFired;
  }

  /** Clear the gate reference after the loop consumed it (timeout path). */
  void clearContextGate() {
    contextGate.clear();
  }

  /**
   * Tempdoc 880 §B.6 — one HELD singleton decision gate: the run parks on a future and a human (or
   * the loop's own timeout) resolves it. Extracted because {@code createBudgetGate} /
   * {@code createContextGate} and their resolve/held/clear siblings had byte-identical bodies
   * differing only in the decision type.
   *
   * <p>Scoped deliberately to arm/resolve/held/clear, and NOT to the other three things that look
   * like they belong here:
   *
   * <ul>
   *   <li><b>Gate creation policy</b> stays per-gate: arming the context gate also latches
   *       {@code contextGateFired}, the two gates use different timeouts
   *       ({@code AgentTimeouts.contextGateMs()} vs {@code budgetGateMs()}) with different timeout
   *       FALLBACKS (context → CONTINUE plus a {@code PHASE_CONTEXT_GATE_UNANSWERED} narration;
   *       budget → FINALIZE, silent), and the background-run guard sits around gate creation for
   *       budget but inside the trigger predicate for context. All in {@code AgentStepRunner}.
   *   <li><b>The two map-keyed gates</b> (approval, virtual-tool) are not members: approval carries
   *       a {@link PendingGate} with detail + timestamp and appears in {@link #parkSnapshot()},
   *       virtual-tool is a bare future map that does not, and {@link #cancel()} clears one map but
   *       not the other.
   * </ul>
   *
   * Those are real behaviour differences, not duplication — unifying them would silently change a
   * memory model, two timeout behaviours and a park-visibility surface.
   */
  private static final class HeldGate<T> {

    // Volatile for the same reason the two raw fields were: armed on the loop thread, read and
    // resolved from HTTP endpoint threads.
    private volatile CompletableFuture<T> gate;
    private volatile long sinceEpochMs;

    /** Park: install a fresh future and stamp the park's start. */
    CompletableFuture<T> arm() {
      var fresh = new CompletableFuture<T>();
      gate = fresh;
      sinceEpochMs = System.currentTimeMillis();
      return fresh;
    }

    /** Complete a held gate. False when nothing is held — the endpoints surface that as 404. */
    boolean resolve(T decision) {
      var current = gate;
      if (current == null || current.isDone()) {
        return false;
      }
      gate = null;
      current.complete(decision);
      return true;
    }

    boolean held() {
      var current = gate;
      return current != null && !current.isDone();
    }

    /** When the current park began; 0 if this gate has never been armed. */
    long sinceEpochMs() {
      return sinceEpochMs;
    }

    void clear() {
      gate = null;
    }
  }

  /**
   * Tempdoc 577 §2.14 Root II — compact older conversation turns to free context headroom.
   * Structurally: drop the OLDEST messages, preserving an anchor at the head and the most recent
   * {@code keepRecent} messages (the live working set). Returns the number of messages dropped, so
   * the caller can narrate the compaction honestly. Loop-thread-only (called between iterations); no
   * concurrent mutation.
   *
   * <p><b>Named honestly (859 §D §2.7):</b> this DELETES messages. It does not summarize them. The
   * SUMMARIZE gate decision is the user's word for the remedy, not a description of the mechanism.
   *
   * <p><b>Two amendments (859 §D §2.7 a/b),</b> each closing a hazard that one compaction could
   * already cause and that repeated compaction — now reachable, since the gate re-arms — multiplies:
   *
   * <ol>
   *   <li><b>The task anchor survives.</b> The opening user message is the run's TASK, and it sat at
   *       index 1, inside the drop range. An agent that forgets what it was asked, mid-run, is worse
   *       than one that stops.
   *   <li><b>Whole assistant+tool groups, never a severed one.</b> A {@code role:"tool"} message
   *       whose parent assistant {@code tool_calls} message was dropped is an orphan — a malformed
   *       conversation for the provider. The drop boundary is pushed forward past any leading tool
   *       messages so a group leaves together.
   * </ol>
   */
  synchronized int compactOlderTurns(int keepRecent) {
    int size = messages.size();
    // Preserve a leading system message (role=system at index 0) as an anchor.
    int start = (size > 0 && "system".equals(messages.get(0).get("role"))) ? 1 : 0;
    // (a) Preserve the TASK anchor: the opening user message, wherever the system message left it.
    if (start < size && "user".equals(messages.get(start).get("role"))) {
      start++;
    }
    int dropEnd = size - Math.max(0, keepRecent);
    if (dropEnd <= start) {
      return 0; // nothing compactable (the working set already fits the keep-window)
    }
    // (b) Never sever a tool result from the assistant call it answers. Messages are dropped as a
    // PREFIX, so a dropped tool message's parent is always dropped with it; the exposure is the
    // other end — a KEPT tool message whose parent falls inside the drop range. Push the boundary
    // forward over those, which drops the whole group instead of orphaning its tail. Bounded by one
    // assistant turn's tool-call count.
    while (dropEnd < size && "tool".equals(messages.get(dropEnd).get("role"))) {
      dropEnd++;
    }
    int dropped = dropEnd - start;
    // Tempdoc 878 §D.3 — REPORT what leaves. `inclusionFor`'s contract already said a source is
    // dropped when every carrier "has lost its Excerpt: lines (or has left the prompt entirely)",
    // but only the compressor ever wrote to the ledger, so a source whose carrier was DELETED here
    // read back as ABSENT — say-nothing — and rendered as ordinary evidence. Deletion is the
    // stronger removal of the two, and it is recorded at the writer rather than inferred at the
    // reader, which is how the two halves diverged in the first place.
    boolean droppedACarrier = false;
    for (Map<String, Object> message : messages.subList(start, dropEnd)) {
      if ("tool".equals(message.get("role"))
          && message.get("tool_call_id") instanceof String id
          && !id.isBlank()) {
        carriersWithTextRemoved.add(id);
        droppedACarrier = true;
      }
    }
    if (droppedACarrier) {
      // A compaction that dropped a carrier IS an observation about the prompt. Arming here means
      // the producer's silence is a statement about evidence rather than an artefact of which
      // passes happened to run.
      compressionObserved = true;
    }
    messages.subList(start, dropEnd).clear();
    return dropped;
  }

  /**
   * Tempdoc 508 §11.5 / §13.5 Phase B — register a future for a
   * pending virtual-tool invocation. Returned future completes when
   * the FE POSTs the result OR when {@link #completeVirtualTool} is
   * called with a synthetic result (e.g., timeout, FE not subscribed).
   */
  CompletableFuture<VirtualToolResult> registerVirtualToolGate(String callId) {
    var future = new CompletableFuture<VirtualToolResult>();
    virtualToolFutures.put(callId, future);
    return future;
  }

  /**
   * Complete a pending virtual-tool future with the FE-supplied
   * (or synthetic) result. Returns true when the future was found
   * and completed; false when no pending call had that callId (which
   * the caller can surface as 404).
   */
  boolean completeVirtualTool(String callId, VirtualToolResult result) {
    var future = virtualToolFutures.remove(callId);
    if (future == null) return false;
    future.complete(result);
    return true;
  }

  /**
   * Result envelope for {@link #completeVirtualTool}. Mirrors the
   * fields the FE POSTs at {@code /api/chat/agent/tool-result}.
   * Success carries the captured output string; failure carries the
   * detail.
   */
  record VirtualToolResult(boolean success, String output, String errorDetail) {
    public static VirtualToolResult success(String output) {
      return new VirtualToolResult(true, output, null);
    }

    public static VirtualToolResult failure(String detail) {
      return new VirtualToolResult(false, null, detail);
    }
  }

  /**
   * Record a tool execution and track consecutive identical calls.
   *
   * @return tempdoc 865 §7.1 — the grounding sources THIS call newly established (empty when it
   *     established none). Returned rather than left to a separate call on purpose: recording an
   *     execution and contributing what it established are one act, so a future call site cannot
   *     record a result whose evidence the accumulator never saw.
   *     <p><b>THE CALLER'S OBLIGATION, and the property that actually matters (review F-2): every
   *     returned delta must be stamped onto the event that call emits.</b> Returning it is only half
   *     — a caller that records and discards puts the source in the terminal list and in NO delta, so
   *     the concatenated deltas stop equalling the terminal list and every position after the gap
   *     shifts. Since {@code AgentSentenceCite.sourceIndex} is a position into that list, the visible
   *     result is inline marks pointing at the wrong documents, with nothing failing. The
   *     grounding-seam audit cannot see this shape (it forbids stamping outside the seam, not
   *     recording without stamping), so it is pinned by test instead.
   */
  List<AgentEvent.AgentSource> recordExecution(ToolCallRequest call, OperationResult result) {
    executedTools.add(new ExecutedToolCall(call, result));
    List<AgentEvent.AgentSource> delta =
        contributeGroundingSources(call == null ? null : call.id(), result);
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      consecutiveIdenticalCalls++;
    } else {
      lastCallSignature = signature;
      consecutiveIdenticalCalls = 1;
    }
    return delta;
  }

  /** Returns how many times the same (tool, args) pair has been called consecutively. */
  int consecutiveIdenticalCalls() {
    return consecutiveIdenticalCalls;
  }

  /**
   * Peek method: returns true if executing this call would cause consecutive identical calls to
   * reach or exceed the given threshold. Does NOT mutate state.
   */
  boolean wouldExceedLoopThreshold(ToolCallRequest call, int threshold) {
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      return (consecutiveIdenticalCalls + 1) >= threshold;
    }
    return false; // Different call — would reset to 1
  }

  /**
   * Records a blocked call: increments consecutive identical call count and loop block count
   * without adding to the executed tools list.
   */
  void recordBlockedCall(ToolCallRequest call) {
    String signature = call.toolName() + ":" + normalizeArgs(call.arguments());
    if (signature.equals(lastCallSignature)) {
      consecutiveIdenticalCalls++;
    } else {
      lastCallSignature = signature;
      consecutiveIdenticalCalls = 1;
    }
    loopBlockCount++;
  }

  /** Returns the total number of loop-blocked calls across the session. */
  int loopBlockCount() {
    return loopBlockCount;
  }

  /** Returns true if any executed tool result was successful. */
  boolean hasSuccessfulToolResult() {
    return executedTools.stream().anyMatch(e -> e.result().success());
  }

  /**
   * Tempdoc 878 §D.7 — the documents this run OPENED by name, oldest first, deduplicated.
   *
   * <p>The finalize instruction requires the model to "name what you had gathered and what you had
   * not gotten to yet", and until now gave it nothing factual to name it from. This is that fact,
   * and it is the one this run can actually support.
   *
   * <p><b>Why a count of what was opened and not "1 of 3 files."</b> 859 §2.3 asked for
   * per-document progress in that shape, and the denominator is not recoverable: how many documents
   * the USER meant lives in their prose, not in anything the loop holds. Stating "1 of 3" would put
   * a fabricated number on the surface this tempdoc exists to make honest. What the run knows
   * exactly is which documents it opened — so that is what it says.
   *
   * <p>OPENED only, not every source: a search hit was not "gotten to", it was returned. Mixing the
   * two would let a run that opened nothing claim it had worked through documents.
   */
  synchronized List<String> openedDocumentPaths() {
    var paths = new LinkedHashSet<String>();
    for (GroundingEntry entry : grounding.values()) {
      AgentEvent.AgentSource source = entry.source();
      if (AgentEvent.AgentSource.ACQUISITION_OPENED.equals(source.acquisition())
          && source.path() != null
          && !source.path().isBlank()) {
        paths.add(source.path());
      }
    }
    return List.copyOf(paths);
  }

  private static String normalizeArgs(String json) {
    if (json == null || json.isBlank()) return "";
    try {
      // Parse into generic Java types (LinkedHashMap for objects), then re-serialize.
      // ORDER_MAP_ENTRIES_BY_KEYS sorts map keys during writeValueAsString, producing
      // canonical JSON regardless of the original key order.
      Object value = NORM_MAPPER.readValue(json, Object.class);
      return NORM_MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      return json.strip(); // fallback for non-JSON arguments
    }
  }

  /** Append a message to the conversation. */
  void appendMessage(Map<String, Object> message) {
    messages.add(message);
  }

  // Multi-agent handoff accessors

  String activeAgentId() {
    return activeAgentId;
  }

  /** Number of LLM calls the current agent has made since the last handoff (or session start). */
  int agentIterationsSinceHandoff() {
    return agentIterationsSinceHandoff;
  }

  void incrementAgentIterations() {
    agentIterationsSinceHandoff++;
  }

  List<Map<String, Object>> handoffHistory() {
    return Collections.unmodifiableList(handoffHistory);
  }

  /** Updates the active agent cursor and appends an entry to the handoff history. */
  void recordHandoff(String fromAgentId, String toAgentId, String reason) {
    activeAgentId = toAgentId;
    // Reset loop guard state — the new agent starts with a clean slate
    lastCallSignature = null;
    consecutiveIdenticalCalls = 0;
    loopBlockCount = 0;
    agentIterationsSinceHandoff = 0;
    var entry = new LinkedHashMap<String, Object>();
    entry.put("fromAgentId", fromAgentId);
    entry.put("toAgentId", toAgentId);
    entry.put("reason", reason);
    entry.put("timestamp", Instant.now().toString());
    handoffHistory.add(entry);
  }

  /**
   * Increments the total handoff count and returns the new total. Used by
   * {@link AgentLoopService} to detect runaway handoff cycles before executing a handoff.
   */
  int incrementTotalHandoffs() {
    return ++totalHandoffs;
  }

  /**
   * Clears all pending approval gates by completing them with {@code false}.
   *
   * <p>Called on handoff to enforce the approval boundary: approvals granted to the previous agent
   * role must not carry over to the new role. Unlike {@link #cancel()}, this does not set the
   * cancelled flag — the loop continues under the new agent.
   *
   * <p>In the current sequential inner-loop architecture, this is always a no-op at the handoff
   * point because each tool call in a batch is processed (and its gate resolved) before the loop
   * advances to the next call. No gates are in-flight when a handoff fires. The call is retained
   * as a defensive safeguard against future loop restructuring.
   */
  void clearPendingApprovals() {
    approvalGates.values().forEach(g -> g.future().complete(false));
    approvalGates.clear();
  }

  // Budget tracking accessors (thread-safe)

  /** Returns the remaining token budget. */
  int budgetRemaining() {
    return budgetRemaining.get();
  }

  /**
   * Tempdoc 577 Ext III — the raise-budget remedy: grant this run additional tokens mid-flight.
   * Thread-safe; the loop's between-step budget check and the next {@code AgentBudgetUpdate} pick up
   * the raised remaining naturally (no special-case resume path).
   */
  void addBudget(int tokens) {
    if (tokens > 0) {
      budgetRemaining.addAndGet(tokens);
      // Tempdoc 859 §D §3.2(7) — queue the grant for narration. The loop drains it, so a raise is
      // announced whether it resolved a held gate or landed mid-run; without this the mid-run raise
      // stayed silent, which is the half of D7 the first pass missed.
      pendingRaiseNarration.addAndGet(tokens);
    }
  }

  /**
   * Tempdoc 859 §D §3.2(7) — take the un-narrated grant total, zeroing it. Returns 0 when there is
   * nothing to announce.
   *
   * <p>DRAIN, not read: it is called from two sites — the budget gate's CONTINUE branch (which
   * narrates immediately, because the run visibly resumes there) and the next iteration_start
   * boundary (which catches a raise that landed while no gate was held). One counter drained by
   * both is what makes "exactly one note per grant" structural rather than a coordination rule
   * between two sites that could drift.
   */
  int drainPendingRaiseNarration() {
    return pendingRaiseNarration.getAndSet(0);
  }

  /**
   * Tempdoc 859 §D §2.7(c) — the PROVIDER-REPORTED size of the most recent prompt, or 0 before the
   * first LLM response. Unlike {@code countPromptTokens} this includes what the projection cannot
   * see (tool schemas), which is why the context-pressure trigger reads it.
   */
  int lastReportedPromptTokens() {
    return lastReportedPromptTokens.get();
  }

  /** Returns the total tokens consumed (prompt + completion). Thread-safe atomic snapshot. */
  synchronized int totalTokens() {
    return promptTokensConsumed.get() + completionTokensConsumed.get();
  }

  /** Tempdoc 577 §2.14 Root II — the model's context window (n_ctx); 0 when unknown. */
  int contextWindow() {
    return contextWindow;
  }

  /** Tempdoc 577 §2.14 Root II — set the context window once at run start (AgentLoopService). */
  void contextWindow(int n) {
    this.contextWindow = Math.max(0, n);
  }

  // --- Run observation (tempdoc 577 §2.14 Root I #13 / 834 §1.3 — the run as an observed entity) ---

  /** Binds this run to its observation channel. Called once at run start by the loop. */
  void observeThrough(RunObservation.Handle observation) {
    this.observation = observation == null ? RunObservation.Handle.NONE : observation;
  }

  /** The run's observation channel — the journal, the replay, and the secondary observers. */
  RunObservation.Handle observation() {
    return observation;
  }

  /** Registers the observer that started the run (the initiating SSE writer). */
  void attachInitiatingObserver(Consumer<AgentEvent> observer) {
    this.initiatingObserver = observer;
  }

  /**
   * Delivers an event to every observer: the journal (which fans out to reattachers) and the
   * initiating observer.
   *
   * <p>An initiating observer whose delivery THROWS is a dead socket, and is dropped — the same
   * eviction the substrate performs for its own listeners. Both halves matter: without the eviction
   * {@link #observerCount()} never reaches 0 and a Watch run proceeds unwatched; without the
   * swallow a closed socket would abort the loop, which is the V3 root cause this design keeps
   * fixed.
   */
  void deliverToObservers(AgentEvent event) {
    observation.publish(event);
    Consumer<AgentEvent> initiator = initiatingObserver;
    if (initiator == null) {
      return;
    }
    try {
      initiator.accept(event);
    } catch (RuntimeException deadSocket) {
      initiatingObserver = null;
    }
  }

  /** How many observers are currently attached to this run (the zero-observer policy reads this). */
  int observerCount() {
    return observation.observerCount() + (initiatingObserver == null ? 0 : 1);
  }

  /** Tempdoc 577 §2.14 Root I (#13) — what a run with NO observer does at its next decision point. */
  enum ZeroObserverPolicy {
    /** Park and await a re-attach before proceeding (a Watch run must not run unsupervised). */
    PARK,
    /** Proceed — the run's posture-graded gates already self-arbitrate without a watcher. */
    PROCEED
  }

  /**
   * Tempdoc 577 §2.14 Root I (#13) — the run's zero-observer behavior, a POSTURE-GRADED property of
   * the run entity (not a hidden default). A Watch run whose observer dropped PARKS — it must not
   * barrel ahead unsupervised; a re-attach resumes supervision (the approval gate is the park point,
   * reusing the held-gate machinery). Assist/Auto runs PROCEED: their gates already arbitrate by
   * posture, so a missing watcher changes nothing. With an observer present, always PROCEED.
   */
  ZeroObserverPolicy zeroObserverPolicy() {
    if (observerCount() > 0) {
      return ZeroObserverPolicy.PROCEED;
    }
    return autonomyLevel == io.justsearch.agent.api.registry.AutonomyLevel.WATCH
        ? ZeroObserverPolicy.PARK
        : ZeroObserverPolicy.PROCEED;
  }

  /**
   * Records token usage from an LLM response. Thread-safe - can be called from any thread.
   *
   * @param promptTokens tokens used for the prompt (may be null)
   * @param completionTokens tokens used for the completion (may be null)
   */
  void recordUsage(Integer promptTokens, Integer completionTokens) {
    if (promptTokens != null) {
      this.promptTokensConsumed.addAndGet(promptTokens);
      this.budgetRemaining.addAndGet(-promptTokens);
      // Tempdoc 859 §D §2.7(c) — remember the PROVIDER-REPORTED prompt size. The context-pressure
      // trigger used only `countPromptTokens`, which was schema-blind and measured ~40% low (577)
      // until 878 §D.6 threaded the tool list through it, so it could fire after the real prompt
      // already exceeded n_ctx. The trigger still reads this, for the staleness axis threading tools
      // does not touch. Same figure the `budget_update` phase `llm_response` puts on the wire.
      this.lastReportedPromptTokens.set(promptTokens);
    }
    if (completionTokens != null) {
      this.completionTokensConsumed.addAndGet(completionTokens);
      this.budgetRemaining.addAndGet(-completionTokens);
    }
  }

  record ExecutedToolCall(ToolCallRequest call, OperationResult result) {}

  // ---------------------------------------------------------------------------
  // Tempdoc 415: session-lifecycle observability accessors
  // ---------------------------------------------------------------------------

  /**
   * Records the typed termination reason. Idempotent: a second call logs a WARN and returns
   * without overwriting the captured reason. Called from the loop-owning thread before each
   * {@code return;} that ends the session, so the {@code finally{}} block can read the reason
   * for both the metric emit and the {@code AgentRunStore.setTerminationReason} patch.
   */
  void markTerminated(TerminalDisposition d, AgentErrorCode code, CancelTrigger trigger) {
    Objects.requireNonNull(d, "disposition");
    if (terminated) {
      LOG.warn(
          "markTerminated called twice (existing={}, new={}); keeping existing.", disposition, d);
      return;
    }
    this.disposition = d;
    this.terminationCode = code;
    this.cancelTrigger = trigger;
    this.terminated = true;
  }

  boolean isTerminated() {
    return terminated;
  }

  TerminalDisposition disposition() {
    return disposition;
  }

  AgentErrorCode terminationCode() {
    return terminationCode;
  }

  CancelTrigger cancelTrigger() {
    return cancelTrigger;
  }

  /** Wall-clock duration since session creation. */
  long durationMs() {
    return Duration.between(startedAt, Instant.now()).toMillis();
  }

  /**
   * Sum of UTF-8 byte sizes of every persisted message in the conversation. Computed on demand
   * (called once per session in the loop's {@code finally{}}); per-message exceptions are
   * swallowed and count as 0 bytes for that message, matching {@link #normalizeArgs}'s defensive
   * style.
   */
  int contextSizeBytes() {
    long total = 0L;
    for (Map<String, Object> message : messages) {
      try {
        total += NORM_MAPPER.writeValueAsString(message).getBytes(StandardCharsets.UTF_8).length;
      } catch (Exception ignored) {
        // Skip un-serializable messages; never fail telemetry on a serialization edge case.
      }
    }
    return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
  }
}
