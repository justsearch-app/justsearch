/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.core.context.EngineContext;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import io.justsearch.core.execution.EngineExecutorSpec.Kind;
import io.justsearch.core.execution.EngineExecutorSpec.Mode;

import io.justsearch.ipc.PruneRequest;
import io.justsearch.ipc.PruneResponse;
import io.justsearch.ipc.SyncDirectoryRequest;
import io.justsearch.ipc.SyncDirectoryResponse;
import io.justsearch.ipc.CircuitBreakerOpenException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sync and periodic maintenance operations for watched roots.
 *
 * <p>Handles prune, sync directory, and periodic sync scheduling. Extracted from
 * {@link KnowledgeClient}.
 */
final class SyncOps {
    private static final Logger log = LoggerFactory.getLogger(SyncOps.class);

    /**
     * Periodic reconcile cadence (tempdoc 626 §Axis-B). The scheduler time-slices ONE root per cycle
     * (round-robin), so worst-case per-root reconcile staleness ≈ {@code watchedRoots.size() *
     * SYNC_INTERVAL_SECONDS}. This is the declared backstop interval — the watcher fast-path catches
     * most changes promptly; this bounds how long a watcher-missed change (overflow/inotify-exhaustion)
     * can persist before the reconcile re-converges the index.
     */
    private static final long SYNC_INTERVAL_SECONDS = 60;

    private final IngestRpcExecutor rpc;
    private final Map<Path, Instant> watchedRoots;
    private final EngineExecutorRegistry.Registration schedulerRegistration;
    /**
     * Tempdoc 626 §Axis-C — records the per-root delete-detection verification outcome of a force=false
     * reconcile so the FE can show a "couldn't verify" state instead of a false "✓ indexed". No-op until
     * wired (tests).
     */
    private final java.util.function.BiConsumer<Path, Boolean> recordUnverified;
    /**
     * Tempdoc 626 §Axis-C (drift-corrected) — records {@code (root, orphanCount)} when a force=false
     * reconcile prunes stale entries a live DELETE missed, so the {@code IndexDriftHealthTap} can emit a
     * one-shot {@code index.drift-corrected} Occurrence. No-op until wired (tests).
     */
    private final java.util.function.BiConsumer<Path, Integer> recordDriftCorrected;

    // Scheduler state (owned by this class)
    private ScheduledExecutorService syncScheduler;
    private ScheduledFuture<?> syncTask;
    private final AtomicLong currentRootIndex = new AtomicLong(0);

    SyncOps(
        EngineExecutorRegistry executors, IngestRpcExecutor rpc, Map<Path, Instant> watchedRoots) {
        this(executors, rpc, watchedRoots, (root, unverified) -> {}, (root, count) -> {});
    }

    SyncOps(
        EngineExecutorRegistry executors,
        IngestRpcExecutor rpc,
        Map<Path, Instant> watchedRoots,
        java.util.function.BiConsumer<Path, Boolean> recordUnverified) {
        this(executors, rpc, watchedRoots, recordUnverified, (root, count) -> {});
    }

    SyncOps(
        EngineExecutorRegistry executors,
        IngestRpcExecutor rpc,
        Map<Path, Instant> watchedRoots,
        java.util.function.BiConsumer<Path, Boolean> recordUnverified,
        java.util.function.BiConsumer<Path, Integer> recordDriftCorrected) {
        Objects.requireNonNull(executors, "executors");
        this.rpc = Objects.requireNonNull(rpc, "rpc");
        this.watchedRoots = Objects.requireNonNull(watchedRoots, "watchedRoots");
        this.recordUnverified =
            recordUnverified == null ? (root, unverified) -> {} : recordUnverified;
        this.recordDriftCorrected =
            recordDriftCorrected == null ? (root, count) -> {} : recordDriftCorrected;
        EngineExecutorRegistry.Limits background = executors.limits(Kind.BACKGROUND);
        this.schedulerRegistration =
            executors.register(
                new EngineExecutorSpec(
                    "knowledge-client-periodic-sync",
                    Kind.BACKGROUND,
                    Mode.SCHEDULED,
                    1,
                    background.maxQueue(),
                    1));
    }

    // ========== RPC helpers ==========

    private PruneResponse executePruneMissing(String pathPrefix, EngineContext engineContext) {
        PruneRequest request = PruneRequest.newBuilder().setPathPrefix(pathPrefix).build();
        return rpc.execute(
                "pruneMissing",
                KnowledgeClient.RpcDeadlineCategory.LONG_RUNNING,
                stub -> stub.pruneMissing(request), engineContext);
    }

    private SyncDirectoryResponse executeSyncDirectory(String rootPath, boolean force, EngineContext engineContext) {
        SyncDirectoryRequest request =
                SyncDirectoryRequest.newBuilder().setRootPath(rootPath).setForce(force).build();
        return rpc.execute(
                "syncDirectory",
                KnowledgeClient.RpcDeadlineCategory.LONG_RUNNING,
                stub -> stub.syncDirectory(request), engineContext);
    }

    // ========== Public operations ==========

