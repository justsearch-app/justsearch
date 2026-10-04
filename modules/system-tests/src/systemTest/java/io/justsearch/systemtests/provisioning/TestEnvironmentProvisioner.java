package io.justsearch.systemtests.provisioning;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit 5 Extension that provisions a test environment for system tests.
 *
 * <p>Provides:
 * <ul>
 *   <li><b>Artifact Verification:</b> Fails fast if the worker JAR is missing</li>
 *   <li><b>Sandboxing:</b> Creates a temporary directory for test data (signals, indices)</li>
 *   <li><b>Configuration:</b> Sets system properties to point to real SSOT/config (no copying)</li>
 *   <li><b>Lifecycle Management:</b> Cleans up temp directories after tests</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * class MyTest {
 *     @RegisterExtension
 *     static TestEnvironmentProvisioner env = new TestEnvironmentProvisioner();
 *
 *     @Test
 *     void myTest() {
 *         Path tempDir = env.getTempDir();
 *         String[] jvmArgs = env.getWorkerJvmArgs();
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p><b>Design:</b> Instead of copying SSOT/config directories (which was slow and caused
 * file locking issues on Windows), this extension now sets system properties that point to
 * the real project directories. This relies on the configuration layer respecting:
 * <ul>
 *   <li>{@code -Djustsearch.repo.root} or {@code JUSTSEARCH_REPO_ROOT}</li>
 *   <li>{@code -Djustsearch.config} or {@code JUSTSEARCH_CONFIG}</li>
 *   <li>{@code -Djustsearch.ssot.path} or {@code JUSTSEARCH_SSOT_PATH}</li>
 * </ul>
 *
 * <p><b>Note:</b> This extension manages <em>files only</em>. Process lifecycle
 * (spawning, killing workers) is handled by {@link io.justsearch.systemtests.process.ManagedProcess}
 * and its shutdown hook.
 */
public class TestEnvironmentProvisioner implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback {
  private static final Logger log = LoggerFactory.getLogger(TestEnvironmentProvisioner.class);

  private Path tempDir;
  private Path projectRoot;
  private boolean initialized = false;

  // Original system property values (for cleanup)
  private String originalRepoRoot;
  private String originalSsotPath;
  private String originalConfig;

  // =========================================================================
  // Lifecycle Callbacks
  // =========================================================================

  @Override
  public void beforeAll(ExtensionContext context) throws Exception {
    if (initialized) {
      return;
    }

    log.info("TestEnvironmentProvisioner: Initializing test environment");

    // Lane F stage A item A13 removed step 1, the verifyWorkerDist() fail-fast: there is no
    // Worker distribution to verify. What this extension is kept for is the system properties
    // below (justsearch.repo.root / ssot.path / config), which the Engine reads in THIS JVM.

    // Step 1: Find project root
    findProjectRoot();

    // Step 2: Create temp directory for test data (signals, indices)
    String testClassName = context.getTestClass()
        .map(Class::getSimpleName)
        .orElse("unknown");
    tempDir = Files.createTempDirectory("test-env-" + testClassName + "-");
    log.info("Created temp directory: {}", tempDir);

    // Step 3: Set system properties to point to real SSOT/config (no copying!)
    configureSystemProperties();

    initialized = true;
    log.info("TestEnvironmentProvisioner: Initialization complete");
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {
    // The stale-signal-file cleanup that used to live here (a 5-attempt retry around
    // deleteIfExists on worker_signal.lock, for Windows lock delays) is gone with the
    // memory-mapped signal bus: lane F stage A left nothing that creates that file.
  }

  @Override
  public void afterAll(ExtensionContext context) {
    log.info("TestEnvironmentProvisioner: Cleaning up");

    // Restore original system properties
    restoreSystemProperties();

    // Clean up temp directory
    if (tempDir != null && Files.exists(tempDir)) {
      cleanupDirectory(tempDir);
    }

    initialized = false;
  }

  // =========================================================================
  // Public Accessors
  // =========================================================================

  /**
   * Returns the temporary directory for this test class.
   * Use for test data: index data, job queue, etc.
   *
   * @throws IllegalStateException if called before initialization
   */
  public Path getTempDir() {
    ensureInitialized();
    return tempDir;
  }

  /**
   * Returns the path to the application.yaml config file in the project.
   *
   * @throws IllegalStateException if called before initialization
   */
  public Path getConfigPath() {
    ensureInitialized();
    return projectRoot.resolve("config/application.yaml");
  }

  /**
   * Returns the project root directory.
   *
   * @throws IllegalStateException if called before initialization
   */
  public Path getProjectRoot() {
    ensureInitialized();
    return projectRoot;
  }

  /**
   * Returns the path to the SSOT directory.
   *
   * @throws IllegalStateException if called before initialization
   */
  public Path getSsotPath() {
    ensureInitialized();
    return projectRoot.resolve("SSOT");
  }

  /**
   * Returns JVM arguments array for spawning worker processes.
   *
   * <p>These arguments configure the worker to:
   * <ul>
   *   <li>Use the real SSOT directory</li>
   *   <li>Use the real config file</li>
   *   <li>Write data to the test's temp directory</li>
   * </ul>
   *
   * @throws IllegalStateException if called before initialization
   */
  public String[] getWorkerJvmArgs() {
    ensureInitialized();
    return new String[] {
        "-Djustsearch.repo.root=" + projectRoot.toAbsolutePath(),
        "-Djustsearch.ssot.path=" + projectRoot.resolve("SSOT").toAbsolutePath(),
        "-Djustsearch.config=" + projectRoot.resolve("config/application.yaml").toAbsolutePath(),
        "-Djustsearch.data.dir=" + tempDir.toAbsolutePath()
    };
  }

  // =========================================================================
  // Private Implementation
  // =========================================================================

  private void findProjectRoot() {
    // Start from current directory and walk up to find project root
    projectRoot = Path.of(".").toAbsolutePath().normalize();
    while (projectRoot != null) {
      if (Files.exists(projectRoot.resolve("SSOT")) &&
          Files.exists(projectRoot.resolve("settings.gradle.kts"))) {
        log.info("Found project root: {}", projectRoot);
        return;
      }
      projectRoot = projectRoot.getParent();
    }

    // Fallback: try relative paths
    Path[] fallbackPaths = {
        Path.of("../../../").toAbsolutePath().normalize(),
        Path.of("../../").toAbsolutePath().normalize(),
        Path.of("../").toAbsolutePath().normalize()
    };

    for (Path fallback : fallbackPaths) {
      if (Files.exists(fallback.resolve("SSOT"))) {
        projectRoot = fallback;
        log.info("Found project root via fallback: {}", projectRoot);
        return;
      }
    }

    throw new IllegalStateException(
        "Could not find project root with SSOT directory. " +
        "Ensure tests are run from within the project structure.");
  }

  private void configureSystemProperties() {
    // Save original values
    originalRepoRoot = System.getProperty("justsearch.repo.root");
    originalSsotPath = System.getProperty("justsearch.ssot.path");
    originalConfig = System.getProperty("justsearch.config");

    // Set properties to point to real project directories
    System.setProperty("justsearch.repo.root", projectRoot.toAbsolutePath().toString());
    System.setProperty("justsearch.ssot.path", projectRoot.resolve("SSOT").toAbsolutePath().toString());
    System.setProperty("justsearch.config", projectRoot.resolve("config/application.yaml").toAbsolutePath().toString());

    log.info("Set system properties: repo.root={}, ssot.path={}, config={}",
        projectRoot, projectRoot.resolve("SSOT"), projectRoot.resolve("config/application.yaml"));
  }

  private void restoreSystemProperties() {
    restoreProperty("justsearch.repo.root", originalRepoRoot);
    restoreProperty("justsearch.ssot.path", originalSsotPath);
    restoreProperty("justsearch.config", originalConfig);
  }

  private void restoreProperty(String key, String originalValue) {
    if (originalValue == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, originalValue);
    }
  }

  private void cleanupDirectory(Path dir) {
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder())
          .forEach(p -> {
            try {
              Files.deleteIfExists(p);
            } catch (IOException e) {
              log.debug("Could not delete {}: {}", p, e.getMessage());
            }
          });
      log.info("Cleaned up temp directory: {}", dir);
    } catch (IOException e) {
      log.warn("Could not walk directory for cleanup: {}", dir, e);
    }
  }

  private void ensureInitialized() {
    if (!initialized) {
      throw new IllegalStateException(
          "TestEnvironmentProvisioner not initialized. " +
          "Ensure the extension is registered with @RegisterExtension.");
    }
  }
}
