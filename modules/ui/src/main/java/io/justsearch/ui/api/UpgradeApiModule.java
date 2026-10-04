/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import io.javalin.Javalin;
import io.justsearch.app.api.OperationLeaseService;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Upgrade lifecycle control cohort over the existing loopback/session-token transport. */
final class UpgradeApiModule implements ApiModule {
  private static final Set<String> ROUTES =
      Set.of(
          "/api/upgrade/prepare",
          "/api/upgrade/cancel",
          "/api/upgrade/commit-shutdown",
          "/api/upgrade/reconcile");

  private final UpgradeController controller;

  UpgradeApiModule(OperationLeaseService leases, Runnable orderlyShutdown) {
    this(leases, orderlyShutdown, null);
  }

  UpgradeApiModule(
      OperationLeaseService leases,
      Runnable orderlyShutdown,
      Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient) {
    this(
        leases,
        (preparationId, receiptNonce) -> orderlyShutdown.run(),
        workerClient);
  }

  UpgradeApiModule(
      OperationLeaseService leases,
      UpgradeShutdownAction orderlyShutdown,
      Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient) {
    this(leases, orderlyShutdown, workerClient, null, null, null, null);
  }

  UpgradeApiModule(
      OperationLeaseService leases,
      UpgradeShutdownAction orderlyShutdown,
      Supplier<io.justsearch.app.services.worker.KnowledgeClient> workerClient,
      Path dataDir,
      Supplier<String> runningVersion,
      BooleanSupplier headReady,
      BooleanSupplier workerReady) {
    this.controller =
        new UpgradeController(
            leases,
            orderlyShutdown,
            workerClient,
            dataDir,
            runningVersion,
            headReady,
            workerReady);
  }

  @Override
  public void register(Javalin app) {
    app.post("/api/upgrade/prepare", controller::prepare);
    app.post("/api/upgrade/cancel", controller::cancel);
    app.post("/api/upgrade/commit-shutdown", controller::commitShutdown);
    app.post("/api/upgrade/reconcile", controller::reconcile);
  }

  @Override
  public Set<String> ownedRoutePaths() {
    return ROUTES;
  }
}
