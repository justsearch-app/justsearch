/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.resolved;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared ONNX model directory discovery via {@link ResolvedPathResolver}.
 *
 * <p>Resolution order (first existing directory with all required files wins):
 *
 * <ol>
 *   <li>Explicit override via environment variable / system property (returned as-is, no
 *       validation)
 *   <li>Model roots from {@link ResolvedPathResolver#resolveModelRoots}: explicit {@code
 *       modelsDir} &rarr; {@code <dataDir>/models} &rarr; {@code <repoRoot>/models} &rarr; {@code
 *       <baseDir>/models}, each checked for {@code onnx/<modelName>/}
 *   <li>Dev fallback: {@code <baseDir>/models/<devSubdir>/} (for development)
 * </ol>
 *
 * <p>Auto-discovered directories (step 2) are validated for required files. The dev fallback
 * (step 3) is discovered but not considered a standard location for auto-enable purposes
 * ({@code autoDiscovered=false}).
 *
 * <p>Used by per-module discoverers (SPLADE, embedding, NER, reranker, citation-scorer) which
 * delegate to this class and wrap the result in their own module-local types.
 */
public final class OnnxModelDiscovery {

  private static final Logger log = LoggerFactory.getLogger(OnnxModelDiscovery.class);

  /** Default required files for ONNX model directories. */
  public static final List<String> DEFAULT_REQUIRED_FILES =
      List.of("model.onnx", "tokenizer.json");

  /** Result of model discovery, tracking where (and whether) a model was found. */
  public record Result(Path modelDir, boolean autoDiscovered) {}

  private OnnxModelDiscovery() {}

  /**
   * Resolves an ONNX model directory using the default required files ({@code model.onnx} and
   * {@code tokenizer.json}). Dev-layout paths found within model roots are NOT auto-discovered.
   *
   * @param explicitPath explicit path from env var (may be null or blank)
   * @param modelName canonical model name for standard locations (e.g. "reranker", "splade")
   * @param devSubdir dev-mode fallback subdirectory relative to {@code <baseDir>/models/} (e.g.
   *     "reranker/ms-marco-MiniLM-L6-v2"), or null to skip the dev fallback
   * @return discovery result with path and auto-discovered flag, or null if not found
   */
  public static Result resolve(String explicitPath, String modelName, String devSubdir) {
    return resolve(
        resolvedConfig(),
        explicitPath,
        modelName,
        devSubdir,
        DEFAULT_REQUIRED_FILES,
        false);
  }

  /** Snapshot-bound variant of {@link #resolve(String, String, String)}. */
  public static Result resolve(
      ResolvedConfig config, String explicitPath, String modelName, String devSubdir) {
    return resolve(
        config, explicitPath, modelName, devSubdir, DEFAULT_REQUIRED_FILES, false);
  }

  /**
   * Resolves an ONNX model directory, checking standard locations if no explicit path is
   * configured.
   *
   * @param explicitPath explicit path from env var (may be null or blank)
   * @param modelName canonical model name for standard locations (e.g. "reranker", "splade")
   * @param devSubdir dev-mode fallback subdirectory relative to {@code <baseDir>/models/} (e.g.
   *     "splade/naver-splade-v3"), or null to skip the dev fallback
   * @param requiredFiles list of filenames that must exist in the directory (e.g. "model.onnx",
   *     "tokenizer.json", "vocab.txt")
   * @param devLayoutAutoDiscovered whether dev-layout paths found within model roots should be
   *     considered auto-discovered (true for SPLADE where the dev layout is the supported repo
   *     layout; false for NER where auto-enable requires explicit ENABLED=true)
   * @return discovery result with path and auto-discovered flag, or null if not found
   */
  public static Result resolve(
      String explicitPath,
      String modelName,
      String devSubdir,
      List<String> requiredFiles,
      boolean devLayoutAutoDiscovered) {
    return resolve(
        resolvedConfig(),
        explicitPath,
        modelName,
        devSubdir,
        requiredFiles,
        devLayoutAutoDiscovered);
  }

