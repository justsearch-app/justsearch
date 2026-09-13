/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;
import io.justsearch.ui.api.TestRequestContexts;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.agent.api.registry.OperationCatalog;
import io.justsearch.agent.api.registry.OperationDispatcher;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse.Hit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 771 item (b) — the CARRIAGE METRIC: what fraction of deliveries put the document's
 * bridge-relevant entity into the text an agent actually receives, carriage OFF vs ON, and what
 * that costs in bytes.
 *
 * <p>This is the measured acceptance for the lever, run offline at $0 against the production
 * delivery renderers ({@code buildSearchContent} / {@code renderSearchText}) — no backend, no paid
 * call. Two arms:
 *
 * <ul>
 *   <li><b>Synthetic strata</b> (always runs). Two document geometries taken from 771 §E's
 *       measurement: a LEGAL geometry (median 9,191-char documents with the bridge sentence at
 *       median char-offset 5,005 — past the 4,096-char {@code content_preview}) and an EMAIL
 *       geometry (median 1,477 chars, bridge name at offset 661 — inside it). 771 §E measured 45%
 *       and 93% live carriage on those two strata; this arm reproduces the MECHANISM that separates
 *       them, not those exact percentages.
 *   <li><b>Real corpus</b> (runs when {@code -Djustsearch.entityCarriage.casesTsv=<file>} points at
 *       the output of {@code scripts/analysis/771-entity-carriage/extract-bridge-cases.py} for a
 *       781-v2 cell). Real CLERC document lengths, real injected bridge sentences at their real
 *       offsets, real derived bridge entities.
 * </ul>
 *
 * <p><b>The model, stated so it can be argued with.</b> Offline there is no NER output, so both arms
 * assume the indexer extracted the bridge entity onto the document's {@code entity_persons_raw}
 * field — which is what tempdoc 326's NER does with a person or organization name in the document
 * body. Where NER misses the name entirely, carriage cannot help and this metric overstates it; that
 * is the model's one load-bearing assumption. The DELIVERY side is not modelled at all: each case is
 * fed to the real renderers, so the OFF number is whatever the real preview-selection code delivers.
 * Hits carry no match spans and no excerpt regions, which is the real legal case — 771 §E measured
 * lexical recall at 0.00 on the camouflaged legal strata, so those hits arrive from the semantic legs
 * with no term occurrence to anchor an excerpt window on.
 */
