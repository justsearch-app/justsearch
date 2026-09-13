/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.inference.InferenceLifecycleManager;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 519 §7 / Step 7: GPU-status broadcast wiring + Online Mode auto-start helpers
 * extracted from {@code HeadAssembly}. Bridges InferenceLifecycleManager mode changes to the
 * in-process {@code GpuSchedulingGauge} so the index half can pause/resume GPU-accelerated
 * embeddings when the LLM activates/deactivates. (Before lane F item A5 the bridge wrote a
 * memory-mapped byte through {@code MainSignalBus}; item A10 deleted that bus.)
 */
public final class InferenceWiring {

  private static final Logger log = LoggerFactory.getLogger(InferenceWiring.class);

  private InferenceWiring() {}

  /**
   * Wires the GPU-claimed signal from {@link InferenceLifecycleManager} to the index half. Returns
   * the registered listener so the caller can remove it on shutdown. The supplier follows async
   * index startup and reconnects; an absent index service must not disable future publication.
   *
   * <p>Lane F item A5: the authority is the in-process {@code GpuSchedulingGauge}
   * ({@code KnowledgeServerBootstrap.gpuScheduling()}), which is where the merged Engine reads
   * {@code main_gpu_active} from. Item A11 removed the second publication — a memory-mapped byte
   * written for a Worker process that no longer exists — so the gauge write below is now the whole
   * broadcast, and there is one writer and one reader in one address space.
   */
  public static io.justsearch.app.api.ModeChangeListener wireGpuStatusBroadcast(
      InferenceLifecycleManager manager,
      java.util.function.Supplier<KnowledgeServerBootstrap> knowledgeServer) {
    io.justsearch.app.api.ModeChangeListener listener =
        (from, to) -> {
          refreshGpuStatus(manager, knowledgeServer.get());
        };
    manager.addModeChangeListener(listener);
    refreshGpuStatus(manager, knowledgeServer.get());
    log.info("GPU status broadcast wired to the current index service");
    return listener;
  }

  /** Seeds a newly connected index service, including when inference became online before it. */
  public static void refreshGpuStatus(
      InferenceLifecycleManager manager, KnowledgeServerBootstrap knowledgeServer) {
    if (manager == null || knowledgeServer == null) return;
    var gauge = knowledgeServer.gpuScheduling();
    // Read the current authority under the same lock as publication. A delayed mode callback or
    // connect-time seed must not overwrite a newer mode with the event's historical value.
    synchronized (gauge) {
      boolean gpuActive = manager.isOnline();
      gauge.setMainGpuActive(gpuActive);
      log.debug("GPU status broadcast: {}", gpuActive ? "ACTIVE" : "FREE");
    }
  }

  /**
   * Tempdoc 737 Phase 1: replaces the former {@code tryStartOnlineMode} direct-switch autostart.
   * The env autostart flags now SEED the persisted {@link RuntimeSpecStore} desired-state rather
   * than driving {@code switchToOnlineMode} directly — the {@code RuntimeReconciler} then converges
   * the engine toward spec at boot. This is the env read (allowlisted here per
   * {@code AppServicesWorkerGuardrailsTest}); the actual transition is owned by the reconciler.
   *
   * <p>Semantics preserved: {@code JUSTSEARCH_AI_AUTOSTART_DISABLED=true} is an explicit operator
   * off (no seed); {@code JUSTSEARCH_AI_AUTOSTART_ENABLED=true} seeds {@code chatEnabled=true} only
   * when the user has never persisted an explicit choice (§12a — env at most seeds the spec).
   */
  public static void seedAutostartSpec(RuntimeSpecStore specStore) {
    if (specStore == null) {
      return;
    }
    boolean autoStartEnabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.autostart.enabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_AUTOSTART_ENABLED", "false")));
    boolean autoStartDisabled =
        Boolean.parseBoolean(
            System.getProperty(
                "justsearch.ai.autostart.disabled",
                System.getenv().getOrDefault("JUSTSEARCH_AI_AUTOSTART_DISABLED", "false")));
    if (autoStartDisabled) {
      log.info(
          "AI auto-start explicitly disabled by operator (JUSTSEARCH_AI_AUTOSTART_DISABLED=true);"
              + " runtime spec not seeded.");
      return;
    }
    if (!autoStartEnabled) {
      log.info(
          "AI auto-start not configured; engine follows the persisted runtime spec. Set"
              + " JUSTSEARCH_AI_AUTOSTART_ENABLED=true to seed chat-on for a fresh profile.");
      return;
    }
    boolean seeded = specStore.seedAutostartIfUnset();
    log.info(
        seeded
            ? "AI auto-start seeded runtime spec chatEnabled=true (fresh profile)."
            : "AI auto-start requested but user already has an explicit chat preference; not overriding.");
  }
}
