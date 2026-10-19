/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** A named hold on the chat controller's convergence. Closing it releases the hold. */
public interface Hold extends AutoCloseable {
  /** Who took the hold, for diagnostics. */
  String owner();

  @Override
  void close();
}
