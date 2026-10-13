/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.EngineAdmissionService;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.engine.EngineShutdownSequence;
import io.justsearch.app.engine.ShutdownRequest;
import io.justsearch.app.services.HeadAssembly;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pins the production launcher order; document/process cancellation is checked in app-services. */
final class HeadlessAppVduShutdownTest {
  @Test void terminalEnrichmentQuiescesBeforeTheLiveWorkDrain(@TempDir Path directory) {
    var quiesced = new AtomicBoolean();
    var head = mock(HeadAssembly.class, invocation -> {
      if ("quiesceEnrichmentForTerminalShutdown".equals(invocation.getMethod().getName())) {
        quiesced.set(true);
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
    var admission = mock(EngineAdmissionService.class);
    doAnswer(invocation -> {
      assertTrue(quiesced.get(), "pending-status cleanup precedes admission closure");
      return null;
    }).when(admission).beginClosing();
    when(admission.awaitDrained(Duration.ofSeconds(5))).thenAnswer(invocation -> quiesced.get());
    var sequence = new EngineShutdownSequence(directory,
        HeadlessApp.orderedShutdownSteps(null, head, null, null, null, null, null,
            null, mock(OperationLeaseService.class), admission, mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
            mock(io.justsearch.app.api.operations.OperationStore.class), null, () -> {}), ignored -> {});
    var result = sequence.run(ShutdownRequest.Reason.QUIT);
    assertTrue(quiesced.get(), "enrichment must be cancelled before waiting for live work");
    assertTrue(result.clean(), "terminal enrichment is drained before dependency teardown");
    verify(head).close();
  }
}
