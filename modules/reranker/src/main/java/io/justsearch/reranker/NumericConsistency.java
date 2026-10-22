/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Numeric containment safeguard, shared by citation producers. It can only veto a candidate;
 * the producer still owns scores, thresholds and complete-scoring counts.
 */
public final class NumericConsistency {
  private static final String SEPARATORS = ", \\u00a0\\u202f";
  private static final String GROUPED_NUMBER =
      "[0-9]{1,3}(?:(?:,[0-9]{3})+|(?: [0-9]{3})+)(?:\\.[0-9]+)?";
  // A complete grouped value ends at whitespace; later quantities cannot change its value.
  // Otherwise consume adjacent digit groups and decimal points whole, never decimal pieces.
  private static final Pattern NUMBER = Pattern.compile(GROUPED_NUMBER + "(?=\\p{Zs})"
      + "|(?:[0-9]+|\\.[0-9]+)(?:[.,\\p{Zs}][0-9]+)*");
  private static final Pattern UNGROUPED_NUMBER = Pattern.compile("[0-9]+(?:\\.[0-9]+)*");
  private static final Pattern PLAIN_NUMBER = Pattern.compile(
      "[0-9]+(?:\\.[0-9]+)?|" + GROUPED_NUMBER + "|\\.[0-9]+");
  private static final Pattern REFERENCE =
      Pattern.compile("\\[[1-9][0-9]*\\](?:[ \\t]*\\[[1-9][0-9]*\\])*");
  private static final Pattern ORDINAL = Pattern.compile("(?m)^[ \\t]*[0-9]+[.)][ \\t]+");
  private static final Pattern INLINE_CODE = Pattern.compile("(`+)(.*?)\\1(?!`)", Pattern.DOTALL);

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
        ? List.of() : passages.stream().map(text -> values(normalizeDigits(text), false)).toList();
  }

  public boolean supports(int sentenceIndex, int passageIndex) {
    Set<String> required = claims.get(sentenceIndex);
    return required.isEmpty() || passages.get(passageIndex).containsAll(required);
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
    return values(withoutReferences(protectInlineCode(normalizeDigits(text))), true);
  }

  /** NX3: claims require plain boundaries; passages admit whole values with any decoration. */
  private static Set<String> values(String text, boolean plainOnly) {
    if (text == null || text.isEmpty()) return Set.of();
    Set<String> values = new HashSet<>();
    var numbers = NUMBER.matcher(text);
    while (numbers.find()) {
      if (PLAIN_NUMBER.matcher(numbers.group()).matches()) {
        if (!decimalFragment(text, numbers.start(), numbers.end())
            && (!plainOnly || (numbers.group().charAt(0) != '.' && plainBoundary(text, numbers.start() - 1, true)
                && plainBoundary(text, numbers.end(), false)))) {
          values.add(canonical(numbers.group()));
        }
      } else if (!plainOnly) {
        // Non-grouping commas/spaces separate passage values; decimal pieces never do.
        var parts = UNGROUPED_NUMBER.matcher(text).region(numbers.start(), numbers.end());
        while (parts.find()) {
          if (PLAIN_NUMBER.matcher(parts.group()).matches()
              && !decimalFragment(text, parts.start(), parts.end())) {
            values.add(canonical(parts.group()));
          }
        }
      }
    }
    return Set.copyOf(values);
  }

  private static boolean decimalFragment(String text, int start, int end) {
    if (start > 0 && text.charAt(start - 1) == '.') return true;
    int next = end;
    while (next < text.length() && text.charAt(next) == '.') next++;
    return next > end && next < text.length() && Character.isDigit(text.charAt(next));
  }

  private static boolean plainBoundary(String text, int position, boolean before) {
    if (position < 0 || position >= text.length()) return true;
    char character = text.charAt(position);
    if (Character.isWhitespace(character) || Character.isSpaceChar(character)) return true;
    if (character == ',' || character == ':' || character == '\'' || character == '’') {
      int other = position + (before ? -1 : 1);
      if (other >= 0 && other < text.length() && Character.isDigit(text.charAt(other))) return false;
    }
    return ".,;:!?\"'()[]{}‘’“”«»‹›。！？、，；：".indexOf(character) >= 0;
  }

  private static String canonical(String token) {
    String compact = token.replaceAll("[" + SEPARATORS + "]", "").replaceFirst("^0+(?=[0-9])", "");
    return compact.startsWith(".") ? "0" + compact : compact;
  }

  private static String normalizeDigits(String text) {
    if (text == null) return "";
    String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .replace('\u066b', '.').replace('\u066c', ',');
    StringBuilder out = new StringBuilder(normalized.length());
    normalized.codePoints().forEach(cp -> {
      int digit = Character.digit(cp, 10);
      if (Character.isDigit(cp) && digit >= 0) out.append((char) ('0' + digit));
      else out.appendCodePoint(cp);
    });
    return out.toString();
  }

  private static String protectInlineCode(String text) {
    // Quotes retain code boundaries without making an initial code literal a list ordinal.
    var code = INLINE_CODE.matcher(text);
    return code.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
        match.group().replace('[', '(').replace(']', ')').replace('`', '\'')));
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
