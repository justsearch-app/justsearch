/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.memory;

/** The unlocked memory store could not load completely; no reads or mutations are safe. */
public final class MemoriesUnavailableException extends IllegalStateException {
  public MemoriesUnavailableException(Throwable cause) {
    super("Memories are unavailable: stored memories could not be loaded. Retry after fixing the read failure.", cause);
  }
}
