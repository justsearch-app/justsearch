/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.util.List;
import java.util.Set;
import io.justsearch.indexerworker.extract.LatinLanguageDetector;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.text.TextContentNodeRendererContext;
import org.commonmark.renderer.text.TextContentNodeRendererFactory;
import org.commonmark.renderer.text.TextContentRenderer;

/**
 * Utility for detecting/resolving language from text content.
 *
 * <p>Indexing, VDU replacement and passage regeneration label the full stored content with the
 * same rule ({@link #contentLanguage}).
 */
public final class LanguageUtils {

  private static final int MIN_LATIN_LETTERS = 50;
  /** Characters per language window. */
  private static final int WINDOW_CHARS = 4096;
  /** At most this many windows vote on a document's label. */
  private static final int MAX_WINDOWS = 8;
  /** How far a window edge may move to reach a line break. */
  private static final int WINDOW_EDGE_SLACK = 256;
  private static final char BOM = '\uFEFF';
  private static final Set<String> STRUCTURED_DATA_MIMES = Set.of(
      "text/csv", "text/tab-separated-values", "application/json", "application/x-ndjson",
      "application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml", "application/toml",
      "application/sql", "application/x-sql", "text/x-sql");
  private static final List<String> DESCRIPTION_KEYS =
      List.of("description", "summary", "excerpt", "abstract");

  /** Result of frontmatter detection: body text and raw frontmatter (if any). */
  public record FrontmatterResult(String body, String rawFrontmatter) {}

  private LanguageUtils() {}

  /**
   * Resolves the language of a document's main text from its full content.
   *
   * <p>Source code and structured data, as the app classifies them by file kind and MIME type,
   * have no natural language and get no label. Otherwise the content is prepared as for the
   * preview (Markdown converted to plain text). A text shorter than 4,096 characters is one window;
   * a longer one is read as up to eight windows of 4,096 characters, evenly spaced from its start to
   * its end. Each window gets the existing rule on its own (non-Latin script rules, then bundled
   * statistical models for Latin prose), except that a window labelled by a script rule votes only
   * when that script holds the majority of its letters. The document's label is the language most
   * windows name, when it leads every other language. So an opening block in another language (site menus,
   * a cover page) does not decide the label. A tie or no window label means no label; the machine
   * locale is never consulted.
   *
   * @param content the document's full content, as stored
   * @param fileKind the stored file kind ({@code code}, {@code text}, {@code markdown}, ...)
   * @param mimeBase the stored MIME type without parameters
   * @return an ISO language code, or null for code, data or insufficient evidence
   */
  public static String contentLanguage(String content, String fileKind, String mimeBase) {
    if (content == null || isCodeOrData(fileKind, mimeBase)) {
      return null;
    }
    return votedLanguage(preparedText(content, "markdown".equals(fileKind)));
  }

  private static boolean isCodeOrData(String fileKind, String mimeBase) {
    if ("code".equals(fileKind)) {
      return true;
    }
    return mimeBase != null
        && (STRUCTURED_DATA_MIMES.contains(mimeBase) || mimeBase.endsWith("+json"));
  }

  /**
   * The plurality label of a prepared text's windows: {@code min(8, ceil(length / 4096))} windows
   * of 4,096 characters, the first starting at the start and the last ending at the end, evenly
   * spaced between. An inner window edge moves inward to the nearest line break within 256
   * characters, and never splits a surrogate pair.
   */
  private static String votedLanguage(String text) {
    int length = text.length();
    if (length <= WINDOW_CHARS) {
      return detectLanguage(text, false);
    }
    int windows = Math.min(MAX_WINDOWS, (length + WINDOW_CHARS - 1) / WINDOW_CHARS);
    java.util.Map<String, Integer> votes = new java.util.HashMap<>();
    for (int i = 0; i < windows; i++) {
      int start = (int) ((long) i * (length - WINDOW_CHARS) / (windows - 1));
      int end = start + WINDOW_CHARS;
      String label = detectLanguage(text.substring(
          start == 0 ? 0 : lineStartAtOrAfter(text, start),
          end == length ? length : lineEndAtOrBefore(text, end)), true);
      if (label != null) {
        votes.merge(label, 1, Integer::sum);
      }
    }
    String leader = null;
    int most = 0;
    boolean tied = false;
    for (var vote : votes.entrySet()) {
      if (vote.getValue() > most) {
        leader = vote.getKey();
        most = vote.getValue();
        tied = false;
      } else if (vote.getValue() == most) {
        tied = true;
      }
    }
    return tied ? null : leader;
  }

