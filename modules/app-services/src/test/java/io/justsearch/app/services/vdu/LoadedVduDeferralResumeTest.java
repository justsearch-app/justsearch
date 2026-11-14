/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.util.EnergyState;
import io.justsearch.ipc.VduUpdateOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Chat loaded, machine idle on AC power: an automatic picture-reading pass stopped by an energy
 * drop or a bulk/indexing hold, with no user activity, starts again by itself once the conditions
 * return. User activity still waits for the next idle period. Real seam: {@link VduResumeSeam}.
 */
@Timeout(240)
final class LoadedVduDeferralResumeTest {
  private static final Duration RESUME_LIMIT = Duration.ofMinutes(30);
  @TempDir Path directory;
  private VduResumeSeam vdu;

  @AfterEach void tearDown() throws Exception {
    if (vdu != null) vdu.close();
  }

  // S5: deferred by energy REDUCED, a bulk hold or an indexing hold; zero or one document done.
  @ParameterizedTest(name = "{0} after {1} completed")
  @CsvSource({"ENERGY,0", "ENERGY,1", "BULK,0", "BULK,1", "INDEXING,0", "INDEXING,1"})
  void deferredPassResumesOnALaterIdleTickOnceConditionsReturn(String cause, int completedFirst)
      throws Exception {
    vdu = new VduResumeSeam(directory, true);
    var fired = new AtomicBoolean();
    List<String> claimed = new CopyOnWriteArrayList<>();
    Runnable defer = () -> { if (fired.compareAndSet(false, true)) condition(cause, true); };
    if (completedFirst == 0) {
      vdu.onClaim = id -> { claimed.add(id); defer.run(); };
    } else {
      vdu.onClaim = claimed::add;
      vdu.onResult = id -> defer.run();
    }
    vdu.tick();
    assertTrue(fired.get(), "the deferral happened during the pass");
    assertEquals(1, vdu.passes.get());
    long done = vdu.states.values().stream().filter("DONE"::equals).count();
    assertEquals(completedFirst, done, "documents completed before the deferral: " + vdu.states);
    if (completedFirst == 0) {
      String interrupted = claimed.getFirst();
      assertEquals("PENDING", vdu.states.get(interrupted), "the interrupted claim returns to PENDING");
      assertEquals(0, vdu.retries.get(interrupted), "without spending a retry");
    } else {
      String completed = claimed.getFirst();
      assertEquals(List.of(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT), vdu.outcomesOf(completed));
    }
    assertTrue(VduResumeSeam.awaitFor(() -> vdu.manager.isOnline()
        && !vdu.manager.currentConfig().vduMode(), 20), "normal chat is restored");
    vdu.onClaim = id -> {};
    vdu.onResult = id -> {};

    condition(cause, false);
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "a new pass starts on a later idle tick without user activity: passes=" + vdu.passes
            + " states=" + vdu.states);
    for (String id : vdu.documents) {
      assertEquals(List.of(VduUpdateOutcome.VDU_UPDATE_OUTCOME_SUCCESS_TEXT), vdu.outcomesOf(id),
          "each picture is read exactly once; committed results stay committed: " + id);
      assertEquals(1, vdu.retries.get(id), "only the successful read spent an attempt: " + id);
    }
  }

  // S5c: a hold taken between the sampler's check and the indexing lease (selected=0, NONE).
  @Test void holdBetweenSamplerCheckAndLeaseStartsWorkOnALaterTickAfterRelease() throws Exception {
    vdu = new VduResumeSeam(directory, true);
    int launches = vdu.launches();
    vdu.beforeLease.set(() -> vdu.indexingHold.pause(null, "USER_PAUSED"));
    vdu.tick();
    assertNull(vdu.beforeLease.get(), "the pass started and met the hold at its lease");
    assertEquals(0, vdu.passes.get(), "the held pass selected nothing");
    assertEquals(launches, vdu.launches(), "and launched nothing");
    vdu.indexingHold.resume();
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "after the release a later tick starts work: passes=" + vdu.passes + " states=" + vdu.states);
  }

  // S6: user activity during the pass, alone or with an energy drop: no new pass until idle again.
  @ParameterizedTest(name = "with energy drop: {0}") @ValueSource(booleans = {false, true})
  void userActivityDuringThePassWaitsForTheNextIdlePeriod(boolean withEnergyDrop) throws Exception {
    vdu = new VduResumeSeam(directory, true);
    var fired = new AtomicBoolean();
    vdu.onResult = id -> {
      if (!fired.compareAndSet(false, true)) return;
      vdu.userActivity();
      if (withEnergyDrop) condition("ENERGY", true);
    };
    vdu.tick();
    assertTrue(fired.get());
    assertEquals(1, vdu.states.values().stream().filter("DONE"::equals).count(), vdu.states.toString());
    vdu.onResult = id -> {};
    if (withEnergyDrop) condition("ENERGY", false);
    int passes = vdu.passes.get();
    int launches = vdu.launches();
    for (int i = 0; i < 4; i++) {
      vdu.advance(Duration.ofSeconds(60));
      vdu.tick();
    }
    assertEquals(passes, vdu.passes.get(), "no new pass before the machine is idle again");
    assertEquals(launches, vdu.launches());
    vdu.advance(Duration.ofMillis(VduResumeSeam.IDLE));
    assertTrue(vdu.resumesWithoutUserAction(RESUME_LIMIT),
        "once idle again the remaining pictures are read: " + vdu.states);
  }

  private void condition(String cause, boolean deferring) {
    switch (cause) {
      case "ENERGY" -> vdu.energy.set(new EnergyState(
          deferring ? EnergyState.Intent.REDUCED : EnergyState.Intent.FULL, EnergyState.Source.AC));
      case "BULK" -> vdu.bulk.setGame(deferring);
      case "INDEXING" -> {
        if (deferring) vdu.indexingHold.pause(null, "USER_PAUSED");
        else vdu.indexingHold.resume();
      }
      default -> throw new IllegalArgumentException(cause);
    }
  }
}
