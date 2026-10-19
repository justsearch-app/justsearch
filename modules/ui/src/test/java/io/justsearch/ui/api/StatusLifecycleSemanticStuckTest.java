/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.justsearch.app.api.status.CompatibilityStatusView;
import io.justsearch.app.api.status.CoreIndexView;
import io.justsearch.app.api.status.EnrichmentProgressView;
import io.justsearch.app.api.status.FailureTrackingView;
import io.justsearch.app.api.status.GpuDiagnosticsView;
import io.justsearch.app.api.status.MigrationGenerationView;
import io.justsearch.app.api.status.QueueDbStatusView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.SearchConfigView;
import io.justsearch.app.api.status.TelemetryMetricsView;
import io.justsearch.app.api.status.VectorFormatView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.app.services.lifecycle.LifecycleProjection;
import io.justsearch.core.component.ComponentState;
import io.justsearch.ort.DeviceGatePolicy;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Spec D test K3 (S3, standing state), Head side: a device breaker open for {@code
 * breakerStandingMs} degrades the {@code embedding} component with {@code
 * encoders.health.semantic_stuck}; a shorter opening is reported per request only. The envelope is
 * built by the production {@link StatusLifecycleHandler#buildReadinessEnvelope} over a real
 * component projection, with the handler's clock fixed.
 */
final class StatusLifecycleSemanticStuckTest {

  private final StatusComponentFixture components = new StatusComponentFixture();

  @AfterEach
  void close() throws Exception {
    components.close();
  }

  @Test
  void aBreakerOpenForTheStandingTimeDegradesTheEmbeddingWithTheStuckCode() {
    StatusLifecycleHandler handler = newHandler();
    long now = 1_800_000_000_000L;
    handler.setClockForTesting(() -> now);
    long standingMs = DeviceGatePolicy.DEFAULTS.breakerStandingMs();
    WorkerOperationalView closed = readyWorkerView(null);
    WorkerOperationalView opening = readyWorkerView(now - standingMs + 1);
    WorkerOperationalView standing = readyWorkerView(now - standingMs);

    ReadinessEnvelopeView whileClosed = envelope(handler, closed);
    ReadinessEnvelopeView whileOpening = envelope(handler, opening);
    ReadinessEnvelopeView whileStanding = envelope(handler, standing);

    assertEquals("READY", whileClosed.components().get("embedding").state());
    assertEquals("READY", whileOpening.components().get("embedding").state(),
        "an opening shorter than breakerStandingMs is reported per request only");
    assertEquals("DEGRADED", whileStanding.components().get("embedding").state());
    assertEquals("encoders.health.semantic_stuck",
        whileStanding.components().get("embedding").reasonCode());
    assertTrue(whileStanding.composites().get("aiFeatures").reasonCodes()
        .contains("encoders.health.semantic_stuck"));
  }

  private ReadinessEnvelopeView envelope(StatusLifecycleHandler handler, WorkerOperationalView view) {
    return handler.buildReadinessEnvelope(
        view, readyProjection(), StatusLifecycleHandler.WorkerContact.observed(System.currentTimeMillis()), false);
  }

  /** A real four-component READY projection (so INDEX_SERVING is not gated by index state). */
  private LifecycleProjection.Projection readyProjection() {
    for (String name : java.util.List.of("api", "index", "encoders", "generative")) {
      components.transition(name, ComponentState.READY, null, name + " ready");
    }
    return components.projection();
  }

  /** Minimal handler — buildReadinessEnvelope null-guards every non-retrieval dimension's suppliers. */
  private StatusLifecycleHandler newHandler() {
    var graph = components.capabilities();
    StatusLifecycleHandler handler = new StatusLifecycleHandler(
        mock(io.justsearch.app.api.OnlineAiService.class),
        mock(io.justsearch.agent.api.AgentService.class),
        () -> null,
        null,
        null,
        null,
        Instant.now(),
        () -> "OK",
        null,
        null,
        null,
        graph.worker(),
        graph.inference());
    components.attach(handler);
    return handler;
  }

  /** A serving, compatible worker whose embedding is ready, with the given breaker open-since. */
  private static WorkerOperationalView readyWorkerView(Long semanticStuckSinceMs) {
    return WorkerOperationalViewBuilder.builder()
        .core(new CoreIndexView(true, 0, 0, "SERVING", 0, 0))
        .failure(FailureTrackingView.empty())
        .migration(MigrationGenerationView.empty())
        .compatibility(
            new CompatibilityStatusView("COMPATIBLE", "", "", "", "", "", "COMPATIBLE", false, ""))
        .queueDb(QueueDbStatusView.healthy())
        .enrichment(EnrichmentProgressView.empty())
        .gpu(GpuDiagnosticsView.empty())
        .vectorFormat(VectorFormatView.empty())
        .telemetry(new TelemetryMetricsView(0.0, 0, 0, 0.25, "OK"))
        .searchConfig(SearchConfigView.empty())
        .embeddingReady(true)
        .semanticStuckSinceMs(semanticStuckSinceMs)
        .build();
  }
}
