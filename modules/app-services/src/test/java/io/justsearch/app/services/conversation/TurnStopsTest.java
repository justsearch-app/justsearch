/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;

import io.justsearch.app.api.EngineWorkHandle;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * S2.3 / N2.3 — a Stop that overtakes its dispatch still stops the turn when it starts, and the
 * Stops kept for that are bounded: in number, in age, and never for a turn that already finished.
 */
final class TurnStopsTest {

  private static final String REASON = "user_stop";

  private final AtomicLong clock = new AtomicLong();
  private final TurnStops stops = new TurnStops(REASON, clock::get);

  private static TurnStops.Turn turn(String id) {
    return new TurnStops.Turn("conv-1", id);
  }

  /** The reasons this work was cancelled with, in order. */
  private static List<Object> cancels(EngineWorkHandle work) {
    return mockingDetails(work).getInvocations().stream()
        .filter(call -> call.getMethod().getName().equals("cancel"))
        .map(call -> call.getArgument(0))
        .toList();
  }

  @Test
  void aStopOnARunningTurnCancelsItAtOnce() {
    var work = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), work);

    assertTrue(stops.stop(turn("t-1")));
    assertEquals(List.of(REASON), cancels(work));
  }

  @Test
  void aStopBeforeTheTurnStartsCancelsItWhenItStarts() {
    assertFalse(stops.stop(turn("t-1")), "nothing was running to stop yet");
    var work = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), work);

    assertEquals(List.of(REASON), cancels(work));
  }

  @Test
  void anEarlyStopIsUsedOnce() {
    stops.stop(turn("t-1"));
    var first = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), first);
    stops.finished(turn("t-1"), first);
    var second = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), second);

    assertEquals(List.of(REASON), cancels(first));
    assertEquals(List.of(), cancels(second));
  }

  @Test
  void anEarlyStopNamesOnlyItsOwnTurn() {
    stops.stop(turn("t-1"));
    var other = mock(EngineWorkHandle.class);
    stops.started(turn("t-2"), other);
    var elsewhere = mock(EngineWorkHandle.class);
    stops.started(new TurnStops.Turn("conv-2", "t-1"), elsewhere);
    var own = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), own);

    assertEquals(List.of(), cancels(other));
    assertEquals(List.of(), cancels(elsewhere));
    assertEquals(List.of(REASON), cancels(own));
  }

  @Test
  void anEarlyStopExpires() {
    stops.stop(turn("t-1"));
    clock.addAndGet(TurnStops.EARLY_STOP_TTL.toNanos() + 1);
    var work = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), work);

    assertEquals(List.of(), cancels(work));
  }

  @Test
  void anEarlyStopWithinItsTimeStillApplies() {
    stops.stop(turn("t-1"));
    clock.addAndGet(TurnStops.EARLY_STOP_TTL.toNanos() - 1);
    var work = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), work);

    assertEquals(List.of(REASON), cancels(work));
  }

  @Test
  void onlyTheNewestEarlyStopsAreKept() {
    for (int i = 0; i <= TurnStops.MAX_EARLY_STOPS; i++) stops.stop(turn("t-" + i));
    var oldest = mock(EngineWorkHandle.class);
    stops.started(turn("t-0"), oldest);
    var newest = mock(EngineWorkHandle.class);
    stops.started(turn("t-" + TurnStops.MAX_EARLY_STOPS), newest);

    assertEquals(List.of(), cancels(oldest));
    assertEquals(List.of(REASON), cancels(newest));
  }

  @Test
  void aStopAfterTheTurnFinishedIsNotKept() {
    var work = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), work);
    stops.finished(turn("t-1"), work);

    assertFalse(stops.stop(turn("t-1")));
    var again = mock(EngineWorkHandle.class);
    stops.started(turn("t-1"), again);

    assertEquals(List.of(), cancels(work));
    assertEquals(List.of(), cancels(again));
  }
}
