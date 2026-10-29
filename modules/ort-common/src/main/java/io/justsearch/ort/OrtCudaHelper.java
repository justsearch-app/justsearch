/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ort;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.ConfigPrecedence;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.model.ArchiveExtractionState;
import io.justsearch.configuration.resolved.ConfigStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared CUDA DLL initialization utilities for ONNX Runtime encoders.
 *
 * <p>Used by both {@code OnnxEmbeddingEncoder} and {@code SpladeEncoder} to preload CUDA
 * dependency DLLs and copy them to the ORT extraction directory on Windows.
 *
 * <p>On Windows, ORT extracts native libraries (including {@code onnxruntime_providers_cuda.dll})
 * to a temp directory ({@code %TEMP%/onnxruntime-java<random>/}). When Windows {@code LoadLibrary}
 * loads that DLL, it resolves transitive dependencies (cuDNN, cuFFT, cuBLAS) by searching the
 * DLL's directory — NOT already-loaded modules. Therefore, {@code System.load()} preloading alone
 * is insufficient; the dependency DLLs must physically exist in the ORT temp directory.
 */
public final class OrtCudaHelper {

  private static final Logger log = LoggerFactory.getLogger(OrtCudaHelper.class);

  /**
   * The pinned CUDA toolkit major version (single source — keep in lockstep with the DLL list
   * below). Surfaced into the benchmark-release hardware projection so an eval run records the
   * CUDA major it linked against (tempdoc 623 U7). The driver version is reported separately via
   * NVML; this is the toolkit pin, not the runtime/driver version.
   */
  public static final String CUDA_TOOLKIT_MAJOR = "12";

  /**
   * CUDA dependency DLLs in load order. cuBLAS depends on cuBLASLt, so cuBLASLt must come first.
   * Version-pinned to CUDA 12 / cuDNN 9 / cuFFT 11. Update this list when upgrading CUDA toolkit.
   */
  static List<String> cudaDependencyDllOrder() {
    if (!io.justsearch.configuration.PlatformPaths.isWindows()) {
      // Linux's sparse/solver libraries need nvJitLink first. SONAME preloading works even
      // after a CPU session initialized ORT; changing ORT's one-shot native.path does not.
      return nativeLibraryNames("nvJitLink", "cudart", "cublasLt", "cublas", "cufft", "curand",
          "cusparse", "cusolver", "cudnn", "cudnn_graph", "cudnn_heuristic", "cudnn_ops",
          "cudnn_cnn", "cudnn_adv", "cudnn_engines_runtime_compiled", "cudnn_engines_precompiled");
    }
    return nativeLibraryNames("cudart", "cublasLt", "cublas", "cufft", "curand", "cusparse", "cusolver",
          "nvJitLink", "cudnn", "cudnn_graph", "cudnn_heuristic", "cudnn_ops", "cudnn_cnn",
          "cudnn_adv", "cudnn_engines_runtime_compiled", "cudnn_engines_precompiled");
  }

  private static final Set<String> PRELOADED_NATIVE_DLLS = ConcurrentHashMap.newKeySet();
  private static volatile boolean legacyKeyWarningLogged;

  private OrtCudaHelper() {}

  /**
   * Prepares CUDA dependencies for ORT GPU session creation.
   *
   * <p>Performs two steps: (1) preloads CUDA DLLs via {@code System.load()}, and (2) copies them
   * to the ORT temp extraction directory so Windows {@code LoadLibrary} can resolve transitive
   * dependencies.
   *
   * @param nativePath directory containing CUDA dependency DLLs
   */
  public static void prepareCudaDependencies(Path nativePath) {
    preloadCudaDependencies(nativePath);
    copyCudaDllsToOrtTempDir(nativePath);
  }

