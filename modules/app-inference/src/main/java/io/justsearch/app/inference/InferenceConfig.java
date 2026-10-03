/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;

import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.ModelPathSource;
import io.justsearch.configuration.SystemAccess;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigResolution;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the InferenceLifecycleManager.
 *
 * <p>Defines paths to model files, server executable, and runtime parameters.
 *
 * @param serverExecutable path to llama-server executable
 * @param modelPath path to main VLM model (e.g., qwen3-vl-8b-instruct-q4_k_m.gguf)
 * @param mmprojPath path to vision projector model (e.g., mmproj-model-f16.gguf)
 * @param serverPort port for llama-server HTTP API
 * @param contextSize context window size in tokens; always positive on a built record — 0 means
 *     "auto" only at the builder, which resolves it through {@link ContextWindowPolicy}
 * @param gpuLayers number of layers to offload to GPU (default: 0 = CPU mode)
 * @param vduMode when true, server launches with vision-safe flags ({@code -np 1},
 *     {@code --cache-ram 0}) that prevent multi-slot vision errors and prompt cache corruption
 * @param chatProfileId id of the {@link ChatModelProfile} that selected {@code modelPath} +
 *     {@code mmprojPath} as an atomic pair, or {@code null} when no profile governed the choice
 *     (an operator-set path, or a bare path applied at runtime). Never a stale claim: whoever
 *     changes {@code modelPath} without a profile MUST clear this, because the id is what
 *     downstream surfaces report as the realized chat identity (tempdoc 842 §2.5).
 */
