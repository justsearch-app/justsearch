package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallPlannerTest {

  @TempDir Path tempDir;

  @Test
  void gpuFullProfile_downloadsEverything() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_FULL, plan.profile());
    assertTrue(plan.skipped().isEmpty());
    // Should include: FP16 embedding variant + tokenizer + GGUF chat + GGUF mmproj
    assertEquals(4, plan.downloads().size());
    assertTrue(plan.totalBytes() > 0);
  }

  @Test
  void effectiveTargetDir_retainsOnnxCandidatesByContentIdentity() {
    ModelPackage embedding = registryWithEmbeddingOnly().findPackage("embedding");
    ModelVariant cpu = embedding.selectVariant(HardwareProfile.cpuOnly().downloadProfile());

    String target = InstallPlanner.effectiveTargetDir(embedding, cpu);
    assertTrue(target.startsWith("onnx/embed/candidates/"));
    assertEquals(target, InstallPlanner.effectiveTargetDir(embedding, cpu));

    ModelPackage changedSupport = new ModelPackage(
        embedding.id(), embedding.label(), embedding.description(), embedding.targetDir(),
        embedding.variants(),
        List.of(new SupportingFile("tokenizer.json", "CHANGED", 10_000, "https://example.com/tok")),
        embedding.minVramBytes(), embedding.termsUrl());
    assertNotEquals(
        target, InstallPlanner.effectiveTargetDir(changedSupport, changedSupport.variants().get(0)),
        "supporting asset identity must retain a separate candidate directory");

    ModelPackage chat = registryWithEmbeddingAndChat().findPackage("chat");
    assertEquals("gguf", InstallPlanner.effectiveTargetDir(chat, chat.variants().get(0)));
  }

  @Test
  void cpuProfile_skipsGgufAndDownloadsFp32() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertEquals(1, plan.skipped().size());
    assertEquals("chat", plan.skipped().get(0).packageId());

    // Should include: FP32 embedding variant + tokenizer (no chat)
    assertEquals(2, plan.downloads().size());
    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("chat")));
    assertTrue(plan.downloads().stream()
        .filter(d -> d.isModelVariant())
        .allMatch(d -> d.targetPath().contains("model.onnx")));
  }

  @Test
  void gpuLiteProfile_skipsChatButDownloadsFp16() {
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = new HardwareProfile(true, true, 6_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_LITE, plan.profile());
    assertEquals(1, plan.skipped().size());
    assertEquals("chat", plan.skipped().get(0).packageId());

    // FP16 embedding variant + tokenizer
    assertEquals(2, plan.downloads().size());
    assertTrue(plan.downloads().stream()
        .filter(d -> d.isModelVariant())
        .allMatch(d -> d.targetPath().contains("fp16")));
  }

  @Test
  void alreadyInstalledFiles_skippedInPlan() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // Pre-create files whose bytes match the registry identity.
    Path modelFile = embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx");
    Path tokenizerFile = embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json");
    Files.createDirectories(modelFile.getParent());
    Files.write(modelFile, new byte[1_000_000]);
    Files.write(tokenizerFile, new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(plan.downloads().isEmpty());
    assertEquals(1, plan.alreadyInstalled().size());
    assertEquals("embedding", plan.alreadyInstalled().get(0));
    assertEquals(0, plan.totalBytes());
  }

  @Test
  void sameSizeCorruptOnnxCandidate_isPlannedForRepair() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    Path modelFile = embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx");
    Path tokenizerFile = embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json");
    Files.createDirectories(modelFile.getParent());
    byte[] corrupt = new byte[1_000_000];
    corrupt[0] = 1;
    Files.write(modelFile, corrupt);
    Files.write(tokenizerFile, new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), tempDir);

    assertTrue(
        plan.downloads().stream()
            .anyMatch(
                    d ->
                    d.isModelVariant()
                        && d.targetPath()
                            .equals(embeddingTargetPath(HardwareProfile.cpuOnly(), "model.onnx"))),
        "same-size corruption in a candidate-owned ONNX file must schedule a repair");
  }

  @Test
  void deltaComputation_onlyDownloadsMissing() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // Pre-create only the tokenizer at its declared size (model is missing).
    Path tokenizerFile = embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json");
    Files.createDirectories(tokenizerFile.getParent());
    Files.write(tokenizerFile, new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(1, plan.downloads().size());
    assertTrue(plan.downloads().get(0).isModelVariant());
    assertEquals(embeddingTargetPath(HardwareProfile.cpuOnly(), "model.onnx"),
        plan.downloads().get(0).targetPath());
  }

  /**
   * Sandbox round 8. A cancelled multi-GB download leaves its bytes in {@code <target>.partial} and
   * the final target absent, so {@code isAlreadyInstalled} (which probes the FINAL path) answers
   * false and the file is correctly still planned — but the plan used to charge the user its FULL
   * size, contradicting the cancel dialog's promise that the bytes were kept. The download stays in
   * the plan at its full {@code sizeBytes} (the fetch needs that for its Range request and
   * verification); what changes is that the staged bytes are now counted and excluded from what the
   * network still owes.
   */
  @Test
  void partialOnDisk_countsAsResumable_andIsExcludedFromRemainingBytes() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // 400_000 of the FP32 model's 1_000_000 bytes downloaded before the user cancelled.
    Path partial = embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx.partial");
    Files.createDirectories(partial.getParent());
    Files.write(partial, new byte[400_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    // Still planned, still at its full size — resuming does not shrink the file.
    assertEquals(2, plan.downloads().size());
    assertEquals(1_010_000, plan.totalBytes());
    assertEquals(400_000, plan.resumableBytes());
    assertEquals(610_000, plan.remainingBytes());
  }

  @Test
  void noPartialOnDisk_reportsNothingResumable() {
    InstallPlan plan =
        InstallPlanner.plan(registryWithEmbeddingOnly(), HardwareProfile.cpuOnly(), tempDir);

    assertEquals(0, plan.resumableBytes());
    assertEquals(plan.totalBytes(), plan.remainingBytes());
  }

  /**
   * A partial longer than the expected total is the impossible state {@code DownloadResume.decide}
   * refuses to resume from. Counting it would promise bytes the fetch is about to throw away, so the
   * planner discounts it — under-promising is the only safe direction for a consent number.
   */
  @Test
  void partialLargerThanExpected_isNotCountedAsResumable() throws Exception {
    Path partial = embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx.partial");
    Files.createDirectories(partial.getParent());
    Files.write(partial, new byte[1_500_000]); // declared size is 1_000_000

    InstallPlan plan =
        InstallPlanner.plan(registryWithEmbeddingOnly(), HardwareProfile.cpuOnly(), tempDir);

    assertEquals(0, plan.resumableBytes());
    assertEquals(plan.totalBytes(), plan.remainingBytes());
  }

  /** A partial staged for a SUPPORTING file counts too — the planner probes both download kinds. */
  @Test
  void partialForSupportingFile_countsAsResumable() throws Exception {
    Path partial = embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json.partial");
    Files.createDirectories(partial.getParent());
    Files.write(partial, new byte[4_000]);

    InstallPlan plan =
        InstallPlanner.plan(registryWithEmbeddingOnly(), HardwareProfile.cpuOnly(), tempDir);

    assertEquals(4_000, plan.resumableBytes());
    assertEquals(plan.totalBytes() - 4_000, plan.remainingBytes());
  }

  /**
   * The completed-file path and the partial path are different questions about different paths: a
   * COMPLETE file leaves the plan entirely, a partial stays in it with its bytes discounted. Pinning
   * both in one plan proves the planner is not conflating them.
   */
  @Test
  void completedFileLeavesThePlan_whilePartialStaysWithItsBytesDiscounted() throws Exception {
    Path tokenizer = embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json");
    Files.createDirectories(tokenizer.getParent());
    Files.write(tokenizer, new byte[10_000]); // complete, at its declared size
    Files.write(embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx.partial"), new byte[250_000]);

    InstallPlan plan =
        InstallPlanner.plan(registryWithEmbeddingOnly(), HardwareProfile.cpuOnly(), tempDir);

    assertEquals(1, plan.downloads().size());
    assertEquals(embeddingTargetPath(HardwareProfile.cpuOnly(), "model.onnx"),
        plan.downloads().get(0).targetPath());
    assertEquals(1_000_000, plan.totalBytes());
    assertEquals(250_000, plan.resumableBytes());
    assertEquals(750_000, plan.remainingBytes());
  }

  @Test
  void mcpLiteIntent_skipsLlmTier_evenOnCapableHardware() {
    ModelRegistry registry = registryWithTiers();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    // Full Desktop (default overload) includes the LLM tier on capable hardware.
    InstallPlan full = InstallPlanner.plan(registry, hw, tempDir);
    assertTrue(full.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));

    // MCP Lite excludes the LLM tier by intent, independent of hardware.
    InstallPlan lite = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);
    assertTrue(lite.downloads().stream().noneMatch(d -> d.packageId().equals("chat")));
    assertTrue(
        lite.skipped().stream()
            .anyMatch(s -> s.packageId().equals("chat") && s.reason().contains("mcp-lite")));
    // Retrieval-core stays wanted by every intent.
    assertTrue(lite.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")));
  }

  @Test
  void untaggedPackage_alwaysWanted_soPreTierRegistriesAreUnchanged() {
    // registryWithEmbeddingAndChat() leaves tiers null → MCP Lite must NOT exclude them; only
    // hardware gates, and GPU_FULL keeps the chat package.
    ModelRegistry registry = registryWithEmbeddingAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);
    InstallPlan lite = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);
    assertTrue(lite.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
  }

  @Test
  void gpuLiteProfile_includesCudaRuntime_notGatedOnGgufVramFloor() {
    // Regression for the miswired cuda-runtime gate: a CUDA-functional GPU with < 7.5 GB VRAM
    // (GPU_LITE) must still download the runtime-tier CUDA DLLs, because it downloads the FP16
    // CUDA ONNX variants that need them. Previously the runtime package reused the GGUF VRAM
    // floor and was wrongly skipped for GPU_LITE.
    ModelRegistry registry = registryWithRuntime();
    HardwareProfile hw = new HardwareProfile(true, true, 6_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.GPU_LITE, plan.profile());
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")),
        "GPU_LITE must download the CUDA runtime");
    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void cpuProfile_skipsCudaRuntime() {
    ModelRegistry registry = registryWithRuntime();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("cuda-runtime")));
    assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void wrongSizeFile_isNotTreatedAsAlreadyInstalled() throws Exception {
    ModelRegistry registry = registryWithEmbeddingOnly();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    // A truncated/wrong file at the right path (size != declared) must be re-planned, not trusted.
    Path modelFile = embeddingTarget(HardwareProfile.cpuOnly(), "model.onnx");
    Files.createDirectories(modelFile.getParent());
    Files.write(modelFile, new byte[42]); // declared size is 1_000_000
    Files.write(embeddingTarget(HardwareProfile.cpuOnly(), "tokenizer.json"), new byte[10_000]);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(
        plan.downloads().stream()
            .anyMatch(d -> d.isModelVariant()
                && d.targetPath().equals(embeddingTargetPath(HardwareProfile.cpuOnly(), "model.onnx"))),
        "wrong-size model must be scheduled for re-download");
  }

  @Test
  void mcpLiteIntent_onGpu_includesCudaRuntime_butStillSkipsChat() {
    // Option A (owner decision): the CUDA runtime is hardware-support wanted by every intent, because
    // mcp-lite still downloads the CUDA FP16 retrieval variants that need it. So mcp-lite on a GPU
    // must download cuda-runtime — while STILL skipping the LLM chat tier. Reverting the
    // wants(RUNTIME) change makes the intent gate skip cuda-runtime for mcp-lite → this goes red.
    ModelRegistry registry = registryWithRuntimeAndChat();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);

    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")),
        "mcp-lite on GPU must download the CUDA runtime for GPU retrieval");
    assertTrue(
        plan.downloads().stream().noneMatch(d -> d.packageId().equals("chat")),
        "mcp-lite must still skip the LLM chat tier");
    assertTrue(
        plan.skipped().stream()
            .anyMatch(s -> s.packageId().equals("chat") && s.reason().contains("mcp-lite")));
  }

  @Test
  void mcpLiteIntent_onCpu_skipsCudaRuntime() {
    // No CUDA → no runtime, for every intent. The planner's usesCuda() gate is the sole authority.
    ModelRegistry registry = registryWithRuntimeAndChat();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, InstallIntent.MCP_LITE, tempDir, tempDir);

    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("cuda-runtime")));
    assertTrue(plan.skipped().stream().anyMatch(s -> s.packageId().equals("cuda-runtime")));
  }

  @Test
  void hardwareIndependentRuntimePackage_isNeverSkipped_onCudaHardware() {
    // Tempdoc 772 Q3: a RUNTIME-tier package with requiresCuda=false must be representable and never
    // skipped for hardware reasons. On CUDA hardware it downloads like any wanted package.
    ModelRegistry registry = registryWithHardwareIndependentRuntime();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("runtime-cpu-support")));
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("runtime-cpu-support")),
        "a requiresCuda=false RUNTIME package must download on CUDA hardware");
  }

  @Test
  void hardwareIndependentRuntimePackage_isNeverSkipped_onNonCudaHardware() {
    // The point of the tempdoc 772 Q3 change: a RUNTIME-tier package with requiresCuda=false is
    // NEVER skipped for hardware reasons — unlike cuda-runtime (requiresCuda=true), which the CPU
    // profile skips (see cpuProfile_skipsCudaRuntime). Before this change RUNTIME tier itself gated
    // on CUDA, so a hardware-independent runtime package could not be expressed at all.
    ModelRegistry registry = registryWithHardwareIndependentRuntime();
    HardwareProfile hw = HardwareProfile.cpuOnly();

    InstallPlan plan = InstallPlanner.plan(registry, hw, tempDir);

    assertEquals(DownloadProfile.CPU, plan.profile());
    assertTrue(plan.skipped().stream().noneMatch(s -> s.packageId().equals("runtime-cpu-support")));
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("runtime-cpu-support")),
        "a requiresCuda=false RUNTIME package must download even without CUDA");
  }

  // ── Tempdoc 840 Phase 2: per-component decline ────────────────────────────────────────────────

  @Test
  void declinedPackage_isSkippedWithTheUserDeclinedCause() {
    ModelRegistry registry = registryWithNecessities();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan plan =
        InstallPlanner.plan(
            registry, hw, InstallIntent.DEFAULT, Set.of("reranker"), tempDir, tempDir);

    assertTrue(plan.downloads().stream().noneMatch(d -> d.packageId().equals("reranker")));
    InstallPlan.SkippedPackage skip =
        plan.skipped().stream()
            .filter(s -> s.packageId().equals("reranker"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        SkipCause.USER_DECLINED,
        skip.cause(),
        "the typed cause is what logic reads — a reworded reason must not change the meaning");
    assertTrue(skip.reason().contains("declined"), "and the prose still names the user's choice");
    // Everything else the hardware allows is unaffected.
    assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")));
    assertTrue(plan.downloads().stream().anyMatch(d -> d.packageId().equals("chat")));
  }

  @Test
  void declinedNonDeclinablePackages_areInstalledAnyway() {
    // The guard: a stale settings file, a hand edit, or a future UI bug naming EVERY package must
    // not be able to switch off the ones the product cannot work without. embedding is REQUIRED and
    // cuda-runtime is INFRASTRUCTURE (declining "GPU runtime libraries" would silently remove chat,
    // since that package also delivers the cuda12 llama-server) — both must survive.
    ModelRegistry registry = registryWithNecessities();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);
    Set<String> declineEverything = Set.of("embedding", "reranker", "chat", "cuda-runtime");

    InstallPlan plan =
        InstallPlanner.plan(
            registry, hw, InstallIntent.DEFAULT, declineEverything, tempDir, tempDir);

    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("embedding")),
        "a REQUIRED package must be installed even when the declined set names it");
    assertTrue(
        plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime")),
        "an INFRASTRUCTURE package must be installed even when the declined set names it");
    assertTrue(
        plan.skipped().stream()
            .noneMatch(
                s ->
                    s.cause() == SkipCause.USER_DECLINED
                        && (s.packageId().equals("embedding")
                            || s.packageId().equals("cuda-runtime"))),
        "and neither may be recorded as user-declined");
    // The two genuinely declinable ones ARE honored, so the test cannot pass by ignoring the set.
    assertTrue(
        plan.skipped().stream()
            .filter(s -> s.cause() == SkipCause.USER_DECLINED)
            .map(InstallPlan.SkippedPackage::packageId)
            .toList()
            .containsAll(List.of("reranker", "chat")));
  }

  @Test
  void emptyAndNullDeclinedSets_leaveThePlanUnchanged() {
    ModelRegistry registry = registryWithNecessities();
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan baseline = InstallPlanner.plan(registry, hw, InstallIntent.DEFAULT, tempDir, tempDir);
    InstallPlan empty =
        InstallPlanner.plan(registry, hw, InstallIntent.DEFAULT, Set.of(), tempDir, tempDir);
    InstallPlan nulled =
        InstallPlanner.plan(registry, hw, InstallIntent.DEFAULT, null, tempDir, tempDir);

    assertEquals(baseline.downloads(), empty.downloads());
    assertEquals(baseline.downloads(), nulled.downloads());
    assertEquals(baseline.skipped(), nulled.skipped());
  }

  /**
   * Invariant H1 (tempdoc 840 Phase 2): no package may select a CUDA/FP16 variant unless the
   * cuda-runtime package is part of the same plan.
   *
   * <p>This holds today only IMPLICITLY — {@code selectVariant} and the cuda-runtime hardware gate
   * both read {@code profile.usesCuda()}, so they cannot disagree by construction. That is exactly
   * why it needs a test rather than trust: the round-11 defect class is a machine that ran every
   * ONNX encoder on CPU while reporting a complete install, and a future edit that gates the runtime
   * package on anything else (a tier, a VRAM floor, a user decline) would re-open it silently. Run
   * against the SHIPPED registry, across every hardware profile, and with every package declined —
   * the decline axis is the newest way to break it.
   */
  @Test
  void noPackageSelectsACudaVariant_unlessCudaRuntimeIsInThePlan() {
    ModelRegistry registry = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    List<HardwareProfile> profiles =
        List.of(
            HardwareProfile.cpuOnly(),
            new HardwareProfile(true, false, 0L), // GPU present, CUDA not functional
            new HardwareProfile(true, true, 6_000_000_000L), // GPU_LITE
            HardwareProfile.gpuFull(12_000_000_000L));
    Set<String> declineEverything =
        registry.packages().stream().map(ModelPackage::id).collect(java.util.stream.Collectors.toSet());

    for (HardwareProfile hw : profiles) {
      for (Set<String> declined : List.of(Set.<String>of(), declineEverything)) {
        InstallPlan plan =
            InstallPlanner.plan(registry, hw, InstallIntent.DEFAULT, declined, tempDir, tempDir);
        boolean runtimePlanned =
            plan.downloads().stream().anyMatch(d -> d.packageId().equals("cuda-runtime"))
                || plan.alreadyInstalled().contains("cuda-runtime");

        for (ModelPackage pkg : registry.packages()) {
          boolean pkgPlanned =
              plan.downloads().stream().anyMatch(d -> d.packageId().equals(pkg.id()))
                  || plan.alreadyInstalled().contains(pkg.id());
          if (!pkgPlanned) {
            continue;
          }
          ModelVariant selected = pkg.selectVariant(plan.profile());
          if (selected == null || selected.targetEP() != ExecutionProvider.CUDA) {
            continue;
          }
          assertTrue(
              runtimePlanned,
              "H1 violated: package '" + pkg.id() + "' selects the CUDA variant '"
                  + selected.filename() + "' on profile " + plan.profile()
                  + " (declined=" + declined.size() + ") but cuda-runtime is not in the plan");
          assertTrue(
              pkg.dependsOn().contains("cuda-runtime"),
              "package '" + pkg.id() + "' can select a CUDA variant, so the registry must declare"
                  + " dependsOn cuda-runtime — that declaration is what makes H1 checkable rather"
                  + " than merely true-by-construction");
        }
      }
    }
  }

  @Test
  void devOnlyPackage_isNeverPlanned_forAnyHardwareOrIntent() {
    // Tempdoc 842: chat-compact exists for dev stacks only. The skip is unconditional — no intent
    // wants it and no hardware permits it — so a devOnly package must be absent from the downloads
    // of every (hardware × intent) combination, and present in skipped() with a reason naming why.
    ModelRegistry registry = registryWithDevOnlyChat();

    for (HardwareProfile hw :
        List.of(
            HardwareProfile.gpuFull(12_000_000_000L),
            new HardwareProfile(true, true, 6_000_000_000L),
            HardwareProfile.cpuOnly())) {
      for (InstallIntent intent : InstallIntent.values()) {
        InstallPlan plan = InstallPlanner.plan(registry, hw, intent, tempDir, tempDir);
        String where = hw.downloadProfile() + "/" + intent.id();
        assertTrue(
            plan.downloads().stream().noneMatch(d -> d.packageId().equals("chat-compact")),
            "devOnly package must never be downloaded (" + where + ")");
        assertTrue(
            plan.skipped().stream()
                .anyMatch(
                    sk ->
                        sk.packageId().equals("chat-compact")
                            && sk.reason().contains("development-only")),
            "devOnly skip must be recorded with a naming reason (" + where + ")");
        assertFalse(
            plan.alreadyInstalled().contains("chat-compact"),
            "a skipped devOnly package is not 'already installed' (" + where + ")");
        assertFalse(
            InstallPlanner.isIncludedByPlan(registry.findPackage("chat-compact"), intent, hw),
            "isIncludedByPlan must agree with the planner loop (" + where + ")");
      }
    }
  }

  /** A registry exercising all four {@link Necessity} categories against the real package ids. */
  private ModelRegistry registryWithNecessities() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding model", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000,
                "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE, false, false,
        Necessity.REQUIRED, List.of("cuda-runtime"));
    ModelPackage reranker = new ModelPackage(
        "reranker", "Search reranker", "Better ranking", "onnx/reranker",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "HHHH", 300_000, "https://example.com/rr-fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "IIII", 150_000, "https://example.com/rr-fp16")),
        List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT, false, false,
        Necessity.IMPROVES_RESULTS, List.of("cuda-runtime"));
    ModelPackage chat = new ModelPackage(
        "chat", "Chat model", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM, false, false,
        Necessity.ADDS_FEATURE, List.of());
    ModelPackage cudaRuntime = new ModelPackage(
        "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12",
        List.of(),
        List.of(
            new SupportingFile(
                "cuda.zip", "FFFF", 200_000_000L, "https://example.com/cuda.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true, false,
        Necessity.INFRASTRUCTURE, List.of());
    return new ModelRegistry(
        2, "test registry", List.of(embedding, reranker, chat, cudaRuntime));
  }

  @Test
  void devOnlyPackage_contributesNoBytes_soConsentTotalsAreUnaffected() {
    // The devOnly skip must happen before any byte accounting: a user consenting to the plan must
    // never see the dev model's size in the total, even on the most capable hardware.
    HardwareProfile hw = HardwareProfile.gpuFull(12_000_000_000L);

    InstallPlan withoutDevOnly = InstallPlanner.plan(registryWithTiers(), hw, tempDir);
    InstallPlan withDevOnly = InstallPlanner.plan(registryWithDevOnlyChat(), hw, tempDir);

    assertEquals(withoutDevOnly.totalBytes(), withDevOnly.totalBytes());
    assertEquals(withoutDevOnly.downloads().size(), withDevOnly.downloads().size());
  }

  /**
   * {@link #registryWithTiers()} plus a devOnly LLM-tier package — the shape of the real
   * {@code chat-compact} entry (tempdoc 842): wanted by tier, permitted by hardware, and still
   * never planned.
   */
  private ModelRegistry registryWithDevOnlyChat() {
    ModelPackage chatCompact = new ModelPackage(
        "chat-compact", "Chat model (compact)", "Dev-only small chat model", "compact",
        List.of(
            new ModelVariant("compact.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "HHHH", 2_700_000_000L, "https://example.com/compact-gguf")),
        List.of(
            new SupportingFile(
                "compact-mmproj.gguf", "IIII", 670_000_000L, "https://example.com/compact-mmproj")),
        0, null, null, null, CapabilityTier.LLM, false, true,
        Necessity.ADDS_FEATURE, List.of());
    List<ModelPackage> packages = new java.util.ArrayList<>(registryWithTiers().packages());
    packages.add(chatCompact);
    return new ModelRegistry(2, "test registry", packages);
  }

  /**
   * A registry carrying a hypothetical hardware-independent RUNTIME-tier package (tempdoc 772 Q3):
   * RUNTIME tier but {@code requiresCuda=false}, so the planner's CUDA gate never skips it. Does not
   * correspond to any real production package.
   */
  private ModelRegistry registryWithHardwareIndependentRuntime() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage runtimeCpuSupport = new ModelPackage(
        "runtime-cpu-support", "CPU runtime libraries", "Always-required runtime payload", "cpu-rt",
        List.of(),
        List.of(
            new SupportingFile(
                "runtime.zip", "GGGG", 50_000_000L, "https://example.com/runtime.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, false, false,
        Necessity.INFRASTRUCTURE, List.of());
    return new ModelRegistry(2, "test registry", List.of(embedding, runtimeCpuSupport));
  }

  private ModelRegistry registryWithRuntimeAndChat() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage cudaRuntime = new ModelPackage(
        "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12",
        List.of(),
        List.of(
            new SupportingFile(
                "cuda.zip", "FFFF", 200_000_000L, "https://example.com/cuda.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true, false,
        Necessity.INFRASTRUCTURE, List.of());
    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM);
    return new ModelRegistry(2, "test registry", List.of(embedding, cudaRuntime, chat));
  }

  private ModelRegistry registryWithRuntime() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage cudaRuntime = new ModelPackage(
        "cuda-runtime", "GPU runtime libraries", "CUDA DLLs", "cuda12",
        List.of(),
        List.of(
            new SupportingFile(
                "cuda.zip", "FFFF", 200_000_000L, "https://example.com/cuda.zip", true)),
        0, null, "native-bin/llama-server/variants", null, CapabilityTier.RUNTIME, true, false,
        Necessity.INFRASTRUCTURE, List.of());
    return new ModelRegistry(2, "test registry", List.of(embedding, cudaRuntime));
  }

  private ModelRegistry registryWithTiers() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(new SupportingFile("tokenizer.json", "CCCC", 10_000, "https://example.com/tok")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(
            new SupportingFile("mmproj.gguf", "EEEE", 1_000_000_000L, "https://example.com/mmproj")),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null, null, null, CapabilityTier.LLM);
    return new ModelRegistry(2, "test registry", List.of(embedding, chat));
  }

  private ModelRegistry registryWithEmbeddingAndChat() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "AAAA", 1_000_000, "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(
            new SupportingFile(
                "tokenizer.json", "CCCC",
                10_000, "https://example.com/tok")),
        0, null);

    ModelPackage chat = new ModelPackage(
        "chat", "Chat", "Conversational AI", "gguf",
        List.of(
            new ModelVariant("model.gguf", ModelPrecision.GGUF, ExecutionProvider.LLAMA_SERVER,
                "DDDD", 5_000_000_000L, "https://example.com/gguf")),
        List.of(
            new SupportingFile("mmproj.gguf", "EEEE", 1_000_000_000L, "https://example.com/mmproj")),
        HardwareProfile.MINIMUM_VRAM_FOR_GGUF, null);

    return new ModelRegistry(2, "test registry", List.of(embedding, chat));
  }

  private Path embeddingTarget(HardwareProfile hardware, String filename) {
    ModelPackage embedding = registryWithEmbeddingOnly().findPackage("embedding");
    return tempDir.resolve(InstallPlanner.effectiveTargetDir(
        embedding, embedding.selectVariant(hardware.downloadProfile()))).resolve(filename);
  }

  private String embeddingTargetPath(HardwareProfile hardware, String filename) {
    ModelPackage embedding = registryWithEmbeddingOnly().findPackage("embedding");
    String dir = InstallPlanner.effectiveTargetDir(
        embedding, embedding.selectVariant(hardware.downloadProfile()));
    return dir + "/" + filename;
  }

  private ModelRegistry registryWithEmbeddingOnly() {
    ModelPackage embedding = new ModelPackage(
        "embedding", "Embedding", "Semantic search", "onnx/embed",
        List.of(
            new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
                "D29751F2649B32FF572B5E0A9F541EA660A50F94FF0BEEDFB0B692B924CC8025", 1_000_000,
                "https://example.com/fp32"),
            new ModelVariant("model_fp16.onnx", ModelPrecision.FP16, ExecutionProvider.CUDA,
                "BBBB", 500_000, "https://example.com/fp16")),
        List.of(
            new SupportingFile(
                "tokenizer.json", String.join("", "95B532CC4381AFFD", "FF0D956E12520A04",
                    "129ED49D37E15422", "8368FE5621F0B9A2"),
                10_000, "https://example.com/tok")),
        0, null);

    return new ModelRegistry(2, "test registry", List.of(embedding));
  }
}
