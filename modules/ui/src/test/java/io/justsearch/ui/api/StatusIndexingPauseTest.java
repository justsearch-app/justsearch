/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** S36.1/8/10: the user pause reports acknowledged quiet, even with no queued work. */
final class StatusIndexingPauseTest {
  private Object call(Object target, String method) {
    return assertDoesNotThrow(() -> target.getClass().getMethod(method).invoke(target));
  }

  @Test
  void pausePrecedesIdleAndWaitsForAllAcknowledgments() throws Exception {
    Object hold = assertDoesNotThrow(() -> Class.forName("io.justsearch.core.scheduling.IndexingHold")
        .getConstructor().newInstance(), "status needs the real acknowledged hold");
    var assembler = new OperatingStateAssembler();
    assertDoesNotThrow(() -> OperatingStateAssembler.class
        .getDeclaredMethod("setIndexingHold", Supplier.class)
        .invoke(assembler, (Supplier<Object>) () -> call(hold, "snapshot")));
    Object active = call(hold, "tryAcquire");
    long deadline = System.currentTimeMillis() + 60_000;
    hold.getClass().getMethod("pause", Long.class, String.class).invoke(hold, deadline, "USER_PAUSED");
    var worker = mock(WorkerOperationalView.class);
    var core = mock(CoreIndexView.class);
    when(worker.core()).thenReturn(core);
    var method = OperatingStateAssembler.class.getDeclaredMethod("indexing", PowerStatusView.class,
        WorkerOperationalView.class, long.class, Instant.class);
    method.setAccessible(true);
    Object pausing = method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("PAUSING", call(pausing, "state").toString());
    assertEquals(deadline, call(pausing, "untilEpochMs"));
    assertTrue(call(pausing, "reasons").toString().contains("USER_PAUSED"));
    assertTrue(call(pausing, "affects").toString().contains("VDU"), "the global user hold covers picture reading");
    call(active, "close");
    Object paused = method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("PAUSED", call(paused, "state").toString(), "an empty queue still reports user pause");
    when(core.pendingJobs()).thenReturn(1L);
    // IndexStatusOps classifies ready queued work as INDEXING even when processing is held.
    when(core.indexState()).thenReturn("INDEXING");
    Object waiting = method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals(true, call(waiting, "waitingForResume"));
    call(hold, "resume");
    Object resumed = method.invoke(assembler, null, worker, 1L, Instant.now());
    assertEquals("RUNNING", call(resumed, "state").toString());
    assertEquals(false, call(resumed, "waitingForResume"));
  }
}
