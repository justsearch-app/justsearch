/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

/**
 * Admits managed answer-server launches against an owner outside inference: the AI install
 * owner's file-removal lock (design D4). A launch is registered from just before its process
 * starts until it returns, so a removal of the answer model's files cannot begin meanwhile, and a
 * launch is refused while such a removal runs. Once the process exists the runtime reports the
 * answer server as running, which keeps refusing the removal.
 */
@FunctionalInterface
public interface ManagedLaunchFence {

  /**
   * Registers one managed launch until the result closes.
   *
   * @throws IllegalStateException with a plain reason when no managed launch may start now
   */
  Admission admit();

  /** One admitted launch; closing it is idempotent. */
  interface Admission extends AutoCloseable {
    @Override
    void close();
  }

  /** Admits every launch; the default until composition binds the removal lock. */
  ManagedLaunchFence OPEN = () -> () -> {};
}
