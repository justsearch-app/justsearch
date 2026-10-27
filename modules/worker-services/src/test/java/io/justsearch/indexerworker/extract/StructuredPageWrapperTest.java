/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.extraction.StructuredDocument;
import io.justsearch.indexing.extraction.StructuredDocument.*;
import java.io.StringReader;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.InputSource;

final class StructuredPageWrapperTest {
  @ParameterizedTest
  @ValueSource(strings = {"page-number", "homepage", "page-content", "Page", "content"})
  void similarClassNamesDoNotStartPages(String cssClass) throws Exception {
    var document = parse("<div class='" + cssClass + "'>Body</div>", 1000);
    assertEquals(0, document.pageCount());
    assertEquals(List.of(new Paragraph("Body", 0)), document.elements());
    assertEquals("Body", document.toAnnotatedText());
  }

  @ParameterizedTest
  @ValueSource(strings = {"page", "page foo", "foo page", "foo&#9;page&#10;bar", " page "})
  void exactWhitespaceSeparatedClassTokenStartsPage(String cssClass) throws Exception {
    var document = parse("<div class='" + cssClass + "'>Body</div>", 1000);
    assertEquals(1, document.pageCount());
    assertEquals(List.of(new Paragraph("Body", 0)), document.elements());
    assertEquals("Body", document.toAnnotatedText());
  }

  @Test
  void preambleBelongsToFirstPageWithoutInventingABoundary() throws Exception {
    var document = parse("Preamble<div class='page'><p>First</p></div>"
        + "<div class='page'><p>Second</p></div>", 1000);
    assertEquals(2, document.pageCount());
    assertEquals(List.of(new Paragraph("Preamble", 0), new Paragraph("First", 0),
        new PageBreak(1), new Paragraph("Second", 1)), document.elements());
    assertEquals("Preamble\n\nFirst\n\n---\n\nSecond", document.toAnnotatedText());
  }

  @Test
  void everyStructuredElementUsesPhysicalPageBeforeEnrichment() throws Exception {
    String body = "<h1>Title</h1><p>Body</p><table><tr><td>Cell</td></tr></table>"
        + "<ul><li>Item</li></ul>";
    var document = parse("<div class='page'>" + body + "</div><div class='page'>"
        + body + "</div>", 1000);
    assertEquals(2, document.pageCount());
    assertEquals(List.of(new Heading(1, "Title", 0), new Paragraph("Body", 0),
        new Table(List.of(List.of("Cell")), 0), new ListBlock(List.of("Item"), false, 0),
        new PageBreak(1), new Heading(1, "Title", 1), new Paragraph("Body", 1),
        new Table(List.of(List.of("Cell")), 1), new ListBlock(List.of("Item"), false, 1)),
        document.elements());
  }

  @Test
  void emptyWrappersCountAndBlankFirstPageKeepsItsBoundary() throws Exception {
    var document = parse("<div class='page'/><div class='page'>Text</div>"
        + "<div class='page'/><div class='page'/>", 1000);
    assertEquals(4, document.pageCount());
    assertEquals(List.of(new PageBreak(1), new Paragraph("Text", 1), new PageBreak(2),
        new PageBreak(3)), document.elements());
    assertEquals("---\n\nText\n\n---\n\n---", document.toAnnotatedText());
  }

  @Test
  void truncationStillIgnoresLaterPageWrappers() throws Exception {
    var document = parse("<div class='page'>Enough text</div><div class='page'>Later</div>", 5);
    assertEquals(1, document.pageCount());
    assertEquals(List.of(new Paragraph("Enough text", 0)), document.elements());
  }

  private static StructuredDocument parse(String body, int maxChars) throws Exception {
    var handler = new StructuredContentHandler(maxChars);
    SAXParserFactory.newInstance().newSAXParser().parse(
        new InputSource(new StringReader("<body>" + body + "</body>")), handler);
    return handler.getDocument();
  }
}
