/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.csv.SurrogateSafeTextAndCSVParser;
import org.apache.tika.parser.csv.TextAndCSVConfig;
import org.apache.tika.parser.csv.TextAndCSVParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.XHTMLContentHandler;
import org.junit.jupiter.api.Test;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

class OwnedTextParserDifferentialTest {
  @Test
  void saxAndMetadataMatchTikaForFixedEncodingAndTextCorpus() throws Exception {
    for (String encoding : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "US-ASCII", "windows-1252",
        "Shift_JIS", "GB18030", "EUC-KR", "KOI8-R")) {
      String text = switch (encoding) {
        case "windows-1252" -> "European € „quotes“ café déjà vu. ".repeat(40);
        case "Shift_JIS" -> "日本語の文章です。東京の図書館で新しい本を読みます。".repeat(40);
        case "GB18030" -> "这是一个中文文本，今天我们在北京的图书馆阅读新的书籍。".repeat(40);
        case "EUC-KR" -> "한국어 문장입니다. 오늘 우리는 도서관에서 새로운 책을 읽습니다.".repeat(40);
        case "KOI8-R" -> "Это русский текст. Сегодня мы читаем новые книги в библиотеке. ".repeat(40);
        default -> "Ordinary ASCII writing with several lines.\n".repeat(200);
      };
      assertDifferential(text.getBytes(Charset.forName(encoding)), "text/plain", null, new ParseContext());
      if (encoding.startsWith("UTF-")) {
        assertDifferential(("\uFEFF" + text).getBytes(Charset.forName(encoding)), "text/plain", null,
            new ParseContext());
      }
    }
    for (String type : List.of("text/markdown", "application/json", "text/x-python", "application/javascript")) {
      assertDifferential("# Heading\nOrdinary café 😀 text.\n".getBytes(StandardCharsets.UTF_8), type, null,
          new ParseContext());
    }
    assertDifferential("Override € and café".getBytes(Charset.forName("windows-1252")), "text/plain",
        "text/plain; charset=windows-1252", new ParseContext());
    assertDifferential("BOM wins: café 😀".getBytes(StandardCharsets.UTF_16), "text/plain",
        "text/plain; charset=windows-1252", new ParseContext());
    assertDifferential(new byte[0], "text/plain", null, new ParseContext());
  }

  @Test
  void csvAndTsvSniffingMetadataAndCellsMatchTika() throws Exception {
    for (String csv : List.of("name,value\nfirst,one\nsecond,two\n",
        "name\tvalue\nfirst\tone\nsecond\ttwo\n", "name|value\nfirst|one\nsecond|two\n",
        "name;value\nfirst;one\nsecond;two\n", "name,value\n\"quoted, comma\",\"two\nlines\"\n",
        "a,b\n\n,,\nshort\nlong,row,extra\n", "a,b\n\"bad quote,remaining\n",
        "a,b\n\"quoted\" stray,b\n", "name,value\n😀,𠀀\n𐐀,last\n")) {
      for (String type : List.of("text/plain", "text/csv", "text/tab-separated-values")) {
        assertDifferential(csv.getBytes(StandardCharsets.UTF_8), type, null, new ParseContext());
      }
    }
    var context = new ParseContext();
    var config = new TextAndCSVConfig();
    config.setNameToDelimiterMap(Map.of("caret", '^'));
    context.set(TextAndCSVConfig.class, config);
    byte[] custom = "name^value\nfirst^one\nsecond^two\n".getBytes(StandardCharsets.UTF_8);
    assertDifferential(custom, "text/plain", null, context);
    assertDifferential(custom, "text/csv", "text/csv; charset=UTF-8; delimiter=caret", context);
    assertDifferential(custom, "text/csv", "text/csv; charset=UTF-8; delimiter=^", context);
    // Force CSV parsing beyond the sniff window, including Tika's recovery/error path.
    String malformed = "a,b\n" + "first,second\n".repeat(2000) + "\"unterminated quote";
    assertDifferential(malformed.getBytes(StandardCharsets.UTF_8), "text/csv",
        "text/csv; charset=UTF-8; delimiter=comma", new ParseContext());
    assertDifferential("é,€\nà,£\n".getBytes(Charset.forName("windows-1252")), "text/csv",
        "text/csv; charset=windows-1252; delimiter=comma", new ParseContext());
  }

  @Test
  void malformedUtf16AndUtf8AndControlsMatchTika() throws Exception {
    for (String text : List.of("x".repeat(4095) + "\uD83Dqending",
        "x".repeat(4096) + "\uDE00ending", "x".repeat(4095) + "\uD83D")) {
      for (ByteOrder order : List.of(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN)) {
        var bytes = ByteBuffer.allocate(text.length() * 2 + 2).order(order).putChar('\uFEFF');
        for (char ch : text.toCharArray()) bytes.putChar(ch);
        assertDifferential(bytes.array(), "text/plain", null, new ParseContext());
      }
    }
    byte[] invalidUtf8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', (byte) 0xC3, 'b', (byte) 0xFF, 'c'};
    assertDifferential(invalidUtf8, "text/plain", null, new ParseContext());
    assertDifferential("a\0b\u0001c\u000Bd\t\r\ne\fending".getBytes(StandardCharsets.UTF_8),
        "text/plain", "text/plain; charset=UTF-8", new ParseContext());
  }

  @Test
  void otherParserFamiliesKeepSaxAndMetadata() throws Exception {
    String config = """
        <properties><encodingDetectors><encodingDetector class="io.justsearch.indexerworker.extract.OwnedTextEncodingDetector">
        <encodingDetector class="org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector"/>
        <encodingDetector class="org.apache.tika.detect.DefaultEncodingDetector"/>
        </encodingDetector></encodingDetectors><parsers><parser class="org.apache.tika.parser.DefaultParser">
        <parser-exclude class="org.apache.tika.parser.ocr.TesseractOCRParser"/>
        </parser></parsers></properties>
        """;
    var original = new org.apache.tika.parser.AutoDetectParser(TextNameMagicConflictDetector.wrapDefault(),
        new org.apache.tika.config.TikaConfig(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8))).getParser());
    var owned = OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault());
    for (String type : List.of("text/html", "application/xml", "text/x-java-source", "text/x-c++src", "text/x-groovy")) {
      String text = switch (type) {
        case "text/html" -> "<html><head><meta charset=UTF-8></head><body><p>HTML café 😀</p></body></html>";
        case "application/xml" -> "<?xml version=\"1.0\" encoding=\"UTF-8\"?><document>XML café 😀</document>";
        default -> "// source café 😀\nclass Example { }\n";
      };
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      assertEquals(capture(original, bytes, type, type, new ParseContext()),
          capture(owned, bytes, type, type, new ParseContext()), type);
    }
    for (String fixture : List.of("/fixtures/pdf/pdf-text-layer.pdf", "/fixtures/office/office-marker.docx")) {
      try (var input = OwnedTextParserDifferentialTest.class.getResourceAsStream(fixture)) {
        assertNotNull(input, fixture);
        byte[] bytes = input.readAllBytes();
        String type = fixture.endsWith(".pdf") ? "application/pdf"
            : "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        assertEquals(capture(original, bytes, type, type, new ParseContext()),
            capture(owned, bytes, type, type, new ParseContext()), fixture);
      }
    }
  }

  @Test
  void arbitraryShortReaderCallsKeepPairsTogether() throws Exception {
    String expected = "x".repeat(4095) + "😀𠀀𐐀ending😀";
    for (int readSize : List.of(1, 2, 3, 7, 4095, 4096)) {
      Reader reader = new StringReader(expected) {
        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
          return super.read(buffer, offset, Math.min(length, readSize));
        }
      };
      var output = new BodyContentHandler(-1);
      var xhtml = new XHTMLContentHandler(output, new Metadata());
      xhtml.startDocument();
      Method method = SurrogateSafeTextAndCSVParser.class.getDeclaredMethod(
          "handleText", Reader.class, XHTMLContentHandler.class);
      method.setAccessible(true);
      method.invoke(null, reader, xhtml);
      xhtml.endDocument();
      assertEquals(expected, output.toString().strip(), "reader chunk=" + readSize);
    }
  }

  @Test
  void malformedReaderSurrogatesKeepTikaReplacementBehaviour() throws Exception {
    // Also exercises the EOF carry branch, independently of byte decoders replacing bad units.
    for (String input : List.of("x".repeat(4095) + "\uD83Dq", "x".repeat(4096) + "\uDE00q",
        "x".repeat(4095) + "\uD83D")) {
      var output = new BodyContentHandler(-1);
      var xhtml = new XHTMLContentHandler(output, new Metadata());
      xhtml.startDocument();
      Method method = SurrogateSafeTextAndCSVParser.class.getDeclaredMethod(
          "handleText", Reader.class, XHTMLContentHandler.class);
      method.setAccessible(true);
      method.invoke(null, new StringReader(input), xhtml);
      xhtml.endDocument();
      assertEquals(input.replace('\uD83D', '\uFFFD').replace('\uDE00', '\uFFFD'), output.toString().strip());
    }
  }

  @Test
  void supportedTypesAndParserIdentityFollowOwnedRouting() throws Exception {
    var owned = new SurrogateSafeTextAndCSVParser();
    assertEquals(new TextAndCSVParser().getSupportedTypes(new ParseContext()),
        owned.getSupportedTypes(new ParseContext()));
    var metadata = new Metadata();
    metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "identity.txt");
    OwnedTikaParsers.create(TextNameMagicConflictDetector.wrapDefault()).parse(
        new ByteArrayInputStream("Ordinary writing".getBytes(StandardCharsets.UTF_8)),
        new BodyContentHandler(-1), metadata, new ParseContext());
    assertTrue(Arrays.asList(metadata.getValues(TikaCoreProperties.TIKA_PARSED_BY))
        .contains(SurrogateSafeTextAndCSVParser.class.getName()), metadata.toString());
  }

  private static void assertDifferential(byte[] bytes, String type, String override, ParseContext context)
      throws Exception {
    var detector = new OwnedTextEncodingDetector(List.of((stream, metadata) -> null, new DefaultEncodingDetector()));
    var expected = capture(new TextAndCSVParser(detector), bytes, type, override, context);
    var actual = capture(new SurrogateSafeTextAndCSVParser(detector), bytes, type, override, context);
    assertEquals(expected, actual, type + " override=" + override);
  }

  private static Capture capture(Parser parser, byte[] bytes, String type, String override, ParseContext context)
      throws Exception {
    var metadata = new Metadata();
    metadata.set(Metadata.CONTENT_TYPE, type);
    if (override != null) metadata.set(TikaCoreProperties.CONTENT_TYPE_USER_OVERRIDE, override);
    var events = new ArrayList<String>();
    var handler = new DefaultHandler() {
      private boolean identityMeta;
      private boolean identityWhitespace;
      @Override public void startDocument() { events.add("startDocument"); }
      @Override public void endDocument() { events.add("endDocument"); }
      @Override public void startPrefixMapping(String prefix, String uri) { events.add("prefix:" + prefix + ":" + uri); }
      @Override public void endPrefixMapping(String prefix) { events.add("endPrefix:" + prefix); }
      @Override public void startElement(String uri, String local, String name, Attributes attrs) {
        // XHTML repeats parser-identity metadata in <head>; the outer CompositeParser adds one.
        if (local.equals("meta") && (TikaCoreProperties.TIKA_PARSED_BY.getName().equals(attrs.getValue("name"))
            || TikaCoreProperties.TIKA_PARSED_BY_FULL_SET.getName().equals(attrs.getValue("name")))) {
          identityMeta = true;
          return;
        }
        var attributes = new TreeMap<String, String>();
        for (int i = 0; i < attrs.getLength(); i++) attributes.put(attrs.getQName(i), attrs.getValue(i));
        events.add("start:" + uri + ":" + local + ":" + name + ":" + attributes);
      }
      @Override public void endElement(String uri, String local, String name) {
        if (identityMeta) {
          identityMeta = false;
          identityWhitespace = true;
          return;
        }
        events.add("end:" + uri + ":" + local + ":" + name);
      }
      @Override public void characters(char[] chars, int start, int length) {
        events.add("text:" + new String(chars, start, length));
      }
      @Override public void ignorableWhitespace(char[] chars, int start, int length) {
        if (identityWhitespace) {
          identityWhitespace = false;
          return;
        }
        events.add("space:" + new String(chars, start, length));
      }
      @Override public void processingInstruction(String target, String data) { events.add("pi:" + target + ":" + data); }
    };
    String failure = null;
    try {
      parser.parse(new ByteArrayInputStream(bytes), handler, metadata, context);
    } catch (org.apache.tika.exception.TikaException | java.io.IOException | org.xml.sax.SAXException e) {
      failure = e.getClass().getName() + ":" + e.getMessage();
    }
    var values = new TreeMap<String, List<String>>();
    for (String key : metadata.names()) {
      if (!key.equals(TikaCoreProperties.TIKA_PARSED_BY.getName())
          && !key.equals(TikaCoreProperties.TIKA_PARSED_BY_FULL_SET.getName())) {
        values.put(key, List.of(metadata.getValues(key)));
      }
    }
    return new Capture(events, values, failure);
  }

  private record Capture(List<String> events, Map<String, List<String>> metadata, String failure) {}
}
