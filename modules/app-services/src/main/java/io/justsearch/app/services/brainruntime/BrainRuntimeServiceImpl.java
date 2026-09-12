/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.brainruntime;

import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.registry.operations.handlers.SetChatEnabledHandler;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import io.justsearch.app.services.settings.UiSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of {@link BrainRuntimeService}, extracted from
 * {@code InferenceHandlers} as part of tempdoc 519 §9 Block B3 / Step 3.
 */
public final class BrainRuntimeServiceImpl implements BrainRuntimeService {

  private static final Logger log = LoggerFactory.getLogger(BrainRuntimeServiceImpl.class);

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  private final EnterprisePolicyService enterprisePolicyService;
  private final java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger;
  // Tempdoc 737 fix pack (fix 4): the runtime-intent authority. switchInferenceMode records the
  // chat-enabled intent through these (spec write + reconciler nudge) instead of a raw switchTo*.
  // Nullable for graceful degradation / test seams that don't exercise the mode switch.
  private final RuntimeSpecStore runtimeSpecStore;
  private final RuntimeReconciler runtimeReconciler;

  public BrainRuntimeServiceImpl(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      EnterprisePolicyService enterprisePolicyService,
      java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger) {
    this(onlineAi, settingsStore, enterprisePolicyService, offlineProcessingTrigger, null, null);
  }

  /**
   * Tempdoc 737 fix pack (fix 4): threads the runtime-intent authority so
   * {@link #switchInferenceMode} routes through the same {@link SetChatEnabledHandler.RuntimeIntentWrite}
   * spec-write path as the {@code core.set-chat-enabled} / {@code core.switch-inference-mode}
   * operation handlers — one authority, no raw {@code switchTo*}.
   */
  public BrainRuntimeServiceImpl(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      EnterprisePolicyService enterprisePolicyService,
      java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger,
      RuntimeSpecStore runtimeSpecStore,
      RuntimeReconciler runtimeReconciler) {
    this.onlineAi = onlineAi;
    this.settingsStore = settingsStore;
    this.enterprisePolicyService = enterprisePolicyService;
    this.offlineProcessingTrigger = offlineProcessingTrigger;
    this.runtimeSpecStore = runtimeSpecStore;
    this.runtimeReconciler = runtimeReconciler;
  }

  @Override
  public String reloadInference() throws Exception {
    if (!(onlineAi instanceof OnlineAiRuntimeControl control)) {
      throw new IllegalStateException("Inference runtime control unavailable");
    }
    if (settingsStore == null) {
      throw new IllegalStateException("Settings store unavailable");
    }
    if (enterprisePolicyService != null) {
      try {
        enterprisePolicyService.snapshot();
      } catch (Exception ignored) {
        // best-effort; do not fail reload on policy snapshot errors
      }
    }
    UiSettings s = settingsStore.load();
    control.applyRuntimeOverrides(
        s.getLlmModelPath(),
        s.getContextLength(),
        s.getGpuLayers(),
        OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);
    return onlineAi.getCurrentMode();
  }

  @Override
  public java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>
      triggerOfflineProcessing(io.justsearch.core.context.EngineContext context,
          java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome> progress) {
    if (offlineProcessingTrigger == null) {
      throw new UnsupportedOperationException("Offline processing not available");
    }
    log.info("Triggering offline processing (VDU + Embeddings)");
    return offlineProcessingTrigger.apply(context, progress);
  }

  /**
   * Records a chat-enabled intent (tempdoc 737 §12b/fix 4). {@code online} → {@code chatEnabled=true},
   * {@code indexing} → {@code chatEnabled=false}, routed through the ONE runtime-intent authority
   * ({@link SetChatEnabledHandler.RuntimeIntentWrite}: spec write + {@code reconciler.specChanged()})
   * rather than a raw {@code switchTo*} — so it cannot re-introduce the §3b circular denial and the
   * mode transition is the reconciler's business.
   *
   * <p><b>Async semantics:</b> the intent write returns immediately; the engine converges toward the
   * new spec on the reconciler thread and may still be transitioning when this method returns.
   * Tempdoc 804 §B6: the outcome therefore carries the live {@code getCurrentMode()} <i>and</i>
   * whether it already equals what was requested — a bare live-mode read was being reported as if
   * it were the transition's result. Enterprise online-AI / GPU enforcement is a convergence ceiling
   * inside the reconciler, not an intent-time denial (§12b).
   */
  @Override
  public ModeTransitionOutcome switchInferenceMode(String mode) throws Exception {
    if (mode == null || mode.isBlank()) {
      throw new IllegalArgumentException("Missing 'mode' field");
    }
    boolean enabled;
    if ("online".equalsIgnoreCase(mode)) {
      enabled = true;
    } else if ("indexing".equalsIgnoreCase(mode)) {
      enabled = false;
    } else {
      throw new IllegalArgumentException("Invalid mode. Use 'online' or 'indexing'");
    }
    if (runtimeSpecStore == null || runtimeReconciler == null) {
      throw new IllegalStateException("Runtime authority unavailable (AI runtime not configured)");
    }
    SetChatEnabledHandler.RuntimeIntentWrite.writeIntent(runtimeSpecStore, runtimeReconciler, enabled);
    return ModeTransitionOutcome.of(mode, onlineAi.getCurrentMode());
  }
}
