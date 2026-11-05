/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.DownloadedFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 1 of "Remove files" (design D4), on real files in a temporary models folder only:
 * the ownership boundary includes the models folder and every folder above it; recovery acts only
 * on a well-formed record and on plain folders inside the models folder; restoring never falls
 * back to an overwriting move; a checkpoint that cannot be saved still puts the captured file back
 * and leaves no running record without an owner; a part missing a required file is not usable.
 */
final class ModelFileRemovalSafetyTest {
  /** The engine's whole-removal refusal for a linked models folder (ModelFileRemoval.ROOT_LINKED). */
  private static final String ROOT_LINKED =
      "The models folder is a link or inside a linked folder, so nothing can be removed here";
  /** The engine's reason for a recorded file whose place cannot be checked (KEPT_UNCERTAIN). */
  private static final String UNCERTAIN = "kept - not touched: its place could not be checked";

  @TempDir Path tmp;

  private final ModelFileRemovalTest.FakeSettings settings = new ModelFileRemovalTest.FakeSettings();
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private static ModelFileRemoval engine(Path home, Path models, RemovalModelsFixture fixture) {
    return new ModelFileRemoval(home, models, () -> fixture.registry, () -> null,
        new ModelFileRemovalFence());
  }

  private DownloadedFiles.Part part(ModelFileRemoval engine, String id) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  @Test
  void aModelsFolderThatIsALinkRefusesTheWholeRemovalAndDeletesNothing() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("real-home"));
    fixture.writeChat();
    Path linkedModels = tmp.resolve("linked-models");
    Files.createSymbolicLink(linkedModels, fixture.models);
    ModelFileRemoval engine = engine(fixture.home, linkedModels, fixture);

    DownloadedFiles.Part chat = part(engine, "chat");
    String digest = chat.previewDigest;

    assertFalse(chat.removable, "nothing behind a linked models folder is JustSearch's to delete");
    assertEquals(ROOT_LINKED, chat.reason);
    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish());
    assertEquals(ROOT_LINKED, refused.getMessage());
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
    assertArrayEquals(RemovalModelsFixture.PROJECTOR,
        Files.readAllBytes(fixture.models.resolve("mmproj-F16.gguf")));
    assertTrue(settings.declined.isEmpty(), "no decline was recorded");
  }

  @Test
  void aLinkedFolderAboveTheModelsFolderRefusesTheWholeRemovalAndDeletesNothing() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("real-home"));
    fixture.writeReranker();
    Path linkedHome = tmp.resolve("linked-home");
    Files.createSymbolicLink(linkedHome, fixture.home);
    ModelFileRemoval engine = engine(linkedHome, linkedHome.resolve("models"), fixture);

    DownloadedFiles.Part reranker = part(engine, "reranker");
    String digest = reranker.previewDigest;

    assertFalse(reranker.removable);
    assertEquals(ROOT_LINKED, reranker.reason);
    assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("reranker", digest, settings, ELIGIBLE, IDLE).finish());
    assertTrue(Files.exists(fixture.rerankerDir(0).resolve("model.onnx")));
    assertTrue(Files.exists(fixture.rerankerDir(1).resolve("model_fp16.onnx")));
  }

  @Test
  void recoveryNeverPutsBackThroughARemovalFolderReplacedByALink() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    ModelFileRemoval engine = engine(fixture.home, fixture.models, fixture);
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:answer.gguf")) throw new ModelFileRemovalTest.SimulatedDeath();
    });
    ModelFileRemoval.Run run = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE);
    assertThrows(ModelFileRemovalTest.SimulatedDeath.class, run::finish);
    List<Path> captured = fixture.stagedFiles();
    assertEquals(1, captured.size(), "precondition: one captured file");
    String stagedName = captured.get(0).getFileName().toString();
    // While the process is down, the removal folder is swapped for a link to an unrelated folder
    // that holds someone's file under the captured name.
    Path removalFolder = fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER);
    Files.move(removalFolder, tmp.resolve("moved-aside"));
    Path external = Files.createDirectories(tmp.resolve("external"));
    byte[] unrelated = RemovalModelsFixture.bytes("someone else's file");
    Files.write(external.resolve(stagedName), unrelated);
    Files.createSymbolicLink(removalFolder, external);

    ModelFileRemoval restarted = engine(fixture.home, fixture.models, fixture);
    DownloadedFiles.Removal report = assertDoesNotThrow(
        () -> restarted.view(settings.settings, ELIGIBLE).removal);

    assertTrue(Files.exists(external.resolve(stagedName)), "the unrelated file is still where it was");
    assertArrayEquals(unrelated, Files.readAllBytes(external.resolve(stagedName)));
    assertFalse(Files.exists(fixture.models.resolve("answer.gguf"), java.nio.file.LinkOption.NOFOLLOW_LINKS),
        "nothing was published at the canonical place from outside the models folder");
    assertNotNull(report);
    assertEquals("interrupted", report.state);
    assertTrue(report.kept.stream().anyMatch(f -> UNCERTAIN.equals(f.reason)),
        "the file is reported as not touched: " + report.kept.stream().map(f -> f.reason).toList());
  }

  @Test
  void recoveryNeverRestoresToARecordedPlaceOutsideTheModelsFolder() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    Path removalFolder = Files.createDirectories(fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER));
    String stagedName = UUID.randomUUID() + ".removing";
    Files.write(removalFolder.resolve(stagedName), RemovalModelsFixture.CHAT);
    Path outside = Files.createDirectories(tmp.resolve("outside"));
    Files.writeString(fixture.home.resolve(ModelFileRemoval.JOURNAL_FILENAME),
        "{\"version\":1,\"packageId\":\"chat\",\"label\":\"Chat model\",\"state\":\"running\","
            + "\"files\":[{\"path\":\"../../outside/victim.gguf\",\"staged\":\".removing/" + stagedName
            + "\",\"state\":\"captured\"}]}");
    ModelFileRemoval engine = engine(fixture.home, fixture.models, fixture);

    DownloadedFiles.Removal report = assertDoesNotThrow(
        () -> engine.view(settings.settings, ELIGIBLE).removal);

    assertFalse(Files.exists(outside.resolve("victim.gguf")), "nothing was written outside the models folder");
    assertTrue(Files.exists(removalFolder.resolve(stagedName)), "the captured file is kept, untouched");
    assertNotNull(report);
    assertEquals("interrupted", report.state);
    assertTrue(report.kept.stream().anyMatch(f -> f.path.equals(".removing/" + stagedName)),
        "the file left in the removal folder is reported");
  }

  @Test
  void restoringNeverFallsBackToAMoveWhenAnAtomicNoReplacePublicationIsUnavailable()
      throws Exception {
    // A hard link to a folder is refused by every file system, while a plain move of it succeeds:
    // only the removed fallback could put it at the canonical place.
    Path staged = Files.createDirectories(tmp.resolve("removing").resolve("captured.removing"));
    Path canonical = tmp.resolve("models").resolve("answer.gguf");
    Files.createDirectories(canonical.getParent());

    boolean restored = ModelFileRemoval.restoreWithoutOverwrite(staged, canonical);

    assertFalse(restored);
    assertTrue(Files.isDirectory(staged), "the captured entry stays in the removal folder");
    assertFalse(Files.exists(canonical), "nothing was moved into the canonical place");
  }

  @Test
  void aCheckpointThatCannotBeSavedAfterCapturePutsTheFileBackAndLeavesNoRunningRecord()
      throws Exception {
    assumeTrue(tmp.getFileSystem().supportedFileAttributeViews().contains("posix"),
        "the record folder is made read-only through POSIX permissions");
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    var fence = new ModelFileRemovalFence();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, fence);
    String digest = part(engine, "chat").previewDigest;
    ModelFileRemoval.Run run = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE);
    // The record's folder turns read-only just before the first capture: the checkpoint after the
    // capture cannot be saved.
    engine.setCheckpointHookForTest(name -> {
      if (name.startsWith("capturing:")) {
        try {
          Files.setPosixFilePermissions(fixture.home, PosixFilePermissions.fromString("r-xr-xr-x"));
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    });
    DownloadedFiles.Removal outcome;
    try {
      outcome = assertDoesNotThrow(run::finish, "a failed checkpoint is reported, not thrown");
      assumeTrue(!Files.isWritable(fixture.home), "running as a user that ignores permissions");
    } finally {
      Files.setPosixFilePermissions(fixture.home, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    assertTrue(Files.exists(fixture.models.resolve("answer.gguf")), "the captured file is back at its place");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
    assertArrayEquals(RemovalModelsFixture.PROJECTOR,
        Files.readAllBytes(fixture.models.resolve("mmproj-F16.gguf")), "never captured, never deleted");
    assertTrue(fixture.stagedFiles().isEmpty(), "nothing is left in the removal folder");
    assertNotEquals("removed", outcome.state, outcome.message);
    assertNotEquals("running", outcome.state, outcome.message);
    assertTrue(outcome.deleted.isEmpty());
    assertTrue(fence.removingPart().isEmpty(), "the lock was released");
    DownloadedFiles.Removal later = engine.view(settings.settings, ELIGIBLE).removal;
    assertNotEquals("running", later.state, "no running record is left without an owner");
    assertTrue(later.kept.stream().anyMatch(f -> f.path.equals("answer.gguf")), later.message);
  }

  @Test
  void aPartMissingARequiredFileIsIncompleteNotDownloaded() throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    // One retained variant whose tokenizer was edited: it fails the ownership check and is kept.
    Path dir = Files.createDirectories(fixture.rerankerDir(0));
    Files.write(dir.resolve("model.onnx"), RemovalModelsFixture.RERANK_FP32);
    Files.writeString(dir.resolve("tokenizer.json"), "{\"tokenizer\":\"edited by hand\"}");
    Files.write(dir.resolve("config.json"), RemovalModelsFixture.CONFIG);
    ModelFileRemoval engine = engine(fixture.home, fixture.models, fixture);
    String digest = part(engine, "reranker").previewDigest;

    DownloadedFiles.Removal removal = engine.prepare("reranker", digest, settings, ELIGIBLE, IDLE).finish();

    assertFalse(Files.exists(dir.resolve("model.onnx")), "precondition: the weights were removed");
    assertTrue(Files.exists(dir.resolve("tokenizer.json")), "precondition: the edited file was kept");
    assertEquals("removed", removal.state, removal.message);
    DownloadedFiles.Part after = part(engine, "reranker");
    assertEquals("incomplete", after.state, "a part without its weights is not usable");
  }

  @Test
  void aRecordWithoutAFileListIsReportedAsUnreadableAndTouchesNothing() throws Exception {
    malformedRecordIsReportedAsUnreadable(
        "{\"version\":1,\"packageId\":\"chat\",\"state\":\"running\",\"files\":null}");
  }

  @Test
  void aRecordWithANullFileEntryIsReportedAsUnreadableAndTouchesNothing() throws Exception {
    malformedRecordIsReportedAsUnreadable(
        "{\"version\":1,\"packageId\":\"chat\",\"state\":\"running\",\"files\":[null]}");
  }

  @Test
  void aRecordWithAnInvalidPathIsReportedAsUnreadableAndTouchesNothing() throws Exception {
    malformedRecordIsReportedAsUnreadable("{\"version\":1,\"packageId\":\"chat\",\"state\":\"running\","
        + "\"files\":[{\"path\":\"bad\\u0000name\",\"staged\":\".removing/left.removing\",\"state\":\"captured\"}]}");
  }

  @Test
  void aRecordWithAMissingPathIsReportedAsUnreadableAndTouchesNothing() throws Exception {
    malformedRecordIsReportedAsUnreadable("{\"version\":1,\"packageId\":\"chat\",\"state\":\"running\","
        + "\"files\":[{\"path\":null,\"staged\":\".removing/left.removing\",\"state\":\"captured\"}]}");
  }

  private void malformedRecordIsReportedAsUnreadable(String record) throws Exception {
    var fixture = new RemovalModelsFixture(tmp.resolve("home"));
    fixture.writeChat();
    Path staged = fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER).resolve("left.removing");
    Files.createDirectories(staged.getParent());
    Files.write(staged, RemovalModelsFixture.CHAT);
    Files.writeString(fixture.home.resolve(ModelFileRemoval.JOURNAL_FILENAME), record);
    ModelFileRemoval engine = engine(fixture.home, fixture.models, fixture);

    DownloadedFiles view = assertDoesNotThrow(() -> engine.view(settings.settings, ELIGIBLE));

    assertEquals("interrupted", view.removal.state);
    assertEquals("The removal record could not be read; files in the removal folder were kept",
        view.removal.message);
    assertTrue(view.removal.kept.stream().anyMatch(f -> f.path.equals(".removing/left.removing")));
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(staged), "kept untouched");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
  }
}
