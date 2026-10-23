package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.LINUX)
class NativeExecutableModeTest {
  @TempDir Path root;

  @Test void onlyDeclaredExecutableGetsExecutePermissionIncludingIdempotentExtraction() throws Exception {
    Path archive = root.resolve("runtime.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (String name : new String[] {"llama-server", "libggml.so", "model.gguf", "llama-server.exe"}) {
        zip.putNextEntry(new ZipEntry(name));
        zip.write("contents".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    Path target = root.resolve("extracted");
    ArchiveInstallRepairTest.extract(archive, target);
    assertTrue(Files.isExecutable(target.resolve("llama-server")));
    for (String name : new String[] {"libggml.so", "model.gguf", "llama-server.exe"}) {
      assertFalse(Files.isExecutable(target.resolve(name)), name);
    }
    assertTrue(target.resolve("llama-server").toFile().setExecutable(false, false));
    ArchiveInstallRepairTest.extract(archive, target);
    assertTrue(Files.isExecutable(target.resolve("llama-server")));
    assertFalse(Files.exists(target.resolve("llama-server.partial")));
  }

  @Test void zipTraversalIsStillRejected() throws Exception {
    Path archive = root.resolve("hostile.zip");
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      zip.putNextEntry(new ZipEntry("../escape"));
      zip.write(1);
      zip.closeEntry();
    }
    assertThrows(java.io.IOException.class,
        () -> ArchiveInstallRepairTest.extract(archive, root.resolve("extracted")));
    assertFalse(Files.exists(root.resolve("escape")));
  }
}
