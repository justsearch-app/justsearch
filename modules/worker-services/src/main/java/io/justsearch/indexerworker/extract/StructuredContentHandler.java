/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexing.extraction.StructuredDocument;
import io.justsearch.indexing.extraction.StructuredDocument.*;
import java.util.ArrayList;
import java.util.List;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX content handler that builds a {@link StructuredDocument} from Tika's XHTML SAX events.
 *
 * <p>Per-parse object — create a new instance for each parse operation.
 *
 * <p>Tika 3.x parsers emit XHTML elements: {@code h1}–{@code h6}, {@code table/tr/td/th}, {@code
 * p}, {@code div} (with {@code class="page"}), {@code ol/ul/li}. This handler captures these
 * structural elements and builds a typed intermediate representation.
 */
public final class StructuredContentHandler extends DefaultHandler {

  private final int maxContentLength;
  private final boolean pdf;
  private int paragraphDepth;
  private final List<Element> elements = new ArrayList<>();
  private int currentPageIndex = 0;
  private boolean hasPageWrappers = false;
  private int totalChars = 0;
  private boolean limitReached = false;

  // Text accumulator for content between structural elements
  private final StringBuilder textBuffer = new StringBuilder();

  // Heading state
  private int headingLevel = 0;
  private StringBuilder headingBuffer;

  // Table state
  private boolean inTable = false;
  private int tableNestingDepth = 0;
  private List<List<String>> tableRows;
  private List<String> currentRow;
  private StringBuilder cellBuffer;
  private boolean inCell = false;

  // List state
  private boolean inList = false;
  private int listNestingDepth = 0;
  private boolean orderedList = false;
  private List<String> listItems;
  private StringBuilder itemBuffer;
  private boolean inListItem = false;
  private StringBuilder boundaryBuffer;

  public StructuredContentHandler(int maxContentLength) {
    this(maxContentLength, false);
  }

  StructuredContentHandler(int maxContentLength, boolean pdf) {
    this.maxContentLength = maxContentLength;
    this.pdf = pdf;
  }

  @Override
  public void startElement(String uri, String localName, String qName, Attributes atts)
      throws SAXException {
    if (limitReached) return;

    String name = localName.isEmpty() ? qName : localName;
    String nameLower = name.toLowerCase(java.util.Locale.ROOT);

    switch (nameLower) {
      case "h1", "h2", "h3", "h4", "h5", "h6" -> {
        flushTextBuffer();
        headingLevel = nameLower.charAt(1) - '0';
        headingBuffer = new StringBuilder();
      }
      case "p" -> {
        paragraphDepth++;
        // Flush any accumulated text before the paragraph
        if (headingLevel == 0 && !inTable && !inListItem) {
          flushTextBuffer();
        }
      }
      case "table" -> {
        if (inCell) appendBufferSeparator();
        tableNestingDepth++;
        if (tableNestingDepth == 1) {
          flushTextBuffer();
          inTable = true;
          tableRows = new ArrayList<>();
          currentRow = null;
          cellBuffer = null;
        }
        // Nested tables: flatten their text into the enclosing cell.
      }
      case "tr" -> {
        if (inTable && tableNestingDepth == 1) {
          currentRow = new ArrayList<>();
        }
      }
      case "td", "th" -> {
        if (inTable && tableNestingDepth == 1) {
          cellBuffer = new StringBuilder();
          inCell = true;
        }
      }
      case "div" -> {
        String cssClass = atts.getValue("class");
        if (cssClass != null && List.of(cssClass.split("\\s+")).contains("page")) {
          flushTextBuffer();
          if (hasPageWrappers) {
            currentPageIndex++;
            addElement(new PageBreak(currentPageIndex));
          }
          hasPageWrappers = true;
        }
      }
      case "ol" -> {
        if (inCell) {
          appendBufferSeparator();
          break;
        }
        listNestingDepth++;
        if (listNestingDepth == 1) {
          flushTextBuffer();
          inList = true;
          orderedList = true;
          listItems = new ArrayList<>();
        }
      }
      case "ul" -> {
        if (inCell) {
          appendBufferSeparator();
          break;
        }
        listNestingDepth++;
        if (listNestingDepth == 1) {
          flushTextBuffer();
          inList = true;
          orderedList = false;
          listItems = new ArrayList<>();
        }
      }
      case "li" -> {
        if (inCell) {
          appendBufferSeparator();
          break;
        }
        if (inList && listNestingDepth == 1) {
          itemBuffer = new StringBuilder();
          inListItem = true;
        }
      }
      default -> {
        // Ignore unknown elements
      }
    }
  }

