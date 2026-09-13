package io.justsearch.agent;

import io.justsearch.core.context.EngineContext;
import io.justsearch.agent.EngineContextTestFixtures;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.AgentEvent;
import io.justsearch.agent.api.ToolCallRequest;
import io.justsearch.agent.api.registry.OperationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 865 §7.5 — the retrieved-vs-received JOIN on the delegate plane: source → the tool call
 * that carried it → whether that call's message still holds its excerpts in the prompt.
 *
 * <p>These drive the REAL {@link AgentContextCompressor} over a real message list, in the same order
 * {@code AgentStepRunner} does it (record the execution, append the tool message, compress, record
 * the receipt). A mocked compressor would test the join against a fixture of the very behaviour the
 * join is about — the receipt's whole claim is that the compressor's own output is the fact source.
 */
final class AgentGroundingInclusionTest {

  /** The real defaults (`ResolvedConfigBuilder`: enabled, minChars 200, keepLastResults 1). */
  private static AgentContextCompressor compressor() {
    return new AgentContextCompressor(true, 200, 1);
  }

  private static AgentSession session() {
    return new AgentSession(new ArrayList<>(List.of(Map.of("role", "user", "content", "q"))), 8000, EngineContextTestFixtures.AGENT_LOOP);
  }

  private static ToolCallRequest searchCall(String id) {
    return new ToolCallRequest(id, "core_search_index", "{\"query\":\"x\"}");
  }

  private static Map<String, Object> chunkHit(String parentDocId) {
    return Map.of(
        "parentDocId", parentDocId,
        "chunkIndex", 0,
        "path", "/docs/" + parentDocId + ".md",
        "title", "Doc " + parentDocId,
        "excerpt", "an excerpt",
        "startLine", 1,
        "endLine", 5,
        "headingText", "");
  }

  /**
   * A search tool message shaped like {@code SearchTool.formatResults}' output: long enough to clear
   * the compressor's {@code minChars} floor, and carrying the indented {@code Excerpt:} lines that
   * are the thing Layer 3 removes.
   */
  private static String searchMessage(String... docIds) {
    return toolMessage(ToolResultCarrier::excerptLine, docIds);
  }

  /**
   * The dense-only shape: a hit with no excerpt regions is written as a {@code Preview:} line
   * instead ({@code SearchTool.formatResults}' else-branch), and Layer 3 never strips those.
   */
  private static String previewMessage(String... docIds) {
    return toolMessage(ToolResultCarrier::previewLine, docIds);
  }

  private static String toolMessage(
      java.util.function.UnaryOperator<String> carrierLine, String... docIds) {
    var sb = new StringBuilder("Found ").append(docIds.length).append(" result(s)\n");
    for (String id : docIds) {
      sb.append("  /docs/").append(id).append(".md\n");
      sb.append("    Lines 1-5\n");
      // Written through the PRODUCER's own formatter, never a hand-copied literal — the coupling
      // this shares with the compressor's reader is the whole point of ToolResultCarrier.
      sb.append(
          carrierLine.apply(
              "the passage text for "
                  + id
                  + ", padded so this message clears the compressor's minimum-length floor and is"
                  + " therefore a message compression will actually rewrite rather than skip"));
    }
    return sb.toString();
  }

  /** One dispatch, exactly as {@code AgentStepRunner.executeIteration} sequences it. */
  private static List<AgentEvent.AgentSource> dispatch(
      AgentSession session, AgentContextCompressor compressor, String callId, String... docIds) {
    return dispatchWithMessage(session, compressor, callId, searchMessage(docIds), docIds);
  }

  private static List<AgentEvent.AgentSource> dispatchWithMessage(
      AgentSession session,
      AgentContextCompressor compressor,
      String callId,
      String toolText,
      String... docIds) {
    var hits = new ArrayList<Map<String, Object>>();
    for (String id : docIds) {
      hits.add(chunkHit(id));
    }
    var result = OperationResult.success(toolText, Map.of("searchResults", List.copyOf(hits)));
    List<AgentEvent.AgentSource> delta = session.recordExecution(searchCall(callId), result);
    var message = new LinkedHashMap<String, Object>();
    message.put("role", "tool");
    message.put("tool_call_id", callId);
    message.put("content", compressor.truncate(result.message()));
    session.appendMessage(message);
    session.recordCompression(compressor.compressToolMessages(session.messages()));
    return delta;
  }

