/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

/** The runtime has no writer because it has not opened, or has already closed. */
public final class IndexWriterUnavailableException extends IllegalStateException {
  public IndexWriterUnavailableException(String message) {
    super(message);
  }
  public IndexWriterUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
