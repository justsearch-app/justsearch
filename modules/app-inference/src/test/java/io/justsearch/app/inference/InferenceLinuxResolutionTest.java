package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class InferenceLinuxResolutionTest {
  @TempDir Path root;

  private Path resolve(Map<String, String> entries) throws Exception {
    var method = InferenceConfig.class.getDeclaredMethod(
        "findServerExecutable", ResolvedConfig.class, Path.class, boolean.class);
    method.setAccessible(true);
    try {
      return (Path) method.invoke(null, TestResolvedConfigHelper.fromEntries(entries), root, true);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof RuntimeException cause) throw cause;
      throw e;
    }
  }

  @Test void nativeNameDoesNotSelectWindowsArtifact() throws Exception {
    Path bin = root.resolve("native-bin/llama-server");
    Files.createDirectories(bin);
    Files.writeString(bin.resolve("llama-server.exe"), "Windows binary");
    assertEquals(bin.resolve("llama-server"), resolve(Map.of()));
  }

  @Test void exactExecutableOverrideWins() throws Exception {
    Path exe = Files.writeString(root.resolve("operator server"), "#!/bin/sh\nexit 0\n");
    assertTrue(exe.toFile().setExecutable(true, true));
    assertEquals(exe, resolve(Map.of("justsearch.server.exe", exe.toString())));
  }

  @Test void missingOverrideIsRefused() {
    assertThrows(IllegalStateException.class,
        () -> resolve(Map.of("justsearch.server.exe", root.resolve("missing").toString())));
  }

  @Test void nonExecutableOverrideIsRefused() throws Exception {
    Path exe = Files.writeString(root.resolve("not executable"), "data");
    assertThrows(IllegalStateException.class,
        () -> resolve(Map.of("justsearch.server.exe", exe.toString())));
  }

  @Test void directoryOverrideIsRefused() {
    assertThrows(IllegalStateException.class,
        () -> resolve(Map.of("justsearch.server.exe", root.toString())));
  }
}
