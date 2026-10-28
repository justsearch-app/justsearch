/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Utf8ValidatorTest {
  @ParameterizedTest(name = "streamingAnswersMatchJdkDecoder({0})")
  @ValueSource(strings = {"random-bytes", "random-scalars", "edge-sequences"})
  void streamingAnswersMatchJdkDecoder(String family) {
    var random = new Random(0x55544638L);
    List<byte[]> samples = switch (family) {
      case "random-bytes" -> randomBytes(random);
      case "random-scalars" -> randomScalars(random);
      case "edge-sequences" -> edgeSequences();
      default -> throw new IllegalArgumentException(family);
    };
    int sample = 0;
    for (byte[] bytes : samples) {
      for (int maximumRead : new int[] {1, 3, 8, 9, 8192}) {
        var validator = new PreparedExtractionInput.Utf8Validator();
        int offset = 0;
        byte[] buffer = new byte[maximumRead + Long.BYTES];
        validator.accept(new byte[] {(byte) 0xFF}, 0);
        while (offset < bytes.length) {
          int read = Math.min(bytes.length - offset, 1 + random.nextInt(maximumRead));
          Arrays.fill(buffer, read, read + Long.BYTES, (byte) 0xFF);
          System.arraycopy(bytes, offset, buffer, 0, read);
          validator.accept(buffer, read);
          offset += read;
        }
        String context = family + " sample " + sample + ", maximum read " + maximumRead;
        if (validator.windowDecides()) {
          assertTrue(firstScalarEndsInsideWindow(bytes), context);
          assertFalse(validator.isNonAsciiUtf8(), "the unused snapshot fact stays false: " + context);
        } else {
          assertEquals(jdkNonAsciiUtf8(bytes), validator.isNonAsciiUtf8(), context);
        }
      }
      sample++;
    }
  }

  private static List<byte[]> randomBytes(Random random) {
    var samples = new ArrayList<byte[]>();
    for (int i = 0; i < 300; i++) {
      byte[] bytes = new byte[random.nextInt(512)];
      random.nextBytes(bytes);
      samples.add(withAsciiPrefix(bytes, i % 3 == 0 ? 16_384 : random.nextInt(32)));
    }
    return samples;
  }

  private static List<byte[]> randomScalars(Random random) {
    var samples = new ArrayList<byte[]>();
    for (int i = 0; i < 300; i++) {
      var text = new StringBuilder();
      int count = random.nextInt(128);
      for (int j = 0; j < count; j++) {
        int scalar;
        do {
          scalar = random.nextBoolean() ? random.nextInt(128) : random.nextInt(0x110000);
        } while (scalar >= 0xD800 && scalar <= 0xDFFF);
        text.appendCodePoint(scalar);
      }
      int prefix = i % 2 == 0 ? 16_380 + random.nextInt(8) : random.nextInt(32);
      samples.add(withAsciiPrefix(text.toString().getBytes(StandardCharsets.UTF_8), prefix));
    }
    return samples;
  }

  private static List<byte[]> edgeSequences() {
    var samples = new ArrayList<byte[]>();
    var sequences = new ArrayList<byte[]>();
    for (int value = 0; value <= 0xFF; value++) sequences.add(new byte[] {(byte) value});
    for (int scalar : new int[] {0x80, 0x7FF, 0x800, 0xD7FF, 0xE000, 0xFFFF, 0x1F600, 0x10FFFF}) {
      byte[] sequence = Character.toString(scalar).getBytes(StandardCharsets.UTF_8);
      for (int length = 1; length <= sequence.length; length++) {
        sequences.add(Arrays.copyOf(sequence, length));
      }
    }
    for (int[] sequence : new int[][] {{0xC0, 0xAF}, {0xE0, 0x9F, 0xBF}, {0xED, 0xA0, 0x80},
        {0xF0, 0x8F, 0xBF, 0xBF}, {0xF4, 0x90, 0x80, 0x80}, {0xE2, 'a', 0xAC},
        {0xC2, 0xC2}, {0xF0, 0x90, 0x80, 'a'}, {0xF5, 0x80, 0x80, 0x80}}) {
      byte[] bytes = new byte[sequence.length];
      for (int i = 0; i < sequence.length; i++) bytes[i] = (byte) sequence[i];
      sequences.add(bytes);
    }
    samples.add(new byte[0]);
    for (byte[] sequence : sequences) {
      for (int prefix : new int[] {0, 7, 16_380, 16_381, 16_382, 16_383, 16_384, 16_385}) {
        byte[] bytes = withAsciiPrefix(sequence, prefix);
        samples.add(bytes);
        byte[] invalidTail = Arrays.copyOf(bytes, bytes.length + 1);
        invalidTail[bytes.length] = (byte) 0xFF;
        samples.add(invalidTail);
      }
    }
    return samples;
  }

  private static byte[] withAsciiPrefix(byte[] suffix, int prefix) {
    byte[] bytes = new byte[prefix + suffix.length];
    Arrays.fill(bytes, 0, prefix, (byte) 'a');
    System.arraycopy(suffix, 0, bytes, prefix, suffix.length);
    return bytes;
  }

  private static boolean jdkNonAsciiUtf8(byte[] bytes) {
    try {
      var text = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
      return text.chars().anyMatch(character -> character >= 0x80);
    } catch (CharacterCodingException expected) {
      return false;
    }
  }

  private static boolean firstScalarEndsInsideWindow(byte[] bytes) {
    int first = 0;
    while (first < bytes.length && bytes[first] >= 0) first++;
    // The JDK validates the first non-ASCII scalar independently of any later invalid bytes.
    for (int length = 2; length <= 4 && first + length <= bytes.length
        && first + length <= OwnedTextEncodingDetector.UTF8_WINDOW_BYTES; length++) {
      if (jdkNonAsciiUtf8(Arrays.copyOfRange(bytes, first, first + length))) return true;
    }
    return false;
  }
}
