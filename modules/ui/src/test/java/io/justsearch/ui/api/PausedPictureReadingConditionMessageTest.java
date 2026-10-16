/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import io.justsearch.app.observability.health.ConditionStore;
import io.justsearch.app.observability.health.HealthEvent;
import io.justsearch.app.observability.health.HealthEventChangeRegistry;
import io.justsearch.app.observability.health.Source;
import io.justsearch.app.services.bootstrap.CapabilityGraph;
import io.justsearch.app.services.observability.health.LifecycleSnapshotTap;
import io.justsearch.app.services.vdu.VduCapabilityState;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.component.ComponentState;
import io.justsearch.core.component.TestEngineComponents;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * b100: while automatic picture reading is paused, the {@code vdu.unavailable} health condition the
 * real status handler asserts carries, in its existing {@code message} field, why it is paused and
 * when the next try is. The readiness component itself has no message field.
 */
final class PausedPictureReadingConditionMessageTest {

  private static final Source HEAD_SRC = Source.forProcess("head", "instance-1", "1.0");
  private static final String PAUSED = "vdu.breaker_open.refused";
  private static final String DETAIL = "Automatic picture reading is paused after 3 automatic passes"
      + " in a row made no progress. Next try at 14:05; a manual run resumes it now.";

  @Test
  void thePauseConditionSaysWhenTheNextTryIs(@TempDir Path indexBase) throws Exception {
    ConditionStore conditions = new ConditionStore();
    var tap = new LifecycleSnapshotTap(conditions,
        new HealthEventChangeRegistry(Executors.newVirtualThreadPerTaskExecutor()), HEAD_SRC,
        Clock.systemUTC());
    var components = TestEngineComponents.fourComponents();
    components.handle("api").transition(ComponentState.READY, null, null);
    components.handle("index").transition(ComponentState.READY, null, null);

    KnowledgeServerBootstrap knowledgeServer = mock(KnowledgeServerBootstrap.class);
    KnowledgeClient client = mock(KnowledgeClient.class);
    when(client.getWorkerOperationalView(TestRequestContexts.internal())).thenReturn(workerView());
    BootstrapLeaseFixtures.bind(knowledgeServer, client);
    when(knowledgeServer.hasClient()).thenReturn(true);

    var capability = new VduCapabilityState();
    block(capability, PAUSED, DETAIL);

    StatusLifecycleHandler handler = newHandler(indexBase, components);
    handler.setKnowledgeServer(knowledgeServer, null);
    handler.setVduCapabilitySnapshotSupplier(capability::snapshot);
    handler.setLifecycleSnapshotTap(tap);

    Optional<HealthEvent> found = Optional.empty();
    long deadline = System.currentTimeMillis() + 5_000L;
    while (System.currentTimeMillis() < deadline) {
      handler.sampleAndBuildStatusSnapshot();
      found = conditions.find("vdu.unavailable", "vdu");
      if (found.isPresent()
          && ((AssertedCondition) found.get().body()).message().isPresent()) {
        break;
      }
      Thread.sleep(20L);
    }
    assertTrue(found.isPresent(), "the pause asserts the vdu.unavailable condition");
    AssertedCondition condition = (AssertedCondition) found.get().body();
    assertEquals(Optional.of(DETAIL), condition.message(),
        "the condition's message says why picture reading is paused and when it tries next");
  }

  /** {@code block(code, detail)} where it exists; the older one-argument form otherwise. */
  private static void block(VduCapabilityState state, String code, String detail) throws Exception {
    try {
      VduCapabilityState.class.getMethod("block", String.class, String.class)
          .invoke(state, code, detail);
    } catch (NoSuchMethodException beforeDetail) {
      state.block(code);
    }
  }

  private static StatusLifecycleHandler newHandler(Path indexBase, TestEngineComponents components) {
    var capabilities = CapabilityGraph.fromRegistry(components);
    var handler = new StatusLifecycleHandler(
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
        capabilities.worker(),
        capabilities.inference());
    handler.setIndexComponent(components, components.handle("index"));
    return handler;
  }

  private static WorkerOperationalView workerView() {
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
        .visualExtraction(new VisualExtractionView(true, true, "tesseract", null, 0L, 4L, null, false))
        .embeddingReady(Boolean.TRUE)
        .build();
  }
}
