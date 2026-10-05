/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/** An admitted generation. Closing it is idempotent and ends the exchange's lease exactly once. */
public interface GenerationLease extends AutoCloseable {
  @Override
  void close();
}
