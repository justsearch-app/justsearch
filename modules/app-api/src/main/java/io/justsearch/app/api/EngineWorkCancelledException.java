/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api;

/** First-wins process-local cancellation reason, carried intact to HTTP and streaming clients. */
public final class EngineWorkCancelledException extends java.util.concurrent.CancellationException {
  private static final long serialVersionUID = 1L;
  private final String reasonCode;

  public EngineWorkCancelledException(String reasonCode) {
    super("Engine work cancelled: " + reasonCode);
    this.reasonCode = java.util.Objects.requireNonNull(reasonCode, "reasonCode");
  }

  public String reasonCode() { return reasonCode; }
}
