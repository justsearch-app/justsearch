package io.justsearch.ui;

import static org.junit.jupiter.api.Assertions.*;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import io.justsearch.ort.OrtCudaHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Run alone with an extracted host-only pack; uses the Engine's GPU ORT dependency. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "JUSTSEARCH_LINUX_GPU_PACK_DIR", matches = ".+")
class LinuxOrtAfterCpuTest {
  @TempDir Path work;

  @Test void cpuSessionThenCudaKernelUsesOnlyPackDependencies() throws Exception {
    Path pack = Path.of(System.getenv("JUSTSEARCH_LINUX_GPU_PACK_DIR")).toRealPath();
    // Relu, shape [2,3], float32. Both sessions execute the same graph.
    byte[] model = Base64.getDecoder().decode("CAk6ZRIIdmVyaWZpZXIKFQoFaW5wdXQSBm91dHB1dCIEUmVsdVoXCgVpbnB1dBIOCgwIARIICgIIAgoCCANiGAoGb3V0cHV0Eg4KDAgBEggKAggCCgIIA4IBDgoFc2NvcGUSBWdyYXBoQgIQEXIOCgVzY29wZRIFbW9kZWw=");
    var environment = OrtEnvironment.getEnvironment();
    try (var options = new OrtSession.SessionOptions();
        var session = environment.createSession(model, options)) {
      execute(environment, session);
    }
    assertFalse(Files.readString(Path.of("/proc/self/maps")).contains("libcublas"),
        "Run this test alone in a fresh test JVM so CPU precedes CUDA");
    // ORT already initialized: loading dependencies must work without changing native.path.
    String nativePath = System.getProperty("onnxruntime.native.path");
    OrtCudaHelper.prepareCudaDependencies(pack);
    assertEquals(nativePath, System.getProperty("onnxruntime.native.path"));
    try (var options = new OrtSession.SessionOptions()) {
      options.addCUDA(0);
      options.enableProfiling(work.resolve("cuda-profile").toString());
      try (var session = environment.createSession(model, options)) {
        execute(environment, session);
        String profile = Files.readString(Path.of(session.endProfiling()));
        assertTrue(Pattern.compile("\"provider\"\\s*:\\s*\"CUDAExecutionProvider\"").matcher(profile).find(),
            "The graph must execute a CUDA kernel: " + profile);
      }
    }
    int loaded = 0;
    for (String line : Files.readAllLines(Path.of("/proc/self/maps"))) {
      if (line.matches(".*lib(?:cudart|cublas|cufft|curand|cusparse|cusolver|nvJitLink|nvrtc|cudnn).*")) {
        Path library = Path.of(line.substring(line.indexOf('/')).trim()).toRealPath();
        assertTrue(library.startsWith(pack), "CUDA library outside pack: " + library);
        loaded++;
      }
    }
    assertTrue(loaded > 0, "No CUDA libraries mapped");
  }

  private static void execute(OrtEnvironment environment, OrtSession session) throws Exception {
    try (var input = OnnxTensor.createTensor(environment, new float[][] {{-1, 2, -3}, {4, -5, 6}});
        var output = session.run(Map.of("input", input))) {
      float[][] actual = (float[][]) output.get(0).getValue();
      assertArrayEquals(new float[] {0, 2, 0}, actual[0]);
      assertArrayEquals(new float[] {4, 0, 6}, actual[1]);
    }
  }
}
