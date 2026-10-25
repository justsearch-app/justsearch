/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import java.io.IOException;
/**
 * Categorized runtime exception used by the Lucene runtime to surface IO failures upstream without
 * flattening them into IllegalStateException.
 */
public final class IndexRuntimeIOException extends RuntimeException {
  public enum Reason {
    DISK_IO,
    DISK_FULL,
    LOCKED,
    SCHEMA_MISMATCH,
    CORRUPT_INDEX,
    CONFIGURATION,
    BACKPRESSURE,
    DRAINING
  }

  private final Reason reason;
  public IndexRuntimeIOException(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason == null ? Reason.DISK_IO : reason;
  }

  public Reason reason() { return reason; }

  static IndexRuntimeIOException fromIndexOperation(
      IOException cause, String message) {
    return new IndexRuntimeIOException(LuceneRuntimeUtils.classifyIOException(cause), message, cause);
  }

}
