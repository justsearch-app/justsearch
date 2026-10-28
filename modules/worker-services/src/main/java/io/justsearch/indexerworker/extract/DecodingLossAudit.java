/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Set;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ParseRecord;

/** Strict replay of a whole-file text parser's actual charset over the prepared snapshot. */
final class DecodingLossAudit {
  private static final Set<String> TEXT_PARSERS = Set.of(
      "org.apache.tika.parser.csv.TextAndCSVParser",
      "org.apache.tika.parser.csv.SurrogateSafeTextAndCSVParser",
      "org.apache.tika.parser.code.SourceCodeParser",
      "org.apache.tika.parser.html.JSoupParser");
  private static final Set<String> DISPATCHERS = Set.of(
      "org.apache.tika.parser.AutoDetectParser", "org.apache.tika.parser.DefaultParser",
      "org.apache.tika.parser.CompositeParser");

  private DecodingLossAudit() {}

  static boolean isLossy(PreparedExtractionInput input, Metadata metadata, ParseContext context)
      throws IOException {
    // Text parsers set this before emitting HTML metadata. Meta tags append values, so
    // get() retains the parser's charset rather than a document-supplied later value.
    String encoding = metadata.get(Metadata.CONTENT_ENCODING);
    if (encoding == null) return false;
    // HTML meta tags can add arbitrary metadata, including X-TIKA:Parsed-By. Only Tika's
    // parse-context record identifies the parsers that actually ran. The owned graph's
    // CompositeParser dispatches between DefaultParser and the owned text/CSV parser.
    // Tika 3.2.3 records each
    // parser before invoking it, in insertion order: the first non-dispatcher owns the outer
    // document. Later entries may decode embedded resources and do not decide this audit's scope.
    ParseRecord record = context.get(ParseRecord.class);
    if (record == null) return false;
    boolean textParser = false;
    for (String parser : record.getParsers()) {
      if (DISPATCHERS.contains(parser)) continue;
      textParser = TEXT_PARSERS.contains(parser);
      break; // An outer container remains excluded even if a later embedded parser handles text.
    }
    if (!textParser) return false;
    Charset charset;
    try {
      charset = Charset.forName(encoding);
    } catch (UnsupportedCharsetException | java.nio.charset.IllegalCharsetNameException unsupported) {
      return false; // No JDK decoder for the charset the parser reported.
    }
    boolean utf8 = charset.equals(StandardCharsets.UTF_8);
    boolean ascii = charset.equals(StandardCharsets.US_ASCII);
    var decoder = utf8 || ascii ? null : charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    // These parsers' readers consume a leading Unicode BOM. Replay the same bytes without it;
    // in particular a consumed BOM must not become an error under a conflicting declaration.
    try (var channel = input.openChannel()) {
      ByteBuffer bytes = ByteBuffer.allocate(64 * 1024);
      byte[] data = bytes.array();
      CharBuffer chars = decoder == null ? null : CharBuffer.allocate(bytes.capacity());
      while (bytes.position() < 4 && channel.read(bytes) != -1) {
        // Gather enough prefix bytes to recognize any consumed Unicode BOM, even on short reads.
      }
      bytes.flip();
      // Generic Unicode decoders need the BOM to select byte order; it is valid input to them.
      int bom = charset.name().equals("UTF-16") || charset.name().equals("UTF-32")
          ? 0 : bomLength(bytes);
      bytes.position(bom);
      boolean endOfInput = false;
      while (true) {
        if (utf8) {
          if (invalidUtf8(data, bytes, endOfInput)) return true;
        } else if (ascii) {
          bytes.position(skipAscii(bytes, bytes.position()));
          if (bytes.hasRemaining()) return true;
        } else {
          var result = decoder.decode(bytes, chars, endOfInput);
          chars.clear(); // Discard output, reusing storage even when the decoder overflows.
          if (result.isError()) return true;
          if (result.isOverflow()) continue;
        }
        if (endOfInput) break;
        bytes.compact(); // Retain incomplete sequences across reads.
        endOfInput = channel.read(bytes) == -1;
        bytes.flip();
      }
      if (decoder == null) return false;
      while (true) {
        var result = decoder.flush(chars);
        chars.clear();
        if (!result.isOverflow()) return result.isError();
      }
    }
  }

  /** Validate without producing chars; leave a split sequence for compact/read to complete. */
  private static boolean invalidUtf8(byte[] data, ByteBuffer bytes, boolean endOfInput) {
    int position = bytes.position();
    int limit = bytes.limit();
    while (position < limit) {
      int lead = data[position] & 0xff;
      if (lead < 0x80) {
        position = skipAscii(bytes, position);
        continue;
      }
      if (lead < 0xc2 || lead > 0xf4) return true;
      int length = lead < 0xe0 ? 2 : lead < 0xf0 ? 3 : 4;
      if (limit - position < length) {
        bytes.position(position);
        return endOfInput;
      }
      int second = data[position + 1] & 0xff;
      if ((second & 0xc0) != 0x80) return true;
      if (length > 2) {
        if ((lead == 0xe0 && second < 0xa0) // Overlong three-byte form.
            || (lead == 0xed && second >= 0xa0) // Encoded surrogate.
            || (lead == 0xf0 && second < 0x90) // Overlong four-byte form.
            || (lead == 0xf4 && second >= 0x90)) return true; // Above U+10FFFF.
        for (int offset = 2; offset < length; offset++) {
          if ((data[position + offset] & 0xc0) != 0x80) return true;
        }
      }
      position += length;
    }
    bytes.position(position);
    return false;
  }

  private static int skipAscii(ByteBuffer bytes, int start) {
    // UTF-8 and US-ASCII have no shift state; resume this scan after each UTF-8 sequence.
    int position = start;
    int limit = bytes.limit();
    while (position <= limit - Long.BYTES
        && (bytes.getLong(position) & 0x8080808080808080L) == 0) position += Long.BYTES;
    while (position < limit && bytes.get(position) >= 0) position++;
    return position;
  }

  private static int bomLength(ByteBuffer bytes) {
    if (bytes.remaining() >= 4
        && ((bytes.get(0) == 0 && bytes.get(1) == 0 && bytes.get(2) == (byte) 0xfe && bytes.get(3) == (byte) 0xff)
            || (bytes.get(0) == (byte) 0xff && bytes.get(1) == (byte) 0xfe && bytes.get(2) == 0 && bytes.get(3) == 0))) return 4;
    if (bytes.remaining() >= 3 && bytes.get(0) == (byte) 0xef && bytes.get(1) == (byte) 0xbb && bytes.get(2) == (byte) 0xbf) return 3;
    if (bytes.remaining() >= 2
        && ((bytes.get(0) == (byte) 0xfe && bytes.get(1) == (byte) 0xff)
            || (bytes.get(0) == (byte) 0xff && bytes.get(1) == (byte) 0xfe))) return 2;
    return 0;
  }
}
