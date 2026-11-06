/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import io.justsearch.app.api.DownloadedFiles;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 3 of "Remove files" (design D4), on real files in a temporary models folder only: on
 * a platform without directory handles a captured file swapped after its last check is kept, never
 * deleted (POSIX checks the entry and then unlinks it by name relative to the checked removal
 * folder's handle, which the decided threat model accepts: programs under the user's own account
 * are trusted); and a new file that appears at a
 * canonical place after its own file was captured is kept, reported, and keeps the removal from
 * being reported as complete, also after a crash.
 */
@Tag("cross-platform")
final class ModelFileRemovalRepair3Test {
  /** ModelFileRemoval.KEPT_NEW_FILE, as the page shows it. */
  private static final String KEPT_NEW_FILE = "kept - a new file appeared here";
  /** ModelFileRemoval.KEPT_CHANGED. */
  private static final String KEPT_CHANGED = "kept - it changed while being removed";
  /** ModelFileRemoval.KEPT_NOT_DELETED. */
  private static final String KEPT_NOT_DELETED = "kept - could not be deleted";
  /** ModelFileRemoval.KEPT_PUT_BACK. */
  private static final String KEPT_PUT_BACK = "kept - the removal was interrupted; the file was put back";

  private static final byte[] NEWCOMER = RemovalModelsFixture.bytes("someone else's new file");
  private static final byte[] SWAPPED_IN = RemovalModelsFixture.bytes("swapped in after the last check");

  @TempDir Path tmp;

  private final ModelFileRemovalTest.FakeSettings settings = new ModelFileRemovalTest.FakeSettings();
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private RemovalModelsFixture fixture;

  private ModelFileRemoval engine(Path models) throws Exception {
    return new ModelFileRemoval(fixture.home, models, () -> fixture.registry, () -> null,
        new ModelFileRemovalFence());
  }

  private DownloadedFiles.Part part(ModelFileRemoval engine, String id) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private static List<String> reasons(DownloadedFiles.Removal removal, String path) {
    return removal.kept.stream().filter(f -> f.path.equals(path)).map(f -> f.reason).toList();
  }

  private static List<String> deleted(DownloadedFiles.Removal removal) {
    return removal.deleted.stream().map(f -> f.path).toList();
  }

  // --- 1. Never deleted by name after the last check ------------------------------------------

  @Test
  void whereThereAreNoDirectoryHandlesACapturedFileSwappedAfterItsLastCheckIsNeverDeletedByName()
      throws Exception {
    assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
        "Windows deletes through a verified file handle instead (ModelFileRemovalWindowsTest)");
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    var noHandles = new NoDirectoryHandlesFileSystem(FileSystems.getDefault());
    ModelFileRemoval engine = engine(noHandles.wrap(fixture.models));
    Path models = fixture.models.toAbsolutePath().normalize();
    Path aside = tmp.resolve("aside.gguf");
    AtomicBoolean armed = new AtomicBoolean();
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("verified:answer.gguf")) armed.set(true);
    });
    // The deletion's next step opens the models folder; whatever happens then happens after the
    // last check by name: the verified file is swapped out of its captured name for another.
    noHandles.onOpenDirectory = dir -> {
      if (!dir.equals(models) || !armed.getAndSet(false)) return;
      try {
        Path staged = fixture.stagedFiles().getFirst();
        Files.move(staged, aside);
        Files.write(staged, SWAPPED_IN);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    };

    DownloadedFiles.Removal removal =
        engine.prepare("chat", part(engine, "chat").previewDigest, settings, ELIGIBLE, IDLE).finish();

    List<Path> staged = fixture.stagedFiles();
    assertEquals(1, staged.size(), "the swapped-in file is still in the removal folder: " + staged);
    assertArrayEquals(SWAPPED_IN, Files.readAllBytes(staged.getFirst()),
        "the file swapped in after the last check was not deleted");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(aside), "precondition: swapped");
    assertEquals(List.of(KEPT_CHANGED), reasons(removal, staged.getFirst().getParent().getFileName()
        + "/" + staged.getFirst().getFileName()));
    assertArrayEquals(RemovalModelsFixture.PROJECTOR,
        Files.readAllBytes(fixture.models.resolve("mmproj-F16.gguf")),
        "without a handle to delete through, the verified projector is put back, not deleted by name");
    assertEquals(List.of(KEPT_NOT_DELETED), reasons(removal, "mmproj-F16.gguf"));
    assertTrue(removal.deleted.isEmpty(), "nothing was deleted: " + deleted(removal));
    assertEquals("not-removed", removal.state, removal.message);
  }

  // --- 3. A new file at a canonical place is kept and reported --------------------------------

  @Test
  void aNewFileWrittenAtTheCanonicalPlaceWhileTheCapturedFileIsCheckedIsKeptAndReported()
      throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    ModelFileRemoval engine = engine(fixture.models);
    Path canonical = fixture.models.resolve("answer.gguf");
    engine.setCheckpointHookForTest(name -> {
      // Captured, not yet hashed: its place is free, and another program writes there.
      if (!name.equals("captured:answer.gguf")) return;
      try {
        Files.write(canonical, NEWCOMER);
      } catch (java.io.IOException e) {
        throw new UncheckedIOException(e);
      }
    });

    DownloadedFiles.Removal removal =
        engine.prepare("chat", part(engine, "chat").previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertTrue(deleted(removal).containsAll(List.of("answer.gguf", "mmproj-F16.gguf")),
        "precondition: the verified captured files were deleted: " + deleted(removal));
    assertArrayEquals(NEWCOMER, Files.readAllBytes(canonical), "the new file is never deleted");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(removal, "answer.gguf"));
    assertEquals(Long.valueOf(NEWCOMER.length), removal.kept.stream()
        .filter(f -> f.path.equals("answer.gguf")).findFirst().orElseThrow().sizeBytes, "measured");
    assertEquals("partly-removed", removal.state, "not removed while a file sits there: " + removal.message);

    DownloadedFiles.Removal shown = engine.view(settings.settings, ELIGIBLE).removal;
    assertEquals("partly-removed", shown.state, "the page's report says the same");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(shown, "answer.gguf"));
  }

  @Test
  void aNewFileAtTheCanonicalPlaceOfADeletedFileIsReportedAfterACrash() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    ModelFileRemoval engine = engine(fixture.models);
    Path canonical = fixture.models.resolve("answer.gguf");
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("captured:mmproj-F16.gguf")) return;
      try {
        Files.write(canonical, NEWCOMER); // after answer.gguf was deleted
      } catch (java.io.IOException e) {
        throw new UncheckedIOException(e);
      }
      throw new ModelFileRemovalTest.SimulatedDeath();
    });
    ModelFileRemoval.Run run =
        engine.prepare("chat", part(engine, "chat").previewDigest, settings, ELIGIBLE, IDLE);
    assertThrows(ModelFileRemovalTest.SimulatedDeath.class, run::finish);

    // A new process over the same folders.
    DownloadedFiles.Removal report = engine(fixture.models).view(settings.settings, ELIGIBLE).removal;

    assertEquals("interrupted", report.state);
    assertEquals(List.of("answer.gguf"), deleted(report), "precondition: the verified file went");
    assertEquals(List.of(KEPT_NEW_FILE), reasons(report, "answer.gguf"));
    assertArrayEquals(NEWCOMER, Files.readAllBytes(canonical), "recovery never deletes");
    assertEquals(List.of(KEPT_PUT_BACK), reasons(report, "mmproj-F16.gguf"));
    assertFalse(fixture.stagedFiles().stream().findAny().isPresent(), "nothing left captured");
  }
}
