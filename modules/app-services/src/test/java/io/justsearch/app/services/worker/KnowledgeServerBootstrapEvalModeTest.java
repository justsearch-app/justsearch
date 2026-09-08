package io.justsearch.app.services.worker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression tests for the eval-mode gate in {@link KnowledgeServerBootstrap#tryIngestHelpFiles}.
 *
 * <p>Covers two scenarios:
 * <ul>
 *   <li>{@code justsearch.eval.mode=true}: the skip path fires; no marker written, no submitBatch
 *       invocation on the client.</li>
 *   <li>{@code justsearch.eval.mode=false} (default): the existing behavior still runs — help files
 *       are submitted and the version marker is written.</li>
 * </ul>
 *
 * <p>The inverse case is important as an anti-regression: an accidental {@code return;} at the top
 * of the method would silently pass the skip test but break production.
 *
 * <p><b>Smoke verification.</b> An end-to-end smoke is available via
 * {@code ./gradlew :modules:ui:runHeadless} — {@code workingDirectory} resolves to the repo root
 * where {@code SSOT/docs/help/} is tracked (5 .md files). The unit-test inverse below remains the
 * cheap regression gate; the smoke confirms the gate reflects production behavior. Verified
 * 2026-04-23: marker file appears, app.log contains {@code "Ingested 5 built-in help files"}.
 */
@DisplayName("KnowledgeServerBootstrap eval-mode help-ingest gate")
final class KnowledgeServerBootstrapEvalModeTest {

  private static final String EVAL_MODE_PROP = "justsearch.eval.mode";

  @TempDir Path tempDir;
  private String priorEvalModeValue;

  @BeforeEach
  void rememberEvalModeProperty() {
    priorEvalModeValue = System.getProperty(EVAL_MODE_PROP);
  }

  @AfterEach
  void restoreEvalModeProperty() {
    if (priorEvalModeValue == null) {
      System.clearProperty(EVAL_MODE_PROP);
    } else {
      System.setProperty(EVAL_MODE_PROP, priorEvalModeValue);
    }
  }

  @Test
  @DisplayName("eval.mode=true: skip fires, no marker, no submitBatch")
  void skipsHelpIngestWhenEvalModeIsTrue() throws Exception {
    System.setProperty(EVAL_MODE_PROP, "true");
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    KnowledgeServerConfig config = configFor(dataDir, tempDir.resolve("working"));
    KnowledgeClient client = mock(KnowledgeClient.class);

    KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap(config);
    bootstrap.tryIngestHelpFiles(client, config);

    assertFalse(
        Files.exists(dataDir.resolve(".help-ingested-version")),
        "Marker file must NOT be written when eval.mode=true");
    verify(client, never()).submitBatch(any(), anyBoolean(), anyString());
  }

  @Test
  @DisplayName("eval.mode=false: help files ingest, marker written")
  void ingestsHelpFilesWhenEvalModeIsFalse() throws Exception {
    System.clearProperty(EVAL_MODE_PROP);
    Path dataDir = Files.createDirectories(tempDir.resolve("data"));
    Path workingDir = tempDir.resolve("working");
    Path helpDir = Files.createDirectories(workingDir.resolve("SSOT").resolve("docs").resolve("help"));
    Files.writeString(helpDir.resolve("welcome.md"), "# Welcome\n\nSample help content.\n");
    Files.writeString(helpDir.resolve("search.md"), "# Search\n\nTips for searching.\n");
    KnowledgeServerConfig config = configFor(dataDir, workingDir);

    // Don't stub submitBatch — default null return is fine; production ignores the return value.
    KnowledgeClient client = mock(KnowledgeClient.class);

    KnowledgeServerBootstrap bootstrap = new KnowledgeServerBootstrap(config);
    bootstrap.tryIngestHelpFiles(client, config);

    assertTrue(
        Files.exists(dataDir.resolve(".help-ingested-version")),
        "Marker file must be written after successful ingest");
    // Production calls submitBatch exactly once with the full path list — no batching at this layer.
    verify(client, times(1)).submitBatch(anyList(), anyBoolean(), anyString());
  }

  /** Build a minimal KnowledgeServerConfig pointing at the temp directories. */
  private static KnowledgeServerConfig configFor(Path dataDir, Path workingDir) {
    return new KnowledgeServerConfig(
        /* isProduction */ false,
        /* dataDir */ dataDir,
        /* libDir */ dataDir, // unused by tryIngestHelpFiles
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
