/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.justsearch.app.api.DownloadedFiles;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OperationLease;
import io.justsearch.app.api.OperationLeaseSnapshot;
import io.justsearch.app.services.lease.OperationLeaseServiceImpl;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Repair round 2 of "Remove files" (design D4), on real files in a temporary models folder only:
 * the removal folder is private to the Engine's user and is checked again before every capture and
 * deletion, so a staged file or the folder itself replaced after verification is never deleted; the
 * removal record is replaced only atomically; a download cancelled before its first file completed
 * is listed and removable; a removal holds upgrade admission.
 */
@Tag("cross-platform")
final class ModelFileRemovalRepair2Test {
  /** ModelFileRemoval.FOLDER_NOT_PRIVATE, as the page shows it. */
  private static final String FOLDER_NOT_PRIVATE =
      "The removal folder can be changed by another account, so nothing can be removed";
  /** ModelFileRemoval.KEPT_CHANGED. */
  private static final String KEPT_CHANGED = "kept - it changed while being removed";
  /** ModelFileRemoval.RECORD_NOT_ATOMIC. */
  private static final String RECORD_NOT_ATOMIC =
      "Nothing was removed: this disk can't save the removal record safely";
  /** ModelFileRemoval.UPDATE_PREPARING. */
  private static final String UPDATE_PREPARING = "An update is being prepared - try again after it";

  @TempDir Path tmp;

  private final ModelFileRemovalTest.FakeSettings settings = new ModelFileRemovalTest.FakeSettings();
  private static final ModelFileRemoval.Eligibility ELIGIBLE = (id, s) -> Optional.empty();
  private static final ModelFileRemoval.UsageProbe IDLE = id -> Optional.empty();

  private RemovalModelsFixture fixture;

  private ModelFileRemoval engine() throws Exception {
    if (fixture == null) fixture = new RemovalModelsFixture(tmp.resolve("home"));
    return new ModelFileRemoval(fixture.home, fixture.models, () -> fixture.registry, () -> null,
        new ModelFileRemovalFence());
  }

  private DownloadedFiles.Part part(ModelFileRemoval engine, String id) {
    return engine.view(settings.settings, ELIGIBLE).parts.stream()
        .filter(p -> p.id.equals(id)).findFirst().orElseThrow();
  }

  private Path removalFolder() {
    return fixture.models.resolve(ModelFileRemoval.REMOVAL_FOLDER);
  }

  private Path onlyStaged() throws Exception {
    return onlyStaged("precondition: one captured file");
  }

  private Path onlyStaged(String why) throws Exception {
    List<Path> staged = fixture.stagedFiles();
    assertEquals(1, staged.size(), why + " " + staged);
    return staged.get(0);
  }

  // --- 1. The removal folder is private -----------------------------------------------------

  @Test
  void aSharedWritableRemovalFolderRefusesTheWholeRemovalAndDeletesNothing() throws Exception {
    ModelFileRemoval engine = engine();
    assumeTrue(RemovalLinks.posix(fixture.models), "shared write access is set through POSIX permissions");
    fixture.writeChat();
    Files.createDirectory(removalFolder());
    Files.setPosixFilePermissions(removalFolder(), PosixFilePermissions.fromString("rwxrwxrwx"));
    String digest = part(engine, "chat").previewDigest;

    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish());

