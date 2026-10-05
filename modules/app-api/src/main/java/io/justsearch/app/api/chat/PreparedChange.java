/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * The first phase of a two-phase settings commit. Exactly one of {@link #install}, {@link #retire}
 * or {@link #abort} ends it; {@code abort} stops the candidate with {@code ROLLBACK}.
 *
 * @param <C> the chat configuration type, owned by the controller implementation
 */
public interface PreparedChange<C> {
  C candidate();

  void install();

  void retire();

  void abort();
}
