/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.services.bootstrap.BootTrace;
import io.justsearch.app.services.bootstrap.Memoized;
import io.justsearch.app.services.bootstrap.PhaseRecord;
import io.justsearch.app.services.bootstrap.RebuildHistory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BootRoutesLazyStateTest {
  @Test
  void failedRegistrationIsVisibleAsDegradedAfterMemoResolution() {
    var registration = Memoized.<Boolean>of(() -> {
      throw new IllegalStateException("registration failed");
    });
    assertThrows(IllegalStateException.class, registration::get);
    HeadAssembly head = mock(HeadAssembly.class);
    when(head.agentToolsRegistration()).thenReturn(registration);
    when(head.rebuildHistory()).thenReturn(new RebuildHistory());
    var trace = new BootTrace(BootTrace.HEAD, 1L, 2L,
        List.of(PhaseRecord.lazyPending("agent-tools-registration", "connect")));

    Map<String, Object> envelope = BootRoutes.envelopeWithLazyState(trace, head);
    var boot = (Map<?, ?>) envelope.get("boot");
    var phases = (List<?>) boot.get("phases");
    var phase = (Map<?, ?>) phases.getFirst();
    assertEquals(PhaseRecord.DEGRADED, phase.get("outcome"));
    assertEquals("agent_tools.registration_failed", phase.get("reasonCode"));
  }
}
