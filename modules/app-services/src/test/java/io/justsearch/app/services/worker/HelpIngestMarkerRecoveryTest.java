/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The recovery contract for the {@code help-ingested-version} durable store
 * ({@code governance/store-recoverability.v1.json}): {@code recoverability: DERIVED},
 * {@code upgradeHandling: REBUILD}, {@code corruptionPolicy: REGENERATE_BEFORE_CONSUMPTION}.
 *
 * <p><b>Why this file exists, which is the interesting part.</b> This marker was a durable artifact
 * with no register row and no recovery test for its whole life, and the register did not notice
 * because {@code KnowledgeServerBootstrap.java} appeared in the {@code implementationSources} of a
 * DIFFERENT row — the retired {@code worker-config-snapshot} store, which listed the file because
 * it handled the snapshot. The coverage check sees files, so a file classified for one reason reads
 * as classified for every reason. Lane F item A19 deleted that row and the hole surfaced
 * immediately. The gate was right on both occasions; the register was carrying a false negative.
 *
 * <p>The store's declared behaviour is that a marker which is absent, stale, or unreadable causes a
 * re-ingest rather than a skip or a failure — "regenerate before consumption" — and that a marker
 * matching the current version suppresses the work. Those are the two directions asserted here.
 * Neither was pinned before: {@code KnowledgeServerBootstrapEvalModeTest} covers the eval-mode gate
 * and the happy path, so a marker that was never read at all would have passed it.
 */
@DisplayName("help-ingest marker — recovery contract (store-recoverability)")
final class HelpIngestMarkerRecoveryTest {

  private static final String MARKER = ".help-ingested-version";

  @TempDir Path tempDir;

  @Test
  @DisplayName("a stale marker regenerates: the help files are re-ingested and the marker advances")
  void staleMarkerTriggersReingest() throws Exception {
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path workingDir = helpTreeUnder(tempDir.resolve("working"));
    // A marker left by an older app generation. REBUILD means this must not be trusted.
    Files.writeString(dataDir.resolve(MARKER), "v1");
    KnowledgeClient client = mock(KnowledgeClient.class);

    new KnowledgeServerBootstrap(configFor(dataDir, workingDir))
        .tryIngestHelpFiles(client, configFor(dataDir, workingDir));

    verify(client, times(1)).submitBatch(anyList(), anyBoolean(), anyString());
    assertTrue(
        Files.readString(dataDir.resolve(MARKER)).trim().length() > 0,
        "the marker must be rewritten to the current version, not left naming the old one");
    assertTrue(
        !"v1".equals(Files.readString(dataDir.resolve(MARKER)).trim()),
        "a stale marker that survives the re-ingest would make the next boot skip it again");
  }

  @Test
  @DisplayName("an unreadable marker regenerates rather than failing the boot")
  void unreadableMarkerRegenerates() throws Exception {
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path workingDir = helpTreeUnder(tempDir.resolve("working"));
    // Corruption, in the only shape a one-token file has: content that is not a known version.
    Files.writeString(dataDir.resolve(MARKER), "\0\0not-a-version\0");
    KnowledgeClient client = mock(KnowledgeClient.class);

    new KnowledgeServerBootstrap(configFor(dataDir, workingDir))
        .tryIngestHelpFiles(client, configFor(dataDir, workingDir));

    verify(client, times(1)).submitBatch(anyList(), anyBoolean(), anyString());
  }

  @Test
  @DisplayName("a current marker suppresses the work — the store must actually be read")
  void currentMarkerSuppressesReingest() throws Exception {
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path workingDir = helpTreeUnder(tempDir.resolve("working"));
    KnowledgeClient first = mock(KnowledgeClient.class);

    // Ingest once to learn the current version token rather than hardcoding it — a literal here
    // would silently stop matching the day HELP_FILES_VERSION is bumped, which is exactly the
    // moment this assertion needs to still mean something.
    new KnowledgeServerBootstrap(configFor(dataDir, workingDir))
        .tryIngestHelpFiles(first, configFor(dataDir, workingDir));
    String current = Files.readString(dataDir.resolve(MARKER)).trim();
    assertEquals(1, org.mockito.Mockito.mockingDetails(first).getInvocations().size());

    KnowledgeClient second = mock(KnowledgeClient.class);
    new KnowledgeServerBootstrap(configFor(dataDir, workingDir))
        .tryIngestHelpFiles(second, configFor(dataDir, workingDir));

    verify(second, never()).submitBatch(anyList(), anyBoolean(), anyString());
    assertEquals(
        current,
        Files.readString(dataDir.resolve(MARKER)).trim(),
        "a no-op boot must not rewrite the marker");
  }

  private static Path helpTreeUnder(Path workingDir) throws Exception {
    Path helpDir =
        Files.createDirectories(workingDir.resolve("SSOT").resolve("docs").resolve("help"));
    Files.writeString(helpDir.resolve("welcome.md"), "# Welcome\n");
    return workingDir;
  }

  private static KnowledgeServerConfig configFor(Path dataDir, Path workingDir) {
    return new KnowledgeServerConfig(
        /* isProduction */ false,
        /* dataDir */ dataDir,
        /* libDir */ dataDir,
        /* workingDirectory */ workingDir,
        /* deadlineMs */ 5_000L,
        /* portDiscoveryTimeoutMs */ 15_000L,
        /* maxRetries */ 3,
        /* workerShutdownTimeoutMs */ 5_000L,
        /* pidValidationTimeoutMs */ 5_000L,
        /* stabilityWindowMs */ 300_000L,
        /* batchSize */ 100,
        /* healthCheckRetryBudgetMs */ 0L,
        /* bootFaultInjectAttempts */ 0);
  }
}
