/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.ArchiveExtractionState;
import io.justsearch.configuration.model.InstallPlanner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stage 1 of an install run: acquire every planned file.
 *
 * <p>This is the adapter between the install plan and {@link AcquisitionScheduler} — it turns
 * planned downloads into scheduler items and binds the scheduler's four seams to their production
 * implementations ({@link ResumableFetch} for transport, {@link PlacementStage} for promotion,
 * {@link InstallAttemptMemory} for cross-run history, and the caller's listener for projection). The
 * scheduler owns the set; this owns what one item's fetch is made of.
 */
final class AcquisitionStage {

  private static final Logger log = LoggerFactory.getLogger(AcquisitionStage.class);

  private final Path modelsDir;
  private final ResumableFetch.Transfer transport;
  private final TransportRetryPolicy retryPolicy;
  private final InstallAttemptMemory attempts;
  private final BooleanSupplier cancelRequested;
  private final LongSupplier nanoClock;
  private final PlacementStage placement;
  private final AcquisitionScheduler.Listener listener;
  private final AcquisitionScheduler.PauseGate pauseGate;

  AcquisitionStage(
      Path modelsDir,
      ResumableFetch.Transfer transport,
      TransportRetryPolicy retryPolicy,
      InstallAttemptMemory attempts,
      BooleanSupplier cancelRequested,
      LongSupplier nanoClock,
      PlacementStage placement,
      AcquisitionScheduler.Listener listener,
      AcquisitionScheduler.PauseGate pauseGate) {
    this.modelsDir = modelsDir;
    this.transport = transport;
    this.retryPolicy = retryPolicy;
    this.attempts = attempts;
    this.cancelRequested = cancelRequested;
    this.nanoClock = nanoClock;
    this.placement = placement;
    this.listener = listener;
    this.pauseGate =
        pauseGate == null ? AcquisitionScheduler.PauseGate.open() : pauseGate;
  }

  /**
   * The scheduler for the given downloads, in plan order, built but NOT started.
   *
   * <p>Takes the download list rather than the whole {@link InstallPlan} because a staged run gives
   * it one stage's SLICE of the plan (tempdoc 840 Phase 3); nothing else on the plan was ever read
   * here.
   *
   * <p>Hands back the scheduler rather than the summary of having run it so the caller can hold the
   * live object while it works: the rate/ETA estimate has to be DERIVED WHEN READ ({@link
   * AcquisitionScheduler#estimate()} tests the stall window against the clock at call time), and a
   * caller that only ever sees the finished summary has nothing left to ask.
   */
  AcquisitionScheduler scheduler(List<InstallPlan.PlannedDownload> downloads) {
    Map<String, InstallPlan.PlannedDownload> byTargetPath = new HashMap<>();
    List<AcquisitionScheduler.Item> items = new ArrayList<>();
    for (InstallPlan.PlannedDownload dl : downloads) {
      byTargetPath.putIfAbsent(dl.targetPath(), dl);
      items.add(
          new AcquisitionScheduler.Item(dl.targetPath(), dl.packageId(), dl.extractionOnly() ? 0L : dl.sizeBytes()));
    }
    return new AcquisitionScheduler(
        items,
        (item, startTier, progress) -> fetchOne(byTargetPath.get(item.id()), item, startTier, progress),
        item -> placement.place(byTargetPath.get(item.id())),
        new MemoryLedger(attempts, id -> byTargetPath.get(id).url()),
        listener,
        cancelRequested,
        nanoClock,
        pauseGate);
  }

  /**
   * One item's transport: prepare its directory, then run the whole decide-transfer-verify cycle at
   * the tier this file's history earned.
   *
   * <p>A directory-preparation failure is returned as a non-transport {@link ResumableFetch.Outcome}
   * rather than thrown, so the scheduler treats it exactly as it treated the loop's {@code continue}:
   * the package fails with that message, no transport history is recorded (the failure is local, and
   * escalating the transport would not fix a directory), and the set carries on.
   */
  private ResumableFetch.Outcome fetchOne(
      InstallPlan.PlannedDownload dl,
      AcquisitionScheduler.Item item,
      int startTier,
      DownloadExecutor.ProgressCallback progress) {
    Path targetFile = modelsDir.resolve(dl.targetPath());
    Path partialFile = InstallPlanner.partialPathFor(targetFile);
    try {
      if (dl.extract()) {
        ArchiveExtractionState.requireSafePath(targetFile);
        ArchiveExtractionState.requireSafePath(partialFile);
      }
      Files.createDirectories(targetFile.getParent());
      if (dl.extract()) {
        ArchiveExtractionState.requireSafePath(targetFile);
        ArchiveExtractionState.requireSafePath(partialFile);
      }
    } catch (IOException e) {
      return new ResumableFetch.Outcome(
          false, false, "Failed to prepare download directory: " + e.getMessage(), null, 0, null);
    }

    if (dl.extractionOnly()) {
      listener.onItemVerifying(item);
      try {
        DownloadExecutor.verify(targetFile, dl.sizeBytes(), dl.sha256());
        return new ResumableFetch.Outcome(true, false, null, null, 0, null);
      } catch (Exception mismatch) {
        // Status is deliberately cheap; only an install run hashes a kept archive. A corrupt
        // ZIP goes through normal verified acquisition instead of reaching the extractor.
        log.info("Kept archive requires re-download: {}", targetFile);
      }
    }

    // Tempdoc 824 §3.4: pass n meets tier n. Decided from this FILE's history across runs, which
    // only the memory knows. §3.1's ladder retries WITHIN a pass from this rung; the memory is what
    // makes the NEXT pass start somewhere else.
    final TransportRetryPolicy filePolicy = retryPolicy.withStartTier(startTier);
    if (startTier > 0) {
      log.info(
          "Repair escalation for {}: starting at transport tier {} ({} earlier pass(es) failed it)",
          dl.targetPath(),
          startTier,
          attempts.get(dl.targetPath()).failedPasses());
    }

    // A cancelled multi-GB install used to delete its .partial here and start over from zero.
    // ResumableFetch decides instead whether the bytes on disk provably belong to THIS download
    // (sidecar identity) and resumes them — always followed by the same SHA-256 verification a
    // fresh download gets, with a discard-and-restart-once on mismatch.
    return ResumableFetch.fetch(
        new ResumableFetch.Request(
            partialFile, dl.url(), dl.sizeBytes(), dl.sha256(), dl.targetPath()),
        transport,
        progress,
        cancelRequested,
        new ResumableFetch.Hooks(
            // Tempdoc 374 sandbox round 4 issue G: BITS leaves BIT*.tmp scratch files when its
            // download fails; they would otherwise accumulate across retries. Only on a fresh start
            // — a suspended BITS job we are about to resume still owns its scratch file.
            () -> cleanupBitsTmpFiles(targetFile.getParent()),
            () -> listener.onItemVerifying(item),
            // A spaced retry can take ~40 s per file; without the attempt counter the UI would look
            // frozen exactly when it is doing the thing that saves the install.
            attempt -> listener.onAttempt(item, attempt, filePolicy.maxAttempts())),
        filePolicy);
  }

  /**
   * {@link InstallAttemptMemory} seen through the scheduler's ledger seam.
   *
   * @param urlByItemId the memory records the source url alongside a failure as user-facing
   *     evidence, and the outcome does not carry it — it comes from the plan, which only this stage
   *     has
   */
  private record MemoryLedger(
      InstallAttemptMemory memory, Function<String, String> urlByItemId)
      implements AcquisitionScheduler.AttemptLedger {

    @Override
    public int startTierFor(String itemId) {
      return memory.startTierFor(itemId);
    }

    @Override
    public boolean isTerminal(String itemId) {
      return memory.isTerminal(itemId);
    }

    @Override
    public int attemptCount(String itemId) {
      InstallAttemptMemory.Attempt a = memory.get(itemId);
      return a == null ? 0 : a.attempts();
    }

    @Override
    public void recordTransportFailure(
        String itemId, ResumableFetch.Outcome outcome, int startTier) {
      memory.recordTransportFailure(
          itemId,
          urlByItemId.apply(itemId),
          outcome.transferAttempts(),
          outcome.error(),
          startTier);
    }

    @Override
    public void recordSuccess(String itemId) {
      memory.recordSuccess(itemId);
    }
  }

  /**
   * Removes orphaned {@code *.tmp} files in a download target directory before starting a download.
   * Catches BITS scratch files (named like {@code BIT411F.tmp}) that BITS leaves behind when its job
   * fails or is cancelled. Tempdoc 374 sandbox round 4 issue G.
   */
  private static void cleanupBitsTmpFiles(Path dir) {
    if (dir == null || !Files.isDirectory(dir)) return;
    try (var stream = Files.list(dir)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".tmp"))
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                  log.debug("Removed orphaned BITS tmp file: {}", p);
                } catch (IOException ignored) {
                  // best-effort
                }
              });
    } catch (IOException ignored) {
      // best-effort; the failure won't block the download itself
    }
  }
}
