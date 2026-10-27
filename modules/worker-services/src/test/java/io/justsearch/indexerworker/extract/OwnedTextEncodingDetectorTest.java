/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.Test;

class OwnedTextEncodingDetectorTest {
  @Test
  void acceptedByteOrdersSetMetadataAndRestoreStream() throws Exception {
    for (Charset encoding : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
      byte[] bytes = "A readable sentence in either byte order.".getBytes(encoding);
      var input = new ByteArrayInputStream(bytes);
      var metadata = new Metadata();
      assertEquals(encoding, detector().detect(input, metadata));
      assertEquals(encoding.name(), metadata.get(TikaCoreProperties.DETECTED_ENCODING));
      assertEquals(OwnedTextEncodingDetector.class.getName(), metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertArrayEquals(bytes, input.readAllBytes());
    }
  }

  @Test
  void oddFinalByteIsIgnored() throws Exception {
    byte[] bytes = "A readable sentence.".getBytes(StandardCharsets.UTF_16LE);
    assertEquals(StandardCharsets.UTF_16LE, detect(Arrays.copyOf(bytes, bytes.length + 1)));
  }

  @Test
  void nulEvidenceThresholdsAreInclusive() throws Exception {
    assertEquals(StandardCharsets.UTF_16LE, detect(("abcd" + "Ж".repeat(60)).getBytes(StandardCharsets.UTF_16LE)));
    assertEquals(StandardCharsets.UTF_16LE, detect("abcdefghĀ".getBytes(StandardCharsets.UTF_16LE)));
  }

  @Test
  void sparseNulsDoNotOverrideDefault() throws Exception {
    assertUsesDefault("abc".getBytes(StandardCharsets.UTF_16LE));
    assertUsesDefault(("abcd" + "Ж".repeat(61)).getBytes(StandardCharsets.UTF_16LE));
  }

  @Test
  void oppositeLaneNulsDoNotOverrideDefault() throws Exception {
    assertUsesDefault("abcdefghĀĀ".getBytes(StandardCharsets.UTF_16LE));
  }

  @Test
  void nulUnitsDoNotOverrideDefault() throws Exception {
    assertUsesDefault("A readable sentence with a NUL\0 inside.".getBytes(StandardCharsets.UTF_16LE));
  }

  @Test
  void disallowedC0ControlsDoNotOverrideDefault() throws Exception {
    for (char control = 1; control < 32; control++) {
      if (control != '\t' && control != '\n' && control != '\f' && control != '\r') {
        assertUsesDefault(("A readable sentence " + control + " with a control.").getBytes(StandardCharsets.UTF_16LE));
      }
    }
  }

  @Test
  void allowedWhitespaceKeepsUtf16() throws Exception {
    assertEquals(StandardCharsets.UTF_16LE,
        detect("A sentence\twith\nallowed\fwhitespace\rcharacters.".getBytes(StandardCharsets.UTF_16LE)));
  }

  @Test
  void malformedSurrogatesDoNotOverrideDefault() throws Exception {
    byte[] prefix = "A readable sentence.".getBytes(StandardCharsets.UTF_16LE);
    assertUsesDefault(appendUnit(prefix, 0xD800));
    assertUsesDefault(appendUnit(prefix, 0xDC00));
    assertUsesDefault(appendUnit(appendUnit(prefix, 0xD800), 'x'));
    assertUsesDefault(appendUnit(appendUnit(prefix, 0xDC00), 0xD800));
    byte[] fullWindow = "a".repeat(4096).getBytes(StandardCharsets.UTF_16LE);
    fullWindow[8188] = 0;
    fullWindow[8189] = (byte) 0xD8;
    assertUsesDefault(fullWindow);
  }

  @Test
  void fullWindowCanEndWithHighSurrogate() throws Exception {
    byte[] bytes = appendUnit("a".repeat(4095).getBytes(StandardCharsets.UTF_16LE), 0xD800);
    assertEquals(8192, bytes.length);
    assertEquals(StandardCharsets.UTF_16LE, detect(bytes));
  }

  @Test
  void byteOrderMarksStayWithDefaultDetectors() throws Exception {
    assertUsesDefault(("\uFEFF" + "A readable sentence.").getBytes(StandardCharsets.UTF_16LE));
    assertUsesDefault(("\uFEFF" + "A readable sentence.").getBytes(StandardCharsets.UTF_16BE));
    assertUsesDefault(("\uFEFF" + "A readable sentence.").getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void unsupportedMarkSkipsRuleWithoutReading() throws Exception {
    try (var input = new FilterInputStream(new ByteArrayInputStream(new byte[0])) {
      @Override
      public boolean markSupported() {
        return false;
      }

      @Override
      public int read(byte[] bytes, int offset, int length) {
        throw new AssertionError("non-markable stream must not be read");
      }
    }) {
      assertEquals(StandardCharsets.UTF_8, detector().detect(input, new Metadata()));
    }
  }

  @Test
  void readFailureStillResetsStream() throws Exception {
    byte[] bytes = "A readable sentence.".getBytes(StandardCharsets.UTF_16LE);
    try (var input = new FilterInputStream(new ByteArrayInputStream(bytes)) {
      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        in.read();
        throw new IOException("read failure after consuming one byte");
      }
    }) {
      IOException failure = assertThrows(IOException.class, () -> detector().detect(input, new Metadata()));
      assertEquals("read failure after consuming one byte", failure.getMessage());
      assertEquals(bytes[0], input.read());
    }
  }

  private static byte[] appendUnit(byte[] bytes, int unit) {
    byte[] result = Arrays.copyOf(bytes, bytes.length + 2);
    result[bytes.length] = (byte) unit;
    result[bytes.length + 1] = (byte) (unit >>> 8);
    return result;
  }

  private static void assertUsesDefault(byte[] bytes) throws Exception {
    var input = new ByteArrayInputStream(bytes);
    assertEquals(StandardCharsets.UTF_8, detector().detect(input, new Metadata()));
    assertArrayEquals(bytes, input.readAllBytes());
  }

  private static Charset detect(byte[] bytes) throws IOException {
    return detector().detect(new ByteArrayInputStream(bytes), new Metadata());
  }

  private static OwnedTextEncodingDetector detector() {
    return new OwnedTextEncodingDetector(List.of(
        (InputStream input, Metadata metadata) -> null,
        (InputStream input, Metadata metadata) -> StandardCharsets.UTF_8));
  }
}
