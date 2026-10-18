package io.justsearch.app.services.ai.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.app.api.ApiErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class RuntimePackPlatformTest {
  @Test void windowsRuntimePackIsRefusedBeforeInstallation() {
    var manifest = new AiPackManifestV1();
    manifest.schemaVersion = 1;
    manifest.packId = "windows-runtime";
    manifest.packVersion = "1";
    manifest.kind = "runtime";
    manifest.variantId = "cuda12";
    var result = AiPackValidator.validateRuntimePack(manifest);
    assertFalse(result.ok());
    assertEquals(ApiErrorCode.PACK_KIND_UNSUPPORTED, result.errorCode());
    assertTrue(result.message().contains("JUSTSEARCH_SERVER_EXE"));
  }
}
