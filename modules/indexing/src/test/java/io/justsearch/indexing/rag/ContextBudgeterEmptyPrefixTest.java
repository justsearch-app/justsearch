/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** S12: an empty safe prefix is a rejected append, with no observable state change. */
final class ContextBudgeterEmptyPrefixTest {

  @ParameterizedTest(name = "rejectedPrefixPreservesStateAndNextOrdinal({arguments})[{index}]")
  @ValueSource(booleans = {false, true})
  void rejectedPrefixPreservesStateAndNextOrdinal(boolean later) {
    ContextBudgeter budgeter = new ContextBudgeter(later ? 21 : 7);
    if (later) {
      assertEquals(ContextBudgeter.AppendResult.APPENDED, budgeter.appendSection("a", "x"));
    }
    String before = budgeter.build();
    int length = budgeter.length();
    boolean empty = budgeter.isEmpty();
    var sections = List.copyOf(budgeter.sections());

    assertEquals(ContextBudgeter.AppendResult.STOPPED_BUDGET,
        budgeter.appendSection("b", "😀"));
    assertEquals(before, budgeter.build());
    assertEquals(length, budgeter.length());
    assertEquals(empty, budgeter.isEmpty());
    assertEquals(sections, budgeter.sections());

    // STOPPED_BUDGET tells a caller to stop this loop; it does not latch the budgeter shut.
    assertEquals(ContextBudgeter.AppendResult.APPENDED, budgeter.appendSection("c", "y"));
    int index = later ? 1 : 0;
    String separator = later ? ContextBudgeter.SECTION_SEPARATOR : "";
    assertEquals(before + separator + ContextBudgeter.sectionHeader(index + 1, "c") + "y",
        budgeter.build());
    assertEquals(new ContextBudgeter.Section("c", "y", false, index),
        budgeter.sections().get(index));
    assertEquals(later ? 21 : 7, budgeter.length());
    assertFalse(budgeter.isEmpty());
  }

  @ParameterizedTest(name = "nonemptyPrefixesPreserveUtf16Policy({arguments})[{index}]")
  @MethodSource("nonemptyPrefixes")
  void nonemptyPrefixesPreserveUtf16Policy(
      String content, int available, String expected, boolean truncated) {
    String header = ContextBudgeter.sectionHeader(1, "a");
    ContextBudgeter budgeter = new ContextBudgeter(header.length() + available);
    assertEquals(truncated ? ContextBudgeter.AppendResult.APPENDED_TRUNCATED
            : ContextBudgeter.AppendResult.APPENDED,
        budgeter.appendSection("a", content));
    assertEquals(header + expected, budgeter.build());
    assertEquals(List.of(new ContextBudgeter.Section("a", expected, truncated, 0)),
        budgeter.sections());
    assertEquals(header.length() + expected.length(), budgeter.length());
    assertTrue(budgeter.length() <= budgeter.maxChars());
  }

  private static Stream<Arguments> nonemptyPrefixes() {
    return Stream.of(
        Arguments.of("x", 1, "x", false),
        Arguments.of("😀", 2, "😀", false),
        Arguments.of("😀x", 2, "😀", true),
        Arguments.of("ab😀", 3, "ab", true),
        Arguments.of(" 😀", 1, " ", true),
        Arguments.of("e\u0301x", 1, "e", true));
  }

  @Test
  void malformedSurrogatePolicyIsUnchanged() {
    // Keep the malformed value out of parameter display names and their XML attributes.
    ContextBudgeter budgeter = new ContextBudgeter(7);
    assertEquals(ContextBudgeter.AppendResult.APPENDED_TRUNCATED,
        budgeter.appendSection("a", "\uD83Dx"));
    assertEquals("[1] a\n\uD83D", budgeter.build());
    assertEquals(List.of(new ContextBudgeter.Section("a", "\uD83D", true, 0)),
        budgeter.sections());
  }
}
