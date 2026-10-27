/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.apache.tika.detect.CompositeEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;

/** Worker text decoding policy: HTML declarations, BOM-less UTF-16 evidence, then Tika defaults. */
public final class OwnedTextEncodingDetector extends CompositeEncodingDetector {
  private static final long serialVersionUID = 1L;
  private static final int UTF16_WINDOW_BYTES = 8192;
  private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
  // The types handled by Tika's HTML parser; ordinary XML keeps its own declaration handling.
  private static final Set<String> HTML_TYPES = Set.of(
      "text/html", "application/xhtml+xml", "application/vnd.wap.xhtml+xml", "application/x-asp");

  /** Tika's configuration loader supplies the two configured child detectors. */
  public OwnedTextEncodingDetector(List<EncodingDetector> detectors) {
    super(detectors);
    if (detectors.size() != 2) {
      throw new IllegalArgumentException("Expected Tika HTML and default encoding detectors");
    }
  }

  @Override
  public Charset detect(InputStream input, Metadata metadata) throws IOException {
    Charset charset = null;
    MediaType type = MediaType.parse(metadata.get(Metadata.CONTENT_TYPE));
    if (type != null && HTML_TYPES.contains(type.getBaseType().toString())) {
      // Tika handles BOM > supported metadata/meta declaration; null means guessing is needed.
      charset = getDetectors().getFirst().detect(input, metadata);
      if (charset != null) {
        metadata.set(TikaCoreProperties.ENCODING_DETECTOR, getDetectors().getFirst().getClass().getName());
      }
    }
    if (charset == null) {
      charset = detectBomlessUtf16(input);
      if (charset != null) {
        metadata.set(TikaCoreProperties.ENCODING_DETECTOR, getClass().getName());
      }
    }
    if (charset == null) {
      charset = getDetectors().getLast().detect(input, metadata);
    }
    // Tika's universal detector can downgrade Windows-1252 to Latin-1. Printable Latin-1
    // is identical; its C1 controls would otherwise be replaced by the XHTML handler.
    if (StandardCharsets.ISO_8859_1.equals(charset)) {
      charset = WINDOWS_1252;
    }
    if (charset != null) {
      metadata.set(TikaCoreProperties.DETECTED_ENCODING, charset.name());
    }
    return charset;
  }

  private static Charset detectBomlessUtf16(InputStream input) throws IOException {
    if (input == null || !input.markSupported()) {
      return null;
    }
    byte[] window;
    input.mark(UTF16_WINDOW_BYTES);
    try {
      window = input.readNBytes(UTF16_WINDOW_BYTES);
    } finally {
      input.reset();
    }
    if (window.length >= 2
        && ((window[0] == (byte) 0xFF && window[1] == (byte) 0xFE)
            || (window[0] == (byte) 0xFE && window[1] == (byte) 0xFF))) {
      return null;
    }
    if (window.length >= 3 && window[0] == (byte) 0xEF
        && window[1] == (byte) 0xBB && window[2] == (byte) 0xBF) {
      return null;
    }
    int units = window.length / 2;
    int even = 0;
    int odd = 0;
    for (int offset = 0; offset < units * 2; offset += 2) {
      if (window[offset] == 0) even++;
      if (window[offset + 1] == 0) odd++;
    }
    Charset candidate;
    if (odd >= 4 && odd * 16 >= units && even * 8 <= odd) {
      candidate = StandardCharsets.UTF_16LE;
    } else if (even >= 4 && even * 16 >= units && odd * 8 <= even) {
      candidate = StandardCharsets.UTF_16BE;
    } else {
      return null;
    }
    var decoder = candidate.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    CharBuffer decoded = CharBuffer.allocate(units);
    // A full window may stop between a surrogate pair's units. With endOfInput=false the
    // decoder retains only that final high surrogate; all other malformed input still fails.
    if (decoder.decode(ByteBuffer.wrap(window, 0, units * 2), decoded,
        window.length < UTF16_WINDOW_BYTES).isError()) {
      return null;
    }
    decoded.flip();
    while (decoded.hasRemaining()) {
      char value = decoded.get();
      if (value < 0x20 && value != '\t' && value != '\n' && value != '\f' && value != '\r') {
        return null;
      }
    }
    return candidate;
  }
}
