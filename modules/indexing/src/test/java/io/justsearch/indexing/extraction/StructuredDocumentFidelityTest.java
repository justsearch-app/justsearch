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

  private static Element element(String kind, String text, int page) {
    return switch (kind) {
      case "heading" -> new Heading(1, text, page);
      case "list" -> new ListBlock(List.of(text), false, page);
      case "table" -> new Table(List.of(List.of(text)), page);
      default -> new Paragraph(text, page);
    };
  }
}
