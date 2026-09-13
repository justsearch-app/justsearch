package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pure unit tests for {@link AnswerSegmentation#splitSentences}. No index or services needed. */
@DisplayName("AnswerSegmentation splitSentences")
class WorkerSearchServiceSplitSentencesTest {

  @Test
  @DisplayName("splits simple sentences")
  void splitSimple() {
    List<String> result =
        AnswerSegmentation.splitSentences(
            "First sentence. Second sentence. Third sentence.");
    assertEquals(3, result.size());
    assertEquals("First sentence.", result.get(0));
    assertEquals("Second sentence.", result.get(1));
    assertEquals("Third sentence.", result.get(2));
  }

  @Test
  @DisplayName("handles question marks and exclamation points")
  void handleQuestionAndExclamation() {
    List<String> result =
        AnswerSegmentation.splitSentences(
            "What is machine learning? It uses neural networks! That is interesting.");
    assertEquals(3, result.size());
    assertTrue(result.get(0).endsWith("?"), "First sentence should end with ?");
    assertTrue(result.get(1).endsWith("!"), "Second sentence should end with !");
  }

  @Test
  @DisplayName("returns empty list for blank input")
  void emptyInput() {
    assertEquals(List.of(), AnswerSegmentation.splitSentences(""));
    assertEquals(List.of(), AnswerSegmentation.splitSentences("   "));
    assertEquals(List.of(), AnswerSegmentation.splitSentences(null));
  }

  @Test
  @DisplayName("handles single sentence without trailing period")
  void singleSentenceNoPeriod() {
    List<String> result = AnswerSegmentation.splitSentences("Just one sentence");
    assertEquals(1, result.size());
    assertEquals("Just one sentence", result.get(0));
  }
}
