/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.core.scheduling.IndexingHold;
import io.justsearch.core.scheduling.IndexingSchedule;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * S6c (C3'): operating.indexing names the reading-rule reasons apart from bulk-work reasons, says
 * when ready file jobs are held, and while a release is active reports file reading as it runs
 * (never "paused"), with the rule reasons still listed.
 */
final class StatusReadNowProjectionTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static Object call(Object target, String name, Object... args) {
    return assertDoesNotThrow(() -> {
      for (var method : target.getClass().getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == args.length) {
          return method.invoke(target, args);
        }
      }
      throw new NoSuchMethodException(name);
    }, name);
  }

  @Test
  void ruleHoldReleaseAndManualPauseProjectDistinctly() throws Exception {
    var hold = new IndexingHold();
    hold.setLocalTime(() -> new IndexingHold.LocalMinute(1, 500));
    hold.setPolicy(false, null, null, new IndexingSchedule(true, List.of(1), 600, 660), false);
    var assembler = new OperatingStateAssembler();
    assembler.setIndexingHold(hold::snapshot);
    var worker = mock(WorkerOperationalView.class);
    var core = mock(CoreIndexView.class);
    when(core.pendingJobs()).thenReturn(4L);
    when(core.indexState()).thenReturn("IDLE");
    when(worker.core()).thenReturn(core);
    var migration = mock(MigrationGenerationView.class);
    when(migration.pendingReadyJobsCount()).thenReturn(4L);
    when(worker.migration()).thenReturn(migration);
    var method = OperatingStateAssembler.class.getDeclaredMethod("indexing", PowerStatusView.class,
        WorkerOperationalView.class, long.class, Instant.class);
    method.setAccessible(true);

    var held = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("PAUSED", held.state().name());
    var heldJson = JSON.valueToTree(held);
    assertEquals("[\"schedule\"]", heldJson.path("readingRuleReasons").toString());
    assertTrue(heldJson.path("heldFilesWaiting").asBoolean(), "ready file jobs are held");
    assertFalse(heldJson.path("release").path("active").asBoolean(true));

    assertEquals("STARTED", call(call(hold, "readWaitingNow", true), "outcome").toString());
    var lease = call(hold, "tryAcquireIngestion");
    var released = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    var releasedJson = JSON.valueToTree(released);
    assertNotEquals("PAUSED", released.state().name(), "while released, not \"Reading paused\"");
    assertNotEquals("PAUSING", released.state().name());
    assertTrue(releasedJson.path("release").path("active").asBoolean(), releasedJson.toString());
    assertEquals("[]", releasedJson.path("readingRuleReasons").toString(), "no rule holds file reading");
    assertFalse(releasedJson.path("heldFilesWaiting").asBoolean(true), "nothing is held back");
    assertEquals(List.of("SCHEDULE"), released.reasons().stream().map(Enum::name).toList(),
        "the rule still holds the other lanes");
    assertNull(released.untilEpochMs());
    assertFalse(released.waitingForResume());
    ((IndexingHold.Lease) lease).close();

    hold.pause(null, "USER_PAUSED");
    var paused = (OperatingIndexingView) method.invoke(assembler, null, worker, 1L, Instant.now());
    var pausedJson = JSON.valueToTree(paused);
    assertEquals("PAUSED", paused.state().name());
    assertEquals(List.of("USER_PAUSED", "SCHEDULE"), paused.reasons().stream().map(Enum::name).toList());
    assertFalse(pausedJson.path("release").path("active").asBoolean(true), "Pause ended the release");

    when(migration.pendingReadyJobsCount()).thenReturn(0L);
    hold.resume();
    var empty = JSON.valueToTree(method.invoke(assembler, null, worker, 1L, Instant.now()));
    assertFalse(empty.path("heldFilesWaiting").asBoolean(true), "no ready file job is held");
    assertEquals("[\"schedule\"]", empty.path("readingRuleReasons").toString());
  }
}
