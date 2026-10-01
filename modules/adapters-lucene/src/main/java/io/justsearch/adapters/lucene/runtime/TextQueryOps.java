/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import static io.justsearch.adapters.lucene.runtime.QueryFilterBuilder.applyRuntimeFilters;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.QppSignals;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.QuerySyntax;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchFilters;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.SearchResult;
import io.justsearch.indexing.SchemaFields;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes.RuntimeSearchSort;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal text-query-building collaborator for {@link LuceneLifecycleManager}.
 *
 * <p>Encapsulates the query construction logic (simple content queries, fuzzy correction, prefix
 * expansion, token analysis) that was previously inlined in the runtime facade.
 *
 * <p>Lifecycle: instances are created in {@code applyComponents()} and discarded on {@code close()}.
 * Access from the runtime must go through a volatile snapshot to ensure visibility across threads.
 */
public final class TextQueryOps {
  private static final Logger log = LoggerFactory.getLogger(TextQueryOps.class);

  static final int MIN_PREFIX_LENGTH = 3;
  static final float EXACT_BOOST = 2.0f;
  /** 306-B1: title field boost in DisjunctionMaxQuery (industry standard 1.5-3x). */
  static final float TITLE_BOOST = 3.0f;
  /** 326: author/sender field boost — matches on document author are a strong relevance signal. */
  static final float AUTHOR_BOOST = 3.0f;
  /** 306-B1: tie-breaker for DisjunctionMaxQuery — 10% of secondary field score added. */
  static final float TITLE_TIE_BREAKER = 0.1f;

  private final RuntimeSession session;
  private final SearcherBridge bridge;
  private final ReadPathOps readPathOps;

  TextQueryOps(RuntimeSession session, SearcherBridge bridge, ReadPathOps readPathOps) {
    this.session = session;
    this.bridge = bridge;
    this.readPathOps = readPathOps;
  }

  /** Result of building a corrected query, pairing the Lucene query with the corrected text. */
  public record CorrectedQuery(Query query, String correctedText) {}

  /**
   * Builds the Lucene query used for user-entered TEXT searches, including structured filters.
   * Convenience overload defaulting to {@link QuerySyntax#SIMPLE}.
   */
  public Query buildTextQuery(String queryText, RuntimeSearchFilters filters)
      throws org.apache.lucene.queryparser.classic.ParseException {
    return buildTextQuery(queryText, filters, QuerySyntax.SIMPLE);
  }

  /**
   * Builds the Lucene query used for user-entered TEXT searches, including structured filters.
   *
   * <p>When {@code syntax} is {@link QuerySyntax#SIMPLE}, user input is escaped (operators treated
   * as literal). When {@code syntax} is {@link QuerySyntax#LUCENE}, the query is parsed as Lucene
   * syntax (phrases/boolean/field qualifiers).
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public Query buildTextQuery(String queryText, RuntimeSearchFilters filters, QuerySyntax syntax)
      throws org.apache.lucene.queryparser.classic.ParseException {
    if (queryText == null || queryText.isBlank()) {
      return null;
    }

    QuerySyntax effective = (syntax == null) ? QuerySyntax.SIMPLE : syntax;
    return applyRuntimeFilters(buildMultiFieldQuery(queryText, effective), filters);
  }

  /**
   * Builds the content+title+author DisjunctionMaxQuery for {@code syntax}, without any
   * filters. Shared by {@link #buildTextQuery} and {@link #searchTextWithFilter} so the two cannot
   * drift on which fields participate or how each is parsed.
   */
  private Query buildMultiFieldQuery(String queryText, QuerySyntax requestedSyntax)
      throws org.apache.lucene.queryparser.classic.ParseException {
    QuerySyntax syntax = (requestedSyntax == null) ? QuerySyntax.SIMPLE : requestedSyntax;
    Query contentQuery;
    Query titleQuery;
    if (syntax == QuerySyntax.SIMPLE) {
      contentQuery = buildSimpleContentQuery(queryText);
      titleQuery = buildFieldQuery(queryText, SchemaFields.TITLE, QuerySyntax.SIMPLE);
    } else {
      contentQuery = buildFieldQuery(queryText, SchemaFields.CONTENT, QuerySyntax.LUCENE);
      titleQuery = buildFieldQuery(queryText, SchemaFields.TITLE, QuerySyntax.LUCENE);
    }

    // 326: Author/sender field query — matches on document author boost relevance.
    Query authorQuery = buildFieldQuery(queryText, SchemaFields.AUTHOR, syntax);

    // 306-B1 + 326: Multi-field search — query content, title, and author using
    // DisjunctionMaxQuery (Elasticsearch best_fields pattern). Best field score drives ranking,
    // with tie-breaker from other fields. Documents without titles/author are unaffected.
    return combineMultiField(contentQuery, titleQuery, authorQuery);
  }

