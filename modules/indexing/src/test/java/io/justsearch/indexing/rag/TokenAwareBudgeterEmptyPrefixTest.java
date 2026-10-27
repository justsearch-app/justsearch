/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Preservation control using a deterministic UTF-16 counter, without a tokenizer or model. */
final class TokenAwareBudgeterEmptyPrefixTest {

  @ParameterizedTest(name = "emptyPrefixAlreadyStopsWithoutMutation({arguments})[{index}]")
  @ValueSource(booleans = {false, true})
  void emptyPrefixAlreadyStopsWithoutMutation(boolean later) {
    TokenAwareBudgeter budgeter =
        new TokenAwareBudgeter(later ? 21 : 7, text -> Optional.of(text.length()));
    if (later) {
      assertEquals(TokenAwareBudgeter.AppendResult.APPENDED, budgeter.appendSection("a", "x"));
    }
    String before = budgeter.build();
    int length = budgeter.length();
    int tokens = budgeter.estimatedTokens();
    boolean empty = budgeter.isEmpty();
    var sections = List.copyOf(budgeter.sections());

    assertEquals(TokenAwareBudgeter.AppendResult.STOPPED_BUDGET,
        budgeter.appendSection("b", "😀"));
    assertEquals(before, budgeter.build());
    assertEquals(length, budgeter.length());
    assertEquals(tokens, budgeter.estimatedTokens());
    assertEquals(empty, budgeter.isEmpty());
    assertEquals(sections, budgeter.sections());

    assertEquals(TokenAwareBudgeter.AppendResult.APPENDED, budgeter.appendSection("c", "y"));
    int index = later ? 1 : 0;
    assertEquals(before + (later ? ContextBudgeter.SECTION_SEPARATOR : "")
        + ContextBudgeter.sectionHeader(index + 1, "c") + "y", budgeter.build());
    assertEquals(new ContextBudgeter.Section("c", "y", false, index),
        budgeter.sections().get(index));
    assertEquals(later ? 21 : 7, budgeter.estimatedTokens());
  }
}
