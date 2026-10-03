/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tempdoc 771 item (b) — entity carriage: the engine-side half of the hop-2 fix, as pure functions
 * over fields the search response already carries.
 *
 * <p>Why this exists: 771 §E re-measured F-039 on 767's certified strata and found that when a
 * structure-phrased query DOES surface the gold document, the delivered excerpt carries the bridge
 * entity's name in only <b>13/29 = 45%</b> of legal retrievals, against <b>43/46 = 93%</b> on email.
 * The cause is mechanical, not statistical: legal gold documents are long real CLERC cases (median
 * 9,191 chars) whose injected bridge sentence sits at median char-offset 5,005 — past the 4,096-char
 * {@code content_preview} window the delivery excerpt is windowed out of. The bridge entity's name
 * is the ONLY way the answer document is reachable (it is a df=1 globally-unique token that puts the
 * gold at rank 1-2 once queried), and the 782 hero mechanism census found agents almost never Read
 * past delivered evidence (median 0 post-search reads). So on the F-039 domain, an entity absent
 * from the delivered text means hop 2 structurally cannot start.
 *
 * <p><b>What this does.</b> For a delivered hit whose excerpt does not already name some of the
 * document's indexed NER entities (tempdoc 326's {@code entity_*_raw} fields, which the hit already
 * carries), it renders one bounded line listing the missing names. That is the cheapest mechanism
 * that puts entity NAMES into delivered content: no document re-read at delivery time, no second
 * query path, no query-time NER — the values are read off the hit's own field map.
 *
 * <p><b>Content-only, and not a ranking change.</b> Nothing here touches retrieval, fusion, excerpt
 * selection, the MCP tool schema, or any tool parameter (F-016: schema complexity measurably hurts
 * agents). The rendered line is additive delivered text and is governed by the same {@link
 * McpDeliveryGovernor} budget as any other body text.
 *
 * <p><b>Default OFF</b> (D-004 default-off → measure → flip). {@link Settings#OFF} reproduces
 * pre-771 delivery byte-for-byte, which is what {@code McpTierEquivalenceGoldenTest}'s byte-golden
 * fixtures assert on every build.
 *
 * <p>Pure and stateless so carriage is unit-testable without a live backend, matching {@link
 * McpDeliveryFraming}'s design. Package-private: a same-package helper, not public API.
 */
final class McpEntityCarriage {

  private McpEntityCarriage() {}

  /**
   * The per-document NER entity fields (tempdoc 326) carriage reads, in rendering order. These are
   * the {@code *_raw} keyword fields — the canonical extracted surface forms — not the ICU-analyzed
   * {@code *_text} siblings, which hold the same names in a form meant for BM25 rather than display.
   * Same field set {@link McpDeliveryFraming#ENTITY_FACET_FIELDS} reads its F1 vocabulary from, so
   * the two levers name entities from one source.
   */
  static final List<String> ENTITY_FIELDS =
      List.of("entity_persons_raw", "entity_organizations_raw", "entity_locations_raw");

  /** The rendered line's prefix. Deliberately non-exhaustive ("include") — see {@link #line}. */
  static final String PREFIX = "Indexed entities in this document include: ";

  /** Separator between rendered entity values. */
  static final String SEPARATOR = "; ";

  /**
   * Entity values shorter than this are dropped: too generic to seed a follow-up search, and the
   * likeliest shape for a fragment produced by the multi-value split below. Matches {@link
   * McpDeliveryFraming#MIN_ENTITY_LENGTH} so the two levers agree on what counts as an entity.
   */
  static final int MIN_ENTITY_LENGTH = 4;

  /**
   * The resolved carriage settings. Threaded explicitly from the call site rather than read from the
   * global {@link ConfigStore} inside the renderer, so carriage is unit-testable by constructing
   * this record directly — no global mutable state in tests. Mirrors {@link
   * McpDeliveryFraming.Settings}.
   */
  record Settings(boolean enabled, int maxChars) {

    /** Carriage off — the shipped default. */
    static final Settings OFF =
        new Settings(false, ResolvedConfig.Search.DEFAULT_ENTITY_CARRIAGE_MAX_CHARS);
  }

  /** Resolves carriage from the same immutable config captured with the serving search. */
  static Settings resolveSettings(ResolvedConfig snapshot) {
    if (snapshot == null) return Settings.OFF;
    ResolvedConfig.Search.EntityCarriage carriage = snapshot.search().mcpEntityCarriage();
    if (carriage == null) {
      return Settings.OFF;
    }
    return new Settings(carriage.enabled(), carriage.maxChars());
  }

  /**
   * The carriage line for one delivered hit, or {@code null} when there is nothing to carry — the
   * document has no indexed entities, every one of them already appears in the delivered text, or
   * the budget admits none.
   *
   * <p><b>Trigger, stated precisely.</b> The line lists the document's indexed entities that the
   * delivered text does NOT already contain. It is not an "excerpt names zero entities" trigger:
   * which entity is the bridge is unknowable at delivery time, so an excerpt that happens to name
   * one entity while burying the bridge one is exactly the 45%-carriage failure this lever exists
   * for. Listing only the missing names also means the line costs bytes only for what it adds.
   *
   * <p><b>Non-exhaustive by construction.</b> When the budget cannot fit every missing name the line
   * carries as many as fit, in field order (persons, organizations, locations). The "include"
   * wording is therefore literally true at every budget — the line never claims to be the document's
   * complete entity set, which a "Entities: ..." phrasing would.
   */
  static String line(String deliveredText, Map<String, String> fields, int maxChars) {
    if (maxChars <= PREFIX.length()) {
      return null;
    }
    List<String> missing = missingEntities(deliveredText, fields);
    if (missing.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder(PREFIX);
    int rendered = 0;
    for (String entity : missing) {
      String safe = McpSearchResultFormatter.sanitize(entity);
      if (safe.isBlank()) {
        continue;
      }
      int cost = safe.length() + (rendered == 0 ? 0 : SEPARATOR.length());
      if (sb.length() + cost > maxChars) {
        break;
      }
      if (rendered > 0) {
        sb.append(SEPARATOR);
      }
      sb.append(safe);
      rendered++;
    }
    return rendered == 0 ? null : sb.toString();
  }

  /**
   * The document's indexed entity values that {@code deliveredText} does not already contain, in
   * field order and de-duplicated case-insensitively.
   */
  static List<String> missingEntities(String deliveredText, Map<String, String> fields) {
    List<String> all = documentEntities(fields);
    if (all.isEmpty()) {
      return List.of();
    }
    String haystack = deliveredText == null ? "" : deliveredText.toLowerCase(Locale.ROOT);
    List<String> missing = new ArrayList<>();
    for (String entity : all) {
      if (!haystack.contains(entity.toLowerCase(Locale.ROOT))) {
        missing.add(entity);
      }
    }
    return missing;
  }

  /**
   * Every entity value the hit's field map carries, in {@link #ENTITY_FIELDS} order,
   * de-duplicated case-insensitively (first spelling wins).
   *
   * <p><b>One separator, deliberately.</b> A multi-valued keyword field reaches the hit map as a
   * single joined string. Every stored-field read path joins with {@code " | "} ({@code
   * SearchResultFormatter#extractFromDocument} and {@code #extractFromStoredFields} both merge that
   * way), and since the {@code entity_*_raw} fields are {@code stored: true} they are always
   * populated by that path before {@code ReadPathOps#projectDocValues} — which skips a field already
   * in the map — could offer its own {@code ", "}-joined rendering. So {@code " | "} is the only
   * joiner that reaches a search hit, and it is the only one split here.
   *
   * <p>Splitting on {@code ", "} as well was measured and REMOVED: 14 of the 50 bridge entities in
   * the 781-v2 legal cell are {@code "Name, ST"} shaped, and splitting them fragmented the exact
   * names carriage exists to deliver — real-corpus carriage was 82% with the comma split and 100%
   * without it (771 item (b) measurement, 2026-07-29). Where a value genuinely arrives comma-joined
   * (a DocValues-only read path) it is carried WHOLE as one budget unit: coarser accounting, but a
   * name is never cut in half, and every name still reaches the agent verbatim.
   */
  static List<String> documentEntities(Map<String, String> fields) {
    if (fields == null || fields.isEmpty()) {
      return List.of();
    }
    Set<String> seen = new LinkedHashSet<>();
    List<String> out = new ArrayList<>();
    for (String field : ENTITY_FIELDS) {
      String joined = fields.get(field);
      if (joined == null || joined.isBlank()) {
        continue;
      }
      for (String raw : joined.split("\\s\\|\\s")) {
        String value = raw.trim();
        if (value.length() < MIN_ENTITY_LENGTH) {
          continue;
        }
        if (seen.add(value.toLowerCase(Locale.ROOT))) {
          out.add(value);
        }
      }
    }
    return out;
  }

  /**
   * The delivered text carriage tests a hit's entities against: the excerpt the agent is handed,
   * plus the title, which is delivered on every hit and routinely names the document's subject
   * entity. Reading the title too keeps carriage from re-stating a name the header line already
   * carried.
   */
  static String deliveredText(KnowledgeSearchResponse.Hit hit, String preview) {
    String title = hit == null ? "" : hit.fields().getOrDefault("title", "");
    return (title == null ? "" : title) + "\n" + (preview == null ? "" : preview);
  }
}