  /**
   * Checks for missing core CUDA libraries using the current native target names (advisory).
   *
   * <p><b>Use case:</b> validating an externally-set {@code JUSTSEARCH_NATIVE_PATH} that
   * is supposed to be a self-contained ORT GPU directory — i.e. a directory that
   * holds BOTH the ORT EP DLLs ({@code onnxruntime_providers_cuda.dll},
   * {@code onnxruntime_providers_shared.dll}) AND the CUDA runtime DLLs.
   *
   * <p>Do <b>not</b> use this for validating the bundled
   * {@code …\native-bin\llama-server\variants\cuda12\} directory — that dir
   * ships CUDA runtime DLLs only; the ORT EP DLLs auto-extract from
   * {@code onnxruntime-gpu.jar} to a JVM temp dir at runtime. For that case,
   * use {@link #checkMissingCudaRuntimeDlls(Path)} instead. Tempdoc 374
   * alpha.13 round-5 finding: the alpha.13 fix B used this method as a
   * defensive guard against the cuda12 dir, which always tripped because
   * the ORT EP DLLs are never in cuda12 — silently disabled the entire fix.
   *
   * @param searchPath directory to check for DLLs
   * @return list of missing DLL names (empty if all present or the directory is unavailable)
   */
  public static List<String> checkMissingCudaDlls(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<String> missing = new ArrayList<>();
    for (String dll : nativeLibraryNames("ort-cuda", "ort-shared", "cudart", "cublas", "cublasLt")) {
      if (!Files.exists(searchPath.resolve(dll))) {
        missing.add(dll);
      }
    }
    return missing;
  }

  /**
   * Checks for missing CUDA <em>runtime</em> DLLs in a search path. Distinct from
   * {@link #checkMissingCudaDlls} — this method validates only the runtime DLLs
   * that ship next to llama-server's cuda12 binary
   * ({@code cudart64_12.dll}, {@code cublas64_12.dll}, {@code cublasLt64_12.dll}).
   *
   * <p><b>Use case:</b> validating the bundled
   * {@code …\native-bin\llama-server\variants\cuda12\} directory before pointing
   * ORT at it via {@code justsearch.onnxruntime.native_path}. ORT's CUDA EP DLL
   * (auto-extracted from the {@code onnxruntime-gpu} JAR to a JVM temp dir)
   * depends on these runtime libraries at LoadLibrary time;
   * {@link #copyCudaDllsToOrtTempDir} copies them next to the EP DLL so the
   * Windows loader can resolve the dependency chain.
   *
   * <p>Tempdoc 374 alpha.14 fix: replaces the broken use of
   * {@link #checkMissingCudaDlls} as a precondition guard.
   *
   * @param searchPath directory to check for DLLs
   * @return list of missing DLL names (empty if all present or the directory is unavailable)
   */
  public static List<String> checkMissingCudaRuntimeDlls(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<String> missing = new ArrayList<>();
    for (String dll : nativeLibraryNames("cudart", "cublas", "cublasLt")) {
      if (!Files.exists(searchPath.resolve(dll))) {
        missing.add(dll);
      }
    }
    return missing;
  }

  /**
   * Resolves the ORT native path from environment/system properties, falling back to the given
   * default.
   *
   * @param defaultPath fallback path (typically the model directory)
   * @return resolved native path
   */
  public static Path resolveOrtNativePath(Path defaultPath) {
    // 1. First-class resolved config (centralized, propagated via worker snapshot)
    ConfigStore store = ConfigStore.globalOrNull();
    if (store != null) {
      Path configured = store.get().paths().ortNativePath();
      if (configured != null && Files.isDirectory(configured)) {
        log.info("ORT native path resolved via ConfigStore: {}", configured);
        return configured;
      }
      log.debug("ORT native path: ConfigStore returned {} (isDir={}), falling through",
          configured, configured != null && Files.isDirectory(configured));
    } else {
      log.debug("ORT native path: ConfigStore is null, falling through");
    }
    // 2. Canonical key via EnvRegistry, then deprecated key fallback for backwards compatibility.
    String ortNativePathStr = EnvRegistry.ORT_NATIVE_PATH.getString(null);
    if (ortNativePathStr == null || ortNativePathStr.isBlank()) {
      ortNativePathStr =
          ConfigPrecedence.envOrProperty(
              "JUSTSEARCH_NATIVE_PATH", "onnxruntime.native.path", null);
    }
    if (ortNativePathStr == null || ortNativePathStr.isBlank()) {
      // Install AI can populate this directory after CPU sessions initialized ORT. Resolve
      // its dependencies on demand instead of changing a restart-required process source.
      if (store != null && io.justsearch.configuration.PlatformPaths.isLinux()) {
        Path home = store.get().paths().dataDir();
        if (home != null) {
          Path pack = home.resolve("native-bin/llama-server/variants/cuda12");
          if (linuxRuntimePackComplete(pack)) {
            return pack;
          }
        }
      }
      log.info("ORT native path: no env/sysprop configured, using default: {}", defaultPath);
      return defaultPath;
    }
    try {
      Path configuredPath = Path.of(ortNativePathStr).toAbsolutePath().normalize();
      if (Files.isDirectory(configuredPath)) {
        if (!legacyKeyWarningLogged) {
          legacyKeyWarningLogged = true;
          log.warn(
              "ORT native path resolved via deprecated key (JUSTSEARCH_NATIVE_PATH / "
                  + "onnxruntime.native.path). Migrate to JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH "
                  + "/ justsearch.onnxruntime.native_path. Resolved path: {}",
              configuredPath);
        }
        log.info("ORT native path resolved via env/sysprop: {}", configuredPath);
        return configuredPath;
      }
      log.info("ORT native path: env/sysprop value '{}' is not a directory, using default: {}",
          configuredPath, defaultPath);
    } catch (Exception e) {
      log.debug(
          "Ignoring invalid ORT native path '{}': {}", ortNativePathStr, e.getMessage());
    }
    return defaultPath;
  }

