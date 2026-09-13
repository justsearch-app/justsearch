/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.observability.runtime.RuntimeContext;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.infra.health.InfraHealthAggregator;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: small static helpers extracted from {@code HeadAssembly} that
 * have no instance state. Grouped here to reduce bootstrap NCSS without spawning a separate
 * file per 5-line helper.
 */
public final class BootstrapHelpers {

  private static final Logger log = LoggerFactory.getLogger(BootstrapHelpers.class);

  private BootstrapHelpers() {}

  /** Log the AI services configuration at startup for debuggability. */
  public static void logAiServicesConfiguration(
      OnlineAiService onlineAiService,
      InferenceLifecycleManager inferenceManager,
      KnowledgeClient knowledgeClient,
      AgentService agentService) {
    log.info("=== AI Services Configuration ===");
    log.info("  OnlineAiService: {}", onlineAiService.getClass().getSimpleName());
    log.info("  InferenceManager: {}", inferenceManager != null ? "ACTIVE" : "DISABLED");
    log.info("  KnowledgeClient: {}", knowledgeClient != null ? "CONNECTED" : "UNAVAILABLE");
    if (inferenceManager != null) {
      log.info("  LLM Mode: {}", inferenceManager.getCurrentMode());
    }
    boolean isOnlineAiUnavailable = inferenceManager == null;
    log.info("  Endpoint Behavior:");
    log.info(
        "    /api/chat/batch-summarize: {} ({})",
        onlineAiService.getClass().getSimpleName(),
        isOnlineAiUnavailable ? "unavailable" : "active with llama-server");
    log.info(
        "  AgentService: {} ({} tools)",
        agentService.isAvailable() ? "AVAILABLE" : "UNAVAILABLE",
        agentService.availableOperations().size());
    log.info("=================================");
  }

  /** Build the {@link InfraHealthAggregator.Config} from a {@link ResolvedConfig.InfraHealth}. */
  public static InfraHealthAggregator.Config toInfraHealthConfig(ResolvedConfig.InfraHealth ih) {
    return new InfraHealthAggregator.Config(
        Duration.ofMillis(ih.pollIntervalMs()),
        Duration.ofMillis(ih.nrtStaleMs()),
        Duration.ofMillis(ih.translatorHandshakeStaleMs()),
        ih.annCacheReadyPercent());
  }

  /**
   * The i18n catalogs the ONE registry message resolver reads. Tempdoc 876 §B.3: the emitter
   * resolves {@code presentation().descriptionKey()} for every operation it offers the model, and
   * the offering includes operations PROJECTED from workflows ({@code WorkflowOperationProjection}
   * carries the Workflow's own Presentation through), whose keys live in the workflow catalog. A
   * resolver over only the operation catalog handed the model the literal key
   * {@code registry-workflow.research-brief.description} as a tool description. The two key
   * namespaces are disjoint ({@code ops.*} vs {@code registry-workflow.*}), so this is a union.
   */
  private static final java.util.List<String> REGISTRY_MESSAGE_RESOURCES =
      java.util.List.of(
          "/messages/registry-operation.en.properties", "/messages/registry-workflow.en.properties");

  /**
   * Load the union of the registry i18n properties files the agent offering resolves against. A
   * catalog missing from the classpath fails loudly at boot rather than degrading every one of its
   * descriptions into a raw key.
   */
  public static Properties loadRegistryMessages() {
    Properties p = new Properties();
    for (String resource : REGISTRY_MESSAGE_RESOURCES) {
      loadInto(p, resource);
    }
    return p;
  }

  private static void loadInto(Properties into, String resource) {
    try (InputStream is = BootstrapHelpers.class.getResourceAsStream(resource);
        InputStreamReader r =
            new InputStreamReader(
                Objects.requireNonNull(is, resource + " not on classpath"),
                StandardCharsets.UTF_8)) {
      into.load(r);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load registry messages from " + resource, e);
    }
  }

  /** Initial {@link RuntimeContext} read from system property (eval mode) + ConfigStore. */
  public static RuntimeContext initialRuntimeContext() {
    io.justsearch.app.observability.runtime.SystemMode systemMode =
        Boolean.getBoolean("justsearch.eval.mode")
            ? io.justsearch.app.observability.runtime.SystemMode.EVAL
            : io.justsearch.app.observability.runtime.SystemMode.PRODUCTION;
    ResolvedConfig rc = currentResolvedConfig();
    boolean automationEnabled = rc != null && rc.ui().automationEnabled();
    return new RuntimeContext(systemMode, automationEnabled);
  }

  /**
   * Resolve the occurrence-log ring buffer size from system property /
   * JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER env var. Falls back to
   * {@code OccurrenceLog.DEFAULT_CAPACITY} on null/blank/non-positive/invalid input.
   */
  public static int resolveOccurrenceBufferSize() {
    String raw =
        System.getProperty(
            "justsearch.health.occurrence.buffer",
            System.getenv("JUSTSEARCH_HEALTH_OCCURRENCE_BUFFER"));
    if (raw == null || raw.isBlank()) {
      return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
    }
    try {
      int parsed = Integer.parseInt(raw.trim());
      if (parsed <= 0) {
        return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
      }
      return parsed;
    } catch (NumberFormatException e) {
      return io.justsearch.app.observability.health.OccurrenceLog.DEFAULT_CAPACITY;
    }
  }

  /**
   * Tempdoc 519 §10 final-push: extracted from {@code HeadAssembly.configureAutomationDiagnostics}.
   * Applies the diagnostics override sliders ({@code nrtLag=15_000ms},
   * {@code translatorHandshake=5min ago}, {@code annReady=5}) when automation + force-diagnostics
   * are both enabled in config. Returns true when the overrides were applied.
   */
  public static boolean configureAutomationDiagnostics(
      io.justsearch.app.observability.InfraDiagnosticsService diagnosticsService) {
    ResolvedConfig rc = currentResolvedConfig();
    if (rc == null) return false;
    boolean automationEnabled = rc.ui().automationEnabled();
    if (!automationEnabled) return false;
    boolean forceDiagnostics = rc.ui().forceDiagnostics();
    if (!forceDiagnostics) {
      log.info("Automation diagnostics overrides disabled via automation flag.");
      return false;
    }
    log.info(
        "Automation diagnostics overrides enabled (simulating degraded translator + cold ANN cache).");
    diagnosticsService.setNrtLagSupplier(() -> 15_000L);
    diagnosticsService.setTranslatorHandshakeSupplier(
        () -> java.time.Instant.now().minus(Duration.ofMinutes(5)));
    diagnosticsService.setAnnReadySupplier(() -> 5);
    return true;
  }

  /** First non-blank value from the supplied list, or null if none. */
  public static String chooseFirstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) {
        return v;
      }
    }
    return null;
  }

  /** Resolve the JustSearch home directory (CWD by default). */
  public static Path getJustSearchHome() {
    return io.justsearch.app.services.bootstrap.BootstrapInferenceFactory.getJustSearchHome(
        currentResolvedConfig(), System.getProperty("user.dir"));
  }

  /** Get the current {@link ResolvedConfig} from {@link ConfigStore#globalOrNull()}, or null. */
  public static ResolvedConfig currentResolvedConfig() {
    ConfigStore store = ConfigStore.globalOrNull();
    return store != null ? store.get() : null;
  }
}
