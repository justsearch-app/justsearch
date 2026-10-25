/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

/** The parser pool cannot accept any document; distinct from a child crashing on one document. */
public final class SandboxServiceUnavailableException extends SandboxExtractionException {
  public SandboxServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
