package io.justsearch.app.services.conversation.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.conversation.ConversationContext;
import io.justsearch.agent.api.conversation.InjectorResult;
import io.justsearch.agent.api.conversation.SseEvent;
import io.justsearch.agent.api.registry.Audience;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.DocumentRecord;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.core.util.TokenEstimation;
import io.justsearch.indexing.rag.ContextBudgeter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link RAGContext} (slice 491 C3). */
final class RAGContextTest {

  // --- tempdoc 799 §N.2: justsearch.rag.top_k is wired. Before this, the setting resolved
  // correctly and DEFAULT_TOP_K always won. Precedence must be body -> configured -> 5.

  @Test
  @DisplayName("799 N.2: configured top-K replaces the hardcoded default when body omits topK")
  void configuredTopKUsedWhenBodyOmitsIt() {
    // Tempdoc 883 decision 3: the configured top_k is now an upper BOUND on a budget-derived
    // default, so this test supplies a window wide enough to afford it — otherwise it would
    // be asserting the budget rather than the config precedence it exists to pin. The bound
    // itself is covered by defaultTopKIsDerivedFromTheBudget.
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs, 17, () -> stubAi(32768, 32768));
    injector.inject(stubCtx(Map.of("question", "what?")));
    assertEquals(17, docs.lastTopK, "configured default must reach the retrieval call");
  }

  @Test
  @DisplayName("799 N.2: an explicit body topK still wins over the configured default")
  void bodyTopKWinsOverConfiguredDefault() {
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs, 17);
    injector.inject(stubCtx(Map.of("question", "what?", "topK", 3)));
    assertEquals(3, docs.lastTopK, "per-request topK must not be overridden by config");
  }

  @Test
  @DisplayName("799 N.2: no configured value falls back to DEFAULT_TOP_K")
  void fallsBackToCompiledDefault() {
    // Tempdoc 883 decision 3: the configured top_k is now an upper BOUND on a budget-derived
    // default, so this test supplies a window wide enough to afford it — otherwise it would
    // be asserting the budget rather than the config precedence it exists to pin. The bound
    // itself is covered by defaultTopKIsDerivedFromTheBudget.
    var docs = new TrackingDocs();
    var injector =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(32768, 32768));
    injector.inject(stubCtx(Map.of("question", "what?")));
    assertEquals(RAGContext.DEFAULT_TOP_K, docs.lastTopK);
  }

  @Test
  @DisplayName("799 N.2: a non-positive configured value is rejected, not propagated")
  void nonPositiveConfiguredValueRejected() {
    // Tempdoc 883 decision 3: the configured top_k is now an upper BOUND on a budget-derived
    // default, so this test supplies a window wide enough to afford it — otherwise it would
    // be asserting the budget rather than the config precedence it exists to pin. The bound
    // itself is covered by defaultTopKIsDerivedFromTheBudget.
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs, 0, () -> stubAi(32768, 32768));
    injector.inject(stubCtx(Map.of("question", "what?")));
    assertEquals(RAGContext.DEFAULT_TOP_K, docs.lastTopK);
  }

  @Test
  @DisplayName("ID is stable and namespaced under core")
  void idIsCoreNamespaced() {
    assertEquals("core.rag-context", RAGContext.ID);
  }

  @Test
  @DisplayName("Missing question → terminalError NO_QUESTION before any fetch")
  void missingQuestion() {
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("docIds", List.of("a"))));
    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_QUESTION", r.terminalError().get().payload().get("errorCode"));
    assertEquals(0, docs.retrieveCalls, "retrieve must not be called when question missing");
  }

  @Test
  @DisplayName("Empty docIds → open-retrieval via retrieveContext (NO_CONTENT when index is empty)")
  void emptyDocIdsOpenRetrieval() {
    var docs = new TrackingDocs();
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));
    // Open-retrieval attempts retrieveContext; the default stub returns empty context,
    // and since there's no batch fallback for empty docIds, it terminates with NO_CONTENT.
    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_CONTENT", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("Empty docIds + successful open-retrieval → happy path (chunks found via pre-search)")
  void emptyDocIdsSuccessfulOpenRetrieval() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("found text", 2, 3, 1, List.of(), "BM25", "pre_search", false, List.of());
    var injector = new RAGContext(docs);
    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));
    assertFalse(r.terminalError().isPresent());
    assertEquals(1, r.messages().size());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("found text"), "open-retrieval content injected");
    assertTrue(content.contains("what?"), "question appended");
  }

  @Test
  @DisplayName("Happy path: chunked retrieval → emits rag.meta, builds user message, stashes attributes")
  void happyPathChunkedRetrieval() {
    var citation =
        new ContextCitation(
            "doc-1", 0, 1, 0, 100, 0.9f, "excerpt", 0, 0, "", 0, ContextInclusion.ABSENT);
    var section = new ContextSection("[doc-1]", "the chunk text", false, 0, 0);
    var ctxResult =
        new ContextResult(
            "the chunk text",
            1, // chunksUsed
            1, // chunksFound
            1,
            List.of(citation),
            "BM25",
            "ok",
            false,
            List.of(section));
    var docs = new StubDocs(ctxResult, Map.of());
    var injector = new RAGContext(docs);
    var ctx = stubCtx(Map.of("question", "what is this?", "docIds", List.of("doc-1")));

    InjectorResult r = injector.inject(ctx);

    assertFalse(r.terminalError().isPresent(), "happy path must not be terminal");
    assertEquals(1, r.messages().size(), "single user message injected");
    String userContent = (String) r.messages().get(0).get("content");
    assertTrue(userContent.contains("Documents:"));
    assertTrue(userContent.contains("the chunk text"));
    assertTrue(userContent.contains("Question: what is this?"));

    // rag.meta + rag.citations events (slice 493: citations emitted at retrieval time)
    assertEquals(2, r.events().size());
    SseEvent meta = r.events().get(0);
    assertEquals("rag.meta", meta.name());
    assertEquals("BM25", meta.payload().get("retrieval_mode"));
    assertEquals(1, meta.payload().get("chunks_used"));
    assertEquals(1, meta.payload().get("chunks_found"));
    // Slice 493 Phase A: QualitySignals exposed in rag.meta
    assertTrue(meta.payload().containsKey("best_chunk_score"), "rag.meta must include best_chunk_score");
    assertTrue(meta.payload().containsKey("retrieval_coverage"), "rag.meta must include retrieval_coverage");
    assertTrue(meta.payload().containsKey("score_gap"), "rag.meta must include score_gap");
    assertTrue(meta.payload().containsKey("chunks_considered"), "rag.meta must include chunks_considered");
    SseEvent citations = r.events().get(1);
    assertEquals("rag.citations", citations.name());
    @SuppressWarnings("unchecked")
    var citList = (List<Map<String, Object>>) citations.payload().get("citations");
    assertEquals(1, citList.size(), "one citation from the single chunk");
    assertEquals("doc-1", citList.get(0).get("parentDocId"));

    // attributes stashed for downstream consumers
    assertEquals(true, ctx.attributes().get(RAGContext.ATTR_USED_RAG));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_CHUNKS_USED));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_CHUNKS_FOUND));
    assertEquals(1, ctx.attributes().get(RAGContext.ATTR_FILE_COUNT));
    assertEquals(List.of("doc-1"), ctx.attributes().get(RAGContext.ATTR_DOC_IDS));
    @SuppressWarnings("unchecked")
    List<ContextCitation> stashedCitations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    assertEquals(1, stashedCitations.size());
  }

  @Test
  @DisplayName("Retrieval returns empty chunks → fall back to fetchBatch")
  void fallbackToBatchFetch() {
    var emptyRetrieval =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of());
    var batch =
        Map.of("doc-1", new DocumentRecord("doc-1", "the fallback content", Map.of()));
    var docs = new StubDocs(emptyRetrieval, batch);
    var injector = new RAGContext(docs);
    var ctx = stubCtx(Map.of("question", "q", "docIds", List.of("doc-1")));

    InjectorResult r = injector.inject(ctx);

    assertFalse(r.terminalError().isPresent());
    assertEquals(1, r.messages().size());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("the fallback content"), "fallback content used");
    assertEquals(false, ctx.attributes().get(RAGContext.ATTR_USED_RAG));
    assertEquals(0, ctx.attributes().get(RAGContext.ATTR_CHUNKS_USED));
    @SuppressWarnings("unchecked")
    List<ContextCitation> citations =
        (List<ContextCitation>) ctx.attributes().get(RAGContext.ATTR_CITATIONS);
    assertTrue(citations.isEmpty(), "no citations when fallback path used");
  }

  @Test
  @DisplayName("FALLBACK_FAILED retrieval mode → terminalError immediately (no batch retry)")
  void fallbackFailedTerminal() {
    var failed =
        new ContextResult("", 0, 0, 0, List.of(), "FALLBACK_FAILED", "all_failed", false, List.of());
    var docs = new TrackingDocs();
    docs.retrieveResult = failed;
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertTrue(r.terminalError().isPresent());
    assertEquals("FETCH_FAILED", r.terminalError().get().payload().get("errorCode"));
    assertEquals(0, docs.fetchBatchCalls, "FALLBACK_FAILED must not retry fetchBatch");
  }

  @Test
  @DisplayName("Both retrieval and fallback empty → terminalError NO_CONTENT")
  void retrievalAndFallbackEmpty() {
    var emptyRetrieval =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of());
    var docs = new StubDocs(emptyRetrieval, Map.of()); // no batch content either
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertTrue(r.terminalError().isPresent());
    assertEquals("NO_CONTENT", r.terminalError().get().payload().get("errorCode"));
  }

  // --- Tempdoc 806 B.2 (round-12): an unscoped ask whose retrieval never COMPLETED used to answer
  // "No matching documents found in the index" — a confident claim about the corpus produced by a
  // call that did not run. Round 12 hit it on a cold reranker; the same question answered in ~9.5s
  // on retry, so the corpus claim was false.

  @Test
  @DisplayName("806: unscoped ask + retrieval TIMEOUT → RETRIEVAL_TIMEOUT, never NO_CONTENT")
  void openRetrievalTimeoutIsNotAClaimAboutTheCorpus() {
    var docs = new FailingRetrieveDocs(new java.util.concurrent.TimeoutException("budget"));
    var injector = new RAGContext(docs);

    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));

    assertTrue(r.terminalError().isPresent());
    Map<String, Object> payload = r.terminalError().get().payload();
    assertEquals("RETRIEVAL_TIMEOUT", payload.get("errorCode"));
    String message = String.valueOf(payload.get("error"));
    assertFalse(
        message.contains("No matching documents"),
        "an unfinished retrieval must not report on the corpus: " + message);
    assertTrue(
        message.contains("not a result about your documents"),
        "the copy must disclaim the corpus reading: " + message);
  }

  @Test
  @DisplayName("806: a gRPC DEADLINE_EXCEEDED underneath is recognised as a timeout, not a plain failure")
  void grpcDeadlineIsRecognisedAsTimeout() {
    var docs =
        new FailingRetrieveDocs(
            new RuntimeException("DEADLINE_EXCEEDED: deadline exceeded after 9.999s"));
    var injector = new RAGContext(docs);

    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));

    assertEquals("RETRIEVAL_TIMEOUT", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("806: a non-timeout retrieval failure is RETRIEVAL_FAILED, still not a corpus claim")
  void openRetrievalFailureIsNotAClaimAboutTheCorpus() {
    var docs = new FailingRetrieveDocs(new IllegalStateException("worker down"));
    var injector = new RAGContext(docs);

    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));

    assertEquals("RETRIEVAL_FAILED", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("806: a retrieval that RAN and found nothing still says NO_CONTENT (unchanged)")
  void openRetrievalThatRanAndFoundNothingKeepsTheCorpusClaim() {
    var docs = new TrackingDocs(); // completes with an empty ContextResult
    var injector = new RAGContext(docs);

    InjectorResult r = injector.inject(stubCtx(Map.of("question", "what?")));

    assertEquals("NO_CONTENT", r.terminalError().get().payload().get("errorCode"));
  }

  @Test
  @DisplayName("RetrieveContextWithMeta throwing falls through to batch fetch")
  void retrievalExceptionFallsBackToBatch() {
    var batch =
        Map.of("doc-1", new DocumentRecord("doc-1", "the fallback content", Map.of()));
    var docs = new ThrowingRetrieveDocs(batch);
    var injector = new RAGContext(docs);

    InjectorResult r =
        injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("doc-1"))));

    assertFalse(r.terminalError().isPresent());
    assertNotNull(r.messages());
    String content = (String) r.messages().get(0).get("content");
    assertTrue(content.contains("the fallback content"));
  }

  @Test
  @DisplayName("topK respected when supplied in body")
  void topKFromBody() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("text", 1, 1, 1, List.of(), "BM25", "ok", false, List.of());
    var injector = new RAGContext(docs);
    injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("a"), "topK", 12)));
    assertEquals(12, docs.lastTopK);
  }

  @Test
  @DisplayName("Default topK = 5 when missing")
  void topKDefault() {
    var docs = new TrackingDocs();
    docs.retrieveResult =
        new ContextResult("text", 1, 1, 1, List.of(), "BM25", "ok", false, List.of());
    // Tempdoc 883 decision 3: the configured top_k is now an upper BOUND on a budget-derived
    // default, so this test supplies a window wide enough to afford it — otherwise it would
    // be asserting the budget rather than the config precedence it exists to pin. The bound
    // itself is covered by defaultTopKIsDerivedFromTheBudget.
    var injector =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(32768, 32768));
    injector.inject(stubCtx(Map.of("question", "q", "docIds", List.of("a"))));
    assertEquals(RAGContext.DEFAULT_TOP_K, docs.lastTopK);
  }

  // --- tempdoc 821 §3-C2: the chat SPI carries the caller's collection scope to retrieval.
  // The RAG path had no way to express a scope at all, so an ASK always ran under the default one.

  @Test
  @DisplayName("821 3-C2: a body collection scope reaches the scoped-retrieval params")
  void collectionScopeReachesScopedRetrieval() {
    var docs = new CapturingParamsDocs();
    new RAGContext(docs)
        .inject(stubCtx(Map.of(
            "question", "what did the agent do?",
            "docIds", List.of("d:/agent/session-1.md"),
            "collection", List.of("agent-history"))));

    assertNotNull(docs.lastParams, "retrieveContext(params) must be the path taken");
    assertEquals(List.of("agent-history"), docs.lastParams.collection());
  }

  @Test
  @DisplayName("821 3-C2: the scope also reaches the open-retrieval (empty docIds) path")
  void collectionScopeReachesOpenRetrieval() {
    var docs = new CapturingParamsDocs();
    new RAGContext(docs)
        .inject(stubCtx(Map.of(
            "question", "what did the agent do?", "collection", List.of("agent-history"))));

    assertNotNull(docs.lastParams);
    assertEquals(List.of("agent-history"), docs.lastParams.collection());
  }

  @Test
  @DisplayName("821 3-C2: an absent collection is an empty scope, i.e. unchanged behavior")
  void absentCollectionIsEmptyScope() {
    var docs = new CapturingParamsDocs();
    new RAGContext(docs).inject(stubCtx(Map.of("question", "what?", "docIds", List.of("a"))));

    assertNotNull(docs.lastParams);
    assertEquals(
        List.of(),
        docs.lastParams.collection(),
        "empty is the DEFAULT scope on the Worker side, never a match-nothing filter");
  }

  // --- tempdoc 845: honest context budgeting. Both call sites hardcoded
  // computeSafeInputBudgetTokens(8192, 1024) — a window that does not exist paired with a reserve
  // that ignored the request. Each site is covered separately: reverting either one to the old
  // constants fails a named test below.

  /** CALL SITE 2 (tryOpenRetrieval) — the budget crosses the wire as the Worker's cap. */
  @Test
  @DisplayName("845: open retrieval sends a budget sized by the LIVE window, not 8192")
  void openRetrievalBudgetUsesLiveContextWindow() {
    var docs = new CapturingParamsDocs();
    // No docIds -> open retrieval. Live window 4096, Thorough's reserve 3072.
    var ctx = stubCtx(Map.of("question", "what?"));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    assertNotNull(docs.lastParams);
    assertEquals(
        460,
        docs.lastParams.maxContextTokens(),
        "(4096 - 3072 - 512) * 0.9; the old hardcode sent 5990 into a 4096-token window");
  }

  @Test
  @DisplayName("845: open-retrieval budget tracks the request's own reserve, not a flat 1024")
  void openRetrievalBudgetTracksRequestReserve() {
    var docs = new CapturingParamsDocs();
    var thorough = stubCtx(Map.of("question", "what?"));
    thorough.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(thorough);
    int thoroughBudget = docs.lastParams.maxContextTokens();

    var quick = stubCtx(Map.of("question", "what?"));
    quick.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 512);
    new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(quick);
    int quickBudget = docs.lastParams.maxContextTokens();

    assertTrue(
        quickBudget > thoroughBudget,
        "a smaller completion reserve must buy more input budget: "
            + quickBudget
            + " vs "
            + thoroughBudget);
    assertEquals(2764, quickBudget);
  }

  @Test
  @DisplayName("845: an unobserved window falls back to the CONFIGURED one, never to 8192")
  void unknownLiveWindowFallsBackToConfigured() {
    var docs = new CapturingParamsDocs();
    var ctx = stubCtx(Map.of("question", "what?"));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    // llmContextTokens() null = no server observed yet; configured is the next authority.
    new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(null, 2048)).inject(ctx);

    assertEquals(
        460,
        docs.lastParams.maxContextTokens(),
        "(2048 - 1024 - 512) * 0.9 — unknown must not be treated as generous");
  }

  @Test
  @DisplayName("883: the DEFAULT passage count is derived from the budget, bounded by top_k")
  void defaultTopKIsDerivedFromTheBudget() {
    // A 32768-token window affords 56 chunks of the corpus's 500-token chunk size, but rag.top_k is
    // an upper bound, not a target: the ask stays at 5.
    var wide = new CapturingParamsDocs();
    var wideCtx = stubCtx(Map.of("question", "what?"));
    wideCtx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    new RAGContext(wide, 5, () -> stubAi(32768, 32768)).inject(wideCtx);
    assertEquals(5, wide.lastParams.topK(), "top_k bounds the derived shape from above");

    // A 2048-token window affords 921/500 = 1. Asking for five passages there is what made 845's
    // trimmer fire on every ask: the shape now asks for what the window can hold.
    var narrow = new CapturingParamsDocs();
    var narrowCtx = stubCtx(Map.of("question", "what?"));
    narrowCtx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    new RAGContext(narrow, 5, () -> stubAi(null, 2048)).inject(narrowCtx);
    assertEquals(
        1,
        narrow.lastParams.topK(),
        "(2048 - 1024 - 512) * 0.9 = 460 tokens of input budget holds ONE 500-token chunk");
  }

  @Test
  @DisplayName("883: an explicit body topK still wins verbatim, budget or no budget")
  void explicitTopKIsNeverOverriddenByTheBudget() {
    var docs = new CapturingParamsDocs();
    var ctx = stubCtx(Map.of("question", "what?", "topK", 7));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    new RAGContext(docs, 5, () -> stubAi(null, 2048)).inject(ctx);

    assertEquals(
        7,
        docs.lastParams.topK(),
        "a caller that named a number gets that number; the derivation replaces the DEFAULT only");
  }

  @Test
  @DisplayName("883: the derived shape and the wire budget come from ONE ContextBudget per request")
  void shapeAndWireBudgetAgree() {
    var docs = new CapturingParamsDocs();
    var ctx = stubCtx(Map.of("question", "what?"));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    new RAGContext(docs, 5, () -> stubAi(8192, 8192)).inject(ctx);

    io.justsearch.core.util.ContextBudget budget =
        RAGContext.budgetFor(() -> stubAi(8192, 8192), 1024);
    assertEquals(budget.inputBudget(), docs.lastParams.maxContextTokens());
    assertEquals(
        Math.max(1, Math.min(5, budget.inputBudget() / 500)), docs.lastParams.topK());
  }

  @Test
  @DisplayName("845: open-retrieval budget stays positive so the Worker keeps token-aware mode")
  void openRetrievalBudgetNeverCollapsesToZero() {
    var docs = new CapturingParamsDocs();
    var ctx = stubCtx(Map.of("question", "what?"));
    // A reserve larger than the whole window leaves no headroom at all.
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 9000);
    new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    assertTrue(
        docs.lastParams.maxContextTokens() > 0,
        "0 would flip the Worker into its 200K-character fallback, the opposite of a zero budget");
  }

  /** CALL SITE 1 (inject) — the post-retrieval truncation safety net. */
  @Test
  @DisplayName("845: an over-budget context is TRIMMED and reported, not passed through whole")
  void oversizedContextIsTrimmedAndReported() {
    // ~2900 estimated tokens: comfortably OVER the honest budget for a 3072-token reserve (460),
    // and comfortably UNDER what the old hardcoded (8192, 1024) call allowed (5990). Sizing it
    // between the two is what makes this test discriminate — a context large enough to truncate
    // under BOTH budgets would pass even with the defect restored.
    String huge = ("lorem ipsum dolor sit amet consectetur ").repeat(300);
    var docs =
        new StubDocs(
            new ContextResult(huge, 3, 3, 3, List.of(), "BM25", "ok", false, List.of()), Map.of());

    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    InjectorResult result =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    String injected = (String) result.messages().get(0).get("content");
    assertTrue(
        injected.contains("[... content truncated ...]"),
        "the safety net must actually fire against a real 4096-token window");
    assertTrue(
        injected.length() < huge.length(),
        "the trimmed prompt must be shorter than the retrieved context");

    // The honesty half: a locally-trimmed turn must not report itself as untruncated.
    Map<String, Object> ragMeta = ragMetaOf(result);
    assertEquals(
        true,
        ragMeta.get("context_truncated"),
        "context_truncated was Worker-only, so a locally-trimmed turn looked complete");
  }

  @Test
  @DisplayName("845: a context that fits is untouched and still reports untruncated")
  void fittingContextIsNotTrimmed() {
    String small = "a short retrieved passage";
    var docs =
        new StubDocs(
            new ContextResult(small, 1, 1, 1, List.of(), "BM25", "ok", false, List.of()), Map.of());

    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    InjectorResult result =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    String injected = (String) result.messages().get(0).get("content");
    assertTrue(injected.contains(small), "an in-budget context must survive intact");
    assertFalse(injected.contains("[... content truncated ...]"));
    assertEquals(false, ragMetaOf(result).get("context_truncated"));
  }

  @Test
  @DisplayName("845: the Worker's own truncation is still reported when the local trim does not fire")
  void workerTruncationIsPreserved() {
    var docs =
        new StubDocs(
            // contextTruncated = true from the Worker, tiny context so the local trim cannot fire.
            new ContextResult("tiny", 1, 1, 1, List.of(), "BM25", "ok", true, List.of()), Map.of());

    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    InjectorResult result =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    assertEquals(
        true,
        ragMetaOf(result).get("context_truncated"),
        "the two truncation sources must OR together, not replace one another");
  }

  @Test
  @DisplayName("845: rag.meta is still emitted before rag.citations")
  void ragMetaStillPrecedesCitations() {
    var citation =
        new ContextCitation("a", 0, 1, 0, 4, 0.9f, "text", 1, 1, null, 0, ContextInclusion.ABSENT);
    var docs =
        new StubDocs(
            new ContextResult("text", 1, 1, 1, List.of(citation), "BM25", "ok", false, List.of()),
            Map.of());

    InjectorResult result =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096))
            .inject(stubCtx(Map.of("question", "what?", "docIds", List.of("a"))));

    List<String> order = result.events().stream().map(SseEvent::name).toList();
    assertTrue(order.contains("rag.meta"), "rag.meta must still be emitted: " + order);
    assertTrue(
        order.indexOf("rag.meta") < order.indexOf("rag.citations"),
        "deferring the emission must not reorder the stream: " + order);
  }

  // --- Tempdoc 849: retrieved is not received. The cut is now section-aware on the SECTIONED
  // branch and records, per citation, what it did with that passage. The whole-document fallback
  // keeps the structure-blind cut (D-1) — deleting it would leave a whole-document context
  // entirely untruncated, re-opening the overcommit 845 closed.

  /** 6 words / 39 chars per repeat; 30 repeats estimate to 293 tokens against a 460-token budget. */
  private static final String PASSAGE_BODY = "lorem ipsum dolor sit amet consectetur ".repeat(30);

  @Test
  @DisplayName("849: the passage the cut discarded is DROPPED on ITS OWN citation")
  void droppedIsRecordedOnTheCitationItDescribes() {
    InjectorResult result = injectOverBudgetSectionedTurn(4);
    List<Map<String, Object>> citations = citationsOf(result);

    assertEquals(4, citations.size(), "every retrieved passage still gets a citation");
    // Named citations, not "some citation is dropped" — the whole point is per-passage truth.
    assertEquals("included", citationFor(citations, "doc-0").get("contextInclusion"));
    assertEquals("dropped", citationFor(citations, "doc-3").get("contextInclusion"));
    // …and the prompt genuinely does not contain the dropped passage's header.
    String raw = assembledContextOf(result);
    assertFalse(raw.contains("] doc-3"), "a dropped passage must not be in the prompt: " + raw);
  }

  @Test
  @DisplayName("849: a boundary passage is PARTIAL with a plausible contextIncludedChars")
  void boundaryPassageIsPartial() {
    InjectorResult result = injectOverBudgetSectionedTurn(4);
    Map<String, Object> boundary = citationFor(citationsOf(result), "doc-1");

    assertEquals("partial", boundary.get("contextInclusion"));
    int includedChars = (Integer) boundary.get("contextIncludedChars");
    assertTrue(includedChars > 0, "partial means SOME text reached the model, not none");
    assertTrue(
        includedChars < PASSAGE_BODY.length(),
        "partial means NOT all of it either: " + includedChars + " of " + PASSAGE_BODY.length());
  }

  @Test
  @DisplayName("849: a turn that FITS marks every citation included AND reports untruncated")
  void fittingTurnMarksEveryCitationIncluded() {
    var retrieval = sectionedRetrieval(3, "a short retrieved passage");
    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 1024);
    InjectorResult result =
        new RAGContext(new StubDocs(retrieval, Map.of()), RAGContext.DEFAULT_TOP_K,
                () -> stubAi(4096, 4096))
            .inject(ctx);

    for (Map<String, Object> c : citationsOf(result)) {
      assertEquals("included", c.get("contextInclusion"), "nothing was cut: " + c);
    }
    // The discriminator: without this the test would also pass if the field simply defaulted to
    // "included" for a turn that WAS truncated.
    assertEquals(false, ragMetaOf(result).get("context_truncated"));
  }

  @Test
  @DisplayName("849: the re-assembled context still parses as numbered passages (flattening fix)")
  void reAssembledContextSurvivesTheRealPromptFormatter() {
    InjectorResult result = injectOverBudgetSectionedTurn(4);
    String raw = assembledContextOf(result);

    assertTrue(
        raw.contains(ContextBudgeter.SECTION_SEPARATOR),
        "the separators the old structure-blind cut flattened must survive: " + raw);

    // Round-tripped through the REAL online-path parser, not a re-implementation of it: that
    // parser keys each passage id to the header ordinal precisely so it cannot disagree with the
    // FE's sources[n - 1]. The old cut destroyed every header, so every parse fell back to a
    // running counter — the exact divergence its javadoc was written to prevent.
    String passages = InferenceLifecycleManager.formatContextAsNumberedPassages(raw);
    assertTrue(passages.contains("<passage id=\"1\" source=\"doc-0\">"), passages);
    assertTrue(passages.contains("<passage id=\"2\" source=\"doc-1\">"), passages);
    assertEquals(
        2,
        passages.split("<passage id=", -1).length - 1,
        "exactly the two passages that survived the cut, each with its ORIGINAL ordinal");
  }

  @Test
  @DisplayName("849 D-1: a whole-document fallback over budget is STILL truncated")
  void wholeDocumentFallbackIsStillTruncated() {
    // chunksUsed == 0 → the context is REPLACED by fetchBatch text, which has no sections. This
    // branch must keep the structure-blind cut; the section-aware branch has nothing to work with.
    var emptyRetrieval =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of());
    String huge = "lorem ipsum dolor sit amet consectetur ".repeat(300);
    var docs =
        new StubDocs(
            emptyRetrieval, Map.of("doc-1", new DocumentRecord("doc-1", huge, Map.of())));

    var ctx = stubCtx(Map.of("question", "q", "docIds", List.of("doc-1")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    InjectorResult result =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(ctx);

    String injected = (String) result.messages().get(0).get("content");
    assertTrue(
        injected.contains("[... content truncated ...]"),
        "the fallback branch must keep truncating — 845's overcommit is one deleted call away");
    assertTrue(injected.length() < huge.length());
    assertEquals(true, ragMetaOf(result).get("context_truncated"));
  }

  @Test
  @DisplayName("849 D-5: headers and separators count against the budget, not just content")
  void sectionOverheadIsChargedAgainstTheBudget() {
    // Content alone fits the 460-token budget; content + the 20 headers + 19 separators does not.
    // A re-assembly that budgeted only content would emit every section and overshoot.
    String label = "a-source-label-long-enough-to-cost-real-tokens-in-its-header";
    String body = "alpha beta gamma delta ".repeat(4);
    var retrieval = sectionedRetrieval(20, body, label);
    int contentTokens = 20 * TokenEstimation.estimateTokens(body);
    assertTrue(contentTokens <= 460, "precondition: the CONTENT alone fits (" + contentTokens + ")");

    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    InjectorResult result =
        new RAGContext(new StubDocs(retrieval, Map.of()), RAGContext.DEFAULT_TOP_K,
                () -> stubAi(4096, 4096))
            .inject(ctx);

    String raw = assembledContextOf(result);
    assertTrue(
        TokenEstimation.estimateTokens(raw) <= 460,
        "the assembled context must respect the budget INCLUDING its own overhead: "
            + TokenEstimation.estimateTokens(raw));
    assertEquals(true, ragMetaOf(result).get("context_truncated"));
  }

  @Test
  @DisplayName("849 D-5: a zero input budget means the same floor on BOTH cut branches")
  void zeroBudgetCarriesTheSameFloorOnBothBranches() {
    // Since 845 the budget genuinely can be 0 (the reserve swallows the window). truncateIfNeeded
    // has always floored that at MIN_BUDGET, so 0 means "a 256-token floor of context", not "no
    // context". Both branches must agree — otherwise the sectioned branch silently returns nothing.
    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("doc-1")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 9000);
    assertEquals(
        0,
        TokenEstimation.computeSafeInputBudgetTokens(4096, 9000),
        "precondition: this really is the zero-budget case");

    InjectorResult sectioned =
        new RAGContext(
                new StubDocs(sectionedRetrieval(4, PASSAGE_BODY), Map.of()),
                RAGContext.DEFAULT_TOP_K,
                () -> stubAi(4096, 4096))
            .inject(ctx);
    String sectionedContext = assembledContextOf(sectioned);
    assertFalse(sectionedContext.isEmpty(), "a zero budget must not silently mean NO context");
    assertTrue(
        TokenEstimation.estimateTokens(sectionedContext) <= 256,
        "…but it must respect the same MIN_BUDGET floor the other branch applies");

    var fallbackCtx = stubCtx(Map.of("question", "what?", "docIds", List.of("doc-1")));
    fallbackCtx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 9000);
    var docs =
        new StubDocs(
            new ContextResult("", 0, 0, 0, List.of(), "BM25", "no_hits", false, List.of()),
            Map.of("doc-1", new DocumentRecord("doc-1", PASSAGE_BODY.repeat(4), Map.of())));
    InjectorResult fallback =
        new RAGContext(docs, RAGContext.DEFAULT_TOP_K, () -> stubAi(4096, 4096)).inject(fallbackCtx);
    String fallbackContent = (String) fallback.messages().get(0).get("content");
    assertTrue(fallbackContent.contains("lorem"), "the floor leaves real content on both branches");
    assertEquals(true, ragMetaOf(fallback).get("context_truncated"));
  }

  @Test
  @DisplayName("849 F6: an assembly that overshoots though its PARTS fit falls back and truncates")
  void nonAdditiveEstimatorOvershootIsCaught() {
    // The per-section loop budgets the SUM of the parts, but estimateTokens is not additive: it
    // switches charEstimate on the whitespace and non-ASCII RATIOS of whatever string it is given.
    // Sparse-ASCII sections estimate at len/4; dense-CJK sections at len. Concatenated, the mixture
    // crosses BOTH thresholds the parts individually stayed the safe side of (nonAscii > 0.5 with
    // whitespace < 0.05), so the whole estimates ~len — far above the sum of the parts.
    String sparseAscii = ("a".repeat(49) + " ").repeat(2); // 100 chars, whitespace ratio 0.02
    String denseCjk = "文".repeat(150); // 150 chars, no whitespace, 100% non-ASCII
    List<ContextSection> sections =
        List.of(
            new ContextSection("d0", sparseAscii, false, 0, 0),
            new ContextSection("d1", denseCjk, false, 1, 1),
            new ContextSection("d2", sparseAscii, false, 2, 2),
            new ContextSection("d3", denseCjk, false, 3, 3));

    int partsSum = sections.stream().mapToInt(s -> TokenEstimation.estimateTokens(s.content())).sum();
    StringBuilder naive = new StringBuilder();
    for (int i = 0; i < sections.size(); i++) {
      if (i > 0) naive.append(ContextBudgeter.SECTION_SEPARATOR);
      naive.append(ContextBudgeter.sectionHeader(i + 1, sections.get(i).sourceLabel()))
          .append(sections.get(i).content());
    }
    int assembled = TokenEstimation.estimateTokens(naive.toString());
    // The counter-example only means anything if BOTH preconditions hold — otherwise the test would
    // pass for the wrong reason (e.g. because the parts alone already exceeded the budget).
    assertTrue(partsSum <= 460, "precondition: the parts' own estimates fit the budget: " + partsSum);
    assertTrue(
        assembled > 460,
        "precondition: the ASSEMBLY estimates over it (" + assembled + ") — this is the"
            + " non-additivity the guard exists for");

    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    List<ContextCitation> citations = new java.util.ArrayList<>();
    for (int i = 0; i < sections.size(); i++) {
      citations.add(
          new ContextCitation(
              "doc-" + i, i, 4, 0, 10, 0.9f, "e" + i, 0, 0, "", 0, ContextInclusion.ABSENT));
    }
    var retrieval =
        new ContextResult(
            naive.toString(), 4, 4, 4, citations, "BM25", "ok", false, sections);
    InjectorResult result =
        new RAGContext(new StubDocs(retrieval, Map.of()), RAGContext.DEFAULT_TOP_K,
                () -> stubAi(4096, 4096))
            .inject(ctx);

    String raw = assembledContextOf(result);
    assertTrue(
        TokenEstimation.estimateTokens(raw) <= 460,
        "the guarantee is about the string handed to the model, not the parts it was built from: "
            + TokenEstimation.estimateTokens(raw));
    assertEquals(true, ragMetaOf(result).get("context_truncated"));
    // The per-section record cannot survive a structure-blind cut of the assembly, so it is dropped
    // rather than left describing text that is no longer there.
    for (Map<String, Object> c : citationsOf(result)) {
      assertFalse(c.containsKey("contextInclusion"), c.toString());
    }
  }

  @Test
  @DisplayName("849: a citations/sections length mismatch leaves inclusion ABSENT, not guessed")
  void lengthMismatchLeavesInclusionUnresolved() {
    // The join is positional. When the two lists disagree the alignment is suspect, and a state
    // derived from a suspect alignment is worse than no state at all.
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-0", 0, 2, 0, 10, 0.9f, "e0", 0, 0, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-1", 1, 2, 0, 10, 0.8f, "e1", 0, 0, "", 0, ContextInclusion.ABSENT));
    List<ContextSection> sections =
        List.of(
            new ContextSection("doc-0", "one", false, 0, 0),
            new ContextSection("doc-1", "two", false, 1, 1),
            new ContextSection("doc-2", "three", false, 2, 2));
    var retrieval =
        new ContextResult("one", 2, 2, 2, citations, "BM25", "ok", false, sections);

    InjectorResult result =
        new RAGContext(new StubDocs(retrieval, Map.of()), RAGContext.DEFAULT_TOP_K,
                () -> stubAi(4096, 4096))
            .inject(stubCtx(Map.of("question", "what?", "docIds", List.of("a"))));

    for (Map<String, Object> c : citationsOf(result)) {
      assertFalse(
          c.containsKey("contextInclusion"),
          "absence is expressed by omitting the key, never by a guessed state: " + c);
    }
  }

  /** A sectioned retrieval whose four passages cannot all fit the honest 460-token budget. */
  private static InjectorResult injectOverBudgetSectionedTurn(int sectionCount) {
    var ctx = stubCtx(Map.of("question", "what?", "docIds", List.of("a")));
    ctx.attributes().put(RAGContext.ATTR_COMPLETION_RESERVE_TOKENS, 3072);
    return new RAGContext(
            new StubDocs(sectionedRetrieval(sectionCount, PASSAGE_BODY), Map.of()),
            RAGContext.DEFAULT_TOP_K,
            () -> stubAi(4096, 4096))
        .inject(ctx);
  }

  private static ContextResult sectionedRetrieval(int count, String body) {
    return sectionedRetrieval(count, body, null);
  }

  /** Mirrors what the Worker's ContextBudgeter produces: index-aligned citations and sections. */
  private static ContextResult sectionedRetrieval(int count, String body, String labelPrefix) {
    List<ContextCitation> citations = new java.util.ArrayList<>(count);
    List<ContextSection> sections = new java.util.ArrayList<>(count);
    StringBuilder assembled = new StringBuilder();
    for (int i = 0; i < count; i++) {
      String label = labelPrefix == null ? "doc-" + i : labelPrefix + "-" + i;
      citations.add(
          new ContextCitation(
              "doc-" + i, i, count, 0, body.length(), 0.9f, "excerpt-" + i, 0, 0, "", 0,
              ContextInclusion.ABSENT));
      sections.add(new ContextSection(label, body, false, i, i));
      if (i > 0) assembled.append(ContextBudgeter.SECTION_SEPARATOR);
      assembled.append('[').append(i + 1).append("] ").append(label).append('\n').append(body);
    }
    return new ContextResult(
        assembled.toString(), count, count, count, citations, "BM25", "ok", false, sections);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> citationsOf(InjectorResult result) {
    return (List<Map<String, Object>>)
        result.events().stream()
            .filter(e -> "rag.citations".equals(e.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no rag.citations event emitted"))
            .payload()
            .get("citations");
  }

  private static Map<String, Object> citationFor(
      List<Map<String, Object>> citations, String parentDocId) {
    return citations.stream()
        .filter(c -> parentDocId.equals(c.get("parentDocId")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no citation for " + parentDocId));
  }

  /** The context actually put in front of the model, unwrapped from the injected user message. */
  private static String assembledContextOf(InjectorResult result) {
    String injected = (String) result.messages().get(0).get("content");
    int end = injected.indexOf("\n\nQuestion: ");
    return injected.substring("Documents:\n".length(), end);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> ragMetaOf(InjectorResult result) {
    return (Map<String, Object>)
        result.events().stream()
            .filter(e -> "rag.meta".equals(e.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no rag.meta event emitted"))
            .payload();
  }

  /** Minimal online-AI stub exposing only the two context-window accessors this budget reads. */
  private static io.justsearch.app.api.OnlineAiService stubAi(Integer observed, Integer configured) {
    return new io.justsearch.app.api.OnlineAiService() {
      @Override
      public CompletableFuture<String> summarize(String content) {
        return CompletableFuture.completedFuture("");
      }

      @Override
      public CompletableFuture<String> askQuestion(String question, String context) {
        return CompletableFuture.completedFuture("");
      }

      @Override
      public boolean isAvailable() {
        return true;
      }

      @Override
      public boolean isStartingUp() {
        return false;
      }

      @Override
      public Integer llmContextTokens() {
        return observed;
      }

      @Override
      public Integer configuredContextTokens() {
        return configured;
      }
    };
  }

  private static ConversationContext stubCtx(Map<String, Object> body) {
    return new ConversationContext() {
      @Override
      public io.justsearch.core.context.EngineContext engineContext() {
        return io.justsearch.app.services.TestEngineContexts.internal();
      }

      private final Map<String, Object> a = new HashMap<>();
      private final Map<String, Object> b = new LinkedHashMap<>(body);

      @Override
      public List<Map<String, Object>> messages() {
        return List.of();
      }

      @Override
      public int iteration() {
        return 0;
      }

      @Override
      public Audience audience() {
        return Audience.USER;
      }

      @Override
      public String sessionId() {
        return null;
      }

      @Override
      public Map<String, Object> requestBody() {
        return b;
      }

      @Override
      public Map<String, Object> attributes() {
        return a;
      }
    };
  }

  /** Stub that succeeds retrieval with the supplied result, and falls back to a fixed batch map. */
  private static final class StubDocs implements DocumentService {
    private final ContextResult retrieval;
    private final Map<String, DocumentRecord> batch;

    StubDocs(ContextResult retrieval, Map<String, DocumentRecord> batch) {
      this.retrieval = retrieval;
      this.batch = batch;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(batch.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        DocumentRecord r = batch.get(id);
        if (r != null) out.put(id, r);
      }
      return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(retrieval);
    }
  }

  /** Tracks calls + returns a configurable result. */
  private static final class TrackingDocs implements DocumentService {
    int retrieveCalls = 0;
    int fetchBatchCalls = 0;
    int lastTopK = -1;
    ContextResult retrieveResult =
        new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of());

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      fetchBatchCalls++;
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, io.justsearch.core.context.EngineContext engineContext) {
      retrieveCalls++;
      lastTopK = topK;
      return CompletableFuture.completedFuture(retrieveResult);
    }
  }

  /**
   * Tempdoc 821 §3-C2 — captures the rich {@link RetrieveContextParams} the injector builds.
   * {@link TrackingDocs} cannot serve this: it implements only the positional overload, so the
   * interface default drops every filter component before the assertion could see it.
   */
  private static final class CapturingParamsDocs implements DocumentService {
    RetrieveContextParams lastParams;

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(
          new ContextResult("", 0, 0, 0, List.of(), "BM25", "", false, List.of()));
    }

    @Override
    public CompletionStage<ContextResult> retrieveContext(RetrieveContextParams params, io.justsearch.core.context.EngineContext engineContext) {
      lastParams = params;
      return CompletableFuture.completedFuture(
          new ContextResult("text", 1, 1, 1, List.of(), "BM25", "ok", false, List.of()));
    }
  }

  /** Tempdoc 806 B.2 — retrieval that fails with a caller-chosen cause; no batch content at all. */
  private static final class FailingRetrieveDocs implements DocumentService {
    private final Throwable cause;

    FailingRetrieveDocs(Throwable cause) {
      this.cause = cause;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(Map.of());
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.failedFuture(cause);
    }
  }

  private static final class ThrowingRetrieveDocs implements DocumentService {
    private final Map<String, DocumentRecord> batch;

    ThrowingRetrieveDocs(Map<String, DocumentRecord> batch) {
      this.batch = batch;
    }

    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.completedFuture(batch.get(docId));
    }

    @Override
    public CompletionStage<Map<String, DocumentRecord>> fetchBatch(List<String> docIds, io.justsearch.core.context.EngineContext engineContext) {
      Map<String, DocumentRecord> out = new LinkedHashMap<>();
      for (String id : docIds) {
        DocumentRecord r = batch.get(id);
        if (r != null) out.put(id, r);
      }
      return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<ContextResult> retrieveContextWithMeta(
        String question, Set<String> docIds, int topK, int maxContextTokens, io.justsearch.core.context.EngineContext engineContext) {
      return CompletableFuture.failedFuture(new RuntimeException("retrieval down"));
    }
  }
}
