/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.http.Context;
import io.justsearch.agent.api.AgentService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.lifecycle.LifecycleReasonCode;
import io.justsearch.app.api.lifecycle.LifecycleSnapshotV1;
import io.justsearch.contract.wire.LifecycleState;
import io.justsearch.app.api.lifecycle.ReadinessDimension;
import io.justsearch.app.api.gpl.GplJobStatus;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.api.status.InferenceRuntimeView;
import io.justsearch.app.api.status.ModelDistributionStatusView;
import io.justsearch.app.api.status.ModelVariantView;
import io.justsearch.app.api.status.ReadinessComponentView;
import io.justsearch.app.api.status.ReadinessCompositeView;
import io.justsearch.app.api.status.ReadinessEnvelopeView;
import io.justsearch.app.api.status.GpuStatusView;
import io.justsearch.app.api.status.PowerStatusView;
import io.justsearch.app.api.status.AtRestProtectionView;
import io.justsearch.app.api.status.StatusMeta;
import io.justsearch.app.api.status.StatusResponse;
import io.justsearch.app.api.status.TelemetryHealthView;
import io.justsearch.app.api.status.VisualExtractionView;
import io.justsearch.app.api.status.WorkerOperationalView;
import io.justsearch.app.api.status.WorkerOperationalViewBuilder;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.telemetry.RrdMetricStore;
import io.justsearch.telemetry.RrdMetricStore.TimeSeriesResult;
import io.justsearch.telemetry.TelemetryHealthSnapshot;
import io.justsearch.telemetry.TelemetryHealthState;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Package-private collaborator handling status and lifecycle HTTP endpoints.
 *
 * <p>Extracted from {@link LocalApiServer} to reduce class size. Handles the {@code /api/status}
 * and {@code /api/health} endpoints registered via {@link
 * io.justsearch.ui.api.routes.StatusRoutes}.
 */
final class StatusLifecycleHandler implements io.justsearch.app.api.StatusSnapshotProvider {
  private static final Logger log = LoggerFactory.getLogger(StatusLifecycleHandler.class);
  private static final String READINESS_READY = "READY";
  private static final String READINESS_DEGRADED = "DEGRADED";
  private static final String READINESS_NOT_READY = "NOT_READY";
  private static final String READINESS_NOT_CONFIGURED = "NOT_CONFIGURED";
  private static final String READINESS_UNKNOWN = "UNKNOWN";

  /** 335 §9: Cache GPU status to avoid NVML init/shutdown on every poll. */
  private static final long GPU_CACHE_TTL_MS = 5_000;
  private volatile GpuStatusView cachedGpuStatus;
  private volatile long cachedGpuStatusAtMs;

  /** Tempdoc 629 (FLOOR): cache the at-rest probe (subprocess) to avoid a PowerShell call per poll. */
  private static final long AT_REST_CACHE_TTL_MS = 5_000;
  private volatile AtRestProtectionView cachedAtRest;
  private volatile long cachedAtRestAtMs;

  /**
   * Tempdoc 629 (FLOOR): the disk-encryption probe, shared with {@link #atRestTap} so both the
   * status View and the condition read one subprocess result. Wired in {@code LocalApiServer};
   * lazily self-constructed from {@link #indexBasePath} on the test-only Builder path.
   */
  private volatile io.justsearch.app.services.atrest.DiskEncryptionProbe diskEncryptionProbe;

  /** Tempdoc 629 (FLOOR): the at-rest-protection condition tap; null on test-only paths. */
  private volatile io.justsearch.app.services.observability.health.AtRestHealthTap atRestTap;

  /** Tempdoc 629 (#2): the LAYER legibility tap — asserts at-rest.authored when the data key is locked. */
  private volatile io.justsearch.app.services.observability.health.ConversationProtectionHealthTap
      conversationProtectionTap;

  /** Tempdoc 629 (#2): supplier of the conversation-encryption state string (late-bound; null in tests). */
  private volatile Supplier<String> conversationProtectionStateSupplier;

  private final OnlineAiService onlineAi;
  private final AgentService agentService;
  private final Supplier<InferenceRuntimeView> inferenceSnapshotSupplier;
  private final io.justsearch.app.services.lifecycle.WorkerCapability workerCapability;
  private final io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability;
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private volatile String knowledgeServerStartError;

  /**
   * Tempdoc 333 §4 (deferred there, filled by 821 §3-C1): epoch-ms of the newest SUCCESSFUL Worker
   * gRPC observation in this Head process, or {@code 0} when the Worker has never been reached.
   *
   * <p>Stamped from the timestamp taken immediately BEFORE the call, not after it — a conservative
   * choice, so the age reported to consumers is never younger than the observation actually is.
   */
  private volatile long lastWorkerObservationAtMs;

  /**
   * Tempdoc 885 item 6 — one Worker observation, taken by the internal sampler.
   *
   * @param view the observed view, or a fallback view when {@code failed}
   * @param failed the observation did not reach a live Worker (capability unavailable, or the RPC
   *     threw). A failed sample is still a sample: it is what makes {@code workerRpcStale} true
   *     without anyone having to call the Worker from a request thread to find out.
   * @param failureReason exception class + message when the RPC threw; {@code null} otherwise
   * @param sampledAtMs epoch-ms stamped immediately BEFORE the call
   */
  record WorkerViewSample(
      WorkerOperationalView view, boolean failed, String failureReason, long sampledAtMs) {}

  /** Sampling period while nothing is in progress (tempdoc 885 item 6, design decision 4). */
  static final long SAMPLER_IDLE_PERIOD_MS = 10_000L;

  /** Sampling period while indexing / backfill / AI activation is in progress. */
  static final long SAMPLER_BUSY_PERIOD_MS = 2_000L;

  /** How many sampling periods a sample may age before it is reported stale. */
  static final int SAMPLE_STALE_PERIODS = 3;

  /** The sampler's last observation; {@code null} until the first one. */
  private volatile WorkerViewSample lastWorkerSample;

  /**
   * Wall clock for sample stamping and age. Injectable so the age-based staleness rule is testable:
   * without a seam, proving "a sample older than {@link #SAMPLE_STALE_PERIODS} periods reads stale"
   * would mean sleeping 30 s, so in practice it would not be proven at all — and a rule with no test
   * is a rule that can be changed to any value and stay green.
   */
  private volatile java.util.function.LongSupplier clockMs = System::currentTimeMillis;

  /** Visible for testing: drive the sampler's clock. */
  void setClockForTesting(java.util.function.LongSupplier clock) {
    this.clockMs = clock == null ? System::currentTimeMillis : clock;
  }

  private final Path indexBasePath;
  private final Instant startTime;
  private final Supplier<String> diskPressureSupplier;
  private final Supplier<RerankerService> lambdamartRerankerSupplier;
  private final Supplier<GplStatusProvider> gplCoordinatorSupplier;
  private final Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier;
  private volatile Supplier<io.justsearch.app.services.vdu.VduCapabilityState.Snapshot>
      vduCapabilitySnapshotSupplier;
  /** Tempdoc 672 follow-up: is a VDU offline-processing batch actively running right now. */
  private volatile Supplier<Boolean> vduProcessingSupplier;

  /** Tempdoc 419 C3 V1 — late-bound suppliers for the head-side time-series + health views. */
  private volatile Supplier<RrdMetricStore> rrdStoreSupplier;
  private volatile Supplier<TelemetryHealthState> telemetryHealthSupplier;

  /**
   * Tempdoc 630 — late-bound supplier of the connected {@link
   * io.justsearch.app.services.worker.KnowledgeServerBootstrap}, read at request time for the
   * energy-intent ("Paused — saving energy") + post-resume ("Catching up after sleep") status
   * fields. Null/absent before the worker connects ⇒ both default to neutral.
   */
  private volatile Supplier<KnowledgeServerBootstrap>
      knowledgeServerLifecycleSupplier;

  /**
   * Tempdoc 419 C3 V2 P3 — late-bound GPU saturation monitor. Wired in {@code LocalApiServer}
   * after the monitor + sampler are constructed. {@code null} on headless / non-GPU setups,
   * in which case the {@code GPU} readiness dim defaults to READY.
   */
  private volatile io.justsearch.ui.observability.GpuSaturationMonitor gpuSaturationMonitor;

  /**
   * Tempdoc 430 Phase 4 — late-bound HealthEvent substrate tap. Wired in {@code
   * LocalApiServer}. {@code null} when no bootstrap is supplied (test-only Builder path);
   * the readiness envelope is computed but no events flow through the substrate.
   */
  private volatile io.justsearch.app.services.observability.health.LifecycleSnapshotTap
      lifecycleSnapshotTap;

  /**
   * Tempdoc 430 Phase 6 — late-bound worker-view substrate tap. Observes
   * {@code WorkerOperationalView} fields not surfaced via the readiness envelope.
   * {@code null} on test-only paths; receives the same {@code workerView} +
   * {@code workerRpcStale} pair this handler computes per request.
   */
  private volatile io.justsearch.app.services.observability.health.WorkerSnapshotTap
      workerSnapshotTap;

  /** Tempdoc 626 §Axis-C — per-root index-drift condition tap; null on test-only paths. */
  private volatile io.justsearch.app.services.observability.health.IndexDriftHealthTap
      indexDriftTap;

  /**
   * Tempdoc 501 Phase 26 (§13.7 Q5) — late-bound runtime manifest publisher.
   * When non-null and {@code current() != null}, the overall lifecycle
   * projection is read from the manifest rather than re-derived locally.
   * Eliminates the duplicate state-projection surface that the §13.4.3 audit
   * found. Null on test-only paths and during the brief window between
   * {@link LocalApiServer} construction and the publisher's first publish.
   */
  private volatile io.justsearch.ui.runtime.RuntimeManifestPublisher runtimeManifestPublisher;

  StatusLifecycleHandler(
      OnlineAiService onlineAi,
      AgentService agentService,
      Supplier<InferenceRuntimeView> inferenceSnapshotSupplier,
      KnowledgeServerBootstrap knowledgeServer,
      String knowledgeServerStartError,
      Path indexBasePath,
      Instant startTime,
      Supplier<String> diskPressureSupplier,
      Supplier<RerankerService> lambdamartRerankerSupplier,
      Supplier<GplStatusProvider> gplCoordinatorSupplier,
      Supplier<GpuCapabilitiesService> gpuCapabilitiesSupplier,
      io.justsearch.app.services.lifecycle.WorkerCapability workerCapability,
      io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability) {
    // Tempdoc 412 Phase 3: engineMonitorSupplier removed (Phase 0 finding 2: EngineMonitor was
    // dead code; setters never called in production, so the supplier was always null in
    // practice). Inference status is now sourced from {@link InferenceLifecycleManager}'s
    // typed accessors via {@link #buildInferenceView()}.
    this.onlineAi = onlineAi;
    this.agentService = agentService;
    this.inferenceSnapshotSupplier = inferenceSnapshotSupplier;
    this.workerCapability = workerCapability;
    this.inferenceCapability = inferenceCapability;
    this.knowledgeServer = knowledgeServer;
    this.knowledgeServerStartError = knowledgeServerStartError;
    this.indexBasePath = indexBasePath;
    this.startTime = startTime;
    this.diskPressureSupplier = diskPressureSupplier;
    this.lambdamartRerankerSupplier = lambdamartRerankerSupplier;
    this.gplCoordinatorSupplier = gplCoordinatorSupplier;
    this.gpuCapabilitiesSupplier = gpuCapabilitiesSupplier;
  }

