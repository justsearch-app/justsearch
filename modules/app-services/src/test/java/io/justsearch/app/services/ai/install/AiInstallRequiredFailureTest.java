/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.configuration.NativeTargets;
import io.justsearch.configuration.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A finished acquisition is unsuccessful if required files could not be acquired. */
final class AiInstallRequiredFailureTest {
  @TempDir Path home;

  @Test
  void allDownloadsFailedWithPlatformSkipsReportsFailureOnBothPlatforms() throws Exception {
    checkFailure(false);
  }

  @Test
  void partialFailureKeepsAcquiredFilesAndNamesTheFailureOnBothPlatforms() throws Exception {
    checkFailure(true);
  }

  private void checkFailure(boolean partialSuccess) throws Exception {
    byte[] bytes = "verified model fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    var shipped = ModelRegistryLoader.loadFromClasspath("ai/model-registry.v2.json");
    var broken = new ModelPackage("embedding", "Search embeddings", "Fixture", "embedding",
        List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            sha, bytes.length, "https://example.invalid/broken")), List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_CORE);
    var good = new ModelPackage("reranker", "Reranker", "Fixture", "reranker",
        List.of(new ModelVariant("model.onnx", ModelPrecision.FP32, ExecutionProvider.CPU,
            sha, bytes.length, "https://example.invalid/good")), List.of(),
        0, null, null, null, CapabilityTier.RETRIEVAL_ENRICHMENT);
    var packages = new java.util.ArrayList<>(List.of(broken, shipped.findPackage("chat"),
        shipped.findPackage("cuda-runtime")));
    if (partialSuccess) packages.add(good);
    var registry = new ModelRegistry(2, "required failure", packages);
    for (String targetId : List.of("linux-x86_64", "windows-x86_64")) {
      var nativeTarget = NativeTargets.forTarget(targetId);
      Path data = home.resolve(targetId);
      try (var target = mockStatic(NativeTargets.class, CALLS_REAL_METHODS);
           var restore = mockStatic(RuntimeRestoreUtil.class);
           var download = mockConstruction(DownloadExecutor.class, (mock, context) -> {
             when(mock.lastFailure()).thenReturn(new TransportFailure(
                 "curl unavailable", "Install curl and retry the download.", false));
             when(mock.transfer(anyString(), any(), any(), any(), anyInt())).thenAnswer(call -> {
               if (!call.<String>getArgument(0).endsWith("/good")) return false;
               Files.write(call.<Path>getArgument(1), bytes);
               return true;
             });
           })) {
        assertNotNull(download);
        target.when(NativeTargets::current).thenReturn(nativeTarget);
        restore.when(() -> RuntimeRestoreUtil.ensureRuntimePresent(data)).thenReturn(true);
        var service = spy(new AiInstallService(null, null, null, null, data));
        doReturn(registry).when(service).getManifest();
        doReturn(HardwareProfile.cpuOnly()).when(service).detectHardwareProfile();
        // Real stage orchestration and disk reconciliation; only transport and native restore are fake.
        var run = java.util.Arrays.stream(AiInstallService.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("runInstallInternal")).findFirst().orElseThrow();
        run.setAccessible(true);
        var leaseConstructor = run.getParameterTypes()[0]
            .getDeclaredConstructor(io.justsearch.app.api.OperationLeaseHandle.class);
        leaseConstructor.setAccessible(true);
        run.invoke(service, leaseConstructor.newInstance(mock(io.justsearch.app.api.OperationLeaseHandle.class)));
        var status = service.getStatus();
        assertEquals("failed", status.state, targetId + ": " + status.message);
        assertFalse(status.installedFully);
        assertTrue(status.repairNeeded);
        assertNotEquals("activation_required", status.phase);
        assertTrue(status.lastError.contains("embedding"), status.lastError);
        assertTrue(status.lastError.contains("Install curl and retry"), status.lastError);
        assertFalse(status.message.startsWith("AI installed"), status.message);
        if (partialSuccess) {
          assertEquals("installed", status.packages.stream().filter(p -> p.packageId.equals("reranker"))
              .findFirst().orElseThrow().state);
          var plan = InstallPlanner.plan(registry, HardwareProfile.cpuOnly(), service.modelsDir(), data);
          assertTrue(plan.alreadyInstalled().contains("reranker"));
        }
      }
    }
  }
}
