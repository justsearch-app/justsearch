/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.ai.install;

import io.justsearch.app.api.AiInstallException;
import io.justsearch.app.api.AiInstallStatus;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.InstallPlanPreview;
import io.justsearch.app.api.OnlineAiRuntimeControl;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.OpCriticality;
import io.justsearch.app.api.OpLeaseOutcome;
import io.justsearch.app.api.OperationLeaseHandle;
import io.justsearch.app.api.OperationLeaseService;
import io.justsearch.app.services.runtimestate.RuntimeReconciler;
import io.justsearch.app.services.runtimestate.RuntimeStatus;
import io.justsearch.configuration.PlatformPaths;
import io.justsearch.configuration.SystemPropertyUtils;
import io.justsearch.configuration.model.CapabilityTier;
import io.justsearch.configuration.model.DownloadProfile;
import io.justsearch.configuration.model.ExecutionProvider;
import io.justsearch.configuration.model.HardwareProfile;
import io.justsearch.configuration.model.InstallContract;
import io.justsearch.configuration.model.InstallContractIO;
import io.justsearch.configuration.model.InstallIntent;
import io.justsearch.configuration.model.InstallPlan;
import io.justsearch.configuration.model.InstallPlanner;
import io.justsearch.configuration.model.ModelPackage;
import io.justsearch.configuration.model.ModelRegistry;
import io.justsearch.configuration.model.ModelRegistryLoader;
import io.justsearch.configuration.model.ModelVariant;
import io.justsearch.configuration.model.SkipCause;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.configuration.resolved.ResolvedConfig;
import io.justsearch.app.services.config.ConfigStoreRebuilder;
import io.justsearch.app.api.EffectivePolicy;
import io.justsearch.app.api.EnterprisePolicyService;
import io.justsearch.app.api.UiSettings;
import io.justsearch.app.services.settings.UiSettingsStore;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * v2 AI install service — hardware-aware, package-based, contract-generating.
 *
 * <p>Replaces v1 {@code AiInstallService}. Key differences:
 *
 * <ul>
 *   <li>Loads v2 registry (package-based with variant metadata)
 *   <li>Builds {@link HardwareProfile} → selects download profile (GPU-full/GPU-lite/CPU)
 *   <li>Downloads to target paths directly (no flat copies, no arrange step)
 *   <li>Writes {@link InstallContract} (bill of materials for runtime)
 *   <li>Runtime reads contract via composition root in KnowledgeServer (no manifest needed)
 * </ul>
 */
public final class AiInstallService implements io.justsearch.app.api.AiInstallService {
  private static final Logger log = LoggerFactory.getLogger(AiInstallService.class);
  private static final String REGISTRY_RESOURCE = "ai/model-registry.v2.json";

  private final OnlineAiService onlineAi;
  private final UiSettingsStore settingsStore;
  // Tempdoc 374 alpha.17 R3: late-bound. LocalApiServer constructs this service
  // before the worker bootstrap completes (HeadlessApp passes null at api-builder
  // time and late-binds via apiServer.lateBindKnowledgeServer). Pre-alpha.17
  // this field was final-null forever — tryRestartWorkerBestEffort silently
  // early-returned, the post-Install-AI worker restart never fired, and the
  // boot-1 worker JVM kept its empty ORT native_path until the user manually
  // relaunched. Volatile to make the late-bind visible to any subsequent
  // Install AI invocation.
  private volatile KnowledgeServerBootstrap knowledgeServer;
  private final EnterprisePolicyService policyService;
  // Tempdoc 737 fix pack (fix 3): the single-writer runtime authority. When present, the post-
  // install smoke test brackets its engine use in an INSTALL_SMOKE_TEST procedure and requests the
  // engine through the reconciler instead of a raw switchToOnlineMode() — so the reconciler does not
  // fight the smoke test's engine-up (spec is still false during a fresh install: install ≠ enable).
  // Nullable; the legacy raw path is retained for test / non-configured constructions.
  private final RuntimeReconciler reconciler;
  // Tempdoc 374 alpha.27: VramDetector dependency removed; HardwareProfile build
  // now uses GpuCapabilitiesService (NVML-first) directly.

  private final Path homeDir;
  private final Path modelsDir;

  // Liveness backstop window (575 §17 Face C, polled-state model): a "running" install with no
  // progress for longer than this is treated as a dead owner and reclaimed to terminal on read.
  private static final long STALE_RUNNING_MS = 5 * 60_000L; // 5 min

  /** Whole budget for the post-install smoke-test answer, spent in cancellation-polling slices. */
  private static final long SMOKE_TEST_TIMEOUT_MS = 60_000L; // 60 s

  private final Object lock = new Object();
  private final AtomicBoolean running = new AtomicBoolean(false);

  /**
   * Op-lease SPI (tempdoc 617). This is the primary model-acquisition path: it downloads and moves
   * roughly 9 GB into AI Home on a virtual thread that outlives its HTTP request, so the
   * request-scoped mutation lease in {@code ApiSecurityFilters} is long released while
   * {@code DownloadExecutor.moveAtomicBestEffort} is still promoting partial files into place.
   * Without a lease, upgrade prepare reports no blocker and the installer can launch mid-download —
   * the exact outcome D2's "an update never touches models" invariant exists to prevent. Defaults
   * to no-op so existing constructors and tests are unaffected.
   */
  private volatile OperationLeaseService operationLeases = OperationLeaseService.noOp();

  /**
   * Late-binds the op-lease SPI. Set by {@code ServicePhase}, which creates the lease service after
   * this service is constructed.
   */
  public void setOperationLeaseService(OperationLeaseService leases) {
    this.operationLeases = leases == null ? OperationLeaseService.noOp() : leases;
  }
  private final AtomicBoolean cancelFlag = new AtomicBoolean(false);
  private final AiInstallStatus status = new AiInstallStatus();
  private volatile DownloadExecutor downloadExecutor;

  /**
   * Halts the in-flight acquisition between items (tempdoc 840 Phase 3, task 5). Nothing exposes it
   * over the wire yet — this is the mechanism, and the endpoint is a later phase.
   */
  private final AcquisitionPause pause = new AcquisitionPause(cancelFlag::get);

  /**
   * What a stage's op-lease claims its download will take, from the only evidence available before
   * it runs: its bytes over an assumed throughput. Deliberately optimistic, per {@code
   * OperationLeaseService#register}'s contract ("optimistic upper bound … should not be the
   * worst-case timeout") — the service derives the expiry from it, doubling it and capping at an
   * hour, so an over-generous estimate is what actually costs something.
   */
  private static final long LEASE_ESTIMATE_BYTES_PER_SEC = 2_000_000L; // ~16 Mbit/s

  private static final long MIN_STAGE_LEASE_SEC = 60L;
  private static final long MAX_STAGE_LEASE_SEC = 3600L;

  /**
   * The first stage's lease estimate, which necessarily predates the plan: it is registered on the
   * CALLING thread (see {@link #startInstall}) so upgrade prepare can never observe no blocker while
   * a download is starting, and the plan is only computed once the install thread runs. Every LATER
   * stage's lease is sized from its own bytes.
   */
  private static final long PRE_PLAN_STAGE_LEASE_SEC = 900L;

  // Tempdoc 562: `installedFully` is session-ephemeral — only set true at the end of an install RUN, never
  // rehydrated — so after a restart a returning user with models already on disk reads a false "Not
  // Installed" (and is offered a ~10 GB re-download). "Is installed" is a function of disk, not a remembered
  // event. On the first status reads after boot we recompute it once from on-disk model presence (the
  // planner's own already-installed detection). The one-shot is consumed only on a DEFINITIVE answer: a
  // successful plan marks `diskRecomputeDone`; a transient dependency failure leaves it open to retry
  // (capped), so an early-boot hiccup can't permanently strand the false "Not Installed". `recomputeInProgress`
  // keeps a single concurrent runner; `diskRecomputeDone` short-circuits the hot poll path after that.
  private volatile boolean diskRecomputeDone = false;
  private final AtomicBoolean recomputeInProgress = new AtomicBoolean(false);
  private final AtomicInteger recomputeAttempts = new AtomicInteger(0);
  private static final int MAX_RECOMPUTE_ATTEMPTS = 3;

  // Bytes an interrupted run left staged in `.partial` files, as last derived FROM DISK by the
  // planner. Kept off the polling hot path (the plan needs a hardware probe): refreshed at every
  // point the plan is already being computed — boot recompute, plan preview, install start — plus
  // explicitly on cancellation, the one moment the number changes without a plan being asked for.
  private volatile long resumableBytesOnDisk;

  /**
   * What the runtime OBSERVES per registry package id ({@code "active"} | {@code "inactive"} |
   * {@code "unknown"}), tempdoc 824 §3.3c. Late-bound to {@code
   * RuntimeActivationService.functionalStatusByPackage} — the same derivation {@code GET
   * /api/ai/runtime/status} publishes, so this is a projection of that authority, not a second one
   * (the install status cannot disagree with the runtime status about whether SPLADE is running).
   *
   * <p>Defaults to "nothing observed": every package reads {@code "unknown"}, which is the
   * fail-closed answer — the alarming "a required component is missing" copy is what an unknown
   * capability still produces, exactly as before this field existed.
   */
  private volatile java.util.function.Supplier<Map<String, String>> functionalStatusSource =
      Map::of;

  /**
   * How long one functional-status projection is reused. The FE polls {@code GET
   * /api/ai/install/status} at ~1 Hz for the whole length of an install, and the projection is not
   * free — it resolves four encoder rows, two of which read the Worker's policy snapshot. Capability
   * state changes on the scale of a runtime restart, so a 5 s window costs the surface nothing it
   * can perceive and takes the fan-out off the poll path.
   */
  private static final long FUNCTIONAL_STATUS_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

  /** One projection plus when it was taken; null until the first read. */
  private record CachedFunctionalStatus(Map<String, String> value, long atNanos) {}

  private volatile CachedFunctionalStatus functionalStatusCache;

  /** Monotonic, so the TTL cannot be skewed by a wall-clock adjustment. */
  private volatile java.util.function.LongSupplier nanoClock = System::nanoTime;

  /** Usable-space source for the per-stage disk precondition; swapped in tests (tempdoc 840 U2). */
  private volatile FreeSpaceCheck.Probe freeSpaceProbe = FreeSpaceCheck.filesystemProbe();

  /**
   * The running stage's live rate estimator, or null when no acquisition is in flight.
   *
   * <p>Held rather than sampled because the estimate has to be derived AT READ TIME. {@link
   * AcquisitionRate#estimate} decides "this transfer has stopped reporting" by comparing its newest
   * sample against the clock, and the only previous caller asked for the estimate on the line after
   * feeding it a sample — where that comparison is always ~0 ns and the stall arm could never fire.
   * A genuinely stalled transfer therefore left its last measured rate standing on the wire for as
   * long as it was stalled, which is the most confident lie the class is built to avoid. Same
   * refreshed-on-read reasoning as the functional-status projection in {@link #getStatus}.
   */
  private volatile java.util.function.Supplier<AcquisitionRate.Estimate> liveRateSource;

  /** Test seam: report a chosen amount of free space without needing a real full disk. */
  void setFreeSpaceProbeForTest(FreeSpaceCheck.Probe probe) {
    this.freeSpaceProbe = probe == null ? FreeSpaceCheck.filesystemProbe() : probe;
  }

  /**
   * Late-binds the runtime's observed per-package capability status. Called by {@code ServicePhase}
   * after {@code RuntimeActivationService} exists — it takes THIS service as a constructor
   * argument, so the dependency can only run in this direction.
   */
  public void setFunctionalStatusSource(
      java.util.function.Supplier<Map<String, String>> source) {
    this.functionalStatusSource = source == null ? Map::of : source;
    this.functionalStatusCache = null;
  }

