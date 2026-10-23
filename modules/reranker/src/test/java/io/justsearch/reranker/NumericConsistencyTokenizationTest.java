/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Historical literal corpus under NX3; replaced form rules are sourced to the owner amendment. */
class NumericConsistencyTokenizationTest {
  @Test
  void comparesNx2FormerlyWithheldDecidedForms() {
    String[][] forms = {
        {"$5", "true"},
        {"-$5", "true"},
        {"$-5", "true"},
        {"5€", "true"},
        {"€ 5", "true"},
        {"1:5", "true"},
        {"1.2.3", "false"},
        {"5kg-10kg", "true"},
        {"5°-10°", "true"}
    };
    assertAll(java.util.Arrays.stream(forms).map(form -> () -> {
      assertTrue(supports(form[0], "1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3"), form[0]);
      assertTrue(supports(form[0], form[0]), form[0]);
      assertEquals(Boolean.parseBoolean(form[1]), supports("5", form[0]), form[0]);
    }));
  }

  @Test
  void checksFormerNx2RejectionsUnderNx3() {
    // NX3: "The guard checks plain numbers only." Nonplain claims never withhold.
    String[][] pairs = {
        {"The rate is %-5.", "The rate is 5.", "true"},
        {"Oran %-5.", "Oran 5.", "true"},
        {"The rate is % -5.", "The rate is -5.", "true"},
        {"The rate is %5.", "The rate is 5.", "true"},
        {"The rate is %-5.", "The rate is %-5.", "true"},
        {"The rate is % -5.", "The rate is % -5.", "true"},
        {"The value is 0x10.", "The values are 0 and 10.", "true"},
        {"The value is 0x10.", "The value is 0x10.", "true"},
        {"The value is 0.", "The value is 0x10.", "true"},
        {"The value is 10.", "The value is 0x10.", "true"},
        {"The value is 1883 and 0x10.", "The values are 1883, 0 and 10.", "true"},
        {"The date is 21/10/2026.", "The parts are 21 and 2026.", "true"},
        {"The date is 21.10.2026.", "The parts are 21 and 2026.", "true"},
        {"The fraction is 3/4.", "The parts are 3 and 4.", "true"},
        {"The interval is 5kg-10kg.", "The value is 5 kg.", "true"},
        {"The price is $5.", "The price is 15.", "true"},
        {"The price is $-5.", "The price is 5.", "true"},
        {"The time is 1:30.", "The value is 1.", "true"},
        {"The version is v1.2.3.", "The values are 1 and 2.", "true"},
        {"The value is 1.5.", "The values are 1 and 5.", "false"},
        {"Opened in 1883.", "Opened in 18830; the value is 0x10.", "false"},
        {"The area is 5m².", "The area is 50 m².", "true"},
        {"The value is 0b101.", "The values are 0 and 101.", "true"},
        {"The value is 101.", "The value is 0b101.", "true"},
        {"The rate is ％-٥.", "The rate is 5.", "true"},
        {"The value is 1.", "The value is 1e3.", "true"},
        {"The value is 3.", "The value is 1e3.", "true"},
        {"The value is 1.", "The value is 1/2.", "true"},
        {"The value is 2.", "The value is 1/2.", "true"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var guard = new NumericConsistency(List.of(NumericConsistency.claim(pair[0])), List.of(pair[1]));
      assertEquals(Boolean.parseBoolean(pair[2]), guard.supports(0, 0), java.util.Arrays.toString(pair));
    }));
  }

  @Test
  void extractsNx2DecidedValuesBesideUndecidedForms() {
    String[][] pairs = {
        {"The date is 21/10/2026.", "The date is 21/10/2026."},
        {"The date is 21/10/2026.", "The parts are 21, 10 and 2026."},
        {"The date is 2026-10-21.", "The parts are 2026, 10 and 21."},
        {"The date is 21.10.2026.", "The parts are 21, 10 and 2026."},
        {"The date is ٢٠٢٦/١٠/٢١.", "The parts are 2026, 10 and 21."},
        {"The interval is 5kg-10kg.", "The interval is 5kg-10kg."},
        {"The interval is 5kg-10kg.", "The values are 5 and 10."},
        {"The interval is 5–10 kg.", "The values are 5 and 10."},
        {"The interval is 5°-10°.", "The values are 5 and 10."},
        {"The price is $5.", "The price is $5."},
        {"The price is $5.", "The value is 5."},
        {"The price is 5 €.", "The value is 5."},
        {"The price is € 5.", "The value is 5."},
        {"The price is -$5.", "The value is -5."},
        {"The price is $-5.", "The value is -5."},
        {"The time is 1:30.", "The time is 1:30."},
        {"The time is 1:30.", "The parts are 1 and 30."},
        {"The version is v1.2.3.", "The version is v1.2.3."},
        {"The version is v1.2.3.", "The parts are 1, 2 and 3."},
        {"The value is 1.2.3.", "The parts are 1, 2 and 3."},
        {"The area is 5m².", "The value is 5."},
        {"Opened in 1883.", "Opened in 1883; admission costs $5."},
        {"Opened in 1883.", "Opened in 1883; the value is 0x10."},
        {"Opened in 1883.", "The value is 0x10; opened in 1883."},
        {"Opened in 1883.", "Opened in 1883; the fraction is 3/4."},
        {"Opened in 1883.", "Opened in 1883; the rate is %-5."},
        {"Opened in 1883.", "Opened in 1883; the value is 1e3."},
        {"Opened in 1883.", "Opened in 1883; the value is --5."},
        {"The value is 5.", "The value is 0x10; a separate value is 5."},
        {"The value is 5.", "The rate is %-5; a separate value is 5."},
        {"The interval is 5 kg–10 kg.", "The values are 5 and 10."},
        {"The interval is 10 - 20.", "The values are 10 and 20."},
        {"The interval is 10– 20.", "The values are 10 and 20."},
        {"The interval is -5 - -3.", "The values are -5 and -3."},
        {"Opened in 1883.", "The value is 0x10,1883."},
        {"Opened in 1883.", "The value is 1883,0x10."},
        {"Opened in 1883.", "The value is 0x10 1883."},
        {"Opened in 1883.", "The value is 1883 0x10."},
        {"The date is 1/234/567.", "The parts are 1, 234 and 567."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var guard = new NumericConsistency(List.of(NumericConsistency.claim(pair[0])), List.of(pair[1]));
      assertTrue(guard.supports(0, 0), java.util.Arrays.toString(pair));
    }));
  }

  @Test
  void ignoresNx2UnitExponents() {
    String[][] pairs = {
        {"The area is measured in m².", "Some passage."},
        {"Volume is measured in cm³.", "Some passage."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var guard = new NumericConsistency(List.of(NumericConsistency.claim(pair[0])), List.of(pair[1]));
      assertTrue(guard.supports(0, 0), java.util.Arrays.toString(pair));
      assertEquals(java.util.Set.of(), NumericConsistency.claim(pair[0]).values());
    }));
  }

  @Test
  void skipsFormerNx1ClaimForms() {
    List<List<String>> pairs = List.of(
        List.of(".5", "5", "false"), List.of("-.5", "5", "false"), List.of("+.5%", "5%", "true"),
        List.of(".5", "-.5", "true"), List.of(".5%", ".5", "true"), List.of("−.5", "5", "false"),
        List.of(".٥", "5", "false"), List.of("-.５%", "5%", "true"), List.of(".𝟝", "5", "false"),
        List.of(".5-.7", "5 and 7", "false"), List.of("-.5--.7", "5 and 7", "false"),
        List.of("10-.5", "10 and 5", "false"), List.of("5", "5%%", "true"));
    assertAll(pairs.stream().map(pair -> () -> {
      assertTrue(supports(pair.get(0), pair.get(1)), pair.toString());
      // NX3 skips nonplain reverse claims; a plain 5 still cannot be a decimal subvalue.
      assertEquals(Boolean.parseBoolean(pair.get(2)), supports(pair.get(1), pair.get(0)), "reverse: " + pair);
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
  void doesNotWithholdFormerlyUnsupportedClaims() {
    List<String> unsupported = List.of(
        "--5", "++5", "+-5", "-+5", "- .5", "+ .5", "5-", "5+", "5 % %", "5%%", "%5",
        "5‰", "5‱",
        "1'500", "1’500", "1_500", "1·5",
        "1..5", "5.%", "1e3", "1E-3", "1e+3", "1e", "1e-",
        "1/2", "1⁄2", "½", "1½", "5th", "21st", "2nd", "3rd", "2×10^3",
        "–5", "—5", "‐5", "‑5", "－5", "＋5", "±5", "∓5", "⁻5", "⁺5", "₋5", "₊5", "﹢5",
        "⸺5", "1⋅5", "1˙5",
        "5–", "5—", "5- ", "5--", "-\u200b5", "5\u200b%", ".\u200b5", "5\u20e3",
        "➖5", "➕5", "+/-5", "-/+5", "-,.5", "JDK-15");
    assertAll(unsupported.stream().map(value -> () -> {
      assertTrue(supports(value, "1, 2, 3, 5, 10, 21, 500 and -5 and 5% and -3"), value);
      assertTrue(supports(value, value), "NX3 never withholds a skipped claim: " + value);
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
        List.of("5%--10%", "5% and -10%"),
        List.of("5 %-10%", "5% and 10%"), List.of("5 %--10%", "5% and -10%"), List.of("5%–10%", "5% and 10%"),
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
