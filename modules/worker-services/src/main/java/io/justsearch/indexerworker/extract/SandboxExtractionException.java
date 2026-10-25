/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.indexerworker.ingest.IngestionReasonCodes;

/**
 * Sandbox infrastructure failure, distinct from a parser failure.
 *
 * <p>Promoted to a top-level type by tempdoc 885 item 14 when {@code ProcessExtractionSandbox}
 * (one child JVM per file) was replaced by {@link PersistentExtractionSandbox}: the exception is
 * the boundary contract that {@code JobBatchExtractor} classifies on, so it must not be nested
 * inside whichever implementation happens to throw it.
 */
public class SandboxExtractionException extends ContentExtractor.ExtractionException {
  public SandboxExtractionException(String message, Throwable cause) {
    super(message + " [" + IngestionReasonCodes.SANDBOX_FAILED + "]", cause);
  }
}
