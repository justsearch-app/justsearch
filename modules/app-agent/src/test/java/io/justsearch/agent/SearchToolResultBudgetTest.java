/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.SearchTool;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 877 §2.2 — the assertion that would have caught the tail-death, and the assertion that
 * would have caught the HEAD-death the first fix for it introduced.
 *
 * <p>{@code SearchTool.formatResults} used to divide the Layer-2 cap by {@code hits.size()} and then
 * charge each hit only {@code excerpt.length()}, counting neither the {@code [n] title} header, the
 * {@code Path:} line, the carrier line's framing, nor the trailing summary. At {@code limit:20} the
 * emitted string therefore ran past the cap and {@code AgentContextCompressor.truncate} cut the
 * tail — the lower-ranked hits AND the "Found N results" line — off the message the model saw.
 *
 * <p>The first budget written against that only measured LENGTH, and length is satisfied just as
 * well by dropping hits as by shortening them: a hit whose identity block alone overran its slice
 * emitted nothing, forfeited nothing, and handed its budget to the hits BELOW it — so the top of the
 * ranking disappeared while the bottom rendered, invisibly. Hence {@link #hitsAreEmittedFromTheTop}
 * and the marker-counting helper: a budget test that never counts hits cannot tell "fits" from
 * "fits because half of it is gone".
 *
 * <p>Lives in {@code io.justsearch.agent} rather than beside the tool because the load-bearing half
 * of the check is running the REAL package-private {@code truncate} over the output: a length
 * assertion against a copy of the constant would pass while Layer 2 still fired.
 */
final class SearchToolResultBudgetTest {

  /** Long enough that an uncharged header/framing overshoot is certain, not marginal. */
  private static final String LONG_EXCERPT = "lorem ipsum dolor sit amet ".repeat(40);

  private static final String LONG_PATH =
      "/very/deeply/nested/knowledge/base/directory/tree/segment/for/budget/pressure/doc-";

  /** A result header, as {@code formatResults} writes it: {@code [n] title (score: x.xx)}. */
  private static final Pattern HIT_MARKER = Pattern.compile("(?m)^\\[(\\d+)] .* \\(score: ");

  /** The explicit statement that the budget could not carry every hit. */
  private static final Pattern OMITTED_NOTICE =
      Pattern.compile("(?m)^\\.\\.\\. (\\d+) further results omitted \\(context budget\\)$");

  @Test
  @DisplayName("a 20-hit response fits the Layer-2 cap by construction, so truncate never fires")
  void twentyHitsFitTheLayerTwoCap() {
    KnowledgeSearchResponse response = responseWithLongHits(20);
    SearchTool tool = new SearchTool((req, context) -> response);

    OperationResult result = tool.execute("{\"query\":\"budget\",\"limit\":20}", EngineContextTestFixtures.AGENT_LOOP);
    String message = result.message();

    assertTrue(result.success(), message);
    assertTrue(
        message.length() <= ToolResultCarrier.layerTwoCapChars(AgentContextBudgets.forCall(null)),
        "formatted result is "
            + message.length()
            + " chars, over the Layer-2 cap of "
            + ToolResultCarrier.layerTwoCapChars(AgentContextBudgets.forCall(null)));
    assertEquals(
        message,
        new AgentContextCompressor(false, 200, 1).truncate(message),
        "Layer 2 must not fire on a result the producer already budgeted");
    assertTrue(
        message.contains("Found 20 results"),
        "the trailing summary is reserved out of the budget, so it can never be the part cut: "
            + message);
    assertRankingIsWholeAndTopAnchored(message, 20);
  }

  @Test
  @DisplayName(
      "long titles + paths: hits are dropped from the TAIL and stated, never from the head")
  void hitsAreEmittedFromTheTop() {
    // A realistic worst case rather than the marginal one: an 80-char document title and a 90-char
    // OneDrive-style path. Their identity block alone is ~200 chars — larger than the ~198-char
    // slice an even 20-way split of the default 4000-char cap hands hit 0 — which is exactly the
    // shape that made the OLD budget emit nothing for the top hits and then, budget unspent, render
    // the bottom ones. The result was a gap in the [n] numbering under a summary claiming all 20.
    KnowledgeSearchResponse response = responseWithRealisticallyLongIdentities(20);
    SearchTool tool = new SearchTool((req, context) -> response);

    OperationResult result = tool.execute("{\"query\":\"budget\",\"limit\":20}", EngineContextTestFixtures.AGENT_LOOP);
    String message = result.message();

    assertTrue(result.success(), message);
    assertTrue(
        message.length() <= ToolResultCarrier.layerTwoCapChars(AgentContextBudgets.forCall(null)),
        "formatted result is "
            + message.length()
            + " chars, over the Layer-2 cap of "
            + ToolResultCarrier.layerTwoCapChars(AgentContextBudgets.forCall(null)));
    assertEquals(
        message,
        new AgentContextCompressor(false, 200, 1).truncate(message),
        "Layer 2 must not fire on a result the producer already budgeted");
    assertTrue(
        message.contains("[1] "),
        "the TOP-ranked hit is the one hit that must never be the casualty of the budget: "
            + message);
    assertRankingIsWholeAndTopAnchored(message, 20);

    // Every emitted hit keeps its identity: a header without its Path: line would be a hit the model
    // cannot act on, which is the other way "it fits" can be true and useless.
    List<Integer> emitted = markers(message);
    for (int rank : emitted) {
      assertTrue(
          message.contains("Path: " + LONG_ONEDRIVE_PATH + rank + ".pdf"),
          "hit [" + rank + "] was emitted without its Path: line: " + message);
    }
  }

  /**
   * The invariant the length check cannot see: the emitted {@code [n]} markers are {@code 1..k}
   * with no gaps, and the {@code total - k} that did not fit are STATED. A head-cut breaks the
   * first half of that; a silent drop breaks the second.
   */
  private static void assertRankingIsWholeAndTopAnchored(String message, int total) {
    List<Integer> emitted = markers(message);
    assertTrue(!emitted.isEmpty(), "no result was rendered at all: " + message);
    for (int i = 0; i < emitted.size(); i++) {
      assertEquals(
          i + 1,
          emitted.get(i).intValue(),
          "the rendered ranking must be the top "
              + emitted.size()
              + " hits in order, with no gap — got markers "
              + emitted
              + " in: "
              + message);
    }
    Matcher notice = OMITTED_NOTICE.matcher(message);
    int announced = notice.find() ? Integer.parseInt(notice.group(1)) : 0;
    assertEquals(
        total,
        emitted.size() + announced,
        "every hit is either rendered or accounted for by the omission notice; "
            + emitted.size()
            + " rendered + "
            + announced
            + " announced != "
            + total
            + " in: "
            + message);
  }

  private static List<Integer> markers(String message) {
    var found = new ArrayList<Integer>();
    Matcher m = HIT_MARKER.matcher(message);
    while (m.find()) {
      found.add(Integer.valueOf(m.group(1)));
    }
    return found;
  }

  private static KnowledgeSearchResponse responseWithLongHits(int count) {
    List<KnowledgeSearchResponse.Hit> hits =
        IntStream.rangeClosed(1, count)
            .mapToObj(
                i ->
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("doc-" + i)
                        .score(1.0 - (i * 0.01))
                        .fields(
                            Map.of(
                                "title",
                                "A fairly long document title for hit number " + i,
                                "path",
                                LONG_PATH + i + ".pdf"))
                        .matchedFields(List.of("content"))
                        .excerptRegions(
                            List.of(
                                new KnowledgeSearchResponse.ExcerptRegion(
                                    LONG_EXCERPT, 0, 30, 1, List.of()),
                                new KnowledgeSearchResponse.ExcerptRegion(
                                    LONG_EXCERPT, 40, 70, 2, List.of())))
                        .build())
            .toList();
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(count)
        .tookMs(12)
        .results(hits)
        .build();
  }

  /** 80 characters before the rank suffix — a plausible report title, not a padded test string. */
  private static final String LONG_TITLE =
      "Quarterly Revenue Reconciliation and Segment Margin Analysis, Consolidated View ";

  /** 90 characters before the rank suffix — the shape a synced OneDrive tree actually produces. */
  private static final String LONG_ONEDRIVE_PATH =
      "C:/Users/j.doe/OneDrive - Contoso Holdings GmbH/Finance/Reports/2026/Q3/audit/final/recon-";

  private static KnowledgeSearchResponse responseWithRealisticallyLongIdentities(int count) {
    List<KnowledgeSearchResponse.Hit> hits =
        IntStream.rangeClosed(1, count)
            .mapToObj(
                i ->
                    KnowledgeSearchResponseHitBuilder.builder()
                        .id("doc-" + i)
                        .score(1.0 - (i * 0.01))
                        .fields(
                            Map.of(
                                "title", LONG_TITLE + i,
                                "path", LONG_ONEDRIVE_PATH + i + ".pdf"))
                        .matchedFields(List.of("content"))
                        .excerptRegions(
                            List.of(
                                new KnowledgeSearchResponse.ExcerptRegion(
                                    LONG_EXCERPT, 0, 30, 1, List.of())))
                        .build())
            .toList();
    return KnowledgeSearchResponseBuilder.builder()
        .totalHits(count)
        .tookMs(12)
        .results(hits)
        .build();
  }
}