  private static boolean linuxRuntimePackComplete(Path pack) {
    Path receipts = pack.resolve(ArchiveExtractionState.DIRECTORY);
    List<String> preloads = cudaDependencyDllOrder();
    if (!Files.isDirectory(receipts) || preloads.isEmpty()) return false;
    // The runtime archive's verified inventory includes dlopen-only dependencies such as
    // NVRTC/builtins and zlib. The preload order alone is not a completeness contract.
    try (var files = Files.list(receipts)) {
      var json = tools.jackson.databind.json.JsonMapper.builder().build();
      for (Path receipt : files.filter(p -> p.getFileName().toString().endsWith(".zip.json")).toList()) {
        var recorded = json.readValue(Files.readAllBytes(receipt), ArchiveExtractionState.Receipt.class);
        if (recorded == null || recorded.entries() == null) continue;
        var declared = recorded.entries().stream().map(ArchiveExtractionState.Entry::name).toList();
        if (declared.containsAll(preloads)) {
          String name = receipt.getFileName().toString();
          Path archive = pack.resolve(name.substring(0, name.length() - ".json".length()));
          return ArchiveExtractionState.isApplied(archive, recorded.sha256(), true);
        }
      }
    } catch (IOException | RuntimeException invalid) {
      log.debug("Linux CUDA runtime inventory unavailable at {}: {}", pack, invalid.getMessage());
    }
    return false;
  }

  /**
   * ORT's own native-library directory override system property. Distinct from JustSearch's
   * {@code justsearch.onnxruntime.native_path} config key: this is the property ONNX Runtime's Java
   * loader ({@code ai.onnxruntime.OnnxRuntime}) reads at class-init to decide where to load its
   * native libraries. When set, it reroutes the ENTIRE ORT native set (core + JNI + providers), not
   * just one library — so the directory must be complete (tempdoc 772 §J probe).
   */
  public static final String ORT_NATIVE_PATH_PROPERTY = "onnxruntime.native.path";

  /**
   * Expected ONNX Runtime version of the relocated CUDA-EP native pack. Keep in lockstep with the
   * {@code onnxruntime} entry in {@code gradle/libs.versions.toml} (the single build-time pin for
   * the {@code onnxruntime}/{@code onnxruntime_gpu} artifacts). Mirrors the hardcoded-pin pattern of
   * {@link #CUDA_TOOLKIT_MAJOR}. Used to guard against version skew after a future ORT bump: the
   * pack archive ships {@value #ORT_NATIVE_VERSION_MARKER} carrying its ORT version, and
   * {@link #applyOrtNativePackProperty(Path)} refuses to point ORT at a dir whose marker does not
   * match this — a mismatched external native set would fail ORT init outright.
   */
  public static final String EXPECTED_ORT_NATIVE_VERSION = "1.24.3";

  /** Version-marker filename inside the relocated CUDA-EP native pack (tempdoc 772 §J item 2). */
  public static final String ORT_NATIVE_VERSION_MARKER = "ort-native-version.txt";

