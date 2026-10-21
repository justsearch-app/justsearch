/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/** A bounded state stream lost continuity; its consumer must obtain a fresh snapshot. */
public final class StreamOverflowException extends IllegalStateException {
  public StreamOverflowException(String message) { super(message); }
}
