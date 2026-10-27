package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.knowledge.QueryType;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("QueryClassifier — rule-based pre-retrieval query classification (306)")
class QueryClassifierTest {

  // Frozen labels from d036a07a9b9, covering the baseline and c4ca2dcaf6 test inputs.
  // Restoring these syntactic labels makes no claim about semantic intent or model quality.
  @ParameterizedTest(name = "baselineClassificationTable({index})")
  @MethodSource("baselineCases")
  void baselineClassificationTable(String query, QueryType expected) {
    assertEquals(expected, QueryClassifier.classify(query));
  }

  static Stream<Arguments> baselineCases() {
    return Stream.of(
        Arguments.of("report.pdf quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("REPORT.PDF", QueryType.NAVIGATIONAL),
        Arguments.of("how to write a .py script", QueryType.INFORMATIONAL),
        Arguments.of("convert .json to .csv", QueryType.INFORMATIONAL),
        Arguments.of("src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("C:\\Users\\docs\\notes", QueryType.NAVIGATIONAL),
        Arguments.of("./config", QueryType.NAVIGATIONAL),
        Arguments.of("../parent/dir", QueryType.NAVIGATIONAL),
        Arguments.of("pros/cons of SPLADE", QueryType.INFORMATIONAL),
        Arguments.of("search and/or replace", QueryType.INFORMATIONAL),
        Arguments.of("worker_config_snapshot", QueryType.NAVIGATIONAL),
        Arguments.of("cuda-12", QueryType.NAVIGATIONAL),
        Arguments.of("optimization", QueryType.EXPLORATORY),
        Arguments.of("x", QueryType.EXPLORATORY),
        Arguments.of("\"exact phrase match\"", QueryType.EXACT_MATCH),
        Arguments.of("\"error\"", QueryType.EXACT_MATCH),
        Arguments.of("\"partial quote", QueryType.INFORMATIONAL),
        Arguments.of("how to configure SSL", QueryType.INFORMATIONAL),
        Arguments.of("what is JustSearch used for", QueryType.INFORMATIONAL),
        Arguments.of("why does search return zero", QueryType.INFORMATIONAL),
        Arguments.of("how search", QueryType.INFORMATIONAL),
        Arguments.of("report", QueryType.EXPLORATORY),
        Arguments.of("search", QueryType.EXPLORATORY),
        Arguments.of("search ranking quality", QueryType.INFORMATIONAL),
        Arguments.of("GPU configuration", QueryType.INFORMATIONAL),
        Arguments.of(null, QueryType.INFORMATIONAL),
        Arguments.of("", QueryType.INFORMATIONAL),
        Arguments.of("   ", QueryType.INFORMATIONAL),
        Arguments.of("report.pdf", QueryType.NAVIGATIONAL),
        Arguments.of("notes.txt", QueryType.NAVIGATIONAL),
        Arguments.of("data.csv", QueryType.NAVIGATIONAL),
        Arguments.of("README.md", QueryType.NAVIGATIONAL),
        Arguments.of("config.yaml", QueryType.NAVIGATIONAL),
        Arguments.of("app.java", QueryType.NAVIGATIONAL),
        Arguments.of("index.ts", QueryType.NAVIGATIONAL),
        Arguments.of("main.py", QueryType.NAVIGATIONAL),
        Arguments.of("server.go", QueryType.NAVIGATIONAL),
        Arguments.of("lib.rs", QueryType.NAVIGATIONAL),
        Arguments.of("ResolvedConfigBuilder", QueryType.NAVIGATIONAL),
        Arguments.of("SearchOrchestrator", QueryType.NAVIGATIONAL),
        Arguments.of("QueryClassifier", QueryType.NAVIGATIONAL),
        Arguments.of("how do I configure settings.json", QueryType.NAVIGATIONAL),
        Arguments.of("why src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("why does src/main/java fail", QueryType.NAVIGATIONAL),
        Arguments.of("what does \\d mean", QueryType.NAVIGATIONAL),
        Arguments.of("Quarterly Report.pdf", QueryType.NAVIGATIONAL),
        Arguments.of("C:\\My Files\\notes", QueryType.NAVIGATIONAL),
        Arguments.of("\"src/main/java\"", QueryType.NAVIGATIONAL),
        Arguments.of("\"src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("\"foo\" OR \"bar\"", QueryType.EXACT_MATCH),
        Arguments.of("\"foo\\\"bar\"", QueryType.NAVIGATIONAL),
        Arguments.of("\"\"", QueryType.EXPLORATORY),
        Arguments.of("\"  \"", QueryType.EXACT_MATCH),
        Arguments.of("wie konfiguriere ich settings.json", QueryType.NAVIGATIONAL),
        Arguments.of("pourquoi src/main/java échoue", QueryType.NAVIGATIONAL),
        Arguments.of("why\u2003src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("why\u00a0src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("why\u3000src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("\u00a0report.pdf\u00a0", QueryType.EXPLORATORY),
        Arguments.of("\u00a0\u2003", QueryType.EXPLORATORY),
        Arguments.of("how do I configure settings.json?", QueryType.INFORMATIONAL),
        Arguments.of("\"report.pdf\"", QueryType.EXACT_MATCH),
        Arguments.of(".py", QueryType.EXPLORATORY),
        Arguments.of("*:*", QueryType.EXPLORATORY),
        Arguments.of("\"", QueryType.EXPLORATORY),
        Arguments.of("foo\"bar", QueryType.EXPLORATORY),
        Arguments.of("report.pdf\"", QueryType.EXPLORATORY),
        Arguments.of("\"report.pdf", QueryType.NAVIGATIONAL),
        Arguments.of("\"\"\"", QueryType.EXACT_MATCH),
        Arguments.of("\"\"\"\"", QueryType.EXACT_MATCH),
        Arguments.of("\"foo\"\"bar\"", QueryType.EXACT_MATCH),
        Arguments.of("\"a\"b\"", QueryType.EXACT_MATCH),
        Arguments.of("\"\u00a0\"", QueryType.EXACT_MATCH),
        Arguments.of("\"\t\u2003\u3000\"", QueryType.EXACT_MATCH),
        Arguments.of("\"C:\\My Files\\notes\"", QueryType.NAVIGATIONAL),
        Arguments.of("\"../parent/dir\"", QueryType.NAVIGATIONAL),
        Arguments.of("\u00a0\"exact phrase\"\u3000", QueryType.INFORMATIONAL),
        Arguments.of("report.pdf" + "\t" + "quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf" + "\n" + "quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf" + "\r" + "quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf" + "\f" + "quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf" + "\u000b" + "quarterly", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf" + "\u2003" + "quarterly", QueryType.EXPLORATORY),
        Arguments.of("report.pdf" + "\u00a0" + "quarterly", QueryType.EXPLORATORY),
        Arguments.of("report.pdf" + "\u3000" + "quarterly", QueryType.EXPLORATORY),
        Arguments.of(" ", QueryType.INFORMATIONAL),
        Arguments.of(" " + "report.pdf" + " ", QueryType.NAVIGATIONAL),
        Arguments.of(" " + "\"phrase\"" + " ", QueryType.EXACT_MATCH),
        Arguments.of("\t", QueryType.INFORMATIONAL),
        Arguments.of("\t" + "report.pdf" + "\t", QueryType.NAVIGATIONAL),
        Arguments.of("\t" + "\"phrase\"" + "\t", QueryType.EXACT_MATCH),
        Arguments.of("\n", QueryType.INFORMATIONAL),
        Arguments.of("\n" + "report.pdf" + "\n", QueryType.NAVIGATIONAL),
        Arguments.of("\n" + "\"phrase\"" + "\n", QueryType.EXACT_MATCH),
        Arguments.of("\r", QueryType.INFORMATIONAL),
        Arguments.of("\r" + "report.pdf" + "\r", QueryType.NAVIGATIONAL),
        Arguments.of("\r" + "\"phrase\"" + "\r", QueryType.EXACT_MATCH),
        Arguments.of("\f", QueryType.INFORMATIONAL),
        Arguments.of("\f" + "report.pdf" + "\f", QueryType.NAVIGATIONAL),
        Arguments.of("\f" + "\"phrase\"" + "\f", QueryType.EXACT_MATCH),
        Arguments.of("\u000b", QueryType.INFORMATIONAL),
        Arguments.of("\u000b" + "report.pdf" + "\u000b", QueryType.NAVIGATIONAL),
        Arguments.of("\u000b" + "\"phrase\"" + "\u000b", QueryType.EXACT_MATCH),
        Arguments.of("\u2003", QueryType.INFORMATIONAL),
        Arguments.of("\u2003" + "report.pdf" + "\u2003", QueryType.NAVIGATIONAL),
        Arguments.of("\u2003" + "\"phrase\"" + "\u2003", QueryType.EXACT_MATCH),
        Arguments.of("\u3000", QueryType.INFORMATIONAL),
        Arguments.of("\u3000" + "report.pdf" + "\u3000", QueryType.NAVIGATIONAL),
        Arguments.of("\u3000" + "\"phrase\"" + "\u3000", QueryType.EXACT_MATCH),
        Arguments.of("why src/main/java?", QueryType.NAVIGATIONAL),
        Arguments.of("why does src/main/java fail?", QueryType.NAVIGATIONAL),
        Arguments.of("what does \\d mean?", QueryType.NAVIGATIONAL),
        Arguments.of("pourquoi src/main/java échoue?", QueryType.NAVIGATIONAL),
        Arguments.of("settings.json は どこ", QueryType.NAVIGATIONAL),
        Arguments.of("لماذا src/main/java يفشل", QueryType.NAVIGATIONAL),
        Arguments.of("report.pdf\u2003quarterly", QueryType.EXPLORATORY),
        Arguments.of("'plain'", QueryType.EXPLORATORY),
        Arguments.of("‘plain’", QueryType.EXPLORATORY),
        Arguments.of("“plain”", QueryType.EXPLORATORY),
        Arguments.of("\u200b", QueryType.EXPLORATORY),
        Arguments.of("foo\u200bbar", QueryType.EXPLORATORY),
        Arguments.of("report.pdf?", QueryType.EXPLORATORY),
        Arguments.of("report.pdf,", QueryType.EXPLORATORY),
        Arguments.of("and/or", QueryType.EXPLORATORY),
        Arguments.of("設定.jsonは何？", QueryType.EXPLORATORY),
        Arguments.of("2026-10-26", QueryType.NAVIGATIONAL),
        Arguments.of("iPhone", QueryType.NAVIGATIONAL),
        Arguments.of("GPT-4", QueryType.NAVIGATIONAL),
        Arguments.of("release_v2", QueryType.NAVIGATIONAL),
        Arguments.of("https://example.org", QueryType.NAVIGATIONAL),
        Arguments.of("\\d", QueryType.NAVIGATIONAL),
        Arguments.of("src/main/java为什么失败？", QueryType.NAVIGATIONAL),
        Arguments.of("'src/main/java'", QueryType.NAVIGATIONAL),
        Arguments.of("“src/main/java”", QueryType.NAVIGATIONAL),
        Arguments.of("wie konfiguriere ich settings.json?", QueryType.INFORMATIONAL),
        Arguments.of("prefix \"foo\"", QueryType.INFORMATIONAL),
        Arguments.of("foo\" bar", QueryType.INFORMATIONAL),
        Arguments.of("'two words'", QueryType.INFORMATIONAL),
        Arguments.of("“two words”", QueryType.INFORMATIONAL),
        Arguments.of("\t\n\r\f\u000b", QueryType.INFORMATIONAL),
        Arguments.of("\"\u200b\"", QueryType.EXACT_MATCH),
        Arguments.of("\" x \"", QueryType.EXACT_MATCH),
        Arguments.of("\"./config\"", QueryType.EXACT_MATCH),
        Arguments.of("\ud800", QueryType.EXPLORATORY),
        Arguments.of("\udc00", QueryType.EXPLORATORY),
        Arguments.of("a\ud800b", QueryType.EXPLORATORY),
        Arguments.of("\ud800src/main/java", QueryType.NAVIGATIONAL),
        Arguments.of("a".repeat(250_000), QueryType.EXPLORATORY),
        Arguments.of("a".repeat(250_000) + ".pdf", QueryType.NAVIGATIONAL),
        Arguments.of("a".repeat(250_000) + " next", QueryType.INFORMATIONAL),
        Arguments.of("\"" + "a".repeat(250_000) + "\"", QueryType.EXACT_MATCH));
  }

  @Nested
  @DisplayName("NAVIGATIONAL — file extensions")
  class FileExtensions {
    @ParameterizedTest
    @ValueSource(strings = {"report.pdf", "notes.txt", "data.csv", "README.md", "config.yaml"})
    void fileExtensionQueries(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    void fileExtensionWithExtraTerms() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("report.pdf quarterly"));
    }

    @Test
    void caseInsensitiveExtension() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("REPORT.PDF"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"app.java", "index.ts", "main.py", "server.go", "lib.rs"})
    void codeFileExtensions(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    @DisplayName("bare extension without filename is NOT navigational")
    void bareExtensionNotNavigational() {
      // "how to write a .py script" — the .py has no chars before the dot
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how to write a .py script"));
    }

    @Test
    @DisplayName("bare extensions in natural language are NOT navigational")
    void bareExtensionsInNaturalLanguage() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("convert .json to .csv"));
    }
  }

  @Nested
  @DisplayName("NAVIGATIONAL — path fragments")
  class PathFragments {
    @Test
    void multiSegmentForwardSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("src/main/java"));
    }

    @Test
    void backslashPath() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("C:\\Users\\docs\\notes"));
    }

