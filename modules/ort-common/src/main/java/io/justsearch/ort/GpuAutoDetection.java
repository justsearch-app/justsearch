/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.EnvRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Probes for ORT CUDA DLL availability at startup. Returns a map of config keys to auto-detected
 * values suitable for {@code ResolvedConfigBuilder.contributeAutoDetected()} at ordinal 150.
 *
 * <p>This is a stateless filesystem probe — it does NOT create ORT sessions or load native
 * libraries. It checks for the presence of core CUDA DLLs in known locations.
 *
 * <p>Designed to replace the Gradle-side {@code detectOrtCudaPath()} hack that only works when
 * launched via Gradle tasks. This runs inside the application JVM at startup, so it works
 * regardless of launch method (Gradle, distribution, Tauri, tests).
 */
public final class GpuAutoDetection {

  private static final Logger LOG = LoggerFactory.getLogger(GpuAutoDetection.class);

  /** Relative path under the repo root where ORT CUDA DLLs are conventionally placed. */
  private static final String CUDA_DIR_RELATIVE = "tmp/ort-variant-test/cuda-12.4-v1.24.3";



  private GpuAutoDetection() {}

  /**
   * Probes for ORT CUDA DLL availability.
   *
   * <p>Detection strategy:
   *
   * <ol>
   *   <li>If {@code JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH} is already set (env or sysprop), check
   *       that path for the sentinel DLL. This respects explicit operator configuration.
   *   <li>Otherwise, search conventional locations relative to the repo root (and main repo root
   *       if running from a git worktree).
   * </ol>
   *
   * @param repoRoot the repository root directory (used for conventional path search)
   * @return map of config keys to auto-detected values, or empty map if no CUDA capability found
   */
  public static Map<String, String> probe(Path repoRoot) {
    if (!io.justsearch.configuration.PlatformPaths.isWindows() && !io.justsearch.configuration.PlatformPaths.isLinux()) return Map.of();
    if (NativeTargets.currentIfSupported().isEmpty()) return Map.of();
    Map<String, String> result = new LinkedHashMap<>();

    // Check if ORT native path is already explicitly configured.
    String explicitPath = EnvRegistry.ORT_NATIVE_PATH.get().orElse(null);
    if (explicitPath != null && !explicitPath.isBlank()) {
      Path nativePath = Path.of(explicitPath);
      if (hasSentinelDll(nativePath)) {
        List<String> missing = OrtCudaHelper.checkMissingCudaDlls(nativePath);
        if (missing.isEmpty()) {
          LOG.info("GPU auto-detection: CUDA DLLs found at configured path {}", nativePath);
          result.put("justsearch.gpu.enabled", "true");
          // Don't set onnxruntime.native_path — it's already configured explicitly.
          return result;
        }
        LOG.debug(
            "GPU auto-detection: configured path {} has sentinel but missing DLLs: {}",
            nativePath,
            missing);
      }
      // Windows preserves its self-contained-directory check. Linux operator paths may hold
      // only CUDA dependencies; ORT's JNI/providers still come from the bundled jar.
      if (io.justsearch.configuration.PlatformPaths.isWindows()) return Map.of();
    }

    // Search conventional locations.
    Path detected = searchConventionalPaths(repoRoot);
    if (detected != null) {
      List<String> missing = OrtCudaHelper.checkMissingCudaDlls(detected);
      if (missing.isEmpty()) {
        LOG.info("GPU auto-detection: CUDA DLLs found at {}", detected);
        result.put("justsearch.gpu.enabled", "true");
        result.put("justsearch.onnxruntime.native_path", detected.toString());
        return result;
      }
      LOG.debug(
          "GPU auto-detection: {} has sentinel but missing core DLLs: {}", detected, missing);
    }

    // Fallback: driver-API probe via nvcuda.dll. Tempdoc 374 sandbox round 2
    // finding #4 — the filesystem search above only succeeds when the dev tree
    // has staged the ORT GPU bundle at the conventional path, which is true
    // for dev/CI but not for any production install. The driver-API probe
    // asks the actual question "is at least one CUDA device visible to the
    // installed driver". On success we set gpu.enabled but NOT native_path —
    // ORT auto-extracts its bundled CUDA EP DLLs from onnxruntime_gpu*.jar to
    // a temp dir on first session create, and llama-server's cuda12 variant
    // ships its own runtime DLLs.
    GpuDriverApiProbe.Result driver = GpuDriverApiProbe.probe();
    if (driver.available()) {
      LOG.info(
          "GPU auto-detection: CUDA driver API reports {} device(s); driver version {}",
          driver.deviceCount(),
          driver.driverVersion());
      result.put("justsearch.gpu.enabled", "true");
      // Intentionally do NOT set onnxruntime.native_path here. ORT will
      // resolve its own CUDA EP DLLs from the bundled onnxruntime_gpu jar.
      return result;
    }

    LOG.debug(
        "GPU auto-detection: no CUDA DLLs found via filesystem search; driver-API probe: {}",
        driver.reason());
    return Map.of();
  }

  /**
   * Searches conventional CUDA DLL locations relative to the repo root.
   *
   * <p>Checks:
   *
   * <ol>
   *   <li>{@code <repoRoot>/tmp/ort-variant-test/cuda-12.4-v1.24.3/}
   *   <li>If in a git worktree, the main repo root's equivalent path
   * </ol>
   */
  private static Path searchConventionalPaths(Path repoRoot) {
    if (!io.justsearch.configuration.PlatformPaths.isWindows() || repoRoot == null) return null;

    // Direct check under provided repo root.
    Path candidate = repoRoot.resolve(CUDA_DIR_RELATIVE);
    if (hasSentinelDll(candidate)) {
      return candidate;
    }

    // If this is a git worktree, check the main repo root too.
    // .git is a file in worktrees containing "gitdir: <path>".
    Path dotGit = repoRoot.resolve(".git");
    if (Files.isRegularFile(dotGit)) {
      Path mainRepoRoot = resolveMainRepoRoot(dotGit);
      if (mainRepoRoot != null) {
        candidate = mainRepoRoot.resolve(CUDA_DIR_RELATIVE);
        if (hasSentinelDll(candidate)) {
          return candidate;
        }
      }
    }

    return null;
  }

  /** Resolves the main repo root from a worktree's .git file. */
  private static Path resolveMainRepoRoot(Path dotGitFile) {
    try {
      String content = Files.readString(dotGitFile).trim();
      if (content.startsWith("gitdir:")) {
        Path gitDir = Path.of(content.substring("gitdir:".length()).trim());
        // .git/worktrees/<name> → .git → repo root
        Path mainRoot = gitDir.getParent(); // .git/worktrees
        if (mainRoot != null) mainRoot = mainRoot.getParent(); // .git
        if (mainRoot != null) mainRoot = mainRoot.getParent(); // repo root
        if (mainRoot != null && Files.isDirectory(mainRoot)) {
          return mainRoot;
        }
      }
    } catch (IOException e) {
      LOG.debug("GPU auto-detection: failed to read .git file: {}", e.getMessage());
    }
    return null;
  }

  private static boolean hasSentinelDll(Path dir) {
    return dir != null && Files.isDirectory(dir) && Files.exists(dir.resolve(NativeTargets.current().libraryName("ort-cuda")));
  }

}