  /** Late-binds the Knowledge Server after async Worker startup. */
  void setKnowledgeServer(KnowledgeServerBootstrap ks, String startError) {
    this.knowledgeServer = ks;
    this.knowledgeServerStartError = startError;
  }

  /** Tempdoc 419 C3 V1 — wires the head-side RRD store for GPU-trend backfill. */
  void setRrdStoreSupplier(Supplier<RrdMetricStore> supplier) {
    this.rrdStoreSupplier = supplier;
  }

  /** Tempdoc 419 C3 V1 — wires the head-side telemetry health state for failure-count surfacing. */
  void setTelemetryHealthSupplier(Supplier<TelemetryHealthState> supplier) {
    this.telemetryHealthSupplier = supplier;
  }

  /**
   * Tempdoc 419 C3 V2 P3 — wires the GPU saturation monitor. The handler composes the
   * activity gate from its own suppliers ({@code engineMonitorSupplier}, {@code
   * workerView.migration().processingJobsCount()}, {@code gplCoordinatorSupplier}, {@code
   * onlineAi.isAvailable()}) on each {@code /api/status} call.
   */
  void setGpuSaturationMonitor(io.justsearch.ui.observability.GpuSaturationMonitor monitor) {
    this.gpuSaturationMonitor = monitor;
  }

  /**
   * Tempdoc 430 Phase 4: optional tap that observes per-request readiness envelopes and
   * emits {@code HealthEvent} records through the substrate. Set during {@code
   * LocalApiServer} construction; null in the test-only Builder path.
   */
  void setLifecycleSnapshotTap(
      io.justsearch.app.services.observability.health.LifecycleSnapshotTap tap) {
    this.lifecycleSnapshotTap = tap;
  }

  /**
   * Tempdoc 430 Phase 6: optional tap that observes per-request {@code
   * WorkerOperationalView} for fields not in the readiness envelope (schema /
   * embedding compat, queue-db, job failures). Set during {@code LocalApiServer}
   * construction; null in the test-only Builder path.
   */
  void setWorkerSnapshotTap(
      io.justsearch.app.services.observability.health.WorkerSnapshotTap tap) {
    this.workerSnapshotTap = tap;
  }

  /**
   * Tempdoc 626 §Axis-C: optional tap that reconciles per-watched-root drift
   * ({@code index.drift-unknown}) into the ConditionStore on each snapshot. Owns its
   * watched-roots source; null in the test-only Builder path.
   */
  void setIndexDriftTap(io.justsearch.app.services.observability.health.IndexDriftHealthTap tap) {
    this.indexDriftTap = tap;
  }

  /**
   * Tempdoc 629 (FLOOR): wire the at-rest-protection condition tap + the shared disk-encryption
   * probe, so the {@code /api/status} View and the {@code at-rest.unprotected} condition read one
   * probe result. Late-bound in {@code LocalApiServer}; null on the test-only Builder path.
   */
  void setAtRestTap(
      io.justsearch.app.services.observability.health.AtRestHealthTap tap,
      io.justsearch.app.services.atrest.DiskEncryptionProbe probe) {
    this.atRestTap = tap;
    this.diskEncryptionProbe = probe;
  }

  /** Tempdoc 629 (#2): late-bind the at-rest.authored legibility tap (null on the test-only path). */
  void setConversationProtectionTap(
      io.justsearch.app.services.observability.health.ConversationProtectionHealthTap tap) {
    this.conversationProtectionTap = tap;
  }

  /**
   * Tempdoc 629 (#2): late-bind a supplier of the conversation-encryption state string
   * (not_configured|locked|unlocked) read from {@code HeadAssembly.dataKeyManager().state()}. Null on
   * the test-only Builder path → the View reports "unknown".
   */
  void setConversationProtectionSupplier(Supplier<String> supplier) {
    this.conversationProtectionStateSupplier = supplier;
  }

  /**
   * Tempdoc 501 Phase 26 (§13.7 Q5): wire the runtime manifest publisher
   * so the overall lifecycle projection can be read from one canonical
   * source instead of re-derived per request. Late-bound because
   * {@link LocalApiServer} constructs this handler before the publisher
   * is in scope.
   */
  void setRuntimeManifestPublisher(
      io.justsearch.ui.runtime.RuntimeManifestPublisher publisher) {
    this.runtimeManifestPublisher = publisher;
  }

  void setVduCapabilitySnapshotSupplier(
      Supplier<io.justsearch.app.services.vdu.VduCapabilityState.Snapshot> supplier) {
    this.vduCapabilitySnapshotSupplier = supplier;
  }

  /** Tempdoc 672 follow-up: late-bind the "is VDU actively processing right now" supplier. */
  void setVduProcessingSupplier(Supplier<Boolean> supplier) {
    this.vduProcessingSupplier = supplier;
  }

  /** Tempdoc 630: late-bind the connected knowledge-server bootstrap (energy + resume signals). */
  void setKnowledgeServerLifecycleSupplier(
      Supplier<KnowledgeServerBootstrap> supplier) {
    this.knowledgeServerLifecycleSupplier = supplier;
  }

  /**
   * Builds the {@link PowerStatusView} from the Head-side energy poll (tempdoc 630). Neutral
   * ({@code unknown()}) when the bootstrap isn't connected yet, so the Queue card stays calm until
   * the real signal arrives. Never throws.
   */
  private PowerStatusView buildPowerStatus() {
    var supplier = knowledgeServerLifecycleSupplier;
    if (supplier == null) {
      return PowerStatusView.unknown();
    }
    try {
      var bootstrap = supplier.get();
      if (bootstrap == null) {
        return PowerStatusView.unknown();
      }
      var energy = bootstrap.energyState();
      return new PowerStatusView(energy.reduced(), energy.source().name());
    } catch (RuntimeException e) {
      log.debug("Energy status unavailable: {}", e.getMessage());
      return PowerStatusView.unknown();
    }
  }

  /**
   * Whether an OS resume was handled within the recent notice window (tempdoc 630), computed against
   * the request clock so the "Catching up after sleep" transient auto-clears. Never throws.
   */
  private boolean computeCatchingUp() {
    var supplier = knowledgeServerLifecycleSupplier;
    if (supplier == null) {
      return false;
    }
    try {
      var bootstrap = supplier.get();
      return bootstrap != null && bootstrap.recentlyResumed(System.currentTimeMillis());
    } catch (RuntimeException e) {
      log.debug("Catching-up status unavailable: {}", e.getMessage());
      return false;
    }
  }

  /**
   * observations.md inbox item #1 (2026-05-08): bypasses the broken
   * worker→head RRD replication by feeding the worker-shipped recent-arrays
   * (`recent_job_queue_depth` / `recent_docs_per_sec`, present on every
   * {@code CoreStatus} gRPC response) directly into the {@code TimeseriesSnapshotHolder}s
   * that back the `/api/metrics/worker.*` endpoints. The RRD-based ticks
   * stay scheduled (harmless no-ops because the head's RRD never receives
   * worker.* samples) — this callback is the live data source.
   */
  private volatile java.util.function.BiConsumer<
          WorkerOperationalView, Boolean>
      workerMetricsPublisher;

  void setWorkerMetricsPublisher(
      java.util.function.BiConsumer<
              WorkerOperationalView, Boolean>
          publisher) {
    this.workerMetricsPublisher = publisher;
  }

  void handleStatus(Context ctx) {
    // Tempdoc 885 item 6: the request thread reads the internal sampler's last snapshot. The one
    // debug escape hatch is ?fresh=true, which forces a synchronous sample.
    boolean fresh = "true".equalsIgnoreCase(ctx.queryParam("fresh"));
    ctx.json(fresh ? buildStatusMap(true) : buildStatusMap(false));
  }

  @Override
  public StatusResponse buildStatusSnapshot() {
    return buildStatusMap(false);
  }

  /**
   * Tempdoc 885 item 6 — the internal Worker status sampler's entry point. Runs on the
   * {@code readiness-reconcile} daemon thread, driven by {@code KnowledgeServerHealthMonitor}'s
   * existing schedule (no new executor) and by every capability transition. This is the ONLY
   * production path that performs the {@code IndexStatus} unary, and it is the path that feeds
   * every health tap — the request thread reads what it left behind.
   */
  StatusResponse sampleAndBuildStatusSnapshot() {
    return buildStatusMap(true);
  }

  /**
   * The sampling period the health monitor should use before its next tick: fast while the Worker
   * has index work in flight or the inference runtime is coming up, idle otherwise. Derived from
   * the LAST sample, never from a fresh RPC — so this is safe to call from the monitor thread.
   */
  long samplingPeriodMs() {
    WorkerViewSample sample = lastWorkerSample;
    if (sample == null || sample.failed()) {
      return SAMPLER_IDLE_PERIOD_MS;
    }
    if (hasActiveIndexWork(sample.view())) {
      return SAMPLER_BUSY_PERIOD_MS;
    }
    // AI activation: the inference capability is mid-transition, so its readiness dimension is
    // exactly the one a watcher is waiting on.
    var inference = inferenceCapability;
    if (inference != null) {
      var health = inference.health();
      if (health == io.justsearch.app.api.lifecycle.CapabilityHealth.PENDING
          || health == io.justsearch.app.api.lifecycle.CapabilityHealth.RECOVERING) {
        return SAMPLER_BUSY_PERIOD_MS;
      }
    }
    var vdu = vduProcessingSupplier;
    if (vdu != null) {
      try {
        if (Boolean.TRUE.equals(vdu.get())) {
          return SAMPLER_BUSY_PERIOD_MS;
        }
      } catch (RuntimeException e) {
        log.debug("VDU processing supplier failed while deriving the sampler period", e);
      }
    }
    return SAMPLER_IDLE_PERIOD_MS;
  }

  /**
   * Performs one Worker observation and caches it. Never throws — a failed observation is itself a
   * recorded sample, which is what keeps {@code workerRpcStale} honest when the Worker is gone.
   */
  private WorkerViewSample observeWorker() {
    // Stamped BEFORE the call, not after it, so the age reported to consumers is never younger
    // than the observation actually is (the 821 §3-C1 convention, preserved).
    long sampledAtMs = clockMs.getAsLong();
    if (!workerCapability.available()) {
      return new WorkerViewSample(
          WorkerOperationalView.fallback(workerCapability.health().name()), true, null, sampledAtMs);
    }
    try {
      WorkerOperationalView view = knowledgeServer.client().getWorkerOperationalView(
          io.justsearch.app.services.intent.EngineProvenance.internal("status-sampler",
              io.justsearch.core.context.EngineContext.Survival.INTERACTIVE,
              io.justsearch.core.context.EngineContext.Urgency.BACKGROUND));
      lastWorkerObservationAtMs = sampledAtMs;
      return new WorkerViewSample(view, false, null, sampledAtMs);
    } catch (Exception e) {
      log.debug(
          "Failed to fetch Knowledge Server status: {} - {}",
          e.getClass().getSimpleName(),
          e.getMessage(),
          e);
      return new WorkerViewSample(
          WorkerOperationalView.fallback("UNAVAILABLE"),
          true,
          e.getClass().getSimpleName() + ": " + e.getMessage(),
          sampledAtMs);
    }
  }

