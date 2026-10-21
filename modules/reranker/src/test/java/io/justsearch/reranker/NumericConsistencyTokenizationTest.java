/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** D8/F1/NX1: a lexical value is consumed whole, or cannot support verification (NG2). */
class NumericConsistencyTokenizationTest {
  @Test
  void rejectsDroppedDecimalSignsAndSuffixes() {
    List<List<String>> pairs = List.of(
        List.of(".5", "5"), List.of("-.5", "5"), List.of("+.5%", "5%"),
        List.of(".5", "-.5"), List.of(".5%", ".5"), List.of("−.5", "5"),
        List.of(".٥", "5"), List.of("-.５%", "5%"), List.of(".𝟝", "5"),
        List.of(".5-.7", "5 and 7"), List.of("-.5--.7", "5 and 7"),
        List.of("10-.5", "10 and 5"), List.of("5", "5%%"));
    assertAll(pairs.stream().map(pair -> () -> {
      assertFalse(supports(pair.get(0), pair.get(1)), pair.toString());
      assertFalse(supports(pair.get(1), pair.get(0)), "reverse: " + pair);
    }));
  }

  @Test
  void acceptsOnlyExplicitNx1Equivalences() {
    List<List<String>> pairs = List.of(
        List.of(".5", "0.5"), List.of("+.5%", "0.5%"), List.of("-.5", "-0.5"),
        List.of("+15", "15"), List.of("+000.5", ".5"),
        List.of("−.٥", "-0.5"), List.of(".𝟝", "0.5"),
        List.of(".5-.7", "0.5 and 0.7"), List.of("5%-10%", "5% and 10%"),
        List.of("-.5%--.7%", "-0.5% and -0.7%"));
    assertAll(pairs.stream().map(pair -> () -> {
      assertTrue(supports(pair.get(0), pair.get(1)), pair.toString());
      assertTrue(supports(pair.get(1), pair.get(0)), "reverse: " + pair);
    }));
  }

  @Test
  void withholdsUnagreedNotationRatherThanSplittingIt() {
    List<String> unsupported = List.of(
        "--5", "++5", "+-5", "-+5", "- .5", "+ .5", "5-", "5+", "5 % %", "5%%", "%5",
        "$5", "-$5", "$-5", "5€", "€ 5", "5‰", "5‱",
        "1'500", "1’500", "1_500", "١٬٥٠٠", "١٫٥", "１．５", "1·5", "1:5",
        "1.2.3", "1..5", "5.%", "1e3", "1E-3", "1e+3", "1e", "1e-",
        "1/2", "1⁄2", "½", "1½", "5th", "21st", "2nd", "3rd", "5²", "2×10^3",
        "–5", "—5", "‐5", "‑5", "－5", "＋5", "±5", "∓5", "⁻5", "⁺5", "₋5", "₊5", "﹢5",
        "⸺5", "1 500", "1 500", "1․5", "1⋅5", "1˙5",
        "5–", "5—", "5- ", "5--", "-\u200b5", "5\u200b%", ".\u200b5", "5\u20e3",
        "➖5", "➕5", "+/-5", "-/+5", "-,.5", "5kg-10kg", "5°-10°", "JDK-15");
    assertAll(unsupported.stream().map(value -> () -> {
      assertFalse(supports(value, "1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3"), value);
      assertFalse(supports(value, value), "even identical unsupported notation: " + value);
      assertFalse(supports("5", value), "cannot extract a supporting subvalue: " + value);
    }));
  }

  @Test
  void preservesAgreedWholeValuesAndPunctuation() {
    List<List<String>> pairs = List.of(
        List.of("-15", "−15"), List.of("15 %", "15%"), List.of("0015 kg", "15 m"),
        List.of("1,500", "1500"), List.of("1 500", "1500"), List.of("1 500", "1500"),
        List.of("1 500", "1500"), List.of("12,34", "12 and 34"),
        List.of("10-20", "10 and 20"), List.of("10–20", "10 and 20"),
        List.of("10—20", "10 and 20"), List.of("2026-10-21", "2026 and 10 and 21"),
        List.of("5%--10%", "5% and -10%"), List.of("5%–10%", "5% and 10%"),
        List.of("5%—-10%", "5% and -10%"),
        List.of("2026/10/21", "2026 and 10 and 21"),
        List.of("(15), [15, 20]; 15!", "15 and 20"), List.of("'15'", "15"),
        List.of("‘15’", "15"), List.of("“15”", "15"), List.of("First sentence. 15", "15"),
        List.of("١٥", "15"), List.of("１５", "15"), List.of("𝟙𝟝", "15"));
    assertAll(pairs.stream().map(pair -> () -> assertTrue(supports(pair.get(0), pair.get(1)), pair.toString())));
  }

  private static boolean supports(String sentence, String passage) {
    return new NumericConsistency(List.of(NumericConsistency.claim("The value is " + sentence + ".")),
        List.of("The value is " + passage + ".")).supports(0, 0);
  }
}
