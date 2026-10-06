/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.gpu;

/**
 * Reads the user's foreground context for game detection (Spec E section 4): the shell's
 * notification state, Windows Game Mode and the foreground window. The Engine's residency sampler
 * polls it on each tick.
 *
 * <p>{@link #sampleNow()} never throws: a part that cannot be read is unknown in the sample, and a
 * reader that cannot read anything returns {@link UserContextSample#unknown()}.
 */
public interface UserContextReader extends AutoCloseable {

  /** Takes one reading now. Never throws. */
  UserContextSample sampleNow();

  /** Releases native resources and registrations. Later samples are unknown. */
  @Override
  void close();
}
