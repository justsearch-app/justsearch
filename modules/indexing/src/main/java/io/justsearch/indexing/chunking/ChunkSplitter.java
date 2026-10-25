/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.chunking;

import io.justsearch.core.util.TokenEstimation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits text content into chunks for RAG (Retrieval-Augmented Generation).
 *
 * <p>Chunking strategy:
 * <ul>
 *   <li>Target size: 500 tokens (~375 words)</li>
 *   <li>Overlap: 50 tokens (~38 words) for context continuity</li>
 *   <li>Boundary awareness: Prefers sentence/paragraph boundaries</li>
 * </ul>
 *
 * <p>Why chunk? Large documents exceed LLM context limits. By indexing
 * individual chunks, we can retrieve only the most relevant portions
 * for Q&A, avoiding lossy truncation.
 */
public final class ChunkSplitter {

  private ChunkSplitter() {}

  /**
   * Content-aware chunking mode.
   *
   * <p>Default chunking is content-agnostic. Some file kinds (markdown, code) benefit from stronger
   * structural boundaries (code fences, line boundaries) to avoid incoherent chunks.
   *
   * <p>F2: CSV and JSON modes ensure chunk boundaries don't split mid-row (CSV) or inside string
   * literals (JSON).
   */
  public enum Mode {
    DEFAULT,
    MARKDOWN,
    CODE,
    /** F2: CSV chunking avoids splitting inside quoted fields. */
    CSV,
    /** F2: JSON chunking avoids splitting inside string literals. */
    JSON,
    /**
     * Structured extraction mode: prefers heading markers ({@code ## }), page break markers
     * ({@code ---}), and table block boundaries from {@code StructuredDocument.toAnnotatedText()}.
     */
    STRUCTURED;

    public static Mode fromFileKind(String fileKind) {
      if (fileKind == null || fileKind.isBlank()) {
        return DEFAULT;
      }
      return switch (fileKind.trim().toLowerCase(Locale.ROOT)) {
        case "markdown" -> MARKDOWN;
        case "code" -> CODE;
        default -> DEFAULT;
      };
    }

    /**
     * F2: Selects chunking mode based on MIME type (preferred) or file kind.
     *
     * @param mimeBase the base MIME type (e.g., "text/csv", "application/json")
     * @param fileKind the file kind fallback
     * @return appropriate chunking mode
     */
    public static Mode fromMimeOrFileKind(String mimeBase, String fileKind) {
      if (mimeBase != null && !mimeBase.isBlank()) {
        String normalized = mimeBase.trim().toLowerCase(Locale.ROOT);
        if ("text/csv".equals(normalized)) {
          return CSV;
        }
        if ("application/json".equals(normalized)) {
          return JSON;
        }
      }
      // Use STRUCTURED mode for PDF and Office documents (these benefit from
      // structural extraction via StructuredContentExtractor — tempdoc 252)
      if (fileKind != null) {
        String kind = fileKind.trim().toLowerCase(Locale.ROOT);
        if ("pdf".equals(kind) || "office".equals(kind)) {
          return STRUCTURED;
        }
      }
      return fromFileKind(fileKind);
    }
  }

  /**
   * Version of the splitting <em>algorithm</em>, as opposed to its tunable sizes. Bump it whenever
   * a change here would put existing chunk documents at different boundaries even with the token
   * counts unchanged — new boundary patterns, a different token estimator, a changed CJK rule.
   *
   * <p>It is an {@code index_fingerprint} input (tempdoc 915 §C): chunk boundaries are on-disk
   * shape, so a silent algorithm change would otherwise leave an index whose chunks no longer match
   * what the runtime would produce, with nothing to detect it.
   */
  public static final String ALGORITHM_VERSION = "v2";

  /**
   * Content length at or above which a document is split into chunk documents at all. It lives
   * here, with the other splitting constants, because it is a property of the splitting policy
   * rather than of any one writer: {@code ChunkDocumentWriter} and {@code IndexingDocumentOps}
   * both gate on it, and {@code index_fingerprint} hashes it (tempdoc 915 §C) because it
   * decides whether chunk documents exist on disk.
   */
  public static final int CHUNK_THRESHOLD_CHARS = 2000;

  /**
   * Bounds {@code content_preview}, a {@code stored:true} field derived from the same content
   * this class splits. An {@code index_fingerprint} input for that reason (tempdoc 915 §C).
   */
  public static final int CONTENT_PREVIEW_MAX_CHARS = 4096;

