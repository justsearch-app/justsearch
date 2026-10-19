/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.app.api.EngineWorkHandle;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * The turns a reader's Stop can reach: the ones running now, and the ones whose Stop arrived before
 * they started (a Stop pressed in the instant after Send can overtake its dispatch). Such a Stop is
 * kept and stops its turn the moment it starts, so the turn is saved as stopped rather than running
 * on behind a window that already halted it.
 *
 * <p>What is kept is bounded: at most {@link #MAX_EARLY_STOPS} early Stops, each for at most
 * {@link #EARLY_STOP_TTL}, used once; and a Stop for a turn that already finished is not kept at all,
 * so it can never stop a later dispatch that names the same turn.
 */
final class TurnStops {

  /** How long a Stop waits for its turn to start. */
  static final Duration EARLY_STOP_TTL = Duration.ofSeconds(60);
  /** How many Stops wait at once; the oldest goes first. */
  static final int MAX_EARLY_STOPS = 256;
  /** How many finished turns are remembered, so their late Stops are not kept. */
  static final int MAX_FINISHED_TURNS = 1024;

  /** A turn as a Stop names it: its conversation and the id it was dispatched with. */
  record Turn(String conversationId, String turnId) {}

  private final String reason;
  private final LongSupplier nanoTime;
  private final Map<Turn, EngineWorkHandle> running = new HashMap<>();
  private final Map<Turn, Long> earlyStops = newest(MAX_EARLY_STOPS);
  private final Set<Turn> finished = Collections.newSetFromMap(newest(MAX_FINISHED_TURNS));

  /**
   * @param reason the cancellation reason a Stop cancels its turn's work with
   * @param nanoTime the clock early Stops age by
   */
  TurnStops(String reason, LongSupplier nanoTime) {
    this.reason = reason;
    this.nanoTime = nanoTime;
  }

  /** The turn started with this work; a Stop that arrived first cancels it now. */
  void started(Turn turn, EngineWorkHandle work) {
    boolean stoppedFirst;
    synchronized (this) {
      running.put(turn, work);
      finished.remove(turn);
      Long stoppedAt = earlyStops.remove(turn);
      stoppedFirst = stoppedAt != null && !expired(stoppedAt, nanoTime.getAsLong());
    }
    if (stoppedFirst) work.cancel(reason);
  }

  /** The turn's work ended; a later Stop for it changes nothing. */
  synchronized void finished(Turn turn, EngineWorkHandle work) {
    if (running.remove(turn, work)) finished.add(turn);
  }

  /**
   * The reader's Stop. A running turn is cancelled; a turn that has not started yet is stopped when
   * it starts (within {@link #EARLY_STOP_TTL}); a finished turn is left alone.
   *
   * @return whether a running turn was cancelled now
   */
  boolean stop(Turn turn) {
    EngineWorkHandle work;
    synchronized (this) {
      work = running.get(turn);
      if (work == null) {
        if (!finished.contains(turn)) {
          long now = nanoTime.getAsLong();
          earlyStops.values().removeIf(stoppedAt -> expired(stoppedAt, now));
          earlyStops.put(turn, now);
        }
        return false;
      }
    }
    work.cancel(reason);
    return true;
  }

  private static boolean expired(long stoppedAt, long now) {
    return now - stoppedAt > EARLY_STOP_TTL.toNanos();
  }

  /** A map that keeps only its {@code max} newest entries. */
  private static <K, V> Map<K, V> newest(int max) {
    return new LinkedHashMap<>() {
      private static final long serialVersionUID = 1L;

      @Override
      protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > max;
      }
    };
  }
}
