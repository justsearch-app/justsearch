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

/** Independent checks-first contract. Inputs are SAX events, not parser-inserted whitespace. */
class TableCellBoundaryContractTest {
  static StructuredContentHandler parse(String body, int limit, boolean pdf) throws Exception {
    var factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    var handler = new StructuredContentHandler(limit, pdf);
    factory.newSAXParser().parse(new InputSource(new StringReader("<html><body>" + body
        + "</body></html>")), handler);
    return handler;
  }

  static StructuredDocument document(String body) throws Exception {
    return parse(body, 1_000_000, false).getDocument();
  }

  static String singleCell(String body) throws Exception {
    var doc = document("<table><tr><td>" + body + "</td></tr></table>");
    assertEquals(1, doc.elements().size(), "one table; no phantom standalone blocks");
    return ((Table) doc.elements().getFirst()).rows().getFirst().getFirst();
  }

  @ParameterizedTest
  @ValueSource(strings = {"td", "th"})
  void s1ParagraphsInCellAndReusedLabels(String tag) throws Exception {
    String cell = "<" + tag + "><p>repercussive</p><p>All</p></" + tag + ">";
    var doc = document("<table><tr>" + cell + "</tr><tr><td><p>Jason Adams</p>"
        + "<p>Avneet Nahal</p></td></tr></table>");
    var table = (Table) doc.elements().getFirst();
    assertAll(
        () -> assertTrue(table.rows().get(0).getFirst().matches("repercussive\\s+All"), table.toString()),
        () -> assertTrue(table.rows().get(1).getFirst().matches("Jason Adams\\s+Avneet Nahal"), table.toString()),
        () -> assertFalse(doc.toAnnotatedText().contains("repercussiveAll"), doc.toAnnotatedText()),
        () -> assertFalse(doc.toAnnotatedText().contains("AdamsAvneet"), doc.toAnnotatedText()));
  }

  @Test
  void s1SingleRowTable() throws Exception {
    String actual = singleCell("<p>repercussive</p><p>All</p>");
    assertTrue(actual.matches("repercussive\\s+All"), actual);
  }

  @Test
  void s2BreakInCell() throws Exception {
    String actual = singleCell("repercussive<br/>All");
    assertTrue(actual.matches("repercussive\\s+All"), actual);
  }

  @Test
  void s3NestedCellsRowsAndSurroundingText() throws Exception {
    String actual = singleCell("outer<table><tr><td>inner</td><td>second</td></tr>"
        + "<tr><td>third</td><td>fourth</td></tr></table>tail");
    assertTrue(actual.matches("outer\\s+inner\\s+second\\s+third\\s+fourth\\s+tail"), actual);
  }

  @Test
  void s4ListParagraphs() throws Exception {
    var doc = document("<ul><li><p>repercussive</p><p>All</p></li></ul>");
    String actual = ((ListBlock) doc.elements().getFirst()).items().getFirst();
    assertTrue(actual.matches("repercussive\\s+All"), actual);
  }

  @Test
  void s4ListBreaks() throws Exception {
    var doc = document("<ol><li>repercussive<br/>All</li></ol>");
    String actual = ((ListBlock) doc.elements().getFirst()).items().getFirst();
    assertTrue(actual.matches("repercussive\\s+All"), actual);
  }

  @Test
  void s4ListInCellHasAllTextAndNoStandaloneDuplicate() throws Exception {
    String actual = singleCell("before<ul><li><p>repercussive</p><p>All</p></li>"
        + "<li>next<br/>last</li></ul>after");
    assertTrue(actual.matches("before\\s+repercussive\\s+All\\s+next\\s+last\\s+after"), actual);
  }

