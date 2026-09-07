/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.bootstrap.phases;

import io.justsearch.app.api.DocumentService;
import io.justsearch.app.services.worker.RagMetricCatalog;
import io.justsearch.app.services.worker.RemoteDocumentService;
import io.justsearch.app.services.worker.KnowledgeClient;
import io.justsearch.telemetry.LocalTelemetry;
import io.justsearch.telemetry.Telemetry;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * §31 supplier-aware: constructs a {@link RemoteDocumentService} backed by a lazy
 * {@code Supplier<KnowledgeClient>}. The supplier resolves at use-time, so Worker
 * late-binding doesn't require reconstructing the service. The service's capability gates +
 * callers handle the case where the supplier returns null (Worker not yet connected).
 */
public final class BootstrapDocumentService {

  private static final Logger log = LoggerFactory.getLogger(BootstrapDocumentService.class);

  private BootstrapDocumentService() {}

  /** Construct a supplier-aware DocumentService backed by the gRPC Worker client. */
  public static DocumentService create(
      Supplier<KnowledgeClient> clientSupplier, Telemetry telemetry) {
    log.info(
        "Using RemoteDocumentService (gRPC, supplier-aware) for document fetching - avoids index"
            + " locking");
    RagMetricCatalog ragCatalog =
        telemetry instanceof LocalTelemetry lt
            ? new RagMetricCatalog(lt.registry())
            : RagMetricCatalog.noop();
    return new RemoteDocumentService(clientSupplier, ragCatalog);
  }
}
