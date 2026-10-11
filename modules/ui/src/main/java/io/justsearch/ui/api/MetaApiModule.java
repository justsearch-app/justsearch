/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Tempdoc 583 §D.2a/§D.3 — the {@code /api/meta/*} self-description cohort as a first-class {@link
 * ApiModule}.
 *
 * <p>This dogfoods the seam: the route manifest (§D.3a) and OpenAPI export (§D.3c) were originally
 * bound inline in {@code LocalApiServer.setupRoutes}, which contradicted §D.2a's own principle (a new
 * endpoint family should be a module in the list, not a LocalApiServer edit). Here they live behind
 * one module, so adding a future meta route touches this file, not the composer.
 *
 * <p>The controllers read the live app + the full module list lazily (suppliers): the manifest needs
 * every module's {@link ApiModule#ownedRoutePaths()} to attribute {@code owningModule}, and the module
 * list is finalized during LocalApiServer construction (this module is itself in it).
 */
final class MetaApiModule implements ApiModule {
  private final RouteManifestController routeManifest;
  private final OpenApiController openApi;

  MetaApiModule(Supplier<Javalin> appSupplier, Supplier<List<ApiModule>> modulesSupplier) {
    this.routeManifest = new RouteManifestController(appSupplier, modulesSupplier);
    this.openApi = new OpenApiController(appSupplier, modulesSupplier);
  }

  @Override
  public void register(Javalin app) {
    app.get("/api/meta/routes", routeManifest::handle, ActivityRole.OBSERVATION);
    app.get("/api/meta/openapi.json", openApi::handle, ActivityRole.USER_ACTION);
  }

  @Override
  public Set<String> ownedRoutePaths() {
    return Set.of("/api/meta/routes", "/api/meta/openapi.json");
  }

  @Override
  public String moduleName() {
    return "MetaApiModule";
  }
}