  /** Default target chunk size in tokens (~1.3 tokens per word). */
  public static final int DEFAULT_CHUNK_TOKENS = 500;

  /** Default overlap between chunks in tokens. */
  public static final int DEFAULT_OVERLAP_TOKENS = 50;

  /** Minimum chunk size to avoid tiny fragments. */
  public static final int MIN_CHUNK_TOKENS = 100;

  /** Pattern for sentence boundaries. */
  private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

  /** Pattern for paragraph boundaries. */
  private static final Pattern PARAGRAPH_END = Pattern.compile("\\n\\s*\\n");

  /** Pattern for Markdown headings ("# ", "## ", ...). */
  private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^#{1,6}\\s+");

  /**
   * Token-to-char ratio for Latin text: ~1.3 tokens per word, ~5 chars per word = ~3.85 chars/token.
   */
  private static final double LATIN_CHARS_PER_TOKEN = 5.0 / 1.3;

  /**
   * Token-to-char ratio for CJK text: ~1 token per character.
   */
  private static final double CJK_CHARS_PER_TOKEN = 1.0;

  /**
   * Threshold for considering content as "CJK-dominant" (>50% CJK characters).
   */
  private static final double CJK_THRESHOLD = 0.5;

  /**
   * Converts token count to estimated character count, with overflow protection.
   *
   * <p>Uses content analysis to determine appropriate ratio:
   * <ul>
   *   <li>CJK-dominant text: ~1 char per token</li>
   *   <li>Latin/mixed text: ~3.85 chars per token</li>
   * </ul>
   *
   * @param tokens number of tokens
   * @param content content to analyze for character set (may be null for default ratio)
   * @return estimated character count, clamped to avoid overflow
   */
  private static int tokensToChars(int tokens, String content) {
    // Handle negative/zero tokens - return minimum of 1 char
    if (tokens <= 0) {
      return 1;
    }
    double ratio = detectCharsPerToken(content);
    // Use long arithmetic to avoid overflow
    long result = (long) (tokens * ratio);
    // Clamp to reasonable max (Integer.MAX_VALUE would cause issues downstream)
    return (int) Math.min(result, Integer.MAX_VALUE / 2);
  }

  /**
   * Detects the appropriate chars-per-token ratio based on content character set.
   *
   * @param content content to analyze (may be null)
   * @return chars-per-token ratio
   */
  private static double detectCharsPerToken(String content) {
    if (content == null || content.isEmpty()) {
      return LATIN_CHARS_PER_TOKEN;
    }

    int cjkCount = 0;
    int totalChars = 0;

    for (int i = 0; i < content.length(); i++) {
      char c = content.charAt(i);
      if (!Character.isWhitespace(c)) {
        totalChars++;
        if (isCjkCharacter(c)) {
          cjkCount++;
        }
      }
    }

    if (totalChars == 0) {
      return LATIN_CHARS_PER_TOKEN;
    }

    double cjkRatio = (double) cjkCount / totalChars;
    if (cjkRatio >= CJK_THRESHOLD) {
      return CJK_CHARS_PER_TOKEN;
    }
    return LATIN_CHARS_PER_TOKEN;
  }

  /**
   * Checks if a character is CJK (Chinese, Japanese, Korean).
   */
  private static boolean isCjkCharacter(char c) {
    Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.HIRAGANA
        || block == Character.UnicodeBlock.KATAKANA
        || block == Character.UnicodeBlock.HANGUL_SYLLABLES
        || block == Character.UnicodeBlock.HANGUL_JAMO;
  }

  /**
   * Splits content into chunks with default settings.
   *
   * @param content the text content to split
   * @return list of text chunks
   */
  public static List<String> split(String content) {
    return split(content, DEFAULT_CHUNK_TOKENS, DEFAULT_OVERLAP_TOKENS);
  }

  /**
   * Splits content into chunks with default settings and content-aware mode.
   */
  public static List<String> split(String content, Mode mode) {
    return split(content, DEFAULT_CHUNK_TOKENS, DEFAULT_OVERLAP_TOKENS, mode);
  }

  /**
   * Splits content into chunks with specified token targets.
   *
   * @param content the text content to split
   * @param targetTokens target tokens per chunk
   * @param overlapTokens overlap tokens between chunks
   * @return list of text chunks
   */
  public static List<String> split(String content, int targetTokens, int overlapTokens) {
    return split(content, targetTokens, overlapTokens, Mode.DEFAULT);
  }

