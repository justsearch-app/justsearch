/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.javalin.Javalin;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.gpu.NvmlService;
import io.justsearch.gpu.VramDetector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Task b6d536 b101, preservation: {@code nvidiaSmiAvailable} on /api/inference/status says whether
 * the nvidia-smi tool is available, from its detector, whatever supplied the GPU memory figures.
 * With NVML answering, a missing nvidia-smi reads false and an installed one true, as on b599d4d7e.
 */
final class NvidiaSmiAvailabilityHttpTest {
  private static JsonNode inferenceGpu(VramDetector detector) throws Exception {
    NvmlService nvml = mock(NvmlService.class);
    when(nvml.probe()).thenReturn(new GpuCapabilities.Nvml(true, null, "test-NVML", null,
        "591.59", 591, 59, 1, 12L << 30, 10L << 30, 2L << 30, null, null));
    OnlineAiService online = mock(OnlineAiService.class);
    when(online.getCurrentMode()).thenReturn("offline");
    var handler = new InferenceHandlers(online, null, new GpuCapabilitiesService(nvml, detector),
        null, null, null, null, null, () -> null);
    var app = Javalin.create();
    app.get("/api/inference/status", handler::handleInferenceStatus);
    app.start("127.0.0.1", 0);
    try (HttpClient client = HttpClient.newHttpClient()) {
      var response = client.send(HttpRequest.newBuilder(URI.create(
              "http://127.0.0.1:" + app.port() + "/api/inference/status"))
          .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode(), response.body());
      JsonNode gpu = JsonMapper.builder().build().readTree(response.body()).path("gpu");
      assertTrue(gpu.path("nvmlAvailable").asBoolean(), response.body());
      assertEquals("nvml", gpu.path("vramDetectionSource").asString(), "NVML supplies the memory: " + response.body());
      return gpu;
    } finally {
      app.stop();
    }
  }

  @Test void nvmlMemoryDoesNotMakeAMissingNvidiaSmiAvailable() throws Exception {
    // The detector of a host without nvidia-smi: no reading, no figures.
    VramDetector missing = mock(VramDetector.class);
    when(missing.getTotalVramBytes()).thenReturn(-1L);
    when(missing.getAvailableVramBytes()).thenReturn(-1L);
    when(missing.getDriverVersion()).thenReturn(null);
    when(missing.getVramDescription()).thenReturn("Unknown (nvidia-smi not available)");
    JsonNode gpu = inferenceGpu(missing);
    assertFalse(gpu.path("nvidiaSmiAvailable").asBoolean(),
        "NVML's figures do not make the missing nvidia-smi available: " + gpu);
  }

  @Test void anInstalledNvidiaSmiIsAvailableWhenNvmlSuppliesTheMemory() throws Exception {
    String path = System.getenv("PATH");
    boolean installed = path != null && java.util.Arrays.stream(path.split(java.io.File.pathSeparator))
        .filter(directory -> !directory.isBlank()).anyMatch(directory -> {
          try {
            return Files.isRegularFile(Path.of(directory.strip()).resolve("nvidia-smi.exe"));
          } catch (RuntimeException invalid) {
            return false;
          }
        });
    Assumptions.assumeTrue(installed, "nvidia-smi is not installed on PATH");
    JsonNode gpu = inferenceGpu(new VramDetector());
    assertTrue(gpu.path("nvidiaSmiAvailable").asBoolean(), "the installed tool is available: " + gpu);
  }
}
