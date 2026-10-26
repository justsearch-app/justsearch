/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Spec D §2 tests K2 (close rule) and K4 (what does not open the breaker), on the device gate itself
 * with a controllable clock: the breaker is open exactly while a held seat that is not its session's
 * first run has reached {@code stuckAfterMs}, and it turns away only foreground query legs that
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
    try (DeviceEncoderGate.Seat firstRun = gate.seat(session, background())) {
      clock.addAndGet(PAST_STUCK_NANOS);
      assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "a cold first run is not stuck");

      var timedOut =
          assertThrows(
              SessionAcquireDeadlineExceededException.class,
              () -> gate.seat(new Object(), queryLeg(Duration.ofMillis(50))));
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, timedOut.phase());
      assertFalse(timedOut.breakerOpen(), "the query waited at the gate; it was not turned away");
      assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "a gate timeout does not open the breaker");
    }
    long seatedAt = clock.get();
    try (DeviceEncoderGate.Seat secondRun = gate.seat(session, background())) {
      long threshold = TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs());
      clock.addAndGet(threshold - 1);
      assertTrue(gate.breakerOpenSinceNanos().isEmpty(), "one nanosecond before the threshold");
      var before = assertThrows(SessionAcquireDeadlineExceededException.class,
          () -> gate.seat(new Object(), queryLeg(Duration.ofMillis(50))));
      assertFalse(before.breakerOpen(), "before the threshold a fresh query really waits");
      assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, before.phase());

      clock.incrementAndGet();
      assertEquals(OptionalLong.of(seatedAt + threshold), gate.breakerOpenSinceNanos(),
          "the breaker opens exactly at the threshold");
      var at = assertThrows(SessionAcquireDeadlineExceededException.class,
          () -> gate.seat(new Object(), queryLeg(Duration.ofSeconds(5))));
      assertTrue(at.breakerOpen());
      assertEquals(SessionAcquireDeadlineExceededException.Phase.RUN, at.phase());

      clock.incrementAndGet();
      OptionalLong since = gate.breakerOpenSinceNanos();
      assertTrue(since.isPresent(), "the session's second run past stuckAfterMs opens the breaker");
      assertEquals(seatedAt + TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs()), since.getAsLong());
    }
  }

  /**
   * K2 (close rule): the breaker closes in the lock step that releases the last over-age seat; the
   * next query leg is seated at once.
   */
  @Test
  void releasingTheStuckRunClosesTheBreakerInTheSameStep() throws Exception {
    gate.seat(session, background()).close();
    try (DeviceEncoderGate.Seat stuck = gate.seat(session, background())) {
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
  }

  /**
   * Spec D §2.2: only a foreground leg with a keyword fallback is turned away. A VECTOR-only leg, the
   * reranker and prewarm (foreground with no fallback), bulk work and a foreground caller without a
   * semantic deadline queue as usual (CPU legs never reach a gate, P2).
   */
  @Test
  void onlyForegroundLegsWithAKeywordFallbackAreTurnedAway() throws Exception {
    gate.seat(session, background()).close();
    try (DeviceEncoderGate.Seat stuck = gate.seat(session, background())) {
      clock.addAndGet(PAST_STUCK_NANOS);
      assertTrue(gate.breakerOpenSinceNanos().isPresent());

      List<Supplier<SessionAcquisitionRequest>> queued = List.of(
          // VECTOR-only and the reranker: REQUIRED (may create a session, released at the deadline).
          () -> queuedRequest(SessionAcquisitionRequest.Urgency.FOREGROUND, true, true),
          // No semantic deadline (prewarm, NER single-text): waits for its run.
          () -> queuedRequest(SessionAcquisitionRequest.Urgency.FOREGROUND, true, false),
          // Bulk work.
          () -> queuedRequest(SessionAcquisitionRequest.Urgency.BACKGROUND, false, true));
      for (Supplier<SessionAcquisitionRequest> attempt : queued) {
        SessionAcquisitionRequest request = attempt.get();
        var missed =
            assertThrows(
                SessionAcquireDeadlineExceededException.class, () -> gate.seat(new Object(), request));
        assertFalse(missed.breakerOpen(), "queued behind the stuck run, not turned away: " + request);
        assertEquals(SessionAcquireDeadlineExceededException.Phase.GATE, missed.phase());
      }
      stuck.close();
    }
  }

  @Test
  void multipleSeatsKeepIndependentAgesAndReleaseClearsOnlyTheOverAgeSeat() throws Exception {
    DeviceGatePolicy policy = new DeviceGatePolicy(2, POLICY.agingSeatings(), POLICY.agingWallMs(),
        POLICY.recompositionWaitMs(), POLICY.stuckAfterMs(), POLICY.breakerStandingMs());
    DeviceEncoderGate twoSeats = new DeviceEncoderGate(policy, clock::get);
    Object otherSession = new Object();
    twoSeats.seat(session, background()).close();
    twoSeats.seat(otherSession, background()).close();
    long firstSeatedAt = clock.get();
    try (DeviceEncoderGate.Seat first = twoSeats.seat(session, background())) {
      clock.incrementAndGet();
      try (DeviceEncoderGate.Seat second = twoSeats.seat(otherSession, background())) {
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs()) - 2);
        assertTrue(twoSeats.breakerOpenSinceNanos().isEmpty(), "neither seat reached its threshold");
        clock.incrementAndGet();
        assertEquals(OptionalLong.of(firstSeatedAt + TimeUnit.MILLISECONDS.toNanos(POLICY.stuckAfterMs())),
            twoSeats.breakerOpenSinceNanos(), "only the older seat reached its threshold");
        first.close();
        assertTrue(twoSeats.breakerOpenSinceNanos().isEmpty(),
            "releasing the older seat clears the breaker while the younger seat is still held");
        clock.incrementAndGet();
        assertTrue(twoSeats.breakerOpenSinceNanos().isPresent(),
            "the younger seat now reaches its threshold");
        // Re-closing the first lease must not release the other session's seat.
        first.close();
        assertTrue(twoSeats.breakerOpenSinceNanos().isPresent());
      }
    }
    assertTrue(twoSeats.breakerOpenSinceNanos().isEmpty(), "the last stuck seat released");
    try (DeviceEncoderGate.Seat next = twoSeats.seat(new Object(), queryLeg(Duration.ofSeconds(5)))) {
      assertEquals(0, twoSeats.waiting(), "a fresh query can acquire after both releases");
    }
  }

  private static SessionAcquisitionRequest queuedRequest(
      SessionAcquisitionRequest.Urgency urgency, boolean mayCreate, boolean releaseAtDeadline) {
    return new SessionAcquisitionRequest(urgency,
        System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(50), () -> false,
        mayCreate, releaseAtDeadline);
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
