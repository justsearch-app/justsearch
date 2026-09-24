/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlacementStageTest {

  private static final String TARGET_PATH = "onnx/serving/model.onnx";
  private static final String CANDIDATE_TARGET_PATH =
      "onnx/embed/candidates/0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef/model.onnx";

  @TempDir Path tempDir;

  @Test
  void placeReusesExistingExactTargetAndDiscardsVerifiedPartial() throws Exception {
    Path target = tempDir.resolve(TARGET_PATH);
    Files.createDirectories(target.getParent());
    Files.writeString(target, "candidate", StandardCharsets.UTF_8);
    Path partial = InstallPlanner.partialPathFor(target);
    Files.writeString(partial, "candidate", StandardCharsets.UTF_8);

    String sha = DownloadExecutor.sha256(target);
    String failure = new PlacementStage(tempDir).place(download(sha, 9));

    assertNull(failure);
    assertEquals("candidate", Files.readString(target));
    assertFalse(Files.exists(partial));
  }

  @Test
  void placeRefusesSameSizeDifferentExistingTarget() throws Exception {
    Path target = tempDir.resolve(TARGET_PATH);
    Files.createDirectories(target.getParent());
    Files.writeString(target, "old-bytes", StandardCharsets.UTF_8);
    Path partial = InstallPlanner.partialPathFor(target);
    Files.writeString(partial, "new-bytes", StandardCharsets.UTF_8);

    String sha = DownloadExecutor.sha256(partial);
    String failure = new PlacementStage(tempDir).place(download(sha, 9));

    assertTrue(failure.contains("different bytes"));
    assertEquals("old-bytes", Files.readString(target));
    assertTrue(Files.exists(partial), "the refused candidate remains available for diagnosis/retry");
  }

  @Test
  void placeMovesVerifiedPartialIntoEmptyTargetWithoutReplacement() throws Exception {
    Path target = tempDir.resolve(TARGET_PATH);
    Files.createDirectories(target.getParent());
    Path partial = InstallPlanner.partialPathFor(target);
    Files.writeString(partial, "candidate", StandardCharsets.UTF_8);

    String sha = DownloadExecutor.sha256(partial);
    String failure = new PlacementStage(tempDir).place(download(sha, 9));

    assertNull(failure);
    assertEquals("candidate", Files.readString(target));
    assertFalse(Files.exists(partial));
  }

  @Test
  void placeReplacesCorruptCandidateWithoutReplacingServingTarget() throws Exception {
    Path downloaded = tempDir.resolve("downloaded-model.onnx");
    Files.writeString(downloaded, "candidate", StandardCharsets.UTF_8);
    String sha = DownloadExecutor.sha256(downloaded);
    String targetPath = "onnx/embed/candidates/" + sha + "/model.onnx";
    Path target = tempDir.resolve(targetPath);
    Files.createDirectories(target.getParent());
    Files.writeString(target, "corrupt", StandardCharsets.UTF_8);
    Path partial = InstallPlanner.partialPathFor(target);
    Files.copy(downloaded, partial);

    String failure = new PlacementStage(tempDir).place(new InstallPlan.PlannedDownload(
        "embedding", "https://example.invalid/model.onnx", targetPath, sha, 9, true));

    assertNull(failure);
    assertEquals("candidate", Files.readString(target));
    assertFalse(Files.exists(partial));
  }

  @Test
  void placeRefusesDifferentSupportingFileInRetainedCandidateDirectory() throws Exception {
    Path target = tempDir.resolve(CANDIDATE_TARGET_PATH).getParent().resolve("tokenizer.json");
    Files.createDirectories(target.getParent());
    Files.writeString(target, "old-tokenizer", StandardCharsets.UTF_8);
    Path partial = InstallPlanner.partialPathFor(target);
    Files.writeString(partial, "new-tokenizer", StandardCharsets.UTF_8);
    String sha = DownloadExecutor.sha256(partial);
    String relative = tempDir.relativize(target).toString().replace('\\', '/');

    String failure = new PlacementStage(tempDir).place(new InstallPlan.PlannedDownload(
        "embedding", "https://example.invalid/tokenizer.json", relative, sha,
        Files.size(partial), false));

    assertTrue(failure.contains("different bytes"));
    assertEquals("old-tokenizer", Files.readString(target));
    assertTrue(Files.exists(partial));
  }

  private static InstallPlan.PlannedDownload download(String sha, long size) {
    return new InstallPlan.PlannedDownload(
        "embedding", "https://example.invalid/model.onnx",
        TARGET_PATH, sha, size, true);
  }

}
