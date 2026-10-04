package io.justsearch.indexing.extraction;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.extraction.StructuredDocument.*;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class StructuredDocumentTest {

  @Nested
  class AnnotatedTextSerialization {

    @Test
    void emptyDocument() {
      var doc = new StructuredDocument(List.of(), 0);
      assertEquals("", doc.toAnnotatedText());
    }

    @Test
    void singleParagraph() {
      var doc = new StructuredDocument(List.of(new Paragraph("Hello world", -1)), 0);
      assertEquals("Hello world", doc.toAnnotatedText());
    }

    @Test
    void headingLevels() {
      var doc =
          new StructuredDocument(
              List.of(
                  new Heading(1, "Title", -1),
                  new Paragraph("Body text", -1),
                  new Heading(2, "Section", -1),
                  new Paragraph("More text", -1)),
              0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("# Title"), "H1 should use single #");
      assertTrue(result.contains("## Section"), "H2 should use ##");
      assertTrue(result.contains("Body text"));
      assertTrue(result.contains("More text"));
    }

    @Test
    void headingMatchesFindPrecedingHeadingRegex() {
      // The existing findPrecedingHeading() regex is: ^(#{1,6})\s+(.+?)\s*$
      var doc = new StructuredDocument(List.of(new Heading(3, "My Section", -1)), 0);
      String result = doc.toAnnotatedText();
      assertTrue(
          result.contains("### My Section"),
          "Heading format must match findPrecedingHeading regex");
    }

    @Test
    void tableWithHeadersTripletFormat() {
      var table =
          new Table(
              List.of(
                  List.of("Company", "Revenue", "Profit"),
                  List.of("Acme", "$10M", "$2M"),
                  List.of("Beta", "$15M", "$3M")),
              -1);
      var doc = new StructuredDocument(List.of(table), 0);
      String result = doc.toAnnotatedText();

      // Triplet format: first col is row ID, remaining cols use header
      assertTrue(result.contains("Company = Acme"), "First cell should be row identity");
      assertTrue(result.contains("Acme, Revenue = $10M"), result);
      assertTrue(result.contains("Acme, Profit = $2M"), result);
      assertTrue(result.contains("Beta, Revenue = $15M"), result);
      assertTrue(result.contains("Beta, Profit = $3M"), result);
    }

    @Test
    void amplifiedTableStopsSerializingAsSoonAsOutputBudgetIsSpent() {
      String header = "h".repeat(65_536);
      var rows = new java.util.AbstractList<List<String>>() {
        @Override public int size() { return 100_001; }
        @Override public List<String> get(int index) {
          if (index > 2) fail("Serializer continued reading rows after spending its output budget");
          return List.of(index == 0 ? header : "x");
        }
      };
      var doc = new StructuredDocument(List.of(new Table(rows, -1)), 0);

      var result = doc.toAnnotatedText(65_544);

      assertEquals(65_544, result.text().length());
      assertTrue(result.truncated());
      assertTrue(result.text().startsWith(header + " = x\n"));
    }

    @Test
    void outputBudgetCoversEveryElementAndRepeatedRowIdentifiers() {
      var doc = new StructuredDocument(List.of(
          new Heading(2, "heading", 0), new Paragraph("paragraph", 0),
          new Table(List.of(List.of("id", "value"), List.of("row", "cell")), 0),
          new PageBreak(1), new ListBlock(List.of("one", "two"), true, 1)), 2);
      String full = doc.toAnnotatedText();
      for (int cap = 0; cap < full.length(); cap++) {
        var result = doc.toAnnotatedText(cap);
        assertEquals(full.substring(0, cap).stripTrailing(), result.text(), "cap=" + cap);
        assertTrue(result.truncated(), "cap=" + cap);
      }
      assertEquals(full, doc.toAnnotatedText(1_000).text());
      assertFalse(doc.toAnnotatedText(1_000).truncated());
    }

    @Test
    void outputBudgetDoesNotSplitSurrogatePairs() {
      var doc = new StructuredDocument(List.of(new Paragraph("a\uD83D\uDE00b", -1)), 0);
      var result = doc.toAnnotatedText(2);
      assertEquals("a", result.text());
      assertTrue(result.truncated());
    }

    @Test
    void tableWithoutHeaders() {
      var table = new Table(List.of(List.of("cell1", "cell2")), -1);
      var doc = new StructuredDocument(List.of(table), 0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("cell1"));
      assertTrue(result.contains("cell2"));
    }

    @Test
    void emptyTableSkipped() {
      var doc = new StructuredDocument(List.of(new Table(List.of(), -1)), 0);
      assertEquals("", doc.toAnnotatedText());
    }

    @Test
    void tableEmptyCellsSkipped() {
      var table =
          new Table(
              List.of(List.of("Header1", "Header2"), List.of("value", "")),
              -1);
      var doc = new StructuredDocument(List.of(table), 0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("Header1 = value"), result);
      assertFalse(result.contains("Header2 ="), "Empty cells should be skipped");
    }

    @Test
    void pageBreakRendering() {
      var doc =
          new StructuredDocument(
              List.of(
                  new Paragraph("Page 1 content", 0),
                  new PageBreak(1),
                  new Paragraph("Page 2 content", 1)),
              2);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("---"), "Page break should render as ---");
      assertTrue(result.indexOf("Page 1") < result.indexOf("---"));
      assertTrue(result.indexOf("---") < result.indexOf("Page 2"));
    }

    @Test
    void unorderedList() {
      var doc =
          new StructuredDocument(
              List.of(new ListBlock(List.of("item one", "item two", "item three"), false, -1)), 0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("- item one"));
      assertTrue(result.contains("- item two"));
      assertTrue(result.contains("- item three"));
    }

    @Test
    void orderedList() {
      var doc =
          new StructuredDocument(
              List.of(new ListBlock(List.of("first", "second"), true, -1)), 0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("1. first"));
      assertTrue(result.contains("2. second"));
    }

    @Test
    void mixedDocument() {
      var doc =
          new StructuredDocument(
              List.of(
                  new Heading(1, "Report", 0),
                  new Paragraph("Introduction paragraph.", 0),
                  new Table(
                      List.of(List.of("Name", "Value"), List.of("Revenue", "$100")),
                      0),
                  new PageBreak(1),
                  new Heading(2, "Details", 1),
                  new ListBlock(List.of("Point A", "Point B"), false, 1)),
              2);
      String result = doc.toAnnotatedText();

      // Verify ordering
      int headingIdx = result.indexOf("# Report");
      int introIdx = result.indexOf("Introduction");
      int tableIdx = result.indexOf("Revenue");
      int breakIdx = result.indexOf("---");
      int detailsIdx = result.indexOf("## Details");
      int listIdx = result.indexOf("- Point A");

      assertTrue(headingIdx >= 0, "Heading missing");
      assertTrue(introIdx > headingIdx, "Intro should follow heading");
      assertTrue(tableIdx > introIdx, "Table should follow intro");
      assertTrue(breakIdx > tableIdx, "Break should follow table");
      assertTrue(detailsIdx > breakIdx, "Details should follow break");
      assertTrue(listIdx > detailsIdx, "List should follow details heading");
    }

    @Test
    void blankParagraphSkipped() {
      var doc = new StructuredDocument(List.of(new Paragraph("  ", -1)), 0);
      assertEquals("", doc.toAnnotatedText());
    }

    @Test
    void emptyListItemsSkipped() {
      var doc =
          new StructuredDocument(
              List.of(new ListBlock(List.of("good", "", "  ", "also good"), false, -1)), 0);
      String result = doc.toAnnotatedText();
      assertTrue(result.contains("- good"));
      assertTrue(result.contains("- also good"));
      assertFalse(result.contains("- \n"), "Empty items should be skipped");
    }
  }

  @Nested
  class FlatTextSerialization {

    @Test
    void concatenatesAllText() {
      var doc =
          new StructuredDocument(
              List.of(
                  new Heading(1, "Title", -1),
                  new Paragraph("Body", -1),
                  new Table(List.of(List.of("a", "b")), -1)),
              0);
      String result = doc.toFlatText();
      assertTrue(result.contains("Title"));
      assertTrue(result.contains("Body"));
      assertTrue(result.contains("a"));
      assertTrue(result.contains("b"));
    }

    @Test
    void pageBreaksOmitted() {
      var doc =
          new StructuredDocument(
              List.of(new Paragraph("before", 0), new PageBreak(1), new Paragraph("after", 1)), 2);
      String result = doc.toFlatText();
      assertFalse(result.contains("---"));
      assertTrue(result.contains("before"));
      assertTrue(result.contains("after"));
    }
  }

  @Nested
  class HeaderFooterRemoval {

    @Test
    void singlePageUnchanged() {
      var doc = new StructuredDocument(List.of(new Paragraph("content", 0)), 1);
      StructuredDocument result = doc.removeHeadersFooters();
      assertSame(doc, result, "Single-page document should be returned unchanged");
    }

    @Test
    void twoPageUnchanged() {
      var doc =
          new StructuredDocument(
              List.of(
                  new Paragraph("header", 0),
                  new PageBreak(1),
                  new Paragraph("header", 1)),
              2);
      StructuredDocument result = doc.removeHeadersFooters();
      assertSame(doc, result, "Two-page document should be returned unchanged");
    }

    @Test
    void repeatedHeadersRemoved() {
      List<Element> elements = new java.util.ArrayList<>();
      for (int p = 0; p < 4; p++) {
        if (p > 0) elements.add(new PageBreak(p));
        elements.add(new Paragraph("Page Header", p)); // repeated header
        elements.add(new Paragraph("Unique content page " + p, p));
      }
      var doc = new StructuredDocument(elements, 4);
      StructuredDocument result = doc.removeHeadersFooters(0.5);

      String text = result.toAnnotatedText();
      assertFalse(text.contains("Page Header"), "Repeated header should be removed");
      assertTrue(text.contains("Unique content page 0"));
      assertTrue(text.contains("Unique content page 3"));
    }

    @Test
    void repeatedFootersRemoved() {
      List<Element> elements = new java.util.ArrayList<>();
      for (int p = 0; p < 4; p++) {
        if (p > 0) elements.add(new PageBreak(p));
        elements.add(new Paragraph("Unique content " + p, p));
        elements.add(new Paragraph("Confidential - Do Not Distribute", p)); // repeated footer
      }
      var doc = new StructuredDocument(elements, 4);
      StructuredDocument result = doc.removeHeadersFooters(0.5);

      String text = result.toAnnotatedText();
      assertFalse(
          text.toLowerCase().contains("confidential"), "Repeated footer should be removed");
      assertTrue(text.contains("Unique content 0"));
      assertTrue(text.contains("Unique content 3"));
    }

    @Test
    void uniqueContentPreserved() {
      List<Element> elements = new java.util.ArrayList<>();
      for (int p = 0; p < 3; p++) {
        if (p > 0) elements.add(new PageBreak(p));
        elements.add(new Paragraph("Different header " + p, p));
        elements.add(new Paragraph("Different footer " + p, p));
      }
      var doc = new StructuredDocument(elements, 3);
      StructuredDocument result = doc.removeHeadersFooters(0.5);

      assertEquals(
          doc.elements().size(),
          result.elements().size(),
          "No elements should be removed when all are unique");
    }

    @Test
    void noPageBreaksUnchanged() {
      var doc =
          new StructuredDocument(
              List.of(new Paragraph("a", -1), new Paragraph("b", -1), new Paragraph("c", -1)), 0);
      StructuredDocument result = doc.removeHeadersFooters();
      assertSame(doc, result, "Documents without page info should be unchanged");
    }
  }
}