  /**
   * The complete ORT native library set the external {@code onnxruntime.native.path} directory must
   * contain. Setting the property reroutes ALL ORT natives to that dir (tempdoc 772 §J probe:
   * externalizing only the EP DLL fails init because the core libs are then sought there too).
   */
  static List<String> ortNativeDllSet() {
    return nativeLibraryNames("ort", "ort-jni", "ort-shared", "ort-cuda");
  }

  private static List<String> nativeLibraryNames(String... ids) {
    return NativeTargets.currentIfSupported().map(target -> java.util.Arrays.stream(ids).map(target::libraryName).toList()).orElseGet(List::of);
  }

  /** Outcome of {@link #evaluateOrtNativePack(Path, String)}. */
  public enum OrtNativePackStatus {
    /** All checks passed; the property should be (or was) set to the pack dir. */
    SET,
    /** {@code onnxruntime.native.path} was already set (user override); left untouched. */
    ALREADY_SET_EXTERNALLY,
    /** The pack dir is null or not a directory (pack not installed — CPU path). */
    DIR_ABSENT,
    /** The pack dir exists but is missing one or more of {@link #ortNativeDllSet()}. */
    INCOMPLETE,
    /** The pack dir is complete but its version marker does not match {@link #EXPECTED_ORT_NATIVE_VERSION}. */
    VERSION_MISMATCH
  }

  /**
   * Decision record from evaluating a candidate ORT native pack directory.
   *
   * @param status the classification
   * @param detail human-readable specifics (the resolved path for {@code SET}, the missing DLL list
   *     for {@code INCOMPLETE}, the version pair for {@code VERSION_MISMATCH}, the external value for
   *     {@code ALREADY_SET_EXTERNALLY}, the offending path for {@code DIR_ABSENT})
   */
  public record OrtNativePackDecision(OrtNativePackStatus status, String detail) {}

  /**
   * Pure detection for the relocated CUDA-EP native pack (tempdoc 772 §J item 2). No side effects —
   * separated from {@link #applyOrtNativePackProperty(Path)} so it is unit-testable without touching
   * process-global system properties.
   *
   * <p>Detection is file-existence-based, never provider-list-based: with the EP DLL trimmed from
   * the jar and no property set, {@code OrtEnvironment.getAvailableProviders()} STILL lists CUDA
   * (tempdoc 772 §J probe), so a provider-list check would give a false positive.
   *
   * @param packDir candidate directory (the {@code cuda-runtime} pack's cuda12 dir), or {@code null}
   * @param existingProperty the current value of {@code onnxruntime.native.path} ({@code null}/blank
   *     if unset)
   * @return the decision; only {@link OrtNativePackStatus#SET} means the property should be written
   */
  public static OrtNativePackDecision evaluateOrtNativePack(Path packDir, String existingProperty) {
    if (existingProperty != null && !existingProperty.isBlank()) {
      return new OrtNativePackDecision(OrtNativePackStatus.ALREADY_SET_EXTERNALLY, existingProperty);
    }
    if (NativeTargets.currentIfSupported().isEmpty()) {
      return new OrtNativePackDecision(OrtNativePackStatus.DIR_ABSENT, "GPU unavailable: unsupported native target");
    }
    if (packDir == null || !Files.isDirectory(packDir)) {
      return new OrtNativePackDecision(OrtNativePackStatus.DIR_ABSENT, String.valueOf(packDir));
    }
    List<String> missing = new ArrayList<>();
    for (String dll : ortNativeDllSet()) {
      if (!Files.exists(packDir.resolve(dll))) {
        missing.add(dll);
      }
    }
    if (!missing.isEmpty()) {
      return new OrtNativePackDecision(OrtNativePackStatus.INCOMPLETE, missing.toString());
    }
    String markerVersion = readOrtNativeVersionMarker(packDir);
    if (!EXPECTED_ORT_NATIVE_VERSION.equals(markerVersion)) {
      return new OrtNativePackDecision(
          OrtNativePackStatus.VERSION_MISMATCH,
          "expected " + EXPECTED_ORT_NATIVE_VERSION + " but marker is " + markerVersion);
    }
    return new OrtNativePackDecision(
        OrtNativePackStatus.SET, packDir.toAbsolutePath().normalize().toString());
  }

