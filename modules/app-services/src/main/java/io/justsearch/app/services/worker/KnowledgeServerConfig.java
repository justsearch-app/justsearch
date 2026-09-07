/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import io.justsearch.configuration.JustSearchConfigurationLoader;
import io.justsearch.configuration.PlatformPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configuration for the Knowledge Server client.
 *
 * <p>Handles Dev/Prod profile detection and path resolution.
 *
 * <p>Uses {@link JustSearchConfigurationLoader} for centralized SSOT discovery
 * instead of duplicating the filesystem traversal logic.
 */
public record KnowledgeServerConfig(
        boolean isProduction,
        Path dataDir,
        Path libDir,
        Path workingDirectory,
        Path signalFilePath,
        long deadlineMs,
        long portDiscoveryTimeoutMs,
        int maxRetries,
        long workerShutdownTimeoutMs,
        long pidValidationTimeoutMs,
        long stabilityWindowMs,
        int batchSize,
        long healthCheckRetryBudgetMs,
        int bootFaultInjectAttempts) {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServerConfig.class);

    /**
     * Tempdoc 825 §D4: the boot fault injector is a TEST affordance and must be unreachable in a
     * shipped build. Enforced here, in the type, rather than at the one read site — a compact
     * constructor cannot be forgotten by a future caller, and this record is already the allowlisted
     * config surface ({@code AppServicesWorkerGuardrailsTest}), so the guard sits with the read.
     */
    public KnowledgeServerConfig {
        if (isProduction && bootFaultInjectAttempts != 0) {
            log.warn(
                "Ignoring justsearch.worker.boot.faultInjectAttempts={} — the boot fault injector is"
                    + " disabled in production builds",
                bootFaultInjectAttempts);
            bootFaultInjectAttempts = 0;
        }
        if (bootFaultInjectAttempts < 0) {
            bootFaultInjectAttempts = 0;
        }
    }

    /**
     * Tempdoc 251 (2026-03) measured the prior 5000ms default tripping the circuit breaker on
     * long documents and recommended 15000ms; the {@code JUSTSEARCH_WORKER_DEADLINE_MS} /
     * {@code justsearch.worker.deadline_ms} escape hatch remains for operators who need a
     * different value.
     */
    private static final long DEFAULT_DEADLINE_MS = 15_000;
    private static final long DEFAULT_PORT_DISCOVERY_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_WORKER_SHUTDOWN_TIMEOUT_MS = 5000;
    private static final long DEFAULT_PID_VALIDATION_TIMEOUT_MS = 5000;
    /** Default stability window: 5 minutes. Worker must run this long to reset restart counter. */
    private static final long DEFAULT_STABILITY_WINDOW_MS = 300_000;
    /** Default batch size for file submissions. Must be <= Worker MAX_BATCH_SIZE (10,000). */
    private static final int DEFAULT_BATCH_SIZE = 5000;
    /**
     * Tempdoc 374 alpha.23 R13-A defect #1: 30-second budget for the bootstrap
     * health-check retry loop. Round-13 cycle 2 caught the worker mid Lucene
     * SearcherManager warmup (~3s observed); 30s gives ~10x margin without
     * meaningfully extending cold-start when the worker wins the race.
     */
    private static final long DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS = 30_000;

    /**
     * Loads configuration from environment and system properties.
     */
    public static KnowledgeServerConfig load() {
        boolean isProd = detectProductionMode();
        Path dataDir = resolveDataDir();
        Path libDir = resolveLibDir();
        Path workingDir = resolveWorkingDirectory();
        Path signalFile = dataDir.resolve("worker_signal.lock");

        long deadline = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_DEADLINE_MS", "justsearch.worker.deadline_ms"),
                DEFAULT_DEADLINE_MS);
        long portTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_PORT_TIMEOUT_MS", "justsearch.worker.port_timeout_ms"),
                DEFAULT_PORT_DISCOVERY_TIMEOUT_MS);
        int maxRetries = parseInt(
                envOrProperty("JUSTSEARCH_WORKER_MAX_RETRIES", "justsearch.worker.max_retries"),
                DEFAULT_MAX_RETRIES);
        long shutdownTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_SHUTDOWN_TIMEOUT_MS", "justsearch.worker.shutdown_timeout_ms"),
                DEFAULT_WORKER_SHUTDOWN_TIMEOUT_MS);
        long pidValidationTimeout = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_PID_VALIDATION_TIMEOUT_MS", "justsearch.worker.pid_validation_timeout_ms"),
                DEFAULT_PID_VALIDATION_TIMEOUT_MS);
        long stabilityWindow = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_STABILITY_WINDOW_MS", "justsearch.worker.stability_window_ms"),
                DEFAULT_STABILITY_WINDOW_MS);
        int batchSize = parseInt(
                envOrProperty("JUSTSEARCH_WORKER_BATCH_SIZE", "justsearch.worker.batch_size"),
                DEFAULT_BATCH_SIZE);
        long healthCheckRetryBudget = parseLong(
                envOrProperty("JUSTSEARCH_WORKER_HEALTH_RETRY_BUDGET_MS", "justsearch.worker.health_retry_budget_ms"),
                DEFAULT_HEALTH_CHECK_RETRY_BUDGET_MS);
        // Tempdoc 825 §D4: countdown fault injector — the first N PID validations fail, then the
        // injector stops, which is what makes boot-recovery CONVERGENCE reproducible (the existing
        // pid_validation_timeout_ms knob fails EVERY attempt, so it can only prove the pin). The
        // compact constructor zeroes it in production.
        int bootFaultInjectAttempts = parseInt(
                envOrProperty("JUSTSEARCH_WORKER_BOOT_FAULT_INJECT_ATTEMPTS", "justsearch.worker.boot.faultInjectAttempts"),
                0);

        KnowledgeServerConfig config = new KnowledgeServerConfig(
                isProd,
                dataDir,
                libDir,
                workingDir,
                signalFile,
                deadline,
                portTimeout,
                maxRetries,
                shutdownTimeout,
                pidValidationTimeout,
                stabilityWindow,
                batchSize,
                healthCheckRetryBudget,
                bootFaultInjectAttempts);

        log.info("Loaded KnowledgeServerConfig: production={}, dataDir={}", isProd, dataDir);

        return config;
    }

    /**
     * Detects if running in production mode.
     *
     * <p>Production indicators:
     * <ul>
     *   <li>System property "justsearch.prod" = "true"</li>
     *   <li>Running from a bundled JRE (java.home is within app directory)</li>
     *   <li>Environment variable "JUSTSEARCH_PROD" = "true"</li>
     * </ul>
     */
    private static boolean detectProductionMode() {
        // Explicit production flag
        String prodFlag = envOrProperty("JUSTSEARCH_PROD", "justsearch.prod");
        if ("true".equalsIgnoreCase(prodFlag)) {
            return true;
        }
        if ("false".equalsIgnoreCase(prodFlag)) {
            return false;
        }

        // Check if running from bundled runtime
        String javaHome = System.getProperty("java.home", "");
        Path javaHomePath = Path.of(javaHome).toAbsolutePath().normalize();

        // In production, java.home is typically inside the app's dist/runtime directory
        Path distRuntime = resolveLibDir().getParent().resolve("runtime");
        if (Files.exists(distRuntime) && javaHomePath.startsWith(distRuntime.toAbsolutePath())) {
            return true;
        }

        return false;
    }

    /**
     * Resolves data directory using centralized PlatformPaths.
     * See {@link PlatformPaths#resolveDataDir()} for resolution order.
     */
    private static Path resolveDataDir() {
        return PlatformPaths.resolveDataDir().toAbsolutePath();
    }

    private static Path resolveLibDir() {
        // Production/bundled layout: lib is sibling to runtime in the dist folder.
        // Always checked — the bundled layout may exist even when prod=false (e.g.,
        // alpha builds with CORS relaxed for browser testing), so the `isProd` flag
        // that callers used to pass in never changed behaviour.
        String javaHome = System.getProperty("java.home", "");
        Path runtimePath = Path.of(javaHome).getParent();
        if (runtimePath != null) {
            Path libPath = runtimePath.resolve("lib");
            if (Files.isDirectory(libPath)) {
                return libPath.toAbsolutePath();
            }
        }

        // Development: use build output
        Path repoRoot = resolveRepoRoot();
        return repoRoot.resolve("modules").resolve("dist").resolve("build").resolve("libs");
    }

    private static Path resolveWorkingDirectory() {
        // Use repo root in dev, data dir in prod
        String configured = envOrProperty("JUSTSEARCH_WORKING_DIR", "justsearch.working_dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath();
        }

        try {
            return resolveRepoRoot();
        } catch (IllegalStateException e) {
            // Fallback to current directory
            return Path.of("").toAbsolutePath();
        }
    }

    // Lane F stage A item A13 deleted resolveWorkerLibDir and the workerLibDir record component.
    // It answered "where are the Worker distribution jars" for the deleted WorkerSpawner (item
    // A11), resolving JUSTSEARCH_WORKER_LIB_DIR / justsearch.worker.lib.dir, then the bundled
    // lib/worker/ layout, then modules/indexer-worker/build/install/indexer-worker/lib. All three
    // are gone: one JVM has one classpath, and the index half is on it.

    /**
     * Resolves the repository root using the centralized configuration loader.
     *
     * <p>This delegates to {@link JustSearchConfigurationLoader} to avoid duplicating
     * the SSOT discovery logic across the codebase.
     *
     * @return the repository root path
     * @throws IllegalStateException if the repo root cannot be found
     */
    private static Path resolveRepoRoot() {
        JustSearchConfigurationLoader loader = new JustSearchConfigurationLoader();
        Optional<Path> ssotRoot = loader.ssotRoot();
        if (ssotRoot.isPresent()) {
            // SSOT root is inside the repo, so parent is repo root
            return ssotRoot.get().getParent();
        }
        // Fallback: traverse up from CWD (legacy behavior for edge cases)
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("SSOT"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Repo root not found (missing SSOT directory)");
    }

    private static String envOrProperty(String envKey, String propKey) {
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return System.getProperty(propKey);
    }

    /**
     * Returns the default Head-to-Worker RPC deadline in milliseconds, absent an
     * env/sysprop override (tempdoc 882 item 5, tempdoc 251).
     *
     * @return the default deadline in milliseconds
     */
    public static long defaultDeadlineMs() {
        return DEFAULT_DEADLINE_MS;
    }

    /**
     * Returns whether GPU acceleration is allowed by policy.
     *
     * <p>This centralizes the policy check so other classes don't need direct env/sysprop access.
     * Defaults to true if not explicitly configured.
     *
     * @return true if GPU acceleration is allowed by policy
     */
    public static boolean isGpuAccelerationPolicyEnabled() {
        String value = envOrProperty("JUSTSEARCH_POLICY_GPU_ACCELERATION_ENABLED",
            "policy.gpu_acceleration_enabled");
        // Default to true if not set
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
