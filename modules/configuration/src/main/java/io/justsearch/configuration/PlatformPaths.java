/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration;

import java.nio.file.Path;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized utility for resolving platform-specific paths.
 *
 * <p>This class provides the authoritative implementation for data directory resolution,
 * ensuring consistent behavior across all JustSearch components (UI Host, Worker, etc.).
 *
 * <p>Per the SSOT architecture (see docs/explanation/06-configuration-ssot.md), this class
 * delegates to {@link EnvRegistry} for environment/property resolution and provides
 * platform-aware defaults when no explicit configuration is provided.
 *
 * @see EnvRegistry#DATA_DIR
 */
public final class PlatformPaths {

    private static final Logger log = LoggerFactory.getLogger(PlatformPaths.class);

    private PlatformPaths() {
        // Utility class
    }

    /**
     * Expands the {@code ${user.home}} placeholder in a string, if present.
     *
     * <p>This is intentionally narrow: we do not try to implement a generic templating engine. The goal is to prevent
     * accidental filesystem writes to literal placeholder paths like {@code ${user.home}/.justsearch}.
     */
    public static String expandUserHomePlaceholders(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (!raw.contains("${user.home}")) {
            return raw;
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            return raw;
        }
        return raw.replace("${user.home}", userHome);
    }

    /**
     * Resolves the root data directory for JustSearch artifacts.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>System property: {@code -Djustsearch.data.dir=/path}</li>
     *   <li>Environment variable: {@code JUSTSEARCH_DATA_DIR=/path}</li>
     *   <li>Platform-specific default:
     *     <ul>
     *       <li>Windows: {@code %LOCALAPPDATA%\JustSearch}</li>
     *       <li>macOS: {@code ~/Library/Application Support/JustSearch}</li>
     *       <li>Linux/other: {@code ~/.justsearch}</li>
     *     </ul>
     *   </li>
     * </ol>
     *
     * @return the resolved data directory path (never null)
     */
    public static Path resolveDataDir() {
        // Canonical sources (SSOT):
        //   -Djustsearch.data.dir  (system property)
        //   JUSTSEARCH_DATA_DIR    (environment variable)
        //
        // Precedence:
        //   canonical sysprop > env var > platform default

        String canonical =
            assertNoUnexpandedPlaceholders(
                expandUserHomePlaceholders(System.getProperty(EnvRegistry.DATA_DIR.sysProp())),
                "-D" + EnvRegistry.DATA_DIR.sysProp());
        if (canonical != null && !canonical.isBlank()) {
            return Path.of(canonical);
        }

        String env =
            assertNoUnexpandedPlaceholders(
                expandUserHomePlaceholders(System.getenv(EnvRegistry.DATA_DIR.envVar())),
                EnvRegistry.DATA_DIR.envVar());
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }

        return getPlatformDefault();
    }

    /**
     * Returns the platform-specific default data directory.
     *
     * <p>This method does NOT check for environment overrides - use {@link #resolveDataDir()}
     * for the full resolution chain.
     *
     * @return the platform default path
     */
    public static Path getPlatformDefault() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            String appData = System.getenv("LOCALAPPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "JustSearch");
            }
            return Path.of(userHome, "AppData", "Local", "JustSearch");
        } else if (os.contains("mac")) {
            return Path.of(userHome, "Library", "Application Support", "JustSearch");
        } else {
            return Path.of(userHome, ".justsearch");
        }
    }

    public static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }

    public static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("linux");
    }

    public static boolean isMac() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac");
    }

    public static Path resolveUserHome() {
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            // Avoid returning a null Path; callers should handle the exception if this ever happens (it should not on normal JVMs).
            throw new IllegalStateException("System property user.home is not set");
        }
        return Path.of(userHome);
    }

    /**
     * Resolves the AI home directory (used for model storage, inference config, etc.).
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@link EnvRegistry#HOME} (system property or env var)</li>
     *   <li>{@link #resolveDataDir()} (standard data directory)</li>
     *   <li>Current working directory (ultimate fallback)</li>
     * </ol>
     *
     * @return the resolved AI home path (never null)
     */
    public static Path resolveAiHome() {
        try {
            Path fromEnv = EnvRegistry.HOME.getPath();
            if (fromEnv != null) return fromEnv;
        } catch (Exception e) {
            log.warn("Failed to resolve AI home from EnvRegistry.HOME, falling back to dataDir", e);
        }
        try {
            return resolveDataDir();
        } catch (Exception e) {
            log.warn("Failed to resolve dataDir for AI home, falling back to user.dir", e);
            return Path.of(System.getProperty("user.dir"));
        }
    }

    /**
     * Resolves the index base path for a given collection.
     *
     * @param collectionName the collection name (e.g., "default")
     * @return the index path: {@code dataDir/index/collectionName}
     */
    public static Path resolveIndexPath(String collectionName) {
        return resolveDataDir().resolve("index").resolve(collectionName);
    }

    public static String assertNoUnexpandedPlaceholders(String value, String source) {
        if (value == null) {
            return null;
        }
        // After expanding ${user.home}, any remaining ${...} is almost certainly a mistake that can create
        // literal directories like "${user.home}" or "${FOO}". Fail fast.
        if (value.contains("${")) {
            throw new IllegalArgumentException(
                "Unexpanded placeholder in path value from " + source + ": " + value
                    + " (did you mean to set an absolute path?)");
        }
        return value;
    }
}
