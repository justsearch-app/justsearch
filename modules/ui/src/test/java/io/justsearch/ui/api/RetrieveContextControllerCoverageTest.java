/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;
import io.justsearch.core.context.EngineContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.http.Context;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.OnlineAiService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 836 S2S3-A.1 — {@code POST /api/knowledge/match-citations} publishes the TEXT-coverage
 * axis beside the sentence axis it already published.
 *
 * <p>This endpoint is the seam's live instrument (836 §9.7, §10): it calls the same
 * {@code documents.matchCitations} the ask and summarize matchers call. It reported
 * {@code sentences_scored} from S1, so without this it would be the one surface saying "every
 * sentence was scored" about a pass that read a fraction of the caller's text — the exact
 * misreport §10.7 gap 1 records, on the surface used to measure the fix.
 */
@DisplayName("RetrieveContextController — match-citations publishes per-source coverage")
final class RetrieveContextControllerCoverageTest {

  /** A result carrying the §10.7 gap-2 shape: one examined source, one starved. */
  private static final class StubDocs implements DocumentService {
    @Override
    public CompletionStage<DocumentRecord> fetch(String docId, EngineContext engineContext) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<CitationMatchResult> matchCitationsAgainst(
        String answerText, List<VerificationSource> sources, double threshold, EngineContext engineContext) {
      return CompletableFuture.completedFuture(
          new CitationMatchResult(
              List.of(
                  new CitationMatchEntry(0, "A sentence.", 0, 0.91, "a.md", TextSource.SUPPLIED)),
              4,
              1,
              12L,
              3,
              ScorerKind.CROSS_ENCODER,
              List.of(new SourceCoverage(0, 16, 5), new SourceCoverage(1, 9, 0))));
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> invoke() {
    OnlineAiService ai = mock(OnlineAiService.class);
    when(ai.isAvailable()).thenReturn(false);
    var controller = new RetrieveContextController(null, StubDocs::new, ai, () -> "");

    AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
    Context ctx = mock(Context.class);
    when(ctx.path()).thenReturn("/api/knowledge/retrieve-context");
    when(ctx.bodyAsClass(Map.class))
        .thenReturn(
            Map.of(
                "answer_text",
                "A sentence.",
                "chunk_refs",
                List.of(
                    Map.of("parent_doc_id", "a.md", "chunk_index", 0),
                    Map.of("parent_doc_id", "b.md", "chunk_index", 0))));
    when(ctx.status(org.mockito.ArgumentMatchers.anyInt())).thenReturn(ctx);
    when(ctx.json(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            inv -> {
              captured.set((Map<String, Object>) inv.getArgument(0));
              return ctx;
            });

    controller.handleMatchCitations(ctx);
    assertNotNull(captured.get(), "the controller must have produced a response");
    return captured.get();
  }

  @Test
  @DisplayName("per-source coverage rides the response, one entry per source")
  @SuppressWarnings("unchecked")
  void publishesPerSourceCoverage() {
    var response = invoke();

    var coverage = (List<Map<String, Object>>) response.get("source_coverage");
    assertNotNull(coverage, "source_coverage must be present");
    assertEquals(2, coverage.size());
    assertEquals(0, coverage.get(0).get("source_index"));
    assertEquals(16, coverage.get(0).get("windows_considered"));
    assertEquals(5, coverage.get(0).get("windows_scored"));
    assertEquals(9, coverage.get(1).get("windows_considered"));
    assertEquals(0, coverage.get(1).get("windows_scored"));
  }

  @Test
  @DisplayName("the derived reads travel too — complete-ness and the starved list")
  void publishesDerivedReads() {
    var response = invoke();

    assertEquals(
        false,
        response.get("text_coverage_complete"),
        "5 of 16 windows scored is not a complete examination");
    assertEquals(
        List.of(1),
        response.get("starved_sources"),
        "source 1 had text and got no window — never examined, not unsupported");
  }

  @Test
  @DisplayName("the sentence axis is unchanged beside it — two axes, never blended")
  void sentenceAxisUnchanged() {
    var response = invoke();

    assertEquals(4, response.get("sentences_total"));
    assertEquals(3, response.get("sentences_scored"));
    assertEquals(true, response.get("scoring_incomplete"));
    assertEquals("CROSS_ENCODER", response.get("scorer"));
    assertTrue(response.containsKey("matches"));
  }
}
