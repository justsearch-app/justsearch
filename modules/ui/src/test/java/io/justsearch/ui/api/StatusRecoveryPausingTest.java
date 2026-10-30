/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.operating.OperatingIndexingView;
import io.justsearch.core.scheduling.IndexingHold;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** D5: recovery remains primary, with a reason code that agrees with its acknowledgment state. */
final class StatusRecoveryPausingTest {
  @Test
  void recoveryCodeDistinguishesDrainingFromAcknowledgedQuiet() throws Exception {
    var hold = new IndexingHold();
    var active = hold.tryAcquire();
    var assembler = new OperatingStateAssembler();
    assembler.setIndexingHold(hold::snapshot);
    var method = OperatingStateAssembler.class.getDeclaredMethod("indexing", PowerStatusView.class,
        WorkerOperationalView.class, long.class, Instant.class);
    method.setAccessible(true);
    try {
      hold.pause(null, "SETTINGS_RECOVERY");
      var draining = (OperatingIndexingView) method.invoke(assembler, null, null, 1L, Instant.now());
      assertEquals("PAUSING", draining.state().name());
      assertEquals("indexing.pausing_settings_recovery_check_exclusions", draining.reasonCode());
    } finally { active.close(); }
    var quiet = (OperatingIndexingView) method.invoke(assembler, null, null, 1L, Instant.now());
    assertEquals("PAUSED", quiet.state().name());
    assertEquals("indexing.paused_settings_recovery_check_exclusions", quiet.reasonCode());
  }
}
