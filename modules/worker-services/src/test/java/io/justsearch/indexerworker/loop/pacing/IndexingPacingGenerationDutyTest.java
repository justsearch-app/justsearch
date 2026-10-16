/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop.pacing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.core.scheduling.BulkWorkGate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Spec E E-U2 and E-P14's arithmetic (§1.4, §1.6): chat generation is the second input of the one
 * indexing duty cycle. Deterministic: clocks and sleep are injected.
 *
 * <pre>
 * effectiveDuty = searchBusy ? dutyPct     : dutyFloor ? generationDutyPct : 100
 * maxDebt       = searchBusy ? MAX_DEBT_MS : generationMaxDebtMs
 * </pre>
 */
final class IndexingPacingGenerationDutyTest {

  private static final class FakeTime implements IndexingPacing.Sleeper {
    private long ms = 1_000_000L;
    private long nanos;
    private final List<Long> sleeps = new ArrayList<>();
    private Runnable onSleep = () -> {};

    @Override
    public void sleepMs(long millis) {
      sleeps.add(millis);
      advance(millis);
      onSleep.run();
    }

    void advance(long millis) {
      ms += millis;
      nanos += TimeUnit.MILLISECONDS.toNanos(millis);
    }

    long totalSlept() {
      return sleeps.stream().mapToLong(Long::longValue).sum();
    }
  }

  private static IndexingPacing pacing(FakeTime t, ForegroundLoad load, BulkWorkGate gate) {
    return new IndexingPacing(load, gate, 20, 0L, () -> t.ms, () -> t.nanos, t);
  }

  /** One work slice of {@code workMs} after an established clock; returns the yield. */
  private static long yieldAfter(FakeTime t, IndexingPacing p, long workMs) {
    p.pace();
    t.advance(workMs);
    long before = t.totalSlept();
    p.pace();
    return t.totalSlept() - before;
  }

  // GPU profile, generating, no search: 50% duty with a 1 s debt cap
  @Test
  void generationOnAGpuProfileRunsAtFiftyPercent() {
    var t = new FakeTime();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGenerating(true);
    var p = pacing(t, new ForegroundLoad(() -> t.ms), gate);

    assertTrue(p.foregroundBusy(), "the duty floor paces indexing");
    assertFalse(p.searchBusy());
    assertEquals(400L, yieldAfter(t, p, 400), "400 ms of work yields 400 ms at 50%");
    assertEquals(1_000L, yieldAfter(t, p, 1_000), "a 1 s slice yields the 1 s cap: 50% effective");
  }

  // NO_GPU and UNRESOLVED keep today's search duty and 2 s cap while generating (E-P14, N6)
  @Test
  void generationOffAGpuProfileKeepsTodaysDuty() {
    for (var profile : List.of(BulkWorkGate.Profile.NO_GPU, BulkWorkGate.Profile.UNRESOLVED)) {
      var t = new FakeTime();
      var gate = new BulkWorkGate();
      gate.setProfile(profile);
      gate.setGenerating(true);
      var p = pacing(t, new ForegroundLoad(() -> t.ms), gate);

      assertEquals(400L, yieldAfter(t, p, 100), profile + ": 100 ms of work yields 400 ms at 20%");
      assertEquals(2_000L, yieldAfter(t, p, 1_000),
          profile + ": a 1 s slice yields the 2 s cap, about 33% effective, as today");
    }
  }

  // search and generation overlap: the search duty and cap govern (ADR-0048 unchanged)
  @Test
  void searchDutyGovernsWhenBothAreBusy() {
    var t = new FakeTime();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P16_PLUS);
    gate.setGenerating(true);
    var load = new ForegroundLoad(() -> t.ms);
    var p = pacing(t, load, gate);
    load.started();

    assertTrue(p.searchBusy());
    assertEquals(400L, yieldAfter(t, p, 100), "20% while a search runs, not 50%");
    assertEquals(2_000L, yieldAfter(t, p, 1_000), "and the 2 s cap");
  }

  // no generation and no search: nothing yields
  @Test
  void noInputNoYield() {
    var t = new FakeTime();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    var p = pacing(t, new ForegroundLoad(() -> t.ms), gate);

    assertFalse(p.foregroundBusy());
    assertEquals(0L, yieldAfter(t, p, 1_000));
  }

  // the yield re-reads the inputs per chunk: generation ending mid-yield releases at once
  @Test
  void generationEndingMidYieldReleases() {
    var t = new FakeTime();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGenerating(true);
    var p = pacing(t, new ForegroundLoad(() -> t.ms), gate);
    t.onSleep = () -> gate.setGenerating(false);

    assertEquals(IndexingPacing.MAX_SLEEP_CHUNK_MS, yieldAfter(t, p, 1_000),
        "one chunk, then the released floor ends the yield");
  }

  // a search ending mid-yield while chat still generates drops to the generation cap
  @Test
  void searchEndingMidYieldDropsToTheGenerationCap() {
    var t = new FakeTime();
    var gate = new BulkWorkGate();
    gate.setProfile(BulkWorkGate.Profile.P12);
    gate.setGenerating(true);
    var load = new ForegroundLoad(() -> t.ms);
    var p = pacing(t, load, gate);
    load.started();
    p.pace();
    t.advance(1_000);
    t.onSleep = () -> {
      if (load.inFlight() > 0) load.finished();
    };
    p.pace();

    assertEquals(IndexingPacing.MAX_SLEEP_CHUNK_MS + 1_000L, t.totalSlept(),
        "2 s owed under search; after the first chunk only chat runs, so at most its 1 s cap remains");
  }
}
