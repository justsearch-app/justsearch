/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.embed.onnx;

import java.util.AbstractList;
import java.util.Objects;
import java.util.RandomAccess;

/** Lazy overlapping windows, repeating the tokenizer's leading and trailing framing tokens. */
public final class TokenWindows extends AbstractList<long[][]> implements RandomAccess {
  private final long[][] fields;
  private final int payload;
  private final int stride;
  private final int size;

  public TokenWindows(
      long[] ids, long[] mask, long[] typeIds, int chunkSize, int chunkOverlap, int maxSeqLen) {
    Objects.requireNonNull(ids, "ids");
    Objects.requireNonNull(mask, "mask");
    Objects.requireNonNull(typeIds, "typeIds");
    if (ids.length != mask.length || ids.length != typeIds.length) {
      throw new IllegalArgumentException("Token array lengths must match");
    }
    fields = new long[][] {ids, mask, typeIds};
    size = count(ids.length, chunkSize, chunkOverlap, maxSeqLen);
    payload = Math.min(chunkSize, maxSeqLen) - 2;
    stride = Math.max(1, payload - chunkOverlap);
  }

  /** Counts windows without allocating token arrays or per-window metadata. */
  public static int count(int tokenCount, int chunkSize, int chunkOverlap, int maxSeqLen) {
    if (tokenCount < 0 || chunkSize <= 2 || maxSeqLen <= 2 || chunkOverlap < 0) {
      throw new IllegalArgumentException("Framed windows require capacity for content and two special tokens");
    }
    if (tokenCount == 0) return 0;
    int payload = Math.min(chunkSize, maxSeqLen) - 2;
    long remaining = Math.max(0L, (long) tokenCount - 2 - payload);
    long stride = Math.max(1, payload - chunkOverlap);
    return 1 + (int) ((remaining + stride - 1) / stride);
  }

  @Override public int size() { return size; }

  /** First source content-token index in this window. */
  public int start(int index) {
    Objects.checkIndex(index, size);
    return 1 + index * stride;
  }

  /** Exclusive source content-token index; the final framing token is excluded. */
  public int end(int index) {
    return (int) Math.min(fields[0].length - 1L, (long) start(index) + payload);
  }

  @Override public long[][] get(int index) {
    Objects.checkIndex(index, size);
    if (fields[0].length <= 2) {
      return new long[][] {fields[0].clone(), fields[1].clone(), fields[2].clone()};
    }
    int start = start(index);
    int end = end(index);
    int length = Math.max(0, end - start) + 2;
    long[][] result = new long[3][length];
    for (int field = 0; field < 3; field++) {
      long[] source = fields[field];
      result[field][0] = source[0];
      System.arraycopy(source, start, result[field], 1, Math.max(0, end - start));
      result[field][length - 1] = source[source.length - 1];
    }
    return result;
  }
}
