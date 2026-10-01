/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 847 T18/T18b/T18c — the S0 markdown shape matrix, promoted from a throwaway probe to the
 * permanent pin that {@link AnswerSegmentation#splitSentences} segments markdown as markdown.
 *
 * <p>The shapes are S0's verbatim (847 §S0-results): 23 answers an LLM really produces, chosen to
 * cover every way the pre-847 prose segmentation fused or invented a key — the whole-block collapse
 * of single-newline lists/quotes/tables, the trailing ordinal, the orphan {@code "."}, the
 * standalone ordinal, and the CJK/Japanese variants that fuse differently.
 */
@DisplayName("847 S5 — markdown-structure-aware sentence segmentation")
class AnswerSegmentationTest {

  record Shape(String id, String markdown, List<String> expected) {}

  private static final List<Shape> SHAPES =
      List.of(
          new Shape(
              "A-prose-baseline",
              """
              The retrieval pipeline has three stages. Query expansion runs first [2]. \
              A cross-encoder then rescores the top candidates [1].
              """,
              List.of(
                  "The retrieval pipeline has three stages.",
                  "Query expansion runs first [2].",
                  "A cross-encoder then rescores the top candidates [1].")),
          new Shape(
              "B-numbered-bold-loose",
              """
              The pipeline has three stages:

              1. **Query Expansion**: the query is expanded with synonyms before retrieval [2].

              2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].

              3. **Reranking**: a cross-encoder rescores the top candidates by relevance [1].
              """,
              List.of(
                  "The pipeline has three stages:",
                  "Query Expansion: the query is expanded with synonyms before retrieval [2].",
                  "Retrieval Pipeline: candidates are fetched by hybrid BM25 and dense vector"
                      + " search [1][3].",
                  "Reranking: a cross-encoder rescores the top candidates by relevance [1].")),
          new Shape(
              "C-numbered-bold-tight",
              """
              The pipeline has three stages:
              1. **Query Expansion**: the query is expanded with synonyms before retrieval [2].
              2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].
              3. **Reranking**: a cross-encoder rescores the top candidates by relevance [1].
              """,
              List.of(
                  "The pipeline has three stages:",
                  "Query Expansion: the query is expanded with synonyms before retrieval [2].",
                  "Retrieval Pipeline: candidates are fetched by hybrid BM25 and dense vector"
                      + " search [1][3].",
                  "Reranking: a cross-encoder rescores the top candidates by relevance [1].")),
          new Shape(
              "D-numbered-plain",
              """
              Three stages run in order:

              1. The query is expanded with synonyms before retrieval [2].

              2. Candidates are fetched by hybrid BM25 and dense vector search [1][3].

              3. A cross-encoder rescores the top candidates by relevance [1].
              """,
              List.of(
                  "Three stages run in order:",
                  "The query is expanded with synonyms before retrieval [2].",
                  "Candidates are fetched by hybrid BM25 and dense vector search [1][3].",
                  "A cross-encoder rescores the top candidates by relevance [1].")),
          new Shape(
              "E-bullet-dash",
              """
              Key properties of the index:

              - The index is written only by the index component [1].
              - The API gateway delegates all index IO through ports [2].
              - Search analysis is locale invariant by construction [3].
              """,
              List.of(
                  "Key properties of the index:",
                  "The index is written only by the index component [1].",
                  "The API gateway delegates all index IO through ports [2].",
                  "Search analysis is locale invariant by construction [3].")),
          new Shape(
              "F-bullet-star-bold",
              """
              Key properties:

              * **Ownership**: the index is written only by the index component [1].
              * **Transport**: the Head delegates all index IO through ports [2].
              """,
              List.of(
                  "Key properties:",
                  "Ownership: the index is written only by the index component [1].",
                  "Transport: the Head delegates all index IO through ports [2].")),
          new Shape(
              "G-nested-list",
              """
              Retrieval works in two layers:

              1. **Lexical**: BM25 over the analyzed text field [1].
                 - Stopwords are not removed per language [3].
                 - Case folding is applied after NFC normalization [3].
              2. **Dense**: cosine similarity over the embedding field [2].
              """,
              List.of(
                  "Retrieval works in two layers:",
                  "Lexical: BM25 over the analyzed text field [1].",
                  "Stopwords are not removed per language [3].",
                  "Case folding is applied after NFC normalization [3].",
                  "Dense: cosine similarity over the embedding field [2].")),
          new Shape(
              "H-heading-then-list",
              """
              ## Retrieval

              The pipeline fuses two retrievers [1].

              ### Stages

              1. Lexical retrieval scores the analyzed text field [1].
              2. Dense retrieval scores the embedding field [2].
              """,
              List.of(
                  "Retrieval",
                  "The pipeline fuses two retrievers [1].",
                  "Stages",
                  "Lexical retrieval scores the analyzed text field [1].",
                  "Dense retrieval scores the embedding field [2].")),
          new Shape(
              "I-table-rows",
              """
              | Stage | Component | Source |
              | --- | --- | --- |
              | Expansion | Query rewriter expands synonyms. | [2] |
              | Retrieval | Hybrid BM25 and dense search runs. | [1] |
              | Reranking | Cross-encoder rescores candidates. | [3] |
              """,
              // One key per CELL, in reading order: the renderer's blocks are cells too, so the
              // clamp and the key agree. The `[2]`/`[1]`/`[3]` source cells carry no letter and are
              // suppressed as junk, exactly like a bare ordinal.
              List.of(
                  "Stage",
                  "Component",
                  "Source",
                  "Expansion",
                  "Query rewriter expands synonyms.",
                  "Retrieval",
                  "Hybrid BM25 and dense search runs.",
                  "Reranking",
                  "Cross-encoder rescores candidates.")),
          new Shape(
              "J-short-items",
              """
              Does it rebuild the index?

              1. It does not.
              2. It reuses the segment.
              3. It re-opens the reader.
              """,
              List.of(
                  "Does it rebuild the index?",
                  "It does not.",
                  "It reuses the segment.",
                  "It re-opens the reader.")),
          new Shape(
              "K-blockquote",
              """
              The design states:

              > The Head never touches Lucene [1].
              > All index IO is delegated to the Worker [2].
              """,
              List.of(
                  "The design states:",
                  "The Head never touches Lucene [1].",
                  "All index IO is delegated to the Worker [2].")),
          new Shape(
              "L-fence-between-items",
              """
              To enable the flag:

              1. Set the flag in the configuration file [1].

              ```json
              { "citations": { "enabled": true } }
              ```

              2. Restart JustSearch so the change is applied [2].
              """,
              // The fence keeps its own key (it is a block a reader sees), but the ```json info
              // string is structure and no longer heads the key — the leading foreign token that
              // zeroed its match (847 §S0-results surprise 4) is gone.
              List.of(
                  "To enable the flag:",
                  "Set the flag in the configuration file [1].",
                  "{ \"citations\": { \"enabled\": true } }",
                  "Restart JustSearch so the change is applied [2].")),
          new Shape(
              "M-cjk-numbered",
              """
              检索管道包含三个阶段：

              1. **查询扩展**：在检索之前使用同义词扩展查询 [2]。

              2. **混合检索**：系统同时使用稀疏和稠密两种方式召回候选文档 [1][3]。

              3. **重新排序**：交叉编码器对候选结果重新打分 [1]。
              """,
              List.of(
                  "检索管道包含三个阶段：",
                  "查询扩展：在检索之前使用同义词扩展查询 [2]。",
                  "混合检索：系统同时使用稀疏和稠密两种方式召回候选文档 [1][3]。",
                  "重新排序：交叉编码器对候选结果重新打分 [1]。")),
          new Shape(
              "N-cjk-bullets",
              """
              索引的关键属性：

              - 索引只由索引组件写入 [1]。
              - API 网关通过进程内端口委托所有索引读写 [2]。
              """,
              List.of("索引的关键属性：", "索引只由索引组件写入 [1]。", "API 网关通过进程内端口委托所有索引读写 [2]。")),
          new Shape(
              "O-japanese-numbered",
              """
              検索パイプラインは三つの段階から成ります。

              1. **クエリ拡張**：検索の前に同義語でクエリを拡張します [2]。

              2. **再ランキング**：クロスエンコーダが候補を並べ替えます [1]。
              """,
              List.of(
                  "検索パイプラインは三つの段階から成ります。",
                  "クエリ拡張：検索の前に同義語でクエリを拡張します [2]。",
                  "再ランキング：クロスエンコーダが候補を並べ替えます [1]。")),
          new Shape(
              "P-live-observed",
              // The shape of the live persisted sentenceText quoted in 847 §1.1.
              """
              Here is how the system answers a question:

              1. **Query Understanding**: the question is analyzed and expanded [2].

              2. **Retrieval Pipeline**: candidates are fetched by hybrid BM25 and dense vector search [1][3].

              3. **Answer Synthesis**: the model writes the answer from the retrieved context [1].
              """,
              List.of(
                  "Here is how the system answers a question:",
                  "Query Understanding: the question is analyzed and expanded [2].",
                  "Retrieval Pipeline: candidates are fetched by hybrid BM25 and dense vector"
                      + " search [1][3].",
                  "Answer Synthesis: the model writes the answer from the retrieved context [1].")),
          new Shape(
              "Q-two-digit-short-items",
              """
              9. It does not.
              10. It reuses the reader.
              11. It is cached.
              12. No.
              13. The Worker owns the index and never yields it to the Head [1].
              """,
              // "No." survives: it is short, and the renderer will not anchor a 2-character key,
              // but it is a sentence the answer makes and the sources panel may show it grounded.
              // Only the bare ordinals it was fused with ("No.\n13." was ONE key) are gone.
              List.of(
                  "It does not.",
                  "It reuses the reader.",
                  "It is cached.",
                  "No.",
                  "The Worker owns the index and never yields it to the Head [1].")),
          new Shape(
              "R-bullets-with-links",
              """
              Two rules apply:

              - See [the architecture overview](docs/explanation/01-system-overview.md) for details [1].
              - The API gateway delegates all index IO through ports [2].
              """,
              // The link contributes its label, never its URL — the same collapsing the renderer
              // applies to the key before anchoring.
              List.of(
                  "Two rules apply:",
                  "See the architecture overview for details [1].",
                  "The API gateway delegates all index IO through ports [2].")),
          new Shape(
              "S-hardwrapped-prose",
              // Soft line breaks INSIDE one sentence: a block is not a line, so this stays one key.
              """
              The index component owns Lucene and the API gateway delegates every index read
              and write to it through ports, so no index handle ever exists in the API
              layer [1]. That boundary is enforced by an ArchUnit rule [2].
              """,
              List.of(
                  "The index component owns Lucene and the API gateway delegates every index read and"
                      + " write to it through ports, so no index handle ever exists in the API layer"
                      + " [1].",
                  "That boundary is enforced by an ArchUnit rule [2].")),
          new Shape(
              "T-abbreviation",
              // The residual BreakIterator leaves behind, EXHIBITED rather than asserted. Block
              // structure cannot help: the split is INSIDE one paragraph. Measured precisely — an
              // abbreviation only ends a sentence when what follows looks like a new one, so
              // "Dr. Smith" splits and "e.g. lexical" does not. Note what the split key is: "Dr."
              // has a letter, so the junk predicate cannot catch it; it is a real key that will
              // never anchor, and it counts in sentences_total.
              """
              Dr. Smith owns the retrieval spec [1]. The pipeline runs in two passes, e.g. lexical then dense [2].
              """,
              List.of(
                  "Dr.",
                  "Smith owns the retrieval spec [1].",
                  "The pipeline runs in two passes, e.g. lexical then dense [2].")),
          new Shape(
              "U-task-list",
              """
              Checklist:

              - [x] The index is written only by the index component [1].
              - [ ] The API gateway delegates all index IO through ports [2].
              """,
              // The task marker is structure: `marked` renders a checkbox element, so a key that
              // began with a literal "[x]" would open with a token no DOM run can match.
              List.of(
                  "Checklist:",
                  "The index is written only by the index component [1].",
                  "The API gateway delegates all index IO through ports [2].")),
          new Shape(
              "V-raw-html-block",
              // DOMPurify keeps the container and the renderer walks its text nodes, so the prose
              // inside is a citable sentence the reader sees. Skipping the block would leave it
              // with no key at all — unscored, uncounted, absent from the sources panel.
              """
              <div class="note">
              The Worker owns the index [1]. The Head delegates all IO [2].
              </div>
              """,
              List.of("The Worker owns the index [1].", "The Head delegates all IO [2].")),
          new Shape(
              "W-block-image",
              // Alt text is an ATTRIBUTE in the rendered DOM, never a text node. A key minted from
              // it could match nothing and would sit in sentences_total reading as ungrounded.
              """
              ![Architecture diagram of the retrieval pipeline](docs/arch.png)

              The Worker owns the index [1].
              """,
              List.of("The Worker owns the index [1].")));

  /** A block marker surviving into a key — the fusion signature the pre-847 splitter produced. */
  private static final Pattern LEADING_MARKER =
      Pattern.compile("(?m)^\\s*(?:\\d+[.)]|[-*+]|#{1,6}|>|\\|)(?:\\s|$)");

  // --- T18 ---------------------------------------------------------------------------------

  @Test
  @DisplayName("T18 — every shape segments one key per sentence per block")
  void shapeMatrix() {
    List<String> drifted = new ArrayList<>();
    StringBuilder detail = new StringBuilder();
    for (Shape shape : SHAPES) {
      List<String> actual = AnswerSegmentation.splitSentences(shape.markdown());
      if (!shape.expected().equals(actual)) {
        drifted.add(shape.id());
        detail
            .append("\n  ")
            .append(shape.id())
            .append("\n    expected ")
            .append(shape.expected())
            .append("\n    actual   ")
            .append(actual);
      }
    }
    // Reported as a set so a revert-run names every shape it breaks, not just the first.
    assertEquals(List.of(), drifted, "segmentation drifted for:" + detail);
  }

  @Test
  @DisplayName("T18 — no key carries a marker, a line break, or a foreign block's text")
  void classificationCounters() {
    Map<String, Integer> classes = new LinkedHashMap<>();
    for (Shape shape : SHAPES) {
      for (String key : AnswerSegmentation.splitSentences(shape.markdown())) {
        classes.merge(classify(key), 1, Integer::sum);
      }
    }
    // A partial fix (blank-line rule only, or marker strip only) leaves one of these non-zero.
    assertEquals(
        Map.of("SENTENCE", 85),
        classes,
        "every key must be a plain sentence; classes found: " + classes);
  }

  @Test
  @DisplayName("T18 — the measured break mechanism: a marker no longer suppresses the boundary")
  void breakMechanism() {
    // 847 §S0-results surprise 1, inverted: each of these emitted ONE fused key (or a key plus an
    // orphan ".") under the pre-847 prose splitter.
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n- Beta two."));
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n\n- Beta two."));
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n* Beta two."));
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n> Beta two."));
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n## Beta two."));
    assertEquals(List.of("Alpha one.", "Beta two."), AnswerSegmentation.splitSentences("Alpha one.\n\n2. Beta two."));
    // Not a list, and the key says so: an ordered list may only interrupt a paragraph when it
    // starts at 1 (CommonMark 0.31 §5.3), so `marked` renders this as ONE paragraph reading
    // "Alpha one. 2. Beta two." — and the key now matches what the reader sees rather than what
    // the source looks like. The blank-line variant above is a real list, and breaks.
    assertEquals(
        List.of("Alpha one. 2.", "Beta two."),
        AnswerSegmentation.splitSentences("Alpha one.\n2. Beta two."));
    assertEquals(List.of("阿尔法一。", "贝塔二。"), AnswerSegmentation.splitSentences("阿尔法一。\n- 贝塔二。"));
    assertEquals(List.of("阿尔法一。", "贝塔二。"), AnswerSegmentation.splitSentences("阿尔法一。\n\n2. 贝塔二。"));
  }

  // --- T18b --------------------------------------------------------------------------------

  @Test
  @DisplayName("T18b — the denominator moves for two measured reasons: junk removal and de-fusion")
  void denominatorAccounting() {
    int legacyTotal = 0;
    int legacyJunk = 0;
    int legacyFused = 0;
    for (Shape shape : SHAPES) {
      for (String key : legacySplitSentences(shape.markdown())) {
        legacyTotal++;
        if (!carriesLetter(key)) {
          legacyJunk++;
        } else if (!"SENTENCE".equals(classify(key))) {
          legacyFused++;
        }
      }
    }
    // Measured on the pre-847 splitter over this matrix (847 §S0-results): orphan "." and
    // standalone-ordinal keys were scored by the cross-encoder and counted in 836 §3.6's coverage
    // denominator, and one key in five fused a foreign block's text.
    assertEquals(73, legacyTotal, "legacy key count over the matrix");
    assertEquals(9, legacyJunk, "legacy junk keys (orphan '.' / bare ordinal)");
    assertEquals(37, legacyFused, "legacy keys carrying a marker, a line break or a sibling block");
    assertEquals(
        12.3,
        Math.round(1000.0 * legacyJunk / legacyTotal) / 10.0,
        0.05,
        "legacy junk share of sentences_total, percent");

    int total = 0;
    int junk = 0;
    for (Shape shape : SHAPES) {
      for (String key : AnswerSegmentation.splitSentences(shape.markdown())) {
        total++;
        if (!carriesLetter(key)) {
          junk++;
        }
      }
    }
    assertEquals(0, junk, "no key may be scored that carries no sentence");
    assertEquals(85, total, "sentences_total over the matrix after S5");
    // The denominator did not merely shrink: de-fusion ADDS the keys a fused block hid (a 3-item
    // list was one key), while junk removal takes keys away. Both effects are real and opposite,
    // which is why 836's coverage numbers move in a direction no single count predicts.
    assertTrue(
        total > legacyTotal - legacyJunk,
        "de-fusion must recover more keys than junk removal takes away");
  }

  @Test
  @DisplayName("T18b — a key the renderer could never anchor is never scored")
  void junkKeysAreNotScored() {
    assertEquals(List.of(), AnswerSegmentation.splitSentences("1.\n\n2.\n\n3."));
    assertEquals(List.of(), AnswerSegmentation.splitSentences("- 2026.\n- 1999."));
    assertEquals(List.of(), AnswerSegmentation.splitSentences("."));
    assertEquals(
        List.of("Real sentence here."),
        AnswerSegmentation.splitSentences("2026.\n\nReal sentence here."));
    // A short sentence is NOT junk. The renderer may decline to anchor it (its floor is 4 word-like
    // characters), but the producer does not delete evidence on the consumer's behalf — and a Han
    // sentence reaches that floor in three characters (HI-6).
    assertEquals(List.of("No."), AnswerSegmentation.splitSentences("- No."));
    assertEquals(List.of("阿尔法一。", "贝塔二。"), AnswerSegmentation.splitSentences("阿尔法一。\n- 贝塔二。"));
  }

  // --- T18c --------------------------------------------------------------------------------

  @Test
  @DisplayName("T18c — segmentation is locale-invariant, and does not read the default locale")
  void localeIsInert() {
    Locale original = Locale.getDefault();
    try {
      for (Locale forced : List.of(Locale.ROOT, Locale.JAPAN, new Locale("tr", "TR"))) {
        Locale.setDefault(forced);
        for (Shape shape : SHAPES) {
          List<String> root = AnswerSegmentation.splitSentences(shape.markdown(), Locale.ROOT);
          assertEquals(shape.expected(), root, "shape " + shape.id() + " under default " + forced);
          for (Locale locale :
              List.of(Locale.ENGLISH, Locale.JAPANESE, Locale.SIMPLIFIED_CHINESE, Locale.GERMAN)) {
            assertEquals(
                root,
                AnswerSegmentation.splitSentences(shape.markdown(), locale),
                "shape " + shape.id() + " differs under " + locale);
          }
        }
      }
    } finally {
      Locale.setDefault(original);
    }
  }

  // --- helpers -----------------------------------------------------------------------------

  private static String classify(String key) {
    if (!carriesLetter(key)) {
      return "JUNK";
    }
    if (key.indexOf('\n') >= 0) {
      return "MULTILINE";
    }
    if (LEADING_MARKER.matcher(key).find()) {
      return "MARKER";
    }
    return "SENTENCE";
  }

  private static boolean carriesLetter(String key) {
    return key.codePoints().anyMatch(Character::isLetter);
  }

  /**
   * The pre-847 splitter, verbatim: {@link BreakIterator} over the raw markdown as if it were
   * prose. Kept as the measurement baseline T18b subtracts from — without it "the denominator
   * dropped by the junk count" is a claim, not a measurement.
   */
  private static List<String> legacySplitSentences(String text) {
    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    bi.setText(text);
    List<String> sentences = new ArrayList<>();
    int start = bi.first();
    for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        sentences.add(sentence);
      }
    }
    return sentences;
  }
}
