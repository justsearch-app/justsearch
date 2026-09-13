package io.justsearch.app.services.ai.runtime;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.api.AiRuntimeActivationStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallService;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.worker.OnnxModelStatus;
import io.justsearch.app.services.worker.WorkerFeatureCache;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.services.policy.EnterprisePolicyServiceImpl;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.core.execution.TestEngineExecutors;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class RuntimeActivationServiceTest {

  private final TestEngineExecutors processExecutors = new TestEngineExecutors();

  @TempDir Path tmp;

  private String prevHome;
  private final Map<String, String> prevProps = new HashMap<>();

  @AfterEach
  void cleanup() {
    if (prevHome == null) System.clearProperty("justsearch.home");
    else System.setProperty("justsearch.home", prevHome);
    // Restore any system properties set during tests
    for (var entry : prevProps.entrySet()) {
      if (entry.getValue() == null) System.clearProperty(entry.getKey());
      else System.setProperty(entry.getKey(), entry.getValue());
    }
    prevProps.clear();
    processExecutors.close();
  }

  @Test
  void stalePersistedActivationStatusResetsToIdleOnStartup() throws Exception {
    setHome(tmp);
    Files.createDirectories(tmp.resolve("ai"));
    Files.writeString(
        tmp.resolve("ai/runtime-activation-state.json"),
        """
        {"state":"running","phase":"self_test","message":"stale","errorCode":"",
         "selfTestPort":12345,"startedAtEpochMs":1,"updatedAtEpochMs":2}
        """);

    RuntimeActivationService service =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            new EnterprisePolicyServiceImpl());

    AiRuntimeActivationStatus status = service.getActivationStatus();
    assertEquals("idle", status.state);
    assertEquals("", status.phase);
    assertEquals(0L, status.selfTestPort);
    assertFalse(
        Files.readString(tmp.resolve("ai/runtime-activation-state.json"))
            .contains("\"state\" : \"running\""));
  }

  @Test
  void activationBlockedWhenGpuDisabledByPolicy() throws Exception {
    setHome(tmp);

    Files.writeString(
        tmp.resolve("policy.v1.json"),
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-26T00:00:00Z",
          "downloadsEnabled": true,
          "onlineAiEnabled": true,
          "gpuAccelerationEnabled": false,
          "disallowExternalInferenceServers": false,
          "allowlists": {
            "packManifestSha256": [],
            "modelSha256": []
          }
        }
        """,
        StandardCharsets.UTF_8);

    EnterprisePolicyService policy = new EnterprisePolicyServiceImpl();
    RuntimeActivationService svc =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            policy);

    svc.startActivate("cuda-12.4");
    AiRuntimeActivationStatus st = awaitDone(svc);
    assertEquals("failed", st.state);
    assertEquals("POLICY_GPU_DISABLED", st.errorCode);
  }

  @Test
  void activationBlockedWhenOnlineAiDisabledByPolicy() throws Exception {
    setHome(tmp);

    Files.writeString(
        tmp.resolve("policy.v1.json"),
        """
        {
          "schemaVersion": 1,
          "updatedAt": "2025-12-26T00:00:00Z",
          "downloadsEnabled": true,
          "onlineAiEnabled": false,
          "gpuAccelerationEnabled": true,
          "disallowExternalInferenceServers": false,
          "allowlists": {
            "packManifestSha256": [],
            "modelSha256": []
          }
        }
        """,
        StandardCharsets.UTF_8);

    EnterprisePolicyService policy = new EnterprisePolicyServiceImpl();
    RuntimeActivationService svc =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null,
            policy);

    svc.startActivate("cuda-12.4");
    AiRuntimeActivationStatus st = awaitDone(svc);
    assertEquals("failed", st.state);
    assertEquals("POLICY_ONLINE_AI_DISABLED", st.errorCode);
  }

  // --------------- ONNX feature status tests (D-4, tempdoc 215) ---------------

  @Test
  void onnxFeatureActiveWhenCacheReportsFound() {
    setHome(tmp);
    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", true, "C:\\models\\citation-scorer", true, true));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    // reranker, citation_scorer (discovery-derived) + embed, splade (tempdoc 806 B.2 — policy-
    // snapshot-derived, so they are present even though WorkerModelDiscovery never enumerates them).
    assertEquals(4, features.size());
    assertEquals("active", features.get(0).status());
    assertEquals("auto_discovered", features.get(0).reason());
    assertEquals("C:\\models\\reranker", features.get(0).modelPath());
    assertEquals("active", features.get(1).status());
    assertEquals("auto_discovered", features.get(1).reason());
    assertEquals("C:\\models\\citation-scorer", features.get(1).modelPath());
  }

  @Test
  void onnxFeatureInactiveWhenCacheReportsNotFound() {
    setHome(tmp);
    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", false, null, false, false),
        new OnnxModelStatus("citation-scorer", false, null, false, false));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    assertEquals("inactive", features.get(0).status());
    assertEquals("not_found", features.get(0).reason());
    assertNull(features.get(0).modelPath());
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  @Test
  void onnxFeatureInactiveWhenCacheIsNull() {
    setHome(tmp);
    // 4-arg constructor — no WorkerFeatureCache
    RuntimeActivationService svc =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null, null);

    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    assertEquals("inactive", features.get(0).status());
    assertEquals("not_found", features.get(0).reason());
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  @Test
  void onnxFeatureDisabledTakesPrecedenceOverCache() {
    setHome(tmp);
    setProp("justsearch.rerank.enabled", "false");

    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", true, "C:\\models\\citation-scorer", true, true));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    // Reranker disabled by env var — cache ignored
    assertEquals("inactive", features.get(0).status());
    assertEquals("disabled", features.get(0).reason());
    // Citation scorer not disabled — cache used
    assertEquals("active", features.get(1).status());
    assertEquals("auto_discovered", features.get(1).reason());
  }

  @Test
  void onnxFeatureExplicitPathTakesPrecedenceOverCache() {
    setHome(tmp);
    setProp("justsearch.rerank.model_path", "D:\\custom\\reranker");

    WorkerFeatureCache cache = () -> List.of(
        new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
        new OnnxModelStatus("citation-scorer", false, null, false, false));

    RuntimeActivationService svc = createServiceWithCache(cache);
    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    // Reranker uses explicit path — cache ignored
    assertEquals("active", features.get(0).status());
    assertEquals("explicit_path", features.get(0).reason());
    assertEquals("D:\\custom\\reranker", features.get(0).modelPath());
    // Citation scorer falls through to cache (not found)
    assertEquals("inactive", features.get(1).status());
    assertEquals("not_found", features.get(1).reason());
  }

  private RuntimeActivationService createServiceWithCache(WorkerFeatureCache cache) {
    return new RuntimeActivationService(processExecutors,
        OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
        null, null, cache);
  }

  // --------------- Observed execution provider (tempdoc 805 G.3, round-11 F3) ---------------

  /** The Worker's ONNX discovery said "found + session active" for both features. */
  private static final WorkerFeatureCache DISCOVERED_AND_ACTIVE =
      () ->
          List.of(
              new OnnxModelStatus("reranker", true, "C:\\models\\reranker", true, true),
              new OnnxModelStatus("citation-scorer", true, "C:\\models\\citation", true, true));

  private static io.justsearch.app.api.inference.EncoderRuntimeView cudaFallbackView() {
    // Exactly OrtCudaStatus.missingDlls' shape as WorkerStatusMapper maps it: configured + attempted
    // + unavailable, with the missing native names. Derived through the explainer so the test drives
    // the SAME derivation production uses, not a hand-built view.
    return io.justsearch.app.services.observability.EncoderRuntimeExplainer.explain(
        io.justsearch.ort.EncoderRole.RERANKER,
        new io.justsearch.app.api.status.OrtCudaView(
            true,
            true,
            false,
            "cuda12",
            "C:\\native-bin\\ort",
            "ORT CUDA native pack incomplete",
            List.of("onnxruntime_providers_cuda.dll")),
        Map.of("variant", Map.of("executionProvider", "CUDA")));
  }

  @Test
  void onnxFeatureReportsGpuFallback_whenTheOrtSessionRanOnCpu() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);
    svc.setEncoderRuntimeCache(
        () -> Map.of(io.justsearch.ort.EncoderRole.RERANKER, cudaFallbackView()));

    AiRuntimeStatusResponse.OnnxFeatureStatus reranker = svc.getStatus().onnxFeatures().get(0);

    // The round-11 lie: these two say "all good" for a CPU-fallback session — unchanged, because
    // they are honest about INTENT. The observed axis is what was missing.
    assertEquals("active", reranker.status(), "discovery-derived intent is unchanged (additive fields)");
    assertTrue(reranker.modelActive(), "a session DOES exist — that was never the wrong part");

    assertEquals("cpu", reranker.executionProvider(), "the session actually runs on CPU");
    assertTrue(reranker.gpuFallback(), "GPU was configured and did not happen — the round-11 defect");
    assertTrue(
        reranker.fallbackReason().contains("onnxruntime_providers_cuda.dll"),
        "the reason names the missing native, not just 'GPU unavailable': " + reranker.fallbackReason());
  }

  @Test
  void onnxFeatureReportsCuda_whenTheOrtSessionIsOnGpu() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);
    svc.setEncoderRuntimeCache(
        () ->
            Map.of(
                io.justsearch.ort.EncoderRole.RERANKER,
                io.justsearch.app.services.observability.EncoderRuntimeExplainer.explain(
                    io.justsearch.ort.EncoderRole.RERANKER,
                    new io.justsearch.app.api.status.OrtCudaView(
                        true, true, true, "cuda12", "C:\\native-bin\\ort", "", List.of()),
                    Map.of("variant", Map.of("executionProvider", "CUDA")))));

    AiRuntimeStatusResponse.OnnxFeatureStatus reranker = svc.getStatus().onnxFeatures().get(0);

    assertEquals("cuda", reranker.executionProvider());
    assertFalse(reranker.gpuFallback(), "a GPU session is not a fallback");
    assertNull(reranker.fallbackReason());
  }

  @Test
  void onnxFeatureReportsUnknownEp_whenTheWorkerHasNotAnswered() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);

    AiRuntimeStatusResponse.OnnxFeatureStatus reranker = svc.getStatus().onnxFeatures().get(0);

    assertEquals(
        "unknown",
        reranker.executionProvider(),
        "no observation degrades to unknown — never to a positive 'cuda' claim");
    assertFalse(reranker.gpuFallback());
  }

  @Test
  void cpuByDesignIsNotAFallback() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);
    // citation-scorer is CPU-only by design (EncoderRole.isCpuOnly) — the policy says CPU, so
    // running on CPU is the intent being met, not a degradation.
    svc.setEncoderRuntimeCache(
        () ->
            Map.of(
                io.justsearch.ort.EncoderRole.CITATION,
                io.justsearch.app.services.observability.EncoderRuntimeExplainer.explain(
                    io.justsearch.ort.EncoderRole.CITATION,
                    io.justsearch.app.api.status.OrtCudaView.notConfigured(),
                    Map.of("variant", Map.of("executionProvider", "CPU")))));

    AiRuntimeStatusResponse.OnnxFeatureStatus citation = svc.getStatus().onnxFeatures().get(1);

    assertEquals("cpu", citation.executionProvider());
    assertFalse(citation.gpuFallback(), "CPU by design must never read as a GPU fallback");
    assertNull(citation.fallbackReason());
  }

  // ------- Observed EP for the always-on Worker encoders (tempdoc 806 B.2, round-12) -------

  private static io.justsearch.app.api.inference.EncoderRuntimeView fallbackViewFor(
      io.justsearch.ort.EncoderRole role) {
    return io.justsearch.app.services.observability.EncoderRuntimeExplainer.explain(
        role,
        new io.justsearch.app.api.status.OrtCudaView(
            true,
            true,
            false,
            "cuda12",
            "C:\\native-bin\\ort",
            "ORT CUDA native pack incomplete",
            List.of("onnxruntime_providers_cuda.dll")),
        Map.of("variant", Map.of("executionProvider", "CUDA")));
  }

  private static AiRuntimeStatusResponse.OnnxFeatureStatus row(
      List<AiRuntimeStatusResponse.OnnxFeatureStatus> features, String id) {
    return features.stream()
        .filter(f -> id.equals(f.id()))
        .findFirst()
        .orElseGet(() -> fail("no onnxFeatures row with id=" + id + " in " + features));
  }

  /**
   * Round 11's fallback hit embed and SPLADE too; round 12 could not assert it over the API because
   * {@code onnxFeatures} carried only the two Head-configured cross-encoders.
   */
  @Test
  void embedAndSpladeCarryObservedEpFromTheSameExplainerAuthority() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);
    svc.setEncoderRuntimeCache(
        () ->
            Map.of(
                io.justsearch.ort.EncoderRole.EMBEDDING,
                fallbackViewFor(io.justsearch.ort.EncoderRole.EMBEDDING),
                io.justsearch.ort.EncoderRole.SPLADE,
                fallbackViewFor(io.justsearch.ort.EncoderRole.SPLADE)));

    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    for (String id : List.of("embed", "splade")) {
      AiRuntimeStatusResponse.OnnxFeatureStatus f = row(features, id);
      assertEquals("active", f.status(), id + " is named by the policy snapshot");
      assertEquals("worker_policy_snapshot", f.reason(), id + " intent source");
      assertTrue(f.modelActive(), id + " has a live session");
      assertEquals("cpu", f.executionProvider(), id + " actually runs on CPU");
      assertTrue(f.gpuFallback(), id + " was configured for CUDA and did not get it");
      assertTrue(
          f.fallbackReason().contains("onnxruntime_providers_cuda.dll"),
          id + " reason names the missing native: " + f.fallbackReason());
    }
  }

  @Test
  void embedAndSpladeReportUnknownRatherThanInactiveBeforeTheWorkerAnswers() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);

    List<AiRuntimeStatusResponse.OnnxFeatureStatus> features = svc.getStatus().onnxFeatures();

    for (String id : List.of("embed", "splade")) {
      AiRuntimeStatusResponse.OnnxFeatureStatus f = row(features, id);
      assertEquals(
          "unknown",
          f.status(),
          id + ": no policy snapshot is not evidence the encoder is inactive");
      assertEquals("worker_not_answered", f.reason());
      assertFalse(f.modelActive(), id + " must not claim a session it cannot see");
      assertEquals("unknown", f.executionProvider());
      assertFalse(f.gpuFallback());
    }
  }

  @Test
  void embedAndSpladeReportInactiveWhenThePolicySnapshotSaysNotConfigured() {
    setHome(tmp);
    RuntimeActivationService svc = createServiceWithCache(DISCOVERED_AND_ACTIVE);
    svc.setEncoderRuntimeCache(
        () ->
            Map.of(
                io.justsearch.ort.EncoderRole.SPLADE,
                io.justsearch.app.services.observability.EncoderRuntimeExplainer.explain(
                    io.justsearch.ort.EncoderRole.SPLADE,
                    io.justsearch.app.api.status.OrtCudaView.notConfigured(),
                    null)));

    AiRuntimeStatusResponse.OnnxFeatureStatus splade = row(svc.getStatus().onnxFeatures(), "splade");

    assertEquals("inactive", splade.status(), "the snapshot answered: not part of this config");
    assertEquals("not_configured", splade.reason());
    assertFalse(splade.modelActive());
    assertEquals("none", splade.executionProvider(), "not-configured is 'none', not 'unknown'");
    assertFalse(splade.gpuFallback());
  }

  // --------------- Tempdoc 727 F-3: leftover-variant WARN false positive ---------------

  /**
   * Reproduces the round's evidence: a fresh install's own in-flight cuda-runtime extraction
   * creates {@code variants/cuda12} before {@code llama-server.exe} is staged, which pre-fix
   * code misdiagnosed as "leftover from a previous build" — and, because {@code
   * listInstalledVariants()} runs on every status poll, logged it once per poll (~1/sec).
   */
  @Test
  void leftoverVariantWarnSuppressedWhileInstallServiceReportsRunning() throws Exception {
    setHome(tmp);
    Path variantDir = createEmptyVariantDir("cuda12");

    FakeAiInstallService installService = new FakeAiInstallService("running");
    RuntimeActivationService svc = createServiceWithInstallHelper(installService);

    List<ILoggingEvent> events = captureLogsDuring(() -> svc.getStatus());

    assertFalse(
        events.stream().anyMatch(e -> e.getLevel() == Level.WARN && leftoverMessage(e, variantDir)),
        "must not warn while the AI install run is still \"running\" — the directory is this"
            + " install's own in-flight extraction, not a leftover");
  }

  /**
   * Filesystem fallback guard: even with no {@code AiInstallService} wired (e.g. an older
   * composition path), a {@code *.tmp} file directly in the variant directory — the on-disk
   * signature of a Windows BITS in-progress transfer — must suppress the WARN too.
   */
  @Test
  void leftoverVariantWarnSuppressedWhileTmpFilePresent() throws Exception {
    setHome(tmp);
    Path variantDir = createEmptyVariantDir("cuda12");
    Files.writeString(variantDir.resolve("BITAA6D.tmp"), "partial-download", StandardCharsets.UTF_8);

    RuntimeActivationService svc =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(),
            new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
            null, null);

    List<ILoggingEvent> events = captureLogsDuring(() -> svc.getStatus());

    assertFalse(
        events.stream().anyMatch(e -> e.getLevel() == Level.WARN && leftoverMessage(e, variantDir)),
        "must not warn while a *.tmp file is present in the variant dir");
  }

  /**
   * A genuine leftover (no install running, no *.tmp) must still be logged — but only once per
   * process lifetime, not once per status poll. Pre-fix code logged a fresh WARN on every one of
   * the 3 {@code getStatus()} calls below (the round observed ~1/sec spam); this asserts exactly
   * one line survives across repeated polling.
   */
  @Test
  void genuineLeftoverVariantWarnLoggedOnceNotPerPoll() throws Exception {
    setHome(tmp);
    Path variantDir = createEmptyVariantDir("cuda12");

    FakeAiInstallService installService = new FakeAiInstallService("idle");
    RuntimeActivationService svc = createServiceWithInstallHelper(installService);

    List<ILoggingEvent> events =
        captureLogsDuring(
            () -> {
              svc.getStatus();
              svc.getStatus();
              svc.getStatus();
            });

    long warnCount =
        events.stream().filter(e -> e.getLevel() == Level.WARN && leftoverMessage(e, variantDir)).count();
    assertEquals(
        1,
        warnCount,
        "genuine leftover must warn exactly once across repeated polls, not once per poll");
  }

  // --------------- Tempdoc 804 §B2: chat-model resolution chain ---------------

  /**
   * Round 10's F3 shape: settings hold no {@code llmModelPath} (the shipped prod build discarded
   * every settings write) while the install contract still records exactly which chat model Install
   * AI placed on disk. Activation must resolve through the contract and get PAST the model-path
   * check — the later failure (a dummy exe cannot self-test) is expected and irrelevant here.
   */
  @Test
  void blankSettingsResolvesChatModelFromInstallContract() throws Exception {
    setHome(tmp);
    createVariantExe("cuda12");
    Path chatModel = seedContract("model.gguf", true);
    assertTrue(Files.isRegularFile(chatModel));

    RuntimeActivationService svc = createServiceWithSettingsFile();
    svc.startActivate("cuda12");
    AiRuntimeActivationStatus st = awaitDone(svc, 60_000);

    assertNotEquals(
        "MODEL_PATH_REQUIRED",
        st.errorCode,
        "install contract records a chat model — activation must not claim none is configured: " + st.message);
    // Precision: reaching the GPU self-test is only possible AFTER both the model-path check and
    // the MODEL_NOT_FOUND existence check, so a blank result would mean the test passed for the
    // wrong reason (e.g. an earlier variant/policy failure) rather than because the fallback fired.
    assertFalse(
        st.result == null || st.result.isBlank(),
        "activation must reach the GPU self-test (state=" + st.state + ", errorCode=" + st.errorCode + ")");
  }

  /**
   * Settings win: an explicit user choice is not overridden by the contract fallback. The contract
   * deliberately names a file that does NOT exist, so an inverted precedence would surface as
   * {@code MODEL_NOT_FOUND} instead of silently passing.
   */
  @Test
  void settingsModelPathWinsOverInstallContract() throws Exception {
    setHome(tmp);
    createVariantExe("cuda12");
    seedContract("ghost.gguf", false);

    Path chosen = tmp.resolve("chosen.gguf");
    Files.writeString(chosen, "user-chosen-model", StandardCharsets.UTF_8);
    Path settingsFile = tmp.resolve("settings.json");
    UiSettingsStore store =
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, settingsFile);
    io.justsearch.app.api.UiSettings s = store.load();
    s.setLlmModelPath(chosen.toAbsolutePath().toString());
    store.save(s);

    RuntimeActivationService svc =
        new RuntimeActivationService(processExecutors,
            OnlineAiService.unavailable(), store, null, new EnterprisePolicyServiceImpl());
    svc.startActivate("cuda12");
    AiRuntimeActivationStatus st = awaitDone(svc, 60_000);

    assertNotEquals(
        "MODEL_NOT_FOUND",
        st.errorCode,
        "settings named an existing model — the contract's (missing) file must not be used: " + st.message);
    assertFalse(
        st.result == null || st.result.isBlank(),
        "activation must reach the GPU self-test with the settings-provided model");
    assertEquals(
        chosen.toAbsolutePath().toString(),
        store.load().getLlmModelPath(),
        "the user's explicit model path must survive activation untouched");
  }

  /**
   * Neither settings nor contract: the failure must name a remedy that exists. "Import a models
   * pack first" described a dependency the product does not have (tempdoc 804 §B2).
   */
  @Test
  void noSettingsAndNoContractFailsWithInstallAiRemedy() throws Exception {
    setHome(tmp);
    createVariantExe("cuda12");

    RuntimeActivationService svc = createServiceWithSettingsFile();
    svc.startActivate("cuda12");
    AiRuntimeActivationStatus st = awaitDone(svc, 30_000);

    assertEquals("failed", st.state);
    assertEquals("MODEL_PATH_REQUIRED", st.errorCode);
    assertEquals(
        "No chat model configured. Run Install AI to download one, or import a models pack.",
        st.message);
  }

  private RuntimeActivationService createServiceWithSettingsFile() {
    return new RuntimeActivationService(processExecutors,
        OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE, tmp.resolve("settings.json")),
        null,
        new EnterprisePolicyServiceImpl());
  }

  private Path createVariantExe(String variantId) throws Exception {
    Path variantDir = createEmptyVariantDir(variantId);
    Path exe = variantDir.resolve("llama-server.exe");
    Files.writeString(exe, "not-a-real-exe", StandardCharsets.UTF_8);
    return exe;
  }

  /**
   * Seeds the v0.1.0-shaped state: a populated install contract naming the chat model, and (when
   * {@code createFile}) the model file itself under the contract-recorded models dir. Written
   * through {@link InstallContractIO} — the same writer {@code AiInstallService} uses.
   */
  private Path seedContract(String filename, boolean createFile) throws Exception {
    Path modelsDir = tmp.resolve("models");
    Path chatDir = modelsDir.resolve("chat");
    Files.createDirectories(chatDir);
    Path modelFile = chatDir.resolve(filename);
    if (createFile) {
      Files.writeString(modelFile, "gguf-bytes", StandardCharsets.UTF_8);
    }

    InstallContract contract =
        new InstallContract(
            2,
            System.currentTimeMillis(),
            HardwareProfile.cpuOnly(),
            DownloadProfile.GPU_FULL,
            Map.of(
                "chat",
                new InstallContract.InstalledModel(
                    "chat", filename, null, null, "chat", "sha", List.of(filename), false, null)),
            modelsDir.toAbsolutePath(),
            null);
    InstallContractIO.write(contract, tmp);
    return modelFile;
  }

  private Path createEmptyVariantDir(String variantId) throws Exception {
    Path variantsRoot = tmp.resolve("native-bin").resolve("llama-server").resolve("variants");
    Path variantDir = variantsRoot.resolve(variantId);
    Files.createDirectories(variantDir);
    return variantDir;
  }

  private RuntimeActivationService createServiceWithInstallHelper(AiInstallService installService) {
    return new RuntimeActivationService(processExecutors,
        OnlineAiService.unavailable(),
        new UiSettingsStore(UiSettingsStore.PersistenceMode.READ_WRITE),
        null,
        null,
        null,
        null,
        installService);
  }

  private static boolean leftoverMessage(ILoggingEvent e, Path variantDir) {
    return e.getFormattedMessage().contains("likely leftover from a previous build")
        && e.getFormattedMessage().contains(variantDir.toString());
  }

  /** Attaches a ListAppender to RuntimeActivationService's logger for the duration of {@code action}. */
  private static List<ILoggingEvent> captureLogsDuring(ThrowingRunnable action) throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(RuntimeActivationService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      action.run();
      return List.copyOf(appender.list);
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** Minimal stub of {@link AiInstallService} — only {@link #getStatus()} is exercised. */
  private static final class FakeAiInstallService implements AiInstallService {
    private final String state;

    FakeAiInstallService(String state) {
      this.state = state;
    }

    @Override
    public ModelRegistry getManifest() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public AiInstallStatus getStatus() {
      AiInstallStatus status = new AiInstallStatus();
      status.state = state;
      return status;
    }

    @Override
    public InstallPlanPreview previewInstallPlan() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public AiInstallService.Attempt startInstall(boolean acceptTerms) {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void cancel() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public AiInstallService.Attempt repair(boolean acceptTerms) throws AiInstallException {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void pauseInstall() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void resumeInstall() {
      throw new UnsupportedOperationException("not used by this test");
    }

    @Override
    public void setPackageDeclined(String packageId, boolean declined) {
      throw new UnsupportedOperationException("not used by this test");
    }
  }

  /** Sets a system property and records the previous value for cleanup. */
  private void setProp(String key, String value) {
    prevProps.putIfAbsent(key, System.getProperty(key));
    System.setProperty(key, value);
  }

  private void setHome(Path home) {
    prevHome = System.getProperty("justsearch.home");
    System.setProperty("justsearch.home", home.toAbsolutePath().toString());
    System.setProperty("justsearch.data.dir", home.toAbsolutePath().toString());
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc) throws Exception {
    return awaitDone(svc, 5_000);
  }

  private static AiRuntimeActivationStatus awaitDone(RuntimeActivationService svc, long timeoutMs)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      AiRuntimeActivationStatus st = svc.getActivationStatus();
      if (!"running".equalsIgnoreCase(st.state)) {
        return st;
      }
      Thread.sleep(50);
    }
    fail("Timed out waiting for runtime activation to finish");
    return svc.getActivationStatus();
  }

}
