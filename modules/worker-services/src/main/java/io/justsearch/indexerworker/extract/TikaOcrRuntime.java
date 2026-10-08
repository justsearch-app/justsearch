/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import io.justsearch.configuration.EnvRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Best-effort runtime probes for Tika/Tesseract OCR capability. */
public final class TikaOcrRuntime {
  public static final String REASON_DISABLED = "ocr.disabled";
  public static final String REASON_ENGINE_MISSING = "ocr.engine_missing";
  public static final String REASON_LANGUAGE_MISSING = "ocr.language_missing";

  private static final Duration LANG_CACHE_TTL = Duration.ofSeconds(30);
  private static volatile long languageCacheAtMs;
  private static volatile Set<String> languageCache = Set.of();
  private static volatile RuntimePaths languageCacheRuntime;

  private TikaOcrRuntime() {}

  public record RuntimePaths(Path executable, Path executableDirectory, Path tessdataDirectory) {
    public boolean available() {
      return executable != null;
    }
  }

  public static boolean isEngineAvailable() {
    return resolve().available();
  }

  public static RuntimePaths resolve() {
    String configured = EnvRegistry.TESSERACT_PATH.get().orElse(null);
    if (configured != null && !configured.isBlank()) {
      return runtimePaths(executableFromPathLike(configured));
    }
    String legacyEnv = System.getenv("TESSERACT_PATH");
    if (legacyEnv != null && !legacyEnv.isBlank()) {
      return runtimePaths(executableFromPathLike(legacyEnv));
    }

    for (Path root : appOwnedRuntimeRoots()) {
      Path candidate = findExecutableInDirectory(root);
      if (candidate != null) {
        return runtimePaths(candidate);
      }
    }

    Path pathExecutable = findExecutableOnPath();
    return runtimePaths(pathExecutable);
  }

  public static String blockedReason(OcrRoutingConfig config) {
    if (config == null || !config.enabled()) {
      return REASON_DISABLED;
    }
    if (!isEngineAvailable()) {
      return REASON_ENGINE_MISSING;
    }
    RuntimePaths runtime = resolve();
    if (explicitTessdataConfigured() && runtime.tessdataDirectory() == null) {
      return REASON_LANGUAGE_MISSING;
    }
    Set<String> available = availableLanguages(runtime);
    if (available.isEmpty() && !config.languages().isEmpty()) {
      return REASON_LANGUAGE_MISSING;
    }
    for (String language : config.languages()) {
      if (!available.contains(language.toLowerCase(Locale.ROOT))) {
        return REASON_LANGUAGE_MISSING;
      }
    }
    return "";
  }

  static void resetLanguageCacheForTests() {
    languageCacheAtMs = 0L;
    languageCache = Set.of();
    languageCacheRuntime = null;
  }

