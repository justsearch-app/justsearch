/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.resolved.ConfigStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Restores the bundled llama-server runtime into AI Home.
 *
 * <p>Extracted from v1 {@code AiInstallService}. Handles version-aware copying of the
 * llama-server.exe + adjacent DLLs (llama.dll, ggml*.dll, mtmd.dll, etc.) from the bundled
 * distribution to the AI Home directory.
 */
public final class RuntimeRestoreUtil {
  private static final Logger log = LoggerFactory.getLogger(RuntimeRestoreUtil.class);
  private static final String RUNTIME_VERSION_FILE = "runtime-version.txt";

  private RuntimeRestoreUtil() {}

  /**
   * Ensures the llama-server runtime is present in AI Home. Copies from the bundled distribution if
   * missing or outdated.
   *
   * @param homeDir the AI Home directory
   * @return true if the runtime exe exists and is non-empty after restore
   */
  public static boolean ensureRuntimePresent(Path homeDir) {
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      ConfigStore store = ConfigStore.globalOrNull();
      Path external = store == null ? null : store.get().ai().serverExe();
      if (external == null && NativeTargets.current().supportsRuntimePacks()) {
        external = homeDir.resolve("native-bin/llama-server/variants/cuda12")
            .resolve(NativeTargets.current().executableName("llama-server"));
        if (!Files.isRegularFile(external)) return false;
      }
      if (external == null) return false;
      if (!Files.isRegularFile(external) || !Files.isExecutable(external)) {
        throw new IllegalStateException("JUSTSEARCH_SERVER_EXE must name a regular executable file: " + external);
      }
      return true;
    }
    Path targetDir = homeDir.resolve("native-bin").resolve("llama-server");
    Path targetExe = targetDir.resolve(NativeTargets.current().executableName("llama-server"));

    try {
      Files.createDirectories(targetDir);
    } catch (IOException e) {
      log.debug("Failed to create runtime directory {} (best-effort)", targetDir, e);
    }

    Path sourceDir = resolveBundledRuntimeDir();
    boolean forceUpdate = false;
    if (sourceDir != null && Files.isDirectory(sourceDir)) {
      String bundled = readTextBestEffort(sourceDir.resolve(RUNTIME_VERSION_FILE));
      String installed = readTextBestEffort(targetDir.resolve(RUNTIME_VERSION_FILE));
      if (bundled != null && !bundled.isBlank()) {
        bundled = bundled.trim();
        installed = installed == null ? "" : installed.trim();
        forceUpdate = !bundled.equals(installed);
      }
      copyMissingRuntimeFilesBestEffort(sourceDir, targetDir, forceUpdate);
    }

    return Files.exists(targetExe) && sizeBestEffort(targetExe) > 0;
  }

  static Path resolveBundledRuntimeDir() {
    Path headlessDir = null;
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path fromProp = cs != null ? cs.get().paths().repoRoot() : null;
      if (fromProp != null && Files.isDirectory(fromProp)) {
        headlessDir = fromProp;
      }
    } catch (Exception e) {
      log.debug("Failed to resolve bundled runtime dir from ConfigStore (best-effort)", e);
    }
    if (headlessDir == null) {
      headlessDir = Path.of(System.getProperty("user.dir"));
    }
    return headlessDir.resolve("native-bin").resolve("llama-server");
  }

  private static void copyMissingRuntimeFilesBestEffort(
      Path sourceDir, Path targetDir, boolean forceUpdate) {
    try (var stream = Files.list(sourceDir)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean isRuntimeMeta =
                    lower.equalsIgnoreCase(RUNTIME_VERSION_FILE) || lower.startsWith("license");
                if (!(lower.endsWith(".exe") || lower.endsWith(".dll") || isRuntimeMeta)) {
                  return;
                }
                Path dest = targetDir.resolve(p.getFileName());
                if (!forceUpdate && Files.exists(dest) && sizeBestEffort(dest) > 0) {
                  return;
                }
                try {
                  Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception e) {
                  log.warn("Failed to restore runtime file {} -> {}", p, dest, e);
                }
              });
    } catch (Exception e) {
      log.warn("Failed to scan bundled runtime dir {}", sourceDir, e);
    }
  }

  private static String readTextBestEffort(Path p) {
    try {
      if (p == null || !Files.exists(p)) return null;
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static long sizeBestEffort(Path p) {
    try {
      return Files.exists(p) ? Files.size(p) : 0;
    } catch (Exception e) {
      return 0;
    }
  }
}
