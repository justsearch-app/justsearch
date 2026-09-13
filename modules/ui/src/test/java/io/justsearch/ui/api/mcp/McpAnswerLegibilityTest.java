/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.core.context.EngineContext;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.DocumentService.ContextCitation;
import io.justsearch.app.api.DocumentService.ContextInclusion;
import io.justsearch.app.api.DocumentService.ContextResult;
import io.justsearch.app.api.DocumentService.ContextSection;
import io.justsearch.app.api.DocumentService.QualitySignals;
import io.justsearch.app.api.RetrieveContextParams;
import io.justsearch.app.api.WorkerServices;
import io.justsearch.app.services.HeadAssembly;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tempdoc 725 (design #2, increments W2a/W2b/W2c) — pins {@code justsearch_answer}'s
 * self-describing evidence-pack header, the {@code response_format} concise/detailed density
 * tier, and the {@code contextFormat} the call site requests from {@code DocumentService}.
 *
 * <p>The W2b regression (audit-without-test rule): {@code McpToolSurface.callAnswer} used to
 * request {@code ContextFormat.XML} even though the Worker's {@code ContextBudgeter} has no
 * XML/PLAIN branch and always renders LABELED ({@code "[n] label\n"} sections) — a dead
 * orphan request (tempdoc 725 orphan #5). {@link #answerRequestsLabeledContextFormat()} captures
 * the actual {@link RetrieveContextParams} passed to {@code DocumentService.retrieveContext} and
 * fails if the call site ever requests a format other than LABELED again.
 */
@DisplayName("McpToolSurface justsearch_answer: response legibility (tempdoc 725 W2)")
final class McpAnswerLegibilityTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneId.of("UTC"));

  private record Invocation(
      Map<String, Object> result, RetrieveContextParams capturedParams) {}

  private static Invocation invokeAnswer(ContextResult canned, Map<String, Object> args) {
    DocumentService documents = mock(DocumentService.class);
    ArgumentCaptor<RetrieveContextParams> captor =
        ArgumentCaptor.forClass(RetrieveContextParams.class);
    when(documents.retrieveContext(captor.capture(), any(EngineContext.class)))
        .thenReturn(CompletableFuture.completedFuture(canned));
    WorkerServices workers = new WorkerServices(null, documents, null, null, null);
    HeadAssembly facade = mock(HeadAssembly.class);
    when(facade.workers()).thenReturn(workers);

    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> facade,
            FIXED_CLOCK);
    Map<String, Object> result = surface.callTool("justsearch_answer", args, "s1", TestRequestContexts.mcp("s1"));
    return new Invocation(result, captor.getValue());
  }

  private static Invocation invokeAnswer(ContextResult canned) {
    return invokeAnswer(canned, Map.of("query", "widget torque"));
  }

  private static String textOf(Map<String, Object> result) {
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
    return (String) content.get(0).get("text");
  }

  private static ContextResult fixtureResult(boolean truncated, List<ContextSection> sections) {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-1", 0, 2, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-1", 1, 2, 40, 80, 0.8f, "excerpt-2", 5, 8, "", 0, ContextInclusion.ABSENT),
            new ContextCitation(
                "doc-2", 0, 1, 0, 30, 0.7f, "excerpt-3", 1, 3, "", 0, ContextInclusion.ABSENT));
    return new ContextResult(
        "[From: doc-1]\nexcerpt-1\n\n---\n\n[From: doc-2]\nexcerpt-3",
        3,
        3,
        0,
        citations,
        "HYBRID",
        "HYBRID_AVAILABLE",
        truncated,
        sections,
        new QualitySignals(0.9f, 0.1f, 0.5f, 5, 3));
  }

  // ---------------------------------------------------------------------
  // W2a: evidence-pack header
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(a) header states passage/document counts, retrieval mode, and the no-answer fact")
  void headerStatesCountsModeAndNoAnswerFact() {
    String text = textOf(invokeAnswer(fixtureResult(false, List.of())).result());

    assertTrue(
        text.startsWith(
            "Evidence pack: 3 passages from 2 documents (retrieval mode: HYBRID). No synthesized"
                + " answer is included."),
        text);
    assertFalse(text.contains("Context was truncated to fit limits."), text);
  }

  @Test
  @DisplayName("(a) header appends the truncation fact only when contextTruncated is true")
  void headerAppendsTruncationFactWhenTruncated() {
    String text = textOf(invokeAnswer(fixtureResult(true, List.of())).result());

    assertTrue(
        text.contains(
            "(retrieval mode: HYBRID). No synthesized answer is included. Context was truncated"
                + " to fit limits."),
        text);
  }

  @Test
  @DisplayName(
      "(a) header appends the pack-selection facts sentence when quality signals are populated"
          + " (tempdoc 731 I6a)")
  void headerAppendsPackSelectionFactsWhenQualityPresent() {
    String text = textOf(invokeAnswer(fixtureResult(false, List.of())).result());

    // fixtureResult's QualitySignals(0.9f, 0.1f, 0.5f, 5, 3): chunksIncluded=3,
    // chunksConsidered=5, retrievalCoverage=0.5f → "0.50" at 2 decimals.
    assertTrue(
        text.contains(
            "Pack selection: 3 of 5 candidate passages (retrieval coverage 0.50)."),
        text);
  }

  @Test
  @DisplayName(
      "(a) header omits the pack-selection facts sentence when quality signals are absent"
          + " (tempdoc 731 I6a — never render a 0-of-0 placeholder)")
  void headerOmitsPackSelectionFactsWhenQualityAbsent() {
    // fulltextFallbackFixtureResult uses the 9-arg ContextResult constructor, which defaults
    // quality to QualitySignals.EMPTY — the FULLTEXT_FALLBACK path never computes real signals.
    String text = textOf(invokeAnswer(fulltextFallbackFixtureResult()).result());

    assertFalse(text.contains("Pack selection:"), text);
  }

  /**
   * Mirrors the REAL shape {@code RemoteDocumentService.retrieveContextFallback} (gRPC-failure
   * catch, FULLTEXT_FALLBACK path) actually returns: empty citations (a chunk-RAG-only concept
   * the full-document fallback never populates), a non-blank budgeter-built context with two
   * {@code [From: ...]} sections, populated {@code sections()}/{@code docsUsed()}, and
   * retrievalMode "FULLTEXT_FALLBACK" — the 9-arg {@code ContextResult} constructor this call site
   * uses (quality defaults to {@code QualitySignals.EMPTY}).
   */
  private static ContextResult fulltextFallbackFixtureResult() {
    String context = "[From: doc-a.txt]\ncontent-a\n\n---\n\n[From: doc-b.txt]\ncontent-b";
    List<ContextSection> sections =
        List.of(
            new ContextSection("doc-a.txt", "content-a", false, 0, 0),
            new ContextSection("doc-b.txt", "content-b", false, 1, 1));
    return new ContextResult(
        context, 0, 0, 2, List.of(), "FULLTEXT_FALLBACK", "GRPC_FAILED", false, sections);
  }

  @Test
  @DisplayName(
      "(a) header derives passage/document counts from sections in the FULLTEXT_FALLBACK path"
          + " (citations empty, tempdoc 725 review fix)")
  void headerDerivesCountsFromSectionsWhenCitationsEmptyInFallback() {
    String text = textOf(invokeAnswer(fulltextFallbackFixtureResult()).result());

    assertTrue(
        text.startsWith(
            "Evidence pack: 2 passages from 2 documents (retrieval mode: FULLTEXT_FALLBACK). No"
                + " synthesized answer is included."),
        text);
    assertFalse(text.contains("Evidence pack: 0 passages from 0 documents"), text);
  }

  // ---------------------------------------------------------------------
  // W2b: the call site must request the format the Worker actually renders
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(b) callAnswer requests ContextFormat.LABELED (the format ContextBudgeter renders)")
  void answerRequestsLabeledContextFormat() {
    Invocation invocation = invokeAnswer(fixtureResult(false, List.of()));

    assertEquals(
        RetrieveContextParams.ContextFormat.LABELED,
        invocation.capturedParams().contextFormat(),
        "the MCP call site must request the format the Worker's ContextBudgeter actually renders"
            + " — requesting XML here regressed to a dead orphan request (tempdoc 725 orphan #5)");
  }

  // ---------------------------------------------------------------------
  // W2c: response_format concise/detailed
  // ---------------------------------------------------------------------

  @Test
  @DisplayName("(c) detailed (default) renders the full assembled context verbatim")
  void detailedRendersFullContextVerbatim() {
    ContextResult canned = fixtureResult(false, List.of());
    String text = textOf(invokeAnswer(canned).result());

    assertTrue(text.contains(canned.context()), text);
  }

  @Test
  @DisplayName("(c) concise caps passages at 3 highest-rank sections, trimmed to ~600 chars each")
  void conciseCapsAndTrimsPassages() {
    String longContent = "x".repeat(700);
    List<ContextSection> fourSections =
        List.of(
            new ContextSection("doc-1", longContent, false, 0, 0),
            new ContextSection("doc-2", longContent, false, 1, 1),
            new ContextSection("doc-3", longContent, false, 2, 2),
            new ContextSection("doc-4", "should not appear", false, 3, 3));
    ContextResult canned = fixtureResult(false, fourSections);

    String text =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "concise")).result());

    // Tempdoc 822 §3a: concise re-renders sections detailed mode passes through verbatim, so it
    // carries the same "[n] label" header — a section's ordinal must not depend on the density.
    assertTrue(text.contains("[1] doc-1"), text);
    assertTrue(text.contains("[2] doc-2"), text);
    assertTrue(text.contains("[3] doc-3"), text);
    assertFalse(text.contains("doc-4"), text);
    assertFalse(text.contains("should not appear"), text);
    assertTrue(text.contains(McpSearchResultFormatter.TRUNCATION_REMEDY), text);
    // Each trimmed passage is <= 600 chars of source content, so the run of "x" characters never
    // reaches the untrimmed 700-char length.
    assertFalse(text.contains("x".repeat(700)), text);
  }

  @Test
  @DisplayName("(c) response_format is optional and defaults to detailed")
  void responseFormatDefaultsToDetailed() {
    ContextResult canned = fixtureResult(false, List.of());
    String withoutArg = textOf(invokeAnswer(canned, Map.of("query", "q")).result());
    String withDetailed =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "detailed")).result());

    assertEquals(withDetailed, withoutArg);
  }

  // ---------------------------------------------------------------------
  // Tempdoc 732 issue 7: raw-echo sites strip control chars but must NOT reuse the
  // quote/backslash-stripping sanitize() -- answer context/passage text is not quote-delimited,
  // so a literal quote or backslash (routine in legal-text corpora) must survive verbatim.
  // Control char and backslash characters below are built via char-code concatenation, not
  // escape literals, to keep the corpus-fidelity fixtures unambiguous.
  // ---------------------------------------------------------------------

  private static ContextResult customContextFixture(String context, List<ContextSection> sections) {
    List<ContextCitation> citations =
        List.of(
            new ContextCitation(
                "doc-1", 0, 1, 0, 40, 0.9f, "excerpt-1", 1, 4, "", 0, ContextInclusion.ABSENT));
    return new ContextResult(
        context,
        1,
        1,
        0,
        citations,
        "HYBRID",
        "HYBRID_AVAILABLE",
        false,
        sections,
        new QualitySignals(0.9f, 0.1f, 0.5f, 5, 3));
  }

  @Test
  @DisplayName(
      "(d) detailed mode strips a control char from result.context() but preserves quotes and"
          + " backslashes")
  void detailedContextStripsControlCharAndPreservesQuotesBackslashes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String cleanContext =
        "[From: doc-1]\nThe court cited \"Roe v. Wade\" at C:" + backslash + "legal" + backslash
            + "path.";
    String rawContext =
        "[From: doc-1]\nThe court cited \"Roe v. Wade\"" + controlChar + " at C:" + backslash
            + "legal" + backslash + "path.";
    ContextResult canned = customContextFixture(rawContext, List.of());

    String text = textOf(invokeAnswer(canned).result());

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    assertTrue(text.contains(cleanContext), text);
  }

  @Test
  @DisplayName(
      "(d) concise mode strips a control char from per-section passage text but preserves"
          + " quotes and backslashes")
  void concisePerSectionTextStripsControlCharAndPreservesQuotesBackslashes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String cleanSection =
        "The court cited \"Roe v. Wade\" at C:" + backslash + "legal" + backslash + "path.";
    String rawSection =
        "The court cited \"Roe v. Wade\"" + controlChar + " at C:" + backslash + "legal"
            + backslash + "path.";
    List<ContextSection> sections = List.of(new ContextSection("doc-1", rawSection, false, 0, 0));
    ContextResult canned = customContextFixture("[From: doc-1]\n" + rawSection, sections);

    String text =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "concise")).result());

    assertFalse(text.contains(String.valueOf(controlChar)), text);
    assertTrue(text.contains(cleanSection), text);
  }

  // ---------------------------------------------------------------------
  // Refute-first review of the tempdoc 732 delta: buildConciseAnswerText's empty-sections
  // fallback (result.sections().isEmpty() -> windowStartingAt(result.context(), 0, 600)) was still
  // unpinned for stripControlChars -- distinct from the per-section branch pinned above.
  // ---------------------------------------------------------------------

  @Test
  @DisplayName(
      "(e) concise mode with empty sections strips a control char from the raw-context window"
          + " fallback and preserves quotes/backslashes")
  void conciseEmptySectionsWindowStripsControlCharAndPreservesQuotesBackslashes() {
    char controlChar = (char) 7;
    char backslash = (char) 92;
    String cleanHead =
        "The court cited \"Roe v. Wade\" at C:" + backslash + "legal" + backslash + "path. ";
    String rawHead =
        "The court cited \"Roe v. Wade\"" + controlChar + " at C:" + backslash + "legal"
            + backslash + "path. ";
    String rawContext = rawHead + "z".repeat(700); // > 600 chars: forces windowStartingAt to cut
    ContextResult canned =
        new ContextResult(
            rawContext, 0, 0, 0, List.of(), "FULLTEXT_FALLBACK", "GRPC_FAILED", false, List.of());

    String text =
        textOf(invokeAnswer(canned, Map.of("query", "q", "response_format", "concise")).result());

    // Branch pin: citations AND sections both empty drives the "sections absent too" defensive
    // header fallback (McpToolSurface.callAnswer, tempdoc 725 review-fix comment) --
    // distinctDocs floors to max(docsUsed, 1) = 1, so the header reads "1 passages from 1
    // documents" even though nothing was retrieved. This corroborates result.sections() is empty,
    // the precondition for the buildConciseAnswerText branch under test.
    assertTrue(
        text.startsWith(
            "Evidence pack: 1 passages from 1 documents (retrieval mode: FULLTEXT_FALLBACK)."),
        text);
    assertFalse(text.contains(String.valueOf(controlChar)), text);
    assertFalse(text.contains("[From:"), text);
    String expectedWindow =
        cleanHead
            + "z".repeat(600 - rawHead.length())
            + McpSearchResultFormatter.TRUNCATION_REMEDY;
    assertTrue(text.contains(expectedWindow), text);
  }
}