    boolean pruneMissing(String pathPrefix, EngineContext engineContext) {
        try {
            PruneResponse response = executePruneMissing(pathPrefix, engineContext);

            if (response.getAborted()) {
                log.info("Prune aborted for {} (user activity)", pathPrefix);
                return false;
            } else if (!response.getError().isEmpty()) {
                log.warn("Prune error for {}: {}", pathPrefix, response.getError());
                return false;
            } else {
                log.info(
                        "Pruned {} orphan documents under {}",
                        response.getPrunedCount(),
                        pathPrefix);
                return true;
            }
        } catch (CircuitBreakerOpenException e) {
            log.debug("pruneMissing rejected by circuit breaker for {}", pathPrefix);
            return false;
        } catch (Exception e) {
            log.warn("pruneMissing RPC failed for {}", pathPrefix, e);
            // Don't fail reindex if prune fails - still submit current files
            return false;
        }
    }

    SyncDirectoryResponse syncDirectory(String rootPath, boolean force, EngineContext engineContext) {
        try {
            SyncDirectoryResponse response = executeSyncDirectory(rootPath, force, engineContext);

            // Tempdoc 626 §Axis-C/§Recency — update the per-root verification state from this reconcile.
            if (response.getError().isEmpty()) {
                Path root = Path.of(rootPath).toAbsolutePath().normalize();
                if (!force) {
                    // force=false periodic reconcile: a user-activity skip (skipped + !unverified) leaves
                    // the prior state untouched (it isn't a verification result); a clean full scan clears
                    // it (verified, stamping lastVerifiedAt); the cap-skipped scan sets it (UNVERIFIED).
                    if (response.getDeleteDetectionUnverified()) {
                        recordUnverified.accept(root, true);
                    } else if (!response.getSkipped()) {
                        recordUnverified.accept(root, false);
                    }
                    // Tempdoc 626 §Axis-C (drift-corrected) — the reconcile pruned stale entries a live
                    // DELETE missed: a one-shot "drift was found and corrected" signal for the tap. Kept
                    // force=false-only — a forced bulk re-prune is not the clean "a live delete was
                    // missed" signal.
                    if (response.getFilesDeleted() > 0) {
                        recordDriftCorrected.accept(root, response.getFilesDeleted());
                    }
                } else if (!response.getSkipped()) {
                    // Tempdoc 626 §Recency — a force=true reconcile re-prunes orphans + re-walks the whole
                    // root (a full re-converge), so a non-skipped success IS a verification: clear the
                    // unverified flag and stamp the lastVerifiedAt heartbeat. This is what makes the scoped
                    // "Verify this folder" recovery (core.reconcile-root) refresh the per-root state.
                    recordUnverified.accept(root, false);
                }
            }

            if (response.getSkipped()) {
                log.debug("SyncDirectory skipped for {} (force={})", rootPath, force);
            } else if (!response.getError().isEmpty()) {
                log.warn("SyncDirectory error for {}: {}", rootPath, response.getError());
            } else {
                log.info(
                        "SyncDirectory complete for {}: {} added, {} deleted",
                        rootPath,
                        response.getFilesAdded(),
                        response.getFilesDeleted());
            }
            return response;
        } catch (CircuitBreakerOpenException e) {
            log.debug("syncDirectory rejected by circuit breaker for {}", rootPath);
            return null;
        } catch (Exception e) {
            log.warn("syncDirectory RPC failed for {}", rootPath, e);
            return null;
        }
    }

    void startPeriodicSync() {
        EngineContext engineContext = io.justsearch.app.services.intent.EngineProvenance.internal(
            "periodic-root-sync", EngineContext.Survival.DURABLE, EngineContext.Urgency.BACKGROUND);
        if (syncScheduler != null) {
            log.debug("Periodic sync already started");
            return;
        }

        syncScheduler =
                schedulerRegistration.openScheduled(
                        r -> {
                            Thread t = new Thread(r, "sync-scheduler");
                            t.setDaemon(true);
                            return t;
                        });

        syncTask =
                syncScheduler.scheduleAtFixedRate(
                        () -> {
                            try {
                                List<Path> roots = List.copyOf(watchedRoots.keySet());
                                if (roots.isEmpty()) {
                                    return;
                                }

                                // Time-slicing: sync ONE root per cycle
                                int index =
                                        (int)
                                                (currentRootIndex.getAndIncrement()
                                                        % roots.size());
                                Path root = roots.get(index);

                                log.debug(
                                        "Periodic sync: root {} of {} ({})",
                                        index + 1,
                                        roots.size(),
                                        root);

                                // force=false: Worker will skip if user is actively searching
                                syncDirectory(root.toString(), /* force= */ false, engineContext);

                            } catch (Exception e) {
                                log.warn("Periodic sync failed", e);
                            }
                        },
                        SYNC_INTERVAL_SECONDS,
                        SYNC_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);

        log.info(
                "Periodic sync scheduler started (interval={}s, time-sliced)",
                SYNC_INTERVAL_SECONDS);
    }

    void stopPeriodicSync() {
        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
        if (syncScheduler != null) {
            schedulerRegistration.close();
            syncScheduler = null;
        } else {
            schedulerRegistration.close();
        }
        log.debug("Periodic sync scheduler stopped");
    }
}