  /**
   * Splits content into chunks with specified token targets and content-aware mode.
   *
   * @param content the text content to split
   * @param targetTokens target tokens per chunk
   * @param overlapTokens overlap tokens between chunks
   * @param mode chunking mode (content-aware)
   * @return list of text chunks
   */
  public static List<String> split(String content, int targetTokens, int overlapTokens, Mode mode) {
    if (content == null || content.isBlank()) {
      return List.of();
    }

    // Use content-aware token-to-char conversion (handles CJK and overflow)
    int targetChars = tokensToChars(targetTokens, content);
    int overlapChars = tokensToChars(overlapTokens, content);
    int minChars = tokensToChars(MIN_CHUNK_TOKENS, content);
    // Keep invariants when callers request very small targetTokens (e.g., tests).
    minChars = Math.min(minChars, Math.max(1, targetChars));

    List<String> chunks = new ArrayList<>();
    String remaining = content.trim();

    List<Range> fences = (mode == Mode.MARKDOWN) ? findMarkdownCodeFences(remaining) : List.of();

    while (!remaining.isEmpty()) {
      // Determine chunk end position
      int endPos = Math.min(targetChars, remaining.length());

      // If we're not at the end, try to find a good boundary
      if (endPos < remaining.length()) {
        endPos = findBoundaryAbsolute(remaining, endPos, minChars, mode, fences);
      } else if (mode == Mode.MARKDOWN) {
        // Bug fix: Even for the last chunk, check if we START inside a fence
        // and extend to include the whole fence (overlap can put us mid-fence)
        Range fence = findContainingRange(fences, 0);
        if (fence != null && fence.end() <= remaining.length()) {
          endPos = fence.end();
        }
      }

      if (bisectsSurrogatePair(remaining, endPos)) {
        endPos++;
      }

      // Extract chunk
      String chunk = remaining.substring(0, endPos).trim();
      if (!chunk.isEmpty()) {
        chunks.add(chunk);
      }

      // A chunk reaching the text end makes any further window a contained suffix.
      if (endPos >= remaining.length()) {
        break;
      }

      // Move past chunk, accounting for overlap
      int advance = Math.max(endPos - overlapChars, minChars);

      // Bug fix: In MARKDOWN mode, don't let overlap pull us back inside a fence.
      if (mode == Mode.MARKDOWN) {
        Range fence = findContainingRange(fences, advance);
        if (fence != null && advance > fence.start() && fence.end() <= remaining.length()) {
          // We'd be inside a fence; advance to fence end instead
          advance = fence.end();
        }
      }

      if (bisectsSurrogatePair(remaining, advance)) {
        // Keep overlap where possible; a one-unit budget still advances a whole code point.
        advance = advance > 1 ? advance - 1 : Character.charCount(remaining.codePointAt(0));
      }
      if (advance >= remaining.length()) {
        break;
      }
      remaining = remaining.substring(advance).trim();
      if (mode == Mode.MARKDOWN) {
        fences = findMarkdownCodeFences(remaining);
      }
    }

    return chunks;
  }

