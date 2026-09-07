/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.app.util.WindowsJobObject;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages the Knowledge Worker process lifecycle.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Zero MMF port before spawn</li>
 *   <li>Spawn worker with Dev/Prod profile detection</li>
 *   <li>Monitor worker process health</li>
 *   <li>Write heartbeat every 1s (suicide pact)</li>
 *   <li>Restart worker on unexpected death</li>
 * </ul>
 */
public final class WorkerSpawner implements Closeable {
    public enum ShutdownOutcome {
        GRACEFUL,
        FORCED,
        FAILED
    }

    private static final Logger log = LoggerFactory.getLogger(WorkerSpawner.class);

    private static final long HEARTBEAT_INTERVAL_MS = 1000;
    private static final long PORT_POLL_INTERVAL_MS = 100;
    // Restart cap / backoff / stability constants now live in SupervisionPolicy (tempdoc 627) so the
    // pure SupervisionDecision authority owns the recovery policy. See SupervisionPolicy.DEFAULT_*.

    /**
     * Single source of truth for system properties forwarded from Head to Worker via {@code -D}
     * flags. Add new worker-relevant properties here — one place, not scattered calls.
     *
     * <p>These are properties that must be visible as JVM system properties in the Worker process,
     * either because they are read via {@link EnvRegistry#get()} before the config snapshot is
     * loaded, or because they are consumed by non-application code (ORT JNI, Netty).
     *
     * <p>Most config values reach the Worker through two other channels that do NOT require entries
     * here: (1) blanket {@code JUSTSEARCH_*} env var forwarding via {@code ProcessBuilder}, and
     * (2) the config snapshot written by HeadlessApp at ordinal 450. Only add entries here for
     * properties that cannot rely on those channels.
     *
     * @see <a href="docs/tempdocs/329-head-worker-config-pipeline.md">Tempdoc 329</a>
     */
    static final Set<EnvRegistry> WORKER_FORWARDED_PROPS = EnumSet.of(
        // --- Bootstrap: read by configuration module before snapshot is fully applied ---
        EnvRegistry.CONFIG_PATH,       // YAML config location (ResolvedConfigBuilder)
        EnvRegistry.REPO_ROOT,         // repo root (RepoRootLocator via EnvRegistry.get())
        EnvRegistry.SSOT_PATH,         // SSOT path (RepoRootLocator via EnvRegistry.get())

        // --- GPU / policy: read via EnvRegistry.get() by subsystem config classes ---
        EnvRegistry.GPU_ENABLED,       // master ONNX GPU switch (tempdoc 337)
        EnvRegistry.GPU_LAYERS,        // global GPU layer count
        // Tempdoc 374 alpha.16 fix D root-cause: per-encoder gpu.enabled flags must
        // be forwarded to the worker subprocess. Without this, sysprops written by
        // HeadlessApp's boot-time defensive mirror (or by AiInstallService's Install
        // AI follow-up) at the head process don't propagate. The worker's
        // resolveEmbedGpuEnabled fallback chain LOOKS correct on static read + unit
        // tests, but the round-6 sandbox agent measured embed/splade/ner gpuEnabled=
        // false at the worker despite master=true in the snapshot. Forwarding the
        // per-feature keys via -D args at ordinal 500 makes the value explicit and
        // bypasses whatever resolution path was dropping the master fallback at the
        // worker. The agent's env-var workaround (JUSTSEARCH_EMBED_GPU_ENABLED=true)
        // confirmed the worker DOES read these keys via env-var path; the gap was
        // purely sysprop-vs-env propagation.
        EnvRegistry.EMBED_GPU_ENABLED,
        EnvRegistry.SPLADE_GPU_ENABLED,
        EnvRegistry.NER_GPU_ENABLED,
        // Operator escape, forwarded so the Worker honours it when a person sets it. Nothing sets
        // it by default any more (tempdoc 915 §C deleted the Head's two unconditional set-sites).
        EnvRegistry.INDEX_PARITY_ALLOW_MISMATCH,

        // --- Index policy: forwarded for belt-and-suspenders safety ---
        EnvRegistry.INDEX_BASE_PATH,   // index base path override from UI settings
        EnvRegistry.EMBED_ONNX_MODEL_PATH, // embedding model path override (312 Phase 4)
        // Tempdoc 374 alpha.19 Bug J-1: forward the four other per-encoder model_path
        // keys. AiInstallService.applyOnnxSettings writes head sysprops for all five
        // encoders (embedding, splade, ner, reranker, citation-scorer) post-Install-AI;
        // pre-alpha.19 only EMBED_ONNX_MODEL_PATH was in this list, so the others were
        // dropped on the floor between head and worker. Round-9 default-flow validation:
        // SPLADE/NER/reranker silently disabled because the worker saw modelPath=null
        // and fell to OnnxModelDiscovery which couldn't find the GPU_FULL fp16-only
        // layout. Forwarding here makes the explicit-path branch win at ordinal 500.
        EnvRegistry.SPLADE_MODEL_PATH,
        EnvRegistry.NER_MODEL_PATH,
        EnvRegistry.RERANK_MODEL_PATH,
        EnvRegistry.CITATION_SCORER_MODEL_PATH,

        // --- ORT native path: consumed by ORT JNI at native library load time ---
        EnvRegistry.ORT_NATIVE_PATH,   // first-class ORT native path

        // --- Migration: forwarded for belt-and-suspenders safety ---
        // POLICY_GPU_ACCELERATION_ENABLED removed (347): EnterprisePolicyService writes
        // System.setProperty() with its computed value, which would override the operator's
        // env var at ordinal 500. The Worker gets the correct value from the config snapshot
        // (ordinal 450) and blanket JUSTSEARCH_* env var forwarding (ordinal 400).
        EnvRegistry.INDEX_MIGRATION_CUTOVER_MAX_FAILED_JOBS,
        EnvRegistry.INDEX_SCHEMA_MISMATCH_POLICY,

        // --- Worker bootstrap: set by HeadlessApp at runtime ---
        EnvRegistry.WORKER_CONFIG_SNAPSHOT
    );

