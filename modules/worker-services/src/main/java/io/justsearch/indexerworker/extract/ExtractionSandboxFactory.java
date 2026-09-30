/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.core.execution.EngineExecutorRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Builds selectable extraction sandboxes without coupling callers to sandbox implementation
 * classes. Tempdoc 417 post-merge: takes an {@link ExtractionMetricCatalog} (catalog-substrate)
 * instead of the legacy {@code Telemetry} handle.
 *
 * <p>Tempdoc 885 item 14 added {@link Mode#AUTO} — per-family routing between the two — and made
 * it the shipped default. {@link Mode#PROCESS} now means the {@link PersistentExtractionSandbox}
 * pool, not a child JVM per file.
 */
public final class ExtractionSandboxFactory {
  private ExtractionSandboxFactory() {}

  public enum Mode {
    IN_PROCESS,
    PROCESS,
    AUTO
  }

  /**
   * How much longer than the sandbox deadline the outer {@link TimeboxedContentExtractor} waits
   * when a child pool is in play.
   *
   * <p>Both layers used to enforce the same duration, and the outer one starts its clock first, so
   * it ALWAYS won: every wedged child was reported as an interrupted wait rather than as a sandbox
   * timeout, and the pool's own kill-at-the-deadline path — the designed mechanism — never ran.
   * The chaos tier caught this (tempdoc 885 §SC-chaos); the unit tests could not, because they
   * drive the pool directly with no timebox around it. The grace has to cover the kill itself:
   * {@code destroyForcibly} + a 5 s {@code waitFor} + a 2 s stderr-drain join, plus slack.
   */
  static final Duration PROCESS_TIMEBOX_GRACE = Duration.ofSeconds(15);

  /** Pool sizing + leak-guard settings for the out-of-process families. */
  public record PoolSettings(int poolSize, int maxRequestsPerChild) {
    public static PoolSettings defaults() {
      return new PoolSettings(1, PersistentExtractionSandbox.DEFAULT_MAX_REQUESTS_PER_CHILD);
    }

    public PoolSettings {
      poolSize = poolSize > 0 ? poolSize : 1;
      maxRequestsPerChild =
          maxRequestsPerChild > 0
              ? maxRequestsPerChild
              : PersistentExtractionSandbox.DEFAULT_MAX_REQUESTS_PER_CHILD;
    }
  }

  public static TimeboxedContentExtractor create(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      EngineExecutorRegistry.Registration readerRegistration,
      Mode mode,
      TikaExtractionPolicy policy,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      List<String> processCommand) {
    return create(ocrRegistration, timeboxRegistration, readerRegistration, mode, policy,
        OcrRoutingConfig.disabled(), timeout, catalog, OcrMetricCatalog.noop(), processCommand);
  }

  public static TimeboxedContentExtractor create(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      EngineExecutorRegistry.Registration readerRegistration,
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      List<String> processCommand) {
    return create(ocrRegistration, timeboxRegistration, readerRegistration, mode, policy, ocrConfig, timeout,
        catalog, OcrMetricCatalog.noop(), processCommand);
  }

  public static TimeboxedContentExtractor create(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      EngineExecutorRegistry.Registration readerRegistration,
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrMetricCatalog,
      List<String> processCommand) {
    return create(
        ocrRegistration,
        timeboxRegistration,
        readerRegistration,
        mode,
        policy,
        ocrConfig,
        timeout,
        catalog,
        ocrMetricCatalog,
        processCommand,
        PoolSettings.defaults());
  }

  public static TimeboxedContentExtractor create(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      EngineExecutorRegistry.Registration readerRegistration,
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrMetricCatalog,
      List<String> processCommand,
      PoolSettings poolSettings) {
    return create(ocrRegistration, timeboxRegistration, readerRegistration, mode, policy, ocrConfig, timeout,
        catalog, ocrMetricCatalog, processCommand, poolSettings,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static TimeboxedContentExtractor create(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      EngineExecutorRegistry.Registration readerRegistration,
      Mode mode,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      ExtractionMetricCatalog catalog,
      OcrMetricCatalog ocrMetricCatalog,
      List<String> processCommand,
      PoolSettings poolSettings,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    TikaExtractionPolicy effectivePolicy = policy == null ? TikaExtractionPolicy.defaults() : policy;
    OcrRoutingConfig effectiveOcrConfig =
        (ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig)
            .withWorkerLimit(ocrRegistration.spec().threadCount());
    Duration effectiveTimeout =
        timeout == null ? TimeboxedContentExtractor.DEFAULT_TIMEOUT : timeout;
    PoolSettings effectivePool = poolSettings == null ? PoolSettings.defaults() : poolSettings;

    if (mode == Mode.IN_PROCESS) {
      return new TimeboxedContentExtractor(
          timeboxRegistration,
          inProcessSandbox(ocrRegistration, effectivePolicy, effectiveOcrConfig, ocrMetricCatalog),
          effectiveTimeout,
          catalog);
    }
    ExtractionSandbox pool =
        new PersistentExtractionSandbox(
            readerRegistration,
            processCommand,
            effectivePolicy,
            effectiveOcrConfig,
            effectiveTimeout,
            effectivePool.poolSize(),
            effectivePool.maxRequestsPerChild(),
            catalog,
            childRegistry);
    // The sandbox owns the deadline; the timebox is only a backstop for a sandbox that itself
    // wedges. See PROCESS_TIMEBOX_GRACE.
    Duration backstop = effectiveTimeout.plus(PROCESS_TIMEBOX_GRACE);
    try {
      if (mode == Mode.PROCESS) {
        return new TimeboxedContentExtractor(timeboxRegistration, pool, backstop, catalog);
      }
      ContentExtractorProvider provider =
          contributionProvider(ocrRegistration, effectivePolicy, effectiveOcrConfig, ocrMetricCatalog);
      return new TimeboxedContentExtractor(
          timeboxRegistration,
          new RoutingExtractionSandbox(new InProcessExtractionSandbox(provider), pool, provider),
          backstop,
          catalog);
    } catch (RuntimeException | Error failure) {
      try {
        pool.close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /**
   * Deadline for the startup probe's single extraction, because a broken command must not stall
   * Worker boot.
   *
   * <p>This is NOT the whole worst case: a child that hangs is then killed, and that path waits up
   * to 5 s on {@code Process.waitFor}. Boot therefore blocks for at most ~25 s, and only against a
   * child that launches and then hangs; the far commoner failure — a command that cannot launch at
   * all — is rejected by {@code ProcessBuilder.start} at once.
   */
  public static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

  private static final String PROBE_MARKER = "justsearch extraction sandbox probe";

  /**
   * Spawns one child on {@code command} and runs a trivial extraction through it.
   *
   * <p>The pool spawns lazily, which is right for steady state but means a broken child command —
   * a bad operator override, a missing JDK, an unreadable classpath — is invisible until the first
   * real process-routed file. This turns that into one bounded check at wiring time so the Worker
   * can report the degraded boundary immediately. The verdict is diagnostic: callers must retain
   * configured routing rather than silently moving untrusted parsing into the Engine JVM.
   *
   * @return empty when the child launched and answered correctly, else the reason it did not
   */
  public static Optional<String> probeChildCommand(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout) {
    return probeChildCommand(readerRegistration, command, policy, ocrConfig, timeout,
        io.justsearch.app.api.runtime.ManagedChildRegistry.noop());
  }

  public static Optional<String> probeChildCommand(
      EngineExecutorRegistry.Registration readerRegistration,
      List<String> command,
      TikaExtractionPolicy policy,
      OcrRoutingConfig ocrConfig,
      Duration timeout,
      io.justsearch.app.api.runtime.ManagedChildRegistry childRegistry) {
    Path probeFile = null;
    try {
      // Scratch, not state: a JVM temp file written and deleted inside this method, outside the
      // data dir, holding a fixed marker string. It is classified in
      // governance/store-recoverability.v1.json under nonDurableWriteSites for that reason - there
      // is no recovery, upgrade or encryption policy to state, because losing it costs nothing.
      probeFile = Files.createTempFile("justsearch-extraction-probe-", ".txt");
      Files.writeString(probeFile, PROBE_MARKER, StandardCharsets.UTF_8);
      // maxRequestsPerChild = 1: this child is for the probe alone and is discarded with the pool.
      try (PersistentExtractionSandbox sandbox =
          new PersistentExtractionSandbox(readerRegistration, command, policy, ocrConfig, timeout,
              1, 1, null,
              childRegistry)) {
        String content = sandbox.extract(probeFile).result().content();
        if (content == null || !content.contains(PROBE_MARKER)) {
          return Optional.of("child answered without the probe content");
        }
        return Optional.empty();
      }
    } catch (IOException | ContentExtractor.ExtractionException | RuntimeException e) {
      return Optional.of(e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      if (probeFile != null) {
        try {
          Files.deleteIfExists(probeFile);
        } catch (IOException e) {
          // Temp file; the OS reclaims it.
        }
      }
    }
  }

  private static ExtractionSandbox inProcessSandbox(
      EngineExecutorRegistry.Registration ocrRegistration,
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return new InProcessExtractionSandbox(contributionProvider(ocrRegistration, policy, ocrConfig, ocrMetricCatalog));
  }

  // Tempdoc 560 §4.4/§6: the in-process extractor is pulled through the Worker's contribution
  // composer (the content extractor as a real first consumer of the substrate). The default
  // composition is a single CORE Tika catch-all, so this is behaviorally identical to the direct
  // delegate — but the extractor now IS a declared, composable contribution.
  private static ContentExtractorProvider contributionProvider(
      EngineExecutorRegistry.Registration ocrRegistration,
      TikaExtractionPolicy policy, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return ExtractorContributionRegistry.withCoreTika(
        new PolicyDrivenTikaExtractor(
            workers -> ocrRegistration.open(Thread.ofPlatform().daemon().name("pdf-ocr-", 0).factory()),
            policy, ocrConfig.withWorkerLimit(ocrRegistration.spec().threadCount()), ocrMetricCatalog));
  }

  public static TimeboxedContentExtractor inProcessStructured(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      ExtractionMetricCatalog catalog) {
    return inProcessStructured(ocrRegistration, timeboxRegistration, catalog, OcrRoutingConfig.disabled());
  }

  public static TimeboxedContentExtractor inProcessStructured(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      ExtractionMetricCatalog catalog, OcrRoutingConfig ocrConfig) {
    return inProcessStructured(ocrRegistration, timeboxRegistration, catalog, ocrConfig, OcrMetricCatalog.noop());
  }

  public static TimeboxedContentExtractor inProcessStructured(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      ExtractionMetricCatalog catalog, OcrRoutingConfig ocrConfig, OcrMetricCatalog ocrMetricCatalog) {
    return inProcessStructured(ocrRegistration, timeboxRegistration, catalog, ocrConfig, ocrMetricCatalog,
        TikaExtractionPolicy.defaults());
  }

  /** As above, with an explicit policy (tempdoc 799 §N.2 — operator worker.limits.*). */
  public static TimeboxedContentExtractor inProcessStructured(
      EngineExecutorRegistry.Registration ocrRegistration,
      EngineExecutorRegistry.Registration timeboxRegistration,
      ExtractionMetricCatalog catalog,
      OcrRoutingConfig ocrConfig,
      OcrMetricCatalog ocrMetricCatalog,
      TikaExtractionPolicy policy) {
    TikaExtractionPolicy effectivePolicy =
        policy == null ? TikaExtractionPolicy.defaults() : policy;
    OcrRoutingConfig effectiveOcrConfig =
        (ocrConfig == null ? OcrRoutingConfig.disabled() : ocrConfig)
            .withWorkerLimit(ocrRegistration.spec().threadCount());
    return new TimeboxedContentExtractor(
        timeboxRegistration,
        inProcessSandbox(ocrRegistration, effectivePolicy, effectiveOcrConfig, ocrMetricCatalog),
        TimeboxedContentExtractor.DEFAULT_TIMEOUT,
        catalog);
  }
}
