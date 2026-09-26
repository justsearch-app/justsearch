/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.vdu;

import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.core.execution.EngineExecutorRegistry;
import io.justsearch.core.execution.EngineExecutorSpec;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tempdoc 672 follow-up — Head-side counterpart to {@code GpuSaturationSampler}'s shape (same
 * single-thread {@link ScheduledExecutorService}, same defensive posture), periodically
 * evaluating {@link VduPacingPolicy} and auto-triggering {@code
 * OfflineCoordinator.startOfflineProcessing} when idle/energy/exclusivity conditions allow and
 * VDU work is actually pending.
 *
 * <p>Deliberately gates on {@code coordinator.getPendingVduCount() > 0}, not the broader {@code
 * hasPendingWork()} (which also covers embeddings) — embedding backfill already has its own
 * autonomous, idle-aware trigger on the Worker side (tempdoc 630's {@code LoopPacingPolicy}); this
 * sampler exists specifically to close the gap tempdoc 672 found for VDU, not to duplicate a
 * mechanism that already works.
 */
public final class VduOfflineTriggerSampler {
  private static final io.justsearch.core.context.EngineContext AUTOMATIC_CONTEXT =
      io.justsearch.app.services.intent.EngineProvenance.internal("automatic-offline-enrichment",
          io.justsearch.core.context.EngineContext.Survival.DURABLE,
          io.justsearch.core.context.EngineContext.Urgency.BACKGROUND);

  private static final Logger log = LoggerFactory.getLogger(VduOfflineTriggerSampler.class);

  /** Check cadence. Coarser than the 5-minute idle threshold it evaluates — no need to poll fast. */
  static final long CHECK_INTERVAL_SECONDS = 30;

  private final Supplier<OfflineCoordinator> coordinatorSupplier;
  private final Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier;
  private final BooleanSupplier llmOnlineSupplier;
  private final ScheduledExecutorService executor;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  public VduOfflineTriggerSampler(
      EngineExecutorRegistry executors,
      Supplier<OfflineCoordinator> coordinatorSupplier,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      BooleanSupplier llmOnlineSupplier) {
    this.coordinatorSupplier = coordinatorSupplier;
    this.knowledgeServerSupplier = knowledgeServerSupplier;
    this.llmOnlineSupplier = llmOnlineSupplier;
    ThreadFactory tf =
        r -> {
          Thread t = new Thread(r, "vdu-offline-trigger-sampler");
          t.setDaemon(true);
          return t;
        };
    var limits = executors.limits(EngineExecutorSpec.Kind.BACKGROUND);
    this.executorOwner = executors.register(new EngineExecutorSpec(
        "head.vdu-offline-trigger-sampler", EngineExecutorSpec.Kind.BACKGROUND,
        EngineExecutorSpec.Mode.SCHEDULED, 1, limits.maxQueue(), 1));
    try {
      this.executor = executorOwner.openScheduled(tf);
    } catch (RuntimeException | Error failure) {
      try { executorOwner.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  /** Starts the sampler. Idempotent: subsequent calls are no-ops. */
  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    try {
    var _ =
        executor.scheduleAtFixedRate(
            this::checkOnce, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
    } catch (RuntimeException | Error failure) {
      started.set(false);
      throw failure;
    }
    log.debug("VduOfflineTriggerSampler started ({}s cadence)", CHECK_INTERVAL_SECONDS);
  }

  /** Stops the sampler. Idempotent; safe to call without start. */
  public void stop() {
    stopped.set(true);
    executorOwner.close();
  }

  /**
   * Visible for tests. Evaluates the pacing policy once and dispatches
   * {@code startOfflineProcessing()} on its bounded background owner when conditions allow.
   * One pending or running procedure suffices; later ticks observe the durable pending work again.
   */
  void checkOnce() {
    if (stopped.get()) return;
    try {
      OfflineCoordinator coordinator = coordinatorSupplier.get();
      if (coordinator == null || coordinator.isProcessing()) {
        return;
      }
      if (coordinator.getPendingVduCount() <= 0) {
        return;
      }
      KnowledgeServerBootstrap ks = knowledgeServerSupplier.get();
      long msSinceActivity =
          ks != null ? ks.msSinceLastUserActivity(System.currentTimeMillis()) : Long.MAX_VALUE;
      boolean energyReduced = ks != null && ks.energyState().reduced();
      // Tempdoc 737 R4: llmOnline MUST be REALIZED state (mode==ONLINE / lease holder==CHAT), never
      // spec — conflating desired with realized would break the exclusivity mutex. The supplier is
      // wired to inferenceManager().isOnline() (the FSM phase) at CoreApiAssembly.
      boolean llmOnline = llmOnlineSupplier.getAsBoolean();
      if (VduPacingPolicy.shouldTrigger(msSinceActivity, energyReduced, llmOnline)) {
        log.info(
            "Idle ({}ms since activity) and energy conditions met; auto-triggering VDU offline"
                + " processing",
            msSinceActivity);
        coordinator.startOfflineProcessing(AUTOMATIC_CONTEXT, outcome -> {}).whenComplete((outcome, failure) -> {
          if (failure != null) log.warn("Automatic enrichment pass failed", failure);
          else log.info("Automatic enrichment pass: processed={}, failed={}, remaining={}, blocked={}",
              outcome.processed(), outcome.failed(), outcome.remaining(), outcome.blockedReason());
        });
      }
    } catch (RuntimeException e) {
      log.debug("VduOfflineTriggerSampler: check failed: {}", e.getMessage());
    }
  }
}
