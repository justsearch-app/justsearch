/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.justsearch.indexing.extraction.StructuredDocument.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

class PdfWordBoundaryTest {
  @TempDir Path tempDir;
  private static final AttributesImpl EMPTY = new AttributesImpl();

  @ParameterizedTest
  @ValueSource(strings = {"p", "h2", "td", "li"})
  void pdfLineSeparatorsAreKeptInsideEachTextElement(String element) throws Exception {
    var handler = handler(1000, true);
    startContainer(handler, element);
    start(handler, element);
    chars(handler, "nur");
    whitespace(handler, "\n");
    chars(handler, "unzureichend Rech-");
    whitespace(handler, "\n");
    chars(handler, "nung");
    end(handler, element);
    endContainer(handler, element);
    assertEquals("nur\nunzureichend Rechnung", elementText(handler));
  }

  @ParameterizedTest
  @ValueSource(strings = {"p", "h2", "td", "li"})
  void otherFormatsKeepTheirExistingWhitespaceAndHyphens(String element) throws Exception {
    var handler = handler(1000, false);
    startContainer(handler, element);
    start(handler, element);
    chars(handler, "nur");
    whitespace(handler, "\n");
    chars(handler, "unzureichend Rech-\nnung");
    end(handler, element);
    endContainer(handler, element);
    assertEquals("nurunzureichend Rech-\nnung", elementText(handler));
  }

  @ParameterizedTest
  @ValueSource(strings = {"p", "h2", "td", "li"})
  void joiningAndSeparatorsNeverCrossElements(String element) throws Exception {
    var handler = handler(1000, true);
    whitespace(handler, "\n"); // outside text elements is still ignored
    startContainer(handler, element);
    for (String text : List.of("Rech-", "nung")) {
      start(handler, element);
      chars(handler, text);
      end(handler, element);
      whitespace(handler, "\n");
    }
    endContainer(handler, element);
    String text = handler.getDocument().toAnnotatedText();
    assertTrue(text.contains("Rech-"), text);
    assertTrue(text.contains("nung"), text);
    assertFalse(text.contains("Rechnung"), text);
    assertFalse(text.contains("Rech-nung"), text);
  }

  @ParameterizedTest
  @CsvSource({"Rech-,nung,Rechnung", "Anträ‐,ge,Anträge", "Know-,how,Knowhow",
      "1-,a,1-<BR>a", "Rech‐,nung,Rechnung", "Är-,ger,Ärger",
      "E-,Mail,E-<BR>Mail", "CDU-,Fraktion,CDU-<BR>Fraktion", "A-,1,A-<BR>1",
      "a-,ǅ,a-<BR>ǅ", "a-,中,a-<BR>中"})
  void lineEndHyphenRuleUsesUnicodeCategories(String left, String right, String expected)
      throws Exception {
    for (String separator : List.of("\n", "\r\n", "\r", " \t\r\n\t ")) {
      var handler = handler(1000, true);
      start(handler, "p");
      // Separate callbacks for letters, hyphen and each whitespace character.
      for (int i = 0; i < left.length(); i++) chars(handler, left.substring(i, i + 1));
      for (int i = 0; i < separator.length(); i++) whitespace(handler, separator.substring(i, i + 1));
      chars(handler, right);
      end(handler, "p");
      assertEquals(expected.replace("<BR>", separator), elementText(handler));
    }
  }

  @Test
  void characterCallbacksAreJoinedOnlyAtOneLineBreakAndInlineMarkupAddsNoSpace() throws Exception {
    var handler = handler(1000, true);
    start(handler, "p");
    chars(handler, "Rech- \r\n\t");
    start(handler, "b");
    chars(handler, "nung");
    end(handler, "b");
    chars(handler, " E-Mail Rech-\n\nnung");
    end(handler, "p");
    assertEquals("Rechnung E-Mail Rech-\n\nnung", elementText(handler));
  }

  @Test
  void pageBoundariesNeverJoinWordsOrHyphens() throws Exception {
    var handler = handler(1000, true);
    var page = new AttributesImpl();
    page.addAttribute("", "class", "class", "CDATA", "page");
    for (String text : List.of("Rech-", "nung")) {
      handler.startElement("", "div", "div", page);
      start(handler, "p");
      chars(handler, text);
      end(handler, "p");
      end(handler, "div");
    }
    assertEquals("Rech-\n\n---\n\nnung", handler.getDocument().toAnnotatedText());
  }

