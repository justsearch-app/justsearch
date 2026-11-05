/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.UiSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "Remove files" against a temporary models folder (design D4). Each ownership rule and each
 * capture-before-verify case is driven through the real engine on real files; nothing outside the
 * temporary folder is touched.
 */
final class ModelFileRemovalTest {

  @TempDir Path tmp;

  /** A process death between two checkpoints: an Error is never caught by the engine. */
  static final class SimulatedDeath extends Error {
    private static final long serialVersionUID = 1L;
  }

  /** The settings owner as seen by removal: one in-memory read and the recorded declines. */
  static final class FakeSettings implements ModelFileRemoval.Settings {
    final UiSettings settings = new UiSettings();
    final List<String> declined = new ArrayList<>();
    RuntimeException refuse;

    FakeSettings() {
      settings.setRerankerEnabled(false);
      settings.setChatEnabled(false);
      settings.setPictureReadingEnabled(false);
    }

    @Override
    public ModelFileRemoval.UiSettingsStoreView inspect() {
      return new ModelFileRemoval.UiSettingsStoreView(settings, "witness");
    }

    @Override
    public void decline(ModelFileRemoval.UiSettingsStoreView read, String packageId) {
      if (refuse != null) throw refuse;
      declined.add(packageId);
      List<String> next = new ArrayList<>(settings.getDeclinedAiPackages());
      next.add(packageId);
      settings.setDeclinedAiPackages(next);
    }
  }

  private RemovalModelsFixture fixture;
  private final ModelFileRemovalFence fence = new ModelFileRemovalFence();
  private final FakeSettings settings = new FakeSettings();

  private ModelFileRemoval engine() throws Exception {
    if (fixture == null) fixture = new RemovalModelsFixture(tmp.resolve("home"));
    return new ModelFileRemoval(fixture.home, fixture.models, () -> fixture.registry, () -> null, fence);
  }

  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private DownloadedFiles.Part part(ModelFileRemoval engine, String id) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private DownloadedFiles.Removal remove(ModelFileRemoval engine, String id) {
    String digest = part(engine, id).previewDigest;
    return engine.prepare(id, digest, settings, ELIGIBLE, IDLE).finish();
  }

  private static DownloadedFiles.FileEntry kept(DownloadedFiles.Removal removal, String path) {
    return removal.kept.stream().filter(f -> f.path.equals(path)).findFirst().orElseThrow(
        () -> new AssertionError("not kept: " + path + " in " + removal.kept.stream().map(f -> f.path).toList()));
  }

  @Test
  void removesEveryRetainedRerankerVariantAndItsPartialAndRecordsTheDecline() throws Exception {
    ModelFileRemoval engine = engine();
    var paths = fixture.writeReranker();
    Path partial = fixture.rerankerDir(1).resolve("model_fp16.onnx.partial");
    Files.write(partial, new byte[] {1, 2, 3});

    DownloadedFiles.Part before = part(engine, "reranker");
    assertTrue(before.removable, String.valueOf(before.reason));
    assertEquals(7, before.delete.size(), "both variants, their supporting files and the partial");
    long measured = RemovalModelsFixture.RERANK_FP32.length + RemovalModelsFixture.RERANK_FP16.length
        + 2L * (RemovalModelsFixture.TOKENIZER.length + RemovalModelsFixture.CONFIG.length) + 3;
    assertEquals(measured, before.sizeOnDiskBytes, "the measured size on disk");

    DownloadedFiles.Removal removal = remove(engine, "reranker");

    assertEquals("removed", removal.state, removal.message);
    assertEquals(7, removal.deleted.size());
    assertFalse(Files.exists(paths.get("fp32")));
    assertFalse(Files.exists(paths.get("fp16")));
    assertFalse(Files.exists(partial));
    assertEquals(List.of("reranker"), settings.declined, "recorded as declined so Repair skips it");
    assertTrue(fixture.stagedFiles().isEmpty(), "nothing is left in the removal folder");
    DownloadedFiles.Part after = part(engine, "reranker");
    assertEquals("not-downloaded", after.state);
    assertEquals(0L, after.sizeOnDiskBytes);
    assertEquals("removed", engine.view(settings.settings, ELIGIBLE).removal.state,
        "the finished removal is reported from the journal");
  }