@DisplayName("MCP entity carriage: carriage metric OFF vs ON (tempdoc 771 item (b))")
final class McpEntityCarriageMetricTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneId.of("UTC"));
  private static final McpEntityCarriage.Settings OFF = McpEntityCarriage.Settings.OFF;
  private static final McpEntityCarriage.Settings ON = new McpEntityCarriage.Settings(true, 200);
  private static final McpDeliveryFraming.Settings FRAMING_OFF = McpDeliveryFraming.Settings.OFF;

  /** The indexer's {@code content_preview} cap (IndexingDocumentOps#CONTENT_PREVIEW_MAX_CHARS). */
  private static final int CONTENT_PREVIEW_MAX_CHARS = 4096;

  // =========================================================================
  // Synthetic strata
  // =========================================================================

  @Test
  @DisplayName("legal geometry: carriage lifts a structurally-zero baseline to full carriage")
  void legalGeometry() {
    Carriage result = measure(syntheticStratum(200, 9191, 5005));
    report("synthetic legal (n=200, doc 9191 chars, bridge at 5005)", result);

    // The mechanism 771 §E named: the bridge sentence sits past the indexed preview, so NO
    // preview-selection strategy can deliver it. The baseline is not low, it is zero.
    assertTrue(result.offRate() == 0.0d, "OFF carriage was " + result.offRate());
    assertTrue(result.onRate() == 1.0d, "ON carriage was " + result.onRate());
  }

  @Test
  @DisplayName("email geometry: a delivery that already carries the entity pays nothing")
  void emailGeometry() {
    Carriage result = measure(syntheticStratum(200, 1477, 661));
    report("synthetic email (n=200, doc 1477 chars, bridge at 661)", result);

    // The bridge name is inside the head-of-preview window the delivery already shows, so carriage
    // finds nothing missing and adds no bytes — the lever self-suppresses on the stratum that
    // already works (771 §E: 93% carriage on enron).
    assertTrue(result.offRate() == 1.0d, "OFF carriage was " + result.offRate());
    assertTrue(result.onRate() == 1.0d, "ON carriage was " + result.onRate());
    assertTrue(result.meanOverheadBytes() == 0.0d, "overhead was " + result.meanOverheadBytes());
  }

  @Test
  @DisplayName("the overhead is bounded by the configured ceiling, per hit")
  void overheadIsBounded() {
    Carriage result = measure(syntheticStratum(200, 9191, 5005));
    assertTrue(
        result.maxOverheadBytes() <= ON.maxChars() + 5,
        "max overhead " + result.maxOverheadBytes() + " exceeded the per-hit ceiling");
  }

  // =========================================================================
  // Real corpus
  // =========================================================================

  @Test
  @DisplayName("real 781-v2 cell: carriage measured on real document geometries")
  void realCorpus() throws IOException {
    String tsv = System.getProperty("justsearch.entityCarriage.casesTsv");
    assumeTrue(
        tsv != null && !tsv.isBlank(),
        "run scripts/analysis/771-entity-carriage/extract-bridge-cases.py and pass"
            + " -Djustsearch.entityCarriage.casesTsv=<tsv> to measure a real cell");
    Path path = Path.of(tsv);
    assumeTrue(Files.isRegularFile(path), "not a file: " + path);

    List<Case> cases = loadCases(path);
    assumeTrue(!cases.isEmpty(), "no cases in " + path);
    Carriage result = measure(cases);
    report("real corpus " + path.getFileName() + " (n=" + cases.size() + ")", result);

    assertTrue(
        result.onRate() > result.offRate(),
        "carriage did not lift the delivered-entity rate on " + path);
  }

  // =========================================================================
  // The measurement
  // =========================================================================

  /** One measured document: what the index stores, and the entity a hop-2 search needs. */
  private record Case(String docId, String title, String contentPreview, String bridgeEntity) {}

  private record Carriage(
      int n,
      int offCarried,
      int onCarried,
      double meanOverheadBytes,
      int maxOverheadBytes,
      double meanOffBytes) {

    double offRate() {
      return n == 0 ? 0d : (double) offCarried / n;
    }

    double onRate() {
      return n == 0 ? 0d : (double) onCarried / n;
    }
  }

  private static Carriage measure(List<Case> cases) {
    int offCarried = 0;
    int onCarried = 0;
    long overheadTotal = 0;
    long offTotal = 0;
    int maxOverhead = 0;
    for (Case c : cases) {
      KnowledgeSearchResponse resp = responseFor(c);
      int off = render(resp, OFF).getBytes(StandardCharsets.UTF_8).length;
      int on = render(resp, ON).getBytes(StandardCharsets.UTF_8).length;
      if (containsEntity(render(resp, OFF), c.bridgeEntity())) {
        offCarried++;
      }
      if (containsEntity(render(resp, ON), c.bridgeEntity())) {
        onCarried++;
      }
      overheadTotal += on - off;
      offTotal += off;
      maxOverhead = Math.max(maxOverhead, on - off);
    }
    int n = cases.size();
    return new Carriage(
        n,
        offCarried,
        onCarried,
        n == 0 ? 0d : (double) overheadTotal / n,
        maxOverhead,
        n == 0 ? 0d : (double) offTotal / n);
  }

  private static boolean containsEntity(String delivered, String entity) {
    return delivered.toLowerCase(Locale.ROOT).contains(entity.toLowerCase(Locale.ROOT));
  }

  private static void report(String label, Carriage c) {
    System.out.printf(
        Locale.ROOT,
        "%n[771 entity carriage] %s%n"
            + "  deliveries measured        : %d%n"
            + "  bridge entity delivered OFF: %d (%.1f%%)%n"
            + "  bridge entity delivered ON : %d (%.1f%%)%n"
            + "  mean delivery bytes OFF    : %.0f%n"
            + "  mean carriage overhead     : %.1f bytes (%.2f%% of delivery)%n"
            + "  max carriage overhead      : %d bytes%n",
        label,
        c.n(),
        c.offCarried(),
        c.offRate() * 100,
        c.onCarried(),
        c.onRate() * 100,
        c.meanOffBytes(),
        c.meanOverheadBytes(),
        c.meanOffBytes() == 0 ? 0 : c.meanOverheadBytes() / c.meanOffBytes() * 100,
        c.maxOverheadBytes());
  }

  // =========================================================================
  // Fixtures — synthetic strata
  // =========================================================================

  /**
   * {@code n} documents of {@code docLen} chars with the bridge sentence at {@code bridgeOffset},
   * indexed the way the pipeline indexes them: {@code content_preview} is the document's first
   * {@link #CONTENT_PREVIEW_MAX_CHARS} chars, so a bridge sentence past that offset is simply not in
   * the field the delivery excerpt is drawn from.
   */
  private static List<Case> syntheticStratum(int n, int docLen, int bridgeOffset) {
    List<Case> cases = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      String bridge = "Ofrles Prodres " + String.format(Locale.ROOT, "%04d", i);
      String body = filler(docLen, i);
      String sentence = " The facility was designed by the engineer " + bridge + ". ";
      int at = Math.min(bridgeOffset, body.length());
      String doc = body.substring(0, at) + sentence + body.substring(at);
      String preview =
          doc.length() > CONTENT_PREVIEW_MAX_CHARS
              ? doc.substring(0, CONTENT_PREVIEW_MAX_CHARS)
              : doc;
      cases.add(
          new Case(
              "cases/" + i + ".txt",
              "The power station in the upper wetlands " + i,
              preview,
              bridge));
    }
    return cases;
  }

  private static String filler(int len, int seed) {
    StringBuilder sb = new StringBuilder(len + 200);
    int section = seed;
    while (sb.length() < len) {
      sb.append("MEMORANDUM ORDER, section ")
          .append(section++)
          .append(": the parties dispute the allocation of remediation costs under the consent")
          .append(" decree, and the record before the court does not resolve that question. ");
    }
    return sb.substring(0, len);
  }

  // =========================================================================
  // Fixtures — real 781-v2 corpus cell
  // =========================================================================

  /**
   * Reads the cases emitted by {@code scripts/analysis/771-entity-carriage/extract-bridge-cases.py}
   * — one line per two-hop query: the hop-1 document, its title, the derived bridge entity, and the
   * document's indexed {@code content_preview}.
   *
   * <p>The JSON half of the corpus read lives in that script rather than here because Jackson is not
   * on this module's test compile classpath, and adding it for a measurement input would churn
   * dependency lockfiles. What matters for the metric's validity is that the DELIVERY half is not
   * modelled — the cases go through the real renderers below.
   */
  private static List<Case> loadCases(Path tsv) throws IOException {
    List<Case> cases = new ArrayList<>();
    List<String> lines = Files.readAllLines(tsv, StandardCharsets.UTF_8);
    for (int i = 1; i < lines.size(); i++) {
      String line = lines.get(i);
      if (line.isBlank()) {
        continue;
      }
      String[] cols = line.split("\t", -1);
      if (cols.length < 4) {
        continue;
      }
      cases.add(
          new Case(unescape(cols[0]), unescape(cols[1]), unescape(cols[3]), unescape(cols[2])));
    }
    return cases;
  }

  private static String unescape(String value) {
    return value
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\");
  }

  // =========================================================================
  // Delivery
  // =========================================================================

  /**
   * Builds the delivered hit for one case, modelling the engine's excerpt selection as it actually
   * ships: {@code search.evidence_span.entity_signal} defaults to {@code ner_membership}, so when
   * the bridge name lies INSIDE the indexed {@code content_preview} the selector's entity-coverage
   * ranking finds the window that holds it, and the delivery excerpt is anchored there. When the
   * name lies past the 4,096-char preview it is absent from the field the selector scores at all —
   * no ranking can recover it, and the hit arrives with nothing to anchor on.
   *
   * <p>This is the conservative modelling choice for the lever: it maximises the OFF baseline by
   * giving the pre-carriage delivery the best excerpt the shipped selector could produce.
   *
   * <p>No facets. The response-level facet sidecar renders the top entity values across the whole
   * matched set; giving a one-hit response the case's own bridge entity as its top facet would
   * deliver the name for free and measure the fixture rather than the lever.
   */
  private static KnowledgeSearchResponse responseFor(Case c) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("title", c.title());
    fields.put("path", c.docId());
    fields.put("content_preview", c.contentPreview());
    // The model's one assumption: the indexer's NER extracted the bridge name onto the document.
    fields.put("entity_persons_raw", c.bridgeEntity());

    List<KnowledgeSearchResponse.ExcerptRegion> regions = List.of();
    int at = c.contentPreview().indexOf(c.bridgeEntity());
    if (at >= 0) {
      int start = Math.max(0, at - 200);
      int end = Math.min(c.contentPreview().length(), at + c.bridgeEntity().length() + 200);
      regions =
          List.of(
              new KnowledgeSearchResponse.ExcerptRegion(
                  c.contentPreview().substring(start, end), start, end, 1, List.of()));
    }
    Hit hit = new Hit(c.docId(), 0.71d, fields, List.of(), List.of(), regions, null);
    return new KnowledgeSearchResponse(
        1L, 1L, 7L, List.of(hit), null, Map.of(), null, null, null, null, null, null, null);
  }

  private static String render(KnowledgeSearchResponse resp, McpEntityCarriage.Settings carriage) {
    McpToolSurface surface =
        new McpToolSurface(
            List.of(OperationCatalog.of("core", List.of())),
            mock(OperationDispatcher.class),
            () -> null,
            () -> null,
            FIXED_CLOCK);
    McpSearchResponseContent content =
        surface.buildSearchContent(
            resp,
            Map.of("query", "value associated with the designer"),
            FRAMING_OFF,
            -1L,
            carriage, TestRequestContexts.mcp("s1"));
    return McpToolSurface.renderSearchText(resp, content, false);
  }
}