public record InferenceConfig(
    Path serverExecutable,
    Path modelPath,
    Path mmprojPath,
    int serverPort,
    int contextSize,
    int gpuLayers,
    boolean vduMode,
    String chatProfileId
) {
  private static final Logger log = LoggerFactory.getLogger(InferenceConfig.class);

  public InferenceConfig {
    Objects.requireNonNull(serverExecutable, "serverExecutable is required");
    Objects.requireNonNull(modelPath, "modelPath is required");
    // mmprojPath can be null for text-only models
    if (serverPort <= 0 || serverPort > 65535) {
      throw new IllegalArgumentException("serverPort must be between 1 and 65535");
    }
    if (contextSize <= 0) {
      throw new IllegalArgumentException("contextSize must be positive");
    }
    if (gpuLayers < 0) {
      throw new IllegalArgumentException("gpuLayers must be non-negative");
    }
    // A blank id is not a weaker claim than no claim — normalize so `chatProfileId() == null`
    // is the single "no profile governed this pair" test everywhere downstream.
    chatProfileId = (chatProfileId == null || chatProfileId.isBlank()) ? null : chatProfileId.trim();
  }

  /**
   * Legacy seven-component form — equivalent to the canonical constructor with no profile claim.
   *
   * <p>Kept so call sites that genuinely have no profile to declare (telemetry fixtures, external
   * server tests, hand-built configs) stay readable, and so {@code null} remains the explicit,
   * honest default rather than something a caller has to remember to pass.
   */
  public InferenceConfig(
      Path serverExecutable,
      Path modelPath,
      Path mmprojPath,
      int serverPort,
      int contextSize,
      int gpuLayers,
      boolean vduMode) {
    this(serverExecutable, modelPath, mmprojPath, serverPort, contextSize, gpuLayers, vduMode, null);
  }

  /**
   * Creates configuration from environment variables and default paths.
   *
   * <p>Environment variables:
   * <ul>
   *   <li>JUSTSEARCH_SERVER_EXE - path to llama-server.exe</li>
   *   <li>JUSTSEARCH_MODELS_DIR - directory containing model files</li>
   *   <li>JUSTSEARCH_SERVER_PORT - HTTP port (default: 8080)</li>
   *   <li>JUSTSEARCH_CONTEXT_SIZE - context window size; unset means auto, and the window is
   *       derived by {@link ContextWindowPolicy} (tempdoc 883)</li>
   *   <li>JUSTSEARCH_GPU_LAYERS - GPU layers to offload (default: 0)</li>
   * </ul>
   *
   * @param baseDir the base directory for resolving relative paths
   * @return configuration based on environment
   */
  public static InferenceConfig fromEnvironment(Path baseDir) {
    return fromResolvedConfig(ConfigStore.global().get(), baseDir);
  }

  /** Resolves manager configuration from the same snapshot used by its composition decision. */
  public static InferenceConfig fromResolvedConfig(ResolvedConfig rc, Path baseDir) {
    Objects.requireNonNull(rc, "rc");
    log.debug("Creating InferenceConfig from resolved configuration");
    Path resolvedBaseDir =
        ResolvedPathResolver.resolveBaseDir(
            rc,
            baseDir != null
                ? baseDir.toAbsolutePath().normalize().toString()
                : SystemAccess.sysProp("user.dir"));
    log.debug("  Base directory: {}", resolvedBaseDir);

    Path configuredModelsDir = ResolvedPathResolver.resolveModelsDir(rc, resolvedBaseDir);
    log.debug(
        "  Models directory: {} (exists: {})",
        configuredModelsDir,
        Files.isDirectory(configuredModelsDir));

    // Tempdoc 374 sandbox round 4 issue A: serverPort and apiPort both
    // historically defaulted to 8080 — when the head HTTP API binds 8080
    // first, every install's smoke-test llama-server collides on the same
    // port. Default away from apiPort. Users who need 8080 can still set
    // JUSTSEARCH_SERVER_PORT explicitly.
    int port = rc.ports().serverPort();
    if (port <= 0) {
      int apiPort = rc.ports().apiPort();
      port = apiPort > 0 && apiPort != 8081 ? 8081 : 8082;
    }
    // Locked decision: CPU fallback must work by default.
    int layers = rc.ai().gpuLayers();
    // Tempdoc 883: 0 = auto. The one default for this quantity is the ladder's top rung for this
    // backend, not a literal — the Head normally contributes the same value at ordinal 150, so this
    // branch only fires where no resolver contribution exists (worker snapshots, tests, tools).
    int ctxSize = rc.ai().contextSize();
    if (ctxSize <= 0) ctxSize = ContextWindowPolicy.autoTopRung(layers > 0);
    log.debug("  Server port: {}, context size: {}, GPU layers: {}", port, ctxSize, layers);

    // Tempdoc 374 alpha.13 fix A1: derive CUDA availability from the resolved
    // config instead of shelling out to nvidia-smi. `rc.ai().gpuLayers()` (read
    // at line above) already integrates ordinal-150 auto-detection (driver-API
    // probe via nvcuda.dll), env vars (400), and sysprops (500). When layers
    // > 0, GPU is requested AND available. The previous VramDetector probe
    // shelled out to nvidia-smi.exe — which ships with the full CUDA toolkit,
    // not the driver — and sticky-failed on every host without it on PATH,
    // silently downgrading binary selection to the default (CPU) variant.
    boolean cudaAvailable = layers > 0;
    log.debug("  CUDA available: {} (derived from gpu_layers={})", cudaAvailable, layers);

    // Find llama-server executable (prefer CUDA variant when GPU is available)
    Path serverExe = findServerExecutable(rc, resolvedBaseDir, cudaAvailable);

    // ---------------------------------------------------------------------
    // Chat model selection (tempdoc 842 §2.1 correction + §2.3).
    //
    // ONE axis, not two: the chat model and the "extraction VLM" are the same
    // llama-server engine loading the same file. The profile names an atomic
    // (model, mmproj) pair so a half-swap — new model, stale projector — is
    // unrepresentable; that half-swap is precisely how dev stacks ended up
    // running text-only with no error anywhere.
    // ---------------------------------------------------------------------
    ChatModelProfile profile;
    // isSet() covers env var + JVM flag; the resolution trace additionally catches a
    // YAML/settings/snapshot-borne selection. Reading only isSet() would resolve the key through
    // the chain and then ignore what the chain said — the "resolves, is reachable, changes
    // nothing" shape the config-surface gate exists to catch.
    boolean chatProfileSet =
        EnvRegistry.CHAT_PROFILE.isSet()
            || isExplicitlySourced(rc, EnvRegistry.CHAT_PROFILE.configKey());
    boolean legacyProfileSet = EnvRegistry.VLM_PROFILE.isSet();
    if (chatProfileSet) {
      profile = ChatModelProfile.resolve(rc.ai().chatProfile());
    } else if (legacyProfileSet) {
      // Legacy key kept working: its ids ("qwen-vl", "paddle-ocr-vl") resolve through the same
      // alias/id logic, so an existing justsearch.vlm.profile keeps selecting the same two files.
      profile = ChatModelProfile.resolve(EnvRegistry.VLM_PROFILE.get().orElse(null));
    } else {
      profile = ChatModelProfile.DEFAULT;
    }
    // "Explicit" means a human or launcher NAMED a profile. It is what lets profile resolution
    // supersede a system-owned stored path; without it, nothing changes for anyone.
    boolean profileExplicit = chatProfileSet || legacyProfileSet;
    log.debug(
        "  Chat model profile: {} (explicit: {}, chat.profile set: {}, vlm.profile set: {})",
        profile.id(),
        profileExplicit,
        chatProfileSet,
        legacyProfileSet);

    // Per-file overrides keep their win over the profile's members (advanced testing);
    // no auto-discovery of other GGUFs.
    String vlmModel = nonBlankOr(rc.ai().vlmModel(), profile.modelFile());
    String mmprojModel = nonBlankOr(rc.ai().mmprojModel(), profile.mmprojFile());
    boolean profileModelFileUsed = vlmModel.equals(profile.modelFile());

    log.debug("  Model files (from env or profile):");
    log.debug("    VLM: {} (set: {})", vlmModel, EnvRegistry.VLM_MODEL.isSet());
    log.debug("    MMProj: {} (set: {})", mmprojModel, EnvRegistry.MMPROJ_MODEL.isSet());

    // ---------------------------------------------------------------------
    // Model-path override precedence.
    //
    // The pre-842 rule was "any stored llm.model_path wins". Every installed and dev data dir
    // stores one (written at boot from settings.json), so that rule would make an explicit
    // compact profile silently inert — the design's landmine. The distinction that fixes it
    // already exists in the runtime: the source marker next to the stored value.
    // ---------------------------------------------------------------------
    Path llmModelPath = rc.ai().llmModelPath();
    String llmModelPathOverride = llmModelPath != null ? llmModelPath.toString() : null;
    boolean hasStoredModelPath = llmModelPathOverride != null && !llmModelPathOverride.isBlank();
    ModelPathOwner owner = classifyModelPathOwner(rc, hasStoredModelPath);
    boolean operatorOverride =
        owner == ModelPathOwner.OPERATOR_ENV
            || owner == ModelPathOwner.OPERATOR_SYSPROP
            || owner == ModelPathOwner.OPERATOR_YAML;
    // The stored path governs when an operator set it, OR (status quo) when nobody named a
    // profile and a settings-borne value is all we have. Otherwise the profile governs.
    boolean usingLlmModelOverride = hasStoredModelPath && (operatorOverride || !profileExplicit);

    Path modelPath;
    Path associatedModelsDir;
    if (usingLlmModelOverride) {
      Path raw = Path.of(llmModelPathOverride.trim());
      modelPath = raw.isAbsolute() ? raw : resolvedBaseDir.resolve(raw);
      associatedModelsDir =
          modelPath.getParent() != null ? modelPath.getParent() : configuredModelsDir;
      log.debug(
          "  Using LLM model override: {} (associatedModelsDir={})",
          modelPath,
          associatedModelsDir);
    } else {
      modelPath = configuredModelsDir.resolve(vlmModel);
      associatedModelsDir = configuredModelsDir;
    }

    Path mmprojPath;
    if (usingLlmModelOverride && !EnvRegistry.MMPROJ_MODEL.isSet()) {
      // When the user explicitly picks a model file path, we should NOT assume a specific projector.
      // Passing a mismatched mmproj can cause llama-server startup to fail.
      log.info(
          "LLM model override is set; mmproj not explicitly configured. Starting in text-only mode (mmproj disabled). "
              + "Set {} / {} to enable vision.",
          EnvRegistry.MMPROJ_MODEL.sysProp(),
          EnvRegistry.MMPROJ_MODEL.envVar());
      mmprojPath = null;
    } else {
      mmprojPath = resolveOptionalModelPath(mmprojModel, associatedModelsDir);
      if (mmprojPath != null && !Files.exists(mmprojPath)) {
        log.warn("MMProj model not found at {}. Vision features will be disabled.", mmprojPath);
        mmprojPath = null;
      }
    }

    // The claim is made only when the profile actually supplied the model file. A per-file
    // VLM_MODEL override displaces a profile member, and a stale "compact" stamp on someone
    // else's file is worse than no stamp at all.
    String chatProfileId = (!usingLlmModelOverride && profileModelFileUsed) ? profile.id() : null;

    log.debug("  Resolved paths:");
    log.debug("    Server executable: {} (exists: {})", serverExe, Files.exists(serverExe));
    log.debug("    Model path: {} (exists: {})", modelPath, Files.exists(modelPath));
    log.debug(
        "    MMProj path: {} (exists: {})",
        mmprojPath,
        mmprojPath != null && Files.exists(mmprojPath));

    // ONE greppable line naming what governed. This is what a human reads when the wrong
    // model loads.
    log.info(
        "Chat model governed by {}: model={} mmproj={} chatProfileId={}",
        governedByLabel(owner, usingLlmModelOverride, profile, chatProfileId),
        modelPath,
        mmprojPath,
        chatProfileId);

    return new InferenceConfig(
        serverExe,
        modelPath,
        mmprojPath,
        port,
        ctxSize,
        layers,
        false, // vduMode — normal startup, not VDU batch
        chatProfileId);
  }

  /** Who owns the stored {@code justsearch.llm.model_path} value, if anyone. */
  enum ModelPathOwner {
    /** No stored value at all. */
    NONE,
    /** {@code JUSTSEARCH_LLM_MODEL_PATH} env var — always an operator lock. */
    OPERATOR_ENV,
    /** JVM flag with no system-owned marker — a human named this file. */
    OPERATOR_SYSPROP,
    /** {@code config.yaml} — nothing writes that file but a human. */
    OPERATOR_YAML,
    /** JVM flag written by the system itself (settings promotion, auto-select, profile). */
    SYSTEM_STORED,
    /** settings.json / worker-snapshot / auto-detected value that never became an operator lock. */
    STORED_SETTINGS
  }

  /**
   * Classifies the stored model path by <em>who wrote it</em>, using the resolution trace the
   * config chain already records plus the {@code .source} marker sysprop.
   *
   * <p>The trace is the honest seam, and since tempdoc 883 §C.5c (#605) it is the WHOLE seam for
   * this key. Every settings-borne chat-model path — the boot one, the installer's, the pack
   * import's — now arrives as {@code settings.json} at ordinal 300, because none of them is copied
   * into a system property any more. So a {@code jvm_arg} here really is an operator {@code -D},
   * and the marker branch below is inert: it is retained, not relied on, for tempdoc 842's
   * profile-persistence writer, which would be the first system writer to need labelling again.
   *
   * <p>That structural change is what closes 842's landmine at the root. The rule used to need a
   * marker because every installed data dir carried a promoted 9B path that read exactly like an
   * operator lock; with no promotion left, "an explicit profile supersedes a stored settings path"
   * follows from the ordinal chain alone.
   *
   * <p>Unknown/absent provenance is treated as an operator flag unless the marker says otherwise:
   * when in doubt, an explicit path stays sacred.
   */
  static ModelPathOwner classifyModelPathOwner(ResolvedConfig rc, boolean hasStoredModelPath) {
    if (!hasStoredModelPath) {
      return ModelPathOwner.NONE;
    }
    ConfigResolution res =
        rc != null ? rc.resolution(EnvRegistry.LLM_MODEL_PATH.configKey()) : null;
    String sourceName = res != null ? res.sourceName() : null;
    if ("env_var".equals(sourceName)) {
      return ModelPathOwner.OPERATOR_ENV;
    }
    if ("yaml".equals(sourceName)) {
      // config.yaml has no installer writing into it; a path there is a human's choice.
      return ModelPathOwner.OPERATOR_YAML;
    }
    if (sourceName == null || "jvm_arg".equals(sourceName)) {
      String marker = SystemAccess.sysProp(ModelPathSource.SOURCE_PROP_LLM_MODEL_PATH);
      return ModelPathSource.isSystemOwned(marker)
          ? ModelPathOwner.SYSTEM_STORED
          : ModelPathOwner.OPERATOR_SYSPROP;
    }
    return ModelPathOwner.STORED_SETTINGS;
  }

  /**
   * True when {@code key} resolved from a real source rather than the programmatic default —
   * i.e. somebody actually asked for this value.
   */
  private static boolean isExplicitlySourced(ResolvedConfig rc, String key) {
    ConfigResolution res = rc != null ? rc.resolution(key) : null;
    return res != null && res.isResolved() && !"default".equals(res.sourceName());
  }

  private static String governedByLabel(
      ModelPathOwner owner,
      boolean usingLlmModelOverride,
      ChatModelProfile profile,
      String chatProfileId) {
    if (!usingLlmModelOverride) {
      return chatProfileId != null ? "profile:" + profile.id() : "per-file-override";
    }
    return switch (owner) {
      case OPERATOR_ENV -> "operator-env";
      case OPERATOR_SYSPROP -> "operator-sysprop";
      case OPERATOR_YAML -> "operator-yaml";
      default -> "stored-settings";
    };
  }

  private static String nonBlankOr(String value, String fallback) {
    return value != null && !value.isBlank() ? value : fallback;
  }

  private static Path resolveOptionalModelPath(String raw, Path modelsDir) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isBlank()) return null;
    if ("none".equalsIgnoreCase(trimmed) || "null".equalsIgnoreCase(trimmed)) return null;

    Path p = Path.of(trimmed);
    if (p.isAbsolute()) {
      return p;
    }
    // Support relative paths that already exist (relative to CWD) for dev.
    if (Files.exists(p)) {
      return p;
    }
    return modelsDir.resolve(p);
  }

  /**
   * Creates a builder for InferenceConfig.
   */
  public static Builder builder() {
    return new Builder();
  }

  private static Path findServerExecutable(ResolvedConfig rc, Path baseDir, boolean preferCudaVariant) {
    log.debug("Finding llama-server executable (preferCuda={})...", preferCudaVariant);

    String envPath = rc.ai().serverExe() != null ? rc.ai().serverExe().toString() : null;
    if (envPath != null && !envPath.isBlank()) {
      Path p = Path.of(envPath);
      log.debug("  JUSTSEARCH_SERVER_EXE set to: {}", envPath);
      if (Files.exists(p)) {
        log.debug("  Found via environment variable: {}", p);
        return p;
      } else {
        log.warn("  JUSTSEARCH_SERVER_EXE path does not exist: {}", p);
      }
    } else {
      log.debug("  JUSTSEARCH_SERVER_EXE not set");
    }

    Path normalizedBaseDir =
        baseDir != null
            ? baseDir.toAbsolutePath().normalize()
            : ResolvedPathResolver.resolveBaseDir(rc, System.getProperty("user.dir"));
    Path found = findExistingServerExecutable(normalizedBaseDir, preferCudaVariant);
    if (found != null) {
      return found;
    }

    Path explicitRepoRoot = ResolvedPathResolver.resolveExplicitRepoRoot(rc);
    if (explicitRepoRoot != null && !explicitRepoRoot.equals(normalizedBaseDir)) {
      Path repoRootFound = findExistingServerExecutable(explicitRepoRoot, preferCudaVariant);
      if (repoRootFound != null) {
        return repoRootFound;
      }
      // 369: Dev layout — Tauri shell bundles the binary under its resources.
      // Reuses findExistingServerExecutable so CUDA variant selection applies.
      Path devBase = explicitRepoRoot.resolve(
          "modules/shell/src-tauri/resources/headless");
      Path devFound = findExistingServerExecutable(devBase, preferCudaVariant);
      if (devFound != null) {
        log.info("  Found via dev layout (Tauri resources): {}", devFound);
        return devFound;
      }
      return canonicalServerExecutable(explicitRepoRoot);
    }

    return canonicalServerExecutable(normalizedBaseDir);
  }

  private static Path findExistingServerExecutable(Path baseDir, boolean preferCudaVariant) {
    if (baseDir == null) {
      return null;
    }
    Path nativeBin = baseDir.resolve("native-bin").resolve("llama-server");
    log.debug("  Searching in: {} (exists: {})", nativeBin, Files.isDirectory(nativeBin));

    // 1. Check canonical baseline path FIRST (deterministic, preferred in release builds)
    Path directPath = nativeBin.resolve("llama-server.exe");
    if (Files.exists(directPath)) {
      log.debug("  Found baseline at canonical path: {}", directPath);
      // If GPU requested, still check for CUDA variant which is better
      if (preferCudaVariant) {
        Path cudaVariant = findCudaVariant(nativeBin);
        if (cudaVariant != null) {
          log.info("  Preferring CUDA variant over baseline: {}", cudaVariant);
          return cudaVariant;
        }
      }
      return directPath;
    }

    // 2. Check CUDA variant in variants/ directory (dev mode — canonical path often absent)
    if (preferCudaVariant) {
      Path cudaVariant = findCudaVariant(nativeBin);
      if (cudaVariant != null) {
        return cudaVariant;
      }
    }

    // 3. Scan subdirectories (SORTED for determinism, skip variants/)
    if (Files.isDirectory(nativeBin)) {
      try (var dirs = Files.list(nativeBin)) {
        var found = dirs
            .filter(Files::isDirectory)
            .filter(d -> !"variants".equalsIgnoreCase(d.getFileName().toString()))
            .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
            .peek(d -> log.debug("    Checking subdirectory: {}", d))
            .map(d -> d.resolve("llama-server.exe"))
            .filter(Files::exists)
            .findFirst();
        if (found.isPresent()) {
          log.debug("  Found in subdirectory (legacy layout): {}", found.get());
          return found.get();
        }
      } catch (IOException e) {
        log.debug("  Error scanning subdirectories: {}", e.getMessage());
      }
    }

    // 4. Last resort: any variant (CUDA binary works for CPU too, just larger)
    Path anyVariant = findCudaVariant(nativeBin);
    if (anyVariant != null) {
      log.info("  No baseline found; falling back to variant: {}", anyVariant);
      return anyVariant;
    }

    return null;
  }

  /**
   * Finds the CUDA variant executable under {@code variants/cuda12/}.
   *
   * @return path to the variant executable, or null if not found
   */
  private static Path findCudaVariant(Path nativeBin) {
    Path variantsDir = nativeBin.resolve("variants");
    if (!Files.isDirectory(variantsDir)) {
      return null;
    }
    // Prefer cuda12 explicitly (deterministic, no scanning ambiguity)
    Path cuda12 = variantsDir.resolve("cuda12").resolve("llama-server.exe");
    if (Files.exists(cuda12)) {
      log.debug("  Found CUDA variant: {}", cuda12);
      return cuda12;
    }
    // Future: scan variants/ for other CUDA versions if cuda12 not present
    return null;
  }

  private static Path canonicalServerExecutable(Path baseDir) {
    Path directPath = baseDir.resolve("native-bin").resolve("llama-server").resolve("llama-server.exe");
    log.debug("  Using fallback path: {} (exists: {})", directPath, Files.exists(directPath));
    return directPath;
  }

  /**
   * Returns a copy of this config with {@code vduMode} set to the given value. All other fields are
   * preserved. Used by the lifecycle manager to toggle between normal and VDU server configurations.
   */
  public InferenceConfig withVduMode(boolean vdu) {
    if (vdu == this.vduMode) return this;
    // The (model, mmproj) pair is untouched here, so the profile claim carries: dropping it would
    // make every VDU batch report an unknown chat identity.
    return new InferenceConfig(
        serverExecutable,
        modelPath,
        mmprojPath,
        serverPort,
        contextSize,
        gpuLayers,
        vdu,
        chatProfileId);
  }

  /**
   * Validates that all required files exist.
   *
   * @throws IllegalStateException if required files are missing
   */
  public void validate() {
    if (!Files.exists(serverExecutable)) {
      throw new IllegalStateException("llama-server executable not found: " + serverExecutable);
    }
    if (!Files.exists(modelPath)) {
      throw new IllegalStateException("Model file not found: " + modelPath);
    }
    if (mmprojPath != null && !Files.exists(mmprojPath)) {
      throw new IllegalStateException("Vision projector not found: " + mmprojPath);
    }
  }

  /**
   * Builder for InferenceConfig.
   */
  public static final class Builder {
    private Path serverExecutable;
    private Path modelPath;
    private Path mmprojPath;
    private int serverPort = 8080;
    // Tempdoc 883: 0 = auto; build() resolves it through the ladder policy rather than a literal.
    private int contextSize = 0;
    private int gpuLayers = 0;
    private boolean vduMode = false;
    private String chatProfileId;

    private Builder() {}

    public Builder serverExecutable(Path serverExecutable) {
      this.serverExecutable = serverExecutable;
      return this;
    }

    public Builder modelPath(Path modelPath) {
      this.modelPath = modelPath;
      return this;
    }

    public Builder mmprojPath(Path mmprojPath) {
      this.mmprojPath = mmprojPath;
      return this;
    }

    public Builder serverPort(int serverPort) {
      this.serverPort = serverPort;
      return this;
    }

    public Builder contextSize(int contextSize) {
      this.contextSize = contextSize;
      return this;
    }

    public Builder gpuLayers(int gpuLayers) {
      this.gpuLayers = gpuLayers;
      return this;
    }

    public Builder vduMode(boolean vduMode) {
      this.vduMode = vduMode;
      return this;
    }

    /**
     * Declares which {@link ChatModelProfile} selected this config's (model, mmproj) pair. Leave
     * unset for hand-built or operator-path configs — {@code null} is the honest "no profile
     * governed this" value.
     */
    public Builder chatProfileId(String chatProfileId) {
      this.chatProfileId = chatProfileId;
      return this;
    }

    public InferenceConfig build() {
      return new InferenceConfig(
          serverExecutable,
          modelPath,
          mmprojPath,
          serverPort,
          contextSize > 0 ? contextSize : ContextWindowPolicy.autoTopRung(gpuLayers > 0),
          gpuLayers,
          vduMode,
          chatProfileId
      );
    }
  }
}
