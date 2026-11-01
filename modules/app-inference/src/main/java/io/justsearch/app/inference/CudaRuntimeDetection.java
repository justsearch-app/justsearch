/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.configuration.NativeTargets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Heuristic detection of missing CUDA runtime DLLs for GPU-accelerated llama-server.
 *
 * <p>Scans the server directory and system paths for CUDA Toolkit runtime libraries. Returns a
 * human-readable warning if the runtime is missing, or {@code null} if OK. Extracted from {@link
 * LlamaServerOps} to reduce class size.
 */
final class CudaRuntimeDetection {
  private static final Logger LOG = LoggerFactory.getLogger(CudaRuntimeDetection.class);

  private CudaRuntimeDetection() {}

  /**
   * Checks whether the CUDA runtime is available for a dynamically-linked ggml-cuda.dll.
   *
   * @param serverExecutable path to the llama-server executable
   * @return a warning message if CUDA runtime is missing, or {@code null} if OK
   */
  static String detectCudaRuntimeWarning(Path serverExecutable) {
    return detectCudaRuntimeWarning(serverExecutable, NativeTargets.currentIfSupported().orElse(null));
  }

  static String detectCudaRuntimeWarning(Path serverExecutable, NativeTargets target) {
    if (serverExecutable == null || target == null) {
      return null;
    }
    Path serverDir = serverExecutable.getParent();
    if (serverDir == null || !Files.isDirectory(serverDir)) {
      return null;
    }

    String libraryName = target.libraryName("ggml-cuda");
    Path ggmlCuda = serverDir.resolve(libraryName);
    if (!Files.exists(ggmlCuda)) {
      LOG.warn(
          "GPU mode requested (-ngl > 0) but {} not found in {}. "
              + "GPU acceleration will not work. Install a CUDA-enabled llama-server variant.",
          libraryName, serverDir);
      return null;
    }
    // The size heuristic and Toolkit DLL search describe the Windows distributions only.
    if (!target.targetId().startsWith("windows-")) return null;

    // Check if it's the small dynamically-linked version (~80MB) vs large statically-linked
    // (~400MB+)
    long cudaDllSize;
    try {
      cudaDllSize = Files.size(ggmlCuda);
    } catch (IOException e) {
      LOG.debug("Failed to check ggml-cuda.dll size: {}", e.getMessage());
      return null;
    }

    // If ggml-cuda.dll is small (<200MB), it's dynamically linked and needs CUDA Toolkit
    boolean isDynamicallyLinked = cudaDllSize < 200_000_000L;
    if (!isDynamicallyLinked) {
      // Large statically-linked DLL - should work without CUDA Toolkit
      LOG.debug(
          "Detected statically-linked ggml-cuda.dll ({}MB), CUDA Toolkit not required",
          cudaDllSize / (1024 * 1024));
      return null;
    }

    // Check for CUDA Toolkit runtime DLLs
    boolean hasCudaRuntime = false;
    String[] cudaRuntimePatterns = {"cudart64_", "cudart32_"};
    try (var stream = Files.list(serverDir)) {
      hasCudaRuntime =
          stream.anyMatch(
              p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String pattern : cudaRuntimePatterns) {
                  if (name.startsWith(pattern) && name.endsWith(".dll")) {
                    return true;
                  }
                }
                return false;
              });
    } catch (IOException e) {
      LOG.debug("Failed to scan for CUDA runtime DLLs: {}", e.getMessage());
    }

    // Also check System32 and PATH for CUDA Toolkit installation
    if (!hasCudaRuntime) {
      String systemRoot = System.getenv("SystemRoot");
      if (systemRoot != null) {
        try (var stream = Files.list(Path.of(systemRoot, "System32"))) {
          hasCudaRuntime =
              stream.anyMatch(
                  p -> {
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return name.startsWith("cudart64_") && name.endsWith(".dll");
                  });
        } catch (IOException e) {
          LOG.debug("Failed to scan System32 for CUDA runtime: {}", e.getMessage());
        }
      }
    }

    if (!hasCudaRuntime) {
      String warning =
          "GPU mode requested but CUDA Toolkit runtime not found. "
              + "ggml-cuda.dll ("
              + (cudaDllSize / (1024 * 1024))
              + "MB) requires cudart64_*.dll from NVIDIA CUDA Toolkit. "
              + "Options: (1) Install CUDA Toolkit from nvidia.com, or "
              + "(2) Use a statically-linked llama-server variant (cuda12) that includes CUDA"
              + " runtime. "
              + "Without CUDA runtime, llama-server will fall back to CPU.";
      LOG.warn(warning);
      return warning;
    }
    return null; // CUDA runtime found
  }
}
