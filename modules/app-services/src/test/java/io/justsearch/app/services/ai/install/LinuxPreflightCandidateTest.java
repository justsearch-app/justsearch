/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.app.api.AiRuntimeStatusResponse;
import io.justsearch.app.services.ai.preflight.AiPreflightService;
import io.justsearch.app.services.ai.runtime.RuntimeActivationService;
import io.justsearch.configuration.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** S7/S12: preflight reads the same retained model location as acquisition. */
final class LinuxPreflightCandidateTest {
  @TempDir Path home;

  @Test
  void retainedSearchModelAndSupportingFilesAreCompleteWithoutChatRuntime() throws Exception {
    var service = service();
    var pkg = service.getManifest().findPackage("embedding");
    var dir = service.modelsDir().resolve(InstallPlanner.effectiveTargetDir(
        pkg, pkg.selectVariant(HardwareProfile.cpuOnly().downloadProfile())));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("model.onnx"), "model");
    Files.writeString(dir.resolve("tokenizer.json"), "tokenizer");

    var result = preflight(service);
    var search = result.packages().getFirst();
    assertTrue(search.complete(), "retained acquisition directory must be visible to preflight");
    assertFalse(search.blockingIncomplete());
    assertEquals(List.of("model.onnx"), search.presentVariantFiles());
    assertTrue(search.missingFiles().isEmpty());
    assertFalse(result.runtimeInstalled());
    assertFalse(result.canActivateDefault(), "chat activation is independent of search readiness");
  }

  @Test
  void supportingFilesInOldDirectoryCannotCompleteRetainedCandidate() throws Exception {
    var service = service();
    var pkg = service.getManifest().findPackage("embedding");
    var dir = service.modelsDir().resolve(InstallPlanner.effectiveTargetDir(
        pkg, pkg.selectVariant(HardwareProfile.cpuOnly().downloadProfile())));
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("model.onnx"), "model");
    var old = service.modelsDir().resolve(pkg.targetDir());
    Files.writeString(old.resolve("tokenizer.json"), "unrelated old tokenizer");

    var search = preflight(service).packages().getFirst();
    assertEquals(List.of("model.onnx"), search.presentVariantFiles());
    assertFalse(search.complete());
    assertTrue(search.blockingIncomplete());
    assertEquals(List.of("tokenizer.json"), search.missingFiles());
  }

  private AiInstallService service() {
    var pkg = new ModelPackage("embedding", "Search", "Fixture", "search", List.of(
        new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            "abc", 5, "https://example.invalid/model")), List.of(
        new SupportingFile("tokenizer.json", "def", 9, "https://example.invalid/tokenizer")),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    var service = spy(new AiInstallService(null, null, null, null, home));
    doReturn(new ModelRegistry(2, "fixture", List.of(pkg))).when(service).getManifest();
    doReturn(HardwareProfile.cpuOnly()).when(service).buildHardwareProfile();
    return service;
  }

  private AiPreflightService.AiPreflightResult preflight(AiInstallService service) {
    var runtime = mock(RuntimeActivationService.class);
    when(runtime.getStatus()).thenReturn(new AiRuntimeStatusResponse(null, List.of(), null, List.of()));
    return new AiPreflightService(service, runtime).getPreflight();
  }
}