  /**
   * @param sampleWorker {@code true} on the sampler path (perform the {@code IndexStatus} unary and
   *     reconcile every health tap); {@code false} on a read path (serve the cached sample and
   *     touch no tap). The one exception: when no sample has ever been taken the read path takes
   *     one synchronously, so a request arriving in the boot window before the sampler's self-seed
   *     lands still reports the truth. That can happen at most once per process.
   */
  private StatusResponse buildStatusMap(boolean sampleWorker) {
    // Appendix D: stable lifecycle subset (schema v1).
    LifecycleSnapshotV1 lifecycleSnapshot = computeLifecycleSnapshot();

    long uptimeMs = System.currentTimeMillis() - startTime.toEpochMilli();

    // JVM Memory
    Runtime runtime = Runtime.getRuntime();
    long totalMemory = runtime.totalMemory();
    long freeMemory = runtime.freeMemory();
    long usedMemory = totalMemory - freeMemory;
    long maxMemory = runtime.maxMemory();

    // Disk pressure (from telemetry subsystem, when available)
    String diskPressure = null;
    if (diskPressureSupplier != null) {
      diskPressure = diskPressureSupplier.get();
    }

    // NOTE: Head MUST NOT touch Lucene index files. Index availability is reported by the Worker
    // when available.
    // Keep this field for UI backwards-compat, but treat it as "Worker-connected index available".
    // Tempdoc 885 item 6: the Worker observation is the sampler's, not this thread's.
    WorkerViewSample sample = lastWorkerSample;
    if (sampleWorker || sample == null) {
      sample = observeWorker();
      lastWorkerSample = sample;
    }

    boolean indexAvailable = !sample.failed();
    String indexStatusReason = sample.failureReason();
    // workerRpcAtMs is the SAMPLE's observation time (tempdoc 885 item 6 moved the observation off
    // the request thread); age = now - workerRpcAtMs. A sample is stale when it failed, or when it
    // is older than SAMPLE_STALE_PERIODS sampling periods — which is how a wedged sampler surfaces
    // as "contact lost" instead of as a silently-frozen snapshot served as fresh.
    long workerRpcAtMs = sample.sampledAtMs();
    boolean workerRpcStale =
        sample.failed()
            || (clockMs.getAsLong() - workerRpcAtMs)
                > (long) SAMPLE_STALE_PERIODS * samplingPeriodMs();
    WorkerOperationalView workerView = overlayVduCapability(sample.view());

    // Tempdoc 412 Phase 3: single inference status surface (replaces LlmStatusView + OnlineAiView).
    InferenceRuntimeView inference = buildInferenceView();

    // 821 §3-C1: the per-dimension freshness fact, derived from the SAME worker-contact outcome
    // that StatusMeta reports below — one authority, two projections (envelope + _meta).
    WorkerContact workerContact =
        workerRpcStale
            ? WorkerContact.lost(
                lastWorkerObservationAtMs, System.currentTimeMillis(), startTime.toEpochMilli())
            : WorkerContact.observed(workerRpcAtMs);

    ReadinessEnvelopeView readiness =
        buildReadinessEnvelope(workerView, lifecycleSnapshot, workerContact);

    // Tempdoc 885 item 6: the taps reconcile on the SAMPLER path only. Before this item they
    // reconciled on whichever thread happened to call buildStatusMap, which meant GET /api/status
    // paid for the index-drift tap's getWatchedRoots() RPC — the second Worker call this item
    // removes from the request thread. The sampler runs on every monitor tick and every capability
    // transition, so the substrate advances at least as often as it did under the browser poll.
    if (sampleWorker) {
      feedHealthTaps(readiness, workerView, workerRpcStale);
    }

    // Legacy alias booleans remain exposed, but are derived from canonical typed readiness.
    boolean aiReady = readinessComponentReady(readiness, "ai");
    boolean embeddingReady = readinessComponentReady(readiness, "embedding");

    return buildStatusResponse(
        lifecycleSnapshot,
        uptimeMs,
        usedMemory,
        totalMemory,
        maxMemory,
        diskPressure,
        workerView,
        indexAvailable,
        indexStatusReason,
        inference,
        readiness,
        aiReady,
        embeddingReady,
        workerRpcAtMs,
        workerRpcStale);
  }

