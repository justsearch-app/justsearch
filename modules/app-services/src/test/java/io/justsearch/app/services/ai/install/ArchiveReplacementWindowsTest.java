/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("windows")
@EnabledOnOs(OS.WINDOWS)
class ArchiveReplacementWindowsTest {
  @TempDir Path root;

  @Test void heldOpenChangedFileIsReplacedOrLeavesUnreceiptedRepair() throws Exception {
    Path archive = root.resolve("pack.zip");
    Files.write(archive, ArchiveInstallRepairTest.zip(Map.of("library.dll", "new-bytes")));
    Path target = root.resolve("library.dll");
    Files.writeString(target, "old-bytes");
    try (FileChannel held = FileChannel.open(target, StandardOpenOption.READ)) {
      try {
        AiInstallService.extractZipInPlace(archive, root);
        assertEquals("new-bytes", Files.readString(target));
        assertTrue(Files.isRegularFile(root.resolve(".archive-receipts/pack.zip.json")));
        var oldBytes = java.nio.ByteBuffer.allocate(9);
        held.read(oldBytes);
        assertEquals("old-bytes", new String(oldBytes.array(), java.nio.charset.StandardCharsets.UTF_8));
      } catch (IOException sharingViolation) {
        assertFalse(Files.exists(root.resolve(".archive-receipts/pack.zip.json")));
        assertTrue(Files.exists(root.resolve(".archive-receipts/pack.zip.json.pending")));
        assertEquals("old-bytes", Files.readString(target), "a refused replacement must not truncate the final file");
      }
      assertTrue(held.isOpen());
    }
    AiInstallService.extractZipInPlace(archive, root);
    assertEquals("new-bytes", Files.readString(target));
  }

  @Test void replacementRejectsWindowsJunctionOutOfDestination() throws Exception {
    Path outside = root.resolve("outside");
    Path target = root.resolve("target");
    Files.createDirectories(outside);
    Files.createDirectories(target);
    Files.writeString(outside.resolve("library.dll"), "old-bytes");
    Path archive = root.resolve("pack.zip");
    Files.write(archive, ArchiveInstallRepairTest.zip(Map.of("redirect/library.dll", "new-bytes")));
    Path junction = target.resolve("redirect");
    Process maker = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", junction.toString(), outside.toString())
        .redirectErrorStream(true).start();
    if (!maker.waitFor(15, TimeUnit.SECONDS)) {
      maker.destroyForcibly();
      fail("junction creation timed out");
    }
    assertEquals(0, maker.exitValue(), new String(maker.getInputStream().readAllBytes()));
    try {
      assertThrows(IOException.class, () -> AiInstallService.extractZipInPlace(archive, target));
      assertEquals("old-bytes", Files.readString(outside.resolve("library.dll")));
    } finally {
      Files.deleteIfExists(junction);
    }
  }
}
