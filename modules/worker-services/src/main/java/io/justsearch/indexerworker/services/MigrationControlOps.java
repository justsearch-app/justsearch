/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.indexerworker.index.IndexGenerationManager;
import io.justsearch.ipc.IndexGcRequest;
import io.justsearch.ipc.IndexGcResponse;
import io.justsearch.ipc.MigrationCutoverRequest;
import io.justsearch.ipc.MigrationCutoverResponse;
import io.justsearch.ipc.MigrationPauseRequest;
import io.justsearch.ipc.MigrationPauseResponse;
import io.justsearch.ipc.MigrationResumeRequest;
import io.justsearch.ipc.MigrationResumeResponse;
import io.justsearch.ipc.MigrationRollbackRequest;
import io.justsearch.ipc.MigrationRollbackResponse;
import io.justsearch.ipc.MigrationStartRequest;
import io.justsearch.ipc.MigrationStartResponse;
/**
 * Migration lifecycle control helper for {@link WorkerIngestService}.
 *
 * <p>Encapsulates the 6 migration control RPCs (start, cutover, pause, resume, rollback, GC) that
 * all gate on {@link IndexGenerationManager} and manage migration state transitions. Extracted to
 * reduce the size of the service class.
 *
 * <p>Lane F stage A item A3: every method here answers with a response on both the success and the
 * failure path — a refusal rides in the response's {@code accepted=false} + {@code error} fields,
 * never as a transport status. So the conversion is a pure return-instead-of-onNext, and no
 * {@link WorkerServiceException} is thrown from this class.
 */
final class MigrationControlOps {

  private final IndexGenerationManager indexGenerationManager;
  private final Runnable restartWorkerCallback;

  MigrationControlOps(IndexGenerationManager indexGenerationManager, Runnable restartWorkerCallback) {
    this.indexGenerationManager = indexGenerationManager;
    this.restartWorkerCallback = restartWorkerCallback;
  }