  private static AgentEvent.AgentSource sourceOf(
      List<AgentEvent.AgentSource> sources, String parentDocId) {
    return sources.stream()
        .filter(s -> s.parentDocId().equals(parentDocId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no source for " + parentDocId + " in " + sources));
  }

  @Test
  @DisplayName("865 §7.5 THE JOIN: a source whose carrier message lost its excerpts is dropped; the current call's is not")
  void strippedCarrier_yieldsDroppedSource_intactCarrierDoesNot() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");
    dispatch(session, compressor, "call-3", "d3");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(3, sources.size(), "all three searches established a source");

    // The compressor keeps the last result and rewrites the older ones, so the run's own message
    // list is the evidence: d1's and d2's carriers no longer hold an `Excerpt:` line, d3's does.
    assertEquals(
        "dropped",
        sourceOf(sources, "d1").contextInclusion(),
        "d1's carrier was compressed away, so its passage is not in the prompt the answer is"
            + " written from — the state 849's `suppressGroundingFor` acts on");
    assertEquals("dropped", sourceOf(sources, "d2").contextInclusion());
    assertEquals(0, sourceOf(sources, "d1").contextIncludedChars(), "dropped means zero chars");
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d3").contextInclusion(),
        "d3's carrier is intact, and the producer then says NOTHING rather than `included`:"
            + " Layers 1-2 (SearchTool's per-result budget, truncate's hard cut) are upstream cuts"
            + " this producer cannot witness, so `included` would be a claim it has no standing to"
            + " make. Only `dropped` is monotone across the three layers.");
    assertEquals(
        AgentEvent.AgentSource.INCLUDED_CHARS_UNKNOWN,
        sourceOf(sources, "d3").contextIncludedChars());
  }

  /**
   * THE RED-BEFORE GUARD. Removing the receipt — the one line {@code AgentStepRunner} adds at each
   * compression site — is the way this slice most plausibly regresses: the join keeps compiling, the
   * mint keeps working, and every source silently returns to absent. Without this test the
   * regression is invisible, because "no badge" is also what the plane did before 865.
   */
  @Test
  @DisplayName("865 §7.5: with no receipt recorded, every source stays ABSENT — the producer is the receipt, not the compression")
  void withoutTheReceipt_nothingIsClaimed() {
    var session = session();
    var compressor = compressor();

    // Same three dispatches, same real compression — but the receipt is never handed to the session.
    for (String callId : List.of("call-1", "call-2", "call-3")) {
      var result =
          OperationResult.success(
              searchMessage(callId), Map.of("searchResults", List.of(chunkHit(callId))));
      session.recordExecution(searchCall(callId), result);
      var message = new LinkedHashMap<String, Object>();
      message.put("role", "tool");
      message.put("tool_call_id", callId);
      message.put("content", result.message());
      session.appendMessage(message);
      compressor.compressToolMessages(session.messages());
    }

    for (AgentEvent.AgentSource s : session.collectGroundingSources()) {
      assertEquals(
          AgentEvent.AgentSource.INCLUSION_ABSENT,
          s.contextInclusion(),
          "a producer that was told nothing says nothing — never `dropped` inferred from the"
              + " compressor's behaviour without the compressor's own report");
    }
  }

