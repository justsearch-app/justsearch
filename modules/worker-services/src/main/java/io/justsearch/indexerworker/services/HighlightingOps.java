/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.util.TextAnalysisUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.ExcerptRegion;
import io.justsearch.ipc.MatchSpan;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.memory.MemoryIndex;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Matches;
import org.apache.lucene.search.MatchesIterator;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.Weight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Static utility methods for computing match spans and excerpt regions.
 *
 * <p>Extracted from {@link WorkerSearchService} — all methods are pure functions
 * operating on Lucene Analyzer/Query primitives with no runtime state.
 */
public final class HighlightingOps {
  private static final Logger log = LoggerFactory.getLogger(HighlightingOps.class);

  private HighlightingOps() {}

  /** Match offset tagged with the analyzed term that produced it. */
  public record TermMatch(int startOffset, int endOffset, String term) {}

  /** Cluster of nearby matches with per-term frequency tracking. */
  public record MatchCluster(int startOffset, int endOffset, Map<String, Integer> termFreqs) {}

  /**
   * Computes match spans using Lucene query semantics (phrases/boolean), without requiring the
   * underlying index to store offsets.
   *
   * <p>Implementation detail: we build a per-hit {@link MemoryIndex} from the stored field values
   * and use Lucene {@link Matches} APIs to obtain char offsets.
   */
  public static List<MatchSpan> computeMatchSpansFromQuery(
      Analyzer analyzer, org.apache.lucene.search.Query query, Map<String, String> fields) {
    if (analyzer == null || query == null || fields == null || fields.isEmpty()) return List.of();

    String preview = fields.get(SchemaFields.CONTENT_PREVIEW);
    String title = fields.get(SchemaFields.TITLE);

    ArrayList<MatchSpan> out = new ArrayList<>();
    addMatchSpansFromMemoryIndex(out, analyzer, query, SchemaFields.CONTENT_PREVIEW, preview, List.of(SchemaFields.CONTENT), 20);
    // UX: also highlight title for default (content) queries, while still supporting title:... queries.
    addMatchSpansFromMemoryIndex(
        out, analyzer, query, SchemaFields.TITLE, title, List.of(SchemaFields.CONTENT, SchemaFields.TITLE), 12);
    return out.isEmpty() ? List.of() : List.copyOf(out);
  }

  /**
   * Scores a cluster for excerpt region selection using BM25-inspired weighting.
   * Higher is better. Combines term diversity, IDF, tf saturation, and position bias.
   */
  public static double scoreCluster(
      MatchCluster cluster, Map<String, Double> termIdfWeights, int contentLength) {
    double score = 0.0;
    for (var entry : cluster.termFreqs().entrySet()) {
      double idf = termIdfWeights.getOrDefault(entry.getKey(), 1.0);
      double tf = entry.getValue();
      score += idf * (tf / (tf + 1.2)); // BM25 k1=1.2
    }
    // Position bias: 0-15% bonus for earlier passages (tiebreaker only).
    if (contentLength > 0) {
      double posFrac = (double) (cluster.startOffset() + cluster.endOffset()) / 2.0 / contentLength;
      score *= 1.0 + 0.15 * (1.0 - posFrac);
    }
    return score;
  }

