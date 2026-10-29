package io.justsearch.ort;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 374 alpha.21 Bug Q regression coverage for the
 * {@link OrtCudaHelper#checkMissingCudaDlls(Path)} vs
 * {@link OrtCudaHelper#checkMissingCudaRuntimeDlls(Path)} distinction.
 *
 * <p>Round-11 evidence: {@code NativeSessionHandle.tryCreateGpuSession} called
 * {@code checkMissingCudaDlls} which validates BOTH the ORT EP DLLs (auto-extracted
 * from the JAR at runtime, never present in cuda12) AND the CUDA runtime DLLs.
 * Always reported the EP DLLs as "missing" → INFO-spam log line on every encoder
 * boot. The post-fix call site uses {@code checkMissingCudaRuntimeDlls} which only
 * reports problems with the runtime DLLs that we actually expect cuda12 to ship.
 */
@DisplayName("OrtCudaHelper.checkMissingCudaRuntimeDlls (374 alpha.21 Bug Q)")
class OrtCudaHelperTest {

  @TempDir Path tmp;

  /**
   * The alpha.15+ cuda12 bundled layout: CUDA runtime DLLs present, ORT EP DLLs
   * absent (those auto-extract from the JAR at runtime). Pre-alpha.21
   * {@code checkMissingCudaDlls} would have reported the 2 EP DLLs as missing.
   * Post-fix {@code checkMissingCudaRuntimeDlls} returns empty.
   */
  @Test
  @DisplayName("alpha.15+ cuda12 layout (runtime DLLs present, EP DLLs absent) → empty (Bug Q fix)")
  void cuda12Layout_runtimePresent_epAbsent_returnsEmpty() throws IOException {
    Path cuda12 = tmp.resolve("cuda12");
    Files.createDirectories(cuda12);
    // CUDA runtime DLLs (the alpha.15+ cuda-runtime package payload).
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cudart")));
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublas")));
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublasLt")));
    // Deliberately NOT creating onnxruntime_providers_cuda.dll or
    // onnxruntime_providers_shared.dll — those auto-extract from the JAR at runtime.

    List<String> missing = OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12);

    assertTrue(
        missing.isEmpty(),
        "checkMissingCudaRuntimeDlls must return empty for the alpha.15+ cuda12 layout"
            + " (runtime DLLs present, EP DLLs absent). Pre-alpha.21 NativeSessionHandle"
            + " called the wrong helper (checkMissingCudaDlls) and logged INFO spam every"
            + " encoder boot saying the EP DLLs were missing — by-design behaviour reported"
            + " as a problem (374 alpha.21 Bug Q).");
  }

  /**
   * Real failure mode: cuda12 dir is missing one of the CUDA runtime DLLs (e.g.,
   * cuda-runtime package extraction was incomplete). The helper correctly reports
   * the missing runtime DLL — this is when the post-fix WARN log SHOULD fire.
   */
  @Test
  @DisplayName("cuda12 layout missing cudart64_12.dll → reports it (real failure mode)")
  void cuda12Layout_missingRuntimeDll_returnsIt() throws IOException {
    Path cuda12 = tmp.resolve("cuda12-incomplete");
    Files.createDirectories(cuda12);
    // Only 2 of the 3 baseline runtime DLLs.
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublas")));
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublasLt")));
    // cudart64_12.dll deliberately missing.

    List<String> missing = OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12);

    assertFalse(
        missing.isEmpty(),
        "missing runtime DLL must be reported so the warn log fires");
    assertTrue(
        missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("cudart")),
        "the specific missing runtime DLL must appear in the list");
  }

  /**
   * Compare-and-contrast: the pre-fix helper {@code checkMissingCudaDlls} ALWAYS
   * reports the EP DLLs as missing in the cuda12 layout because they're never there.
   * This test pins that the two helpers have different semantics — Bug Q is about
   * using the right one for the right context, not "fixing" the broader helper.
   */
  @Test
  @DisplayName("checkMissingCudaDlls reports EP DLLs missing for cuda12 layout (pre-fix behaviour pinned)")
  void preFixHelper_reportsEpDllsMissing_documentingTheGap() throws IOException {
    Path cuda12 = tmp.resolve("cuda12-prefix");
    Files.createDirectories(cuda12);
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cudart")));
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublas")));
    touch(cuda12.resolve(io.justsearch.configuration.NativeTargets.current().libraryName("cublasLt")));

    List<String> missing = OrtCudaHelper.checkMissingCudaDlls(cuda12);

    assertFalse(
        missing.isEmpty(),
        "checkMissingCudaDlls validates EP DLLs too, which are JAR-bundled — this is the"
            + " pre-fix call site's misleading behaviour. Documented for clarity; the helper"
            + " itself is correct (per its javadoc), the misuse was at NativeSessionHandle.");
    assertTrue(
        missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-cuda"))
            || missing.contains(io.justsearch.configuration.NativeTargets.current().libraryName("ort-shared")),
        "EP DLLs are reported missing");
  }

  private static void touch(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, "stub");
  }
  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void linuxJitLinkIsPreloadedBeforeSparseAndSolver() {
    var order = OrtCudaHelper.cudaDependencyDllOrder();
    assertTrue(order.indexOf("libnvJitLink.so.12") < order.indexOf("libcusparse.so.12"));
    assertTrue(order.indexOf("libnvJitLink.so.12") < order.indexOf("libcusolver.so.11"));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
  void linuxResolvesPackInstalledAfterBootWithoutChangingProcessSources() throws IOException {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    try {
      var boot = io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .putDefault("justsearch.data.dir", tmp.toString()).build();
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(boot));
      Path fallback = Files.createDirectories(tmp.resolve("model"));
      org.junit.jupiter.api.Assertions.assertEquals(fallback, OrtCudaHelper.resolveOrtNativePath(fallback));
      Path pack = Files.createDirectories(tmp.resolve("native-bin/llama-server/variants/cuda12"));
      // W6b precondition: a newly created or partial extraction is not an ORT pack.
      org.junit.jupiter.api.Assertions.assertEquals(fallback, OrtCudaHelper.resolveOrtNativePath(fallback));
      // Independently specified pack inventory, including dlopen-only NVRTC and zlib.
      List<String> required = List.of("libcudart.so.12", "libcublas.so.12", "libcublasLt.so.12",
          "libcufft.so.11", "libnvJitLink.so.12", "libcurand.so.10", "libcusparse.so.12",
          "libcusolver.so.11", "libnvrtc.so.12", "libnvrtc-builtins.so.12.8", "libz.so.1",
          "libcudnn.so.9", "libcudnn_graph.so.9", "libcudnn_heuristic.so.9", "libcudnn_ops.so.9",
          "libcudnn_cnn.so.9", "libcudnn_adv.so.9", "libcudnn_engines_runtime_compiled.so.9",
          "libcudnn_engines_precompiled.so.9");
      for (String name : required) touch(pack.resolve(name));
      Path archive = pack.resolve("ort-cuda12-cudnn9-linux-x86_64.zip");
      try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
        for (String name : required) {
          zip.putNextEntry(new java.util.zip.ZipEntry(name));
          zip.write(Files.readAllBytes(pack.resolve(name)));
          zip.closeEntry();
        }
      }
      try (var zip = new java.util.zip.ZipFile(archive.toFile())) {
        io.justsearch.configuration.model.ArchiveExtractionState.complete(archive, "a".repeat(64),
            io.justsearch.configuration.model.ArchiveExtractionState.entries(zip, pack, archive));
      }
      org.junit.jupiter.api.Assertions.assertEquals(pack, OrtCudaHelper.resolveOrtNativePath(fallback));
      for (String name : required) {
        Files.delete(pack.resolve(name));
        org.junit.jupiter.api.Assertions.assertEquals(fallback, OrtCudaHelper.resolveOrtNativePath(fallback), name);
        touch(pack.resolve(name));
      }
      Files.writeString(pack.resolve("libnvrtc.so.12"), "truncated");
      org.junit.jupiter.api.Assertions.assertEquals(fallback, OrtCudaHelper.resolveOrtNativePath(fallback));
      touch(pack.resolve("libnvrtc.so.12"));
      io.justsearch.configuration.model.ArchiveExtractionState.begin(archive);
      org.junit.jupiter.api.Assertions.assertEquals(fallback, OrtCudaHelper.resolveOrtNativePath(fallback));
      org.junit.jupiter.api.Assertions.assertNull(boot.paths().ortNativePath());
      Path operator = Files.createDirectories(tmp.resolve("operator"));
      var explicit = io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .putDefault("justsearch.data.dir", tmp.toString())
          .putSettings("justsearch.onnxruntime.native_path", operator.toString()).build();
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(explicit));
      org.junit.jupiter.api.Assertions.assertEquals(operator, OrtCudaHelper.resolveOrtNativePath(fallback));
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

}