  MigrationStartResponse startMigration(MigrationStartRequest request) {
    String reason = request.getReason();
    boolean restart = request.getRestartWorker();
    try {
      if (indexGenerationManager == null) {
        return MigrationStartResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .build();
      }
      IndexGenerationManager.State next =
          indexGenerationManager.startMigration(reason.isBlank() ? "manual" : reason.trim());
      String active =
          next == null || next.active_generation() == null ? "" : next.active_generation();
      String building =
          next == null || next.building_generation() == null ? "" : next.building_generation();
      String ms = next == null || next.migration_state() == null ? "" : next.migration_state();

      MigrationStartResponse response =
          MigrationStartResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setMigrationState(ms)
              .setActiveGenerationId(active)
              .setBuildingGenerationId(building)
              .setRestartScheduled(restart && restartWorkerCallback != null)
              .build();

      if (restart && restartWorkerCallback != null) {
        // Best-effort: restart after responding.
        new Thread(
                () -> {
                  try {
                    Thread.sleep(150);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  restartWorkerCallback.run();
                },
                "migration-start-restart")
            .start();
      }
      return response;
    } catch (Exception e) {
      return MigrationStartResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to start migration" : e.getMessage())
          .build();
    }
  }

  MigrationCutoverResponse requestCutover(MigrationCutoverRequest request) {
    try {
      if (indexGenerationManager == null) {
        return MigrationCutoverResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .build();
      }
      // Force SWITCHING (best-effort). Cutover monitor will handle the rest.
      if (request.getForceSwitching()) {
        indexGenerationManager.updateMigrationState(
            IndexGenerationManager.MigrationState.SWITCHING);
      }
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      return MigrationCutoverResponse.newBuilder()
          .setAccepted(true)
          .setError("")
          .setMigrationState(s == null || s.migration_state() == null ? "" : s.migration_state())
          .build();
    } catch (Exception e) {
      return MigrationCutoverResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to request cutover" : e.getMessage())
          .build();
    }
  }

  MigrationPauseResponse pauseMigration(MigrationPauseRequest request) {
    try {
      if (indexGenerationManager == null) {
        return MigrationPauseResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .setMigrationPaused(false)
            .build();
      }
      IndexGenerationManager.State s = indexGenerationManager.readStateBestEffort();
      String ms = s == null || s.migration_state() == null ? "" : s.migration_state();
      if ("SWITCHING".equalsIgnoreCase(ms)) {
        return MigrationPauseResponse.newBuilder()
            .setAccepted(false)
            .setError("Cannot pause during SWITCHING")
            .setMigrationPaused(false)
            .build();
      }
      IndexGenerationManager.State next =
          indexGenerationManager.setMigrationPaused(
              true, request == null ? "" : request.getReason());
      return MigrationPauseResponse.newBuilder()
          .setAccepted(true)
          .setError("")
          .setMigrationPaused(next != null && Boolean.TRUE.equals(next.migration_paused()))
          .build();
    } catch (Exception e) {
      return MigrationPauseResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to pause migration" : e.getMessage())
          .setMigrationPaused(false)
          .build();
    }
  }

  MigrationResumeResponse resumeMigration(MigrationResumeRequest request) {
    try {
      if (indexGenerationManager == null) {
        return MigrationResumeResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .setMigrationPaused(false)
            .build();
      }
      IndexGenerationManager.State next = indexGenerationManager.setMigrationPaused(false, null);
      return MigrationResumeResponse.newBuilder()
          .setAccepted(true)
          .setError("")
          .setMigrationPaused(next != null && Boolean.TRUE.equals(next.migration_paused()))
          .build();
    } catch (Exception e) {
      return MigrationResumeResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to resume migration" : e.getMessage())
          .setMigrationPaused(false)
          .build();
    }
  }

  MigrationRollbackResponse rollbackMigration(MigrationRollbackRequest request) {
    boolean restart = request.getRestartWorker();
    try {
      if (indexGenerationManager == null) {
        return MigrationRollbackResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .build();
      }
      IndexGenerationManager.State next = indexGenerationManager.rollbackToPreviousGeneration();
      if (next == null) {
        return MigrationRollbackResponse.newBuilder()
            .setAccepted(false)
            .setError("No index state available")
            .build();
      }
      MigrationRollbackResponse response =
          MigrationRollbackResponse.newBuilder()
              .setAccepted(true)
              .setError("")
              .setActiveGenerationId(
                  next.active_generation() == null ? "" : next.active_generation())
              .setPreviousGenerationId(
                  next.previous_generation() == null ? "" : next.previous_generation())
              .setRestartScheduled(restart && restartWorkerCallback != null)
              .build();

      if (restart && restartWorkerCallback != null) {
        new Thread(
                () -> {
                  try {
                    Thread.sleep(150);
                  } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                  }
                  restartWorkerCallback.run();
                },
                "migration-rollback-restart")
            .start();
      }
      return response;
    } catch (Exception e) {
      return MigrationRollbackResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to rollback migration" : e.getMessage())
          .build();
    }
  }

  IndexGcResponse runIndexGc(IndexGcRequest request) {
    try {
      if (indexGenerationManager == null) {
        return IndexGcResponse.newBuilder()
            .setAccepted(false)
            .setError("Index generation manager not available")
            .setMarkedCount(0)
            .setPrunedCount(0)
            .build();
      }
      int keepLatest = request == null ? 0 : request.getKeepLatest();
      boolean pruneMarkedOnly = request != null && request.getPruneMarkedOnly();
      IndexGenerationManager.GcResult r =
          indexGenerationManager.gcBestEffort(keepLatest, pruneMarkedOnly);
      return IndexGcResponse.newBuilder()
          .setAccepted(true)
          .setError("")
          .setMarkedCount(r == null ? 0 : r.markedCount())
          .setPrunedCount(r == null ? 0 : r.prunedCount())
          .build();
    } catch (Exception e) {
      return IndexGcResponse.newBuilder()
          .setAccepted(false)
          .setError(e.getMessage() == null ? "Failed to run GC" : e.getMessage())
          .setMarkedCount(0)
          .setPrunedCount(0)
          .build();
    }
  }
}