  /**
   * Builds a content query for SIMPLE mode: escapes user input, parses it, and adds prefix
   * expansion on the last analyzed token so partial words match (e.g. "justsearc" matches
   * "justsearch"). This method does NOT apply runtime filters -- callers must do so separately or
   * combine with a pre-built filter Query.
   */
  Query buildSimpleContentQuery(String queryText)
      throws org.apache.lucene.queryparser.classic.ParseException {
    org.apache.lucene.queryparser.classic.QueryParser parser =
        new org.apache.lucene.queryparser.classic.QueryParser(
            SchemaFields.CONTENT, session.snapshot.indexAnalyzer());
    parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
    parser.setAllowLeadingWildcard(false);
    String escapedInput = org.apache.lucene.queryparser.classic.QueryParser.escape(queryText);
    Query contentQuery = parser.parse(escapedInput);
    return withPrefixExpansion(contentQuery, queryText);
  }

  /**
   * Builds a parsed query against a specific field. Used for title-field queries (306-B1).
   * For SIMPLE mode, escapes user input; for LUCENE mode, parses raw.
   */
  private Query buildFieldQuery(String queryText, String field, QuerySyntax syntax)
      throws org.apache.lucene.queryparser.classic.ParseException {
    org.apache.lucene.queryparser.classic.QueryParser parser =
        new org.apache.lucene.queryparser.classic.QueryParser(field, session.snapshot.indexAnalyzer());
    parser.setDefaultOperator(org.apache.lucene.queryparser.classic.QueryParser.Operator.OR);
    parser.setAllowLeadingWildcard(false);
    String input = (syntax == QuerySyntax.SIMPLE)
        ? org.apache.lucene.queryparser.classic.QueryParser.escape(queryText)
        : queryText;
    return parser.parse(input);
  }

  /**
   * Wraps content, title, and author queries in a {@link DisjunctionMaxQuery} so that the best
   * field's score drives ranking, with a small tie-breaker from other fields. Title is boosted by
   * {@link #TITLE_BOOST} and author by {@link #AUTHOR_BOOST}.
   */
  static Query combineMultiField(Query contentQuery, Query titleQuery, Query authorQuery) {
    float titleBoost = resolveTitleBoost();

    boolean hasTitle = titleQuery != null && titleBoost > 0.0f;
    boolean hasAuthor = authorQuery != null && AUTHOR_BOOST > 0.0f;

    if (!hasTitle && !hasAuthor) return contentQuery;

    List<Query> disjuncts = new ArrayList<>();
    disjuncts.add(contentQuery);
    if (hasTitle) {
      disjuncts.add(new BoostQuery(titleQuery, titleBoost));
    }
    if (hasAuthor) {
      disjuncts.add(new BoostQuery(authorQuery, AUTHOR_BOOST));
    }
    return new DisjunctionMaxQuery(disjuncts, TITLE_TIE_BREAKER);
  }

