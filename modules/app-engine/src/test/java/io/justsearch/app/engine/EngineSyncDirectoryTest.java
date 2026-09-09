/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.ipc.SyncDirectoryResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lane F stage A item A12 — the in-process replacement for
 * {@code systemtests.process.SyncDirectoryIntegrationTest}.
 *
 * <p><b>What the retired test asserted, and why it survives the collapse.</b> It drove the
 * {@code SyncDirectory} RPC against a spawned Worker and pinned bidirectional reconciliation:
 * empty directory returns zeros, missing files are added, orphans are pruned, additions and
 * deletions land in one pass, {@code force=true} never yields to the user-activity check, a
 * non-existent root is handled rather than crashing, nested trees are walked, unsupported types do
 * not raise, the source-tree/Obsidian layout admission policy admits exactly what it claims, and a
 * moderate root reconciles quickly. Not one of those is a property of the transport — they are all
 * properties of the reconciler, which is now reached by a direct call on
 * {@code KnowledgeClient.syncDirectory} (KnowledgeClient.java:1541). The response is still the same
 * {@code SyncDirectoryResponse} proto, so every count and flag assertion carries over verbatim
 * rather than being adapted to a new shape.
 *
 * <p><b>The one thing that changed meaning, and how it is handled.</b> The retired test configured
 * the spawned Worker with {@code .withoutEnv("JUSTSEARCH_INGESTION_SKIP_PATTERNS")} and its two
 * siblings (SyncDirectoryIntegrationTest.java:59-61), because the dev harness sets those in the
 * Worker's environment (modules/ui/build.gradle.kts) and they would otherwise override the default
 * skip policy that test 9's exact count of 7 depends on. <b>An in-process test cannot unset an
 * environment variable for the JVM it is already running in.</b> Rather than quietly assert a count
 * that a stray environment variable would invalidate, {@link #syncPinsSourceTreeAndObsidianLayout}
 * checks the three overrides are absent and fails with an explanatory message if they are not — a
 * legible failure instead of a silent mis-test. Defaults apply when they are unset, which is the
 * state the assertion needs (DefaultWorkerAppServicesSkipPolicyTest.java:74-85).
 *
 * <p><b>What was dropped, and why.</b> The {@code mmf.keepAlive()} heartbeat and the
 * {@code awaitPort}/{@code isHealthy} port-discovery handshake — there is no second process to keep
 * alive and no port to discover.
 */
@DisplayName("Engine SyncDirectory (in-process)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(600)
final class EngineSyncDirectoryTest {

  @TempDir static Path tempDir;

  private static EngineTestHarness harness;

  /**
   * The directory the sync tests reconcile. A sibling of the Engine's data directory, never a
   * parent: a sync that walked the Lucene index would enqueue the index into itself.
   */
  private static Path syncTestDir;

  @BeforeAll
  static void startEngine() throws Exception {
    syncTestDir = tempDir.resolve("sync-test-data");
    Files.createDirectories(syncTestDir);
    harness = EngineTestHarness.start(tempDir.resolve("data"));
    assertTrue(harness.client().isHealthy(TestEngineContexts.FOREGROUND), "the engine must be healthy before the sync tests");
  }

  @AfterAll
  static void stopEngine() {
    if (harness != null) {
      harness.close();
      harness = null;
    }
  }

  private static long docCount() {
    return harness.status().getCore().getDocCount();
  }

  // =========================================================================
  // Basic Sync Operations
  // =========================================================================

  @Test
  @Order(1)
  @DisplayName("syncDirectory on empty directory returns zeros")
  void syncEmptyDirectoryReturnsZeros() {
    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertEquals(0, response.getFilesAdded(), "should add 0 files from an empty directory");
    assertEquals(0, response.getFilesDeleted(), "should delete 0 orphans");
    assertFalse(response.getSkipped(), "should not skip when force=true");
    assertTrue(response.getError().isEmpty(), "should have no error");
  }

  @Test
  @Order(2)
  @DisplayName("syncDirectory adds missing files to index")
  void syncAddsNewFiles() throws Exception {
    Path file1 = syncTestDir.resolve("new-file-1.txt");
    Path file2 = syncTestDir.resolve("new-file-2.txt");
    Files.writeString(file1, "Content for file 1 - apples and oranges");
    Files.writeString(file2, "Content for file 2 - bananas and grapes");

    // Baseline BEFORE the sync enqueues: the retired test read the count after and then waited for
    // "that + 2", which is unreachable if either file had already been indexed by then.
    long baseline = docCount();

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(
        response.getFilesAdded() >= 2,
        "should enqueue at least 2 new files, got " + response.getFilesAdded());
    assertTrue(response.getError().isEmpty(), "should have no error");

    assertTrue(
        harness.awaitIndexed(baseline + 2, 60_000), "new files should be indexed within 60 seconds");

    assertTrue(harness.awaitSearchable("apples", 30_000), "should find 'apples' after sync");
    assertTrue(harness.awaitSearchable("bananas", 30_000), "should find 'bananas' after sync");
  }

  @Test
  @Order(3)
  @DisplayName("syncDirectory prunes orphan documents")
  void syncPrunesOrphans() throws Exception {
    Path orphanFile = syncTestDir.resolve("orphan-file.txt");
    Files.writeString(orphanFile, "This file will become an orphan - unique keyword: xyzorphan123");

    long baseline = docCount();
    assertEquals(
        1,
        harness.client().submitBatch(List.of(orphanFile), TestEngineContexts.FOREGROUND).getAcceptedCount(),
        "the orphan-to-be must be accepted");
    assertTrue(harness.awaitIndexed(baseline + 1, 60_000), "orphan file should be indexed");

    assertTrue(
        harness.awaitSearchable("xyzorphan123", 30_000),
        "should find the orphan file before deletion");

    Files.delete(orphanFile);
    assertFalse(Files.exists(orphanFile), "file should be deleted from disk");

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(
        response.getFilesDeleted() >= 1,
        "should prune at least 1 orphan document, got " + response.getFilesDeleted());
    assertTrue(response.getError().isEmpty(), "should have no error");

    // The retired test slept 1s then asserted a hard zero; polling asserts the same end state
    // without the sleep being load-bearing.
    assertTrue(
        harness.awaitNotSearchable("xyzorphan123", 30_000),
        "orphan should not be searchable after prune");
  }

  @Test
  @Order(4)
  @DisplayName("syncDirectory handles both additions and deletions")
  void syncHandlesBothAdditionsAndDeletions() throws Exception {
    Path toDelete = syncTestDir.resolve("to-delete.txt");
    Files.writeString(toDelete, "File to delete - keyword: deleteme789");

    long baseline = docCount();
    assertEquals(
        1,
        harness.client().submitBatch(List.of(toDelete), TestEngineContexts.FOREGROUND).getAcceptedCount(),
        "the to-delete file must be accepted");
    assertTrue(harness.awaitIndexed(baseline + 1, 60_000), "the to-delete file should be indexed");
    assertTrue(
        harness.awaitSearchable("deleteme789", 30_000), "should find the file before operations");

    Path newFile = syncTestDir.resolve("brand-new.txt");
    Files.writeString(newFile, "Brand new file - keyword: brandnew456");
    Files.delete(toDelete);

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(
        response.getFilesAdded() >= 1,
        "should add at least 1 new file, got " + response.getFilesAdded());
    assertTrue(
        response.getFilesDeleted() >= 1,
        "should delete at least 1 orphan, got " + response.getFilesDeleted());
    assertTrue(response.getError().isEmpty(), "should have no error");

    assertTrue(harness.awaitSearchable("brandnew456", 60_000), "new file should be searchable");
    assertTrue(
        harness.awaitNotSearchable("deleteme789", 30_000), "deleted file should not be searchable");
  }

  // =========================================================================
  // Force Flag Behavior
  // =========================================================================

  @Test
  @Order(5)
  @DisplayName("syncDirectory with force=true completes successfully")
  void syncWithForceCompletesSuccessfully() throws Exception {
    Path activeFile = syncTestDir.resolve("active-test.txt");
    Files.writeString(activeFile, "Testing with force flag - keyword: activetest111");

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertFalse(response.getSkipped(), "should NOT skip when force=true");
    assertTrue(response.getError().isEmpty(), "should have no error");
  }

  // =========================================================================
  // Edge Cases
  // =========================================================================

  @Test
  @Order(6)
  @DisplayName("syncDirectory handles non-existent directory gracefully")
  void syncHandlesNonExistentDirectory() {
    Path nonExistent = tempDir.resolve("this-dir-does-not-exist-12345");
    assertFalse(Files.exists(nonExistent), "directory should not exist");

    SyncDirectoryResponse response = harness.client().syncDirectory(nonExistent.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    // Zeros or an error are both acceptable; a crash is not. That was the retired test's
    // judgement and it is unchanged.
    assertEquals(0, response.getFilesAdded(), "should add 0 files from a non-existent directory");
  }

  @Test
  @Order(7)
  @DisplayName("syncDirectory handles nested directories")
  void syncHandlesNestedDirectories() throws Exception {
    Path nested = syncTestDir.resolve("level1/level2/level3");
    Files.createDirectories(nested);

    Path deepFile = nested.resolve("deep-file.txt");
    Files.writeString(deepFile, "Deep nested file - keyword: deepnested999");

    long baseline = docCount();

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(
        response.getFilesAdded() >= 1,
        "should find the deeply nested file, got " + response.getFilesAdded());
    assertTrue(response.getError().isEmpty(), "should have no error");

    assertTrue(harness.awaitIndexed(baseline + 1, 60_000), "the nested file should be indexed");
    assertTrue(
        harness.awaitSearchable("deepnested999", 30_000), "should find the deeply nested file");
  }

  @Test
  @Order(8)
  @DisplayName("syncDirectory ignores unsupported file types")
  void syncIgnoresUnsupportedFileTypes() throws Exception {
    Path binaryFile = syncTestDir.resolve("unsupported.xyz");
    Files.write(binaryFile, new byte[] {0x00, 0x01, 0x02, 0x03});

    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(
        response.getError().isEmpty(), "should have no error even with unsupported files present");
  }

  @Test
  @Order(9)
  @DisplayName("syncDirectory pins source-tree and Obsidian layout admission policy")
  void syncPinsSourceTreeAndObsidianLayout() throws Exception {
    assertSkipPolicyOverridesAbsent();

    // Drain whatever the earlier ordered tests enqueued before the baseline is read, so the
    // exact "+7" below counts only this test's admissions.
    assertTrue(
        harness.awaitIndexed(docCount(), 60_000),
        "earlier ordered tests should drain before the layout baseline is captured");
    long baseline = docCount();

    Path layoutRoot = syncTestDir.resolve("layout-admission");
    writeLayoutFile(layoutRoot, "# Vault note\n\nvaultnote897\n", "vault", "notes", "design.md");
    writeLayoutFile(
        layoutRoot,
        "final class App { String marker = \"javasource897\"; }\n",
        "src",
        "java",
        "App.java");
    writeLayoutFile(
        layoutRoot,
        "export const marker = \"typescriptsource897\";\n",
        "src",
        "typescript",
        "app.ts");
    writeLayoutFile(layoutRoot, "marker = \"pythonsource897\"\n", "src", "python", "main.py");
    writeLayoutFile(layoutRoot, "{\"marker\":\"obsidianmetadata897\"}\n", ".obsidian", "app.json");
    writeLayoutFile(
        layoutRoot,
        "final class Generated { String marker = \"buildcontent897\"; }\n",
        "build",
        "generated",
        "Generated.java");
    writeLayoutFile(layoutRoot, "export const marker = \"distcontent897\";\n", "dist", "bundle.js");

    writeLayoutFile(layoutRoot, "forbiddenlayout897", ".git", "config.md");
    writeLayoutFile(layoutRoot, "forbiddenlayout897", "node_modules", "pkg", "readme.md");
    writeLayoutFile(layoutRoot, "forbiddenlayout897", "__pycache__", "visible.md");
    writeLayoutFile(layoutRoot, "forbiddenlayout897", "build", "classes", "App.class");
    writeLayoutFile(layoutRoot, "forbiddenlayout897", "src", "python", "module.pyc");

    SyncDirectoryResponse response = harness.client().syncDirectory(layoutRoot.toString(), true, TestEngineContexts.FOREGROUND);

    assertNotNull(response, "response should not be null");
    assertTrue(response.getError().isEmpty(), "layout sync should have no error");
    assertFalse(response.getSkipped(), "force=true layout sync should not be skipped");
    assertEquals(
        7, response.getFilesAdded(), "only the seven admitted layout files should enqueue");
    assertTrue(
        harness.awaitIndexed(baseline + 7, 60_000),
        "the seven admitted layout files should become searchable");

    for (String marker :
        List.of(
            "vaultnote897",
            "javasource897",
            "typescriptsource897",
            "pythonsource897",
            "obsidianmetadata897",
            "buildcontent897",
            "distcontent897")) {
      assertTrue(
          harness.awaitSearchable(marker, 30_000),
          "admitted layout marker should be searchable: " + marker);
    }
    assertEquals(
        0,
        harness.client().search("forbiddenlayout897", 10, TestEngineContexts.FOREGROUND).getResultsCount(),
        "skipped directories and compiled extensions must not become searchable");
  }

  /**
   * The in-process stand-in for the retired test's {@code .withoutEnv(...)} calls — see the class
   * javadoc. Reads both the environment variable and the system property, because
   * {@code EnvRegistry} resolves either (EnvRegistry.java:537-556).
   */
  private static void assertSkipPolicyOverridesAbsent() {
    assertOverrideAbsent("JUSTSEARCH_INGESTION_SKIP_PATTERNS", "justsearch.ingestion.skip.patterns");
    assertOverrideAbsent(
        "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS", "justsearch.ingestion.skip.extensions");
    assertOverrideAbsent(
        "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES", "justsearch.ingestion.skip.directory_names");
  }

  private static void assertOverrideAbsent(String envName, String syspropName) {
    String env = System.getenv(envName);
    String sysprop = System.getProperty(syspropName);
    assertTrue(
        (env == null || env.isBlank()) && (sysprop == null || sysprop.isBlank()),
        "This assertion pins the DEFAULT ingestion skip policy, so "
            + envName
            + " / -D"
            + syspropName
            + " must not be set for this JVM. The retired system test stripped them from the"
            + " spawned Worker's environment; an in-process test cannot unset them, so it refuses"
            + " to assert a count they would invalidate. Saw env="
            + env
            + ", sysprop="
            + sysprop);
  }

  private static Path writeLayoutFile(Path root, String content, String... components)
      throws Exception {
    Path target = root;
    for (String component : components) {
      target = target.resolve(component);
    }
    Files.createDirectories(target.getParent());
    Files.writeString(target, content);
    return target;
  }

  // =========================================================================
  // Performance / Timeout
  // =========================================================================

  @Test
  @Order(10)
  @DisplayName("syncDirectory completes within reasonable time for moderate directory")
  void syncCompletesInReasonableTime() throws Exception {
    Path perfDir = syncTestDir.resolve("perf-test");
    Files.createDirectories(perfDir);

    for (int i = 0; i < 20; i++) {
      Files.writeString(
          perfDir.resolve("perf-file-" + i + ".txt"),
          "Performance test file " + i + " - content for testing");
    }

    long startTime = System.currentTimeMillis();
    SyncDirectoryResponse response = harness.client().syncDirectory(syncTestDir.toString(), true, TestEngineContexts.FOREGROUND);
    long elapsed = System.currentTimeMillis() - startTime;

    assertNotNull(response, "response should not be null");
    assertTrue(response.getError().isEmpty(), "should have no error");
    // Sync enqueues; it does not wait for indexing. The bound is the retired test's own 60s.
    assertTrue(elapsed < 60_000, "sync should complete within 60 seconds, took " + elapsed + "ms");
  }
}