  /**
   * Reconciles every health tap from one observation. The single home for tap feeding (tempdoc 885
   * item 6): the sampler is the only caller, so "does the sampler feed the same taps the request
   * path used?" is answered by there being exactly one such path.
   */
  private void feedHealthTaps(
      ReadinessEnvelopeView readiness, WorkerOperationalView workerView, boolean workerRpcStale) {
    // Tempdoc 430 Phase 4: feed the readiness envelope into the HealthEvent substrate
    // tap. The tap reconciles each dim's (state, reasonCode) against ConditionStore and
    // broadcasts deltas through HealthEventChangeRegistry to SSE subscribers. Null when
    // no HeadAssembly was supplied (test-only path).
    var tap = lifecycleSnapshotTap;
    if (tap != null) {
      try {
        tap.accept(readiness);
      } catch (RuntimeException e) {
        log.warn("LifecycleSnapshotTap failed during a readiness snapshot; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 430 Phase 6: feed the worker view + stale flag into the second tap. The
    // tap reconciles compatibility / queue-db / failure fields not surfaced through the
    // readiness envelope. Stale views short-circuit emission entirely (per §B.T.5 —
    // unknown ≠ healthy; a fallback view's queueDbHealthy=true would falsely clear a
    // real queue-db.unhealthy condition without this guard).
    var workerTap = workerSnapshotTap;
    if (workerTap != null) {
      try {
        workerTap.accept(workerView, workerRpcStale);
      } catch (RuntimeException e) {
        log.warn("WorkerSnapshotTap failed during a readiness snapshot; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 626 §Axis-C: reconcile per-root index-drift (delete-detection-unverified) into the
    // ConditionStore. The tap pulls the watched roots itself; failures are logged, never fatal.
    var driftTap = indexDriftTap;
    if (driftTap != null) {
      try {
        driftTap.accept();
      } catch (RuntimeException e) {
        log.warn("IndexDriftHealthTap failed during a readiness snapshot; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 629 (FLOOR): reconcile the at-rest-unprotected condition (data-dir volume not
    // OS-encrypted) into the ConditionStore. Verdict-independent (a confidentiality concern, not a
    // retrieval degradation); failures are logged, never fatal.
    var atRest = atRestTap;
    if (atRest != null) {
      try {
        atRest.accept();
      } catch (RuntimeException e) {
        log.warn("AtRestHealthTap failed during a readiness snapshot; substrate broadcast suppressed", e);
      }
    }

    // Tempdoc 629 (#2): reconcile the at-rest.authored condition (AUTHORED stores encrypted + locked)
    // into the ConditionStore. Verdict-independent; failures logged, never fatal.
    var convTap = conversationProtectionTap;
    if (convTap != null) {
      try {
        convTap.accept();
      } catch (RuntimeException e) {
        log.warn(
            "ConversationProtectionHealthTap failed during a readiness snapshot; broadcast suppressed", e);
      }
    }

    // observations.md inbox item #1: feed worker-shipped recent arrays into the
    // TIMESERIES metric holders so /api/metrics/worker.* + Sparklines have live
    // data without needing a working worker→head RRD replication wire.
    var metricsPublisher = workerMetricsPublisher;
    if (metricsPublisher != null) {
      try {
        metricsPublisher.accept(workerView, workerRpcStale);
      } catch (RuntimeException e) {
        log.warn(
            "Worker-metrics publisher failed during a readiness snapshot; metric snapshots not refreshed",
            e);
      }
    }
  }

  private StatusResponse buildStatusResponse(
      LifecycleSnapshotV1 lifecycleSnapshot,
      long uptimeMs,
      long usedMemory,
      long totalMemory,
      long maxMemory,
      String diskPressure,
      WorkerOperationalView workerView,
      boolean indexAvailable,
      String indexStatusReason,
      InferenceRuntimeView inference,
      ReadinessEnvelopeView readiness,
      boolean aiReady,
      boolean embeddingReady,
      long workerRpcAtMs,
      boolean workerRpcStale) {
    return new StatusResponse(
        lifecycleSnapshot.schema_version(),
        lifecycleSnapshot.observed_at(),
        lifecycleSnapshot.lifecycle(),
        lifecycleSnapshot.components(),
        "ok",
        "JustSearch Local API",
        indexBasePath == null ? "" : indexBasePath.toString(),
        uptimeMs,
        usedMemory,
        totalMemory,
        maxMemory,
        diskPressure,
        workerView,
        indexAvailable,
        knowledgeServerStartError,
        indexStatusReason,
        // 630: Head-side energy-intent + post-resume "catching up" transient.
        buildPowerStatus(),
        computeCatchingUp(),
        inference,
        readiness,
        aiReady,
        embeddingReady,
        // 330 §4: Grouped sub-objects (same data, structured)
        workerView != null
            ? io.justsearch.app.api.status.EmbeddingStatusGroup.from(workerView, embeddingReady)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.SchemaStatusGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.ChunkCoverageGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.QueueHealthGroup.from(workerView)
            : null,
        workerView != null
            ? io.justsearch.app.api.status.MigrationStatusGroup.from(workerView)
            : null,
        // 335 §9: GPU utilization and VRAM
        buildGpuStatus(),
        // 629 (FLOOR): at-rest protection of the data-dir volume
        buildAtRestProtection(),
        // 629 (#2): conversation encryption state (reactive single-authority status)
        buildConversationProtection(),
        // 381: Model distribution status
        buildModelDistributionStatus(),
        // 415: Active agent session count (sourced from agent.session.active_count gauge).
        // Omitted via @JsonInclude(NON_NULL) on StatusResponse when agent capability is
        // unavailable, so field presence on /api/status signals "agent capability is live."
        agentService.isAvailable()
            ? new io.justsearch.app.api.status.AgentSessionView(
                agentService.activeSessionCount())
            : null,
        // 419 C3 V1: telemetry-subsystem health counters (populated in Step C).
        buildTelemetryHealth(),
        // 333 §5: Freshness and provenance metadata
        new StatusMeta(workerRpcAtMs, workerRpcStale));
  }

  private WorkerOperationalView overlayVduCapability(WorkerOperationalView workerView) {
    if (workerView == null) {
      return null;
    }
    var supplier = vduCapabilitySnapshotSupplier;
    io.justsearch.app.services.vdu.VduCapabilityState.Snapshot snapshot = null;
    if (supplier != null) {
      try {
        snapshot = supplier.get();
      } catch (RuntimeException e) {
        log.debug("VDU capability snapshot supplier failed: {}", e.getMessage());
      }
    }
    // Tempdoc 672 follow-up: "is a batch running right now" is read independently of the
    // blocked-reason snapshot above — a healthy, actively-processing batch has no blocked reason
    // at all, so the old blockedReason-only guard would have skipped this fact entirely.
    var processingSupplier = vduProcessingSupplier;
    boolean processing = false;
    if (processingSupplier != null) {
      try {
        Boolean value = processingSupplier.get();
        processing = value != null && value;
      } catch (RuntimeException e) {
        log.debug("VDU processing supplier failed: {}", e.getMessage());
      }
    }
    String blockedReason = snapshot != null ? snapshot.blockedReason() : null;
    boolean blockedReasonPresent = blockedReason != null && !blockedReason.isBlank();
    if (!blockedReasonPresent && !processing) {
      return workerView;
    }
    VisualExtractionView visual =
        workerView.visualExtraction() == null
            ? VisualExtractionView.empty()
            : workerView.visualExtraction();
    VisualExtractionView merged =
        new VisualExtractionView(
            visual.ocrEnabled(),
            visual.ocrEngineAvailable(),
            visual.ocrEngine(),
            visual.ocrBlockedReason(),
            visual.visualTextNeededCount(),
            visual.visualEnrichmentNeededCount(),
            blockedReasonPresent ? blockedReason : visual.vduBlockedReason(),
            processing);
    return WorkerOperationalViewBuilder.builder()
        .core(workerView.core())
        .failure(workerView.failure())
        .migration(workerView.migration())
        .compatibility(workerView.compatibility())
        .queueDb(workerView.queueDb())
        .enrichment(workerView.enrichment())
        .gpu(workerView.gpu())
        .vectorFormat(workerView.vectorFormat())
        .telemetry(workerView.telemetry())
        .searchConfig(workerView.searchConfig())
        .visualExtraction(merged)
        .buildStamp(workerView.buildStamp())
        .aiReady(workerView.aiReady())
        .embeddingReady(workerView.embeddingReady())
        .build();
  }

  /**
   * Tempdoc 419 C3 V1: build telemetry-health view from {@code TelemetryHealthState}.
   *
   * <p>Returns {@link TelemetryHealthView#empty()} when the supplier isn't wired (tests / boot
   * paths without {@link io.justsearch.telemetry.LocalTelemetry}).
   */
  private TelemetryHealthView buildTelemetryHealth() {
    var snapshot = currentTelemetrySnapshot();
    if (snapshot == null) return TelemetryHealthView.empty();
    return new TelemetryHealthView(snapshot.flushFailures(), snapshot.gaugeCallbackFailures());
  }

  /**
   * Tempdoc 419 C3 V2 P1: shared snapshot accessor. Returns {@code null} on missing supplier,
   * supplier exception, null state, or snapshot exception. Both {@link #buildTelemetryHealth}
   * and the readiness envelope's {@code TELEMETRY} dim consume this so the V1 view and V2
   * readiness can't drift.
   */
  private TelemetryHealthSnapshot currentTelemetrySnapshot() {
    Supplier<TelemetryHealthState> supp = telemetryHealthSupplier;
    if (supp == null) return null;
    TelemetryHealthState state;
    try {
      state = supp.get();
    } catch (RuntimeException e) {
      return null;
    }
    if (state == null) return null;
    try {
      return state.snapshot();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Tempdoc 419 C3 V1: 30-min RRD trend for {@code metric} on the head-side store. Returns an
   * empty array when the store isn't wired or hasn't accumulated data yet.
   */
  private double[] recentTrend(String metric) {
    Supplier<RrdMetricStore> supp = rrdStoreSupplier;
    if (supp == null) return new double[0];
    RrdMetricStore store;
    try {
      store = supp.get();
    } catch (RuntimeException e) {
      return new double[0];
    }
    if (store == null) return new double[0];
    long nowSec = Instant.now().getEpochSecond();
    TimeSeriesResult result;
    try {
      result = store.query(metric, nowSec - 1800, nowSec);
    } catch (RuntimeException e) {
      log.debug("RRD query for {} failed: {}", metric, e.getMessage());
      return new double[0];
    }
    if (result == null) return new double[0];
    double[] values = result.values();
    return values == null ? new double[0] : values;
  }

  /** 335 §9: Build GPU status from NVML probe (best-effort, Head-side, cached). */
  private GpuStatusView buildGpuStatus() {
    // Return cached value if fresh enough (avoid NVML init/shutdown per poll).
    long now = System.currentTimeMillis();
    GpuStatusView cached = cachedGpuStatus;
    if (cached != null && (now - cachedGpuStatusAtMs) < GPU_CACHE_TTL_MS) {
      return cached;
    }

    GpuStatusView result = probeGpuStatus();
    cachedGpuStatus = result;
    cachedGpuStatusAtMs = now;
    return result;
  }

  /**
   * Tempdoc 629 (FLOOR): build the at-rest protection view from the (cached) disk-encryption probe.
   * Coarse on/off fidelity only — {@code qualityKnown=false} always, since the configuration-quality
   * distinction needs admin elevation (629 §R1 / confidence-probe P1).
   */
  private AtRestProtectionView buildAtRestProtection() {
    long now = System.currentTimeMillis();
    AtRestProtectionView cached = cachedAtRest;
    if (cached != null && (now - cachedAtRestAtMs) < AT_REST_CACHE_TTL_MS) {
      return cached;
    }
    AtRestProtectionView result;
    try {
      io.justsearch.app.services.atrest.DiskEncryptionProbe probe = diskEncryptionProbe;
      if (probe == null) {
        // Test-only / Builder path: self-construct from the index base path.
        probe = new io.justsearch.app.services.atrest.DiskEncryptionProbe(indexBasePath);
        diskEncryptionProbe = probe;
      }
      io.justsearch.app.services.atrest.AtRestProtection p = probe.current();
      result =
          new AtRestProtectionView(p.state().name(), p.source(), p.confidence().name(), false);
    } catch (RuntimeException e) {
      log.debug("at-rest probe failed: {}", e.getMessage());
      result = AtRestProtectionView.unknown();
    }
    cachedAtRest = result;
    cachedAtRestAtMs = now;
    return result;
  }

  /**
   * Tempdoc 629 (#2): the reactive conversation-encryption status. A direct, cheap read of
   * {@code DataKeyManager.state()} (synchronized, in-process — no probe, no cache needed), so the
   * Health card + the locked-chat gate project one always-current authority off the /api/status poll.
   */
  private io.justsearch.app.api.status.ConversationProtectionView buildConversationProtection() {
    Supplier<String> supplier = conversationProtectionStateSupplier;
    if (supplier == null) {
      return io.justsearch.app.api.status.ConversationProtectionView.unknown();
    }
    try {
      return new io.justsearch.app.api.status.ConversationProtectionView(supplier.get());
    } catch (RuntimeException e) {
      log.debug("conversation-protection state read failed: {}", e.getMessage());
      return io.justsearch.app.api.status.ConversationProtectionView.unknown();
    }
  }

  private GpuStatusView probeGpuStatus() {
    if (gpuCapabilitiesSupplier == null) {
      return GpuStatusView.unavailable();
    }
    try {
      GpuCapabilitiesService svc = gpuCapabilitiesSupplier.get();
      if (svc == null) {
        return GpuStatusView.unavailable();
      }
      // Tempdoc 587: read the UNIFIED resolver snapshot (CUDA folded into the NVML/nvidia-smi
      // merge). Utilization/VRAM stay NVML-only; cudaFunctional + source + confidence come from
      // the merged effective view, so the surface reads one resolver instead of the raw snapshot.
      GpuCapabilities caps =
          new io.justsearch.app.services.gpu.GpuCapabilityResolver(svc).snapshot();
      GpuCapabilities.Nvml nvml = caps.nvml();
      if (nvml == null || !nvml.available()) {
        return GpuStatusView.unavailable();
      }
      GpuCapabilities.Effective eff = caps.effective();
      Boolean cudaFunctional =
          eff != null && eff.cuda() != null ? eff.cuda().functional() : null;
      String source = eff != null ? eff.source() : null;
      String confidence = eff != null && eff.confidence() != null ? eff.confidence().name() : null;
      return new GpuStatusView(
          true,
          nvml.gpuUtilizationPercent(),
          nvml.memoryUtilizationPercent(),
          nvml.totalVramBytes(),
          nvml.usedVramBytes(),
          nvml.freeVramBytes(),
          nvml.driverVersion(),
          nvml.deviceCount(),
          // 419 C3 V1: 30-min trends from head-side RRD store.
          recentTrend(io.justsearch.app.services.observability.HeadGpuMetricCatalog
              .UTILIZATION_PERCENT),
          recentTrend(io.justsearch.app.services.observability.HeadGpuMetricCatalog
              .MEMORY_UTILIZATION_PERCENT),
          cudaFunctional,
          source,
          confidence);
    } catch (Exception e) {
      log.debug("GPU status probe failed: {}", e.getMessage());
      return GpuStatusView.unavailable();
    }
  }

  /** 381: Build model distribution status from install contract (cached, best-effort). */
  private static final long MODEL_DIST_CACHE_TTL_MS = 30_000;
  private volatile ModelDistributionStatusView cachedModelDist;
  private volatile long cachedModelDistAtMs;

  private ModelDistributionStatusView buildModelDistributionStatus() {
    long now = System.currentTimeMillis();
    ModelDistributionStatusView cached = cachedModelDist;
    if (cached != null && (now - cachedModelDistAtMs) < MODEL_DIST_CACHE_TTL_MS) {
      return cached;
    }
    ModelDistributionStatusView result = probeModelDistributionStatus();
    cachedModelDist = result;
    cachedModelDistAtMs = now;
    return result;
  }

  private ModelDistributionStatusView probeModelDistributionStatus() {
    try {
      io.justsearch.configuration.model.InstallContract contract = readInstallContract();
      if (contract == null) return ModelDistributionStatusView.unavailable();

      Path modelsDir = resolveModelsDir(contract);
      if (modelsDir == null) return ModelDistributionStatusView.unavailable();

      io.justsearch.configuration.model.HardwareProfile hardware = buildCurrentHardwareProfile();

      Map<String, ModelVariantView> variants = new LinkedHashMap<>();
      for (var entry : contract.models().entrySet()) {
        var model = entry.getValue();
        if (model.skipped()) {
          variants.put(
              entry.getKey(),
              new ModelVariantView(null, null, null, false, null, true, model.skipReason()));
          continue;
        }
        // Tempdoc 374 alpha.18 Bug I + alpha.21 Bug R: per-role gpuAllowed.
        // Pre-alpha.21 the status display passed `true` for ALL roles, which
        // diverged from the runtime composition path (composeCitationRole hardcodes
        // false). Result: citation reported as `degraded(CUDA)` in the status block
        // even though the runtime correctly ran it on CPU — round-11 evidence
        // showed `"FP16 variant not installed — running INT8 on CUDA"` as the
        // misleading degradation reason. Honoring EncoderRole.isPackageCpuOnly
        // here makes the display match what the runtime actually does.
        io.justsearch.configuration.model.VariantSelection sel =
            io.justsearch.configuration.model.VariantSelector.select(
                entry.getKey(), contract, hardware, modelsDir,
                /* gpuAllowed= */ !io.justsearch.ort.EncoderRole.isPackageCpuOnly(entry.getKey()));
        if (sel != null) {
          variants.put(
              entry.getKey(),
              new ModelVariantView(
                  sel.modelFile().getFileName().toString(),
                  sel.precision().name(),
                  sel.executionProvider().name(),
                  sel.degraded(),
                  sel.degradationReason(),
                  false,
                  null));
        }
      }

      String guidance = buildUpgradeGuidance(contract.downloadProfile());
      return new ModelDistributionStatusView(
          contract.downloadProfile().name(), variants, guidance);
    } catch (Exception e) {
      log.debug("Failed to build model distribution status: {}", e.getMessage());
      return ModelDistributionStatusView.unavailable();
    }
  }

  private io.justsearch.configuration.model.InstallContract readInstallContract() {
    Path aiHome = resolveAiHome();
    if (aiHome == null) return null;
    return io.justsearch.configuration.model.InstallContractIO.read(aiHome);
  }

  private static Path resolveAiHome() {
    try {
      io.justsearch.configuration.resolved.ConfigStore cs =
          io.justsearch.configuration.resolved.ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception e) {
      // best-effort
    }
    try {
      return io.justsearch.configuration.PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Tempdoc 374 alpha.20 Bug M: prefer the install contract's recorded modelsDir.
   * Mirrors {@code KnowledgeServer.resolveModelsDir} so head-side status display agrees
   * with worker-side contract path resolution. Pre-alpha.20 the head used
   * {@code aiHome/models} which is wrong for users who pre-stage models via
   * {@code JUSTSEARCH_MODELS_DIR} — the {@code modelDistribution.modelVariants}
   * status block reported every package as "Model file missing from disk" after
   * cold restart (round-10 evidence) even though the files were on disk.
   */
  private Path resolveModelsDir(io.justsearch.configuration.model.InstallContract contract) {
    if (contract != null && contract.modelsDir() != null) {
      return contract.modelsDir();
    }
    Path aiHome = resolveAiHome();
    return aiHome != null ? aiHome.resolve("models") : null;
  }

  private io.justsearch.configuration.model.HardwareProfile buildCurrentHardwareProfile() {
    boolean cudaFunctional =
        io.justsearch.configuration.EnvRegistry.GPU_ENABLED
            .get()
            .map(Boolean::parseBoolean)
            .orElse(false);
    long vramBytes = -1;
    if (gpuCapabilitiesSupplier != null) {
      try {
        var caps = gpuCapabilitiesSupplier.get();
        if (caps != null) {
          var snap = caps.snapshot();
          if (snap.effective() != null) {
            vramBytes = snap.effective().totalVramBytes();
          }
        }
      } catch (Exception e) {
        // best-effort
      }
    }
    boolean gpuDetected = vramBytes > 0 || cudaFunctional;
    return new io.justsearch.configuration.model.HardwareProfile(
        gpuDetected, cudaFunctional, vramBytes);
  }

  private static String buildUpgradeGuidance(
      io.justsearch.configuration.model.DownloadProfile profile) {
    return switch (profile) {
      case GPU_FULL -> null;
      case GPU_LITE ->
          "Your GPU has insufficient VRAM for chat/RAG. Upgrade to a GPU with 8+ GB VRAM, then re-run Install AI.";
      case CPU ->
          "No CUDA-capable GPU detected. Add an NVIDIA GPU with CUDA support, then re-run Install AI for full AI features.";
    };
  }

  /** Handles GET /api/health - stable, machine-oriented lifecycle surface (Appendix D schema v1). */
  void handleHealth(Context ctx) {
    LifecycleSnapshotV1 snapshot = computeLifecycleSnapshot();
    int httpStatus = healthHttpStatus(snapshot.lifecycle().state());
    ctx.status(httpStatus).json(snapshot);
  }

  static int healthHttpStatus(LifecycleState state) {
    if (state == null) {
      return 503;
    }
    return switch (state) {
      case LIFECYCLE_STATE_READY, LIFECYCLE_STATE_DEGRADED -> 200;
      case LIFECYCLE_STATE_STARTING,
          LIFECYCLE_STATE_ERROR,
          LIFECYCLE_STATE_STOPPING,
          LIFECYCLE_STATE_STOPPED,
          LIFECYCLE_STATE_UNSPECIFIED,
          UNRECOGNIZED -> 503;
    };
  }

  static boolean hasActiveIndexWork(WorkerOperationalView workerView) {
    if (workerView == null) {
      return false;
    }
    return workerView.migration().processingJobsCount() > 0
        || workerView.migration().pendingJobsCount() > 0
        || workerView.migration().pendingReadyJobsCount() > 0;
  }

  /**
   * Tempdoc 419 C3 V2 P3: composes the activity gate for {@code GpuSaturationMonitor}.
   *
   * <p>The gate is a sum of "GPU should be busy" signals; SATURATED requires it to be 0.
   * Components:
   *
   * <ul>
   *   <li>LLM engine queue depth — chat / embedding / generation backpressure
   *   <li>Worker indexing processing-jobs count
   *   <li>GPL training in RUNNING phase (LambdaMART builds run on GPU)
   *   <li>Online AI availability — llama-server keeps GPU resident even when idle (background
   *       KV warmup / cache residency); suppressing alerts whenever it's up avoids
   *       false-positive saturation events.
   * </ul>
   */
  private long computeGpuActivityGate(WorkerOperationalView workerView) {
    long gate = 0L;
    // Tempdoc 412 Phase 3 reconciliation: EngineMonitor was removed (its queue depth was
    // a stale duplicate of OnlineAiService state). The inference event substrate replaces it,
    // but a per-tick queue-depth gauge is deferred until the llama-server Prometheus scraper
    // lands (see InferenceRuntimeView's dropped `queue` field). The activity gate degrades
    // by one signal in the interim; the remaining (worker processing-jobs, GPL training, and
    // onlineAi.isAvailable) terms still cover the dominant false-positive cases.
    if (workerView != null) {
      gate += Math.max(0L, workerView.migration().processingJobsCount());
    }
    if (gplCoordinatorSupplier != null) {
      try {
        var coord = gplCoordinatorSupplier.get();
        if (coord != null && coord.getStatus().status() == GplJobStatus.Status.RUNNING) {
          gate += 1;
        }
      } catch (RuntimeException ignored) {
        // Best-effort.
      }
    }
    if (onlineAi != null && onlineAi.isAvailable()) {
      gate += 1;
    }
    return gate;
  }

  /**
   * Forward whichever specific cause the producer set, falling back to the generic
   * {@code INFERENCE_OFFLINE} for an unrecognized reason. The worker twin is
   * {@link #resolveWorkerReasonCode}.
   *
   * <p>Tempdoc 656 added this filter because the reason slot could hold arbitrary free prose from
   * the mode-change callback ("Inference offline", "GPU allocated to indexing"), which it silently
   * substituted the generic code for — deleting the cause. After tempdoc 837 S4/S5 swept the
   * producers, every non-test writer passes a {@link LifecycleReasonCode}, so the fallback is
   * defensive rather than routine: the slot now carries {@code inference.crashed} /
   * {@code inference.deactivated} / {@code inference.gpu_yielded_to_indexing} where it used to
   * carry one sentence the consumer had to discard.
   */
  private static String resolveInferenceReasonCode(
      io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability) {
    String reason = inferenceCapability.pendingReason();
    return LifecycleReasonCode.isKnown(reason) ? reason : LifecycleReasonCode.INFERENCE_OFFLINE.code();
  }

  /**
   * Tempdoc 837 §3.2: the worker twin of {@link #resolveInferenceReasonCode}. Every reason-bearing
   * worker {@code transition(...)} site now passes a {@link LifecycleReasonCode}, so this forwards
   * whatever specific cause the producer knew — {@code worker.lost} (it was serving and stopped),
   * {@code worker.index_corrupt}, {@code worker.spawn_recovery_exhausted}, {@code worker.shut_down} — and
   * falls back to the caller's generic code only for an unrecognized reason. Replaces the inlined
   * one-code special case (tempdoc 627) that could publish exactly {@code worker.restart_exhausted}
   * and collapsed everything else onto {@code worker.spawn.failed}.
   */
  private static String resolveWorkerReasonCode(
      io.justsearch.app.services.lifecycle.WorkerCapability workerCapability,
      LifecycleReasonCode fallback) {
    String reason = workerCapability.pendingReason();
    return LifecycleReasonCode.isKnown(reason) ? reason : fallback.code();
  }

  static String throughputReadinessReason(WorkerOperationalView workerView) {
    if (workerView == null || !hasActiveIndexWork(workerView)) {
      return null;
    }
    String windowState = workerView.telemetry().throughputWindowState();
    if ("STALLED".equalsIgnoreCase(windowState)) {
      return LifecycleReasonCode.WORKER_THROUGHPUT_STALLED.code();
    }
    if ("DEGRADED".equalsIgnoreCase(windowState)) {
      return LifecycleReasonCode.WORKER_THROUGHPUT_DEGRADED.code();
    }
    return null;
  }

  /**
   * Tempdoc 412 Phase 3: returns the canonical inference status view via the injected supplier.
   * The projection logic lives in {@code HeadAssembly.projectInferenceSnapshot} (correct
   * layering: it's the only place that has both the {@link InferenceLifecycleManager} reference
   * and the API record types). This handler is just a forwarder.
   */
  private InferenceRuntimeView buildInferenceView() {
    if (inferenceSnapshotSupplier == null) {
      return new InferenceRuntimeView(
          "OFFLINE",
          null,
          false,
          null,
          new io.justsearch.app.api.status.LifecycleCounters(0L, 0L, 0L),
          false,
          "",
          "",
          "",
          "");
    }
    return inferenceSnapshotSupplier.get();
  }

  /** Visible for tests (tempdoc 837 §3.2 asserts the worker arm's reason_code per producer state). */
  LifecycleSnapshotV1 computeLifecycleSnapshot() {
    Instant now = Instant.now();

    // Head component: this process is running if we're in this handler.
    LifecycleSnapshotV1.Component head =
        new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);

    // Worker component: derived from capability health. The bootstrap now keeps
    // workerCapability in sync with every health transition, so capability
    // health is always current — even in integration tests that create a real bootstrap.
    LifecycleSnapshotV1.Component worker = switch (workerCapability.health()) {
      case READY -> new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);
      // Tempdoc 837 §3.2 (was 627's one-code special case): forward whichever specific cause the
      // producer set — worker.lost, worker.index_corrupt, worker.spawn_recovery_exhausted — and fall back to
      // worker.spawn.failed only for an unrecognized reason. worker.spawn.failed thereby becomes TRUE
      // for the first time: it now fires only when the worker actually failed to start.
      case DEGRADED -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_ERROR,
          resolveWorkerReasonCode(workerCapability, LifecycleReasonCode.WORKER_SPAWN_FAILED));
      // Tempdoc 627: RECOVERING is transient (a supervised restart is in flight). Surface it as a
      // distinct, calm reason (worker.recovering) at DEGRADED severity — NOT ERROR/spawn-failed — so the
      // FE verdict renders a routine self-heal as "Restarting…" instead of "Service degraded".
      case RECOVERING -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED, LifecycleReasonCode.WORKER_RECOVERING.code());
      // Tempdoc 837 S3: OFFLINE covers two different truths — "never set up" (worker.not_configured)
      // and "we stopped it" (worker.shut_down, on an orderly teardown). Forward the specific one.
      case OFFLINE -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED,
          resolveWorkerReasonCode(workerCapability, LifecycleReasonCode.WORKER_NOT_CONFIGURED));
      case PENDING -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_STARTING, LifecycleReasonCode.WORKER_STARTING.code());
    };

    // Inference component: derived from capability health (migrated from OnlineAiService).
    // Tempdoc 656: mirrors the WORKER component's pattern just above (prefer a specific known
    // reason over the generic fallback) — previously every non-READY/non-STARTING state hardcoded
    // INFERENCE_OFFLINE regardless of what inferenceCapability.pendingReason() actually held,
    // which is why RuntimeActivationService's now-wired precise reasons (Task 2) weren't reaching
    // this composite/the FE degradation banner even after the runtime-manifest fix landed.
    LifecycleSnapshotV1.Component inference = switch (inferenceCapability.health()) {
      case READY -> new LifecycleSnapshotV1.Component(LifecycleState.LIFECYCLE_STATE_READY, null);
      case PENDING -> onlineAi != null && onlineAi.isStartingUp()
          ? new LifecycleSnapshotV1.Component(
              LifecycleState.LIFECYCLE_STATE_STARTING, LifecycleReasonCode.INFERENCE_STARTING.code())
          : new LifecycleSnapshotV1.Component(
              LifecycleState.LIFECYCLE_STATE_DEGRADED, resolveInferenceReasonCode(inferenceCapability));
      case DEGRADED, RECOVERING, OFFLINE -> new LifecycleSnapshotV1.Component(
          LifecycleState.LIFECYCLE_STATE_DEGRADED, resolveInferenceReasonCode(inferenceCapability));
    };

    LifecycleSnapshotV1.Components components =
        new LifecycleSnapshotV1.Components(head, worker, inference);

    // Tempdoc 501 Phase 26 (§13.7 Q5): prefer the runtime manifest's overall
    // lifecycle projection. The publisher composes it from the same
    // capability sources via LifecycleProjection.derive, but eliminating the
    // re-derivation here means /api/status and /api/runtime/manifest are
    // guaranteed to agree on the discriminator. Fall back to direct
    // derivation when the publisher isn't wired (test-only Builder) or
    // hasn't produced its first manifest yet (brief boot window).
    LifecycleState overallState = readManifestLifecycle();
    if (overallState == null) {
      overallState =
          io.justsearch.app.services.lifecycle.LifecycleProjection.derive(
              workerCapability, inferenceCapability);
    }
    LifecycleSnapshotV1.Lifecycle lifecycle =
        new LifecycleSnapshotV1.Lifecycle(overallState, resolveOverallReason(overallState, worker, inference), null);

    return new LifecycleSnapshotV1(
        LifecycleSnapshotV1.SCHEMA_VERSION, now.toString(), lifecycle, components);
  }

  /** Phase 26: read overall lifecycle from manifest; null on unwired/no-publish/unrecognized string. Package-private for Phase 39 test coverage. */
  LifecycleState readManifestLifecycle() {
    io.justsearch.ui.runtime.RuntimeManifestPublisher pub = this.runtimeManifestPublisher;
    if (pub == null) {
      return null;
    }
    io.justsearch.app.api.runtime.RuntimeManifest m = pub.current();
    if (m == null || m.lifecycle() == null) {
      return null;
    }
    try {
      return LifecycleState.valueOf(m.lifecycle());
    } catch (IllegalArgumentException unrecognized) {
      log.debug("manifest lifecycle string not a LifecycleState: {}", m.lifecycle());
      return null;
    }
  }

  private static String resolveOverallReason(
      LifecycleState overall,
      LifecycleSnapshotV1.Component worker,
      LifecycleSnapshotV1.Component inference) {
    if (overall == LifecycleState.LIFECYCLE_STATE_READY) {
      return null;
    }
    // Worker problems take priority — if the worker component is unhealthy, use its reason.
    if (worker.state() != LifecycleState.LIFECYCLE_STATE_READY) {
      return worker.reason_code();
    }
    // Otherwise the inference component drove the degradation.
    return inference.reason_code();
  }

  // Package-private for StatusLifecycleHandlerTest (tempdoc 600): lets the test drive a
  // BLOCKED_LEGACY worker view through the real component→composite rollup and assert the
  // `retrieval` composite resolves to DEGRADED carrying the compat reason code — the chain the
  // unit test for `compatBlockedReason` alone does not cover.
  ReadinessEnvelopeView buildReadinessEnvelope(
      WorkerOperationalView workerView,
      LifecycleSnapshotV1 lifecycleSnapshot,
      WorkerContact workerContact) {
    String observedAt = lifecycleSnapshot.observed_at();

    // Compute all components via exhaustive switch — adding a new ReadinessDimension
    // constant without handling it here is a compile error (Java 21+).
    Map<String, ReadinessComponentView> components = new LinkedHashMap<>();
    Map<ReadinessDimension, ReadinessComponentView> computed =
        new EnumMap<>(ReadinessDimension.class);
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      ReadinessComponentView comp =
          withContactProvenance(
              dim, computeComponent(dim, workerView, lifecycleSnapshot, observedAt), workerContact);
      components.put(dim.key(), comp);
      computed.put(dim, comp);
    }

    // Assemble composites from dim.composite() grouping — no hardcoded lists.
    // 821 §P P1: a composite also aggregates its members' staleness, because the FE consumes
    // composites (aiStateStore reads status.readiness.composites) and would otherwise never see
    // the per-component freshness facts computed just above. The aggregate is a projection of the
    // SAME component views — no second contact read — so the two can't disagree.
    Map<String, List<ReadinessDimension>> byComposite = new LinkedHashMap<>();
    for (ReadinessDimension dim : ReadinessDimension.values()) {
      byComposite.computeIfAbsent(dim.composite(), k -> new ArrayList<>()).add(dim);
    }
    Map<String, ReadinessCompositeView> composites = new LinkedHashMap<>();
    for (var entry : byComposite.entrySet()) {
      List<ReadinessComponentView> members = new ArrayList<>();
      for (ReadinessDimension dim : entry.getValue()) {
        members.add(computed.get(dim));
      }
      composites.put(entry.getKey(), readinessComposite(members));
    }

    return new ReadinessEnvelopeView(1, observedAt, components, composites);
  }

  /**
   * Tempdoc 600 Design A: maps the worker's embedding/schema compatibility state to an actionable
   * retrieval-degradation reason code, or {@code null} when retrieval is not compat-blocked.
   *
   * <p>Only the {@code BLOCKED_*} states map (a rebuild is genuinely required to restore the dense
   * leg): {@code REBUILDING} is owned by {@link #embeddingRebuildReason} (its remedy is to wait,
   * not to reindex), and {@code UNAVAILABLE}/{@code COMPATIBLE} are not retrieval-degradation
   * causes. Schema is checked before embedding to mirror the worker's own
   * {@code reindexRequiredReason} precedence. These reason codes are free strings on the readiness
   * composite (no enum/schema change) and are worded + given a remedy on the FE via
   * {@code readinessNotice.CAUSE_ROWS}.
   */
  static String compatBlockedReason(WorkerOperationalView workerView) {
    // Tempdoc 837 S6 removed the migration branch that used to sit here (tempdoc 628 Stage C's
    // `index.rebuilding`), restoring this to the pure compat-state function its javadoc above already
    // describes. The branch fired only while migrationState ∈ {MIGRATING, SWITCHING}, which is exactly
    // the window the FE verdict forces to `transitioning` — so the code was published on the wire and
    // no surface could ever word it. WHY a rebuild is running now travels as a facet of that
    // transition (io.justsearch.app.api.status.MigrationSource → verdict.reasons `source:<member>`), in the
    // authority that is actually rendered.
    var compat = workerView.compatibility();
    if (compat == null) {
      return null;
    }
    // Checked before the plain mismatch: both describe the same shape disagreement, but this one
    // additionally says the Worker has stopped trying to fix it by itself, which is the more
    // actionable of the two.
    if ("BLOCKED_REBUILD_BRAKE".equals(compat.indexSchemaCompatState())) {
      return LifecycleReasonCode.INDEX_REBUILD_BRAKE_EXHAUSTED.code();
    }
    if ("BLOCKED_MISMATCH".equals(compat.indexSchemaCompatState())) {
      return LifecycleReasonCode.INDEX_SCHEMA_MISMATCH.code();
    }
    if ("BLOCKED_LEGACY".equals(compat.indexSchemaCompatState())) {
      return LifecycleReasonCode.INDEX_BLOCKED_LEGACY.code();
    }
    if ("BLOCKED_MISMATCH".equals(compat.embeddingCompatState())) {
      return LifecycleReasonCode.INDEX_EMBEDDING_MISMATCH.code();
    }
    if ("BLOCKED_LEGACY".equals(compat.embeddingCompatState())) {
      return LifecycleReasonCode.INDEX_EMBEDDING_LEGACY.code();
    }
    return null;
  }

  /**
   * An in-place embedding rebuild is running: {@code embeddingCompatState=REBUILDING}, under which
   * the Worker refuses dense queries ({@code hybridFallback=REBUILD_IN_PROGRESS}). Returns
   * {@code index.embedding_rebuilding}, else {@code null}.
   *
   * <p>Nothing in the readiness envelope — and so nothing in the 595 verdict — observed this
   * outage before. (The conditions store does: {@code WorkerSnapshotTap} maps {@code REBUILDING} to
   * {@code embedding.not-ready}, tempdoc 726 F5. This closes the readiness side of the same fact.)
   * The 595 Stability axis sees only
   * generation migrations, and {@link #compatBlockedReason}/{@link #denseUnavailableReason} both
   * exclude {@code REBUILDING} by design (their remedies — reindex, or "no model" — are wrong
   * here; the remedy is to wait), so readiness reported the embedding dimension READY off
   * encoder-loaded semantics while queries were being served keyword-only.
   *
   * <p>{@code BLOCKED_*} and {@code REBUILDING} are mutually exclusive states of the same
   * embedding-compat enum, so this never competes with {@link #compatBlockedReason}.
   */
  static String embeddingRebuildReason(WorkerOperationalView workerView) {
    var compat = workerView.compatibility();
    if (compat == null) {
      return null;
    }
    return "REBUILDING".equals(compat.embeddingCompatState())
        ? LifecycleReasonCode.INDEX_EMBEDDING_REBUILDING.code()
        : null;
  }

  /**
   * Tempdoc 598 reopen (B-3): the dense/semantic leg cannot run for a reason a rebuild does NOT fix —
   * the embedding model is not loaded ({@code UNAVAILABLE} compat) or the embedder is unavailable on an
   * otherwise-{@code COMPATIBLE} index ({@code embeddingReady=false}). Returns
   * {@code index.dense_unavailable} for those POSITIVELY-known not-serviceable states, else {@code null}.
   *
   * <p>This makes the {@code retrieval} composite a faithful projection of the Worker's own issuance
   * predicate {@code denseServiceable = allowQueryEmbeddings()(==COMPATIBLE) && embeddingProvider.isAvailable()}
   * — reconstructed here from {@code embeddingCompatState} + {@code embeddingReady} (which equals
   * {@code isAvailable()} on the Worker, WorkerHealthService) — so the search banner stops claiming "fully
   * semantic" while AUTO has degraded to keyword. It does NOT fire for {@code BLOCKED_*} (handled by
   * {@link #compatBlockedReason} with the rebuild remedy), {@code REBUILDING} (handled by
   * {@link #embeddingRebuildReason}, whose remedy is to wait), or an UNKNOWN/empty compat or {@code null}
   * {@code embeddingReady} (we never alarm on "don't know" — only on a positively-known dense block).
   */
  static String denseUnavailableReason(WorkerOperationalView workerView) {
    var compat = workerView.compatibility();
    if (compat == null) {
      return null;
    }
    String embState = compat.embeddingCompatState();
    // No embedding model resolvable: dense genuinely cannot run (and a reindex won't add a model).
    if ("UNAVAILABLE".equals(embState)) {
      return LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE.code();
    }
    // COMPATIBLE index but the embedder is explicitly down (e.g. model load failed): dense falls to
    // keyword (Fix-A resolveAutoDense), so the banner must not claim semantic. Only on an explicit
    // false — a null/unknown embeddingReady is left alone (no false alarm during startup windows).
    if ("COMPATIBLE".equals(embState) && Boolean.FALSE.equals(workerView.embeddingReady())) {
      return LifecycleReasonCode.INDEX_DENSE_UNAVAILABLE.code();
    }
    return null;
  }

  /**
   * Computes a single readiness component. The switch expression is exhaustive — adding a new
   * {@link ReadinessDimension} constant without a corresponding case arm is a compile error.
   */
  private ReadinessComponentView computeComponent(
      ReadinessDimension dim,
      WorkerOperationalView workerView,
      LifecycleSnapshotV1 lifecycleSnapshot,
      String observedAt) {
    return switch (dim) {
      case WORKER_CONTROL_PLANE ->
          readinessComponent(
              mapWorkerLifecycleToReadiness(lifecycleSnapshot.components().worker().state()),
              lifecycleSnapshot.components().worker().reason_code(),
              dim.source(),
              observedAt);

      case INDEX_SERVING -> {
        boolean indexHealthy = workerView.core().indexHealthy();
        String indexState = workerView.core().indexState();
        String workerReason = lifecycleSnapshot.components().worker().reason_code();
        String throughputReason = throughputReadinessReason(workerView);
        String compatBlockedReason = compatBlockedReason(workerView);
        String embeddingRebuildReason = embeddingRebuildReason(workerView);
        String denseUnavailableReason = denseUnavailableReason(workerView);
        String state;
        String reason;

        // Tempdoc 837 S3: worker.shut_down joins worker.not_configured here — both mean "no worker is
        // serving and that is not a fault", which is the NOT_CONFIGURED verdict this branch encodes.
        // Without it an orderly teardown would fall through to the ERROR-shaped branches below.
        if (LifecycleReasonCode.WORKER_NOT_CONFIGURED.code().equals(workerReason)
            || LifecycleReasonCode.WORKER_SHUT_DOWN.code().equals(workerReason)) {
          // 821 §3-C1: this one branch derives head-side (the lifecycle snapshot's worker reason),
          // yet the dimension is classified worker-observed as a whole, so it is marked stale here
          // too. Deliberate: over-marking a NOT_CONFIGURED verdict is the safe direction, and
          // splitting the classification per-branch would put the decision back inline.
          state = READINESS_NOT_CONFIGURED;
          reason = workerReason;
        } else if (compatBlockedReason != null) {
          // Tempdoc 600 Design A: a serving index can be HEALTHY for keyword search yet have its
          // dense/semantic leg BLOCKED (a legacy index with no embedding fingerprint, or a
          // model/schema mismatch). That actionable cause was previously visible only as a coarse
          // `reindexRequired` boolean (synthesized into a generic banner). Emit it as a
          // retrieval-composite reason code so the ONE verdict authority (595) names the real cause.
          state = READINESS_DEGRADED;
          reason = compatBlockedReason;
        } else if (indexHealthy && embeddingRebuildReason != null) {
          // An in-place embedding rebuild: keyword serving is intact, but the Worker refuses dense
          // queries until it finishes. Nothing else in readiness observes this window, so without
          // this branch the composite claims full semantic retrieval while queries fall back to
          // keyword. Ranked below compatBlockedReason only for symmetry — the two are mutually
          // exclusive embedding-compat states.
          state = READINESS_DEGRADED;
          reason = embeddingRebuildReason;
        } else if (indexHealthy && denseUnavailableReason != null) {
          // Tempdoc 598 reopen (B-3): the index serves keyword fine, but the dense leg can't run for a
          // non-rebuild reason (no embedding model / embedder down). Degrade the `retrieval` composite so
          // the banner reflects "semantic unavailable" instead of over-claiming "fully semantic". Ranked
          // below compatBlockedReason (a rebuild-fixable block is the more actionable cause).
          state = READINESS_DEGRADED;
          reason = denseUnavailableReason;
        } else if (indexHealthy && throughputReason != null) {
          state = READINESS_DEGRADED;
          reason = throughputReason;
        } else if (indexHealthy) {
          state = READINESS_READY;
          reason = null;
        } else if ("STARTING".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.WORKER_STARTING.code();
        } else if ("NOT_STARTED".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_CONFIGURED;
          reason = LifecycleReasonCode.WORKER_NOT_STARTED.code();
        } else if ("ERROR".equalsIgnoreCase(indexState)
            || "UNAVAILABLE".equalsIgnoreCase(indexState)) {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.WORKER_UNAVAILABLE.code();
        } else {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.INDEX_NOT_HEALTHY.code();
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case AI -> {
        String state =
            mapInferenceLifecycleToReadiness(
                lifecycleSnapshot.components().inference().state());
        String reason = lifecycleSnapshot.components().inference().reason_code();
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case EMBEDDING -> {
        Boolean embReady = workerView.embeddingReady();
        String rebuildReason = embeddingRebuildReason(workerView);
        String state;
        String reason;
        if (embReady == null) {
          state = READINESS_UNKNOWN;
          reason = LifecycleReasonCode.WORKER_HEALTH_EMBEDDING_PROBE_MISSING.code();
        } else if (!Boolean.TRUE.equals(embReady)) {
          state = READINESS_NOT_READY;
          reason = LifecycleReasonCode.WORKER_HEALTH_EMBEDDING_NOT_READY.code();
        } else if (rebuildReason != null) {
          // The probe means "the encoder is loaded", not "the corpus is embedded". During an
          // in-place rebuild the encoder is up while queries cannot use embeddings, so a bare
          // READY here would assert semantic retrieval the Worker is refusing to serve.
          state = READINESS_DEGRADED;
          reason = rebuildReason;
        } else {
          state = READINESS_READY;
          reason = null;
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case CHUNK_EMBEDDING -> {
        // DEGRADED-capped: emit DEGRADED (not NOT_CONFIGURED) so the retrieval
        // composite stays DEGRADED rather than NOT_CONFIGURED, which would block consumers.
        boolean chunkReady = workerView.enrichment().chunk().chunkVectorsReady();
        double chunkCoverage = workerView.enrichment().chunk().chunkVectorCoveragePercent();
        String state;
        String reason;
        // Tempdoc 804 §D1 — an EMPTY corpus is not degradation. No chunk passages exist AND no
        // documents are indexed ⇒ there is nothing to embed yet, so "passage embeddings are not
        // ready" is a claim about work that does not exist, and it made the degradation banner the
        // default first-run experience on every fresh install. Both counts are required: indexed
        // documents with no chunks is genuinely pending work (keep NOT_READY), and chunks without
        // parents is an inconsistency we must not hide. Mirrors VISUAL_TEXT_EXTRACTION below, which
        // already reports READY when its needed-count is zero.
        if (workerView.enrichment().chunk().chunkDocCount() == 0
            && workerView.core().indexedDocuments() == 0) {
          state = READINESS_READY;
          reason = null;
        } else if (chunkReady) {
          state = READINESS_READY;
          reason = null;
        } else {
          state = READINESS_DEGRADED;
          reason =
              (chunkCoverage > 0)
                  ? LifecycleReasonCode.CHUNK_EMBEDDING_IN_PROGRESS.code()
                  : LifecycleReasonCode.CHUNK_EMBEDDING_NOT_READY.code();
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }

      case VISUAL_TEXT_EXTRACTION -> {
        var visual = workerView.visualExtraction();
        if (visual == null || visual.visualTextNeededCount() <= 0) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        String blockedReason = visual.ocrBlockedReason();
        if (blockedReason == null || blockedReason.isBlank()) {
          blockedReason = visual.vduBlockedReason();
        }
        if (blockedReason == null || blockedReason.isBlank()) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            normalizeVisualExtractionReason(blockedReason),
            dim.source(),
            observedAt);
      }

      case VISUAL_DOCUMENT_UNDERSTANDING -> {
        var visual = workerView.visualExtraction();
        if (visual == null || visual.visualEnrichmentNeededCount() <= 0) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        String blockedReason = visual.vduBlockedReason();
        if (blockedReason == null || blockedReason.isBlank()) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            normalizeVisualExtractionReason(blockedReason),
            dim.source(),
            observedAt);
      }

      case GPU -> {
        // Tempdoc 419 C3 V2 P3: GPU saturation surfaces SATURATED when sustained > 80% with
        // no activity gate open. Defaults to READY when the monitor isn't wired (NVML
        // unavailable / headless) — saturation is opt-in evidence, not a blocking signal.
        var monitor = gpuSaturationMonitor;
        if (monitor == null) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        long activityGate = computeGpuActivityGate(workerView);
        io.justsearch.ui.observability.GpuSaturationMonitor.Result gpuResult;
        try {
          gpuResult = monitor.compute(activityGate);
        } catch (RuntimeException e) {
          log.debug("GPU saturation compute failed: {}", e.getMessage());
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        if (!io.justsearch.ui.observability.GpuSaturationMonitor.STATE_SATURATED.equals(
            gpuResult.state())) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        yield readinessComponent(
            READINESS_DEGRADED,
            LifecycleReasonCode.GPU_SATURATED.code(),
            dim.source(),
            observedAt);
      }

      case TELEMETRY -> {
        // Tempdoc 419 C3 V2 P1: classify the live telemetry snapshot via the shared helper used
        // by GET /api/telemetry/health. Returns READY with null reason when the supplier isn't
        // wired (tests / boot paths without LocalTelemetry).
        var snapshot = currentTelemetrySnapshot();
        if (snapshot == null) {
          yield readinessComponent(READINESS_READY, null, dim.source(), observedAt);
        }
        var classification = TelemetryHealthClassifier.classify(snapshot);
        String state =
            classification.state() == LifecycleState.LIFECYCLE_STATE_READY
                ? READINESS_READY
                : READINESS_DEGRADED;
        yield readinessComponent(state, classification.reasonCode(), dim.source(), observedAt);
      }

      case LAMBDAMART_MODEL -> {
        // Absent by design (F-021: the LambdaMART reranker measured harmful and ships
        // unconfigured), so "not configured" is the healthy steady state, not degradation —
        // READY carrying an informational reason code. Emitting DEGRADED here made every
        // install read "Reduced capability" on the `retrieval` composite forever.
        String state = READINESS_READY;
        String reason = LifecycleReasonCode.LAMBDAMART_NOT_CONFIGURED.code();
        if (lambdamartRerankerSupplier != null && gplCoordinatorSupplier != null) {
          var reranker = lambdamartRerankerSupplier.get();
          var gplCoord = gplCoordinatorSupplier.get();
          if (reranker != null && gplCoord != null) {
            var trainingStatus = reranker.getTrainingStatus();
            if (reranker.isLoaded()) {
              state = READINESS_READY;
              reason = null;
            } else if (trainingStatus != null
                && "TRAINING".equals(trainingStatus.status())) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_TRAINING.code();
            } else if ((trainingStatus != null && "FAILED".equals(trainingStatus.status()))
                || gplCoord.getStatus().status()
                    == GplJobStatus.Status.FAILED
                || (trainingStatus != null
                    && "SUCCEEDED".equals(trainingStatus.status())
                    && !reranker.isLoaded())) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_FAILED.code();
            } else if (trainingStatus != null && trainingStatus.lastTrainedAt() != null) {
              state = READINESS_DEGRADED;
              reason = LifecycleReasonCode.LAMBDAMART_FAILED.code();
            }
          }
        }
        yield readinessComponent(state, reason, dim.source(), observedAt);
      }
    };
  }

  private static String normalizeVisualExtractionReason(String blockedReason) {
    return switch (blockedReason) {
      case "ocr.disabled" -> LifecycleReasonCode.OCR_DISABLED.code();
      case "ocr.engine_missing" -> LifecycleReasonCode.OCR_ENGINE_MISSING.code();
      case "ocr.language_missing" -> LifecycleReasonCode.OCR_LANGUAGE_MISSING.code();
      case "vdu.ai_offline" -> LifecycleReasonCode.VDU_AI_OFFLINE.code();
      case "vdu.insufficient_vram" -> LifecycleReasonCode.VDU_INSUFFICIENT_VRAM.code();
      case "vdu.missing_mmproj" -> LifecycleReasonCode.VDU_MISSING_MMPROJ.code();
      case "vdu.circuit_open" -> LifecycleReasonCode.VDU_CIRCUIT_OPEN.code();
      default -> LifecycleReasonCode.OCR_ENGINE_MISSING.code();
    };
  }

  /**
   * Builds a component with response-build provenance ({@code stale=false}, {@code stalenessMs=0}).
   * Worker-observed dimensions are re-stamped afterwards by {@link #withContactProvenance} when the
   * Worker was not reached — this factory never decides freshness on its own.
   */
  private static ReadinessComponentView readinessComponent(
      String state, String reasonCode, String source, String observedAt) {
    return new ReadinessComponentView(state, reasonCode, source, observedAt, false, 0);
  }

  /**
   * The Head's contact state with the Worker for a single {@code /api/status} response — the fact
   * {@link StatusMeta} already reports process-wide, projected onto individual dimensions.
   *
   * @param stale {@code true} when this response's Worker gRPC observation did not happen (the call
   *     threw, or the worker capability was unavailable so no call was attempted)
   * @param lastObservationAtMs epoch-ms of the newest successful Worker observation in this Head
   *     process, or {@code 0} when the Worker has never been reached
   * @param outOfContactMs how long the Head has been without a fresh Worker observation: measured
   *     from {@code lastObservationAtMs}, or — when there has never been one — from Head start,
   *     which is a true lower bound on the gap rather than an invented zero. Always {@code 0} while
   *     contact holds.
   */
  record WorkerContact(boolean stale, long lastObservationAtMs, long outOfContactMs) {

    /** The Worker answered during this response build. */
    static WorkerContact observed(long observedAtMs) {
      return new WorkerContact(false, observedAtMs, 0L);
    }

    /** The Worker could not be observed during this response build. */
    static WorkerContact lost(long lastObservationAtMs, long nowMs, long headStartedAtMs) {
      long since = lastObservationAtMs > 0 ? lastObservationAtMs : headStartedAtMs;
      return new WorkerContact(true, lastObservationAtMs, Math.max(0L, nowMs - since));
    }
  }

  /**
   * Whether a dimension's verdict is read from the Worker's gRPC {@link WorkerOperationalView}.
   *
   * <p>This is the classification {@code ReadinessComponentView.stale} depends on, and it is an
   * exhaustive switch on purpose: a new {@link ReadinessDimension} constant fails to compile until
   * someone decides which side it falls on. Each arm names the dimension's declared {@code
   * source()} so the mapping can be checked against the enum without leaving this method.
   *
   * <p>Worker-observed dimensions matter because when the RPC fails the handler substitutes {@link
   * WorkerOperationalView#fallback}: their arms then answer from placeholder data, so their verdict
   * is a derivation, not an observation. Head-local dimensions read head-side supervisor,
   * capability, or monitor state that is genuinely current at response-build time — a Worker
   * outage does not make them stale.
   */
  private static boolean workerObserved(ReadinessDimension dim) {
    return switch (dim) {
      case INDEX_SERVING, // source: worker_status
              EMBEDDING, // source: worker_health_check
              CHUNK_EMBEDDING, // source: worker_status
              VISUAL_TEXT_EXTRACTION, // source: worker_status
              // source: head_vdu_status — the blocked-reason overlay IS head-side, but this arm's
              // gate is visualEnrichmentNeededCount, a Worker count. A fallback view zeroes it and
              // the arm yields READY, so the dimension is classified by what it reads rather than
              // by its label; calling it head-local would ship an unobserved READY as fresh.
              VISUAL_DOCUMENT_UNDERSTANDING,
              // source: gpu_saturation_monitor — the NVML sample IS head-local and current, but
              // the arm feeds computeGpuActivityGate(workerView), whose first term is the Worker's
              // processingJobsCount. A fallback view zeroes that term, REMOVING a suppression
              // signal, so a Worker outage can flip this dimension to DEGRADED/gpu.saturated on
              // placeholder data. Since the unsafe direction is a false DEGRADED, the oldest input
              // governs the freshness claim: classified worker-observed by the same
              // what-it-reads standard as VDU above.
              GPU ->
          true;
      case WORKER_CONTROL_PLANE, // source: lifecycle_snapshot — head-side workerCapability.health()
              AI, // source: lifecycle_inference — head-side inferenceCapability.health()
              LAMBDAMART_MODEL, // source: head_gpl_status
              TELEMETRY -> // source: telemetry_health
          false;
    };
  }

  /**
   * Stamps a component with the Worker-contact freshness fact.
   *
   * <p>{@code observedAt} means "when the fact behind this component was last observed from its
   * source". For head-local dimensions and for worker-observed ones while contact holds, that is
   * this response's build time — they were observed just now — so the component passes through
   * unchanged. For a worker-observed dimension after contact is lost, the response-build timestamp
   * would be a false freshness claim: the verdict shown is fallback-derived and the last real
   * observation is older. Those components therefore carry the epoch of the newest successful
   * Worker observation instead, with {@code stalenessMs} measuring the gap to now. When the Worker
   * has never been reached in this Head process there is no observation to timestamp, so {@code
   * observedAt} is omitted rather than fabricated, and {@code stalenessMs} measures from Head start.
   *
   * <p>For a dimension that mixes head-local and Worker inputs (see {@code GPU} in {@link
   * #workerObserved}), the OLDEST input governs: the timestamp under-claims the freshness of the
   * head-local part rather than over-claiming the freshness of the Worker part.
   */
  private static ReadinessComponentView withContactProvenance(
      ReadinessDimension dim, ReadinessComponentView comp, WorkerContact contact) {
    if (!contact.stale() || !workerObserved(dim)) {
      return comp;
    }
    String observedAt =
        contact.lastObservationAtMs() > 0
            ? Instant.ofEpochMilli(contact.lastObservationAtMs()).toString()
            : null;
    return new ReadinessComponentView(
        comp.state(), comp.reasonCode(), comp.source(), observedAt, true, contact.outOfContactMs());
  }

  /**
   * Rolls member components up into one composite. State and reason codes combine as before; {@code
   * stale} is true when ANY member is stale and {@code maxStalenessMs} is the oldest member's age,
   * so a composite is exactly as fresh as its least-fresh part (tempdoc 821 §P P1).
   */
  private static ReadinessCompositeView readinessComposite(List<ReadinessComponentView> members) {
    List<String> states = new ArrayList<>();
    List<String> reasons = new ArrayList<>();
    boolean stale = false;
    long maxStalenessMs = 0L;
    for (ReadinessComponentView member : members) {
      states.add(member.state());
      String reasonCode = member.reasonCode();
      if (reasonCode != null && !reasonCode.isBlank()) {
        reasons.add(reasonCode);
      }
      if (member.stale()) {
        stale = true;
        maxStalenessMs = Math.max(maxStalenessMs, member.stalenessMs());
      }
    }
    return new ReadinessCompositeView(combineReadinessState(states), reasons, stale, maxStalenessMs);
  }

  private static String combineReadinessState(List<String> states) {
    if (states.stream().anyMatch(READINESS_NOT_READY::equals)) return READINESS_NOT_READY;
    if (states.stream().anyMatch(READINESS_UNKNOWN::equals)) return READINESS_UNKNOWN;
    if (states.stream().anyMatch(READINESS_NOT_CONFIGURED::equals)) return READINESS_NOT_CONFIGURED;
    if (states.stream().anyMatch(READINESS_DEGRADED::equals)) return READINESS_DEGRADED;
    return READINESS_READY;
  }

  private static String mapWorkerLifecycleToReadiness(LifecycleState state) {
    if (state == null) return READINESS_UNKNOWN;
    return switch (state) {
      case LIFECYCLE_STATE_READY -> READINESS_READY;
      case LIFECYCLE_STATE_DEGRADED -> READINESS_DEGRADED;
      case LIFECYCLE_STATE_STARTING -> READINESS_NOT_READY;
      case LIFECYCLE_STATE_ERROR, LIFECYCLE_STATE_STOPPING, LIFECYCLE_STATE_STOPPED ->
          READINESS_NOT_READY;
      case LIFECYCLE_STATE_UNSPECIFIED, UNRECOGNIZED -> READINESS_UNKNOWN;
    };
  }

  private static String mapInferenceLifecycleToReadiness(LifecycleState state) {
    if (state == null) return READINESS_UNKNOWN;
    return switch (state) {
      case LIFECYCLE_STATE_READY -> READINESS_READY;
      case LIFECYCLE_STATE_DEGRADED -> READINESS_DEGRADED;
      case LIFECYCLE_STATE_STARTING -> READINESS_NOT_READY;
      case LIFECYCLE_STATE_ERROR, LIFECYCLE_STATE_STOPPING, LIFECYCLE_STATE_STOPPED ->
          READINESS_NOT_READY;
      case LIFECYCLE_STATE_UNSPECIFIED, UNRECOGNIZED -> READINESS_UNKNOWN;
    };
  }

  private static boolean readinessComponentReady(
      ReadinessEnvelopeView envelope, String componentKey) {
    if (envelope == null) return false;
    ReadinessComponentView comp = envelope.components().get(componentKey);
    return comp != null && READINESS_READY.equals(comp.state());
  }
}