  /** Reads title boost from ConfigStore, falling back to TITLE_BOOST constant. */
  private static float resolveTitleBoost() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs != null) {
      return (float) cs.get().search().titleBoost();
    }
    return TITLE_BOOST;
  }

  /**
   * Builds a fuzzy Lucene query for zero-hit retry (typo correction).
   *
   * <p>Analyzes the query text into tokens using the index analyzer, then resolves the closest
   * indexed term for each token within the specified edit distance. Used by the in-process port search service
   * when the primary query returns 0 hits and corrections are enabled.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorrectedQuery buildFuzzyTextQuery(
      String queryText, RuntimeSearchFilters filters, int maxEditDistance) {
    if (queryText == null || queryText.isBlank()) {
      return null;
    }

    List<String> tokens = analyzeToTokens(queryText);
    if (tokens.isEmpty()) {
      return null;
    }

    int clampedEdit = Math.max(0, Math.min(maxEditDistance, 2));

    try {
      return withSearcher(
          searcher -> {
            IndexReader reader = searcher.getIndexReader();
            List<String> resolvedTokens = new ArrayList<>();

            for (String token : tokens) {
              String resolved =
                  resolveClosestTerm(reader, SchemaFields.CONTENT, token, clampedEdit);
              if (resolved != null) {
                resolvedTokens.add(resolved);
              }
            }

            if (resolvedTokens.isEmpty()) {
              return null;
            }
            String correctedText = String.join(" ", resolvedTokens);
            try {
              Query correctedQuery = buildSimpleContentQuery(correctedText);
              return new CorrectedQuery(
                  applyRuntimeFilters(correctedQuery, filters), correctedText);
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
              log.warn(
                  "Failed to parse corrected query '{}': {}", correctedText, e.getMessage());
              return null;
            }
          });
    } catch (IOException e) {
      log.warn("Fuzzy text query build failed: {}", e.getMessage());
      log.debug("Fuzzy text query build failed (stack trace)", e);
      return null;
    }
  }

  /**
   * Builds a per-term corrected query: tokens found in the index use exact match, tokens with zero
   * document frequency are resolved to the closest indexed term via fuzzy matching.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public CorrectedQuery buildPerTermFuzzyQuery(
      String queryText, RuntimeSearchFilters filters, int maxEditDistance) {
    if (queryText == null || queryText.isBlank()) {
      return null;
    }

    List<String> tokens = analyzeToTokens(queryText);
    if (tokens.isEmpty()) {
      return null;
    }

    int clampedEdit = Math.max(0, Math.min(maxEditDistance, 2));

    try {
      return withSearcher(
          searcher -> {
            IndexReader reader = searcher.getIndexReader();
            List<String> correctedTokens = new ArrayList<>();
            boolean anyCorrected = false;

            for (String token : tokens) {
              Term t = new Term(SchemaFields.CONTENT, token);
              int df = reader.docFreq(t);
              if (df == 0) {
                String resolved =
                    resolveClosestTerm(reader, SchemaFields.CONTENT, token, clampedEdit);
                if (resolved != null) {
                  correctedTokens.add(resolved);
                  anyCorrected = true;
                }
              } else {
                correctedTokens.add(token);
              }
            }

            if (!anyCorrected || correctedTokens.isEmpty()) {
              return null;
            }
            String correctedText = String.join(" ", correctedTokens);
            try {
              Query correctedQuery = buildSimpleContentQuery(correctedText);
              return new CorrectedQuery(
                  applyRuntimeFilters(correctedQuery, filters), correctedText);
            } catch (org.apache.lucene.queryparser.classic.ParseException e) {
              log.warn(
                  "Failed to parse corrected query '{}': {}", correctedText, e.getMessage());
              return null;
            }
          });
    } catch (IOException e) {
      log.warn("Per-term fuzzy query build failed: {}", e.getMessage());
      log.debug("Per-term fuzzy query build failed (stack trace)", e);
      return null;
    }
  }

  /** Runs text through the index analyzer and collects resulting tokens. */
  List<String> analyzeToTokens(String text) {
    List<String> result = new ArrayList<>();
    try (TokenStream stream =
        session.snapshot.indexAnalyzer().tokenStream(SchemaFields.CONTENT, text)) {
      CharTermAttribute attr = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        result.add(attr.toString());
      }
      stream.end();
    } catch (IOException e) {
      log.warn("Analyzer failed during prefix expansion: {}", e.getMessage());
      log.debug("Analyzer failed during prefix expansion (stack trace)", e);
    }
    return result;
  }

  /** Standard Levenshtein edit distance (insert, delete, substitute). */
  static int levenshteinDistance(String a, String b) {
    int lenA = a.length();
    int lenB = b.length();
    int[][] dp = new int[lenA + 1][lenB + 1];
    for (int i = 0; i <= lenA; i++) {
      dp[i][0] = i;
    }
    for (int j = 0; j <= lenB; j++) {
      dp[0][j] = j;
    }
    for (int i = 1; i <= lenA; i++) {
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= lenB; j++) {
        int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
        dp[i][j] =
            Math.min(
                Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
      }
    }
    return dp[lenA][lenB];
  }

  /**
   * Finds the indexed term closest to {@code token} within {@code maxEdits} Levenshtein distance.
   * Prefers lower edit distance; breaks ties by higher document frequency. Returns null if no term
   * is within range.
   */
  String resolveClosestTerm(IndexReader reader, String field, String token, int maxEdits)
      throws IOException {
    Terms terms = MultiTerms.getTerms(reader, field);
    if (terms == null) {
      return null;
    }

    TermsEnum iterator = terms.iterator();
    BytesRef termRef;
    String bestTerm = null;
    int bestDistance = Integer.MAX_VALUE;
    int bestDf = 0;

    while ((termRef = iterator.next()) != null) {
      String candidate = termRef.utf8ToString();
      int distance = levenshteinDistance(candidate, token);
      if (distance <= maxEdits
          && (distance < bestDistance
              || (distance == bestDistance && iterator.docFreq() > bestDf))) {
        bestDistance = distance;
        bestTerm = candidate;
        bestDf = iterator.docFreq();
      }
    }
    return bestTerm;
  }

  /**
   * ORs the exact query with a {@link PrefixQuery} on the last analyzed token so that partial word
   * input matches indexed terms (e.g. "justsearc" matches "justsearch"). The exact query is boosted
   * so complete-word hits rank above prefix-only hits.
   *
   * <p>Only the last token is prefix-expanded (standard search-as-you-type convention). Expansion
   * is skipped when the last analyzed token is shorter than {@code MIN_PREFIX_LENGTH} to avoid
   * overly broad matches from degenerate tokens like "c" (from "C++").
   */
  Query withPrefixExpansion(Query exactQuery, String rawInput) {
    String trimmed = rawInput.trim();
    if (trimmed.isEmpty()) {
      return exactQuery;
    }

    // Analyze the last whitespace-delimited word through the index analyzer to
    // obtain the same token form stored in the index.
    int lastWs = -1;
    for (int i = trimmed.length() - 1; i >= 0; i--) {
      if (Character.isWhitespace(trimmed.charAt(i))) {
        lastWs = i;
        break;
      }
    }
    String lastWord = lastWs < 0 ? trimmed : trimmed.substring(lastWs + 1);

    List<String> tokens = analyzeToTokens(lastWord);
    if (tokens.isEmpty()) {
      return exactQuery;
    }
    String lastToken = tokens.get(tokens.size() - 1);
    if (lastToken.length() < MIN_PREFIX_LENGTH) {
      return exactQuery;
    }

    // Use constant-score rewrite for high-fanout prefixes to avoid BooleanQuery clause
    // explosions (TooManyClauses) on large corpora while keeping prefix recall.
    PrefixQuery prefixQuery =
        new PrefixQuery(
            new Term(SchemaFields.CONTENT, lastToken),
            MultiTermQuery.CONSTANT_SCORE_REWRITE);

    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    builder.add(new BoostQuery(exactQuery, EXACT_BOOST), BooleanClause.Occur.SHOULD);
    builder.add(prefixQuery, BooleanClause.Occur.SHOULD);
    return builder.build();
  }

  /**
   * Builds a SPLADE sparse retrieval query using FeatureField.
   *
   * @param queryWeights SPLADE sparse vector mapping tokens to weights
   * @param filters optional runtime filters (may be null)
   * @return the combined Lucene query, or null if weights are empty
   */
  public Query buildSpladeQuery(Map<String, Float> queryWeights, LuceneRuntimeTypes.RuntimeSearchFilters filters) {
    if (queryWeights == null || queryWeights.isEmpty()) {
      return null;
    }

    var builder = new BooleanQuery.Builder();
    for (var entry : queryWeights.entrySet()) {
      builder.add(
          org.apache.lucene.document.FeatureField.newLinearQuery(
              "splade", entry.getKey(), entry.getValue()),
          BooleanClause.Occur.SHOULD);
    }
    Query spladeQuery = builder.build();

    // Exclude chunk documents — chunk SPLADE is handled by searchChunksSplade (Score-max)
    spladeQuery =
        new BooleanQuery.Builder()
            .add(spladeQuery, BooleanClause.Occur.MUST)
            .add(
                new org.apache.lucene.search.TermQuery(
                    new Term(SchemaFields.IS_CHUNK, "true")),
                BooleanClause.Occur.MUST_NOT)
            .build();

    Query filter = QueryFilterBuilder.buildFilterQueryOnly(filters);
    if (filter != null) {
      spladeQuery =
          new BooleanQuery.Builder()
              .add(spladeQuery, BooleanClause.Occur.MUST)
              .add(filter, BooleanClause.Occur.FILTER)
              .build();
    }

    return spladeQuery;
  }

  /** Provides searcher access for fuzzy term resolution. */
  <T> T withSearcher(ReadPathOps.SearcherOperation<T> op) throws IOException {
    return bridge.withSearcher(op);
  }

  /**
   * Returns per-term document frequencies for the given terms in the specified field.
   *
   * <p>Used for IDF-based excerpt region scoring.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public Map<String, Integer> getTermDocFreqs(String field, Collection<String> terms) {
    if (terms == null || terms.isEmpty()) return Map.of();
    try {
      return bridge.withSearcher(searcher -> {
        IndexReader reader = searcher.getIndexReader();
        Map<String, Integer> result = new HashMap<>();
        for (String term : terms) {
          result.put(term, reader.docFreq(new Term(field, term)));
        }
        return result;
      });
    } catch (IOException e) {
      return Map.of();
    }
  }

  /**
   * Returns per-term QPP (Query Performance Prediction) signals for the given terms in one searcher
   * acquisition.
   *
   * <p>Collects docFreq, totalTermFreq per term, plus field statistics (fieldDocCount,
   * sumTotalTermFreq) needed to compute MaxIDF, AvgICTF, and QueryScope.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public QppSignals getQppSignals(String field, Collection<String> terms) {
    if (terms == null || terms.isEmpty()) {
      return new QppSignals(0L, Map.of(), Map.of(), 0L);
    }
    try {
      return bridge.withSearcher(
          searcher -> {
            IndexReader reader = searcher.getIndexReader();
            long fieldDocCount = reader.getDocCount(field);

            // Sum total term frequency across all leaves for the field
            long sumTotal = 0;
            for (LeafReaderContext lctx : reader.leaves()) {
              Terms fieldTerms = lctx.reader().terms(field);
              if (fieldTerms != null) {
                sumTotal += fieldTerms.getSumTotalTermFreq();
              }
            }

            Map<String, Integer> dfs = new HashMap<>();
            Map<String, Long> cfs = new HashMap<>();
            for (String term : terms) {
              Term t = new Term(field, term);
              dfs.put(term, reader.docFreq(t));
              cfs.put(term, reader.totalTermFreq(t));
            }
            return new QppSignals(fieldDocCount, dfs, cfs, sumTotal);
          });
    } catch (IOException e) {
      return new QppSignals(0L, Map.of(), Map.of(), 0L);
    }
  }

  /**
   * Executes a text search with optional structured filters, absorbing ParseException.
   *
   * <p>Combines buildTextQuery + searchFn + ParseException handling into a single method,
   * eliminating duplicated try/catch boilerplate from the facade.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  public SearchResult searchText(String queryText, int limit, RuntimeSearchFilters filters) {
    return searchText(queryText, limit, filters, null, QuerySyntax.SIMPLE);
  }

  /**
   * Text search with optional soft-boost filters (363), parsing the user's text with {@code syntax}.
   *
   * <p>Tempdoc 821 §P / register F-046: the multi-leg lexical leg passes the REQUEST's syntax here — a
   * {@code query_syntax: "lucene"} request retrieves via a LUCENE parse on this leg, exactly as the
   * sparse-only shortcut always has. Whatever value the caller passes must also be the value used by
   * anything that re-derives a COUNT over this leg's matched population (the facet scan and the
   * headline match count in {@code SearchResponseBuilder}); the coupling is per-request now, carried
   * by {@code SearchDecision.MultiLegDecision.runtimeSyntax()}.
   *
   * <p>A malformed LUCENE query degrades to an empty result here rather than throwing out of the leg
   * (legs run inside fusion futures). The multi-leg executor pre-validates LUCENE syntax and mirrors
   * the sparse shortcut's {@code INVALID_ARGUMENT} signal instead, so the empty-result branch is the
   * defensive floor, not the user-visible behaviour.
   */
  public SearchResult searchText(
      String queryText,
      int limit,
      RuntimeSearchFilters filters,
      RuntimeSearchFilters boostFilters,
      QuerySyntax syntax) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0, null);
    }
    try {
      Query combined = buildTextQuery(queryText, filters, syntax);
      if (boostFilters != null && combined != null) {
        var qb = new BooleanQuery.Builder();
        qb.add(combined, BooleanClause.Occur.MUST);
        QueryFilterBuilder.applyBoostFilters(
            qb, boostFilters, QueryFilterBuilder.DEFAULT_BOOST_WEIGHT);
        combined = qb.build();
      }
      return readPathOps.search(combined, limit, null, RuntimeSearchSort.RELEVANCE, null);
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse query", e);
      log.debug("Failed query text: {}", queryText);
      return new SearchResult(List.of(), 0, 0, null);
    }
  }

  /**
   * Text search with a Lucene Query filter applied, parsing the user's text with {@code syntax}.
   *
   * <p>Builds the full multi-field query (content + title + author) with {@code syntax}, then
   * combines with the provided Lucene filter using a BooleanQuery. This ensures the 2-leg hybrid
   * path (HybridSearchOps) gets the same field boosts as the direct text search path — and, since
   * tempdoc 821 §P, the same honouring of the request's {@code query_syntax}.
   *
   * <p>Does NOT call {@code ensureStarted()} — caller (facade) is responsible for that guard.
   */
  SearchResult searchTextWithFilter(String queryText, int limit, Query filter, QuerySyntax syntax) {
    if (queryText == null || queryText.isBlank()) {
      return new SearchResult(List.of(), 0, 0, null);
    }
    try {
      // Use the shared multi-field build (content + title + author) instead of content-only. The
      // old code called buildSimpleContentQuery(), causing the 2-leg hybrid path to rank
      // differently from the direct text search and 3-way paths.
      Query multiFieldQuery = buildMultiFieldQuery(queryText, syntax);

      BooleanQuery.Builder combined = new BooleanQuery.Builder();
      combined.add(multiFieldQuery, BooleanClause.Occur.MUST);
      combined.add(filter, BooleanClause.Occur.FILTER);
      return readPathOps.search(combined.build(), limit, null, RuntimeSearchSort.RELEVANCE, null);
    } catch (org.apache.lucene.queryparser.classic.ParseException e) {
      log.warn("Failed to parse query for filtered text search", e);
      log.debug("Failed query text: {}", queryText);
      return new SearchResult(List.of(), 0, 0, null);
    }
  }
}
