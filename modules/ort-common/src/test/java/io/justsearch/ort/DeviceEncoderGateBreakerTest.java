/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Spec D §2 tests K2 (close rule) and K4 (what does not open the breaker), on the device gate itself
 * with a controllable clock: the breaker is open exactly while a held seat that is not its session's
 * first run is older than {@code stuckAfterMs}, and it turns away only foreground query legs that
 * have a keyword fallback.
 */
final class DeviceEncoderGateBreakerTest {

  private static final DeviceGatePolicy POLICY = DeviceGatePolicy.DEFAULTS;
  private static final long PAST_STUCK_NANOS =
      TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs() + 1);

  private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1_000));
  private final DeviceEncoderGate gate = new DeviceEncoderGate(POLICY, clock::get);
  private final Object session = new Object();

  /**
   * K4 (D11, D1): a session's first run held past {@code stuckAfterMs} does not open the breaker,
   * and neither does a query that timed out waiting at the gate behind it. The same session's
   * second run held as long does (the positive control).
   */
  @Test
  void aFirstRunOrAGateTimeoutDoesNotOpenTheBreakerButALaterRunDoes() throws Exception {
    DeviceEncoderGate.Seat firstRun = gate.seat(session, background());
    clock.addAndGet(PAST_STUCK_NANOS);
    assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "a cold first run is not stuck");

    var timedOut =
        assertThrows(
            SessionAcquireDeadlineExceededException.class,
            () -> gate.seat(new Object(), queryLeg(Duration.ofMillis(50))));
    assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, timedOut.phase());
    assertFalse(timedOut.breakerOpen(), "the query waited at the gate; it was not turned away");
    assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "a gate timeout does not open the breaker");

    firstRun.close();
    long seatedAt = clock.get();
    DeviceEncoderGate.Seat secondRun = gate.seat(session, background());
    clock.addAndGet(PAST_STUCK_NANOS);
    OptionalLong since = gate.breakerOpenSinceNanos();
    assertTrue(since.isPresent(), "the session's second run past stuckAfterMs opens the breaker");
    assertEquals(seatedAt + TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs()), since.getAsLong());
    secondRun.close();
  }

  /**
   * K2 (close rule): the breaker closes in the lock step that releases the last over-age seat; the
   * next query leg is seated at once.
   */
  @Test
  void releasingTheStuckRunClosesTheBreakerInTheSameStep() throws Exception {
    gate.seat(session, background()).close();
    DeviceEncoderGate.Seat stuck = gate.seat(session, background());
    clock.addAndGet(PAST_STUCK_NANOS);

    var turnedAway =
        assertThrows(
            SessionAcquireDeadlineExceededException.class,
            () -> gate.seat(new Object(), queryLeg(Duration.ofSeconds(5))));
    assertTrue(turnedAway.breakerOpen());
    assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, turnedAway.phase());

    stuck.close();
    assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "closed with the release, no timer");
    try (DeviceEncoderGate.Seat _ = gate.seat(new Object(), queryLeg(Duration.ofSeconds(5)))) {
      assertEquals(0, gate.waiting());
    }
  }

  /**
   * Spec D §2.2: only a foreground leg with a keyword fallback is turned away. A VECTOR-only leg, the
   * reranker and prewarm (foreground with no fallback), bulk work and a foreground caller without a
   * semantic deadline queue as usual (CPU legs never reach a gate, P2).
   */
  @Test
  void onlyForegroundLegsWithAKeywordFallbackAreTurnedAway() throws Exception {
    gate.seat(session, background()).close();
    DeviceEncoderGate.Seat stuck = gate.seat(session, background());
    clock.addAndGet(PAST_STUCK_NANOS);
    assertTrue(gate.breakerOpenSinceNanos().isPresent());

    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50);
    SessionAcquisitionRequest[] queued = {
      // VECTOR-only and the reranker: REQUIRED (may create a session, released at the deadline).
      new SessionAcquisitionRequest(
          SessionAcquisitionRequest.Urgency.FOREGROUND, deadline, () -> false, true, true),
      // No semantic deadline (prewarm, NER single-text): waits for its run.
      new SessionAcquisitionRequest(
          SessionAcquisitionRequest.Urgency.FOREGROUND, deadline, () -> false, true, false),
      // Bulk work.
      new SessionAcquisitionRequest(
          SessionAcquisitionRequest.Urgency.BACKGROUND, deadline, () -> false, false, true),
    };
    for (SessionAcquisitionRequest request : queued) {
      var missed =
          assertThrows(
              SessionAcquireDeadlineExceededException.class, () -> gate.seat(new Object(), request));
      assertFalse(missed.breakerOpen(), "queued behind the stuck run, not turned away: " + request);
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, missed.phase());
    }
    stuck.close();
  }

  private static SessionAcquisitionRequest background() {
    return SessionAcquisitionRequest.within(
        SessionAcquisitionRequest.Urgency.BACKGROUND, Duration.ofMinutes(1));
  }

  /** A hybrid search's semantic leg: FALLBACK, foreground, released at its deadline. */
  private static SessionAcquisitionRequest queryLeg(Duration timeout) {
    return new SessionAcquisitionRequest(
        SessionAcquisitionRequest.Urgency.FOREGROUND,
        System.nanoTime() + timeout.toNanos(),
        () -> false,
        false,
        true);
  }
}