  /**
   * Finds a good boundary near the target position (absolute indices).
   *
   * <p>Prefers paragraph > sentence > word boundaries. In CODE mode, prefers line boundaries.
   * In MARKDOWN mode, avoids splitting inside fenced code blocks when possible, and prefers heading
   * boundaries.
   *
   * <p>F2: In CSV mode, uses quote-aware row boundaries to avoid splitting inside quoted fields.
   * In JSON mode, uses string-safe boundaries to avoid splitting inside string literals.
   */
  private static int findBoundaryAbsolute(
      String text, int targetEnd, int minEnd, Mode mode, List<Range> markdownFences) {
    // Look for paragraph boundary within reasonable range
    int searchStart = Math.max(minEnd, targetEnd - 200);
    int searchEnd = Math.min(text.length(), targetEnd + 100);
    if (searchStart >= searchEnd) {
      return targetEnd;
    }

    // F2: CSV mode - use quote-aware row boundary detection
    if (mode == Mode.CSV) {
      return findQuoteAwareRowBoundary(text, targetEnd, minEnd);
    }

    // F2: JSON mode - use string-safe boundary detection
    if (mode == Mode.JSON) {
      return findJsonSafeBoundary(text, targetEnd, minEnd);
    }

    // STRUCTURED mode: prefer heading and page-break markers from annotated text
    if (mode == Mode.STRUCTURED) {
      String window = text.substring(searchStart, searchEnd);

      // Priority 1: page break markers (strongest boundary)
      int pageBreak = window.lastIndexOf("\n---\n");
      if (pageBreak >= 0) {
        int pos = searchStart + pageBreak;
        if (pos >= minEnd && pos <= targetEnd + 100) {
          return pos;
        }
      }

      // Priority 2: heading markers
      var headingMatcher = MARKDOWN_HEADING.matcher(window);
      int lastHeading = -1;
      while (headingMatcher.find()) {
        int pos = searchStart + headingMatcher.start();
        if (pos >= minEnd && pos <= targetEnd + 100) {
          lastHeading = pos;
        }
      }
      if (lastHeading > 0) {
        return lastHeading;
      }
      // Fall through to paragraph > sentence > word
    }

    if (mode == Mode.MARKDOWN) {
      Range fence = findContainingRange(markdownFences, targetEnd);
      if (fence != null) {
        // Prefer cutting before the fence (keeps code block intact), otherwise cut after it.
        if (fence.start() >= minEnd) {
          return fence.start();
        }
        // Bug fix: Always include the whole fence rather than splitting inside.
        // Large chunks are preferable to broken code blocks for LLM context.
        // Note: fence.end() is guaranteed <= text.length() by findMarkdownCodeFences().
        return fence.end();
      }

      // Prefer markdown heading boundaries (cut before a heading line).
      String window = text.substring(searchStart, searchEnd);
      var headingMatcher = MARKDOWN_HEADING.matcher(window);
      int lastHeading = -1;
      while (headingMatcher.find()) {
        int pos = searchStart + headingMatcher.start();
        if (pos >= minEnd && pos <= targetEnd + 100 && !isInsideRanges(markdownFences, pos)) {
          lastHeading = pos;
        }
      }
      if (lastHeading > 0) {
        return lastHeading;
      }
    }

    if (mode == Mode.CODE) {
      // In code, prefer to end at a newline boundary to avoid mid-line splits.
      int nl = findLastNewlineBoundary(text, searchStart, searchEnd);
      if (nl > 0) {
        return nl;
      }
    }

    String searchWindow = text.substring(searchStart, searchEnd);

    // Try paragraph boundary first
    var paragraphMatcher = PARAGRAPH_END.matcher(searchWindow);
    int lastParagraph = -1;
    while (paragraphMatcher.find()) {
      int pos = searchStart + paragraphMatcher.end();
      if (pos >= minEnd && pos <= targetEnd + 100) {
        lastParagraph = pos;
      }
    }
    if (lastParagraph > 0) {
      return lastParagraph;
    }

    // Try sentence boundary
    var sentenceMatcher = SENTENCE_END.matcher(searchWindow);
    int lastSentence = -1;
    while (sentenceMatcher.find()) {
      int pos = searchStart + sentenceMatcher.end();
      if (pos >= minEnd && pos <= targetEnd + 100) {
        lastSentence = pos;
      }
    }
    if (lastSentence > 0) {
      return lastSentence;
    }

    // Fall back to word boundary (space)
    int spacePos = text.lastIndexOf(' ', targetEnd);
    if (spacePos > minEnd) {
      return spacePos + 1;
    }

    // Last resort: use target position
    return targetEnd;
  }

  private record Range(int start, int end) {}

  private static boolean isInsideRanges(List<Range> ranges, int pos) {
    if (ranges == null || ranges.isEmpty()) return false;
    for (Range r : ranges) {
      if (pos > r.start() && pos < r.end()) {
        return true;
      }
    }
    return false;
  }

  private static Range findContainingRange(List<Range> ranges, int pos) {
    if (ranges == null || ranges.isEmpty()) return null;
    for (Range r : ranges) {
      if (pos > r.start() && pos < r.end()) {
        return r;
      }
    }
    return null;
  }

  private static int findLastNewlineBoundary(String text, int minPos, int maxPos) {
    if (text == null || text.isEmpty()) return -1;
    int end = Math.min(text.length(), Math.max(0, maxPos));
    int start = Math.max(0, Math.min(minPos, end));
    int idx = text.lastIndexOf('\n', end - 1);
    if (idx < start) return -1;
    // Return the position after newline so the slice is a full line boundary.
    return idx + 1;
  }

