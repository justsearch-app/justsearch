/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 2 of an install run: getting verified bytes to their final home, and recording what landed.
 *
 * <p>Two entry points, at two different rhythms, because that is what the run actually does:
 *
 * <ul>
 *   <li>{@link #place} runs PER ITEM, from inside acquisition. A file is promoted the moment it
 *       verifies, not after the whole set — an install interrupted halfway leaves the files it
 *       finished at their real paths, which is what makes the planner's already-installed check
 *       skip them on the next run.
 *   <li>{@link #writeContract} runs ONCE, after the set. The bill of materials describes a run, not
 *       a file.
 * </ul>
 */
final class PlacementStage {

  private static final Logger log = LoggerFactory.getLogger(PlacementStage.class);

  private final Path modelsDir;

  PlacementStage(Path modelsDir) {
    this.modelsDir = modelsDir;
  }

  /**
   * Promotes one verified {@code .partial} to its target path and expands it when the plan says the
   * package ships an archive.
   *
   * @return the user-facing failure message, or {@code null} when the item is in place. Failures are
   *     returned rather than thrown because one file failing must not end the set — the caller marks
   *     the package failed and moves to the next item.
   */
  String place(InstallPlan.PlannedDownload dl) {
    if (dl == null || dl.targetPath() == null || dl.targetPath().isBlank()) {
      return "Failed to finalize: missing target path";
    }
    if (dl.sha256() == null || dl.sha256().isBlank()) {
      return "Failed to finalize: missing expected SHA-256";
    }
    Path targetFile = modelsDir.resolve(dl.targetPath()).normalize();
    Path partialFile = InstallPlanner.partialPathFor(targetFile);
    try {
      if (Files.exists(targetFile, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(targetFile)
            || !Files.isRegularFile(targetFile, LinkOption.NOFOLLOW_LINKS)) {
          return "Failed to finalize: refusing to replace non-regular target " + targetFile;
        }
        // A serving generation may still own this path. Reuse only a target whose complete
        // identity matches the verified download; a same-size byte change is not safe to replace.
        boolean replacedCandidate = false;
        try {
          DownloadExecutor.verify(targetFile, dl.sizeBytes(), dl.sha256());
        } catch (Exception mismatch) {
          if (!isRepairableModelTarget(targetFile, dl)) {
            return "Failed to finalize: refusing to replace existing target with different bytes";
          }
          // A corrupt selected ONNX file can be repaired in its candidate-owned directory.
          // Supporting files may belong to a serving generation even in that directory; changing
          // their bytes in place would change that generation's model context on its next boot.
          if (Files.isSymbolicLink(partialFile)) {
            return "Failed to finalize: refusing symbolic-link staging file " + partialFile;
          }
          DownloadExecutor.verify(partialFile, dl.sizeBytes(), dl.sha256());
          replaceCandidateTarget(partialFile, targetFile);
          replacedCandidate = true;
        }
        if (!replacedCandidate) {
          Files.deleteIfExists(partialFile);
        }
      } else {
        if (Files.isSymbolicLink(partialFile)) {
          return "Failed to finalize: refusing symbolic-link staging file " + partialFile;
        }
        // The fetch path verifies the partial before calling us. Verify again at this ownership
        // boundary, then move without REPLACE_EXISTING so a concurrent target can never be lost.
        DownloadExecutor.verify(partialFile, dl.sizeBytes(), dl.sha256());
        moveIntoEmptyTarget(partialFile, targetFile);
      }
    } catch (Exception e) {
      return "Failed to finalize: " + e.getMessage();
    }

    // Tempdoc 374 alpha.15 fix B: archive extraction. The cuda-runtime package ships its DLLs in a
    // single zip (too large for the NSIS installer payload). After download + SHA verification the
    // zip is expanded into the same directory; the archive itself stays on disk so the planner's
    // isAlreadyInstalled check skips re-download next time.
    if (dl.extract()) {
      try {
        AiInstallService.extractZipInPlace(targetFile, targetFile.getParent());
      } catch (IOException e) {
        return "Failed to extract " + targetFile.getFileName() + ": " + e.getMessage();
      }
    }
    return null;
  }

  private static void moveIntoEmptyTarget(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to);
    }
  }

  private static void replaceCandidateTarget(Path from, Path to) throws IOException {
    try {
      Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private boolean isRepairableModelTarget(Path target, InstallPlan.PlannedDownload download) {
    return isCandidateOwnedTarget(target)
        && download.isModelVariant()
        && target.getFileName().toString().endsWith(".onnx");
  }

  /** The planner's candidate shape: models/.../candidates/<package identity SHA-256>/<file>. */
  private boolean isCandidateOwnedTarget(Path target) {
    Path root = modelsDir.toAbsolutePath().normalize();
    Path normalized = target.toAbsolutePath().normalize();
    if (!normalized.startsWith(root) || Files.isSymbolicLink(root)) {
      return false;
    }
    Path relative = root.relativize(normalized);
    Path cursor = root;
    for (Path component : relative) {
      cursor = cursor.resolve(component);
      if (Files.isSymbolicLink(cursor)) {
        return false;
      }
    }
    Path candidateDir = normalized.getParent();
    Path candidatesDir = candidateDir == null ? null : candidateDir.getParent();
    if (candidateDir == null
        || candidatesDir == null
        || !"candidates".equals(candidatesDir.getFileName().toString())) {
      return false;
    }
    String identity = candidateDir.getFileName().toString();
    if (identity.length() != 64) {
      return false;
    }
    for (int i = 0; i < identity.length(); i++) {
      char c = identity.charAt(i);
      if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
        return false;
      }
    }
    return true;
  }

  /**
   * Writes the run's install contract.
   *
   * <p>Deliberately NOT best-effort: a failure here propagates. The contract is the runtime's only
   * bill of materials, and a run that reported completion without one leaves the next boot resolving
   * models by guesswork.
   */
  void writeContract(InstallContract contract, Path homeDir) {
    InstallContractIO.write(contract, homeDir);
    log.info("Install contract written to {}", homeDir.resolve(InstallContract.CONTRACT_FILENAME));
  }
}
