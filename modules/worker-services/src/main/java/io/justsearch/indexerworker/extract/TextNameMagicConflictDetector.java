/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;

/**
 * Resolves the one detection conflict that silently indexes a text document with NO content: the
 * file's NAME says text, its BYTES are text, but its opening bytes collide with some binary
 * format's magic number, so Tika hands the file to a binary parser that emits nothing.
 *
 * <p><b>Why this exists (tempdoc 803 blocker, found 2026-08-14).</b> One document of 5,408 in
 * {@code mixed/miracl-fr-2k} indexed empty and failed embed + SPLADE + NER, which failed the
 * readiness gate and made the corpus incomparable — blocking the 803 re-baseline. The document is
 * ordinary French prose beginning <em>"P4 Jean Merieux"</em>. {@code P4} followed by whitespace is
 * the NetPBM binary-bitmap magic number. Tika's {@code MimeTypes.detect} prefers a magic hit over
 * the name hint unless the name type is a specialization of the magic type, and {@code text/plain}
 * is not a specialization of {@code image/x-portable-bitmap} — so a 272-byte French paragraph was
 * parsed as a bitmap. BM25 could not match a word of it; the document was in the index as a
 * content-less shell.
 *
 * <p><b>Why not the dropout fallback chain (tempdoc 790).</b> That chain's tiers are OCR and
 * VDU/VLM — image recovery. Feeding a fake bitmap to OCR recovers nothing and invites the VLM
 * confabulation 790 measured, and the AI tier is off entirely in eval-mode backends. The text was
 * never lost to rendering; it was lost to routing, so routing is where it is fixed.
 *
 * <p><b>The rule, and its deliberate narrowness.</b> Promotion to the name-based type happens only
 * when ALL of: the delegate's answer is non-textual; the name-based answer IS {@code text/*}; and
 * the leading bytes actually decode as text. Any one of those failing leaves the delegate's answer
 * untouched — so this can only fire where today's behaviour is already producing an empty
 * extraction, and a genuine binary carrying a {@code .txt} extension still detects as the binary
 * (its bytes do not decode).
 */
public final class TextNameMagicConflictDetector implements Detector {

  private static final long serialVersionUID = 1L;

  /**
   * Bytes inspected for the text check. Large enough that a binary payload's first non-text byte is
   * seen in practice, small enough to stay inside the mark buffer Tika's detection streams provide.
   */
  static final int TEXT_PROBE_BYTES = 8192;

  private final Detector delegate;

  public TextNameMagicConflictDetector(Detector delegate) {
    this.delegate = delegate;
  }

  /** The detector every extractor in this package should use. */
  public static Detector wrapDefault() {
    return new TextNameMagicConflictDetector(new DefaultDetector(MimeTypes.getDefaultMimeTypes()));
  }

  @Override
  public MediaType detect(InputStream input, Metadata metadata) throws IOException {
    MediaType detected = delegate.detect(input, metadata);
    if (detected == null || isTextual(detected)) {
      return detected;
    }
    MediaType byName = nameBasedType(metadata);
    if (byName == null || !isTextual(byName)) {
      return detected;
    }
    if (!leadingBytesDecodeAsText(input)) {
      return detected;
    }
    return byName;
  }

  private static boolean isTextual(MediaType type) {
    return "text".equals(type.getType());
  }

  /**
   * The type Tika would assign from the file name alone. Detection with a {@code null} stream is
   * MimeTypes' name-only path, so this never re-reads the content.
   */
  private static MediaType nameBasedType(Metadata metadata) throws IOException {
    String name = metadata == null ? null : metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
    if (name == null || name.isBlank()) {
      return null;
    }
    Metadata nameOnly = new Metadata();
    nameOnly.set(TikaCoreProperties.RESOURCE_NAME_KEY, name);
    return MimeTypes.getDefaultMimeTypes().detect(null, nameOnly);
  }

  /**
   * Whether the stream's leading bytes are text: strict-UTF-8 decodable with no NUL or stray C0
   * control characters. Strict decoding is what separates the two cases this detector exists to
   * tell apart — French prose starting {@code "P4 "} decodes; a real packed bitmap row does not.
   *
   * <p>The end-of-input flag is load-bearing and must track whether the probe actually reached the
   * end of the file. A dangling multi-byte prefix is legitimate when the probe cut a large document
   * mid-character, and is malformed input when it is the file's last byte — decoding a short read
   * with {@code endOfInput=false} would accept a real bitmap whose final row byte happens to be a
   * UTF-8 lead byte.
   */
  private static boolean leadingBytesDecodeAsText(InputStream input) throws IOException {
    if (input == null || !input.markSupported()) {
      return false;
    }
    input.mark(TEXT_PROBE_BYTES + 1);
    byte[] probe = new byte[TEXT_PROBE_BYTES];
    int read;
    try {
      read = input.readNBytes(probe, 0, probe.length);
    } finally {
      input.reset();
    }
    if (read <= 0) {
      return false;
    }
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    boolean endOfInput = read < probe.length;
    CharBuffer out = CharBuffer.allocate(read);
    CoderResult coderResult = decoder.decode(ByteBuffer.wrap(probe, 0, read), out, endOfInput);
    if (coderResult.isError()) {
      return false;
    }
    if (endOfInput && decoder.flush(out).isError()) {
      return false;
    }
    out.flip();
    while (out.hasRemaining()) {
      if (isBinaryControlChar(out.get())) {
        return false;
      }
    }
    return true;
  }

  /** C0 controls that no text document uses; tab/newline/carriage-return/form-feed are text. */
  private static boolean isBinaryControlChar(char c) {
    return c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f';
  }
}
