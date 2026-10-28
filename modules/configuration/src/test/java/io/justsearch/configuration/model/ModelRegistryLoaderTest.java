package io.justsearch.configuration.model;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelRegistryLoaderTest {
  private String priorOs;
  @org.junit.jupiter.api.BeforeEach void windowsRegistryContract() {
    priorOs = System.getProperty("os.name");
    System.setProperty("os.name", "Windows 11");
  }
  @org.junit.jupiter.api.AfterEach void restoreOs() { System.setProperty("os.name", priorOs); }


  @Test
  void loadsV2RegistryFromClasspath() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertEquals(2, registry.schemaVersion());
    assertFalse(registry.packages().isEmpty());
  }

  @Test
  void registryContainsExpectedPackages() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertNotNull(registry.findPackage("embedding"));
    assertNotNull(registry.findPackage("splade"));
    assertNotNull(registry.findPackage("reranker"));
    assertNotNull(registry.findPackage("ner"));
    assertNotNull(registry.findPackage("citation-scorer"));
    assertNotNull(registry.findPackage("chat"));
    assertNotNull(registry.findPackage("cuda-runtime")); // alpha.15
    assertNotNull(registry.findPackage("chat-compact")); // tempdoc 842
    assertEquals(8, registry.packages().size());
  }

  @Test
  void embeddingPackageHasBothVariants() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage embed = registry.findPackage("embedding");

    assertEquals(2, embed.variants().size());

    ModelVariant cpuVariant = embed.selectVariant(DownloadProfile.CPU);
    assertNotNull(cpuVariant);
    assertEquals("model.onnx", cpuVariant.filename());
    assertEquals(ModelPrecision.FP32, cpuVariant.precision());
    assertEquals(ExecutionProvider.CPU, cpuVariant.targetEP());

    ModelVariant gpuVariant = embed.selectVariant(DownloadProfile.GPU_FULL);
    assertNotNull(gpuVariant);
    assertEquals("model_fp16.onnx", gpuVariant.filename());
    assertEquals(ModelPrecision.FP16, gpuVariant.precision());
    assertEquals(ExecutionProvider.CUDA, gpuVariant.targetEP());
  }

  @Test
  void spladePackageHasCorrectVariants() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage splade = registry.findPackage("splade");

    assertEquals(2, splade.variants().size());
    assertEquals(4, splade.supportingFiles().size());
    assertEquals("splade/naver-splade-v3", splade.targetDir());
  }

  @Test
  void nerPackageUsesInt8ForCpu() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage ner = registry.findPackage("ner");

    ModelVariant cpuVariant = ner.selectVariant(DownloadProfile.CPU);
    assertEquals(ModelPrecision.INT8, cpuVariant.precision());
  }

  @Test
  void citationScorerIsCpuOnly_fallsBackForGpuProfiles() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage citation = registry.findPackage("citation-scorer");

    assertEquals(1, citation.variants().size());
    assertEquals(ExecutionProvider.CPU, citation.variants().get(0).targetEP());
    // GPU profiles fall back to the CPU variant (citation-scorer has no CUDA variant)
    ModelVariant gpuSelected = citation.selectVariant(DownloadProfile.GPU_FULL);
    assertNotNull(gpuSelected);
    assertEquals(ExecutionProvider.CPU, gpuSelected.targetEP());
    assertEquals(ModelPrecision.INT8, gpuSelected.precision());
  }

  @Test
  void chatPackageHasVramRequirement() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage chat = registry.findPackage("chat");

    assertTrue(chat.hasVramRequirement());
    assertEquals(HardwareProfile.MINIMUM_VRAM_FOR_GGUF, chat.minVramBytes());
  }

  @Test
  void chatPackageHasMmprojAsSupportingFile() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage chat = registry.findPackage("chat");

    assertEquals(1, chat.supportingFiles().size());
    assertTrue(chat.supportingFiles().get(0).filename().contains("mmproj"));
  }

  @Test
  void chatCompactPackageHasOneVariantAndItsOwnMmproj() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    ModelPackage compact = registry.findPackage("chat-compact");

    assertEquals("compact", compact.targetDir());
    assertEquals(CapabilityTier.LLM, compact.tier());
    assertEquals(1, compact.variants().size());
    assertEquals("Qwen3.5-4B-Q4_K_M.gguf", compact.variants().get(0).filename());
    assertEquals(ModelPrecision.GGUF, compact.variants().get(0).precision());
    assertEquals(ExecutionProvider.LLAMA_SERVER, compact.variants().get(0).targetEP());
    assertEquals(1, compact.supportingFiles().size());
    assertEquals("mmproj-F16.gguf", compact.supportingFiles().get(0).filename());
    // No VRAM floor: the compact pair is the profile that exists FOR constrained/contended GPUs.
    assertFalse(compact.hasVramRequirement());
  }

  /**
   * Tempdoc 842 — production install-plan guard, the same defect class as
   * {@link #cudaRuntimeDeclaresRequiresCuda_othersDefaultFalse()}. The loader disables
   * FAIL_ON_UNKNOWN_PROPERTIES, so a loader that never parses {@code devOnly} throws no error and
   * instead silently ships a 2.7 GB dev model to every user. Every other package leaves the field
   * unset → false.
   */
  @Test
  void chatCompactIsDevOnly_othersDefaultFalse() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertTrue(
        registry.findPackage("chat-compact").devOnly(),
        "chat-compact must load devOnly=true or it enters every user's install plan");
    assertFalse(registry.findPackage("chat").devOnly());
    assertFalse(registry.findPackage("embedding").devOnly());
    assertFalse(registry.findPackage("cuda-runtime").devOnly());
  }

  /**
   * Tempdoc 632 — the registry is the license SSOT; the generated NOTICE projects from this field.
   * Every package must declare a license so the notice generator's presence-check stays green and no
   * model ships unattributed.
   */
  @Test
  void everyPackageDeclaresALicense() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    for (ModelPackage pkg : registry.packages()) {
      assertNotNull(pkg.license(), "package '" + pkg.id() + "' is missing a license (tempdoc 632 SSOT)");
      assertFalse(pkg.license().isBlank(), "package '" + pkg.id() + "' has a blank license");
    }
    // Spot-check the two non-Apache cases that are easy to mislabel.
    assertEquals("AFL-3.0", registry.findPackage("ner").license());
    assertEquals("Apache-2.0", registry.findPackage("chat").license());
  }

  /**
   * Tempdoc 657 — every package must declare a capability tier so an install intent can include or
   * exclude whole capability groups. A missing tier would silently make a package always-installed
   * (untagged ⇒ always wanted), defeating MCP Lite's ability to skip the LLM.
   */
  @Test
  void everyPackageDeclaresATier() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    for (ModelPackage pkg : registry.packages()) {
      assertNotNull(pkg.tier(), "package '" + pkg.id() + "' is missing a capability tier (tempdoc 657)");
    }
    // Spot-check the retrieval vs LLM vs runtime split MCP Lite depends on.
    assertEquals(CapabilityTier.RETRIEVAL_CORE, registry.findPackage("embedding").tier());
    assertEquals(CapabilityTier.RETRIEVAL_ENRICHMENT, registry.findPackage("splade").tier());
    assertEquals(CapabilityTier.LLM, registry.findPackage("chat").tier());
    assertEquals(CapabilityTier.RUNTIME, registry.findPackage("cuda-runtime").tier());
    // The dev-only compact chat pair is still LLM-tier — it is excluded by devOnly, not by tier.
    assertEquals(CapabilityTier.LLM, registry.findPackage("chat-compact").tier());
  }

  /**
   * Tempdoc 772 Q3 — production hardware-gating guard. The JSON loader must deserialize
   * cuda-runtime's {@code "requiresCuda": true} so the planner keeps skipping it on non-CUDA
   * hardware. A regression here (loader dropping the field, or JSON losing the key) would silently
   * un-gate cuda-runtime in production. Every other package leaves the field unset → false.
   */
  @Test
  void cudaRuntimeDeclaresRequiresCuda_othersDefaultFalse() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertTrue(
        registry.findPackage("cuda-runtime").requiresCuda(),
        "cuda-runtime must load requiresCuda=true to stay hardware-gated in production");
    assertFalse(registry.findPackage("embedding").requiresCuda());
    assertFalse(registry.findPackage("chat").requiresCuda());
  }

  /**
   * Tempdoc 772 §J item 2 — the win-x64 ORT CUDA execution-provider native set is trimmed from the
   * shipped installer jar and relocated into the consent-gated cuda-runtime pack. The loader must
   * carry the {@code ort-native-cuda12-v1.24.3.zip} supporting file with {@code extract: true} so
   * the pack unpacks the ORT DLLs into the cuda12 dir the worker points ORT at. A regression here
   * (entry dropped, or {@code extract} lost) would silently degrade GPU users to CPU with the
   * jar's CUDA EP DLL now gone.
   */
  @Test
  void cudaRuntimeShipsRelocatedOrtNativePack() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    SupportingFile ortNative =
        registry.findPackage("cuda-runtime").supportingFiles().stream()
            .filter(sf -> sf.filename().equals("ort-native-cuda12-v1.24.3.zip"))
            .findFirst()
            .orElse(null);

    assertNotNull(
        ortNative,
        "cuda-runtime must carry the relocated ORT CUDA-EP native pack (tempdoc 772 §J item 2)");
    assertTrue(ortNative.extract(), "the ORT native pack must extract into the cuda12 dir");
    assertTrue(ortNative.sizeBytes() > 0, "the ORT native pack must declare a real sizeBytes");
    assertNotNull(ortNative.sha256(), "the ORT native pack must declare a sha256");
  }

  /**
   * Tempdoc 633 #6 — first-run robustness. Every model/file download must resolve over HTTPS from an
   * allowlisted *public* host, so a stranger can clone → build → first-run without hitting a private or
   * unreachable source. This makes the README's "downloads from public sources" line a checked invariant
   * (not a one-time hand-promise) and fails the build if a future registry edit introduces a non-public
   * download URL.
   */
  @Test
  void everyDownloadUrlResolvesFromPublicHost() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    // Project-controlled releases (github.com/eliasjustus/justsearch-releases),
    // the upstream llama.cpp binaries (github.com/ggml-org), and the chat-model GGUF (huggingface.co).
    java.util.Set<String> allowedHosts = java.util.Set.of("github.com", "huggingface.co");

    java.util.List<String> urls = new java.util.ArrayList<>();
    for (ModelPackage pkg : registry.packages()) {
      for (ModelVariant v : pkg.variants()) urls.add(v.downloadUrl());
      for (SupportingFile sf : pkg.supportingFiles()) urls.add(sf.downloadUrl());
    }
    assertFalse(urls.isEmpty(), "registry exposed no downloadUrls to validate");

    for (String url : urls) {
      assertNotNull(url, "a download entry has a null URL");
      java.net.URI uri = java.net.URI.create(url);
      assertEquals("https", uri.getScheme(), "downloadUrl must be HTTPS: " + url);
      assertTrue(
          allowedHosts.contains(uri.getHost()),
          "downloadUrl host '" + uri.getHost() + "' is not in the public allowlist " + allowedHosts + ": " + url);
    }
  }

  /**
   * Tempdoc 840 Phase 2 — the shipped classification. This is the mapping the install UI turns into
   * user-facing copy and into which switches exist at all, so it is asserted per package rather than
   * spot-checked: getting {@code embedding} or {@code cuda-runtime} wrong here is what makes search
   * (or chat) silently switch-off-able.
   */
  @Test
  void everyPackageDeclaresItsNecessity() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    assertEquals(Necessity.REQUIRED, registry.findPackage("embedding").necessity());
    assertEquals(Necessity.IMPROVES_RESULTS, registry.findPackage("splade").necessity());
    assertEquals(Necessity.IMPROVES_RESULTS, registry.findPackage("reranker").necessity());
    assertEquals(Necessity.IMPROVES_RESULTS, registry.findPackage("ner").necessity());
    assertEquals(Necessity.IMPROVES_RESULTS, registry.findPackage("citation-scorer").necessity());
    assertEquals(Necessity.ADDS_FEATURE, registry.findPackage("chat").necessity());
    assertEquals(Necessity.INFRASTRUCTURE, registry.findPackage("cuda-runtime").necessity());

    assertFalse(
        registry.findPackage("embedding").necessity().userDeclinable(),
        "search does not work without the embedding model");
    assertFalse(
        registry.findPackage("cuda-runtime").necessity().userDeclinable(),
        "declining 'GPU runtime libraries' would remove chat as a side effect its label never names");
  }

  /**
   * Tempdoc 840 Phase 2 — {@code dependsOn} is what makes invariant H1 (nothing that needs the CUDA
   * runtime is acquired without it) checkable.
   *
   * <p>Deliberately NOT a biconditional against "has an FP16/CUDA variant". That was the first
   * formulation and it is too narrow: {@code chat} ships only a GGUF/{@code LLAMA_SERVER} variant yet
   * genuinely depends on the runtime package, because {@code cuda-runtime} is what delivers the cuda12
   * {@code llama-server.exe} that {@code applyCudaServerExe()} points chat at, and this build does not
   * support CPU chat at all. Encoding the proxy instead of the real relation would have left the one
   * package whose dependency is least obvious as the one package that did not declare it.
   *
   * <p>So the loop asserts only the direction that catches a MISSING edge — a CUDA variant always
   * implies the dependency — and the named cases below pin the two entries a reader would otherwise
   * get wrong in opposite directions.
   */
  @Test
  void everyPackageNeedingTheCudaRuntimeDeclaresTheDependency() {
    ModelRegistry registry =
        ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");

    for (ModelPackage pkg : registry.packages()) {
      boolean hasCudaVariant =
          pkg.variants().stream().anyMatch(v -> v.targetEP() == ExecutionProvider.CUDA);
      if (hasCudaVariant) {
        assertTrue(
            pkg.dependsOn().contains("cuda-runtime"),
            "package '" + pkg.id() + "' ships a CUDA variant, so it must declare the runtime edge");
      }
    }
    assertTrue(registry.findPackage("embedding").dependsOn().contains("cuda-runtime"));
    assertTrue(
        registry.findPackage("chat").dependsOn().contains("cuda-runtime"),
        "chat has no CUDA *variant* but needs the cuda12 llama-server the runtime package delivers");
    assertTrue(
        registry.findPackage("citation-scorer").dependsOn().isEmpty(),
        "CPU-only INT8 package — the one that genuinely has no edge");
    assertTrue(
        registry.findPackage("cuda-runtime").dependsOn().isEmpty(),
        "the runtime package cannot depend on itself");
  }

  /**
   * Tempdoc 840 Phase 2 — fail-closed defaulting, the same rule as {@code required} on supporting
   * files. An ABSENT necessity (a pre-840 registry) and an UNRECOGNIZED one (a typo, or a category a
   * newer registry uses and this build does not know) must BOTH land on REQUIRED: a package nobody
   * classified must never become silently switch-off-able, and an unknown value must not fail the
   * whole registry load.
   */
  @Test
  void absentOrUnrecognizedNecessity_defaultsToRequired(@TempDir Path tempDir) throws Exception {
    Path json = tempDir.resolve("registry.json");
    Files.writeString(
        json,
        """
        {
          "schemaVersion": 2,
          "purpose": "test",
          "packages": [
            { "id": "unclassified", "label": "L", "description": "D", "targetDir": "d",
              "minVramBytes": 0, "variants": [], "supportingFiles": [] },
            { "id": "typo", "label": "L", "description": "D", "targetDir": "d",
              "necessity": "nice-to-have", "minVramBytes": 0, "variants": [], "supportingFiles": [] },
            { "id": "classified", "label": "L", "description": "D", "targetDir": "d",
              "necessity": "adds-feature", "minVramBytes": 0, "variants": [], "supportingFiles": [] }
          ]
        }
        """);

    ModelRegistry registry = ModelRegistryLoader.loadFromFile(json);

    assertEquals(Necessity.REQUIRED, registry.findPackage("unclassified").necessity());
    assertEquals(Necessity.REQUIRED, registry.findPackage("typo").necessity());
    assertFalse(registry.findPackage("typo").necessity().userDeclinable());
    assertEquals(
        Necessity.ADDS_FEATURE,
        registry.findPackage("classified").necessity(),
        "a recognized value must still be honored — the default must not swallow everything");
    assertTrue(registry.findPackage("unclassified").dependsOn().isEmpty(), "absent dependsOn ⇒ empty");
  }
}
