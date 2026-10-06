/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ort.DeviceEncoderGate;
import io.justsearch.ort.DeviceGatePolicy;
import io.justsearch.ort.SessionAcquisitionRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Spec D §3.3 ("pace points every ≤ {@code bulkPaceTargetMs} of GPU work") with §3.5 (pacing sets
 * the duty floor, the gate sets the order): the work a {@link IndexingPacing#pace()} interval is
 * charged for is the time the bulk slice worked, not the time it spent waiting for a device seat. A
 * slice that waited at the gate has already yielded the device for that long.
 *
 * <p>One fake clock drives the real {@link DeviceEncoderGate} and the pacing arithmetic; the pacing
 * sleeper records instead of sleeping.
 */
final class IndexingPacingGateWaitTest {

  private static final long WAITED_AT_GATE_NANOS = TimeUnit.SECONDS.toNanos(3);
  private static final long WORKED_NANOS = TimeUnit.MILLISECONDS.toNanos(500);

  @Test
  @Timeout(30)
  void aSliceThatWaitedAtTheGateAndWorkedHalfASecondIsNotPaused() throws Exception {
    AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
    DeviceEncoderGate gate = new DeviceEncoderGate(DeviceGatePolicy.DEFAULTS, clock::get);
    ForegroundLoad load = new ForegroundLoad(() -> clock.get() / 1_000_000L);
    load.started(); // foreground is busy for the whole test
    List<Long> slept = new ArrayList<>();
    IndexingPacing pacing =
        new IndexingPacing(
            load,
            IndexingPacing.DEFAULT_DUTY_PCT,
            IndexingPacing.DEFAULT_COOLDOWN_MS,
            () -> clock.get() / 1_000_000L,
            clock::get,
            slept::add);

    pacing.pace(); // the bulk thread's slice starts here

    // A foreground run holds the device; the bulk run queues behind it for 3 s of device time.
    DeviceEncoderGate.Seat foregroundRun =
        gate.seat(new Object(), request(SessionAcquisitionRequest.Urgency.FOREGROUND));
    Thread bulkThread = Thread.currentThread();
    AtomicReference<Throwable> helperFailure = new AtomicReference<>();
    Thread foregroundFinisher =
        new Thread(
            () -> {
              try {
                while (bulkThread.getState() != Thread.State.TIMED_WAITING) {
                  Thread.onSpinWait();
                }
                clock.addAndGet(WAITED_AT_GATE_NANOS);
                foregroundRun.close();
              } catch (Throwable thrown) {
                helperFailure.set(thrown);
              }
            },
            "gate-wait-foreground");
    foregroundFinisher.start();
    try (DeviceEncoderGate.Seat bulkRun =
        gate.seat(new Object(), request(SessionAcquisitionRequest.Urgency.BACKGROUND))) {
      clock.addAndGet(WORKED_NANOS); // the bulk run's own GPU work
    }
    foregroundFinisher.join(5_000);
    assertEquals(null, helperFailure.get(), "the foreground run finished");

    pacing.pace();

    assertEquals(
        List.of(),
        slept,
        "a slice that waited 3 s at the gate and worked 0.5 s has already yielded more than the"
            + " 2 s that 0.5 s of work owes at a 20% duty");

    // Control: the same 0.5 s of work without a gate wait is paced (0.5 s x 80/20 = 2 s).
    clock.addAndGet(WORKED_NANOS);
    pacing.pace();
    long sleptMs = slept.stream().mapToLong(Long::longValue).sum();
    assertTrue(sleptMs == 2_000L, "0.5 s of work with no gate wait yields 2 s, slept " + sleptMs);
  }

  private static SessionAcquisitionRequest request(SessionAcquisitionRequest.Urgency urgency) {
    return SessionAcquisitionRequest.within(urgency, Duration.ofHours(1));
  }
}
