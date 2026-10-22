/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** NX3 owner decision: only standalone plain claim numbers participate in containment. */
class NumericConsistencyPlainTest {
  @Test
  void appliesNx3aNormalizationAndGroupedBoundaries() {
    String[][] pairs = {
        {"The voltage measured 5 volts.", "The voltage measured 1٫5 volts.", "5", "false"},
        {"The voltage measured 1.5 volts.", "The voltage measured 1٫5 volts.", "1.5", "true"},
        {"The voltage measured ١٫٥ volts.", "The voltage measured 1.5 volts.", "1.5", "true"},
        {"The voltage measured ١٫٥ volts.", "The voltage measured 5 volts.", "1.5", "false"},
        {"The voltage measured 5 volts.", "The voltage measured ١٫٥ volts.", "5", "false"},
        {"The voltage measured 5 volts.", "The voltage measured ٫5 volts.", "5", "false"},
        {"The voltage measured 5 volts.", "The voltage measured １．５ volts.", "5", "false"},
        {"The voltage measured １．５ volts.", "The voltage measured 1.5 volts.", "1.5", "true"},
        {"The voltage measured １．５ volts.", "The voltage measured 5 volts.", "1.5", "false"},
        {"The voltage measured 1.5 volts.", "The voltage measured １．５ volts.", "1.5", "true"},
        {"The meeting starts at 1：30.", "No number here.", "", "true"},
        {"The meeting starts at １：３０.", "No number here.", "", "true"},
        {"The shipment contains 5，000 bags.", "The shipment contains 5000 bags.", "5000", "true"},
        {"The shipment contains 5，000 bags.", "No number here.", "5000", "false"},
        {"The shipment contains ٥٬٠٠٠ bags.", "The shipment contains 5000 bags.", "5000", "true"},
        {"The shipment contains 5000 bags.", "The shipment contains ٥٬٠٠٠ bags.", "5000", "true"},
        {"The shipment contains 5000 bags.", "The shipment contains 5，000 bags.", "5000", "true"},
        {"The shipment contains 200 bags.", "The shipment contains 1٬200 bags.", "200", "false"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 5kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 50kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200 500kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1 200 5kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains 1٬200 5kg bags.", "1200", "true"},
        {"The shipment contains 1200 bags.", "The shipment contains １，２００ 5kg bags.", "1200", "true"},
        {"The shipment contains 1,200 5kg bags.", "The shipment contains 1200 bags.", "1200", "true"},
        {"The shipment contains 1,200 5kg bags.", "No number here.", "1200", "false"},
        {"The shipment contains 200 bags.", "The shipment contains 1,200 5kg bags.", "200", "false"},
        {"The shipment contains 1200 bags.", "The shipment contains 1,200.5 5kg bags.", "1200", "false"},
        {"The shipment contains 1200.5 bags.", "The shipment contains 1,200.5 5kg bags.", "1200.5", "true"},
        {"The value is 5².", "The value is 52.", "52", "true"},
        {"The value is 5².", "The value is 5.", "52", "false"},
        {"The value is ١٬٥٠٠.", "The value is 1500.", "1500", "true"},
        {"The value is 1 500.", "The value is 1500.", "1500", "true"},
        {"The value is 1 500.", "The value is 1500.", "1500", "true"},
        {"The value is 1․5.", "The value is 1.5.", "1.5", "true"},
        {"The voltage is ١٫٥.", "The values are 1 and 5.", "1.5", "false"},
        {"القيمة ١٫٥.", "Values are 1, 2, 3, 5 and -5 and 5%.", "1.5", "false"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var claim = NumericConsistency.claim(pair[0]);
      assertEquals(pair[2].isEmpty() ? Set.of() : Set.of(pair[2]), claim.values(), pair[0]);
      assertEquals(Boolean.parseBoolean(pair[3]), supports(pair[0], pair[1]),
          java.util.Arrays.toString(pair));
    }));
  }

  @Test
  void skipsTouchingFormsWithoutWithholding() {
    for (String form : List.of("%-5", "% -5", "%5", "0x10", "0b101", "5kg-10kg",
        "21/10/2026", "2026-10-21", "21.10.2026", "$5", "-$5", "$-5", "5€",
        "1:30", "v1.2.3", "1.2.3e4", "1e3", "5kg%", "-15", "+15", "15%", "5°",
        ".5", "-.5", "+.5%", "1/2", "1..5", "1_500", "5th",
        "値は１５です", "m²", "cm³")) {
      var claim = NumericConsistency.claim("The value is " + form + ".");
      assertEquals(Set.of(), claim.values(), "skipped means no checked values: " + form);
      assertTrue(new NumericConsistency(List.of(claim), List.of("No number here.")).supports(0, 0), form);
    }
  }