  /**
   * Reads and trims the first non-blank line of the pack's {@value #ORT_NATIVE_VERSION_MARKER}, or
   * {@code null} if absent/unreadable/empty.
   */
  private static String readOrtNativeVersionMarker(Path packDir) {
    Path marker = packDir.resolve(ORT_NATIVE_VERSION_MARKER);
    if (!Files.isRegularFile(marker)) {
      return null;
    }
    try {
      for (String line : Files.readAllLines(marker)) {
        String trimmed = line.trim();
        if (!trimmed.isEmpty()) {
          return trimmed;
        }
      }
    } catch (IOException e) {
      log.debug("Failed to read ORT native version marker {}: {}", marker, e.getMessage());
    }
    return null;
  }

  /**
   * Detects the relocated CUDA-EP native pack (tempdoc 772 §J item 2) and, when present and
   * version-matched, sets ORT's {@code onnxruntime.native.path} system property to it so ONNX
   * Runtime loads the CUDA execution provider (trimmed from the shipped jar) from the pack instead.
   *
   * <p><b>Must be called before the first {@code OrtEnvironment.getEnvironment()} in the process</b>
   * — ORT captures the property once at class-init. The single production caller is the earliest
   * point of {@code IndexerWorker.main} (after config load, before the Knowledge Server that creates
   * ORT sessions).
   *
   * <p>On any non-{@code SET} outcome the property is left unset and ORT serves the CPU path from
   * the jar's retained core natives (CUDA fails with the legible {@code ORT_EP_FAIL "Failed to find
   * CUDA shared provider"} shape). {@code DIR_ABSENT} — the normal pack-not-installed / CPU-only
   * path — logs one INFO; the genuinely-wrong states {@code INCOMPLETE} and {@code VERSION_MISMATCH}
   * log one WARN naming what was missing. An externally pre-set property (user override) is
   * respected and never clobbered.
   *
   * @param packDir the {@code cuda-runtime} pack's cuda12 dir (from
   *     {@code justsearch.onnxruntime.native_path}), or {@code null} if unconfigured
   * @return the decision that was acted upon
   */
  public static OrtNativePackDecision applyOrtNativePackProperty(Path packDir) {
    OrtNativePackDecision decision =
        evaluateOrtNativePack(packDir, System.getProperty(ORT_NATIVE_PATH_PROPERTY));
    switch (decision.status()) {
      case SET -> {
        System.setProperty(ORT_NATIVE_PATH_PROPERTY, decision.detail());
        log.info(
            "ORT CUDA native pack detected — set {}={} (ORT {}). CUDA execution provider will load"
                + " from the pack.",
            ORT_NATIVE_PATH_PROPERTY,
            decision.detail(),
            EXPECTED_ORT_NATIVE_VERSION);
      }
      case ALREADY_SET_EXTERNALLY ->
          log.info(
              "ORT native path {} already set externally to {} — respecting override, not"
                  + " overwriting with detected CUDA pack.",
              ORT_NATIVE_PATH_PROPERTY,
              decision.detail());
      case DIR_ABSENT ->
          log.info(
              "ORT CUDA native pack not installed (dir {}). ONNX inference will run on CPU; GPU"
                  + " requires the consent-gated cuda-runtime pack. (Normal on CPU-only setups.)",
              decision.detail());
      case INCOMPLETE ->
          log.warn(
              "ORT CUDA native pack at {} is incomplete — missing {}. Not setting {}; ONNX inference"
                  + " will run on CPU.",
              packDir,
              decision.detail(),
              ORT_NATIVE_PATH_PROPERTY);
      case VERSION_MISMATCH ->
          log.warn(
              "ORT CUDA native pack at {} has a version skew ({}). Not setting {}; ONNX inference"
                  + " will run on CPU. Re-install the cuda-runtime pack to match the shipped ORT"
                  + " version.",
              packDir,
              decision.detail(),
              ORT_NATIVE_PATH_PROPERTY);
    }
    return decision;
  }