  /**
   * Best-effort detection of fenced code blocks in markdown.
   *
   * <p>Supports ``` and ~~~ fences (up to 3 spaces indentation). Unterminated fences are treated
   * as "open until end of document" to avoid splitting inside.
   */
  private static List<Range> findMarkdownCodeFences(String text) {
    if (text == null || text.isEmpty()) return List.of();
    List<Range> ranges = new ArrayList<>();

    String fenceMarker = null;
    int fenceStart = -1;

    int i = 0;
    while (i < text.length()) {
      int lineStart = i;
      int lineEnd = text.indexOf('\n', i);
      if (lineEnd < 0) {
        lineEnd = text.length();
      }

      String line = text.substring(lineStart, lineEnd);
      int indent = 0;
      while (indent < line.length() && indent < 3) {
        char c = line.charAt(indent);
        if (c == ' ' || c == '\t') {
          indent++;
        } else {
          break;
        }
      }
      String afterIndent = line.substring(indent);
      String marker = null;
      if (afterIndent.startsWith("```")) {
        marker = "```";
      } else if (afterIndent.startsWith("~~~")) {
        marker = "~~~";
      }

      if (marker != null) {
        if (fenceMarker == null) {
          fenceMarker = marker;
          fenceStart = lineStart;
        } else if (fenceMarker.equals(marker)) {
          int closeEnd = lineEnd;
          if (closeEnd < text.length() && text.charAt(closeEnd) == '\n') {
            closeEnd++;
          }
          ranges.add(new Range(fenceStart, closeEnd));
          fenceMarker = null;
          fenceStart = -1;
        }
      }

      i = lineEnd;
      if (i < text.length() && text.charAt(i) == '\n') {
        i++;
      }
    }

    if (fenceMarker != null && fenceStart >= 0) {
      ranges.add(new Range(fenceStart, text.length()));
    }

    return ranges;
  }

  /**
   * Estimates token count for text.
   *
   * <p>Delegates to {@link TokenEstimation#estimateTokens(String)} which uses a combination of
   * word-based (~1.3 tokens/word) and character-based estimates, with adjustments for dense
   * content (JSON, minified code) and CJK text.
   *
   * @param text the text to estimate
   * @return estimated token count
   */
  public static int estimateTokens(String text) {
    return TokenEstimation.estimateTokens(text);
  }

  // ========== F2: CSV and JSON Boundary Detection ==========

  /**
   * F2: Finds a quote-aware row boundary for CSV content.
   *
   * <p>Scans from start to find newlines that are not inside a quoted field.
   * RFC 4180 rules: fields may be enclosed in double quotes, and quotes within fields are escaped
   * by doubling ("").
   *
   * <p>Prefers the boundary closest to targetEnd within the search window.
   *
   * @param text the CSV text
   * @param targetEnd target end position
   * @param minEnd minimum end position (will not return before this)
   * @return position after a valid row boundary, or fallback to word boundary
   */
  static int findQuoteAwareRowBoundary(String text, int targetEnd, int minEnd) {
    if (text == null || text.isEmpty()) {
      return Math.min(targetEnd, text != null ? text.length() : 0);
    }

    // Use flexible search window similar to other modes
    int preferredStart = Math.max(0, targetEnd - 200);
    int searchEnd = Math.min(text.length(), targetEnd + 100);

    // Scan from start to searchEnd to determine quote state at each newline
    // Collect all valid boundaries
    int bestBoundary = -1;
    int bestDistance = Integer.MAX_VALUE;
    boolean inQuotes = false;

    for (int i = 0; i < searchEnd; i++) {
      char c = text.charAt(i);

      if (c == '"') {
        // Check for escaped quote ("")
        if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          i++; // Skip the escaped quote
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == '\n' && !inQuotes) {
        int afterNewline = i + 1;
        // Consider boundaries >= preferredStart and within searchEnd
        if (afterNewline >= preferredStart && afterNewline <= searchEnd) {
          // Prefer boundary closest to targetEnd (but not exceeding it by much)
          int distance = Math.abs(afterNewline - targetEnd);
          // Prefer boundaries at or before targetEnd, with small penalty for going over
          if (afterNewline > targetEnd) {
            distance += 50; // Small penalty for overshooting
          }
          if (distance < bestDistance) {
            bestDistance = distance;
            bestBoundary = afterNewline;
          }
        }
      }
    }

    // If we found a valid row boundary, use it
    if (bestBoundary > 0) {
      return bestBoundary;
    }

    // Fallback: find last space for word boundary
    int spacePos = text.lastIndexOf(' ', Math.min(targetEnd, text.length() - 1));
    if (spacePos > preferredStart) {
      return spacePos + 1;
    }

    return Math.min(targetEnd, text.length());
  }