  @Test
  void answersRemovesTheModelAndItsPictureReadingFileTogether() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();

    DownloadedFiles.Removal removal = remove(engine, "chat");

    assertEquals("removed", removal.state);
    assertEquals(List.of("answer.gguf", "mmproj-F16.gguf"),
        removal.deleted.stream().map(f -> f.path).sorted().toList());
    assertFalse(Files.exists(fixture.models.resolve("mmproj-F16.gguf")));
  }

  @Test
  void aCustomFileAtTheCanonicalNameWithADifferentHashIsPutBackUnchanged() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    // Same size as the registry's file, different bytes: only the full hash can tell.
    byte[] custom = RemovalModelsFixture.CHAT.clone();
    custom[0] ^= 1;
    Path model = fixture.models.resolve("answer.gguf");
    Files.write(model, custom);

    DownloadedFiles.Removal removal = remove(engine, "chat");

    assertArrayEquals(custom, Files.readAllBytes(model), "the custom file is back, unchanged");
    assertEquals(ModelFileRemoval.KEPT_NOT_VERIFIED, kept(removal, "answer.gguf").reason);
    assertFalse(Files.exists(fixture.models.resolve("mmproj-F16.gguf")), "the verified file went");
    assertEquals("removed", removal.state, "a file that failed ownership was never JustSearch's");
  }

  @Test
  void aDifferentSizedFileIsKeptWithoutBeingTouched() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Files.writeString(fixture.models.resolve("answer.gguf"), "someone else's model");

    DownloadedFiles.Part preview = part(engine, "chat");

    assertEquals(ModelFileRemoval.KEPT_NOT_VERIFIED, preview.kept.get(0).reason);
    assertEquals(List.of("mmproj-F16.gguf"), preview.delete.stream().map(f -> f.path).toList());
  }

  @Test
  void aLinkedParentFolderKeepsEverythingBehindIt() throws Exception {
    ModelFileRemoval engine = engine();
    Path outside = tmp.resolve("outside");
    Files.createDirectories(outside);
    Path dir = fixture.rerankerDir(0);
    Files.createDirectories(dir.getParent());
    Files.createSymbolicLink(dir, outside);
    Files.write(outside.resolve("model.onnx"), RemovalModelsFixture.RERANK_FP32);

    DownloadedFiles.Part preview = part(engine, "reranker");

    assertTrue(preview.delete.isEmpty());
    assertEquals(ModelFileRemoval.KEPT_LINKED_FOLDER, preview.kept.get(0).reason);
    assertFalse(preview.removable, "nothing it may delete");
    assertTrue(Files.exists(outside.resolve("model.onnx")));
  }

  @Test
  void aFileReplacedByALinkBeforeCaptureIsKeptAndTheTargetIsUntouched() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Path model = fixture.models.resolve("answer.gguf");
    Path elsewhere = tmp.resolve("user-copy.gguf");
    Files.write(elsewhere, RemovalModelsFixture.CHAT);
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("declined")) {
        try {
          Files.delete(model);
          Files.createSymbolicLink(model, elsewhere);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(ModelFileRemoval.KEPT_LINKED_FOLDER, kept(removal, "answer.gguf").reason);
    assertTrue(Files.isSymbolicLink(model), "the link is left where it was");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(elsewhere));
  }

  @Test
  void aHardLinkedFileIsKept() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Path model = fixture.models.resolve("answer.gguf");
    Path other = tmp.resolve("hardlink.gguf");
    Files.createLink(other, model);
    assumeTrue(model.getFileSystem().supportedFileAttributeViews().contains("unix"));

    DownloadedFiles.Removal removal = remove(engine, "chat");

    assertEquals(ModelFileRemoval.KEPT_LINKED, kept(removal, "answer.gguf").reason);
    assertTrue(Files.exists(model));
    assertTrue(Files.exists(other));
  }

  @Test
  void aFileAnotherWantedPartListsIsKeptAndGoesOnlyWhenThatPartIsDeclined() throws Exception {
    // A second part that lists the same picture-reading file beside the answer model.
    fixture = new RemovalModelsFixture(tmp.resolve("home"), json -> json.replace("]}" + "]}",
        "]},{\"id\":\"ner\",\"necessity\":\"improves-results\",\"label\":\"Entity recognition\","
            + "\"targetDir\":\"\",\"tier\":\"retrieval-enrichment\",\"minVramBytes\":0,\"variants\":[],"
            + "\"supportingFiles\":[{\"filename\":\"mmproj-F16.gguf\",\"sha256\":\""
            + RemovalModelsFixture.sha(RemovalModelsFixture.PROJECTOR) + "\",\"sizeBytes\":"
            + RemovalModelsFixture.PROJECTOR.length + ",\"downloadUrl\":\"http://127.0.0.1:9/x\"}]}]}"));
    assertNotNull(fixture.pkg("ner"), "precondition: the fixture registry has the sharing part");
    ModelFileRemoval engine = engine();
    fixture.writeChat();

    DownloadedFiles.Part preview = part(engine, "chat");
    assertEquals("kept - used by Entity recognition",
        preview.kept.stream().filter(f -> f.path.equals("mmproj-F16.gguf")).findFirst().orElseThrow().reason);

    settings.settings.setDeclinedAiPackages(List.of("ner"));
    DownloadedFiles.Part declinedOther = part(engine, "chat");
    assertTrue(declinedOther.delete.stream().anyMatch(f -> f.path.equals("mmproj-F16.gguf")),
        "a declined part does not hold the file");
  }

  @Test
  void aFileOutsideTheModelsFolderIsKept() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"),
        json -> json.replace("\"id\":\"chat\",\"necessity\":\"adds-feature\",\"label\":\"Chat model\",\"targetDir\":\"\"",
                "\"id\":\"chat\",\"necessity\":\"adds-feature\",\"label\":\"Chat model\",\"targetDir\":\"../escape\""));
    ModelFileRemoval engine = engine();
    Path escaped = fixture.home.resolve("escape");
    Files.createDirectories(escaped);
    Files.write(escaped.resolve("answer.gguf"), RemovalModelsFixture.CHAT);

    DownloadedFiles.Part preview = part(engine, "chat");

    assertEquals(ModelFileRemoval.KEPT_LINKED_FOLDER, preview.kept.get(0).reason);
    assertTrue(preview.delete.isEmpty());
    assertTrue(Files.exists(escaped.resolve("answer.gguf")));
  }

  @Test
  void aReplacementBetweenResolveAndCaptureIsKeptAndPutBack() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Path model = fixture.models.resolve("answer.gguf");
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("fenced")) {
        try {
          // A new file with the very same bytes: only its identity differs from what was resolved.
          Path fresh = tmp.resolve("fresh.gguf");
          Files.write(fresh, RemovalModelsFixture.CHAT);
          Files.move(fresh, model, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(ModelFileRemoval.KEPT_CHANGED, kept(removal, "answer.gguf").reason);
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(model), "put back at its place");
    assertTrue(fixture.stagedFiles().isEmpty());
    assertEquals("removed", removal.state, "the projector, which passed, was deleted");
  }

  @Test
  void aFailedVerificationNeverOverwritesAFileThatTookTheCanonicalPlace() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Path model = fixture.models.resolve("answer.gguf");
    byte[] corrupt = RemovalModelsFixture.CHAT.clone();
    corrupt[3] ^= 7;
    Files.write(model, corrupt);
    byte[] newcomer = RemovalModelsFixture.bytes("a file written meanwhile");
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:answer.gguf")) {
        try {
          Files.write(model, newcomer);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    assertArrayEquals(newcomer, Files.readAllBytes(model), "the newcomer is not overwritten");
    DownloadedFiles.FileEntry left = removal.kept.stream()
        .filter(f -> ModelFileRemoval.KEPT_IN_REMOVAL_FOLDER.equals(f.reason)).findFirst().orElseThrow();
    assertTrue(left.path.startsWith(ModelFileRemoval.REMOVAL_FOLDER + "/"), left.path);
    Path staged = fixture.models.resolve(left.path);
    assertArrayEquals(corrupt, Files.readAllBytes(staged), "the captured file is kept, not deleted");
  }

  @Test
  void aCrashAfterCaptureIsReportedAndTheFileIsPutBackOnRestartWithoutAnyDeletion() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (name.equals("captured:answer.gguf")) throw new SimulatedDeath();
    });
    ModelFileRemoval.Run run = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE);
    assertThrows(SimulatedDeath.class, run::finish);
    assertFalse(Files.exists(fixture.models.resolve("answer.gguf")), "precondition: it was captured");

    // A new process over the same folders.
    ModelFileRemoval restarted = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence());
    DownloadedFiles.Removal report = restarted.view(settings.settings, ELIGIBLE).removal;

    assertEquals("interrupted", report.state);
    assertEquals(ModelFileRemoval.KEPT_PUT_BACK, kept(report, "answer.gguf").reason);
    assertArrayEquals(RemovalModelsFixture.CHAT,
        Files.readAllBytes(fixture.models.resolve("answer.gguf")), "restored, not deleted");
    assertTrue(Files.exists(fixture.models.resolve("mmproj-F16.gguf")), "never captured, never deleted");
    assertTrue(report.deleted.isEmpty());
    assertTrue(settings.settings.getDeclinedAiPackages().contains("chat"),
        "recovery never re-enables the part");
  }

  @Test
  void aFutureOrUnreadableRemovalRecordIsReportedAndNothingIsRestoredOrDeleted() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    Path staged = fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER).resolve("left.removing");
    Files.createDirectories(staged.getParent());
    Files.write(staged, RemovalModelsFixture.CHAT);
    Files.writeString(fixture.home.resolve(ModelFileRemoval.JOURNAL_FILENAME),
        "{\"version\":2,\"packageId\":\"chat\",\"state\":\"running\",\"files\":[{\"path\":\"answer.gguf\","
            + "\"staged\":\".removing/left.removing\",\"state\":\"captured\"}]}");

    DownloadedFiles.Removal report = engine.view(settings.settings, ELIGIBLE).removal;

    assertEquals("interrupted", report.state);
    assertEquals("kept in the removal folder", kept(report, ".removing/left.removing").reason);
    assertTrue(Files.exists(staged), "a record this version cannot read never drives a restore or deletion");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
  }

  @Test
  void filesInTheRemovalFolderWithoutAnyRecordAreReported() throws Exception {
    ModelFileRemoval engine = engine();
    Path staged = fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER).resolve("orphan.removing");
    Files.createDirectories(staged.getParent());
    Files.writeString(staged, "left behind");

    DownloadedFiles.Removal report = engine.view(settings.settings, ELIGIBLE).removal;

    assertEquals("kept in the removal folder", kept(report, ".removing/orphan.removing").reason);
    assertTrue(Files.exists(staged));
  }

  @Test
  void aRenameFailureKeepsTheFileAndReportsNotRemoved() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    // The removal folder exists; only the rename out of the models folder can fail.
    Files.createDirectories(fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER));
    Files.setPosixFilePermissions(fixture.models, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(fixture.models), "running as a user that ignores permissions");
      DownloadedFiles.Removal removal = remove(engine, "chat");
      assertEquals("not-removed", removal.state);
      assertEquals(ModelFileRemoval.KEPT_IN_USE, kept(removal, "answer.gguf").reason);
      assertTrue(Files.exists(fixture.models.resolve("answer.gguf")));
      assertTrue(Files.exists(fixture.models.resolve("mmproj-F16.gguf")));
    } finally {
      Files.setPosixFilePermissions(fixture.models, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void refusalsDeleteNothingAndRecordNothing() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;

    var switchedOn = assertThrows(ModelFileRemoval.Refusal.class, () -> engine.prepare("chat", digest,
        settings, (id, s) -> Optional.of("Switch answers off first"), IDLE));
    assertEquals("Switch answers off first", switchedOn.getMessage());
    var inUse = assertThrows(ModelFileRemoval.Refusal.class, () -> engine.prepare("chat", digest,
        settings, ELIGIBLE, id -> Optional.of("The answer model is still loaded - try again in a moment")));
    assertEquals("The answer model is still loaded - try again in a moment", inUse.getMessage());
    var stale = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", "0".repeat(64), settings, ELIGIBLE, IDLE));
    assertEquals("The files changed since this list was shown - review it again", stale.getMessage());
    var notYet = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("splade", digest, settings, ELIGIBLE, IDLE));
    assertEquals("Removal not available yet", notYet.getMessage());

    assertTrue(Files.exists(fixture.models.resolve("answer.gguf")));
    assertTrue(Files.exists(fixture.models.resolve("mmproj-F16.gguf")));
    assertTrue(settings.declined.isEmpty());
    assertNull(engine.view(settings.settings, ELIGIBLE).removal, "no removal was recorded");
    assertTrue(fence.removingPart().isEmpty(), "every refusal released the lock");
  }

  @Test
  void aDeclineTheSettingsOwnerRefusesDeletesNothing() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    settings.refuse = new IllegalStateException("Settings changed since this was read");

    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", digest, settings, ELIGIBLE, IDLE));

    assertTrue(refused.getMessage().startsWith("Nothing was removed"), refused.getMessage());
    assertTrue(Files.exists(fixture.models.resolve("answer.gguf")));
    assertEquals("refused", engine.view(settings.settings, ELIGIBLE).removal.state);
    assertTrue(fence.removingPart().isEmpty());
  }

  @Test
  void eligibilityIsReadAgainUnderTheLock() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    AtomicInteger reads = new AtomicInteger();
    List<Boolean> lockedAtRead = new ArrayList<>();
    ModelFileRemoval.Eligibility observing = (id, s) -> {
      reads.incrementAndGet();
      lockedAtRead.add(fence.removingPart().isPresent());
      return Optional.empty();
    };
    engine.prepare("chat", digest, settings, observing, IDLE).finish();

    assertEquals(List.of(true), lockedAtRead, "the final check runs with the lock held");
    assertEquals(1, reads.get());
  }

  @Test
  void partsThisStepCannotRemoveAreShownWithTheirReasonAndMeasuredSize() throws Exception {
    ModelFileRemoval engine = engine();
    Path embed = fixture.models.resolve(io.justsearch.configuration.model.InstallPlanner
        .effectiveTargetDir(fixture.pkg("embedding"), fixture.pkg("embedding").variants().get(0)));
    Files.createDirectories(embed);
    Files.write(embed.resolve("model.onnx"), RemovalModelsFixture.EMBED);

    DownloadedFiles view = engine.view(settings.settings, ELIGIBLE);

    DownloadedFiles.Part meaning = view.parts.stream().filter(p -> p.id.equals("embedding")).findFirst().orElseThrow();
    assertFalse(meaning.removable);
    assertEquals("Needed for search by meaning", meaning.reason);
    assertEquals((long) RemovalModelsFixture.EMBED.length, meaning.sizeOnDiskBytes);
    DownloadedFiles.Part splade = view.parts.stream().filter(p -> p.id.equals("splade")).findFirst().orElseThrow();
    assertEquals("Removal not available yet", splade.reason);
    assertFalse(splade.removable);
  }
}