  @Override
  public void endElement(String uri, String localName, String qName) throws SAXException {
    if (limitReached) return;

    String name = localName.isEmpty() ? qName : localName;
    String nameLower = name.toLowerCase(java.util.Locale.ROOT);

    switch (nameLower) {
      case "h1", "h2", "h3", "h4", "h5", "h6" -> {
        if (headingLevel > 0 && headingBuffer != null) {
          String text = finishText(headingBuffer);
          if (!text.isEmpty()) {
            addElement(new Heading(headingLevel, text, currentPageIndex));
          }
          headingLevel = 0;
          headingBuffer = null;
        }
      }
      case "p" -> {
        paragraphDepth--;
        appendBufferSeparator();
        if (!inTable && !inListItem && headingLevel == 0) {
          flushTextBuffer();
        }
      }
      case "table" -> {
        tableNestingDepth--;
        if (tableNestingDepth == 0 && inTable) {
          // Flush last row if not already added
          if (currentRow != null && !currentRow.isEmpty()) {
            tableRows.add(currentRow);
          }
          if (!tableRows.isEmpty()) {
            flushTextBuffer();
            addElement(new Table(List.copyOf(tableRows), currentPageIndex));
          }
          inTable = false;
          tableRows = null;
          currentRow = null;
          cellBuffer = null;
          inCell = false;
        }
      }
      case "tr" -> {
        if (tableNestingDepth > 1) appendBufferSeparator();
        if (inTable && tableNestingDepth == 1 && currentRow != null) {
          tableRows.add(currentRow);
          currentRow = new ArrayList<>();
        }
      }
      case "td", "th" -> {
        if (tableNestingDepth > 1) appendBufferSeparator();
        if (inTable && tableNestingDepth == 1 && cellBuffer != null && currentRow != null) {
          currentRow.add(finishText(cellBuffer));
          cellBuffer = new StringBuilder();
          inCell = false;
        }
      }
      case "ol", "ul" -> {
        if (inCell) {
          appendBufferSeparator();
          break;
        }
        listNestingDepth--;
        if (listNestingDepth == 0 && inList) {
          if (!listItems.isEmpty()) {
            addElement(new ListBlock(List.copyOf(listItems), orderedList, currentPageIndex));
          }
          inList = false;
          listItems = null;
        }
      }
      case "li" -> {
        if (inCell) {
          appendBufferSeparator();
          break;
        }
        if (inList && listNestingDepth == 1 && itemBuffer != null) {
          String text = finishText(itemBuffer);
          if (!text.isEmpty()) {
            listItems.add(text);
          }
          itemBuffer = null;
          inListItem = false;
        }
      }
      case "br" -> appendBufferSeparator();
      default -> {
        // Ignore unknown elements
      }
    }
  }

  @Override
  public void characters(char[] ch, int start, int length) throws SAXException {
    if (limitReached) return;

    int retained = Math.min(length, Math.max(0, maxContentLength - totalChars));
    if (retained > 0 && Character.isHighSurrogate(ch[start + retained - 1])
        && ((retained < length && Character.isLowSurrogate(ch[start + retained]))
            || totalChars + retained >= maxContentLength)) retained--;
    String text = new String(ch, start, retained);

    // A structural separator already supplies the whitespace before the next word.
    // Keep empty/whitespace-only blocks from adding more, even across SAX callbacks.
    StringBuilder buffer = inCell ? cellBuffer : inListItem ? itemBuffer : null;
    if (headingLevel == 0 && buffer != null && buffer == boundaryBuffer) {
      text = text.stripLeading();
      if (!text.isEmpty()) boundaryBuffer = null;
    }

    if (headingLevel > 0 && headingBuffer != null) {
      headingBuffer.append(text);
    } else if (inCell && cellBuffer != null) {
      cellBuffer.append(text);
    } else if (inListItem && itemBuffer != null) {
      itemBuffer.append(text);
    } else {
      textBuffer.append(text);
    }

    totalChars += retained;
    if (retained < length || totalChars >= maxContentLength) {
      limitReached = true;
    }
  }

