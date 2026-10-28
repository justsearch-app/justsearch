/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.tika.detect.DefaultEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8WindowEncodingDetectorTest {
  private static final int WINDOW = 16_384;
  // Original historical fixture was absent; this literal 222-byte prose also reproduces GB18030.
  private static final String REFERENCE_HEBREW =
      "זהו מסמך בעברית ללא סימן סדר בתים. היום אנחנו קוראים ספרים חדשים בספרייה בעיר. ילדים ומבוגרים מחפשים מילים ומגלים סיפורים.";
  private static final String SHORT_HEBREW = "מסמך בעברית ללא סימן סדר";
  private static final String HEBREW =
      "זה טקסט בעברית. היום אנחנו קוראים ספרים חדשים בספרייה העירונית.";
  private static final String UTF8_STEP = OwnedTextEncodingDetector.class.getName() + "#detectUtf8";

  @Test
  void universalDetectorReproducesHebrewGb18030Defect() throws Exception {
    for (String text : List.of(SHORT_HEBREW, HEBREW, REFERENCE_HEBREW)) {
      assertEquals(Charset.forName("GB18030"), new DefaultEncodingDetector().detect(
          new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), new Metadata()));
    }
    assertEquals(222, REFERENCE_HEBREW.getBytes(StandardCharsets.UTF_8).length);
  }

  @ParameterizedTest
  @ValueSource(strings = {"א", "€", "😀", "\u0080", "\u07ff", "\u0800", "\ud7ff", "\ue000", "\uffff", "\udbff\udfff"})
  void completeMultibyteCodePointSelectsUtf8(String text) throws Exception {
    assertUtf8(text.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void hebrewAtAllLengthsSelectsUtf8() throws Exception {
    for (String text : List.of(SHORT_HEBREW, REFERENCE_HEBREW, HEBREW,
        HEBREW.repeat(3), HEBREW.repeat(20), HEBREW.repeat(200))) {
      assertUtf8(text.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void continuingWindowMayCutAnyMultibyteSequence() throws Exception {
    for (String character : List.of("א", "€", "😀")) {
      int width = character.getBytes(StandardCharsets.UTF_8).length;
      for (int prefix = 1; prefix < width; prefix++) {
        String before = HEBREW + "a".repeat(WINDOW - HEBREW.getBytes(StandardCharsets.UTF_8).length - prefix);
        byte[] bytes = (before + character + HEBREW).getBytes(StandardCharsets.UTF_8);
        assertUtf8(bytes);
        assertDefault(Arrays.copyOf(bytes, WINDOW));
      }
      String complete = HEBREW + "a".repeat(WINDOW - HEBREW.getBytes(StandardCharsets.UTF_8).length - width)
          + character;
      assertEquals(WINDOW, complete.getBytes(StandardCharsets.UTF_8).length);
      assertUtf8(complete.getBytes(StandardCharsets.UTF_8));
    }
  }

  @Test
  void shortReadsKeepCompleteAndCutWindowResults() throws Exception {
    byte[] bytes = (HEBREW + "a".repeat(WINDOW - HEBREW.getBytes(StandardCharsets.UTF_8).length - 2)
        + "😀" + HEBREW).getBytes(StandardCharsets.UTF_8);
    try (var input = new BufferedInputStream(new FilterInputStream(new ByteArrayInputStream(bytes)) {
      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        return super.read(buffer, offset, Math.min(3, length));
      }
    }, 16)) {
      var metadata = new Metadata();
      assertEquals(StandardCharsets.UTF_8, detector().detect(input, metadata));
      assertEquals(UTF8_STEP, metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertArrayEquals(bytes, input.readAllBytes());
    }
  }

  @Test
  void asciiEmptyAndCutFirstMultibyteSequenceKeepDefault() throws Exception {
    assertDefault(new byte[0]);
    assertDefault("ASCII only".getBytes(StandardCharsets.UTF_8));
    assertDefault("a".repeat(WINDOW + 20).getBytes(StandardCharsets.UTF_8));
    for (String character : List.of("א", "€", "😀")) {
      int width = character.getBytes(StandardCharsets.UTF_8).length;
      for (int prefix = 1; prefix < width; prefix++) {
        byte[] bytes = ("a".repeat(WINDOW - prefix) + character + HEBREW).getBytes(StandardCharsets.UTF_8);
        assertDefault(bytes);
        assertDefault(Arrays.copyOf(bytes, WINDOW));
      }
    }
  }

  @Test
  void invalidUtf8RestoresStreamAndKeepsDefault() throws Exception {
    for (byte[] invalid : List.of(new byte[] {(byte) 0xFF}, new byte[] {(byte) 0xC0, (byte) 0xAF},
        new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80},
        new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80},
        new byte[] {(byte) 0x80}, new byte[] {(byte) 0xE2},
        new byte[] {(byte) 0xE2, (byte) 0x82}, new byte[] {(byte) 0xF0, (byte) 0x90, (byte) 0x80},
        new byte[] {(byte) 0xE2, 'a', (byte) 0xAC})) {
      byte[] valid = HEBREW.getBytes(StandardCharsets.UTF_8);
      byte[] bytes = Arrays.copyOf(valid, valid.length + invalid.length);
      System.arraycopy(invalid, 0, bytes, valid.length, invalid.length);
      assertDefault(bytes);
    }
  }

  @Test
  void malformedCutPrefixRejectsButLookaheadIsNotPartOfWindow() throws Exception {
    for (byte[] malformed : List.of(new byte[] {(byte) 0xC0}, new byte[] {(byte) 0xC1},
        new byte[] {(byte) 0xF5}, new byte[] {(byte) 0xF8}, new byte[] {(byte) 0xFF},
        new byte[] {(byte) 0xE0, (byte) 0x80},
        new byte[] {(byte) 0xED, (byte) 0xA0}, new byte[] {(byte) 0xF0, (byte) 0x80},
        new byte[] {(byte) 0xF4, (byte) 0x90})) {
      byte[] prefix = (HEBREW + "a".repeat(WINDOW - HEBREW.getBytes(StandardCharsets.UTF_8).length
          - malformed.length)).getBytes(StandardCharsets.UTF_8);
      byte[] bytes = Arrays.copyOf(prefix, WINDOW + 2);
      System.arraycopy(malformed, 0, bytes, prefix.length, malformed.length);
      bytes[WINDOW] = (byte) 0x80;
      bytes[WINDOW + 1] = (byte) 0x80;
      assertDefault(bytes);
    }
    byte[] bytes = (HEBREW + "a".repeat(WINDOW - HEBREW.getBytes(StandardCharsets.UTF_8).length))
        .getBytes(StandardCharsets.UTF_8);
    bytes = Arrays.copyOf(bytes, WINDOW + 1);
    bytes[WINDOW] = (byte) 0xFF;
    assertUtf8(bytes);
  }

  @Test
  void utf8ReadProblemRestoresStreamAndKeepsDefault() throws Exception {
    byte[] bytes = HEBREW.repeat(200).getBytes(StandardCharsets.UTF_8);
    try (var input = new FilterInputStream(new ByteArrayInputStream(bytes)) {
      private int marks;
      private boolean failed;
      @Override
      public void mark(int limit) {
        marks++;
        super.mark(limit);
      }
      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        if (marks == 2 && !failed) {
          failed = true;
          in.read();
          throw new IOException("UTF-8 window read failure");
        }
        return super.read(buffer, offset, length);
      }
    }) {
      var metadata = new Metadata();
      assertEquals(StandardCharsets.US_ASCII, sentinelDetector().detect(input, metadata));
      assertArrayEquals(bytes, input.readAllBytes());
    }
  }

  @Test
  void declarationsAndBomlessUtf16KeepPrecedenceAndAttribution() throws Exception {
    byte[] html = ("<html><head><meta charset=windows-1252></head><body>" + HEBREW
        + "</body></html>").getBytes(StandardCharsets.UTF_8);
    var declared = new Metadata();
    declared.set(Metadata.CONTENT_TYPE, "text/html");
    assertEquals(Charset.forName("windows-1252"), detector().detect(new ByteArrayInputStream(html), declared));
    assertEquals("org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector", declared.get(TikaCoreProperties.ENCODING_DETECTOR));
    var supported = new Metadata();
    supported.set(Metadata.CONTENT_TYPE, "text/html; charset=windows-1255");
    assertEquals(Charset.forName("windows-1255"), detector().detect(new ByteArrayInputStream(html), supported));
    for (Charset charset : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
      var metadata = new Metadata();
      assertEquals(charset, detector().detect(new ByteArrayInputStream(
          ("ASCII prefix " + HEBREW).getBytes(charset)), metadata));
      assertEquals(OwnedTextEncodingDetector.class.getName(), metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
    }
    for (Charset charset : List.of(StandardCharsets.UTF_8, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
      byte[] bom = ("\uFEFF" + HEBREW).getBytes(charset);
      var metadata = new Metadata();
      metadata.set(Metadata.CONTENT_TYPE, "text/html; charset=windows-1252");
      assertEquals(charset, detector().detect(new ByteArrayInputStream(bom), metadata));
      assertEquals("org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector", metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertDefault(bom);
    }
  }

  @Test
  void nonHtmlMetadataDoesNotChangeTodaysChain() throws Exception {
    var metadata = new Metadata();
    metadata.set(Metadata.CONTENT_TYPE, "text/plain; charset=windows-1252");
    assertEquals(StandardCharsets.UTF_8, detector().detect(new ByteArrayInputStream(
        "Writing café € 😀".getBytes(StandardCharsets.UTF_8)), metadata));
  }

  private static void assertUtf8(byte[] bytes) throws Exception {
    try (var input = new BufferedInputStream(new ByteArrayInputStream(bytes), 16)) {
      var metadata = new Metadata();
      assertEquals(StandardCharsets.UTF_8, detector().detect(input, metadata));
      assertEquals("UTF-8", metadata.get(TikaCoreProperties.DETECTED_ENCODING));
      assertEquals(UTF8_STEP, metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertArrayEquals(bytes, input.readAllBytes());
    }
  }

  private static void assertDefault(byte[] bytes) throws Exception {
    try (var input = new BufferedInputStream(new ByteArrayInputStream(bytes), 16)) {
      var metadata = new Metadata();
      assertEquals(StandardCharsets.US_ASCII, sentinelDetector().detect(input, metadata));
      assertNotEquals(UTF8_STEP, metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertArrayEquals(bytes, input.readAllBytes());
    }
  }

  private static OwnedTextEncodingDetector sentinelDetector() {
    return new OwnedTextEncodingDetector(List.of((EncodingDetector) (input, metadata) -> null,
        (input, metadata) -> StandardCharsets.US_ASCII));
  }

  private static OwnedTextEncodingDetector detector() throws ReflectiveOperationException {
    return new OwnedTextEncodingDetector(List.of((EncodingDetector) Class.forName(
        "org.apache.tika.parser.html.charsetdetector.StandardHtmlEncodingDetector").getConstructor().newInstance(), new DefaultEncodingDetector()));
  }
}