  @Test
  void keptWhitespaceSharesCharacterLimitAndSurrogateSafety() throws Exception {
    var handler = handler(5, true);
    start(handler, "p");
    chars(handler, "abc");
    whitespace(handler, "\n");
    chars(handler, "😀tail");
    assertTrue(handler.isLimitReached());
    assertEquals("abc", elementText(handler)); // trailing newline stripped, no orphan surrogate
    var exact = handler(5, true);
    start(exact, "p");
    chars(exact, "abc");
    whitespace(exact, "\n");
    chars(exact, "de");
    assertTrue(exact.isLimitReached());
    assertEquals("abc\nd", elementText(exact));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void realPdfPreservesWrappedWordsAndJoinsSplitWords(boolean tagged) throws Exception {
    Path pdf = writePdf(tagged);
    try (var extractor = new StructuredContentExtractor()) {
      var result = extractor.extractWithStatus(pdf);
      String text = result.result().content();
      assertEquals(1, result.pageCount(), "must use structured extraction");
      assertTrue(text.contains("nur\nunzureichend Rechnung"), text);
      assertTrue(text.contains("beitragspflichtig\nwaren Anträge"), text);
      assertFalse(result.truncated());
    }
  }

  @Test
  void flatFallbackUsesPhysicalPdfTextAndKeepsItsLineBreaks() throws Exception {
    Path pdf = writePdf(true);
    var failed = new AtomicBoolean();
    var markedContent = new AtomicBoolean(true);
    try (var extractor = new StructuredContentExtractor()) {
      AutoDetectParser parser = mock(AutoDetectParser.class);
      doAnswer(call -> {
        ParseContext context = call.getArgument(3);
        Class<?> configType = Class.forName("org.apache.tika.parser.pdf.PDFParserConfig");
        Object config = context.get(configType);
        markedContent.set((Boolean) configType.getMethod("isExtractMarkedContent").invoke(config));
        failed.set(true);
        throw new SAXException("forced structured failure");
      }).when(parser).parse(any(), any(), any(), any());
      var parserField = StructuredContentExtractor.class.getDeclaredField("parser");
      parserField.setAccessible(true);
      parserField.set(extractor, parser);
      var result = extractor.extractWithStatus(pdf);
      assertTrue(failed.get());
      assertFalse(markedContent.get(), "flat fallback must inherit physical PDF settings");
      assertEquals(0, result.pageCount(), "empty summary proves flat fallback ran");
      assertTrue(result.result().content().contains("nur\nunzureichend Rech-\nnung"),
          result.result().content());
    }
  }

  private Path writePdf(boolean tagged) throws Exception {
    Path pdf = tempDir.resolve("wrapped-" + tagged + ".pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      if (tagged) {
        var root = new PDStructureTreeRoot();
        document.getDocumentCatalog().setStructureTreeRoot(root);
        var markInfo = new PDMarkInfo();
        markInfo.setMarked(true);
        document.getDocumentCatalog().setMarkInfo(markInfo);
        var paragraph = new PDStructureElement("P", root);
        paragraph.setPage(page);
        paragraph.appendKid(0);
        root.appendKid(paragraph);
      }
      try (var content = new PDPageContentStream(document, page)) {
        if (tagged) {
          var properties = new COSDictionary();
          properties.setInt(COSName.MCID, 0);
          content.beginMarkedContent(COSName.P, PDPropertyList.create(properties));
        }
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(40, 700);
        for (String line : List.of("nur", "unzureichend Rech-", "nung beitragspflichtig", "waren Anträ-", "ge")) {
          content.showText(line);
          content.newLineAtOffset(0, -14);
        }
        content.endText();
        if (tagged) content.endMarkedContent();
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }

  private static StructuredContentHandler handler(int limit, boolean pdf) {
    return new StructuredContentHandler(limit, pdf);
  }

  private static String elementText(StructuredContentHandler handler) {
    return switch (handler.getDocument().elements().getFirst()) {
      case Paragraph p -> p.text();
      case Heading h -> h.text();
      case Table t -> t.rows().getFirst().getFirst();
      case ListBlock l -> l.items().getFirst();
      default -> throw new AssertionError("expected text element");
    };
  }

  private static void startContainer(StructuredContentHandler handler, String element) throws Exception {
    if (element.equals("td")) { start(handler, "table"); start(handler, "tr"); }
    if (element.equals("li")) start(handler, "ul");
  }

  private static void endContainer(StructuredContentHandler handler, String element) throws Exception {
    if (element.equals("td")) { end(handler, "tr"); end(handler, "table"); }
    if (element.equals("li")) end(handler, "ul");
  }

  private static void start(StructuredContentHandler handler, String name) throws SAXException {
    handler.startElement("", name, name, EMPTY);
  }

  private static void end(StructuredContentHandler handler, String name) throws SAXException {
    handler.endElement("", name, name);
  }

  private static void chars(StructuredContentHandler handler, String text) throws SAXException {
    handler.characters(text.toCharArray(), 0, text.length());
  }

  private static void whitespace(StructuredContentHandler handler, String text) throws SAXException {
    handler.ignorableWhitespace(text.toCharArray(), 0, text.length());
  }
}