  /**
   * F2: Finds a JSON-safe boundary that doesn't split inside string literals.
   *
   * <p>Scans forward tracking string state (toggled by unescaped quotes) and finds
   * boundaries at positions where we're not inside a string. Prefers newlines and
   * structural characters (comma, brace, bracket).
   *
   * @param text the JSON text
   * @param targetEnd target end position
   * @param minEnd minimum end position (will not return before this)
   * @return position of a safe boundary, or targetEnd if none found
   */
  static int findJsonSafeBoundary(String text, int targetEnd, int minEnd) {
    if (text == null || text.isEmpty()) {
      return Math.min(targetEnd, text != null ? text.length() : 0);
    }

    int preferredStart = Math.max(0, targetEnd - 200);
    int searchEnd = Math.min(text.length(), targetEnd + 100);

    // First pass: find all positions where we're NOT inside a string
    // Track state up to searchEnd
    boolean inString = false;
    int bestBoundary = -1;
    int bestDistance = Integer.MAX_VALUE;

    for (int i = 0; i < searchEnd; i++) {
      char c = text.charAt(i);

      if (inString) {
        if (c == '\\' && i + 1 < text.length()) {
          i++; // Skip escaped character
        } else if (c == '"') {
          inString = false;
        }
      } else {
        // We're outside a string - check if this is a good boundary position
        boolean isGoodBoundary = false;
        int boundaryPos = i + 1; // Position after this character

        switch (c) {
          case '"' -> inString = true; // Entering string
          case '\n', ',', '}', ']' -> isGoodBoundary = true;
          default -> { /* not a good boundary */ }
        }

        if (isGoodBoundary && boundaryPos >= preferredStart && boundaryPos <= searchEnd) {
          int distance = Math.abs(boundaryPos - targetEnd);
          if (boundaryPos > targetEnd) {
            distance += 50; // Penalty for overshooting
          }
          if (distance < bestDistance) {
            bestDistance = distance;
            bestBoundary = boundaryPos;
          }
        }
      }
    }

    if (bestBoundary > 0) {
      return bestBoundary;
    }

    // Fallback: find last space not in string
    inString = false;
    int lastSpace = -1;
    for (int i = 0; i < Math.min(targetEnd, text.length()); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (c == '\\' && i + 1 < text.length()) {
          i++;
        } else if (c == '"') {
          inString = false;
        }
      } else {
        if (c == '"') {
          inString = true;
        } else if (c == ' ' && i + 1 >= preferredStart) {
          lastSpace = i + 1;
        }
      }
    }

    if (lastSpace > 0) {
      return lastSpace;
    }