  /**
   * Returns the list of candidate CUDA dependency DLL paths found in the given directory.
   *
   * @param searchPath directory to scan
   * @return list of absolute, normalized paths to existing DLLs (in load order)
   */
  public static List<Path> candidateCudaDependencyDlls(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return List.of();
    }
    List<Path> dlls = new ArrayList<>();
    for (String dllName : cudaDependencyDllOrder()) {
      Path dllPath = searchPath.resolve(dllName);
      if (Files.exists(dllPath)) {
        dlls.add(dllPath.toAbsolutePath().normalize());
      }
    }
    return dlls;
  }

  // --- Internal ---

  private static void preloadCudaDependencies(Path searchPath) {
    for (Path dllPath : candidateCudaDependencyDlls(searchPath)) {
      String key = dllPath.toString();
      if (!PRELOADED_NATIVE_DLLS.add(key)) {
        continue;
      }
      try {
        System.load(key);
        log.debug("Preloaded CUDA dependency {}", dllPath);
      } catch (UnsatisfiedLinkError | SecurityException e) {
        PRELOADED_NATIVE_DLLS.remove(key);
        log.debug("Failed to preload CUDA dependency {}: {}", dllPath, e.getMessage());
      }
    }
  }

  /**
   * Copies CUDA dependency DLLs into the ORT temp extraction directory so that Windows
   * {@code LoadLibrary} can find them when loading {@code onnxruntime_providers_cuda.dll}.
   *
   * <p><b>TOCTOU note:</b> This method identifies the ORT extraction directory by scanning
   * {@code %TEMP%} for the most recently modified {@code onnxruntime-java*} directory containing
   * {@code onnxruntime.dll}. This heuristic assumes a single ORT-using JVM per machine. If multiple
   * JVMs run concurrently, or stale directories have newer timestamps, the wrong directory may be
   * selected. In practice, only one JustSearch Worker runs per machine, so the risk is low.
   *
   * <p>The check-then-copy on individual DLLs ({@code Files.exists} → {@code Files.copy}) also has
   * a narrow TOCTOU window. This is benign: concurrent copies of the same DLL would produce
   * identical content, and {@code Files.copy} throws {@code FileAlreadyExistsException} which is
   * caught and logged.
   */
  private static void copyCudaDllsToOrtTempDir(Path searchPath) {
    if (searchPath == null || !Files.isDirectory(searchPath)) {
      return;
    }
    if (!io.justsearch.configuration.PlatformPaths.isWindows() || NativeTargets.currentIfSupported().isEmpty()) {
      return;
    }

    Path tmpDir = Path.of(ConfigPrecedence.envOrProperty(null, "java.io.tmpdir", "."));
    Path bestDir = null;
    long bestMtime = 0;
    try (var dirStream = Files.newDirectoryStream(tmpDir, "onnxruntime-java*")) {
      for (Path candidate : dirStream) {
        if (!Files.isDirectory(candidate)) {
          continue;
        }
        Path ortDll = candidate.resolve(NativeTargets.current().libraryName("ort"));
        if (!Files.exists(ortDll)) {
          continue;
        }
        long mtime = Files.getLastModifiedTime(ortDll).toMillis();
        if (mtime > bestMtime) {
          bestMtime = mtime;
          bestDir = candidate;
        }
      }
    } catch (IOException e) {
      log.debug("Failed to scan for ORT extraction directory: {}", e.getMessage());
      return;
    }

    if (bestDir == null) {
      log.debug("No ORT extraction directory found in {}", tmpDir);
      return;
    }

    log.debug("Found ORT extraction directory: {}", bestDir);
    int copied = 0;
    for (Path dllPath : candidateCudaDependencyDlls(searchPath)) {
      Path target = bestDir.resolve(dllPath.getFileName().toString());
      if (!Files.exists(target)) {
        try {
          Files.copy(dllPath, target);
          copied++;
          log.debug("Copied CUDA DLL to ORT temp dir: {}", target.getFileName());
        } catch (IOException e) {
          log.debug(
              "Failed to copy CUDA DLL {} to ORT temp dir: {}",
              dllPath.getFileName(),
              e.getMessage());
        }
      }
    }
    if (copied > 0) {
      log.info(
          "Copied {} CUDA dependency DLLs to ORT extraction directory {}", copied, bestDir);
    }
  }
}
