/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.reranker.NumericConsistency;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.HtmlInline;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.LinkReferenceDefinition;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.node.ThematicBreak;
import org.commonmark.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ONE way an LLM answer is cut into sentences (tempdoc 847 §2.2).
 *
 * <p>Two consumers: {@code CitationMatchOps} (the keys that are scored, persisted and anchored in
 * the rendered answer) and the RAG faithfulness eval, which scores the same sentences with the same
 * cross-encoder. A second copy of this logic makes the eval measure a segmentation production does
 * not use — which is exactly what it did before this class existed.
 */
public final class AnswerSegmentation {

  private static final Logger log = LoggerFactory.getLogger(AnswerSegmentation.class);

  /** Tags whose CONTENT the renderer never shows, so it is not answer text (DOMPurify drops them). */
  private static final Pattern INVISIBLE_HTML =
      Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");

  private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]*>");

  private AnswerSegmentation() {}

  /**
   * Splits an answer into the sentence keys that are scored, persisted and later anchored in the
   * rendered answer.
   *
   * <p>The answer is markdown, so it is segmented as markdown: the same commonmark parser {@link
   * LanguageUtils} already uses supplies the block structure, and {@link BreakIterator} runs over
   * ONE block's text at a time, never across a block boundary. Segmenting the raw markdown as prose
   * fused wholesale (847 §S0-results, measured over 19 shapes): a terminal {@code .} followed by
   * whitespace and a marker (`- `, `* `, `&gt; `, `| `, `## `) suppresses the sentence boundary
   * entirely, so a whole bullet list, blockquote or table plus its lead-in paragraph became a single
   * key; inserting a blank line instead split the terminator off as an orphan {@code "."} key. Block
   * structure subsumes both — no marker blacklist to maintain.
   *
   * <p>{@link Locale#ROOT}, not {@code Locale.ENGLISH}: all measured shapes segment identically
   * under {@code en}/{@code root}/{@code ja}/{@code zh}, so the former locale was a per-language
   * lever in appearance only (HI-6).
   *
   * <p>Two residuals, both measured and both left alone. Abbreviations still split a sentence
   * ({@code "e.g."} ends one) — {@link BreakIterator}'s own limit, exhibited by a shape in
   * {@code AnswerSegmentationTest} rather than merely asserted. And a HARD line break becomes one
   * space here while the rendered DOM concatenates the text around {@code <br>} with no separator at
   * all, so the two tokenizations disagree at exactly that boundary; the renderer's prefix match
   * stops there and the mark is short or absent, never misplaced.
   */
  public static List<String> splitSentences(String text) {
    return splitSentences(text, Locale.ROOT);
  }

  /**
   * Segmentation under an explicit locale. Production always passes {@link Locale#ROOT}; the
   * parameter exists so a test can pin that the locale is inert (HI-6), which a hardcoded constant
   * cannot be asked.
   */
  static List<String> splitSentences(String text, Locale locale) {
    return splitClaims(text, locale).stream().map(NumericConsistency.Claim::text).toList();
  }

  /** Keeps numeric claims in code distinct from reference literals without changing mark keys. */
  public static List<NumericConsistency.Claim> splitClaims(String text) {
    return splitClaims(text, Locale.ROOT);
  }

  private static List<NumericConsistency.Claim> splitClaims(String text, Locale locale) {
    if (text == null || text.isBlank()) return List.of();
    List<NumericConsistency.Claim> claims = new ArrayList<>();
    for (Block block : blockTexts(text)) segmentBlock(block, locale, claims);
    return claims;
  }

  private record Block(String text, String numericText) {}

  /** Sentence boundaries are taken only from the original visible text. */
  private static void segmentBlock(Block block, Locale locale, List<NumericConsistency.Claim> out) {
    BreakIterator bi = BreakIterator.getSentenceInstance(locale);
    bi.setText(block.text());
    int start = bi.first();
    for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
      String sentence = block.text().substring(start, end).trim();
      if (carriesClaim(sentence)) {
        out.add(new NumericConsistency.Claim(sentence,
            NumericConsistency.proseValues(block.numericText().substring(start, end))));
      }
    }
  }

  /**
   * True when a segment carries a claim — a segment with no letter in it does not.
   *
   * <p>That single predicate is exactly the junk 847 §2.2 measured: the orphan {@code "."} a blank
   * line splits off a terminator, and the bare ordinal ({@code "1."}, {@code "2."}, and the
   * {@code "2026."}-shaped key a word-count floor alone would admit). 8 of 64 keys over the shape
   * matrix (12.5 %) were one of these — scored by the cross-encoder, persisted as evidence, and
   * counted in 836 §3.6's coverage denominator.
   *
   * <p>Deliberately NOT the renderer's {@code >= 4} word-like-character anchoring floor (847
   * §2.1a): a three-character Han sentence is a real sentence, and a producer that applied the
   * consumer's floor would silently delete it from the evidence a reader can open in the sources
   * panel (HI-6). Honest limit: this predicate IS still a consumer judgment — it decides a
   * letterless segment can carry no claim. It is safe in the practical case rather than in
   * principle: a numerals-only "sentence" is unanchorable anyway (the renderer's uniqueness clause
   * rejects it), and CJK numerals are letters, so no script loses a sentence to it.
   */
  private static boolean carriesClaim(String segment) {
    return segment.codePoints().anyMatch(Character::isLetter);
  }

  /**
   * The answer's block texts in reading order: one entry per leaf block that carries content, with
   * markdown structure (list markers, ordinals, task-list checkboxes, quote guillemets, emphasis,
   * link URLs) removed — it is structure the reader never sees as text, and it is what fused the
   * keys.
   *
   * <p>GFM tables and task lists are parsed by their extensions, so a table is one block per CELL
   * and a {@code - [x]} item's key starts at its text. That is what {@code marked} renders and what
   * the renderer's block clamp measures against (847 §2.1c): the two sides agree on what a block is
   * by construction. Core commonmark reads a table as one paragraph — which would leave the whole
   * table as a single key, the fusion class this slice removes — and leaves a literal {@code [x]} at
   * the head of a task item's key, which is a leading foreign token the renderer can never match.
   *
   * <p>Falls back to segmenting the raw answer when the parse fails, so a malformed answer still
   * produces keys rather than none.
   */
  private static List<Block> blockTexts(String markdown) {
    List<Block> blocks = new ArrayList<>();
    try {
      Parser parser =
          Parser.builder()
              .extensions(List.of(TablesExtension.create(), TaskListItemsExtension.create()))
              .build();
      collectBlocks(parser.parse(markdown), blocks);
    } catch (RuntimeException e) {
      log.debug("Markdown parse failed; segmenting the answer as prose: {}", e.getMessage());
      return List.of(new Block(markdown, markdown));
    }
    return blocks;
  }

  private static void collectBlocks(Node node, List<Block> out) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Paragraph || child instanceof Heading || child instanceof TableCell) {
        addBlock(inlineText(child), out);
      } else if (child instanceof FencedCodeBlock fenced) {
        addBlock(new Block(fenced.getLiteral(), protectCode(fenced.getLiteral())), out);
      } else if (child instanceof IndentedCodeBlock indented) {
        addBlock(new Block(indented.getLiteral(), protectCode(indented.getLiteral())), out);
      } else if (child instanceof HtmlBlock htmlBlock) {
        // A raw HTML container is markup, but the prose INSIDE it is answer text: DOMPurify keeps
        // it and the renderer walks its text nodes like any other (MarkdownBlock.ts:336-341).
        // Skipping the block outright would leave a visible, citable sentence with no key at all —
        // unscored, uncounted, absent from the sources panel.
        String visible = visibleHtmlText(htmlBlock.getLiteral());
        addBlock(new Block(visible, visible), out);
      } else if (child instanceof ThematicBreak || child instanceof LinkReferenceDefinition) {
        // Structure, and no text a reader could see grounded.
        continue;
      } else {
        // Container block (list, list item, blockquote, custom): its children are the leaves.
        collectBlocks(child, out);
      }
    }
  }

  private static void addBlock(Block block, List<Block> out) {
    String trimmed = block.text() == null ? "" : block.text().strip();
    if (!trimmed.isEmpty()) {
      out.add(new Block(trimmed, block.numericText().strip()));
    }
  }

  private static String protectCode(String literal) {
    // Protect ASCII and NFKC-compatible brackets in place, keeping visible sentence offsets.
    return literal.replace('[', '(').replace(']', ')')
        .replace('［', '(').replace('］', ')').replace('﹇', '(').replace('﹈', ')');
  }

  /**
   * The reader-visible text of a raw HTML block: script and style CONTENT removed (the renderer
   * shows neither), tags replaced by a space so two adjacent elements do not weld their words
   * together, whitespace collapsed.
   */
  private static String visibleHtmlText(String literal) {
    if (literal == null || literal.isBlank()) {
      return "";
    }
    String withoutInvisible = INVISIBLE_HTML.matcher(literal).replaceAll(" ");
    return HTML_TAG.matcher(withoutInvisible).replaceAll(" ").replaceAll("\\s+", " ").strip();
  }

  /**
   * A leaf block's text as the reader sees it: inline emphasis and code fences drop away, a link
   * contributes its label and not its URL (the renderer collapses inline links the same way before
   * anchoring), and every line break inside the block becomes a single space so a soft-wrapped
   * sentence stays one sentence.
   *
   * <p>An image contributes NOTHING, not its alt text: {@code marked} renders alt as an ATTRIBUTE,
   * so the rendered DOM has no text node for it. Including it would mint a key for a block image
   * that no run in the DOM can match — a phantom sentence in {@code sentences_total} that always
   * reads as ungrounded — and would break a sentence containing an inline image in two.
   */
  private static Block inlineText(Node block) {
    StringBuilder text = new StringBuilder();
    StringBuilder numericText = new StringBuilder();
    appendInline(block, text, numericText);
    return new Block(text.toString().replaceAll("\\s+", " ").strip(),
        numericText.toString().replaceAll("\\s+", " ").strip());
  }

  private static void appendInline(Node node, StringBuilder text, StringBuilder numericText) {
    for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
      if (child instanceof Text literal) {
        text.append(literal.getLiteral());
        numericText.append(literal.getLiteral());
      } else if (child instanceof Code code) {
        text.append(code.getLiteral());
        numericText.append(protectCode(code.getLiteral()));
      } else if (child instanceof SoftLineBreak
          || child instanceof HardLineBreak
          || child instanceof HtmlInline
          || child instanceof Image) {
        text.append(' ');
        numericText.append(' ');
      } else {
        appendInline(child, text, numericText);
      }
    }
  }
}