  /** Test seam: ages the functional-status cache without spending five real seconds. */
  void setNanoClockForTest(java.util.function.LongSupplier clock) {
    this.nanoClock = clock == null ? System::nanoTime : clock;
  }

  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService,
      Path aiHomeDir) {
    this(onlineAi, settingsStore, knowledgeServer, policyService, aiHomeDir, null);
  }

  /** Tempdoc 737 fix pack (fix 3): canonical constructor threading the nullable reconciler. */
  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService,
      Path aiHomeDir,
      RuntimeReconciler reconciler) {
    this.onlineAi = onlineAi;
    this.settingsStore = settingsStore;
    this.knowledgeServer = knowledgeServer;
    this.policyService = policyService;
    this.reconciler = reconciler;
    this.homeDir = aiHomeDir;
    // Honor JUSTSEARCH_MODELS_DIR (env or sysprop) so Install AI checks the
    // operator-supplied dir for already-present models. When all required
    // models are present, InstallPlanner produces zero downloads and Install
    // AI completes near-instantly. Tempdoc 374 sandbox round 2 finding #2.
    String envModelsDir = io.justsearch.configuration.EnvRegistry.MODELS_DIR.get().orElse(null);
    if (envModelsDir != null && !envModelsDir.isBlank()) {
      Path candidate = Path.of(envModelsDir.trim());
      if (Files.isDirectory(candidate)) {
        log.info("AiInstallService: using JUSTSEARCH_MODELS_DIR={} as models root (env override)", candidate);
        this.modelsDir = candidate;
      } else {
        log.warn("JUSTSEARCH_MODELS_DIR={} does not exist; falling back to default {}/models",
            candidate, homeDir);
        this.modelsDir = homeDir.resolve("models");
      }
    } else {
      this.modelsDir = homeDir.resolve("models");
    }
  }

  /** Production constructor — resolves AI Home from ConfigStore / platform defaults. */
  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService) {
    this(onlineAi, settingsStore, knowledgeServer, policyService, resolveHomeDir(), null);
  }

  /**
   * Tempdoc 737 fix pack (fix 3): production constructor resolving AI Home and threading the nullable
   * {@link RuntimeReconciler} used to bracket the post-install smoke test.
   */
  public AiInstallService(
      OnlineAiService onlineAi,
      UiSettingsStore settingsStore,
      KnowledgeServerBootstrap knowledgeServer,
      EnterprisePolicyService policyService,
      RuntimeReconciler reconciler) {
    this(onlineAi, settingsStore, knowledgeServer, policyService, resolveHomeDir(), reconciler);
  }

  /**
   * Tempdoc 374 alpha.17 R3: late-bind the worker reference. Called from
   * {@code LocalApiServer.lateBindKnowledgeServer} once the Worker bootstrap
   * finishes. Without this, {@link #tryRestartWorkerBestEffort} silently
   * no-ops at end of Install AI and the worker never picks up the new ORT
   * native_path until the user manually relaunches.
   */
  public void setKnowledgeServer(KnowledgeServerBootstrap knowledgeServer) {
    this.knowledgeServer = knowledgeServer;
  }

  /** Package-private accessor for the late-bind regression test. */
  KnowledgeServerBootstrap knowledgeServerForTest() {
    return knowledgeServer;
  }

  public ModelRegistry getManifest() {
    return ModelRegistryLoader.loadFromClasspath(REGISTRY_RESOURCE);
  }

  /**
   * Tempdoc 656 Task 4: the already-resolved models root (honors {@code JUSTSEARCH_MODELS_DIR}
   * per the constructor above), exposed read-only so a preflight check can resolve the same
   * on-disk paths this service uses to install, without re-deriving the env-override logic.
   */
  public Path modelsDir() {
    return modelsDir;
  }

  /** Tempdoc 656 Task 4: the resolved AI home directory, for packages using {@code installRoot}. */
  public Path aiHome() {
    return homeDir;
  }

  public AiInstallStatus getStatus() {
    maybeRecomputeInstalledFromDisk();
    Map<String, String> observed = observedFunctionalStatus();
    Set<String> declined = declinedPackages();
    synchronized (lock) {
      reapIfStale();
      refreshRateEstimateLocked();
      status.resumableBytes = resumableBytesOnDisk;
      // Tempdoc 840 Phase 4 — the pause gate the run actually waits on is the authority; mirroring
      // its bit here on read is what keeps a second copy from existing at all.
      status.paused = pause.isPaused();
      // Tempdoc 824 §3.3c — refreshed on read, not on write: the capability comes up and goes down
      // independently of any install run, so a value stamped at completion would be stale for the
      // whole life of the status object (which outlives the run by design).
      for (var ps : status.packages) {
        ps.functionalStatus = observed.getOrDefault(ps.packageId, "unknown");
        // Same read-time reasoning, for the same reason: a decline recorded between two polls must
        // show on the next one, and the settings store is the one place it lives.
        ps.declined = declined.contains(ps.packageId);
      }
      // Deep copy INSIDE the lock: the caller (the HTTP handler) serializes with no lock held while
      // the install thread keeps mutating the live object — including clearing and repopulating
      // status.packages — so handing out the live reference made a ConcurrentModificationException
      // reachable at the 1 Hz poll. The two writes above stay on the live object; only the copy
      // leaves.
      return status.snapshot();
    }
  }

  /**
   * The runtime's observed per-package capability status, or an empty map when nothing is bound or
   * the source throws. Never lets a diagnostic projection break a status read.
   *
   * <p>Reused for {@link #FUNCTIONAL_STATUS_TTL_NANOS} so a 1 Hz install poll cannot fan the
   * projection out per read. A failed projection is cached too — a throwing source is exactly the
   * case where repeating it every poll costs the most and buys the least.
   */
  private Map<String, String> observedFunctionalStatus() {
    long now = nanoClock.getAsLong();
    CachedFunctionalStatus cached = functionalStatusCache;
    if (cached != null && now - cached.atNanos() < FUNCTIONAL_STATUS_TTL_NANOS) {
      return cached.value();
    }
    Map<String, String> fresh;
    try {
      Map<String, String> observed = functionalStatusSource.get();
      fresh = observed == null ? Map.of() : observed;
    } catch (Exception e) {
      log.debug("AiInstall functional-status projection unavailable (best-effort): {}", e.toString());
      fresh = Map.of();
    }
    functionalStatusCache = new CachedFunctionalStatus(fresh, now);
    return fresh;
  }

  /**
   * Re-derives {@link AiInstallStatus#resumableBytes} from disk via the planner. Used where the
   * staged-byte total changes but no plan is being computed anyway (cancellation); everywhere else
   * {@link #recordResumableBytes} folds the number off a plan already in hand. Best-effort — a probe
   * failure leaves the last known value rather than reporting a false zero, which would read as
   * "your download was discarded".
   */
  private void refreshResumableBytesFromDisk() {
    try {
      recordResumableBytes(
          InstallPlanner.plan(
              getManifest(),
              buildHardwareProfile(),
              installIntent(),
              declinedPackages(),
              modelsDir,
              homeDir));
    } catch (Exception e) {
      log.debug("AiInstall resumable-bytes probe skipped (best-effort): {}", e.toString());
    }
  }

  /** Folds a freshly-computed plan's staged-byte total into the polled status. */
  private void recordResumableBytes(InstallPlan plan) {
    resumableBytesOnDisk = plan.resumableBytes();
  }

  @Override
  public InstallPlanPreview previewInstallPlan() {
    InstallPlanPreview preview = new InstallPlanPreview();
    ModelRegistry registry = getManifest();
    HardwareProfile hardware = buildHardwareProfile();
    DownloadProfile profile = hardware.downloadProfile();
    InstallIntent intent = installIntent();
    preview.intent = intent.id();
    preview.downloadProfile = profile.name();

    // Reuse the PURE planner (no side effects) — tempdoc 381 §F "show the plan before download".
    InstallPlan plan =
        InstallPlanner.plan(registry, hardware, intent, declinedPackages(), modelsDir, homeDir);
    // What the download will actually COST: complete files are already excluded by the planner, and
    // `remainingBytes()` also drops the bytes an interrupted run left staged. Stating `totalBytes()`
    // here charged the user for bytes already on their disk (Sandbox round 8).
    preview.totalDownloadBytes = plan.remainingBytes();
    preview.resumableBytes = plan.resumableBytes();
    recordResumableBytes(plan);

    // Bytes still to download, per package id — RESUME-ADJUSTED, exactly like the headline
    // `totalDownloadBytes` above, so the rows sum to the total instead of quietly re-introducing at
    // component granularity the "charged the user for bytes already on their disk" defect the
    // headline was fixed for (Sandbox round 8, per-component half in tempdoc 840 R3).
    Map<String, Long> downloadByPkg = new HashMap<>();
    for (var dl : plan.downloads()) {
      downloadByPkg.merge(dl.packageId(), dl.remainingBytes(), Long::sum);
    }

    // One estimate per tier, in canonical order.
    Map<CapabilityTier, InstallPlanPreview.TierEstimate> byTier = new LinkedHashMap<>();
    for (CapabilityTier t : CapabilityTier.values()) {
      var te = new InstallPlanPreview.TierEstimate();
      te.tier = t.id();
      te.label = t.label();
      te.includedByIntent = intent.wants(t);
      byTier.put(t, te);
    }
    for (ModelPackage pkg : registry.packages()) {
      CapabilityTier t = pkg.tier();
      // A devOnly package (tempdoc 842) is never installed, so its bytes must never appear in a
      // total the user consents to — the planner skips it, and this projection has to agree.
      if (t == null || pkg.devOnly()) {
        continue;
      }
      var te = byTier.get(t);
      ModelVariant variant = pkg.selectVariant(profile);
      long footprint = variant != null ? variant.sizeBytes() : 0L;
      for (var sf : pkg.supportingFiles()) {
        footprint += sf.sizeBytes();
      }
      te.totalBytes += footprint;
      te.downloadBytes += downloadByPkg.getOrDefault(pkg.id(), 0L);
    }
    preview.tiers.addAll(byTier.values());

    // Per-component rows. Every value is projected from the SAME plan the tier totals came from, so
    // the list cannot disagree with what an install would actually do. Derived here rather than read
    // off `status.packages`, which is run bookkeeping and is empty on an idle machine.
    Set<String> installedIds = new HashSet<>(plan.alreadyInstalled());
    Map<String, InstallPlan.SkippedPackage> skippedById = new HashMap<>();
    for (var sk : plan.skipped()) {
      skippedById.putIfAbsent(sk.packageId(), sk);
    }
    Set<String> declined = declinedPackages();
    for (ModelPackage pkg : registry.packages()) {
      // A devOnly package (tempdoc 842) is not a component the user has any decision about: it is
      // never installed, never declinable, and listing it would offer a choice that does nothing.
      if (pkg.devOnly()) {
        continue;
      }
      var ce = new InstallPlanPreview.ComponentEstimate();
      ce.id = pkg.id();
      ce.label = pkg.label() == null ? pkg.id() : pkg.label();
      ce.description = pkg.description() == null ? "" : pkg.description();
      ce.tier = pkg.tier() == null ? "" : pkg.tier().id();
      ce.necessity = pkg.necessity() == null ? "" : pkg.necessity().id();
      ce.declinable = pkg.necessity() != null && pkg.necessity().userDeclinable();
      ce.declined = declined.contains(pkg.id());

      ModelVariant variant = pkg.selectVariant(profile);
      long footprint = variant != null ? variant.sizeBytes() : 0L;
      for (var sf : pkg.supportingFiles()) {
        footprint += sf.sizeBytes();
      }
      ce.totalBytes = footprint;
      ce.downloadBytes = downloadByPkg.getOrDefault(pkg.id(), 0L);

      InstallPlan.SkippedPackage skipped = skippedById.get(pkg.id());
      if (installedIds.contains(pkg.id())) {
        ce.state = "installed";
      } else if (skipped != null) {
        // The planner already decided WHY, with a typed cause. Re-deriving it from the prose reason
        // would be the classification-by-string defect this codebase removed elsewhere.
        ce.state =
            switch (skipped.cause() == null ? SkipCause.HARDWARE : skipped.cause()) {
              case USER_DECLINED -> "declined";
              case INTENT -> "not-in-mode";
              case HARDWARE -> "unavailable";
              // Not reachable today — devOnly packages are filtered out of this loop above. The arm
              // exists so the switch stays exhaustive: if that filter is ever removed, a dev package
              // reads as "this build does not install it" rather than as a hardware failure.
              case DEV_ONLY -> "not-in-mode";
            };
        if ("unavailable".equals(ce.state)) {
          ce.unavailableReason = skipped.reason() == null ? "" : skipped.reason();
        }
      } else if (downloadByPkg.containsKey(pkg.id())) {
        // Keyed on the plan still LISTING files for this package, not on its byte cost: a file whose
        // .partial already holds every one of its bytes costs nothing more to fetch and is still not
        // installed — it has not been verified or promoted to its final path.
        ce.state = "to-download";
      } else {
        // Wanted, not skipped, nothing left to fetch — every file is already on disk even though the
        // planner did not list the package as fully installed (a partially-contracted package).
        ce.state = "installed";
      }
      preview.components.add(ce);
    }
    return preview;
  }

  /**
   * Tempdoc 562 — durable installed-state projection. On the first status read after boot, if no install
   * has run this session (state {@code idle}, {@code installedFully} false), recompute "is installed" from
   * on-disk model presence by reusing the planner's own already-installed detection: when the plan has
   * nothing left to download (and something already installed), the models are present, so a returning user
   * reads the honest "AI Offline / Start AI" (the runtime is one activate away) instead of a false
   * "Not Installed" that implies the ~10 GB download was lost. This is NOT keyed on the runtime exe alone
   * (the CPU baseline ships bundled, so a fresh pre-download machine has a "default" variant but no model) —
   * the planner knows the chat model + encoders are still missing on a fresh install, so that case correctly
   * stays "Not Installed". The one-shot ({@code diskRecomputeDone}) is consumed only on a definitive answer
   * (a successful plan); a transient failure retries on a later poll, capped.
   */
  private void maybeRecomputeInstalledFromDisk() {
    if (diskRecomputeDone) {
      return; // hot-path short-circuit once a definitive answer was reached.
    }
    synchronized (lock) {
      if (status.installedFully || running.get() || !"idle".equals(status.state)) {
        return;
      }
    }
    // Single concurrent runner — a contender just skips this poll (it sees the result on the next read).
    if (!recomputeInProgress.compareAndSet(false, true)) {
      return;
    }
    try {
      ModelRegistry registry = getManifest();
      HardwareProfile hardware = buildHardwareProfile();
      InstallPlan plan =
          InstallPlanner.plan(
              registry, hardware, installIntent(), declinedPackages(), modelsDir, homeDir);
      // A successful plan is a DEFINITIVE answer (installed or not) — consume the one-shot now.
      diskRecomputeDone = true;
      // Same disk-is-the-authority reasoning, applied to the OTHER thing a restart forgets: bytes a
      // cancelled run left staged. Without this the first post-restart poll reports 0 staged bytes.
      recordResumableBytes(plan);
      if (applyInstalledFromPlan(plan, registry)) {
        log.info(
            "AiInstall: recomputed installedFully=true from on-disk model presence after restart (tempdoc 562).");
      }
    } catch (Exception e) {
      // Transient failure (resource/IO/probe) — do NOT consume the one-shot; retry on a later poll, capped
      // so a persistent error cannot re-run the plan forever.
      if (recomputeAttempts.incrementAndGet() >= MAX_RECOMPUTE_ATTEMPTS) {
        diskRecomputeDone = true;
      }
      log.debug("AiInstall on-disk installed recompute skipped (best-effort): {}", e.toString());
    } finally {
      recomputeInProgress.set(false);
    }
  }

  /**
   * Tempdoc 562 — the plan→installed decision, package-private so the positive path can be unit-tested by
   * injecting a plan (staging the registry's full file set on disk would be brittle). "Fully on disk" = the
   * planner found nothing left to download AND something already installed (the latter guards the
   * empty-registry case; the former is the profile-aware "chat model + required encoders present" check, not
   * the bundled runtime exe alone). When so, set {@code installedFully} — re-checking under the lock that no
   * real install run has taken over since the caller's guard. Returns whether it flipped the status.
   *
   * <p>Tempdoc 804 §B8 (round-10 F2) — the plan alone is a claim about the CURRENT registry, not about the
   * installation. A newer app version that adds a package made every completed installation read "Not
   * Installed" with an empty package list. So when downloads remain, completeness is measured against the
   * {@link InstallContract} that recorded the install: a remaining download for a package the contract
   * covered is a genuine gap (still "Not Installed"); one for a package the contract never covered is a
   * newly-registered artifact, reported as {@link AiInstallStatus#pendingRegistryAdditions}.
   */
  boolean applyInstalledFromPlan(InstallPlan plan, ModelRegistry registry) {
    return applyInstalledFromPlan(plan, registry, readInstallContractBestEffort());
  }

  /** Contract-injecting overload — the seam the B8 regression test drives (no on-disk contract staging). */
  boolean applyInstalledFromPlan(InstallPlan plan, ModelRegistry registry, InstallContract contract) {
    // Tempdoc 805 G.3 — the decision moved into InstallCompleteness, at FILE granularity and aware of
    // the contract's entry kind (a skipped entry claims no files). The package-level `containsKey`
    // this replaces called round 11's skipped cuda-runtime entry a contracted gap.
    InstallCompleteness completeness =
        InstallCompleteness.compute(plan, contract, declinedPackages());
    // repairNeeded is set even when the completeness claim is unchanged — it answers a different
    // question ("is a required file missing?") and must reach the UI on every recompute.
    synchronized (lock) {
      if (!running.get() && "idle".equals(status.state)) {
        status.repairNeeded = completeness.repairNeeded();
        // Tempdoc 824 §3.3b — reported on the same recompute as repairNeeded, and deliberately
        // beside it rather than inside it: an optional gap is a fact, not a reason to alarm.
        status.optionalGaps.clear();
        for (InstallCompleteness.OptionalGap gap : completeness.optionalGaps()) {
          status.optionalGaps.add(
              new AiInstallStatus.OptionalGap(gap.packageId(), gap.fileName()));
        }
      }
    }
    if (!completeness.installedFully()) {
      return false; // genuinely not (fully) installed — leave the honest "Not Installed".
    }
    synchronized (lock) {
      if (status.installedFully || running.get() || !"idle".equals(status.state)) {
        return false;
      }
      status.packages.clear();
      populateStatusPackages(plan, registry);
      status.pendingRegistryAdditions.clear();
      status.pendingRegistryAdditions.addAll(completeness.pendingRegistryAdditions());
      status.installedFully = true;
      touch();
    }
    return true;
  }

  /**
   * The install contract recorded by the run that installed this machine, or null when absent or
   * unreadable. Best-effort by design: a missing/corrupt contract must degrade to "the plan is the only
   * authority", never fail a status read.
   */
  private InstallContract readInstallContractBestEffort() {
    try {
      return InstallContractIO.read(homeDir);
    } catch (Exception e) {
      log.debug("AiInstall: install contract unreadable (best-effort): {}", e.toString());
      return null;
    }
  }

  /**
   * Liveness backstop (tempdoc 575 §17 Face C). Install is a <em>polled-state</em> liveness model: the
   * backend owns the state, the FE polls it. If the owner wedges in "running" (no {@code
   * updatedAtEpochMs} progress past {@link #STALE_RUNNING_MS}), reclaim it to a terminal failed state
   * on the next read — so the UI never polls a dead "running" forever (the gap this fixes: install/pack
   * previously had no backstop, unlike the worker's recoverStuckJobs reaper). The owner certifies its
   * own death; the FE's shorter staleness window surfaces a "stalled" badge earlier, while still running.
   */
  private void reapIfStale() {
    if (io.justsearch.app.services.ai.PolledStateLiveness.isStaleRunning(
        status.state, status.updatedAtEpochMs, System.currentTimeMillis(), STALE_RUNNING_MS)) {
      running.set(false);
      fail(
          "STALLED",
          "Install stalled — no progress for over "
              + (STALE_RUNNING_MS / 1000)
              + "s; reclaimed by the liveness backstop (575 §17 Face C).");
    }
  }

  public void startInstall(boolean acceptTerms) {
    if (!acceptTerms) {
      throw new AiInstallException(
          400, ApiErrorCode.TERMS_REQUIRED, "You must accept the model terms before downloading.");
    }
    checkPolicy();
    if (!running.compareAndSet(false, true)) {
      throw new AiInstallException(
          409, ApiErrorCode.INSTALL_ALREADY_RUNNING, "AI install is already running.");
    }
    cancelFlag.set(false);
    pause.resume();
    // Registered on the CALLING thread, before the virtual thread starts: registering inside it
    // leaves a window where upgrade prepare sees no blocker while the download is about to begin.
    // Same race-window closure as BulkReindexHandler. Tempdoc 840 Phase 3: the run now takes ONE
    // LEASE PER STAGE instead of one blanket 7200 s lease, and this is the first stage's — the only
    // one that cannot be sized from its bytes, because the plan does not exist yet on this thread.
    // The staged loop releases it when the first stage ends and registers the next stage's itself.
    StageLease lease =
        new StageLease(
            operationLeases.register(
                InstallStage.first().leaseOpClass(),
                OpCriticality.INTERRUPTIBLE_WITH_LOSS,
                PRE_PLAN_STAGE_LEASE_SEC,
                Map.of("source", "ai.model-install", "stage", InstallStage.first().id()),
                // Cancellation callback: upgrade prepare drains every active lease, so without this
                // a consented update would sit behind a multi-hour download instead of asking it to
                // stop. Safe to honour because a cancelled download resumes from its .partial rather
                // than restarting (tempdoc 798). The lease still blocks until this actually returns —
                // the request is an ask, not a release.
                this::cancel));
    try {
      Thread.ofVirtual()
          .name("ai-install-v2")
          .start(
              () -> {
                boolean ok = false;
                try {
                  runInstallInternal(lease);
                  ok = true;
                } finally {
                  running.set(false);
                  // The pause gate outlives the run (it is a service field). A run cancelled while
                  // paused leaves the flag set on purpose, so clearing it belongs to whichever run
                  // ends — otherwise the next status read reports a terminated run as paused.
                  pause.clear();
                  try {
                    // Every exit — completed, failed, cancelled — changes what is staged on disk,
                    // and a run that ends is exactly when a surface starts asking again. One
                    // re-derivation here covers all three rather than one per terminal path.
                    // Refresh BEFORE releasing the lease so a draining upgrade reads a truthful
                    // resumable-bytes state, and inside its own try so a refresh failure can
                    // never leak the lease.
                    refreshResumableBytesFromDisk();
                  } finally {
                    lease.release(ok ? OpLeaseOutcome.SUCCESS : OpLeaseOutcome.FAILURE);
                  }
                }
              });
    } catch (RuntimeException e) {
      // The thread never ran, so its finally block will not release the lease.
      running.set(false);
      lease.release(OpLeaseOutcome.FAILURE);
      throw e;
    }
  }

  /**
   * Whether an install run is in flight, as a cheap field read.
   *
   * <p>Exists so a caller that needs only this one bit does not pay for {@link #getStatus()}, which
   * also runs the boot disk-recompute, the staleness reaper and the runtime-observation projection
   * (tempdoc 824 §3.3c — that one can issue Worker RPCs on a cache miss). {@code
   * RuntimeActivationService.isLikelyInFlightInstall} calls this from inside a per-directory loop on
   * the {@code /api/ai/runtime/status} path; routing that through the full status read would put
   * blocking RPC work behind a filesystem probe, and re-enter the very service the projection reads.
   */
  @Override
  public boolean isInstallRunning() {
    synchronized (lock) {
      return "running".equals(status.state);
    }
  }

  public void cancel() {
    synchronized (lock) {
      status.cancelRequested = true;
      status.message = "Cancellation requested.";
      touch();
    }
    cancelFlag.set(true);
    // A run halted between items is waiting on the pause monitor, not on the transport, so the
    // cancel flag alone would not be read until the wait's next slice. Wakes it without resuming it:
    // a cancelled run must not look like a continued one.
    pause.wakeForCancellation();
    DownloadExecutor exec = downloadExecutor;
    if (exec != null) exec.cancel();
  }

  /**
   * Halts an in-flight install before its next file, keeping the run, its op-lease and its place in
   * the set (tempdoc 840 Phase 3, reachable over HTTP since Phase 4). Distinct from {@link
   * #cancel()}, which is terminal.
   *
   * <p>Refuses when nothing is running. The pause gate is a service field that outlives any one run,
   * so arming it with no run in flight would halt the NEXT install before its first byte — a
   * failure whose cause is invisible at the surface that caused it.
   */
  @Override
  public void pauseInstall() {
    requireRunInFlight("pause");
    pause.pause();
  }

  /** Continues a paused install at its next file. Refuses when nothing is running. */
  @Override
  public void resumeInstall() {
    requireRunInFlight("resume");
    pause.resume();
  }

  private void requireRunInFlight(String verb) {
    if (!running.get()) {
      throw new AiInstallException(
          409,
          ApiErrorCode.INSTALL_NOT_RUNNING,
          "No AI install is running, so there is nothing to " + verb + ".");
    }
  }

  /**
   * Records or withdraws the user's decision not to install one package (tempdoc 840 Phase 4).
   *
   * <p>Validation order is deliberate: an unknown id is a 404 before declinability is consulted, so
   * a typo never reports "this component cannot be turned off" about a component that does not
   * exist. Declinability itself is read from {@code Necessity.userDeclinable()} — the one authority —
   * rather than a list of ids maintained here, and only the DECLINE direction is gated: withdrawing
   * a decline can never be an invalid request, including for a package that was never declinable
   * (where it is simply a no-op).
   *
   * <p>The preference is advisory at the planner, which honours it only for a declinable package;
   * this check is what makes the refusal legible instead of silent.
   */
  @Override
  public void setPackageDeclined(String packageId, boolean declined) {
    String id = packageId == null ? "" : packageId.trim();
    if (id.isEmpty()) {
      throw new AiInstallException(
          400, ApiErrorCode.INVALID_REQUEST, "A component id is required.");
    }
    ModelPackage pkg;
    try {
      pkg = getManifest().findPackage(id);
    } catch (Exception e) {
      throw new AiInstallException(
          503, ApiErrorCode.MANIFEST_UNAVAILABLE, "Model registry unavailable: " + e.getMessage());
    }
    if (pkg == null) {
      throw new AiInstallException(
          404, ApiErrorCode.PACKAGE_NOT_FOUND, "No AI component with id '" + id + "'.");
    }
    if (declined && !pkg.necessity().userDeclinable()) {
      throw new AiInstallException(
          400,
          ApiErrorCode.PACKAGE_NOT_DECLINABLE,
          "'" + pkg.label() + "' is " + pkg.necessity().label().toLowerCase(java.util.Locale.ROOT)
              + " and cannot be turned off.");
    }
    if (settingsStore == null) {
      throw new AiInstallException(
          503,
          ApiErrorCode.SETTINGS_UNAVAILABLE,
          "Settings are unavailable, so the choice cannot be remembered.");
    }
    // UiSettingsStore.save() is a silent no-op in IN_MEMORY mode, so without this the endpoint would
    // answer 200 and forget the choice on the next read — the same class of lie as a fabricated 0.
    if (!settingsStore.mode().isWritable()) {
      throw new AiInstallException(
          409,
          ApiErrorCode.SETTINGS_READ_ONLY,
          "Settings are read-only in this session, so the choice cannot be remembered.");
    }
    try {
      UiSettings settings = settingsStore.load();
      List<String> next = new ArrayList<>(settings.getDeclinedAiPackages());
      boolean changed = declined ? (!next.contains(id) && next.add(id)) : next.remove(id);
      if (!changed) {
        return; // already in the requested state — writing settings again would be pure churn.
      }
      settings.setDeclinedAiPackages(next);
      settingsStore.save(settings);
      log.info("AI component '{}' {} by the user", id, declined ? "declined" : "re-enabled");
    } catch (Exception e) {
      // Unlike the read path (best-effort, defaults to "decline nothing"), a WRITE that silently
      // failed would tell the user their choice was saved when it was not.
      throw new AiInstallException(
          500,
          ApiErrorCode.AI_INSTALL_ERROR,
          "Failed to save the component choice: " + e.getMessage());
    }
  }

  /** Whether an install run is currently halted between files. */
  public boolean isInstallPaused() {
    return pause.isPaused();
  }

  public void repair(boolean acceptTerms) {
    startInstall(acceptTerms);
  }

  // ---------------------------------------------------------------------------
  // Core install flow
  // ---------------------------------------------------------------------------

  /**
   * @param firstStageLease the first stage's op-lease, already registered on the calling thread. The
   *     staged loop takes it over for whichever stage it reaches first; this method's caller
   *     releases it as a backstop on every path that never gets there (a release is once-only).
   */
  private void runInstallInternal(StageLease firstStageLease) {
    updateState("running", "preflight", "Starting AI install...");
    try {
      Files.createDirectories(homeDir);
      Files.createDirectories(modelsDir);
    } catch (IOException e) {
      fail("INSTALL_IO_ERROR", "Failed to create directories: " + e.getMessage());
      return;
    }

    ModelRegistry registry;
    try {
      registry = getManifest();
    } catch (Exception e) {
      fail("MANIFEST_UNAVAILABLE", "Failed to load model registry: " + e.getMessage());
      return;
    }

    if (policyBlocksDownloads()) {
      fail("DOWNLOADS_DISABLED", "Downloads disabled by administrator policy.");
      return;
    }

    // Build hardware profile
    updateState("running", "plan", "Detecting hardware and planning downloads...");
    HardwareProfile hardware = buildHardwareProfile();
    DownloadProfile profile = hardware.downloadProfile();
    log.info("Hardware profile: gpuDetected={}, cudaFunctional={}, vramBytes={}, profile={}",
        hardware.gpuDetected(), hardware.cudaFunctional(), hardware.vramBytes(), profile);

    // Compute download plan
    InstallPlan plan =
        InstallPlanner.plan(
            registry, hardware, installIntent(), declinedPackages(), modelsDir, homeDir);
    log.info(
        "Install plan: profile={}, downloads={}, skipped={}, alreadyInstalled={}, totalBytes={}",
        plan.profile(),
        plan.downloads().size(),
        plan.skipped().size(),
        plan.alreadyInstalled().size(),
        plan.totalBytes());
    recordResumableBytes(plan);

    // Populate status
    synchronized (lock) {
      status.downloadProfile = profile.name();
      status.totalBytes = plan.totalBytes();
      status.downloadedBytes = 0;
      status.packages.clear();
      populateStatusPackages(plan, registry);
      touch();
    }

    // Restore llama-server runtime. Tempdoc 772 §Design "Design 1": the bundled runtime is not the
    // only possible runtime source — a RUNTIME-tier package the plan supplies can deliver one too.
    // So fail RUNTIME_MISSING only when the bundled restore fails AND the plan supplies no runtime
    // package. Today no pack-delivered runtime package exists (see planSuppliesRuntime), so for
    // every real install this stays exactly today's "restore or fail" — the second clause is
    // dormant-but-correct until such a package is introduced.
    updateState("running", "restore_runtime", "Restoring AI runtime...");
    boolean bundledRuntimePresent = RuntimeRestoreUtil.ensureRuntimePresent(homeDir);
    if (!runtimePreconditionMet(bundledRuntimePresent, plan, registry)) {
      fail(
          "RUNTIME_MISSING",
          "Bundled AI runtime is missing and no runtime-supplying package is planned.");
      return;
    }

    // Reclaim BITS jobs an earlier crash left in the queue (tempdoc 840). Here rather than at app
    // boot: the set of jobs a sidecar still claims is only knowable once the plan is, and a
    // PowerShell spawn on every cold start buys no timeliness.
    sweepOrphanedBitsJobsBestEffort(plan);

    // ---- Stages 1-3, once per acquisition stage ------------------------------------------------
    downloadExecutor = new DownloadExecutor(cancelFlag);
    // Round 16: the environment reset ~40 % of new connections in bursts and the product answered
    // with two attempts ~7 s apart, so the "fallback" landed inside the same degraded window.
    // Spacing, not attempt count, is what makes a later attempt an independent trial.
    final TransportRetryPolicy retryPolicy = TransportRetryPolicy.defaultPolicy();
    // Tempdoc 824 §3.4: the only thing that distinguishes repair pass 4 from pass 1 is what the
    // earlier passes learned. Loaded once per run; every mutation persists immediately, so a run
    // killed mid-flight still leaves the passes it completed on record.
    InstallAttemptMemory attempts = InstallAttemptMemory.load(homeDir);
    PlacementStage placement = new PlacementStage(modelsDir);

    List<InstallStage.Slice> slices =
        InstallStage.partition(
            plan.downloads(),
            packageId -> {
              ModelPackage pkg = registry.findPackage(packageId);
              return pkg == null ? null : pkg.tier();
            });
    publishStagePlan(slices);

    // ONE reconciler procedure for the whole install window (tempdoc 840 Phase 3). Staged
    // acquisition churns the runtime several times where a monolithic run churned it once — a
    // Worker restart per stage, plus the engine's runtime overrides when the chat model lands — and
    // drift convergence must stay suppressed across all of it, not per burst. The engine returns to
    // spec exactly once, at the end. INSTALL_SMOKE_TEST nests inside this; overlapping kinds are
    // supported and only the LAST end re-arms convergence.
    RuntimeReconciler reconciler = this.reconciler;
    boolean acquisitionProcedure = false;
    if (reconciler != null) {
      reconciler.beginProcedure(
          RuntimeStatus.ProcedureKind.INSTALL_ACQUISITION, "staged-model-acquisition");
      acquisitionProcedure = true;
    }
    try {
      if (!runStages(slices, plan, registry, placement, attempts, retryPolicy, firstStageLease)) {
        return;
      }

      // Check for failures
      long failedCount = countPackagesByState("failed");
      long totalCount = status.packages.size();
      if (failedCount > 0 && failedCount == totalCount) {
        fail("ALL_DOWNLOADS_FAILED", "All packages failed to install.");
        return;
      }

      // ---- The run's bill of materials -----------------------------------------------------
      placement.writeContract(buildContract(plan, registry, hardware), homeDir);

      // Cancellation checkpoints BETWEEN the tail's steps, never inside one: the tail used to run
      // unchecked from here to applyCompletionState, so a user who cancelled during it (contract,
      // config, worker restart, and a 60 s smoke test) was told the install COMPLETED — and the
      // op-lease drain callback that stops an install for a pending app update was a no-op for the
      // same window. A half-written contract is worse than a completed one, so no step is made
      // interruptible mid-write.
      if (cancelFlag.get()) {
        cancelled();
        return;
      }

      // ---- Stage 4: validate ---------------------------------------------------------------
      ValidationStage.Verdict verdict =
          new ValidationStage(
                  cancelFlag::get,
                  this::cancelled,
                  () -> updateState("running", "smoke_test", "Running smoke test..."),
                  this::smokeTestBestEffort)
              .run(profile.includesGguf() && isPolicyOnlineAiAllowed());
      if (!verdict.allowsCompletion()) {
        return;
      }

      applyCompletionState();
    } finally {
      if (acquisitionProcedure) {
        reconciler.endProcedure(RuntimeStatus.ProcedureKind.INSTALL_ACQUISITION);
      }
    }
  }

  /**
   * Binds {@link StagedAcquisition}'s four seams to their production implementations and projects
   * its stage events onto {@link AiInstallStatus}. The sequencing itself lives in that class; this
   * is what one stage's acquire / configure / lease is MADE OF, the same split {@link
   * AcquisitionStage} has against {@link AcquisitionScheduler}.
   *
   * <p><b>Why the whole configuration list runs per stage instead of a per-stage selection.</b> The
   * ORDER of {@code ConfigurationStage.forInstall}'s steps is load-bearing and lives in exactly one
   * place; a per-stage subset would either re-declare that order or filter it, and a filtered view
   * is a second place the order lives — the fork this package keeps closing. It is also unnecessary:
   * every step already guards on ITS OWN inputs being on disk, which is a strictly better stage
   * selector than a tier mapping could be, because it is disk truth rather than plan truth.
   * {@code applySettings} falls out unless the chat GGUF is a regular file; {@code applyOrtNativePath}
   * unless the cuda12 dir holds every CUDA runtime DLL; {@code applyCudaServerExe} unless the cuda12
   * llama-server binary exists; {@code applyOnnxSettings} writes a path only for a package that is
   * present and neither skipped nor failed. So the core stage applies the ORT native path (its
   * RUNTIME tier just delivered the DLLs) and silently does not apply the chat settings, and the
   * chat stage applies them. Re-running an already-applied step is a no-op by construction:
   * {@code setSysPropIfBlank} is first-writer-wins, and the settings writes are the same absolute
   * paths.
   *
   * <p>Only the Worker restart is stage-gated, because it is the one step whose input is not a file
   * but the RUN: a restart is a user-visible search blip, so a stage that placed nothing must not
   * pay for one.
   *
   * @return true to carry on with the rest of the run; false when a stage already put the run into a
   *     terminal state
   */
  private boolean runStages(
      List<InstallStage.Slice> slices,
      InstallPlan plan,
      ModelRegistry registry,
      PlacementStage placement,
      InstallAttemptMemory attempts,
      TransportRetryPolicy retryPolicy,
      StageLease firstStageLease) {

    // The set-wide byte counter the surface reads, kept across stages: each stage's scheduler counts
    // its OWN slice from zero, so without this base the progress bar would restart at every
    // boundary. One number, banked once per stage from that stage's own summary.
    java.util.concurrent.atomic.AtomicLong priorStageBytes = new java.util.concurrent.atomic.AtomicLong();
    AcquisitionScheduler.Listener projection = acquisitionProjection(plan, priorStageBytes::get);

    // Run-scoped, so they outlive one stage's configuration pass. These three write process-wide
    // latches (a system property, the selected server binary, the engine's runtime overrides) whose
    // work is done the first time it succeeds; applyOnnxSettings is deliberately NOT among them
    // because its work is incremental — each stage that lands an encoder gives it one more path to
    // write.
    BooleanSupplier cudaServerExeOnce = StagedAcquisition.applyOncePerRun(this::applyCudaServerExe);
    BooleanSupplier llmSettingsOnce =
        StagedAcquisition.applyOncePerRun(() -> applySettings(registry, plan));
    BooleanSupplier ortNativePathOnce = StagedAcquisition.applyOncePerRun(this::applyOrtNativePath);

    boolean completed =
        new StagedAcquisition(
                slice ->
                    acquireStage(
                        new AcquisitionStage(
                                modelsDir,
                                downloadExecutor,
                                retryPolicy,
                                attempts,
                                cancelFlag::get,
                                nanoClock,
                                placement,
                                projection,
                                pause)
                            .scheduler(slice.downloads())),
                (slice, acquired) ->
                    ConfigurationStage.forInstall(
                            cudaServerExeOnce,
                            llmSettingsOnce,
                            () -> applyOnnxSettings(registry, plan),
                            ortNativePathOnce,
                            StagedAcquisition.restartGate(acquired, this::tryRestartWorkerBestEffort),
                            cancelFlag::get,
                            (phase, message) -> updateState("running", phase, message))
                        .apply(),
                // The pass a run owes when no stage acquired anything (tempdoc 840 R1): same steps,
                // same order, same once-per-run latches — and the Worker restart UNGATED, because a
                // pre-staged models dir is new to the Worker even though this run fetched none of it.
                () ->
                    ConfigurationStage.forInstall(
                            cudaServerExeOnce,
                            llmSettingsOnce,
                            () -> applyOnnxSettings(registry, plan),
                            ortNativePathOnce,
                            this::tryRestartWorkerBestEffort,
                            cancelFlag::get,
                            (phase, message) -> updateState("running", phase, message))
                        .apply(),
                this::registerStageLease,
                new StagedAcquisition.Listener() {
                  @Override
                  public void onStageStarted(InstallStage stage) {
                    setCurrentStage(stage);
                  }

                  @Override
                  public void onStageBlocked(InstallStage stage, String reason) {
                    recordStageBlocked(stage, reason);
                  }

                  @Override
                  public void onStageAcquired(
                      InstallStage stage, AcquisitionScheduler.Summary summary) {
                    // Banked whatever happened: the bytes this stage PLACED are on disk, and the
                    // next stage's progress continues from them even if this one ended badly.
                    long placed = summary == null ? 0L : summary.acquiredBytes();
                    long total = priorStageBytes.addAndGet(placed);
                    // Settle both counters on the exact placed figure. In-flight progress reports
                    // stop one placement short of it — the last file's bytes are credited when it
                    // is promoted, and no progress event follows — so without this a finished stage
                    // reads just under its own total.
                    synchronized (lock) {
                      status.downloadedBytes = total;
                      var st = findStageStatus(stage.id());
                      if (st != null) {
                        st.downloadedBytes = placed;
                      }
                      touch();
                    }
                  }

                  @Override
                  public void onStageEnded(InstallStage stage, StagedAcquisition.StageState state) {
                    markStage(stage, state.id());
                  }
                },
                cancelFlag::get,
                // Refuse a stage the disk cannot hold, BEFORE its first byte (tempdoc 840 U2).
                // Per stage, not per run: a disk that fits the retrieval core but not the chat model
                // still ends up with working search. Fail-open — an unmeasurable filesystem never
                // blocks anything (see FreeSpaceCheck).
                this::diskBlockedReason)
            .run(slices, success -> firstStageLease.release(leaseOutcome(success)));

    if (!completed) {
      cancelled();
      return false;
    }
    synchronized (lock) {
      status.currentStage = "";
      touch();
    }
    return true;
  }

  private static OpLeaseOutcome leaseOutcome(boolean success) {
    return success ? OpLeaseOutcome.SUCCESS : OpLeaseOutcome.FAILURE;
  }

  /**
   * Runs one stage's acquisition with its estimator published for the length of the transfer.
   *
   * <p>The publication is the point: {@link AcquisitionRate#estimate} decides "this transfer has
   * stopped reporting" by comparing its newest sample against the clock, so it has to be asked WHEN
   * THE ANSWER IS WANTED. Holding the live scheduler lets {@link #getStatus} do that; a projection
   * that only saw the value handed to it alongside a sample could never observe a stall, because at
   * that instant no time has passed since the sample.
   *
   * <p>Package-private so a test can drive exactly this — sample, let the clock run past the stall
   * window, read the status — without staging a real multi-GB transfer.
   */
  // PMD sees only this method: the publication looks overwritten by the `finally`. It is read from
  // another thread — `getStatus` dereferences `liveRateSource` for the length of the transfer.
  @SuppressWarnings("PMD.UnusedAssignment")
  AcquisitionScheduler.Summary acquireStage(AcquisitionScheduler scheduler) {
    liveRateSource = scheduler::estimate;
    try {
      return scheduler.run();
    } finally {
      liveRateSource = null;
    }
  }

  /**
   * Why this stage's files will not fit, or null when they will (tempdoc 840 U2, corrected by R6).
   *
   * <p>Two things this asks that the first version of the check did not. It measures the
   * filesystems the bytes ACTUALLY land on, derived from the slice's own target paths, rather than
   * {@code modelsDir} alone: a package with an {@code installRoot} (today {@code cuda-runtime},
   * essentially the whole CORE payload) is planned with an ABSOLUTE path under the AI home, so with
   * {@code JUSTSEARCH_MODELS_DIR} pointed at another volume the old probe measured a disk receiving
   * almost none of them — and the direction that hurts is the silent one, where a roomy models
   * drive lets an install onto a nearly-full home drive proceed into the late IO error this check
   * exists to pre-empt. And it demands only the bytes still OWED: a file with 5.5 GB already in its
   * {@code .partial} needs room for the rest, not for a copy of itself, which is what makes the
   * check compatible with resuming rather than a second reason a resumable download is refused.
   *
   * <p>Package-private so the decision is testable without a second physical volume.
   */
  String diskBlockedReason(InstallStage.Slice slice) {
    Map<Path, Long> byDestination = new LinkedHashMap<>();
    for (InstallPlan.PlannedDownload dl : slice.downloads()) {
      // An installRoot package's targetPath is already absolute, and Path.resolve returns it
      // unchanged in that case — so this one expression covers both planner shapes.
      byDestination.merge(
          modelsDir.resolve(dl.targetPath()), Math.max(0L, dl.remainingBytes()), Long::sum);
    }
    return FreeSpaceCheck.blockedReason(
        slice.stage().label(), FreeSpaceCheck.groupByFilesystem(byDestination), freeSpaceProbe);
  }

  /**
   * Registers one stage's op-lease, sized from the bytes that stage actually has to move.
   *
   * @throws io.justsearch.app.api.OperationAdmissionClosedException when an upgrade preparation owns
   *     the admission barrier — the caller ends the run rather than downloading into a pending
   *     update
   */
  private StagedAcquisition.Lease registerStageLease(InstallStage.Slice slice) {
    // Sized from the bytes still OWED, not the file sizes: how long the stage holds the lease is a
    // function of what it has to transfer, and a resumed stage that only needs its last 0.8 GB must
    // not block an upgrade for as long as a stage fetching all 6.3 GB.
    long estimateSec =
        Math.max(
            MIN_STAGE_LEASE_SEC,
            Math.min(MAX_STAGE_LEASE_SEC, slice.remainingBytes() / LEASE_ESTIMATE_BYTES_PER_SEC));
    StageLease lease =
        new StageLease(
            operationLeases.register(
                slice.stage().leaseOpClass(),
                OpCriticality.INTERRUPTIBLE_WITH_LOSS,
                estimateSec,
                Map.of(
                    "source", "ai.model-install",
                    "stage", slice.stage().id(),
                    "bytes", slice.bytes()),
                this::cancel));
    return success -> lease.release(leaseOutcome(success));
  }

  /**
   * An op-lease handle that releases at most once.
   *
   * <p>The first stage's lease has two potential releasers — the staged loop, when that stage ends,
   * and {@link #startInstall}'s finally block, which is the backstop for every path that never
   * reaches the loop. Both must be able to fire without the lease being released twice, which would
   * publish a second release event for an op that only ended once.
   */
  private static final class StageLease {
    private final OperationLeaseHandle handle;
    private final AtomicBoolean released = new AtomicBoolean(false);

    StageLease(OperationLeaseHandle handle) {
      this.handle = handle;
    }

    void release(OpLeaseOutcome outcome) {
      if (released.compareAndSet(false, true)) {
        handle.release(outcome);
      }
    }
  }

  /**
   * Projects an acquisition run onto {@link AiInstallStatus}.
   *
   * <p>{@link AcquisitionScheduler} deliberately does not know this type exists — it is the wire DTO
   * the FE polls, and the FE keys off {@code phase}, so every surface-visible effect of the download
   * set is gathered here rather than scattered through the loop that produces it.
   *
   * @param priorStageBytes bytes banked by the stages that already ran. Each stage's scheduler
   *     counts its own slice from zero (it owns only that slice), so the set-wide counter is this
   *     base plus the running stage's own number — one authority, read twice, rather than a second
   *     accumulator that could disagree with it.
   */
  private AcquisitionScheduler.Listener acquisitionProjection(
      InstallPlan plan, java.util.function.LongSupplier priorStageBytes) {
    Map<String, InstallPlan.PlannedDownload> byTargetPath = new HashMap<>();
    for (InstallPlan.PlannedDownload dl : plan.downloads()) {
      byTargetPath.putIfAbsent(dl.targetPath(), dl);
    }
    return new AcquisitionScheduler.Listener() {
      @Override
      public void onItemStarted(AcquisitionScheduler.Item item) {
        updateState("running", "download", "Downloading " + item.id() + "...");
        updatePackageState(item.packageId(), "downloading");
      }

      @Override
      public void onItemVerifying(AcquisitionScheduler.Item item) {
        updatePackageState(item.packageId(), "verifying");
      }

      @Override
      public void onAttempt(AcquisitionScheduler.Item item, int attempt, int maxAttempts) {
        updateState(
            "running",
            "download",
            "Downloading "
                + item.id()
                + "..."
                + (attempt > 1 ? " (attempt " + attempt + " of " + maxAttempts + ")" : ""));
      }

      @Override
      public void onProgress(
          AcquisitionScheduler.Item item, long overallBytes, long packageBytes) {
        synchronized (lock) {
          status.downloadedBytes = priorStageBytes.getAsLong() + overallBytes;
          AiInstallStatus.StageStatus stage = findStageStatus(status.currentStage);
          if (stage != null) {
            stage.downloadedBytes = overallBytes;
          }
          // Report cumulative bytes for the package (prior completed files + current in-flight).
          // Pass 0 for total so the existing package-level total (set in populateStatusPackages)
          // wins.
          updatePackageProgress(item.packageId(), packageBytes, 0);
          // The rate is deliberately NOT stamped here (tempdoc 840 R4). `estimate` is measured on
          // the line after the sample that produced it, where the stall window it is supposed to
          // enforce cannot have elapsed; a value written here would stand unchanged on the wire for
          // as long as a stalled transfer stayed stalled. refreshRateEstimateLocked derives it from
          // the same estimator when the status is READ instead.
          touch();
        }
      }

      @Override
      public void onItemResumed(AcquisitionScheduler.Item item) {
        // Project the resume verdict onto the package so the UI can say the earlier progress was
        // kept. Sticky across a multi-file package: one resumed file makes the package resumed.
        markPackageResumed(item.packageId());
      }

      @Override
      public void onItemTerminal(AcquisitionScheduler.Item item, int attemptCount) {
        // Tempdoc 824 §3.4: three passes of transport failure is no longer a story about luck.
        // Repair stays *needed* (a file IS missing) but stops being *offered* as the remedy — an
        // affordance that cannot succeed must not be presented as one.
        InstallPlan.PlannedDownload dl = byTargetPath.get(item.id());
        markPackageTerminal(
            item.packageId(),
            "TRANSPORT_UNAVAILABLE",
            attemptCount,
            dl == null ? null : dl.url(),
            modelsDir.resolve(item.id()).toString());
      }

      @Override
      public void onItemFailed(AcquisitionScheduler.Item item, String message) {
        failPackage(item.packageId(), message);
      }

      @Override
      public void onItemInstalled(AcquisitionScheduler.Item item, long packageBytes) {
        // Settle the package counter on the exact placed figure BEFORE marking it installed
        // (tempdoc 840 R7). In-flight progress reports stop one credit short — the item's bytes are
        // banked at placement and no progress event follows it — so an installed 1.99 GB package
        // used to sit at 99.97 % of itself for the rest of the run. The stage counter has had this
        // settlement since Phase 3 (onStageAcquired); the per-package one never got it.
        synchronized (lock) {
          updatePackageProgress(item.packageId(), packageBytes, 0);
          touch();
        }
        updatePackageState(item.packageId(), "installed");
      }
    };
  }

  /**
   * Removes {@code JustSearch AI} BITS jobs that no planned download's resume sidecar claims — the
   * jobs a crash or hard kill mid-transfer stranded in the queue for its 90-day default lifetime.
   *
   * <p>The claimed set is built BEFORE anything is removed, and from exactly the source the resume
   * decision itself reads ({@link DownloadResume#read}). That is what makes the sweep safe rather than
   * merely lucky: a sidecar this cannot read is a sidecar {@code ResumableFetch} cannot resume from
   * either, so its job is genuinely unusable. Best-effort throughout — a failure here must never cost
   * the install.
   */
  private void sweepOrphanedBitsJobsBestEffort(InstallPlan plan) {
    try {
      Set<String> claimed = new HashSet<>();
      for (InstallPlan.PlannedDownload dl : plan.downloads()) {
        Path partial = InstallPlanner.partialPathFor(modelsDir.resolve(dl.targetPath()));
        DownloadResume.State recorded = DownloadResume.read(partial);
        if (recorded == null) continue;
        String jobId = recorded.bitsJobId();
        if (jobId != null && !jobId.isBlank()) claimed.add(jobId);
      }
      DownloadExecutor.sweepOrphanedBitsJobs(claimed);
    } catch (Exception e) {
      log.debug("Orphaned-BITS-job sweep skipped (best-effort): {}", e.toString());
    }
  }

  /**
   * Terminal honesty decision, extracted so a regression test can pin the INS-005 property (a
   * multi-package run where some assets fail must still report {@code state == "completed"} with an
   * accurate count, never {@code failed}) without staging real downloads — mirroring why {@link
   * #applyInstalledFromPlan} was made package-private. Reads the already-populated {@code
   * status.packages}: a per-package {@code failed} keeps {@code installedFully} false but the run
   * completed; {@code skipped} (hardware/policy) is distinguished from {@code failed}.
   */
  void applyCompletionState() {
    applyCompletionState(recomputeCompletenessFromDiskBestEffort());
  }

  /**
   * Re-derives completeness from DISK, or null when the probe cannot answer (no registry on the
   * classpath, IO failure). Cheap by construction — the planner's already-installed test is
   * existence + size, never a hash ({@code InstallPlanner.isAlreadyInstalled}).
   */
  private InstallCompleteness recomputeCompletenessFromDiskBestEffort() {
    try {
      InstallPlan plan =
          InstallPlanner.plan(
              getManifest(),
              buildHardwareProfile(),
              installIntent(),
              declinedPackages(),
              modelsDir,
              homeDir);
      return InstallCompleteness.compute(plan, readInstallContractBestEffort(), declinedPackages());
    } catch (Exception e) {
      log.debug("AiInstall completion disk recompute skipped (best-effort): {}", e.toString());
      return null;
    }
  }

  /**
   * Completion-state decision with the disk verdict injected — the seam the §3.3d/§3.3b regression
   * tests drive (staging the registry's whole file set to make a real probe answer would be
   * brittle, the same reason {@link #applyInstalledFromPlan} takes an injected contract).
   *
   * @param diskTruth what disk says about required/optional files, or null when indeterminate — in
   *     which case the package bookkeeping remains the only authority (today's behaviour exactly)
   */
  void applyCompletionState(InstallCompleteness diskTruth) {
    long failedCount = countPackagesByState("failed");
    long skippedCount = countPackagesByState("skipped");
    // The skips that actually LIMIT the install — the only ones `installedFully` may count and the
    // only ones the "skipped on this hardware" banner may name (tempdoc 941 round 19, F3). A skip is
    // a limitation iff its typed cause says so (`SkipCause.limitsInstall`); an unclassified skip
    // fails closed onto the hardware verdict, so every pre-fix caller's answer is unchanged.
    List<AiInstallStatus.PackageStatus> limitingSkips =
        status.packages.stream()
            .filter(ps -> "skipped".equals(ps.state))
            .filter(
                ps -> {
                  SkipCause cause = SkipCause.fromId(ps.skipCause);
                  return cause == null || cause.limitsInstall();
                })
            .toList();
    long limitingSkippedCount = limitingSkips.size();
    long totalCount = status.packages.size();
    // installedFully is true only when every package actually reached "installed" (or was skipped
    // for a reason that is not a limitation) — computed from the positive states rather than "no
    // failed && no skipped", so a package left in a non-terminal state (pending/downloading/
    // verifying, e.g. a loop that aborted early) can never read as a clean install. The download
    // loop terminalizes every package today, so this is defense in depth.
    boolean fullyInstalled =
        countPackagesByState("installed") + (skippedCount - limitingSkippedCount) == totalCount;

    // The message must come from the SAME authority as installedFully, or the two contradict each
    // other on the round-16 wedge: a package whose only casualty was an optional file is "failed" in
    // the run's bookkeeping while disk says the install is complete. Counting it as failed in the
    // message would deny the flag printed beside it. When disk cannot answer, the bookkeeping is the
    // only authority for both, so they still agree.
    long messageFailedCount = failedCount;
    boolean optionalFilesMissing = false;
    if (diskTruth != null) {
      // A HashSet, not the returned immutable List: this runs on the terminal-state path, and
      // List.copyOf(...).contains(null) THROWS rather than answering false.
      Set<String> requiredGapPackages = new HashSet<>(diskTruth.packagesWithMissingRequiredFiles());
      messageFailedCount =
          status.packages.stream()
              .filter(ps -> "failed".equals(ps.state))
              .filter(ps -> requiredGapPackages.contains(ps.packageId))
              .count();
      optionalFilesMissing = !diskTruth.optionalGaps().isEmpty();
    }
    String optionalNote = optionalFilesMissing ? "; optional files missing" : "";

    if (messageFailedCount > 0) {
      long installed = totalCount - messageFailedCount - skippedCount;
      updateState(
          "completed",
          "done",
          "AI installed ("
              + installed
              + "/"
              + totalCount
              + " packages; "
              + messageFailedCount
              + " failed"
              + optionalNote
              + ").");
    } else if (limitingSkippedCount > 0) {
      // Partial-success path: state is still "completed" (Install AI ran to
      // termination), but installedFully is false so the Brain UI can show
      // a "Installed with limitations" banner. Tempdoc 374 finding #8.
      //
      // Only the LIMITING skips are named. This sentence blames the user's machine, and it used to
      // be printed for every skip regardless of cause — so a package the packager excluded, and a
      // component the user themselves declined, were both reported as things this hardware could
      // not run (tempdoc 941 round 19, F3). A skip the user chose or the packager made is recorded
      // per-package (`skipReason` + `skipCause`); it is not a limitation of the install.
      String skippedLabels = limitingSkips.stream()
          .map(ps -> ps.label != null && !ps.label.isBlank() ? ps.label : ps.packageId)
          .collect(java.util.stream.Collectors.joining(", "));
      updateState(
          "completed",
          "done",
          "Installed with limitations: " + skippedLabels + " skipped on this hardware.");
    } else if (optionalFilesMissing) {
      // Every package disk considers complete, and the gap is named rather than dressed up as a
      // failed package (the phrasing that sat next to installedFully: true in round 16).
      updateState(
          "completed",
          "done",
          "AI installed (" + totalCount + "/" + totalCount + " packages" + optionalNote + ").");
    } else {
      updateState("completed", "done", "AI installed.");
    }
    synchronized (lock) {
      // Tempdoc 804 §B8 — a run just planned against the CURRENT registry, so nothing is a pending
      // registry addition any more (the signal only describes a contract older than the registry).
      status.pendingRegistryAdditions.clear();
      status.optionalGaps.clear();
      if (diskTruth == null) {
        // Tempdoc 805 G.3 — a run against the current registry leaves a required file missing only
        // where a package FAILED; a hardware/policy skip is not a repairable gap (the file was
        // never required on this machine).
        status.installedFully = fullyInstalled;
        status.repairNeeded = failedCount > 0;
      } else {
        // Tempdoc 824 §3.3d — the completion claim is CHECKED against disk before it becomes the
        // terminal claim, closing the "idle"-gated blind spot that left the completing session's
        // bookkeeping unverified until the next process start. Two directions matter equally:
        // a clean run whose files are not actually on disk must not read green (the
        // `unreachable-seed-green` direction), and a run whose only casualties were OPTIONAL files
        // must not read red (round 16 — one 872-byte metadata file, SPLADE serving on CUDA).
        boolean requiredMissing = diskTruth.repairNeeded();
        status.repairNeeded = requiredMissing;
        // A HARDWARE skip still means "installed with limitations", never "installed cleanly"
        // (tempdoc 374 finding #8) — disk cannot speak to a package it never planned. A skip the
        // packager, the mode or the user decided is NOT a limitation, and counting it here made
        // `installedFully:true` unreachable on every machine while a devOnly package sat in the
        // registry (tempdoc 941 round 19, F3).
        status.installedFully = !requiredMissing && limitingSkippedCount == 0;
        for (InstallCompleteness.OptionalGap gap : diskTruth.optionalGaps()) {
          status.optionalGaps.add(
              new AiInstallStatus.OptionalGap(gap.packageId(), gap.fileName()));
        }
      }
      touch();
    }
  }

  // ---------------------------------------------------------------------------
  // Install intent
  // ---------------------------------------------------------------------------

  /**
   * Resolves the install/runtime intent (tempdoc 657) from {@code -Djustsearch.mode} /
   * {@code JUSTSEARCH_MODE}, defaulting to Full Desktop when unset. Read the same way by the plan and
   * the contract, so the recorded intent matches what was actually planned.
   */
  public InstallIntent installIntent() {
    return InstallIntent.fromConfig(io.justsearch.configuration.EnvRegistry.MODE.get().orElse(null));
  }

  // ---------------------------------------------------------------------------
  // Per-component intent (tempdoc 840 Phase 2)
  // ---------------------------------------------------------------------------

  /**
   * The packages the user currently declines, read from {@link UiSettings}. This service is the ONLY
   * place that reads the preference: {@code InstallPlanner.plan} and {@code
   * InstallCompleteness.compute} take it as a parameter and stay pure functions, so both can be
   * tested and previewed without a settings store existing at all.
   *
   * <p>Best-effort: an unavailable or throwing store yields the empty set, which means "decline
   * nothing" — the conservative direction, because it can only over-install, never silently drop a
   * component the user still wants.
   */
  public Set<String> declinedPackages() {
    if (settingsStore == null) return Set.of();
    try {
      UiSettings s = settingsStore.load();
      if (s == null) return Set.of();
      return Set.copyOf(s.getDeclinedAiPackages());
    } catch (Exception e) {
      log.debug("AiInstall declined-package preference unavailable (best-effort): {}", e.toString());
      return Set.of();
    }
  }

  // ---------------------------------------------------------------------------
  // Runtime precondition (tempdoc 772 §Design "Design 1")
  // ---------------------------------------------------------------------------

  /**
   * The runtime precondition: an install may proceed iff the bundled runtime restored successfully
   * OR the computed plan already supplies a runtime via a pack-delivered runtime package. Only when
   * both fail is {@code RUNTIME_MISSING} raised. Package-private + static so the three-way behavior
   * can be unit-tested directly (bundled present; bundled absent + no runtime pack; bundled absent +
   * runtime pack) without driving the full install flow.
   */
  static boolean runtimePreconditionMet(
      boolean bundledRuntimePresent, InstallPlan plan, ModelRegistry registry) {
    return bundledRuntimePresent || planSuppliesRuntime(plan, registry);
  }

  /**
   * Whether the plan already supplies a working runtime via a pack-delivered RUNTIME-tier package —
   * i.e. a RUNTIME-tier package that is <em>hardware-independent</em> ({@code requiresCuda=false})
   * appears in {@code downloads()} or {@code alreadyInstalled()}. The hardware-independence filter is
   * deliberate: {@code cuda-runtime} is a RUNTIME-tier package too, but it is a CUDA DLL supplement
   * ({@code requiresCuda=true}), not a from-scratch runtime supplier — counting it would change
   * today's behavior on GPU hardware (proceed instead of RUNTIME_MISSING when the bundled restore
   * fails). No hardware-independent RUNTIME package exists in any production registry today, so this
   * returns false for every real install and the bundled-runtime restore stays the sole runtime
   * source — behavior is unchanged. It activates only for a future hardware-independent runtime
   * package (tempdoc 772 Q3 / §Design "Design 1").
   */
  static boolean planSuppliesRuntime(InstallPlan plan, ModelRegistry registry) {
    Set<String> runtimePackIds = new HashSet<>();
    for (ModelPackage pkg : registry.packages()) {
      if (pkg.tier() == CapabilityTier.RUNTIME && !pkg.requiresCuda()) {
        runtimePackIds.add(pkg.id());
      }
    }
    if (runtimePackIds.isEmpty()) {
      return false;
    }
    for (InstallPlan.PlannedDownload dl : plan.downloads()) {
      if (runtimePackIds.contains(dl.packageId())) {
        return true;
      }
    }
    for (String id : plan.alreadyInstalled()) {
      if (runtimePackIds.contains(id)) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Hardware profile
  // ---------------------------------------------------------------------------

  public HardwareProfile buildHardwareProfile() {
    // Tempdoc 587: read every GPU fact from the ONE composition seam (GpuCapabilityResolver),
    // which folds the CUDA driver-API probe into the NVML/nvidia-smi VRAM+device merge. This
    // replaces the prior split — a direct GpuDriverApiProbe call for cudaFunctional plus a
    // separate GpuCapabilitiesService call for VRAM — so both axes come from one resolver and the
    // raw probes are no longer reached directly (the bypass GpuProbeAccessTest now forecloses).
    boolean cudaFunctional = false;
    long vramBytes = -1;
    try {
      io.justsearch.gpu.GpuCapabilities.Effective effective =
          new io.justsearch.app.services.gpu.GpuCapabilityResolver().snapshot().effective();
      if (effective.cuda() != null && Boolean.TRUE.equals(effective.cuda().functional())) {
        cudaFunctional = true;
      }
      if (effective.totalVramBytes() != null && effective.totalVramBytes() > 0) {
        vramBytes = effective.totalVramBytes();
      }
    } catch (Throwable t) {
      log.debug("GPU capability resolve failed (best-effort): {}", t.getMessage());
    }
    boolean gpuDetected = vramBytes > 0 || cudaFunctional;
    return new HardwareProfile(gpuDetected, cudaFunctional, vramBytes);
  }

  // ---------------------------------------------------------------------------
  // ---------------------------------------------------------------------------
  // Contract generation
  // ---------------------------------------------------------------------------

  /**
   * Package-private (not private) for the same reason as {@link #applyInstalledFromPlan}: the
   * entry-kind decision this makes is what {@link InstallCompleteness} later reads as install
   * history, so it needs a regression test that does not stage real downloads (tempdoc 805 G.3).
   */
  InstallContract buildContract(
      InstallPlan plan, ModelRegistry registry, HardwareProfile hardware) {
    Map<String, InstallContract.InstalledModel> models = new LinkedHashMap<>();

    for (ModelPackage pkg : registry.packages()) {
      // Check if skipped
      InstallPlan.SkippedPackage skip =
          plan.skipped().stream()
              .filter(s -> s.packageId().equals(pkg.id()))
              .findFirst()
              .orElse(null);
      if (skip != null) {
        // Tempdoc 840 Phase 2: the planner's typed cause is carried through verbatim. Re-deriving it
        // here (from the reason prose, or by re-evaluating hardware/intent/declined) would be a
        // second authority for a decision the planner already made, and would drift the first time
        // a skip message is reworded.
        String reason = skip.reason() != null ? skip.reason() : "Skipped";
        models.put(
            pkg.id(), InstallContract.InstalledModel.skipped(pkg.id(), skip.cause(), reason));
        continue;
      }

      ModelVariant variant = pkg.selectVariant(plan.profile());
      if (variant == null && pkg.supportingFiles().isEmpty()) {
        // Nothing to record: no variant AND no supporting files.
        models.put(pkg.id(), InstallContract.InstalledModel.skipped(pkg.id(), "No variant"));
        continue;
      }

      // Collect installed files. Tempdoc 805 G.3 (derisk U3, refinement 3): a VARIANTLESS package
      // whose supporting files this run installed is recorded as installed-WITH-FILES, not
      // skipped("No variant"). `ModelPackage.selectVariant` returns null for `variants: []`, which
      // is exactly cuda-runtime — the package whose new supporting file round 11 lost — so the
      // contract carried no per-file authority for the one package class that needed it.
      List<String> installedFiles = new ArrayList<>();
      if (variant != null) {
        installedFiles.add(variant.filename());
      }
      for (var sf : pkg.supportingFiles()) {
        installedFiles.add(sf.filename());
      }

      models.put(
          pkg.id(),
          new InstallContract.InstalledModel(
              pkg.id(),
              variant == null ? null : variant.filename(),
              variant == null ? null : variant.precision(),
              variant == null ? null : variant.targetEP(),
              pkg.targetDir(),
              variant == null ? null : variant.sha256(),
              installedFiles,
              false,
              null));
    }

    // Tempdoc 374 alpha.20 Bug M: record the absolute modelsDir so contract
    // path resolution survives cold restart. Pre-alpha.20 the contract only
    // carried relative `targetDir` per package; the `<root>` against which to
    // resolve was looked up at runtime via JUSTSEARCH_MODELS_DIR env var or
    // resolved-config snapshot. On cold restart (GUI launch) the env var
    // doesn't inherit, the snapshot is empty (UiSettings has no modelsDir
    // field), and the runtime fell back to aiHome/models — wrong directory
    // for users who pre-stage models. Recording modelsDir here makes the
    // contract self-describing.
    return new InstallContract(
        2, System.currentTimeMillis(), hardware, plan.profile(), models,
        modelsDir != null ? modelsDir.toAbsolutePath().normalize() : null,
        installIntent());
  }

  // ---------------------------------------------------------------------------
  // Settings application
  // ---------------------------------------------------------------------------

  /** @return true when the chat model path was written; false on any guard that skipped the step */
  private boolean applySettings(ModelRegistry registry, InstallPlan plan) {
    if (settingsStore == null) return false;
    if (!plan.profile().includesGguf()) return false; // No chat model → nothing to configure

    ModelPackage chat = registry.findPackage("chat");
    if (chat == null) return false;
    ModelVariant chatVariant = chat.selectVariant(plan.profile());
    if (chatVariant == null) return false;

    Path chatModelPath = modelsDir.resolve(chat.targetDir()).resolve(chatVariant.filename());
    if (!Files.isRegularFile(chatModelPath)) return false;

    UiSettings s = settingsStore.load();
    s.setLlmModelPath(chatModelPath.toAbsolutePath().toString());
    settingsStore.save(s);

    // No sysprop write here (883 §C.5c residue, #605 review S1). The save above plus the rebuild
    // below already deliver this path at ordinal 300 (settings.json) through
    // ConfigStoreRebuilder.contributeUiSettings, and every reader takes it from ResolvedConfig —
    // InferenceConfig reads rc.ai().llmModelPath(), and LLM_MODEL_PATH was not in
    // WorkerSpawner.WORKER_FORWARDED_PROPS, so no process boundary depended on the sysprop even
    // before lane F stage A item A11 deleted that spawner and the boundary with it.
    // Writing it as well put a GUI/installer value at ordinal 500, which is the precedence lie
    // tempdoc 842 (S2) then needed a companion `.source` marker to un-tell: with the write gone the
    // marker has nothing to correct, and the installer's path classifies as STORED_SETTINGS —
    // re-derivable, supersedable by an explicit chat profile — from the ordinal chain alone. That
    // is 842 §2.3's rule reached structurally instead of by annotation.
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);

    OnlineAiService onlineAi = this.onlineAi;
    if (onlineAi instanceof OnlineAiRuntimeControl control) {
      control.applyRuntimeOverrides(
          s.getLlmModelPath(),
          s.getContextLength(),
          s.getGpuLayers(),
          OnlineAiRuntimeControl.RestartPolicy.RESTART_IF_ONLINE);
    }
    return true;
  }

  /**
   * Writes per-feature ONNX model paths to UiSettings + system properties so
   * the Head's {@code RuntimeActivationService.resolveOneOnnxFeature} sees
   * step 2 (explicit_path) hit and stops reporting reason="not_found" for
   * installed features. Mirrors {@link #applySettings} for the LLM path.
   *
   * <p>Only writes for packages that are present on disk after install (i.e.,
   * not skipped/failed). Each package's models live at
   * {@code modelsDir / pkg.targetDir()}.
   *
   * @return true when at least one feature path was written
   */
  private boolean applyOnnxSettings(ModelRegistry registry, InstallPlan plan) {
    if (settingsStore == null) return false;

    UiSettings s = settingsStore.load();
    boolean dirty = false;

    // Map package id → (UiSettings setter, sysprop key). Only ONNX features —
    // chat is handled by applySettings(); pipeline-only packages have no
    // head-side path key.
    record OnnxFeature(String pkgId, java.util.function.Consumer<String> setter, String sysProp) {}
    List<OnnxFeature> features = List.of(
        new OnnxFeature("embedding", s::setEmbedOnnxModelPath, "justsearch.embed.onnx.model_path"),
        new OnnxFeature("reranker", s::setRerankerModelPath, "justsearch.rerank.model_path"),
        new OnnxFeature("ner", s::setNerModelPath, "justsearch.ner.model_path"),
        new OnnxFeature("splade", s::setSpladeModelPath, "justsearch.splade.model_path"),
        new OnnxFeature(
            "citation-scorer", s::setCitationScorerModelPath, "justsearch.citation.scorer.model_path"));

    for (OnnxFeature feature : features) {
      ModelPackage pkg = registry.findPackage(feature.pkgId());
      if (pkg == null) continue;
      // Skip if Install AI didn't actually install this package.
      if (isPackageSkippedOrFailed(pkg.id(), plan)) continue;
      // …and skip one this run has not GOT to yet. This step runs once per acquisition stage now
      // (tempdoc 840 Phase 3), so "not skipped and not failed" is no longer the same question as
      // "installed": at the core stage, an enrichment package is merely pending. Writing its path
      // then would latch a sysprop (setSysPropIfBlank is first-writer-wins) toward a directory an
      // earlier interrupted run happened to create, for a package this run may still fail.
      if (isPackageAwaitingItsStage(pkg.id())) continue;

      Path modelDir = modelsDir.resolve(pkg.targetDir());
      if (!Files.isDirectory(modelDir)) continue;

      String absolute = modelDir.toAbsolutePath().toString();
      feature.setter().accept(absolute);
      // This sysprop write SURVIVES the 883 promotion retirement, and not by oversight (#605
      // review S1). Unlike the chat model path it is what the index half actually reads: these five
      // keys reach it through EnvRegistry.get(), i.e. from THIS JVM'S SYSPROPS. Before lane F stage
      // A the route was longer and the reason was the same — the Worker was respawned right after
      // this step (ConfigurationStage's restart gate) and WorkerSpawner forwarded the five keys as
      // `-D` args. Item A11 deleted the respawn and the forwarding; the read is now direct, so this
      // write matters more, not less. The ordinal-450 worker snapshot cannot carry them instead, because
      // ResolvedConfig.toWorkerSnapshot is called exactly once, at boot (HeadlessApp.resolveConfig),
      // so the file on disk predates this install and knows nothing about the models it just
      // landed. Deleting this line would re-open tempdoc 374 alpha.19 Bug J-1: SPLADE/NER/reranker
      // silently disabled after Install AI because the Worker saw modelPath=null. The real fix is
      // to make the snapshot re-writable at runtime, which is a separate change; until then this is
      // a knowingly-kept ordinal-500 write, not a forgotten one.
      SystemPropertyUtils.setSysPropIfBlank(feature.sysProp(), absolute);
      dirty = true;
    }

    if (dirty) {
      settingsStore.save(s);
      ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    }
    return dirty;
  }

  /**
   * Tempdoc 374 alpha.15 follow-up: write {@code justsearch.server.exe} pointing at
   * the cuda12 llama-server binary (now extracted by the cuda-runtime Install AI
   * package) so the next {@link #applySettings} → {@code applyRuntimeOverrides}
   * call routes chat through the cuda12 variant instead of the default CPU
   * binary.
   *
   * <p>Why this is needed: {@link io.justsearch.ui.HeadlessApp#maybeAutoSelectCuda12Variant}
   * runs once at boot, before Install AI populates the cuda12 dir. On a fresh
   * alpha.15 install (no prior alpha) the boot-time auto-select fires before
   * the cuda12 binary exists and SKIPs. Without this method, chat would stay
   * on the default CPU variant until the next app restart (when boot-time
   * auto-select sees the populated dir). This method closes the gap inside a
   * single Install-AI-then-apply cycle.
   *
   * <p>Respects user overrides: if a server.exe is already RESOLVED with a
   * non-{@code auto_selected_cuda12} source (env var, settings.json, operator
   * config), the explicit choice wins — see {@link #serverExeIsUserOwned}.
   *
   * @return true when the cuda12 server.exe was selected; false when the binary is absent or a user
   *     override was respected
   */
  private boolean applyCudaServerExe() {
    Path cuda12Exe =
        homeDir
            .resolve("native-bin/llama-server/variants/cuda12")
            .resolve("llama-server.exe");
    if (!Files.isRegularFile(cuda12Exe)) {
      log.debug(
          "alpha.15: cuda12 llama-server.exe not at {} — skipping (cuda-runtime"
              + " package skipped, CPU-only profile, or extract failed)",
          cuda12Exe);
      return false;
    }
    ConfigStore store = ConfigStore.globalOrNull();
    ResolvedConfig resolved = store == null ? null : store.get();
    if (serverExeIsUserOwned(resolved)) {
      log.info(
          "alpha.15: justsearch.server.exe already resolved to {} (source={}); respecting user"
              + " override",
          resolved.ai().serverExe(),
          resolved.ai().serverExeSource());
      return false;
    }
    String absPath = cuda12Exe.toAbsolutePath().toString();
    System.setProperty(io.justsearch.configuration.EnvRegistry.SERVER_EXE.sysProp(), absPath);
    System.setProperty(
        io.justsearch.configuration.EnvRegistry.SERVER_EXE_SOURCE.sysProp(), "auto_selected_cuda12");

    UiSettings s = settingsStore.load();
    s.setServerExecutablePath(absPath);
    settingsStore.save(s);
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    log.info("alpha.15: server.exe set to cuda12 variant: {}", absPath);
    return true;
  }

  /**
   * True when a server executable is already resolved and was NOT chosen by a previous cuda12
   * auto-selection — i.e. someone (env var, settings.json, operator {@code -D}) made an explicit
   * choice that {@link #applyCudaServerExe} must not overwrite.
   *
   * <p>Reads the RESOLVED config rather than the {@code justsearch.server.exe} system property.
   * Tempdoc 883 decision 4 slice 2 deleted the settings-to-sysprop promotion, so a GUI-chosen
   * executable no longer appears in that property at all; keeping the old sysprop read would have
   * made this guard fall through and silently replace the user's choice — including writing the
   * cuda12 path back into their persisted {@code UiSettings}. The resolved value carries the whole
   * ordinal chain (settings.json 300, env 400, {@code -D} 500), which is strictly more than the
   * system property ever did.
   *
   * <p>Package-private and static so the guarantee is testable without an installer.
   *
   * @param resolved the current resolved config, or {@code null} when no store is published yet
   */
  static boolean serverExeIsUserOwned(ResolvedConfig resolved) {
    if (resolved == null) return false;
    Path exe = resolved.ai().serverExe();
    if (exe == null || exe.toString().isBlank()) return false;
    return !"auto_selected_cuda12".equals(resolved.ai().serverExeSource());
  }

  /**
   * Tempdoc 374 alpha.14 fix B: write the {@code justsearch.onnxruntime.native_path}
   * sysprop pointing at the llama.cpp cuda12 variant directory. ORT's CUDA EP
   * DLL (auto-extracted from the onnxruntime-gpu JAR to a JVM temp dir) needs
   * cuBLAS + cuBLASLt + cuRT at LoadLibrary time;
   * {@link io.justsearch.ort.OrtCudaHelper#copyCudaDllsToOrtTempDir} copies
   * them next to the EP DLL.
   *
   * <p>Pre-alpha.14 the precondition guard checked for ORT EP DLLs in this
   * dir (which never live there — they ship in the JAR), tripped on every
   * invocation, and silently disabled the entire fix. Alpha.14 uses
   * {@link io.justsearch.ort.OrtCudaHelper#checkMissingCudaRuntimeDlls} which
   * checks only the runtime DLLs that actually live in cuda12.
   *
   * <p><b>Known limitation (deferred to alpha.15):</b> ORT's full CUDA
   * dependency surface includes cuFFT (and possibly cuRand/cuSparse/cuSolver/
   * cuDNN for some models). The bundled cuda12 variant is sized for
   * llama.cpp's needs, which are a strict subset of ORT's. Bundling the
   * additional DLLs into NSIS pushes the staged sidecar past the 32-bit
   * single-file mmap limit (~1.93 GB; tempdoc 374 G21). Alpha.15 will move
   * the supplemental CUDA runtime DLLs to an Install AI download package so
   * they reach the cuda12 dir post-install without bloating the installer.
   * Until then, fresh installs see ORT GPU init fail with
   * {@code cufft64_11.dll missing} even though this fix runs correctly —
   * the agent's round-5 experimental verification confirmed cuFFT is the
   * next blocker.
   */
  private boolean applyOrtNativePath() {
    Path cuda12Dir = homeDir.resolve("native-bin/llama-server/variants/cuda12");
    return writeOrtNativePathSysprop(cuda12Dir, settingsStore::load);
  }

  /**
   * Tempdoc 374 alpha.14 fix: extracted from {@link #applyOrtNativePath} so a
   * regression test can exercise the production codepath without spinning up
   * the full {@link AiInstallService}. Previously the alpha.13 fix B
   * precondition was wrong-headed (called {@code checkMissingCudaDlls} which
   * looked for the ORT EP DLLs — those live in the JAR, not in cuda12/),
   * tripped on every invocation, and silently disabled the entire fix. The
   * agent's sandbox round 5 finding pinpointed it; the lack of a regression
   * test on this codepath was a CLAUDE.md "audit-driven fixes need a test"
   * violation.
   *
   * <p>Returns {@code true} if the sysprop was newly set (or was already set
   * by the user), {@code false} if the precondition guard prevented it.
   *
   * @param cuda12Dir directory containing the bundled CUDA runtime DLLs
   *     (cudart64_12.dll, cublas64_12.dll, cublasLt64_12.dll). Typically
   *     {@code %APPDATA%/io.justsearch.shell/native-bin/llama-server/variants/cuda12}.
   * @param settingsLoader supplier for the current UiSettings (used by the
   *     ConfigStore rebuild). Test stubs can return a default UiSettings.
   */
  static boolean writeOrtNativePathSysprop(
      Path cuda12Dir, java.util.function.Supplier<UiSettings> settingsLoader) {
    if (cuda12Dir == null || !Files.isDirectory(cuda12Dir)) {
      log.debug(
          "alpha.14 fix B: cuda12 variant dir not found at {} — skipping ORT native_path"
              + " write (CPU-only build or variant not staged)",
          cuda12Dir);
      return false;
    }
    // Validate the CUDA *runtime* DLLs that actually live in cuda12 — distinct
    // from the ORT EP DLLs (those auto-extract from onnxruntime-gpu.jar to a
    // JVM temp dir at runtime; alpha.13 mistakenly checked for them here).
    var missing = io.justsearch.ort.OrtCudaHelper.checkMissingCudaRuntimeDlls(cuda12Dir);
    if (!missing.isEmpty()) {
      log.warn(
          "alpha.14 fix B: cuda12 variant dir {} is missing CUDA runtime DLLs {} —"
              + " not setting justsearch.onnxruntime.native_path. ONNX encoders will"
              + " fall back to CPU.",
          cuda12Dir,
          missing);
      return false;
    }
    String absPath = cuda12Dir.toAbsolutePath().toString();
    SystemPropertyUtils.setSysPropIfBlank("justsearch.onnxruntime.native_path", absPath);
    UiSettings s = settingsLoader != null ? settingsLoader.get() : null;
    ConfigStoreRebuilder.rebuild(ConfigStore.globalOrNull(), s);
    log.info("alpha.14 fix B: ORT native path set to {}", absPath);
    return true;
  }

  /**
   * True when this run tracks the package but has not finished with it — its files are still
   * pending, downloading or verifying.
   *
   * <p>Deliberately answers false for a package this run has no status entry for, so a package the
   * plan never mentioned behaves exactly as it did before staging existed.
   */
  private boolean isPackageAwaitingItsStage(String pkgId) {
    synchronized (lock) {
      var ps = findPackageStatus(pkgId);
      if (ps == null) return false;
      return "pending".equals(ps.state)
          || "downloading".equals(ps.state)
          || "verifying".equals(ps.state);
    }
  }

  /** True if the package was in plan.skipped() OR a per-package status reports failed. */
  private boolean isPackageSkippedOrFailed(String pkgId, InstallPlan plan) {
    for (var sp : plan.skipped()) {
      if (pkgId.equals(sp.packageId())) return true;
    }
    return status.packages.stream()
        .anyMatch(ps -> pkgId.equals(ps.packageId) && "failed".equals(ps.state));
  }

  // ---------------------------------------------------------------------------
  // Worker restart and smoke test
  // ---------------------------------------------------------------------------

  /**
   * @return always false since lane F stage A item A11: there is no worker process to restart.
   *
   * <p>This used to replace the Worker child process so a freshly installed model was picked up
   * without the user doing anything. The index half runs in this process now, so the equivalent is
   * an Engine restart — the user's action, not the installer's. The method is kept (rather than
   * having its two call sites drop the step silently) so the FALSE it returns keeps flowing into
   * the install status the surface already renders: the caller reports "a restart is required"
   * instead of claiming the model is live. Stage A §10, "restart-as-reload".
   */
  private boolean tryRestartWorkerBestEffort() {
    if (knowledgeServer == null || !knowledgeServer.hasClient()) {
      return false;
    }
    log.info(
        "AI install complete; an Engine restart is required to load the new model ({})",
        io.justsearch.app.services.worker.RestartRequiredException.CODE);
    return false;
  }

  /**
   * Post-install smoke test: bring the engine up, ask one question, confirm a non-empty reply.
   *
   * <p>Tempdoc 737 fix pack (fix 3): when a {@link RuntimeReconciler} is wired, the engine use is a
   * reconciler procedure ({@code INSTALL_SMOKE_TEST}) and the engine is requested via {@link
   * RuntimeReconciler#procedureRequireEngine(boolean)} rather than a raw {@code switchToOnlineMode()}.
   * This stops the reconciler from converging the freshly-started engine straight back DOWN while
   * the procedure is answering (spec is {@code chatEnabled=false} during a fresh install — the user
   * has not enabled chat yet). When the procedure ends, the reconciler returns the engine to spec:
   * with chat still disabled the engine converges DOWN, which is correct — <b>install is not
   * enable</b>. When no reconciler is wired (test / non-configured constructions) the legacy raw
   * {@code switchToOnlineMode()} path is kept.
   *
   * <p>The answer is waited for in {@link TransportRetryPolicy#CANCEL_POLL_SLICE_MS} slices with the
   * cancel flag polled between them, for the reason that method's javadoc gives: {@link #cancel()}
   * only raises a flag and never interrupts the install thread, so one blocking 60 s {@code get} made
   * both the user's Cancel button and the op-lease drain callback a no-op for a whole minute at the
   * very end of the run.
   */
  private boolean smokeTestBestEffort() {
    OnlineAiService onlineAi = this.onlineAi;
    RuntimeReconciler reconciler = this.reconciler;
    boolean procedureBegun = false;
    try {
      if (reconciler != null) {
        reconciler.beginProcedure(
            RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST, "post-install-smoke-test");
        procedureBegun = true;
        reconciler.procedureRequireEngine(true);
      } else {
        onlineAi.switchToOnlineMode(); // LEGACY-FALLBACK: no reconciler wired (test/non-configured)
      }
      CompletableFuture<String> answer = onlineAi.askQuestion("Reply with exactly OK.", "OK");
      long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SMOKE_TEST_TIMEOUT_MS);
      String result;
      while (true) {
        if (cancelFlag.get()) {
          // Stop the engine answering a question nobody wants any more.
          answer.cancel(true);
          cancelled();
          return false;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
          throw new TimeoutException("no reply within " + SMOKE_TEST_TIMEOUT_MS + " ms");
        }
        long sliceMs =
            Math.min(
                TransportRetryPolicy.CANCEL_POLL_SLICE_MS,
                TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1L);
        try {
          result = answer.get(sliceMs, TimeUnit.MILLISECONDS);
          break;
        } catch (TimeoutException sliceElapsed) {
          // Budget not spent yet: re-check cancellation and wait another slice.
        }
      }
      if (result == null || result.isBlank()) {
        fail("SMOKE_TEST_FAILED", "Smoke test failed: empty response");
        return false;
      }
      return true;
    } catch (Exception e) {
      fail("SMOKE_TEST_FAILED", "Smoke test failed: " + e.getMessage());
      return false;
    } finally {
      // Balanced on every exit — including the cancellation return above — so the reconciler always
      // gets the engine back to spec.
      if (procedureBegun) {
        reconciler.endProcedure(RuntimeStatus.ProcedureKind.INSTALL_SMOKE_TEST);
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Policy helpers
  // ---------------------------------------------------------------------------

  private void checkPolicy() {
    if (policyService == null) return;
    try {
      EffectivePolicy p = policyService.snapshot();
      if (p != null && !p.downloadsEnabled()) {
        throw new AiInstallException(
            403, ApiErrorCode.DOWNLOADS_DISABLED, "Downloads disabled by administrator policy.");
      }
    } catch (AiInstallException e) {
      throw e;
    } catch (Exception e) {
      log.debug("Policy snapshot failed (best-effort)", e);
    }
  }

  private boolean policyBlocksDownloads() {
    if (policyService == null) return false;
    try {
      EffectivePolicy p = policyService.snapshot();
      return p != null && !p.downloadsEnabled();
    } catch (Exception e) {
      return false;
    }
  }

  private boolean isPolicyOnlineAiAllowed() {
    if (policyService == null) return true;
    try {
      EffectivePolicy p = policyService.snapshot();
      return p == null || p.onlineAiEnabled();
    } catch (Exception e) {
      return true;
    }
  }

  // ---------------------------------------------------------------------------
  // Status management
  // ---------------------------------------------------------------------------

  private void updateState(String newState, String newPhase, String msg) {
    synchronized (lock) {
      boolean wasRunning = "running".equalsIgnoreCase(status.state);
      status.state = newState;
      status.phase = newPhase;
      status.message = msg == null ? "" : msg;
      if (status.startedAtEpochMs <= 0 && "running".equalsIgnoreCase(newState)) {
        status.startedAtEpochMs = System.currentTimeMillis();
      }
      if (!"running".equalsIgnoreCase(newState)) {
        clearRateEstimate();
      } else if (!wasRunning) {
        clearErrorStateLocked();
      }
      touch();
    }
  }

  /**
   * Drops the transfer-rate reading back to its unknown sentinel. Called on every exit from {@code
   * running}: a rate measured by a run that has ended describes a transfer that is no longer
   * happening, and leaving the last number in place would keep a completed install claiming a live
   * download speed. Caller holds {@link #lock}.
   */
  private void clearRateEstimate() {
    status.bytesPerSecond = -1d;
    status.remainingSeconds = -1L;
  }

  /**
   * Re-derives the published transfer rate from the running stage's estimator (tempdoc 840 R4).
   *
   * <p>Called on the READ path, which is the only place it can honestly be called: the stall arm of
   * {@link AcquisitionRate#estimate} measures how long ago the newest sample arrived, so an estimate
   * taken at sample time can never observe a stall, and one stamped then keeps describing a transfer
   * that has since stopped. {@code estimate} measures the running STAGE's slice, so its own horizon
   * answers "time left in this stage"; the run-level horizon is that same rate divided into the
   * run's remainder — one estimator answering both questions instead of two that could disagree.
   *
   * <p>Only while the run is {@code running}: every other state already had {@link
   * #clearRateEstimate} applied to it, and re-publishing a rate over that would undo it. Caller
   * holds {@link #lock}.
   */
  private void refreshRateEstimateLocked() {
    var source = liveRateSource;
    if (source == null || !"running".equalsIgnoreCase(status.state)) {
      return;
    }
    AcquisitionRate.Estimate stageEstimate;
    try {
      stageEstimate = source.get();
    } catch (RuntimeException e) {
      log.debug("AiInstall rate estimate unavailable (best-effort): {}", e.toString());
      return;
    }
    if (stageEstimate == null) {
      return;
    }
    long remainingBytes =
        status.totalBytes > 0L ? Math.max(0L, status.totalBytes - status.downloadedBytes) : -1L;
    AcquisitionRate.Estimate runWide = stageEstimate.reHorizon(remainingBytes);
    // The UNKNOWN sentinel reaches the wire intact — never a 0 the surface would render as
    // "0 B/s, 0s left" on a transfer that is merely young, or on one that has stopped reporting.
    status.bytesPerSecond = runWide.rateKnown() ? runWide.bytesPerSecond() : -1d;
    status.remainingSeconds = runWide.remainingKnown() ? runWide.remainingSeconds() : -1L;
  }

  /**
   * Drops the previous run's terminal error on the transition INTO {@code running}, because a run
   * that is starting has not failed. Caller holds {@link #lock}.
   *
   * <p>{@link #fail} is the only writer of these two fields and nothing ever cleared them, so the
   * ordinary "press Install, hit a problem, fix it, press Install again" sequence finished {@code
   * state=completed} while still publishing the earlier attempt's {@code errorCode} — a completed
   * install reporting {@code RUNTIME_MISSING}. This is the exact counterpart of {@link
   * #clearRateEstimate}, which sits on the opposite edge of the same transition for the same reason;
   * it was simply never applied to the error fields.
   */
  private void clearErrorStateLocked() {
    status.errorCode = "";
    status.lastError = "";
  }

  private void fail(String errorCode, String message) {
    log.warn("AI install failed [{}]: {}", errorCode, message);
    synchronized (lock) {
      status.state = "failed";
      status.errorCode = errorCode;
      status.lastError = message;
      status.message = message;
      // This path sets `state` directly rather than through updateState, so it has to drop the rate
      // itself — a failed run must not keep publishing the speed it was moving at when it died.
      clearRateEstimate();
      touch();
    }
  }

  private void cancelled() {
    updateState("cancelled", status.phase, "Cancelled.");
    // The surviving byte count is re-derived from disk by startInstall's finally block, which covers
    // this path and every other terminal one. `state = "cancelled"` itself is session-ephemeral and
    // gone after a restart; the staged bytes are not, which is why the UI keys on them, not on this.
  }

  private void touch() {
    status.updatedAtEpochMs = System.currentTimeMillis();
  }

  private void populateStatusPackages(InstallPlan plan, ModelRegistry registry) {
    // Packages with downloads
    for (var dl : plan.downloads()) {
      if (findPackageStatus(dl.packageId()) != null) continue;
      ModelPackage pkg = registry.findPackage(dl.packageId());
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = dl.packageId();
      ps.label = pkg != null ? pkg.label() : dl.packageId();
      describe(ps, pkg);
      ps.tier = tierId(pkg);
      ps.stage = stageId(pkg);
      ps.state = "pending";
      ps.bytesTotal = plan.downloads().stream()
          .filter(d -> d.packageId().equals(dl.packageId()))
          .mapToLong(InstallPlan.PlannedDownload::sizeBytes)
          .sum();
      status.packages.add(ps);
    }
    // Already installed
    for (String id : plan.alreadyInstalled()) {
      ModelPackage pkg = registry.findPackage(id);
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = id;
      ps.label = pkg != null ? pkg.label() : id;
      describe(ps, pkg);
      ps.tier = tierId(pkg);
      ps.stage = stageId(pkg);
      ps.state = "installed";
      status.packages.add(ps);
    }
    // Skipped
    for (var sk : plan.skipped()) {
      // A devOnly package (tempdoc 842) is not a component of a USER install: it is never planned,
      // never declinable, and the user has no decision about it. Listing it here made it one — it
      // became a `skipped` row, and `installedFully` (which counted skipped rows) could then never
      // be true on ANY machine, while the completion message blamed the user's hardware for a
      // packaging decision (tempdoc 941 round 19, F3). The plan-preview loop above already filters
      // it on exactly this reasoning; run bookkeeping now agrees with the preview.
      if (sk.cause() == SkipCause.DEV_ONLY) {
        continue;
      }
      var ps = new AiInstallStatus.PackageStatus();
      ps.packageId = sk.packageId();
      ModelPackage pkg = registry.findPackage(sk.packageId());
      ps.label = pkg != null ? pkg.label() : sk.packageId();
      describe(ps, pkg);
      ps.tier = tierId(pkg);
      ps.stage = stageId(pkg);
      ps.state = "skipped";
      ps.skipReason = sk.reason();
      // The planner already decided WHY, with a typed cause; carry it rather than let a consumer
      // re-derive it from the prose (or, as the completion message did, assume it).
      ps.skipCause = sk.cause() == null ? "" : sk.cause().id();
      status.packages.add(ps);
    }
  }

  /**
   * Projects the registry's standing description of a package onto its status row (tempdoc 840
   * Phase 4): what it is for, how badly the product needs it, and whether that necessity leaves the
   * user any choice.
   *
   * <p>All three are facts about the REGISTRY, so they are stamped when the row is built. The
   * user's own decision is not — {@code declined} is a live preference and is refreshed on every
   * status read instead (see {@link #getStatus()}).
   */
  private static void describe(AiInstallStatus.PackageStatus ps, ModelPackage pkg) {
    if (pkg == null) {
      return;
    }
    ps.description = pkg.description() == null ? "" : pkg.description();
    // Never null: ModelPackage's compact constructor normalizes an unclassified package to REQUIRED.
    ps.necessity = pkg.necessity().id();
    ps.declinable = pkg.necessity().userDeclinable();
  }

  /** The package's capability-tier id (tempdoc 657), or {@code null} if the package/tier is unknown. */
  private static String tierId(ModelPackage pkg) {
    return pkg != null && pkg.tier() != null ? pkg.tier().id() : null;
  }

  /**
   * The acquisition stage that delivers this package — a projection of its tier through {@link
   * InstallStage}, computed here so a package that is skipped or already installed (and therefore
   * appears in no stage SLICE) still says which stage it belongs to.
   */
  private static String stageId(ModelPackage pkg) {
    return InstallStage.forTier(pkg == null ? null : pkg.tier()).id();
  }

  /**
   * Publishes the run's stage plan — the ordered stages, their capabilities and their byte shares —
   * before the first stage starts. A projection of the partition, so nothing here is a second
   * authority over what a stage contains.
   */
  private void publishStagePlan(List<InstallStage.Slice> slices) {
    synchronized (lock) {
      status.stages.clear();
      status.readyCapabilities.clear();
      status.currentStage = "";
      for (InstallStage.Slice slice : slices) {
        var st = new AiInstallStatus.StageStatus();
        st.stage = slice.stage().id();
        st.label = slice.stage().label();
        st.state = "pending";
        st.capabilities.addAll(slice.stage().tierIds());
        st.totalBytes = slice.bytes();
        status.stages.add(st);
      }
      touch();
    }
    log.info(
        "Install staged into {}: {}",
        slices.size(),
        slices.stream()
            .map(s -> s.stage().id() + "=" + s.downloads().size() + " file(s)/" + s.bytes() + "B")
            .collect(java.util.stream.Collectors.joining(", ")));
  }

  private void setCurrentStage(InstallStage stage) {
    synchronized (lock) {
      status.currentStage = stage.id();
      var st = findStageStatus(stage.id());
      if (st != null) {
        st.state = "running";
      }
      touch();
    }
  }

  /**
   * Records how one stage ended, and whether its capabilities are now usable.
   *
   * <p>Readiness takes TWO facts, because either alone can lie. The stage must have ended in a state
   * whose configuration pass actually ran — a stage cancelled after its last file landed has the
   * bytes on disk but never restarted the Worker onto them, so the capability is not live — and the
   * stage must have DELIVERED, which {@link #stageDeliveredLocked} defines.
   */
  private void markStage(InstallStage stage, String state) {
    boolean configurationRan =
        StagedAcquisition.StageState.COMPLETED.id().equals(state)
            || StagedAcquisition.StageState.SKIPPED.id().equals(state);
    synchronized (lock) {
      var st = findStageStatus(stage.id());
      if (st != null) {
        st.state = state;
      }
      if (configurationRan && stageDeliveredLocked(stage)) {
        for (String capability : stage.tierIds()) {
          if (!status.readyCapabilities.contains(capability)) {
            status.readyCapabilities.add(capability);
          }
        }
      }
      touch();
    }
  }

  /**
   * Whether this stage's capabilities are actually on this machine: at least one of its packages
   * reached {@code installed}, and none of them is in a state that says the stage is unfinished or
   * broken. Caller holds the lock.
   *
   * <p><b>{@code skipped} is NEUTRAL</b> — it neither delivers nor blocks. A package the hardware
   * cannot run, the install mode does not want, or the user declined is out of scope for the stage,
   * not a failure to deliver it. Demanding that EVERY package of a stage reach {@code installed}
   * made {@code retrieval-core} unreachable on every non-NVIDIA machine in existence: {@code
   * cuda-runtime} declares {@code requiresCuda} and lives in the CORE stage, so it is
   * hardware-skipped there, and the stage that delivered a perfectly working search silently never
   * announced itself.
   *
   * <p>Everything else still fails closed. A {@code failed} package means the capability's model did
   * not land; {@code pending} / {@code downloading} / {@code verifying} means the stage is not
   * finished with it yet. Zero installed is not a delivery either — a stage whose every package was
   * declined has nothing to announce.
   */
  private boolean stageDeliveredLocked(InstallStage stage) {
    boolean anyInstalled = false;
    for (var ps : status.packages) {
      if (!stage.id().equals(ps.stage)) continue;
      if ("installed".equals(ps.state)) {
        anyInstalled = true;
      } else if (!"skipped".equals(ps.state)) {
        return false;
      }
    }
    return anyInstalled;
  }

  /**
   * Records that a stage was refused before it started, with the reason the user can act on
   * (tempdoc 840 U2). Distinct from a failure: nothing was attempted.
   */
  private void recordStageBlocked(InstallStage stage, String reason) {
    synchronized (lock) {
      var st = findStageStatus(stage.id());
      if (st != null) {
        st.blockedReason = reason == null ? "" : reason;
      }
      touch();
    }
  }

  private AiInstallStatus.StageStatus findStageStatus(String stageId) {
    if (stageId == null || stageId.isEmpty()) return null;
    for (var st : status.stages) {
      if (stageId.equals(st.stage)) return st;
    }
    return null;
  }

  private void updatePackageState(String packageId, String state) {
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        // A package failure is terminal for this install run. Later files may
        // proceed, but the aggregate must stay failed so installedFully cannot lie.
        if ("failed".equals(ps.state)) {
          return;
        }
        ps.state = state;
      }
      touch();
    }
  }

  /** Records that this package continued an earlier run's bytes instead of restarting from zero. */
  private void markPackageResumed(String packageId) {
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        ps.resumed = true;
      }
      touch();
    }
  }

  private void updatePackageProgress(String packageId, long bytes, long total) {
    var ps = findPackageStatus(packageId);
    if (ps != null) {
      if ("failed".equals(ps.state)) {
        return;
      }
      ps.bytesDownloaded = Math.max(0, bytes);
      if (total > 0) ps.bytesTotal = total;
    }
  }

  /**
   * Records that this package will not converge on its own (tempdoc 824 §3.4) and what the user can
   * do instead: the exact URL and the exact path. Called before {@link #failPackage} so the
   * terminal verdict is attached to the same package the failure is about.
   */
  private void markPackageTerminal(
      String packageId, String terminalReason, int attempts, String url, String targetPath) {
    log.warn(
        "Package [{}] will not repair automatically: {} after {} transport attempts on {}",
        packageId,
        terminalReason,
        attempts,
        url);
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        ps.terminalReason = terminalReason;
        ps.attempts = attempts;
        ps.url = url == null ? "" : url;
        ps.targetPath = targetPath == null ? "" : targetPath;
      }
      touch();
    }
  }

  private void failPackage(String packageId, String message) {
    log.warn("Package install failed [{}]: {}", packageId, message);
    synchronized (lock) {
      var ps = findPackageStatus(packageId);
      if (ps != null) {
        ps.state = "failed";
        ps.error = message;
      }
      touch();
    }
  }

  private AiInstallStatus.PackageStatus findPackageStatus(String packageId) {
    for (var ps : status.packages) {
      if (packageId.equalsIgnoreCase(ps.packageId)) return ps;
    }
    return null;
  }

  /**
   * Tempdoc 374 alpha.15 fix B: extract a zip archive into {@code targetDir},
   * skipping entries that already exist (idempotent re-runs). Used by the
   * cuda-runtime package to expand the supplemental CUDA DLLs (cuFFT et al.)
   * into the cuda12 variant dir post-download.
   *
   * <p>Defensive against zip-slip: rejects entries whose normalized path
   * escapes {@code targetDir} (e.g., {@code ../../etc/passwd}).
   *
   * @param zipFile the archive to extract
   * @param targetDir the directory entries are extracted into (entries are
   *     resolved relative to this dir; nested zip entries preserve their path
   *     under it)
   */
  static void extractZipInPlace(Path zipFile, Path targetDir) throws IOException {
    if (zipFile == null || !Files.isRegularFile(zipFile)) {
      throw new IOException("Archive not found: " + zipFile);
    }
    Path normalizedTarget = targetDir.toAbsolutePath().normalize();
    Files.createDirectories(normalizedTarget);
    int extracted = 0;
    try (var zip = new java.util.zip.ZipFile(zipFile.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        if (entry.isDirectory()) continue;
        Path resolved = normalizedTarget.resolve(entry.getName()).normalize();
        if (!resolved.startsWith(normalizedTarget)) {
          throw new IOException(
              "Refusing zip entry that escapes target directory: " + entry.getName());
        }
        if (Files.exists(resolved)) continue; // idempotent re-extract
        Files.createDirectories(resolved.getParent());
        // Extract to a sibling .partial then atomically rename, so a crash mid-copy never leaves a
        // half-written file at the final path (which the existence-skip above would then trust
        // forever). Mirrors the download path's partial-then-moveAtomicBestEffort discipline.
        Path partial = resolved.resolveSibling(resolved.getFileName() + ".partial");
        Files.deleteIfExists(partial);
        try (var in = zip.getInputStream(entry)) {
          Files.copy(in, partial);
        }
        DownloadExecutor.moveAtomicBestEffort(partial, resolved);
        extracted++;
      }
    }
    log.info(
        "Extracted {} new entries from {} to {}",
        extracted,
        zipFile.getFileName(),
        normalizedTarget);
  }

  private long countPackagesByState(String state) {
    return status.packages.stream().filter(p -> state.equals(p.state)).count();
  }

  private static Path resolveHomeDir() {
    try {
      ConfigStore cs = ConfigStore.globalOrNull();
      Path fromEnv = cs != null ? cs.get().paths().home() : null;
      if (fromEnv != null) return fromEnv;
    } catch (Exception e) {
      log.debug("Failed to resolve AI home dir from ConfigStore (best-effort)", e);
    }
    try {
      return PlatformPaths.resolveDataDir();
    } catch (Exception e) {
      return Path.of(System.getProperty("user.dir"));
    }
  }
}
