/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.text.TextQualityAnalyzer;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Compact JSON evidence used to explain and route OCR/VDU extraction decisions. */
public record VisualExtractionEvidence(
    int schemaVersion,
    int pageCount,
    int textCharCount,
    double textQualityScore,
    double charsPerPage,
    double alphanumericRatio,
    String ocrLanguage,
    Double ocrMeanConfidence,
    Integer ocrLowConfidenceWordCount,
    Integer ocrWordCount,
    int pagesWithTextLayer,
    int pagesMissingReadableText,
    boolean mixedPdf,
    int tableCount,
    int headingCount,
    int listCount,
    int imagePageCount,
    String layoutComplexity,
    Boolean contentTruncated,
    String ocrFallbackRoute,
    String ocrSkipReason,
    String route) {
  public static final int SCHEMA_VERSION = 1;
  public static final int MAX_JSON_CHARS = 8192;
  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  static VisualExtractionEvidence from(
      String content,
      StructuredDocumentSummary summary,
      String route,
      OcrRoutingConfig ocrConfig,
      boolean ocrRoute) {
    return from(content, summary, route, ocrConfig, ocrRoute, OcrConfidenceExtractor.Summary.empty());
  }

  public static VisualExtractionEvidence from(
      String content,
      StructuredDocumentSummary summary,
      String route,
      OcrRoutingConfig ocrConfig,
      boolean ocrRoute,
      OcrConfidenceExtractor.Summary ocrConfidence) {
    return from(content, summary, route, ocrConfig, ocrRoute, ocrConfidence, false);
  }

  public static VisualExtractionEvidence from(
      String content,
      StructuredDocumentSummary summary,
      String route,
      OcrRoutingConfig ocrConfig,
      boolean ocrRoute,
      OcrConfidenceExtractor.Summary ocrConfidence,
      boolean mixedPdfOverride) {
    return from(
        content,
        summary,
        route,
        ocrConfig,
        ocrRoute,
        ocrConfidence,
        mixedPdfOverride,
        RoutingFacts.empty());
  }

  public static VisualExtractionEvidence from(
      String content,
      StructuredDocumentSummary summary,
      String route,
      OcrRoutingConfig ocrConfig,
      boolean ocrRoute,
      OcrConfidenceExtractor.Summary ocrConfidence,
      boolean mixedPdfOverride,
      RoutingFacts facts) {
    StructuredDocumentSummary s = summary == null ? StructuredDocumentSummary.empty() : summary;
    String text = content == null ? "" : content;
    double quality = TextQualityAnalyzer.computeQualityScore(text, s.pageCount());
    double charsPerPage = s.pageCount() > 0 ? (double) text.length() / s.pageCount() : 0.0d;
    OcrConfidenceExtractor.Summary confidence =
        ocrConfidence == null ? OcrConfidenceExtractor.Summary.empty() : ocrConfidence;
    RoutingFacts f = facts == null ? RoutingFacts.empty() : facts;
    return new VisualExtractionEvidence(
        SCHEMA_VERSION,
        s.pageCount(),
        text.length(),
        round(quality),
        round(charsPerPage),
        round(TextQualityAnalyzer.getAlphanumericRatio(text)),
        ocrRoute && ocrConfig != null ? ocrConfig.tikaLanguage() : null,
        confidence.present() ? round(confidence.meanConfidence()) : null,
        confidence.present() ? confidence.lowConfidenceWordCount() : null,
        confidence.present() ? confidence.wordCount() : null,
        s.pagesWithReadableText(),
        s.pagesMissingReadableText(),
        mixedPdfOverride || (!ocrRoute && s.mixedPdf()),
        s.tableCount(),
        s.headingCount(),
        s.listCount(),
        s.imagePageCount(),
        s.layoutComplexity(),
        f.contentTruncated(),
        blankToNull(f.ocrFallbackRoute()),
        blankToNull(f.ocrSkipReason()),
        route == null || route.isBlank() ? "structured" : route);
  }

  public String toJson() {
    try {
      Map<String, Object> value = new LinkedHashMap<>();
      put(value, "schemaVersion", schemaVersion);
      put(value, "pageCount", pageCount);
      put(value, "textCharCount", textCharCount);
      put(value, "textQualityScore", textQualityScore);
      put(value, "charsPerPage", charsPerPage);
      put(value, "alphanumericRatio", alphanumericRatio);
      put(value, "ocrLanguage", ocrLanguage);
      put(value, "ocrMeanConfidence", ocrMeanConfidence);
      put(value, "ocrLowConfidenceWordCount", ocrLowConfidenceWordCount);
      put(value, "ocrWordCount", ocrWordCount);
      put(value, "pagesWithTextLayer", pagesWithTextLayer);
      put(value, "pagesMissingReadableText", pagesMissingReadableText);
      put(value, "mixedPdf", mixedPdf);
      Map<String, Object> structured = new LinkedHashMap<>();
      put(structured, "tables", tableCount);
      put(structured, "headings", headingCount);
      put(structured, "lists", listCount);
      put(value, "structuredElementCounts", structured);
      put(value, "imagePageCount", imagePageCount);
      put(value, "layoutComplexity", layoutComplexity);
      put(value, "contentTruncated", contentTruncated);
      put(value, "ocrFallbackRoute", ocrFallbackRoute);
      put(value, "ocrSkipReason", ocrSkipReason);
      put(value, "route", route);
      String json = MAPPER.writeValueAsString(value);
      return json.length() <= MAX_JSON_CHARS ? json : json.substring(0, MAX_JSON_CHARS);
    } catch (Exception e) {
      return "{}";
    }
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static double round(double value) {
    if (!Double.isFinite(value)) {
      return 0.0d;
    }
    return Math.round(value * 1000.0d) / 1000.0d;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  public record RoutingFacts(Boolean contentTruncated, String ocrFallbackRoute, String ocrSkipReason) {
    public static RoutingFacts empty() {
      return new RoutingFacts(null, null, null);
    }

    public static RoutingFacts of(boolean contentTruncated, String ocrFallbackRoute, OcrSkipReason ocrSkipReason) {
      return new RoutingFacts(
          contentTruncated ? Boolean.TRUE : null,
          ocrFallbackRoute,
          ocrSkipReason == null ? null : ocrSkipReason.wireValue());
    }
  }
}
