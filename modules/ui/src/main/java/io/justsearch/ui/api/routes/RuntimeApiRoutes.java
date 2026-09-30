/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api.routes;

import io.javalin.Javalin;
import io.justsearch.app.api.runtime.Reachability;
import io.justsearch.ui.api.runtime.RuntimeInstancesController;
import io.justsearch.ui.api.runtime.RuntimeManifestController;
import io.justsearch.ui.api.runtime.RuntimeManifestStreamController;
import io.justsearch.ui.api.runtime.RuntimeProbeController;
import io.justsearch.ui.runtime.RuntimeManifestPublisher;
import io.justsearch.ui.runtime.RuntimeTransportRegistry;

/**
 * Tempdoc 501 Phase 37 (F7): runtime axis route registration extracted
 * out of {@link io.justsearch.ui.api.LocalApiServer}.
 *
 * <p>Previously LocalApiServer carried (a) 4 controller fields, (b) two
 * constructor branches initializing them or nulling them out, (c) the
 * transport-registry population, and (d) the route registration block —
 * ~30 lines of substrate-specific wiring inside a general-purpose API
 * server. This class owns all of it. LocalApiServer collapses to one
 * field of this type and one call.
 *
 * <p>Construction does the wiring (controllers + registry + publisher
 * binding); {@link #register(Javalin)} attaches the route handlers to
 * the Javalin instance.
 */
public final class RuntimeApiRoutes {

  private final RuntimeManifestController manifestController;
  private final RuntimeManifestStreamController streamController;
  private final RuntimeInstancesController instancesController;
  private final RuntimeProbeController probeController;

  public RuntimeApiRoutes(io.justsearch.core.execution.EngineExecutorRegistry executors, RuntimeManifestPublisher publisher) {
    if (publisher == null) {
      throw new IllegalArgumentException("publisher must be non-null");
    }
    this.manifestController = new RuntimeManifestController(publisher);
    this.streamController = new RuntimeManifestStreamController(executors, publisher);
    this.instancesController = new RuntimeInstancesController(publisher);
    this.probeController = new RuntimeProbeController(publisher);

    // Tempdoc 501 Phase 35 (F1): build the registry alongside the
    // controllers, populate it with the runtime axis transports, hand
    // it to the publisher. Single source of truth for reachability.
    var registry = new RuntimeTransportRegistry();
    registry.registerHttp("/api/runtime/manifest", Reachability.AUDIENCE_PUBLIC);
    registry.registerSse("/api/runtime/manifest/stream", Reachability.AUDIENCE_PUBLIC);
    registry.registerWellKnown(
        "/.well-known/justsearch/manifest.json", Reachability.AUDIENCE_PUBLIC);
    registry.registerHttp("/api/runtime/instances", Reachability.AUDIENCE_PUBLIC);
    registry.registerProbe("/api/runtime/ready", Reachability.AUDIENCE_PUBLIC);
    registry.registerProbe("/api/runtime/live", Reachability.AUDIENCE_PUBLIC);
    registry.registerMcpTool(
        "justsearch_runtime_manifest", Reachability.AUDIENCE_PUBLIC);
    // Tempdoc 654: advertise the MCP ingress endpoint itself (POST /mcp) so the manifest —
    // the one discovery object — self-describes the Runtime Contract's MCP surface. The 5-tool
    // set is discovered via MCP's own tools/list once connected.
    registry.registerHttp("/mcp", Reachability.AUDIENCE_PUBLIC);
    registry.registerFilesystem(
        publisher.manifestPath().toString(), Reachability.AUDIENCE_FULL);
    publisher.setTransportRegistry(registry);
  }

  /** Register all runtime-axis routes with the given Javalin instance. */
  public void register(Javalin app) {
    // Tempdoc 501 Phase 2: REST + SSE for /api/runtime/manifest.
    // Tempdoc 501 Phase 14: .well-known/ mirror (RFC-8615 surface).
    app.get("/api/runtime/manifest", manifestController::handleGet);
    app.get("/.well-known/justsearch/manifest.json", manifestController::handleGet);
    app.sse("/api/runtime/manifest/stream", streamController::handle);

    // Tempdoc 501 Phase 24 (§13.4.4 time axis): per-instance history reader.
    app.get("/api/runtime/instances", instancesController::handleList);
    app.get("/api/runtime/instances/{id}", instancesController::handleGetOne);

    // Tempdoc 501 Phase 27 (§13.7 Q6): k8s-style readiness/liveness probes.
    // Phase 38 (F11): HEAD support for probe systems that issue HEAD.
    app.get("/api/runtime/ready", probeController::handleReady);
    app.head("/api/runtime/ready", probeController::handleReadyHead);
    app.get("/api/runtime/live", probeController::handleLive);
    app.head("/api/runtime/live", probeController::handleLiveHead);
  }

  /** Stream controller exposed so {@link io.justsearch.ui.api.LocalApiServer#shutdown} can call shutdown(). */
  public RuntimeManifestStreamController streamController() {
    return streamController;
  }
}