    return Math.min(targetEnd, text.length());
  }

  /** Whether a UTF-16 offset separates an existing high/low surrogate pair. */
  public static boolean bisectsSurrogatePair(String text, int offset) {
    return offset > 0 && offset < text.length()
        && Character.isHighSurrogate(text.charAt(offset - 1))
        && Character.isLowSurrogate(text.charAt(offset));
  }

  /**
   * Represents a chunk with its metadata.
   */
  public record Chunk(
      int index,
      String content,
      int startChar,
      int endChar,
      int estimatedTokens
  ) {
    public static Chunk of(int index, String content, int startChar, int endChar) {
      return new Chunk(index, content, startChar, endChar, estimateTokens(content));
    }
  }

  /**
   * Splits content into chunks with full metadata.
   *
   * @param content the text content to split
   * @return list of chunks with metadata
   */
  public static List<Chunk> splitWithMetadata(String content) {
    return splitWithMetadata(content, DEFAULT_CHUNK_TOKENS, DEFAULT_OVERLAP_TOKENS);
  }

  /**
   * Splits content into chunks with full metadata.
   *
   * @param content the text content to split
   * @param targetTokens target tokens per chunk
   * @param overlapTokens overlap tokens between chunks
   * @return list of chunks with metadata
   */
  public static List<Chunk> splitWithMetadata(String content, int targetTokens, int overlapTokens) {
    return splitWithMetadata(content, targetTokens, overlapTokens, Mode.DEFAULT);
  }

  /**
   * Splits content into chunks with full metadata and content-aware mode.
   */
  public static List<Chunk> splitWithMetadata(String content, int targetTokens, int overlapTokens, Mode mode) {
    if (content == null || content.isBlank()) {
      return List.of();
    }

    // Use content-aware token-to-char conversion (handles CJK and overflow)
    int targetChars = tokensToChars(targetTokens, content);
    int overlapChars = tokensToChars(overlapTokens, content);
    int minChars = tokensToChars(MIN_CHUNK_TOKENS, content);
    // Keep invariants when callers request very small targetTokens (e.g., tests).
    minChars = Math.min(minChars, Math.max(1, targetChars));

    List<Chunk> chunks = new ArrayList<>();
    // Track the offset where content starts after leading whitespace trim
    // This ensures returned offsets are relative to the ORIGINAL content
    int contentOffset = content.length() - content.stripLeading().length();
    // Use strip() (Unicode-whitespace), NOT trim() (ASCII <=0x20): the offset adjustment below uses
    // stripLeading/stripTrailing, so the content trimming must use the same whitespace definition or
    // the returned offsets drift from chunk content for control / non-ASCII-whitespace chars
    // (tempdoc 554: a real defect a generated property counterexample surfaced).
    String text = content.strip();
    List<Range> fences = (mode == Mode.MARKDOWN) ? findMarkdownCodeFences(text) : List.of();
    int position = 0;
    int index = 0;

    while (position < text.length()) {
      int targetEnd = Math.min(position + targetChars, text.length());
      int endPos = targetEnd;

      if (targetEnd < text.length()) {
        int boundary =
            findBoundaryAbsolute(
                text,
                targetEnd,
                Math.min(text.length(), position + minChars),
                mode,
                fences);
        // CSV/JSON boundary finders can return a position at/before the chunk start; never let the
        // chunk end precede its start (crashes substring) or exceed the text. Fall back to the
        // un-adjusted target end (always > position) — tempdoc 554: a generated property
        // counterexample surfaced this StringIndexOutOfBounds.
        endPos = (boundary > position && boundary <= text.length()) ? boundary : targetEnd;
      }

      if (bisectsSurrogatePair(text, endPos)) {
        endPos++;
      }

      String rawChunk = text.substring(position, endPos);
      String chunkContent = rawChunk.strip();
      if (!chunkContent.isEmpty()) {
        // Bug fix: Adjust offsets to match stripped content exactly.
        // The offset law is: original.substring(startChar, endChar) == chunk.content()
        int leadingWhitespace = rawChunk.length() - rawChunk.stripLeading().length();
        int trailingWhitespace = rawChunk.length() - rawChunk.stripTrailing().length();
        int adjustedStart = contentOffset + position + leadingWhitespace;
        int adjustedEnd = contentOffset + endPos - trailingWhitespace;
        // Invariant: no emitted chunk's span may be contained in its predecessor's — a trimmed
        // all-whitespace-tail window would otherwise emit redundant contained content (tempdoc 749).
        boolean containedInPredecessor =
            !chunks.isEmpty()
                && adjustedStart >= chunks.get(chunks.size() - 1).startChar()
                && adjustedEnd <= chunks.get(chunks.size() - 1).endChar();
        if (!containedInPredecessor) {
          chunks.add(Chunk.of(index++, chunkContent, adjustedStart, adjustedEnd));
        }
      }

      // A chunk reaching the text end makes any further window a contained suffix.
      if (endPos >= text.length()) {
        break;
      }

      int advance = Math.max(endPos - position - overlapChars, minChars);

      // Bug fix: In MARKDOWN mode, don't let overlap pull us back inside a fence.
      // If we ended at a fence boundary, advance should be at least to that fence end.
      if (mode == Mode.MARKDOWN) {
        int newPosition = position + advance;
        Range fence = findContainingRange(fences, newPosition);
        if (fence != null && newPosition > fence.start() && fence.end() <= text.length()) {
          // We'd be inside a fence; advance to fence end instead
          advance = fence.end() - position;
        }
      }

      int nextPosition = position + advance;
      if (bisectsSurrogatePair(text, nextPosition)) {
        nextPosition = nextPosition > position + 1
            ? nextPosition - 1
            : position + Character.charCount(text.codePointAt(position));
      }
      position = nextPosition;
    }

    return chunks;
  }
}
