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
 * in use; once it is released the next removal takes it. Repair round 3: a captured file is deleted
 * only through the one handle its identity, links and hash were checked through; a file swapped in
 * under its captured name before that handle opened is not deleted, the name cannot be swapped
 * while the handle is held, and a file that cannot be opened that way is kept and put back.
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

  private static String digest(ModelFileRemoval engine, ModelFileRemovalTest.FakeSettings settings,
      ModelFileRemoval.Eligibility eligible) {
    return engine.view(settings.settings, eligible).parts.stream()
        .filter(p -> p.id.equals("chat")).findFirst().orElseThrow().previewDigest;
  }

  @Test
  void aFileSwappedInUnderTheCapturedNameBeforeTheDeletingHandleOpensIsNotDeleted() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var settings = new ModelFileRemovalTest.FakeSettings();
    ModelFileRemoval.Eligibility eligible = (id, s) -> Optional.empty();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence());
    fixture.writeChat();
    Path aside = tmp.resolve("aside.gguf");
    byte[] swappedIn = RemovalModelsFixture.bytes("swapped in after the checks by name");
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("verified:answer.gguf")) return;
      try {
        Path staged = fixture.stagedFiles().getFirst();
        Files.move(staged, aside);
        Files.write(staged, swappedIn);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest(engine, settings, eligible),
        settings, eligible, id -> Optional.empty()).finish();

    var staged = fixture.stagedFiles();
    assertEquals(1, staged.size(), "the swapped-in file stays in the removal folder: " + staged);
    assertArrayEquals(swappedIn, Files.readAllBytes(staged.getFirst()), "not deleted");
    assertEquals(ModelFileRemoval.KEPT_CHANGED, removal.kept.stream()
        .filter(f -> f.path.startsWith(ModelFileRemoval.REMOVAL_FOLDER + "/")).findFirst()
        .orElseThrow().reason);
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(aside), "precondition: swapped");
    assertFalse(removal.deleted.stream().anyMatch(f -> f.path.equals("answer.gguf")));
  }

  @Test
  void theCapturedNameCannotBeSwappedWhileTheVerifiedHandleDeletesIt() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var settings = new ModelFileRemovalTest.FakeSettings();
    ModelFileRemoval.Eligibility eligible = (id, s) -> Optional.empty();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence());
    fixture.writeChat();
    Path aside = tmp.resolve("aside.gguf");
    var refusedRename = new java.util.concurrent.atomic.AtomicBoolean();
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("deleting:answer.gguf")) return;
      // After every check through the handle: renaming the file away must fail while it is held.
      try {
        Files.move(fixture.stagedFiles().getFirst(), aside);
      } catch (java.nio.file.FileSystemException held) {
        refusedRename.set(true);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest(engine, settings, eligible),
        settings, eligible, id -> Optional.empty()).finish();

    assertTrue(refusedRename.get(), "the held, verified file could not be renamed away");
    assertFalse(Files.exists(aside));
    assertEquals("removed", removal.state, removal.message);
    assertTrue(fixture.stagedFiles().isEmpty(), "the verified file itself was deleted");
  }

  @Test
  void aCapturedFileAnotherProgramOpensForWritingIsKeptBecauseItsHandleCannotBeTaken()
      throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var settings = new ModelFileRemovalTest.FakeSettings();
    ModelFileRemoval.Eligibility eligible = (id, s) -> Optional.empty();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence());
    fixture.writeChat();
    FileChannel[] writer = new FileChannel[1];
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("captured:answer.gguf")) return;
      try {
        // Java's default sharing lets the capture's rename and put-back by name proceed, but a
        // writer refuses the deleting handle, which shares reading only.
        writer[0] = FileChannel.open(fixture.stagedFiles().getFirst(), StandardOpenOption.WRITE);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal;
    try {
      removal = engine.prepare("chat", digest(engine, settings, eligible), settings, eligible,
          id -> Optional.empty()).finish();
    } finally {
      if (writer[0] != null) writer[0].close();
    }

    assertFalse(removal.deleted.stream().anyMatch(f -> f.path.equals("answer.gguf")),
        "not deleted without its handle");
    String reason = removal.kept.stream().filter(f -> f.path.equals("answer.gguf")
        || f.path.startsWith(ModelFileRemoval.REMOVAL_FOLDER + "/")).findFirst().orElseThrow().reason;
    assertTrue(reason.equals(ModelFileRemoval.KEPT_NOT_DELETED)
        || reason.equals(ModelFileRemoval.KEPT_IN_REMOVAL_FOLDER), reason);
    Path back = fixture.models.resolve("answer.gguf");
    Path kept = Files.exists(back) ? back : fixture.stagedFiles().getFirst();
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(kept), "kept, unchanged");
    assertEquals("partly-removed", removal.state, removal.message);
  }
}
