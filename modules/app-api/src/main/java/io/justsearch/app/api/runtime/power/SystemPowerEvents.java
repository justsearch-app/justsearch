/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime.power;

import java.util.function.Consumer;

/**
 * The machine's suspends and resumes, as the Engine sees them (Spec F §3.5, S11 power part).
 *
 * <p>A sleeping machine stops every timer the Engine runs, and on resume each of them sees the whole
 * sleep as elapsed time: a per-request timeout fires, a health probe looks like a dead device. This
 * is the producer those consumers read: the chat controller (timers reset, a resume probe), ORT
 * device detection and the extraction timeout re-arm. Spec F owns only the producer and its
 * registration; the consumers are their own specs'.
 *
 * <p>On Windows the events come from {@code PowerRegisterSuspendResumeNotification}
 * ({@link WindowsPowerNotifications}); elsewhere there are none ({@link NoPowerEvents}), and {@link
 * #suspendedNanosBetween} answers 0, which is what a consumer that never heard of suspend assumed.
 */
public interface SystemPowerEvents {

  /** Stops a listener's delivery. Idempotent. */
  @FunctionalInterface
  interface Subscription extends AutoCloseable {
    @Override
    void close();
  }

  /**
   * Delivers every later event to {@code listener}, in order, on one dispatch thread. A listener
   * that throws keeps its subscription, and the failure reaches no other listener.
   */
  Subscription subscribe(Consumer<PowerEvent> listener);

  /**
   * How much of {@code [t0, t1]} ({@link System#nanoTime()} instants) the machine spent suspended,
   * as far as notifications tell; a suspend not yet resumed counts up to {@code t1}.
   */
  long suspendedNanosBetween(long t0, long t1);

  /**
   * Registers this process for power notifications, once. Called from {@code HeadlessApp.main}
   * right after containment. Never throws: a registration that fails yields {@link NoPowerEvents}
   * with the reason logged.
   */
  static SystemPowerEvents installAtBoot() {
    return BootPowerEvents.install();
  }

  /** What {@link #installAtBoot} returned, or {@link NoPowerEvents} before it ran. */
  static SystemPowerEvents current() {
    return BootPowerEvents.current();
  }
}
