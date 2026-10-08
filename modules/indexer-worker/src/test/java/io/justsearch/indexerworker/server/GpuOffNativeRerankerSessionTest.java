/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.server;

import static org.junit.jupiter.api.Assertions.*;

import ai.onnxruntime.OnnxTensor;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ResolvedConfigBuilder;
import io.justsearch.ort.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The provider of a created native session, observed from ORT's actual kernel profile. */
final class GpuOffNativeRerankerSessionTest {
  @Test
  void gpuOffCreatesOnlyCpuRerankerSessions(@TempDir Path dir) throws Exception {
    // A float identity graph, one input/output of shape [4], opset 18.
    Path model = dir.resolve("model.onnx");
    Files.write(model, java.util.Base64.getDecoder().decode(
        "CA0SHWp1c3RzZWFyY2gtY2FwYWJpbGl0eS1maXh0dXJlOmoKIwoFaW5wdXQSBm91dHB1dBoIaWRlbnRpdHkiCElkZW50aXR5EhhjYXBhYmlsaXR5X2ZpeHR1cmVfZ3JhcGhaEwoFaW5wdXQSCgoICAESBAoCCARiFAoGb3V0cHV0EgoKCAgBEgQKAggEQgIQEnIhChppby5qdXN0c2VhcmNoLnBvb2xpbmdfbW9kZRIDY2xzciMKHGlvLmp1c3RzZWFyY2guY29udGV4dF9sZW5ndGgSAzUxMnImCiFpby5qdXN0c2VhcmNoLmVtYmVkZGluZ19kaW1lbnNpb24SATRyIwobaW8uanVzdHNlYXJjaC5jcHVfcHJlY2lzaW9uEgRmcDMyciMKG2lvLmp1c3RzZWFyY2guZ3B1X3ByZWNpc2lvbhIEZnAxNnIyCh1pby5qdXN0c2VhcmNoLmRvY3VtZW50X3ByZWZpeBIRc2VhcmNoX2RvY3VtZW50OiByLAoaaW8uanVzdHNlYXJjaC5xdWVyeV9wcmVmaXgSDnNlYXJjaF9xdWVyeTog"));
    var cfg = new ResolvedConfigBuilder()
        .putDefault("policy.gpu_acceleration_enabled", "false")
        .putDefault("justsearch.rerank.gpu.enabled", "true")
        .putDefault("justsearch.ort.profiling_dir", dir.toString()).build();
    var hardware = HardwareProfile.gpuFull(0);
    var variant = new VariantSelection(model, ModelPrecision.FP32,
        cfg.ai().reranker().gpuEnabled() ? ExecutionProvider.CUDA : ExecutionProvider.CPU,
        false, "native policy probe");
    var policy = ModelSessionPolicyResolver.resolve(EncoderRole.RERANKER, cfg, hardware, variant);
    try (var sessions = OrtSessionAssembler.buildManager("gpu-off-reranker",
        new Composition(RuntimePolicyResolver.resolve(cfg, hardware), policy,
            new ModelArtifacts(model, model)), () -> true);
        var lease = sessions.acquire(SessionAcquisitionRequest.within(
            SessionAcquisitionRequest.Urgency.FOREGROUND, Duration.ofSeconds(30), () -> false));
        var tensor = OnnxTensor.createTensor(sessions.environment(), new float[] {1, 2, 3, 4});
        var result = lease.session().run(Map.of("input", tensor))) {
      String profile = Files.readString(Path.of(lease.session().endProfiling()), StandardCharsets.UTF_8);
      assertAll(
          () -> assertTrue(lease.isCpu(), "effective GPU-off policy must select a native CPU session"),
          () -> assertEquals(ExecutionProvider.CPU, policy.variant().executionProvider(),
              "GPU-off must not configure CUDA and rely on native fallback"),
          () -> assertTrue(profile.contains("CPUExecutionProvider"), profile),
          () -> assertFalse(profile.contains("CUDAExecutionProvider"), profile));
    }
  }
}
