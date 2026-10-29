/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.core.scheduling.IndexingSchedule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StatusIndexingRulesTest {
  @Test
  void everyPolicyCombinationHasOrderedReasonsCoherentCodeAndNoExpiredDeadline() throws Exception {
    var hold = new IndexingHold(java.time.Clock.fixed(Instant.ofEpochMilli(1000), java.time.ZoneOffset.UTC));
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
    var assembler = new OperatingStateAssembler();
    assembler.setIndexingHold(hold::snapshot);
    var worker = mock(WorkerOperationalView.class);
    var core = mock(CoreIndexView.class);
    when(worker.core()).thenReturn(core);
    var method = OperatingStateAssembler.class.getDeclaredMethod("indexing", PowerStatusView.class,
        WorkerOperationalView.class, long.class, Instant.class);
    method.setAccessible(true);
    for (int mask = 1; mask < 16; mask++) {
      boolean recovery = (mask & 8) != 0;
      boolean manual = (mask & 4) != 0;
      boolean schedule = (mask & 2) != 0;
      boolean battery = (mask & 1) != 0;
      hold.setOnBattery(battery);
      hold.setPolicy(recovery || manual, recovery || manual ? 2000L : null,
          recovery ? "SETTINGS_RECOVERY" : manual ? "USER_PAUSED" : null,
          schedule ? new IndexingSchedule(true, List.of(1), 600, 660) : null, battery);
      var expected = new java.util.ArrayList<String>();
      if (recovery) expected.add("SETTINGS_RECOVERY");
      else if (manual) expected.add("USER_PAUSED");
      if (schedule) expected.add("SCHEDULE");
      if (battery) expected.add("ON_BATTERY");
      var view = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
      assertEquals(expected, view.reasons().stream().map(Enum::name).toList());
      assertEquals("PAUSED", view.state().name());
      String code = switch (expected.getFirst()) {
        case "SETTINGS_RECOVERY" -> "indexing.paused_settings_recovery_check_exclusions";
        case "USER_PAUSED" -> "indexing.paused_user";
        case "SCHEDULE" -> "indexing.paused_schedule";
        default -> "indexing.paused_on_battery";
      };
      assertEquals(code, view.reasonCode());
      assertEquals(recovery || manual ? 2000L : null, view.untilEpochMs());
    }
    hold.setPolicy(false, null, null, null, false);
    var admitted = hold.tryAcquire();
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), true);
    var drainingSchedule = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("PAUSING", drainingSchedule.state().name());
    assertEquals("indexing.pausing_schedule", drainingSchedule.reasonCode());
    hold.setPolicy(false, null, null, null, true);
    var drainingBattery = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("PAUSING", drainingBattery.state().name());
    assertEquals("indexing.pausing_on_battery", drainingBattery.reasonCode());
    admitted.close();
    hold.setPolicy(true, 999L, "USER_PAUSED", new IndexingSchedule(true, List.of(1), 600, 660), true);
    var expired = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("indexing.paused_schedule", expired.reasonCode());
    assertNull(expired.untilEpochMs());
  }
}
