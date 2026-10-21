/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AiInstallPolicyIntegrationTest extends LocalApiIntegrationTestBase {
  @Test
  void missingPolicyKeepsPermissiveDefaults() throws Exception {
    var response = getJson("/api/policy/effective");
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(response.json().path("downloadsEnabled").asBoolean());
    assertTrue(response.json().path("gpuAccelerationEnabled").asBoolean());
  }

  @Test
  void gpuOffCpuPreviewAndDownloadDenialUseTheRealApi() throws Exception {
    Files.writeString(aiHome.resolve("policy.v1.json"),
        "{\"schemaVersion\":1,\"downloadsEnabled\":false,\"gpuAccelerationEnabled\":false}");
    var effective = getJson("/api/policy/effective");
    assertFalse(effective.json().path("downloadsEnabled").asBoolean());
    assertFalse(effective.json().path("gpuAccelerationEnabled").asBoolean());
    var preview = getJson("/api/ai/install/plan-preview");
    assertEquals(200, preview.statusCode(), preview.body());
    assertEquals("CPU", preview.json().path("downloadProfile").asText());
    for (String operation : List.of("start", "repair")) {
      var response = postJson("/api/ai/install/" + operation, Map.of("acceptTerms", true));
      assertEquals(403, response.statusCode(), response.body());
      assertEquals("DOWNLOADS_DISABLED", response.json().path("errorCode").asText());
    }
    assertFalse(Files.exists(aiHome.resolve("install-contract.v2.json")));
    assertFalse(Files.exists(aiHome.resolve("models")));
  }

  @Test
  void allowlistedOfflineImportStillWorksWithDownloadsDisabled() throws Exception {
    BuiltPack pack = buildZipModelsPack(tmp.resolve("offline.zip"));
    Files.writeString(aiHome.resolve("policy.v1.json"), MAPPER.writeValueAsString(Map.of(
        "schemaVersion", 1, "downloadsEnabled", false,
        "allowlists", Map.of("packManifestSha256", List.of(pack.manifestSha256())))));
    assertFalse(getJson("/api/policy/effective").json().path("downloadsEnabled").asBoolean());
    var start = postJson("/api/ai/packs/import",
        Map.of("path", pack.zipPath().toString(), "allowDowngrade", false));
    assertEquals(200, start.statusCode(), start.body());
    var done = awaitPackImportDone(Duration.ofSeconds(10));
    assertEquals("completed", done.path("state").asText(), done.toString());
    assertTrue(Files.isRegularFile(aiHome.resolve("models/chat.gguf")));
    assertTrue(Files.isRegularFile(aiHome.resolve("models/embed.gguf")));
  }

  @Test
  void corruptDetectedMachinePolicyKeepsFailClosedDefaults() throws Exception {
    assertTrue(isProgramDataSandbox(), "machine fixture requires Gradle's PROGRAMDATA sandbox");
    var machine = machinePolicyPathOrNull();
    Files.createDirectories(machine.getParent());
    Files.writeString(machine, "{ corrupt machine policy");
    String previousOs = System.getProperty("os.name");
    try {
      // Select only the Windows policy reader; the regular file and its parse failure are real.
      System.setProperty("os.name", "Windows 11");
      var effective = new io.justsearch.app.services.policy.EnterprisePolicyServiceImpl().snapshot();
      assertTrue(effective.machine().present());
      assertFalse(effective.downloadsEnabled());
      assertFalse(effective.onlineAiEnabled());
      assertFalse(effective.gpuAccelerationEnabled());
    } finally {
      if (previousOs == null) System.clearProperty("os.name");
      else System.setProperty("os.name", previousOs);
    }
  }
}
