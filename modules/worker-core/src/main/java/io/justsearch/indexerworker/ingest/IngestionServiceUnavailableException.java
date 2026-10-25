/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ingest;

/** A shared ingestion service cannot serve any document; retry without spending file attempts. */
public final class IngestionServiceUnavailableException extends IllegalStateException {
  public IngestionServiceUnavailableException(String message) { super(message); }
  public IngestionServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
