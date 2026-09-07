/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.observability.health.AssertedCondition;
import io.justsearch.app.observability.health.ConditionStatus;
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.lifecycle.InferenceCapability;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.services.observability.health.LifecycleSnapshotTap;
import io.justsearch.app.services.observability.health.ReadinessReconciliationTrigger;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.app.services.worker.KnowledgeClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 876 §B.2a — binds the production composition, not a stand-in for it.
 *
 * <p>{@code ReadinessReconciledWithoutRequestTest} (app-services) proves the trigger's own
 * mechanics over a hand-authored envelope supplier. What it cannot reach is the single line that
 * makes the trigger do anything in the running head: {@code CoreApiAssembly}'s {@code
 * readinessTrigger.attach(statusLifecycleHandler::sampleAndBuildStatusSnapshot)}. That method reference is
 * the whole wiring — if it were attached to nothing, or if the thunk threw on every call, every
 * unit test over the trigger would still be green.
 *
 * <p>So this test assembles the real pair: a real {@link StatusLifecycleHandler} with a real {@link
 * LifecycleSnapshotTap} over a real {@link ConditionStore} (mirroring {@code CoreApiAssembly}'s tap
 * wiring), a real {@link WorkerCapability}, a real {@link ReadinessReconciliationTrigger} wired the
 * way {@code OrchestrationPhase} wires it, and the SAME {@code
 * statusLifecycleHandler::sampleAndBuildStatusSnapshot} thunk {@code CoreApiAssembly} attaches. There is no
 * Javalin {@code Context}, no HTTP server and no {@code /api/status} call anywhere in this file:
 * the handler's only caller is the trigger's daemon thread, so a condition observed to change here
 * changed because a capability transitioned.
 *
 * <p>The Worker gRPC call {@code buildStatusMap} performs is kept offline the way {@code
 * StatusReadinessStalenessTest} keeps it offline — a mocked {@link KnowledgeServerBootstrap} whose
 * client returns a canned {@link WorkerOperationalView}.
 */
final class ReadinessTriggerCompositionTest {

  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");
  private static final long AWAIT_DEADLINE_MS = 5_000L;

  @Test
  @DisplayName(
      "a worker READY transition drives the real handler's snapshot and clears index.unavailable")
  void workerReadyTransitionReconcilesTheRealHandlerSnapshot(@TempDir Path indexBase)
      throws InterruptedException {
    ConditionStore conditions = new ConditionStore();
    LifecycleSnapshotTap tap =
        new LifecycleSnapshotTap(
            conditions, new HealthEventChangeRegistry(), HEAD_SRC, Clock.systemUTC());

    // Real capabilities: the worker starts PENDING, exactly as it does before the Worker connects.
    WorkerCapability worker = new WorkerCapability();
    InferenceCapability inference = new InferenceCapability(false);

    KnowledgeServerBootstrap knowledgeServer = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView()).thenReturn(healthyWorkerView());
    when(knowledgeServer.client()).thenReturn(client);

    StatusLifecycleHandler handler = newHandler(indexBase, worker, inference);
    handler.setKnowledgeServer(knowledgeServer, null);
    // Mirrors CoreApiAssembly's tap wiring — the tap is the only writer of index.unavailable.
    handler.setLifecycleSnapshotTap(tap);

    try (ReadinessReconciliationTrigger trigger = new ReadinessReconciliationTrigger()) {
      // OrchestrationPhase's wiring.
      trigger.wireTo(worker, inference);
      // CoreApiAssembly's wiring — the production method reference, not a test lambda.
      trigger.attach(handler::sampleAndBuildStatusSnapshot);

      HealthEvent asserted =
          awaitCondition(
              conditions,
              true,
              "the attach self-seed must run the sampler: a PENDING worker yields"
                  + " INDEX_SERVING NOT_READY, which the tap asserts as index.unavailable");
      assertEquals("index.unavailable", asserted.id());
      AssertedCondition condition = (AssertedCondition) asserted.body();
      assertEquals("worker", condition.subject());
      assertEquals(ConditionStatus.TRUE, condition.status(), "the condition must be ASSERTED");

      // The Worker connects. Nothing calls /api/status — there is no request path in this graph.
      worker.transition(CapabilityHealth.READY, null);

      awaitCondition(
          conditions,
          false,
          "the capability transition must re-run the sampler and clear index.unavailable"
              + " — not wait for the next status request");
    }
  }

  /**
   * Polls the condition store until {@code index.unavailable} reaches {@code expectedPresent}, or
   * the deadline passes. The trigger runs the thunk on its own daemon thread, so the change is
   * asynchronous with respect to {@code transition()}.
   */
  private static HealthEvent awaitCondition(
      ConditionStore conditions, boolean expectedPresent, String message)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + AWAIT_DEADLINE_MS;
    Optional<HealthEvent> found;
    do {
      found = conditions.find("index.unavailable", "worker");
      if (found.isPresent() == expectedPresent) {
        return found.orElse(null);
      }
      Thread.sleep(20L);
    } while (System.currentTimeMillis() < deadline);
    return fail(
        message
            + " (index.unavailable was "
            + (found.isPresent() ? "still asserted" : "absent")
            + " after "
            + AWAIT_DEADLINE_MS
            + "ms)");
  }

  // ---------------------------------------------------------------- helpers

  private static StatusLifecycleHandler newHandler(
      Path indexBase, WorkerCapability worker, InferenceCapability inference) {
    return new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        indexBase,
        Instant.now().minusSeconds(60),
        () -> "OK",
        null,
        null,
        null,
        worker,
        inference);
  }

  private static WorkerOperationalView healthyWorkerView() {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 10, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(CompatibilityStatusView.empty())
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .visualExtraction(VisualExtractionView.empty())
        .embeddingReady(Boolean.TRUE)
        .build();
  }
}
