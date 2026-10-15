/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.app.api.runtime.child.OwnedHelper;

import io.justsearch.configuration.EnvRegistry;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.regex.Pattern;
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

  private static ResolutionInputs recordedInputs;
  private static RuntimePaths recordedRuntime;
  private static long resolutionCount;

  private TikaOcrRuntime() {}

  /** Immutable executable selection; tessdata may be refreshed independently of this selection. */
  public record RuntimePaths(Path executable, Path executableDirectory, Path tessdataDirectory,
      String source, Path launcher, Instant resolvedAt, String unavailableReason) {
    public RuntimePaths(Path executable, Path executableDirectory, Path tessdataDirectory) {
      this(executable, executableDirectory, tessdataDirectory, "configured", null, Instant.now(), "");
    }
    public boolean available() { return executable != null; }
  }

  private record ResolutionInputs(String configured, List<String> appRuntimeState) {}

  public static boolean isEngineAvailable() { return resolve().available(); }

  /** PATH and legacy environment are process inputs, not a fresh search on each launch. */
  public static synchronized RuntimePaths resolve() {
    String configured = EnvRegistry.TESSERACT_PATH.get().orElse(null);
    List<Path> roots = appOwnedRuntimeRoots();
    ResolutionInputs inputs = new ResolutionInputs(configured, appRuntimeState(roots));
    if (!inputs.equals(recordedInputs)) {
      recordedRuntime = discover(configured, System.getenv("TESSERACT_PATH"), roots, System.getenv("PATH"));
      recordedInputs = inputs;
      resolutionCount++;
    }
    Path tessdata = recordedRuntime.available()
        ? findTessdataDirectory(recordedRuntime.executableDirectory()) : null;
    if (Objects.equals(tessdata, recordedRuntime.tessdataDirectory())) return recordedRuntime;
    return new RuntimePaths(recordedRuntime.executable(), recordedRuntime.executableDirectory(), tessdata,
        recordedRuntime.source(), recordedRuntime.launcher(), recordedRuntime.resolvedAt(), recordedRuntime.unavailableReason());
  }

  private static List<String> appRuntimeState(List<Path> roots) {
    List<String> state = new ArrayList<>();
    for (Path root : roots.stream().distinct().toList()) {
      state.add(root.toAbsolutePath().normalize().toString());
      state.add(fileState(root));
      if (Files.isDirectory(root)) {
        try (var files = Files.list(root)) {
          files.filter(file -> {
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            return name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib");
          }).sorted().forEach(file -> state.add(fileState(file)));
        } catch (java.io.IOException e) { state.add("runtime libraries unreadable"); }
      }
      for (String name : executableNames()) {
        Path candidate = root.resolve(name);
        state.add(fileState(candidate));
        state.add(fileState(shimPath(candidate)));
      }
    }
    return List.copyOf(state);
  }

  private static String fileState(Path path) {
    try {
      BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class);
      // Log/temporary files in the runtime directory are not executable-resolution inputs.
      if (a.isDirectory()) return path.toRealPath() + ":" + a.fileKey();
      return path.toRealPath() + ":" + a.fileKey() + ":" + a.size() + ":" + a.lastModifiedTime();
    } catch (java.io.IOException e) { return "missing"; }
  }

  private static RuntimePaths discover(String configured, String legacy, List<Path> roots, String path) {
    if (configured != null && !configured.isBlank()) return runtimePaths(executableFromPathLike(configured), "configured");
    if (legacy != null && !legacy.isBlank()) return runtimePaths(executableFromPathLike(legacy), "legacy variable");
    for (Path root : roots) {
      Path candidate = findExecutableInDirectory(root);
      if (candidate != null) return runtimePaths(candidate, "app-owned");
    }
    return runtimePaths(findExecutableOnPath(path), "PATH");
  }

  /** Existing free-text engine status carries support diagnostics without a wire-schema change. */
  public static String engineStatus() {
    RuntimePaths runtime = resolve();
    return OcrRoutingConfig.ENGINE + " [" + runtime.source() + "] "
        + (runtime.available() ? runtime.executable() : runtime.unavailableReason());
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

  static synchronized void resetLanguageCacheForTests() {
    recordedInputs = null;
    recordedRuntime = null;
    resolutionCount = 0;
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

  private static RuntimePaths runtimePaths(Path found, String source) {
    Instant at = Instant.now();
    if (found == null) return new RuntimePaths(null, null, null, source, null, at, "Tesseract executable not found");
    Path launcher = found.toAbsolutePath().normalize();
    try {
      Path executable = unwrapLauncher(launcher, new HashSet<>());
      checkExecutable(executable);
      Path directory = executable.getParent();
      return new RuntimePaths(executable, directory, findTessdataDirectory(directory), source,
          launcher.equals(executable) ? null : launcher, at, "");
    } catch (Exception e) {
      return new RuntimePaths(null, null, null, source, launcher, at,
          "Cannot use Tesseract candidate " + launcher + ": " + e.getMessage());
    }
  }

  private static void checkExecutable(Path executable) throws java.io.IOException {
    Process process = null;
    // A real launch, once per recorded resolution: its window is written only when it spawns, its
    // start forced before the helper's receipts, its end with them as one group.
    try (var intent = OwnedHelper.launchWindow(executable.getFileName().toString(), "Tesseract recorded availability",
        Duration.ofSeconds(5))) {
      process = OwnedHelper.start(new ProcessBuilder(executable.toString(), "--version")
          .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD),
          "Tesseract recorded availability");
      if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
        OwnedHelper.terminate(process, "timeout");
        throw new java.io.IOException("executable availability check timed out");
      }
      if (process.exitValue() != 0) throw new java.io.IOException("executable availability check exited " + process.exitValue());
    } catch (InterruptedException e) {
      if (process != null) OwnedHelper.terminate(process, "interrupted");
      Thread.currentThread().interrupt();
      throw new java.io.IOException("executable availability check interrupted", e);
    }
  }

  private static Path shimPath(Path executable) {
    String name = executable.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return executable.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".shim");
  }

  private static Path unwrapLauncher(Path candidate, Set<Path> visited) throws java.io.IOException {
    Path executable = candidate.toRealPath();
    if (!isExecutable(executable)) throw new java.io.IOException("target is not executable");
    if (!visited.add(executable) || visited.size() > 8) throw new java.io.IOException("launcher cycle or excessive nesting");
    Path shim = shimPath(candidate);
    if (Files.isRegularFile(shim)) {
      var match = Pattern.compile("(?m)^\\s*path\\s*=\\s*\"([^\"]+)\"\\s*$")
          .matcher(Files.readString(shim, StandardCharsets.UTF_8));
      if (!match.find()) throw new java.io.IOException("Scoop .shim has no quoted target path");
      Path target = Path.of(match.group(1));
      return unwrapLauncher(target.isAbsolute() ? target : candidate.getParent().resolve(target), visited);
    }
    String name = executable.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.endsWith(".cmd") || name.endsWith(".bat")) {
      // Only transparent, single-command forwarding wrappers are safe to replace. Never run cmd.exe.
      List<String> lines = Files.readAllLines(executable, StandardCharsets.UTF_8).stream()
          .map(String::trim).filter(line -> !line.isEmpty() && !line.equalsIgnoreCase("@echo off")
              && !line.toLowerCase(Locale.ROOT).startsWith("rem ") && !line.startsWith("::")).toList();
      if (lines.size() != 1) throw new java.io.IOException("batch launcher is not a transparent executable forwarder");
      var match = Pattern.compile("@?(?:call\\s+)?\"([^\"]+\\.exe)\"\\s+%\\*", Pattern.CASE_INSENSITIVE).matcher(lines.getFirst());
      if (!match.matches()) throw new java.io.IOException("batch launcher has no unambiguous executable target");
      String targetText = match.group(1).replace("%~dp0", executable.getParent() + File.separator);
      if (targetText.contains("%")) throw new java.io.IOException("batch target uses unsupported environment expansion");
      Path target = Path.of(targetText);
      return unwrapLauncher(target.isAbsolute() ? target : executable.getParent().resolve(target), visited);
    }
    return executable;
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

  private static Path findExecutableOnPath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    for (String dir : path.split(File.pathSeparator)) {
      if (dir == null || dir.isBlank()) {
        continue;
      }
      Path candidate;
      try { candidate = findExecutableInDirectory(Path.of(dir)); }
      catch (java.nio.file.InvalidPathException invalid) { continue; }
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
    Path candidate;
    try { candidate = Path.of(path); }
    catch (java.nio.file.InvalidPathException invalid) { return null; }
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
