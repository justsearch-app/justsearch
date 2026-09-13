package io.justsearch.indexerworker.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.indexerworker.embed.EmbeddingService;
import io.justsearch.ipc.HealthCheckRequest;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.reranker.WorkerModelDiscovery;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WorkerHealthServiceTest {

  @Test
  void checkReturnsServingWithVersion() {
    WorkerHealthService service = new WorkerHealthService("1.0.0-test");

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getServing());
    assertEquals("1.0.0-test", response.getVersion());
  }

  @Test
  void checkSurfacesOrtVersionInEffectiveConfig() {
    // tempdoc 623 U7: the ORT library version rides the existing effective_config map (no new
    // proto field) so it reaches the Head's /api/debug/state for the benchmark-release projection.
    WorkerHealthService service = new WorkerHealthService("1.0.0-test");

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getEffectiveConfigMap().containsKey("ort.version"));
    assertNotNull(response.getEffectiveConfigMap().get("ort.version"));
  }

  @Test
  void checkHandlesNullVersion() {
    WorkerHealthService service = new WorkerHealthService(null);

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getServing());
    assertEquals("unknown", response.getVersion());
  }

  @Test
  void deepCheckPassesWithNullComponents() {
    // Test that deep checks with null components still return serving
    WorkerHealthService service = new WorkerHealthService("2.0.0", null, null, null);

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getServing());
    assertEquals("2.0.0", response.getVersion());
    assertFalse(response.getAiReady());
    assertFalse(response.getEmbeddingReady());
  }

  @Test
  void deepCheckReportsEmbeddingAndAiReadyWhenEmbeddingServiceIsAvailable() {
    EmbeddingService embeddingService = mock(EmbeddingService.class);
    when(embeddingService.isAvailable()).thenReturn(true);

    WorkerHealthService service = new WorkerHealthService("2.1.0", null, null, embeddingService);

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getServing());
    assertTrue(response.getAiReady());
    assertTrue(response.getEmbeddingReady());
  }

  @Test
  void checkPopulatesOnnxModelsFromDiscovery() {
    var models = List.of(
        new WorkerModelDiscovery.DiscoveredModel("reranker", true, "C:\\models\\reranker", true),
        new WorkerModelDiscovery.DiscoveredModel("citation-scorer", false, null, false));

    WorkerHealthService service = new WorkerHealthService("1.0.0", null, null, null, null, models);

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    var onnxModels = response.getOnnxModelsList();
    assertEquals(2, onnxModels.size());
    assertEquals("reranker", onnxModels.get(0).getModelName());
    assertTrue(onnxModels.get(0).getFound());
    assertEquals("C:\\models\\reranker", onnxModels.get(0).getPath());
    assertTrue(onnxModels.get(0).getAutoDiscovered());
    assertEquals("citation-scorer", onnxModels.get(1).getModelName());
    assertFalse(onnxModels.get(1).getFound());
    assertEquals("", onnxModels.get(1).getPath());
    assertFalse(onnxModels.get(1).getAutoDiscovered());
  }

  @Test
  void checkReturnsEmptyOnnxModelsWhenNoneProvided() {
    WorkerHealthService service = new WorkerHealthService("1.0.0");

    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());

    assertTrue(response.getOnnxModelsList().isEmpty());
  }

  @Test
  void fatalLoopStateIsVisibleWithoutDisablingHealthySearchServing() {
    WorkerHealthService service =
        new WorkerHealthService("1.0.0", null, null, null, () -> "FAILED", List.of());
    HealthCheckResponse response = service.check(HealthCheckRequest.getDefaultInstance());
    assertEquals("FAILED", response.getWorkerState());
    assertTrue(response.getServing(), "fatal ingest must not falsely mark search unavailable");
  }
}
