/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class AsciiFirstUtf8CaptureTest {
  @TempDir Path tempDir;

  static Stream<Arguments> windowEdges() {
    // Explicit zero-based byte offsets: the first multi-byte sequence crosses the 16 KiB window.
    return Stream.of(Arguments.of(16_383, "é"), Arguments.of(16_382, "中"),
        Arguments.of(16_383, "中"), Arguments.of(16_381, "😀"),
        Arguments.of(16_382, "😀"), Arguments.of(16_383, "😀"), Arguments.of(16_384, "א"));
  }

  @ParameterizedTest(name = "firstSequenceAcrossWindowEdgeSelectsUtf8(offset {0}, {1})")
  @MethodSource("windowEdges")
  void firstSequenceAcrossWindowEdgeSelectsUtf8(int offset, String character) throws Exception {
    assertCapturedUtf8(offset, character, 8192);
  }

  static Stream<Arguments> captureEdges() {
    return Stream.of(Arguments.of(24_575, "😀", 8192), Arguments.of(65_536, "é中😀", 3),
        Arguments.of(1_048_575, "😀", 8192));
  }

  @ParameterizedTest(name = "captureCarriesSequencesAcrossReadsAndSpill(offset {0}, {1}, read {2})")
  @MethodSource("captureEdges")
  void captureCarriesSequencesAcrossReadsAndSpill(int offset, String character, int readSize) throws Exception {
    assertCapturedUtf8(offset, character, readSize);
  }

  @ParameterizedTest(name = "snapshotAcceptsUtf8ScalarBoundaries({arguments})")
  @ValueSource(strings = {"\u0080", "\u07ff", "\u0800", "\ud7ff", "\ue000", "\uffff", "\ud800\udc00", "\udbff\udfff"})
  void snapshotAcceptsUtf8ScalarBoundaries(String character) throws Exception {
    assertCapturedUtf8(65_536, character, 8192);
  }

  private void assertCapturedUtf8(int offset, String character, int readSize) throws Exception {
    byte[] bytes = ("a".repeat(offset) + character + " ספרים").getBytes(StandardCharsets.UTF_8);
    Path file = Files.write(tempDir.resolve("capture.txt"), bytes);
    try (var factory = new PreparedExtractionInput.Factory() {
      @Override
      InputStream openSource(Path source) {
        return new FilterInputStream(new ByteArrayInputStream(bytes)) {
          @Override
          public int read(byte[] buffer, int start, int length) throws IOException {
            return super.read(buffer, start, Math.min(readSize, length));
          }
        };
      }
    }; var prepared = factory.prepare(file, TikaExtractionPolicy.defaults());
        var input = new java.io.BufferedInputStream(prepared.openStream())) {
      Files.delete(file);
      assertEquals(bytes.length > PreparedExtractionInput.MAX_IN_MEMORY_BYTES, prepared.isDiskBacked());
      Metadata metadata = prepared.metadata();
      // A sentinel fallback proves the owned step decides, independently of Tika statistics.
      var detector = new OwnedTextEncodingDetector(List.of((EncodingDetector) (stream, meta) -> null,
          (stream, meta) -> StandardCharsets.US_ASCII));
      assertEquals(StandardCharsets.UTF_8, detector.detect(input, metadata));
      assertEquals(OwnedTextEncodingDetector.class.getName() + "#detectUtf8",
          metadata.get(TikaCoreProperties.ENCODING_DETECTOR));
      assertArrayEquals(bytes, input.readAllBytes(), "detection must reset the snapshot stream");
    }
  }
}
