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
  @Test void noPassPreservesOriginalCancellationAndStopOrder(@TempDir Path directory) {
    var calls = new java.util.ArrayList<String>();
    var head = mock(HeadAssembly.class, invocation -> {
      switch (invocation.getMethod().getName()) {
        case "quiesceEnrichmentForTerminalShutdown" -> calls.add("early-stop");
        case "close" -> calls.add("head-close");
        default -> { }
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
    var admission = mock(EngineAdmissionService.class);
    doAnswer(c -> { calls.add("admission-close"); return null; }).when(admission).beginClosing();
    doAnswer(c -> { calls.add("interactive-cancel"); return null; }).when(admission).cancelInteractive(anyString());
    when(admission.awaitDrained(Duration.ofSeconds(5))).thenAnswer(c -> { calls.add("admission-drain"); return true; });
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    doAnswer(c -> { calls.add("attempts-close"); return null; }).when(attempts).beginClosing();
    when(attempts.awaitDrained(Duration.ofSeconds(5))).thenAnswer(c -> { calls.add("attempts-drain"); return true; });
    var leases = mock(OperationLeaseService.class);
    doAnswer(c -> { calls.add("freeze"); return null; }).when(leases).freezeAdmission(anyString());
    var sequence = new EngineShutdownSequence(directory,
        HeadlessApp.orderedShutdownSteps(null, head, null, null, null, null, null,
            null, leases, admission, mock(io.justsearch.app.api.EngineProcessResources.class), () -> null,
            mock(io.justsearch.app.api.operations.OperationStore.class), attempts,
            () -> calls.add("producers")), ignored -> {});
    assertTrue(sequence.run(ShutdownRequest.Reason.QUIT).clean());
    assertEquals(java.util.List.of("admission-close", "attempts-close", "freeze", "interactive-cancel",
        "producers", "admission-drain", "attempts-drain", "head-close"), calls,
        "no picture pass: the original cancellation and drain order is preserved exactly");
  }

  @Test void activePassAllowsThreeSecondStopAndRunsLaterSteps(@TempDir Path directory) {
    var stopped = new AtomicBoolean();
    var head = mock(HeadAssembly.class, invocation -> {
      if ("prepareEnrichmentShutdown".equals(invocation.getMethod().getName())) return true;
      if ("quiesceEnrichmentForTerminalShutdown".equals(invocation.getMethod().getName())) {
        Duration budget = invocation.getArgument(1);
        // The process port reports the exit at 3s. The caller's real shutdown sequence supplies
        // the budget; this fake does no OS process manipulation.
        Thread.sleep(Math.min(3000, budget.toMillis()));
        if (budget.toMillis() < 3000) throw new IllegalStateException("process survived the short allowance");
        stopped.set(true);
      }
      return RETURNS_DEFAULTS.answer(invocation);
    });
    var admission = mock(EngineAdmissionService.class);
    when(admission.awaitDrained(any())).thenReturn(true);
    var attempts = mock(io.justsearch.app.api.operations.OperationAttemptRunner.class);
    when(attempts.awaitDrained(any())).thenReturn(true);
    var resources = mock(io.justsearch.app.api.EngineProcessResources.class);
    var sequence = new EngineShutdownSequence(directory,
        HeadlessApp.orderedShutdownSteps(null, head, null, null, null, null, null,
            null, mock(OperationLeaseService.class), admission, resources, () -> null,
            mock(io.justsearch.app.api.operations.OperationStore.class), attempts, () -> {}), ignored -> {});
    assertTrue(sequence.run(ShutdownRequest.Reason.QUIT).clean(), "3s stop is within the original terminal allowance");
    assertTrue(stopped.get());
    verify(admission).cancelInteractive(anyString());
    verify(admission).awaitDrained(Duration.ofSeconds(5));
    verify(attempts).awaitDrained(Duration.ofSeconds(5));
    verify(head).close();
    verify(resources).close();
  }

  @Test void terminalEnrichmentQuiescesBeforeTheLiveWorkDrain(@TempDir Path directory) {
    var quiesced = new AtomicBoolean();
    var head = mock(HeadAssembly.class, invocation -> {
      if ("prepareEnrichmentShutdown".equals(invocation.getMethod().getName())) return true;
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