  private static Set<String> availableLanguages(RuntimePaths runtime) {
    long now = System.currentTimeMillis();
    Set<String> cached = languageCache;
    if (sameRuntime(runtime, languageCacheRuntime) && now - languageCacheAtMs < LANG_CACHE_TTL.toMillis()) {
      return cached;
    }
    if (runtime == null || runtime.executable() == null) {
      languageCache = Set.of();
      languageCacheAtMs = now;
      languageCacheRuntime = runtime;
      return Set.of();
    }
    try {
      ProcessBuilder builder =
          new ProcessBuilder(runtime.executable().toString(), "--list-langs").redirectErrorStream(true);
      if (runtime.tessdataDirectory() != null) {
        builder.environment().put("TESSDATA_PREFIX", runtime.tessdataDirectory().toString());
      }
      Process process = OwnedHelper.start(builder, "Tesseract language availability");
      boolean exited = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
      if (!exited) {
        OwnedHelper.terminate(process, "timeout");
        return cached;
      }
      String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      Set<String> languages = new HashSet<>();
      for (String line : output.split("\\R")) {
        String value = line.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank() || value.startsWith("list of available")) {
          continue;
        }
        languages.add(value);
      }
      languageCache = Set.copyOf(languages);
      languageCacheAtMs = now;
      languageCacheRuntime = runtime;
      return languageCache;
    } catch (Exception e) {
      languageCacheAtMs = now;
      languageCacheRuntime = runtime;
      return cached;
    }
  }

  private static RuntimePaths runtimePaths(Path executable) {
    if (executable == null) {
      return new RuntimePaths(null, null, null);
    }
    Path normalized = executable.toAbsolutePath().normalize();
    Path executableDirectory = normalized.getParent();
    return new RuntimePaths(normalized, executableDirectory, findTessdataDirectory(executableDirectory));
  }

  private static List<Path> appOwnedRuntimeRoots() {
    List<Path> roots = new ArrayList<>();
    addHomeRoot(roots, EnvRegistry.HOME.get().orElse(null));
    addHomeRoot(roots, EnvRegistry.DATA_DIR.get().orElse(null));
    addBundleRoot(roots, EnvRegistry.REPO_ROOT.get().orElse(null));
    addBundleRoot(roots, System.getProperty("user.dir"));
    addLaunchParentBundleRoot(roots, System.getProperty("user.dir"));
    addAncestorBundleRoots(roots, System.getProperty("user.dir"));
    addHomeRoot(roots, appDataHome());
    addHomeRoot(roots, localAppDataHome());
    addBundleRoot(roots, ".");
    addLaunchParentBundleRoot(roots, ".");
    addAncestorBundleRoots(roots, ".");
    return roots;
  }

  private static void addHomeRoot(List<Path> roots, String home) {
    if (home == null || home.isBlank()) {
      return;
    }
    roots.add(Path.of(home).resolve("native-bin").resolve("tesseract"));
  }

  private static void addBundleRoot(List<Path> roots, String root) {
    if (root == null || root.isBlank()) {
      return;
    }
    roots.add(Path.of(root).resolve("native-bin").resolve("tesseract"));
  }

  private static void addLaunchParentBundleRoot(List<Path> roots, String root) {
    if (root == null || root.isBlank()) {
      return;
    }
    Path parent = Path.of(root).toAbsolutePath().normalize().getParent();
    if (parent != null) {
      roots.add(parent.resolve("native-bin").resolve("tesseract"));
    }
  }

  private static void addAncestorBundleRoots(List<Path> roots, String root) {
    if (root == null || root.isBlank()) {
      return;
    }
    Path current = Path.of(root).toAbsolutePath().normalize();
    for (int depth = 0; depth < 6 && current != null; depth++) {
      roots.add(current.resolve("native-bin").resolve("tesseract"));
      current = current.getParent();
    }
  }

  private static String appDataHome() {
    String appData = System.getenv("APPDATA");
    return appData == null || appData.isBlank() ? null : Path.of(appData, "io.justsearch.shell").toString();
  }

  private static String localAppDataHome() {
    String localAppData = System.getenv("LOCALAPPDATA");
    return localAppData == null || localAppData.isBlank() ? null : Path.of(localAppData, "JustSearch").toString();
  }

  private static Path findExecutableOnPath() {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return null;
    }
    for (String dir : path.split(File.pathSeparator)) {
      if (dir == null || dir.isBlank()) {
        continue;
      }
      Path candidate = findExecutableInDirectory(Path.of(dir));
      if (candidate != null) {
        return candidate;
      }
    }
    return null;
  }

  private static Path executableFromPathLike(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    Path candidate = Path.of(path);
    if (Files.isDirectory(candidate)) {
      return findExecutableInDirectory(candidate);
    }
    if (isExecutable(candidate)) {
      return candidate;
    }
    return null;
  }

  private static Path findExecutableInDirectory(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) {
      return null;
    }
    for (String name : executableNames()) {
      Path candidate = dir.resolve(name);
      if (isExecutable(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static List<String> executableNames() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
        ? List.of("tesseract.exe", "tesseract.bat", "tesseract.cmd", "tesseract")
        : List.of("tesseract");
  }

  private static Path findTessdataDirectory(Path executableDirectory) {
    String explicitPath = EnvRegistry.TESSDATA_PATH.get().orElse(null);
    if (explicitPath != null && !explicitPath.isBlank()) {
      return directoryFromPathLike(explicitPath);
    }
    if (executableDirectory == null) {
      return directoryFromPathLike(System.getenv("TESSDATA_PREFIX"));
    }
    for (Path candidate :
        List.of(
            executableDirectory.resolve("tessdata"),
            executableDirectory.getParent() == null ? executableDirectory.resolve("tessdata") : executableDirectory.getParent().resolve("tessdata"),
            executableDirectory.resolve("share").resolve("tessdata"))) {
      if (Files.isDirectory(candidate)) {
        return candidate.toAbsolutePath().normalize();
      }
    }
    return directoryFromPathLike(System.getenv("TESSDATA_PREFIX"));
  }

  private static boolean explicitTessdataConfigured() {
    String explicitPath = EnvRegistry.TESSDATA_PATH.get().orElse(null);
    return explicitPath != null && !explicitPath.isBlank();
  }

  private static Path directoryFromPathLike(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    Path candidate = Path.of(path);
    return Files.isDirectory(candidate) ? candidate.toAbsolutePath().normalize() : null;
  }

  private static boolean isExecutable(Path path) {
    return path != null && Files.isRegularFile(path) && path.toFile().canExecute();
  }

  private static boolean sameRuntime(RuntimePaths left, RuntimePaths right) {
    if (left == right) {
      return true;
    }
    if (left == null || right == null) {
      return false;
    }
    return Objects.equals(left.executable(), right.executable())
        && Objects.equals(left.tessdataDirectory(), right.tessdataDirectory());
  }
}
