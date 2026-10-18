package io.justsearch.app.inference;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.parallel.ResourceLock("SYSTEM_PROPERTIES")
class InferenceExplicitExecutableTest {
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

  @Test void invalidExplicitOverrideNeverFallsBackOnEitherTarget() throws Exception {
    String os = System.getProperty("os.name");
    String arch = System.getProperty("os.arch");
    try {
      System.setProperty("os.arch", "amd64");
      for (String platform : java.util.List.of("Windows 11", "Linux")) {
        System.setProperty("os.name", platform);
        Path bin = Files.createDirectories(root.resolve("native-bin/llama-server"));
        Path fallback = bin.resolve(platform.startsWith("Windows") ? "llama-server.exe" : "llama-server");
        Files.writeString(fallback, "fixture baseline");
        assertThrows(IllegalStateException.class,
            () -> resolve(Map.of("justsearch.server.exe", root.resolve("missing").toString())), platform);
        assertThrows(IllegalStateException.class,
            () -> resolve(Map.of("justsearch.server.exe", root.toString())), platform);
      }
    } finally {
      System.setProperty("os.name", os);
      System.setProperty("os.arch", arch);
    }
  }
}
