package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import io.justsearch.ort.OrtCudaHelper;
import io.justsearch.configuration.NativeTargets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpladeEncoderNativePathTest {

  @TempDir Path tempDir;

  @Test
  void resolveOrtNativePath_prefersSystemPropertyWhenDirectoryExists() throws Exception {
    Path configured = Files.createDirectories(tempDir.resolve("cuda12"));
    String previous = System.getProperty("onnxruntime.native.path");
    System.setProperty("onnxruntime.native.path", configured.toString());
    try {
      assertEquals(configured.toAbsolutePath().normalize(), OrtCudaHelper.resolveOrtNativePath(tempDir));
    } finally {
      restoreProperty(previous);
    }
  }

  @Test
  void resolveOrtNativePath_fallsBackWhenConfiguredDirectoryMissing() {
    String previous = System.getProperty("onnxruntime.native.path");
    System.setProperty("onnxruntime.native.path", tempDir.resolve("missing").toString());
    try {
      assertEquals(tempDir, OrtCudaHelper.resolveOrtNativePath(tempDir));
    } finally {
      restoreProperty(previous);
    }
  }

  @Test
  void candidateCudaDependencyDlls_returnsOnlyExistingDllsInStableOrder() throws Exception {
    Path configured = Files.createDirectories(tempDir.resolve("cuda12"));
    Files.createFile(configured.resolve(NativeTargets.current().libraryName("cudnn")));
    Files.createFile(configured.resolve(NativeTargets.current().libraryName("cudart")));
    Files.createFile(configured.resolve(NativeTargets.current().libraryName("cublasLt")));

    assertIterableEquals(
        List.of(
            configured.resolve(NativeTargets.current().libraryName("cudart")).toAbsolutePath().normalize(),
            configured.resolve(NativeTargets.current().libraryName("cublasLt")).toAbsolutePath().normalize(),
            configured.resolve(NativeTargets.current().libraryName("cudnn")).toAbsolutePath().normalize()),
        OrtCudaHelper.candidateCudaDependencyDlls(configured));
  }

  private static void restoreProperty(String previous) {
    if (previous == null) {
      System.clearProperty("onnxruntime.native.path");
    } else {
      System.setProperty("onnxruntime.native.path", previous);
    }
  }
}
