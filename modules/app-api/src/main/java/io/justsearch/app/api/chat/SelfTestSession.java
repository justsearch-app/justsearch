/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.chat;

/**
 * An open activation self-test. The main chat server stays stopped until the session closes.
 *
 * @param <S> the self-test specification, owned by the controller implementation
 * @param <R> the self-test result, owned by the controller implementation
 */
public interface SelfTestSession<S, R> extends AutoCloseable {
  R run(S spec);

  @Override
  void close();
}
