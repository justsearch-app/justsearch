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
 *   <li>Spawns worker process</li>
 *   <li>Connects gRPC client</li>
 *   <li>Provides health monitoring</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>{@code
 * KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap();
 * bootstrap.start();
 *
 * // Use the client
 * RemoteKnowledgeClient client = bootstrap.client();
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

    // Tempdoc 825 review F10: volatile. These three are written by whichever thread runs start() /
    // closeForUpgrade() — the boot thread, and (since 825) the health monitor's executor — and read
    // by the other: hasClient() is the monitor's arm discriminator, and the shutdown coordinator
    // calls closeForUpgrade() from the shutdown hook thread. Without volatile a reader can observe a
    // stale non-null spawner (double shutdown) or a stale null client (a needless recovery attempt
    // against a worker that is already up).
    private volatile MainSignalBus signalBus;
    private volatile WorkerSpawner spawner;
    private volatile RemoteKnowledgeClient client;

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
     * Tempdoc 630's OS energy-intent poll, moved off {@code WorkerSpawner} at item A5 because it has
     * to outlive the spawner (deleted at A11). Constructed eagerly and started with the integration,
     * so {@link #energyState()} answers UNKNOWN — not null — before the first poll.
     */
    private final io.justsearch.app.services.power.EnergyStatePoller energyPoller =
        new io.justsearch.app.services.power.EnergyStatePoller(
            gpuScheduling,
            reduced -> {
                MainSignalBus bus = signalBus;
                if (bus != null) {
                    bus.writeEnergyReduced(reduced);
                }
            });

    /** Tempdoc 630: epoch-ms of the most recent OS-resume handled, for the "Catching up" notice. */
    private final java.util.concurrent.atomic.AtomicLong lastResumeEpochMs =
        new java.util.concurrent.atomic.AtomicLong(0);
    /** How long after a resume the transient "Catching up after sleep" notice stays up. */
    private static final long RESUME_NOTICE_WINDOW_MS = 30_000;

    /** gRPC deadline for the FIRST PID-validation attempt; each further attempt doubles it. */
    private static final long PID_VALIDATION_FIRST_ATTEMPT_MS = 1_000;
    /** Budget below which a further PID-validation attempt is not worth issuing. */
    private static final long PID_VALIDATION_MIN_ATTEMPT_MS = 250;
    /** Budget below which stale-port recovery would overrun the window rather than rescue it. */
    private static final long PID_VALIDATION_MISMATCH_RECOVERY_FLOOR_MS = 1_500;

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

    /** Whether the last failed {@link #start()} left supervision holding the restart budget. */
    private volatile boolean supervisionEngagedOnLastAttempt;

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

    /**
     * Tempdoc 825 §D4: countdown for the prod-guarded boot fault injector. Each remaining count fails
     * one PID validation with the exact 821 §O.4 signature, then the injector stops — which is what
     * makes CONVERGENCE (not just the pin) reproducible. Zero unless
     * {@code justsearch.worker.boot.faultInjectAttempts} is set on a non-production config.
     */
    private final java.util.concurrent.atomic.AtomicInteger bootFaultCountdown;

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
        this.bootFaultCountdown =
            new java.util.concurrent.atomic.AtomicInteger(
                config != null ? config.bootFaultInjectAttempts() : 0);
    }

    /**
     * Starts the Knowledge Server integration.
     *
     * <p>Steps:
     * <ol>
     *   <li>Creates signal bus</li>
     *   <li>Starts worker spawner (which spawns the process)</li>
     *   <li>Connects gRPC client to discovered port</li>
     * </ol>
     *
     * @throws IOException if spawn or connection fails
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

            // 2. Create signal bus
            signalBus = new MainSignalBus(config.signalFilePath());

            // 3. Create and start worker spawner (with IPC telemetry)
            spawner = new WorkerSpawner(config, signalBus, ipcTelemetry);
            // Tempdoc 627: bridge the spawner's supervision lifecycle to the worker capability so a
            // recovery (RECOVERING) or terminal give-up (DEGRADED + worker.restart_exhausted) is
            // legible on /api/health. Mirrors the Brain's InferenceCapabilityWiring mode→capability
            // bridge. onRecovered is intentionally a no-op: the next health poll confirms READY, so we
            // never claim healthy before it is verified.
            spawner.setSupervisionEvents(new SupervisionEvents() {
                @Override
                public void onRecovering(String reason, RecoveryContext ctx) {
                    // Tempdoc 627 (N2): park the forensic context before the transition so the
                    // capability-health bridge (a synchronous transition listener) attaches it to the
                    // worker.restart-attempted occurrence.
                    workerCapability.setRecoveryContext(ctx);
                    // Tempdoc 837 S3: the supervisor's sentence is the DETAIL; the reason slot carries
                    // the code the /api/status RECOVERING arm already publishes.
                    workerCapability.transition(
                        CapabilityHealth.RECOVERING,
                        LifecycleReasonCode.WORKER_RECOVERING.code(),
                        reason);
                }

                @Override
                public void onGaveUp(String reason) {
                    workerCapability.transition(
                        CapabilityHealth.DEGRADED,
                        LifecycleReasonCode.WORKER_RESTART_EXHAUSTED.code(),
                        reason);
                }
            });
            int port = spawner.start();

            // 3b. Start the OS energy-intent poll (tempdoc 630). It used to be step 5b inside
            // spawner.start(); item A5 moved it here because it must outlive the spawner (A11).
            // Started right after the spawner so the signal bus is open and the transitional MMF
            // write in the sink lands on the first poll, exactly as it did before the move.
            energyPoller.start();

            // 4. Create circuit breaker for gRPC failure handling
            GrpcCircuitBreaker circuitBreaker = new GrpcCircuitBreaker(ipcTelemetry);

            // 5. Create and connect client (with circuit breaker and telemetry)
            client = new RemoteKnowledgeClient(signalBus, config.deadlineMs(), config.maxRetries(), config.batchSize(), circuitBreaker, ipcTelemetry);
            client.connect(port);

            // 5.5. Validate that the connected port belongs to our spawned worker PID
            long expectedPid = spawner.getWorkerPid();
            validateWorkerPid(expectedPid, config.pidValidationTimeoutMs());

            // 5.6. Check for Head→Worker config divergence (tempdoc 329)
            checkConfigDivergence();

            // 6. Verify health with bounded retry (Tempdoc 374 alpha.23 R13-A defect #1).
            // Round-13 cycle 2 caught the worker still warming up Lucene SearcherManager.
            // Pre-fix: a single isHealthy() call straddled the warmup, transitioned to ERROR,
            // and steps 7-9 never ran. Post-fix: poll for up to healthCheckRetryBudgetMs.
            // If the budget elapses without success, KnowledgeServerHealthMonitor takes over.
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
                    log.info("Knowledge Server became healthy after {}ms of warmup polling on port {}",
                            healthCheckElapsedMs, port);
                } else {
                    log.info("Knowledge Server is READY on port {}", port);
                }
                completeReadyInitialization();
            } else {
                // Tempdoc 837 §3.1: the start-time health budget elapsed — the worker NEVER started.
                // Review F7: this site is reachable DURING a recovery arc — the attempt's worker
                // spawns and answers gRPC but never reaches healthy — and it was the one worker-down
                // site without a suppression guard. The rule now lives in transitionWorkerDown, so
                // this call is unconditional and the funnel decides.
                transitionWorkerDown(
                    LifecycleReasonCode.WORKER_SPAWN_FAILED,
                    "Health check failed after " + healthCheckElapsedMs + "ms");
                log.warn("Knowledge Server health check failed after {}ms budget; auxiliary services not initialized — background monitor will retry",
                        healthCheckElapsedMs);
            }

        } catch (Exception e) {
            // Read supervision's verdict BEFORE close() drops the spawner that holds it.
            supervisionEngagedOnLastAttempt = spawner != null && spawner.supervisionEngaged();
            transitionWorkerDown(
                LifecycleReasonCode.WORKER_SPAWN_FAILED, "Start failed: " + e.getMessage());
            log.error("Failed to start Knowledge Server integration", e);
            close();
            throw e;
        }
    }

    /**
     * Starts with a bounded retry, so a transient boot-time timing failure is not terminal.
     *
     * <p>{@link #start()} tears itself down via {@link #close()} on failure — which resets
     * {@code started} and drops the spawner, client and signal bus — so a subsequent attempt
     * respawns from a clean slate rather than leaking the previous worker. Only failures
     * {@link WorkerStartFailures#isTransient(Throwable) classified as transient} are retried, and
     * only while {@link WorkerSpawner#supervisionEngaged() supervision has not engaged}; everything
     * else propagates from the first attempt with its original error.
     */
    public void startWithRetry() throws IOException, InterruptedException {
        startWithRetry(DEFAULT_START_ATTEMPTS, DEFAULT_START_RETRY_BACKOFF_MS);
    }

    /** {@link #startWithRetry()} with an explicit attempt budget; visible for tests. */
    public void startWithRetry(int maxAttempts, long backoffMs)
            throws IOException, InterruptedException {
        int[] attemptNo = {0};
        supervisionEngagedOnLastAttempt = false;
        try {
            WorkerStartFailures.startWithRetry(
                () -> {
                    retryPending = ++attemptNo[0] < maxAttempts;
                    start();
                },
                maxAttempts,
                backoffMs,
                () -> supervisionEngagedOnLastAttempt);
        } catch (Exception e) {
            // The per-attempt narration was suppressed; the final verdict lands exactly once, here —
            // unless the boot-recovery arm owns this arc's narration (tempdoc 825), in which case the
            // verdict is ITS terminal give-up, not a per-cycle spawn-failed pin.
            retryPending = false;
            // transitionWorkerDown is the one funnel: it owns both the arc-suppression rule (F7) and
            // the "never overwrite supervision's terminal verdict" rule (F1).
            transitionWorkerDown(
                LifecycleReasonCode.WORKER_SPAWN_FAILED, "Start failed: " + e.getMessage());
            throw e;
        } finally {
            retryPending = false;
        }
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
     * Tempdoc 825 (§D2 mechanism 1, corrected by review F2): whether a supervisor is alive RIGHT NOW
     * and holding the restart budget — the honest cross-cycle form of the veto.
     *
     * <p>The obvious signal, {@code supervisionEngagedOnLastAttempt}, is the wrong one here.
     * {@link WorkerSpawner#supervisionEngaged()} latches on {@code restartCount > 0}, so it means "a
     * supervised restart happened at some point during that attempt", not "supervision is working on
     * it". That field is also never cleared until the next {@link #startWithRetry}, so a boot-recovery
     * arm gated on it would stand down forever and never run the very attempt that clears it — the
     * bricked-boot class would get zero attempts and no terminal code at all. It stays private, doing
     * the job it was written for: bounding ONE {@code startWithRetry} call.
     *
     * <p>This asks the live question instead. After a failed start, {@link #close()} has dropped the
     * spawner, so no supervisor exists and the restart budget is nobody's — recovery may proceed.
     * While a spawner IS alive and has restarted the worker, supervision owns the arc and this
     * authority yields the cycle (never permanently: the next tick re-asks).
     */
    public boolean supervisionActive() {
        WorkerSpawner s = spawner;
        return s != null && s.supervisionEngaged();
    }

    /**
     * Tempdoc 825: whether a gRPC client is bound. This is the discriminator between the health
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
     * Validates that the connected gRPC port belongs to the expected worker PID.
     *
     * <p>This prevents connecting to a stale/zombie process that wrote its port
     * between zeroPort() and the new worker starting.
     *
     * <p>Each attempt gets its own gRPC deadline, escalating from
     * {@link #PID_VALIDATION_FIRST_ATTEMPT_MS} and always clamped to the budget left. Before that,
     * every attempt inherited the STANDARD RPC deadline, which equals the whole validation window by
     * default — so one slow cold health check (the worker-side check runs live SQLite and Lucene
     * queries, expensive on first contact) consumed the entire budget and this loop never got a
     * second iteration. That turned a warm-up race into a permanently worker-less Head.
     *
     * @param expectedPid the PID of the spawned worker process
     * @param timeoutMs maximum time to retry PID validation
     * @throws PidValidationTimeoutException if PID validation fails after timeout
     */
    private void validateWorkerPid(long expectedPid, long timeoutMs) throws InterruptedException {
        int remainingFaults = bootFaultCountdown.getAndUpdate(n -> n > 0 ? n - 1 : 0);
        if (remainingFaults > 0) {
            // Tempdoc 825 §D4: the countdown fault injector. Fails PID validation with the exact
            // 821 §O.4 signature (PidValidationTimeoutException — transient, so the boot retry and
            // the recovery arm both engage) for the first N attempts and then stops, so a test can
            // prove CONVERGENCE and not merely the pin. The guard is exactly this: the config's
            // compact constructor zeroes the count whenever isProduction() is true — which is a
            // DETECTED property (explicit justsearch.prod / JUSTSEARCH_PROD flag, or a bundled-JRE
            // layout), not a build-time constant, so a shipped build launched with the flag forced
            // to false can still arm it. That is the same reach every other dev-only sysprop in
            // KnowledgeServerConfig has; it is not a security boundary.
            log.warn(
                "Injecting boot fault (justsearch.worker.boot.faultInjectAttempts): {} injected"
                    + " failure(s) remaining after this one",
                remainingFaults - 1);
            throw new PidValidationTimeoutException(
                "Injected boot fault (justsearch.worker.boot.faultInjectAttempts): expected PID "
                    + expectedPid);
        }
        awaitWorkerPid(
                expectedPid,
                timeoutMs,
                attemptDeadlineMs -> client.getHealthCheck(attemptDeadlineMs).getPid(),
                remainingMs -> {
                    ipcTelemetry.recordPidMismatch();
                    // The reconnect is NOT covered by the per-attempt gRPC deadline: connect() closes
                    // the old channel with its own 5s awaitTermination, so entering this arm on a
                    // nearly-spent budget overruns the window for a recovery that cannot land anyway.
                    if (remainingMs < PID_VALIDATION_MISMATCH_RECOVERY_FLOOR_MS) {
                        log.warn("Skipping stale-port recovery: only {}ms of the validation window left",
                                remainingMs);
                        return;
                    }
                    // Zero the stale port and wait for the new worker to write its port.
                    signalBus.zeroPort();
                    Thread.sleep(100);
                    long awaitTimeout = Math.min(1000, Math.max(100, remainingMs / 2));
                    client.connect(signalBus.awaitPort(awaitTimeout, 100));
                });
    }

    /** One PID-validation attempt, honouring a per-attempt gRPC deadline. */
    @FunctionalInterface
    interface PidProbe {
        long reportedPid(long attemptDeadlineMs) throws Exception;
    }

    /** Re-points the client at a freshly spawned worker after a stale-port PID mismatch. */
    @FunctionalInterface
    interface StalePortRecovery {
        void reconnect(long remainingMs) throws Exception;
    }

    /**
     * The PID-validation retry loop, decoupled from its collaborators so the attempt schedule is
     * directly testable. Package-private for {@code WorkerPidValidationTest}.
     */
    static void awaitWorkerPid(
            long expectedPid, long timeoutMs, PidProbe probe, StalePortRecovery onMismatch)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long attemptBudgetMs = PID_VALIDATION_FIRST_ATTEMPT_MS;
        int retryCount = 0;

        while (true) {
            long remainingMs = deadline - System.currentTimeMillis();
            if (remainingMs < PID_VALIDATION_MIN_ATTEMPT_MS) {
                break;
            }
            long attemptDeadlineMs = Math.min(remainingMs, attemptBudgetMs);
            attemptBudgetMs = Math.min(attemptBudgetMs * 2, Math.max(timeoutMs, 1));
            try {
                long actualPid = probe.reportedPid(attemptDeadlineMs);

                if (actualPid == expectedPid) {
                    log.info("Worker PID validated: {}", actualPid);
                    return;
                }

                // PID mismatch - likely stale port from zombie process
                log.warn("PID mismatch: expected {}, got {} (retry {})", expectedPid, actualPid, ++retryCount);
                onMismatch.reconnect(deadline - System.currentTimeMillis());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.debug("PID validation attempt failed: {}", e.getMessage());
                Thread.sleep(100);
            }
        }

        throw new PidValidationTimeoutException(
                "PID validation timeout after " + timeoutMs + "ms: expected PID " + expectedPid);
    }

    /**
     * Compares critical config values between Head and Worker, logging WARN on divergence.
     *
     * <p>This turns silent misconfiguration (tempdoc 312 item 20) into a visible signal.
     * Best-effort: failures are logged but do not block startup.
     */
    private void checkConfigDivergence() {
        try {
            HealthCheckResponse response = client.getHealthCheck();
            Map<String, String> workerConfig = response.getEffectiveConfigMap();
            if (workerConfig.isEmpty()) {
                log.debug("Worker did not report effective config (older version?)");
                return;
            }

            int mismatches = 0;
            for (EnvRegistry key : EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS) {
                String headValue = key.get().orElse("");
                String workerValue = workerConfig.getOrDefault(key.sysProp(), "");
                if (!headValue.equals(workerValue)) {
                    log.warn("Config divergence [{}]: head='{}', worker='{}'",
                            key.sysProp(), headValue, workerValue);
                    mismatches++;
                }
            }
            if (mismatches == 0) {
                log.info("Head→Worker config check passed ({} keys verified)",
                        EnvRegistry.CONFIG_DIVERGENCE_CHECK_KEYS.size());
            } else {
                log.warn("Head→Worker config divergence detected: {} key(s) differ. "
                        + "Check env var / system property forwarding.", mismatches);
            }
        } catch (Exception e) {
            log.debug("Config divergence check failed (non-fatal): {}", e.getMessage());
        }
    }

    /**
     * Returns the gRPC client for Knowledge Server operations.
     *
     * @throws IllegalStateException if not started or not ready
     */
    public RemoteKnowledgeClient client() {
        if (client == null) {
            throw new IllegalStateException("Knowledge Server not started");
        }
        return client;
    }

    /**
     * Returns the worker spawner for process management.
     */
    public WorkerSpawner spawner() {
        return spawner;
    }

    /**
     * Returns the signal bus for inter-process coordination.
     *
     * <p>Used by InferenceLifecycleManager to broadcast GPU status changes
     * to the Worker process.
     *
     * @return the MainSignalBus, or null if not started
     */
    public MainSignalBus signalBus() {
        return signalBus;
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
     * worker.log, because nothing wrote the fatal-reason marker on the refusal path.
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
        // Tempdoc 627: feed each poll into the spawner's hang detector. A sustained-unhealthy streak on
        // a still-alive worker (the "liveness" signal) triggers a budgeted graceful restart — closing
        // the Worker's observation→actuation loop. This is the only wiring the health monitor needs.
        if (spawner != null) {
            spawner.recordHealthResult(healthy);
        }
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
    public WorkerSpawner.ShutdownOutcome closeForUpgrade() {
        log.info("Shutting down Knowledge Server integration...");
        WorkerSpawner.ShutdownOutcome outcome = WorkerSpawner.ShutdownOutcome.GRACEFUL;

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

        if (spawner != null) {
            try {
                outcome = spawner.shutdownForUpgrade();
            } catch (Exception e) {
                log.warn("Error closing spawner", e);
                outcome = WorkerSpawner.ShutdownOutcome.FAILED;
            }
            spawner = null;
        }

        // Signal bus is closed by spawner, but ensure cleanup
        signalBus = null;

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
    void tryIngestHelpFiles(RemoteKnowledgeClient client, KnowledgeServerConfig config) {
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