    @Test
    void relativePathDotSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("./config"));
    }

    @Test
    void relativePathDotDotSlash() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("../parent/dir"));
    }

    @Test
    @DisplayName("single forward slash in natural language is NOT navigational")
    void singleSlashNotNavigational() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("pros/cons of SPLADE"));
    }

    @Test
    @DisplayName("and/or is NOT navigational")
    void andOrNotNavigational() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("search and/or replace"));
    }
  }

  @Nested
  @DisplayName("NAVIGATIONAL — identifier detection")
  class Identifiers {
    @ParameterizedTest
    @ValueSource(strings = {"ResolvedConfigBuilder", "SearchOrchestrator", "QueryClassifier"})
    void camelCaseIdentifiers(String query) {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify(query));
    }

    @Test
    void snakeCaseIdentifier() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("worker_config_snapshot"));
    }

    @Test
    void kebabCaseWithDigits() {
      assertEquals(QueryType.NAVIGATIONAL, QueryClassifier.classify("cuda-12"));
    }

    @Test
    @DisplayName("plain lowercase single word is EXPLORATORY, not NAVIGATIONAL")
    void plainWordNotIdentifier() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("optimization"));
    }

    @Test
    void shortSingleChar() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("x"));
    }
  }

  @Nested
  @DisplayName("EXACT_MATCH — quoted phrases")
  class QuotedPhrases {
    @Test
    void quotedPhrase() {
      assertEquals(QueryType.EXACT_MATCH, QueryClassifier.classify("\"exact phrase match\""));
    }

    @Test
    void singleQuotedWord() {
      assertEquals(QueryType.EXACT_MATCH, QueryClassifier.classify("\"error\""));
    }

    @Test
    void notQuotedIfOnlyOpeningQuote() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("\"partial quote"));
    }
  }

  @Nested
  @DisplayName("INFORMATIONAL — question words")
  class QuestionWords {
    @Test
    void howQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how to configure SSL"));
    }

    @Test
    void whatQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("what is JustSearch used for"));
    }

    @Test
    void whyQuestion() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("why does search return zero"));
    }

    @Test
    void shortQuestionWordNotEnoughTerms() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("how search"));
    }
  }

  @Nested
  @DisplayName("EXPLORATORY — single plain terms")
  class SingleTerms {
    @Test
    void singleWord() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("optimization"));
    }

    @Test
    void singleWordNoExtension() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("report"));
    }

    @Test
    void singleWordSearch() {
      assertEquals(QueryType.EXPLORATORY, QueryClassifier.classify("search"));
    }
  }

  @Nested
  @DisplayName("INFORMATIONAL — default for multi-term queries")
  class DefaultMultiTerm {
    @Test
    void multiTermDefault() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("search ranking quality"));
    }

    @Test
    void twoTermDefault() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("GPU configuration"));
    }
  }

  @Nested
  @DisplayName("Edge cases")
  class EdgeCases {
    @Test
    void nullQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify(null));
    }

    @Test
    void emptyQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify(""));
    }

    @Test
    void blankQuery() {
      assertEquals(QueryType.INFORMATIONAL, QueryClassifier.classify("   "));
    }
  }

  @Nested
  @DisplayName("Helper method contracts")
  class HelperMethods {
    @Test
    void hasFileExtension_positive() {
      assertTrue(QueryClassifier.hasFileExtension("report.pdf"));
    }

    @Test
    void hasFileExtension_negative() {
      assertFalse(QueryClassifier.hasFileExtension("just a query"));
    }

    @Test
    void hasFileExtension_bareExtensionNegative() {
      assertFalse(QueryClassifier.hasFileExtension(".py"));
    }

    @Test
    void hasPathFragment_multiSegment() {
      assertTrue(QueryClassifier.hasPathFragment("src/main/java"));
    }

    @Test
    void hasPathFragment_backslash() {
      assertTrue(QueryClassifier.hasPathFragment("C:\\Users"));
    }

    @Test
    void hasPathFragment_singleSlashNegative() {
      assertFalse(QueryClassifier.hasPathFragment("pros/cons"));
    }

    @Test
    void hasPathFragment_negative() {
      assertFalse(QueryClassifier.hasPathFragment("no path here"));
    }

    @Test
    void isQuotedPhrase_positive() {
      assertTrue(QueryClassifier.isQuotedPhrase("\"hello world\""));
    }

    @Test
    void isQuotedPhrase_tooShort() {
      assertFalse(QueryClassifier.isQuotedPhrase("\"\""));
    }

    @Test
    void looksLikeIdentifier_camelCase() {
      assertTrue(QueryClassifier.looksLikeIdentifier("ResolvedConfig"));
    }

    @Test
    void looksLikeIdentifier_snakeCase() {
      assertTrue(QueryClassifier.looksLikeIdentifier("worker_config"));
    }

    @Test
    void looksLikeIdentifier_plainWord() {
      assertFalse(QueryClassifier.looksLikeIdentifier("optimization"));
    }

    @Test
    void looksLikeIdentifier_singleChar() {
      assertFalse(QueryClassifier.looksLikeIdentifier("x"));
    }
  }
}