  /**
   * PER-FINAL-PROMPT, NOT CUMULATIVE. The plane degrades continuously, so "was stripped once" is not
   * the question — "is it in the prompt the answer was written from" is. A source re-delivered by a
   * later search has a live carrier again, and the receipt is a picture of ONE prompt, so it must
   * stop being described as dropped.
   */
  @Test
  @DisplayName("865 §7.5: a source stripped at an early iteration but re-returned by a later search is no longer dropped")
  void reEstablishedSource_losesTheDroppedState() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");
    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "precondition: d1's only carrier has been compressed");

    // The run-wide dedup means this call establishes NOTHING new — and that is exactly the case a
    // first-carrier-only model gets wrong: no source is minted, but d1's text is in the prompt again.
    List<AgentEvent.AgentSource> delta = dispatch(session, compressor, "call-3", "d1");
    assertTrue(delta.isEmpty(), "the dedup is unchanged: a repeat hit establishes no new source");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(2, sources.size(), "and adds no source to the terminal list either");
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d1").contextInclusion(),
        "d1 has an intact carrier again, so the run may no longer say its passage was never sent");
    assertEquals(
        "dropped",
        sourceOf(sources, "d2").contextInclusion(),
        "and d2, whose only carrier is still compressed, is unaffected — the state is per source,"
            + " not per run");
  }

  @Test
  @DisplayName("865 §7.5: the per-call delta carries NO inclusion — a tool call has no final prompt to be a fact about")
  void theDeltaIsMintedAbsent() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    List<AgentEvent.AgentSource> delta = dispatch(session, compressor, "call-2", "d2");

    assertEquals(1, delta.size());
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        delta.get(0).contextInclusion(),
        "the delta is the identity a call established; inclusion is resolved only where the prompt"
            + " exists (`collectGroundingSources`, at the two grounded terminals)");

    OperationResult stamped = OperationResult.success("r").withGrounding(delta);
    Map<?, ?> wire = (Map<?, ?>) ((List<?>) stamped.structuredData().get("grounding")).get(0);
    assertFalse(
        wire.containsKey("contextInclusion"),
        "and the stamped wire shape stays the eight identity fields — PR-1's delta contract is"
            + " untouched by this slice");
  }

  @Test
  @DisplayName("865 §7.5: the receipt's THIRD outcome — a message it cannot classify is named in neither set")
  void theReceiptHasThreeOutcomesNotTwo() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call-1", "d1");
    // A tool message carrying no hit text at all — a browse/ingest result. Short enough that
    // compression leaves it alone, so nothing is ever taken from it.
    var plain = new LinkedHashMap<String, Object>();
    plain.put("role", "tool");
    plain.put("tool_call_id", "call-plain");
    plain.put("content", "Created 1 folder.");
    session.appendMessage(plain);
    dispatch(session, compressor, "call-2", "d2");

    AgentContextCompressor.CompressionReceipt receipt =
        compressor.compressToolMessages(session.messages());

    assertTrue(receipt.observed(), "tool messages were classified");
    assertTrue(receipt.textIntact().contains("call-2"), "the kept result still carries its text");
    assertFalse(
        receipt.textIntact().contains("call-plain"),
        "a message with no carrier line does not hold hit text");
    assertFalse(
        receipt.textRemoved().contains("call-plain"),
        "but neither may it be reported as REMOVED — nothing was taken from it, and the two-outcome"
            + " reading of this is exactly what made a dense-only Preview: message read as dropped");
    // call-1 is also in neither set HERE — this pass rewrote nothing, and the strip that emptied it
    // left no marker. That is precisely why the removal fact lives on the session, folded from the
    // pass that witnessed it, rather than being re-derived from the artifact every time.
    assertFalse(receipt.textIntact().contains("call-1"));
    assertFalse(receipt.textRemoved().contains("call-1"));
    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "and the session still knows, because it folded the receipt that did witness it");
  }

  @Test
  @DisplayName("865 §7.5: a message whose text was removed by an EARLIER pass stays removed")
  void textRemovedByAnEarlierPassStaysRemoved() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call-1", "d1");
    dispatch(session, compressor, "call-2", "d2");

    // Two further passes that rewrite nothing: `compressToolOutput` refuses to re-compress its own
    // output, and the strip is idempotent. A receipt built ONLY from "what did this pass rewrite"
    // would forget call-1 here; the session's accumulated view is what makes the fact durable.
    session.recordCompression(compressor.compressToolMessages(session.messages()));
    session.recordCompression(compressor.compressToolMessages(session.messages()));

    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "still dropped after passes that changed nothing");
  }

  /**
   * THE PREVIEW-ONLY CASE (review F1). A vector/dense-only hit has no excerpt regions, so {@code
   * SearchTool.formatResults} writes its text as a {@code Preview:} line — and Layer 3 never strips
   * those. A receipt that asked "does this message still have an {@code Excerpt:} line?" answered no
   * for such a message with ZERO compression having occurred, and the panel then rendered "Retrieved
   * · never sent to the model" over text sitting verbatim in the prompt.
   *
   * <p>The worst shape a producer can take: not silence, but a confident false claim, on the exact
   * question it was built to answer.
   */
  @Test
  @DisplayName("865 §7.5 F1: a dense-only Preview: hit that was never compressed is NOT dropped")
  void previewOnlyHit_isNotReportedDropped() {
    var session = session();
    var compressor = compressor();

    dispatchWithMessage(session, compressor, "call-1", previewMessage("d1"), "d1");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(1, sources.size());
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d1").contextInclusion(),
        "its Preview: line is untouched in the prompt, so the run may not say it was never sent");
  }

  @Test
  @DisplayName("865 §7.5 F1: a Preview: carrier is still reported dropped once compression rewrites it")
  void previewOnlyHit_isDroppedOnceItsMessageIsCompressed() {
    var session = session();
    var compressor = compressor();

    dispatchWithMessage(session, compressor, "call-1", previewMessage("d1"), "d1");
    // A second dispatch pushes call-1 out of the keep-window, and its preview text goes with it.
    dispatchWithMessage(session, compressor, "call-2", previewMessage("d2"), "d2");

    assertEquals(
        "dropped",
        sourceOf(session.collectGroundingSources(), "d1").contextInclusion(),
        "recognising Preview: as a carrier must not make the producer blind — a compressed preview"
            + " message has lost its text just as an excerpt one has");
  }

  /**
   * The ALL-quantifier (review F6). {@link AgentSession#inclusionFor} says {@code dropped} only when
   * EVERY carrier has lost its text; one intact carrier silences it. The complementary case —
   * several carriers, all stripped — is what proves the quantifier is an ALL and not a vacuous
   * "the first one".
   */
  @Test
  @DisplayName("865 §7.5: two carriers, both stripped ⇒ dropped (the ALL-quantifier, not the first)")
  void everyCarrierStripped_yieldsDropped() {
    var session = session();
    var compressor = compressor();

    dispatch(session, compressor, "call-1", "d1");
    // call-2 re-returns d1, giving it a SECOND carrier, and adds d2.
    dispatch(session, compressor, "call-2", "d1", "d2");
    // call-3 pushes both earlier messages out of the keep-window.
    dispatch(session, compressor, "call-3", "d3");

    List<AgentEvent.AgentSource> sources = session.collectGroundingSources();
    assertEquals(
        "dropped",
        sourceOf(sources, "d1").contextInclusion(),
        "d1 had two carriers and BOTH lost their text — nothing in the prompt holds it now");
    assertEquals(
        AgentEvent.AgentSource.INCLUSION_ABSENT,
        sourceOf(sources, "d3").contextInclusion(),
        "d3's only carrier is the kept one");
  }

  /**
   * THE DRIFT TEST (review F3). The producer and the reader of the carrier-line format now share one
   * symbol, and this drives the REAL {@code SearchTool} — its own response formatting, not a
   * hand-copied literal — into the REAL {@link AgentContextCompressor#receiptFor}.
   *
   * <p>It is structural rather than cosmetic because the failure mode is total: if the reader stops
   * matching what the writer emits, EVERY source of EVERY search reports dropped, which is a
   * confident false claim on every card rather than a missing badge.
   */
  @Test
  @DisplayName("865 §7.5 F3: the REAL SearchTool's formatting is what the receipt recognises")
  void theReceiptRecognisesTheRealProducersOutput() {
    var hit =
        io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder.builder()
            .id("doc-1")
            .score(0.9)
            .fields(Map.of("title", "Doc 1", "path", "/docs/doc-1.md"))
            .matchedFields(List.of("content"))
            .excerptRegions(
                List.of(
                    new io.justsearch.app.api.knowledge.KnowledgeSearchResponse.ExcerptRegion(
                        "a matching passage", 0, 18, 1, List.of())))
            .build();
    var denseOnlyHit =
        io.justsearch.app.api.knowledge.KnowledgeSearchResponseHitBuilder.builder()
            .id("doc-2")
            .score(0.8)
            .fields(
                Map.of(
                    "title", "Doc 2",
                    "path", "/docs/doc-2.md",
                    "content_preview", "the dense-only preview text"))
            .matchedFields(List.of("content"))
            .excerptRegions(List.of())
            .build();

    for (var hits : List.of(List.of(hit), List.of(denseOnlyHit))) {
      var response =
          io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder.builder()
              .totalHits(hits.size())
              .tookMs(4)
              .results(hits)
              .build();
      OperationResult result =
          new io.justsearch.agent.tools.SearchTool((req, context) -> response).execute("{\"query\":\"q\"}", EngineContextTestFixtures.AGENT_LOOP);
      assertTrue(result.success(), result.message());

      var message = new LinkedHashMap<String, Object>();
      message.put("role", "tool");
      message.put("tool_call_id", "call-real");
      message.put("content", result.message());

      AgentContextCompressor.CompressionReceipt receipt =
          AgentContextCompressor.receiptFor(List.of(message), java.util.Set.of());
      assertTrue(
          receipt.textIntact().contains("call-real"),
          "the reader must recognise the writer's own carrier line; it did not, for this output:\n"
              + result.message());
    }
  }

  @Test
  @DisplayName("865 §7.5 F3: the strip target stays narrower than the carrier reader")
  void theStripTargetIsNarrowerThanTheReader() {
    String excerpt = ToolResultCarrier.excerptLine("x");
    String preview = ToolResultCarrier.previewLine("x");

    assertTrue(ToolResultCarrier.carriesText(excerpt), "both spellings carry text");
    assertTrue(ToolResultCarrier.carriesText(preview));
    assertTrue(
        ToolResultCarrier.STRIPPABLE_LINE.matcher(excerpt).find(), "Layer 3 strips excerpt lines");
    assertFalse(
        ToolResultCarrier.STRIPPABLE_LINE.matcher(preview).find(),
        "and must NOT strip preview lines — widening it to match the reader would silently delete a"
            + " dense-only hit's whole text from the prompt");
  }

  /* ── Tempdoc 878 §D.2: the Layer-3 scar ────────────────────────────────────────────────────── */

  /**
   * The scar's two defining non-matches, asserted as the properties they are rather than through a
   * strip. Matching {@code CARRIER_LINE} would make the receipt report stripped text as still in the
   * prompt; matching {@code STRIPPABLE_LINE} would let the next pass strip the scar and the elision
   * would go silent one iteration later. Both are one-character mistakes away.
   */
  @Test
  @DisplayName("878 §D.2: the elision scar is neither a carrier line nor itself strippable")
  void theScarMatchesNeitherPattern() {
    String scar = ToolResultCarrier.elidedLine(3, 900);

    assertFalse(
        ToolResultCarrier.carriesText(scar),
        "a scar must not read as a carrier — the receipt would then report stripped text as intact,"
            + " the exact false claim ToolResultCarrier exists to prevent");
    assertFalse(
        ToolResultCarrier.STRIPPABLE_LINE.matcher(scar).find(),
        "and must not be strippable — a second pass would delete the mark and the elision would be"
            + " silent again");
    assertFalse(
        ToolResultCarrier.mayHaveStrippableLine(scar),
        "including via the cheap pre-check, which is what actually decides whether a second pass"
            + " runs the regex at all");
  }

  /**
   * The behaviour the scar exists for: a stripped READ result used to collapse to a bare
   * {@code [read] … More: offset_chars=N} header — an instruction to call the tool again, on a
   * message whose page had just been deleted, with nothing saying the deletion had happened.
   */
  @Test
  @DisplayName("878 §D.2: a stripped read result no longer presents a bare More: header with no trace of the removal")
  void strippingAReadPageLeavesAScarBesideTheMoreHeader() {
    String readResult =
        "[read] /docs/manual.md — chars 0–3000 of more; More: call core_read_document again with"
            + " offset_chars=3000\n"
            + ToolResultCarrier.readLine("the page text ".repeat(60));

    String stripped = AgentContextCompressor.stripSearchExcerpts(readResult);

    assertFalse(
        ToolResultCarrier.carriesText(stripped), "the page itself is gone — that is Layer 3's job");
    assertTrue(
        stripped.contains("More: call core_read_document again"),
        "the header survives, as it always has: the run keeps the fact of the read and the offset");
    assertTrue(
        stripped.contains("Elided:"),
        "RED BEFORE 878: the strip deleted the page and left the More: instruction standing alone,"
            + " so the only thing the model could read was an invitation to call the tool again");
    assertTrue(
        stripped.contains("will not return it"),
        "and the scar states the CONSEQUENCE, not only the quantity — the observed failure was a"
            + " behaviour, so a mark that merely recorded history would change nothing");
  }

  /** A second pass must find nothing to strip, and must not stack a second scar. */
  @Test
  @DisplayName("878 §D.2: the strip is idempotent — a second pass adds no second scar")
  void theStripIsIdempotent() {
    String once = AgentContextCompressor.stripSearchExcerpts(searchMessage("d1", "d2"));
    String twice = AgentContextCompressor.stripSearchExcerpts(once);

    assertEquals(once, twice, "a stripped message is a fixed point of the strip");
    assertEquals(
        1,
        once.split("Elided:", -1).length - 1,
        "exactly ONE scar per message, whatever the number of lines removed — a scar per line would"
            + " cost a ten-hit result more tokens than the strip saved");
  }

  /**
   * The scar must not change what the RECEIPT says. It is addressed to the model; the reader's
   * answer about this call is unchanged, and a scar that flipped it would trade one false claim for
   * another.
   */
  @Test
  @DisplayName("878 §D.2: the scar leaves the inclusion receipt's verdict untouched")
  void theScarDoesNotDisturbTheReceipt() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call_1", "d1");
    dispatch(session, compressor, "call_2", "d2");

    var sources = session.collectGroundingSources();
    assertEquals(
        "dropped",
        sourceOf(sources, "d1").contextInclusion(),
        "the first call's message was stripped, scar and all — it still reports dropped");
    assertEquals(
        "",
        sourceOf(sources, "d2").contextInclusion(),
        "and the live call's carrier is intact, so nothing is claimed about it");
  }

  /* ── Tempdoc 878 §D.3: compaction reports what it dropped ──────────────────────────────────── */

  /**
   * The reader-facing half of the same discipline. {@code compactOlderTurns} DELETES messages;
   * before 878 it told the ledger nothing, so a source whose only carrier had been deleted resolved
   * to ABSENT — say-nothing — and rendered as ordinary evidence next to sources that were still in
   * the prompt. Deletion is the stronger removal of the two, so it must report at least as much as
   * a strip does.
   */
  @Test
  @DisplayName("878 §D.3: a source whose only carrier was compacted away is reported dropped, not absent")
  void compactedAwayCarrier_yieldsDroppedSource() {
    var session = session();
    var compressor = compressor();
    dispatch(session, compressor, "call_1", "d1");
    dispatch(session, compressor, "call_2", "d2");

    // Keep only the most recent message, which drops both tool messages' groups.
    int dropped = session.compactOlderTurns(1);
    assertTrue(dropped > 0, "the fixture must actually compact something");

    var sources = session.collectGroundingSources();
    assertEquals(
        "dropped",
        sourceOf(sources, "d1").contextInclusion(),
        "RED BEFORE 878: compaction wrote nothing to the ledger, so this read back ABSENT");
    assertEquals(
        "dropped",
        sourceOf(sources, "d2").contextInclusion(),
        "including the call whose carrier was INTACT until compaction deleted it — the case a"
            + " strip-only ledger could never see");
  }
}
