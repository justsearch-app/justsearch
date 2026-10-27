/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.apache.tika.detect.CompositeEncodingDetector;
import org.apache.tika.detect.EncodingDetector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;

/** Worker text decoding policy, composed from Tika's HTML and default detectors in that order. */
public final class OwnedTextEncodingDetector extends CompositeEncodingDetector {
  private static final long serialVersionUID = 1L;
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
}