  @Test
  void p1SingleParagraphInlineRunsAndTripletLayout() throws Exception {
    var doc = document("<table><tr><th> Name </th><th> Value </th></tr>"
        + "<tr><td><p> Revenue </p></td><td><p> reper<b>cus</b>sive </p></td></tr></table>");
    assertEquals(List.of(List.of("Name", "Value"), List.of("Revenue", "repercussive")),
        ((Table) doc.elements().getFirst()).rows());
    assertEquals("Name = Revenue\nRevenue, Value = repercussive", doc.toAnnotatedText());
  }

  @Test
  void p1EmptyParagraphsMakeNoContent() throws Exception {
    assertEquals("repercussive", singleCell("<p/><p>  </p><p>reper<span>cus</span>sive</p><p/>"));
    assertEquals("", singleCell("<p/><p> </p><p/>"));
  }

  @Test
  void extraEmptyParagraphsBetweenWordsDoNotDoubleSeparators() throws Exception {
    String actual = singleCell("<p>alpha</p><p/><p> </p><p>beta</p><br/><br/>");
    assertEquals(singleCell("<p>alpha</p><p>beta</p>"), actual,
        "empty paragraphs/breaks add no extra separators");
    assertTrue(actual.matches("alpha\\s+beta"), actual);
  }

  @Test
  void p2OutsideBlocksAndPostTableText() throws Exception {
    var doc = document("<h2> Heading </h2><p>first</p><p>second</p>"
        + "<table><tr><td>cell</td></tr></table><p>after</p>");
    assertEquals(List.of(new Heading(2, "Heading", 0), new Paragraph("first", 0),
        new Paragraph("second", 0), new Table(List.of(List.of("cell")), 0),
        new Paragraph("after", 0)), doc.elements());
  }

  @Test
  void p2PdfPhysicalLineHyphenJoinsButParagraphHyphenDoesNot() throws Exception {
    var h = new StructuredContentHandler(1000, true);
    var attrs = new org.xml.sax.helpers.AttributesImpl();
    h.startElement("", "p", "p", attrs);
    h.characters("reper-".toCharArray(), 0, 6);
    h.ignorableWhitespace("\n".toCharArray(), 0, 1);
    h.characters("cussive".toCharArray(), 0, 7);
    h.endElement("", "p", "p");
    h.startElement("", "p", "p", attrs);
    h.characters("inter-".toCharArray(), 0, 6);
    h.endElement("", "p", "p");
    h.startElement("", "p", "p", attrs);
    h.characters("national".toCharArray(), 0, 8);
    h.endElement("", "p", "p");
    assertEquals(List.of(new Paragraph("repercussive", 0), new Paragraph("inter-", 0),
        new Paragraph("national", 0)), h.getDocument().elements());
  }

  @Test
  void p3LimitsIncludeSeparatorsAndPreserveSurrogates() throws Exception {
    for (int cap = 0; cap <= 24; cap++) {
      var handler = parse("<table><tr><td>" + "<p>ab😀</p>".repeat(10)
          + "</td></tr></table>", cap, false);
      var doc = handler.getDocument();
      String raw = doc.toFlatText().stripTrailing();
      assertTrue(raw.length() <= cap, "handler overran cap=" + cap + ": " + raw);
      var text = doc.toAnnotatedText(cap);
      assertTrue(text.text().length() <= cap, "serialized cap=" + cap);
      assertTrue(handler.isLimitReached() || text.truncated(), "truncation cap=" + cap);
      assertWholeSurrogates(raw);
      assertWholeSurrogates(text.text());
    }
  }

  static void assertWholeSurrogates(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)), text);
        i++;
      } else assertFalse(Character.isLowSurrogate(c), text);
    }
  }

  @Test
  void extraCellHyphenIsNotJoinedAcrossParagraphsInPdfMode() throws Exception {
    var doc = parse("<table><tr><td><p>inter-</p><p>national</p></td></tr></table>",
        1000, true).getDocument();
    String actual = ((Table) doc.elements().getFirst()).rows().getFirst().getFirst();
    assertTrue(actual.matches("inter-\\s+national"), actual);
  }
}
