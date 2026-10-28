package io.justsearch.app.services.ai.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RuntimeNativeResolutionTest {
  @TempDir Path root;

  @Test void omittedAndDefaultKeepExactOperatorPath() throws Exception {
    Path exe = Files.writeString(root.resolve("operator server"), "#!/bin/sh\nexit 0\n");
    assertTrue(exe.toFile().setExecutable(true, true));
    for (String variant : new String[] {null, "", "default"}) {
      assertEquals(exe, RuntimeActivationService.activationExecutable(root, variant, exe));
    }
  }

  @Test void explicitCuda12NeverSelectsCuda13Override() throws Exception {
    Path exe = Files.writeString(root.resolve("cuda13"), "binary");
    assertTrue(exe.toFile().setExecutable(true, true));
    assertThrows(IllegalStateException.class,
        () -> RuntimeActivationService.activationExecutable(root, "cuda12", exe));
  }

  @Test void invalidOverridesCannotFallBackToWindowsPack() throws Exception {
    Files.writeString(root.resolve("llama-server.exe"), "Windows executable");
    Path noExecute = Files.writeString(root.resolve("no-execute"), "binary");
    for (Path path : new Path[] {null, root, root.resolve("missing"), noExecute}) {
      assertThrows(IllegalStateException.class,
          () -> RuntimeActivationService.activationExecutable(root, "default", path));
    }
  }

  @Test void operatorRuntimeUsesTheSameResolvedPathAsInference() throws Exception {
    var previous = io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
    try {
      var resolved = io.justsearch.configuration.resolved.ResolvedConfig.builder()
          .put("justsearch.server.exe", 400, "env_var", "JUSTSEARCH_SERVER_EXE",
              "operator dir/operator server")
          .build();
      io.justsearch.configuration.resolved.ConfigStore.setGlobal(
          new io.justsearch.configuration.resolved.ConfigStore(resolved));
      var method = RuntimeActivationService.class.getDeclaredMethod("operatorServerExeOrNull");
      method.setAccessible(true);
      assertEquals(resolved.ai().serverExe(), method.invoke(null));
      assertEquals(Path.of("operator dir/operator server"), resolved.ai().serverExe());
    } finally {
      io.justsearch.configuration.resolved.TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test void windowsBackendKeepsVariantAndBaselineLayouts() throws Exception {
    String previous = System.getProperty("os.name");
    try {
      System.setProperty("os.name", "Windows 11");
      Path variants = Files.createDirectories(root.resolve("variants"));
      Path cuda = Files.createDirectories(variants.resolve("cuda12")).resolve("llama-server.exe");
      Files.writeString(cuda, "binary");
      Path baseline = Files.writeString(root.resolve("llama-server.exe"), "binary");
      assertEquals(cuda, RuntimeActivationService.activationExecutable(variants, "cuda12", null));
      assertEquals(baseline, RuntimeActivationService.activationExecutable(variants, "default", null));
    } finally {
      System.setProperty("os.name", previous);
    }
  }
  @Test void linuxDownloadedPackIsUsedForExplicitAndDefaultActivation() throws Exception {
    Path variants = Files.createDirectories(root.resolve("variants"));
    Path cuda = Files.createDirectories(variants.resolve("cuda12")).resolve("llama-server");
    Files.writeString(cuda, "#!/bin/sh\nexit 0\n");
    assertTrue(cuda.toFile().setExecutable(true, true));
    for (String variant : new String[] {null, "", "default", "cuda12"}) {
      assertEquals(cuda, RuntimeActivationService.activationExecutable(variants, variant, null));
    }
  }

  @Test void linuxNeverDiscoversAnUnconfiguredFlatCpuFallback() throws Exception {
    Path variants = Files.createDirectories(root.resolve("variants"));
    Path flat = Files.writeString(root.resolve("llama-server"), "#!/bin/sh\nexit 0\n");
    assertTrue(flat.toFile().setExecutable(true, true));
    assertThrows(IllegalStateException.class,
        () -> RuntimeActivationService.activationExecutable(variants, "default", null));
  }

}
