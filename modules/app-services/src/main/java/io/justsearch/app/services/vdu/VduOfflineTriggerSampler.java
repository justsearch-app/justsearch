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
  private final BooleanSupplier chatClaimedSupplier;
  private final Supplier<io.justsearch.app.inference.chat.ChatHolds> chatHoldsSupplier;
  private final java.util.function.LongSupplier processUptimeMillis;
  private long loadedAttemptAt = Long.MIN_VALUE;
  private volatile long retryAfter;
  private final ScheduledExecutorService executor;
  private final EngineExecutorRegistry.Registration executorOwner;
  private final AtomicBoolean started = new AtomicBoolean(false);
  private final AtomicBoolean stopped = new AtomicBoolean(false);

  public VduOfflineTriggerSampler(
      EngineExecutorRegistry executors,
      Supplier<OfflineCoordinator> coordinatorSupplier,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      BooleanSupplier chatClaimedSupplier) {
    this(executors, coordinatorSupplier, knowledgeServerSupplier, chatClaimedSupplier, () -> null);
  }

  public VduOfflineTriggerSampler(
      EngineExecutorRegistry executors,
      Supplier<OfflineCoordinator> coordinatorSupplier,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      BooleanSupplier chatClaimedSupplier,
      Supplier<io.justsearch.app.inference.chat.ChatHolds> chatHoldsSupplier) {
    this(executors, coordinatorSupplier, knowledgeServerSupplier, chatClaimedSupplier,
        chatHoldsSupplier, () -> java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
  }

  VduOfflineTriggerSampler(EngineExecutorRegistry executors,
      Supplier<OfflineCoordinator> coordinatorSupplier,
      Supplier<KnowledgeServerBootstrap> knowledgeServerSupplier,
      BooleanSupplier chatClaimedSupplier,
      Supplier<io.justsearch.app.inference.chat.ChatHolds> chatHoldsSupplier,
      java.util.function.LongSupplier processUptimeMillis) {
    this.coordinatorSupplier = coordinatorSupplier;
    this.knowledgeServerSupplier = knowledgeServerSupplier;
    this.chatClaimedSupplier = chatClaimedSupplier;
    this.chatHoldsSupplier = chatHoldsSupplier;
    this.processUptimeMillis = processUptimeMillis;
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
  synchronized void checkOnce() {
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
      long msSinceActivity = idleMillis(ks);
      boolean energyReduced = ks != null && ks.energyState().reduced();
      // Spec A step 6: the claim is the chat controller's own answer (!vduMayStart()), wired at
      // CoreApiAssembly — realized state and open holds, never spec.
      boolean chatClaimed = chatClaimedSupplier.getAsBoolean();
      // Spec E section 1.3 item 6: the bulk work gate's hold (held() only, never the duty floor).
      var bulkWork = ks != null ? ks.bulkWork() : null;
      boolean bulkHeld = bulkWork != null && bulkWork.held();
      var holds = chatHoldsSupplier.get();
      long now = System.currentTimeMillis();
      boolean loadedPass = chatClaimed && holds != null
          && holds.automaticVduMayStart(java.time.Duration.ofMillis(
              VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS))
          && automaticEligible(ks)
          && (loadedAttemptAt == Long.MIN_VALUE || msSinceActivity < now - loadedAttemptAt)
          && now >= retryAfter;
      if (VduPacingPolicy.shouldTrigger(msSinceActivity, energyReduced,
          chatClaimed && !loadedPass, bulkHeld)) {
        log.info(
            "Idle ({}ms since activity) and energy conditions met; auto-triggering VDU offline"
                + " processing",
            msSinceActivity);
        // Repeated automatic passes without progress pause automatic picture reading: no pass
        // starts, so llama-server is not relaunched for vision until the back-off's probe.
        AutomaticVduBreaker breaker = coordinator.automaticBreaker();
        if (breaker != null && !breaker.tryStartAutomatic()) {
          log.debug("Automatic picture reading is paused: {}", breaker.snapshot());
          return;
        }
        java.util.concurrent.CompletionStage<io.justsearch.app.api.OfflineProcessingOutcome> pass;
        try {
          if (loadedPass) loadedAttemptAt = now;
          pass = loadedPass
              ? coordinator.startAutomaticProcessing(AUTOMATIC_CONTEXT,
                  () -> !stopped.get() && automaticEligible(knowledgeServerSupplier.get()))
              : coordinator.startOfflineProcessing(AUTOMATIC_CONTEXT, outcome -> {});
        } catch (RuntimeException | Error notStarted) {
          if (breaker != null) breaker.passEnded(new AutomaticVduBreaker.Result.Neutral());
          throw notStarted;
        }
        pass.whenComplete((outcome, failure) -> {
          if (breaker != null) {
            String detail = coordinator.vduCapabilityState() == null ? null
                : coordinator.vduCapabilityState().snapshot().detail();
            breaker.passEnded(failure != null ? AutomaticVduBreaker.classify(failure, detail)
                : AutomaticVduBreaker.classify(outcome, detail));
          }
          if (failure != null) {
            if (loadedPass) retryAfter = System.currentTimeMillis()
                + VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
            log.warn("Automatic enrichment pass failed", failure);
          }
          else {
            if (loadedPass && outcome.processed() == 0 && outcome.failed() == 0
                && outcome.remaining() > 0 && outcome.blockedReason()
                    == io.justsearch.app.api.OfflineProcessingOutcome.BlockReason.NONE) {
              // BUSY entry made no launch attempt. Defer for one idle window; a racing metadata
              // lease must not strand the backlog until another person happens to act.
              synchronized (VduOfflineTriggerSampler.this) {
                loadedAttemptAt = Long.MIN_VALUE;
                retryAfter = System.currentTimeMillis() + VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
              }
            }
            log.info("Automatic enrichment pass: processed={}, failed={}, remaining={}, blocked={}",
                outcome.processed(), outcome.failed(), outcome.remaining(), outcome.blockedReason());
          }
        });
      }
    } catch (RuntimeException e) {
      if (loadedAttemptAt != Long.MIN_VALUE) {
        loadedAttemptAt = Long.MIN_VALUE;
        retryAfter = System.currentTimeMillis() + VduPacingPolicy.DEFAULT_IDLE_THRESHOLD_MS;
      }
      log.debug("VduOfflineTriggerSampler: check failed: {}", e.getMessage());
    }
  }

  private long idleMillis(KnowledgeServerBootstrap ks) {
    return Math.min(processUptimeMillis.getAsLong(),
        ks != null ? ks.msSinceLastUserActivity(System.currentTimeMillis()) : Long.MAX_VALUE);
  }

  private boolean automaticEligible(KnowledgeServerBootstrap ks) {
    if (ks == null) return false;
    var energy = ks.energyState();
    var bulk = ks.bulkWork();
    return energy != null && energy.source() == io.justsearch.app.util.EnergyState.Source.AC
        && VduPacingPolicy.shouldTrigger(idleMillis(ks),
            energy.reduced(), false, bulk != null && bulk.held());
  }
}
