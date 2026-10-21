/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Numeric containment safeguard, shared by citation producers. It can only veto a candidate;
 * the producer still owns scores, thresholds and complete-scoring counts.
 */
public final class NumericConsistency {
  private static final String SEPARATORS = ", \\u00a0\\u202f";
  private static final Pattern NUMBER = Pattern.compile(
      "[+\\-]?(?:[0-9]+(?:[" + SEPARATORS + "][0-9]+)*(?:\\.[0-9]+)?|\\.[0-9]+)"
          + "(?:[ \\u00a0\\u202f]*[%\\u066a\\uff05])?");
  private static final Pattern UNGROUPED_NUMBER =
      Pattern.compile("[+\\-]?(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"
          + "(?:[ \\u00a0\\u202f]*[%\\u066a\\uff05])?");
  private static final Pattern GROUPED_INTEGER =
      Pattern.compile("[0-9]{1,3}(?:,[0-9]{3})+|[0-9]{1,3}(?: [0-9]{3})+"
          + "|[0-9]{1,3}(?:\\u00a0[0-9]{3})+|[0-9]{1,3}(?:\\u202f[0-9]{3})+");
  private static final Pattern REFERENCE =
      Pattern.compile("\\[[1-9][0-9]*\\](?:[ \\t]*\\[[1-9][0-9]*\\])*");
  private static final Pattern ORDINAL = Pattern.compile("(?m)^[ \\t]*[0-9]+[.)][ \\t]+");
  private static final Pattern INLINE_CODE = Pattern.compile("(`+)(.*?)\\1(?!`)", Pattern.DOTALL);
  // No agreed interpretation for these forms. Never turn their pieces into supporting values.
  private static final String UNSUPPORTED = "unsupported numeric notation";
  private static final Pattern UNIT_EXPONENT = Pattern.compile("(\\p{L}+)[²³]");
  private static final Pattern CURRENCY = Pattern.compile("\\p{Sc}[ \\u00a0\\u202f]*");
  // Separate parts with punctuation: spaces could turn 1/234/567 into the grouped integer 1234567.
  private static final Pattern PARTS = Pattern.compile(
      "(?<![\\p{L}\\p{N}./:])(?:[vV]?[0-9]+(?:\\.[0-9]+){2,}"
          + "|[0-9]+(?:/[0-9]+){2}|[0-9]+(?::[0-9]+)+)(?![0-9/:]|\\.[0-9.])");
  private static final Pattern RANGE = Pattern.compile(
      "([0-9](?:[ \\u00a0\\u202f]*[%\\u066a\\uff05])?)"
          + "(?:[ \\u00a0\\u202f]*(?![eE]\\b)[\\p{L}°]+(?=[-–—])"
          + "|(?![eE]\\b)[\\p{L}°]+[ \\u00a0\\u202f]*|[ \\u00a0\\u202f]*)[-–—]"
          + "[ \\u00a0\\u202f]*(?=[+\\-]?\\.?[0-9])");
  private static final Pattern UNSUPPORTED_NOTATION = Pattern.compile(
      "[\\p{No}\\p{Nl}]|[0-9][ \\u00a0\\u202f]*[‰‱]"
          + "|[0-9]['’][0-9]|[0-9][_٫٬．·․⋅˙:⁄/\\^×]|[_٫٬．·․⋅˙:⁄/\\^×][0-9]"
          + "|[0-9]\\p{L}+[+\\-]?[0-9]"
          + "|[\\p{M}\\p{Cf}]\\.?[0-9]|[0-9][\\p{M}\\p{Cf}]"
          + "|[0-9][\\p{Zs}&&[^ \\u00a0\\u202f]][0-9]"
          + "|[0-9](?:[eE](?![a-zA-Z])|(?:st|nd|rd|th)\\b)"
          + "|[\\p{L}\\p{So}][\\-–—][+\\-]?\\.?[0-9]"
          + "|[+\\-][/,][+\\-.,/ \\u00a0\\u202f]*[0-9]"
          + "|[±∓⁻⁺₋₊﹢＋➕➖\\p{Pd}&&[^\\-–—]][ \\u00a0\\u202f]*[+\\-]?\\.?[0-9]");

  private final List<Set<String>> claims;
  private final List<Set<String>> passages;

  /** Immutable numeric metadata travels beside, never inside, the persisted sentence key. */
  public record Claim(String text, Set<String> values) {
    public Claim {
      values = Set.copyOf(values);
    }
  }