  /** A window start: just after the nearest line break at most the slack ahead, else unsplit. */
  private static int lineStartAtOrAfter(String text, int position) {
    int limit = Math.min(text.length(), position + WINDOW_EDGE_SLACK);
    for (int i = position - 1; i < limit; i++) {
      if (text.charAt(i) == '\n') {
        return i + 1;
      }
    }
    return unsplit(text, position);
  }

  /** A window end: at the nearest line break at most the slack behind, else unsplit. */
  private static int lineEndAtOrBefore(String text, int position) {
    int limit = Math.max(0, position - WINDOW_EDGE_SLACK);
    for (int i = position; i >= limit; i--) {
      if (text.charAt(i) == '\n') {
        return i;
      }
    }
    return unsplit(text, position);
  }

  /** Moves a boundary between the halves of a surrogate pair before the pair. */
  private static int unsplit(String text, int position) {
    return Character.isHighSurrogate(text.charAt(position - 1))
        && Character.isLowSurrogate(text.charAt(position)) ? position - 1 : position;
  }

  /**
   * Creates a content preview from full content. When {@code isMarkdown} is true, strips
   * frontmatter, extracts the description field, skips the leading H1 heading, and converts
   * markdown to plain text. When false, only truncates.
   *
   * @param content the full content
   * @param maxChars maximum characters to include
   * @param isMarkdown whether the content is markdown (enables frontmatter/heading/syntax stripping)
   * @return the preview string
   */
  public static String contentPreview(String content, int maxChars, boolean isMarkdown) {
    return truncate(preparedText(content, isMarkdown), maxChars);
  }

  /** The full text the preview and the language windows are taken from. */
  private static String preparedText(String content, boolean isMarkdown) {
    if (content == null || content.isBlank()) {
      return "";
    }

    String preview;
    if (isMarkdown) {
      // 1. Detect and strip frontmatter
      FrontmatterResult fm = stripFrontmatter(content);

      // 2. Extract description from frontmatter (if any)
      String description = extractDescription(fm.rawFrontmatter());

      // 3. Skip leading H1 heading in body
      String body = skipLeadingHeading(fm.body()).strip();

      // 4. Strip markdown syntax from body
      body = stripMarkdown(body);

      // 5. Strip markdown syntax from description (authors often put markdown in description fields)
      if (description != null && !description.isBlank()) {
        description = stripMarkdown(description);
      }

      // 6. Compose preview: description first (visible in compact mode), then body for context
      if (description != null && !description.isBlank()) {
        preview = body.isEmpty() ? description : description + "\n\n" + body;
      } else {
        preview = body;
      }

      // 7. Collapse excessive whitespace
      preview = collapseWhitespace(preview);
    } else {
      preview = content.strip();
    }
    return preview;
  }

