/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import io.justsearch.app.api.EngineWorkHandle;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The turns a reader's Stop can reach: the ones running now, and the ones whose Stop arrived before
 * they started (a Stop can overtake its dispatch, and a dispatch can wait a long time before it
 * starts: for the chat model to load, or for Engine capacity). Such a Stop is kept and stops its
 * turn the moment it starts, however long that takes, so the turn is saved as stopped rather than
 * running on behind a window that asked to stop it.
 *
 * <p>What is kept is bounded: at most {@link #MAX_EARLY_STOPS} early Stops, the oldest going first,
 * each used once; and a Stop for a turn that already finished is not kept at all, so it can never
 * stop a later dispatch that names the same turn.
 */
final class TurnStops {

  /** How many Stops wait at once; the oldest goes first. */
  static final int MAX_EARLY_STOPS = 256;
  /** How many finished turns are remembered, so their late Stops are not kept. */
  static final int MAX_FINISHED_TURNS = 1024;

  /** A turn as a Stop names it: its conversation and the id it was dispatched with. */
  record Turn(String conversationId, String turnId) {}

  private final String reason;
  private final Map<Turn, EngineWorkHandle> running = new HashMap<>();
  private final Set<Turn> earlyStops = Collections.newSetFromMap(newest(MAX_EARLY_STOPS));
  private final Set<Turn> finished = Collections.newSetFromMap(newest(MAX_FINISHED_TURNS));

  /** @param reason the cancellation reason a Stop cancels its turn's work with */
  TurnStops(String reason) {
    this.reason = reason;
  }

  /** The turn started with this work; a Stop that arrived first cancels it now. */
  synchronized void started(Turn turn, EngineWorkHandle work) {
    running.put(turn, work);
    finished.remove(turn);
    if (earlyStops.remove(turn)) work.cancel(reason);
  }

  /**
   * The turn's outcome is about to be saved as complete: from here a Stop changes nothing, and says
   * so. Taken under the same lock a Stop takes, so a Stop either lands before (and the turn's work
   * carries it) or after (and it answers {@link ConversationEngine.TurnStop#UNCHANGED}), never in between.
   */
  synchronized void completing(Turn turn, EngineWorkHandle work) {
    if (running.remove(turn, work)) finished.add(turn);
  }

  /** The turn's work ended; a later Stop for it changes nothing. */
  synchronized void finished(Turn turn, EngineWorkHandle work) {
    if (running.remove(turn, work)) finished.add(turn);
  }

  /**
   * The reader's Stop. A running turn is cancelled; a turn that has not started yet is stopped when
   * it starts; a finished turn is left alone.
   */
  synchronized ConversationEngine.TurnStop stop(Turn turn) {
    EngineWorkHandle work = running.get(turn);
    if (work == null) {
      if (finished.contains(turn)) return ConversationEngine.TurnStop.UNCHANGED;
      earlyStops.add(turn);
      return ConversationEngine.TurnStop.PENDING;
    }
    work.cancel(reason);
    // The first reason wins: work already ending for another reason (a deadline, say) is not
    // saved as stopped, so the Stop does not say it was.
    return work.cancellationReason().filter(reason::equals).isPresent()
        ? ConversationEngine.TurnStop.STOPPED
        : ConversationEngine.TurnStop.UNCHANGED;
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