  public NumericConsistency(List<Claim> claims, List<String> passages) {
    this.claims = claims.stream().map(Claim::values).toList();
    // Numeric-free answers retain the existing fast path, including their winner/tie ordering.
    this.passages = this.claims.stream().allMatch(Set::isEmpty)
        ? List.of() : passages.stream().map(NumericConsistency::claim).map(Claim::values).toList();
  }

  public boolean supports(int sentenceIndex, int passageIndex) {
    Set<String> required = claims.get(sentenceIndex);
    return required.isEmpty()
        || (!required.contains(UNSUPPORTED) && passages.get(passageIndex).containsAll(required));
  }

  /** Plain scorer entry point; Markdown-aware callers supply claims from their parsed blocks. */
  public static Claim claim(String text) {
    if (text == null) return new Claim(null, Set.of());
    String guarded = protectInlineCode(normalizeDigits(text));
    return new Claim(text, proseValues(ORDINAL.matcher(guarded).replaceAll(" ")));
  }

  /**
   * Values in prose with recognized reference markers removed. Parsed code must protect brackets
   * before calling this method; unlike the plain entry point this does not remove list ordinals.
   */
  public static Set<String> proseValues(String text) {
    if (text == null || text.isEmpty()) return Set.of();
    String numericText = withoutReferences(protectInlineCode(normalizeDigits(text)));
    numericText = UNIT_EXPONENT.matcher(numericText).replaceAll("$1");
    numericText = CURRENCY.matcher(numericText).replaceAll("");
    numericText = PARTS.matcher(numericText).replaceAll(match -> match.group().replaceFirst("^[vV]", "")
        .replaceAll("[./:]", ";"));
    numericText = RANGE.matcher(numericText).replaceAll("$1;");

    // Mark undecided lexical forms before collecting values. Keep separate decided values even
    // when an undecided form occurs in the same passage; only claims use the withholding marker.
    BitSet undecided = new BitSet(numericText.length());
    var unsupported = UNSUPPORTED_NOTATION.matcher(numericText);
    while (unsupported.find()) markUndecided(numericText, undecided, unsupported.start(), unsupported.end());
    List<MatchResult> numbers = numbers(numericText);
    for (MatchResult number : numbers) {
      if (incompleteNumber(numericText, number.start(), number.end())) {
        markUndecided(numericText, undecided, number.start(), number.end());
      }
    }
    Set<String> values = new HashSet<>();
    if (!undecided.isEmpty()) values.add(UNSUPPORTED);
    for (MatchResult number : numbers) {
      int unknown = undecided.nextSetBit(number.start());
      if (unknown < 0 || unknown >= number.end()) values.add(canonical(number.group()));
    }
    return Set.copyOf(values);
  }

  private static List<MatchResult> numbers(String text) {
    List<MatchResult> numbers = new ArrayList<>();
    var matcher = NUMBER.matcher(text);
    while (matcher.find()) {
      String integer = matcher.group().replaceFirst("^[+\\-]", "").split("\\.", 2)[0]
          .replaceFirst("[ \\u00a0\\u202f]*[%\\u066a\\uff05]$", "");
      if (integer.chars().anyMatch(c -> c == ',' || c == ' ' || c == '\u00a0' || c == '\u202f')
          && !GROUPED_INTEGER.matcher(integer).matches()) {
        var parts = UNGROUPED_NUMBER.matcher(text).region(matcher.start(), matcher.end());
        while (parts.find()) numbers.add(parts.toMatchResult());
      } else {
        numbers.add(matcher.toMatchResult());
      }
    }
    return numbers;
  }

  /** Consume an undecided form whole so none of its digit pieces can support another claim. */
  private static void markUndecided(String text, BitSet undecided, int start, int end) {
    while (start > 0 && notationCharacter(text.charAt(start - 1))) start--;
    while (end < text.length() && notationCharacter(text.charAt(end))) end++;
    undecided.set(start, end);
  }

  private static boolean notationCharacter(char character) {
    return Character.isLetterOrDigit(character) || ".+-–—%/'’_٫٬．·․⋅˙:⁄^×".indexOf(character) >= 0
        || switch (Character.getType(character)) {
          case Character.OTHER_NUMBER, Character.LETTER_NUMBER, Character.NON_SPACING_MARK,
              Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK, Character.FORMAT,
              Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.OTHER_SYMBOL,
              Character.DASH_PUNCTUATION -> true;
          default -> false;
        };
  }

