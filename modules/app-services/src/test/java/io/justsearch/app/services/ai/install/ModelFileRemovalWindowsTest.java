/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.app.api.DownloadedFiles;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Design D4 on Windows: a model file another program holds open without delete sharing (as a
 * native session holds its weights) cannot be captured, so it is kept, unchanged, and reported as
 * in use; once it is released the next removal takes it.
 */
@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
final class ModelFileRemovalWindowsTest {
  @TempDir Path tmp;

  @Test
  void aFileHeldOpenWithoutDeleteSharingIsKeptUntilItIsReleased() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var settings = new ModelFileRemovalTest.FakeSettings();
    ModelFileRemoval.Eligibility eligible = (id, s) -> Optional.empty();
    ModelFileRemoval.UsageProbe idle = id -> Optional.empty();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence());
    fixture.writeChat();
    Path model = fixture.models.resolve("answer.gguf");

    DownloadedFiles.Removal held;
    try (FileChannel session = FileChannel.open(model, StandardOpenOption.READ,
        com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)) {
      String digest = engine.view(settings.settings, eligible).parts.stream()
          .filter(p -> p.id.equals("chat")).findFirst().orElseThrow().previewDigest;
      held = engine.prepare("chat", digest, settings, eligible, idle).finish();
      ByteBuffer first = ByteBuffer.allocate(RemovalModelsFixture.CHAT.length);
      session.read(first, 0);
      assertArrayEquals(RemovalModelsFixture.CHAT, first.array(), "the holder still reads its file");
    }

    assertEquals(ModelFileRemoval.KEPT_IN_USE, held.kept.stream()
        .filter(f -> f.path.equals("answer.gguf")).findFirst().orElseThrow().reason);
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(model), "kept, unchanged");
    assertFalse(Files.exists(fixture.models.resolve("mmproj-F16.gguf")), "the file nobody held went");
    assertEquals("partly-removed", held.state, held.message);
    assertTrue(fixture.stagedFiles().isEmpty(), "nothing was left in the removal folder");

    String again = engine.view(settings.settings, eligible).parts.stream()
        .filter(p -> p.id.equals("chat")).findFirst().orElseThrow().previewDigest;
    DownloadedFiles.Removal released = engine.prepare("chat", again, settings, eligible, idle).finish();

    assertEquals("removed", released.state, released.message);
    assertFalse(Files.exists(model), "taken once released");
  }
}
