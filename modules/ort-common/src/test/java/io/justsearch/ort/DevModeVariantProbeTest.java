package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.ModelPrecision;
import io.justsearch.configuration.model.VariantSelection;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link DevModeVariantProbe}. Tempdoc 397 §14.26 T2-A1.
 *
 * <p>Covers the four probe cases (missing dir, CPU-only file, CUDA-only file, both present on
 * CUDA hardware), plus rejection of legacy optimized siblings without a source content identity.
 */
@DisplayName("DevModeVariantProbe")
class DevModeVariantProbeTest {

  @TempDir Path cacheRoot;
  private ConfigStore previousStore;

  @BeforeEach
  void isolateOptimizedStore() {
    previousStore = ConfigStore.globalOrNull();
    ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(Map.of(
        "justsearch.ort.optimized_cache_dir", cacheRoot.toString()))));
  }

  @AfterEach
  void restoreConfig() {
    TestResolvedConfigHelper.restoreGlobal(previousStore);
  }

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
  void missingSourceProbesNeverReachStoreConfigurationOrNativeInitialization(@TempDir Path modelDir)
      throws IOException {
    var configurations = new AtomicInteger();
    // configured() is the existing boundary that queries OnnxSessionCache.ortVersion() and
    // initializes native ORT. Fail there to model an unavailable native library, with no native
    // initialization in this test JVM. A filesystem-only probe must never cross the boundary.
    try (var store = mockStatic(OrtOptimizedModelStore.class)) {
      store.when(OrtOptimizedModelStore::configured).thenAnswer(call -> {
        configurations.incrementAndGet();
        throw new UnsatisfiedLinkError("Native ORT must not initialize during discovery");
      });
      assertNull(DevModeVariantProbe.probe(null, false));
      assertNull(DevModeVariantProbe.probe(modelDir.resolve("missing-dir"), true));
      assertNull(DevModeVariantProbe.probe(modelDir, false));
      assertNull(DevModeVariantProbe.probe(modelDir, true));
      assertNull(DevModeVariantProbe.probeExact(modelDir.resolve("absent.onnx"), true));
      assertNull(DevModeVariantProbe.probeExact(modelDir.resolve("absent.onnx"), ModelPrecision.FP32,
          ExecutionProvider.CPU, false));
      Files.createFile(modelDir.resolve("model.onnx.optimized"));
      assertNull(DevModeVariantProbe.probe(modelDir, false));
      Path cpu = Files.createFile(modelDir.resolve("model.onnx"));
      var selected = DevModeVariantProbe.probe(modelDir, true);
      assertNotNull(selected);
      assertEquals(cpu, selected.modelFile());
      assertTrue(selected.degraded());
      Files.delete(cpu);
      Path gpu = Files.createFile(modelDir.resolve("model_fp16.onnx"));
      var gpuSelected = DevModeVariantProbe.probe(modelDir, true);
      assertNotNull(gpuSelected);
      assertEquals(gpu, gpuSelected.modelFile());
      assertEquals(ExecutionProvider.CUDA, gpuSelected.executionProvider());
      assertNull(DevModeVariantProbe.probe(modelDir, false));
      assertEquals(0, configurations.get());
      store.verifyNoInteractions();
    }
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
  void witnessedDescriptorIgnoresChangedManifestAndKeepsExactFile(@TempDir Path modelDir)
      throws IOException {
    Path selectedFile = Files.createFile(modelDir.resolve("selected.onnx"));
    Files.createFile(modelDir.resolve("sibling.onnx"));
    Files.writeString(modelDir.resolve("model_manifest.json"),
        "{\"cpu\":\"sibling.onnx\",\"capabilities\":{\"cpu_precision\":\"fp32\"}}");

    assertNull(DevModeVariantProbe.probeExact(selectedFile, false));
    var selected = DevModeVariantProbe.probeExact(selectedFile, ModelPrecision.INT8,
        ExecutionProvider.CPU, false);
    assertNotNull(selected);
    assertEquals(selectedFile, selected.modelFile());
    assertEquals(ModelPrecision.INT8, selected.precision());
    assertEquals(ExecutionProvider.CPU, selected.executionProvider());
    assertNull(DevModeVariantProbe.probeExact(selectedFile, ModelPrecision.FP16,
        ExecutionProvider.CUDA, false));
  }

  @Test
  void legacyOptimizedSiblingCannotReplaceMissingSource(@TempDir Path modelDir) throws IOException {
    Files.createFile(modelDir.resolve("model.onnx.optimized"));

    VariantSelection variant = DevModeVariantProbe.probe(modelDir, /* gpuEnabled= */ false);
    assertNull(variant);
  }
}