  /**
   * Snapshot-bound discovery. Candidate ordering is identical to the legacy overload; only the
   * configuration source is explicit rather than {@link ConfigStore#globalOrNull()}.
   */
  public static Result resolve(
      ResolvedConfig config,
      String explicitPath,
      String modelName,
      String devSubdir,
      List<String> requiredFiles,
      boolean devLayoutAutoDiscovered) {
    // 1. Explicit override — returned as-is, caller's responsibility to validate
    if (explicitPath != null && !explicitPath.isBlank()) {
      Path p = Path.of(explicitPath);
      log.debug("ONNX model '{}': explicit path set to {}", modelName, p);
      return new Result(p, false);
    }

    // 2. Auto-discover via ResolvedPathResolver (modelsDir > dataDir > repoRoot > baseDir)
    Path baseDir =
        ResolvedPathResolver.resolveBaseDir(config, System.getProperty("user.dir"));
    for (Path modelRoot : ResolvedPathResolver.resolveModelRoots(config, baseDir)) {
      // Standard layout: <modelRoot>/onnx/<modelName>/
      Path candidate = modelRoot.resolve("onnx").resolve(modelName);
      if (isCompleteModelDir(candidate, requiredFiles)) {
        log.debug("ONNX model '{}': found at {}", modelName, candidate);
        return new Result(candidate, true);
      }
      // Dev layout within model root: <modelRoot>/<devSubdir>/
      // (e.g., <modelsDir>/splade/naver-splade-v3/ when modelsDir is a shared model directory)
      if (devSubdir != null) {
        Path devCandidate = modelRoot.resolve(devSubdir);
        if (isCompleteModelDir(devCandidate, requiredFiles)) {
          log.debug("ONNX model '{}': found at dev layout {}", modelName, devCandidate);
          return new Result(devCandidate, devLayoutAutoDiscovered);
        }
      }
    }

    // 3. Dev fallback: <baseDir>/models/<devSubdir>/
    //    Discovered but NOT auto-discovered (autoDiscovered=false) — dev must explicitly enable
    if (devSubdir != null) {
      Path devPath = baseDir.resolve("models").resolve(devSubdir);
      if (isCompleteModelDir(devPath, requiredFiles)) {
        log.debug("ONNX model '{}': found at dev path {}", modelName, devPath);
        return new Result(devPath, false);
      }
    }

    log.debug("ONNX model '{}': not found at any standard location", modelName);
    return null;
  }

  /**
   * Checks that a directory contains all required model files, or alternatively contains a
   * {@code model_manifest.json} (which declares the actual filenames) plus {@code tokenizer.json},
   * or contains a {@code model_fp16.onnx} GPU-only variant in lieu of {@code model.onnx}.
   * The fallbacks support models that use non-default filenames (e.g. only
   * {@code model_fp16.onnx} without a {@code model.onnx} — the case for Install AI's GPU_FULL
   * profile).
   */
  private static boolean isCompleteModelDir(Path dir, List<String> requiredFiles) {
    if (!Files.isDirectory(dir)) {
      return false;
    }
    // Primary check: all explicitly required files present
    boolean allPresent = true;
    for (String file : requiredFiles) {
      if (!Files.exists(dir.resolve(file))) {
        allPresent = false;
        break;
      }
    }
    if (allPresent) {
      return true;
    }
    // Tempdoc 374 alpha.19 Bug J-2: GPU_FULL Install AI installs only model_fp16.onnx
    // (the CUDA variant), not the conventional model.onnx. Without this fallback,
    // OnnxModelDiscovery returns "not found at any standard location" for splade/ner/
    // reranker on every default-flow GPU_FULL install — silently disabling those
    // encoders. The alpha.16 Bug C suite of fixes already handles GPU-only layouts at
    // the encoder level (ModelManifest.resolveExistingModelFile, OnnxEmbeddingEncoder/
    // SpladeEncoder/etc.); this brings discovery into alignment.
    boolean hasFp16Variant =
        Files.exists(dir.resolve("model_fp16.onnx"))
            && Files.exists(dir.resolve("tokenizer.json"));
    if (hasFp16Variant) {
      // Honour any non-tokenizer required files too (e.g. SPLADE's vocab.txt) — the
      // fp16 fallback substitutes for model.onnx alone, not for the rest of the
      // role-specific required-file list.
      for (String file : requiredFiles) {
        if (file.equals("model.onnx") || file.equals("tokenizer.json")) continue;
        if (!Files.exists(dir.resolve(file))) {
          return false;
        }
      }
      return true;
    }
    // Existing fallback: model_manifest.json declares which ONNX files to use, so the
    // directory is valid even without a conventional model.onnx. Tokenizer is still required.
    return Files.exists(dir.resolve("model_manifest.json"))
        && Files.exists(dir.resolve("tokenizer.json"));
  }

  private static ResolvedConfig resolvedConfig() {
    ConfigStore store = ConfigStore.globalOrNull();
    return store != null ? store.get() : null;
  }
}
