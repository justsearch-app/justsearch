/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import java.util.Objects;

/**
 * One suspend or resume of the machine (Spec F §3.5).
 *
 * @param kind whether the machine is going to sleep or has woken
 * @param atNanos {@link System#nanoTime()} when the notification arrived
 * @param epoch the suspend count: a SUSPEND opens epoch {@code n}, and the RESUME that closes it
 *     carries the same {@code n}
 */
public record PowerEvent(Kind kind, long atNanos, long epoch) {

  /** The two transitions a consumer acts on. */
  public enum Kind {
    SUSPEND,
    RESUME
  }

  public PowerEvent {
    Objects.requireNonNull(kind, "kind");
  }
}
