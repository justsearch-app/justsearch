/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.util;

import io.justsearch.indexing.SchemaFields;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

/**
 * Utility methods for text analysis and query term processing.
 *
 * <p>Extracted from WorkerSearchService for reusability and testability.
 */
public final class TextAnalysisUtils {

  private TextAnalysisUtils() {
    // Utility class - no instantiation
  }

  /**
   * Extracts normalized query terms from a query string.
   *
   * <p>Splits on non-alphanumeric characters, lowercases, filters short terms, and caps at 10
   * terms.
   *
   * @param queryString the query string (may be null)
   * @return set of normalized terms, or empty set if input is null/blank
   */
  public static Set<String> normalizedQueryTerms(String queryString) {
    if (queryString == null) return Set.of();
    String q = queryString.trim().toLowerCase(Locale.ROOT);
    if (q.isBlank()) return Set.of();
    Set<String> out = new HashSet<>();
    for (String part : q.split("[^\\p{L}\\p{Nd}]+")) {
      if (part == null) continue;
      String t = part.trim();
      if (t.length() < 2) continue;
      out.add(t);
      if (out.size() >= 10) break; // cap noise
    }
    return out.isEmpty() ? Set.of() : Set.copyOf(out);
  }

  /**
   * Analyzes text using a Lucene analyzer to extract terms.
   *
   * @param analyzer the Lucene analyzer (may be null)
   * @param field the field name for analysis
   * @param text the text to analyze (may be null)
   * @return set of analyzed terms, or empty set on failure
   */
  public static Set<String> analyzeTerms(Analyzer analyzer, String field, String text) {
    if (analyzer == null || text == null || text.isBlank()) return Set.of();
    HashSet<String> terms = new HashSet<>();
    try (TokenStream ts = analyzer.tokenStream(field, text)) {
      CharTermAttribute termAttr = ts.addAttribute(CharTermAttribute.class);
      ts.reset();
      while (ts.incrementToken()) {
        String t = termAttr.toString();
        if (t == null || t.isBlank()) continue;
        if (t.length() < 2) continue;
        terms.add(t);
        if (terms.size() >= 10) break;
      }
      ts.end();
    } catch (Exception e) {
      // Best-effort; fall back to normalized splitter.
      return normalizedQueryTerms(text);
    }
    return terms.isEmpty() ? Set.of() : Set.copyOf(terms);
  }

  /**
   * Computes which fields matched the query.
   *
   * @param mode the search mode
   * @param queryTerms the query terms
   * @param fields the document fields
   * @return list of matched field names
   */
  public static List<String> computeMatchedFields(
      boolean hasLexicalQuery, Set<String> queryTerms, Map<String, String> fields) {
    if (!hasLexicalQuery) {
      return List.of("vector");
    }
    if (queryTerms == null || queryTerms.isEmpty() || fields == null || fields.isEmpty()) {
      return List.of();
    }

    List<String> matched = new ArrayList<>();

    String title = fields.get(SchemaFields.TITLE);
    if (containsAny(title, queryTerms)) {
      matched.add(SchemaFields.TITLE);
    }

    String path = fields.get(SchemaFields.PATH);
    if (containsAny(path, queryTerms)) {
      matched.add(SchemaFields.PATH);
    }

    // Prefer marking snippet match when possible; otherwise fall back to "content" (TEXT/HYBRID).
    String preview = fields.get(SchemaFields.CONTENT_PREVIEW);
    if (containsAny(preview, queryTerms)) {
      matched.add(SchemaFields.CONTENT_PREVIEW);
    } else {
      String content = fields.get(SchemaFields.CONTENT);
      if (containsAny(content, queryTerms)) {
        matched.add(SchemaFields.CONTENT);
      }
    }

    // If nothing matched explicitly (e.g. content field not stored), fall back to "content"
    // since the document was returned by the search engine — something must have matched.
    if (matched.isEmpty()) {
      matched.add(SchemaFields.CONTENT);
    }

    return matched;
  }

  /**
   * Checks if text contains any of the given terms (case-insensitive).
   *
   * @param text the text to search (may be null)
   * @param terms the terms to look for (may be null)
   * @return true if any term is found in text
   */
  public static boolean containsAny(String text, Set<String> terms) {
    if (text == null || text.isBlank() || terms == null || terms.isEmpty()) return false;
    String lower = text.toLowerCase(Locale.ROOT);
    for (String t : terms) {
      if (t == null || t.isBlank()) continue;
      if (lower.contains(t)) return true;
    }
    return false;
  }
}
