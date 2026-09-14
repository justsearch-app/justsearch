/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 771 item (b) — entity carriage: the pure selection/rendering functions, and the rendered
 * before/after produced by the SAME renderers production calls ({@code buildSearchContent} /
 * {@code renderSearchText} / {@code McpEvidenceProjection#searchEvidence}), with the carriage {@link
 * McpEntityCarriage.Settings} threaded explicitly rather than read from a global config store.
 *
 * <p>The fixture reproduces the geometry 771 §E measured on the legal strata: a long document whose
 * bridge-entity sentence sits past the delivered excerpt window, so the delivered text names the
 * document's subject but not the entity an agent needs for a follow-up (hop-2) search.
 */
@DisplayName("MCP entity carriage (tempdoc 771 item (b))")
final class McpEntityCarriageTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneId.of("UTC"));

  private static final McpEntityCarriage.Settings CARRIAGE_OFF = McpEntityCarriage.Settings.OFF;
  private static final McpEntityCarriage.Settings CARRIAGE_ON =
      new McpEntityCarriage.Settings(true, 200);
  private static final McpDeliveryFraming.Settings FRAMING_OFF = McpDeliveryFraming.Settings.OFF;

  /** The bridge entity: named only in the buried sentence, never in title or delivered excerpt. */
  private static final String BRIDGE = "Ofrles Prodres";

  private static McpToolSurface surface() {
    return new McpToolSurface(
        List.of(OperationCatalog.of("core", List.of())),
        mock(OperationDispatcher.class),
        () -> null,
        () -> null,
        FIXED_CLOCK);
  }

  // =========================================================================
  // Entity extraction from the hit's field map
  // =========================================================================

  @Nested
  @DisplayName("document entities")
  class DocumentEntities {

    @Test
    @DisplayName("the stored-field ' | ' joiner splits — it is the only joiner a search hit carries")
    void storedFieldJoinerSplits() {
      assertEquals(
          List.of("Ofrles Prodres", "Eitte Saants"),
          McpEntityCarriage.documentEntities(
              Map.of("entity_persons_raw", "Ofrles Prodres | Eitte Saants")));
    }

    @Test
    @DisplayName("a comma inside an entity value is NOT a separator — 'Mank, MT' survives whole")
    void commaIsNotASeparator() {
      // Measured regression guard (771 item (b), 2026-07-29): splitting on ", " as well fragmented
      // 14 of the 50 bridge entities in the 781-v2 legal cell into unsearchable pieces and capped
      // real-corpus carriage at 82%. A location- or suffix-shaped name must survive intact.
      assertEquals(
          List.of("Mank, MT"), McpEntityCarriage.documentEntities(Map.of("entity_locations_raw", "Mank, MT")));
      assertEquals(
          List.of("Mank, MT", "Newson, NY"),
          McpEntityCarriage.documentEntities(
              Map.of("entity_locations_raw", "Mank, MT | Newson, NY")));
    }

    @Test
    @DisplayName("field order is persons, organizations, locations — and duplicates collapse")
    void fieldOrderAndDedupe() {
      Map<String, String> fields = new LinkedHashMap<>();
      fields.put("entity_locations_raw", "Delaware");
      fields.put("entity_organizations_raw", "Acme Holdings | delaware");
      fields.put("entity_persons_raw", "Ofrles Prodres");
      // "delaware" is the case-insensitive duplicate of the locations entry; first spelling wins,
      // and it wins in field order, so the persons/organizations/locations order is what renders.
      assertEquals(
          List.of("Ofrles Prodres", "Acme Holdings", "delaware"),
          McpEntityCarriage.documentEntities(fields));
    }

    @Test
    @DisplayName("values shorter than the floor are dropped — too generic to seed a search")
    void shortValuesDropped() {
      assertEquals(
          List.of("Prodres"),
          McpEntityCarriage.documentEntities(Map.of("entity_persons_raw", "Ltd | Prodres | AG")));
    }

    @Test
    @DisplayName("no entity fields, blank fields, and a null map all yield nothing")
    void emptySources() {
      assertEquals(List.of(), McpEntityCarriage.documentEntities(null));
      assertEquals(List.of(), McpEntityCarriage.documentEntities(Map.of()));
      assertEquals(List.of(), McpEntityCarriage.documentEntities(Map.of("title", "x")));
      assertEquals(
          List.of(), McpEntityCarriage.documentEntities(Map.of("entity_persons_raw", "   ")));
    }
  }

  // =========================================================================
  // The carriage line
  // =========================================================================

  @Nested
  @DisplayName("carriage line")
  class CarriageLine {

    @Test
    @DisplayName("lists only the entities the delivered text does NOT already name")
    void listsOnlyMissingEntities() {
      String line =
          McpEntityCarriage.line(
              "the reactor was commissioned by Eitte Saants in the northern marshlands",
              Map.of("entity_persons_raw", "Eitte Saants | " + BRIDGE),
              200);
      assertNotNull(line);
      assertTrue(line.contains(BRIDGE), line);
      assertFalse(line.contains("Eitte Saants"), line);
    }

    @Test
    @DisplayName("null when every indexed entity already appears in the delivered text")
    void nullWhenNothingMissing() {
      assertNull(
          McpEntityCarriage.line(
              "commissioned by Eitte Saants and " + BRIDGE,
              Map.of("entity_persons_raw", "Eitte Saants | " + BRIDGE),
              200));
    }

    @Test
    @DisplayName("containment is case-insensitive — a differently-cased mention still counts")
    void containmentIsCaseInsensitive() {
      assertNull(
          McpEntityCarriage.line(
              "COMMISSIONED BY OFRLES PRODRES", Map.of("entity_persons_raw", BRIDGE), 200));
    }

    @Test
    @DisplayName("null when the document has no indexed entities")
    void nullWithoutEntities() {
      assertNull(McpEntityCarriage.line("some excerpt", Map.of("title", "t"), 200));
    }

    @Test
    @DisplayName("the whole rendered line stays within maxChars, dropping names rather than cutting one")
    void respectsByteCeiling() {
      Map<String, String> fields =
          Map.of("entity_persons_raw", "Alexandrina Vasilievna | Bartholomew Fitzgerald | Cassiopeia Winterbourne");
      String full = McpEntityCarriage.line("excerpt", fields, 200);
      assertNotNull(full);
      assertTrue(full.contains("Cassiopeia Winterbourne"), full);

      String tight = McpEntityCarriage.line("excerpt", fields, 70);
      assertNotNull(tight);
      assertTrue(tight.length() <= 70, "line was " + tight.length() + " chars: " + tight);
      assertTrue(tight.startsWith(McpEntityCarriage.PREFIX), tight);
      assertTrue(tight.contains("Alexandrina Vasilievna"), tight);
      // Dropped whole, never truncated mid-name: a half-name is not a searchable entity.
      assertFalse(tight.contains("Bartholomew Fitz"), tight);
    }

    @Test
    @DisplayName("a ceiling that cannot fit the prefix suppresses the line entirely")
    void ceilingBelowPrefixSuppresses() {
      Map<String, String> fields = Map.of("entity_persons_raw", BRIDGE);
      assertNull(McpEntityCarriage.line("excerpt", fields, 10));
      assertNull(McpEntityCarriage.line("excerpt", fields, 0));
      assertNull(McpEntityCarriage.line("excerpt", fields, -1));
    }

    @Test
    @DisplayName("the title counts as delivered text — carriage does not restate the header")
    void titleCountsAsDelivered() {
      Hit hit = hit("doc-1", "Order of " + BRIDGE, "excerpt without the name", BRIDGE);
      assertNull(
          McpEntityCarriage.line(
              McpEntityCarriage.deliveredText(hit, "excerpt without the name"), hit.fields(), 200));
    }
  }

  // =========================================================================
  // Rendered delivery: OFF vs ON
  // =========================================================================

  @Nested
  @DisplayName("rendered delivery")
  class RenderedDelivery {

    @Test
    @DisplayName("OFF: a long document's buried bridge entity is NOT delivered — the 771 §E failure")
    void offDoesNotDeliverBuriedEntity() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      String off = renderSearch(resp, CARRIAGE_OFF);
      assertFalse(off.contains(BRIDGE), off);
      assertFalse(off.contains(McpEntityCarriage.PREFIX), off);
    }

    @Test
    @DisplayName("ON: the same delivery carries the bridge entity, so hop 2 can start")
    void onDeliversBuriedEntity() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      String on = renderSearch(resp, CARRIAGE_ON);
      assertTrue(on.contains(BRIDGE), on);
      assertTrue(on.contains(McpEntityCarriage.PREFIX), on);
    }

    @Test
    @DisplayName("OFF renders byte-identically to a surface that never knew about carriage")
    void offIsTheControlArm() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      String off = renderSearch(resp, CARRIAGE_OFF);
      String on = renderSearch(resp, CARRIAGE_ON);
      // The ON delivery is the OFF delivery plus carriage lines and nothing else.
      assertEquals(off, stripLinesContaining(on, McpEntityCarriage.PREFIX));
    }

    @Test
    @DisplayName("the overhead is bounded by maxChars per hit")
    void overheadIsBounded() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      int off = renderSearch(resp, CARRIAGE_OFF).length();
      int on = renderSearch(resp, CARRIAGE_ON).length();
      int hits = resp.results().size();
      // Each carriage line costs at most maxChars plus its four-space indent and newline.
      assertTrue(on - off <= hits * (CARRIAGE_ON.maxChars() + 5), "overhead was " + (on - off));
      assertTrue(on > off, "carriage added nothing");
    }

    @Test
    @DisplayName("carriage renders at BOTH densities — concise omits the excerpt, not the entity")
    void renderedInConciseToo() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      McpSearchResponseContent content =
          surface()
              .buildSearchContent(
                  resp, Map.of("query", "power station upper wetlands"), FRAMING_OFF, -1L,
                  CARRIAGE_ON, TestRequestContexts.mcp("s1"));
      String concise = McpToolSurface.renderSearchText(resp, content, true);
      assertFalse(concise.contains("Preview:"), concise);
      assertTrue(concise.contains(BRIDGE), concise);
    }

    @Test
    @DisplayName("the structured tier carries the same line — carriage cannot fix one tier only")
    void structuredTierCarriesIt() {
      KnowledgeSearchResponse resp = buriedEntityResponse();
      McpSearchResponseContent content =
          surface()
              .buildSearchContent(
                  resp, Map.of("query", "power station upper wetlands"), FRAMING_OFF, -1L,
                  CARRIAGE_ON, TestRequestContexts.mcp("s1"));
      Map<String, Object> structured = McpEvidenceProjection.searchEvidence(resp, content, false);
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> results = (List<Map<String, Object>>) structured.get("results");
      String carriage = (String) results.get(0).get("entityCarriage");
      assertNotNull(carriage, structured.toString());
      assertTrue(carriage.contains(BRIDGE), carriage);

      McpSearchResponseContent offContent =
          surface()
              .buildSearchContent(
                  resp, Map.of("query", "power station upper wetlands"), FRAMING_OFF, -1L,
                  CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> offResults =
          (List<Map<String, Object>>)
              McpEvidenceProjection.searchEvidence(resp, offContent, false).get("results");
      assertFalse(offResults.get(0).containsKey("entityCarriage"), offResults.toString());
    }

    @Test
    @DisplayName("carriage composes with F1: the newly delivered entity becomes markable")
    void composesWithContinuationFraming() {
      KnowledgeSearchResponse resp = withEntityFacets(buriedEntityResponse());
      McpDeliveryFraming.Settings f1 =
          new McpDeliveryFraming.Settings(true, false, false, 400, 0.40);

      // Without carriage F1 has nothing to mark: the entity is not in the delivered text at all.
      McpSearchResponseContent withoutCarriage =
          surface()
              .buildSearchContent(
                  resp, Map.of("query", "power station upper wetlands"), f1, -1L, CARRIAGE_OFF, TestRequestContexts.mcp("s1"));
      assertNull(withoutCarriage.hits().get(0).continuation());

      // With carriage the same F1 setting now marks the bridge entity as a hop-2 candidate.
      McpSearchResponseContent withCarriage =
          surface()
              .buildSearchContent(
                  resp, Map.of("query", "power station upper wetlands"), f1, -1L, CARRIAGE_ON,
                  TestRequestContexts.mcp("s1"));
      assertNotNull(withCarriage.hits().get(0).continuation());
      assertTrue(withCarriage.hits().get(0).continuation().contains(BRIDGE));
    }
  }

  // =========================================================================
  // Fixtures
  // =========================================================================

  /**
   * The 771 §E legal geometry: a ~9,200-char document whose bridge sentence sits at char ~5,000,
   * past the 4,096-char {@code content_preview} the delivery excerpt is windowed out of. The hit is
   * semantic-only (no match spans, no excerpt regions) — the real legal case, where 771 §E measured
   * lexical recall at 0.00 after camouflage, so the delivery falls back to the head of the preview.
   */
  private static KnowledgeSearchResponse buriedEntityResponse() {
    Hit hit =
        hit(
            "cases/408933.txt",
            "The power station in the upper wetlands",
            longDocPreview(),
            BRIDGE + " | Eitte Saants");
    // No facets. The response-level facet block renders the top entity values across the WHOLE
    // matched set, so a one-hit fixture with facets on would deliver the bridge name via the facet
    // sidecar and mask what carriage does. That is not the production case 771 §E measured: at
    // limit 10+ the five facet slots hold the most-referenced entities of the matched set, which is
    // exactly not the per-hit bridge entity of one long document.
    return new KnowledgeSearchResponse(
        1L, 1L, 7L, List.of(hit), null, Map.of(), null, null, null, null, null, null, null);
  }

  /** The same response with the bridge entity in the facet snapshot, which F1 reads its vocabulary from. */
  private static KnowledgeSearchResponse withEntityFacets(KnowledgeSearchResponse resp) {
    return new KnowledgeSearchResponse(
        resp.totalHits(),
        resp.matchCount(),
        resp.tookMs(),
        resp.results(),
        null,
        Map.of("entity_persons_raw", Map.of(BRIDGE, 2L)),
        null,
        null,
        null,
        null,
        null,
        null, null);
  }

  /**
   * The stored {@code content_preview}: 4,096 chars of case body, none of it naming the bridge
   * entity — the sentence that does sits at char 5,005 of the source document and is therefore not
   * in the indexed preview at all.
   */
  private static String longDocPreview() {
    StringBuilder sb = new StringBuilder(4096);
    int section = 0;
    while (sb.length() < 4096) {
      sb.append("MEMORANDUM ORDER, section ")
          .append(section++)
          .append(": the parties dispute the allocation of remediation costs under the consent")
          .append(" decree, and the record before the court does not resolve the question. ");
    }
    return sb.substring(0, 4096);
  }

  private static Hit hit(String path, String title, String preview, String persons) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("title", title);
    fields.put("path", path);
    fields.put("content_preview", preview);
    fields.put("entity_persons_raw", persons);
    return new Hit(path, 0.71d, fields, List.of(), List.of(), List.of(), null);
  }

  private static String renderSearch(
      KnowledgeSearchResponse resp, McpEntityCarriage.Settings carriage) {
    McpSearchResponseContent content =
        surface()
            .buildSearchContent(
                resp, Map.of("query", "power station upper wetlands"), FRAMING_OFF, -1L, carriage, TestRequestContexts.mcp("s1"));
    return McpToolSurface.renderSearchText(resp, content, false);
  }

  private static String stripLinesContaining(String text, String needle) {
    StringBuilder sb = new StringBuilder();
    String[] lines = text.split("\n", -1);
    for (int i = 0; i < lines.length; i++) {
      if (lines[i].contains(needle)) {
        continue;
      }
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(lines[i]);
    }
    return sb.toString();
  }
}
