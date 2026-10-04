/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intermediate representation for structured document content.
 *
 * <p>Decouples extraction (Tika SAX, Docling, VLM) from indexing. All extractors produce a {@code
 * StructuredDocument}; all consumers (chunker, serializer) read from it.
 *
 * @param elements ordered list of typed document elements
 * @param pageCount total number of pages (0 if unknown or not applicable)
 */
public record StructuredDocument(List<Element> elements, int pageCount) {

  /** A typed element within a structured document. */
  public sealed interface Element permits Heading, Paragraph, Table, PageBreak, ListBlock {
    /** Page index (0-based) this element appears on, or -1 if unknown. */
    int pageIndex();
  }

  /** A heading with hierarchy level (1=H1, 6=H6). */
  public record Heading(int level, String text, int pageIndex) implements Element {}

  /** A paragraph of body text. */
  public record Paragraph(String text, int pageIndex) implements Element {}

  /**
   * A table with rows of cells. The first row is treated as the header row when {@link
   * #hasHeaders()} is true.
   */
  public record Table(List<List<String>> rows, int pageIndex) implements Element {
    public boolean hasHeaders() {
      return rows.size() > 1;
    }
  }

  /** A page boundary marker. */
  public record PageBreak(int pageIndex) implements Element {}

  /** A list (ordered or unordered) of text items. */
  public record ListBlock(List<String> items, boolean ordered, int pageIndex) implements Element {}

  /**
   * Serialize to search-optimized annotated text.
   *
   * <ul>
   *   <li>Headings: {@code ## heading text} (Markdown-style markers)
   *   <li>Tables: triplet serialization ({@code row_header, col_header = value})
   *   <li>Page breaks: {@code \n---\n}
   *   <li>Paragraphs: text followed by blank line
   *   <li>Lists: {@code - item} or {@code 1. item}
   * </ul>
   */
  public String toAnnotatedText() {
    return toAnnotatedText(Integer.MAX_VALUE).text();
  }

  /** Serializes within the extraction character budget, including repeated table labels. */
  public AnnotatedText toAnnotatedText(int maxChars) {
    if (maxChars < 0) throw new IllegalArgumentException("maxChars must not be negative");
    BoundedText sb = new BoundedText(maxChars);
    try {
      for (Element el : elements) {
        switch (el) {
          case Heading h -> appendHeading(sb, h);
          case Paragraph p -> appendParagraph(sb, p);
          case Table t -> appendTable(sb, t);
          case PageBreak ignored -> appendPageBreak(sb);
          case ListBlock lb -> appendListBlock(sb, lb);
        }
      }
    } catch (OutputLimitReached ignored) {
      return new AnnotatedText(sb.text(), true);
    }
    return new AnnotatedText(sb.text(), false);
  }

  public record AnnotatedText(String text, boolean truncated) {}

  /**
   * Serialize to flat text (backward-compatible with parseToString output). Simple concatenation
   * with whitespace separators.
   */
  public String toFlatText() {
    StringBuilder sb = new StringBuilder();
    for (Element el : elements) {
      String text = extractText(el);
      if (text != null && !text.isBlank()) {
        if (!sb.isEmpty()) {
          sb.append('\n');
        }
        sb.append(text);
      }
    }
    return sb.toString();
  }

  /**
   * Remove repeated headers and footers from multi-page documents.
   *
   * <p>Detects text elements that appear at the start or end of pages across more than {@code
   * threshold} fraction of pages, and removes them.
   *
   * @param threshold fraction of pages a line must appear on to be considered a header/footer
   *     (e.g., 0.5 = >50%)
   * @return a new StructuredDocument with headers/footers removed
   */
  public StructuredDocument removeHeadersFooters(double threshold) {
    if (pageCount <= 2) {
      return this;
    }

    // Group elements by page
    Map<Integer, List<Element>> byPage = new HashMap<>();
    for (Element el : elements) {
      if (el instanceof PageBreak) continue;
      byPage.computeIfAbsent(el.pageIndex(), k -> new ArrayList<>()).add(el);
    }

    if (byPage.size() <= 2) {
      return this;
    }

    // Collect candidate headers (first text element per page) and footers (last text element)
    Map<String, Integer> headerCounts = new HashMap<>();
    Map<String, Integer> footerCounts = new HashMap<>();

    for (List<Element> pageElements : byPage.values()) {
      if (pageElements.isEmpty()) continue;

      String firstText = normalizeForComparison(extractText(pageElements.getFirst()));
      if (firstText != null && !firstText.isBlank()) {
        headerCounts.merge(firstText, 1, Integer::sum);
      }

      String lastText = normalizeForComparison(extractText(pageElements.getLast()));
      if (lastText != null && !lastText.isBlank()) {
        footerCounts.merge(lastText, 1, Integer::sum);
      }
    }

    // Identify repeated headers/footers
    int pageThreshold = (int) Math.ceil(byPage.size() * threshold);
    var repeatedHeaders = new java.util.HashSet<String>();
    var repeatedFooters = new java.util.HashSet<String>();

    headerCounts.forEach(
        (text, count) -> {
          if (count >= pageThreshold) repeatedHeaders.add(text);
        });
    footerCounts.forEach(
        (text, count) -> {
          if (count >= pageThreshold) repeatedFooters.add(text);
        });

    if (repeatedHeaders.isEmpty() && repeatedFooters.isEmpty()) {
      return this;
    }

    // Rebuild element list, marking first/last per page for removal
    Map<Integer, Element> firstByPage = new HashMap<>();
    Map<Integer, Element> lastByPage = new HashMap<>();
    for (var entry : byPage.entrySet()) {
      List<Element> pageElements = entry.getValue();
      if (!pageElements.isEmpty()) {
        firstByPage.put(entry.getKey(), pageElements.getFirst());
        lastByPage.put(entry.getKey(), pageElements.getLast());
      }
    }

    List<Element> filtered = new ArrayList<>();
    for (Element el : elements) {
      if (el instanceof PageBreak) {
        filtered.add(el);
        continue;
      }

      boolean isFirst = firstByPage.get(el.pageIndex()) == el;
      boolean isLast = lastByPage.get(el.pageIndex()) == el;

      String normalized = normalizeForComparison(extractText(el));

      boolean remove = false;
      if (isFirst && normalized != null && repeatedHeaders.contains(normalized)) {
        remove = true;
      }
      if (isLast && normalized != null && repeatedFooters.contains(normalized)) {
        remove = true;
      }

      if (!remove) {
        filtered.add(el);
      }
    }

    return new StructuredDocument(filtered, pageCount);
  }

