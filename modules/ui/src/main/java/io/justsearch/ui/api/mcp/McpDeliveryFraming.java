/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.mcp;

import io.justsearch.app.api.knowledge.SearchTrace;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tempdoc 789 Phase 2 — the three flag-gated agent-delivery framings, as pure functions over facts
 * the response already carries.
 *
 * <p>Why this exists: the 782 hero campaign's mechanism analysis (register F-043) found the tool's
 * accuracy deficit is a DELIVERY-SHAPE effect, not a retrieval effect — span carriage was 0.80-0.88,
 * the gold was routinely delivered, and the response shape terminated the loop anyway. 38% of breaks
 * were pure hop-1 stopping (the agent read an intermediate fact and answered with it); the tool arm
 * abstained 2x baseline at identical fabrication counts, including replay-verified cells denying gold
 * present in their own output; and {@code justsearch_answer} involvement anti-correlated with success
 * in all three strata. These three framings are the probe arms against those three shapes:
 *
 * <ul>
 *   <li><b>F1 continuation</b> — against hop-1 stopping: a delivered excerpt naming an indexed entity
 *       the query did not carries one line marking it as a possible intermediate fact.
 *   <li><b>F2 evidence-not-answer</b> — against the terminal answer shape: deliveries are framed as
 *       lexical/semantic matches, not verified answers.
 *   <li><b>F3 calibrated absence</b> — against over-trusted emptiness: zero-hit, weak-relevance and
 *       thin deliveries carry corpus coverage and explicit absence-is-not-evidence framing.
 * </ul>
 *
 * <p><b>Content-only by construction.</b> Nothing here touches retrieval, the MCP tool schema, or any
 * tool parameter (F-016: schema complexity measurably hurts agents — the charter forbids new
 * parameters until measured otherwise). Every input is a fact the response already carries or an
 * index statistic an existing surface already exposes.
 *
 * <p><b>All framings default OFF.</b> {@link Settings#OFF} reproduces pre-789 delivery byte-for-byte,
 * which is what {@code McpTierEquivalenceGoldenTest}'s byte-golden fixtures assert on every build.
 *
 * <p>Pure and stateless so each framing is unit-testable without a live backend, matching {@link
 * McpSearchResultFormatter}'s design. Package-private: a same-package helper, not public API.
 */
final class McpDeliveryFraming {

  private McpDeliveryFraming() {}

  /**
   * The entity facet fields the F1 vocabulary is read from. These are the per-document NER fields
   * (tempdoc 326) that {@code McpToolSurface#callSearch} ALREADY requests on every search, so F1
   * introduces no new query path — it reads the facet snapshot the response came back with.
   */
  static final List<String> ENTITY_FACET_FIELDS =
      List.of("entity_persons_raw", "entity_organizations_raw", "entity_locations_raw");

  /** Entity values shorter than this are too generic to be worth a continuation line. */
  static final int MIN_ENTITY_LENGTH = 4;

  /** Query/entity tokens shorter than this are ignored when testing entity-in-query overlap. */
  static final int MIN_OVERLAP_TOKEN_LENGTH = 3;

  /** Cap on continuation lines per response, so the framing cannot dominate the payload. */
  static final int MAX_CONTINUATION_LINES = 3;

  /**
   * The resolved framing flags. Threaded explicitly from the call site rather than read from the
   * global {@link ConfigStore} inside the renderers, so every framing is unit-testable by
   * constructing this record directly — no global mutable state in tests.
   */
  record Settings(
      boolean continuationEnabled,
      boolean evidenceNotAnswerEnabled,
      boolean calibratedAbsenceEnabled,
      int thinResultFloorBytes,
      double weakScoreFloor) {

    /** Every framing off — the shipped default. */
    static final Settings OFF =
        new Settings(
            false,
            false,
            false,
            ResolvedConfig.Search.DEFAULT_THIN_RESULT_FLOOR_BYTES,
            ResolvedConfig.Search.DEFAULT_WEAK_SCORE_FLOOR);
  }

  /** Resolves framing from the same immutable config captured with the serving operation. */
  static Settings resolveSettings(ResolvedConfig snapshot) {
    if (snapshot == null) {
      return Settings.OFF;
    }
    ResolvedConfig.Search.McpFraming framing = snapshot.search().mcpFraming();
    if (framing == null) {
      return Settings.OFF;
    }
    return new Settings(
        framing.continuationEnabled(),
        framing.evidenceNotAnswerEnabled(),
        framing.calibratedAbsenceEnabled(),
        framing.thinResultFloorBytes(),
        framing.weakScoreFloor());
  }

  // =========================================================================
  // F1 — continuation
  // =========================================================================

  /**
   * F1: the continuation line for one delivered excerpt, or {@code null} when the excerpt names no
   * indexed entity that the query did not already name.
   *
   * <p>Entity source is the facet snapshot ({@link #ENTITY_FACET_FIELDS}) the search response already
   * carries — the charter's "prefer reading existing fields over running new NER at query time". The
   * vocabulary is the entity values the index holds for the matched document set; an entity is
   * eligible only if the delivered text actually contains it, so the line never claims something the
   * agent was not handed.
   *
   * <p><b>Count semantics, stated honestly.</b> The facet count is a MATCHED-DOCUMENT tally, not a
   * corpus-wide document frequency: {@code FacetingEngine} tallies facet values over the documents
   * matching this query (its own comment: "facet value &lt;= matchedDocs by construction"). The
   * rendered wording therefore says "of the documents matching this search" rather than the charter's
   * looser "the corpus contains further documents". Reporting the matched-set count as a corpus count
   * would be a fabricated statistic; obtaining a true corpus-wide count would need a second query
   * path, which the charter explicitly rules out ("omit the count rather than adding a new query
   * path"). The available count is honest about its own scope, so it ships with scoped wording.
   */
  static String continuationLine(String deliveredText, String query, Map<String, Long> vocabulary) {
    if (deliveredText == null || deliveredText.isBlank() || vocabulary.isEmpty()) {
      return null;
    }
    String haystack = deliveredText.toLowerCase(Locale.ROOT);
    Set<String> queryTokens = overlapTokens(query);
    for (Map.Entry<String, Long> candidate : vocabulary.entrySet()) {
      String entity = candidate.getKey();
      if (entity == null || entity.length() < MIN_ENTITY_LENGTH) {
        continue;
      }
      if (namedByQuery(entity, queryTokens)) {
        continue;
      }
      if (!haystack.contains(entity.toLowerCase(Locale.ROOT))) {
        continue;
      }
      String safeEntity = McpSearchResultFormatter.sanitize(entity);
      if (safeEntity.isBlank()) {
        continue;
      }
      return "note: this excerpt names \""
          + safeEntity
          + "\" — "
          + candidate.getValue()
          + " of the documents matching this search also reference it. If that is an intermediate"
          + " fact rather than your answer, a follow-up search for it may locate the final answer.";
    }
    return null;
  }

  /**
   * The F1 entity vocabulary: every value under {@link #ENTITY_FACET_FIELDS} in the response's facet
   * snapshot, highest matched-document count first so the most-referenced entity wins the single
   * continuation slot. Returns an empty map when facets are absent (facets disabled, or NER not yet
   * complete) — F1 then emits nothing rather than inventing an entity source.
   */
  static Map<String, Long> entityVocabulary(Map<String, Map<String, Long>> facets) {
    LinkedHashMap<String, Long> merged = new LinkedHashMap<>();
    if (facets == null || facets.isEmpty()) {
      return merged;
    }
    List<Map.Entry<String, Long>> all = new ArrayList<>();
    for (String field : ENTITY_FACET_FIELDS) {
      Map<String, Long> values = facets.get(field);
      if (values == null) {
        continue;
      }
      for (Map.Entry<String, Long> e : values.entrySet()) {
        if (e.getKey() != null && !e.getKey().isBlank() && e.getValue() != null) {
          all.add(Map.entry(e.getKey(), e.getValue()));
        }
      }
    }
    all.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
    for (Map.Entry<String, Long> e : all) {
      merged.putIfAbsent(e.getKey(), e.getValue());
    }
    return merged;
  }

  /**
   * True when the query already names this entity — every one of the entity's significant tokens
   * appears in the query. This is the charter's "entity NOT present in the query" test: a query of
   * "who did Vince Kaminski email" suppresses the "Vince Kaminski" continuation (it is not an
   * intermediate fact, it is the subject), while "who did the risk manager email" does not.
   *
   * <p>Token-set containment rather than substring matching, so word order and surrounding phrasing
   * do not defeat the check, and a partial overlap ("Kaminski" alone in a query for "Vince Kaminski")
   * still counts the entity as NOT named — the agent has not been shown the full name.
   */
  private static boolean namedByQuery(String entity, Set<String> queryTokens) {
    Set<String> entityTokens = overlapTokens(entity);
    if (entityTokens.isEmpty()) {
      // No significant tokens to compare (e.g. an all-punctuation value): treat as named, so it
      // never produces a continuation line.
      return true;
    }
    return queryTokens.containsAll(entityTokens);
  }

  /** Lowercased alphanumeric tokens of length &gt;= {@link #MIN_OVERLAP_TOKEN_LENGTH}. */
  private static Set<String> overlapTokens(String text) {
    Set<String> out = new LinkedHashSet<>();
    if (text == null || text.isBlank()) {
      return out;
    }
    for (String raw : text.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}]+")) {
      if (raw.length() >= MIN_OVERLAP_TOKEN_LENGTH) {
        out.add(raw);
      }
    }
    return out;
  }

  // =========================================================================
  // F2 — evidence, not answer
  // =========================================================================

  /**
   * F2 for {@code justsearch_search}: a header naming the terms that actually matched plus one
   * sentence stating what the delivery is and is not. Terms come from the per-hit matched-term lists
   * the response already computed (the same facts the "Matched:" lines render), deduplicated in
   * first-seen rank order.
   */
  static String searchEvidenceHeader(long totalHits, List<String> matchedTerms) {
    StringBuilder sb = new StringBuilder("Retrieval evidence — ");
    sb.append(totalHits).append(totalHits == 1 ? " document matches" : " documents match");
    if (matchedTerms != null && !matchedTerms.isEmpty()) {
      sb.append(" on ");
      for (int i = 0; i < matchedTerms.size(); i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append('"').append(McpSearchResultFormatter.sanitize(matchedTerms.get(i))).append('"');
      }
    }
    sb.append(
        ". These are lexical and semantic matches to your query, not verified answers to your"
            + " question — read the excerpts and judge for yourself whether they answer it.");
    return sb.toString();
  }

  /**
   * F2 for {@code justsearch_answer}. The charter carves this tool out of Phase 2's scope EXCEPT for
   * F2, because the census found answer-shaped output the most terminal delivery shape of all
   * (register F-043) and the probe needs the arm coverage.
   *
   * <p>It was NOT structurally messy to apply, so it ships as chartered: the answer renderer already
   * opens with a self-describing "Evidence pack" header (tempdoc 725 W2a) and this line prepends
   * above it. No engine-side hop-2 behaviour is touched — this is a text prefix and nothing else.
   */
  static String answerEvidenceHeader(long passages, long distinctDocs) {
    return "Retrieval evidence — "
        + passages
        + (passages == 1 ? " passage" : " passages")
        + " from "
        + distinctDocs
        + (distinctDocs == 1 ? " document" : " documents")
        + ", selected by lexical and semantic match to your query. This is retrieved evidence, not a"
        + " verified answer to your question — the passages may be relevant without containing the"
        + " answer.";
  }

  /**
   * The response-level matched-term list for {@link #searchEvidenceHeader}: each hit's already-
   * computed informative terms, deduplicated case-insensitively in rank order and capped.
   */
  static List<String> responseMatchedTerms(List<McpSearchResponseContent.HitContent> hits, int cap) {
    List<String> out = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    if (hits == null) {
      return out;
    }
    for (McpSearchResponseContent.HitContent hit : hits) {
      for (String term : hit.matchedTerms()) {
        if (term == null || term.isBlank()) {
          continue;
        }
        if (seen.add(term.toLowerCase(Locale.ROOT))) {
          out.add(term);
          if (out.size() >= cap) {
            return out;
          }
        }
      }
    }
    return out;
  }

  // =========================================================================
  // F3 — calibrated absence
  // =========================================================================

  /**
   * The trigger inputs F3 reads, carried as one record so the arms are named at the call site rather
   * than positional in a five-scalar signature.
   *
   * @param totalHits the response's total match count — 0 is the zero-hit arm
   * @param topScore the MAXIMUM score over delivered hits, or negative when unavailable — see {@link
   *     #topDeliveredScore}
   * @param scoreComparable whether {@code topScore} is on the bounded [0,1] scale an absolute floor
   *     can be compared against — see {@link #normalizedFusionScale}
   * @param deliveredBodyBytes the rendered hit-body size — see {@link #deliveredBodyBytes}
   * @param indexedDocs the corpus coverage count, or negative when unavailable
   */
  record AbsenceSignals(
      long totalHits,
      double topScore,
      boolean scoreComparable,
      int deliveredBodyBytes,
      long indexedDocs) {}

  /**
   * True when this response's fused score is on a bounded [0,1] scale that an absolute floor can be
   * compared against — the precondition for the weak-score arm.
   *
   * <p><b>Why the scope limit.</b> {@code KnowledgeSearchResponse.Hit.score} has no documented
   * meaning; its SCALE is set by which retrieval legs ran. Only two fusion methods produce a convex
   * combination of min-max-normalized leg scores and are therefore bounded [0,1]: {@code "hybrid"}
   * (the Bm25+Dense pair) and {@code "cc"} (the three-way combination). The splade pairings fuse by
   * RRF, whose values sit around 0.016-0.033, and a single-leg delivery carries a raw unbounded BM25
   * score or a raw cosine — comparing any of those against a [0,1] floor would fire the arm on every
   * healthy delivery. So the score arm stays SILENT for them and the zero-hit and byte arms carry
   * those deliveries alone.
   *
   * <p>The fusion method is read from the FUSION stage's {@code detail}, which the worker sets from
   * the chosen leg set and the head maps unconditionally — it is an always-on response fact, not a
   * {@code debug}-gated one, so the arm needs no new query path or parameter.
   */
  static boolean normalizedFusionScale(SearchTrace trace) {
    if (trace == null || trace.stages() == null) {
      return false;
    }
    for (SearchTrace.TraceStage stage : trace.stages()) {
      if (stage == null
          || stage.id() != SearchTrace.StageId.FUSION
          || stage.status() != SearchTrace.StageStatus.EXECUTED) {
        continue;
      }
      String detail = stage.detail();
      if (detail == null) {
        continue;
      }
      String method = detail.trim().toLowerCase(Locale.ROOT);
      if ("hybrid".equals(method) || "cc".equals(method)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The MAXIMUM score over the delivered hits, or {@code -1.0} when nothing was delivered.
   *
   * <p><b>Max, not {@code hits.get(0)}.</b> The cross-encoder REORDERS results without rewriting
   * their scores ({@code KnowledgeSearchEngine} builds its reranked list by index and leaves each
   * hit's {@code score} untouched), so the first delivered hit is not in general the highest-scoring
   * one. Reading rank 1 would make the arm fire on reranked deliveries whose actual top score is
   * healthy.
   */
  static double topDeliveredScore(List<McpSearchResponseContent.HitContent> hits) {
    if (hits == null || hits.isEmpty()) {
      return -1.0;
    }
    double max = -1.0;
    for (McpSearchResponseContent.HitContent hit : hits) {
      if (hit != null && hit.score() > max) {
        max = hit.score();
      }
    }
    return max;
  }

  /**
   * F3: the calibrated-absence block for a zero-result, weak-relevance or thin-result delivery, or
   * {@code null} when the delivery is none of the three.
   *
   * <p>Carries the three things the charter names: corpus coverage ({@code indexedDocs}, read from
   * the index status surface {@code justsearch_status} already exposes), what was searched, and an
   * explicit statement that absence of results is not evidence of absence, with a concrete
   * suggestion. {@code indexedDocs < 0} means the count was unavailable, and the coverage clause is
   * omitted rather than guessed.
   *
   * <p><b>Design note — what the score arm measures (post-Amendment-3 redesign).</b> The fused score
   * is min-max normalized WITHIN the query's own candidate set, so it measures how decisively the
   * top document won its own candidate set, not absolute relevance against the corpus. That is the
   * discriminating property the arm wants: a query whose best candidate barely separates from the
   * rest of its own retrieval window is the "matches were returned but nothing really matched" shape
   * the framing targets. It is scoped by {@link #normalizedFusionScale} to the {@code cc}/{@code
   * hybrid} fusion methods, where the score is bounded [0,1]; RRF and single-leg deliveries get the
   * zero-hit and byte arms only.
   *
   * <p>The arm exists because the byte signal had no dynamic range in live measurement (tempdoc 789
   * Amendment 3): gibberish, rare-phrase and healthy queries all delivered ~1,630-1,725 content
   * bytes, so the thin arm never fired and the probe arm was dropped for want of a positive control.
   * The score signal does have range on the same corpus — 0.22 for a gibberish query, 1.00 for a
   * gold-bearing healthy one. The default floor of 0.40 sits above the measured weak regime and
   * below both the measured healthy value and the structural landmark at {@code alpha = 0.5} (the
   * default {@code index.hybrid.cc_alpha}), the fused score a document topping exactly one
   * normalized leg receives.
   *
   * <p>The three arms are independent: a zero-hit delivery is framed as such, and a non-empty
   * delivery may trip the score arm, the byte arm, or both.
   */
  static String absenceNote(AbsenceSignals signals, String query, Settings settings) {
    boolean empty = signals.totalHits() == 0;
    boolean weakScore =
        !empty
            && signals.scoreComparable()
            && signals.topScore() >= 0.0
            && signals.topScore() < settings.weakScoreFloor();
    boolean thinBytes = !empty && signals.deliveredBodyBytes() < settings.thinResultFloorBytes();
    if (!empty && !weakScore && !thinBytes) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    long indexedDocs = signals.indexedDocs();
    if (indexedDocs >= 0) {
      sb.append(indexedDocs)
          .append(indexedDocs == 1 ? " document is" : " documents are")
          .append(" indexed and ")
          .append(indexedDocs == 1 ? "was" : "were")
          .append(" searched");
    } else {
      sb.append("The index was searched");
    }
    String safeQuery = McpSearchResultFormatter.sanitize(query);
    if (!safeQuery.isBlank()) {
      sb.append(" for \"").append(safeQuery).append('"');
    }
    sb.append(". ");
    if (empty) {
      sb.append("No document matched. ");
    } else {
      if (weakScore) {
        sb.append("Matches were returned but scored weakly (top relevance ")
            .append(fixed2(signals.topScore()))
            .append(" of a possible 1.00, under the ")
            .append(fixed2(settings.weakScoreFloor()))
            .append(" floor for a substantive match). ");
      }
      if (thinBytes) {
        sb.append("Matches were returned but carry very little text (")
            .append(signals.deliveredBodyBytes())
            .append(" bytes, under the ")
            .append(settings.thinResultFloorBytes())
            .append("-byte floor for a substantive result). ");
      }
    }
    sb.append(
        "Absence of results is not evidence of absence: the index may phrase the fact differently,"
            + " the document may not be indexed, or the match may sit in a field this query did not"
            + " reach. Before concluding the information does not exist, try alternate phrasings or"
            + " narrower terms; if you have native file tools, reading or grepping the source"
            + " directory directly will settle it.");
    return sb.toString();
  }

  /** Two-decimal rendering of a relevance value, locale-independent so the text is stable. */
  private static String fixed2(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  /**
   * The delivered-body size the F3 thin-result trigger measures: the per-hit text an agent actually
   * receives — title, path, preview and matched terms — summed across delivered hits.
   *
   * <p><b>Disposition after the post-Amendment-3 redesign: RETAINED</b> as the degenerate-delivery
   * arm — documents matched but carry essentially no deliverable text (the empty-extraction share
   * tempdoc 790 targets). The score arm structurally cannot see that case: a hit can score 1.00 on a
   * title match while delivering ~30 bytes of body. The Amendment-3 corpus never tripped this floor
   * because every delivery there carried ~1.6 KB, which is a fact about that corpus, not evidence
   * the arm is vacuous.
   *
   * <p>Deliberately excludes the response-level scaffolding (the "Found N results" line, facets,
   * hints, and the framing lines themselves): those are constant-ish overhead present even on a
   * result set carrying no usable document text, so counting them would let boilerplate lift a
   * substantively empty delivery over the floor. Excluding the framing lines also keeps the trigger
   * independent of which OTHER framings are enabled, so F1+F3 compose without F1's continuation
   * lines suppressing F3.
   */
  static int deliveredBodyBytes(List<McpSearchResponseContent.HitContent> hits) {
    if (hits == null) {
      return 0;
    }
    int total = 0;
    for (McpSearchResponseContent.HitContent hit : hits) {
      total += hit.title().length() + hit.path().length() + hit.preview().length();
      for (String term : hit.matchedTerms()) {
        total += term.length();
      }
    }
    return total;
  }
}