    private final KnowledgeServerConfig config;
    private final MainSignalBus signalBus;
    private final IpcTelemetry telemetry;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Process> workerProcess = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean manualRestarting = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final AtomicInteger discoveredPort = new AtomicInteger(0);
    /** Timestamp (epoch ms) of last successful worker start/restart for stability tracking. */
    private final AtomicLong lastSuccessfulStartTime = new AtomicLong(0);
    /** Consecutive gRPC-unhealthy polls while the process is alive (hang detection, tempdoc 627). */
    private final AtomicInteger consecutiveUnhealthy = new AtomicInteger(0);
    /** Set once {@link SupervisionDecision.Action#GIVE_UP} fired — supervision's terminal verdict. */
    private final AtomicBoolean supervisionGaveUp = new AtomicBoolean(false);
    /** Serializes the death-path and hang-path supervision onto one shared restart budget. */
    private final java.util.concurrent.locks.ReentrantLock superviseLock =
        new java.util.concurrent.locks.ReentrantLock();
    /** Declared recovery policy (cap / backoff / stability window / hang threshold). */
    private final SupervisionPolicy supervisionPolicy;
    /** Supervision lifecycle callback, bridged to WorkerCapability by the bootstrap (mirrors Brain). */
    private volatile SupervisionEvents supervisionEvents = SupervisionEvents.NOOP;

    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> monitorTask;
    private WindowsJobObject jobObject;

    /**
     * Creates a WorkerSpawner with no-op telemetry.
     * Use {@link #WorkerSpawner(KnowledgeServerConfig, MainSignalBus, IpcTelemetry)} for instrumentation.
     */
    public WorkerSpawner(KnowledgeServerConfig config, MainSignalBus signalBus) {
        this(config, signalBus, IpcTelemetry.noop());
    }

    /**
     * Creates a WorkerSpawner with the specified telemetry for IPC metrics.
     *
     * @param config the knowledge server configuration
     * @param signalBus the signal bus for inter-process coordination
     * @param telemetry the IPC telemetry instance for metrics recording
     */
    public WorkerSpawner(KnowledgeServerConfig config, MainSignalBus signalBus, IpcTelemetry telemetry) {
        this.config = config;
        this.signalBus = signalBus;
        this.telemetry = telemetry != null ? telemetry : IpcTelemetry.noop();
        this.supervisionPolicy = SupervisionPolicy.from(config);
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "worker-spawner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the worker process and begins heartbeat/monitoring.
     *
     * @return the discovered gRPC port
     * @throws IOException if worker spawn fails
     * @throws InterruptedException if port discovery is interrupted
     */
    public int start() throws IOException, InterruptedException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("WorkerSpawner already running");
        }

        // 0. Create Job Object for crash-safe process containment (Windows only)
        jobObject = WindowsJobObject.createOrNull();

        // 1. Open signal bus
        signalBus.open();

        // 2. Zero port and clear shutdown before spawn
        signalBus.zeroPort();
        signalBus.clearShutdown();

        // 3. Write initial heartbeat
        signalBus.writeHeartbeat();

        // 4. Spawn worker process
        spawnWorker();

        // 5. Start heartbeat thread
        heartbeatTask = scheduler.scheduleAtFixedRate(
                this::writeHeartbeat,
                HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS,
                TimeUnit.MILLISECONDS);

        // 5b. (tempdoc 630) The OS energy-intent poll used to live here. Lane F item A5 moved it to
        // EnergyStatePoller, owned by KnowledgeServerBootstrap: it publishes the in-process
        // GpuSchedulingGauge and (transitionally) the same MMF byte, and it must outlive this class,
        // which item A11 deletes.

        // 6. Await port discovery (with telemetry timing)
        int port;
        try (var sample = telemetry.startPortDiscovery()) { // NOPMD - telemetry timing
            port = signalBus.awaitPort(
                    config.portDiscoveryTimeoutMs(), PORT_POLL_INTERVAL_MS, workerProcess.get());
        } catch (IllegalStateException e) {
            // Port discovery timeout or early crash detection
            telemetry.recordPortDiscoveryTimeout();
            throw e;
        }
        discoveredPort.set(port);
        lastSuccessfulStartTime.set(System.currentTimeMillis());

        // 7. Start process monitor
        monitorTask = scheduler.scheduleWithFixedDelay(
                this::checkWorkerHealth,
                1000,
                1000,
                TimeUnit.MILLISECONDS);

