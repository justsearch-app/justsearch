/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.brainruntime;

import io.justsearch.app.api.BrainRuntimeService;
import io.justsearch.app.api.ModeTransitionOutcome;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.services.registry.operations.handlers.SetChatEnabledHandler;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeSpecStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production implementation of {@link BrainRuntimeService}, extracted from
 * {@code InferenceHandlers} as part of tempdoc 519 §9 Block B3 / Step 3.
 */
public final class BrainRuntimeServiceImpl implements BrainRuntimeService {

  private static final Logger log = LoggerFactory.getLogger(BrainRuntimeServiceImpl.class);

  private final OnlineAiService onlineAi;
  private final java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger;
  // Tempdoc 737 fix pack (fix 4): the runtime-intent authority. switchInferenceMode records the
  // chat-enabled intent through these (spec write + reconciler nudge) instead of a raw switchTo*.
  // Nullable for graceful degradation / test seams that don't exercise the mode switch.
  private final RuntimeSpecStore runtimeSpecStore;
  private final RuntimeReconciler runtimeReconciler;

  public BrainRuntimeServiceImpl(
      OnlineAiService onlineAi,
      java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger) {
    this(onlineAi, offlineProcessingTrigger, null, null);
  }

  /**
   * Tempdoc 737 fix pack (fix 4): threads the runtime-intent authority so
   * {@link #switchInferenceMode} routes through the same {@link SetChatEnabledHandler.RuntimeIntentWrite}
   * spec-write path as the {@code core.set-chat-enabled} / {@code core.switch-inference-mode}
   * operation handlers — one authority, no raw {@code switchTo*}.
   */
  public BrainRuntimeServiceImpl(
      OnlineAiService onlineAi,
      java.util.function.BiFunction<io.justsearch.core.context.EngineContext, java.util.function.Consumer<io.justsearch.app.api.OfflineProcessingOutcome>, java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome>> offlineProcessingTrigger,
      RuntimeSpecStore runtimeSpecStore,
      RuntimeReconciler runtimeReconciler) {
    this.onlineAi = onlineAi;
    this.offlineProcessingTrigger = offlineProcessingTrigger;
    this.runtimeSpecStore = runtimeSpecStore;
    this.runtimeReconciler = runtimeReconciler;
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
  public ModeTransitionOutcome switchInferenceMode(String mode,
      io.justsearch.core.context.EngineContext context, String idempotencyKey) throws Exception {
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
    var observed = new java.util.concurrent.atomic.AtomicReference<ModeTransitionOutcome>();
    var result = runtimeSpecStore.writeIntent(enabled, context, idempotencyKey, () -> {
      runtimeReconciler.specChanged();
      observed.set(ModeTransitionOutcome.of(mode, onlineAi.getCurrentMode()));
    });
    if (!result.response().success()) {
      var response = result.response();
      var metadata = new java.util.LinkedHashMap<>(response.structuredData());
      metadata.put("operationKey", result.record().key());
      metadata.put("operationRecordId", result.record().id());
      throw new io.justsearch.app.api.settings.SettingsCommitOwner.Refused(
          new io.justsearch.agent.api.registry.OperationResult(false, response.message(), response.executionId(),
              metadata, response.errorCode(), response.errorDetails(), response.retryable()));
    }
    var outcome = observed.get();
    if (outcome == null) outcome = ModeTransitionOutcome.of(mode, null);
    if (result.record().state() != io.justsearch.app.api.operations.OperationState.COMPLETE) {
      outcome = new ModeTransitionOutcome(outcome.requested(), null, ModeTransitionOutcome.STATE_ACCEPTED);
    }
    var revision = result.response().structuredData().get("acceptedRevision");
    return outcome.withReceipt(result.record().key(), revision instanceof Number value ? value.longValue() : null);
  }
}