  /** Truncates to at most {@code maxChars} without splitting a surrogate pair. */
  private static String truncate(String text, int maxChars) {
    if (text.length() <= maxChars) {
      return text;
    }
    int cut = maxChars;
    if (cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1))
        && Character.isLowSurrogate(text.charAt(cut))) {
      cut--;
    }
    return text.substring(0, cut);
  }

  /** Overload that defaults to {@code isMarkdown = false} for backwards compatibility. */
  public static String contentPreview(String content, int maxChars) {
    return contentPreview(content, maxChars, false);
  }

  /**
   * Detects and strips frontmatter from content. Supports YAML ({@code ---}), YAML-Pandoc
   * ({@code ---}/{@code ...}), and TOML ({@code +++}).
   *
   * <p>Uses string scanning (gray-matter style), not regex. The opening delimiter must be at byte
   * 0 (after optional BOM). Returns the body text and raw frontmatter separately so the caller can
   * parse the frontmatter if needed.
   *
   * @param content the full content
   * @return body and raw frontmatter; if no frontmatter detected, body is the original content
   */
  /**
   * Extracts structured metadata from YAML frontmatter.
   * Returns an empty map if no frontmatter is found or content has no target keys.
   */
  public static java.util.Map<String, String> extractFrontmatterMetadata(String content) {
    FrontmatterResult fm = stripFrontmatter(content);
    if (fm.rawFrontmatter() == null) {
      return java.util.Map.of();
    }
    String[] lines = fm.rawFrontmatter().split("\\R");
    java.util.Map<String, String> meta = new java.util.HashMap<>();
    putIfPresent(meta, "source", extractYamlValue(lines, "source"));
    putIfPresent(meta, "category", extractYamlValue(lines, "category"));
    putIfPresent(meta, "published_at", extractYamlValue(lines, "published_at"));
    String author = extractYamlValue(lines, "author");
    if (author != null && !"None".equalsIgnoreCase(author)) {
      meta.put("author", author);
    }
    return meta.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(meta);
  }

  private static void putIfPresent(java.util.Map<String, String> map, String key, String value) {
    if (value != null && !value.isBlank()) {
      map.put(key, value);
    }
  }

  public static FrontmatterResult stripFrontmatter(String content) {
    if (content == null || content.isEmpty()) {
      return new FrontmatterResult(content != null ? content : "", null);
    }

    String s = content;
    // Strip optional BOM
    if (s.charAt(0) == BOM) {
      s = s.substring(1);
    }
    if (s.length() < 3) {
      return new FrontmatterResult(s, null);
    }

    // Detect opening delimiter
    String openDelim;
    boolean isYaml;
    if (s.startsWith("---")) {
      // Reject "----" (4+ dashes — not frontmatter)
      if (s.length() > 3 && s.charAt(3) == '-') {
        return new FrontmatterResult(s, null);
      }
      openDelim = "---";
      isYaml = true;
    } else if (s.startsWith("+++")) {
      if (s.length() > 3 && s.charAt(3) == '+') {
        return new FrontmatterResult(s, null);
      }
      openDelim = "+++";
      isYaml = false;
    } else {
      return new FrontmatterResult(s, null);
    }

    // Find end of opening delimiter line
    int afterOpen = indexOfLineEnd(s, openDelim.length());
    if (afterOpen < 0) {
      return new FrontmatterResult(s, null); // delimiter is the entire content
    }

    // Scan for closing delimiter line-by-line
    int pos = afterOpen;
    while (pos < s.length()) {
      int lineEnd = indexOfLineEnd(s, pos);
      String line = (lineEnd < 0 ? s.substring(pos) : s.substring(pos, lineEnd)).stripTrailing();

      if (isClosingDelimiter(line, openDelim, isYaml)) {
        int bodyStart = lineEnd < 0 ? s.length() : lineEnd;
        String rawFm = s.substring(afterOpen, pos);
        String body = s.substring(bodyStart);
        return new FrontmatterResult(body, rawFm);
      }

      if (lineEnd < 0) {
        break; // reached end without finding closing delimiter
      }
      pos = lineEnd;
    }

    // No closing delimiter found — not frontmatter
    return new FrontmatterResult(s, null);
  }

  /**
   * Extracts a description value from frontmatter text. Checks keys in priority order:
   * description, summary, excerpt, abstract.
   *
   * <p>Uses line scanning for both YAML ({@code key: value}) and TOML ({@code key = "value"})
   * formats. Handles single-line values, quoted values, and multi-line block scalars ({@code |}
   * and {@code >}).
   *
   * @param rawFrontmatter the text between frontmatter delimiters (null if none)
   * @return the description string, or null if not found
   */
  public static String extractDescription(String rawFrontmatter) {
    if (rawFrontmatter == null || rawFrontmatter.isBlank()) {
      return null;
    }

    String[] lines = rawFrontmatter.split("\\R");

    // Try each key in priority order across all lines
    for (String key : DESCRIPTION_KEYS) {
      String result = extractYamlValue(lines, key);
      if (result != null) {
        return result;
      }
    }

    // Fallback: TOML-style line scan (key = "value" or key = 'value')
    return extractDescriptionFromToml(rawFrontmatter);
  }

  /**
   * Skips the first ATX H1 heading ({@code # Title}) if it appears before any body content.
   * Only skips H1 (single {@code #}), not H2-H6. Blank lines before the heading are allowed.
   *
   * @param body the body text (after frontmatter stripping)
   * @return body without the leading H1, or unchanged if no leading H1 found
   */
  public static String skipLeadingHeading(String body) {
    if (body == null || body.isEmpty()) {
      return body != null ? body : "";
    }

    int pos = 0;
    int len = body.length();

    // Skip leading blank lines
    while (pos < len) {
      int lineEnd = indexOfLineEnd(body, pos);
      String line = (lineEnd < 0 ? body.substring(pos) : body.substring(pos, lineEnd)).strip();

      if (line.isEmpty()) {
        // blank line — skip
        pos = lineEnd < 0 ? len : lineEnd;
        continue;
      }

      // First non-blank line: check if it's an H1
      if (line.startsWith("# ") || line.equals("#")) {
        return lineEnd < 0 ? "" : body.substring(lineEnd);
      }

      // First non-blank line is not an H1 — return body unchanged
      return body;
    }

    return body;
  }

  /**
   * Strips markdown syntax from text, returning plain text. Uses commonmark-java's {@link
   * TextContentRenderer} to parse the markdown AST and extract text content.
   *
   * <p>Customizes rendering for links (strips URLs, keeps text), images (keeps alt text), and
   * blockquotes (strips guillemet decorators). Falls back to the raw input if parsing fails.
   */
  static String stripMarkdown(String text) {
    if (text == null || text.isBlank()) {
      return text != null ? text : "";
    }
    try {
      Parser parser = Parser.builder().build();
      Node document = parser.parse(text);
      TextContentRenderer renderer =
          TextContentRenderer.builder().nodeRendererFactory(new CleanTextNodeRendererFactory()).build();
      return renderer.render(document).strip();
    } catch (RuntimeException e) {
      return text;
    }
  }

  /**
   * Custom renderer that strips link URLs and blockquote guillemets for cleaner preview text.
   * Renders only the child text content for links, images, and blockquotes.
   */
  private static final class CleanTextNodeRendererFactory
      implements TextContentNodeRendererFactory {
    @Override
    public NodeRenderer create(TextContentNodeRendererContext context) {
      return new NodeRenderer() {
        @Override
        public Set<Class<? extends Node>> getNodeTypes() {
          return Set.of(Link.class, Image.class, BlockQuote.class);
        }

        @Override
        public void render(Node node) {
          Node child = node.getFirstChild();
          while (child != null) {
            Node next = child.getNext();
            context.render(child);
            child = next;
          }
        }
      };
    }
  }

  /** Collapses runs of 3+ newlines (with optional whitespace between) into a paragraph break. */
  static String collapseWhitespace(String text) {
    if (text == null || text.isEmpty()) {
      return text != null ? text : "";
    }
    return text.replaceAll("(\\s*\\n){3,}", "\n\n").strip();
  }

  // ---- Frontmatter helpers ----

  /** Returns the index after the next line ending (past \n or \r\n), or -1 if no newline. */
  private static int indexOfLineEnd(String s, int from) {
    int idx = s.indexOf('\n', from);
    return idx < 0 ? -1 : idx + 1;
  }

  private static boolean isClosingDelimiter(String line, String openDelim, boolean isYaml) {
    if (isYaml) {
      return "---".equals(line) || "...".equals(line);
    }
    return openDelim.equals(line);
  }

  /**
   * Extracts a YAML value for a top-level key. Handles:
   * <ul>
   *   <li>Inline: {@code key: value}</li>
   *   <li>Quoted: {@code key: "value"} or {@code key: 'value'}</li>
   *   <li>Block scalar: {@code key: |} or {@code key: >} followed by indented lines</li>
   * </ul>
   */
  public static String extractYamlValue(String[] lines, String key) {
    String prefix = key + ":";
    for (int i = 0; i < lines.length; i++) {
      String line = lines[i];
      String trimmed = line.strip();
      if (!trimmed.startsWith(prefix)) {
        continue;
      }
      // Ensure exact key match (not a prefix of a longer key)
      if (trimmed.length() > prefix.length()
          && trimmed.charAt(prefix.length()) != ' '
          && trimmed.charAt(prefix.length()) != '\t') {
        continue;
      }
      // Also reject indented lines (nested keys, not top-level)
      if (!line.isEmpty() && (line.charAt(0) == ' ' || line.charAt(0) == '\t')) {
        continue;
      }

      String val = trimmed.substring(prefix.length()).strip();

      // Block scalar indicator (| or >)
      if (val.equals("|") || val.equals(">") || val.equals("|-") || val.equals(">-")) {
        boolean fold = val.startsWith(">");
        return readBlockScalar(lines, i + 1, fold);
      }

      // Inline value — strip optional surrounding quotes
      val = stripYamlQuotes(val);
      return val.isBlank() ? null : val;
    }
    return null;
  }

  /** Reads a YAML block scalar (indented continuation lines) starting from the given line. */
  private static String readBlockScalar(String[] lines, int startLine, boolean fold) {
    StringBuilder sb = new StringBuilder();
    int blockIndent = -1;
    for (int i = startLine; i < lines.length; i++) {
      String line = lines[i];
      if (line.isEmpty() || line.isBlank()) {
        if (blockIndent >= 0) {
          sb.append(fold ? " " : "\n");
        }
        continue;
      }
      // Determine indent of first content line
      int indent = 0;
      while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
        indent++;
      }
      if (indent == 0) {
        break; // no indentation — end of block scalar
      }
      if (blockIndent < 0) {
        blockIndent = indent;
      }
      String content = indent <= line.length() ? line.substring(Math.min(blockIndent, indent)) : line;
      if (!sb.isEmpty()) {
        sb.append(fold ? " " : "\n");
      }
      sb.append(content.stripTrailing());
    }
    String result = sb.toString().strip();
    return result.isEmpty() ? null : result;
  }

  /** Strips surrounding single or double quotes from a YAML value. */
  private static String stripYamlQuotes(String val) {
    if (val.length() >= 2
        && ((val.startsWith("\"") && val.endsWith("\""))
            || (val.startsWith("'") && val.endsWith("'")))) {
      return val.substring(1, val.length() - 1);
    }
    return val;
  }

  /** Simple TOML line scan: finds key = "value" or key = 'value' for description keys. */
  private static String extractDescriptionFromToml(String toml) {
    for (String rawLine : toml.split("\\R")) {
      String line = rawLine.strip();
      for (String key : DESCRIPTION_KEYS) {
        if (line.startsWith(key) && line.length() > key.length()) {
          String rest = line.substring(key.length()).strip();
          if (rest.startsWith("=")) {
            String val = rest.substring(1).strip();
            // Strip surrounding quotes
            if (val.length() >= 2
                && ((val.startsWith("\"") && val.endsWith("\""))
                    || (val.startsWith("'") && val.endsWith("'")))) {
              val = val.substring(1, val.length() - 1);
            }
            if (!val.isBlank()) {
              return val.strip();
            }
          }
        }
      }
    }
    return null;
  }

  private static String detectLanguage(String window, boolean ofSeveral) {
    if (window.isEmpty()) {
      return null;
    }
    int latin = 0;
    int cyrillic = 0;
    int han = 0;
    int kana = 0;
    int hangul = 0;
    int arabic = 0;
    int devanagari = 0;
    int greek = 0;
    int examined = 0;
    for (int i = 0; i < window.length(); i++) {
      char ch = window.charAt(i);
      if (!Character.isLetter(ch)) {
        continue;
      }
      examined++;
      Character.UnicodeBlock block = Character.UnicodeBlock.of(ch);
      if (block == null) {
        continue;
      }
      if (isLatin(block)) {
        latin++;
      } else if (isCyrillic(block)) {
        cyrillic++;
      } else if (isHan(block)) {
        han++;
      } else if (isKana(block)) {
        kana++;
      } else if (isHangul(block)) {
        hangul++;
      } else if (isArabic(block)) {
        arabic++;
      } else if (isDevanagari(block)) {
        devanagari++;
      } else if (isGreek(block)) {
        greek++;
      }
    }
    if (examined < 8) {
      return null;
    }
    int threshold = Math.max(5, examined / 6);
    String[] scripts = {"zh", "ja", "ko", "ar", "hi", "ru", "el"};
    int[] counts = {han, kana, hangul, arabic, devanagari, cyrillic, greek};
    for (int i = 0; i < scripts.length; i++) {
      if (counts[i] >= threshold) {
        // One window of several votes for a script only where that script holds the majority of
        // its letters: a multilingual annex or a quoted block does not outvote the body.
        return !ofSeveral || 2 * counts[i] > examined ? scripts[i] : null;
      }
    }
    if (latin >= MIN_LATIN_LETTERS && latin >= examined * 0.8) {
      return LatinLanguageDetector.detect(window);
    }
    return null;
  }

  private static boolean isLatin(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.BASIC_LATIN
        || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
        || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL
        || block == Character.UnicodeBlock.LATIN_EXTENDED_B
        || block == Character.UnicodeBlock.LATIN_EXTENDED_C
        || block == Character.UnicodeBlock.LATIN_EXTENDED_D
        || block == Character.UnicodeBlock.LATIN_EXTENDED_E
        || block == Character.UnicodeBlock.LATIN_EXTENDED_F
        || block == Character.UnicodeBlock.LATIN_EXTENDED_G;
  }

  private static boolean isCyrillic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CYRILLIC
        || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B
        || block == Character.UnicodeBlock.CYRILLIC_EXTENDED_C;
  }

  private static boolean isHan(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT;
  }

  private static boolean isKana(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.HIRAGANA
        || block == Character.UnicodeBlock.KATAKANA
        || block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS;
  }

  private static boolean isHangul(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.HANGUL_SYLLABLES
        || block == Character.UnicodeBlock.HANGUL_JAMO
        || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
        || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_A
        || block == Character.UnicodeBlock.HANGUL_JAMO_EXTENDED_B;
  }

  private static boolean isArabic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.ARABIC
        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A
        || block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B
        || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
        || block == Character.UnicodeBlock.ARABIC_EXTENDED_A;
  }

  private static boolean isDevanagari(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.DEVANAGARI
        || block == Character.UnicodeBlock.DEVANAGARI_EXTENDED;
  }

  private static boolean isGreek(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.GREEK
        || block == Character.UnicodeBlock.GREEK_EXTENDED;
  }

}
