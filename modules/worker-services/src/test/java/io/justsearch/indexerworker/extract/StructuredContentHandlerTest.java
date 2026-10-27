package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.indexing.extraction.StructuredDocument;
import io.justsearch.indexing.extraction.StructuredDocument.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

class StructuredContentHandlerTest {

  private static final int MAX_LEN = 10 * 1024 * 1024;
  private static final Attributes EMPTY_ATTRS = new AttributesImpl();

  @Nested
  class ParagraphParsing {

    @Test
    void plainTextBecomesParagraph() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      chars(handler, "Hello world");
      StructuredDocument doc = handler.getDocument();

      assertEquals(1, doc.elements().size());
      assertInstanceOf(Paragraph.class, doc.elements().getFirst());
      assertEquals("Hello world", ((Paragraph) doc.elements().getFirst()).text());
    }

    @Test
    void pElementFlushes() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "p", "p", EMPTY_ATTRS);
      chars(handler, "First para");
      handler.endElement("", "p", "p");
      handler.startElement("", "p", "p", EMPTY_ATTRS);
      chars(handler, "Second para");
      handler.endElement("", "p", "p");

      StructuredDocument doc = handler.getDocument();
      assertEquals(2, doc.elements().size());
      assertEquals("First para", ((Paragraph) doc.elements().get(0)).text());
      assertEquals("Second para", ((Paragraph) doc.elements().get(1)).text());
    }
  }

  @Nested
  class HeadingParsing {

    @Test
    void h1Heading() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "h1", "h1", EMPTY_ATTRS);
      chars(handler, "Title");
      handler.endElement("", "h1", "h1");

      StructuredDocument doc = handler.getDocument();
      assertEquals(1, doc.elements().size());
      var heading = (Heading) doc.elements().getFirst();
      assertEquals(1, heading.level());
      assertEquals("Title", heading.text());
    }

    @Test
    void h3Heading() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "h3", "h3", EMPTY_ATTRS);
      chars(handler, "Subsection");
      handler.endElement("", "h3", "h3");

      StructuredDocument doc = handler.getDocument();
      var heading = (Heading) doc.elements().getFirst();
      assertEquals(3, heading.level());
      assertEquals("Subsection", heading.text());
    }

    @Test
    void textBeforeHeadingFlushedAsParagraph() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      chars(handler, "Before heading");
      handler.startElement("", "h2", "h2", EMPTY_ATTRS);
      chars(handler, "The Heading");
      handler.endElement("", "h2", "h2");

      StructuredDocument doc = handler.getDocument();
      assertEquals(2, doc.elements().size());
      assertInstanceOf(Paragraph.class, doc.elements().get(0));
      assertInstanceOf(Heading.class, doc.elements().get(1));
    }
  }

  @Nested
  class TableParsing {

    @Test
    void simpleTable() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "table", "table", EMPTY_ATTRS);
      // Row 1 (header)
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "th", "th", EMPTY_ATTRS);
      chars(handler, "Name");
      handler.endElement("", "th", "th");
      handler.startElement("", "th", "th", EMPTY_ATTRS);
      chars(handler, "Value");
      handler.endElement("", "th", "th");
      handler.endElement("", "tr", "tr");
      // Row 2 (data)
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "Revenue");
      handler.endElement("", "td", "td");
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "$100M");
      handler.endElement("", "td", "td");
      handler.endElement("", "tr", "tr");
      handler.endElement("", "table", "table");

      StructuredDocument doc = handler.getDocument();
      assertEquals(1, doc.elements().size());
      var table = (Table) doc.elements().getFirst();
      assertEquals(2, table.rows().size());
      assertEquals("Name", table.rows().get(0).get(0));
      assertEquals("Revenue", table.rows().get(1).get(0));
      assertEquals("$100M", table.rows().get(1).get(1));
    }

    @Test
    void nestedTableFlattened() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "table", "table", EMPTY_ATTRS);
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "outer");
      // Nested table — should be ignored
      handler.startElement("", "table", "table", EMPTY_ATTRS);
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "inner");
      handler.endElement("", "td", "td");
      handler.endElement("", "tr", "tr");
      handler.endElement("", "table", "table");
      handler.endElement("", "td", "td");
      handler.endElement("", "tr", "tr");
      handler.endElement("", "table", "table");

      StructuredDocument doc = handler.getDocument();
      assertEquals(1, doc.elements().size());
      var table = (Table) doc.elements().getFirst();
      // The outer cell should contain the outer text (inner table text goes to cell buffer)
      assertTrue(table.rows().get(0).get(0).contains("outer"));
    }

    @Test
    void textBeforeTableFlushed() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      chars(handler, "Intro text");
      handler.startElement("", "table", "table", EMPTY_ATTRS);
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "cell");
      handler.endElement("", "td", "td");
      handler.endElement("", "tr", "tr");
      handler.endElement("", "table", "table");

      StructuredDocument doc = handler.getDocument();
      assertEquals(2, doc.elements().size());
      assertInstanceOf(Paragraph.class, doc.elements().get(0));
      assertInstanceOf(Table.class, doc.elements().get(1));
    }
  }

  @Nested
  class PageBreakParsing {

    @Test
    void divWithPageClass() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      chars(handler, "Page 1 text");

      var pageAtts = new AttributesImpl();
      pageAtts.addAttribute("", "class", "class", "CDATA", "page");
      handler.startElement("", "div", "div", pageAtts);
      handler.endElement("", "div", "div");

      chars(handler, "Page 2 text");

      StructuredDocument doc = handler.getDocument();
      assertEquals(2, doc.elements().size());
      assertInstanceOf(Paragraph.class, doc.elements().get(0));
      assertInstanceOf(Paragraph.class, doc.elements().get(1));

      assertEquals(0, ((Paragraph) doc.elements().get(0)).pageIndex());
      assertEquals(0, ((Paragraph) doc.elements().get(1)).pageIndex());
      assertEquals(1, doc.pageCount());
    }

    @Test
    void divWithoutPageClassIgnored() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      var divAtts = new AttributesImpl();
      divAtts.addAttribute("", "class", "class", "CDATA", "content");
      handler.startElement("", "div", "div", divAtts);
      chars(handler, "text");
      handler.endElement("", "div", "div");

      StructuredDocument doc = handler.getDocument();
      assertEquals(1, doc.elements().size());
      assertInstanceOf(Paragraph.class, doc.elements().getFirst());
    }
  }

  @Nested
  class ListParsing {

    @Test
    void unorderedList() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "ul", "ul", EMPTY_ATTRS);
      handler.startElement("", "li", "li", EMPTY_ATTRS);
      chars(handler, "item 1");
      handler.endElement("", "li", "li");
      handler.startElement("", "li", "li", EMPTY_ATTRS);
      chars(handler, "item 2");
      handler.endElement("", "li", "li");
      handler.endElement("", "ul", "ul");

      StructuredDocument doc = handler.getDocument();
      assertEquals(1, doc.elements().size());
      var list = (ListBlock) doc.elements().getFirst();
      assertFalse(list.ordered());
      assertEquals(2, list.items().size());
      assertEquals("item 1", list.items().get(0));
      assertEquals("item 2", list.items().get(1));
    }

    @Test
    void orderedList() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);
      handler.startElement("", "ol", "ol", EMPTY_ATTRS);
      handler.startElement("", "li", "li", EMPTY_ATTRS);
      chars(handler, "first");
      handler.endElement("", "li", "li");
      handler.endElement("", "ol", "ol");

      StructuredDocument doc = handler.getDocument();
      var list = (ListBlock) doc.elements().getFirst();
      assertTrue(list.ordered());
    }
  }

  @Nested
  class CharLimitTracking {

    @Test
    void stopsAcceptingAfterLimit() throws SAXException {
      var handler = new StructuredContentHandler(10);
      chars(handler, "12345678901234567890"); // 20 chars, limit is 10

      StructuredDocument doc = handler.getDocument();
      // Should have captured something before the limit was reached
      assertFalse(doc.elements().isEmpty());
    }
  }

  @Nested
  class MixedContent {

    @Test
    void fullDocumentFlow() throws SAXException {
      var handler = new StructuredContentHandler(MAX_LEN);

      // H1
      handler.startElement("", "h1", "h1", EMPTY_ATTRS);
      chars(handler, "Report Title");
      handler.endElement("", "h1", "h1");

      // Paragraph
      handler.startElement("", "p", "p", EMPTY_ATTRS);
      chars(handler, "Introduction text.");
      handler.endElement("", "p", "p");

      // Table
      handler.startElement("", "table", "table", EMPTY_ATTRS);
      handler.startElement("", "tr", "tr", EMPTY_ATTRS);
      handler.startElement("", "td", "td", EMPTY_ATTRS);
      chars(handler, "A");
      handler.endElement("", "td", "td");
      handler.endElement("", "tr", "tr");
      handler.endElement("", "table", "table");

      // Page break
      var pageAtts = new AttributesImpl();
      pageAtts.addAttribute("", "class", "class", "CDATA", "page");
      handler.startElement("", "div", "div", pageAtts);
      handler.endElement("", "div", "div");

      // H2
      handler.startElement("", "h2", "h2", EMPTY_ATTRS);
      chars(handler, "Section Two");
      handler.endElement("", "h2", "h2");

      // List
      handler.startElement("", "ul", "ul", EMPTY_ATTRS);
      handler.startElement("", "li", "li", EMPTY_ATTRS);
      chars(handler, "Point");
      handler.endElement("", "li", "li");
      handler.endElement("", "ul", "ul");

      StructuredDocument doc = handler.getDocument();
      assertEquals(5, doc.elements().size());
      assertInstanceOf(Heading.class, doc.elements().get(0));
      assertInstanceOf(Paragraph.class, doc.elements().get(1));
      assertInstanceOf(Table.class, doc.elements().get(2));
      assertInstanceOf(Heading.class, doc.elements().get(3));
      assertInstanceOf(ListBlock.class, doc.elements().get(4));
      assertEquals(1, doc.pageCount());
    }
  }

  // ---- Helper ----

  private static void chars(StructuredContentHandler handler, String text) throws SAXException {
    handler.characters(text.toCharArray(), 0, text.length());
  }
}
