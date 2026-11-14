/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.ParserDecorator;
import org.apache.tika.sax.ContentHandlerDecorator;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

/** Keeps visible-text corrections scoped to the parser producing the text, including embeds. */
final class OfficeContentParser extends ParserDecorator {
  OfficeContentParser(Parser parser) {
    super(parser);
  }

  private record Source(Metadata metadata) {}

  static boolean isPackage(ParseContext context) {
    Source source = context.get(Source.class);
    if (source == null) return false;
    String type = source.metadata().get(Metadata.CONTENT_TYPE);
    return type != null && (type.contains("officedocument")
        || type.startsWith("application/vnd.oasis.opendocument.")
        || type.startsWith("application/vnd.ms-word.")
        || type.startsWith("application/vnd.ms-excel.")
        || type.startsWith("application/vnd.ms-powerpoint."));
  }

  @Override
  public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
      throws IOException, SAXException, TikaException {
    Source previous = context.get(Source.class);
    Source source = new Source(metadata);
    context.set(Source.class, source);
    String type = metadata.get(Metadata.CONTENT_TYPE);
    boolean word = type != null && (type.equals("application/msword")
        || type.startsWith("application/vnd.ms-word.")
        || type.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml."));
    try {
      if (word) {
        var visible = new NoteReferences(handler, context, source);
        super.parse(stream, visible, metadata, context);
        visible.flush();
      } else {
        super.parse(stream, handler, metadata, context);
      }
    } finally {
      context.set(Source.class, previous);
    }
  }

  /** POI markers can span character callbacks, but never structural element boundaries. */
  private static final class NoteReferences extends ContentHandlerDecorator {
    private static final String FOOTNOTE = "[footnoteRef:";
    private static final String ENDNOTE = "[endnoteRef:";
    private final ParseContext context;
    private final Source source;
    private final StringBuilder pending = new StringBuilder();
    private boolean number;
    private boolean digit;

    NoteReferences(ContentHandler handler, ParseContext context, Source source) {
      super(handler);
      this.context = context;
      this.source = source;
    }

    @Override
    public void characters(char[] chars, int start, int length) throws SAXException {
      if (length == 0) {
        super.characters(chars, start, length);
        return;
      }
      // Nested parsers own their text: literal markers in an attached text/HTML file stay.
      if (context.get(Source.class) != source) {
        flush();
        super.characters(chars, start, length);
        return;
      }
      StringBuilder visible = new StringBuilder(length);
      for (int i = start; i < start + length; i++) {
        char ch = chars[i];
        if (number) {
          if (ch >= '0' && ch <= '9') {
            pending.append(ch);
            digit = true;
            continue;
          }
          if (ch == '-' && !digit && pending.charAt(pending.length() - 1) == ':') {
            pending.append(ch);
            continue;
          }
          if (ch == ']' && digit) {
            // The reference separates words even when POI supplies no surrounding spaces.
            visible.append(' ');
            reset();
            continue;
          }
          visible.append(pending);
          reset();
        }
        if (pending.isEmpty()) {
          if (ch == '[') pending.append(ch);
          else visible.append(ch);
        } else {
          pending.append(ch);
          String candidate = pending.toString();
          if (FOOTNOTE.equals(candidate) || ENDNOTE.equals(candidate)) {
            number = true;
          } else if (!FOOTNOTE.startsWith(candidate) && !ENDNOTE.startsWith(candidate)) {
            if (ch == '[') {
              visible.append(pending, 0, pending.length() - 1);
              reset();
              pending.append(ch);
            } else {
              visible.append(pending);
              reset();
            }
          }
        }
      }
      emit(visible.toString());
    }

    private void reset() {
      pending.setLength(0);
      number = false;
      digit = false;
    }

    private void emit(String text) throws SAXException {
      if (!text.isEmpty()) super.characters(text.toCharArray(), 0, text.length());
    }

    void flush() throws SAXException {
      String text = pending.toString();
      reset();
      emit(text);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes attributes)
        throws SAXException {
      flush();
      super.startElement(uri, localName, qName, attributes);
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
      flush();
      super.endElement(uri, localName, qName);
    }

    @Override
    public void ignorableWhitespace(char[] chars, int start, int length) throws SAXException {
      flush();
      super.ignorableWhitespace(chars, start, length);
    }
  }
}