  /**
   * Computes query-focused excerpt regions from full document content.
   * Finds match positions via MemoryIndex, clusters nearby matches, scores clusters
   * using BM25-style term diversity + IDF + tf saturation + position bias, extracts
   * text windows, and returns the top-N highest-scoring non-overlapping regions.
   */
  public static List<ExcerptRegion> computeExcerptRegions(
      Analyzer analyzer, org.apache.lucene.search.Query query, String content, int maxRegions,
      Map<String, Double> termIdfWeights) {
    if (analyzer == null || query == null || content == null || content.isEmpty()) return List.of();
    if (maxRegions <= 0) return List.of();

    try {
      // 1. Find all match positions in full content using MemoryIndex.
      ArrayList<TermMatch> matchOffsets = new ArrayList<>();
      collectMatchOffsets(matchOffsets, analyzer, query, content, 200);
      if (matchOffsets.isEmpty()) return List.of();

      // 2. Cluster nearby matches (within 400 chars), tracking per-term frequencies.
      List<MatchCluster> clusters = buildClusters(matchOffsets);

      // 3. Score and rank clusters. Extract ±200 char windows snapped to sentence boundaries.
      Map<String, Double> idf = termIdfWeights != null ? termIdfWeights : Map.of();
      int contentLen = content.length();
      HashMap<MatchCluster, Double> clusterScores = new HashMap<>();
      for (MatchCluster c : clusters) {
        clusterScores.put(c, scoreCluster(c, idf, contentLen));
      }
      ArrayList<MatchCluster> ranked = new ArrayList<>(clusters);
      ranked.sort((a, b) -> Double.compare(clusterScores.get(b), clusterScores.get(a)));

      BreakIterator sentenceBreaker = BreakIterator.getSentenceInstance(Locale.ROOT);
      sentenceBreaker.setText(content);

      ArrayList<ExcerptRegion> regions = new ArrayList<>();
      for (MatchCluster cluster : ranked) {
        if (regions.size() >= maxRegions) break;
        WindowGeom w = projectWindow(content, cluster, sentenceBreaker);
        if (overlapsSelected(w.winStart(), w.winEnd(), regions)) continue;

        String excerptText = content.substring(w.winStart(), w.winEnd());
        ExcerptRegion.Builder rb = ExcerptRegion.newBuilder()
            .setText(excerptText)
            .setStartChar(w.winStart())
            .setEndChar(w.winEnd())
            .setApproxLine(w.approxLine());
        for (MatchSpan span : windowMatchSpans(excerptText, w.winStart(), matchOffsets)) {
          rb.addMatchSpans(span);
        }
        regions.add(rb.build());
      }

      // Sort by document position for natural reading order.
      regions.sort(java.util.Comparator.comparingInt(ExcerptRegion::getStartChar));
      return List.copyOf(regions);
    } catch (Exception e) {
      log.debug("Excerpt region computation failed: {}", e.getMessage());
      return List.of();
    }
  }

  /** Sentence-snapped window geometry for one cluster (extracted for reuse; tempdoc 775). */
  public record WindowGeom(int winStart, int winEnd, int approxLine) {}

  /**
   * Clusters match offsets within 400 chars, tracking per-term frequencies. Extracted verbatim from
   * {@link #computeExcerptRegions} so {@code EvidenceSpanSelector} (tempdoc 775) reuses the same
   * candidate windows without re-scanning content. Sorts {@code matchOffsets} in place by start.
   */
  public static List<MatchCluster> buildClusters(List<TermMatch> matchOffsets) {
    ArrayList<MatchCluster> clusters = new ArrayList<>();
    if (matchOffsets.isEmpty()) return clusters;
    matchOffsets.sort(java.util.Comparator.comparingInt(TermMatch::startOffset));
    int cStart = matchOffsets.get(0).startOffset();
    int cEnd = matchOffsets.get(0).endOffset();
    HashMap<String, Integer> cTermFreqs = new HashMap<>();
    cTermFreqs.merge(matchOffsets.get(0).term(), 1, Integer::sum);
    for (int i = 1; i < matchOffsets.size(); i++) {
      TermMatch m = matchOffsets.get(i);
      if (m.startOffset() - cEnd <= 400) {
        cEnd = Math.max(cEnd, m.endOffset());
        cTermFreqs.merge(m.term(), 1, Integer::sum);
      } else {
        clusters.add(new MatchCluster(cStart, cEnd, Map.copyOf(cTermFreqs)));
        cStart = m.startOffset();
        cEnd = m.endOffset();
        cTermFreqs = new HashMap<>();
        cTermFreqs.merge(m.term(), 1, Integer::sum);
      }
    }
    clusters.add(new MatchCluster(cStart, cEnd, Map.copyOf(cTermFreqs)));
    return clusters;
  }

