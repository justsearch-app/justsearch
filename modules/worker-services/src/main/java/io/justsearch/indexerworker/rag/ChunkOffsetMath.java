/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.rag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure offset/heading math for RAG chunk metadata — the law-bearing core extracted from
 * {@link ChunkDocumentWriter} (tempdoc 555 Pillar A, functional-core/imperative-shell §4). These were
 * pure static methods trapped inside an IO-bearing writer; isolating them makes the law
 * (line-number = 1 + newlines before the offset; preceding-heading = last markdown heading before the
 * offset) independently testable and mutation-targeted. Registered in governance/logic-seams.v1.json.
 *
 * <p>No IO, no state — a registered behavioral-law seam.
 */
public final class ChunkOffsetMath {

  private ChunkOffsetMath() {}

  /** Markdown heading pattern: 1-6 # characters at start of line, followed by space and text. */
  private static final Pattern MARKDOWN_HEADING_PATTERN =
      Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$", Pattern.MULTILINE);

  /** Result of heading extraction. */
  public record HeadingInfo(int level, String text) {
    public static final HeadingInfo NONE = new HeadingInfo(0, "");
  }

  /** Immutable per-document offset index; publication never scans a content prefix per child. */
  public static final class MetadataIndex {
    private final String content;
    private final int[] newlines;
    private final java.util.List<Heading> headings = new java.util.ArrayList<>();

    private record Heading(int level, int start, int end) {}

    public MetadataIndex(String content) {
      this.content = content == null ? "" : content;
      newlines = java.util.stream.IntStream.range(0, this.content.length())
          .filter(i -> this.content.charAt(i) == '\n').toArray();
      Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(this.content);
      while (matcher.find()) {
        int start = matcher.start(2), end = matcher.end(2);
        while (start < end && this.content.charAt(start) <= ' ') start++;
        while (end > start && this.content.charAt(end - 1) <= ' ') end--;
        if (start == end) continue;
        // Lucene keyword terms and sorted DocValues accept at most 32766 UTF-8 bytes.
        int boundedEnd = start, bytes = 0;
        while (boundedEnd < end) {
          int cp = this.content.codePointAt(boundedEnd);
          int width = cp <= 0x7f ? 1 : cp <= 0x7ff ? 2 : cp <= 0xffff ? 3 : 4;
          if (bytes + width > 32766) break;
          bytes += width;
          boundedEnd += Character.charCount(cp);
        }
        headings.add(new Heading(matcher.group(1).length(), start, boundedEnd));
      }
    }

    public int lineNumber(int offset) {
      int limit = Math.max(0, Math.min(offset, content.length()));
      int at = java.util.Arrays.binarySearch(newlines, limit);
      return 1 + (at >= 0 ? at : -at - 1);
    }

    public HeadingInfo precedingHeading(int offset) {
      int limit = Math.max(0, Math.min(offset, content.length()));
      int low = 0, high = headings.size();
      while (low < high) {
        int mid = (low + high) >>> 1;
        if (headings.get(mid).start() < limit) low = mid + 1;
        else high = mid;
      }
      if (low == 0) return HeadingInfo.NONE;
      Heading heading = headings.get(low - 1);
      int end = Math.min(limit, heading.end());
      if (end > heading.start() && end < content.length()
          && Character.isLowSurrogate(content.charAt(end))
          && Character.isHighSurrogate(content.charAt(end - 1))) end--;
      return new HeadingInfo(heading.level(), content.substring(heading.start(), end).trim());
    }
  }

  /**
   * Calculates the 1-based line number for a given character offset.
   *
   * @param content the full document content
   * @param charOffset 0-based character offset
   * @return 1-based line number
   */
  public static int calculateLineNumber(String content, int charOffset) {
    if (content == null || content.isEmpty() || charOffset <= 0) {
      return 1;
    }
    int line = 1;
    int limit = Math.min(charOffset, content.length());
    for (int i = 0; i < limit; i++) {
      if (content.charAt(i) == '\n') {
        line++;
      }
    }
    return line;
  }

  /**
   * Finds the nearest markdown heading preceding the given character offset.
   *
   * @param content the full document content
   * @param charOffset 0-based character offset
   * @return HeadingInfo with level (1-6) and text, or HeadingInfo.NONE if no heading found
   */
  public static HeadingInfo findPrecedingHeading(String content, int charOffset) {
    if (content == null || content.isEmpty() || charOffset <= 0) {
      return HeadingInfo.NONE;
    }

    int searchLimit = Math.min(charOffset, content.length());
    String searchRegion = content.substring(0, searchLimit);

    Matcher matcher = MARKDOWN_HEADING_PATTERN.matcher(searchRegion);
    HeadingInfo lastFound = HeadingInfo.NONE;

    while (matcher.find()) {
      int level = matcher.group(1).length();
      String text = matcher.group(2).trim();
      if (!text.isEmpty()) {
        lastFound = new HeadingInfo(level, text);
      }
    }

    return lastFound;
  }
}
