package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.extraction.StructuredDocument.ListBlock;
import io.justsearch.indexing.extraction.StructuredDocument.Table;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

class StructuredBufferBoundaryTest {
  @ParameterizedTest
  @ValueSource(strings = {"td", "li"})
  void htmlBreakNewlineSeparatesWords(String bufferTag) throws SAXException {
    var handler = buffer(bufferTag, 1000);
    chars(handler, "alpha");
    handler.ignorableWhitespace(new char[] {'\n'}, 0, 1);
    chars(handler, "beta");
    end(handler, bufferTag);

    assertEquals("alpha beta", text(handler));
  }

  @ParameterizedTest
  @ValueSource(strings = {"td", "li"})
  void whitespaceOnlyBlocksAcrossCallbacksDoNotMultiplySeparators(String bufferTag)
      throws SAXException {
    var handler = buffer(bufferTag, 1000);
    start(handler, "p");
    chars(handler, "alpha");
    end(handler, "p");
    start(handler, "p");
    chars(handler, " ");
    chars(handler, "\t\n");
    end(handler, "p");
    start(handler, "p");
    chars(handler, " ");
    chars(handler, "be");
    start(handler, "b");
    chars(handler, "ta");
    end(handler, "b");
    chars(handler, "  gamma");
    end(handler, "p");
    end(handler, bufferTag);

    assertEquals("alpha beta  gamma", text(handler));
    assertFalse(handler.isLimitReached());
  }

  @ParameterizedTest
  @ValueSource(strings = {"td", "li"})
  void separatorUsesOneBudgetUnitBeforeSurrogatePair(String bufferTag) throws SAXException {
    for (int cap = 6; cap <= 8; cap++) {
      var handler = buffer(bufferTag, cap);
      start(handler, "p");
      chars(handler, "alpha");
      end(handler, "p");
      start(handler, "p");
      chars(handler, "😀");
      end(handler, "p");
      end(handler, bufferTag);

      assertTrue(handler.isLimitReached(), "cap=" + cap);
      assertEquals(cap == 8 ? "alpha 😀" : "alpha", text(handler), "cap=" + cap);
    }
  }

  private static StructuredContentHandler buffer(String tag, int cap) throws SAXException {
    var handler = new StructuredContentHandler(cap);
    if (tag.equals("td")) {
      start(handler, "table");
      start(handler, "tr");
    } else {
      start(handler, "ul");
    }
    start(handler, tag);
    return handler;
  }

  private static String text(StructuredContentHandler handler) {
    var element = handler.getDocument().elements().getFirst();
    return element instanceof Table table
        ? table.rows().getFirst().getFirst()
        : ((ListBlock) element).items().getFirst();
  }

  private static void start(StructuredContentHandler handler, String tag) throws SAXException {
    handler.startElement("", tag, tag, new AttributesImpl());
  }

  private static void end(StructuredContentHandler handler, String tag) throws SAXException {
    handler.endElement("", tag, tag);
  }

  private static void chars(StructuredContentHandler handler, String text) throws SAXException {
    handler.characters(text.toCharArray(), 0, text.length());
  }
}