  /**
   * Projects a ±200-char window around a cluster center, snapped to sentence (then word) boundaries,
   * and computes its approximate 1-based line. Extracted verbatim from {@link #computeExcerptRegions}
   * (tempdoc 775) — the geometry both the IDF-only delivery path and the evidence-span selector share.
   */
  public static WindowGeom projectWindow(String content, MatchCluster cluster, BreakIterator sentenceBreaker) {
    int contentLen = content.length();
    int center = (cluster.startOffset() + cluster.endOffset()) / 2;
    int winStart = Math.max(0, center - 200);
    int winEnd = Math.min(contentLen, center + 200);

    // Snap to sentence boundaries (limit growth to ±80 chars to avoid huge excerpts).
    if (winStart > 0) {
      int sentStart = sentenceBreaker.preceding(winStart);
      if (sentStart != BreakIterator.DONE && winStart - sentStart <= 80) {
        winStart = sentStart;
      } else {
        // Fallback: snap to word boundary.
        int scan = winStart;
        while (scan > winStart - 40 && scan > 0 && !Character.isWhitespace(content.charAt(scan))) {
          scan--;
        }
        if (scan > 0 && Character.isWhitespace(content.charAt(scan))) winStart = scan + 1;
      }
    }
    if (winEnd < contentLen) {
      int sentEnd = sentenceBreaker.following(winEnd);
      if (sentEnd != BreakIterator.DONE && sentEnd - winEnd <= 80) {
        winEnd = sentEnd;
      } else {
        // Fallback: snap to word boundary.
        int scan = winEnd;
        while (scan < winEnd + 40 && scan < contentLen && !Character.isWhitespace(content.charAt(scan))) {
          scan++;
        }
        if (scan < contentLen) winEnd = scan;
      }
    }

    int approxLine = 1;
    for (int i = 0; i < winStart && i < contentLen; i++) {
      if (content.charAt(i) == '\n') approxLine++;
    }
    return new WindowGeom(winStart, winEnd, approxLine);
  }