  /** Regex search must never resume inside an unsupported signed/decimal/percent expression. */
  private static boolean incompleteNumber(String text, int start, int end) {
    int previous = start - 1;
    while (previous >= 0 && isSpace(text.charAt(previous))) previous--;
    if (previous >= 0) {
      char prefix = text.charAt(previous);
      if ((prefix == '.' && previous == start - 1)
          || isPercent(prefix)) return true;
      if (prefix == '+' || prefix == '-') {
        // A second minus may be a negative range endpoint: 5--3 or .5--.3.
        if (previous != start - 1 || prefix != '-' || previous == 0
            || !endsNumber(text.charAt(previous - 1))) return true;
      }
      if ((prefix == '–' || prefix == '—')
          && (previous == 0 || !endsNumber(text.charAt(previous - 1)))) return true;
    }
    int next = end;
    while (next < text.length() && isSpace(text.charAt(next))) next++;
    if (next == text.length()) return false;
    char suffix = text.charAt(next);
    if (isPercent(suffix)) return true;
    if (next == end && suffix == '.') {
      while (next < text.length() && text.charAt(next) == '.') next++;
      if (next < text.length()
          && (Character.isDigit(text.charAt(next)) || isPercent(text.charAt(next)))) return true;
    }
    // A dangling sign/range delimiter must not disappear, even when separated by whitespace.
    if (suffix == '+' || suffix == '-' || suffix == '–' || suffix == '—') {
      int following = next + 1;
      while (following < text.length() && isSpace(text.charAt(following))) following++;
      if (following < text.length() && Character.isLetter(text.charAt(following))) return false;
      if (following < text.length() && (text.charAt(following) == '-' || text.charAt(following) == '+')) following++;
      if (following < text.length() && text.charAt(following) == '.') following++;
      return following == text.length() || !Character.isDigit(text.charAt(following));
    }
    return false;
  }

  private static boolean isSpace(char character) {
    return Character.isWhitespace(character) || Character.isSpaceChar(character);
  }

  private static boolean endsNumber(char character) {
    return Character.isDigit(character) || isPercent(character);
  }

  private static boolean isPercent(char character) {
    return character == '%' || character == '\u066a' || character == '\uff05';
  }

  private static String canonical(String token) {
    String compact = token.replaceAll("[" + SEPARATORS + "]", "");
    if (compact.charAt(0) == '+') compact = compact.substring(1);
    int start = compact.charAt(0) == '-' ? 1 : 0;
    if (compact.charAt(start) == '.') compact = compact.substring(0, start) + "0" + compact.substring(start);
    int first = start;
    while (first + 1 < compact.length() && compact.charAt(first) == '0'
        && Character.isDigit(compact.charAt(first + 1))) first++;
    return compact.substring(0, start) + compact.substring(first);
  }

  private static String normalizeDigits(String text) {
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().forEach(cp -> {
      int digit = Character.digit(cp, 10);
      if (Character.isDigit(cp) && digit >= 0) out.append((char) ('0' + digit));
      else if (cp == '\u2212') out.append('-');
      else out.appendCodePoint(cp);
    });
    return out.toString();
  }

  private static String protectInlineCode(String text) {
    var code = INLINE_CODE.matcher(text);
    return code.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
        match.group().replace('[', '(').replace(']', ')')));
  }

  private static boolean prosePunctuation(char character) {
    return switch (Character.getType(character)) {
      case Character.OTHER_PUNCTUATION, Character.END_PUNCTUATION,
          Character.FINAL_QUOTE_PUNCTUATION -> true;
      default -> false;
    };
  }

  private static String withoutReferences(String text) {
    StringBuilder out = new StringBuilder(text);
    var marker = REFERENCE.matcher(text);
    while (marker.find()) {
      int previous = marker.start() - 1;
      while (previous >= 0 && (Character.isWhitespace(text.charAt(previous))
          || Character.isSpaceChar(text.charAt(previous)))) previous--;
      int next = marker.end();
      while (next < text.length() && (Character.isWhitespace(text.charAt(next))
          || Character.isSpaceChar(text.charAt(next)))) next++;
      boolean followsProse = previous >= 0
          && (Character.isLetterOrDigit(text.charAt(previous)) || prosePunctuation(text.charAt(previous)));
      boolean atBoundary = next == text.length() || prosePunctuation(text.charAt(next));
      if (followsProse && atBoundary) {
        for (int i = marker.start(); i < marker.end(); i++) out.setCharAt(i, ' ');
      }
    }
    return out.toString();
  }
}
