/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.justsearch.app.api.EngineWorkHandle;
import io.justsearch.app.services.conversation.ConversationEngine.TurnStop;
import io.justsearch.core.context.EngineContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * S2.3 / N2.3 — a Stop that overtakes its dispatch still stops the turn when it starts; the Stops
 * kept for that are bounded in number (not in age: a turn can wait minutes to start) and never
 * kept for a turn that already finished; and what a Stop answers is what happens to the turn.
 */
final class TurnStopsTest {

  private static final String REASON = "user_stop";

  private final TurnStops stops = new TurnStops(REASON);

  private static TurnStops.Turn turn(String id) {
    return new TurnStops.Turn("conv-1", id);
  }

  @Test
  void aStopOnARunningTurnCancelsItAtOnce() {
    var work = new Work();
    stops.started(turn("t-1"), work);

    assertEquals(TurnStop.STOPPED, stops.stop(turn("t-1")));
    assertEquals(List.of(REASON), work.cancels);
  }

  @Test
  void aStopBeforeTheTurnStartsCancelsItWhenItStarts() {
    assertEquals(TurnStop.PENDING, stops.stop(turn("t-1")), "nothing was running to stop yet");
    var work = new Work();
    stops.started(turn("t-1"), work);

    assertEquals(List.of(REASON), work.cancels);
  }

  @Test
  void anEarlyStopIsUsedOnce() {
    stops.stop(turn("t-1"));
    var first = new Work();
    stops.started(turn("t-1"), first);
    stops.finished(turn("t-1"), first);
    var second = new Work();
    stops.started(turn("t-1"), second);

    assertEquals(List.of(REASON), first.cancels);
    assertEquals(List.of(), second.cancels);
  }

  @Test
  void anEarlyStopNamesOnlyItsOwnTurn() {
    stops.stop(turn("t-1"));
    var other = new Work();
    stops.started(turn("t-2"), other);
    var elsewhere = new Work();
    stops.started(new TurnStops.Turn("conv-2", "t-1"), elsewhere);
    var own = new Work();
    stops.started(turn("t-1"), own);

    assertEquals(List.of(), other.cancels);
    assertEquals(List.of(), elsewhere.cancels);
    assertEquals(List.of(REASON), own.cancels);
  }

  @Test
  void aStopOnWorkAlreadyCancelledForAnotherReasonDoesNotSayItStopped() {
    var work = new Work();
    work.cancel("deadline_exceeded");
    stops.started(turn("t-1"), work);

    assertEquals(TurnStop.UNCHANGED, stops.stop(turn("t-1")), "the turn ends for its own reason");
  }

  @Test
  void aStopOnceTheAnswerIsBeingSavedChangesNothingAndSaysSo() {
    var work = new Work();
    stops.started(turn("t-1"), work);
    stops.completing(turn("t-1"), work);

    assertEquals(TurnStop.UNCHANGED, stops.stop(turn("t-1")));
    assertEquals(List.of(), work.cancels);
  }

  @Test
  void onlyTheNewestEarlyStopsAreKept() {
    for (int i = 0; i <= TurnStops.MAX_EARLY_STOPS; i++) stops.stop(turn("t-" + i));
    var oldest = new Work();
    stops.started(turn("t-0"), oldest);
    var newest = new Work();
    stops.started(turn("t-" + TurnStops.MAX_EARLY_STOPS), newest);

    assertEquals(List.of(), oldest.cancels);
    assertEquals(List.of(REASON), newest.cancels);
  }

  @Test
  void aStopAfterTheTurnFinishedIsNotKept() {
    var work = new Work();
    stops.started(turn("t-1"), work);
    stops.finished(turn("t-1"), work);

    assertEquals(TurnStop.UNCHANGED, stops.stop(turn("t-1")));
    var again = new Work();
    stops.started(turn("t-1"), again);

    assertEquals(List.of(), work.cancels);
    assertEquals(List.of(), again.cancels);
  }

  /** Work that records each cancel and, like the Engine's, keeps the first reason it is given. */
  private static final class Work implements EngineWorkHandle {
    final List<String> cancels = new ArrayList<>();
    private String reason;

    @Override public Optional<String> cancellationReason() { return Optional.ofNullable(reason); }

    @Override public void cancel(String reason) {
      cancels.add(reason);
      if (this.reason == null) this.reason = reason;
    }

    @Override public EngineContext context() { throw new UnsupportedOperationException(); }
    @Override public EngineWorkHandle retain() { return this; }
    @Override public void waitingClientGone() {}
    @Override public Registration onCancel(Consumer<String> callback) { return () -> {}; }
    @Override public Registration onBackground(Runnable callback) { return () -> {}; }
    @Override public Registration onCompletion(Runnable callback) { return () -> {}; }
    @Override public void close() {}
  }
}