        log.info("Worker started on port {}", port);
        return port;
    }

    /**
     * Restarts the worker process and waits for port re-discovery.
     *
     * <p>This is used for explicit "apply" flows (e.g., embedding config changes) where we need the
     * new environment to take effect without restarting the whole backend.
     *
     * @return the (re)discovered gRPC port
     */
    public int restart() throws IOException, InterruptedException {
        if (!running.get()) {
            throw new IllegalStateException("WorkerSpawner not running");
        }

        manualRestarting.set(true);
        try {
            // Stop any running worker process via the graceful signal-bus path. Tempdoc 627: a bare
            // Process.destroy() is a hard TerminateProcess on Windows (no flush), risking a dirty
            // Lucene index on a mid-commit worker. writeShutdown() lets the worker commit+close first
            // (commitOnClose default), then we force only on timeout. This closes the same hazard for
            // every restart() caller (config-apply, AI install/pack), not just supervised recovery.
            Process p = workerProcess.get();
            if (p != null && p.isAlive()) {
                log.info("Restarting worker (PID: {})...", p.pid());
                stopProcess(p, true);
            }

            // Clear port and re-spawn
            signalBus.zeroPort();
            signalBus.clearShutdown();
            signalBus.writeHeartbeat();
            spawnWorker();

            int port = signalBus.awaitPort(
                    config.portDiscoveryTimeoutMs(), PORT_POLL_INTERVAL_MS, workerProcess.get());
            discoveredPort.set(port);
            log.info("Worker restarted on port {}", port);
            return port;
        } finally {
            manualRestarting.set(false);
        }
    }

    /**
     * Returns the currently discovered port, or 0 if not yet discovered.
     */
    public int getPort() {
        return discoveredPort.get();
    }

    /**
     * Returns true if the worker process is running.
     */
    public boolean isRunning() {
        Process p = workerProcess.get();
        return p != null && p.isAlive();
    }

    /**
     * Returns the worker process PID, or -1 if not running.
     */
    public long getWorkerPid() {
        Process p = workerProcess.get();
        return p != null ? p.pid() : -1;
    }

    /**
     * Whether {@link SupervisionPolicy} has taken over this spawner's process — at least one
     * supervised restart has run, or supervision has given up terminally.
     *
     * <p>The restart budget belongs to the supervisor. A caller that owns a retry loop of its own
     * (boot) must stand down once this is true, otherwise it multiplies the declared restart
     * intensity and papers over the terminal {@code WORKER_RESTART_EXHAUSTED} verdict.
     */
    public boolean supervisionEngaged() {
        return restartCount.get() > 0 || supervisionGaveUp.get();
    }

    /**
     * Installs the supervision lifecycle callback (tempdoc 627). The bootstrap bridges these events to
     * {@code WorkerCapability} transitions so a recovery/give-up is legible on {@code /api/health}.
     * Mirrors the Brain's {@code goOfflineFromMaxCrashes} callback installation.
     */
    public void setSupervisionEvents(SupervisionEvents events) {
        this.supervisionEvents = events != null ? events : SupervisionEvents.NOOP;
    }

    /**
     * Feeds a gRPC-health poll result into the hang detector (tempdoc 627). Called by the health
     * monitor each poll. A healthy result resets the streak; a sustained-unhealthy streak on a still-
     * alive process (the "hang" liveness signal) triggers a budgeted graceful restart — closing the
     * Worker's observation→actuation loop. A dead process is left to the 1s death monitor so the two
     * paths do not double-count against the shared restart budget.
     */
    public void recordHealthResult(boolean healthy) {
        if (!running.get() || manualRestarting.get()) {
            return;
        }
        if (healthy) {
            consecutiveUnhealthy.set(0);
            return;
        }
        Process p = workerProcess.get();
        if (p == null || !p.isAlive()) {
            return; // death path owns this; do not inflate the hang streak
        }
        int n = consecutiveUnhealthy.incrementAndGet();
        if (n >= supervisionPolicy.hangUnhealthyThreshold()) {
            superviseTick(true);
        }
    }

    private void spawnWorker() throws IOException {
        List<String> command = buildCommand();
        log.info("Spawning worker: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);

        // Redirect output to log file instead of inheritIO to capture output in Gradle environment
        try {
            Path logDir = config.dataDir().resolve("logs");
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            Path logFile = logDir.resolve("worker.log");
            // Tempdoc 374 alpha.13 fix P2: rotate the prior worker boot's log so
            // post-mortem evidence survives a single restart. Pre-alpha.13 the
            // file was append-only, so a crashed worker's log was silently
            // overwritten by the next boot's startup banner. Mirrors the
            // alpha.12 lib.rs rotation policy (one extra generation kept).
            if (Files.exists(logFile)) {
                Path log1 = logDir.resolve("worker.log.1");
                Path log2 = logDir.resolve("worker.log.2");
                try {
                    if (Files.exists(log1)) {
                        Files.deleteIfExists(log2);
                        Files.move(log1, log2);
                    }
                    Files.move(logFile, log1);
                } catch (IOException e) {
                    log.debug("worker.log rotation failed (best-effort): {}", e.getMessage());
                }
            }
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            log.info("=======================================================");
            log.info("  Worker log path: {}", logFile);
            log.info("=======================================================");
        } catch (IOException e) {
            log.warn("Failed to setup worker log redirection, falling back to inheritIO", e);
            pb.inheritIO();
        }

        pb.directory(config.workingDirectory().toFile());

        // Set environment variables
        pb.environment().put("JUSTSEARCH_DATA_DIR", config.dataDir().toString());

        // Forward all JUSTSEARCH_* environment variables to worker process.
        // This ensures new features (SPLADE, future subsystems) don't silently
        // fail because their env vars weren't added to a hardcoded allowlist.
        for (var entry : System.getenv().entrySet()) {
            if (entry.getKey().startsWith("JUSTSEARCH_")
                    && !entry.getKey().equals("JUSTSEARCH_DATA_DIR")) {
                pb.environment().put(entry.getKey(), entry.getValue());
                log.debug("Forwarding environment variable {}={}", entry.getKey(), entry.getValue());
            }
        }
        Process process = pb.start();
        workerProcess.set(process);
        log.info("Worker process started with PID: {}", process.pid());

        // Assign to Job Object so OS kills worker if Head crashes
        if (jobObject != null) {
            jobObject.assign(process.pid());
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();

        // Tempdoc 696: the Worker's java binary is ALWAYS the running (Head) JVM's own runtime
        // (java.home), never a bare `java` PATH lookup. A bare lookup resolves to whatever JDK is first
        // on PATH, which can be an incompatible JDK 8 (e.g. a scoop temurin8 shim) — that would crash the
        // JDK-25 Worker classes with UnsupportedClassVersionError. The dev-runner launches the Head under
        // the resolved >=24 JDK (scripts/dev/lib/resolve-jdk.cjs → JAVA_HOME → ui.bat), so java.home here
        // is that JDK deterministically, independent of PATH ordering.
        cmd.add(workerJavaBinary(isWindows()));

        if (config.isProduction()) {
            // PRODUCTION: bundled-JLink AOT cache (JEP 514 — built at compile time, bundled in aot/).
            Path aotPath = config.libDir().resolve("../aot/worker.aot").normalize();
            if (Files.exists(aotPath)) {
                cmd.add("-XX:AOTCache=" + aotPath.toAbsolutePath());
            }
        } else {
            // DEVELOPMENT: dev-mode AOT cache (generated by: ./gradlew generateDevWorkerAotCache).
            // Note: -Xshare:auto is the JVM default since JDK 12, no need to specify explicitly.
            Path devAotPath = config.workingDirectory()
                .resolve("modules").resolve("ui").resolve("build")
                .resolve("aot-dev").resolve("worker").resolve("worker.aot");
            if (Files.exists(devAotPath)) {
                cmd.add("-XX:AOTCache=" + devAotPath.toAbsolutePath());
                log.info("Using dev AOT cache for Worker: {}", devAotPath);
            }
        }

        // Common JVM flags
        // Set -Xms = -Xmx to eliminate GC heap-resize pauses during startup.
        String maxHeapStr = config.workerHeapSize();
        cmd.add("-Xms" + maxHeapStr);
        cmd.add("-Xmx" + maxHeapStr);
        cmd.add("-Dfile.encoding=UTF-8");

        // Allow custom JVM options via environment variable (e.g., GC logging, profiling)
        String customJvmOpts = System.getenv("JUSTSEARCH_JVM_OPTS");
        if (customJvmOpts != null && !customJvmOpts.isBlank()) {
            for (String opt : customJvmOpts.trim().split("\\s+")) {
                if (!opt.isBlank()) {
                    cmd.add(opt);
                }
            }
            log.info("Added custom JVM options from JUSTSEARCH_JVM_OPTS: {}", customJvmOpts);
        }

        // Dev hot-reload support (Phase 2 — tempdoc 305). Returns the classes dir when hot reload
        // is on (null otherwise), because it also has to go on the classpath below.
        Path devHotReloadClassesDir = addDevHotReloadFlags(cmd);

        // Add signal bus path
        cmd.add("-Djustsearch.worker.signal_path=" + signalBus.signalPath().toAbsolutePath());

        // Add data directory
        cmd.add("-Djustsearch.data.dir=" + config.dataDir().toAbsolutePath());

        // Tempdoc 630: forward THIS (Head) process's PID so the Worker can probe Head liveness and
        // distinguish a real Head death from a benign OS-resume stale heartbeat. Computed live from
        // the spawning Head process — robust and exact, so no sysprop round-trip is needed (the
        // HARDCODED_FORWARDED path, like DATA_DIR above). The Worker reads it via
        // EnvRegistry.HEAD_PID at signal-bus init; absent ⇒ heartbeat-only (pre-630) suicide.
        cmd.add("-D" + EnvRegistry.HEAD_PID.sysProp() + "=" + ProcessHandle.current().pid());

        // Forward EnvRegistry-declared properties (single source of truth: WORKER_FORWARDED_PROPS).
        // Uses EnvRegistry.get() which checks sysprop THEN env var — either channel works.
        for (EnvRegistry key : WORKER_FORWARDED_PROPS) {
            key.get().ifPresent(value -> {
                cmd.add("-D" + key.sysProp() + "=" + value);
                log.debug("Forwarding config {}={}", key.sysProp(), value);
            });
        }

        // ORT native path best-effort derivation.
        // If the canonical key is already set (via sysprop or env var), skip derivation entirely.
        // The env var flows to the Worker via blanket JUSTSEARCH_* forwarding (ordinal 400); injecting
        // a derived sysprop (ordinal 500) on top would override the operator's explicit env var.
        if (EnvRegistry.ORT_NATIVE_PATH.get().isEmpty()) {
            // Try JUSTSEARCH_NATIVE_PATH env hint (deprecated — prefer JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH)
            String hintedOrtPath = resolveOnnxRuntimeNativePathHint(System.getenv("JUSTSEARCH_NATIVE_PATH"));
            if (hintedOrtPath != null && !hintedOrtPath.isBlank()) {
                cmd.add("-Djustsearch.onnxruntime.native_path=" + hintedOrtPath);
                log.warn("Derived ORT native path from deprecated JUSTSEARCH_NATIVE_PATH={}. "
                    + "Migrate to JUSTSEARCH_ONNXRUNTIME_NATIVE_PATH.", hintedOrtPath);
            } else {
                // Best-effort: derive from llama-server variant ID
                String derivedOrtPath = resolveOnnxRuntimeNativePathBestEffort();
                if (derivedOrtPath != null && !derivedOrtPath.isBlank()) {
                    cmd.add("-Djustsearch.onnxruntime.native_path=" + derivedOrtPath);
                    log.info("Setting worker justsearch.onnxruntime.native_path={} (derived from variant)", derivedOrtPath);
                }
            }
        }
        // Legacy key forwarding (backwards compatibility — remove in a future release).
        String legacyOrtPath = System.getProperty("onnxruntime.native.path");
        if (legacyOrtPath != null && !legacyOrtPath.isBlank()) {
            cmd.add("-Donnxruntime.native.path=" + legacyOrtPath);
            log.debug("Forwarding legacy ORT path onnxruntime.native.path={}", legacyOrtPath);
        }

        // Bypass Netty's DefaultChannelId NetworkInterface enumeration (100-500ms on machines
        // with virtual adapters: Hyper-V, VPN, WSL2, Docker). Safe for loopback-only IPC.
        // See: https://github.com/netty/netty/issues/2331
        cmd.add("-Dio.netty.machineId=00:00:00:00:00:01");
        cmd.add("-Dio.netty.processId=1");

        // JVM crash diagnostics — write to <dataDir>/crashes/
        String crashDir =
            config.dataDir().toAbsolutePath().resolve("crashes").toString().replace('\\', '/');
        cmd.add("-XX:ErrorFile=" + crashDir + "/hs_err_pid%p.log");
        cmd.add("-XX:+HeapDumpOnOutOfMemoryError");
        cmd.add("-XX:HeapDumpPath=" + crashDir + "/");

        // Startup optimizations (tempdoc 286):
        // - UsePerfData: skip hsperfdata file creation in %TEMP% (JDK-8246020: 6.29% bootstrap)
        // - CompactObjectHeaders: 12→8 byte object headers (JEP 519, JDK 25)
        cmd.add("-XX:-UsePerfData");
        cmd.add("-XX:+UseCompactObjectHeaders");

        // Vector API (jdk.incubator.vector) intentionally NOT added here.
        // The incubator module disables the JVM's full module graph optimization,
        // preventing AOT class linking (JEP 514). Lucene falls back to scalar
        // DefaultVectorizationProvider — cost is ~0.03ms/query. Re-add when
        // Vector API finalizes (est. JDK 28-29). See tempdoc 269 §D4a.
        cmd.add("--sun-misc-unsafe-memory-access=warn");

        // Both the Head and Worker JVMs make FFM downcalls (NVML, the Windows job object,
        // the GPU driver probe); JDK 25 warns without this flag and a later JDK (JEP 472)
        // refuses outright.
        cmd.add("--enable-native-access=ALL-UNNAMED");

        // 371: Pass distribution build stamp for stale-JVM detection.
        Path stampFile = config.workerLibDir().getParent().resolve("build-stamp.txt");
        if (Files.exists(stampFile)) {
            try {
                String stamp = Files.readString(stampFile).trim();
                if (!stamp.isEmpty()) {
                    cmd.add("-D" + EnvRegistry.BUILD_STAMP.sysProp() + "=" + stamp);
                }
            } catch (IOException ignored) {
                // Best-effort — missing stamp is not fatal.
            }
        }

        // Worker classpath — use lib/* wildcard; JVM launcher expands it (not the shell).
        // Must be a plain String, not a Path, to avoid InvalidPathException on Windows.
        cmd.add("-cp");
        cmd.add(buildWorkerClasspath(config.workerLibDir(), devHotReloadClassesDir));
        cmd.add("io.justsearch.indexerworker.IndexerWorker");

        return cmd;
    }

    private String resolveOnnxRuntimeNativePathBestEffort() {
        String variantId = resolveOnnxRuntimeVariantIdBestEffort();
        if (variantId == null || variantId.isBlank()) {
            return null;
        }
        Path dir = config.dataDir()
            .resolve("native-bin")
            .resolve("onnxruntime")
            .resolve("variants")
            .resolve(variantId);
        if (!Files.isDirectory(dir)) {
            return null;
        }
        return dir.toAbsolutePath().toString();
    }

    static String resolveOnnxRuntimeNativePathHint(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            Path dir = Path.of(candidate).toAbsolutePath().normalize();
            if (!Files.isDirectory(dir)) {
                return null;
            }
            if (!looksLikeOnnxRuntimeNativeDir(dir)) {
                return null;
            }
            return dir.toString();
        } catch (Exception expected) {
            // InvalidPathException or similar for malformed candidate strings
            return null;
        }
    }

    static boolean looksLikeOnnxRuntimeNativeDir(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        boolean hasOrtCore = Files.exists(dir.resolve("onnxruntime.dll"));
        boolean hasCudaBits =
            Files.exists(dir.resolve("onnxruntime_providers_cuda.dll"))
                || Files.exists(dir.resolve("onnxruntime_providers_shared.dll"))
                || Files.exists(dir.resolve("cudart64_12.dll"))
                || Files.exists(dir.resolve("cublas64_12.dll"))
                || Files.exists(dir.resolve("cublasLt64_12.dll"));
        return hasOrtCore && hasCudaBits;
    }

    private static String resolveOnnxRuntimeVariantIdBestEffort() {
        ConfigStore store = ConfigStore.globalOrNull();
        ResolvedConfig rc = store != null ? store.get() : null;
        if (rc == null) {
            return null;
        }
        String override = rc.ai().onnxruntimeVariantId();
        if (!override.isEmpty()) {
            return override.trim();
        }
        // Default: follow the active llama-server runtime variantId when it is managed by runtime activation.
        String serverExe = rc.ai().serverExe() != null ? rc.ai().serverExe().toString() : "";
        return tryParseVariantIdFromLlamaServerExe(serverExe);
    }

    private static String tryParseVariantIdFromLlamaServerExe(String exePath) {
        if (exePath == null || exePath.isBlank()) {
            return null;
        }
        try {
            Path p = Path.of(exePath).toAbsolutePath().normalize();
            int n = p.getNameCount();
            for (int i = 0; i + 2 < n; i++) {
                String a = p.getName(i).toString();
                String b = p.getName(i + 1).toString();
                if ("llama-server".equalsIgnoreCase(a) && "variants".equalsIgnoreCase(b)) {
                    String variantId = p.getName(i + 2).toString();
                    return variantId == null || variantId.isBlank() ? null : variantId;
                }
            }
            // Fallback: case-insensitive string parse for non-standard path roots.
            String normalized = exePath.replace('\\', '/');
            String marker = "/native-bin/llama-server/variants/";
            int idx = normalized.toLowerCase(Locale.ROOT).indexOf(marker);
            if (idx < 0) {
                return null;
            }
            String rest = normalized.substring(idx + marker.length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                return null;
            }
            String variantId = rest.substring(0, slash);
            return variantId.isBlank() ? null : variantId;
        } catch (Exception expected) {
            // InvalidPathException or similar for malformed path strings
            return null;
        }
    }

    // NOTE: Intentionally no Lucene write.lock cleanup here.
    //
    // Rationale:
    // - Deleting Lucene internal lock files is unsafe under multi-instance and becomes incorrect once
    //   the index layout becomes generation-scoped (indices/<gen>/...).
    // - Lucene locking is based on OS-level file locks, not the mere presence of write.lock.
    // - If another process truly holds the lock, we *want* startup to fail rather than trying to
    //   "fix" it by deleting files.

    private void writeHeartbeat() {
        try {
            signalBus.writeHeartbeat();
        } catch (Exception e) {
            log.warn("Failed to write heartbeat", e);
        }
    }

    private void checkWorkerHealth() {
        if (!running.get() || manualRestarting.get()) {
            return;
        }
        Process p = workerProcess.get();
        if (p == null || p.isAlive()) {
            return;
        }
        log.warn("Worker process died with exit code: {}", p.exitValue());
        superviseTick(false);
    }

    /**
     * The single budgeted supervision decision point (tempdoc 627). Both the 1s death monitor and the
     * hang detector ({@link #recordHealthResult}) funnel here under one lock, so death-restarts and
     * hang-restarts draw from one shared restart budget instead of two independent counters. Consults
     * the pure {@link SupervisionDecision} authority and executes its verdict verbatim.
     *
     * @param hangSuspected true when invoked by the hang detector (process believed alive but unhealthy)
     */
    private void superviseTick(boolean hangSuspected) {
        superviseLock.lock();
        try {
            if (!running.get() || manualRestarting.get()) {
                return;
            }
            Process p = workerProcess.get();
            boolean alive = p != null && p.isAlive();
            long lastStart = lastSuccessfulStartTime.get();
            boolean known = lastStart > 0;
            long sinceMs = known ? System.currentTimeMillis() - lastStart : 0;
            SupervisionDecision.Input in = new SupervisionDecision.Input(
                    alive, consecutiveUnhealthy.get(), restartCount.get(), known, sinceMs);
            SupervisionDecision.Decision d = SupervisionDecision.decide(in, supervisionPolicy);

            switch (d.action()) {
                case NONE -> {
                    // Observed state needs no action (e.g. a hang tick where the process just recovered).
                }
                case GIVE_UP -> {
                    telemetry.recordRestartLimitExceeded();
                    int attempts = restartCount.get();
                    log.error("Worker restart limit exceeded ({} attempts), giving up", attempts);
                    running.set(false);
                    supervisionGaveUp.set(true);
                    supervisionEvents.onGaveUp("restart cap exceeded after " + attempts + " attempts");
                }
                case RESTART_RESPAWN, RESTART_GRACEFUL -> {
                    if (d.resetBudgetFirst()) {
                        int previousCount = restartCount.getAndSet(0);
                        if (previousCount > 0) {
                            log.info("Worker stable beyond window; resetting restart counter from {}",
                                    previousCount);
                            telemetry.recordStabilityReset(previousCount);
                        }
                    }
                    restartCount.set(d.nextAttempt());
                    // Tempdoc 627 (N2): carry the attempt#, fault kind and backoff the decision
                    // already computed onto the recovery occurrence.
                    String faultKind =
                            d.action() == SupervisionDecision.Action.RESTART_GRACEFUL ? "hang" : "death";
                    supervisionEvents.onRecovering(
                            hangSuspected ? "worker unresponsive; restarting" : "worker process died; restarting",
                            new RecoveryContext(d.nextAttempt(), faultKind, d.backoffMs()));
                    doRestart(d);
                }
            }
        } finally {
            superviseLock.unlock();
        }
    }

    /** Executes a restart verdict: graceful stop (if the process is still alive), backoff, respawn. */
    private void doRestart(SupervisionDecision.Decision d) {
        log.info("Attempting worker restart ({}/{}) after {}ms cooldown",
                d.nextAttempt(), supervisionPolicy.maxRestartAttempts(), d.backoffMs());
        try {
            if (d.action() == SupervisionDecision.Action.RESTART_GRACEFUL) {
                // Hung-but-alive worker: stop it gracefully so it can flush the index before respawn.
                stopProcess(workerProcess.get(), true);
            }
            if (d.backoffMs() > 0) {
                Thread.sleep(d.backoffMs());
            }
            signalBus.zeroPort();
            signalBus.clearShutdown();
            signalBus.writeHeartbeat();
            spawnWorker();

            int port;
            try (var sample = telemetry.startPortDiscovery()) { // NOPMD - telemetry timing
                port = signalBus.awaitPort(
                        config.portDiscoveryTimeoutMs(), PORT_POLL_INTERVAL_MS, workerProcess.get());
            } catch (IllegalStateException e) {
                telemetry.recordPortDiscoveryTimeout();
                throw e;
            }
            discoveredPort.set(port);
            lastSuccessfulStartTime.set(System.currentTimeMillis());
            consecutiveUnhealthy.set(0);
            telemetry.recordRestartSuccess();
            supervisionEvents.onRecovered();
            log.info("Worker restarted on port {}", port);
        } catch (Exception e) {
            telemetry.recordRestartFailed();
            log.error("Failed to restart worker", e);
            // Partial-boot hardening (tempdoc 627): a worker that spawned but never published its gRPC
            // port (awaitPort timeout) is an orphan — alive but unreachable. Reap it gracefully so it
            // cannot linger as a port-less zombie holding the data-dir lock; the next supervision tick
            // then sees a dead process and restarts (or gives up) from a clean slate.
            Process orphan = workerProcess.get();
            if (orphan != null && orphan.isAlive()) {
                log.warn("Reaping port-less worker after failed restart (partial boot)");
                stopProcess(orphan, true);
            }
        }
    }

    /**
     * Stops a worker process. When {@code graceful}, first writes the signal-bus shutdown so the worker
     * can commit+close its Lucene index and SQLite queue ({@code Process.destroy()} is a hard
     * {@code TerminateProcess} on Windows with no flush — tempdoc 627). Forces termination only if the
     * worker does not exit within the configured shutdown timeout. Shared by {@link #close()},
     * {@link #restart()}, and the supervised {@link #doRestart}.
     */
    private ShutdownOutcome stopProcess(Process p, boolean graceful) {
        if (p == null || !p.isAlive()) {
            return ShutdownOutcome.GRACEFUL;
        }
        if (graceful) {
            try {
                signalBus.writeShutdown();
            } catch (Exception e) {
                log.warn("Failed to write shutdown signal", e);
            }
        }
        try {
            if (!p.waitFor(config.workerShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                log.warn("Worker did not terminate gracefully within {}ms, forcing",
                        config.workerShutdownTimeoutMs());
                telemetry.recordShutdownTimeout();
                p.destroyForcibly();
                telemetry.recordForcibleKill();
                awaitForcedExit(p);
                return ShutdownOutcome.FORCED;
            }
            return ShutdownOutcome.GRACEFUL;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            telemetry.recordForcibleKill();
            return ShutdownOutcome.FAILED;
        }
    }

    /**
     * Waits briefly for a force-killed worker to actually leave the process table.
     *
     * <p>{@code destroyForcibly()} only requests termination. Returning before the OS has reaped the
     * process leaves its file handles — the Lucene {@code write.lock} and the jobs.db connection —
     * open, so whatever respawns next (a supervised restart, or a boot retry) can collide with the
     * corpse it just killed.
     */
    private static void awaitForcedExit(Process p) {
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                log.warn("Force-killed worker (PID {}) still alive after 1s", p.pid());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Tempdoc 696: the Worker's java binary — the running (Head) JVM's own {@code java.home}, so the
     * Worker uses the SAME JDK as the Head regardless of PATH ordering. A bare {@code java}/{@code
     * java.exe} is wrong: PATH may front an incompatible JDK (e.g. a scoop JDK-8 shim) and crash the
     * JDK-25 Worker with {@code UnsupportedClassVersionError}. Package-private for unit testing.
     */
    static String workerJavaBinary(boolean isWindows) {
        Path javaHome = Path.of(System.getProperty("java.home"));
        return javaHome.resolve("bin").resolve(isWindows ? "java.exe" : "java").toString();
    }

    /** Default JDWP debug port for hot-reload (HotSwapPush connects here). */
    private static final int DEV_HOTRELOAD_DEFAULT_DEBUG_PORT = 5005;

    /**
     * Adds dev hot-reload JVM flags (tempdoc 305).
     *
     * <p>When {@code JUSTSEARCH_DEV_HOTRELOAD=true} (env var), enables the Worker's
     * {@code DevReloadManager}, publishes the classes directory as a system property, and opens
     * the JDWP listener HotSwapPush attaches to.
     *
     * @return the classes dir when hot reload is enabled (it also goes first on the classpath —
     *     tempdoc 844 R4), or {@code null} when it is off
     */
    private Path addDevHotReloadFlags(List<String> cmd) {
        if (!EnvRegistry.DEV_HOTRELOAD.getBoolean(false)) {
            return null;
        }
        cmd.add("-D" + EnvRegistry.DEV_HOTRELOAD.sysProp() + "=true");
        Path classesDir = devHotReloadClassesDir(config.workingDirectory());
        cmd.add("-D" + EnvRegistry.DEV_HOTRELOAD_CLASSES_DIR.sysProp() + "=" + classesDir);

        // Auto-enable JDWP agent so HotSwapPush can push bytecode updates.
        // Uses JUSTSEARCH_DEV_DEBUG_PORT if set, otherwise defaults to 5005. The dev-runner
        // always sets it (tempdoc 844 R3) and records the value in run.json, so the pusher reads
        // the port instead of assuming this default.
        int debugPort = EnvRegistry.DEV_DEBUG_PORT.getInt(DEV_HOTRELOAD_DEFAULT_DEBUG_PORT);
        cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + debugPort);
        log.info("Worker dev hot-reload enabled: classesDir={}, debugPort={}", classesDir, debugPort);
        return classesDir;
    }

    /** Absolute worker-services classes dir for a given working directory (dev hot reload). */
    static Path devHotReloadClassesDir(Path workingDirectory) {
        return workingDirectory
            .resolve("modules").resolve("worker-services")
            .resolve("build").resolve("classes").resolve("java").resolve("main")
            .toAbsolutePath();
    }

    /**
     * Worker classpath: the installDist jar set, with the dev hot-reload classes dir FIRST when
     * hot reload is on.
     *
     * <p>Tempdoc 844 §4.2 R4 — without the prefix the running Worker loads everything from the
     * jars while HotSwapPush pushes from {@code build/classes/java/main}. Only the classes already
     * loaded get redefined; every class loaded later comes from the STALE jar, in a JVM whose
     * build stamp now claims to be current. Putting the classes dir first makes both halves come
     * from the same tree — what tempdoc 305 Phase 2's child classloader was for, reached by
     * classpath ordering instead.
     *
     * <p>{@code hotReloadClassesDir == null} (hot reload off, i.e. production and every default
     * dev start before 844) returns exactly the previous classpath — zero effect.
     */
    static String buildWorkerClasspath(Path workerLibDir, Path hotReloadClassesDir) {
        String jars = workerLibDir.toAbsolutePath() + java.io.File.separator + "*";
        if (hotReloadClassesDir == null) {
            return jars;
        }
        return hotReloadClassesDir.toAbsolutePath() + java.io.File.pathSeparator + jars;
    }

    @Override
    public void close() {
        shutdownForUpgrade();
    }

    /**
     * Stop the Worker and report whether the durable-owner shutdown was graceful. Idempotent.
     */
    public ShutdownOutcome shutdownForUpgrade() {
        if (!running.compareAndSet(true, false)) {
            return ShutdownOutcome.GRACEFUL;
        }

        log.info("Shutting down worker spawner");

        // Cancel scheduled tasks
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
        if (monitorTask != null) {
            monitorTask.cancel(false);
        }

        // Stop scheduler threads before unmapping/closing the signal bus (Windows safety).
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(config.workerShutdownTimeoutMs(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }

        // Signal shutdown to worker and wait for graceful termination (force only on timeout).
        ShutdownOutcome outcome = stopProcess(workerProcess.get(), true);

        // Close signal bus
        signalBus.close();

        // Close Job Object (after process termination — closing the handle triggers OS kill)
        if (jobObject != null) {
            jobObject.close();
            jobObject = null;
        }

        log.info("Worker spawner shutdown complete");
        return outcome;
    }
}
