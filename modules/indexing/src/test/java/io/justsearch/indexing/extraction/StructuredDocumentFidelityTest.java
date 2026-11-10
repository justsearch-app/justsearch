/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.extraction;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.indexing.extraction.StructuredDocument.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredDocumentFidelityTest {
  @Test void laterWiderAndRaggedRowsRetainEveryCell() {
    for (var rows : List.of(
        List.of(List.of("Report"), List.of("Name", "UniqueSecret")),
        List.of(List.<String>of(), List.of("Name", "UniqueSecret")),
        List.of(List.of("Title"), List.of("Name", "Value"), List.of("A", "B", "UniqueSecret")))) {
      var doc = new StructuredDocument(List.of(new Table(rows, 0)), 1);
      assertTrue(doc.toAnnotatedText().contains("UniqueSecret"));
      assertTrue(doc.toFlatText().contains("UniqueSecret"));
      var bounded = doc.toAnnotatedText(15);
      assertTrue(bounded.truncated());
      assertTrue(bounded.text().length() <= 15);
    }
  }

  @Test void widerRowsKeepExistingLabelsAndDoNotLabelOverflowCells() {
    var doc = new StructuredDocument(List.of(new Table(List.of(
        List.of("Report"), List.of("Name", "UniqueSecret")), 0)), 1);
    assertEquals("Report = Name\nUniqueSecret", doc.toAnnotatedText());
    assertEquals("Report = Name", doc.toAnnotatedText(13).text());
    assertTrue(doc.toAnnotatedText(13).truncated());
  }

  @Test void distinctLongPageEdgesSurviveForEveryTextElementKind() {
    for (String kind : List.of("paragraph", "heading", "list", "table")) {
      var elements = new ArrayList<Element>();
      for (int page = 0; page < 3; page++) {
        elements.add(element(kind, "h".repeat(200) + "HeaderSecret" + page, page));
        elements.add(new Paragraph("Interior" + page, page));
        elements.add(element(kind, "f".repeat(200) + "FooterSecret" + page, page));
      }
      String retained = new StructuredDocument(elements, 3).removeHeadersFooters().toFlatText();
      for (int page = 0; page < 3; page++) {
        assertTrue(retained.contains("HeaderSecret" + page), kind);
        assertTrue(retained.contains("FooterSecret" + page), kind);
      }
    }
  }

  @Test void identicalEdgesAreRemovedButMatchingInteriorRemains() {
    var elements = new ArrayList<Element>();
    for (int page = 0; page < 3; page++) {
      elements.add(new Paragraph("header".repeat(50), page));
      elements.add(new Paragraph("header".repeat(50), page));
      elements.add(new Paragraph("footer".repeat(50), page));
    }
    var retained = new StructuredDocument(elements, 3).removeHeadersFooters();
    assertEquals(3, retained.elements().size());
    assertTrue(retained.elements().stream().allMatch(e -> ((Paragraph)e).text().startsWith("header")));
  }

  @Test void caseDistinctPageEdgesSurviveForEveryWholeElementKind() {
    for (var variants : List.of(
        List.of("Identifier", "IDENTIFIER", "identifier"),
        List.of("Ärger", "ÄRGER", "ärger"),
        List.of("h".repeat(200) + "Code", "h".repeat(200) + "CODE", "h".repeat(200) + "code"))) {
      for (String kind : List.of("paragraph", "heading", "list", "table")) {
        var elements = new ArrayList<Element>();
        for (int page = 0; page < 3; page++) {
          elements.add(edge(kind, "Header " + variants.get(page), page));
          elements.add(new Paragraph("Interior" + page, page));
          elements.add(edge(kind, "Footer " + variants.get(page), page));
          if (page < 2) elements.add(new PageBreak(page + 1));
        }
        var document = new StructuredDocument(elements, 3);
        var retained = document.removeHeadersFooters();
        assertEquals(document.elements(), retained.elements(), kind + " " + variants);
        assertEquals(document.toAnnotatedText(), retained.toAnnotatedText(), kind);
        assertEquals(3, retained.pageCount());
      }
    }
  }

  @Test void repeatedVariantDoesNotRemoveMinorityCaseSpelling() {
    for (String kind : List.of("paragraph", "heading", "list", "table")) {
      var elements = new ArrayList<Element>();
      var expected = new ArrayList<Element>();
      for (int page = 0; page < 4; page++) {
        var header = edge(kind, page == 3 ? "Header CODE" : "Header Code", page);
        var interior = edge(kind, "Header Code", page);
        var footer = edge(kind, page == 3 ? "Footer CODE" : "Footer Code", page);
        elements.addAll(List.of(header, interior, footer));
        if (page == 3) expected.add(header);
        expected.add(interior);
        if (page == 3) expected.add(footer);
      }
      assertEquals(expected, new StructuredDocument(elements, 4).removeHeadersFooters().elements(), kind);
    }
  }

  @Test void edgeComparisonOnlyStripsAndKeepsHeaderFooterInventoriesSeparate() {
    var elements = new ArrayList<Element>();
    var expected = new ArrayList<Element>();
    for (int page = 0; page < 4; page++) {
      var header = new Paragraph(page < 2 ? "\tRepeated Header\u2003" : "Header" + page, page);
      var interior = new Paragraph("Repeated Header", page);
      var footer = new Paragraph(page == 0 ? "Repeated Header" : "Footer" + page, page);
      elements.addAll(List.of(header, interior, footer));
      if (page >= 2) expected.add(header);
      expected.addAll(List.of(interior, footer));
    }
    assertEquals(expected, new StructuredDocument(elements, 4).removeHeadersFooters().elements());
    for (var variants : List.of(
        List.of("é", "e\u0301", "É"),
        List.of("a b", "a  b", "a\tb"),
        List.of("I", "İ", "ı"))) {
      var distinct = new ArrayList<Element>();
      for (int page = 0; page < 3; page++) {
        distinct.add(new Paragraph(variants.get(page), page));
      }
      assertEquals(distinct, new StructuredDocument(distinct, 3).removeHeadersFooters().elements());
    }
  }

  private static Element edge(String kind, String text, int page) {
    return switch (kind) {
      case "list" -> new ListBlock(List.of("Common first item", text), false, page);
      case "table" -> new Table(List.of(List.of("Common first cell", text)), page);
      default -> element(kind, text, page);
    };
  }

  private static Element element(String kind, String text, int page) {
    return switch (kind) {
      case "heading" -> new Heading(1, text, page);
      case "list" -> new ListBlock(List.of(text), false, page);
      case "table" -> new Table(List.of(List.of(text)), page);
      default -> new Paragraph(text, page);
    };
  }
}