  /**
   * True if [winStart,winEnd) overlaps &gt;50% with an already-selected region. Extracted verbatim
   * from {@link #computeExcerptRegions} (the safety-valve guard). With current params separate
   * clusters are ≥407 chars apart so max overlap is ~27% — not currently reachable, retained for
   * future parameter changes.
   */
  static boolean overlapsSelected(int winStart, int winEnd, List<ExcerptRegion> selected) {
    for (ExcerptRegion existing : selected) {
      int overlapStart = Math.max(winStart, existing.getStartChar());
      int overlapEnd = Math.min(winEnd, existing.getEndChar());
      if (overlapEnd > overlapStart) {
        int overlapLen = overlapEnd - overlapStart;
        int minLen = Math.min(winEnd - winStart, existing.getEndChar() - existing.getStartChar());
        if (minLen > 0 && overlapLen > minLen / 2) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Builds the match spans (offsets relative to the window text) for a window. Extracted verbatim
   * from {@link #computeExcerptRegions} (tempdoc 775) so delivery projection is shared.
   */
  public static List<MatchSpan> windowMatchSpans(String excerptText, int winStart, List<TermMatch> matchOffsets) {
    ArrayList<MatchSpan> out = new ArrayList<>();
    for (TermMatch mo : matchOffsets) {
      int relStart = mo.startOffset() - winStart;
      int relEnd = mo.endOffset() - winStart;
      if (relStart < 0 || relEnd > excerptText.length()) continue;
      if (relEnd <= relStart) continue;
      out.add(MatchSpan.newBuilder()
          .setField("content")
          .setStartChar(relStart)
          .setEndChar(relEnd)
          .setTerm(excerptText.substring(relStart, Math.min(relEnd, excerptText.length())))
          .build());
    }
    return out;
  }

  /**
   * Builds a simple OR query from analyzed terms when the parsed Lucene query is unavailable
   * (e.g., HYBRID mode where queryForSpans is null).
   */
  public static org.apache.lucene.search.Query buildTermQuery(Analyzer analyzer, String queryString) {
    if (analyzer == null || queryString == null || queryString.isBlank()) return null;
    Set<String> terms = TextAnalysisUtils.analyzeTerms(analyzer, SchemaFields.CONTENT, queryString);
    if (terms.isEmpty()) return null;
    var builder = new org.apache.lucene.search.BooleanQuery.Builder();
    for (String term : terms) {
      builder.add(
          new TermQuery(
              new org.apache.lucene.index.Term(SchemaFields.CONTENT, term)),
          org.apache.lucene.search.BooleanClause.Occur.SHOULD);
    }
    return builder.build();
  }

  /**
   * Collects match character offsets with term identity from content using MemoryIndex + Matches API.
   */
  public static void collectMatchOffsets(
      List<TermMatch> out, Analyzer analyzer, org.apache.lucene.search.Query query,
      String content, int maxOffsets) {
    try {
      MemoryIndex index = new MemoryIndex(true, false);
      index.addField(SchemaFields.CONTENT, content, analyzer);
      IndexSearcher searcher = index.createSearcher();
      org.apache.lucene.search.Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
      if (searcher.getIndexReader().leaves().isEmpty()) return;
      LeafReaderContext leaf = searcher.getIndexReader().leaves().get(0);
      Matches matches = weight.matches(leaf, 0);
      if (matches == null) return;

      MatchesIterator it = matches.getMatches(SchemaFields.CONTENT);
      if (it == null) return;
      while (it.next()) {
        int start = it.startOffset();
        int end = it.endOffset();
        if (start < 0 || end <= start) continue;
        if (start > content.length()) continue;
        if (end > content.length()) end = content.length();
        // Extract term identity from the leaf query for diversity scoring.
        String term;
        org.apache.lucene.search.Query leafQuery = it.getQuery();
        if (leafQuery instanceof TermQuery tq) {
          term = tq.getTerm().text();
        } else {
          term = content.substring(start, end).toLowerCase(Locale.ROOT);
        }
        out.add(new TermMatch(start, end, term));
        if (out.size() >= maxOffsets) break;
      }
    } catch (Exception e) {
      log.debug("collectMatchOffsets failed: {}", e.getMessage());
    }
  }

  private static void addMatchSpansFromMemoryIndex(
      List<MatchSpan> out,
      Analyzer analyzer,
      org.apache.lucene.search.Query query,
      String labelField,
      String content,
      List<String> queryFields,
      int maxSpans) {
    if (out == null) return;
    if (analyzer == null) return;
    if (query == null) return;
    if (content == null || content.isBlank()) return;
    if (queryFields == null || queryFields.isEmpty()) return;
    if (maxSpans <= 0) return;

    try {
      // Store offsets so MatchesIterator can return char offsets.
      MemoryIndex index = new MemoryIndex(true, false);
      for (String f : queryFields) {
        if (f == null || f.isBlank()) continue;
        index.addField(f, content, analyzer);
      }

      // MemoryIndex.createSearcher() returns a lightweight IndexSearcher backed by in-memory data.
      // No explicit close needed - MemoryIndex holds no OS resources.
      IndexSearcher searcher = index.createSearcher();
      org.apache.lucene.search.Query rewritten = searcher.rewrite(query);
      Weight weight = searcher.createWeight(rewritten, ScoreMode.COMPLETE_NO_SCORES, 1.0f);
      if (searcher.getIndexReader().leaves().isEmpty()) return;
      LeafReaderContext leaf = searcher.getIndexReader().leaves().get(0);
      Matches matches = weight.matches(leaf, 0);
      if (matches == null) return;

      ArrayList<int[]> spans = new ArrayList<>();
      int hardCap = Math.max(maxSpans, maxSpans * 4);
      for (String f : queryFields) {
        if (f == null || f.isBlank()) continue;
        MatchesIterator it = matches.getMatches(f);
        if (it == null) continue;
        while (it.next()) {
          int start = it.startOffset();
          int end = it.endOffset();
          if (start < 0 || end <= start) continue;
          int len = content.length();
          if (start > len) continue;
          if (end > len) end = len;
          spans.add(new int[] {start, end});
          if (spans.size() >= hardCap) break;
        }
        if (spans.size() >= hardCap) break;
      }

      if (spans.isEmpty()) return;
      spans.sort(java.util.Comparator.comparingInt((int[] s) -> s[0]).thenComparingInt((int[] s) -> s[1]));
      ArrayList<int[]> merged = new ArrayList<>();
      int curStart = spans.get(0)[0];
      int curEnd = spans.get(0)[1];
      for (int i = 1; i < spans.size(); i++) {
        int s = spans.get(i)[0];
        int e = spans.get(i)[1];
        if (s <= curEnd) {
          curEnd = Math.max(curEnd, e);
        } else {
          merged.add(new int[] {curStart, curEnd});
          curStart = s;
          curEnd = e;
          if (merged.size() >= maxSpans) break;
        }
      }
      if (merged.size() < maxSpans) {
        merged.add(new int[] {curStart, curEnd});
      }

      for (int[] se : merged) {
        if (se == null || se.length != 2) continue;
        int start = se[0];
        int end = se[1];
        if (start < 0 || end <= start) continue;
        out.add(
            MatchSpan.newBuilder()
                .setField(labelField)
                .setStartChar(start)
                .setEndChar(end)
                .setTerm(content.substring(start, Math.min(end, content.length())))
                .build());
        if (out.size() >= 32) break; // global cap
      }
    } catch (Exception e) {
      // Best-effort: never fail search due to highlight computation.
      log.debug("Highlight computation failed: {}", e.getMessage());
    }
  }

  public static List<MatchSpan> computeMatchSpans(
      Analyzer analyzer, String queryString, Map<String, String> fields) {
    if (analyzer == null || fields == null || fields.isEmpty()) return List.of();
    if (queryString == null || queryString.isBlank()) return List.of();

    // Prefer emitting spans for content_preview (used by UI snippet), then title.
    String preview = fields.get(SchemaFields.CONTENT_PREVIEW);
    String title = fields.get(SchemaFields.TITLE);

    Set<String> previewTerms = TextAnalysisUtils.analyzeTerms(analyzer, SchemaFields.CONTENT_PREVIEW, queryString);
    Set<String> titleTerms = TextAnalysisUtils.analyzeTerms(analyzer, SchemaFields.TITLE, queryString);

    ArrayList<MatchSpan> out = new ArrayList<>();
    addFieldSpans(out, analyzer, SchemaFields.CONTENT_PREVIEW, preview, previewTerms, 20);
    addFieldSpans(out, analyzer, SchemaFields.TITLE, title, titleTerms, 12);
    return out.isEmpty() ? List.of() : List.copyOf(out);
  }

  private static void addFieldSpans(
      List<MatchSpan> out,
      Analyzer analyzer,
      String field,
      String content,
      Set<String> queryTerms,
      int maxSpans) {
    if (out == null) return;
    if (analyzer == null) return;
    if (content == null || content.isBlank()) return;
    if (queryTerms == null || queryTerms.isEmpty()) return;
    if (maxSpans <= 0) return;

    int added = 0;
    try (TokenStream ts = analyzer.tokenStream(field, content)) {
      CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
      OffsetAttribute offAttr = ts.addAttribute(OffsetAttribute.class);
      ts.reset();
      while (ts.incrementToken()) {
        String t = termAttr.toString();
        if (!queryTerms.contains(t)) continue;
        int start = offAttr.startOffset();
        int end = offAttr.endOffset();
        if (start < 0 || end <= start) continue;
        // Clamp to the stored string length (safety).
        int len = content.length();
        if (start > len) continue;
        if (end > len) end = len;
        out.add(
            MatchSpan.newBuilder()
                .setField(field)
                .setStartChar(start)
                .setEndChar(end)
                .setTerm(t)
                .build());
        added++;
        if (added >= maxSpans) break;
      }
      ts.end();
    } catch (Exception e) {
      // Best-effort: skip spans for this field if tokenization fails.
    }
  }
}
