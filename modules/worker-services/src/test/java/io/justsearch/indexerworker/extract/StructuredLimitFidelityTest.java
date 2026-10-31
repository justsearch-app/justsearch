/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import io.justsearch.indexing.extraction.StructuredDocument;
import io.justsearch.indexing.extraction.StructuredDocument.Table;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.helpers.AttributesImpl;

class StructuredLimitFidelityTest {
  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings = {"h2", "ul", "ol", "table"})
  void activeStructureRetainsPrefixAndFinalizesOnce(String kind) throws Exception {
    for (boolean exact : List.of(true, false)) {
      var handler = new StructuredContentHandler(10);
      start(handler, kind);
      if (kind.equals("table")) { start(handler, "tr"); start(handler, "td"); }
      if (kind.equals("ul") || kind.equals("ol")) start(handler, "li");
      chars(handler, exact ? "UniqueSecr" : "UniqueSecretLater");
      var first = handler.getDocument();
      assertEquals("UniqueSecr", first.toFlatText().strip());
      assertTrue(handler.isLimitReached());
      assertEquals(first, handler.getDocument());
      handler.endElement("", kind, kind);
      chars(handler, "must never enter");
      assertEquals(first, handler.getDocument());
      assertTrue(first.toAnnotatedText(10).text().length() <= 10);
      assertTrue(first.toAnnotatedText(10).truncated());
    }
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings = {"ul", "ol", "table"})
  void completedMembersAndActivePrefixSurvive(String kind) throws Exception {
    var handler = new StructuredContentHandler(10);
    start(handler, kind);
    if (kind.equals("table")) start(handler, "tr");
    String member = kind.equals("table") ? "td" : "li";
    start(handler, member); chars(handler, "Done"); handler.endElement("", member, member);
    start(handler, member); chars(handler, "UniqueSecret");
    String text = handler.getDocument().toFlatText();
    assertTrue(text.contains("Done"));
    assertTrue(text.contains("Unique"));
    assertFalse(text.contains("Secret"));
  }

  @ParameterizedTest(name = "{displayName} [{index}] {arguments}") @ValueSource(strings = {"h1", "ul", "table", "p"})
  void supplementaryCharactersAreNotSplitAtTheBoundary(String kind) throws Exception {
    for (boolean splitEvent : List.of(true, false)) {
      var handler = new StructuredContentHandler(3);
      start(handler, kind);
      if (kind.equals("table")) { start(handler, "tr"); start(handler, "td"); }
      if (kind.equals("ul")) start(handler, "li");
      if (splitEvent) { chars(handler, "ab\uD83D"); chars(handler, "\uDE00later"); }
      else chars(handler, "ab\uD83D\uDE00later");
      assertEquals("ab", handler.getDocument().toFlatText().strip());
    }
  }

  @Test
  void limitInsideBlankBodyCellKeepsCompletedHeader() throws Exception {
    var handler = new StructuredContentHandler(7);
    start(handler, "table");
    start(handler, "tr");
    start(handler, "td");
    chars(handler, "Secret");
    handler.endElement("", "td", "td");
    handler.endElement("", "tr", "tr");
    start(handler, "tr");
    start(handler, "td");
    chars(handler, " ");
    assertTrue(handler.isLimitReached());
    var document = handler.getDocument();
    assertEquals("Secret", document.toAnnotatedText(7).text());
    assertEquals(document, handler.getDocument());
    handler.endElement("", "td", "td");
    handler.endElement("", "table", "table");
    assertEquals(document, handler.getDocument());
  }

  @Test
  void limitInsideLaterBlankCellKeepsItsHeaderAfterEarlierValue() throws Exception {
    var complete = new StructuredDocument(List.of(new Table(
        List.of(List.of("A", "Secret"), List.of("x", "")), 0)), 1);
    assertEquals("A = x", complete.toAnnotatedText(20).text());
    for (boolean exact : List.of(true, false)) {
      var handler = new StructuredContentHandler(20);
      start(handler, "table");
      start(handler, "tr");
      for (String header : List.of("A", "Secret")) {
        start(handler, "td");
        chars(handler, header);
        handler.endElement("", "td", "td");
      }
      handler.endElement("", "tr", "tr");
      start(handler, "tr");
      start(handler, "td");
      chars(handler, "x");
      handler.endElement("", "td", "td");
      start(handler, "td");
      chars(handler, " ".repeat(exact ? 12 : 13));
      assertTrue(handler.isLimitReached());
      var document = handler.getDocument();
      assertEquals("A = x\nSecret", document.toAnnotatedText(20).text());
      assertEquals("A = x\nSecr", document.toAnnotatedText(10).text());
      assertTrue(document.toAnnotatedText(10).truncated());
      assertEquals(document, handler.getDocument());
      handler.endElement("", "td", "td");
      handler.endElement("", "table", "table");
      chars(handler, "must never enter");
      assertEquals(document, handler.getDocument());
    }
  }

  private static void start(StructuredContentHandler handler, String tag) throws Exception {
    handler.startElement("", tag, tag, new AttributesImpl());
  }
  private static void chars(StructuredContentHandler handler, String text) throws Exception {
    handler.characters(text.toCharArray(), 0, text.length());
  }
}
