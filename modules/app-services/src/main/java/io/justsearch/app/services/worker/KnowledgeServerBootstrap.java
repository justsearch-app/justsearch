/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.api.lifecycle.CapabilityHealth;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.services.lifecycle.WorkerCapability;
import io.justsearch.app.util.AppInstanceLock;
import io.justsearch.app.util.EnergyState;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.ipc.HealthCheckResponse;
import io.justsearch.telemetry.Telemetry;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bootstrap for the Knowledge Server client-side integration.
 *
 * <p>Manages the complete lifecycle:
 * <ol>
 *   <li>Loads configuration</li>
 *   <li>Opens signal bus</li>
 *   <li>Starts the index half through the {@link WorkerHost} — since lane F stage A item A6 that is
 *       {@code EngineRoot}, composing it in this JVM. Item A11 deleted the other implementation,
 *       which spawned a worker process</li>
 *   <li>Obtains a {@link KnowledgeClient} from the host (direct calls, not a gRPC channel)</li>
 *   <li>Provides health monitoring</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap();
 * bootstrap.start();
 *
 * // Use the client
 * KnowledgeClient client = bootstrap.client();
 * client.search("query", 10);
 *
 * // On shutdown
 * bootstrap.close();
 * }</pre>
 */
public final class KnowledgeServerBootstrap implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeServerBootstrap.class);

    private final KnowledgeServerConfig config;
    private final Telemetry telemetry;
    private final WorkerCapability workerCapability;
    private final AtomicBoolean started = new AtomicBoolean(false);
    /**
     * Tempdoc 502 §4.4: generation counter replaces the never-reset boolean CAS.
     * Generation 0 = never initialized. Generation 1 = first connect (full init).
     * Generation 2+ = recovery (partial re-init: reindex + periodic sync only).
     */
    private final java.util.concurrent.atomic.AtomicLong initGeneration =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.locks.ReentrantLock initLock =
        new java.util.concurrent.locks.ReentrantLock();

    // Tempdoc 825 review F10: volatile. Written by whichever thread runs start() /
    // closeForUpgrade() — the boot thread, and (since 825) the health monitor's executor — and read
    // by the other: hasClient() is the monitor's arm discriminator, so without volatile a reader can
    // observe a stale null client and make a needless recovery attempt against a worker that is up.
    private volatile KnowledgeClient client;

    /**
     * Where the index half lives (lane F item A6). Required since item A11 deleted the alternative:
     * the Engine composition root supplies itself, and there is no spawn-a-process path left.
     */
    private final WorkerHost workerHost;

    /**
     * Tempdoc 672 follow-up: epoch-ms of the most recent {@link #recordUserActivity()} call. Since
     * tempdoc 885 item 3 this is the <b>only</b> consumer of that fact: the Worker no longer
     * receives a Head-written activity byte at all (it observes its own in-flight foreground RPCs),
     * so what remains here is a purely Head-local signal for the Head's own idle checks — VDU
     * pacing and the service-phase idle gate.
     */
    private final java.util.concurrent.atomic.AtomicLong lastUserActivityEpochMs =
        new java.util.concurrent.atomic.AtomicLong(0);

    /**
     * Lane F item A5: the one in-process GPU-scheduling gauge. {@code main_gpu_active} is written
     * from the inference mode-change listener ({@code InferenceWiring.wireGpuStatusBroadcast}) and
     * {@code energy_reduced} from {@link #energyPoller}; while the Worker is still a separate
     * process both writers ALSO publish the matching MMF byte, which is the half deleted at A10.
     * The composition rule ("yield GPU-heavy backfill when either holds") lives in the gauge.
     */
    private final io.justsearch.core.scheduling.GpuSchedulingGauge gpuScheduling =
        new io.justsearch.core.scheduling.GpuSchedulingGauge();

    /**
     * Tempdoc 630's OS energy-intent poll, moved off the spawner at item A5 because it had to
     * outlive it (the spawner went at A11). Constructed eagerly and started with the integration,
     * so {@link #energyState()} answers UNKNOWN — not null — before the first poll.
     */
    private final io.justsearch.app.services.power.EnergyStatePoller energyPoller =
        // Item A11: the second sink is gone. It wrote the OS energy-intent into the memory-mapped
        // signal file for a Worker process to read; there is no second process, and the gauge the
        // first argument writes is the one the indexing loop reads.
        new io.justsearch.app.services.power.EnergyStatePoller(gpuScheduling);

    /** Tempdoc 630: epoch-ms of the most recent OS-resume handled, for the "Catching up" notice. */
    private final java.util.concurrent.atomic.AtomicLong lastResumeEpochMs =
        new java.util.concurrent.atomic.AtomicLong(0);
    /** How long after a resume the transient "Catching up after sleep" notice stays up. */
    private static final long RESUME_NOTICE_WINDOW_MS = 30_000;

    /** Total boot-time {@link #start()} attempts, including the first. */
    public static final int DEFAULT_START_ATTEMPTS = 3;
    /** Pause between boot-time {@link #start()} attempts. */
    public static final long DEFAULT_START_RETRY_BACKOFF_MS = 500;

    /**
     * Whether another {@link #start()} attempt will follow the one now failing. While true, the
     * per-attempt DEGRADED/OFFLINE transitions are suppressed: a boot that ultimately succeeds must
     * not narrate two worker-down occurrences on /api/health, and the capability stays PENDING —
     * which is the honest reading of "still starting". The final outcome always transitions.
     */
    private volatile boolean retryPending;

    /**
     * Tempdoc 825: true while {@link KnowledgeServerHealthMonitor}'s boot-recovery arm owns the
     * narration for an in-flight re-attempt. The arm holds the capability at RECOVERING for the whole
     * recovery arc, so every per-attempt transition this class would otherwise make — PENDING on
     * entry, DEGRADED on failure, OFFLINE on the teardown between attempts — is suppressed. Without
     * it a four-attempt recovery narrates a dozen worker-down/worker-starting occurrences and the
     * "no flapping for a boot that ultimately succeeds" acceptance fails. The READY transition is
     * never suppressed: success must always be narrated.
     */
    private volatile boolean bootRecoveryInFlight;

    /**
     * Tempdoc 915 R1: the last fatal INDEX verdict this bootstrap read out of the dying worker's
     * {@link io.justsearch.ipc.WorkerFatalReasonMarker}, remembered because the marker is deleted as
     * it is read and the read happens BEFORE the two narration guards decide whether the verdict is
     * applied.
     *
     * <p>Live validation caught the whole class: under {@code index.schema_mismatch.policy=FAIL_CLOSED}
     * the worker refused deterministically on all three {@link #startWithRetry} attempts, each
     * per-attempt catch consumed the freshly-written marker while {@code retryPending} suppressed the
     * narration, and the final catch — the one call that IS allowed to narrate — found no marker and
     * reported the generic {@code worker.spawn.failed}. Head readiness then rode the boot-recovery
     * ladder to {@code worker.spawn_recovery_exhausted} and the real cause never reached the user.
     * The corruption axis has the identical hole; it escapes only when its first worker-down call
     * happens to land outside a suppressed arc.
     *
     * <p>Cleared on READY (the worker opened the index, so no index verdict stands) — the same
     * anti-staleness bound {@link io.justsearch.app.services.lifecycle.ReasonRetention} uses.
     */
    private volatile WorkerDown latchedIndexFatalVerdict;

    private AppInstanceLock appLock;
    private IpcTelemetry ipcTelemetry;

    public KnowledgeServerBootstrap() {
        this(KnowledgeServerConfig.load(), new NoopTelemetry());
    }

    /** Tempdoc 627 Deliverable 10: production async-start ctor — loaded config + the injected shared capability. */
    public KnowledgeServerBootstrap(WorkerCapability workerCapability) {
        this(KnowledgeServerConfig.load(), new NoopTelemetry(), workerCapability);
    }

    public KnowledgeServerBootstrap(KnowledgeServerConfig config) {
        this(config, new NoopTelemetry());
    }

    public KnowledgeServerBootstrap(KnowledgeServerConfig config, Telemetry telemetry) {
        this(config, telemetry, new WorkerCapability());
    }

    /**
     * Tempdoc 627 Deliverable 10: inject a shared {@link WorkerCapability} so the Head's
     * {@code CapabilityGraph} and this supervisor drive ONE instance — eliminating the
     * {@code HeadAssembly.connectKnowledgeServer} mirror and its silent-drift bug class. The
     * no-arg / 2-arg ctors keep their own instance for tests and isolated launchers.
     */
    public KnowledgeServerBootstrap(
        KnowledgeServerConfig config, Telemetry telemetry, WorkerCapability workerCapability) {
        this(config, telemetry, workerCapability, WorkerHost.unavailable());
    }

    /**
     * Lane F item A6: the production ctor. {@code workerHost} decides where the index half lives —
     * {@code io.justsearch.app.engine.EngineRoot} for the Engine JVM. Required since item A11: the
     * spawn-a-Worker-process alternative is gone, so a null host is a construction error rather
     * than a fallback.
     */
    public KnowledgeServerBootstrap(
        KnowledgeServerConfig config, Telemetry telemetry, WorkerCapability workerCapability,
        WorkerHost workerHost) {
        this.workerHost =
            java.util.Objects.requireNonNull(workerHost, "workerHost (item A11: no spawn fallback)");
        this.config = config;
        this.telemetry = telemetry != null ? telemetry : new NoopTelemetry();
        this.workerCapability = workerCapability != null ? workerCapability : new WorkerCapability();
        // Tempdoc 915 R1: drop the fatal-index latch wherever READY comes from, rather than at the
        // two sites that happen to write it today. READY means the worker opened the index and is
        // serving, so no index verdict stands — the same anti-staleness bound ReasonRetention uses,
        // and the reason it needs no timer. Placed here so a future READY path inherits it.
        this.workerCapability.addListener(
            (prev, next) -> {
                if (next == CapabilityHealth.READY) {
                    latchedIndexFatalVerdict = null;
                }
            });
    }

    /**
     * Starts the Knowledge Server integration.
     *
     * <p>Steps:
     * <ol>
     *   <li>Acquires the single-instance lock for the data directory</li>
     *   <li>Asks the {@link WorkerHost} to compose the index half (in this JVM)</li>
     *   <li>Polls health until the worker component reaches READY</li>
     * </ol>
     *
     * @throws IOException if composition fails
     * @throws InterruptedException if startup is interrupted
     */
    public void start() throws IOException, InterruptedException {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("KnowledgeServerBootstrap already started");
        }

        // Tempdoc 825: during a boot-recovery arc the monitor holds the capability at RECOVERING and
        // owns the narration; dropping back to PENDING here would re-enter RECOVERING on the next
        // attempt and emit a worker.restart-attempted occurrence per cycle.
        if (!bootRecoveryInFlight) {
            workerCapability.transition(
                CapabilityHealth.PENDING, LifecycleReasonCode.WORKER_STARTING.code(), "Worker starting");
        }
        log.info("Starting Knowledge Server integration...");

        try {
            // 0. Enforce single-instance semantics for this data directory.
            // This must happen before we spawn the Worker (and before any code tries to mutate jobs.db/index).
            //
            // Tempdoc 501 §3.7: when launched under HeadlessApp, the Head has already
            // acquired AppInstanceLock at startup. Re-acquiring in the same JVM on a
            // different FileChannel would throw OverlappingFileLockException, so we skip
            // when AppInstanceLock.isHeldByThisJvm(dataDir) reports true. Standalone
            // callers (tests, isolated launchers) take the acquire path as before.
            if (AppInstanceLock.isHeldByThisJvm(config.dataDir())) {
                log.debug(
                    "Skipping AppInstanceLock acquire: Head already holds it (tempdoc 501 §3.7)");
            } else {
                appLock = new AppInstanceLock(config.dataDir());
                appLock.acquire();
            }

            // 1. Create IPC telemetry for worker lifecycle instrumentation. Tempdoc 417 Phase 2e:
            // IpcTelemetry now wraps a typed catalog. Build it from LocalTelemetry's registry when
            // available; otherwise use the noop variant.
            ipcTelemetry =
                telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt
                    ? new IpcTelemetry(new IpcMetricCatalog(lt.registry()))
                    : IpcTelemetry.noop();

            // 2. The Engine composes the index half in this JVM. Item A11 deleted the alternative:
            // there is no spawner, no memory-mapped signal file, no port to discover, no channel to
            // open and no PID to validate against a spawn. `workerHost` is required rather than
            // optional now, which is why the field is final and the constructor rejects null.
            energyPoller.start();
            try {
                client = workerHost.start(gpuScheduling, ipcTelemetry);
            } catch (IOException | InterruptedException | RuntimeException e) {
                throw e;
            } catch (Exception e) {
                // WorkerHost.start is declared broadly so a future host can fail its own way; the
                // bootstrap contract is IOException, so wrap anything else honestly rather than
                // widening start()'s throws clause.
                throw new IOException("Engine composition failed: " + e.getMessage(), e);
            }

            // 3. Verify health, transition to READY and run the ready-initialization sequence.
            // Unchanged from the spawned path on purpose: /api/health's worker component keeps its
            // exact WORKER_* vocabulary, which design §6 re-cuts at D1, not here.
            awaitHealthyAndComplete();
        } catch (Exception e) {
            transitionWorkerDown(
                LifecycleReasonCode.WORKER_SPAWN_FAILED, "Start failed: " + e.getMessage());
            log.error("Failed to start Knowledge Server integration", e);
            close();
            throw e;
        }
    }

    /**
     * Verifies health with a bounded retry (Tempdoc 374 alpha.23 R13-A defect #1), then transitions
     * the worker capability and runs the ready-initialization sequence.
     *
     * <p>Round-13 cycle 2 caught the worker still warming up Lucene SearcherManager. Pre-fix: a
     * single {@code isHealthy()} call straddled the warmup, transitioned to ERROR, and the
     * auxiliary services never initialized. Post-fix: poll for up to
     * {@code healthCheckRetryBudgetMs}; if the budget elapses without success,
     * {@link KnowledgeServerHealthMonitor} takes over.
     *
     * <p>Lane F item A6 extracted this from {@code start()} so the in-process host and the legacy
     * spawned process shared one readiness path; item A11 deleted the spawned one, and this stayed
     * where it was — the WORKER_* reason codes and the auxiliary-service sequence are the worker
     * component's contract on {@code /api/health}, not a property of how it was started.
     *
     */
    private void awaitHealthyAndComplete() throws InterruptedException {
        long retryBudgetMs = config.healthCheckRetryBudgetMs();
        long healthCheckStartMs = System.currentTimeMillis();
        boolean healthy = client.isHealthy();
        while (!healthy && (System.currentTimeMillis() - healthCheckStartMs) < retryBudgetMs) {
            Thread.sleep(1000);
            healthy = client.isHealthy();
        }
        long healthCheckElapsedMs = System.currentTimeMillis() - healthCheckStartMs;

        if (healthy) {
            workerCapability.transition(CapabilityHealth.READY, null);
            if (healthCheckElapsedMs >= 1000) {
                log.info("Knowledge Server became healthy after {}ms of warmup polling",
                        healthCheckElapsedMs);
            } else {
                log.info("Knowledge Server is READY");
            }
            completeReadyInitialization();
        } else {
            // Tempdoc 837 §3.1: the start-time health budget elapsed — the worker NEVER started.
            // Review F7: this site is reachable DURING a recovery arc — the attempt's worker
            // spawns and answers but never reaches healthy — and it was the one worker-down
            // site without a suppression guard. The rule now lives in transitionWorkerDown, so
            // this call is unconditional and the funnel decides.
            transitionWorkerDown(
                LifecycleReasonCode.WORKER_SPAWN_FAILED,
                "Health check failed after " + healthCheckElapsedMs + "ms");
            log.warn("Knowledge Server health check failed after {}ms budget; auxiliary services not initialized — background monitor will retry",
                    healthCheckElapsedMs);
        }
    }

    /**
     * Starts with a bounded retry, so a transient boot-time timing failure is not terminal.
     *
     * <p>{@link #start()} tears itself down via {@link #close()} on failure — which resets
     * {@code started} and drops the client — so a subsequent attempt composes from a clean slate.
     *
     * <p>Item A11 removed the transient/permanent classifier ({@code WorkerStartFailures}) with the
     * spawner it classified: its whole vocabulary was spawn-shaped (worker jar missing, port
     * discovery timeout, PID mismatch), and an in-process composition fails for different reasons —
     * a Lucene open, a jobs.db migration. Every attempt is now retried within the budget, which is
     * what the spawn-shaped classifier did for the transient class anyway; a permanent failure
     * simply costs the remaining attempts before the same verdict lands.
     */
    public void startWithRetry() throws IOException, InterruptedException {
        startWithRetry(DEFAULT_START_ATTEMPTS, DEFAULT_START_RETRY_BACKOFF_MS);
    }

    /** {@link #startWithRetry()} with an explicit attempt budget; visible for tests. */
    /** {@link #startWithRetry()} with an explicit attempt budget; visible for tests. */
    public void startWithRetry(int maxAttempts, long backoffMs)
            throws IOException, InterruptedException {
        Exception last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            retryPending = attempt < maxAttempts;
            try {
                start();
                retryPending = false;
                return;
            } catch (IOException | InterruptedException | RuntimeException e) {
                last = e;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (attempt < maxAttempts) {
                    log.warn("Knowledge Server start attempt {}/{} failed ({}); retrying in {}ms",
                            attempt, maxAttempts, e.getMessage(), backoffMs);
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        retryPending = false;
        // The per-attempt narration was suppressed; the final verdict lands exactly once, here —
        // unless the boot-recovery arm owns this arc's narration (tempdoc 825), in which case the
        // verdict is ITS terminal give-up. transitionWorkerDown is the one funnel and owns that rule.
        transitionWorkerDown(
            LifecycleReasonCode.WORKER_SPAWN_FAILED,
            "Start failed: " + (last == null ? "unknown" : last.getMessage()));
        if (last instanceof IOException io) {
            throw io;
        }
        if (last instanceof InterruptedException ie) {
            throw ie;
        }
        throw new IOException("Knowledge Server start failed after " + maxAttempts + " attempts", last);
    }

    /**
     * Tempdoc 825: ONE boot-recovery attempt, with this class's per-attempt narration suppressed.
     * Called only by {@link KnowledgeServerHealthMonitor}'s boot-recovery arm, which decides whether
     * an attempt is due ({@link BootRecoveryDecision}), holds the capability at RECOVERING across the
     * arc, and narrates the terminal give-up. The attempt budget lives in {@link BootRecoveryPolicy},
     * so this deliberately does NOT re-run the boot-time 3-attempt retry inside one cycle.
     *
     * <p>The flag is set and cleared around the single call, so a throwing attempt cannot leave the
     * bootstrap permanently unable to narrate.
     */
    public void startForRecovery() throws IOException, InterruptedException {
        bootRecoveryInFlight = true;
        try {
            startWithRetry(1, 0);
        } finally {
            bootRecoveryInFlight = false;
        }
    }

    /** Whether a per-attempt worker-down / worker-starting transition would be a lie right now. */
    private boolean narrationSuppressed() {
        return retryPending || bootRecoveryInFlight;
    }

    /**
     * Tempdoc 825 review F1: whether SUPERVISION has already stamped its terminal verdict on this
     * capability. {@code worker.restart_exhausted} and {@code worker.spawn.failed} are both
     * {@code FAULT}, so {@link io.justsearch.app.services.lifecycle.ReasonRetention} lets the
     * incoming one win — which means this class's own final "start failed" stamp would erase the
     * supervisor's verdict on every boot where supervision engaged and gave up. That is not merely a
     * cosmetic loss: {@link BootRecoveryDecision}'s permanent veto reads this exact slot, so the
     * erasure would silently convert "supervision gave up, stop for good" into "nobody knows, keep
     * re-attempting" — the second restart authority the tempdoc-627 review forbade.
     */
    private boolean supervisionVerdictHeld() {
        return LifecycleReasonCode.WORKER_RESTART_EXHAUSTED
            .code()
            .equals(workerCapability.pendingReason());
    }

    /**
     * Whether a supervisor is alive right now and holding the restart budget.
     *
     * <p><b>Always false since lane F stage A item A11</b>, which deleted {@code WorkerSpawner} and
     * with it the only supervisor the Worker ever had. Kept rather than removed because
     * {@link BootRecoveryDecision}'s {@code SUPERVISION_ENGAGED} veto has a named, dated future
     * producer — design 7.1's one supervisor contract, landing at stage B — so deleting the veto
     * now would mean re-adding it then. Stage A §10 records the loss: no crash detection, no
     * restart budget, no cooldown, no stability window.
     */
    public boolean supervisionActive() {
        return false;
    }

    /**
     * Tempdoc 825: whether a knowledge-port client is bound (it was a gRPC client until lane F
     * stage A item A6 made the port an in-process call). This is the discriminator between the health
     * monitor's two arms — a bound client means the bootstrap is up and {@link #checkHealth()} owns
     * it; no client means {@code start()} never completed and the boot-recovery arm owns it.
     */
    public boolean hasClient() {
        return client != null;
    }

    /**
     * Tempdoc 502 §4.4: generation-based initialization. First call (generation 0→1)
     * runs full initialization. Subsequent calls (recovery) re-run only catch-up steps
     * (reindex + periodic sync). Called from both the bootstrap success path and the
     * health-monitor recovery path.
     */
    private void completeReadyInitialization() {
        if (!initLock.tryLock()) {
            log.debug("completeReadyInitialization already running — skipping");
            return;
        }
        try {
            long prevGen = initGeneration.getAndIncrement();
            if (prevGen == 0) {
                // Tempdoc 626 §Axis-A — the redundant Head-side file watcher was removed; the
                // Worker-side watcher (registered via WatchRoot during the root walk) is the sole
                // event source, and the periodic sync + reindexPersistedRoots are the reconcile
                // backstop. File-event integration now lives entirely in the Worker process.
                client.reindexPersistedRoots();
                tryIngestHelpFiles(client, config);
                client.startPeriodicSync();
            } else {
                log.info("Worker recovery detected (generation {}); re-running catch-up initialization", prevGen + 1);
                client.reindexPersistedRoots();
                client.startPeriodicSync();
            }
        } finally {
            initLock.unlock();
        }
    }

    /**
     * Tempdoc 374 alpha.23 R13-A defect #2: package-private hook called by
     * {@link KnowledgeServerHealthMonitor} when the worker recovers from
     * non-READY to READY. Delegates to the same idempotent helper used by
     * the bootstrap success path.
     */
    void completeReadyInitializationFromMonitor() {
        completeReadyInitialization();
        tryIngestHelpFiles(client, config);
    }

    /**
     * Returns true if the Knowledge Server is ready.
     * Delegates to {@link WorkerCapability#available()}.
     */
    public boolean isReady() {
        return workerCapability.available();
    }

    public WorkerCapability workerCapability() {
        return workerCapability;
    }

    /**
     * Returns the client for Knowledge Server operations.
     *
     * @throws IllegalStateException if not started or not ready
     */
    public KnowledgeClient client() {
        if (client == null) {
            throw new IllegalStateException("Knowledge Server not started");
        }
        return client;
    }

    /**
     * The latest polled OS energy-intent (tempdoc 630), for the /api/status "Paused — saving energy"
     * Queue-card state. Never null: {@link EnergyState#unknown()} until the first poll, which is
     * also what it answers before {@link #start()} has run (item A5 moved the poll off the spawner,
     * so this no longer depends on a spawned process existing).
     */
    public EnergyState energyState() {
        return energyPoller.energyState();
    }

    /**
     * The in-process GPU-scheduling gauge (item A5): {@code main_gpu_active} and
     * {@code energy_reduced} as one holder with one composition rule.
     * {@code InferenceWiring.wireGpuStatusBroadcast} writes the GPU half; {@link #energyPoller}
     * writes the energy half. At item A6 the Engine root hands this same instance to the worker half
     * and the memory-mapped bytes stop being a transport.
     */
    public io.justsearch.core.scheduling.GpuSchedulingGauge gpuScheduling() {
        return gpuScheduling;
    }

    /**
     * Marks an OS resume just handled (tempdoc 630). Called by the health monitor's
     * post-resume eager re-validation so /api/status can surface a brief "Catching up after sleep"
     * transient while the reconcile runs.
     */
    public void markResumed(long nowEpochMs) {
        lastResumeEpochMs.set(nowEpochMs);
    }

    /**
     * Whether an OS resume was handled within the recent notice window (tempdoc 630) — drives the
     * transient "Catching up after sleep" verdict, which auto-clears once the window elapses.
     */
    public boolean recentlyResumed(long nowEpochMs) {
        long last = lastResumeEpochMs.get();
        return last > 0 && (nowEpochMs - last) < RESUME_NOTICE_WINDOW_MS;
    }

    /**
     * Records real user activity (search, suggest, folder listing, preview) for the Head's own
     * idle checks. Tempdoc 885 item 3 removed the Head→Worker half of this call: the Worker paces
     * indexing on its own in-flight foreground-RPC gauge, not on a wall-clock byte the Head writes.
     */
    public void recordUserActivity() {
        lastUserActivityEpochMs.set(System.currentTimeMillis());
    }

    /**
     * Tempdoc 672 follow-up: milliseconds since the most recent {@link #recordUserActivity()}
     * call, or {@link Long#MAX_VALUE} if none has ever been recorded (treated as "idle" by
     * callers, never as "just active" — mirrors {@code EnergyState.unknown()}'s
     * never-throttle-on-uncertainty posture).
     */
    public long msSinceLastUserActivity(long nowEpochMs) {
        long last = lastUserActivityEpochMs.get();
        return last == 0 ? Long.MAX_VALUE : nowEpochMs - last;
    }

    /**
     * Performs a health check and updates state.
     *
     * @return true if healthy
     */
    /** The remedy sentence for a corrupt index — the detail the user needs, not a reason code. */
    private static final String INDEX_CORRUPT_DETAIL =
        "The search index is corrupt and the worker could not auto-recover under the"
            + " fail-closed policy. Set index.recovery.policy=BACKUP_REBUILD (or remove the index"
            + " directory) to rebuild it from your files.";

    /**
     * The remedy sentence for a refused schema mismatch. Tempdoc 915, live validation: the Head used
     * to report this as "Worker process crashed (exit code 1)" with the real cause visible only in
     * the (then separate) worker.log, because nothing wrote the fatal-reason marker on the refusal
     * path. There is one process and one log (engine.log) since lane F stage A items A11/A16, but
     * the remedy sentence is still what the user reads instead of a log.
     */
    private static final String INDEX_SCHEMA_MISMATCH_DETAIL =
            "The search index was built with a different index shape than this version writes, and"
                + " index.schema_mismatch.policy=FAIL_CLOSED refuses to rebuild it. Set the policy to"
                + " BLUE_GREEN_MIGRATE to rebuild alongside the existing index, or rebuild the index"
                + " yourself.";

    /** A worker-down verdict: the reason CODE plus the human sentence behind it (tempdoc 837 §0.2). */
    private record WorkerDown(LifecycleReasonCode code, String detail) {}

    /**
     * tempdoc 628 Stage D-part2: enrich a worker-down verdict with the corruption cause when the worker
     * exited fatally because the index was corrupt and could not be auto-recovered (the opt-in
     * FAIL_CLOSED policy — G2's self-heal default keeps it alive). Lets the Head surface "worker down:
     * the index is corrupt — rebuild to recover" instead of a generic/silent restart-loop. The dying
     * worker stamps {@link io.justsearch.ipc.WorkerFatalReasonMarker}; this reads + clears it.
     *
     * <p>Tempdoc 837 §3.1: corruption is an orthogonal AXIS, not a call site. Each of the four callers
     * passes the axis-1 code it already knows to be true — {@code WORKER_SPAWN_FAILED} where the worker
     * never started, {@code WORKER_LOST} where it was READY and stopped answering — and this helper
     * overrides it only when the marker says the index is corrupt. The remedy paragraph that used to BE
     * the reason is now the detail, so it still reaches the Health-event message and the 503 body while
     * the reason slot stays a code.
     *
     * <p>{@code readAndClear} deletes the marker, so this observation is unrepeatable — see
     * {@link io.justsearch.app.services.lifecycle.WorkerCapability#transition} for the latch that keeps
     * a later generic transition from destroying it.
     */
    private WorkerDown workerDownCode(LifecycleReasonCode generic, String detail) {
        String fatal = io.justsearch.ipc.WorkerFatalReasonMarker.readAndClear(config.dataDir());
        if (io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_CORRUPT.equals(fatal)) {
            return latchIndexFatal(
                    new WorkerDown(LifecycleReasonCode.WORKER_INDEX_CORRUPT, INDEX_CORRUPT_DETAIL));
        }
        if (io.justsearch.ipc.WorkerFatalReasonMarker.INDEX_SCHEMA_MISMATCH.equals(fatal)) {
            return latchIndexFatal(
                    new WorkerDown(
                            LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH, INDEX_SCHEMA_MISMATCH_DETAIL));
        }
        // Tempdoc 915 R1: no marker on disk does NOT mean no fatal index verdict — an earlier call in
        // this same boot arc already consumed it. Re-offering the latched one is what makes the
        // observation survive a suppressed attempt; without it the arc's ONE narrating call reports
        // the generic code and the cause is unrecoverable.
        WorkerDown latched = latchedIndexFatalVerdict;
        if (latched != null) {
            return latched;
        }
        return new WorkerDown(generic, detail);
    }

    private WorkerDown latchIndexFatal(WorkerDown verdict) {
        latchedIndexFatalVerdict = verdict;
        return verdict;
    }

    /**
     * Tempdoc 915 R1: whether a verdict is one of the two fatal INDEX causes, which are the ones a
     * respawn cannot change — the condition lives in the index directory, not in the process.
     */
    private static boolean isIndexFatal(LifecycleReasonCode code) {
        return code == LifecycleReasonCode.WORKER_INDEX_CORRUPT
                || code == LifecycleReasonCode.WORKER_INDEX_SCHEMA_MISMATCH;
    }

    /**
     * The remedy sentence behind the latched fatal index verdict, or {@code null} if none stands.
     * Read by the Head so {@code knowledgeServerStartError} names the refusal instead of the spawn
     * symptom ("Worker process crashed (exit code 1) before writing port to signal file"), which is
     * what the user actually saw in tempdoc 915's live arm 2.
     */
    public String indexFatalDetail() {
        WorkerDown latched = latchedIndexFatalVerdict;
        return latched == null ? null : latched.detail();
    }

    /** The reason code of the latched fatal index verdict, or {@code null} if none stands. */
    public LifecycleReasonCode indexFatalCode() {
        WorkerDown latched = latchedIndexFatalVerdict;
        return latched == null ? null : latched.code();
    }

    /**
     * Applies a {@link #workerDownCode} verdict to the capability as DEGRADED. Visible for tests.
     *
     * <p>Review F1: this is the ONE funnel for every worker-down verdict this class produces
     * ({@code start()}'s per-attempt catch, the health-budget branch, {@code startWithRetry}'s final
     * catch, and {@code checkHealth}'s worker.lost), so the "do not overwrite supervision's terminal
     * verdict" rule lives here rather than at four call sites that would drift.
     * {@code worker.restart_exhausted} and the codes below are all {@code FAULT}, so
     * {@link io.justsearch.app.services.lifecycle.ReasonRetention} lets the incoming one win — and
     * losing it is not cosmetic: {@link BootRecoveryDecision}'s permanent veto reads this exact slot,
     * so an overwrite silently converts "supervision gave up, stop for good" into "nobody knows, keep
     * re-attempting".
     *
     * <p>The two fatal INDEX verdicts ({@link #isIndexFatal}) are the exception, and they are computed
     * BEFORE the guard so the unrepeatable marker is still consumed: they explain WHY supervision
     * exhausted itself and are strictly better information (their {@code STICKY} class says the same).
     * Tempdoc 915 R1 widened this from corruption alone — the schema-mismatch refusal is the same kind
     * of fact, and leaving it out meant a FAIL_CLOSED boot narrated {@code worker.spawn.failed}.
     */
    void transitionWorkerDown(LifecycleReasonCode generic, String detail) {
        WorkerDown down = workerDownCode(generic, detail);
        if (narrationSuppressed()) {
            // Review F7: the suppression rule lives in the funnel too, for the same reason the
            // supervision guard does — it was applied at three of the four worker-down sites and
            // missed the health-budget branch, which is reachable mid-recovery (the attempt's worker
            // spawns and answers gRPC but never becomes healthy) and flapped the arc out of
            // RECOVERING. Any future site inherits the rule instead of having to remember it.
            log.debug(
                "Suppressing worker-down narration ({}): a retry or recovery arc owns it",
                down.code().code());
            return;
        }
        if (supervisionVerdictHeld() && !isIndexFatal(down.code())) {
            log.warn(
                "Not overwriting supervision's terminal {} with {}: the supervisor's verdict stands",
                LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code(),
                down.code().code());
            return;
        }
        workerCapability.transition(
            CapabilityHealth.DEGRADED, down.code().code(), down.detail());
    }

    public boolean checkHealth() {
        if (client == null) {
            return false;
        }
        boolean healthy = client.isHealthy();
        // Item A11: the poll used to feed the spawner's hang detector, whose escalation was a
        // restart. There is no restart authority in stage A (§10) — a lost worker component reports
        // itself lost and stays that way until the user restarts the Engine.
        // Historical note kept because the streak threshold is stage B's input: a sustained streak on
        // a still-alive worker (the "liveness" signal) triggers a budgeted graceful restart — closing
        // the Worker's observation→actuation loop. This is the only wiring the health monitor needs.

        CapabilityHealth current = workerCapability.health();
        if (healthy && current != CapabilityHealth.READY) {
            workerCapability.transition(CapabilityHealth.READY, null);
            log.info("Knowledge Server recovered to READY state");
        } else if (!healthy && current == CapabilityHealth.READY) {
            // Tempdoc 837 §3.1: this branch is guarded on current == READY, so the worker WAS serving
            // and stopped answering — worker.lost, never worker.spawn.failed.
            transitionWorkerDown(LifecycleReasonCode.WORKER_LOST, "Health check failed");
            log.warn("Knowledge Server health check failed");
        }
        return healthy;
    }

    @Override
    public void close() {
        closeForUpgrade();
    }

    /** Ordered close that reports whether Worker process termination required force. */
    public ShutdownOutcome closeForUpgrade() {
        log.info("Shutting down Knowledge Server integration...");
        ShutdownOutcome outcome = ShutdownOutcome.GRACEFUL;

        // Stop the energy poll before the signal bus goes: its transitional MMF write would
        // otherwise race the unmap. The poller is restartable, and the last polled state survives,
        // so a boot-recovery restart resumes without a UNKNOWN window.
        try {
            energyPoller.close();
        } catch (Exception e) {
            log.warn("Error stopping energy-state poller", e);
        }

        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("Error closing client", e);
            }
            client = null;
        }

        if (workerHost != null) {
            // Lane F item A6: the index half is in this JVM, so "shutdown" is an ordered close, not
            // a process termination — GRACEFUL is the only outcome that can be reported honestly.
            try {
                workerHost.close();
            } catch (RuntimeException e) {
                log.warn("Error closing in-process worker host", e);
                outcome = ShutdownOutcome.FAILED;
            }
        }


        // Release app lock last (after we have stopped all components that might touch the data dir).
        if (appLock != null) {
            try {
                appLock.close();
            } catch (Exception e) {
                log.warn("Error releasing app lock", e);
            }
            appLock = null;
        }

        try {
            // Suppressed between boot attempts AND across boot-recovery cycles (tempdoc 825): a retry
            // would immediately re-enter PENDING, and the OFFLINE flap in between is narration of a
            // state the Head was never actually in.
            if (!narrationSuppressed()) {
                workerCapability.transition(
                    CapabilityHealth.OFFLINE,
                    LifecycleReasonCode.WORKER_SHUT_DOWN.code(),
                    "Worker shut down");
            }
        } finally {
            // Must clear even if a capability listener throws: a stranded started=true would make
            // the next start() throw "already started" and replace the real cause in the log.
            started.set(false);
            // Tempdoc 825 (charter item 4 / #439 review finding E): the generation counter outlived
            // the teardown it describes. A later start() on this same instance — which is now the
            // NORMAL path, not a hypothetical, because boot recovery re-starts this instance — would
            // take the generation>=1 "recovery" branch of completeReadyInitialization and skip
            // tryIngestHelpFiles for the whole process lifetime. close() drops the client, the
            // spawner and the signal bus; the generation describes that same connection, so it is
            // reset with them. Re-running help ingest is free: it is marker-file idempotent.
            initGeneration.set(0);
        }
        log.info("Knowledge Server integration shutdown complete");
        return outcome;
    }

    /** Version stamp for built-in help files. Bump when help content changes. */
    private static final String HELP_FILES_VERSION = "v2";

    /** Collection tag for built-in help documents. */
    private static final String HELP_COLLECTION = "justsearch-help";

    /**
     * Auto-ingests built-in help files if not already done for this version.
     *
     * <p>Uses a marker file in the data directory to track which version of
     * help files has been ingested, avoiding unnecessary re-ingestion on every startup.
     */
    // Package-private for unit tests (KnowledgeServerBootstrapEvalModeTest).
    // Not intended as a stable API surface.
    void tryIngestHelpFiles(KnowledgeClient client, KnowledgeServerConfig config) {
        try {
            // Skip help-file auto-ingest in eval mode so a "fresh" index truly starts empty.
            // The 5 bundled help docs would otherwise pollute baseline measurements
            // (precision, doc counts) with non-eval content. `justsearch.eval.mode` is the
            // same flag that gates `/api/debug/reset-index` (LocalApiServer) and is set by
            // the `runHeadlessEval` Gradle task.
            if (Boolean.getBoolean("justsearch.eval.mode")) {
                log.info("Skipping help-file auto-ingest (eval mode)");
                return;
            }

            Path marker = config.dataDir().resolve(".help-ingested-version");

            // Check if already ingested for this version
            if (Files.exists(marker)) {
                String ingested = Files.readString(marker).trim();
                if (HELP_FILES_VERSION.equals(ingested)) {
                    log.debug("Help files already ingested (version {})", ingested);
                    return;
                }
            }

            // Resolve help directory
            Path helpDir = resolveHelpDir(config);
            if (helpDir == null) {
                log.debug("Help files directory not found, skipping auto-ingestion");
                return;
            }

            // Collect .md files
            List<Path> helpFiles;
            try (Stream<Path> walk = Files.walk(helpDir, 1)) {
                helpFiles = walk
                        .filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".md"))
                        .toList();
            }

            if (helpFiles.isEmpty()) {
                log.debug("No help files found in {}", helpDir);
                return;
            }

            // Ingest with collection tag
            client.submitBatch(helpFiles, true, HELP_COLLECTION);
            Files.writeString(marker, HELP_FILES_VERSION);
            log.info("Ingested {} built-in help files (collection={})", helpFiles.size(), HELP_COLLECTION);

        } catch (Exception e) {
            // Non-fatal: help file ingestion failure should not block startup
            log.warn("Failed to ingest help files: {}", e.getMessage());
            log.debug("Failed to ingest help files (stack trace)", e);
        }
    }

    /**
     * Resolves the help files directory from the config's working directory.
     *
     * <p>The working directory is resolved by {@link KnowledgeServerConfig} using the
     * SSOT discovery logic, so this works in both development and production.
     */
    private Path resolveHelpDir(KnowledgeServerConfig config) {
        Path helpDir = config.workingDirectory().resolve("SSOT").resolve("docs").resolve("help");
        if (Files.isDirectory(helpDir)) {
            return helpDir;
        }
        return null;
    }

    /**
     * No-op telemetry marker for when real telemetry is not needed. Tempdoc 417 Phase 3e:
     * Telemetry is now an empty marker interface; no methods to override.
     */
    private static final class NoopTelemetry implements Telemetry {
        @Override
        public void close() {}
    }
}
