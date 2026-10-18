package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.TestResolvedConfigHelper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class RuntimeRestoreNativeTest {
  @TempDir Path root;

  private void withRuntime(Path exe, org.junit.jupiter.api.function.Executable check) throws Throwable {
    var previous = ConfigStore.globalOrNull();
    try {
      ConfigStore.setGlobal(new ConfigStore(TestResolvedConfigHelper.fromEntries(
          exe == null ? Map.of() : Map.of("justsearch.server.exe", exe.toString()))));
      check.execute();
    } finally {
      TestResolvedConfigHelper.restoreGlobal(previous);
    }
  }

  @Test void externalRuntimeIsUsedInPlaceWithoutCopyingOrModifyingIt() throws Throwable {
    Path exe = Files.writeString(root.resolve("operator server"), "runtime");
    assertTrue(exe.toFile().setExecutable(true, true));
    Path home = root.resolve("ai-home");
    withRuntime(exe, () -> assertTrue(RuntimeRestoreUtil.ensureRuntimePresent(home)));
    assertEquals("runtime", Files.readString(exe));
    assertFalse(Files.exists(home));
  }

  @Test void invalidExternalRuntimeIsRefusedAndWindowsFilesAreNotRestored() throws Throwable {
    Path home = Files.createDirectories(root.resolve("ai-home/native-bin/llama-server"));
    Files.writeString(home.resolve("llama-server.exe"), "Windows executable");
    withRuntime(null, () -> assertFalse(RuntimeRestoreUtil.ensureRuntimePresent(root.resolve("ai-home"))));
    withRuntime(root.resolve("missing"), () -> assertThrows(IllegalStateException.class,
        () -> RuntimeRestoreUtil.ensureRuntimePresent(root.resolve("ai-home"))));
    assertFalse(Files.exists(home.resolve("llama-server")));
  }
}
