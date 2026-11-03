/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;

/** Stored representation of the accepted FeatureField entries (design/enrichment.md, revision 4). */
final class SparsePayload {
  static final String FIELD = io.justsearch.indexing.SchemaFields.SPLADE_PAYLOAD;
  private static final int FORMAT = 1;

  private SparsePayload() {}

  static byte[] encode(Map<String, Float> features) {
    try {
      var bytes = new ByteArrayOutputStream();
      var out = new DataOutputStream(bytes);
      out.writeByte(FORMAT);
      out.writeInt(features.size());
      long size = 5;
      for (var entry : features.entrySet()) {
        var term = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(entry.getKey()));
        if (term.remaining() > IndexWriter.MAX_TERM_LENGTH) {
          throw new IllegalArgumentException("Sparse term exceeds Lucene's maximum term length");
        }
        size += 8L + term.remaining();
        if (size > ArrayUtil.MAX_ARRAY_LENGTH) {
          throw new IllegalArgumentException("Sparse payload exceeds Lucene's stored-field limit");
        }
        out.writeInt(term.remaining());
        out.write(term.array(), term.arrayOffset() + term.position(), term.remaining());
        out.writeFloat(entry.getValue());
      }
      return bytes.toByteArray();
    } catch (CharacterCodingException invalidTerm) {
      throw new IllegalArgumentException("Sparse term is not valid UTF-8", invalidTerm);
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  static Map<String, Float> decode(BytesRef bytes) throws IOException {
    // Slice owns the offset/length, including when Lucene returns a view into a shared buffer.
    var in = ByteBuffer.wrap(bytes.bytes, bytes.offset, bytes.length).slice();
    if (in.remaining() < 5 || Byte.toUnsignedInt(in.get()) != FORMAT) {
      throw new IOException("Unknown or truncated sparse payload format");
    }
    int count = in.getInt();
    // Each entry needs at least a length and a float, even for the empty term.
    if (count <= 0 || count > in.remaining() / 8) {
      throw new IOException("Invalid sparse payload feature count");
    }
    var features = new LinkedHashMap<String, Float>();
    for (int i = 0; i < count; i++) {
      if (in.remaining() < 8) throw new IOException("Truncated sparse payload entry");
      int length = in.getInt();
      if (length < 0 || length > IndexWriter.MAX_TERM_LENGTH || length > in.remaining() - 4) {
        throw new IOException("Invalid sparse payload term length");
      }
      var termBytes = in.slice();
      termBytes.limit(length);
      String term = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(termBytes).toString();
      in.position(in.position() + length);
      float weight = in.getFloat();
      if (!Float.isFinite(weight) || weight < Float.MIN_NORMAL || weight > 64.0f
          || features.putIfAbsent(term, weight) != null) {
        throw new IOException("Invalid or duplicate sparse payload feature");
      }
    }
    if (in.hasRemaining()) throw new IOException("Trailing sparse payload bytes");
    return features;
  }
}
