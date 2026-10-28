/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * <p>This is used by both live VDU update and switch-buffer replay paths
 * to ensure parity (P1.5).
 */
public final class LanguageUtils {

  private static final String DEFAULT_LANGUAGE = resolveDefaultLanguageTag();
  private static final char BOM = '\uFEFF';
  private static final List<String> DESCRIPTION_KEYS =
      List.of("description", "summary", "excerpt", "abstract");

  /** Result of frontmatter detection: body text and raw frontmatter (if any). */
  public record FrontmatterResult(String body, String rawFrontmatter) {}

  private LanguageUtils() {}

  /**
   * Resolves the language for a content preview.
   *
   * <p>Uses script-based heuristics to detect non-Latin languages;
   * falls back to the system default locale for Latin-script content.
   *
   * @param preview the content preview (first ~4K chars)
   * @return an IETF BCP 47 language tag (e.g., "en-US", "zh", "ja")
   */
  public static String resolveLanguage(String preview) {
    String detected = detectLanguage(preview);
    if (detected != null && !detected.isBlank()) {
      return detected;
    }
    // Script-based detection cannot distinguish Latin languages reliably;
    // treat them as the default locale.
    return DEFAULT_LANGUAGE != null && !DEFAULT_LANGUAGE.isBlank() ? DEFAULT_LANGUAGE : "und";
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

    // 8. Truncate
    if (preview.length() > maxChars) {
      int cut = maxChars;
      if (cut > 0 && Character.isHighSurrogate(preview.charAt(cut - 1))
          && Character.isLowSurrogate(preview.charAt(cut))) {
        cut--;
      }
      preview = preview.substring(0, cut);
    }
    return preview;
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

  private static String resolveDefaultLanguageTag() {
    try {
      Locale locale = Locale.getDefault();
      if (locale != null) {
        String tag = locale.toLanguageTag();
        if (tag != null && !tag.isBlank()) {
          return tag;
        }
      }
    } catch (RuntimeException ignored) {
      // best-effort
    }
    return "en-US";
  }

  /**
   * Very lightweight script heuristic:
   * - returns explicit language codes for non-Latin scripts
   * - returns null for Latin (caller should use default language tag)
   */
  private static String detectLanguage(String text) {
    if (text == null) {
      return null;
    }
    String sample = text.strip();
    if (sample.isEmpty()) {
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
    int limit = Math.min(sample.length(), 4096);
    for (int i = 0; i < limit; i++) {
      char ch = sample.charAt(i);
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
    if (han >= threshold) {
      return "zh";
    }
    if (kana >= threshold) {
      return "ja";
    }
    if (hangul >= threshold) {
      return "ko";
    }
    if (arabic >= threshold) {
      return "ar";
    }
    if (devanagari >= threshold) {
      return "hi";
    }
    if (cyrillic >= threshold) {
      return "ru";
    }
    if (greek >= threshold) {
      return "el";
    }
    if (latin >= threshold) {
      return null;
    }
    return null;
  }

  private static boolean isLatin(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.BASIC_LATIN
        || block == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
        || block == Character.UnicodeBlock.LATIN_EXTENDED_A
        || block == Character.UnicodeBlock.LATIN_EXTENDED_B
        || block == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL;
  }

  private static boolean isCyrillic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CYRILLIC
        || block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY;
  }

  private static boolean isHan(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
        || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
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
        || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
  }

  private static boolean isArabic(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.ARABIC
        || block == Character.UnicodeBlock.ARABIC_SUPPLEMENT;
  }

  private static boolean isDevanagari(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.DEVANAGARI;
  }

  private static boolean isGreek(Character.UnicodeBlock block) {
    return block == Character.UnicodeBlock.GREEK
        || block == Character.UnicodeBlock.GREEK_EXTENDED;
  }
}
