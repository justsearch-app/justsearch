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
  // M2: an unacquirable serving model set is apart from warm and from no models; readiness bounded
  void unavailableServingModelsAreReportedApartFromWarmAndFromNoModels() {
    EmbeddingService embeddingService = mock(EmbeddingService.class);
    when(embeddingService.isAvailable()).thenReturn(true);
    WorkerHealthService warm = new WorkerHealthService("1.0.0", null, null, embeddingService);
    WorkerHealthService noModels = new WorkerHealthService("1.0.0", null, null, null);
    WorkerHealthService unavailable = new WorkerHealthService("1.0.0", null, null, embeddingService);
    unavailable.queryReadiness().startBound(java.time.Duration.ofDays(1));
    assertEquals(null, unavailable.queryReadiness().servingModelsUnavailable("Encoder set is retiring"));

    HealthCheckResponse warmResponse = warm.check(HealthCheckRequest.getDefaultInstance());
    HealthCheckResponse noModelsResponse = noModels.check(HealthCheckRequest.getDefaultInstance());
    HealthCheckResponse response = unavailable.check(HealthCheckRequest.getDefaultInstance());

    assertFalse(warmResponse.getQueryEncodersCold());
    assertFalse(warmResponse.getVersion().contains("Query models unavailable"));
    assertFalse(noModelsResponse.getAiReady());
    assertFalse(noModelsResponse.getQueryEncodersCold());
    assertFalse(noModelsResponse.getVersion().contains("Query models unavailable"));
    assertTrue(response.getServing(), "lexical search keeps serving");
    assertTrue(response.getQueryEncodersCold(), "not warm");
    assertTrue(response.getVersion().contains("Query models unavailable: Encoder set is retiring"),
        response::getVersion);
    assertFalse(response.getEmbeddingReady(), "not ready before the readiness bound");

    // The existing bound still releases readiness.
    WorkerHealthService bounded = new WorkerHealthService("1.0.0", null, null, embeddingService);
    bounded.queryReadiness().startBound(java.time.Duration.ZERO);
    bounded.queryReadiness().servingModelsUnavailable("Encoder set is retiring");
    assertTrue(bounded.check(HealthCheckRequest.getDefaultInstance()).getEmbeddingReady(),
        "the readiness bound is not extended");

    assertEquals("Encoder set is retiring", unavailable.queryReadiness().servingModelsUnavailable(null));
    assertFalse(unavailable.check(HealthCheckRequest.getDefaultInstance()).getVersion()
        .contains("Query models unavailable"), "cleared once the set can be acquired");
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
