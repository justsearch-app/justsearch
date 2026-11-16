/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * S6c (C1'/C2'): "Read waiting files now" releases the reading rules for file reading only, and the
 * release ends on every stated condition. Members added by the slice are reached reflectively so
 * the before-state fails by assertion.
 */
final class ReadWaitingFilesReleaseTest {
  private static final IndexingSchedule HOURS = new IndexingSchedule(true, List.of(1), 600, 660);

  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, "IndexingHold." + name);
  }

  private static String readNow(IndexingHold hold, boolean filesWaiting) {
    return call(call(hold, "readWaitingNow", filesWaiting), "outcome").toString();
  }

  private static String notHeldReason(IndexingHold hold) {
    var result = call(hold, "readWaitingNow", true);
    assertEquals("NOT_HELD", call(result, "outcome").toString());
    return (String) call(result, "reason");
  }

  private static boolean ingestionHeld(IndexingHold hold) {
    return (Boolean) call(hold, "ingestionHeld");
  }

  private static boolean released(IndexingHold hold) {
    return (Boolean) call(hold.snapshot(), "released");
  }

  /** Outside reading hours on Monday 08:20, optionally on battery with the battery rule. */
  private static IndexingHold outsideHours(AtomicInteger minute) {
    var hold = new IndexingHold();
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, minute.get()));
    hold.setPolicy(false, null, null, HOURS, false);
    return hold;
  }

  @Test
  void outcomesNameWhatHappenedAndTheRulesStayUnchanged() {
    var minute = new AtomicInteger(500);
    var hold = outsideHours(minute);
    assertEquals("NOTHING_WAITING", readNow(hold, false), "nothing waiting installs nothing");
    assertTrue(ingestionHeld(hold));
    assertEquals("STARTED", readNow(hold, true));
    assertFalse(ingestionHeld(hold), "file reading passes the reading-hours rule");
    assertTrue(released(hold));
    assertEquals("JOINED", readNow(hold, true), "a second click joins the one release");
    assertEquals("JOINED", readNow(hold, false), "joining needs no new count");
    assertTrue(hold.held(), "the rules themselves still hold");
    assertEquals(List.of("SCHEDULE"), hold.snapshot().reasons(), "rules unchanged");

    var free = new IndexingHold();
    assertNull(notHeldReason(free), "nothing holds");
    free.pause(null, "USER_PAUSED");
    assertEquals("USER_PAUSED", notHeldReason(free));
    var recovery = outsideHours(new AtomicInteger(500));
    recovery.pause(null, "SETTINGS_RECOVERY");
    assertEquals("SETTINGS_RECOVERY", notHeldReason(recovery));
    var paused = outsideHours(new AtomicInteger(500));
    paused.pause(null, "USER_PAUSED");
    assertEquals("USER_PAUSED", notHeldReason(paused), "manual Pause is never overridden");
    assertNull(call(paused, "tryAcquireIngestion"));
  }

  @Test
  void onlyFileReadingAdmissionIsReleasedOtherLanesStayHeld() throws Exception {
    var hold = outsideHours(new AtomicInteger(500));
    assertNull(call(hold, "tryAcquireIngestion"), "held before the release");
    assertEquals("STARTED", readNow(hold, true));
    assertTrue(hold.held(), "held() is unchanged for enrichment, picture reading and GPL");
    assertNull(hold.tryAcquire(), "other lanes' admission stays closed");
    var waiter = CompletableFuture.supplyAsync(() -> {
      try (var lease = hold.acquire()) { return lease != null; }
      catch (InterruptedException failure) { throw new AssertionError(failure); }
    });
    assertThrows(TimeoutException.class, () -> waiter.get(300, TimeUnit.MILLISECONDS),
        "explicit picture reading or GPL keeps waiting");
    var lease = call(hold, "tryAcquireIngestion");
    assertNotNull(lease, "file reading is admitted");
    assertEquals(IndexingHold.State.PAUSING, hold.snapshot().state(), "admitted reading is not quiet");
    ((IndexingHold.Lease) lease).close();
    hold.setPolicy(false, null, null, null, false);
    assertTrue(waiter.get(2, TimeUnit.SECONDS), "only the rules' end releases the other lanes");
  }

  @Test
  void anEmptyPollUnderTheReleaseEndsItAndAnOlderTokenCannotEndANewerRelease() {
    var hold = outsideHours(new AtomicInteger(500));
    assertNull(call(hold, "activeRelease"));
    assertEquals("STARTED", readNow(hold, true));
    var first = call(hold, "activeRelease");
    assertNotNull(first);
    call(hold, "endRelease", first);
    assertTrue(ingestionHeld(hold), "drained: the hold applies again");
    assertFalse(released(hold));
    assertEquals("STARTED", readNow(hold, true), "files arriving later need a new click");
    call(hold, "endRelease", first);
    assertFalse(ingestionHeld(hold), "an older release's empty poll leaves the newer one");
  }

  @Test
  void pauseRecoveryAndAcceptedRuleChangesEndItUnchangedSavesDoNot() {
    var hold = outsideHours(new AtomicInteger(500));
    assertEquals("STARTED", readNow(hold, true));
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), false);
    assertFalse(ingestionHeld(hold), "an unrelated settings save (same rules) keeps it");
    hold.setPolicy(true, null, "USER_PAUSED", HOURS, false);
    assertTrue(ingestionHeld(hold), "manual Pause ends it");
    hold.setPolicy(false, null, null, HOURS, false);
    assertTrue(ingestionHeld(hold), "Resume does not bring it back");

    assertEquals("STARTED", readNow(hold, true));
    hold.pause(null, "SETTINGS_RECOVERY");
    hold.resume();
    assertTrue(ingestionHeld(hold), "settings recovery ends it");

    assertEquals("STARTED", readNow(hold, true));
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1, 2), 600, 660), false);
    assertTrue(ingestionHeld(hold), "a changed schedule ends it");

    assertEquals("STARTED", readNow(hold, true));
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1, 2), 600, 660), true);
    assertTrue(ingestionHeld(hold), "turning the battery rule on ends it");
    assertEquals(List.of("SCHEDULE"), hold.snapshot().reasons(), "only the edit's rules apply");
  }

  @Test
  void aRuleReasonThatDidNotHoldAtTheStartEndsItBothCoveredReasonsKeepIt() {
    var minute = new AtomicInteger(500);
    var hold = outsideHours(minute);
    hold.setPolicy(false, null, null, HOURS, true);
    assertEquals("STARTED", readNow(hold, true), "released for hours while on mains");
    hold.setOnBattery(true);
    assertTrue(ingestionHeld(hold), "battery beginning while released for hours ends it");
    assertEquals(List.of("SCHEDULE", "ON_BATTERY"), hold.snapshot().reasons());

    assertEquals("STARTED", readNow(hold, true), "both rules covered (S6c-3)");
    assertFalse(ingestionHeld(hold));
    hold.setOnBattery(false);
    assertFalse(ingestionHeld(hold), "a covered reason ending keeps the release while another holds");
    hold.setOnBattery(true);
    assertFalse(ingestionHeld(hold), "a covered reason coming back is not new");

    minute.set(620);
    hold.setOnBattery(false);
    assertFalse(hold.held(), "inside hours on mains nothing holds");
    assertFalse(released(hold));
    minute.set(500);
    assertTrue(ingestionHeld(hold), "a hold that ended once applies again in full");

    var battery = new IndexingHold();
    battery.setLocalTime(() -> new IndexingHold.LocalMinute(1, minute.get()));
    battery.setOnBattery(true);
    battery.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), true);
    minute.set(620);
    assertEquals("STARTED", readNow(battery, true), "released for battery inside hours");
    minute.set(700);
    assertTrue(ingestionHeld(battery), "the hours ending while released for battery ends it");
  }

  @Test
  void aRestartedEngineHasNoRelease() {
    var hold = outsideHours(new AtomicInteger(500));
    assertEquals("STARTED", readNow(hold, true));
    var restarted = outsideHours(new AtomicInteger(500));
    assertTrue(ingestionHeld(restarted), "nothing is saved: a new hold starts held");
    assertFalse(released(restarted));
  }
}