    assertEquals(FOLDER_NOT_PRIVATE, refused.getMessage());
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
    assertArrayEquals(RemovalModelsFixture.PROJECTOR,
        Files.readAllBytes(fixture.models.resolve("mmproj-F16.gguf")));
    assertTrue(fixture.stagedFiles().isEmpty(), "nothing was captured");
    assertTrue(settings.declined.isEmpty(), "no decline was recorded");
  }

  @Test
  void theRemovalFolderIsCreatedOwnerOnly() throws Exception {
    ModelFileRemoval engine = engine();
    assumeTrue(RemovalLinks.posix(fixture.models), "owner-only is read through POSIX permissions");
    fixture.writeChat();

    DownloadedFiles.Removal removal =
        engine.prepare("chat", part(engine, "chat").previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertEquals("removed", removal.state, removal.message);
    assertEquals(PosixFilePermissions.fromString("rwx------"),
        Files.getPosixFilePermissions(removalFolder(), LinkOption.NOFOLLOW_LINKS));
  }

  @Test
  void aStagedFileReplacedAfterVerificationIsNeitherDeletedNorPutBack() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    byte[] decoy = RemovalModelsFixture.bytes("someone else's bytes under the captured name");
    Path movedAway = tmp.resolve("moved-away.gguf");
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("verified:answer.gguf")) return;
      try {
        Path staged = onlyStaged();
        Files.move(staged, movedAway);
        Files.write(staged, decoy);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    Path staged = onlyStaged("the replacement under the captured name was not deleted");
    assertArrayEquals(decoy, Files.readAllBytes(staged), "the replacement was not deleted");
    assertFalse(Files.exists(fixture.models.resolve("answer.gguf"), LinkOption.NOFOLLOW_LINKS),
        "nor published at the canonical place");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(movedAway));
    assertTrue(removal.kept.stream().anyMatch(f -> KEPT_CHANGED.equals(f.reason)
        && f.path.equals(ModelFileRemoval.REMOVAL_FOLDER + "/" + staged.getFileName())),
        "reported where it is: " + removal.kept.stream().map(f -> f.path + " " + f.reason).toList());
  }

  @Test
  void aRemovalFolderReplacedAfterVerificationStopsTheRemovalAndTouchesNothingInIt()
      throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    byte[] decoy = RemovalModelsFixture.bytes("a file in the folder swapped in");
    Path movedAside = tmp.resolve("moved-aside");
    List<Path> decoys = new ArrayList<>();
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("verified:answer.gguf")) return;
      try {
        String stagedName = onlyStaged().getFileName().toString();
        Files.move(removalFolder(), movedAside);
        // The same name, a folder that would pass every other check: only its identity differs.
        if (RemovalLinks.posix(fixture.models)) {
          Files.createDirectory(removalFolder(),
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } else {
          Files.createDirectory(removalFolder());
        }
        decoys.add(Files.write(removalFolder().resolve(stagedName), decoy));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(1, decoys.size(), "precondition: the folder was swapped");
    assertTrue(Files.exists(decoys.get(0)), "the file in the swapped-in folder is still there");
    assertArrayEquals(decoy, Files.readAllBytes(decoys.get(0)), "nothing in the swapped-in folder was deleted");
    assertNotEquals("removed", removal.state, removal.message);
    assertTrue(removal.message.contains(FOLDER_NOT_PRIVATE), removal.message);
    assertArrayEquals(RemovalModelsFixture.PROJECTOR,
        Files.readAllBytes(fixture.models.resolve("mmproj-F16.gguf")), "the removal stopped");
    assertFalse(Files.exists(fixture.models.resolve("answer.gguf"), LinkOption.NOFOLLOW_LINKS),
        "nothing from the swapped-in folder was put back");
  }

  @Test
  void aRemovalFolderOpenedToOtherAccountsBeforeDeletionKeepsTheCapturedFile() throws Exception {
    ModelFileRemoval engine = engine();
    assumeTrue(RemovalLinks.posix(fixture.models), "shared write access is set through POSIX permissions");
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    engine.setCheckpointHookForTest(name -> {
      if (!name.equals("verified:answer.gguf")) return;
      try {
        Files.setPosixFilePermissions(removalFolder(), PosixFilePermissions.fromString("rwxrwxrwx"));
      } catch (Exception e) {
        throw new AssertionError(e);
      }
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    Path staged = onlyStaged("the captured file was not deleted through a folder others can change");
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(staged),
        "the captured file was neither deleted nor moved through a folder others can change");
    assertNotEquals("removed", removal.state, removal.message);
    assertTrue(removal.deleted.isEmpty());
  }

  // --- 5. The removal record is replaced only atomically -------------------------------------

  @Test
  void aDiskWithoutAtomicReplacementRefusesBeforeAnythingIsMoved() throws Exception {
    ModelFileRemoval engine = engine();
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    engine.setRecordReplaceForTest((target, content) -> {
      throw new AtomicMoveNotSupportedException(target.toString(), target.toString(), "no atomic rename");
    });

    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish());

    assertEquals(RECORD_NOT_ATOMIC, refused.getMessage());
    assertArrayEquals(RemovalModelsFixture.CHAT, Files.readAllBytes(fixture.models.resolve("answer.gguf")));
    assertTrue(Files.exists(fixture.models.resolve("mmproj-F16.gguf")));
    assertTrue(fixture.stagedFiles().isEmpty(), "nothing was captured");
    assertTrue(settings.declined.isEmpty(), "no decline was recorded");
    assertFalse(Files.exists(fixture.home.resolve(ModelFileRemoval.JOURNAL_FILENAME)),
        "no record that could be torn was written");
  }

  // --- 6. A download cancelled before its first file completed --------------------------------

  @Test
  void aDownloadCancelledBeforeItsFirstFileCompletedIsListedAndItsStagingFilesCanBeRemoved()
      throws Exception {
    ModelFileRemoval engine = engine();
    // What a cancelled first download leaves: the installer's partial file and its resume record.
    Path partial = fixture.models.resolve("answer.gguf" + io.justsearch.configuration.model.InstallPlanner.PARTIAL_SUFFIX);
    Files.write(partial, new byte[] {1, 2, 3, 4});
    Path resume = DownloadResume.sidecarFor(partial);
    Files.writeString(resume, "{}");

    DownloadedFiles.Part before = part(engine, "chat");

    assertEquals("incomplete", before.state, "listed, not hidden as not downloaded");
    assertTrue(before.removable, String.valueOf(before.reason));
    assertEquals(List.of("answer.gguf.partial", "answer.gguf.partial.resume"),
        before.delete.stream().map(f -> f.path).sorted().toList());
    assertEquals(4L + 2L, before.sizeOnDiskBytes);

    DownloadedFiles.Removal removal = engine.prepare("chat", before.previewDigest, settings, ELIGIBLE, IDLE).finish();

    assertEquals("removed", removal.state, removal.message);
    assertFalse(Files.exists(partial));
    assertFalse(Files.exists(resume));
    assertEquals("not-downloaded", part(engine, "chat").state);
  }

  // --- 7. Upgrade admission -------------------------------------------------------------------

  @Test
  void anUpdateBeingPreparedRefusesTheRemovalAndDeletesNothing() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var leases = OperationLeaseServiceImpl.processLocal();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence(), () -> leases);
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    OperationLeaseSnapshot upgrade = leases.freezeAdmission("application upgrade");

    var refused = assertThrows(ModelFileRemoval.Refusal.class,
        () -> engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish());

    assertEquals(UPDATE_PREPARING, refused.getMessage());
    assertTrue(Files.exists(fixture.models.resolve("answer.gguf")));
    assertTrue(settings.declined.isEmpty());
    leases.releaseAdmission(upgrade.preparationId());
  }

  @Test
  void anUpdatePreparedDuringTheRemovalWaitsForItAsAMustCompleteOperation() throws Exception {
    fixture = new RemovalModelsFixture(tmp.resolve("home"));
    var leases = OperationLeaseServiceImpl.processLocal();
    ModelFileRemoval engine = new ModelFileRemoval(fixture.home, fixture.models,
        () -> fixture.registry, () -> null, new ModelFileRemovalFence(), () -> leases);
    fixture.writeChat();
    String digest = part(engine, "chat").previewDigest;
    List<OperationLeaseSnapshot> seenByUpgrade = new ArrayList<>();
    engine.setCheckpointHookForTest(name -> {
      // The upgrade freezes admission while the first file is being captured.
      if (name.equals("capturing:answer.gguf")) seenByUpgrade.add(leases.freezeAdmission("application upgrade"));
    });

    DownloadedFiles.Removal removal = engine.prepare("chat", digest, settings, ELIGIBLE, IDLE).finish();

    assertEquals(1, seenByUpgrade.size(), "precondition: the upgrade froze admission mid-removal");
    List<OperationLease> blockers = seenByUpgrade.get(0).activeLeases();
    assertEquals(List.of("ai.model-file-removal"), blockers.stream().map(OperationLease::opClass).toList(),
        "the upgrade sees the running removal");
    assertEquals(OpCriticality.MUST_COMPLETE, blockers.get(0).criticality(), "and must wait for it");
    assertEquals("removed", removal.state, "a removal that started finishes");
    assertTrue(leases.snapshot().activeLeases().isEmpty(), "released when it ends");
    leases.releaseAdmission(seenByUpgrade.get(0).preparationId());
  }
}