  /** Convenience overload with default 50% threshold. */
  public StructuredDocument removeHeadersFooters() {
    return removeHeadersFooters(0.5);
  }

  // ---- Serialization helpers ----

  private static void appendHeading(BoundedText sb, Heading h) {
    ensureBlankLine(sb);
    sb.append("#".repeat(Math.max(1, Math.min(6, h.level()))));
    sb.append(' ');
    sb.append(h.text().strip());
    sb.append("\n\n");
  }

  private static void appendParagraph(BoundedText sb, Paragraph p) {
    String text = p.text().strip();
    if (text.isEmpty()) return;
    ensureBlankLine(sb);
    sb.append(text);
    sb.append("\n\n");
  }

  private static void appendTable(BoundedText sb, Table t) {
    if (t.rows().isEmpty()) return;
    ensureBlankLine(sb);

    if (t.hasHeaders()) {
      List<String> headers = t.rows().getFirst();
      for (int r = 1; r < t.rows().size(); r++) {
        List<String> row = t.rows().get(r);
        // Use first column as row identifier if available
        String rowId = !row.isEmpty() ? row.getFirst().strip() : "";
        for (int c = 0; c < headers.size() && c < row.size(); c++) {
          String colHeader = headers.get(c).strip();
          String cellValue = row.get(c).strip();
          if (cellValue.isEmpty()) continue;
          if (!rowId.isEmpty() && c > 0) {
            sb.append(rowId).append(", ");
          }
          if (!colHeader.isEmpty()) {
            sb.append(colHeader).append(" = ");
          }
          sb.append(cellValue);
          sb.append('\n');
        }
      }
    } else {
      // No headers — output cells line by line
      for (List<String> row : t.rows()) {
        for (String cell : row) {
          String stripped = cell.strip();
          if (!stripped.isEmpty()) {
            sb.append(stripped);
            sb.append('\n');
          }
        }
      }
    }
    sb.append('\n');
  }

  private static void appendPageBreak(BoundedText sb) {
    ensureBlankLine(sb);
    sb.append("---\n\n");
  }

  private static void appendListBlock(BoundedText sb, ListBlock lb) {
    ensureBlankLine(sb);
    for (int i = 0; i < lb.items().size(); i++) {
      String item = lb.items().get(i).strip();
      if (item.isEmpty()) continue;
      if (lb.ordered()) {
        sb.append(i + 1).append(". ");
      } else {
        sb.append("- ");
      }
      sb.append(item);
      sb.append('\n');
    }
    sb.append('\n');
  }

  private static void ensureBlankLine(BoundedText sb) {
    if (sb.isEmpty()) return;
    int len = sb.length();
    if (sb.charAt(len - 1) != '\n') {
      sb.append("\n\n");
    } else if (len < 2 || sb.charAt(len - 2) != '\n') {
      sb.append('\n');
    }
  }

  private static final class BoundedText {
    private final StringBuilder buffer = new StringBuilder();
    private final int maxChars;

    BoundedText(int maxChars) {
      this.maxChars = maxChars;
    }

    BoundedText append(String value) {
      int count = Math.min(value.length(), maxChars - buffer.length());
      // Do not split a surrogate pair at the output boundary.
      if (count > 0 && count < value.length()
          && Character.isHighSurrogate(value.charAt(count - 1))
          && Character.isLowSurrogate(value.charAt(count))) {
        count--;
      }
      buffer.append(value, 0, count);
      if (count < value.length()) throw new OutputLimitReached();
      return this;
    }

    BoundedText append(char value) {
      return append(String.valueOf(value));
    }

    BoundedText append(int value) {
      return append(Integer.toString(value));
    }

    boolean isEmpty() {
      return buffer.isEmpty();
    }

    int length() {
      return buffer.length();
    }

    char charAt(int index) {
      return buffer.charAt(index);
    }

    String text() {
      return buffer.toString().stripTrailing();
    }
  }

  private static final class OutputLimitReached extends RuntimeException {
    OutputLimitReached() {
      super(null, null, false, false);
    }
  }

  private static String extractText(Element el) {
    return switch (el) {
      case Heading h -> h.text();
      case Paragraph p -> p.text();
      case Table t -> {
        StringBuilder sb = new StringBuilder();
        for (List<String> row : t.rows()) {
          sb.append(String.join(" ", row)).append('\n');
        }
        yield sb.toString();
      }
      case PageBreak ignored -> null;
      case ListBlock lb -> String.join("\n", lb.items());
    };
  }

  private static String normalizeForComparison(String text) {
    if (text == null) return null;
    String trimmed = text.strip().toLowerCase(java.util.Locale.ROOT);
    return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
  }
}