  @Override
  public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
    if (pdf && (paragraphDepth > 0 || headingLevel > 0 || inCell || inListItem)) {
      // PDFBox's physical line separators arrive here, including between characters callbacks.
      // Reuse character admission so retained separators consume the same surrogate-safe budget.
      characters(ch, start, length);
    } else if (!pdf && (inCell || inListItem)) {
      // Tika's HTML parser emits a br as an ignorable newline rather than an element.
      for (int i = start; i < start + length; i++) {
        if (ch[i] == '\n' || ch[i] == '\r') {
          appendBufferSeparator();
          break;
        }
      }
    }
  }

  private String finishText(StringBuilder buffer) {
    String text = buffer.toString().strip();
    return pdf ? PdfTextNormalization.joinLineEndHyphens(text) : text;
  }

  private void appendBufferSeparator() {
    if (limitReached || headingLevel > 0) return;
    StringBuilder buffer = inCell ? cellBuffer : inListItem ? itemBuffer : null;
    if (buffer == null) return;
    boundaryBuffer = buffer;
    if (buffer.isEmpty() || Character.isWhitespace(buffer.charAt(buffer.length() - 1))) return;
    if (totalChars < maxContentLength) {
      // A space preserves paragraph boundaries even when PDF physical lines join hyphens.
      buffer.append(' ');
      totalChars++;
    }
    if (totalChars >= maxContentLength) limitReached = true;
  }

  /**
   * Returns true if the parser stopped processing SAX events because {@code totalChars} reached
   * {@code maxContentLength}. Authoritative truncation signal — cheaper and more reliable than a
   * post-extraction {@code result.length() > cap} comparison, which is structurally unable to fire
   * when chunk boundaries align exactly with the cap.
   */
  public boolean isLimitReached() {
    return limitReached;
  }

  /**
   * Returns the constructed {@link StructuredDocument}.
   *
   * <p>Call this after parsing is complete.
   */
  public StructuredDocument getDocument() {
    // Finalize unfinished structures as well as body text; clear each buffer after emitting it.
    if (headingBuffer != null) {
      String text = finishText(headingBuffer);
      if (!text.isEmpty()) elements.add(new Heading(headingLevel, text, currentPageIndex));
      headingBuffer = null;
      headingLevel = 0;
    }
    if (inTable) {
      flushTextBufferFinal();
      if (inCell && cellBuffer != null && currentRow != null) {
        currentRow.add(finishText(cellBuffer));
      }
      if (currentRow != null && !currentRow.isEmpty()) tableRows.add(currentRow);
      if (!tableRows.isEmpty()) elements.add(new Table(List.copyOf(tableRows), currentPageIndex, limitReached));
      inTable = false;
      tableNestingDepth = 0;
      tableRows = null;
      currentRow = null;
      cellBuffer = null;
      inCell = false;
    }
    if (inList) {
      if (itemBuffer != null && !itemBuffer.toString().isBlank()) {
        listItems.add(finishText(itemBuffer));
      }
      if (!listItems.isEmpty()) elements.add(new ListBlock(List.copyOf(listItems), orderedList, currentPageIndex));
      inList = false;
      listNestingDepth = 0;
      listItems = null;
      itemBuffer = null;
      inListItem = false;
    }
    flushTextBufferFinal();
    int pages = hasPageWrappers ? currentPageIndex + 1 : 0;
    return new StructuredDocument(List.copyOf(elements), pages);
  }

  private void flushTextBuffer() {
    if (textBuffer.isEmpty()) return;
    String text = finishText(textBuffer);
    textBuffer.setLength(0);
    if (!text.isEmpty()) {
      addElement(new Paragraph(text, currentPageIndex));
    }
  }

  /** Like {@link #flushTextBuffer()} but bypasses the limit check (used in getDocument). */
  private void flushTextBufferFinal() {
    if (textBuffer.isEmpty()) return;
    String text = finishText(textBuffer);
    textBuffer.setLength(0);
    if (!text.isEmpty()) {
      elements.add(new Paragraph(text, currentPageIndex));
    }
  }

  private void addElement(Element el) {
    if (!limitReached) {
      elements.add(el);
    }
  }

}
