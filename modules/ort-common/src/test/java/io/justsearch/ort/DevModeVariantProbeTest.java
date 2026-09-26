package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DevModeVariantProbe}. Tempdoc 397 §14.26 T2-A1.
 *
 * <p>Covers the four probe cases (missing dir, CPU-only file, CUDA-only file, both present on
 * CUDA hardware) the plan named, plus the {@code .optimized} sidecar fallback that the extracted
 * {@code KnowledgeServer.resolveVariant} code already supports.
 */
@DisplayName("DevModeVariantProbe")
class DevModeVariantProbeTest {

  @Test
  void missingDirReturnsNull() {
    assertNull(DevModeVariantProbe.probe(Path.of("does/not/exist"), /* gpuEnabled= */ true));
    assertNull(DevModeVariantProbe.probe(null, /* gpuEnabled= */ false));
  }

  @Test
  void emptyDirReturnsNull(@TempDir Path modelDir) {
    assertNull(DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ true));
  }

  @Test
  void cpuOnlyFileCpuDisabledReturnsCpuVariant(@TempDir Path modelDir) throws IOException {
    Files.createFile(modelDir.resolve("model.onnx"));

    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ false);
    assertNotNull(variant);
    assertEquals(modelDir.resolve("model.onnx"), variant.modelFile());
    assertEquals(ExecutionProvider.CPU, variant.executionProvider());
    assertEquals(ModelPrecision.FP32, variant.precision());
    assertFalse(variant.degraded(), "CPU file on CPU is the intended pairing — not degraded");
  }

  @Test
  void cpuOnlyFileWithGpuEnabledReturnsCudaVariantUsingCpuFile(@TempDir Path modelDir)
      throws IOException {
    // Production pattern: CPU model file present, GPU enabled → probe returns CUDA EP using the
    // CPU file. NativeSessionHandle will attempt a GPU session from it and retry to CPU on
    // failure.
    Files.createFile(modelDir.resolve("model.onnx"));

    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ true);
    assertNotNull(variant);
    assertEquals(modelDir.resolve("model.onnx"), variant.modelFile());
    assertEquals(ExecutionProvider.CUDA, variant.executionProvider());
    // Tempdoc 691 B-5: CPU-variant file on CUDA must be reported as degraded (the CPU variant
    // may be INT8-quantized, ~10× per-call on the CUDA EP) — mirroring VariantSelector's
    // contract-path branch. Reporting it as optimal hid the NER regression.
    assertTrue(variant.degraded(), "CPU-variant file on CUDA must be a degraded selection");
    assertNotNull(variant.degradationReason());
    assertTrue(
        variant.degradationReason().contains("model_fp16.onnx"),
        "reason should name the missing GPU variant file");
  }

  @Test
  void bothFilesPresentGpuEnabledPrefersGpuFile(@TempDir Path modelDir) throws IOException {
    Files.createFile(modelDir.resolve("model.onnx"));
    Files.createFile(modelDir.resolve("model_fp16.onnx"));

    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ true);
    assertNotNull(variant);
    assertEquals(modelDir.resolve("model_fp16.onnx"), variant.modelFile());
    assertEquals(ExecutionProvider.CUDA, variant.executionProvider());
    assertEquals(ModelPrecision.FP16, variant.precision());
    assertFalse(variant.degraded(), "GPU file on CUDA is the intended pairing — not degraded");
  }

  @Test
  void exactGenerationFileDoesNotSelectPresentGpuSibling(@TempDir Path modelDir)
      throws IOException {
    Path cpu = Files.createFile(modelDir.resolve("model.onnx"));
    Files.createFile(modelDir.resolve("model_fp16.onnx"));

    VariantSelection selected = DevModeVariantProbe.probeExact(cpu, true);
    assertNotNull(selected);
    assertEquals(cpu, selected.modelFile());
    assertEquals(ExecutionProvider.CUDA, selected.executionProvider());
    assertTrue(selected.degraded());
    Files.delete(cpu);
    assertNull(DevModeVariantProbe.probeExact(cpu, true));
  }

  @Test
  void exactGenerationFileMustBeDeclaredByItsOwnManifest(@TempDir Path modelDir)
      throws IOException {
    Files.writeString(modelDir.resolve("model_manifest.json"),
        "{\"cpu\":\"model.onnx\",\"gpu\":\"model_fp16.onnx\"}");
    Path unlisted = Files.createFile(modelDir.resolve("other.onnx"));

    assertNull(DevModeVariantProbe.probeExact(unlisted, false));
    assertNull(DevModeVariantProbe.probeExact(unlisted, true));
  }

  @Test
  void optimizedSidecarAcceptedInPlaceOfBareFile(@TempDir Path modelDir) throws IOException {
    // ORT graph-optimisation cache can exist without the original when a build was incremental.
    Files.createFile(modelDir.resolve("model.onnx.optimized"));

    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ false);
    assertNotNull(variant);
    assertEquals(ExecutionProvider.CPU, variant.executionProvider());
  }
}
