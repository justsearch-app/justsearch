/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.inference;
import io.justsearch.app.api.ConfigCode;
import io.justsearch.app.api.HealthCode;
import io.justsearch.app.api.InferenceFailure;
import io.justsearch.app.api.ModeTransitionException;
import io.justsearch.app.api.StartupCode;
import io.justsearch.app.api.TransitionCode;

import io.justsearch.app.api.ModeChangeListener;
import io.justsearch.app.api.OnlineAiLifecycleControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OnlineAiRuntimeIntrospection;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.SamplingParams;
import io.justsearch.configuration.model.ChatModelProfile;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.ResolvedPathResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link OnlineAiService} that delegates to {@link InferenceLifecycleManager}.
 *
 * <p>This class wraps the low-level inference manager and provides a clean API for
 * user-facing AI operations like summarization and Q&A.
 */
public final class OnlineAiServiceImpl
    implements OnlineAiService,
        OnlineAiRuntimeControl,
        OnlineAiRuntimeIntrospection,
        OnlineAiLifecycleControl {

  private static final Logger LOG = LoggerFactory.getLogger(OnlineAiServiceImpl.class);

  private final InferenceLifecycleManager manager;
  private final io.justsearch.app.api.EngineAdmissionService admission;

  /**
   * Creates a new OnlineAiServiceImpl.
   *
   * @param manager the inference lifecycle manager to delegate to
   */
  public OnlineAiServiceImpl(
      io.justsearch.app.api.EngineAdmissionService admission, InferenceLifecycleManager manager) {
    this.admission = Objects.requireNonNull(admission, "admission");
    this.manager = Objects.requireNonNull(manager, "manager");
    LOG.info("OnlineAiServiceImpl created");
  }

  @Override
  public void applyRuntimeOverrides(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyOverridesInternal(
        llmModelPath,
        contextLength,
        gpuLayers,
        restartPolicy,
        io.justsearch.app.inference.telemetry.TransitionReason.CONFIG_APPLY);
  }

  @Override
  public void applyRuntimeOverridesAdmin(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy) {
    applyOverridesInternal(
        llmModelPath,
        contextLength,
        gpuLayers,
        restartPolicy,
        io.justsearch.app.inference.telemetry.TransitionReason.ADMIN_TRIGGERED);
  }

  private void applyOverridesInternal(
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers,
      RestartPolicy restartPolicy,
      io.justsearch.app.inference.telemetry.TransitionReason transitionReason) {
    InferenceConfig current = manager.currentConfig();
    if (current == null) {
      throw new IllegalStateException("Inference runtime not configured");
    }

    InferenceConfig next = applyOverrides(current, llmModelPath, contextLength, gpuLayers);
    InferenceLifecycleManager.RestartPolicy managerPolicy = mapRestartPolicy(restartPolicy);
    try {
      manager.applyConfig(next, managerPolicy, transitionReason);
    } catch (ModeTransitionException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  /**
   * Applies a chat model profile as one atomic unit (tempdoc 842 §2.3).
   *
   * <p>Engine restarts never re-run {@link InferenceConfig#fromEnvironment(Path)} — every apply
   * derives the next config from {@link InferenceLifecycleManager#currentConfig()} — so a profile
   * switch needs its own entry point here rather than a re-resolution at bootstrap.
   */
  @Override
  public void applyChatProfile(ChatModelProfile profile, RestartPolicy restartPolicy) {
    Objects.requireNonNull(profile, "profile is required");
    InferenceConfig current = manager.currentConfig();
    if (current == null) {
      throw new IllegalStateException("Inference runtime not configured");
    }

    Path modelsDir = resolveModelsDir(current);
    Path modelPath = modelsDir.resolve(profile.modelFile());
    if (!Files.isRegularFile(modelPath)) {
      // Fail closed, unlike the projector below: llama-server refuses to start on an unresolvable
      // --model, so restarting the engine onto a file that is not there turns a profile switch
      // into an outage. The HTTP path never gets here (RuntimeActivationService pre-checks and
      // fails with a typed MODEL_NOT_FOUND); this is the same verdict for direct programmatic
      // callers of OnlineAiRuntimeControl.applyChatProfile. Thrown BEFORE any applyConfig, so the
      // running engine is left exactly as it was.
      throw new IllegalStateException(missingProfileModelMessage(profile, modelPath));
    }
    Path mmprojPath = modelsDir.resolve(profile.mmprojFile());
    if (!Files.exists(mmprojPath)) {
      // Same rule as fromEnvironment: a missing projector degrades to text-only with a WARN
      // rather than failing the switch — llama-server refuses to start on an unresolvable
      // --mmproj path, which would turn a profile switch into an outage.
      LOG.warn(
          "Chat profile '{}': mmproj not found at {}. Vision features will be disabled.",
          profile.id(),
          mmprojPath);
      mmprojPath = null;
    }

    InferenceConfig next =
        new InferenceConfig(
            current.serverExecutable(),
            modelPath,
            mmprojPath,
            current.serverPort(),
            current.contextSize(),
            current.gpuLayers(),
            current.vduMode(),
            profile.id());

    LOG.info(
        "Applying chat profile '{}': model={} mmproj={} (modelsDir={})",
        profile.id(),
        modelPath,
        mmprojPath,
        modelsDir);

    try {
      manager.applyConfig(
          next,
          mapRestartPolicy(restartPolicy),
          io.justsearch.app.inference.telemetry.TransitionReason.CONFIG_APPLY);
    } catch (ModeTransitionException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  /**
   * Failure text for a profile whose model file is not on disk.
   *
   * <p>Deliberately word-for-word with {@code RuntimeActivationService.missingProfileModelMessage}
   * (the API-path counterpart) so both surfaces name the same remedy: the compact bundle is a
   * dev-only package excluded from user install plans, so "Run Install AI" would send the reader
   * somewhere that will never fetch it. Not shared as one helper because that class lives in
   * {@code :modules:app-services}, which depends on this module rather than the other way round.
   */
  private static String missingProfileModelMessage(ChatModelProfile profile, Path resolved) {
    String remedy =
        profile == ChatModelProfile.COMPACT
            ? " Run `node scripts/dev/fetch-compact-model.mjs` to download it."
            : " Run Install AI to download it.";
    return "Chat profile '" + profile.id() + "' model does not exist: " + resolved + "." + remedy;
  }

  /**
   * Resolves the models directory a profile's relative filenames hang off.
   *
   * <p>Deliberately the <em>configured</em> models dir, not {@code currentConfig().modelPath()
   * .getParent()}. A profile's {@code modelFile()} / {@code mmprojFile()} are registry
   * {@code targetDir}-relative names ("compact/Qwen3.5-4B-...gguf"), so they are only meaningful
   * under the configured models root. When the running config came from an operator path, its
   * parent is an arbitrary directory that happens to hold one GGUF — resolving a profile against
   * it would point at files that were never installed there.
   * {@code InferenceConfig.fromEnvironment} makes the same choice: its {@code associatedModelsDir}
   * follows the model path only for the override branch, and the profile branch always uses the
   * configured models dir.
   *
   * <p>Falls back to the current model's parent only when there is no ConfigStore to ask.
   */
  private static Path resolveModelsDir(InferenceConfig current) {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs != null) {
      ResolvedConfig rc = cs.get();
      Path baseDir = ResolvedPathResolver.resolveBaseDir(rc, System.getProperty("user.dir"));
      return ResolvedPathResolver.resolveModelsDir(rc, baseDir);
    }
    Path parent = current.modelPath() != null ? current.modelPath().getParent() : null;
    return parent != null ? parent : Path.of(".");
  }

  @Override
  public DetachExternalServerResult detachExternalServer() {
    try {
      var r = manager.detachExternalServer();
      return new DetachExternalServerResult(r.detached(), r.previousPort(), r.newPort());
    } catch (ModeTransitionException e) {
      throw new RuntimeException(e.getMessage(), e);
    }
  }

  @Override
  public RuntimeInfo runtimeInfo() {
    InferenceConfig cfg = manager.currentConfig();
    if (cfg == null) {
      return null;
    }
    return new RuntimeInfo(
        cfg.serverExecutable() == null ? null : cfg.serverExecutable().toString(),
        cfg.modelPath() == null ? null : cfg.modelPath().toString(),
        cfg.mmprojPath() == null ? null : cfg.mmprojPath().toString(),
        cfg.serverPort(),
        cfg.contextSize(),
        cfg.gpuLayers(),
        manager.isUsingExternalLlamaServer());
  }

  @Override
  public ExternalServerStatus externalServerStatus() {
    var d = manager.externalServerDiagnostics();
    return new ExternalServerStatus(
        d.usingExternalLlamaServer(),
        d.verified(),
        d.verificationError(),
        d.modelId(),
        d.contextTokens(),
        d.modelMismatch(),
        d.contextTooSmall(),
        d.adoptedAtMs(),
        d.lastHealthOkAtMs(),
        d.lastHealthError(),
        d.consecutiveHealthFailures());
  }

  @Override
  public ContextWindow contextWindow() {
    return manager.launchedContextWindow();
  }

  @Override
  public String cudaRuntimeWarning() {
    return manager.getCudaRuntimeWarning();
  }

  @Override
  public long lastStartupDurationMs() {
    return manager.getLastStartupDurationMs();
  }

  @Override
  public boolean hasVisionCapability() {
    return manager.hasVisionCapability();
  }

  @Override
  public CompletableFuture<OnlineAiService.VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens) {
    return missingContext("visionCompletionDetailed");
  }

  /** Tempdoc 677 Stage 2: sampling/seed-override overload — see {@link InferenceLifecycleManager}. */
  @Override
  public CompletableFuture<OnlineAiService.VisionCompletionResult> visionCompletionDetailed(
      String prompt, byte[] imageBytes, int maxTokens, SamplingParams sampling, Long seed) {
    return missingContext("visionCompletionDetailed");
  }

  @Override
  public CompletableFuture<OnlineAiService.VisionCompletionResult> visionCompletionDetailed(
      String prompt,
      byte[] imageBytes,
      int maxTokens,
      SamplingParams sampling,
      Long seed,
      io.justsearch.core.context.EngineContext engineContext) {
    int resolved = maxTokens <= 0 ? DEFAULT_QA_TOKENS : maxTokens;
    try (var work = admission.attach(engineContext)) {
      return manager.visionCompletionDetailed(prompt, imageBytes, resolved, sampling, seed, work);
    }
  }

  @Override
  public String activeModelId() {
    return manager.lastKnownModelId();
  }

  @Override
  public List<FailureRecord>
      recentFailures(int limit) {
    return manager.recentFailures(limit);
  }

  @Override
  public long currentGeneration() {
    return manager.currentGeneration();
  }

  @Override
  public boolean isOnline() {
    return manager.isOnline();
  }

  @Override
  public boolean isIndexing() {
    return manager.isIndexing();
  }

  @Override
  public List<TransitionRecord>
      recentTransitions(int limit) {
    return manager.recentTransitions(limit);
  }

  private static InferenceLifecycleManager.RestartPolicy mapRestartPolicy(RestartPolicy policy) {
    if (policy == null) {
      return InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE;
    }
    return switch (policy) {
      case APPLY_ONLY -> InferenceLifecycleManager.RestartPolicy.APPLY_ONLY;
      case RESTART_ALWAYS -> InferenceLifecycleManager.RestartPolicy.RESTART_ALWAYS;
      case RESTART_IF_ONLINE -> InferenceLifecycleManager.RestartPolicy.RESTART_IF_ONLINE;
    };
  }

  private static InferenceConfig applyOverrides(
      InferenceConfig base,
      String llmModelPath,
      Integer contextLength,
      Integer gpuLayers) {
    Path serverExe = base.serverExecutable();
    Path modelPath = base.modelPath();
    Path mmprojPath = base.mmprojPath();
    String chatProfileId = base.chatProfileId();

    // Allow out-of-band override for BYO llama-server path (set via UI settings -> sysprop).
    ConfigStore cs = ConfigStore.globalOrNull();
    String serverOverride = cs != null && cs.get().ai().serverExe() != null
        ? cs.get().ai().serverExe().toString() : null;
    if (serverOverride != null && !serverOverride.isBlank()) {
      try {
        Path p = Path.of(serverOverride.trim());
        serverExe = p;
      } catch (Exception ignored) {
        // Keep existing.
      }
    }

    if (llmModelPath != null && !llmModelPath.isBlank()) {
      Path resolved = resolveOptionalPath(llmModelPath, base.modelPath().getParent());
      boolean changed = !resolved.toAbsolutePath().normalize().equals(base.modelPath().toAbsolutePath().normalize());
      modelPath = resolved;
      // Defensive: mmproj/model mismatches can fail llama-server startup. When the user explicitly changes
      // the model file path, prefer text-only mode unless mmproj is independently configured elsewhere.
      if (changed) {
        mmprojPath = null;
        // The profile claim describes the PAIR. Once a bare path replaces the model, the old id
        // no longer names what is loaded, and a stale claim is worse than none — every surface
        // that reports realized chat identity would report a model that is not running.
        chatProfileId = null;
      }
    }

    int ctx = contextLength != null && contextLength > 0 ? contextLength : base.contextSize();
    // Tempdoc 374 alpha.13 fix A2: 0 from UiSettings means "unset" — defer to
    // base.gpuLayers() which already reflects the resolved config (env vars,
    // sysprops, auto-detection at ordinal 150). The previous `>= 0` check
    // treated UiSettings.gpuLayers default 0 as an explicit override, so every
    // Install AI completion silently clobbered a correctly-resolved 99 with 0
    // — defeating both the auto-detect path and the JUSTSEARCH_LLM_GPU_LAYERS
    // env-var workaround. Explicit user overrides (>0) still take precedence.
    int layers = gpuLayers != null && gpuLayers > 0 ? gpuLayers : base.gpuLayers();

    return new InferenceConfig(
        serverExe,
        modelPath,
        mmprojPath,
        base.serverPort(),
        ctx,
        layers,
        base.vduMode(),
        chatProfileId);
  }

  private static Path resolveOptionalPath(String raw, Path fallbackDir) {
    String trimmed = raw == null ? "" : raw.trim();
    if (trimmed.isBlank()) {
      return fallbackDir;
    }
    Path p = Path.of(trimmed);
    if (p.isAbsolute()) {
      return p;
    }
    if (Files.exists(p)) {
      return p;
    }
    return (fallbackDir == null ? Path.of(System.getProperty("user.dir")) : fallbackDir).resolve(p);
  }

  // ==================== Streaming Methods ====================

  // Tempdoc 499: All streamChat/streamChatWithTools overrides removed. The interface
  // default methods delegate to stream(), which is the single implementation entry point.
  // This eliminates the channel-lossy path — all callers route through StreamSink which
  // exposes all channels (content, reasoning, tools, usage).

  @Override
  public void stream(StreamRequest request, StreamSink sink) {
    if (request.work() == null) {
      sink.onError().accept(
          new IllegalArgumentException("OnlineAiService stream requires an Engine work owner"));
      return;
    }
    int resolved = request.maxTokens() <= 0 ? DEFAULT_QA_TOKENS : request.maxTokens();
    LOG.debug(
        "stream(maxTokens={}, tools={}, sentinel={}) called, messages={}",
        resolved,
        request.tools() != null ? request.tools().size() : 0,
        request.requireSentinel(),
        request.messages() != null ? request.messages().size() : 0);
    manager.stream(request.messages(), request.tools(), resolved,
        sink.onContent(), sink.onReasoning(),
        node -> {
          try {
            sink.onToolCallDelta().accept(node.toString());
          } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
          } catch (Exception e) {
            LOG.debug("Tool call delta callback error", e);
          }
        },
        sink.onUsage(), sink.onComplete(), sink.onError(),
        request.sampling(), request.requireSentinel(), request.work());
  }

  // ==================== Non-Streaming Methods ====================

  @Override
  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages, int maxTokens, SamplingParams sampling) {
    return missingContext("chatCompletion");
  }

  @Override
  public CompletableFuture<String> chatCompletion(
      List<Map<String, Object>> messages,
      int maxTokens,
      SamplingParams sampling,
      io.justsearch.core.context.EngineContext engineContext) {
    int resolved = maxTokens <= 0 ? DEFAULT_QA_TOKENS : maxTokens;
    LOG.debug(
        "chatCompletion(maxTokens={}, sampling={}) called, messages={}",
        resolved,
        sampling,
        messages != null ? messages.size() : 0);
    try (var work = admission.attach(engineContext)) {
      return manager.chatCompletion(messages, resolved, sampling, work);
    }
  }

  @Override
  public CompletableFuture<String> summarize(String content) {
    return missingContext("summarize");
  }

  @Override
  public CompletableFuture<String> summarize(String content, int maxTokens) {
    return missingContext("summarize");
  }

  @Override
  public CompletableFuture<String> summarize(
      String content, int maxTokens, io.justsearch.core.context.EngineContext engineContext) {
    int resolved = maxTokens <= 0 ? DEFAULT_SUMMARY_TOKENS : maxTokens;
    LOG.debug(
        "summarize(maxTokens={}) called, content length: {}",
        resolved,
        content != null ? content.length() : 0);
    try (var work = admission.attach(engineContext)) {
      return manager.summarize(content, resolved, work);
    }
  }

  @Override
  public CompletableFuture<String> askQuestion(String question, String context) {
    return missingContext("askQuestion");
  }

  @Override
  public CompletableFuture<String> askQuestion(
      String question,
      String context,
      io.justsearch.core.context.EngineContext engineContext) {
    LOG.debug("askQuestion called, question: {}, context length: {}",
        question, context != null ? context.length() : 0);
    try (var work = admission.attach(engineContext)) {
      return manager.askQuestion(context, question, DEFAULT_QA_TOKENS, work);
    }
  }

  // ==================== Status Methods ====================

  @Override
  public boolean isAvailable() {
    return manager.isOnline();
  }

  @Override
  public boolean isStartingUp() {
    return manager.getCurrentMode() == io.justsearch.app.api.Mode.TRANSITIONING;
  }

  @Override
  public Integer llmContextTokens() {
    return manager.lastKnownContextTokens();
  }

  @Override
  public Integer configuredContextTokens() {
    return manager.configuredContextTokens();
  }

  @Override
  public java.util.Optional<Integer> countTokens(String text) {
    throw new IllegalStateException("OnlineAiService countTokens requires EngineContext");
  }

  @Override
  public java.util.Optional<Integer> countTokens(
      String text, io.justsearch.core.context.EngineContext engineContext) {
    try (var work = admission.attach(engineContext)) {
      return manager.countTokens(text, work);
    }
  }

  @Override
  public java.util.Optional<Integer> countPromptTokens(List<Map<String, Object>> messages) {
    throw new IllegalStateException("OnlineAiService countPromptTokens requires EngineContext");
  }

  @Override
  public java.util.Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
    throw new IllegalStateException("OnlineAiService countPromptTokens requires EngineContext");
  }

  @Override
  public java.util.Optional<Integer> countPromptTokens(
      List<Map<String, Object>> messages,
      List<Map<String, Object>> tools,
      io.justsearch.core.context.EngineContext engineContext) {
    try (var work = admission.attach(engineContext)) {
      return manager.countPromptTokens(messages, tools, work);
    }
  }

  private static <T> CompletableFuture<T> missingContext(String operation) {
    return CompletableFuture.failedFuture(
        new IllegalStateException("OnlineAiService " + operation + " requires EngineContext"));
  }

  // ==================== Mode Control Methods ====================

  @Override
  public String getCurrentMode() {
    return manager.getCurrentMode().name().toLowerCase(java.util.Locale.ROOT);
  }

  @Override
  public void switchToOnlineMode() {
    LOG.info("Switching to Online Mode...");
    try {
      manager.switchToOnlineMode();
      LOG.info("Switched to Online Mode");
    } catch (ModeTransitionException e) {
      LOG.error("Failed to switch to Online Mode", e);
      throw new RuntimeException("Failed to switch to Online Mode: " + e.getMessage(), e);
    }
  }

  @Override
  public void switchToIndexingMode() {
    LOG.info("Switching to Indexing Mode...");
    try {
      manager.switchToIndexingMode();
      LOG.info("Switched to Indexing Mode");
    } catch (ModeTransitionException e) {
      LOG.error("Failed to switch to Indexing Mode", e);
      throw new RuntimeException("Failed to switch to Indexing Mode: " + e.getMessage(), e);
    }
  }

  // ==================== OnlineAiLifecycleControl (tempdoc 518 P4) ====================

  @Override
  public void enterVduMode() throws ModeTransitionException {
    manager.enterVduMode();
  }

  @Override
  public void exitVduMode() throws ModeTransitionException {
    manager.exitVduMode();
  }

  @Override
  public void addModeChangeListener(ModeChangeListener listener) {
    manager.addModeChangeListener(listener);
  }

  @Override
  public void removeModeChangeListener(ModeChangeListener listener) {
    manager.removeModeChangeListener(listener);
  }
}