  @Test
  void acceptsWholePassageValuesWhateverTouchesThem() {
    String[][] pairs = {
        {"The value is 0.5.", "The value is .5."},
        {"The value is 15.", "The value is -15."},
        {"The value is 15.", "The value is 15%."},
        {"The value is 5.", "The value is 5kg%."},
        {"The value is 5.", "The value is $5."},
        {"The value is 10.", "The range is 5kg-10kg."},
        {"The value is 21.", "The date is 21/10/2026."},
        {"The value is 1.", "The time is 1:30e4."},
        {"The value is 10.", "The value is 0x10."},
        {"The value is 1200.", "The value is $1,200%."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () ->
        assertTrue(supports(pair[0], pair[1]), java.util.Arrays.toString(pair))));
  }

  @Test
  void rejectsSubvaluesAndCurrencyJoining() {
    String[][] pairs = {
        {"The value is 99.", "The value is 2099."},
        {"The value is 15.", "The value is 1.5."},
        {"The value is 1.5.", "The value is 15."},
        {"The price is 15.", "The price is 1$5."},
        {"The value is 1.", "The value is 1.2.3e4."},
        {"The value is 5.", "The value is .5."},
        {"The value is 200.", "The value is 1,200."},
        {"Opened in 1883 with a rate of 5kg%.", "Opened in 18830."}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () ->
        assertFalse(supports(pair[0], pair[1]), java.util.Arrays.toString(pair))));
  }

  @Test
  void requiresEveryPlainValueEvenBesideSkippedForms() {
    String sentence = "Opened in 1883; the rate is 5kg%.";
    assertEquals(Set.of("1883"), NumericConsistency.claim(sentence).values());
    assertTrue(supports(sentence, "Opened in 1883."));
    assertFalse(supports(sentence, "Opened in 18830; the rate is 5kg%."));
    assertFalse(supports("The values are 1883 and 1923.", "Opened in 1883."));
  }

  @Test
  void preservesPlainDigitNormalizationAndGrouping() {
    String[][] pairs = {
        {"15", "15"}, {"0015", "15"}, {"1.5", "1.5"}, {"1.50", "1.50"},
        {"1,500", "1500"}, {"1 500", "1500"}, {"1 500", "1500"}, {"1 500", "1500"},
        {"1,500,000", "1500000"}, {"١٥", "15"}, {"１５", "15"}, {"𝟙𝟝", "15"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      var claim = NumericConsistency.claim("The value is " + pair[0] + ".");
      assertEquals(Set.of(pair[1]), claim.values(), pair[0]);
      assertTrue(new NumericConsistency(List.of(claim), List.of("The value is " + pair[1] + "."))
          .supports(0, 0), pair[0]);
    }));
  }

  @Test
  void preservesPunctuationReferencesAndCode() {
    for (String sentence : List.of("`15. literal`", "The value is (15).", "The value is ‘15’.", "The value is 15...",
        "The value is \"15\".", "The value is `15`.", "The array is `[15]`.")) {
      assertEquals(Set.of("15"), NumericConsistency.claim(sentence).values(), sentence);
    }
    for (String sentence : List.of("The engine owns the index [4].",
        "The engine owns the index [4][7].", "4. The engine owns the index.",
        "索引归引擎所有 [4]。", "The engine owns the index.")) {
      assertEquals(Set.of(), NumericConsistency.claim(sentence).values(), sentence);
    }
  }

  @Test
  void followsExplicitWhitespaceAndPunctuationBoundaries() {
    String[][] pairs = {
        {"The value is 5 kg.", "5"}, {"The value is $ 5.", "5"},
        {"The value is 5 %.", "5"}, {"The label is 5: good.", "5"}
    };
    assertAll(java.util.Arrays.stream(pairs).map(pair -> () -> {
      assertEquals(Set.of(pair[1]), NumericConsistency.claim(pair[0]).values(), pair[0]);
      assertFalse(supports(pair[0], "No number here."), pair[0]);
    }));
    assertEquals(Set.of("5", "10"), NumericConsistency.claim("The range is 5 - 10.").values());
    assertFalse(supports("The range is 5 - 10.", "Only 5."));
    assertEquals(Set.of(), NumericConsistency.claim("The value is 12,34.").values());
    assertEquals(Set.of(), NumericConsistency.claim("The values are 1 2.").values());
  }

  private static boolean supports(String sentence, String passage) {
    return new NumericConsistency(List.of(NumericConsistency.claim(sentence)), List.of(passage))
        .supports(0, 0);
  }
}
