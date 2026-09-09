/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.justsearch.gpu.GpuCapabilities;
import io.justsearch.gpu.GpuCapabilitiesService;
import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.services.HeadAssembly;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.IndexingService;
import io.justsearch.app.api.OnlineAiService;
import io.justsearch.app.api.gpl.GplEvalData;
import io.justsearch.app.api.gpl.GplStatusProvider;
import io.justsearch.app.api.gpl.RerankerService;
import io.justsearch.app.services.intent.EngineProvenance;
import io.justsearch.app.services.observability.HeadApiMetricCatalog;
import io.justsearch.app.services.worker.KnowledgeServerBootstrap;
import io.justsearch.configuration.EnvRegistry;
import io.justsearch.configuration.resolved.ConfigStore;
import io.justsearch.core.context.EngineContext;
import io.justsearch.telemetry.Telemetry;
import io.justsearch.ui.api.routes.AiRoutes;
import io.justsearch.ui.api.routes.DebugRoutes;
import io.justsearch.ui.api.routes.IndexingRoutes;
import io.justsearch.ui.api.routes.InferenceRoutes;
import io.justsearch.ui.api.routes.KnowledgeRoutes;
import io.justsearch.ui.api.routes.StatusRoutes;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Head process's loopback-only HTTP API server (Javalin). Binds 127.0.0.1 only and delegates all
 * index IO to the Worker via gRPC — it never touches Lucene directly (the two hard invariants).
 *
 * <p><b>Structure (tempdoc 583).</b> This class is a thin composer, not a god-class: it wires
 * collaborators and registers routes, but does not itself construct the bulk of the controller graph.
 * 583 decomposed the former ~2600-LOC constructor into per-domain collaborators so this file stays
 * under the 1000-LOC ceiling by construction:
 *
 * <ul>
 *   <li>The two largest controller cohorts are assembled elsewhere ({@link CoreApiAssembly},
 *       {@link ConversationApiAssembly}) and held as their {@code Result} records ({@code core},
 *       {@code convApi}); a new controller in either cohort touches only the assembly, not this file.
 *   <li>The substrate-Resource cohort is one {@link ApiModule} ({@link ResourceApiModule}); cohorts
 *       are held in {@code apiModules} and iterated for register + shutdown (§D.2a), so a new cohort
 *       is one list entry, not a new field threaded through construction / routing / stop.
 *   <li>Route binding is grouped into {@code *Routes} registrars (Status/Indexing/Debug/Ai/...), and
 *       the self-describing {@code /api/meta/*} family ({@link RouteManifestController},
 *       {@link OpenApiController}) projects the live router itself.
 * </ul>
 *
 * <p>The instance-field count is held by the {@code LocalApiServerThinComposerTest} fitness function
 * (§D.4) so the structure cannot silently regrow.
 */
public class LocalApiServer {
  private static final Logger log = LoggerFactory.getLogger(LocalApiServer.class);

  /** Header name for the desktop session token (used in prod mode to protect non-GET endpoints). */
  public static final String SESSION_TOKEN_HEADER = "X-JustSearch-Session";

  /**
   * The MCP Streamable-HTTP endpoint path. Shared with {@link
   * ApiSecurityFilters#setupMcpOriginValidation} so the guarded path and the routed path cannot
   * drift — moving the endpoint without moving the guard would silently unguard it.
   */
  public static final String MCP_ENDPOINT_PATH = "/mcp";

  /**
   * The MCP session-token bootstrap path. Shared with {@link
   * ApiSecurityFilters#setupMcpOriginValidation} for the same reason as {@link #MCP_ENDPOINT_PATH}:
   * this route hands out the credential that gates every mutating call, so the guarded path and the
   * routed path must not be able to drift apart.
   */
  public static final String MCP_TOKEN_PATH = "/api/mcp/token";

  // Tempdoc 374 alpha.21 Bug O: not final because the explicit-port-bind-failure
  // fallback rebuilds a fresh Javalin instance — Javalin/Jetty's lifecycle prohibits
  // re-starting a failed instance. Volatile for safe publication after constructor
  // completes (the field is read by request-handling threads).
  private volatile Javalin app;
  private final int port;
  private final Telemetry telemetry;
  private final SettingsController settingsController;
  private final IndexingController indexingController;
  private final UiReadyController uiReadyController;
  // Tempdoc 583 Stage 1: the nine MessageCatalogController instances moved to MessageCatalogRoutes.
  // Slice 3a.1.9 §A.6a: serves classpath SSOT/schemas/*.v1.json same-origin for <jf-resource-view>.
  private final SchemaController schemaController;
  private final ConversationEncryptionController conversationEncryptionController;
  // Slice 477 H2.3: stub plugin verification endpoint. V1.5.2 swaps
  // in real Sigstore chain verification via sigstore-java.
  private final PluginVerificationController pluginVerificationController;
  // Tempdoc 583 Stage 3 / §D.2a: the ApiModule cohort list (MetaApiModule always; ResourceApiModule
  // with a bootstrap), iterated for register + shutdown. Non-final only so MetaApiModule's lazy
  // `() -> this.apiModules` supplier (it needs the sibling modules for owningModule attribution) can
  // be created before the list literal is assigned in this same constructor; assigned exactly once.
  private java.util.List<ApiModule> apiModules;
  // Tempdoc 583 follow-up: the two largest controller cohorts are held as their assembly Result
  // records and read via accessors (core.statusLifecycleHandler(), convApi.agentController(), ...),
  // so adding a controller to either cohort touches only the assembly — not this file's field region.
  private final CoreApiAssembly.Result core;
  private final ConversationApiAssembly.Result convApi;
  /** Tempdoc 629 (#7): encrypted-backup export of authored data; null on the test-only path. */
  private final ConversationBackupController conversationBackupController;
  // Tempdoc 430 Phase 8: retained for symmetric rule-engine shutdown. Null on the no-bootstrap path.
  private final HeadAssembly HeadAssemblyRef;
  // Carve-out: reassigned in lateBindKnowledgeServer (Worker reconnect), so it stays a mutable field
  // seeded from core.knowledgeSearchController() — it cannot live in the immutable Result.
  private volatile KnowledgeSearchController knowledgeSearchController;
  // Tempdoc 419 / T4: shared scan-progress registry (adapter producer -> ScanProgressController),
  // one per process, closed on shutdown.
  private final io.justsearch.app.services.worker.ScanProgressRegistry scanProgressRegistry;
  private volatile ScanProgressController scanProgressController;
  // Tempdoc 374 alpha.27: the single GPU/VRAM access point (owns its nvidia-smi-fallback VramDetector).
  private final GpuCapabilitiesService gpuCapabilitiesService;
  private final Integer configuredPort;
  private final Instant startTime = Instant.now();
  private final EventBuffer eventBuffer = new EventBuffer();
  private final AtomicInteger inflightRequests = new AtomicInteger(0);
  private final String sessionToken;
  private final boolean prodMode;
  private final ExecutorService slowRequestExecutor;
  private final io.justsearch.core.execution.EngineExecutorRegistry.Registration slowRequestOwner;
  // Tempdoc 583 Stage 4: the request-filter security plumbing collaborator.
  private final ApiSecurityFilters securityFilters;
  /** H4: Cache TTL for GPU snapshot to avoid excessive NVML probes. */
  private static final long GPU_CACHE_TTL_MS = 5000;
  private volatile GpuCapabilities cachedGpuSnapshot;
  private volatile long gpuSnapshotTimestamp;
  private final RerankerService lambdaMartReranker;
  private final io.justsearch.app.services.worker.SearchPerSourceExecutor perSourceSearch;
  private final HeadApiMetricCatalog apiCatalog;

  /** Creates a new builder. Required: settingsStore, indexBasePath. The bootstrap and per-service
   * overrides are provided via fluent setters (.HeadAssembly, .onlineAiService, etc.).
   * A Knowledge Server also requires the Head search owner or explicit perSourceSearch. */
  public static Builder builder(
      io.justsearch.core.execution.EngineExecutorRegistry executors,
      io.justsearch.app.services.settings.UiSettingsStore settingsStore,
      Path indexBasePath) {
    return new Builder(executors, settingsStore, indexBasePath);
  }

  private LocalApiServer(Builder b) {
    this.perSourceSearch = b.perSourceSearch != null ? b.perSourceSearch
        : b.HeadAssembly == null ? null : b.HeadAssembly.perSourceSearch();
    this.scanProgressRegistry = new io.justsearch.app.services.worker.ScanProgressRegistry(b.executors);
    this.telemetry = b.telemetry;
    this.lambdaMartReranker = b.lambdaMartReranker;
    // §31 Phase 4: read helpers/infra from the bootstrap's ServicePhase output rather than
    // constructing duplicates. Fallback for the rare path where HeadAssembly is null
    // (legacy test seams). Bootstrap-provided instances are what feed the operation handlers
    // and the held ServiceGraph; using them here keeps the codebase to one set.
    this.gpuCapabilitiesService =
        b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().gpuCapabilitiesService()
            : new GpuCapabilitiesService();
    // Tempdoc 417 Phase 2c: api catalog needed early because controllers receive it.
    this.apiCatalog =
        b.telemetry instanceof io.justsearch.telemetry.LocalTelemetry lt0
            ? new HeadApiMetricCatalog(lt0.registry())
            : HeadApiMetricCatalog.noop();
    // Tempdoc 737 Phase 1: settings writes that change chatEnabled nudge the runtime
    // reconciler (specChanged) so the persisted intent converges now, not at next boot.
    // Lazily resolved at fire time — null-safe for test paths without a HeadAssembly.
    final HeadAssembly haForSpecNudge = b.HeadAssembly;
    this.settingsController =
        new SettingsController(
            b.settingsStore,
            b.indexBasePath,
            this.telemetry,
            ConfigStore.globalOrNull(),
            () -> {
              var reconciler = haForSpecNudge != null ? haForSpecNudge.runtimeReconciler() : null;
              if (reconciler != null) {
                reconciler.specChanged();
              }
            });
    // Tempdoc 560 §28: the plugin-trust allowlist is persisted as a sibling of settings.json so an
    // operator approval of a URL-loaded plugin survives restarts (otherwise it silently falls back
    // to UNTRUSTED). Mode follows settings (IN_MEMORY for prod/CI isolation; READ_WRITE for use).
    this.pluginVerificationController =
        new PluginVerificationController(
            new io.justsearch.app.services.settings.PluginAllowlistStore(
                b.settingsStore.mode(),
                b.settingsStore.settingsPath().resolveSibling("plugin-allowlist.json")));
    // §31 Step 1.1 + 526 F1: ExcludesService is constructed by ServicePhase + held in the
    // ServiceGraph; IndexingController takes a Supplier<IndexingService> so async Worker
    // connect (HeadAssembly.connectKnowledgeServer) is observed without a setter.
    Supplier<IndexingService> indexingSvcSupplier =
        b.HeadAssembly != null
            ? () -> b.HeadAssembly.workers().indexing() != null
                ? b.HeadAssembly.workers().indexing()
                : IndexingService.unavailable()
            : IndexingService::unavailable;
    io.justsearch.app.api.ExcludesService excludesService =
        b.HeadAssembly != null && b.HeadAssembly.workers().excludes() != null
            ? b.HeadAssembly.workers().excludes()
            : new io.justsearch.app.services.excludes.ExcludesServiceImpl(indexingSvcSupplier);
    // Real process-local lease admission; only the dev-runner file projection is optional.
    io.justsearch.app.api.OperationLeaseService leaseSvc =
        b.operationLeaseService != null
            ? b.operationLeaseService
            : b.HeadAssembly != null && b.HeadAssembly.serviceOut() != null
            ? b.HeadAssembly.serviceOut().operationLeaseService()
            : new io.justsearch.app.services.lease.OperationLeaseServiceImpl();
    this.indexingController =
        new IndexingController(indexingSvcSupplier, excludesService, b.userHome, this.telemetry,
            leaseSvc);
    if (b.HeadAssembly != null) {
      this.indexingController.setWorkerCapability(b.HeadAssembly.capabilities().worker());
    }
    this.uiReadyController = new UiReadyController(eventBuffer, this.telemetry);
    // Tempdoc 429 Phase 5+7 closure: wire RegistryController + CapabilitiesStreamController
    // against the substrate components exposed by HeadAssembly. When the bootstrap
    // is absent (test paths), these stay null and route binding skips them.
    // Tempdoc 583 Stage 3 / §D.2a: route-owning cohorts held as the ApiModule list, iterated for
    // register + shutdown. A future cohort joins by adding another list entry. MetaApiModule (§D.2a/
    // §D.3 — the /api/meta/* self-description family) is always present; the substrate-Resource cohort
    // is present only with a bootstrap (absent on the no-bootstrap test path). MetaApiModule reads the
    // module list lazily (it needs every module's ownedRoutePaths() for owningModule attribution).
    ResourceApiModule resourceApiModule =
        b.HeadAssembly != null
            ? new ResourceApiModule(
                b.HeadAssembly, this.telemetry, b.runtimeManifestPublisher, b.indexBasePath, b.engineAdmission)
            : null;
    MetaApiModule metaApiModule = new MetaApiModule(() -> this.app, () -> this.apiModules);
    UpgradeApiModule upgradeApiModule =
        new UpgradeApiModule(
            leaseSvc,
            b.upgradeShutdownAction,
            b.HeadAssembly == null
                ? null
                : () -> {
                  KnowledgeServerBootstrap worker = b.HeadAssembly.currentKnowledgeServer();
                  return worker == null ? null : worker.client();
                },
            b.upgradeDataDir,
            b.upgradeRunningVersion,
            b.upgradeHeadReady != null ? b.upgradeHeadReady : () -> b.HeadAssembly != null,
            b.upgradeWorkerReady != null
                ? b.upgradeWorkerReady
                : () -> {
                  if (b.HeadAssembly == null || !b.HeadAssembly.capabilities().worker().available()) {
                    return false;
                  }
                  KnowledgeServerBootstrap worker = b.HeadAssembly.currentKnowledgeServer();
                  return worker != null && worker.client() != null;
                });
    // Tempdoc 805 G.1: the normal-quit leg — the shell asks Head to run the SAME ordered shutdown
    // the upgrade path drives, without the upgrade bookkeeping.
    LifecycleApiModule lifecycleApiModule = new LifecycleApiModule(b.lifecycleShutdownAction);
    this.apiModules =
        resourceApiModule != null
            ? java.util.List.of(
                metaApiModule, resourceApiModule, upgradeApiModule, lifecycleApiModule)
            : java.util.List.of(metaApiModule, upgradeApiModule, lifecycleApiModule);
    this.HeadAssemblyRef = b.HeadAssembly;

    // Tempdoc 583 Stage 1: message catalogs are constructed + bound in MessageCatalogRoutes (setupRoutes).
    // Slice 3a.1.9 §A.6a: classpath schema-serving controller.
    this.schemaController = new SchemaController(this.telemetry);
    // Tempdoc 629 (LAYER): at-rest encryption control endpoints, over the one shared key manager.
    this.conversationEncryptionController =
        new ConversationEncryptionController(
            this.HeadAssemblyRef != null
                ? this.HeadAssemblyRef.dataKeyManager()
                : io.justsearch.app.services.encryption.DataKeyManager.disabled());
    // Tempdoc 583 Stage 5: core controller cohort + status wiring (CoreApiAssembly). Held as the
    // Result record and read via accessors; lateBind/stop mutate the same instances as before.
    this.core =
        CoreApiAssembly.assemble(
            b,
            this.telemetry,
            this.apiCatalog,
            this.gpuCapabilitiesService,
            this.eventBuffer,
            this.startTime,
            this.HeadAssemblyRef,
            this.settingsController,
            this::getPort,
            this::resolveLlamaServerPort,
            this::getCachedGpuSnapshot,
            () -> (double) inflightRequests.get());
    // Carve-out C1: seed the reassignable knowledgeSearchController field from the Result (it is
    // re-created in lateBindKnowledgeServer, so it cannot stay inside the immutable record).
    this.knowledgeSearchController = core.knowledgeSearchController();
    // Tempdoc 583 Stage 2: ConversationEngine + agent/chat/MCP assembly (ConversationApiAssembly).
    // resource-cohort presence gates the MCP surface; the late-bound knowledgeSearchController is
    // passed as a supplier (the MCP surface reads it lazily).
    this.convApi =
        ConversationApiAssembly.assemble(
            b,
            this.telemetry,
            this.apiCatalog,
            resourceApiModule != null,
            () -> this.knowledgeSearchController);
    // Tempdoc 629 (#7): the encrypted-backup export controller (reads the 3 AUTHORED stores —
    // conversations, memories, agent-runs — once unlocked). Null on the test-only path with no HeadAssembly.
    this.conversationBackupController =
        this.HeadAssemblyRef != null
            ? new ConversationBackupController(
                this.HeadAssemblyRef, this.HeadAssemblyRef.dataKeyManager())
            : null;
    // Tempdoc 832 (lane D): publish the registry the setters below bind onto the controller-owned
    // adapter, so the AGENT-owned ingest adapter gets the same scan observability — an agent- or
    // MCP-driven directory ingest emitted no scan-progress SSE and left no rollup row. Done here
    // (not in AgentToolFactory.build) because the registry does not exist during the service phase,
    // and this runs before connectKnowledgeServer builds the late-bound agent adapter.
    if (this.HeadAssemblyRef != null) {
      this.HeadAssemblyRef.setScanProgressRegistry(this.scanProgressRegistry);
    }
    this.configuredPort = resolveConfiguredPort();
    ConfigStore cs = ConfigStore.globalOrNull();
    this.prodMode = cs != null && cs.get().policy().prodMode();
    this.sessionToken = b.sessionToken;
    var backgroundLimits = b.executors.limits(
        io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND);
    this.slowRequestOwner = b.executors.register(new io.justsearch.core.execution.EngineExecutorSpec(
        "head.slow-request-dump", io.justsearch.core.execution.EngineExecutorSpec.Kind.BACKGROUND,
        io.justsearch.core.execution.EngineExecutorSpec.Mode.PLATFORM,
        1, backgroundLimits.maxQueue(), 1));
    this.slowRequestExecutor =
        slowRequestOwner.open(
            r -> {
              Thread t = new Thread(r, "slow-request-dump");
              t.setDaemon(true);
              return t;
            });
    SlowRequestDumper.pruneOldDumps(30);
    // Tempdoc 583 Stage 4: CORS / token / capability-gate / slow-dump filters (ApiSecurityFilters);
    // install() binds them inside buildAndStartApp, keeping the loopback bind policy single-authority.
    this.securityFilters =
        new ApiSecurityFilters(
            this.prodMode, this.sessionToken, this.eventBuffer, this.slowRequestExecutor,
            this.HeadAssemblyRef, leaseSvc, b.engineAdmission);

    // Bind to explicit port when provided (dev/prod), otherwise pick a free port.
    int bindPort = configuredPort == null ? 0 : configuredPort;
    // Bind loopback-only by default (prod + dev). This is a local desktop app; avoid LAN exposure.
    try {
      buildAndStartApp(bindPort);
    } catch (Exception e) {
      if (configuredPort != null && configuredPort != 0 && isBindFailure(e)) {
        log.warn("Failed to bind to explicit port {} ({}). Falling back to ephemeral port.",
            configuredPort, e.getMessage());
        eventBuffer.warn("LocalApiServer",
            "Port " + configuredPort + " in use, falling back to ephemeral",
            Map.of("requestedPort", configuredPort, "error", String.valueOf(e.getMessage())));
        // Tempdoc 374 alpha.21 Bug O: build a FRESH Javalin instance for the
        // ephemeral retry. Javalin/Jetty's lifecycle prohibits re-starting a failed
        // instance — pre-alpha.21 the second start() call threw
        // `JavalinException: Server already started - Javalin instances cannot be
        // reused.` The previous failed instance is GC-eligible after this
        // reassignment.
        buildAndStartApp(0);
      } else {
        throw e;
      }
    }
    this.port = app.port();
    log.info("Local API Server started on port {}", port);
    eventBuffer.info("LocalApiServer", "API Server started on port " + port, Map.of("port", port));
    // Tempdoc 419 C3 V2 P3: start the GPU saturation sampler. The sampler's first probe
    // checks NVML availability; on headless / non-GPU machines it short-circuits and never
    // schedules.
    try {
      core.gpuSaturationSampler().start();
    } catch (RuntimeException e) {
      log.warn("GpuSaturationSampler.start failed: {}", e.getMessage());
    }
    // Tempdoc 672 follow-up: start the VDU offline-processing idle/energy auto-trigger sampler,
    // same lifecycle shape as the GPU saturation sampler above.
    try {
      core.vduOfflineTriggerSampler().start();
    } catch (RuntimeException e) {
      log.warn("VduOfflineTriggerSampler.start failed: {}", e.getMessage());
    }
  }

  /**
   * Tempdoc 374 alpha.21 Bug O: build and start a fresh Javalin instance for the given
   * bind port. Called once on first attempt; called a second time on bind failure to
   * construct a NEW instance for the ephemeral-port fallback (Javalin/Jetty's lifecycle
   * prohibits re-starting a failed instance).
   *
   * <p>Side effect: assigns {@code this.app} so the {@code setupCors} /
   * {@code setupSessionTokenEnforcement} / {@code setupRoutes} methods, which read
   * {@code this.app} directly, register handlers on the fresh instance.
   *
   * <p>Re-running setup on a second call is safe: each setup method registers handlers
   * on whatever {@code this.app} currently is. Note that any side-effect emission placed
   * inside a setup method fires TWICE on the bind-fallback path — which is one reason the
   * fail-closed session-token refusal lives in the {@code ApiSecurityFilters} constructor
   * (constructed once, above the try) rather than in its install path (tempdoc 884 item 23).
   */
  private void buildAndStartApp(int bindPort) {
    this.app = Javalin.create(config -> {
      config.showJavalinBanner = false;
      // Jackson 3 uses tools.jackson package; Javalin's built-in JavalinJackson looks for
      // com.fasterxml.jackson (2.x). Provide our own adapter.
      config.jsonMapper(new io.justsearch.ui.json.Jackson3JsonMapper());
    });

    // Tempdoc 875 open item: Javalin's ctx.json declares bare `application/json`, so a client that
    // honours the response charset decoded UTF-8 bytes as Latin-1 (the undo summary's em-dash
    // arrived as the "a-circumflex euro" mojibake while SSE, which declares its charset, was
    // clean). One after-handler over the whole JSON plane; see JsonResponseCharset.
    JsonResponseCharset.install(app);

    // HttpResponseException handler — preserves handler-set body while
    // propagating the exception's HTTP status.
    //
    // The original no-op handler intended to preserve `ctx.json(...)` bodies
    // that controllers set before throwing. It DID preserve body, but also
    // swallowed the status — leaving the response at Javalin's default (200).
    // For controller-thrown HttpResponseExceptions that's fine because they
    // typically call `ctx.status(...)` before throwing. For Javalin's
    // *auto-thrown* `NotFoundResponse` (unmatched route → empty body, status
    // never set), the swallow produced 200 OK with empty body — see the
    // 2026-05-08 inbox-triage validation that confirmed this defect.
    //
    // Setting `ctx.status(e.getStatus())` here propagates the exception's
    // status without clobbering body. The downstream `app.error(404, ...)`
    // hook then fires for genuinely-unmatched paths and writes the typed
    // JSON payload. Controllers that set their own status before throwing
    // are unaffected — `e.getStatus()` reflects the same status they
    // constructed the exception with.
    app.exception(io.javalin.http.HttpResponseException.class, (e, ctx) -> {
      ctx.status(e.getStatus());
    });

    // 404 for unknown routes — without this, Javalin returns 200 OK with an
    // empty text/plain body for unregistered paths, which makes API
    // discovery confusing (tempdoc 374 sandbox round 2 finding #9).
    //
    // observations.md `#247` fix: only overwrite the body when no handler
    // matched. When a handler intentionally returns 404 (resource lookup
    // miss like /api/agent/session/{id} for an unknown id), `matchedPath()`
    // is non-null and the handler-set JSON body is the legitimate response.
    // Without this guard the mapper unconditionally rewrites every 404 to
    // "No handler registered for …", swallowing typed errorCode metadata
    // and producing a misleading message. (OperationsController already
    // worked around this by emitting 422 instead of 404; see OperationsController.java:75-80.)
    //
    // Known limitation (pre-existing, separate from #247): `app.error(404)`
    // never fires for genuinely-unmatched paths in this Javalin 6.7.0
    // configuration — Jetty/Javalin returns 200 with an empty text/plain
    // body before the error mapper runs. The original mapper's docstring
    // implied it upgraded those to 404; that has never been the empirical
    // behavior. Validated in the 2026-05-08 inbox-triage session — fixing
    // it requires a different mechanism (catch-all wildcard route
    // registration at end of setupRoutes, or a global beforeMatched-style
    // hook). Tracked as follow-up; not in scope for #247 closure.
    app.error(404, ctx -> {
      String matched = ctx.matchedPath();
      // Treat null / empty / "*" as "no real route matched" — the OPTIONS
      // wildcard `app.options("/*", ...)` registered for CORS preflight at
      // line ~849 normalizes to matchedPath="*" and shows up here for any
      // unmatched non-OPTIONS request. (Live-confirmed 2026-05-08: GET
      // /api/no-such-endpoint logged matched="*" before this fix.) If we
      // treated "*" as a real match the unmatched-route case would
      // continue to leak through with an empty body.
      if (matched != null && !matched.isEmpty() && !"*".equals(matched)) {
        return;
      }
      String route = ApiErrorHandler.routeOf(ctx);
      ctx.status(404).json(ApiErrorHandler.toResponse(
          ApiErrorCode.NOT_FOUND,
          "No handler registered for " + ctx.method() + " " + ctx.path(),
          telemetry,
          route));
    });

    // Tempdoc 629 (LAYER): a read/write against an AUTHORED store while the data key is locked
    // surfaces as 423 Locked (NOT a 500), so the frontend projects an unlock affordance.
    // Tempdoc 806 W1: the same answer now carries the typed `errorCode`, so a locked response is
    // machine-readable wherever it is raised (this fallback or a controller's own arm) and the two
    // cannot drift into different shapes for one condition.
    app.exception(
        io.justsearch.agent.api.encryption.KeyLockedException.class,
        (e, ctx) ->
            ctx.status(423)
                .json(
                    Map.of(
                        "error",
                        "locked",
                        "locked",
                        true,
                        "errorCode",
                        ApiErrorCode.STORE_LOCKED.name())));

    app.exception(io.justsearch.core.execution.EngineExecutorRejectedException.class,
        (failure, ctx) -> ApiErrorHandler.writeExecutorRefusal(ctx, failure, telemetry));

    // Global fallback: catch any unhandled exception that slips past per-controller try-catch blocks.
    // This ensures all error responses use the standardized ApiErrorHandler shape instead of Javalin's default.
    app.exception(Exception.class, (e, ctx) -> {
      if (ApiErrorHandler.writeExecutorRefusal(ctx, e, telemetry)) return;
      log.error("Unhandled exception on {} {}", ctx.method(), ctx.path(), e);
      // Tempdoc 518 Appendix G Wave A.2: stamp the per-request HTTP span (started by the
      // global before hook) with the exception. The after hook still runs and ends the span;
      // we just record the cause + flip status to ERROR here so the trace is informative.
      Object spanAttr = ctx.attribute("__otel_span__");
      if (spanAttr instanceof io.opentelemetry.api.trace.Span span) {
        span.recordException(e);
        span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR, e.getClass().getSimpleName());
      }
      String route = ApiErrorHandler.routeOf(ctx);
      ApiErrorCode code = ApiErrorHandler.resolve((Exception) e);
      int httpStatus = ApiErrorHandler.httpStatusFor(code);
      ctx.status(httpStatus).json(ApiErrorHandler.toResponse(code, e, telemetry, route));
    });

    // Track inflight requests, capture slow-request timing, and emit an HTTP span per request.
    // Tempdoc 518 Appendix G Wave A.2 + A.3: the same Javalin before/after hooks that track
    // inflight gauges and slow-request timing also start/end an OTel span around the handler,
    // and the response gets an `X-Trace-Id` header (no-op trace ID when tracing is off, so the
    // header shape is stable). Spans named "http.<METHOD>.<route>" carry http.method,
    // http.route, http.status_code attributes plus coarse engine.originator/engine.transport
    // from the cached request context (all on the NdjsonSpanExporter allowlist).
    // The exception handler above sets ERROR status + records the exception on the active span.
    final io.opentelemetry.api.trace.Tracer httpTracer =
        io.opentelemetry.api.GlobalOpenTelemetry.getTracer("io.justsearch.ui.http");
    try {
      app.before(
          ctx -> {
            inflightRequests.incrementAndGet();
            ctx.attribute("__request_start_ns__", System.nanoTime());
            String requestPath = ctx.path();
            io.opentelemetry.api.trace.Span span =
                httpTracer
                    .spanBuilder("http." + ctx.method().name().toLowerCase(Locale.ROOT)
                        + "." + requestPath)
                    .setSpanKind(io.opentelemetry.api.trace.SpanKind.SERVER)
                    .setAttribute("http.method", ctx.method().name())
                    .setAttribute("http.target", requestPath)
                    .startSpan();
            // Tempdoc 518 Wave A defect Fix-2: install the span as the current OTel context
            // on this thread so child spans authored by downstream code
            // (AgentLoopService.spanBuilder("invoke_agent"), KnowledgeHttpApiAdapter
            // .spanBuilder("search"), TransitionRunner's inference.transition span, etc.)
            // are PARENTED to this HTTP span. Without makeCurrent the trace tree is flat.
            // Sync handler model: before → handler → after run on the same thread; the
            // ThreadLocal-backed context propagates correctly. No ctx.future() in any
            // handler body (verified by exploration). The Scope is closed in the matching
            // app.after below; suppressing Error-Prone's MustBeClosedChecker because the
            // lifecycle spans the Javalin before/after pair, not a single block.
            @SuppressWarnings("MustBeClosedChecker")
            io.opentelemetry.context.Scope scope = span.makeCurrent();
            ctx.attribute("__otel_span__", span);
            ctx.attribute("__otel_scope__", scope);
            // Tempdoc 518 Wave A defect Fix-5: skip the X-Trace-Id header when the span
            // context is invalid (HEAD_TRACING_LEVEL=none → no-op tracer returns trace ID
            // 00000000000000000000000000000000). A header carrying all zeros is misleading
            // bug-report context. Real trace IDs only when tracing is active.
            if (span.getSpanContext().isValid()) {
              ctx.header("X-Trace-Id", span.getSpanContext().getTraceId());
            }
          });
      app.after(
          ctx -> {
            inflightRequests.updateAndGet(v -> Math.max(0, v - 1));
            securityFilters.maybeCaptureSlowRequestDump(ctx);
            Object spanAttr = ctx.attribute("__otel_span__");
            Object scopeAttr = ctx.attribute("__otel_scope__");
            if (spanAttr instanceof io.opentelemetry.api.trace.Span span) {
              try {
                int status = ctx.statusCode();
                span.setAttribute("http.status_code", status);
                String matched = ctx.matchedPath();
                if (matched != null && !matched.isBlank() && !"*".equals(matched)) {
                  span.setAttribute("http.route", matched);
                }
                recordEngineProvenance(ctx, span);
                if (status >= 500) {
                  span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
                }
              } finally {
                // Close the Scope BEFORE ending the span — OTel API ordering. Failure to
                // close leaks the ThreadLocal context.
                if (scopeAttr instanceof io.opentelemetry.context.Scope scope) {
                  scope.close();
                }
                span.end();
              }
            }
          });
    } catch (Exception ignored) {
      // best-effort
    }

    securityFilters.install(this.app);
    RouteLifecycleHeaders.install(this.app);
    setupRoutes();
    RouteContractPolicy.validateLifecycleRoutes(
        RouteManifestController.handlerMethodPaths(this.app), RouteContractPolicy.CONTRACTS);

    this.app.start("127.0.0.1", bindPort);
  }

  /**
   * Adds the request's coarse, low-cardinality engine attribution to its completion span.
   *
   * <p>The request context is resolved by the owning route/filter and cached on the Javalin
   * request. Completion must consume that immutable value: resolving headers again here could
   * produce a different attribution after a handler or middleware has changed the request.
   */
  static void recordEngineProvenance(Context request, io.opentelemetry.api.trace.Span span) {
    Object value = request.attribute(RequestEngineContext.ATTRIBUTE);
    if (!(value instanceof EngineContext context)) {
      return;
    }
    span.setAttribute("engine.originator", EngineProvenance.originator(context));
    span.setAttribute("engine.transport", context.transport());
  }

  /** H4: Returns a cached GPU capabilities snapshot (5s TTL) to avoid excessive NVML probes. */
  private GpuCapabilities getCachedGpuSnapshot() {
    long now = System.currentTimeMillis();
    GpuCapabilities cached = cachedGpuSnapshot;
    if (cached == null || now - gpuSnapshotTimestamp > GPU_CACHE_TTL_MS) {
      cached = gpuCapabilitiesService.snapshot();
      cachedGpuSnapshot = cached;
      gpuSnapshotTimestamp = now;
    }
    return cached;
  }

  private void setupRoutes() {
    // Tempdoc 583 follow-up: controllers are read from the assembly Result records. One local
    // alias for inferenceHandlers (9 method refs below) avoids repeated accessor calls.
    var inferenceHandlers = core.inferenceHandlers();
    StatusRoutes.register(
        app,
        this.telemetry,
        core.statusLifecycleHandler()::handleStatus,
        core.statusLifecycleHandler()::handleHealth,
        uiReadyController,
        settingsController,
        core.policyController(),
        core.diagnosticsController());
    IndexingRoutes.register(app, indexingController);
    DebugRoutes.register(app, core.debugStateController(), core.effectiveConfigController(), core.chunkInfoController(), this::handleDebugDashboard, core.logLevelController(), core.timeSeriesController(), core.sessionPoliciesController(), this::handleResetIndex, this::handleAdminRuntimeReload, this::handleAdminInferenceReload);
    AiRoutes.register(app, core.previewController(), core.aiInstallController(), core.aiPackController(), core.aiRuntimeController(), core.aiModelsController(), convApi.chatController());
    InferenceRoutes.register(
        app,
        inferenceHandlers::handleInferenceStatus,
        inferenceHandlers::handleGpuCapabilities,
        inferenceHandlers::handleSetInferenceMode,
        inferenceHandlers::handleReloadInferenceConfig,
        inferenceHandlers::handleDetachExternalInferenceServer,
        inferenceHandlers::handleRestartWorker,
        core.encoderRuntimeController()::handle,
        inferenceHandlers::handleInferenceFailures,
        inferenceHandlers::handleInferenceTransitions);
    KnowledgeRoutes.register(app, knowledgeSearchController, log);
    AgentRoutes.register(
        app,
        convApi.agentController(),
        convApi.agentSessionController(),
        convApi.agentToolsController());
    // Tempdoc 834 §1.6 — the run-stream family (POST managed SSE, already token-covered).
    RunRoutes.register(app, convApi.runStreamController(), convApi.agentSessionController());
    // Tempdoc 583 §D.2a/§D.3: the /api/meta/* self-description family (route manifest + OpenAPI) is
    // bound by MetaApiModule via the apiModules loop below — not inline here (dogfoods the seam).
    // Tempdoc 526 §4.2 — typed DocumentAddress → canonical-coordinate translator.
    if (convApi.resolveAddressController() != null) {
      app.post("/api/document/{id}/resolve-address", convApi.resolveAddressController()::handle);
    }
    app.get(MCP_TOKEN_PATH, this::handleMcpToken);
    if (convApi.mcpProtocolHandler() != null) {
      app.post(MCP_ENDPOINT_PATH, convApi.mcpProtocolHandler()::handlePost);
      app.delete(MCP_ENDPOINT_PATH, convApi.mcpProtocolHandler()::handleDelete);
      // Streamable-HTTP conformance: the single endpoint must answer GET. Registered inside this
      // branch so the 405's `Allow` can never name POST/DELETE while they are unbound (its third
      // entry, OPTIONS, comes from the API-wide CORS preflight catch-all, not from here).
      app.get(MCP_ENDPOINT_PATH, io.justsearch.ui.api.mcp.McpProtocolHandler::handleGet);
    }

    // Tempdoc 374 alpha.17 R5: OpenAI-compatible surface. Proxies to the
    // running llama-server. POST is subject to the same session-token
    // enforcement as the rest of the non-GET API surface in prod mode.
    app.post("/v1/chat/completions", core.openAiCompatController()::handleChatCompletions);
    app.get("/v1/models", core.openAiCompatController()::handleModels);

    // Tempdoc 431 / 429 §E.17 / 430 / 518 / 565 §27.4: the nine i18n message catalogs
    // (errors + registry primitives + health-events + inference-failures + workflow). Tempdoc
    // 583 Stage 1: registered from the declarative MessageCatalogRoutes table — each serves
    // GET /api/messages/<namespace>/{locale} from /messages/<namespace>.en.properties.
    io.justsearch.ui.api.routes.MessageCatalogRoutes.register(app, this.telemetry);
    // Slice 3a.1.9 §A.6a: classpath schemas served same-origin.
    app.get("/api/schemas/{name}", schemaController::handle);

    // Tempdoc 583 Stage 3: every substrate-Resource cohort route (registry / operations /
    // infra-capabilities / runtime-manifest / metric / advisory / history / authorization /
    // hard-stop / memory / SSE streams) is bound by ResourceApiModule when a bootstrap is present.
    for (ApiModule module : this.apiModules) {
      module.register(app);
    }
    // Slice 477 H2.3 — plugin verification (stub for V1.5.1 alpha;
    // V1.5.2 replaces with sigstore-java chain verification).
    app.post("/api/plugins/verify", pluginVerificationController::handleVerify);
    // Tempdoc 560 §28 — operator-approval allowlist (the production-real trust ceremony for a
    // URL-loaded third-party plugin; loopback-only ⇒ inherently operator-local).
    app.post("/api/plugins/allowlist", pluginVerificationController::handleApprove);
    app.get("/api/plugins/allowlist", pluginVerificationController::handleList);

    // Tempdoc 629 (LAYER): at-rest encryption controls for the AUTHORED stores.
    app.get("/api/conversations/encryption", conversationEncryptionController::handleStatus);
    app.post("/api/conversations/encryption/setup", conversationEncryptionController::handleSetup);
    app.post("/api/conversations/encryption/unlock", conversationEncryptionController::handleUnlock);
    app.post("/api/conversations/encryption/lock", conversationEncryptionController::handleLock);
    app.post(
        "/api/conversations/encryption/regenerate-recovery",
        conversationEncryptionController::handleRegenerateRecovery);
    if (conversationBackupController != null) {
      // Tempdoc 629 (Fix 1): POST, NOT GET — the export streams the encrypted vault (keystore + sealed
      // bundle), and ApiSecurityFilters only enforces the session token on POST/PUT/DELETE (GET is always
      // allowed). A GET here would expose the offline-brute-forceable container to any loopback caller.
      app.post(
          "/api/conversations/encryption/export", conversationBackupController::handleExport);
      // 629 (#E): import the read side — token-gated POST, decrypt + write-back via the store list.
      app.post(
          "/api/conversations/encryption/import", conversationBackupController::handleImport);
    }
    app.post("/api/conversations/encryption/recover", conversationEncryptionController::handleRecover);
    app.post(
        "/api/conversations/encryption/change-passphrase",
        conversationEncryptionController::handleChangePassphrase);
    app.delete("/api/plugins/allowlist/{sha}", pluginVerificationController::handleRevoke);

    // Tempdoc 541 §4.2 — composition-substrate observability surface. GET /api/boot/phases
    // returns the immutable BootTrace snapshot. SSE intentionally omitted (HTTP comes up
    // after HeadAssembly construction completes, so SSE subscribers can never observe boot
    // in-progress).
    io.justsearch.ui.api.routes.BootRoutes.register(app, HeadAssemblyRef);
  }

  /**
   * Tempdoc 374 alpha.17 R5: resolve the running llama-server port from
   * ConfigStore live, so an explicit operator override (env var or sysprop) is
   * picked up without restart. Defaulting mirrors {@code InferenceConfig.from}.
   */
  private int resolveLlamaServerPort() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs == null) return 0;
    var rc = cs.get();
    int port = rc.ports().serverPort();
    if (port > 0) return port;
    int apiPort = rc.ports().apiPort();
    return apiPort > 0 && apiPort != 8081 ? 8081 : 8082;
  }

  private void handleMcpToken(Context ctx) {
    ctx.json(Map.of("token", sessionToken != null ? sessionToken : ""));
  }

  private void handleDebugDashboard(Context ctx) {
    ctx.contentType("text/html");
    try (var is = getClass().getClassLoader().getResourceAsStream("debug/dashboard.html")) {
      if (is == null) {
        ctx.status(404).result("Debug dashboard not found");
        return;
      }
      ctx.result(new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (java.io.IOException e) {
      log.warn("Failed to load debug dashboard", e);
      ctx.status(500).result("Failed to load dashboard");
    }
  }

  private void handleResetIndex(Context ctx) {
    if (!Boolean.getBoolean("justsearch.eval.mode")) {
      ctx.status(404).json(Map.of("error", "Not available outside eval mode"));
      return;
    }
    if (HeadAssemblyRef == null) {
      ctx.status(503).json(Map.of("error", "Service unavailable"));
      return;
    }
    try {
      boolean success = HeadAssemblyRef.workers().indexing().resetIndex(RequestEngineContext.get(ctx));
      if (!success) {
        ctx.status(500).json(Map.of("error", "Worker reset failed"));
        return;
      }
      HeadAssemblyRef.workers().indexing().clearAllRoots(RequestEngineContext.get(ctx));
      log.info("Index reset completed via /api/debug/reset-index");
      ctx.status(200).json(Map.of("reset", true));
    } catch (Exception e) {
      log.error("Index reset failed", e);
      ctx.status(500).json(Map.of("error", e.getMessage()));
    }
  }

  /**
   * Tempdoc 406 — POST /api/admin/runtime/reload. Triggers a holder swap on the
   * Worker's ingest runtime via gRPC. Optional JSON body: {@code {"reason":"<tag>"}}.
   * Returns {@code {"swapDurationMs": N}}. Operator-only; no per-route auth — the
   * endpoint inherits loopback-only safety from the Javalin bind to {@code 127.0.0.1}
   * at line 582 ({@code app.start("127.0.0.1", bindPort)}). See CLAUDE.md hard rule
   * "Loopback-only network". A future change that flips the bind to {@code 0.0.0.0}
   * would silently expose this admin endpoint to the network.
   */
  private void handleAdminRuntimeReload(Context ctx) {
    try {
      String reason = "admin_triggered";
      try {
        Map<String, Object> body = ctx.bodyAsClass(Map.class);
        Object r = body == null ? null : body.get("reason");
        if (r instanceof String s && !s.isBlank()) {
          reason = s;
        }
      } catch (Exception ignored) {
        // Body is optional; default reason applies.
      }
      if (HeadAssemblyRef == null) {
        ctx.status(503).json(Map.of("error", "Service unavailable"));
        return;
      }
      log.info("admin runtime reload triggered (reason={})", reason);
      long swapDurationMs = HeadAssemblyRef.workers().indexing().reloadRuntime(reason, RequestEngineContext.get(ctx));
      log.info("admin runtime reload complete in {}ms", swapDurationMs);
      ctx.status(200).json(Map.of("swapDurationMs", swapDurationMs));
    } catch (Exception e) {
      log.error("admin runtime reload failed", e);
      ctx.status(500).json(Map.of("error", e.getMessage()));
    }
  }

  /**
   * Tempdoc 412 Phase 5 — POST /api/admin/inference/reload. Triggers a config-driven restart
   * of the inference runtime via {@link io.justsearch.app.api.OnlineAiRuntimeControl#reloadRuntime}.
   * Optional JSON body: {@code {"reason":"<tag>"}}. Returns
   * {@code {"transitionDurationMs": N, "phase": "<X>", "generationId": Z, "reason": "<tag>"}}.
   *
   * <p>Operator-only. Inherits loopback-only safety from the Javalin bind to
   * {@code 127.0.0.1} (see {@link #handleAdminRuntimeReload}'s analogous comment for the
   * 0.0.0.0 caveat).
   *
   * <p>Returns 503 when the inference runtime is unavailable (AI disabled or not yet
   * constructed); 500 on unexpected errors.
   */
  private void handleAdminInferenceReload(Context ctx) {
    AdminInferenceReloadHandlers.handleAdminInferenceReload(
        ctx,
        HeadAssemblyRef != null ? HeadAssemblyRef.inference().onlineAi() : OnlineAiService.unavailable(),
        () -> HeadAssemblyRef != null ? HeadAssemblyRef.inferenceSnapshot() : null,
        log);
  }

  public int getPort() {
    return port;
  }

  /**
   * Late-binds the Knowledge Server after async Worker startup completes.
   *
   * <p>Updates controllers that need the Worker reference and registers the
   * knowledge search routes that were skipped during initial construction.
   *
   * @param ks the started KnowledgeServerBootstrap (non-null)
   * @param startError the start error string, or null if startup succeeded
   */
  /**
   * Tempdoc 825: binds the ONE worker-recovery authority (the health monitor) into the API surface,
   * so {@code POST /api/worker/restart} shares the automatic loop's budget and vetoes instead of
   * 503-ing in exactly the state it exists for. Independent of
   * {@link #lateBindKnowledgeServer}: the authority exists whether or not a worker is bound.
   */
  public void bindWorkerRecovery(
      io.justsearch.app.services.worker.WorkerRecoveryAuthority workerRecovery) {
    core.inferenceHandlers().setWorkerRecovery(workerRecovery);
  }

  /**
   * Tempdoc 885 item 6: the delay the Worker-status sampler wants before its next observation —
   * 2 s while indexing/backfill/AI activation is in flight, 10 s otherwise. Read by
   * {@code KnowledgeServerHealthMonitor} to set its next tick, so the sampler rides the monitor's
   * schedule instead of adding an executor.
   */
  public long statusSamplingPeriodMs() {
    return core.statusLifecycleHandler().samplingPeriodMs();
  }

  public void lateBindKnowledgeServer(KnowledgeServerBootstrap ks, String startError) {
    // The upgrade routes deliberately do NOT cache `ks` here: HeadAssembly.currentKnowledgeServer()
    // is the single owner of that reference (HeadlessApp calls connectKnowledgeServer immediately
    // before this method), and a second copy on the composition root would be a fork that can go
    // stale on Worker reconnect.
    // Update controllers with Worker reference (read from the CoreApiAssembly Result).
    core.debugStateController().setKnowledgeServer(ks);
    core.inferenceHandlers().setKnowledgeServer(ks);
    core.statusLifecycleHandler().setKnowledgeServer(ks, startError);
    // Tempdoc 400 Phase 2.1 (LR1-c): wire the KnowledgeClient late so
    // /api/debug/session-policies returns the authoritative PolicySnapshot in
    // eval mode. Pre-fix this controller stayed wired with null forever.
    core.sessionPoliciesController().setClient(ks != null ? ks.client() : null);
    // Tempdoc 422: late-bind for /api/inference/encoders so the explainer reads the
    // authoritative PolicySnapshot + OrtCuda probes once Worker boot completes.
    core.encoderRuntimeController().setClient(ks != null ? ks.client() : null);
    // Tempdoc 374 alpha.17 R3: late-bind for /api/ai/install. Pre-alpha.17 the
    // AiInstallController was constructed with the builder's null knowledgeServer
    // and never updated. tryRestartWorkerBestEffort silently no-op'd, leaving the
    // post-Install-AI worker on its boot-time ORT init (no native_path) until the
    // user manually relaunched. Now the worker reference reaches AiInstallService
    // before the user can click "Install AI" (UI doesn't render the dialog until
    // /api/health reports the worker online).
    core.aiInstallService().setKnowledgeServer(ks);

    // Create and register KnowledgeSearchController if not already present
    if (ks != null && this.knowledgeSearchController == null) {
      KnowledgeSearchController ctrl = new KnowledgeSearchController(
          ks,
          this.perSourceSearch,
          this.telemetry,
          this.HeadAssemblyRef != null ? this.HeadAssemblyRef.inference().onlineAi() : OnlineAiService.unavailable(),
          this.lambdaMartReranker,
          this.apiCatalog);
      this.knowledgeSearchController = ctrl;
      // Tempdoc 778 — seal the disposition + feature-snapshot streams with the AUTHORED feedback key.
      if (this.HeadAssemblyRef != null) {
        ctrl.setFeedbackCipher(
            this.HeadAssemblyRef.storeCipher(
                io.justsearch.agent.api.encryption.StoreCatalog.FEEDBACK.recoverability()));
        ctrl.setFeedbackCaptureSettings(this.HeadAssemblyRef.feedbackCaptureSettings());
      }
      // Tempdoc 419 / T4: bind the shared scan-progress registry to the adapter that drives
      // /api/knowledge/ingest. The adapter records events into the registry as the worker
      // emits them; the SSE controller (registered below) subscribes by scanId.
      ctrl.getAdapter().setScanProgressRegistry(this.scanProgressRegistry);
      // Tempdoc 812 D2: bind the substrate-owned scan-rollup aggregator to the same adapter. It
      // listens on the one action-event log for the terminal per-document outcomes (so its counts
      // are the REAL DONE/FAILED states, not the enqueue-time admitted count) and publishes one
      // `operation`-kind scan-completion row back into that log. The adapter is the one Head-side
      // place that knows a scan's id, root, collection and admitted count together.
      if (this.HeadAssemblyRef != null) {
        ctrl.getAdapter()
            .setScanRollupLedger(this.HeadAssemblyRef.substrate().conversation().scanRollupLedger());
      }
      this.scanProgressController =
          new ScanProgressController(this.scanProgressRegistry, this.apiCatalog);
      io.justsearch.ui.api.routes.ScansRoutes.register(this.app, this.scanProgressController);
      RetrieveContextController ragCtrl = this.HeadAssemblyRef != null
          ? new RetrieveContextController(ks,
              this.HeadAssemblyRef.workers().documents(),
              this.HeadAssemblyRef.inference().onlineAi(),
              () -> ctrl.getAdapter().getCachedFacetSnapshot())
          : null;
      KnowledgeRoutes.register(this.app, ctrl, ragCtrl, log);
    }
  }

  /**
   * Returns the session token used for non-GET request authentication.
   * This is exposed so the desktop host (Tauri) can deliver it to the UI.
   * Returns null if token enforcement is not enabled.
   */
  public String getSessionToken() {
    return sessionToken;
  }

  /**
   * Generates a cryptographically secure session token (32 bytes, base64url encoded).
   * This should be called once at startup and passed to the LocalApiServer constructor.
   */
  public static String generateSessionToken() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * Get the event buffer for logging events from other components.
   */
  public EventBuffer getEventBuffer() {
    return eventBuffer;
  }

  public void stop() {
    slowRequestOwner.close();
    // Tempdoc 419 / T4: stop the periodic prune thread on shutdown.
    try {
      scanProgressRegistry.close();
    } catch (RuntimeException e) {
      log.warn("ScanProgressRegistry.close failed: {}", e.getMessage());
    }
    // Tempdoc 419 C3 V2 P3: stop the GPU saturation sampler thread.
    try {
      core.gpuSaturationSampler().stop();
    } catch (RuntimeException e) {
      log.warn("GpuSaturationSampler.stop failed: {}", e.getMessage());
    }
    // Tempdoc 672 follow-up: stop the VDU offline-processing auto-trigger sampler thread.
    try {
      core.vduOfflineTriggerSampler().stop();
    } catch (RuntimeException e) {
      log.warn("VduOfflineTriggerSampler.stop failed: {}", e.getMessage());
    }
    // Tempdoc 583 Stage 3 / §D.2a: stop every ApiModule cohort's background work symmetrically.
    for (ApiModule module : this.apiModules) {
      module.shutdown();
    }
    // Tempdoc 638 PE: stop AgentController's heartbeat scheduler (registered inline, not an ApiModule).
    if (this.convApi != null && this.convApi.agentController() != null) {
      try {
        this.convApi.agentController().shutdown();
      } catch (RuntimeException e) {
        log.warn("AgentController.shutdown failed: {}", e.getMessage());
      }
    }
    // Tempdoc 859 D live-defect D2: ChatController now carries the same heartbeat scheduler, so it
    // gets the same symmetric stop — an added start without an added stop is the 638 PE leak again.
    if (this.convApi != null && this.convApi.chatController() != null) {
      try {
        this.convApi.chatController().shutdown();
      } catch (RuntimeException e) {
        log.warn("ChatController.shutdown failed: {}", e.getMessage());
      }
    }
    // Tempdoc 834 §1.6: stop the run-stream heartbeat scheduler and retire every open run — the
    // same asymmetric-lifecycle trap the line above closes for AgentController.
    if (this.convApi != null && this.convApi.runStreamController() != null) {
      try {
        this.convApi.runStreamController().shutdown();
      } catch (RuntimeException e) {
        log.warn("RunStreamController.shutdown failed: {}", e.getMessage());
      }
    }
    // Tempdoc 430 Phase 8 (rev 3.11 §B.X.6): stop the rule-engine runner symmetrically.
    if (this.HeadAssemblyRef != null) {
      var rr = this.HeadAssemblyRef.substrate().health().ruleRunner();
      if (rr != null) {
        try {
          rr.stop();
        } catch (RuntimeException e) {
          log.warn("RuleRunner.stop failed: {}", e.getMessage());
        }
      }
      // Slice 440 §B.B: unregister the ConfigStore listener so the bridge doesn't leak
      // across LocalApiServer recreation in the same JVM.
      var bridge = this.HeadAssemblyRef.substrate().context().configBridge();
      if (bridge != null) {
        try {
          bridge.stop();
        } catch (RuntimeException e) {
          log.warn("RuntimeContextConfigBridge.stop failed: {}", e.getMessage());
        }
      }
    }
    try { app.stop(); } finally {
      try { core.openAiCompatController().close(); } finally { core.aiRuntimeController().close(); }
    }
  }

  private static boolean isBindFailure(Throwable t) {
    while (t != null) {
      if (t instanceof java.net.BindException) return true;
      t = t.getCause();
    }
    return false;
  }

  private static Integer resolveConfiguredPort() {
    ConfigStore cs = ConfigStore.globalOrNull();
    if (cs == null) return null;
    int port = cs.get().ports().apiPort();
    return port > 0 ? port : null;
  }

  /** Builder for {@link LocalApiServer}; a Knowledge Server requires an explicit search owner. */
  public static final class Builder {
    final io.justsearch.core.execution.EngineExecutorRegistry executors;
    final io.justsearch.app.services.settings.UiSettingsStore settingsStore;
    // Tempdoc 583 Stage 2: package-private so ConversationApiAssembly (same package) can read
    // the inputs it needs for the extracted ConversationEngine/agent/chat/MCP wiring.
    final Path indexBasePath;
    KnowledgeServerBootstrap knowledgeServer;
    tools.jackson.databind.JsonNode configRoot;
    String knowledgeServerStartError;
    private Telemetry telemetry;
    private String sessionToken;
    private Path userHome;
    io.justsearch.app.services.worker.WorkerFeatureCache workerFeatureCache;
    GplStatusProvider gplJobCoordinator;
    RerankerService lambdaMartReranker;
    Supplier<GplEvalData> gplEvalSnapshotSupplier;
    // Tempdoc 429 Phase 5+7 closure (§F.6 + §F.9): the HeadAssembly exposes
    // OperationCatalog/ResourceCatalog/PromptCatalog + CapabilitiesChangeRegistry
    // getters that LocalApiServer reads to wire RegistryController + SSE.
    // Tempdoc 583 Stage 2: package-private (ConversationApiAssembly reads it).
    io.justsearch.app.services.HeadAssembly HeadAssembly;
    io.justsearch.app.services.lifecycle.InferenceCapability inferenceCapability;
    // Tempdoc 519 §5 endpoint: per-service overrides for test paths that don't have a full
    // HeadAssembly. Production passes the bootstrap; tests override individual services
    // here. Each is read with fallback to the bootstrap's typed-record accessor in build().
    // Tempdoc 583 Stage 2: package-private (ConversationApiAssembly reads these overrides).
    DocumentService documentService;
    OnlineAiService onlineAiService;
    io.justsearch.agent.api.AgentService agentService;
    Supplier<io.justsearch.app.api.status.InferenceRuntimeView> inferenceSnapshotSupplier;
    // Tempdoc 501 Phase 2: runtime-manifest publisher (HeadlessApp owns instance; passed
    // through so LocalApiServer can wire the REST + SSE transports).
    // Tempdoc 583 Stage 2: package-private (ConversationApiAssembly reads it for the MCP surface).
    io.justsearch.ui.runtime.RuntimeManifestPublisher runtimeManifestPublisher;
    UpgradeShutdownAction upgradeShutdownAction = (preparationId, receiptNonce) -> {};
    /** Tempdoc 805 G.1: the normal-quit ordered shutdown (POST /api/lifecycle/shutdown). */
    Runnable lifecycleShutdownAction = () -> {};
    io.justsearch.app.api.OperationLeaseService operationLeaseService;
    io.justsearch.app.api.EngineAdmissionService engineAdmission;
    io.justsearch.app.services.worker.SearchPerSourceExecutor perSourceSearch;

    /** Explicit dependency for fixtures without a HeadAssembly; the supplying owner closes it. */
    public Builder perSourceSearch(io.justsearch.app.services.worker.SearchPerSourceExecutor value) {
      this.perSourceSearch = java.util.Objects.requireNonNull(value);
      return this;
    }
    Path upgradeDataDir;
    Supplier<String> upgradeRunningVersion =
        () -> EnvRegistry.APP_VERSION.get().orElse("");
    java.util.function.BooleanSupplier upgradeHeadReady;
    java.util.function.BooleanSupplier upgradeWorkerReady;

    Builder(io.justsearch.core.execution.EngineExecutorRegistry executors,
        io.justsearch.app.services.settings.UiSettingsStore settingsStore, Path indexBasePath) {
      this.executors = java.util.Objects.requireNonNull(executors, "executors");
      this.settingsStore = settingsStore;
      this.indexBasePath = indexBasePath;
    }

    /**
     * Tempdoc 429 §F.6 + §F.9 closure: pass the bootstrap so LocalApiServer can wire
     * RegistryController + CapabilitiesStreamController against the live substrate
     * components.
     */
    public Builder HeadAssembly(io.justsearch.app.services.HeadAssembly bootstrap) {
      this.HeadAssembly = bootstrap;
      return this;
    }

    /** Tempdoc 519 §5 endpoint: per-service override for tests + custom wiring. */
    public Builder documentService(DocumentService documentService) {
      this.documentService = documentService;
      return this;
    }

    public Builder onlineAiService(OnlineAiService onlineAiService) {
      this.onlineAiService = onlineAiService;
      return this;
    }

    public Builder agentService(io.justsearch.agent.api.AgentService agentService) {
      this.agentService = agentService;
      return this;
    }

    public Builder inferenceSnapshotSupplier(
        Supplier<io.justsearch.app.api.status.InferenceRuntimeView> supplier) {
      this.inferenceSnapshotSupplier = supplier;
      return this;
    }

    /**
     * Tempdoc 501 Phase 2: hand the manifest publisher to LocalApiServer so it can wire
     * the REST + SSE transports for {@code /api/runtime/manifest[/stream]}.
     */
    public Builder runtimeManifestPublisher(
        io.justsearch.ui.runtime.RuntimeManifestPublisher publisher) {
      this.runtimeManifestPublisher = publisher;
      return this;
    }

    public Builder upgradeShutdownAction(Runnable action) {
      this.upgradeShutdownAction =
          action == null
              ? (preparationId, receiptNonce) -> {}
              : (preparationId, receiptNonce) -> action.run();
      return this;
    }

    public Builder upgradeShutdownAction(UpgradeShutdownAction action) {
      this.upgradeShutdownAction =
          action == null ? (preparationId, receiptNonce) -> {} : action;
      return this;
    }

    /** Tempdoc 805 G.1: the action POST /api/lifecycle/shutdown runs (Head's ordered close). */
    public Builder lifecycleShutdownAction(Runnable action) {
      this.lifecycleShutdownAction = action == null ? () -> {} : action;
      return this;
    }

    public Builder upgradeReconciliation(
        Path dataDir,
        Supplier<String> runningVersion,
        java.util.function.BooleanSupplier headReady,
        java.util.function.BooleanSupplier workerReady) {
      this.upgradeDataDir = dataDir;
      this.upgradeRunningVersion =
          runningVersion == null ? () -> EnvRegistry.APP_VERSION.get().orElse("") : runningVersion;
      this.upgradeHeadReady = headReady;
      this.upgradeWorkerReady = workerReady;
      return this;
    }

    /**
     * Test seam for the op-lease SPI. Production does not call this: the constructor resolves the
     * service from {@code HeadAssembly.serviceOut()} (see the tempdoc 542 Phase 3 fallback chain
     * above), so only tests that build a server without a HeadAssembly inject one directly.
     */
    @SuppressWarnings("unused") // UpgradeLifecycleContractTest; see UnreferencedCodeTest.KNOWN_UNREFERENCED
    Builder operationLeaseService(io.justsearch.app.api.OperationLeaseService service) {
      this.operationLeaseService = service;
      return this;
    }

    public Builder engineAdmission(io.justsearch.app.api.EngineAdmissionService service) {
      this.engineAdmission = java.util.Objects.requireNonNull(service);
      return this;
    }

    public Builder inferenceCapability(io.justsearch.app.services.lifecycle.InferenceCapability cap) {
      this.inferenceCapability = cap;
      return this;
    }

    public Builder knowledgeServer(KnowledgeServerBootstrap knowledgeServer) {
      this.knowledgeServer = knowledgeServer;
      return this;
    }

    public Builder configRoot(tools.jackson.databind.JsonNode configRoot) {
      this.configRoot = configRoot;
      return this;
    }

    public Builder knowledgeServerStartError(String knowledgeServerStartError) {
      this.knowledgeServerStartError = knowledgeServerStartError;
      return this;
    }

    public Builder telemetry(Telemetry telemetry) {
      this.telemetry = telemetry;
      return this;
    }

    public Builder sessionToken(String sessionToken) {
      this.sessionToken = sessionToken;
      return this;
    }

    public Builder userHome(Path userHome) {
      this.userHome = userHome;
      return this;
    }

    public Builder workerFeatureCache(io.justsearch.app.services.worker.WorkerFeatureCache workerFeatureCache) {
      this.workerFeatureCache = workerFeatureCache;
      return this;
    }

    public Builder gplJobCoordinator(GplStatusProvider gplJobCoordinator) {
      this.gplJobCoordinator = gplJobCoordinator;
      return this;
    }

    public Builder lambdaMartReranker(RerankerService lambdaMartReranker) {
      this.lambdaMartReranker = lambdaMartReranker;
      return this;
    }

    public Builder gplEvalSnapshotSupplier(Supplier<GplEvalData> gplEvalSnapshotSupplier) {
      this.gplEvalSnapshotSupplier = gplEvalSnapshotSupplier;
      return this;
    }

    public LocalApiServer build() {
      if (knowledgeServer != null && perSourceSearch == null
          && (HeadAssembly == null || HeadAssembly.perSourceSearch() == null)) {
        throw new IllegalStateException("Knowledge Server requires the Head per-source search owner"
            + " or an explicitly supplied perSourceSearch owner");
      }
      return new LocalApiServer(this);
    }
  }
}
